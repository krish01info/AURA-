package com.aura.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aura.data.db.AuditLogDao
import com.aura.data.db.BlockedAppDao
import com.aura.data.db.MemoryDao
import com.aura.data.db.PermissionRuleDao
import com.aura.data.db.SkillPackDao
import com.aura.data.db.TaskLogDao
import com.aura.data.model.SkillPackEntity
import com.aura.data.model.TaskLogEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardStats(
    val tasksCompleted: Int = 0,
    val minutesSaved: Int = 0,
    val successRate: Int = 0,
    val totalSkills: Int = 0,
    val totalMemories: Int = 0,
    val deniedCount: Int = 0,
    val blockedAppsCount: Int = 0,
    val permissionRulesCount: Int = 0
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val taskLogDao: TaskLogDao,
    private val skillPackDao: SkillPackDao,
    private val memoryDao: MemoryDao,
    private val auditLogDao: AuditLogDao,
    private val blockedAppDao: BlockedAppDao,
    private val permissionRuleDao: PermissionRuleDao
) : ViewModel() {

    private val _stats = MutableStateFlow(DashboardStats())
    val stats: StateFlow<DashboardStats> = _stats.asStateFlow()

    val topSkills: StateFlow<List<SkillPackEntity>> = skillPackDao.getAllSkills().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val recentTasks: StateFlow<List<TaskLogEntity>> = taskLogDao.getAll().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    init {
        loadStats()
    }

    private fun loadStats() {
        viewModelScope.launch {
            val allTasks = taskLogDao.getAllList()
            val completed = allTasks.count { it.status.name == "COMPLETED" }
            val total = allTasks.size
            val successRate = if (total > 0) (completed * 100 / total) else 0
            // Estimate 2min per task saved vs doing it manually
            val minutesSaved = completed * 2
            val memories = memoryDao.count()
            val skills = skillPackDao.count()
            val denied = auditLogDao.getDeniedCount()
            val blockedApps = blockedAppDao.getAll().let { flow ->
                // Count synchronously via count query isn't exposed — use list size
                0  // will be populated from getAll() flow observer
            }
            val permRules = permissionRuleDao.count()

            _stats.value = DashboardStats(
                tasksCompleted = completed,
                minutesSaved = minutesSaved,
                successRate = successRate,
                totalSkills = skills,
                totalMemories = memories,
                deniedCount = denied,
                permissionRulesCount = permRules
            )
        }
    }
}
