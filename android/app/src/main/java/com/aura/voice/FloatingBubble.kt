package com.aura.voice

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.aura.ui.theme.AuraPrimary
import com.aura.ui.theme.AuraSecondary
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FloatingBubble"

/**
 * FloatingBubble — a draggable floating microphone button rendered via
 * WindowManager + Jetpack Compose (SYSTEM_ALERT_WINDOW).
 *
 * Behaviour:
 *  - Tap  → triggers voice input (calls onTap callback)
 *  - Drag → repositions the bubble on screen
 *  - Long press → shows task history (future feature)
 *
 * Requires [Settings.canDrawOverlays] permission. Call [canShow] before [show].
 * The bubble is hidden during task execution (the STOP button is shown instead).
 */
@Singleton
class FloatingBubble @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var windowManager: WindowManager? = null
    private var bubbleView: ComposeView? = null
    private var isShowing = false

    private var onTapCallback: (() -> Unit)? = null

    /** Returns true if the SYSTEM_ALERT_WINDOW permission has been granted. */
    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    /** Show the floating bubble. No-op if already showing or permission missing. */
    fun show(onTap: () -> Unit) {
        if (isShowing) return
        if (!canShow()) {
            Log.w(TAG, "Missing SYSTEM_ALERT_WINDOW permission — cannot show bubble")
            return
        }

        onTapCallback = onTap
        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 200
        }

        val lifecycleOwner = MyLifecycleOwner()
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        bubbleView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)

            setContent {
                BubbleContent(
                    onTap = { onTapCallback?.invoke() }
                )
            }

            // Make the view draggable via WindowManager layout params update
            setOnTouchListener(object : android.view.View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: android.view.View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (initialTouchX - event.rawX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            windowManager?.updateViewLayout(this@apply, params)
                            return true
                        }
                        MotionEvent.ACTION_UP -> {
                            val dx = event.rawX - initialTouchX
                            val dy = event.rawY - initialTouchY
                            if (dx * dx + dy * dy < 100) { // tap threshold
                                performClick()
                            }
                            return true
                        }
                    }
                    return false
                }
            })
        }

        try {
            windowManager?.addView(bubbleView, params)
            isShowing = true
            Log.i(TAG, "Floating bubble shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add bubble view: ${e.message}")
        }
    }

    /** Hide and remove the floating bubble. */
    fun hide() {
        if (!isShowing) return
        try {
            windowManager?.removeView(bubbleView)
        } catch (e: Exception) {
            Log.w(TAG, "Error removing bubble: ${e.message}")
        }
        bubbleView = null
        windowManager = null
        isShowing = false
        Log.i(TAG, "Floating bubble hidden")
    }

    /** Navigate user to overlay permission settings. */
    fun requestPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            android.net.Uri.parse("package:${context.packageName}")
        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }
}

// ──────────────────────────────────────────────────────────────
// Composable bubble UI
// ──────────────────────────────────────────────────────────────

@Composable
private fun BubbleContent(onTap: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "bubble_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bubble_scale"
    )

    Box(
        modifier = Modifier
            .size(56.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(AuraPrimary, AuraSecondary)
                )
            )
            .pointerInput(Unit) {
                detectDragGestures { _, _ -> /* drag handled by OnTouchListener */ }
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Voice input",
            tint = Color.White,
            modifier = Modifier.size(28.dp)
        )
    }
}

// ──────────────────────────────────────────────────────────────
// Minimal LifecycleOwner for WindowManager-hosted ComposeView
// ──────────────────────────────────────────────────────────────

private class MyLifecycleOwner : SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = androidx.lifecycle.LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore = store

    fun performRestore(savedState: android.os.Bundle?) {
        savedStateRegistryController.performRestore(savedState)
    }

    fun handleLifecycleEvent(event: Lifecycle.Event) {
        lifecycleRegistry.handleLifecycleEvent(event)
    }
}
