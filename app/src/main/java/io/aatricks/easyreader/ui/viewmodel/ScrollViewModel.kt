package io.aatricks.easyreader.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.aatricks.easyreader.data.local.PreferencesManager
import io.aatricks.easyreader.data.model.ScrollProgression
import io.aatricks.easyreader.data.repository.FinishedSeriesData
import io.aatricks.easyreader.data.repository.ScrollProgressionRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScrollViewModel @Inject constructor(
    private val repository: ScrollProgressionRepository,
    private val sessionTracker: ReadingSessionTracker,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    val gamificationEnabled: StateFlow<Boolean> = preferencesManager.scrollGamificationEnabledFlow

    fun setGamificationEnabled(enabled: Boolean) {
        preferencesManager.scrollGamificationEnabled = enabled
        if (!enabled) sessionTracker.stop()
    }

    val progression: StateFlow<ScrollProgression> = repository.progression

    val unseenMilestoneCount: StateFlow<Int> = repository.unseenMilestoneCount.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = 0
    )

    val finishedSeries: StateFlow<List<FinishedSeriesData>> = repository.finishedSeriesData.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = emptyList()
    )

    private val _xpNotice = MutableStateFlow<String?>(null)
    val xpNotice: StateFlow<String?> = _xpNotice.asStateFlow()

    init {
        viewModelScope.launch {
            sessionTracker.completionEvents.collect {
                val rank = progression.value.rankName
                _xpNotice.value = "+10 · $rank"
                delay(XP_NOTICE_DURATION_MS)
                _xpNotice.value = null
            }
        }
    }

    fun markMilestonesSeen() {
        repository.markAllMilestonesSeen()
    }

    companion object {
        private const val XP_NOTICE_DURATION_MS = 2500L
        private const val STOP_TIMEOUT_MILLIS = 5000L
    }
}
