package com.macroplus.app.ui.dailylog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.macroplus.app.data.AuthRepository
import com.macroplus.app.data.DayStatusRepository
import com.macroplus.app.data.LogRepository
import com.macroplus.app.data.model.CustomFood
import com.macroplus.app.data.model.DailyLogStatus
import com.macroplus.app.data.model.DailyTotals
import com.macroplus.app.data.model.DayStatus
import com.macroplus.app.data.model.Food
import com.macroplus.app.data.model.FoodLogEntry
import com.macroplus.app.ui.theme.MacroPlusTheme
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

@Composable
fun DailyLogScreen(viewModel: DailyLogViewModel, onAddFood: (LocalDate) -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    // DailyLogViewModel is now scoped to its NavBackStackEntry (see MacroPlusNavHost), so its
    // init{} block only runs once for the lifetime of that backstack entry -- it will NOT re-run
    // when the user navigates back here from Add Log Entry after saving. Re-fetch on every
    // ON_RESUME (first entry to the screen *and* every subsequent return to it) so the selected
    // day's totals reflect entries logged elsewhere.
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
            FloatingActionButton(onClick = { onAddFood(uiState.selectedDate) }) {
                Text(
                    "+",
                    modifier = Modifier.semantics { contentDescription = "Add food" },
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    if (uiState.selectedDate == LocalDate.now()) "Today" else uiState.selectedDate.toString(),
                    style = MaterialTheme.typography.headlineMedium,
                )
                TextButton(onClick = viewModel::signOut) {
                    Text("Sign out")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                TextButton(onClick = { viewModel.moveDate(-1) }) { Text("Previous") }
                TextButton(
                    onClick = viewModel::goToToday,
                    enabled = uiState.selectedDate != LocalDate.now(),
                ) { Text("Today") }
                // Forward navigation stops at today: the adaptive engine only looks back from
                // today, so food logged against a future date would be invisible to it.
                TextButton(
                    onClick = { viewModel.moveDate(1) },
                    enabled = uiState.selectedDate.isBefore(LocalDate.now()),
                ) { Text("Next") }
            }

            when {
                uiState.isLoading -> CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp))
                uiState.loadFailed -> {
                    // The load for the selected date failed, so this day's entries, totals and
                    // status are *unknown* -- not empty. Render only the error (plus a retry) so
                    // nothing can be mistaken for real data belonging to this date, and so the
                    // day-status controls cannot act on data we do not have.
                    Text(
                        text = uiState.errorMessage ?: "Couldn't load this day's log",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    TextButton(onClick = { viewModel.refresh() }) { Text("Retry") }
                }
                else -> {
                    if (uiState.errorMessage != null) {
                        Text(
                            text = uiState.errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    DayStatusSection(uiState, viewModel)
                    if (uiState.totals == null) {
                        Text(
                            text = "Nothing logged yet for this day.",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    } else {
                        val totals = uiState.totals!!
                        Column(
                            modifier = Modifier.padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text("${totals.calories.toInt()} kcal", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "${totals.proteinG.toInt()}g protein · ${totals.carbsG.toInt()}g carbs · ${totals.fatG.toInt()}g fat",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(uiState.entries) { entry ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(entry.displayName, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "${entry.meal} · ${entry.calories.toInt()} kcal",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                    }
                                    TextButton(
                                        onClick = { viewModel.deleteEntry(entry.id) },
                                        enabled = uiState.deletingEntryId == null,
                                    ) {
                                        Text(if (uiState.deletingEntryId == entry.id) "Deleting…" else "Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayStatusSection(uiState: DailyLogUiState, viewModel: DailyLogViewModel) {
    val savedStatus = uiState.dayStatus?.status
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Day status", style = MaterialTheme.typography.titleMedium)
        Text(
            when (savedStatus) {
                DayStatus.COMPLETE -> "Complete — this day can count toward coaching data."
                DayStatus.PARTIAL -> "Partial — more food may still be missing."
                DayStatus.FASTED -> "Fasted — explicitly declared, so it is not treated as an unlogged day."
                DayStatus.UNLOGGED -> "Unlogged — this day will not count toward coaching data."
                else -> "Choose a status so coaching can distinguish missing data from a completed day."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                DayStatus.COMPLETE to "Complete",
                DayStatus.PARTIAL to "Partial",
                DayStatus.FASTED to "Fasted",
            ).forEach { (status, label) ->
                FilterChip(
                    selected = savedStatus == status,
                    onClick = { viewModel.setDayStatus(status) },
                    enabled = !uiState.isSavingStatus,
                    label = { Text(label) },
                )
            }
        }
        TextButton(
            onClick = { viewModel.setDayStatus(DayStatus.UNLOGGED) },
            enabled = !uiState.isSavingStatus && savedStatus != null && savedStatus != DayStatus.UNLOGGED,
        ) {
            Text(if (uiState.isSavingStatus) "Saving…" else "Clear status")
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
    MacroPlusTheme {
        DailyLogScreen(
            viewModel = DailyLogViewModel(
                logRepository = PreviewLogRepository(fakeEntries, fakeTotals),
                dayStatusRepository = PreviewDayStatusRepository(),
                authRepository = PreviewAuthRepository(),
            ),
            onAddFood = { _ -> },
        )
    }
}
