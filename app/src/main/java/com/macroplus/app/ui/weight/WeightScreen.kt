package com.macroplus.app.ui.weight

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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.macroplus.app.data.TrendRepository
import com.macroplus.app.data.WeightRepository
import com.macroplus.app.data.model.TrendPoint
import com.macroplus.app.data.model.WeightEntry
import com.macroplus.app.ui.theme.MacroPlusTheme
import java.time.Instant

@Composable
fun WeightScreen(viewModel: WeightViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    // Same resume-based refresh pattern as DailyLogScreen -- this ViewModel is scoped to its
    // NavBackStackEntry (see MacroPlusNavHost), so re-entering this tab after logging weight
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
    MacroPlusTheme {
        WeightScreen(
            viewModel = WeightViewModel(
                weightRepository = PreviewWeightRepository(fakeEntries),
                trendRepository = PreviewTrendRepository(fakeTrend),
            ),
        )
    }
}
