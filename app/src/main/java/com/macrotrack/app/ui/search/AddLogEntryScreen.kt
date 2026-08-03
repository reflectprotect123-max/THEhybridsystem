package com.macrotrack.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.macrotrack.app.data.model.Meal

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
