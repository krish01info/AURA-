package com.aura.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.agent.RiskLevel
import com.aura.agent.TaskState
import com.aura.ui.theme.AuraCritical
import com.aura.ui.theme.AuraPrimary
import com.aura.ui.theme.AuraSecondary
import com.aura.ui.theme.AuraSuccess
import com.aura.ui.theme.AuraWarning
import com.aura.ui.viewmodel.HomeViewModel

/**
 * HomeScreen — the main AURA interface.
 *
 * Phase 3: ConfirmationOverlay + BiometricGate for HIGH/CRITICAL actions
 * Phase 4: Voice toggle, listening indicator, WakeWord bubble toggle
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
    var commandText by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "AURA",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = AuraPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Agent",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    },
                    actions = {
                        // Accessibility status dot
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isAccessibilityEnabled) AuraSuccess else AuraCritical)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(onClick = onNavigateToDashboard) {
                            Icon(Icons.Default.BarChart, "Dashboard", tint = AuraPrimary)
                        }
                        IconButton(onClick = onNavigateToSkillStore) {
                            Icon(Icons.Default.Extension, "Skill Store", tint = AuraSecondary)
                        }
                        IconButton(onClick = onNavigateToLog) {
                            Icon(Icons.Default.History, "Task log", tint = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ── Accessibility warning ─────────────────────────────
                AnimatedVisibility(visible = !isAccessibilityEnabled) {
                    Column {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = AuraWarning.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("⚠️", fontSize = 20.sp)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        "Accessibility Service disabled",
                                        fontWeight = FontWeight.SemiBold,
                                        color = AuraWarning
                                    )
                                    Text(
                                        "AURA needs this to control your phone",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                Button(
                                    onClick = {
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = AuraWarning)
                                ) {
                                    Text("Enable", fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }

                // ── Overlay permission hint ───────────────────────────
                AnimatedVisibility(visible = !viewModel.canShowOverlay) {
                    Column {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = AuraSecondary.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("💬", fontSize = 18.sp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Grant overlay permission for floating mic bubble",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = { viewModel.requestOverlayPermission() },
                                    colors = ButtonDefaults.buttonColors(containerColor = AuraSecondary),
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text("Grant", fontSize = 11.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Main Orb ─────────────────────────────────────────
                AuraOrb(taskState = taskState, isListening = isListening)
                Spacer(Modifier.height(16.dp))

                // ── Status text ──────────────────────────────────────
                Text(
                    text = if (isListening) "🎤 Listening…"
                    else if (isSpeaking) "🔊 Speaking…"
                    else taskState.statusText(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.weight(1f))

                // ── Voice Mode Toggle (Phase 4) ───────────────────────
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isVoiceEnabled)
                            AuraPrimary.copy(alpha = 0.12f)
                        else
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isVoiceEnabled) Icons.Default.RecordVoiceOver else Icons.Default.MicOff,
                            contentDescription = null,
                            tint = if (isVoiceEnabled) AuraPrimary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Wake Word Mode",
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "Say \"Hey AURA\" to activate",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                            )
                        }
                        Switch(
                            checked = isVoiceEnabled,
                            onCheckedChange = { viewModel.setVoiceEnabled(it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = AuraPrimary)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Command Input ────────────────────────────────────
                OutlinedTextField(
                    value = commandText,
                    onValueChange = { commandText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "Say your command…",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    },
                    trailingIcon = {
                        Row {
                            IconButton(onClick = { viewModel.startVoiceInput() }) {
                                Icon(
                                    imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = "Voice input",
                                    tint = if (isListening) AuraWarning else AuraSecondary
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (commandText.isNotBlank()) {
                                        viewModel.executeCommand(commandText)
                                        commandText = ""
                                    }
                                }
                            ) {
                                Icon(Icons.Default.Send, "Send command", tint = AuraPrimary)
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AuraPrimary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                )

                Spacer(Modifier.height(16.dp))

                // ── Phase 1 Test Panel (debug) ────────────────────────
                Phase1TestPanel(viewModel = viewModel)

                Spacer(Modifier.height(16.dp))
            }
        }

        // ── Floating Emergency STOP button ──────────────────────────
        val showStop = taskState is TaskState.Executing || taskState is TaskState.Planning
        AnimatedVisibility(
            visible = showStop,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp)
        ) {
            Button(
                onClick = { viewModel.emergencyStop() },
                colors = ButtonDefaults.buttonColors(containerColor = AuraCritical),
                shape = RoundedCornerShape(50)
            ) {
                Icon(Icons.Default.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("STOP", fontWeight = FontWeight.Bold)
            }
        }

        // ── Confirmation / Biometric overlay ─────────────────────────
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

@Composable
private fun AuraOrb(taskState: TaskState, isListening: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = when {
            isListening -> 1.25f
            taskState is TaskState.Executing || taskState is TaskState.Planning -> 1.15f
            else -> 1.02f
        },
        animationSpec = infiniteRepeatable(
            animation = tween(if (isListening) 600 else 1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_scale"
    )

    val orbColor = when {
        isListening -> AuraSecondary
        else -> when (taskState) {
            is TaskState.Executing   -> AuraPrimary
            is TaskState.Planning    -> AuraSecondary
            is TaskState.Completed   -> AuraSuccess
            is TaskState.Failed      -> AuraCritical
            is TaskState.WaitingForUser -> AuraWarning
            else -> AuraPrimary.copy(alpha = 0.5f)
        }
    }

    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(colors = listOf(orbColor, orbColor.copy(alpha = 0.3f)))
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isListening) "🎤" else "✦",
            fontSize = 48.sp,
            color = Color.White
        )
    }
}

private fun TaskState.statusText(): String = when (this) {
    is TaskState.Created        -> "Ready. Give me a command."
    is TaskState.Planning       -> "Thinking…"
    is TaskState.Executing      -> "Step ${stepIndex + 1} of $totalSteps: ${currentStep.action}"
    is TaskState.WaitingForUser -> "Waiting for your approval"
    is TaskState.Verifying      -> "Verifying…"
    is TaskState.Completed      -> "✓ $summary"
    is TaskState.Failed         -> "Failed: $reason"
    is TaskState.Cancelled      -> "Stopped."
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
            .background(Color.Black.copy(alpha = 0.75f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isCritical) "💳 CRITICAL ACTION" else "⚠️ CONFIRM ACTION",
                    fontWeight = FontWeight.Bold,
                    color = riskColor,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Risk Level: ${state.riskLevel.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = riskColor.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = state.riskAction.message
                        ?: "Allow this action: ${state.riskAction.action}?",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (isCritical) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠️ This action cannot be undone",
                        color = AuraCritical,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "🔐 Biometric authentication required",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // Anomaly warning banner
                if (state.anomalyWarning != null) {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = AuraCritical.copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = state.anomalyWarning,
                            color = AuraCritical,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = onDeny,
                        colors = ButtonDefaults.buttonColors(containerColor = AuraCritical),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("✗ Deny")
                    }
                    Button(
                        onClick = onAllow,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isCritical) AuraWarning else AuraSuccess
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (isCritical) "🔐 Authenticate" else "✓ Allow")
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
    androidx.compose.material3.HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    )
    Text(
        text = "Phase 1 Tests (no LLM)",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        modifier = Modifier.padding(bottom = 4.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        androidx.compose.material3.OutlinedButton(
            onClick = { viewModel.runWhatsAppTest() },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Open WhatsApp", style = MaterialTheme.typography.labelSmall)
        }
        androidx.compose.material3.OutlinedButton(
            onClick = { viewModel.runWhatsAppMessageTest() },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("WA → Rahul", style = MaterialTheme.typography.labelSmall)
        }
    }
}
