package com.macrotrack.app.ui.dailylog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DailyLogScreen(viewModel: DailyLogViewModel, onAddFood: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddFood) {
                Icon(Icons.Filled.Add, contentDescription = "Add food")
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            Text("Today", style = MaterialTheme.typography.headlineMedium)

            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
                uiState.errorMessage != null -> Text(
                    text = uiState.errorMessage.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 16.dp),
                )
                uiState.totals == null -> Text(
                    text = "Nothing logged yet today.",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                else -> {
                    val totals = uiState.totals!!
                    Column(modifier = Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${totals.calories.toInt()} kcal", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${totals.proteinG.toInt()}g protein · ${totals.carbsG.toInt()}g carbs · ${totals.fatG.toInt()}g fat",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(uiState.entries) { entry ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text(entry.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${entry.meal} · ${entry.calories.toInt()} kcal",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
