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
import androidx.compose.runtime.LaunchedEffect
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
import com.macrotrack.app.data.model.EntryKind
import com.macrotrack.app.ui.auth.AuthScreen
import com.macrotrack.app.ui.auth.AuthViewModel
import com.macrotrack.app.ui.coach.CoachScreen
import com.macrotrack.app.ui.coach.CoachViewModel
import com.macrotrack.app.ui.dailylog.DailyLogScreen
import com.macrotrack.app.ui.dailylog.DailyLogViewModel
import com.macrotrack.app.ui.search.AddLogEntryScreen
import com.macrotrack.app.ui.search.AddLogEntryViewModel
import com.macrotrack.app.ui.search.BarcodeScannerScreen
import com.macrotrack.app.ui.search.FoodSearchScreen
import com.macrotrack.app.ui.search.FoodSearchViewModel
import com.macrotrack.app.ui.search.CreateCustomFoodScreen
import com.macrotrack.app.ui.search.CreateCustomFoodViewModel
import com.macrotrack.app.ui.search.QuickAddScreen
import com.macrotrack.app.ui.search.QuickAddViewModel
import com.macrotrack.app.ui.search.RecipeBuilderScreen
import com.macrotrack.app.ui.search.RecipeBuilderViewModel
import com.macrotrack.app.ui.weight.WeightScreen
import com.macrotrack.app.ui.weight.WeightViewModel
import io.github.jan.supabase.auth.status.SessionStatus
import java.time.LocalDate

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
                                            // Standard bottom-nav semantics: popUpTo the start
                                            // destination with saveState so repeated tab
                                            // switching pops back to each tab's existing stack
                                            // entry (and its ViewModelStore/state) instead of
                                            // pushing an unbounded chain of new entries: without
                                            // this, Daily Log -> Weight -> Daily Log pushes a
                                            // second daily_log entry rather than reusing the
                                            // first, so the back stack grows without bound and
                                            // ON_RESUME-triggered writing recomputes (Weight,
                                            // Coach) re-fire on every fresh entry. Uses the
                                            // Destinations.DAILY_LOG route constant directly
                                            // (not graph.findStartDestination()) -- this project
                                            // pins navigation-compose 2.9.0, well past the
                                            // 2.4.0 baseline this pattern has been stable since.
                                            navController.navigate(item.route) {
                                                popUpTo(Destinations.DAILY_LOG) { saveState = true }
                                                launchSingleTop = true
                                                restoreState = true
                                            }
                                        }
                                    },
                                    // Text initials keep the bottom navigation dependency-light
                                    // and make the destinations readable even without an icon
                                    // pack or generated vector assets.
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
                            onAddFood = { date -> navController.navigate(Destinations.foodSearchRoute(date)) },
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
                                        macroProgramRepository = appContainer.macroProgramRepository,
                                        weightRepository = appContainer.weightRepository,
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
                    composable(
                        route = Destinations.FOOD_SEARCH_PATTERN,
                        arguments = listOf(navArgument("logDate") { type = NavType.StringType }),
                    ) { foodSearchBackStackEntry ->
                        val logDate = LocalDate.parse(
                            foodSearchBackStackEntry.arguments?.getString("logDate").orEmpty(),
                        )
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
                        val scannedBarcode by foodSearchBackStackEntry.savedStateHandle
                            .getStateFlow<String?>(Destinations.SCANNED_BARCODE_KEY, null)
                            .collectAsState()

                        LaunchedEffect(scannedBarcode) {
                            val barcode = scannedBarcode
                            if (!barcode.isNullOrBlank()) {
                                foodSearchViewModel.onBarcodeDetected(barcode)
                                foodSearchBackStackEntry.savedStateHandle[Destinations.SCANNED_BARCODE_KEY] = null
                            }
                        }

                        FoodSearchScreen(
                            viewModel = foodSearchViewModel,
                            onScanBarcode = { navController.navigate(Destinations.BARCODE_SCANNER) },
                            onCreateCustomFood = { navController.navigate(Destinations.createCustomFoodRoute(logDate)) },
                            onQuickAdd = { navController.navigate(Destinations.quickAddRoute(logDate)) },
                            onCreateRecipe = { navController.navigate(Destinations.recipeBuilderRoute(logDate)) },
                            onResultSelected = { entryKind, id ->
                                navController.navigate(Destinations.addLogEntryRoute(entryKind, id, logDate))
                            },
                            logDate = logDate,
                        )
                    }
                    composable(
                        route = Destinations.QUICK_ADD_PATTERN,
                        arguments = listOf(navArgument("logDate") { type = NavType.StringType }),
                    ) { quickAddBackStackEntry ->
                        val logDate = LocalDate.parse(
                            quickAddBackStackEntry.arguments?.getString("logDate").orEmpty(),
                        )
                        val quickAddViewModel: QuickAddViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    QuickAddViewModel(
                                        logRepository = appContainer.logRepository,
                                        logDate = logDate,
                                    )
                                }
                            },
                        )
                        QuickAddScreen(
                            viewModel = quickAddViewModel,
                            logDate = logDate,
                            onSaved = { navController.popBackStack(Destinations.DAILY_LOG, inclusive = false) },
                            onCancel = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = Destinations.CREATE_CUSTOM_FOOD_PATTERN,
                        arguments = listOf(navArgument("logDate") { type = NavType.StringType }),
                    ) { createFoodBackStackEntry ->
                        val logDate = LocalDate.parse(
                            createFoodBackStackEntry.arguments?.getString("logDate").orEmpty(),
                        )
                        val createViewModel: CreateCustomFoodViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    CreateCustomFoodViewModel(
                                        repository = appContainer.customFoodRepository,
                                    )
                                }
                            },
                        )
                        CreateCustomFoodScreen(
                            viewModel = createViewModel,
                            onSaved = { id ->
                                navController.navigate(
                                    Destinations.addLogEntryRoute(EntryKind.CUSTOM_FOOD, id, logDate),
                                ) {
                                    popUpTo(Destinations.createCustomFoodRoute(logDate)) { inclusive = true }
                                }
                            },
                            onCancel = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = Destinations.RECIPE_BUILDER_PATTERN,
                        arguments = listOf(navArgument("logDate") { type = NavType.StringType }),
                    ) { recipeBuilderBackStackEntry ->
                        val logDate = LocalDate.parse(
                            recipeBuilderBackStackEntry.arguments?.getString("logDate").orEmpty(),
                        )
                        val recipeBuilderViewModel: RecipeBuilderViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    RecipeBuilderViewModel(
                                        recipeRepository = appContainer.recipeRepository,
                                        foodRepository = appContainer.foodRepository,
                                        customFoodRepository = appContainer.customFoodRepository,
                                    )
                                }
                            },
                        )
                        RecipeBuilderScreen(
                            viewModel = recipeBuilderViewModel,
                            logDate = logDate,
                            onSaved = { navController.popBackStack(Destinations.foodSearchRoute(logDate), inclusive = false) },
                            onCancel = { navController.popBackStack() },
                        )
                    }
                    composable(Destinations.BARCODE_SCANNER) {
                        BarcodeScannerScreen(
                            onBarcodeDetected = { barcode ->
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set(Destinations.SCANNED_BARCODE_KEY, barcode)
                                navController.popBackStack()
                            },
                            onClose = { navController.popBackStack() },
                        )
                    }
                    composable(
                        route = Destinations.ADD_LOG_ENTRY_PATTERN,
                        arguments = listOf(
                            navArgument("entryKind") { type = NavType.StringType },
                            navArgument("id") { type = NavType.StringType },
                            navArgument("logDate") { type = NavType.StringType },
                        ),
                    ) { backStackEntryArgs ->
                        val entryKind = backStackEntryArgs.arguments?.getString("entryKind").orEmpty()
                        val id = backStackEntryArgs.arguments?.getString("id").orEmpty()
                        val logDate = LocalDate.parse(
                            backStackEntryArgs.arguments?.getString("logDate").orEmpty(),
                        )
                        val addLogEntryViewModel: AddLogEntryViewModel = viewModel(
                            factory = viewModelFactory {
                                initializer {
                                    AddLogEntryViewModel(
                                        entryKind = entryKind,
                                        id = id,
                                        logDate = logDate,
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
