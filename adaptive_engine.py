#!/usr/bin/env python3
"""Deterministic reference engine for a MacroFactor-style nutrition loop.

This is an executable specification for the Kotlin implementation, not a
claim to reproduce MacroFactor's private parameters.  The public product
behaviour this mirrors is: use logged intake and smoothed weight change,
require enough coverage, hold when data are inadequate, damp updates, and
generate the next week's target from the observed expenditure and signed goal
rate.

No wearable calorie estimates are used.  A missing nutrition day is never
silently interpolated; a missing weight day may be tolerated by the trend
filter because the scale is a noisy measurement.
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import asdict, dataclass
from datetime import date, timedelta
from statistics import fmean
from typing import Any, Dict, Iterable, List, Optional, Sequence, Tuple


KCAL_PER_KG = 7700.0


@dataclass(frozen=True)
class EngineConfig:
    kcal_per_kg: float = KCAL_PER_KG
    trend_alpha: float = 0.20
    minimum_history_days: int = 14
    coverage_window_days: int = 7
    minimum_nutrition_days_per_week: int = 6
    minimum_weight_days_per_week: int = 1
    maximum_expenditure_step_kcal: float = 100.0
    minimum_expenditure_kcal: float = 1000.0
    maximum_expenditure_kcal: float = 6000.0
    default_protein_g_per_kg: float = 1.8
    default_fat_g_per_kg: float = 0.8


@dataclass(frozen=True)
class DailyRecord:
    day: date
    calories: Optional[float] = None
    weight_kg: Optional[float] = None
    nutrition_status: str = "complete"

    @classmethod
    def from_dict(cls, value: Dict[str, Any]) -> "DailyRecord":
        return cls(
            day=date.fromisoformat(str(value["date"])),
            calories=None if value.get("calories") is None else float(value["calories"]),
            weight_kg=None if value.get("weight_kg") is None else float(value["weight_kg"]),
            nutrition_status=str(value.get("nutrition_status", "complete")),
        )


@dataclass(frozen=True)
class ExpenditureEstimate:
    state: str
    confidence: str
    estimate_kcal: Optional[float]
    raw_estimate_kcal: Optional[float]
    previous_estimate_kcal: Optional[float]
    trend_slope_kg_per_week: Optional[float]
    nutrition_days: int
    weight_days: int
    window_start: Optional[str]
    window_end: Optional[str]
    explanation: str


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def _sorted_records(records: Iterable[DailyRecord]) -> List[DailyRecord]:
    return sorted(records, key=lambda record: record.day)


def nutrition_is_countable(record: DailyRecord) -> bool:
    if record.nutrition_status == "fasted":
        # A declared fast is countable only when the caller explicitly stores
        # zero calories.  An unlogged day remains unknown.
        return record.calories == 0
    return record.nutrition_status == "complete" and record.calories is not None and record.calories >= 0


def weight_trend(values: Sequence[Optional[float]], alpha: float = 0.20) -> List[Optional[float]]:
    """Return a recent-weight-weighted EWMA, carrying the last trend across gaps."""
    if not 0 < alpha <= 1:
        raise ValueError("alpha must be greater than 0 and no greater than 1")
    result: List[Optional[float]] = []
    previous: Optional[float] = None
    for value in values:
        if value is not None:
            previous = float(value) if previous is None else alpha * float(value) + (1 - alpha) * previous
        result.append(previous)
    return result


def _linear_slope(points: Sequence[Tuple[date, float]]) -> Optional[float]:
    if len(points) < 2:
        return None
    origin = points[0][0]
    xs = [(day - origin).days for day, _ in points]
    ys = [value for _, value in points]
    x_mean = fmean(xs)
    y_mean = fmean(ys)
    denominator = sum((x - x_mean) ** 2 for x in xs)
    if denominator == 0:
        return None
    return sum((x - x_mean) * (y - y_mean) for x, y in zip(xs, ys)) / denominator


def _period_coverage(records: Sequence[DailyRecord], start: date, end: date) -> Tuple[int, int]:
    period = [record for record in records if start <= record.day <= end]
    nutrition_days = sum(1 for record in period if nutrition_is_countable(record))
    weight_days = sum(1 for record in period if record.weight_kg is not None)
    return nutrition_days, weight_days


def _coverage_explanation(records: Sequence[DailyRecord], config: EngineConfig) -> Tuple[bool, List[str], int, int]:
    if not records:
        return False, ["No daily records are available."], 0, 0
    first, last = records[0].day, records[-1].day
    if (last - first).days + 1 < config.minimum_history_days:
        return False, ["More history is required before updating expenditure."], 0, 0
    last_week_start = last - timedelta(days=config.coverage_window_days - 1)
    previous_week_end = last_week_start - timedelta(days=1)
    previous_week_start = previous_week_end - timedelta(days=config.coverage_window_days - 1)
    current_nutrition, current_weight = _period_coverage(records, last_week_start, last)
    previous_nutrition, previous_weight = _period_coverage(records, previous_week_start, previous_week_end)
    reasons: List[str] = []
    if current_nutrition < config.minimum_nutrition_days_per_week or previous_nutrition < config.minimum_nutrition_days_per_week:
        reasons.append("Nutrition logging is below the 6-of-7-day update gate.")
    if current_weight < config.minimum_weight_days_per_week or previous_weight < config.minimum_weight_days_per_week:
        reasons.append("At least one weigh-in is required in each seven-day period.")
    return not reasons, reasons, current_nutrition + previous_nutrition, current_weight + previous_weight


def estimate_expenditure(
    records: Iterable[DailyRecord],
    previous_estimate_kcal: Optional[float] = None,
    config: EngineConfig = EngineConfig(),
) -> ExpenditureEstimate:
    """Estimate TDEE as mean logged intake minus energy represented by trend slope."""
    ordered = _sorted_records(records)
    if not ordered:
        return ExpenditureEstimate(
            state="holding", confidence="holding", estimate_kcal=previous_estimate_kcal,
            raw_estimate_kcal=None, previous_estimate_kcal=previous_estimate_kcal,
            trend_slope_kg_per_week=None, nutrition_days=0, weight_days=0,
            window_start=None, window_end=None, explanation="No records are available.",
        )
    enough, reasons, gate_nutrition, gate_weight = _coverage_explanation(ordered, config)
    end = ordered[-1].day
    start = max(ordered[0].day, end - timedelta(days=config.coverage_window_days * 2 - 1))
    window = [record for record in ordered if start <= record.day <= end]
    countable = [record for record in window if nutrition_is_countable(record)]
    nutrition_days = len(countable)
    weight_days = sum(1 for record in window if record.weight_kg is not None)
    trend_values = weight_trend([record.weight_kg for record in ordered], config.trend_alpha)
    trend_points = [
        (record.day, trend)
        for record, trend in zip(ordered, trend_values)
        if start <= record.day <= end and trend is not None
    ]
    slope_per_day = _linear_slope(trend_points)
    slope_per_week = None if slope_per_day is None else slope_per_day * 7
    raw: Optional[float] = None
    if enough and countable and slope_per_day is not None:
        raw = fmean(record.calories for record in countable if record.calories is not None) - slope_per_day * config.kcal_per_kg
        raw = _clamp(raw, config.minimum_expenditure_kcal, config.maximum_expenditure_kcal)
    if raw is None:
        confidence = "holding" if previous_estimate_kcal is None else "low"
        explanation = " ".join(reasons) if reasons else "A trend slope and complete intake coverage are required."
        return ExpenditureEstimate(
            state="holding", confidence=confidence, estimate_kcal=previous_estimate_kcal,
            raw_estimate_kcal=None, previous_estimate_kcal=previous_estimate_kcal,
            trend_slope_kg_per_week=slope_per_week, nutrition_days=nutrition_days,
            weight_days=weight_days, window_start=start.isoformat(), window_end=end.isoformat(),
            explanation=explanation,
        )
    if previous_estimate_kcal is None:
        estimate = raw
    else:
        estimate = previous_estimate_kcal + _clamp(
            raw - previous_estimate_kcal,
            -config.maximum_expenditure_step_kcal,
            config.maximum_expenditure_step_kcal,
        )
    span_days = (end - ordered[0].day).days + 1
    if span_days >= 28 and nutrition_days >= 12 and weight_days >= 4:
        confidence = "high"
    elif span_days >= 14:
        confidence = "medium"
    else:
        confidence = "low"
    return ExpenditureEstimate(
        state="updating", confidence=confidence, estimate_kcal=round(estimate, 1),
        raw_estimate_kcal=round(raw, 1), previous_estimate_kcal=previous_estimate_kcal,
        trend_slope_kg_per_week=None if slope_per_week is None else round(slope_per_week, 4),
        nutrition_days=nutrition_days, weight_days=weight_days,
        window_start=start.isoformat(), window_end=end.isoformat(),
        explanation="Expenditure updated from logged intake and smoothed weight trend.",
    )


def initial_expenditure_kcal(
    *,
    weight_kg: float,
    height_cm: float,
    age_years: float,
    sex: str = "unspecified",
    activity_level: str = "moderate",
    body_fat_pct: Optional[float] = None,
) -> float:
    """Return a transparent starting estimate; observed data should replace it."""
    if body_fat_pct is not None and 2 <= body_fat_pct <= 70:
        lean_mass = weight_kg * (1 - body_fat_pct / 100)
        bmr = 370 + 21.6 * lean_mass
    elif sex == "male":
        bmr = 10 * weight_kg + 6.25 * height_cm - 5 * age_years + 5
    elif sex == "female":
        bmr = 10 * weight_kg + 6.25 * height_cm - 5 * age_years - 161
    else:
        # Midpoint fallback rather than guessing a sex-specific equation.
        bmr = 10 * weight_kg + 6.25 * height_cm - 5 * age_years - 78
    factors = {
        "sedentary": 1.20,
        "light": 1.375,
        "moderate": 1.55,
        "high": 1.725,
        "very_high": 1.90,
    }
    return round(max(1000.0, bmr * factors.get(activity_level, factors["moderate"])), 1)


def calorie_target(expenditure_kcal: float, target_rate_kg_per_week: float, config: EngineConfig = EngineConfig()) -> float:
    """Signed rate: negative loses weight, positive gains weight, zero maintains."""
    return round(expenditure_kcal + target_rate_kg_per_week * config.kcal_per_kg / 7, 1)


def macro_targets(
    calories: float,
    body_weight_kg: float,
    protein_g_per_kg: float = EngineConfig.default_protein_g_per_kg,
    fat_g_per_kg: float = EngineConfig.default_fat_g_per_kg,
) -> Dict[str, float]:
    protein = max(0.0, body_weight_kg * protein_g_per_kg)
    fat = max(0.0, body_weight_kg * fat_g_per_kg)
    remaining = calories - protein * 4 - fat * 9
    carbs = max(0.0, remaining / 4)
    return {
        "calories": round(calories, 1),
        "protein_g": round(protein, 1),
        "carbs_g": round(carbs, 1),
        "fat_g": round(fat, 1),
        "macro_calories": round(protein * 4 + carbs * 4 + fat * 9, 1),
    }


def weekly_check_in(
    records: Iterable[DailyRecord],
    *,
    previous_expenditure_kcal: Optional[float],
    body_weight_kg: float,
    target_rate_kg_per_week: float,
    protein_g_per_kg: float = EngineConfig.default_protein_g_per_kg,
    fat_g_per_kg: float = EngineConfig.default_fat_g_per_kg,
    config: EngineConfig = EngineConfig(),
) -> Dict[str, Any]:
    estimate = estimate_expenditure(records, previous_expenditure_kcal, config)
    modules: List[Dict[str, str]] = []
    if estimate.state == "holding":
        if estimate.nutrition_days < config.minimum_nutrition_days_per_week * 2:
            modules.append({"key": "partial_logging", "action": "review incomplete nutrition days"})
        if estimate.weight_days < config.minimum_weight_days_per_week * 2:
            modules.append({"key": "weigh_in", "action": "add a weigh-in for each seven-day period"})
        modules.append({"key": "logging_break", "action": "carry forward the last high-confidence estimate"})
        return {
            "status": "held",
            "estimate": asdict(estimate),
            "modules": modules,
            "targets": None,
            "explanation": estimate.explanation,
        }
    assert estimate.estimate_kcal is not None
    calories = calorie_target(estimate.estimate_kcal, target_rate_kg_per_week, config)
    targets = macro_targets(calories, body_weight_kg, protein_g_per_kg, fat_g_per_kg)
    modules.append({"key": "program_update", "action": "review and accept the proposed calorie and macro targets"})
    return {
        "status": "ready",
        "estimate": asdict(estimate),
        "modules": modules,
        "targets": targets,
        "explanation": (
            "The next target uses observed expenditure and the signed goal rate; "
            "it does not punish or average in unlogged days."
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the deterministic adaptive nutrition reference engine.")
    parser.add_argument("--input", type=argparse.FileType("r"), default="-", help="JSON input file or stdin")
    args = parser.parse_args()
    payload = json.load(args.input)
    records = [DailyRecord.from_dict(value) for value in payload.get("records", [])]
    result = weekly_check_in(
        records,
        previous_expenditure_kcal=payload.get("previous_expenditure_kcal"),
        body_weight_kg=float(payload["body_weight_kg"]),
        target_rate_kg_per_week=float(payload.get("target_rate_kg_per_week", 0)),
        protein_g_per_kg=float(payload.get("protein_g_per_kg", EngineConfig.default_protein_g_per_kg)),
        fat_g_per_kg=float(payload.get("fat_g_per_kg", EngineConfig.default_fat_g_per_kg)),
    )
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
