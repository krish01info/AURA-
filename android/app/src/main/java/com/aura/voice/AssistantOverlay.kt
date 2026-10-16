package com.aura.voice

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.aura.agent.ActionExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AssistantOverlay"

/**
 * AssistantOverlay — the Google-Assistant-style panel that pops up over any app
 * when the user says "Hey AURA".
 *
 * Window type:
 *  1. Accessibility overlay (TYPE_ACCESSIBILITY_OVERLAY) via the connected
 *     AccessibilityService — needs no extra permission.
 *  2. Otherwise TYPE_APPLICATION_OVERLAY if "Display over other apps" is granted.
 *  3. Otherwise nothing is shown ([show] returns false); voice still works.
 *
 * Built with plain Views (no Compose lifecycle needed in a service window).
 * Colours match the app's theme (ui/theme/AURATheme) and follow the phone's light/dark setting.
 * While a task runs the panel becomes a small banner at the top with a Stop button.
 * It lets AURA's own tap/swipe gestures through ([ActionExecutor.gestureListener]),
 * so it never blocks the taps AURA performs in the target app.
 * All methods are safe to call from any thread.
 */
@Singleton
class AssistantOverlay @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val actionExecutor: ActionExecutor
) {
    /** Buttons the panel can show. */
    data class Actions(
        val onAllow: (() -> Unit)? = null,
        val onDeny: (() -> Unit)? = null,
        val onOpenApp: (() -> Unit)? = null,
        val onTalk: (() -> Unit)? = null,
        val onClose: (() -> Unit)? = null,
        val onCancel: (() -> Unit)? = null,
        val onStop: (() -> Unit)? = null,
        val onWrong: (() -> Unit)? = null
    )

    private val main = Handler(Looper.getMainLooper())

    init {
        // AURA's gestures must reach the app, not this window.
        actionExecutor.gestureListener = { through -> main.post { setTouchThrough(through) } }
    }

    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var brandView: TextView? = null
    private var titleView: TextView? = null
    private var bodyView: TextView? = null
    private var buttonRow: LinearLayout? = null
    private var compact = false
    private var palette = Palette.DARK

    /** Panel colours — the same values as AURATheme's light / dark schemes. */
    private data class Palette(
        val card: Int, val stroke: Int, val brand: Int, val title: Int, val body: Int,
        val primary: Int, val onPrimary: Int, val secondary: Int, val onSecondary: Int,
        val stop: Int, val onStop: Int
    ) {
        companion object {
            val DARK = Palette(
                card = Color.parseColor("#F51C1F31"), stroke = Color.parseColor("#2A2E45"),
                brand = Color.parseColor("#9B9DFB"), title = Color.parseColor("#E6E7F2"),
                body = Color.parseColor("#A4A8C3"),
                primary = Color.parseColor("#6366F1"), onPrimary = Color.WHITE,
                secondary = Color.parseColor("#2A2E45"), onSecondary = Color.parseColor("#E6E7F2"),
                stop = Color.parseColor("#3D1A1F"), onStop = Color.parseColor("#F87171")
            )
            val LIGHT = Palette(
                card = Color.parseColor("#FAFFFFFF"), stroke = Color.parseColor("#DCDDEA"),
                brand = Color.parseColor("#5254D4"), title = Color.parseColor("#1A1B2E"),
                body = Color.parseColor("#555973"),
                primary = Color.parseColor("#5254D4"), onPrimary = Color.WHITE,
                secondary = Color.parseColor("#E8E8F2"), onSecondary = Color.parseColor("#1A1B2E"),
                stop = Color.parseColor("#FFE1DE"), onStop = Color.parseColor("#B91C1C")
            )

            fun of(ctx: Context): Palette {
                val night = ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                return if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
            }
        }
    }

    val isShowing: Boolean get() = root != null

    /**
     * Show (or update) the full panel at the bottom of the screen.
     * @return false if no overlay can be drawn on this device right now.
     */
    fun show(title: String, body: String? = null, actions: Actions = Actions()): Boolean {
        if (!canDraw()) return false
        main.post {
            if (!ensureWindow()) return@post
            setCompact(false)
            titleView?.text = title
            bodyView?.text = body.orEmpty()
            bodyView?.visibility = if (body.isNullOrBlank()) View.GONE else View.VISIBLE
            setButtons(actions)
        }
        return true
    }

    /**
     * Small banner at the top — used while steps run or while waiting for an unlock,
     * so it never covers the app's buttons, PIN pad or fingerprint sensor.
     */
    fun showWorking(title: String, body: String? = null, onStop: (() -> Unit)? = null) {
        if (!canDraw()) return
        main.post {
            if (!ensureWindow()) return@post
            setCompact(true)
            titleView?.text = title
            bodyView?.text = body.orEmpty()
            bodyView?.visibility = if (body.isNullOrBlank()) View.GONE else View.VISIBLE
            setButtons(Actions(onStop = onStop))
        }
    }

    /** Remove the panel. */
    fun hide() {
        main.post {
            val view = root ?: return@post
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.w(TAG, "removeView failed: ${e.message}")
            }
            root = null
            windowManager = null
            params = null
            brandView = null
            titleView = null
            bodyView = null
            buttonRow = null
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Window plumbing
    // ──────────────────────────────────────────────────────────────

    private fun canDraw(): Boolean =
        actionExecutor.boundService != null || Settings.canDrawOverlays(appContext)

    private fun ensureWindow(): Boolean {
        if (root != null) return true

        val service = actionExecutor.boundService
        val (ctx, type) = if (service != null) {
            service to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Settings.canDrawOverlays(appContext)) {
            appContext to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            return false
        }

        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
        }

        val panel = buildPanel(ctx)
        return try {
            wm.addView(panel, lp)
            windowManager = wm
            root = panel
            params = lp
            compact = false
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not show assistant panel: ${e.message}")
            false
        }
    }

    private fun setCompact(value: Boolean) {
        val lp = params ?: return
        if (compact == value && root?.isAttachedToWindow == true) return
        compact = value
        lp.gravity = if (value) Gravity.TOP else Gravity.BOTTOM
        // Touchable in both modes (the banner has Stop); gestures pass via setTouchThrough.
        lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        root?.alpha = if (value) 0.92f else 1f
        brandView?.visibility = if (value) View.GONE else View.VISIBLE
        titleView?.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (value) 16f else 20f)
        try {
            windowManager?.updateViewLayout(root, lp)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed: ${e.message}")
        }
    }

    /** While AURA performs a gesture, let touches pass through the panel to the app. */
    private fun setTouchThrough(through: Boolean) {
        val lp = params ?: return
        val view = root ?: return
        val flags = if (through) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (flags == lp.flags) return
        lp.flags = flags
        try {
            windowManager?.updateViewLayout(view, lp)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed: ${e.message}")
        }
    }

    private fun buildPanel(ctx: Context): LinearLayout {
        palette = Palette.of(ctx)
        val pad = dp(ctx, 20)
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 12), dp(ctx, 12), dp(ctx, 12), dp(ctx, 12))
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 28).toFloat()
                setColor(palette.card)
                setStroke(dp(ctx, 1), palette.stroke)
            }
            elevation = dp(ctx, 10).toFloat()
        }

        // Brand row: a small accent dot + "AURA"
        val brand = TextView(ctx).apply {
            text = "AURA"
            setTextColor(palette.brand)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.2f
            gravity = Gravity.CENTER_VERTICAL
            compoundDrawablePadding = dp(ctx, 8)
            setCompoundDrawablesRelative(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                colors = intArrayOf(palette.brand, palette.primary)
                setSize(dp(ctx, 10), dp(ctx, 10))
                setBounds(0, 0, dp(ctx, 10), dp(ctx, 10))
            }, null, null, null)
        }
        val title = TextView(ctx).apply {
            setTextColor(palette.title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(ctx, 6), 0, 0)
        }
        val body = TextView(ctx).apply {
            setTextColor(palette.body)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(ctx, 6), 0, 0)
            maxLines = 6
        }
        val buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(ctx, 12), 0, 0)
        }

        card.addView(brand)
        card.addView(title)
        card.addView(body)
        card.addView(buttons)
        outer.addView(card)

        brandView = brand
        titleView = title
        bodyView = body
        buttonRow = buttons
        return outer
    }

    private fun setButtons(actions: Actions) {
        val row = buttonRow ?: return
        val ctx = row.context
        row.removeAllViews()
        val p = palette
        fun add(label: String, primary: Boolean, onClick: () -> Unit, danger: Boolean = false) {
            row.addView(Button(ctx).apply {
                text = label
                isAllCaps = false
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                stateListAnimator = null   // flat, no default shadow
                setTextColor(when { danger -> p.onStop; primary -> p.onPrimary; else -> p.onSecondary })
                background = GradientDrawable().apply {
                    cornerRadius = dp(ctx, 20).toFloat()
                    setColor(when { danger -> p.stop; primary -> p.primary; else -> p.secondary })
                }
                setPadding(dp(ctx, 20), 0, dp(ctx, 20), 0)
                setOnClickListener { onClick() }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(ctx, 40)
            ).apply { marginStart = dp(ctx, 8) })
        }
        actions.onCancel?.let { add("Cancel", false, it) }
        actions.onStop?.let { add("Stop", false, it, danger = true) }
        actions.onWrong?.let { add("Wrong", false, it) }
        actions.onClose?.let { add("Close", false, it) }
        actions.onTalk?.let { add("Talk", false, it) }
        actions.onDeny?.let { add("Deny", false, it) }
        actions.onOpenApp?.let { add("Open AURA", true, it) }
        actions.onAllow?.let { add("Allow", true, it) }
        row.visibility = if (row.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
}
