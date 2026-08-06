package com.macroplus.app.ui.coach

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
import com.macroplus.app.data.CheckInRepository
import com.macroplus.app.data.ExpenditureRepository
import com.macroplus.app.data.MacroProgramRepository
import com.macroplus.app.data.WeightRepository
import com.macroplus.app.data.model.CheckInModuleDto
import com.macroplus.app.data.model.MacroProgram
import com.macroplus.app.data.model.PersistedCheckIn
import com.macroplus.app.data.model.WeightEntry
import com.macroplus.app.domain.ExpenditureEstimate
import com.macroplus.app.ui.theme.MacroPlusTheme
import java.time.Instant
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
            enabled = !uiState.isSavingGoal && !uiState.isCheckingIn,
        )
        if (uiState.activeProgram == null || uiState.hasUnsavedGoal) {
            Text(
                if (uiState.activeProgram == null) {
                    "Choose a rate and save it. Your goal will be kept between sessions."
                } else {
                    "Your slider change is not saved yet."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = viewModel::saveGoal,
                enabled = !uiState.isSavingGoal,
            ) {
                Text(if (uiState.isSavingGoal) "Saving..." else "Save goal")
            }
        } else {
            Text("Saved goal: ${uiState.activeProgram.name}", style = MaterialTheme.typography.bodySmall)
        }

        val checkIn = uiState.checkIn
        when {
            checkIn == null && uiState.activeProgram != null && !uiState.hasUnsavedGoal -> {
                Button(onClick = viewModel::checkIn, enabled = !uiState.isCheckingIn) {
                    Text(if (uiState.isCheckingIn) "Checking in..." else "Check in")
                }
            }
            checkIn == null -> Text("Save your goal to unlock the weekly check-in.", style = MaterialTheme.typography.bodyMedium)
            checkIn.status == "held" -> {
                Text("Not ready yet -- ${checkIn.explanation}", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = viewModel::checkIn, enabled = !uiState.isCheckingIn) {
                    Text(if (uiState.isCheckingIn) "Checking in..." else "Run check-in again")
                }
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
                if (checkIn.status == "accepted") {
                    uiState.appliedDayTarget?.let { target ->
                        Text(
                            "Next week's target: ${target.calories.toInt()} kcal · " +
                                "${target.proteinG.toInt()}g protein · " +
                                "${target.carbsG.toInt()}g carbs · ${target.fatG.toInt()}g fat",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
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
    override suspend fun getCheckIn(weekStart: LocalDate, programId: String?): PersistedCheckIn? = checkIn
    override suspend fun recomputeCheckIn(
        weekStart: LocalDate,
        weekEnd: LocalDate,
        targetRateKgPerWeek: Double,
        programId: String?,
        proteinGPerKg: Double,
        fatGPerKg: Double,
    ) = error("not used in preview")
    override suspend fun resolve(weekStart: LocalDate, accepted: Boolean, programId: String?): PersistedCheckIn = error("not used in preview")
}

private class PreviewMacroProgramRepository(private val program: MacroProgram?) : MacroProgramRepository {
    override suspend fun getActive(): MacroProgram? = program
    override suspend fun saveActive(targetRateKgPerWeek: Double, name: String): MacroProgram =
        program ?: error("not used in preview")
    override suspend fun getDayTarget(date: LocalDate, programId: String): com.macroplus.app.data.model.MacroProgramDay? = null
    override suspend fun saveDayTargets(
        programId: String,
        startDate: LocalDate,
        endDate: LocalDate,
        calories: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
    ): List<com.macroplus.app.data.model.MacroProgramDay> = emptyList()
}

private class PreviewCoachWeightRepository(private val hasWeighIn: Boolean) : WeightRepository {
    override suspend fun listEntries(since: Instant): List<WeightEntry> = if (hasWeighIn) {
        listOf(
            WeightEntry(
                id = "preview-weight",
                userId = "preview-user",
                measuredAt = "2026-08-03T08:00:00Z",
                weightKg = 80.0,
                source = "manual",
                createdAt = "2026-08-03T08:00:00Z",
            ),
        )
    } else {
        emptyList()
    }

    override suspend fun logWeight(measuredAt: Instant, weightKg: Double, source: String, note: String?): WeightEntry =
        error("not used in preview")

    override suspend fun deleteEntry(entryId: String) = Unit
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
    MacroPlusTheme {
        CoachScreen(
            viewModel = CoachViewModel(
                expenditureRepository = PreviewExpenditureRepository(fakeEstimate),
                checkInRepository = PreviewCheckInRepository(fakeCheckIn),
                macroProgramRepository = PreviewMacroProgramRepository(
                    MacroProgram(
                        id = "preview-program",
                        userId = "preview-user",
                        name = "My macro goal",
                        mode = "manual",
                        goal = "lose",
                        targetRateKgPerWeek = -0.2,
                        startDate = "2026-08-01",
                        status = "active",
                        createdAt = "2026-08-01T08:00:00Z",
                        updatedAt = "2026-08-01T08:00:00Z",
                    ),
                ),
                weightRepository = PreviewCoachWeightRepository(hasWeighIn = true),
            ),
            onLogWeight = {},
        )
    }
}
