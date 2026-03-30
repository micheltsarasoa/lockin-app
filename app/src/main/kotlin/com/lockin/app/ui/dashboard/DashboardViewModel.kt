package com.lockin.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lockin.filter.FilterEngine
import com.lockin.filter.db.DomainDao
import com.lockin.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val filterEngine: FilterEngine,
    private val domainDao: DomainDao,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    data class DashboardState(
        val isFilterReady: Boolean = false,
        val totalDomains: Int = 0,
        val isSyncing: Boolean = false,
    )

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    init {
        loadStats()
    }

    private fun loadStats() {
        viewModelScope.launch {
            val total = domainDao.totalDomainCount()
            _state.value = _state.value.copy(
                isFilterReady = filterEngine.isReady(),
                totalDomains = total,
            )
        }
    }

    fun syncNow() {
        syncScheduler.triggerImmediateSync()
        _state.value = _state.value.copy(isSyncing = true)
    }

    fun refreshStats() = loadStats()
}
