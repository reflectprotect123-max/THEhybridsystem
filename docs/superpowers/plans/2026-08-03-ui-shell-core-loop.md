# UI Shell + Core Logging Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the first real Jetpack Compose UI for MacroTrack — theme, navigation, auth, and the core daily-logging loop (search a food, add it to today's log, see today's totals) — wired to the repository layer already built and reviewed across six prior data-layer slices.

**Architecture:** A `ui/theme` package for Material3 styling, a small `AuthRepository` added to the data layer, a top-level `NavHost` that gates between an auth graph and a main app graph based on `AuthRepository.sessionStatus`, and three screens (Daily Log, Food Search, Add Log Entry) each with a `ViewModel` that calls the existing repositories directly (no new domain logic — everything the screens need was already built in prior slices).

**Tech Stack:** Kotlin, Jetpack Compose (Material3), `androidx.navigation:navigation-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose` (already a dependency), `io.github.jan-tennert.supabase` auth-kt/postgrest-kt 3.7.0.

## Global Constraints

- **This sandbox has no Android SDK and no emulator.** Unlike prior backend slices — some of which could be compiled and run via a bare Kotlin compiler extracted from the Gradle distribution against hand-written stubs — Compose UI cannot be compiled here at all: the Compose compiler plugin and Android platform APIs require the real Android Gradle Plugin toolchain, and this sandbox has no network access to Google's Maven to resolve AGP. **Every task's verification step is static code review only.** Say so explicitly in every task's report — never claim a compile or run that didn't happen. Whoever runs this on a real machine must build and manually exercise every screen before trusting it.
- Scope decision made explicitly here: this plan covers the app shell (theme, navigation, auth) plus the core daily-logging loop only (Food Search → Add Log Entry → Daily Log). Weight logging, trend display, expenditure/coaching, and the weekly check-in screen are **out of scope for this plan** — they're already-built repositories with no UI yet, and are a natural follow-up plan once this core loop is in place. Not a gap to silently paper over; a deliberate sequencing choice given the size of the full UI surface.
- `client.auth.signUpWith(Email) { email = e; password = p }` returns `UserInfo?`; `client.auth.signInWith(Email) { email = e; password = p }` returns `Unit`. Both throw on failure (e.g. `AuthRestException`, `AuthWeakPasswordException`). Both need `import io.github.jan.supabase.auth.auth` and `import io.github.jan.supabase.auth.providers.builtin.Email`. Verified against the real auth-kt 3.7.0 sources.
- `client.auth.sessionStatus: StateFlow<SessionStatus>` — `SessionStatus` is a sealed interface: `Initializing`, `NotAuthenticated(isSignOut: Boolean = false)`, `RefreshFailure(cause)`, `Authenticated(session: UserSession, source: SessionSource = SessionSource.Unknown)`. `Initializing` means still loading (show a splash/loading state); `NotAuthenticated`/`RefreshFailure` means show the auth screen; `Authenticated` means show the main app. Verified against the real auth-kt 3.7.0 sources.
- `client.auth.signOut(scope: SignOutScope = SignOutScope.LOCAL)` is `suspend`.
- Never render a day with no logged entries as zero calories — `LogRepository.getDailyTotals(date)` already returns `null` for that case (an already-verified, already-tested repository behavior); the UI must show an explicit "nothing logged yet" empty state, never a `DailyTotals`-shaped zero.
- Every repository call from a `ViewModel` runs inside `viewModelScope.launch { }` and wraps the call in `try`/`catch`, surfacing failures as a UI-visible error string — never let an uncaught exception crash the screen. This matters especially here because none of these repositories have ever been exercised against a real backend in this session; every network call is a first real use.
- Follow existing repository/model naming and package conventions exactly: `app/src/main/java/com/macrotrack/app/data/` for repositories, `app/src/main/java/com/macrotrack/app/data/model/` for `@Serializable` data classes, `app/src/main/java/com/macrotrack/app/domain/` for pure logic (already has `ServingScaler`, `Scalable`, `MacroResolution` — reuse these, don't re-derive serving-scaling math). New UI code lives under `app/src/main/java/com/macrotrack/app/ui/`.
- `AppContainer` wires every repository via `by lazy` off one shared `SupabaseClient`; `AuthRepository` joins that list the same way.
- Out of scope: barcode camera, OCR/URL/speech/image adapters, `macro_programs` UI, weight/trend/coach/check-in screens (see scope decision above), app icon/branding, Play Store packaging.

---

### Task 1: Gradle dependency + theme package

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/macrotrack/app/ui/theme/Color.kt`
- Create: `app/src/main/java/com/macrotrack/app/ui/theme/Type.kt`
- Create: `app/src/main/java/com/macrotrack/app/ui/theme/Theme.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: `@Composable fun MacroTrackTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)` in package `com.macrotrack.app.ui.theme`. Every later screen task wraps its `@Preview`/root content in this.

- [ ] **Step 1: Add the navigation-compose dependency**

In `app/build.gradle.kts`, inside the `dependencies { }` block, add one line after `implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")`:

```kotlin
    implementation("androidx.navigation:navigation-compose:2.9.0")
```

Note for whoever builds this on a real machine: this sandbox cannot resolve Gradle dependencies (no Maven access), so this version could not be verified to actually exist/resolve against the current Compose BOM. Check `androidx.navigation`'s latest stable 2.9.x release before building and bump if needed — this is a version number, not an API-shape risk (Navigation-Compose's `NavHost`/`composable`/`rememberNavController` surface used in this plan has been stable across the 2.x line for years).

- [ ] **Step 2: Write the color palette**

Create `app/src/main/java/com/macrotrack/app/ui/theme/Color.kt`:

```kotlin
package com.macrotrack.app.ui.theme

import androidx.compose.ui.graphics.Color

// Calm, trustworthy, food/health-appropriate -- deliberately not vibrant or
// gamified, matching CLAUDE.md's "calm explainable coaching experience".
val SageGreen = Color(0xFF5B7B6B)
val SageGreenLight = Color(0xFFDCE8E0)
val SageGreenDark = Color(0xFF3F5A4C)
val WarmSand = Color(0xFFF4EFE6)
val WarmSandDark = Color(0xFF1E1C18)
val Charcoal = Color(0xFF2B2B28)
val Cream = Color(0xFFFFFBF4)
val SoftAmber = Color(0xFFC98A3B)
val MutedRed = Color(0xFFB5544A)
val NeutralGray = Color(0xFF8A8A82)
```

- [ ] **Step 3: Write the typography scale**

Create `app/src/main/java/com/macrotrack/app/ui/theme/Type.kt`:

```kotlin
package com.macrotrack.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val MacroTrackTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
)
```

- [ ] **Step 4: Write the theme composable**

Create `app/src/main/java/com/macrotrack/app/ui/theme/Theme.kt`:

```kotlin
package com.macrotrack.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = SageGreen,
    onPrimary = Cream,
    primaryContainer = SageGreenLight,
    onPrimaryContainer = SageGreenDark,
    secondary = SoftAmber,
    background = WarmSand,
    onBackground = Charcoal,
    surface = Cream,
    onSurface = Charcoal,
    error = MutedRed,
    outline = NeutralGray,
)

private val DarkColors = darkColorScheme(
    primary = SageGreenLight,
    onPrimary = SageGreenDark,
    primaryContainer = SageGreenDark,
    onPrimaryContainer = SageGreenLight,
    secondary = SoftAmber,
    background = WarmSandDark,
    onBackground = Cream,
    surface = Charcoal,
    onSurface = Cream,
    error = MutedRed,
    outline = NeutralGray,
)

@Composable
fun MacroTrackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MacroTrackTypography,
        content = content,
    )
}
```

- [ ] **Step 5: Static review (no compile possible in this sandbox)**

Read all three new files back and confirm: every `Color`/`TextStyle`/`ColorScheme` constructor argument name is spelled correctly against the real Compose Material3 API (these are extremely stable, long-established APIs — cross-check against any existing Compose file in the project, or your own knowledge of `androidx.compose.material3.lightColorScheme`'s parameter names, since this sandbox cannot resolve the dependency to check directly). State explicitly in your report that no `./gradlew` compile was attempted or possible.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/com/macrotrack/app/ui/theme/
git commit -m "feat: add navigation-compose dependency and MacroTrackTheme"
```

---

### Task 2: AuthRepository

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/data/AuthRepository.kt`
- Modify: `app/src/main/java/com/macrotrack/app/data/AppContainer.kt`

**Interfaces:**
- Consumes: `SupabaseClientProvider` (already exists).
- Produces: `AuthRepository` interface with `val sessionStatus: StateFlow<SessionStatus>`, `suspend fun signUp(email: String, password: String)`, `suspend fun signIn(email: String, password: String)`, `suspend fun signOut()`; and `SupabaseAuthRepository(client: SupabaseClient) : AuthRepository`. Task 3 (nav) reads `sessionStatus`; Task 4 (auth screen) calls `signUp`/`signIn`.

- [ ] **Step 1: Write the implementation**

Create `app/src/main/java/com/macrotrack/app/data/AuthRepository.kt`:

```kotlin
package com.macrotrack.app.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.StateFlow

interface AuthRepository {
    val sessionStatus: StateFlow<SessionStatus>
    suspend fun signUp(email: String, password: String)
    suspend fun signIn(email: String, password: String)
    suspend fun signOut()
}

class SupabaseAuthRepository(private val client: SupabaseClient) : AuthRepository {

    override val sessionStatus: StateFlow<SessionStatus>
        get() = client.auth.sessionStatus

    override suspend fun signUp(email: String, password: String) {
        client.auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    override suspend fun signOut() {
        client.auth.signOut()
    }
}
```

- [ ] **Step 2: Wire into AppContainer**

In `app/src/main/java/com/macrotrack/app/data/AppContainer.kt`, add one line after `private val client by lazy { ... }`:

```kotlin
    val authRepository: AuthRepository by lazy { SupabaseAuthRepository(client) }
```

- [ ] **Step 3: Static review**

Confirm `Email.Config`'s two mutable properties are exactly named `email`/`password` (per the real auth-kt 3.7.0 source: `data class Config(var email: String = "", var password: String = "", var data: JsonObject? = null)`) — the lambda in `signUpWith`/`signInWith` above sets `this.email`/`this.password` against that config type. State explicitly that no compile was possible.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/data/AuthRepository.kt app/src/main/java/com/macrotrack/app/data/AppContainer.kt
git commit -m "feat: add AuthRepository wrapping Supabase email/password auth"
```

---

### Task 3: Navigation graph + MainActivity

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/ui/nav/Destinations.kt`
- Create: `app/src/main/java/com/macrotrack/app/ui/nav/MacroTrackNavHost.kt`
- Modify: `app/src/main/java/com/macrotrack/app/MainActivity.kt`

**Interfaces:**
- Consumes: `AuthRepository.sessionStatus` (Task 2), `MacroTrackTheme` (Task 1). References `AuthScreen`/`AuthViewModel` (Task 4), `DailyLogScreen`/`DailyLogViewModel` (Task 5), `FoodSearchScreen`/`FoodSearchViewModel` (Task 6), `AddLogEntryScreen`/`AddLogEntryViewModel` (Task 7) by name only — those files don't exist yet when this task is implemented, so this task's code will not compile stand-alone until Tasks 4-7 land. That's expected and consistent with `MacroTrackNavHost` being the last file wired together; note this explicitly in the task report rather than treating it as a defect.
- Produces: `object Destinations` with route constants `AUTH`, `DAILY_LOG`, `FOOD_SEARCH`, and a route-building function `addLogEntryRoute(entryKind: String, id: String)` plus the pattern `ADD_LOG_ENTRY_PATTERN`. `@Composable fun MacroTrackNavHost(appContainer: AppContainer)`.

- [ ] **Step 1: Write the destinations**

Create `app/src/main/java/com/macrotrack/app/ui/nav/Destinations.kt`:

```kotlin
package com.macrotrack.app.ui.nav

object Destinations {
    const val AUTH = "auth"
    const val DAILY_LOG = "daily_log"
    const val FOOD_SEARCH = "food_search"

    private const val ADD_LOG_ENTRY_BASE = "add_log_entry"
    const val ADD_LOG_ENTRY_PATTERN = "$ADD_LOG_ENTRY_BASE/{entryKind}/{id}"

    /** `entryKind` is one of `EntryKind.FOOD`/`CUSTOM_FOOD`/`RECIPE` (com.macrotrack.app.data.model.EntryKind). */
    fun addLogEntryRoute(entryKind: String, id: String): String = "$ADD_LOG_ENTRY_BASE/$entryKind/$id"
}
```

- [ ] **Step 2: Write the nav host**

Create `app/src/main/java/com/macrotrack/app/ui/nav/MacroTrackNavHost.kt`:

```kotlin
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
```

- [ ] **Step 3: Rewrite MainActivity**

Replace the full contents of `app/src/main/java/com/macrotrack/app/MainActivity.kt`:

```kotlin
package com.macrotrack.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.macrotrack.app.data.AppContainer
import com.macrotrack.app.ui.nav.MacroTrackNavHost
import com.macrotrack.app.ui.theme.MacroTrackTheme

class MainActivity : ComponentActivity() {
    private val appContainer = AppContainer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MacroTrackTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MacroTrackNavHost(appContainer)
                }
            }
        }
    }
}
```

- [ ] **Step 4: Static review**

This task's two new files reference `AuthScreen`/`AuthViewModel`/`DailyLogScreen`/`DailyLogViewModel`/`FoodSearchScreen`/`FoodSearchViewModel`/`AddLogEntryScreen`/`AddLogEntryViewModel`, none of which exist until Tasks 4-7 land — record in your report that this task's file set cannot compile stand-alone and that is expected, not a defect in this task. Confirm the `AppContainer` property names used (`authRepository`, `logRepository`, `dayStatusRepository`, `foodRepository`, `customFoodRepository`, `recipeRepository`, `favoritesRepository`, `recentFoodRepository`) match the actual current `AppContainer.kt` file exactly. Confirm `SessionStatus`'s four subtypes are matched exhaustively in the `when` (no `else` branch needed if all four are covered — `Initializing`, `NotAuthenticated`, `RefreshFailure`, `Authenticated`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/ui/nav/ app/src/main/java/com/macrotrack/app/MainActivity.kt
git commit -m "feat: add navigation graph and auth-gated MainActivity"
```

---

### Task 4: Auth screen

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/ui/auth/AuthViewModel.kt`
- Create: `app/src/main/java/com/macrotrack/app/ui/auth/AuthScreen.kt`

**Interfaces:**
- Consumes: `AuthRepository` (Task 2).
- Produces: `class AuthViewModel(authRepository: AuthRepository) : ViewModel()` with a `StateFlow<AuthUiState>` named `uiState`, and functions `fun signIn(email: String, password: String)`, `fun signUp(email: String, password: String)`. `@Composable fun AuthScreen(viewModel: AuthViewModel)`. Task 3's nav host already constructs `AuthViewModel(appContainer.authRepository)` directly — this task's constructor signature must match that call exactly.

- [ ] **Step 1: Write the ViewModel**

Create `app/src/main/java/com/macrotrack/app/ui/auth/AuthViewModel.kt`:

```kotlin
package com.macrotrack.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrotrack.app.data.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthUiState(
    val isSubmitting: Boolean = false,
    val errorMessage: String? = null,
)

class AuthViewModel(private val authRepository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    fun signIn(email: String, password: String) {
        _uiState.value = AuthUiState(isSubmitting = true)
        viewModelScope.launch {
            try {
                authRepository.signIn(email, password)
                _uiState.value = AuthUiState(isSubmitting = false)
            } catch (e: Exception) {
                _uiState.value = AuthUiState(isSubmitting = false, errorMessage = e.message ?: "Sign in failed")
            }
        }
    }

    fun signUp(email: String, password: String) {
        _uiState.value = AuthUiState(isSubmitting = true)
        viewModelScope.launch {
            try {
                authRepository.signUp(email, password)
                _uiState.value = AuthUiState(isSubmitting = false)
            } catch (e: Exception) {
                _uiState.value = AuthUiState(isSubmitting = false, errorMessage = e.message ?: "Sign up failed")
            }
        }
    }
}
```

- [ ] **Step 2: Write the screen**

Create `app/src/main/java/com/macrotrack/app/ui/auth/AuthScreen.kt`:

```kotlin
package com.macrotrack.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AuthScreen(viewModel: AuthViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isSignUpMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = if (isSignUpMode) "Create your account" else "Welcome back",
            style = MaterialTheme.typography.headlineMedium,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (uiState.errorMessage != null) {
            Text(text = uiState.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                if (isSignUpMode) viewModel.signUp(email, password) else viewModel.signIn(email, password)
            },
            enabled = !uiState.isSubmitting && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isSubmitting) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp))
            } else {
                Text(if (isSignUpMode) "Sign up" else "Sign in")
            }
        }
        TextButton(onClick = { isSignUpMode = !isSignUpMode }) {
            Text(if (isSignUpMode) "Already have an account? Sign in" else "New here? Create an account")
        }
    }
}
```

- [ ] **Step 3: Static review**

Confirm `AuthViewModel`'s constructor signature (`AuthViewModel(authRepository: AuthRepository)`) matches exactly how Task 3's nav host constructs it (`AuthViewModel(appContainer.authRepository)`). Confirm `viewModel.uiState` and `viewModel.signIn`/`viewModel.signUp` are called with matching names/argument order in `AuthScreen`. State explicitly that no compile was possible in this sandbox.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/ui/auth/
git commit -m "feat: add AuthScreen with sign-in/sign-up toggle"
```

---

### Task 5: Daily Log screen

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/ui/dailylog/DailyLogViewModel.kt`
- Create: `app/src/main/java/com/macrotrack/app/ui/dailylog/DailyLogScreen.kt`

**Interfaces:**
- Consumes: `LogRepository.listEntries(date: LocalDate): List<FoodLogEntry>`, `LogRepository.getDailyTotals(date: LocalDate): DailyTotals?` (nullable — never a zero-fill for an unlogged day), `DayStatusRepository.getStatus(date: LocalDate): DailyLogStatus?` (already exist, `app/src/main/java/com/macrotrack/app/data/`). `FoodLogEntry` fields: `id`, `meal`, `displayName`, `calories`, `proteinG`, `carbsG`, `fatG`, `quantity`, `unit`. `DailyTotals` fields: `calories`, `proteinG`, `carbsG`, `fatG`, `entryCount`.
- Produces: `class DailyLogViewModel(logRepository: LogRepository, dayStatusRepository: DayStatusRepository) : ViewModel()` with `StateFlow<DailyLogUiState>` named `uiState`, `fun refresh()`. `@Composable fun DailyLogScreen(viewModel: DailyLogViewModel, onAddFood: () -> Unit)`. Task 3's nav host already constructs this ViewModel and calls this Composable with these exact parameter names — must match.

- [ ] **Step 1: Write the ViewModel**

Create `app/src/main/java/com/macrotrack/app/ui/dailylog/DailyLogViewModel.kt`:

```kotlin
package com.macrotrack.app.ui.dailylog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrotrack.app.data.DayStatusRepository
import com.macrotrack.app.data.LogRepository
import com.macrotrack.app.data.model.DailyTotals
import com.macrotrack.app.data.model.FoodLogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class DailyLogUiState(
    val isLoading: Boolean = true,
    val entries: List<FoodLogEntry> = emptyList(),
    /** Null means "nothing logged today" -- never rendered as a zero. */
    val totals: DailyTotals? = null,
    val errorMessage: String? = null,
)

class DailyLogViewModel(
    private val logRepository: LogRepository,
    private val dayStatusRepository: DayStatusRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DailyLogUiState())
    val uiState: StateFlow<DailyLogUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val today = LocalDate.now()
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val entries = logRepository.listEntries(today)
                val totals = logRepository.getDailyTotals(today)
                _uiState.value = DailyLogUiState(isLoading = false, entries = entries, totals = totals)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Couldn't load today's log")
            }
        }
    }
}
```

Note: `dayStatusRepository` is accepted as a constructor parameter (matching Task 3's nav host call site) but not yet read from in this task's UI — it's wired in now so a future day-status affordance (mark today complete/partial/fasted) can be added without changing the constructor signature every other file depends on. This is deliberate forward-wiring of an interface already decided by Task 3, not speculative scope creep within this task's own logic.

- [ ] **Step 2: Write the screen**

Create `app/src/main/java/com/macrotrack/app/ui/dailylog/DailyLogScreen.kt`:

```kotlin
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
```

Requires adding `implementation("androidx.compose.material:material-icons-extended")` or using `androidx.compose.material.icons.Icons.Filled.Add` from the core icons set already bundled with `androidx.compose.material3:material3` (`Icons.Filled.Add` is in the base `material-icons-core` artifact, which `material3` already depends on transitively — no new dependency needed for this specific icon).

- [ ] **Step 3: Static review**

Confirm `FoodLogEntry`'s actual field names (`displayName`, `meal`, `calories`, etc.) match `app/src/main/java/com/macrotrack/app/data/model/LogEntryModels.kt` exactly. Confirm `DailyTotals`'s fields match the same file. State explicitly that no compile was possible.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/ui/dailylog/
git commit -m "feat: add DailyLogScreen showing today's entries and totals"
```

---

### Task 6: Food Search screen

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/ui/search/FoodSearchViewModel.kt`
- Create: `app/src/main/java/com/macrotrack/app/ui/search/FoodSearchScreen.kt`

**Interfaces:**
- Consumes: `FoodRepository.search(query: String, limit: Int = 20): List<Food>`, `CustomFoodRepository.list(): List<CustomFood>`, `RecipeRepository.list(): List<Recipe>`, `FavoritesRepository.list(): List<FoodFavorite>`, `RecentFoodRepository.getRecent(limit: Int = 10): List<RecentLogReference>` (all already exist). `Food`/`CustomFood` fields: `id`, `name`, `brand`. `Recipe` fields: `id`, `name`. `RecentLogReference` fields: `foodId`, `customFoodId`, `recipeId`, `displayName` (`app/src/main/java/com/macrotrack/app/domain/` — check the actual field names in `RecentLogReference` before writing this task's code, it's a domain type produced by `dedupeRecentReferences`, not one of the model files read for this plan).
- Produces: `data class FoodSearchResult(val entryKind: String, val id: String, val title: String, val subtitle: String?)` and `class FoodSearchViewModel(foodRepository: FoodRepository, customFoodRepository: CustomFoodRepository, recipeRepository: RecipeRepository, favoritesRepository: FavoritesRepository, recentFoodRepository: RecentFoodRepository) : ViewModel()` with `StateFlow<FoodSearchUiState>` named `uiState`, `fun onQueryChanged(query: String)`. `@Composable fun FoodSearchScreen(viewModel: FoodSearchViewModel, onResultSelected: (entryKind: String, id: String) -> Unit)`. Task 3's nav host already constructs this ViewModel and calls this Composable with these exact names — must match. `entryKind` values are `EntryKind.FOOD`/`EntryKind.CUSTOM_FOOD`/`EntryKind.RECIPE` (`com.macrotrack.app.data.model.EntryKind`, already exists).

- [ ] **Step 1: Read `RecentLogReference`'s actual fields**

Read `app/src/main/java/com/macrotrack/app/domain/` for the file defining `RecentLogReference` (created in the food-repository slice) before writing Step 2 — confirm its exact field names (expected: `foodId: String?`, `customFoodId: String?`, `recipeId: String?`, `displayName: String`, `loggedAt: String`, matching how `RecentFoodRepository.getRecent()` constructs it) and use those exact names.

- [ ] **Step 2: Write the ViewModel**

Create `app/src/main/java/com/macrotrack/app/ui/search/FoodSearchViewModel.kt`:

```kotlin
package com.macrotrack.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrotrack.app.data.CustomFoodRepository
import com.macrotrack.app.data.FavoritesRepository
import com.macrotrack.app.data.FoodRepository
import com.macrotrack.app.data.RecentFoodRepository
import com.macrotrack.app.data.RecipeRepository
import com.macrotrack.app.data.model.EntryKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FoodSearchResult(val entryKind: String, val id: String, val title: String, val subtitle: String? = null)

data class FoodSearchUiState(
    val query: String = "",
    val isLoading: Boolean = false,
    val results: List<FoodSearchResult> = emptyList(),
    val recent: List<FoodSearchResult> = emptyList(),
    val errorMessage: String? = null,
)

class FoodSearchViewModel(
    private val foodRepository: FoodRepository,
    private val customFoodRepository: CustomFoodRepository,
    private val recipeRepository: RecipeRepository,
    private val favoritesRepository: FavoritesRepository,
    private val recentFoodRepository: RecentFoodRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FoodSearchUiState())
    val uiState: StateFlow<FoodSearchUiState> = _uiState.asStateFlow()

    init {
        loadRecent()
    }

    private fun loadRecent() {
        viewModelScope.launch {
            try {
                val recent = recentFoodRepository.getRecent().mapNotNull { reference ->
                    when {
                        reference.foodId != null -> FoodSearchResult(EntryKind.FOOD, reference.foodId, reference.displayName)
                        reference.customFoodId != null -> FoodSearchResult(EntryKind.CUSTOM_FOOD, reference.customFoodId, reference.displayName)
                        reference.recipeId != null -> FoodSearchResult(EntryKind.RECIPE, reference.recipeId, reference.displayName)
                        else -> null
                    }
                }
                _uiState.value = _uiState.value.copy(recent = recent)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = e.message ?: "Couldn't load recent foods")
            }
        }
    }

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(results = emptyList(), isLoading = false)
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val foods = foodRepository.search(query).map {
                    FoodSearchResult(EntryKind.FOOD, it.id, it.name, it.brand)
                }
                val customFoods = customFoodRepository.list()
                    .filter { it.name.contains(query, ignoreCase = true) }
                    .map { FoodSearchResult(EntryKind.CUSTOM_FOOD, it.id, it.name, it.brand) }
                val recipes = recipeRepository.list()
                    .filter { it.name.contains(query, ignoreCase = true) }
                    .map { FoodSearchResult(EntryKind.RECIPE, it.id, it.name) }
                _uiState.value = _uiState.value.copy(isLoading = false, results = foods + customFoods + recipes)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Search failed")
            }
        }
    }
}
```

Note: `customFoodRepository.list()`/`recipeRepository.list()` have no server-side search filter (they only list all of the user's own custom foods/recipes), so this ViewModel filters client-side by substring match after fetching the full list. This is acceptable for a user's own custom foods/recipes (expected to be a small personal list, unlike the shared `foods` table `FoodRepository.search` already filters server-side), not a scalability concern worth a repository change in this plan.

- [ ] **Step 3: Write the screen**

Create `app/src/main/java/com/macrotrack/app/ui/search/FoodSearchScreen.kt`:

```kotlin
package com.macrotrack.app.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FoodSearchScreen(viewModel: FoodSearchViewModel, onResultSelected: (entryKind: String, id: String) -> Unit) {
    val uiState by viewModel.uiState.collectAsState()

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = uiState.query,
            onValueChange = viewModel::onQueryChanged,
            label = { Text("Search foods, custom foods, recipes") },
            modifier = Modifier.fillMaxWidth(),
        )

        if (uiState.errorMessage != null) {
            Text(text = uiState.errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error)
        }

        val listToShow = if (uiState.query.isBlank()) uiState.recent else uiState.results
        val sectionLabel = if (uiState.query.isBlank()) "Recent" else "Results"

        if (uiState.isLoading) {
            CircularProgressIndicator()
        } else {
            Text(sectionLabel, style = MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(listToShow) { result ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onResultSelected(result.entryKind, result.id) }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(result.title, style = MaterialTheme.typography.titleMedium)
                        if (result.subtitle != null) {
                            Text(result.subtitle, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Static review**

Confirm the actual `RecentLogReference` field names read in Step 1 match what Step 2's code uses. Confirm `Food`/`CustomFood`'s `name`/`brand` fields and `Recipe`'s `name` field match the model files read for this plan (`FoodModels.kt`, `CustomFoodModels.kt`, `RecipeModels.kt`). State explicitly that no compile was possible.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/ui/search/FoodSearchViewModel.kt app/src/main/java/com/macrotrack/app/ui/search/FoodSearchScreen.kt
git commit -m "feat: add FoodSearchScreen with recent-foods and live search"
```

---

### Task 7: Add Log Entry screen

**Files:**
- Create: `app/src/main/java/com/macrotrack/app/ui/search/AddLogEntryViewModel.kt`
- Create: `app/src/main/java/com/macrotrack/app/ui/search/AddLogEntryScreen.kt`

**Interfaces:**
- Consumes: `FoodRepository.getById(id: String): Food?`, `CustomFoodRepository.getById(id: String): CustomFood?`, `RecipeRepository.getById(id: String): Recipe?`, `LogRepository.logFood(date, food: Food, quantity: Double, unit: String, meal: String, notes: String? = null)`, `LogRepository.logCustomFood(date, customFood: CustomFood, quantity: Double, unit: String, meal: String, notes: String? = null)`, `LogRepository.logRecipeServings(date, recipeId: String, loggedServings: Double, meal: String, notes: String? = null)` (all already exist). `EntryKind`/`Meal` constants (`com.macrotrack.app.data.model`, already exist: `EntryKind.FOOD`/`CUSTOM_FOOD`/`RECIPE`; `Meal.BREAKFAST`/`LUNCH`/`DINNER`/`SNACK`/`OTHER`).
- Produces: `class AddLogEntryViewModel(entryKind: String, id: String, foodRepository: FoodRepository, customFoodRepository: CustomFoodRepository, recipeRepository: RecipeRepository, logRepository: LogRepository) : ViewModel()` with `StateFlow<AddLogEntryUiState>` named `uiState`, `fun onQuantityChanged(quantity: String)`, `fun onMealChanged(meal: String)`, `fun save()`. `@Composable fun AddLogEntryScreen(viewModel: AddLogEntryViewModel, onSaved: () -> Unit, onCancel: () -> Unit)`. Task 3's nav host already constructs this ViewModel and calls this Composable with these exact names — must match.

- [ ] **Step 1: Write the ViewModel**

Create `app/src/main/java/com/macrotrack/app/ui/search/AddLogEntryViewModel.kt`:

```kotlin
package com.macrotrack.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.macrotrack.app.data.CustomFoodRepository
import com.macrotrack.app.data.FoodRepository
import com.macrotrack.app.data.LogRepository
import com.macrotrack.app.data.RecipeRepository
import com.macrotrack.app.data.model.CustomFood
import com.macrotrack.app.data.model.EntryKind
import com.macrotrack.app.data.model.Food
import com.macrotrack.app.data.model.Meal
import com.macrotrack.app.data.model.Recipe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class AddLogEntryUiState(
    val isLoading: Boolean = true,
    val displayName: String = "",
    val defaultUnit: String = "",
    val quantityText: String = "1",
    val meal: String = Meal.OTHER,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null,
)

class AddLogEntryViewModel(
    private val entryKind: String,
    private val id: String,
    private val foodRepository: FoodRepository,
    private val customFoodRepository: CustomFoodRepository,
    private val recipeRepository: RecipeRepository,
    private val logRepository: LogRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddLogEntryUiState())
    val uiState: StateFlow<AddLogEntryUiState> = _uiState.asStateFlow()

    private var loadedFood: Food? = null
    private var loadedCustomFood: CustomFood? = null
    private var loadedRecipe: Recipe? = null

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                when (entryKind) {
                    EntryKind.FOOD -> {
                        val food = foodRepository.getById(id) ?: error("Food not found")
                        loadedFood = food
                        _uiState.value = _uiState.value.copy(
                            isLoading = false, displayName = food.name, defaultUnit = food.servingUnit,
                        )
                    }
                    EntryKind.CUSTOM_FOOD -> {
                        val customFood = customFoodRepository.getById(id) ?: error("Custom food not found")
                        loadedCustomFood = customFood
                        _uiState.value = _uiState.value.copy(
                            isLoading = false, displayName = customFood.name, defaultUnit = customFood.servingUnit,
                        )
                    }
                    EntryKind.RECIPE -> {
                        val recipe = recipeRepository.getById(id) ?: error("Recipe not found")
                        loadedRecipe = recipe
                        _uiState.value = _uiState.value.copy(
                            isLoading = false, displayName = recipe.name, defaultUnit = "serving",
                        )
                    }
                    else -> error("Unknown entryKind: $entryKind")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Couldn't load this item")
            }
        }
    }

    fun onQuantityChanged(quantity: String) {
        _uiState.value = _uiState.value.copy(quantityText = quantity)
    }

    fun onMealChanged(meal: String) {
        _uiState.value = _uiState.value.copy(meal = meal)
    }

    fun save() {
        val quantity = _uiState.value.quantityText.toDoubleOrNull()
        if (quantity == null || quantity <= 0) {
            _uiState.value = _uiState.value.copy(errorMessage = "Enter a quantity greater than 0")
            return
        }
        _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            try {
                val today = LocalDate.now()
                val meal = _uiState.value.meal
                when {
                    loadedFood != null -> logRepository.logFood(today, loadedFood!!, quantity, _uiState.value.defaultUnit, meal)
                    loadedCustomFood != null -> logRepository.logCustomFood(today, loadedCustomFood!!, quantity, _uiState.value.defaultUnit, meal)
                    loadedRecipe != null -> logRepository.logRecipeServings(today, loadedRecipe!!.id, quantity, meal)
                    else -> error("Nothing loaded to save")
                }
                _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = e.message ?: "Couldn't save this entry")
            }
        }
    }
}
```

- [ ] **Step 2: Write the screen**

Create `app/src/main/java/com/macrotrack/app/ui/search/AddLogEntryScreen.kt`:

```kotlin
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
```

- [ ] **Step 3: Static review**

Confirm `LogRepository.logFood`/`logCustomFood`/`logRecipeServings`'s parameter order/names match `app/src/main/java/com/macrotrack/app/data/LogRepository.kt` exactly (in particular, `logRecipeServings` takes `recipeId: String`, not a `Recipe` object — confirm `loadedRecipe!!.id` is passed, not `loadedRecipe!!`). Confirm `Food.servingUnit`/`CustomFood.servingUnit` (via the shared `Scalable` interface) are the correct field names for the default unit shown. State explicitly that no compile was possible.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/macrotrack/app/ui/search/AddLogEntryViewModel.kt app/src/main/java/com/macrotrack/app/ui/search/AddLogEntryScreen.kt
git commit -m "feat: add AddLogEntryScreen completing the core logging loop"
```

---

## Self-Review

**1. Spec coverage:** Theme + nav dependency (Task 1) ✅. `AuthRepository` wrapping the verified auth-kt 3.7.0 API (Task 2) ✅. Navigation graph gating on `sessionStatus`, `MainActivity` rewrite (Task 3) ✅. Auth screen (Task 4) ✅. Daily Log screen never zero-filling an unlogged day (Task 5) ✅. Food Search across foods/custom foods/recipes/recent (Task 6) ✅. Add Log Entry calling the correct `LogRepository` method per entry kind (Task 7) ✅. Weight/trend/coach/check-in screens explicitly deferred to a follow-up plan per this plan's own Global Constraints scope decision ✅. Every task's verification step states no compile was possible in this sandbox ✅.

**2. Placeholder scan:** No TBD/TODO; all code blocks are complete Kotlin, not descriptions of code. Task 6's Step 1 ("read `RecentLogReference`'s actual fields before writing Step 2") is a genuine investigation step given this plan's author didn't have that file open when drafting, not a placeholder — the field names used in Step 2's code are the author's best verified recollection from earlier in this session and must be confirmed, not invented, by the implementer.

**3. Type consistency:** `Destinations.addLogEntryRoute(entryKind, id)` (Task 3) matches `FoodSearchScreen`'s `onResultSelected: (entryKind: String, id: String) -> Unit` callback shape (Task 6) and `AddLogEntryViewModel`'s `entryKind`/`id` constructor parameters (Task 7) exactly. `DailyLogViewModel(logRepository, dayStatusRepository)`/`DailyLogScreen(viewModel, onAddFood)` (Task 5) match Task 3's construction and call site exactly. `FoodSearchViewModel`'s five-repository constructor (Task 6) matches Task 3's construction exactly. `AddLogEntryViewModel`'s six-parameter constructor (Task 7) matches Task 3's construction exactly. `AuthViewModel(authRepository)`/`AuthScreen(viewModel)` (Task 4) match Task 3's construction exactly.
