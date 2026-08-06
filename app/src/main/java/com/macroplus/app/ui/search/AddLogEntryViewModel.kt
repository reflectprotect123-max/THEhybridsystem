package com.macroplus.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macroplus.app.data.CustomFoodRepository
import com.macroplus.app.data.FoodRepository
import com.macroplus.app.data.LogRepository
import com.macroplus.app.data.RecipeRepository
import com.macroplus.app.data.model.CustomFood
import com.macroplus.app.data.model.EntryKind
import com.macroplus.app.data.model.Food
import com.macroplus.app.data.model.Meal
import com.macroplus.app.data.model.Recipe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AddLogEntryUiState(
    val isLoading: Boolean = true,
    val displayName: String = "",
    val defaultUnit: String = "",
    val quantityText: String = "1",
    val meal: String = Meal.OTHER,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null,
)

class AddLogEntryViewModel(
    private val entryKind: String,
    private val id: String,
    private val logDate: LocalDate,
    private val foodRepository: FoodRepository,
    private val customFoodRepository: CustomFoodRepository,
    private val recipeRepository: RecipeRepository,
    private val logRepository: LogRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddLogEntryUiState())
    val uiState: StateFlow<AddLogEntryUiState> = _uiState.asStateFlow()

    private var loadedFood: Food? = null
    private var loadedCustomFood: CustomFood? = null
    private var loadedRecipe: Recipe? = null

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                when (entryKind) {
                    EntryKind.FOOD -> {
                        val food = foodRepository.getById(id) ?: error("Food not found")
                        loadedFood = food
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            displayName = food.name,
                            defaultUnit = food.servingUnit,
                            quantityText = food.servingQty.toString(),
                        )
                    }
                    EntryKind.CUSTOM_FOOD -> {
                        val customFood = customFoodRepository.getById(id) ?: error("Custom food not found")
                        loadedCustomFood = customFood
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            displayName = customFood.name,
                            defaultUnit = customFood.servingUnit,
                            quantityText = customFood.servingQty.toString(),
                        )
                    }
                    EntryKind.RECIPE -> {
                        val recipe = recipeRepository.getById(id) ?: error("Recipe not found")
                        loadedRecipe = recipe
                        _uiState.value = _uiState.value.copy(
                            isLoading = false, displayName = recipe.name, defaultUnit = "serving",
                        )
                    }
                    else -> error("Unknown entryKind: $entryKind")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Couldn't load this item")
            }
        }
    }

    fun onQuantityChanged(quantity: String) {
        _uiState.value = _uiState.value.copy(quantityText = quantity)
    }

    fun onMealChanged(meal: String) {
        _uiState.value = _uiState.value.copy(meal = meal)
    }

    fun save() {
        val quantity = _uiState.value.quantityText.toDoubleOrNull()
        if (quantity == null || !quantity.isFinite() || quantity <= 0) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enter a quantity greater than 0")
            return
        }
        _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val meal = _uiState.value.meal
                when {
                    loadedFood != null -> logRepository.logFood(logDate, loadedFood!!, quantity, _uiState.value.defaultUnit, meal)
                    loadedCustomFood != null -> logRepository.logCustomFood(logDate, loadedCustomFood!!, quantity, _uiState.value.defaultUnit, meal)
                    loadedRecipe != null -> logRepository.logRecipeServings(logDate, loadedRecipe!!.id, quantity, meal)
                    else -> error("Nothing loaded to save")
                }
                _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = e.message ?: "Couldn't save this entry")
            }
        }
    }
}
