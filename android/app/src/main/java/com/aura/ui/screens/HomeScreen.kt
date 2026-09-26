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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToLog: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val taskState by viewModel.taskState.collectAsState()
    val isAccessibilityEnabled by viewModel.isAccessibilityEnabled.collectAsState()
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
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isAccessibilityEnabled) AuraSuccess else AuraCritical)
                        )
                        Spacer(Modifier.width(8.dp))
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
                AnimatedVisibility(visible = !isAccessibilityEnabled) {
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

                Spacer(Modifier.height(24.dp))
                AuraOrb(taskState = taskState)
                Spacer(Modifier.height(16.dp))

                Text(
                    text = taskState.statusText(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.weight(1f))

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
                                Icon(Icons.Default.Mic, "Voice input", tint = AuraSecondary)
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
            }
        }

        val isExecuting = taskState is TaskState.Executing || taskState is TaskState.WaitingForUser
        AnimatedVisibility(
            visible = isExecuting,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
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

        if (taskState is TaskState.WaitingForUser) {
            ConfirmationOverlay(
                state = taskState as TaskState.WaitingForUser,
                onAllow = { viewModel.approveAction() },
                onDeny = { viewModel.denyAction() }
            )
        }
    }
}

@Composable
private fun AuraOrb(taskState: TaskState) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (taskState is TaskState.Executing || taskState is TaskState.Planning) 1.15f else 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb_scale"
    )

    val orbColor = when (taskState) {
        is TaskState.Executing -> AuraPrimary
        is TaskState.Planning -> AuraSecondary
        is TaskState.Completed -> AuraSuccess
        is TaskState.Failed -> AuraCritical
        is TaskState.WaitingForUser -> AuraWarning
        else -> AuraPrimary.copy(alpha = 0.5f)
    }

    Box(
        modifier = Modifier.size(120.dp).scale(scale).clip(CircleShape).background(
            Brush.radialGradient(colors = listOf(orbColor, orbColor.copy(alpha = 0.3f)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Text("✦", fontSize = 48.sp, color = Color.White)
    }
}

private fun TaskState.statusText(): String = when (this) {
    is TaskState.Created -> "Ready. Give me a command."
    is TaskState.Planning -> "Thinking…"
    is TaskState.Executing -> "Step ${stepIndex + 1} of $totalSteps: ${currentStep.action}"
    is TaskState.WaitingForUser -> "Waiting for your approval"
    is TaskState.Verifying -> "Verifying…"
    is TaskState.Completed -> "✓ $summary"
    is TaskState.Failed -> "Failed: $reason"
    is TaskState.Cancelled -> "Stopped."
}

@Composable
private fun ConfirmationOverlay(
    state: TaskState.WaitingForUser,
    onAllow: () -> Unit,
    onDeny: () -> Unit
) {
    val riskColor = if (state.riskLevel == RiskLevel.CRITICAL) AuraCritical else AuraWarning

    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (state.riskLevel == RiskLevel.CRITICAL) "💳 CRITICAL ACTION" else "⚠️ CONFIRM ACTION",
                    fontWeight = FontWeight.Bold,
                    color = riskColor,
                    fontSize = 18.sp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = state.riskAction.message ?: "Allow this action: ${state.riskAction.action}?",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (state.riskLevel == RiskLevel.CRITICAL) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠️ This action cannot be undone",
                        color = AuraCritical,
                        style = MaterialTheme.typography.bodySmall
                    )
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
                        colors = ButtonDefaults.buttonColors(containerColor = AuraSuccess),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("✓ Allow")
                    }
                }
            }
        }
    }
}
