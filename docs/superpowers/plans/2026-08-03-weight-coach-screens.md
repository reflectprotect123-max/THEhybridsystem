# Weight + Coach Screens Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the two screens deliberately deferred from `docs/superpowers/plans/2026-08-03-ui-shell-core-loop.md` — a Weight screen (log a weigh-in, see history, see the smoothed trend) and a Coach screen (expenditure estimate + weekly check-in) — and wire them into the app via a bottom navigation bar alongside the existing Daily Log screen.

**Architecture:** Two new screens, each with its own `ViewModel`, calling directly into the already-built and already-reviewed `WeightRepository`, `TrendRepository`, `ExpenditureRepository`, `CheckInRepository` (no new domain logic — everything these screens need already exists). `MacroTrackNavHost` gains a bottom `NavigationBar` with three top-level destinations (Daily Log, Weight, Coach); Food Search and Add Log Entry remain reached only via Daily Log's FAB, not part of the bottom nav.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), `androidx.navigation:navigation-compose` (already a dependency, no version change), no new Gradle dependencies.

## Global Constraints

- **This sandbox has no Android SDK and no emulator, exactly as in the prior UI plan.** Every task's verification step is static code review only — say so explicitly in every task's report, never claim a compile or run that didn't happen.
- Every `ViewModel` repository call runs inside `viewModelScope.launch { }`, wrapped in `try { ... } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ... }` — rethrow `CancellationException` before catching `Exception`, exactly the pattern already used in `DailyLogViewModel`/`AuthViewModel`/`FoodSearchViewModel`/`AddLogEntryViewModel` (this was a real, fixed bug in the prior plan; do not reintroduce the old bare-`catch(Exception)` pattern here).
- Construct every screen's `ViewModel` via `viewModel(factory = viewModelFactory { initializer { ... } })` inside its `composable { }` block in `MacroTrackNavHost.kt` — never a raw constructor call directly in a composable body. Import `viewModelFactory` and `initializer` from **`androidx.lifecycle.viewmodel`** (not `androidx.lifecycle.viewmodel.compose` — that wrong import path was a real Critical bug caught and fixed in the prior plan's final review; `viewModel` itself is imported from `androidx.lifecycle.viewmodel.compose.viewModel`).
- `CheckInRepository.recomputeCheckIn` requires a caller-supplied `targetRateKgPerWeek` (no `macro_programs` source exists — see `docs/WEEKLY_CHECKIN_GAPS.md`'s "no real source for `targetRateKgPerWeek`"). The Coach screen must expose a visible, user-adjustable control for this (a `Slider`, range −1.0..1.0 kg/week, default `0.0` = "maintaining") — never a hardcoded silent default passed straight to the repository without the user seeing/choosing it.
- `CheckInRepository.recomputeCheckIn` throws `IllegalStateException` if the user has never logged a weigh-in (`docs/WEEKLY_CHECKIN_GAPS.md`: "requires at least one weigh-in"). The Coach screen must catch this specific case and render a calm "log a weigh-in first" empty state with a button that navigates to the Weight screen — never let it crash the screen or show as a raw error message.
- `ExpenditureEstimate.state == "holding"` and `PersistedCheckIn.status == "held"` are valid, expected states — render them calmly and informatively (CLAUDE.md: "missing-data holding is a valid state and must be visible in the UI"), never as an error or a blank/zero value.
- Per `docs/WEEKLY_CHECKIN_GAPS.md`'s "`weekStart`/`weekEnd` are row labels only" gap: `recomputeCheckIn`'s actual computation always uses today's data regardless of what week is passed in, so the exact week-boundary convention chosen here only affects which row a check-in is filed under, not the numbers it computes. This plan uses "the most recent Monday through the following Sunday" (device-local) as that row-labelling convention — document this as the deliberate simplification it is, not a precise computation window.
- `WeightRepository.listEntries`/`TrendRepository.recomputeTrend` have no built-in row cap (`docs/WEIGHT_LOGGING_GAPS.md`: "no row limit"). This plan bounds every fetch to a 90-day `since` window (`Instant.now().minus(90, ChronoUnit.DAYS)`) to keep the Weight screen's history/sparkline fetch small and bounded — a deliberate scope choice for this plan, not a fix to the underlying repository gap, which remains open.
- Do not add any new icon-pack dependency or `Icons.Filled.*` constant beyond `Icons.Filled.Add` (already used, and already flagged in the prior plan's docs as an unverified transitive-dependency assumption — see `docs/superpowers/plans/2026-08-03-ui-shell-core-loop.md`'s Task 5 note). The new bottom `NavigationBar`'s `icon` slot in this plan uses plain `Text`, not an `ImageVector`, specifically to avoid inheriting that same unverified-dependency risk a second time.
- Follow existing package conventions: new screens under `app/src/main/java/com/macrotrack/app/ui/weight/` and `app/src/main/java/com/macrotrack/app/ui/coach/`. Every screen gets a real `@Preview` composable with hand-written fake repositories (the pattern already proven working in `DailyLogScreen.kt`'s `PreviewLogRepository`/`PreviewDayStatusRepository`/`PreviewAuthRepository`) — both repository surfaces touched by this plan (`WeightRepository`+`TrendRepository`, `ExpenditureRepository`+`CheckInRepository`) are small enough (3-4 methods each) that a full preview is achievable, unlike Food Search/Add Log Entry's larger 4-5 repository surface which got a documented deferral instead.
- Out of scope: barcode camera, OCR/URL/speech/image adapters, `macro_programs` UI (a real goal-rate source, editing/deleting weigh-ins beyond what already exists on `WeightRepository`), any chart library dependency (the trend visual is a small hand-rolled `Canvas` sparkline, matching the "prefer a simple custom Canvas-based sparkline over pulling in a new dependency" direction from this app's original concept mockup), notifications/reminders to check in.

---

### Task 1: Bottom navigation + forward-referenced routes

**Files:**
- Modify: `app/src/main/java/com/macrotrack/app/ui/nav/Destinations.kt`
- Modify: `app/src/main/java/com/macrotrack/app/ui/nav/MacroTrackNavHost.kt`

**Interfaces:**
- Consumes: `WeightRepository`/`TrendRepository`/`ExpenditureRepository`/`CheckInRepository` (already exist on `AppContainer`, unmodified by this plan). References `WeightScreen`/`WeightViewModel` (Task 2) and `CoachScreen`/`CoachViewModel` (Task 3) by name only — those files don't exist yet when this task is implemented, so this file will not compile stand-alone until Tasks 2-3 land. That's expected, matching exactly how the prior plan's Task 3 forward-referenced Tasks 4-7; note this in the report rather than treating it as a defect.
- Produces: `Destinations.WEIGHT = "weight"`, `Destinations.COACH = "coach"` route constants. `MacroTrackNavHost` gains a bottom `NavigationBar` shown only on the three top-level routes (`DAILY_LOG`, `WEIGHT`, `COACH`), hidden on `FOOD_SEARCH`/`ADD_LOG_ENTRY_PATTERN`.

- [ ] **Step 1: Add the two route constants**

Modify `app/src/main/java/com/macrotrack/app/ui/nav/Destinations.kt` — add two lines inside the `object Destinations` block, alongside the existing three:

```kotlin
package com.macrotrack.app.ui.nav

object Destinations {
    const val AUTH = "auth"
    const val DAILY_LOG = "daily_log"
    const val WEIGHT = "weight"
    const val COACH = "coach"
    const val FOOD_SEARCH = "food_search"

    private const val ADD_LOG_ENTRY_BASE = "add_log_entry"
    const val ADD_LOG_ENTRY_PATTERN = "$ADD_LOG_ENTRY_BASE/{entryKind}/{id}"

    /** `entryKind` is one of `EntryKind.FOOD`/`CUSTOM_FOOD`/`RECIPE` (com.macrotrack.app.data.model.EntryKind). */
    fun addLogEntryRoute(entryKind: String, id: String): String = "$ADD_LOG_ENTRY_BASE/$entryKind/$id"
}
```

- [ ] **Step 2: Rewrite MacroTrackNavHost.kt to add the bottom nav bar and the two new routes**

Replace the full contents of `app/src/main/java/com/macrotrack/app/ui/nav/MacroTrackNavHost.kt`:

```kotlin
package com.macrotrack.app.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.initializer
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.macrotrack.app.data.AppContainer
import com.macrotrack.app.ui.auth.AuthScreen
import com.macrotrack.app.ui.auth.AuthViewModel
import com.macrotrack.app.ui.coach.CoachScreen
import com.macrotrack.app.ui.coach.CoachViewModel
import com.macrotrack.app.ui.dailylog.DailyLogScreen
import com.macrotrack.app.ui.dailylog.DailyLogViewModel
import com.macrotrack.app.ui.search.AddLogEntryScreen
import com.macrotrack.app.ui.search.AddLogEntryViewModel
import com.macrotrack.app.ui.search.FoodSearchScreen
import com.macrotrack.app.ui.search.FoodSearchViewModel
import com.macrotrack.app.ui.weight.WeightScreen
import com.macrotrack.app.ui.weight.WeightViewModel
import io.github.jan.supabase.auth.status.SessionStatus

private data class BottomNavItem(val route: String, val label: String)

private val BOTTOM_NAV_ITEMS = listOf(
    BottomNavItem(Destinations.DAILY_LOG, "Log"),
    BottomNavItem(Destinations.WEIGHT, "Weight"),
    BottomNavItem(Destinations.COACH, "Coach"),
)

@Composable
fun MacroTrackNavHost(appContainer: AppContainer) {
    // First touch of appContainer.authRepository forces SupabaseClientProvider.create(), which
    // performs a synchronous `check(...)` on config values (SUPABASE_URL / SUPABASE_PUBLISHABLE_KEY)
    // and throws if they're missing. That's not a coroutine/suspend call, so this is a plain
    // try/catch, computed once and cached via `remember` -- not the CancellationException-rethrow
    // pattern used for ViewModel coroutine catch blocks.
    val authRepositoryResult = remember { runCatching { appContainer.authRepository } }
    val authRepository = authRepositoryResult.getOrNull()

    if (authRepository == null) {
        val error = authRepositoryResult.exceptionOrNull()
        Surface(modifier = Modifier.fillMaxSize()) {
            Text(
                text = "Configuration error: ${error?.message ?: "Unknown error"}",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        return
    }

    val sessionStatus by authRepository.sessionStatus.collectAsState()

    when (sessionStatus) {
        is SessionStatus.Initializing -> {
            Surface(modifier = Modifier) { CircularProgressIndicator() }
        }
        is SessionStatus.NotAuthenticated, is SessionStatus.RefreshFailure -> {
            val authViewModel: AuthViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { AuthViewModel(authRepository) }
                },
            )
            AuthScreen(viewModel = authViewModel)
        }
        is SessionStatus.Authenticated -> {
            val navController = rememberNavController()
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = backStackEntry?.destination?.route
            val topLevelRoutes = setOf(Destinations.DAILY_LOG, Destinations.WEIGHT, Destinations.COACH)

            Scaffold(
                bottomBar = {
                    if (currentRoute in topLevelRoutes) {
                        NavigationBar {
                            BOTTOM_NAV_ITEMS.forEach { item ->
                                NavigationBarItem(
                                    selected = currentRoute == item.route,
                                    onClick = {
                                        if (currentRoute != item.route) {
                                            navController.navigate(item.route) { launchSingleTop = true }
                                        }
                                    },
                                    // Plain Text, not an Icons.Filled.* ImageVector -- avoids
                                    // depending on material-icons-core/extended a second time
                                    // beyond the one already-flagged, unverified Icons.Filled.Add
                                    // usage on Daily Log's FAB (see the prior plan's Task 5 note).
                                    icon = { Text(item.label.take(1)) },
                                    label = { Text(item.label) },
                                )
                            }
                        }
                    }
                },
            ) { paddingValues ->
                NavHost(
                    navController = navController,
                    startDestination = Destinations.DAILY_LOG,
                    modifier = Modifier.padding(paddingValues),
                ) {
                    composable(Destinations.DAILY_LOG) {
                        val dailyLogViewModel: DailyLogViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    DailyLogViewModel(
                                        logRepository = appContainer.logRepository,
                                        dayStatusRepository = appContainer.dayStatusRepository,
                                        authRepository = authRepository,
                                    )
                                }
                            },
                        )
                        DailyLogScreen(
                            viewModel = dailyLogViewModel,
                            onAddFood = { navController.navigate(Destinations.FOOD_SEARCH) },
                        )
                    }
                    composable(Destinations.WEIGHT) {
                        val weightViewModel: WeightViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    WeightViewModel(
                                        weightRepository = appContainer.weightRepository,
                                        trendRepository = appContainer.trendRepository,
                                    )
                                }
                            },
                        )
                        WeightScreen(viewModel = weightViewModel)
                    }
                    composable(Destinations.COACH) {
                        val coachViewModel: CoachViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    CoachViewModel(
                                        expenditureRepository = appContainer.expenditureRepository,
                                        checkInRepository = appContainer.checkInRepository,
                                    )
                                }
                            },
                        )
                        CoachScreen(
                            viewModel = coachViewModel,
                            onLogWeight = {
                                navController.navigate(Destinations.WEIGHT) { launchSingleTop = true }
                            },
                        )
                    }
                    composable(Destinations.FOOD_SEARCH) {
                        val foodSearchViewModel: FoodSearchViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    FoodSearchViewModel(
                                        appContainer.foodRepository,
                                        appContainer.customFoodRepository,
                                        appContainer.recipeRepository,
                                        appContainer.favoritesRepository,
                                        appContainer.recentFoodRepository,
                                    )
                                }
                            },
                        )
                        FoodSearchScreen(
                            viewModel = foodSearchViewModel,
                            onResultSelected = { entryKind, id ->
                                navController.navigate(Destinations.addLogEntryRoute(entryKind, id))
                            },
                        )
                    }
                    composable(
                        route = Destinations.ADD_LOG_ENTRY_PATTERN,
                        arguments = listOf(
                            navArgument("entryKind") { type = NavType.StringType },
                            navArgument("id") { type = NavType.StringType },
                        ),
                    ) { backStackEntryArgs ->
                        val entryKind = backStackEntryArgs.arguments?.getString("entryKind").orEmpty()
                        val id = backStackEntryArgs.arguments?.getString("id").orEmpty()
                        val addLogEntryViewModel: AddLogEntryViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    AddLogEntryViewModel(
                                        entryKind = entryKind,
                                        id = id,
                                        foodRepository = appContainer.foodRepository,
                                        customFoodRepository = appContainer.customFoodRepository,
                                        recipeRepository = appContainer.recipeRepository,
                                        logRepository = appContainer.logRepository,
                                    )
                                }
                            },
                        )
                        AddLogEntryScreen(
                            viewModel = addLogEntryViewModel,
                            onSaved = { navController.popBackStack(Destinations.DAILY_LOG, inclusive = false) },
                            onCancel = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
```

Note: the inner `backStackEntry` parameter of the `ADD_LOG_ENTRY_PATTERN` composable lambda is renamed to `backStackEntryArgs` here purely to avoid shadowing the outer `val backStackEntry by navController.currentBackStackEntryAsState()` added by this task — this is a cosmetic rename only, the argument-reading logic is byte-for-byte identical to before.

- [ ] **Step 3: Static review (no compile possible in this sandbox)**

Confirm `Destinations.WEIGHT`/`Destinations.COACH` are spelled identically everywhere they're referenced in the rewritten `MacroTrackNavHost.kt`. Confirm the bottom `NavigationBar`'s `onClick`/`selected` logic reads `currentRoute` (from `currentBackStackEntryAsState()`), not some stale captured value. Confirm `WeightViewModel`'s and `CoachViewModel`'s constructor calls here use the exact parameter names Tasks 2 and 3 will produce (`weightRepository`/`trendRepository` and `expenditureRepository`/`checkInRepository` respectively) — this task is written first, so it is the source of truth Tasks 2-3 must match, not the other way around; note this explicitly in the report. Confirm `appContainer.weightRepository`/`appContainer.trendRepository`/`appContainer.expenditureRepository`/`appContainer.checkInRepository` are real property names by reading the actual current `app/src/main/java/com/macrotrack/app/data/AppContainer.kt`. State explicitly that this file cannot compile stand-alone until Tasks 2-3 land (missing `WeightScreen`/`WeightViewModel`/`CoachScreen`/`CoachViewModel`), and that this is expected, not a defect.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/ui/nav/
git commit -m "feat: add bottom navigation with Weight and Coach routes"
```

---

### Task 2: Weight screen

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/ui/weight/WeightViewModel.kt`
- Create: `app/src/main/java/com/macrotrack/app/ui/weight/WeightScreen.kt`

**Interfaces:**
- Consumes: `WeightRepository.listEntries(since: Instant): List<WeightEntry>`, `WeightRepository.logWeight(measuredAt: Instant, weightKg: Double, source: String = "manual", note: String? = null): WeightEntry` (throws `IllegalArgumentException` if `weightKg !in 20.0..500.0`), `WeightRepository.deleteEntry(entryId: String)`, `TrendRepository.recomputeTrend(since: Instant): List<TrendPoint>` (all already exist, `app/src/main/java/com/macrotrack/app/data/`). `WeightEntry` fields: `id`, `measuredAt` (String, ISO instant), `weightKg`. `TrendPoint` fields: `trendDate` (String), `trendWeightKg` (Double).
- Produces: `class WeightViewModel(weightRepository: WeightRepository, trendRepository: TrendRepository) : ViewModel()` with `StateFlow<WeightUiState>` named `uiState`, `fun refresh()`, `fun onWeightInputChanged(text: String)`, `fun logWeight()`, `fun deleteEntry(entryId: String)`. `@Composable fun WeightScreen(viewModel: WeightViewModel)`. Task 1's nav host already constructs this ViewModel and calls this Composable with these exact names — must match.

- [ ] **Step 1: Write the ViewModel**

Create `app/src/main/java/com/macrotrack/app/ui/weight/WeightViewModel.kt`:

```kotlin
package com.macrotrack.app.ui.weight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrotrack.app.data.TrendRepository
import com.macrotrack.app.data.WeightRepository
import com.macrotrack.app.data.model.TrendPoint
import com.macrotrack.app.data.model.WeightEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit

data class WeightUiState(
    val isLoading: Boolean = true,
    /** Ascending-by-time as returned by the repository; the screen reverses this for display. */
    val entries: List<WeightEntry> = emptyList(),
    val trendPoints: List<TrendPoint> = emptyList(),
    val weightInputText: String = "",
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

class WeightViewModel(
    private val weightRepository: WeightRepository,
    private val trendRepository: TrendRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WeightUiState())
    val uiState: StateFlow<WeightUiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val since = Instant.now().minus(HISTORY_WINDOW_DAYS, ChronoUnit.DAYS)
                val entries = weightRepository.listEntries(since)
                // recomputeTrend both persists and returns the recomputed points -- calling it
                // here (rather than a separate listTrendPoints call) is this screen's chosen
                // trigger for the trend recompute, per docs/TREND_VISUALISATION_GAPS.md's "no
                // trigger connects a weigh-in write/delete to a trend recompute" gap, which names
                // "on-demand when a trend screen opens" as one legitimate option.
                val trendPoints = trendRepository.recomputeTrend(since)
                _uiState.value = _uiState.value.copy(isLoading = false, entries = entries, trendPoints = trendPoints)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Couldn't load weight history")
            }
        }
    }

    fun onWeightInputChanged(text: String) {
        _uiState.value = _uiState.value.copy(weightInputText = text)
    }

    fun logWeight() {
        val weightKg = _uiState.value.weightInputText.toDoubleOrNull()
        if (weightKg == null || weightKg <= 0) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enter a weight in kilograms")
            return
        }
        _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            try {
                weightRepository.logWeight(measuredAt = Instant.now(), weightKg = weightKg)
                _uiState.value = _uiState.value.copy(isSaving = false, weightInputText = "")
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Includes WeightRepository.logWeight's own require(weightKg in 20.0..500.0) --
                // its IllegalArgumentException message ("weightKg must be between 20 and 500,
                // got X") is informative enough to surface directly rather than re-wording it.
                _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = e.message ?: "Couldn't log weight")
            }
        }
    }

    fun deleteEntry(entryId: String) {
        viewModelScope.launch {
            try {
                weightRepository.deleteEntry(entryId)
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "Couldn't delete entry")
            }
        }
    }

    companion object {
        private const val HISTORY_WINDOW_DAYS = 90L
    }
}
```

- [ ] **Step 2: Write the screen**

Create `app/src/main/java/com/macrotrack/app/ui/weight/WeightScreen.kt`:

```kotlin
package com.macrotrack.app.ui.weight

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.macrotrack.app.data.TrendRepository
import com.macrotrack.app.data.WeightRepository
import com.macrotrack.app.data.model.TrendPoint
import com.macrotrack.app.data.model.WeightEntry
import com.macrotrack.app.ui.theme.MacroTrackTheme
import java.time.Instant

@Composable
fun WeightScreen(viewModel: WeightViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    // Same resume-based refresh pattern as DailyLogScreen -- this ViewModel is scoped to its
    // NavBackStackEntry (see MacroTrackNavHost), so re-entering this tab after logging weight
    // elsewhere (or after time has passed) needs an explicit trigger, not just init{}.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Weight", style = MaterialTheme.typography.headlineMedium)

        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = uiState.weightInputText,
                onValueChange = viewModel::onWeightInputChanged,
                label = { Text("Weight (kg)") },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = viewModel::logWeight, enabled = !uiState.isSaving) {
                Text(if (uiState.isSaving) "Saving..." else "Log")
            }
        }

        if (uiState.errorMessage != null) {
            Text(text = uiState.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        when {
            uiState.isLoading -> CircularProgressIndicator()
            uiState.entries.isEmpty() -> Text(
                "No weigh-ins yet. Log your first one above.",
                style = MaterialTheme.typography.bodyLarge,
            )
            else -> {
                TrendSection(uiState.trendPoints)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(uiState.entries.asReversed()) { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text("${entry.weightKg} kg", style = MaterialTheme.typography.titleMedium)
                                Text(entry.measuredAt, style = MaterialTheme.typography.bodyMedium)
                            }
                            TextButton(onClick = { viewModel.deleteEntry(entry.id) }) {
                                Text("Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrendSection(trendPoints: List<TrendPoint>) {
    if (trendPoints.size < 2) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Trend", style = MaterialTheme.typography.titleMedium)
        TrendSparkline(trendPoints, modifier = Modifier.fillMaxWidth().height(80.dp))
        val latest = trendPoints.last()
        // ~7 days prior if the fetched window has at least 8 points, else the earliest available.
        val weekAgoIndex = (trendPoints.size - 8).coerceAtLeast(0)
        val weekAgo = trendPoints[weekAgoIndex]
        val delta = latest.trendWeightKg - weekAgo.trendWeightKg
        val direction = if (delta > 0) "up" else if (delta < 0) "down" else "flat"
        Text(
            "${latest.trendWeightKg} kg trend, $direction ${"%.1f".format(kotlin.math.abs(delta))} kg vs ~a week ago",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun TrendSparkline(points: List<TrendPoint>, modifier: Modifier = Modifier) {
    val weights = points.map { it.trendWeightKg }
    val minW = weights.min()
    val maxW = weights.max()
    val range = (maxW - minW).let { if (it > 0.0) it else 1.0 }
    Canvas(modifier = modifier) {
        val stepX = if (points.size > 1) size.width / (points.size - 1) else 0f
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = index * stepX
            val y = size.height - ((point.trendWeightKg - minW) / range * size.height).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = Color(0xFF5B7B6B), style = Stroke(width = 4f))
    }
}

private class PreviewWeightRepository(private val entries: List<WeightEntry>) : WeightRepository {
    override suspend fun listEntries(since: Instant): List<WeightEntry> = entries
    override suspend fun logWeight(measuredAt: Instant, weightKg: Double, source: String, note: String?): WeightEntry =
        error("not used in preview")
    override suspend fun deleteEntry(entryId: String) = Unit
}

private class PreviewTrendRepository(private val points: List<TrendPoint>) : TrendRepository {
    override suspend fun listTrendPoints(since: Instant): List<TrendPoint> = points
    override suspend fun recomputeTrend(since: Instant): List<TrendPoint> = points
}

@Preview(showBackground = true)
@Composable
private fun WeightScreenPreview() {
    val fakeEntries = listOf(
        WeightEntry(id = "1", userId = "preview-user", measuredAt = "2026-08-01T07:00:00Z", weightKg = 82.4, source = "manual", createdAt = "2026-08-01T07:00:00Z"),
        WeightEntry(id = "2", userId = "preview-user", measuredAt = "2026-08-02T07:00:00Z", weightKg = 82.1, source = "manual", createdAt = "2026-08-02T07:00:00Z"),
        WeightEntry(id = "3", userId = "preview-user", measuredAt = "2026-08-03T07:00:00Z", weightKg = 81.9, source = "manual", createdAt = "2026-08-03T07:00:00Z"),
    )
    val fakeTrend = listOf(
        TrendPoint(userId = "preview-user", trendDate = "2026-08-01", trendWeightKg = 82.5, method = "ewma_reference", sourceWindowDays = 14, createdAt = "2026-08-01T07:00:00Z"),
        TrendPoint(userId = "preview-user", trendDate = "2026-08-02", trendWeightKg = 82.3, method = "ewma_reference", sourceWindowDays = 14, createdAt = "2026-08-02T07:00:00Z"),
        TrendPoint(userId = "preview-user", trendDate = "2026-08-03", trendWeightKg = 82.1, method = "ewma_reference", sourceWindowDays = 14, createdAt = "2026-08-03T07:00:00Z"),
    )
    MacroTrackTheme {
        WeightScreen(
            viewModel = WeightViewModel(
                weightRepository = PreviewWeightRepository(fakeEntries),
                trendRepository = PreviewTrendRepository(fakeTrend),
            ),
        )
    }
}
```

Note on `TextAlign` import: it's listed but unused in this draft — remove that import if the implementer doesn't end up using it (it was included defensively in case a centered empty state is added; if left unused, delete it, since this is a static-review-only branch and an unused import is a needless warning, not a functional issue, but should still be cleaned up while writing the file rather than left for review to catch).

- [ ] **Step 3: Static review (no compile possible in this sandbox)**

Confirm `WeightEntry`/`TrendPoint` field names (`id`, `measuredAt`, `weightKg`; `trendDate`, `trendWeightKg`) match `app/src/main/java/com/macrotrack/app/data/model/WeightModels.kt`/`WeightTrendModels.kt` exactly by reading those files directly. Confirm `WeightRepository`/`TrendRepository`'s method signatures used here (`listEntries`, `logWeight`, `deleteEntry`, `recomputeTrend`) match `app/src/main/java/com/macrotrack/app/data/WeightRepository.kt`/`TrendRepository.kt` exactly. Confirm `WeightViewModel`'s constructor and `WeightScreen`'s composable signature match Task 1's nav host construction site exactly (`weightRepository`/`trendRepository` parameter names, `WeightScreen(viewModel: WeightViewModel)` with no other parameters). Remove the unused `TextAlign` import if present. State explicitly that no compile was possible.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/ui/weight/
git commit -m "feat: add WeightScreen with logging, history, and a trend sparkline"
```

---

### Task 3: Coach screen

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/ui/coach/CoachViewModel.kt`
- Create: `app/src/main/java/com/macrotrack/app/ui/coach/CoachScreen.kt`

**Interfaces:**
- Consumes: `ExpenditureRepository.recomputeExpenditure(): ExpenditureEstimate`, `CheckInRepository.getCheckIn(weekStart: LocalDate): PersistedCheckIn?`, `CheckInRepository.recomputeCheckIn(weekStart: LocalDate, weekEnd: LocalDate, targetRateKgPerWeek: Double, proteinGPerKg: Double = ..., fatGPerKg: Double = ...): CheckInResult` (throws `IllegalStateException` if no weigh-in exists), `CheckInRepository.resolve(weekStart: LocalDate, accepted: Boolean): PersistedCheckIn` (all already exist, `app/src/main/java/com/macrotrack/app/data/`). `ExpenditureEstimate` fields: `state`, `confidence`, `estimateKcal: Double?`, `explanation`. `PersistedCheckIn` fields: `status`, `explanation`, `proposedCalories: Double?`, `proposedProteinG: Double?`, `proposedCarbsG: Double?`, `proposedFatG: Double?`.
- Produces: `class CoachViewModel(expenditureRepository: ExpenditureRepository, checkInRepository: CheckInRepository) : ViewModel()` with `StateFlow<CoachUiState>` named `uiState`, `fun refresh()`, `fun onTargetRateChanged(rate: Double)`, `fun checkIn()`, `fun resolve(accepted: Boolean)`. `@Composable fun CoachScreen(viewModel: CoachViewModel, onLogWeight: () -> Unit)`. Task 1's nav host already constructs this ViewModel and calls this Composable with these exact names — must match.

- [ ] **Step 1: Write the ViewModel**

Create `app/src/main/java/com/macrotrack/app/ui/coach/CoachViewModel.kt`:

```kotlin
package com.macrotrack.app.ui.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrotrack.app.data.CheckInRepository
import com.macrotrack.app.data.ExpenditureRepository
import com.macrotrack.app.data.model.PersistedCheckIn
import com.macrotrack.app.domain.ExpenditureEstimate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class CoachUiState(
    val isLoading: Boolean = true,
    val estimate: ExpenditureEstimate? = null,
    /** False only right after a check-in attempt failed for lack of a weigh-in. */
    val hasWeighIn: Boolean = true,
    val targetRateKgPerWeek: Double = 0.0,
    val checkIn: PersistedCheckIn? = null,
    val isCheckingIn: Boolean = false,
    val isResolving: Boolean = false,
    val errorMessage: String? = null,
)

class CoachViewModel(
    private val expenditureRepository: ExpenditureRepository,
    private val checkInRepository: CheckInRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoachUiState())
    val uiState: StateFlow<CoachUiState> = _uiState.asStateFlow()

    /**
     * "This week" = the most recent Monday through the following Sunday, device-local.
     * docs/WEEKLY_CHECKIN_GAPS.md: weekStart/weekEnd are row labels only -- recomputeCheckIn's
     * actual computation always uses today's data regardless of what week is passed in, so this
     * convention only affects which row a check-in is filed under, not the numbers it computes.
     */
    private fun currentWeekStart(): LocalDate =
        LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val estimate = expenditureRepository.recomputeExpenditure()
                val checkIn = checkInRepository.getCheckIn(currentWeekStart())
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    estimate = estimate,
                    checkIn = checkIn,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Couldn't load your coaching status")
            }
        }
    }

    fun onTargetRateChanged(rate: Double) {
        _uiState.value = _uiState.value.copy(targetRateKgPerWeek = rate)
    }

    fun checkIn() {
        _uiState.value = _uiState.value.copy(isCheckingIn = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val weekStart = currentWeekStart()
                checkInRepository.recomputeCheckIn(
                    weekStart = weekStart,
                    weekEnd = weekStart.plusDays(6),
                    targetRateKgPerWeek = _uiState.value.targetRateKgPerWeek,
                )
                val checkIn = checkInRepository.getCheckIn(weekStart)
                _uiState.value = _uiState.value.copy(isCheckingIn = false, hasWeighIn = true, checkIn = checkIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                // recomputeCheckIn requires at least one logged weigh-in -- a real, expected
                // precondition failure (docs/WEEKLY_CHECKIN_GAPS.md), not an error to display.
                _uiState.value = _uiState.value.copy(isCheckingIn = false, hasWeighIn = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isCheckingIn = false, errorMessage = e.message ?: "Couldn't check in")
            }
        }
    }

    fun resolve(accepted: Boolean) {
        _uiState.value = _uiState.value.copy(isResolving = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val checkIn = checkInRepository.resolve(currentWeekStart(), accepted)
                _uiState.value = _uiState.value.copy(isResolving = false, checkIn = checkIn)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isResolving = false, errorMessage = e.message ?: "Couldn't resolve check-in")
            }
        }
    }
}
```

- [ ] **Step 2: Write the screen**

Create `app/src/main/java/com/macrotrack/app/ui/coach/CoachScreen.kt`:

```kotlin
package com.macrotrack.app.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.macrotrack.app.data.CheckInRepository
import com.macrotrack.app.data.ExpenditureRepository
import com.macrotrack.app.data.model.CheckInModuleDto
import com.macrotrack.app.data.model.PersistedCheckIn
import com.macrotrack.app.domain.ExpenditureEstimate
import com.macrotrack.app.ui.theme.MacroTrackTheme
import java.time.LocalDate

@Composable
fun CoachScreen(viewModel: CoachViewModel, onLogWeight: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Coach", style = MaterialTheme.typography.headlineMedium)

        when {
            uiState.isLoading -> CircularProgressIndicator()
            uiState.errorMessage != null -> Text(
                text = uiState.errorMessage.orEmpty(),
                color = MaterialTheme.colorScheme.error,
            )
            else -> {
                ExpenditureSection(uiState.estimate)
                if (!uiState.hasWeighIn) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Log a weigh-in to unlock your weekly check-in.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = onLogWeight) { Text("Log weight") }
                    }
                } else {
                    CheckInSection(uiState, viewModel)
                }
            }
        }
    }
}

@Composable
private fun ExpenditureSection(estimate: ExpenditureEstimate?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Expenditure estimate", style = MaterialTheme.typography.titleLarge)
        when {
            estimate == null -> Text("Not available yet.", style = MaterialTheme.typography.bodyLarge)
            // CLAUDE.md: "missing-data holding is a valid state and must be visible in the UI"
            // -- rendered calmly, never as an error or a zero.
            estimate.state == "holding" -> {
                Text("Still gathering enough data to update your estimate.", style = MaterialTheme.typography.bodyLarge)
                Text(estimate.explanation, style = MaterialTheme.typography.bodyMedium)
            }
            else -> {
                Text("${estimate.estimateKcal?.toInt() ?: "—"} kcal/day", style = MaterialTheme.typography.titleLarge)
                Text("Confidence: ${estimate.confidence}", style = MaterialTheme.typography.bodyMedium)
                Text(estimate.explanation, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun CheckInSection(uiState: CoachUiState, viewModel: CoachViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("This week's check-in", style = MaterialTheme.typography.titleLarge)

        val checkIn = uiState.checkIn
        when {
            checkIn == null -> {
                val rateLabel = when {
                    uiState.targetRateKgPerWeek < 0 -> "losing"
                    uiState.targetRateKgPerWeek > 0 -> "gaining"
                    else -> "maintaining"
                }
                Text(
                    "Goal rate: ${"%.1f".format(uiState.targetRateKgPerWeek)} kg/week ($rateLabel)",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Slider(
                    value = uiState.targetRateKgPerWeek.toFloat(),
                    onValueChange = { viewModel.onTargetRateChanged(it.toDouble()) },
                    valueRange = -1f..1f,
                    steps = 19,
                )
                Button(onClick = viewModel::checkIn, enabled = !uiState.isCheckingIn) {
                    Text(if (uiState.isCheckingIn) "Checking in..." else "Check in")
                }
            }
            checkIn.status == "held" -> {
                Text("Not ready yet -- ${checkIn.explanation}", style = MaterialTheme.typography.bodyLarge)
            }
            checkIn.status == "pending" -> {
                Text(checkIn.explanation, style = MaterialTheme.typography.bodyLarge)
                if (checkIn.proposedCalories != null) {
                    Text(
                        "Proposed: ${checkIn.proposedCalories.toInt()} kcal · " +
                            "${checkIn.proposedProteinG?.toInt() ?: 0}g protein · " +
                            "${checkIn.proposedCarbsG?.toInt() ?: 0}g carbs · " +
                            "${checkIn.proposedFatG?.toInt() ?: 0}g fat",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { viewModel.resolve(true) }, enabled = !uiState.isResolving) {
                        Text("Accept")
                    }
                    OutlinedButton(onClick = { viewModel.resolve(false) }, enabled = !uiState.isResolving) {
                        Text("Decline")
                    }
                }
            }
            else -> {
                // "accepted" or "declined"
                Text("This week: ${checkIn.status}.", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private class PreviewExpenditureRepository(private val estimate: ExpenditureEstimate) : ExpenditureRepository {
    override suspend fun getLatestEstimate() = error("not used in preview")
    override suspend fun recomputeExpenditure(): ExpenditureEstimate = estimate
    override suspend fun loadRecords() = error("not used in preview")
}

private class PreviewCheckInRepository(private val checkIn: PersistedCheckIn?) : CheckInRepository {
    override suspend fun getCheckIn(weekStart: LocalDate): PersistedCheckIn? = checkIn
    override suspend fun recomputeCheckIn(
        weekStart: LocalDate,
        weekEnd: LocalDate,
        targetRateKgPerWeek: Double,
        proteinGPerKg: Double,
        fatGPerKg: Double,
    ) = error("not used in preview")
    override suspend fun resolve(weekStart: LocalDate, accepted: Boolean): PersistedCheckIn = error("not used in preview")
}

@Preview(showBackground = true)
@Composable
private fun CoachScreenPreview() {
    val fakeEstimate = ExpenditureEstimate(
        state = "updating",
        confidence = "medium",
        estimateKcal = 2450.0,
        rawEstimateKcal = 2410.0,
        previousEstimateKcal = 2470.0,
        trendSlopeKgPerWeek = -0.2,
        nutritionDays = 12,
        weightDays = 6,
        windowStart = "2026-07-20",
        windowEnd = "2026-08-03",
        explanation = "Based on the last 14 days of logging and weigh-ins.",
    )
    val fakeCheckIn = PersistedCheckIn(
        id = "preview-checkin",
        userId = "preview-user",
        weekStart = "2026-07-28",
        weekEnd = "2026-08-03",
        status = "pending",
        proposedCalories = 2300.0,
        proposedProteinG = 150.0,
        proposedCarbsG = 220.0,
        proposedFatG = 70.0,
        modules = emptyList<CheckInModuleDto>(),
        explanation = "Your estimate has stabilised -- here's an updated target.",
        createdAt = "2026-08-03T08:00:00Z",
    )
    MacroTrackTheme {
        CoachScreen(
            viewModel = CoachViewModel(
                expenditureRepository = PreviewExpenditureRepository(fakeEstimate),
                checkInRepository = PreviewCheckInRepository(fakeCheckIn),
            ),
            onLogWeight = {},
        )
    }
}
```

- [ ] **Step 3: Static review (no compile possible in this sandbox)**

Confirm `ExpenditureEstimate`/`PersistedCheckIn` field names used here match `app/src/main/java/com/macrotrack/app/domain/AdaptiveEngineModels.kt`/`app/src/main/java/com/macrotrack/app/data/model/CheckInModels.kt` exactly by reading those files directly. Confirm `ExpenditureRepository`/`CheckInRepository`'s method signatures used here match `app/src/main/java/com/macrotrack/app/data/ExpenditureRepository.kt`/`CheckInRepository.kt` exactly, including `recomputeCheckIn`'s parameter names/order and its two defaulted trailing parameters (`proteinGPerKg`/`fatGPerKg`, left at their defaults here since this plan has no protein/fat-preference UI). Confirm the two preview fake classes correctly implement every member of `ExpenditureRepository`/`CheckInRepository` (the interfaces this plan didn't modify) — a fake missing an override is exactly the class of error that would only be caught by a real compiler, so read both interfaces side-by-side against the fakes line by line. Confirm `CoachViewModel`'s constructor and `CoachScreen`'s composable signature match Task 1's nav host construction site exactly (`expenditureRepository`/`checkInRepository` parameter names, `CoachScreen(viewModel, onLogWeight)`). State explicitly that no compile was possible.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/ui/coach/
git commit -m "feat: add CoachScreen with expenditure estimate and weekly check-in"
```

---

## Self-Review

**1. Spec coverage:** Bottom navigation across Daily Log/Weight/Coach with Food Search/Add Log Entry hidden from it (Task 1) ✅. Weight logging + history + trend sparkline (Task 2) ✅. Coach screen with expenditure estimate (holding rendered calmly), user-adjustable `targetRateKgPerWeek`, check-in, accept/decline, and the "no weigh-in yet" empty state (Task 3) ✅. Every task's verification step states no compile was possible in this sandbox ✅. `docs/WEEKLY_CHECKIN_GAPS.md`'s "no real source for `targetRateKgPerWeek`" gap is addressed by this plan's Slider control, not silently defaulted ✅.

**2. Placeholder scan:** No TBD/TODO; every code block is complete Kotlin. Task 2's Step 2 note about the unused `TextAlign` import is a real, actionable instruction (remove if unused), not a placeholder.

**3. Type consistency:** `WeightViewModel(weightRepository, trendRepository)`/`WeightScreen(viewModel)` (Task 2) match Task 1's construction/call site exactly. `CoachViewModel(expenditureRepository, checkInRepository)`/`CoachScreen(viewModel, onLogWeight)` (Task 3) match Task 1's construction/call site exactly. `Destinations.WEIGHT`/`Destinations.COACH` (Task 1) are the only two new route strings introduced anywhere in this plan, used consistently across the nav host, the bottom nav items, and the Coach screen's `onLogWeight` navigation target.
