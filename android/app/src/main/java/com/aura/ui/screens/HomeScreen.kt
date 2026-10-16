package com.aura.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PictureInPicture
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.agent.RiskLevel
import com.aura.agent.TaskState
import com.aura.ui.theme.AuraCritical
import com.aura.ui.theme.AuraPrimary
import com.aura.ui.theme.AuraPrimaryVariant
import com.aura.ui.theme.AuraSecondary
import com.aura.ui.theme.AuraSuccess
import com.aura.ui.theme.AuraWarning
import com.aura.ui.viewmodel.HomeViewModel
import com.aura.voice.KeywordSpotter

/**
 * HomeScreen — the main AURA interface: status orb, "Hey AURA" switch, command bar,
 * setup notices, the Stop button and the approval sheet (BiometricGate for CRITICAL).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToLog: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSkillStore: () -> Unit = {},
    onNavigateToDashboard: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val taskState by viewModel.taskState.collectAsState()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val isSpeaking by viewModel.isSpeaking.collectAsState()
    val isVoiceEnabled by viewModel.isVoiceEnabled.collectAsState()
    val voiceModelState by viewModel.voiceModelState.collectAsState()
    val voiceModelOfferDismissed by viewModel.voiceModelOfferDismissed.collectAsState()
    var commandText by remember { mutableStateOf("") }

    fun send() {
        if (commandText.isNotBlank()) {
            viewModel.executeCommand(commandText)
            commandText = ""
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "AURA",
                            style = MaterialTheme.typography.titleLarge,
                            letterSpacing = 2.sp
                        )
                    },
                    actions = {
                        val tint = MaterialTheme.colorScheme.onSurfaceVariant
                        IconButton(onClick = onNavigateToDashboard) {
                            Icon(Icons.Rounded.BarChart, "Dashboard", tint = tint)
                        }
                        IconButton(onClick = onNavigateToSkillStore) {
                            Icon(Icons.Rounded.Extension, "Skill Store", tint = tint)
                        }
                        IconButton(onClick = onNavigateToLog) {
                            Icon(Icons.Rounded.History, "Task log", tint = tint)
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Rounded.Settings, "Settings", tint = tint)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Setup notices ────────────────────────────────────
                AnimatedVisibility(visible = !isAccessibilityEnabled) {
                    NoticeCard(
                        icon = Icons.Rounded.AccessibilityNew,
                        tint = AuraWarning,
                        title = "Turn on AURA's Accessibility service",
                        body = "AURA needs it to tap and type in other apps for you.",
                        actionLabel = "Turn on",
                        onAction = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    )
                }

                AnimatedVisibility(
                    visible = isAccessibilityEnabled && !voiceModelOfferDismissed &&
                        voiceModelState !is KeywordSpotter.ModelState.Ready
                ) {
                    VoiceModelCard(
                        state = voiceModelState,
                        onDownload = viewModel::downloadVoiceModel,
                        onDismiss = viewModel::dismissVoiceModelOffer
                    )
                }

                AnimatedVisibility(visible = !viewModel.canShowOverlay) {
                    NoticeCard(
                        icon = Icons.Rounded.PictureInPicture,
                        tint = AuraSecondary,
                        title = "Floating mic",
                        body = "Allow \"Display over other apps\" to get a mic button on any screen.",
                        actionLabel = "Allow",
                        onAction = { viewModel.requestOverlayPermission() }
                    )
                }

                // ── Orb + status ─────────────────────────────────────
                Spacer(Modifier.weight(1f))
                AuraOrb(taskState = taskState, isListening = isListening, isSpeaking = isSpeaking)
                Spacer(Modifier.height(20.dp))
                val (title, detail) = statusLines(taskState, isListening)
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                if (!detail.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
                Spacer(Modifier.weight(1f))

                // ── "Hey AURA" switch ────────────────────────────────
                WakeWordCard(enabled = isVoiceEnabled, onToggle = viewModel::setVoiceEnabled)
                Spacer(Modifier.height(12.dp))

                // ── Command bar ──────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = commandText,
                        onValueChange = { commandText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Type a command…") },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { send() }),
                        trailingIcon = {
                            if (commandText.isNotBlank()) {
                                IconButton(onClick = { send() }) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.Send,
                                        "Send command",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        )
                    )
                    Spacer(Modifier.width(10.dp))
                    FilledIconButton(
                        onClick = { viewModel.startVoiceInput() },
                        modifier = Modifier.size(56.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (isListening) AuraSecondary else MaterialTheme.colorScheme.primary,
                            contentColor = if (isListening) Color.White else MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = if (isListening) Icons.Rounded.GraphicEq else Icons.Rounded.Mic,
                            contentDescription = "Voice input"
                        )
                    }
                }

                // ── Debug-only test buttons (they message "Rahul") ───
                if (com.aura.BuildConfig.DEBUG) {
                    Spacer(Modifier.height(12.dp))
                    Phase1TestPanel(viewModel = viewModel)
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        // ── Stop button while a task runs ───────────────────────────
        val showStop = taskState is TaskState.Executing || taskState is TaskState.Planning ||
            taskState is TaskState.Verifying || taskState is TaskState.Live ||
            taskState is TaskState.WaitingForUnlock
        AnimatedVisibility(
            visible = showStop,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 160.dp)
        ) {
            Button(
                onClick = { viewModel.emergencyStop() },
                colors = ButtonDefaults.buttonColors(containerColor = AuraCritical, contentColor = Color.White),
                shape = CircleShape,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding
            ) {
                Icon(Icons.Rounded.Stop, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Stop")
            }
        }

        // ── Approval sheet (fingerprint for CRITICAL) ───────────────
        if (taskState is TaskState.WaitingForUser) {
            ConfirmationOverlay(
                state = taskState as TaskState.WaitingForUser,
                onAllow = {
                    val activity = context as? FragmentActivity
                    if (activity != null) {
                        viewModel.approveWithBiometric(activity)
                    } else {
                        viewModel.approveAction()
                    }
                },
                onDeny = { viewModel.denyAction() }
            )
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Composables
// ──────────────────────────────────────────────────────────────

/** Tinted card for setup hints: icon, title, one line, optional buttons. */
@Composable
private fun NoticeCard(
    icon: ImageVector,
    tint: Color,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    dismissLabel: String? = null,
    onDismiss: (() -> Unit)? = null,
    extra: @Composable ColumnScope.() -> Unit = {}
) {
    Column {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = tint.copy(alpha = 0.10f)
        ) {
            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(2.dp))
                    Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    extra()
                    if (actionLabel != null || dismissLabel != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (dismissLabel != null && onDismiss != null) {
                                TextButton(onClick = onDismiss) { Text(dismissLabel) }
                            }
                            if (actionLabel != null && onAction != null) {
                                TextButton(onClick = onAction) { Text(actionLabel, color = tint) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

/** First-time setup card: download the offline voice model used by live tasks. */
@Composable
private fun VoiceModelCard(
    state: KeywordSpotter.ModelState,
    onDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val downloading = state as? KeywordSpotter.ModelState.Downloading
    NoticeCard(
        icon = Icons.Rounded.Download,
        tint = AuraPrimary,
        title = "Offline voice for live tasks",
        body = when (state) {
            is KeywordSpotter.ModelState.Downloading -> "Downloading… ${state.percent}%"
            is KeywordSpotter.ModelState.Failed -> "Download failed (${state.reason}). Try again on Wi-Fi."
            else -> "One-time download, about 36 MB (Wi-Fi recommended). Lets AURA act on words like " +
                "\"capture\" or \"next\" inside any app, even offline."
        },
        actionLabel = if (downloading == null) "Download" else null,
        onAction = onDownload,
        dismissLabel = if (downloading == null) "Not now" else null,
        onDismiss = onDismiss
    ) {
        if (downloading != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { downloading.percent / 100f },
                modifier = Modifier.fillMaxWidth().clip(CircleShape),
                color = AuraPrimary,
                trackColor = AuraPrimary.copy(alpha = 0.15f)
            )
        }
    }
}

/** The "Hey AURA" always-listening switch. The whole card toggles it. */
@OptIn(ExperimentalMaterial3Api::class)   // clickable Surface (experimental in older Material 3)
@Composable
private fun WakeWordCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Surface(
        onClick = { onToggle(!enabled) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (enabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            else MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (enabled) Icons.Rounded.RecordVoiceOver else Icons.Rounded.MicOff,
                    contentDescription = null,
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("\"Hey AURA\"", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (enabled) "Listening from any app" else "Off — tap the mic to talk",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = enabled, onCheckedChange = onToggle)
        }
    }
}

/** What the orb shows for a state: colour, icon and how much it breathes. */
private data class OrbLook(val color: Color, val icon: ImageVector, val pulse: Float, val periodMs: Int)

private fun orbLook(state: TaskState, isListening: Boolean, isSpeaking: Boolean): OrbLook = when {
    isListening -> OrbLook(AuraSecondary, Icons.Rounded.Mic, 0.16f, 700)
    isSpeaking -> OrbLook(AuraPrimary, Icons.Rounded.GraphicEq, 0.10f, 900)
    else -> when (state) {
        is TaskState.Planning -> OrbLook(AuraPrimary, Icons.Rounded.AutoAwesome, 0.12f, 900)
        is TaskState.Executing -> OrbLook(AuraPrimary, Icons.Rounded.AutoAwesome, 0.10f, 1100)
        is TaskState.Verifying -> OrbLook(AuraPrimary, Icons.Rounded.Visibility, 0.08f, 1100)
        is TaskState.Live -> OrbLook(AuraSecondary, Icons.Rounded.Hearing, 0.12f, 1000)
        is TaskState.WaitingForUser -> OrbLook(AuraWarning, Icons.Rounded.Shield, 0.06f, 1400)
        is TaskState.WaitingForUnlock -> OrbLook(AuraWarning, Icons.Rounded.Lock, 0.06f, 1400)
        is TaskState.Completed -> OrbLook(AuraSuccess, Icons.Rounded.Check, 0.03f, 2200)
        is TaskState.Failed -> OrbLook(AuraCritical, Icons.Rounded.ErrorOutline, 0.03f, 2200)
        else -> OrbLook(AuraPrimary, Icons.Rounded.AutoAwesome, 0.04f, 2400)
    }
}

@Composable
private fun AuraOrb(taskState: TaskState, isListening: Boolean, isSpeaking: Boolean) {
    val look = orbLook(taskState, isListening, isSpeaking)
    val color by animateColorAsState(look.color, animationSpec = tween(500), label = "orb_color")
    val breath by rememberInfiniteTransition(label = "orb").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(look.periodMs), RepeatMode.Reverse),
        label = "orb_breath"
    )

    Box(modifier = Modifier.size(200.dp), contentAlignment = Alignment.Center) {
        // Soft glow
        Box(
            modifier = Modifier
                .size(200.dp)
                .scale(1f + look.pulse * breath)
                .clip(CircleShape)
                .background(Brush.radialGradient(listOf(color.copy(alpha = 0.32f), Color.Transparent)))
        )
        // Halo ring
        Box(
            modifier = Modifier
                .size(136.dp)
                .scale(1f + look.pulse * 0.5f * breath)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.14f))
        )
        // Core
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(lerp(color, Color.White, 0.18f), lerp(color, AuraPrimaryVariant, 0.35f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(look.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

/** Title + detail line under the orb. */
private fun statusLines(state: TaskState, isListening: Boolean): Pair<String, String?> {
    if (isListening) return "Listening…" to "Go ahead — I'm listening"
    return when (state) {
        is TaskState.Created -> "Ready when you are" to "Say \"Hey AURA\" or type a command below"
        is TaskState.Planning -> "Thinking…" to "Working out the steps"
        is TaskState.Executing -> {
            val step = state.currentStep
            val what = step.action.replace('_', ' ') + (step.text?.let { " \"$it\"" } ?: "")
            "Working on it" to "Step ${state.stepIndex + 1} of ${state.totalSteps} · $what"
        }
        is TaskState.WaitingForUser -> "Needs your OK" to "Check the request below"
        is TaskState.WaitingForUnlock -> "Unlock ${state.appName ?: "the app"}" to "I'll continue after that"
        is TaskState.Live -> "Live" to (state.status ?: "Listening for: ${state.words.joinToString(" · ")}")
        is TaskState.Verifying -> "Checking…" to "Making sure it worked"
        is TaskState.Completed -> "Done" to state.summary.takeUnless { it.trim('!', '.').equals("Done", true) }
        is TaskState.Failed -> "Couldn't finish" to state.reason
        is TaskState.Cancelled -> "Stopped" to null
    }
}

@Composable
private fun ConfirmationOverlay(
    state: TaskState.WaitingForUser,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    val isCritical = state.riskLevel == RiskLevel.CRITICAL
    val riskColor = if (isCritical) AuraCritical else AuraWarning

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
            .pointerInput(Unit) { detectTapGestures { } },   // block taps on the screen behind
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(riskColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isCritical) Icons.Rounded.Fingerprint else Icons.Rounded.Shield,
                        null,
                        tint = riskColor,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = if (isCritical) "Confirm with fingerprint" else "Allow this step?",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.riskAction.message
                        ?: "Allow this action: ${state.riskAction.action.replace('_', ' ')}?",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isCritical) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This can't be undone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = riskColor
                    )
                }
                if (state.anomalyWarning != null) {
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .background(AuraCritical.copy(alpha = 0.10f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(Icons.Rounded.WarningAmber, null, tint = AuraCritical, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = state.anomalyWarning.removePrefix("⚠️").trim(),
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onDeny,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Text("Deny")
                    }
                    Button(
                        onClick = onAllow,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = if (isCritical) ButtonDefaults.buttonColors(containerColor = AuraCritical, contentColor = Color.White)
                            else ButtonDefaults.buttonColors()
                    ) {
                        if (isCritical) {
                            Icon(Icons.Rounded.Fingerprint, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (isCritical) "Verify" else "Allow")
                    }
                }
            }
        }
    }
}

/**
 * Phase 1 debug panel — hard-coded test buttons that bypass the LLM.
 */
@Composable
private fun Phase1TestPanel(viewModel: HomeViewModel) {
    HorizontalDivider(
        modifier = Modifier.padding(bottom = 8.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
    Text(
        text = "Debug tests (no LLM)",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = { viewModel.runWhatsAppTest() },
            modifier = Modifier.weight(1f)
        ) {
            Text("Open WhatsApp", style = MaterialTheme.typography.labelSmall)
        }
        OutlinedButton(
            onClick = { viewModel.runWhatsAppMessageTest() },
            modifier = Modifier.weight(1f)
        ) {
            Text("WA → Rahul", style = MaterialTheme.typography.labelSmall)
        }
    }
}
