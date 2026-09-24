package com.aura.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.aura.data.db.TaskLogDao
import com.aura.data.model.TaskLogEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class TaskLogViewModel @Inject constructor(
    taskLogDao: TaskLogDao
) : ViewModel() {
    val tasks: Flow<List<TaskLogEntity>> = taskLogDao.observeAll()
}
