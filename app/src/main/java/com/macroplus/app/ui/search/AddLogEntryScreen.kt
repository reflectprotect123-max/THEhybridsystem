package com.macroplus.app.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.macroplus.app.data.model.Meal

private val MEAL_OPTIONS = listOf(Meal.BREAKFAST, Meal.LUNCH, Meal.DINNER, Meal.SNACK, Meal.OTHER)

@Composable
fun AddLogEntryScreen(viewModel: AddLogEntryViewModel, onSaved: () -> Unit, onCancel: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onSaved()
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (uiState.isLoading) {
            CircularProgressIndicator()
            return@Column
        }

        Text(uiState.displayName, style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = uiState.quantityText,
            onValueChange = viewModel::onQuantityChanged,
            label = { Text("Quantity (${uiState.defaultUnit})") },
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Meal", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MEAL_OPTIONS.forEach { meal ->
                FilterChip(
                    selected = uiState.meal == meal,
                    onClick = { viewModel.onMealChanged(meal) },
                    label = { Text(meal.replaceFirstChar { it.uppercase() }) },
                )
            }
        }

        if (uiState.errorMessage != null) {
            Text(text = uiState.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::save, enabled = !uiState.isSaving) {
                Text(if (uiState.isSaving) "Saving..." else "Add to log")
            }
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}

// No @Preview here. AddLogEntryViewModel takes four repository interfaces (FoodRepository,
// CustomFoodRepository, RecipeRepository, LogRepository) plus entryKind/id constructor args, and
// there's no fake/mock repository infrastructure in this codebase. Hand-writing four no-op fakes
// (LogRepository alone has eight methods) without a compiler to check them against is a
// meaningfully larger risk of a silently-broken preview than for Auth/Daily Log (one and three
// repositories, respectively, which do have previews). Deferred until either a shared
// fake-repository layer exists or this can be verified on a real machine with Gradle/AGP.
