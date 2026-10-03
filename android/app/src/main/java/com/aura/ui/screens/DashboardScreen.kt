package com.aura.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.ui.theme.AuraPrimary
import com.aura.ui.theme.AuraSecondary
import com.aura.ui.theme.AuraSuccess
import com.aura.ui.theme.AuraWarning
import com.aura.ui.theme.AuraCritical
import com.aura.ui.viewmodel.DashboardViewModel

/**
 * DashboardScreen — Phase 8 Productivity Dashboard.
 *
 * Shows:
 *  - Tasks completed, time saved, success rate (animated stats)
 *  - Top skills used (from SkillRouter hit counts)
 *  - Memory entries count (Phase 5)
 *  - Security events (denied/anomaly counts from Phase 7)
 *  - Recent task history
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBack: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val stats by viewModel.stats.collectAsState()
    val topSkills by viewModel.topSkills.collectAsState()
    val recentTasks by viewModel.recentTasks.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📊", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("Dashboard", fontWeight = FontWeight.Bold, color = AuraPrimary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // ── Hero stats row ───────────────────────────────────
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StatCard(
                        emoji = "✅",
                        label = "Tasks Done",
                        value = "${stats.tasksCompleted}",
                        color = AuraSuccess,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        emoji = "⏱️",
                        label = "Time Saved",
                        value = "${stats.minutesSaved}m",
                        color = AuraPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        emoji = "🎯",
                        label = "Success Rate",
                        value = "${stats.successRate}%",
                        color = AuraSecondary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Second stats row ─────────────────────────────────
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StatCard(
                        emoji = "🧩",
                        label = "Skills",
                        value = "${stats.totalSkills}",
                        color = AuraWarning,
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        emoji = "🧠",
                        label = "Memories",
                        value = "${stats.totalMemories}",
                        color = Color(0xFF8B5CF6),
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        emoji = "🛡️",
                        label = "Blocked",
                        value = "${stats.deniedCount}",
                        color = AuraCritical,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── Top skills ────────────────────────────────────────
            item {
                SectionHeader(
                    icon = Icons.Default.Timeline,
                    title = "Top Skills"
                )
            }

            if (topSkills.isEmpty()) {
                item {
                    EmptyStateCard("Run some tasks to see your most-used skills here")
                }
            } else {
                items(topSkills.take(5)) { skill ->
                    SkillUsageRow(
                        name = skill.skillId.replace("_", " ").replaceFirstChar { it.uppercase() },
                        count = skill.usageCount,
                        successRate = skill.successRate,
                        maxCount = topSkills.maxOf { it.usageCount }
                    )
                }
            }

            // ── Recent tasks ──────────────────────────────────────
            item {
                SectionHeader(
                    icon = Icons.Default.CheckCircle,
                    title = "Recent Tasks"
                )
            }

            if (recentTasks.isEmpty()) {
                item { EmptyStateCard("No tasks yet — give AURA a command!") }
            } else {
                items(recentTasks.take(8)) { task ->
                    RecentTaskRow(
                        goal = task.goal,
                        status = task.status.name,
                        timestamp = task.startedAt
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// Composables
// ──────────────────────────────────────────────────────────────

@Composable
private fun StatCard(
    emoji: String,
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 24.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                text = value,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
    ) {
        Icon(icon, null, tint = AuraPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleMedium
        )
    }
}

@Composable
private fun SkillUsageRow(
    name: String,
    count: Int,
    successRate: Float,
    maxCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${count}x",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { if (maxCount > 0) count.toFloat() / maxCount else 0f },
                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                color = AuraPrimary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )
        }
    }
}

@Composable
private fun RecentTaskRow(goal: String, status: String, timestamp: Long) {
    val statusColor = when (status) {
        "COMPLETED" -> AuraSuccess
        "FAILED"    -> AuraCritical
        "CANCELLED" -> AuraWarning
        else        -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    }
    val statusEmoji = when (status) {
        "COMPLETED" -> "✅"
        "FAILED"    -> "❌"
        "CANCELLED" -> "🛑"
        else        -> "⏳"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(statusEmoji, fontSize = 16.sp, modifier = Modifier.width(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = goal.take(50),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = formatTimestamp(timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

@Composable
private fun EmptyStateCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
        }
    }
}

private fun formatTimestamp(ts: Long): String {
    val ago = System.currentTimeMillis() - ts
    return when {
        ago < 60_000   -> "just now"
        ago < 3_600_000 -> "${ago / 60_000}m ago"
        ago < 86_400_000 -> "${ago / 3_600_000}h ago"
        else            -> "${ago / 86_400_000}d ago"
    }
}
