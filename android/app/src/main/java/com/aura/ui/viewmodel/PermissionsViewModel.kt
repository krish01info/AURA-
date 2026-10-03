package com.aura.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.data.db.BlockedAppDao
import com.aura.data.db.PermissionRuleDao
import com.aura.data.model.AppPermission
import com.aura.data.model.BlockedApp
import com.aura.data.model.PermissionRule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PermissionsViewModel @Inject constructor(
    private val rulesDao: PermissionRuleDao,
    private val blockedAppDao: BlockedAppDao
) : ViewModel() {

    val rules: StateFlow<List<PermissionRule>> = rulesDao.getAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val blockedApps: StateFlow<List<BlockedApp>> = blockedAppDao.getAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun addRule(
        appPackage: String,
        intentAction: String,
        permission: AppPermission,
        label: String
    ) {
        viewModelScope.launch {
            rulesDao.upsert(
                PermissionRule(
                    appPackage   = appPackage.ifBlank { null },
                    intentAction = intentAction.ifBlank { null },
                    permission   = permission,
                    label        = label,
                    priority     = when (permission) {
                        AppPermission.ALWAYS_DENY    -> 10
                        AppPermission.ALWAYS_ALLOW   -> 5
                        AppPermission.ALWAYS_CONFIRM -> 1
                    }
                )
            )
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch { rulesDao.delete(ruleId) }
    }

    fun blockApp(appPackage: String, displayName: String, reason: String) {
        viewModelScope.launch {
            blockedAppDao.block(
                BlockedApp(
                    appPackage  = appPackage,
                    displayName = displayName.ifBlank { appPackage },
                    reason      = reason
                )
            )
        }
    }

    fun unblockApp(appPackage: String) {
        viewModelScope.launch { blockedAppDao.unblock(appPackage) }
    }
}
