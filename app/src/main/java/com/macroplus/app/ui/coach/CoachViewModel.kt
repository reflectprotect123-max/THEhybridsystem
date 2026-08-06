package com.macroplus.app.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroplus.app.data.CheckInRepository
import com.macroplus.app.data.ExpenditureRepository
import com.macroplus.app.data.MacroProgramRepository
import com.macroplus.app.data.WeightRepository
import com.macroplus.app.data.model.MacroProgram
import com.macroplus.app.data.model.MacroProgramDay
import com.macroplus.app.data.model.PersistedCheckIn
import com.macroplus.app.domain.ExpenditureEstimate
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
    /** True when at least one weigh-in exists; Coach gates check-in on this. */
    val hasWeighIn: Boolean = true,
    val targetRateKgPerWeek: Double = 0.0,
    val activeProgram: MacroProgram? = null,
    val appliedDayTarget: MacroProgramDay? = null,
    val hasUnsavedGoal: Boolean = false,
    val checkIn: PersistedCheckIn? = null,
    val isCheckingIn: Boolean = false,
    val isSavingGoal: Boolean = false,
    val isResolving: Boolean = false,
    val errorMessage: String? = null,
)

class CoachViewModel(
    private val expenditureRepository: ExpenditureRepository,
    private val checkInRepository: CheckInRepository,
    private val macroProgramRepository: MacroProgramRepository,
    private val weightRepository: WeightRepository,
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

    private fun nextWeekStart(): LocalDate = currentWeekStart().plusDays(7)

    /**
     * @param force Bypass the [REFRESH_MIN_INTERVAL] throttle below, mirroring
     * `WeightViewModel.refresh(force)`. Screen-resume calls (`CoachScreen`'s ON_RESUME observer)
     * pass nothing and are the ones the throttle exists for.
     */
    fun refresh(force: Boolean = false) {
        // Skip re-triggering the writing recompute (recomputeExpenditure) on a resume that
        // happens shortly after the last successful one -- e.g. a rotation, or a tab re-entry
        // that Finding 3's nav fix didn't already dedupe. Still shows whatever is already
        // loaded; doesn't flip isLoading or clear state. Always proceeds on the very first load
        // (isLoading still true / nothing loaded yet), regardless of the timer.
        //
        // hasWeighIn == false counts as "nothing usable loaded yet" for throttle purposes: that
        // state hides the whole check-in section behind a "log a weigh-in" prompt, and the only
        // way out of it is a refresh that re-reads the weight entries. Throttling that refresh
        // would strand a user who just logged their FIRST weigh-in on the prompt for up to a
        // minute, with no control on screen that could recompute it.
        val state = _uiState.value
        val alreadyHasData = !state.isLoading && state.hasWeighIn
        val last = lastRefreshedAt
        if (!force && alreadyHasData && last != null && Duration.between(last, Instant.now()) < REFRESH_MIN_INTERVAL) {
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val hasWeighIn = weightRepository.listEntries(Instant.EPOCH).isNotEmpty()
                val estimate = expenditureRepository.recomputeExpenditure()
                val activeProgram = macroProgramRepository.getActive()
                val checkIn = activeProgram?.let { checkInRepository.getCheckIn(currentWeekStart(), it.id) }
                var appliedDayTarget = activeProgram?.let {
                    macroProgramRepository.getDayTarget(nextWeekStart(), it.id)
                }
                var targetSyncError: String? = null
                if (activeProgram != null && checkIn?.status == "accepted" && appliedDayTarget == null) {
                    try {
                        appliedDayTarget = ensureNextWeekTarget(activeProgram, checkIn)
                        if (appliedDayTarget == null) {
                            targetSyncError = "Your accepted check-in has no complete target to apply."
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // The accepted check-in remains authoritative. A later
                        // refresh can retry the derived target write without
                        // asking the user to accept the check-in twice.
                        targetSyncError = "Your check-in is accepted, but next week's target is still syncing."
                    }
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    estimate = estimate,
                    activeProgram = activeProgram,
                    appliedDayTarget = appliedDayTarget,
                    targetRateKgPerWeek = activeProgram?.targetRateKgPerWeek ?: 0.0,
                    hasUnsavedGoal = false,
                    checkIn = checkIn,
                    hasWeighIn = hasWeighIn,
                    errorMessage = targetSyncError,
                )
                // CoachScreen renders errorMessage as an exclusive branch, so any non-null message
                // replaces the whole screen. Leave the throttle cleared in that case so the next
                // resume genuinely retries instead of re-showing the same error for a minute.
                lastRefreshedAt = if (targetSyncError == null) Instant.now() else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastRefreshedAt = null
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Couldn't load your coaching status")
            }
        }
    }

    fun onTargetRateChanged(rate: Double) {
        // The slider hands us a Float widened to Double, so an exact tick like -0.9 arrives as
        // -0.8999999761581421. Snap back to the 0.1 grid the slider actually offers (steps = 19
        // over -1f..1f) so the stored goal matches the value the user saw and so hasUnsavedGoal
        // doesn't latch on a difference that is pure float noise.
        val safeRate = (Math.round(rate * 10) / 10.0).coerceIn(-1.0, 1.0)
        _uiState.value = _uiState.value.copy(
            targetRateKgPerWeek = safeRate,
            hasUnsavedGoal = _uiState.value.activeProgram?.targetRateKgPerWeek != safeRate,
            errorMessage = null,
        )
    }

    fun saveGoal() {
        if (_uiState.value.isSavingGoal) return
        _uiState.value = _uiState.value.copy(isSavingGoal = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val program = macroProgramRepository.saveActive(_uiState.value.targetRateKgPerWeek)
                _uiState.value = _uiState.value.copy(
                    activeProgram = program,
                    targetRateKgPerWeek = program.targetRateKgPerWeek,
                    hasUnsavedGoal = false,
                    isSavingGoal = false,
                    // A changed goal creates a new program ID, so a prior proposal
                    // cannot reappear as if it belonged to this goal.
                    checkIn = null,
                    appliedDayTarget = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Clear the throttle: this error takes over the whole screen (CoachScreen renders
                // errorMessage as an exclusive branch), and the only thing that clears it is a
                // successful refresh, so that refresh must not be throttled away.
                lastRefreshedAt = null
                _uiState.value = _uiState.value.copy(
                    isSavingGoal = false,
                    errorMessage = e.message ?: "Couldn't save your goal",
                )
            }
        }
    }

    fun checkIn() {
        val program = _uiState.value.activeProgram
        if (program == null) {
            lastRefreshedAt = null
            _uiState.value = _uiState.value.copy(errorMessage = "Save a goal before checking in.")
            return
        }
        if (!_uiState.value.hasWeighIn) {
            lastRefreshedAt = null
            _uiState.value = _uiState.value.copy(errorMessage = "Log a weigh-in before checking in.")
            return
        }
        _uiState.value = _uiState.value.copy(isCheckingIn = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val weekStart = currentWeekStart()
                checkInRepository.recomputeCheckIn(
                    weekStart = weekStart,
                    weekEnd = weekStart.plusDays(6),
                    targetRateKgPerWeek = _uiState.value.targetRateKgPerWeek,
                    programId = program.id,
                )
                val checkIn = checkInRepository.getCheckIn(weekStart, program.id)
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
                lastRefreshedAt = null
                _uiState.value = _uiState.value.copy(isCheckingIn = false, errorMessage = e.message ?: "Couldn't check in")
            }
        }
    }

    fun resolve(accepted: Boolean) {
        val state = _uiState.value
        val program = state.activeProgram
        if (program == null) {
            lastRefreshedAt = null
            _uiState.value = _uiState.value.copy(errorMessage = "Save a goal before resolving a check-in.")
            return
        }
        if (accepted) {
            val checkIn = state.checkIn
            val hasCompleteTarget = checkIn != null &&
                checkIn.proposedCalories != null &&
                checkIn.proposedProteinG != null &&
                checkIn.proposedCarbsG != null &&
                checkIn.proposedFatG != null
            if (!hasCompleteTarget) {
                lastRefreshedAt = null
                _uiState.value = _uiState.value.copy(
                    errorMessage = "This check-in has no complete target to apply. Run it again before accepting.",
                )
                return
            }
        }
        _uiState.value = _uiState.value.copy(isResolving = true, errorMessage = null)
        viewModelScope.launch {
            var resolvedCheckIn: PersistedCheckIn? = null
            try {
                // Resolve first. This compare-and-set is the authoritative user
                // decision; derived target rows must never be written for a
                // check-in that lost a concurrent resolve or was declined.
                val checkIn = checkInRepository.resolve(currentWeekStart(), accepted, program.id)
                resolvedCheckIn = checkIn
                val appliedDayTarget = if (accepted) {
                    ensureNextWeekTarget(program, checkIn)
                } else {
                    _uiState.value.appliedDayTarget
                }
                val targetSyncError = if (accepted && appliedDayTarget == null) {
                    "Check-in accepted, but its next-week target is still syncing."
                } else {
                    null
                }
                // Same rule as elsewhere in this file: a non-null errorMessage takes over the
                // whole screen, and only a successful refresh clears it, so don't let the
                // throttle swallow that refresh.
                if (targetSyncError != null) {
                    lastRefreshedAt = null
                }
                _uiState.value = _uiState.value.copy(
                    isResolving = false,
                    checkIn = checkIn,
                    appliedDayTarget = appliedDayTarget,
                    errorMessage = targetSyncError,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoSuchElementException) {
                // decodeSingle() on CheckInRepository.resolve's compare-and-set filter matching
                // zero rows -- a losing concurrent resolve.
                val resolved = resolvedCheckIn
                if (resolved != null) {
                    markResolvedTargetSyncFailure(resolved, accepted)
                } else {
                    lastRefreshedAt = null
                    _uiState.value = _uiState.value.copy(isResolving = false, errorMessage = "Someone already resolved this week's check-in.")
                    refreshCheckInAfterFailedResolve()
                }
            } catch (e: IllegalArgumentException) {
                // CheckInRepository.resolve's require(existing.status == "pending") -- the row
                // exists but was already accepted/declined.
                val resolved = resolvedCheckIn
                if (resolved != null) {
                    markResolvedTargetSyncFailure(resolved, accepted)
                } else {
                    lastRefreshedAt = null
                    _uiState.value = _uiState.value.copy(isResolving = false, errorMessage = "This week's check-in was already resolved.")
                    refreshCheckInAfterFailedResolve()
                }
            } catch (e: IllegalStateException) {
                // CheckInRepository.resolve's error(...) when no row exists for this weekStart,
                // or a session-loss edge case (requireUserId() failure). Use a neutral message.
                val resolved = resolvedCheckIn
                if (resolved != null) {
                    markResolvedTargetSyncFailure(resolved, accepted)
                } else {
                    lastRefreshedAt = null
                    _uiState.value = _uiState.value.copy(isResolving = false, errorMessage = "Couldn't resolve check-in — please refresh your session if needed.")
                    refreshCheckInAfterFailedResolve()
                }
            } catch (e: Exception) {
                val resolved = resolvedCheckIn
                if (resolved != null) {
                    markResolvedTargetSyncFailure(resolved, accepted)
                } else {
                    lastRefreshedAt = null
                    _uiState.value = _uiState.value.copy(isResolving = false, errorMessage = e.message ?: "Couldn't resolve check-in")
                }
            }
        }
    }

    /**
     * The user's resolve decision was committed, but the follow-up work after it failed. Keep the
     * resolved row visible and force the next screen refresh to retry the idempotent repair.
     *
     * @param accepted the decision that was committed. Only the accept path writes a derived
     * next-week target, so the decline path must not claim the check-in was accepted.
     */
    private fun markResolvedTargetSyncFailure(resolvedCheckIn: PersistedCheckIn, accepted: Boolean) {
        lastRefreshedAt = null
        _uiState.value = _uiState.value.copy(
            isResolving = false,
            checkIn = resolvedCheckIn,
            errorMessage = if (accepted) {
                "Check-in accepted, but next week's target could not be synced yet. Reopen Coach to retry."
            } else {
                "Check-in declined, but this screen couldn't finish updating. Reopen Coach to retry."
            },
        )
    }

    /** Ensures an accepted check-in has a concrete target for the next program week. */
    private suspend fun ensureNextWeekTarget(program: MacroProgram, checkIn: PersistedCheckIn): MacroProgramDay? {
        val nextWeekStart = nextWeekStart()
        macroProgramRepository.getDayTarget(nextWeekStart, program.id)?.let { return it }
        val calories = checkIn.proposedCalories ?: return null
        val protein = checkIn.proposedProteinG ?: return null
        val carbs = checkIn.proposedCarbsG ?: return null
        val fat = checkIn.proposedFatG ?: return null
        macroProgramRepository.saveDayTargets(
            programId = program.id,
            startDate = nextWeekStart,
            endDate = nextWeekStart.plusDays(6),
            calories = calories,
            proteinG = protein,
            carbsG = carbs,
            fatG = fat,
        )
        return macroProgramRepository.getDayTarget(nextWeekStart, program.id)
    }

    /**
     * Re-fetches this week's check-in after a failed [resolve] whose failure means our in-memory
     * `checkIn` may now be stale (someone else resolved it, or it was already resolved). Best
     * effort: a secondary failure here shouldn't clobber the errorMessage the caller just set, or
     * crash the coroutine, so it's swallowed rather than surfaced.
     */
    private suspend fun refreshCheckInAfterFailedResolve() {
        try {
            val checkIn = checkInRepository.getCheckIn(
                currentWeekStart(),
                _uiState.value.activeProgram?.id,
            )
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
