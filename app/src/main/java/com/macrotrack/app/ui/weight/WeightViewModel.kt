package com.macrotrack.app.ui.weight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrotrack.app.data.TrendRepository
import com.macrotrack.app.data.WeightRepository
import com.macrotrack.app.data.model.TrendPoint
import com.macrotrack.app.data.model.WeightEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

data class WeightUiState(
    val isLoading: Boolean = true,
    /** Ascending-by-time as returned by the repository; the screen reverses this for display. */
    val entries: List<WeightEntry> = emptyList(),
    val trendPoints: List<TrendPoint> = emptyList(),
    val weightInputText: String = "",
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

class WeightViewModel(
    private val weightRepository: WeightRepository,
    private val trendRepository: TrendRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeightUiState())
    val uiState: StateFlow<WeightUiState> = _uiState.asStateFlow()

    /** Wall-clock time of the last successful [refresh]; null until one has ever succeeded. */
    private var lastRefreshedAt: Instant? = null

    /**
     * @param force Bypass the [REFRESH_MIN_INTERVAL] throttle below. Used by [logWeight] and
     * [deleteEntry], which call this after a write specifically to pick up that write's effect on
     * both the entry list and the recomputed trend -- throttling those would leave the screen
     * showing stale data right after the user's own edit, which is worse than the extra
     * recompute the throttle exists to avoid. Screen-resume calls (`WeightScreen`'s ON_RESUME
     * observer) call this with no arguments and are the ones the throttle is for.
     */
    fun refresh(force: Boolean = false) {
        // Skip re-triggering the writing recompute (recomputeTrend) on a resume that happens
        // shortly after the last successful one -- e.g. a rotation, or a tab re-entry that
        // Finding 3's nav fix didn't already dedupe. Still shows whatever is already loaded;
        // doesn't flip isLoading or clear state. Always proceeds on the very first load
        // (isLoading still true / nothing loaded yet), regardless of the timer.
        val alreadyHasData = !_uiState.value.isLoading
        val last = lastRefreshedAt
        if (!force && alreadyHasData && last != null && Duration.between(last, Instant.now()) < REFRESH_MIN_INTERVAL) {
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val since = Instant.now().minus(HISTORY_WINDOW_DAYS, ChronoUnit.DAYS)
                val entries = weightRepository.listEntries(since)
                // recomputeTrend both persists and returns the recomputed points -- calling it
                // here (rather than a separate listTrendPoints call) is this screen's chosen
                // trigger for the trend recompute, per docs/TREND_VISUALISATION_GAPS.md's "no
                // trigger connects a weigh-in write/delete to a trend recompute" gap, which names
                // "on-demand when a trend screen opens" as one legitimate option.
                val trendPoints = trendRepository.recomputeTrend(since)
                _uiState.value = _uiState.value.copy(isLoading = false, entries = entries, trendPoints = trendPoints)
                lastRefreshedAt = Instant.now()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Couldn't load weight history")
            }
        }
    }

    fun onWeightInputChanged(text: String) {
        _uiState.value = _uiState.value.copy(weightInputText = text)
    }

    fun logWeight() {
        val weightKg = _uiState.value.weightInputText.toDoubleOrNull()
        if (weightKg == null || weightKg <= 0) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enter a weight in kilograms")
            return
        }
        _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            try {
                weightRepository.logWeight(measuredAt = Instant.now(), weightKg = weightKg)
                _uiState.value = _uiState.value.copy(isSaving = false, weightInputText = "")
                refresh(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Includes WeightRepository.logWeight's own require(weightKg in 20.0..500.0) --
                // its IllegalArgumentException message ("weightKg must be between 20 and 500,
                // got X") is informative enough to surface directly rather than re-wording it.
                _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = e.message ?: "Couldn't log weight")
            }
        }
    }

    fun deleteEntry(entryId: String) {
        viewModelScope.launch {
            try {
                weightRepository.deleteEntry(entryId)
                refresh(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "Couldn't delete entry")
            }
        }
    }

    companion object {
        private const val HISTORY_WINDOW_DAYS = 90L
        private val REFRESH_MIN_INTERVAL: Duration = Duration.ofSeconds(60)
    }
}
