package com.macrotrack.app.ui.dailylog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrotrack.app.data.AuthRepository
import com.macrotrack.app.data.DayStatusRepository
import com.macrotrack.app.data.LogRepository
import com.macrotrack.app.data.model.DailyTotals
import com.macrotrack.app.data.model.FoodLogEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DailyLogUiState(
    val isLoading: Boolean = true,
    val entries: List<FoodLogEntry> = emptyList(),
    /** Null means "nothing logged today" -- never rendered as a zero. */
    val totals: DailyTotals? = null,
    val errorMessage: String? = null,
)

class DailyLogViewModel(
    private val logRepository: LogRepository,
    private val dayStatusRepository: DayStatusRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyLogUiState())
    val uiState: StateFlow<DailyLogUiState> = _uiState.asStateFlow()

    fun refresh() {
        val today = LocalDate.now()
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val entries = logRepository.listEntries(today)
                val totals = logRepository.getDailyTotals(today)
                _uiState.value = DailyLogUiState(isLoading = false, entries = entries, totals = totals)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Couldn't load today's log")
            }
        }
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
}
