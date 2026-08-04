package com.macrotrack.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import com.macrotrack.app.data.model.Meal
import java.time.LocalDate

private val QUICK_ADD_MEALS = listOf(Meal.BREAKFAST, Meal.LUNCH, Meal.DINNER, Meal.SNACK, Meal.OTHER)

@Composable
fun QuickAddScreen(
    viewModel: QuickAddViewModel,
    logDate: LocalDate,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Quick add", style = MaterialTheme.typography.headlineMedium) }
        item {
            Text(
                "Manual entry for ${if (logDate == LocalDate.now()) "today" else logDate}. No food-source nutrient profile is attached.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item { NumericField("Name", state.name, viewModel::onNameChanged, KeyboardType.Text) }
        item { NumericField("Calories", state.calories, viewModel::onCaloriesChanged) }
        item { NumericField("Protein (g)", state.proteinG, viewModel::onProteinChanged) }
        item { NumericField("Carbohydrate (g)", state.carbsG, viewModel::onCarbsChanged) }
        item { NumericField("Fat (g)", state.fatG, viewModel::onFatChanged) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Meal", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QUICK_ADD_MEALS.forEach { meal ->
                        FilterChip(
                            selected = state.meal == meal,
                            onClick = { viewModel.onMealChanged(meal) },
                            label = { Text(meal.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }
        }
        if (state.errorMessage != null) {
            item { Text(state.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error) }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::save,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.isSaving) CircularProgressIndicator() else Text("Add to log")
                }
                TextButton(
                    onClick = onCancel,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun NumericField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Decimal,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        singleLine = true,
    )
}
