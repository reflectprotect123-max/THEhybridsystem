package com.macroplus.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroplus.app.data.LogRepository
import com.macroplus.app.data.model.Meal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class QuickAddUiState(
    val name: String = "",
    val calories: String = "",
    val proteinG: String = "",
    val carbsG: String = "",
    val fatG: String = "",
    val meal: String = Meal.OTHER,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null,
)

class QuickAddViewModel(
    private val logRepository: LogRepository,
    private val logDate: LocalDate,
) : ViewModel() {

    private val _uiState = MutableStateFlow(QuickAddUiState())
    val uiState: StateFlow<QuickAddUiState> = _uiState.asStateFlow()

    fun onNameChanged(value: String) = update { copy(name = value, errorMessage = null) }
    fun onCaloriesChanged(value: String) = update { copy(calories = value, errorMessage = null) }
    fun onProteinChanged(value: String) = update { copy(proteinG = value, errorMessage = null) }
    fun onCarbsChanged(value: String) = update { copy(carbsG = value, errorMessage = null) }
    fun onFatChanged(value: String) = update { copy(fatG = value, errorMessage = null) }
    fun onMealChanged(value: String) = update { copy(meal = value, errorMessage = null) }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return
        val name = state.name.trim()
        val calories = state.calories.toDoubleOrNull()
        val protein = state.proteinG.toDoubleOrNull()
        val carbs = state.carbsG.toDoubleOrNull()
        val fat = state.fatG.toDoubleOrNull()
        if (name.isBlank()) return showError("Enter a name for this entry")
        if (listOf(calories, protein, carbs, fat).any { it == null || !it.isFinite() || it < 0 }) {
            return showError("Enter valid non-negative nutrition values")
        }

        _uiState.value = state.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            try {
                logRepository.logQuickAdd(
                    date = logDate,
                    displayName = name,
                    calories = calories ?: error("Calories missing"),
                    proteinG = protein ?: error("Protein missing"),
                    carbsG = carbs ?: error("Carbohydrates missing"),
                    fatG = fat ?: error("Fat missing"),
                    meal = state.meal,
                )
                _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = e.message ?: "Couldn't add this entry",
                )
            }
        }
    }

    private fun showError(message: String) {
        _uiState.value = _uiState.value.copy(errorMessage = message)
    }

    private fun update(transform: QuickAddUiState.() -> QuickAddUiState) {
        _uiState.value = transform(_uiState.value)
    }
}
