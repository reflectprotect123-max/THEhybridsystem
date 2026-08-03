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
import java.time.Duration
import java.time.Instant
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

    /** Wall-clock time of the last successful [refresh]; null until one has ever succeeded. */
    private var lastRefreshedAt: Instant? = null

    /**
     * "This week" = the most recent Monday through the following Sunday, device-local.
     * docs/WEEKLY_CHECKIN_GAPS.md: weekStart/weekEnd are row labels only -- recomputeCheckIn's
     * actual computation always uses today's data regardless of what week is passed in, so this
     * convention only affects which row a check-in is filed under, not the numbers it computes.
     */
    private fun currentWeekStart(): LocalDate =
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun refresh() {
        // Skip re-triggering the writing recompute (recomputeExpenditure) on a resume that
        // happens shortly after the last successful one -- e.g. a rotation, or a tab re-entry
        // that Finding 3's nav fix didn't already dedupe. Still shows whatever is already
        // loaded; doesn't flip isLoading or clear state. Always proceeds on the very first load
        // (isLoading still true / nothing loaded yet), regardless of the timer.
        val alreadyHasData = !_uiState.value.isLoading
        val last = lastRefreshedAt
        if (alreadyHasData && last != null && Duration.between(last, Instant.now()) < REFRESH_MIN_INTERVAL) {
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val estimate = expenditureRepository.recomputeExpenditure()
                val checkIn = checkInRepository.getCheckIn(currentWeekStart())
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    estimate = estimate,
                    checkIn = checkIn,
                    // Self-correcting: hasWeighIn can only have been set to false by a failed
                    // checkIn() attempt (see its IllegalStateException branch below). A
                    // successful refresh() means we're not mid-check-in-attempt, so there is no
                    // reason to keep showing the "log a weigh-in" dead end -- if the user still
                    // genuinely has no weigh-in, the next checkIn() call will set this back to
                    // false itself.
                    hasWeighIn = true,
                )
                lastRefreshedAt = Instant.now()
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
                // Reset throttle to ensure refresh() re-runs when user returns after logging a weigh-in.
                lastRefreshedAt = null
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
            } catch (e: NoSuchElementException) {
                // decodeSingle() on CheckInRepository.resolve's compare-and-set filter matching
                // zero rows -- a losing concurrent resolve (docs/WEEKLY_CHECKIN_GAPS.md, "Still
                // open: losing concurrent resolve() call throws opaque error").
                _uiState.value = _uiState.value.copy(isResolving = false, errorMessage = "Someone already resolved this week's check-in.")
                refreshCheckInAfterFailedResolve()
            } catch (e: IllegalArgumentException) {
                // CheckInRepository.resolve's require(existing.status == "pending") -- the row
                // exists but was already accepted/declined.
                _uiState.value = _uiState.value.copy(isResolving = false, errorMessage = "This week's check-in was already resolved.")
                refreshCheckInAfterFailedResolve()
            } catch (e: IllegalStateException) {
                // CheckInRepository.resolve's error(...) when no row exists for this weekStart,
                // or a session-loss edge case (requireUserId() failure). Use a neutral message.
                _uiState.value = _uiState.value.copy(isResolving = false, errorMessage = "Couldn't resolve check-in — please refresh your session if needed.")
                refreshCheckInAfterFailedResolve()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isResolving = false, errorMessage = e.message ?: "Couldn't resolve check-in")
            }
        }
    }

    /**
     * Re-fetches this week's check-in after a failed [resolve] whose failure means our in-memory
     * `checkIn` may now be stale (someone else resolved it, or it was already resolved). Best
     * effort: a secondary failure here shouldn't clobber the errorMessage the caller just set, or
     * crash the coroutine, so it's swallowed rather than surfaced.
     */
    private suspend fun refreshCheckInAfterFailedResolve() {
        try {
            val checkIn = checkInRepository.getCheckIn(currentWeekStart())
            _uiState.value = _uiState.value.copy(checkIn = checkIn)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ignored -- see kdoc above.
        }
    }

    private companion object {
        private val REFRESH_MIN_INTERVAL: Duration = Duration.ofSeconds(60)
    }
}
