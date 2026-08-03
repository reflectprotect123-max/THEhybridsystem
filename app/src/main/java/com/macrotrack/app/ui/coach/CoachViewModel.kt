package com.macrotrack.app.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrotrack.app.data.CheckInRepository
import com.macrotrack.app.data.ExpenditureRepository
import com.macrotrack.app.data.model.PersistedCheckIn
import com.macrotrack.app.domain.ExpenditureEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class CoachUiState(
    val isLoading: Boolean = true,
    val estimate: ExpenditureEstimate? = null,
    /** False only right after a check-in attempt failed for lack of a weigh-in. */
    val hasWeighIn: Boolean = true,
    val targetRateKgPerWeek: Double = 0.0,
    val checkIn: PersistedCheckIn? = null,
    val isCheckingIn: Boolean = false,
    val isResolving: Boolean = false,
    val errorMessage: String? = null,
)

class CoachViewModel(
    private val expenditureRepository: ExpenditureRepository,
    private val checkInRepository: CheckInRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoachUiState())
    val uiState: StateFlow<CoachUiState> = _uiState.asStateFlow()

    /**
     * "This week" = the most recent Monday through the following Sunday, device-local.
     * docs/WEEKLY_CHECKIN_GAPS.md: weekStart/weekEnd are row labels only -- recomputeCheckIn's
     * actual computation always uses today's data regardless of what week is passed in, so this
     * convention only affects which row a check-in is filed under, not the numbers it computes.
     */
    private fun currentWeekStart(): LocalDate =
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val estimate = expenditureRepository.recomputeExpenditure()
                val checkIn = checkInRepository.getCheckIn(currentWeekStart())
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    estimate = estimate,
                    checkIn = checkIn,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Couldn't load your coaching status")
            }
        }
    }

    fun onTargetRateChanged(rate: Double) {
        _uiState.value = _uiState.value.copy(targetRateKgPerWeek = rate)
    }

    fun checkIn() {
        _uiState.value = _uiState.value.copy(isCheckingIn = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val weekStart = currentWeekStart()
                checkInRepository.recomputeCheckIn(
                    weekStart = weekStart,
                    weekEnd = weekStart.plusDays(6),
                    targetRateKgPerWeek = _uiState.value.targetRateKgPerWeek,
                )
                val checkIn = checkInRepository.getCheckIn(weekStart)
                _uiState.value = _uiState.value.copy(isCheckingIn = false, hasWeighIn = true, checkIn = checkIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                // recomputeCheckIn requires at least one logged weigh-in -- a real, expected
                // precondition failure (docs/WEEKLY_CHECKIN_GAPS.md), not an error to display.
                _uiState.value = _uiState.value.copy(isCheckingIn = false, hasWeighIn = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isCheckingIn = false, errorMessage = e.message ?: "Couldn't check in")
            }
        }
    }

    fun resolve(accepted: Boolean) {
        _uiState.value = _uiState.value.copy(isResolving = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val checkIn = checkInRepository.resolve(currentWeekStart(), accepted)
                _uiState.value = _uiState.value.copy(isResolving = false, checkIn = checkIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isResolving = false, errorMessage = e.message ?: "Couldn't resolve check-in")
            }
        }
    }
}
