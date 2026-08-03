package com.macrotrack.app.ui.nav

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val sessionStatus by appContainer.authRepository.sessionStatus.collectAsState()

    when (sessionStatus) {
        is SessionStatus.Initializing -> {
            Surface(modifier = Modifier) { CircularProgressIndicator() }
        }
        is SessionStatus.NotAuthenticated, is SessionStatus.RefreshFailure -> {
            AuthScreen(viewModel = AuthViewModel(appContainer.authRepository))
        }
        is SessionStatus.Authenticated -> {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = Destinations.DAILY_LOG) {
                composable(Destinations.DAILY_LOG) {
                    DailyLogScreen(
                        viewModel = DailyLogViewModel(appContainer.logRepository, appContainer.dayStatusRepository),
                        onAddFood = { navController.navigate(Destinations.FOOD_SEARCH) },
                    )
                }
                composable(Destinations.FOOD_SEARCH) {
                    FoodSearchScreen(
                        viewModel = FoodSearchViewModel(
                            appContainer.foodRepository,
                            appContainer.customFoodRepository,
                            appContainer.recipeRepository,
                            appContainer.favoritesRepository,
                            appContainer.recentFoodRepository,
                        ),
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
                    AddLogEntryScreen(
                        viewModel = AddLogEntryViewModel(
                            entryKind = entryKind,
                            id = id,
                            foodRepository = appContainer.foodRepository,
                            customFoodRepository = appContainer.customFoodRepository,
                            recipeRepository = appContainer.recipeRepository,
                            logRepository = appContainer.logRepository,
                        ),
                        onSaved = { navController.popBackStack(Destinations.DAILY_LOG, inclusive = false) },
                        onCancel = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
