package com.macrotrack.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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

@Composable
fun CreateCustomFoodScreen(
    viewModel: CreateCustomFoodViewModel,
    onSaved: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.createdCustomFoodId) {
        val createdId = state.createdCustomFoodId
        if (createdId != null) onSaved(createdId)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Create custom food", style = MaterialTheme.typography.headlineMedium) }
        item {
            OutlinedTextField(
                value = state.name,
                onValueChange = viewModel::onNameChanged,
                label = { Text("Food name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = state.brand,
                onValueChange = viewModel::onBrandChanged,
                label = { Text("Brand (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            // Hint-only placeholder, never a pre-filled value: the serving
            // denominator has to come from the user, not from MacroTrack.
            OutlinedTextField(
                value = state.servingQty,
                onValueChange = viewModel::onServingQtyChanged,
                label = { Text("Serving quantity") },
                placeholder = { Text("e.g. 43") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
            )
        }
        item {
            OutlinedTextField(
                value = state.servingUnit,
                onValueChange = viewModel::onServingUnitChanged,
                label = { Text("Serving unit (g, ml, serving)") },
                placeholder = { Text("e.g. g") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item { Text("Nutrition for that serving", style = MaterialTheme.typography.titleMedium) }
        item { NutritionField("Calories", state.calories, viewModel::onCaloriesChanged) }
        item { NutritionField("Protein (g)", state.proteinG, viewModel::onProteinChanged) }
        item { NutritionField("Carbohydrate (g)", state.carbsG, viewModel::onCarbsChanged) }
        item { NutritionField("Fat (g)", state.fatG, viewModel::onFatChanged) }
        item {
            Text(
                "Enter the serving size and the values from the label or another source you trust. " +
                    "MacroTrack will preserve them as provided and never fills in a serving size for you.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.errorMessage != null) {
            item { Text(state.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error) }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::save, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
                    if (state.isSaving) CircularProgressIndicator() else Text("Create and log")
                }
                TextButton(onClick = onCancel, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun NutritionField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
    )
}
