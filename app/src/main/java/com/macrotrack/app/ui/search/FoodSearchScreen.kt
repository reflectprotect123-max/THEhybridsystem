package com.macrotrack.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FoodSearchScreen(viewModel: FoodSearchViewModel, onResultSelected: (entryKind: String, id: String) -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = uiState.query,
            onValueChange = viewModel::onQueryChanged,
            label = { Text("Search foods, custom foods, recipes") },
            modifier = Modifier.fillMaxWidth(),
        )

        if (uiState.errorMessage != null) {
            Text(text = uiState.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        val listToShow = if (uiState.query.isBlank()) uiState.recent else uiState.results
        val sectionLabel = if (uiState.query.isBlank()) "Recent" else "Results"

        if (uiState.isLoading) {
            CircularProgressIndicator()
        } else {
            Text(sectionLabel, style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(listToShow) { result ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onResultSelected(result.entryKind, result.id) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(result.title, style = MaterialTheme.typography.titleMedium)
                        if (result.subtitle != null) {
                            Text(result.subtitle, style = MaterialTheme.typography.bodyMedium)
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
