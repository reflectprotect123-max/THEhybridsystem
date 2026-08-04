package com.macrotrack.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrotrack.app.data.CustomFoodRepository
import com.macrotrack.app.domain.ParsedNutritionLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CreateCustomFoodUiState(
    val name: String = "",
    val brand: String = "",
    /**
     * Serving quantity/unit start blank on purpose. They are part of the
     * nutrition denominator, so pre-filling a submittable "100"/"g" would let
     * an untouched field become a serving weight MacroTrack invented rather
     * than one the user provided (CLAUDE.md rule #1). The screen shows the
     * expected shape as a Compose label/placeholder instead.
     */
    val servingQty: String = "",
    val servingUnit: String = "",
    val calories: String = "",
    val proteinG: String = "",
    val carbsG: String = "",
    val fatG: String = "",
    val isSaving: Boolean = false,
    /** Id of the custom food this screen just created; null until a save succeeds. */
    val createdCustomFoodId: String? = null,
    val errorMessage: String? = null,
)

class CreateCustomFoodViewModel(
    private val repository: CustomFoodRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateCustomFoodUiState())
    val uiState: StateFlow<CreateCustomFoodUiState> = _uiState.asStateFlow()

    fun onNameChanged(value: String) = update { copy(name = value, errorMessage = null) }
    fun onBrandChanged(value: String) = update { copy(brand = value, errorMessage = null) }
    fun onServingQtyChanged(value: String) = update { copy(servingQty = value, errorMessage = null) }
    fun onServingUnitChanged(value: String) = update { copy(servingUnit = value, errorMessage = null) }
    fun onCaloriesChanged(value: String) = update { copy(calories = value, errorMessage = null) }
    fun onProteinChanged(value: String) = update { copy(proteinG = value, errorMessage = null) }
    fun onCarbsChanged(value: String) = update { copy(carbsG = value, errorMessage = null) }
    fun onFatChanged(value: String) = update { copy(fatG = value, errorMessage = null) }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return
        val name = state.name.trim()
        val unit = state.servingUnit.trim()
        val servingQtyText = state.servingQty.trim()
        val servingQty = servingQtyText.toDoubleOrNull()
        val calories = state.calories.toDoubleOrNull()
        val protein = state.proteinG.toDoubleOrNull()
        val carbs = state.carbsG.toDoubleOrNull()
        val fat = state.fatG.toDoubleOrNull()
        val values = listOf(servingQty, calories, protein, carbs, fat)
        if (name.isBlank()) return showError("Enter a food name")
        // Serving quantity and unit are required in the same way the macro
        // fields are: MacroTrack never supplies a default denominator.
        if (servingQtyText.isBlank()) return showError("Enter the serving quantity these values are for")
        if (unit.isBlank()) return showError("Enter a serving unit, such as g or ml")
        if (servingQty == null || !servingQty.isFinite() || servingQty <= 0) {
            return showError("Serving quantity must be greater than 0")
        }
        if (values.drop(1).any { it == null || !it.isFinite() || it < 0 }) {
            return showError("Enter valid non-negative nutrition values")
        }
        val servingQtyValue = servingQty ?: return showError("Serving quantity must be greater than 0")
        val caloriesValue = calories ?: return showError("Enter valid non-negative nutrition values")
        val proteinValue = protein ?: return showError("Enter valid non-negative nutrition values")
        val carbsValue = carbs ?: return showError("Enter valid non-negative nutrition values")
        val fatValue = fat ?: return showError("Enter valid non-negative nutrition values")

        _uiState.value = state.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val created = repository.create(
                    name = name,
                    brand = state.brand.trim().ifBlank { null },
                    servingQty = servingQtyValue,
                    servingUnit = unit,
                    calories = caloriesValue,
                    proteinG = proteinValue,
                    carbsG = carbsValue,
                    fatG = fatValue,
                )
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    createdCustomFoodId = created.id,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = e.message ?: "Couldn't create custom food",
                )
            }
        }
    }

    /**
     * Pre-fills only the fields the parser was confident about, and only if
     * the user hasn't already typed something into that field - a scan never
     * overwrites a value the user already entered, matching the "never
     * silently overwrite" rule this app applies everywhere OCR/import
     * touches user-facing data.
     */
    fun onNutritionLabelScanned(result: ParsedNutritionLabel) {
        _uiState.value = _uiState.value.let { state ->
            state.copy(
                calories = state.calories.ifBlank { result.calories?.toString() ?: "" },
                proteinG = state.proteinG.ifBlank { result.proteinG?.toString() ?: "" },
                carbsG = state.carbsG.ifBlank { result.carbsG?.toString() ?: "" },
                fatG = state.fatG.ifBlank { result.fatG?.toString() ?: "" },
                servingQty = state.servingQty.ifBlank { result.servingQty?.toString() ?: "" },
                servingUnit = state.servingUnit.ifBlank { result.servingUnit ?: "" },
                errorMessage = null,
            )
        }
    }

    private fun showError(message: String) {
        _uiState.value = _uiState.value.copy(errorMessage = message)
    }

    private fun update(transform: CreateCustomFoodUiState.() -> CreateCustomFoodUiState) {
        _uiState.value = transform(_uiState.value)
    }
}
