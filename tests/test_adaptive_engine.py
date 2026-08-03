from __future__ import annotations

import unittest
from datetime import date, timedelta

from adaptive_engine import (
    DailyRecord,
    EngineConfig,
    calorie_target,
    estimate_expenditure,
    initial_expenditure_kcal,
    macro_targets,
    weekly_check_in,
    weight_trend,
)


class AdaptiveEngineTests(unittest.TestCase):
    def make_records(self, count: int = 14):
        start = date(2026, 7, 1)
        return [
            DailyRecord(
                day=start + timedelta(days=index),
                calories=2800,
                weight_kg=90 - index * 0.04,
            )
            for index in range(count)
        ]

    def test_weight_trend_smooths_a_spike(self) -> None:
        trend = weight_trend([90.0, 90.2, 90.1, 95.0, 90.2], alpha=0.2)
        self.assertEqual(len(trend), 5)
        self.assertLess(trend[3], 91.5)
        self.assertIsNotNone(trend[4])

    def test_expenditure_updates_only_with_coverage(self) -> None:
        estimate = estimate_expenditure(self.make_records(), previous_estimate_kcal=2800)
        self.assertEqual(estimate.state, "updating")
        self.assertIsNotNone(estimate.estimate_kcal)
        self.assertIsNotNone(estimate.trend_slope_kg_per_week)

        incomplete = self.make_records()
        incomplete[2] = DailyRecord(day=incomplete[2].day, calories=None, weight_kg=89.92, nutrition_status="partial")
        incomplete[3] = DailyRecord(day=incomplete[3].day, calories=None, weight_kg=89.88, nutrition_status="partial")
        held = estimate_expenditure(incomplete, previous_estimate_kcal=2800)
        self.assertEqual(held.state, "holding")
        self.assertEqual(held.estimate_kcal, 2800)

    def test_targets_are_signed_and_preference_driven(self) -> None:
        self.assertLess(calorie_target(2800, -0.5), 2800)
        self.assertGreater(calorie_target(2800, 0.5), 2800)
        targets = macro_targets(2500, 90, protein_g_per_kg=2.0, fat_g_per_kg=0.8)
        self.assertEqual(targets["protein_g"], 180.0)
        self.assertEqual(targets["fat_g"], 72.0)
        self.assertGreaterEqual(targets["carbs_g"], 0)

    def test_check_in_returns_hold_modules(self) -> None:
        records = self.make_records(7)
        result = weekly_check_in(
            records,
            previous_expenditure_kcal=2800,
            body_weight_kg=90,
            target_rate_kg_per_week=-0.3,
        )
        self.assertEqual(result["status"], "held")
        self.assertTrue(result["modules"])

    def test_initial_estimate_is_explicitly_only_a_start(self) -> None:
        estimate = initial_expenditure_kcal(
            weight_kg=90,
            height_cm=177,
            age_years=32,
            sex="male",
            activity_level="moderate",
        )
        self.assertGreater(estimate, 2000)
        self.assertLess(estimate, 4000)


if __name__ == "__main__":
    unittest.main()
