package com.macroplus.app.domain

/**
 * One suggested action surfaced to the user by a check-in, on either path: the "held" path emits
 * the data-coverage modules (partial_logging / weigh_in / logging_break), and the "ready" path
 * emits the program_update module that invites the user to review the proposed targets.
 */
data class CheckInModule(val key: String, val action: String)

/** Mirrors adaptive_engine.py's weekly_check_in() return dict. */
data class CheckInResult(
    val status: String, // "held" | "ready"
    val estimate: ExpenditureEstimate,
    val modules: List<CheckInModule>,
    val targets: MacroTargets?,
    val explanation: String,
)

/**
 * Recommendations are informational, not punishment or retroactive calorie
 * debt -- see docs/ADAPTIVE_ENGINE_CONTRACT.md's check-in module contract.
 */
fun weeklyCheckIn(
    records: List<DailyRecord>,
    previousExpenditureKcal: Double?,
    bodyWeightKg: Double,
    targetRateKgPerWeek: Double,
    proteinGPerKg: Double = EngineConfig().defaultProteinGPerKg,
    fatGPerKg: Double = EngineConfig().defaultFatGPerKg,
    config: EngineConfig = EngineConfig(),
): CheckInResult {
    val estimate = AdaptiveEngine.estimateExpenditure(records, previousExpenditureKcal, config)
    if (estimate.state == "holding") {
        val modules = mutableListOf<CheckInModule>()
        if (estimate.nutritionDays < config.minimumNutritionDaysPerWeek * 2) {
            modules.add(CheckInModule("partial_logging", "review incomplete nutrition days"))
        }
        if (estimate.weightDays < config.minimumWeightDaysPerWeek * 2) {
            modules.add(CheckInModule("weigh_in", "add a weigh-in for each seven-day period"))
        }
        modules.add(CheckInModule("logging_break", "carry forward the last high-confidence estimate"))
        return CheckInResult(
            status = "held", estimate = estimate, modules = modules,
            targets = null, explanation = estimate.explanation,
        )
    }
    val estimateKcal = requireNotNull(estimate.estimateKcal) {
        "estimateExpenditure returned state=updating with a null estimateKcal -- contract violation"
    }
    val calories = calorieTarget(estimateKcal, targetRateKgPerWeek, config)
    val targets = macroTargets(calories, bodyWeightKg, proteinGPerKg, fatGPerKg)
    val modules = listOf(
        CheckInModule("program_update", "review and accept the proposed calorie and macro targets"),
    )
    return CheckInResult(
        status = "ready", estimate = estimate, modules = modules, targets = targets,
        explanation = "The next target uses observed expenditure and the signed goal rate; " +
            "it does not punish or average in unlogged days.",
    )
}
