package com.aura.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.data.model.SkillPackEntity
import com.aura.ui.theme.AuraPrimary
import com.aura.ui.theme.AuraSecondary
import com.aura.ui.theme.AuraSuccess
import com.aura.ui.theme.AuraWarning
import com.aura.ui.viewmodel.SkillStoreViewModel

/**
 * SkillStoreScreen — community skill browser (Phase 6).
 *
 * Shows all local skills with usage stats, success rates, and verification badges.
 * Future: download from Supabase cloud, rate skills, contribute new ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillStoreScreen(
    onBack: () -> Unit,
    viewModel: SkillStoreViewModel = hiltViewModel()
) {
    val skills by viewModel.skills.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(skills, searchQuery) {
        if (searchQuery.isBlank()) skills
        else skills.filter { skill ->
            skill.skillId.contains(searchQuery, ignoreCase = true) ||
            skill.description.contains(searchQuery, ignoreCase = true) ||
            skill.triggerKeywords.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🧩", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Skill Store",
                            fontWeight = FontWeight.Bold,
                            color = AuraPrimary
                        )
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(Modifier.height(12.dp))

            // ── Search ───────────────────────────────────────────
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search skills…") },
                leadingIcon = {
                    Icon(Icons.Default.Search, "Search", tint = AuraSecondary)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AuraPrimary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(16.dp),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            // ── Stats bar ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${filtered.size} skills",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Text(
                    "${skills.count { it.isVerified }} verified",
                    style = MaterialTheme.typography.bodySmall,
                    color = AuraSuccess
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Skill list ────────────────────────────────────────
            AnimatedVisibility(
                visible = filtered.isEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔍", fontSize = 40.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No skills match \"$searchQuery\"",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                        Text(
                            "Run a task — AURA will learn it automatically",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                        )
                    }
                }
            }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filtered, key = { it.skillId }) { skill ->
                    SkillCard(skill = skill)
                }
            }
        }
    }
}

@Composable
private fun SkillCard(skill: SkillPackEntity) {
    val riskColor = when (skill.riskLevel) {
        "CRITICAL" -> Color(0xFFEF4444)
        "HIGH"     -> AuraWarning
        "MEDIUM"   -> AuraSecondary
        else       -> AuraSuccess
    }

    val appEmoji = when {
        skill.appPackage.contains("whatsapp") -> "💬"
        skill.appPackage.contains("paisa")    -> "💳"
        skill.appPackage.contains("zomato")   -> "🍕"
        skill.appPackage.contains("youtube")  -> "▶️"
        skill.appPackage.contains("maps")     -> "🗺️"
        skill.appPackage.contains("gm")       -> "📧"
        else                                   -> "⚡"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(appEmoji, fontSize = 24.sp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = skill.skillId.replace("_", " ")
                                .replaceFirstChar { it.uppercase() },
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (skill.isVerified) {
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                Icons.Default.CheckCircle,
                                "Verified",
                                tint = AuraSuccess,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Text(
                        text = skill.description.ifBlank { skill.appPackage },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                // Risk badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(riskColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = skill.riskLevel,
                        style = MaterialTheme.typography.labelSmall,
                        color = riskColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Success rate bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Star,
                    "Success rate",
                    tint = AuraWarning,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(4.dp))
                LinearProgressIndicator(
                    progress = { skill.successRate },
                    modifier = Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(50)),
                    color = AuraSuccess,
                    trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "${(skill.successRate * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = AuraSuccess
                )
            }

            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Used ${skill.usageCount}x",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                Text(
                    if (skill.createdBy == "local") "📱 Local" else "☁️ Community",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (skill.createdBy == "local") AuraPrimary else AuraSecondary
                )
            }
        }
    }
}
