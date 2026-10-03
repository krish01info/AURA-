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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aura.data.model.AppPermission
import com.aura.data.model.BlockedApp
import com.aura.data.model.PermissionRule
import com.aura.ui.theme.AuraCritical
import com.aura.ui.theme.AuraPrimary
import com.aura.ui.theme.AuraSuccess
import com.aura.ui.theme.AuraWarning
import com.aura.ui.viewmodel.PermissionsViewModel

/**
 * PermissionsScreen — App-Action Permission Manager.
 *
 * Shows user-defined rules for what AURA can do per app/action.
 * Allows adding new rules, blocking apps, and viewing global deny rules.
 *
 * From SKILL-SYSTEM.md:
 *   ALWAYS_ALLOW   → Execute without any dialog
 *   ALWAYS_CONFIRM → Show approval dialog every single time
 *   ALWAYS_DENY    → Blocked. AURA refuses and explains why
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onBack: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel()
) {
    val rules by viewModel.rules.collectAsState()
    val blockedApps by viewModel.blockedApps.collectAsState()
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var showBlockAppDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🔐", fontSize = 20.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("App Permissions", fontWeight = FontWeight.Bold, color = AuraPrimary)
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
        },
        floatingActionButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FloatingActionButton(
                    onClick = { showBlockAppDialog = true },
                    containerColor = AuraCritical,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Block, "Block app", tint = Color.White)
                }
                FloatingActionButton(
                    onClick = { showAddRuleDialog = true },
                    containerColor = AuraPrimary
                ) {
                    Icon(Icons.Default.Add, "Add rule", tint = Color.White)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
        ) {
            // ── Blocked Apps ────────────────────────────────────
            if (blockedApps.isNotEmpty()) {
                item {
                    SectionHeader("🚫", "Blocked Apps", "AURA cannot interact with these apps")
                }
                items(blockedApps) { app ->
                    BlockedAppRow(
                        app = app,
                        onUnblock = { viewModel.unblockApp(app.appPackage) }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }

            // ── Permission Rules ─────────────────────────────────
            item {
                SectionHeader("🛡️", "Permission Rules", "Custom rules override global policy")
            }

            if (rules.isEmpty()) {
                item {
                    EmptyStateCard(
                        "No custom rules yet.\nTap + to add a rule like:\n" +
                        "\"WhatsApp → send_message → ALWAYS ALLOW\""
                    )
                }
            } else {
                items(rules) { rule ->
                    PermissionRuleRow(
                        rule = rule,
                        onDelete = { viewModel.deleteRule(rule.ruleId) }
                    )
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    // ── Add Rule Dialog ─────────────────────────────────────────
    if (showAddRuleDialog) {
        AddRuleDialog(
            onDismiss = { showAddRuleDialog = false },
            onConfirm = { pkg, action, perm, label ->
                viewModel.addRule(pkg, action, perm, label)
                showAddRuleDialog = false
            }
        )
    }

    // ── Block App Dialog ─────────────────────────────────────────
    if (showBlockAppDialog) {
        BlockAppDialog(
            onDismiss = { showBlockAppDialog = false },
            onConfirm = { pkg, name, reason ->
                viewModel.blockApp(pkg, name, reason)
                showBlockAppDialog = false
            }
        )
    }
}

// ─────────────────────────────────────────────────────────────────
// Composables
// ─────────────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(emoji: String, title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(start = 26.dp)
        )
    }
}

@Composable
private fun PermissionRuleRow(rule: PermissionRule, onDelete: () -> Unit) {
    val permColor = when (rule.permission) {
        AppPermission.ALWAYS_ALLOW   -> AuraSuccess
        AppPermission.ALWAYS_CONFIRM -> AuraWarning
        AppPermission.ALWAYS_DENY    -> AuraCritical
    }
    val permLabel = when (rule.permission) {
        AppPermission.ALWAYS_ALLOW   -> "ALWAYS ALLOW"
        AppPermission.ALWAYS_CONFIRM -> "ALWAYS CONFIRM"
        AppPermission.ALWAYS_DENY    -> "ALWAYS DENY"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = permColor.copy(alpha = 0.07f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = rule.appPackage ?: "Any app",
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        " → ${rule.intentAction ?: "any action"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .background(permColor, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            permLabel,
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (rule.maxAmount != null) {
                        Text("≤ ₹${rule.maxAmount.toInt()}", style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                    if (rule.allowedTimeStart != null) {
                        Text("${rule.allowedTimeStart}–${rule.allowedTimeEnd}",
                             style = MaterialTheme.typography.labelSmall,
                             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    }
                }
                if (rule.label.isNotBlank()) {
                    Text(rule.label, style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Delete rule",
                     tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            }
        }
    }
}

@Composable
private fun BlockedAppRow(app: BlockedApp, onUnblock: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = AuraCritical.copy(alpha = 0.07f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Block, null, tint = AuraCritical, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.displayName, fontWeight = FontWeight.Medium,
                     style = MaterialTheme.typography.bodyMedium)
                Text(app.appPackage, style = MaterialTheme.typography.labelSmall,
                     color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f))
                if (app.reason.isNotBlank()) {
                    Text(app.reason, style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                }
            }
            TextButton(onClick = onUnblock) {
                Text("Unblock", color = AuraPrimary)
            }
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
        Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            Text(message, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}

@Composable
private fun AddRuleDialog(
    onDismiss: () -> Unit,
    onConfirm: (pkg: String, action: String, perm: AppPermission, label: String) -> Unit
) {
    var pkg by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var selectedPerm by remember { mutableStateOf(AppPermission.ALWAYS_ALLOW) }
    var showPermMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Permission Rule", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it },
                    label = { Text("App package (blank = any)") },
                    placeholder = { Text("com.whatsapp") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = action,
                    onValueChange = { action = it },
                    label = { Text("Action (blank = any)") },
                    placeholder = { Text("send_message") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // Permission selector
                Box {
                    FilledTonalButton(
                        onClick = { showPermMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = when (selectedPerm) {
                                AppPermission.ALWAYS_ALLOW   -> AuraSuccess.copy(alpha = 0.15f)
                                AppPermission.ALWAYS_CONFIRM -> AuraWarning.copy(alpha = 0.15f)
                                AppPermission.ALWAYS_DENY    -> AuraCritical.copy(alpha = 0.15f)
                            }
                        )
                    ) {
                        Text(selectedPerm.name.replace("_", " "))
                    }
                    DropdownMenu(expanded = showPermMenu, onDismissRequest = { showPermMenu = false }) {
                        AppPermission.entries.forEach { perm ->
                            DropdownMenuItem(
                                text = { Text(perm.name.replace("_", " ")) },
                                onClick = { selectedPerm = perm; showPermMenu = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pkg.trim(), action.trim(), selectedPerm, label.trim()) },
                enabled = true
            ) { Text("Add Rule") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun BlockAppDialog(
    onDismiss: () -> Unit,
    onConfirm: (pkg: String, name: String, reason: String) -> Unit
) {
    var pkg by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block App", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("App name") },
                    placeholder = { Text("SBI YONO") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pkg,
                    onValueChange = { pkg = it },
                    label = { Text("Package name") },
                    placeholder = { Text("com.sbi.lotusintouch") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Reason (optional)") },
                    placeholder = { Text("Banking — too sensitive") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(pkg.trim(), name.trim(), reason.trim()) },
                enabled = pkg.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = AuraCritical)
            ) { Text("Block App") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
