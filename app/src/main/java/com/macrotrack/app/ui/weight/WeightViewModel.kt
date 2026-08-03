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

    fun refresh() {
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
                refresh()
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
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "Couldn't delete entry")
            }
        }
    }

    companion object {
        private const val HISTORY_WINDOW_DAYS = 90L
    }
}
