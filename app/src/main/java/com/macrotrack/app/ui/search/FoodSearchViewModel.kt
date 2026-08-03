package com.macrotrack.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrotrack.app.data.CustomFoodRepository
import com.macrotrack.app.data.FavoritesRepository
import com.macrotrack.app.data.FoodRepository
import com.macrotrack.app.data.RecentFoodRepository
import com.macrotrack.app.data.RecipeRepository
import com.macrotrack.app.data.model.EntryKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FoodSearchResult(val entryKind: String, val id: String, val title: String, val subtitle: String? = null)

data class FoodSearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: List<FoodSearchResult> = emptyList(),
    val recent: List<FoodSearchResult> = emptyList(),
    val errorMessage: String? = null,
)

class FoodSearchViewModel(
    private val foodRepository: FoodRepository,
    private val customFoodRepository: CustomFoodRepository,
    private val recipeRepository: RecipeRepository,
    private val favoritesRepository: FavoritesRepository,
    private val recentFoodRepository: RecentFoodRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FoodSearchUiState())
    val uiState: StateFlow<FoodSearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        loadRecent()
    }

    private fun loadRecent() {
        viewModelScope.launch {
            try {
                val recent = recentFoodRepository.getRecent().mapNotNull { reference ->
                    when {
                        reference.foodId != null -> FoodSearchResult(EntryKind.FOOD, reference.foodId, reference.displayName)
                        reference.customFoodId != null -> FoodSearchResult(EntryKind.CUSTOM_FOOD, reference.customFoodId, reference.displayName)
                        reference.recipeId != null -> FoodSearchResult(EntryKind.RECIPE, reference.recipeId, reference.displayName)
                        else -> null
                    }
                }
                _uiState.value = _uiState.value.copy(recent = recent)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "Couldn't load recent foods")
            }
        }
    }

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(results = emptyList(), isLoading = false)
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        searchJob = viewModelScope.launch {
            try {
                val foods = foodRepository.search(query).map {
                    FoodSearchResult(EntryKind.FOOD, it.id, it.name, it.brand)
                }
                val customFoods = customFoodRepository.list()
                    .filter { it.name.contains(query, ignoreCase = true) }
                    .map { FoodSearchResult(EntryKind.CUSTOM_FOOD, it.id, it.name, it.brand) }
                val recipes = recipeRepository.list()
                    .filter { it.name.contains(query, ignoreCase = true) }
                    .map { FoodSearchResult(EntryKind.RECIPE, it.id, it.name) }
                _uiState.value = _uiState.value.copy(isLoading = false, results = foods + customFoods + recipes)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Search failed")
            }
        }
    }
}
