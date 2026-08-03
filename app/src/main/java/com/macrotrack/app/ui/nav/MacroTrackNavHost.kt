package com.macrotrack.app.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.macrotrack.app.data.AppContainer
import com.macrotrack.app.ui.auth.AuthScreen
import com.macrotrack.app.ui.auth.AuthViewModel
import com.macrotrack.app.ui.dailylog.DailyLogScreen
import com.macrotrack.app.ui.dailylog.DailyLogViewModel
import com.macrotrack.app.ui.search.AddLogEntryScreen
import com.macrotrack.app.ui.search.AddLogEntryViewModel
import com.macrotrack.app.ui.search.FoodSearchScreen
import com.macrotrack.app.ui.search.FoodSearchViewModel
import io.github.jan.supabase.auth.status.SessionStatus

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
            NavHost(navController = navController, startDestination = Destinations.DAILY_LOG) {
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
                ) { backStackEntry ->
                    val entryKind = backStackEntry.arguments?.getString("entryKind").orEmpty()
                    val id = backStackEntry.arguments?.getString("id").orEmpty()
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
