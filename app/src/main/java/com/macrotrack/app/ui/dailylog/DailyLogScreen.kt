package com.macrotrack.app.ui.dailylog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.macrotrack.app.data.AuthRepository
import com.macrotrack.app.data.DayStatusRepository
import com.macrotrack.app.data.LogRepository
import com.macrotrack.app.data.model.CustomFood
import com.macrotrack.app.data.model.DailyLogStatus
import com.macrotrack.app.data.model.DailyTotals
import com.macrotrack.app.data.model.Food
import com.macrotrack.app.data.model.FoodLogEntry
import com.macrotrack.app.ui.theme.MacroTrackTheme
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

@Composable
fun DailyLogScreen(viewModel: DailyLogViewModel, onAddFood: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    // DailyLogViewModel is now scoped to its NavBackStackEntry (see MacroTrackNavHost), so its
    // init{} block only runs once for the lifetime of that backstack entry -- it will NOT re-run
    // when the user navigates back here from Add Log Entry after saving. Re-fetch on every
    // ON_RESUME (first entry to the screen *and* every subsequent return to it) so today's totals
    // reflect entries logged elsewhere.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAddFood) {
                Icon(Icons.Filled.Add, contentDescription = "Add food")
            }
        },
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Today", style = MaterialTheme.typography.headlineMedium)
                TextButton(onClick = viewModel::signOut) {
                    Text("Sign out")
                }
            }

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

/**
 * Hand-written no-op fakes used only by [DailyLogScreenPreview]. `LogRepository` and
 * `DayStatusRepository` are small/self-contained enough to fake by hand without a compiler to
 * check against -- unlike Food Search / Add Log Entry's five-repository surface, which don't get
 * a full-screen preview (see the comment in those files) to keep this lightweight.
 */
private class PreviewLogRepository(private val entries: List<FoodLogEntry>, private val totals: DailyTotals?) : LogRepository {
    override suspend fun listEntries(date: LocalDate): List<FoodLogEntry> = entries
    override suspend fun getDailyTotals(date: LocalDate): DailyTotals? = totals
    override suspend fun listDailyTotals(since: LocalDate): List<DailyTotals> = emptyList()
    override suspend fun logFood(date: LocalDate, food: Food, quantity: Double, unit: String, meal: String, notes: String?): FoodLogEntry =
        error("not used in preview")
    override suspend fun logCustomFood(date: LocalDate, customFood: CustomFood, quantity: Double, unit: String, meal: String, notes: String?): FoodLogEntry =
        error("not used in preview")
    override suspend fun logRecipeServings(date: LocalDate, recipeId: String, loggedServings: Double, meal: String, notes: String?): FoodLogEntry =
        error("not used in preview")
    override suspend fun logQuickAdd(date: LocalDate, displayName: String, calories: Double, proteinG: Double, carbsG: Double, fatG: Double, meal: String, notes: String?): FoodLogEntry =
        error("not used in preview")
    override suspend fun deleteEntry(entryId: String) = Unit
}

private class PreviewDayStatusRepository : DayStatusRepository {
    override suspend fun getStatus(date: LocalDate): DailyLogStatus? = null
    override suspend fun setStatus(date: LocalDate, status: String, note: String?): DailyLogStatus =
        error("not used in preview")
    override suspend fun listStatuses(since: LocalDate): List<DailyLogStatus> = emptyList()
}

private class PreviewAuthRepository : AuthRepository {
    override val sessionStatus: StateFlow<SessionStatus> = MutableStateFlow(SessionStatus.NotAuthenticated())
    override suspend fun signUp(email: String, password: String) = Unit
    override suspend fun signIn(email: String, password: String) = Unit
    override suspend fun signOut() = Unit
}

@Preview(showBackground = true)
@Composable
private fun DailyLogScreenPreview() {
    val fakeEntries = listOf(
        FoodLogEntry(
            id = "preview-1",
            userId = "preview-user",
            logDate = "2026-08-03",
            meal = "breakfast",
            entryKind = "food",
            foodId = "preview-food",
            quantity = 1.0,
            unit = "serving",
            calories = 320.0,
            proteinG = 20.0,
            carbsG = 30.0,
            fatG = 10.0,
            displayName = "Greek yoghurt with berries",
        ),
    )
    val fakeTotals = DailyTotals(
        userId = "preview-user",
        logDate = "2026-08-03",
        calories = 320.0,
        proteinG = 20.0,
        carbsG = 30.0,
        fatG = 10.0,
        entryCount = 1,
    )
    MacroTrackTheme {
        DailyLogScreen(
            viewModel = DailyLogViewModel(
                logRepository = PreviewLogRepository(fakeEntries, fakeTotals),
                dayStatusRepository = PreviewDayStatusRepository(),
                authRepository = PreviewAuthRepository(),
            ),
            onAddFood = {},
        )
    }
}
