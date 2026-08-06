package com.macrotrack.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrotrack.app.data.CustomFoodRepository
import com.macrotrack.app.data.FavoritesRepository
import com.macrotrack.app.data.FoodRepository
import com.macrotrack.app.data.RecentFoodRepository
import com.macrotrack.app.data.RecipeRepository
import com.macrotrack.app.data.model.EntryKind
import kotlinx.coroutines.CancellationException
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
    val favoriteResults: List<FoodSearchResult> = emptyList(),
    val favoriteKeys: Set<String> = emptySet(),
    val favoriteChangingKeys: Set<String> = emptySet(),
    val errorMessage: String? = null,
    val barcodeResult: FoodSearchResult? = null,
    /** Set only when a scanned barcode matched neither `foods` nor the user's own
     * custom foods, so the UI can offer creating a custom food from it instead
     * of a dead end. Cleared as soon as a new search/scan starts. */
    val unmatchedBarcode: String? = null,
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
        loadFavorites()
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "Couldn't load recent foods")
            }
        }
    }

    private fun loadFavorites() {
        viewModelScope.launch {
            try {
                val favorites = favoritesRepository.list()
                val keys = favorites.mapTo(mutableSetOf()) { favorite ->
                    favoriteKey(
                        when {
                            favorite.foodId != null -> EntryKind.FOOD
                            favorite.customFoodId != null -> EntryKind.CUSTOM_FOOD
                            else -> EntryKind.RECIPE
                        },
                        favorite.foodId ?: favorite.customFoodId ?: favorite.recipeId.orEmpty(),
                    )
                }
                val results = favorites.mapNotNull { favorite ->
                    try {
                        when {
                            favorite.foodId != null -> foodRepository.getById(favorite.foodId)?.let {
                                FoodSearchResult(EntryKind.FOOD, it.id, it.name, it.brand)
                            }
                            favorite.customFoodId != null -> customFoodRepository.getById(favorite.customFoodId)?.let {
                                FoodSearchResult(EntryKind.CUSTOM_FOOD, it.id, it.name, it.brand)
                            }
                            favorite.recipeId != null -> recipeRepository.getById(favorite.recipeId)?.let {
                                FoodSearchResult(EntryKind.RECIPE, it.id, it.name)
                            }
                            else -> null
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A deleted source record should not make the entire search screen fail.
                        null
                    }
                }
                _uiState.value = _uiState.value.copy(
                    favoriteKeys = keys,
                    favoriteResults = results.distinctBy { favoriteKey(it.entryKind, it.id) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "Couldn't load favorites")
            }
        }
    }

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(query = query, unmatchedBarcode = null)
        searchJob?.cancel()
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) {
            _uiState.value = _uiState.value.copy(results = emptyList(), isLoading = false)
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        searchJob = viewModelScope.launch {
            try {
                val foods = foodRepository.search(cleanQuery).map {
                    FoodSearchResult(EntryKind.FOOD, it.id, it.name, it.brand)
                }
                val customFoods = customFoodRepository.list()
                    .filter { it.name.contains(cleanQuery, ignoreCase = true) }
                    .map { FoodSearchResult(EntryKind.CUSTOM_FOOD, it.id, it.name, it.brand) }
                val recipes = recipeRepository.list()
                    .filter { it.name.contains(cleanQuery, ignoreCase = true) }
                    .map { FoodSearchResult(EntryKind.RECIPE, it.id, it.name) }
                _uiState.value = _uiState.value.copy(isLoading = false, results = foods + customFoods + recipes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Search failed")
            }
        }
    }

    /**
     * Resolves a camera result using the exact barcode repository path.
     *
     * The scanned value is trimmed only for transport whitespace. It is not
     * padded, converted between UPC/EAN forms, or otherwise rewritten: an
     * exact miss must remain an exact miss rather than a guessed match.
     *
     * Checks `foods` first, then the user's own custom foods (a barcode saved
     * on a previously created custom food - see `onNutritionLabelScanned`'s
     * sibling flow in CreateCustomFoodViewModel) before finally reporting a
     * true miss, so a food added once via label OCR is found on every later
     * scan instead of only ever being reachable by manual search.
     */
    fun onBarcodeDetected(barcode: String) {
        val exactBarcode = barcode.trim()
        if (exactBarcode.isEmpty()) return

        searchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            errorMessage = null,
            barcodeResult = null,
            unmatchedBarcode = null,
        )
        searchJob = viewModelScope.launch {
            try {
                val food = foodRepository.findByBarcode(exactBarcode)
                if (food != null) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        barcodeResult = FoodSearchResult(
                            entryKind = EntryKind.FOOD,
                            id = food.id,
                            title = food.name,
                            subtitle = food.brand,
                        ),
                    )
                    return@launch
                }
                val customFood = customFoodRepository.findByBarcode(exactBarcode)
                _uiState.value = if (customFood != null) {
                    _uiState.value.copy(
                        isLoading = false,
                        barcodeResult = FoodSearchResult(
                            entryKind = EntryKind.CUSTOM_FOOD,
                            id = customFood.id,
                            title = customFood.name,
                            subtitle = customFood.brand,
                        ),
                    )
                } else {
                    _uiState.value.copy(
                        isLoading = false,
                        errorMessage = "No food found for barcode $exactBarcode. Search manually, or add it as a custom food.",
                        unmatchedBarcode = exactBarcode,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Barcode lookup failed",
                )
            }
        }
    }

    fun clearBarcodeResult() {
        _uiState.value = _uiState.value.copy(barcodeResult = null)
    }

    fun isFavorite(result: FoodSearchResult): Boolean =
        favoriteKey(result.entryKind, result.id) in _uiState.value.favoriteKeys

    fun isFavoriteChanging(result: FoodSearchResult): Boolean =
        favoriteKey(result.entryKind, result.id) in _uiState.value.favoriteChangingKeys

    fun toggleFavorite(result: FoodSearchResult) {
        val key = favoriteKey(result.entryKind, result.id)
        val state = _uiState.value
        if (key in state.favoriteChangingKeys) return
        val wasFavorite = key in state.favoriteKeys
        _uiState.value = state.copy(
            favoriteChangingKeys = state.favoriteChangingKeys + key,
            errorMessage = null,
        )
        viewModelScope.launch {
            try {
                if (wasFavorite) {
                    when (result.entryKind) {
                        EntryKind.FOOD -> favoritesRepository.removeFood(result.id)
                        EntryKind.CUSTOM_FOOD -> favoritesRepository.removeCustomFood(result.id)
                        EntryKind.RECIPE -> favoritesRepository.removeRecipe(result.id)
                        else -> error("Unknown favorite entry kind: ${result.entryKind}")
                    }
                } else {
                    when (result.entryKind) {
                        EntryKind.FOOD -> favoritesRepository.addFood(result.id)
                        EntryKind.CUSTOM_FOOD -> favoritesRepository.addCustomFood(result.id)
                        EntryKind.RECIPE -> favoritesRepository.addRecipe(result.id)
                        else -> error("Unknown favorite entry kind: ${result.entryKind}")
                    }
                }
                val current = _uiState.value
                _uiState.value = current.copy(
                    favoriteKeys = if (wasFavorite) current.favoriteKeys - key else current.favoriteKeys + key,
                    favoriteResults = if (wasFavorite) {
                        current.favoriteResults.filterNot { favoriteKey(it.entryKind, it.id) == key }
                    } else {
                        listOf(result) + current.favoriteResults.filterNot { favoriteKey(it.entryKind, it.id) == key }
                    },
                    favoriteChangingKeys = current.favoriteChangingKeys - key,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    favoriteChangingKeys = _uiState.value.favoriteChangingKeys - key,
                    errorMessage = e.message ?: "Couldn't update favorite",
                )
            }
        }
    }

    companion object {
        fun favoriteKey(entryKind: String, id: String): String = "$entryKind:$id"
    }
}
