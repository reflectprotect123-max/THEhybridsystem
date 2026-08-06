package com.macroplus.app.ui.dailylog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroplus.app.data.AuthRepository
import com.macroplus.app.data.DayStatusRepository
import com.macroplus.app.data.LogRepository
import com.macroplus.app.data.model.DailyTotals
import com.macroplus.app.data.model.DailyLogStatus
import com.macroplus.app.data.model.DayStatus
import com.macroplus.app.data.model.FoodLogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DailyLogUiState(
    val isLoading: Boolean = true,
    val selectedDate: LocalDate = LocalDate.now(),
    val entries: List<FoodLogEntry> = emptyList(),
    /** Null means "nothing logged today" -- never rendered as a zero. */
    val totals: DailyTotals? = null,
    /** Null means the user has not explicitly declared today's state. */
    val dayStatus: DailyLogStatus? = null,
    val isSavingStatus: Boolean = false,
    val deletingEntryId: String? = null,
    /**
     * True when the most recent load for [selectedDate] failed. In that state
     * `entries`/`totals`/`dayStatus` are *unknown*, not empty, so the UI must not render them
     * (nor the day-status controls that act on them) as if they described the selected date.
     */
    val loadFailed: Boolean = false,
    val errorMessage: String? = null,
)

class DailyLogViewModel(
    private val logRepository: LogRepository,
    private val dayStatusRepository: DayStatusRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyLogUiState())
    val uiState: StateFlow<DailyLogUiState> = _uiState.asStateFlow()

    private var refreshJob: kotlinx.coroutines.Job? = null

    /**
     * Loads [date]. This function owns the pairing of `selectedDate` with the data fields in
     * *every* state it publishes -- loading, success and failure -- so a failed load can never
     * leave a previously loaded day's entries/totals/status on screen underneath a newly
     * selected date.
     */
    fun refresh(date: LocalDate = _uiState.value.selectedDate) {
        refreshJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            selectedDate = date,
            loadFailed = false,
            errorMessage = null,
        )
        refreshJob = viewModelScope.launch {
            try {
                val entries = logRepository.listEntries(date)
                val totals = logRepository.getDailyTotals(date)
                val dayStatus = dayStatusRepository.getStatus(date)
                _uiState.value = DailyLogUiState(
                    isLoading = false,
                    selectedDate = date,
                    entries = entries,
                    totals = totals,
                    dayStatus = dayStatus,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Replace the state wholesale, exactly like the success path. Copying the old
                // state here would keep the previously loaded day's entries/totals/status
                // visible under the newly selected date.
                _uiState.value = DailyLogUiState(
                    isLoading = false,
                    selectedDate = date,
                    entries = emptyList(),
                    totals = null,
                    dayStatus = null,
                    loadFailed = true,
                    errorMessage = e.message ?: "Couldn't load this day's log",
                )
            }
        }
    }

    /**
     * Moves the selected date by [days]. Forward movement is clamped to today: the adaptive
     * engine and expenditure calculations only look back from today, so food logged against a
     * future date would be invisible to them. Backward movement is intentionally unbounded so
     * history stays browsable.
     */
    fun moveDate(days: Long) {
        val currentDate = _uiState.value.selectedDate
        val nextDate = currentDate.plusDays(days).coerceAtMost(LocalDate.now())
        if (nextDate == currentDate) return
        refresh(nextDate)
    }

    fun goToToday() {
        refresh(LocalDate.now())
    }

    fun signOut() {
        viewModelScope.launch {
            try {
                authRepository.signOut()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "Couldn't sign out")
            }
        }
    }

    /**
     * Saves an explicit state for the selected date. The app never infers
     * `complete` from the presence of entries, and never infers `fasted` from
     * their absence.
     */
    fun setDayStatus(status: String) {
        val selectedDate = _uiState.value.selectedDate
        val totals = _uiState.value.totals
        if (status !in VALID_STATUSES) {
            _uiState.value = _uiState.value.copy(errorMessage = "Unknown day status: $status")
            return
        }
        if (_uiState.value.loadFailed) {
            // `totals` is unknown after a failed load, so the checks below would validate this
            // status against data we do not have for this date.
            _uiState.value = _uiState.value.copy(
                errorMessage = "Reload this day before changing its status.",
            )
            return
        }
        when {
            status == DayStatus.COMPLETE && totals == null -> {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Log at least one food first, or mark the day as fasted.",
                )
                return
            }
            status == DayStatus.PARTIAL && totals == null -> {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Log at least one food before marking the day partial.",
                )
                return
            }
            status == DayStatus.FASTED && totals != null -> {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Remove the logged food before marking this day fasted.",
                )
                return
            }
        }

        _uiState.value = _uiState.value.copy(isSavingStatus = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val saved = dayStatusRepository.setStatus(selectedDate, status)
                if (_uiState.value.selectedDate == selectedDate) {
                    _uiState.value = _uiState.value.copy(dayStatus = saved, isSavingStatus = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_uiState.value.selectedDate == selectedDate) {
                    _uiState.value = _uiState.value.copy(
                        isSavingStatus = false,
                        errorMessage = e.message ?: "Couldn't save this day's status",
                    )
                }
            }
        }
    }

    /** Removes an entry using the repository's owner-scoped soft-delete path. */
    fun deleteEntry(entryId: String) {
        if (_uiState.value.deletingEntryId != null) return
        val selectedDate = _uiState.value.selectedDate
        _uiState.value = _uiState.value.copy(deletingEntryId = entryId, errorMessage = null)
        viewModelScope.launch {
            try {
                logRepository.deleteEntry(entryId)
                val updatedTotals = logRepository.getDailyTotals(selectedDate)
                val currentStatus = _uiState.value.dayStatus
                val updatedStatus = if (updatedTotals == null && currentStatus?.status == DayStatus.COMPLETE) {
                    // A completed day with no remaining entries would otherwise look complete
                    // in the UI while the engine correctly treats its calories as unknown.
                    dayStatusRepository.setStatus(selectedDate, DayStatus.UNLOGGED)
                } else {
                    currentStatus
                }
                if (_uiState.value.selectedDate == selectedDate) {
                    _uiState.value = _uiState.value.copy(
                        entries = logRepository.listEntries(selectedDate),
                        totals = updatedTotals,
                        dayStatus = updatedStatus,
                        deletingEntryId = null,
                    )
                } else {
                    // A date change starts its own refresh. Do not let a slower
                    // delete response overwrite the newly selected day.
                    _uiState.value = _uiState.value.copy(deletingEntryId = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_uiState.value.selectedDate == selectedDate) {
                    _uiState.value = _uiState.value.copy(
                        deletingEntryId = null,
                        errorMessage = e.message ?: "Couldn't delete this entry",
                    )
                }
            }
        }
    }

    companion object {
        private val VALID_STATUSES = setOf(
            DayStatus.COMPLETE,
            DayStatus.PARTIAL,
            DayStatus.FASTED,
            DayStatus.UNLOGGED,
        )
    }
}
