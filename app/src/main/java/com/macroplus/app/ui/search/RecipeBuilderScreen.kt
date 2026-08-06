package com.macroplus.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import java.time.LocalDate

@Composable
fun RecipeBuilderScreen(
    viewModel: RecipeBuilderViewModel,
    logDate: LocalDate,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Create recipe", style = MaterialTheme.typography.headlineMedium)
        Text("The recipe will be available when logging ${if (logDate == LocalDate.now()) "today" else logDate}.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = state.name,
            onValueChange = viewModel::onNameChanged,
            label = { Text("Recipe name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.servings,
            onValueChange = viewModel::onServingsChanged,
            label = { Text("Number of servings") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
        )
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChanged,
            label = { Text("Search an ingredient") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        if (state.errorMessage != null) {
            Text(state.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (state.isSearching) {
                item { CircularProgressIndicator() }
            } else if (state.searchResults.isNotEmpty()) {
                item { Text("Ingredient results", style = MaterialTheme.typography.titleMedium) }
                items(state.searchResults) { result ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.addIngredient(result) }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(result.title, style = MaterialTheme.typography.bodyLarge)
                            result.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        Text("Add", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            item { Text("Ingredients", style = MaterialTheme.typography.titleMedium) }
            if (state.ingredients.isEmpty()) {
                item { Text("Search above and tap an ingredient to add it.", style = MaterialTheme.typography.bodyMedium) }
            } else {
                itemsIndexed(state.ingredients) { index, ingredient ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(ingredient.name, style = MaterialTheme.typography.bodyLarge)
                            Text(ingredient.unit, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            value = ingredient.quantityText,
                            onValueChange = { viewModel.onIngredientQuantityChanged(index, it) },
                            label = { Text("Qty") },
                            modifier = Modifier.width(104.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                        TextButton(onClick = { viewModel.removeIngredient(index) }) { Text("Remove") }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::save, enabled = !state.isSaving) {
                if (state.isSaving) CircularProgressIndicator() else Text("Save recipe")
            }
            TextButton(onClick = onCancel, enabled = !state.isSaving) { Text("Cancel") }
        }
    }
}
