package com.macroplus.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun FoodSearchScreen(
    viewModel: FoodSearchViewModel,
    onResultSelected: (entryKind: String, id: String) -> Unit,
    onScanBarcode: () -> Unit,
    onCreateCustomFood: () -> Unit,
    onQuickAdd: () -> Unit,
    onCreateRecipe: () -> Unit,
    logDate: LocalDate,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.barcodeResult) {
        uiState.barcodeResult?.let { result ->
            viewModel.clearBarcodeResult()
            onResultSelected(result.entryKind, result.id)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Log for ${if (logDate == LocalDate.now()) "today" else logDate}",
            style = MaterialTheme.typography.titleMedium,
        )
        Button(onClick = onScanBarcode, modifier = Modifier.fillMaxWidth()) {
            Text("Scan barcode")
        }
        Button(onClick = onCreateCustomFood, modifier = Modifier.fillMaxWidth()) {
            Text("Create custom food")
        }
        Button(onClick = onQuickAdd, modifier = Modifier.fillMaxWidth()) {
            Text("Quick add calories and macros")
        }
        Button(onClick = onCreateRecipe, modifier = Modifier.fillMaxWidth()) {
            Text("Create recipe")
        }

        OutlinedTextField(
            value = uiState.query,
            onValueChange = viewModel::onQueryChanged,
            label = { Text("Search foods, custom foods, recipes") },
            modifier = Modifier.fillMaxWidth(),
        )

        if (uiState.errorMessage != null) {
            Text(text = uiState.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        val listToShow = if (uiState.query.isBlank()) {
            (uiState.favoriteResults + uiState.recent)
                .distinctBy { FoodSearchViewModel.favoriteKey(it.entryKind, it.id) }
        } else {
            uiState.results
        }
        val sectionLabel = if (uiState.query.isBlank()) "Favorites and recent" else "Results"

        if (uiState.isLoading) {
            CircularProgressIndicator()
        } else {
            Text(sectionLabel, style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(listToShow) { result ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onResultSelected(result.entryKind, result.id) }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(result.title, style = MaterialTheme.typography.titleMedium)
                            if (result.subtitle != null) {
                                Text(result.subtitle, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        androidx.compose.material3.TextButton(
                            onClick = { viewModel.toggleFavorite(result) },
                            enabled = !viewModel.isFavoriteChanging(result),
                        ) {
                            Text(
                                if (viewModel.isFavorite(result)) "★" else "☆",
                                modifier = Modifier.semantics {
                                    contentDescription = if (viewModel.isFavorite(result)) {
                                        "Remove favorite"
                                    } else {
                                        "Add favorite"
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

// No @Preview here. FoodSearchViewModel takes five repository interfaces
// (FoodRepository, CustomFoodRepository, RecipeRepository, FavoritesRepository,
// RecentFoodRepository) and there's no fake/mock repository infrastructure in this codebase.
// Hand-writing five no-op fakes without a compiler to check them against is a meaningfully
// larger risk of a silently-broken preview than for Auth/Daily Log (one and three repositories,
// respectively, which do have previews). Deferred until either a shared fake-repository layer
// exists or this can be verified on a real machine with Gradle/AGP.
