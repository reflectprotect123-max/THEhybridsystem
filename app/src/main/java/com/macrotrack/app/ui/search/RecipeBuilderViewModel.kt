package com.macrotrack.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrotrack.app.data.CustomFoodRepository
import com.macrotrack.app.data.FoodRepository
import com.macrotrack.app.data.RecipeRepository
import com.macrotrack.app.data.model.EntryKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RecipeIngredientDraft(
    val entryKind: String,
    val id: String,
    val name: String,
    val quantityText: String,
    val unit: String,
)

data class RecipeBuilderUiState(
    val name: String = "",
    val servings: String = "1",
    val query: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<FoodSearchResult> = emptyList(),
    val ingredients: List<RecipeIngredientDraft> = emptyList(),
    val addingIngredientKeys: Set<String> = emptySet(),
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null,
)

class RecipeBuilderViewModel(
    private val recipeRepository: RecipeRepository,
    private val foodRepository: FoodRepository,
    private val customFoodRepository: CustomFoodRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecipeBuilderUiState())
    val uiState: StateFlow<RecipeBuilderUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onNameChanged(value: String) = update { copy(name = value, errorMessage = null) }
    fun onServingsChanged(value: String) = update { copy(servings = value, errorMessage = null) }

    fun onQueryChanged(value: String) {
        _uiState.value = _uiState.value.copy(query = value, errorMessage = null)
        searchJob?.cancel()
        if (value.trim().isBlank()) {
            _uiState.value = _uiState.value.copy(isSearching = false, searchResults = emptyList())
            return
        }
        _uiState.value = _uiState.value.copy(isSearching = true, searchResults = emptyList())
        searchJob = viewModelScope.launch {
            try {
                val foods = foodRepository.search(value).map {
                    FoodSearchResult(EntryKind.FOOD, it.id, it.name, it.brand)
                }
                val customFoods = customFoodRepository.list()
                    .filter { it.name.contains(value.trim(), ignoreCase = true) }
                    .map { FoodSearchResult(EntryKind.CUSTOM_FOOD, it.id, it.name, it.brand) }
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    searchResults = (foods + customFoods).distinctBy { "${it.entryKind}:${it.id}" },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSearching = false,
                    errorMessage = e.message ?: "Ingredient search failed",
                )
            }
        }
    }

    fun addIngredient(result: FoodSearchResult) {
        val key = "${result.entryKind}:${result.id}"
        val current = _uiState.value
        if (key in current.addingIngredientKeys || current.ingredients.any { it.entryKind == result.entryKind && it.id == result.id }) {
            _uiState.value = _uiState.value.copy(errorMessage = "That ingredient is already in the recipe.")
            return
        }
        _uiState.value = current.copy(addingIngredientKeys = current.addingIngredientKeys + key)
        viewModelScope.launch {
            try {
                val draft = when (result.entryKind) {
                    EntryKind.FOOD -> {
                        val food = foodRepository.getById(result.id) ?: error("Food not found")
                        RecipeIngredientDraft(
                            entryKind = EntryKind.FOOD,
                            id = food.id,
                            name = food.name,
                            quantityText = food.servingQty.toString(),
                            unit = food.servingUnit,
                        )
                    }
                    EntryKind.CUSTOM_FOOD -> {
                        val food = customFoodRepository.getById(result.id) ?: error("Custom food not found")
                        RecipeIngredientDraft(
                            entryKind = EntryKind.CUSTOM_FOOD,
                            id = food.id,
                            name = food.name,
                            quantityText = food.servingQty.toString(),
                            unit = food.servingUnit,
                        )
                    }
                    else -> error("Only foods and custom foods can be recipe ingredients")
                }
                _uiState.value = _uiState.value.copy(
                    ingredients = _uiState.value.ingredients + draft,
                    query = "",
                    searchResults = emptyList(),
                    addingIngredientKeys = _uiState.value.addingIngredientKeys - key,
                    errorMessage = null,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    addingIngredientKeys = _uiState.value.addingIngredientKeys - key,
                    errorMessage = e.message ?: "Couldn't add ingredient",
                )
            }
        }
    }

    fun onIngredientQuantityChanged(index: Int, value: String) {
        val ingredients = _uiState.value.ingredients.toMutableList()
        val current = ingredients.getOrNull(index) ?: return
        ingredients[index] = current.copy(quantityText = value)
        _uiState.value = _uiState.value.copy(ingredients = ingredients, errorMessage = null)
    }

    fun removeIngredient(index: Int) {
        val ingredients = _uiState.value.ingredients.toMutableList()
        if (index !in ingredients.indices) return
        ingredients.removeAt(index)
        _uiState.value = _uiState.value.copy(ingredients = ingredients, errorMessage = null)
    }

    fun save() {
        val state = _uiState.value
        if (state.isSaving) return
        val name = state.name.trim()
        val servings = state.servings.toDoubleOrNull()
        if (name.isBlank()) return showError("Enter a recipe name")
        if (servings == null || !servings.isFinite() || servings <= 0) {
            return showError("Recipe servings must be greater than 0")
        }
        if (state.ingredients.isEmpty()) return showError("Add at least one ingredient")
        val quantities = state.ingredients.map { it.quantityText.toDoubleOrNull() }
        if (quantities.any { it == null || !it.isFinite() || it <= 0 }) {
            return showError("Every ingredient needs a quantity greater than 0")
        }

        _uiState.value = state.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            var createdRecipeId: String? = null
            try {
                val recipe = recipeRepository.create(
                    name = name,
                    description = null,
                    instructions = null,
                    servings = servings,
                )
                createdRecipeId = recipe.id
                state.ingredients.forEachIndexed { index, ingredient ->
                    recipeRepository.addItem(
                        recipeId = recipe.id,
                        foodId = ingredient.id.takeIf { ingredient.entryKind == EntryKind.FOOD },
                        customFoodId = ingredient.id.takeIf { ingredient.entryKind == EntryKind.CUSTOM_FOOD },
                        quantity = quantities[index] ?: error("Ingredient quantity missing"),
                        unit = ingredient.unit,
                        sortOrder = index,
                    )
                }
                _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort cleanup prevents a failed ingredient insert from leaving a
                // visible empty recipe. If the cleanup also fails, the original error remains
                // actionable and the user can remove the partial recipe later.
                createdRecipeId?.let { recipeId ->
                    try {
                        recipeRepository.delete(recipeId)
                    } catch (cleanup: CancellationException) {
                        throw cleanup
                    } catch (_: Exception) {
                        // Preserve the original failure for the user.
                    }
                }
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = e.message ?: "Couldn't save recipe",
                )
            }
        }
    }

    private fun showError(message: String) {
        _uiState.value = _uiState.value.copy(errorMessage = message)
    }

    private fun update(transform: RecipeBuilderUiState.() -> RecipeBuilderUiState) {
        _uiState.value = transform(_uiState.value)
    }
}
