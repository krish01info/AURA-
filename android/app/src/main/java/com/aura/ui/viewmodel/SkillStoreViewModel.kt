package com.aura.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.aura.data.model.SkillPackEntity
import com.aura.skills.SkillRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import androidx.lifecycle.viewModelScope
import javax.inject.Inject

@HiltViewModel
class SkillStoreViewModel @Inject constructor(
    private val skillRepository: SkillRepository
) : ViewModel() {

    val skills: StateFlow<List<SkillPackEntity>> = skillRepository.getAllSkillsFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
}
