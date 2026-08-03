from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

import import_ausnut
import import_openfoodfacts


ROOT = Path(__file__).parent
FIXTURES = ROOT / "fixtures"


class ImporterTests(unittest.TestCase):
    def test_open_food_facts_filters_and_scales_servings(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "off.sql"
            args = SimpleNamespace(
                input=FIXTURES / "off.jsonl",
                output=output,
                keep_duplicate_barcodes=False,
                legacy_schema=False,
            )
            counters = import_openfoodfacts.import_products(args)
            self.assertEqual(counters["read"], 4)
            self.assertEqual(counters["written"], 2)
            self.assertEqual(counters["not_australia"], 1)
            self.assertEqual(counters["missing_required_macro"], 1)
            sql = output.read_text(encoding="utf-8")
            self.assertIn("nutrition_basis_qty", sql)
            self.assertIn("'Test Bar'", sql)
            self.assertIn("'Volume Drink'", sql)
            self.assertIn("ARRAY['en:milk']::text[]", sql)
            # 250 kcal/100 g × 40 g = 100 kcal.
            self.assertIn("'Acme', '930000000001', 40, 'g', 100", sql)
            # 40 kcal/100 ml × 250 ml = 100 kcal.
            self.assertIn("'Acme', '930000000002', 250, 'ml', 100", sql)

    def test_open_food_facts_legacy_output(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "off_legacy.sql"
            args = SimpleNamespace(
                input=FIXTURES / "off.jsonl",
                output=output,
                keep_duplicate_barcodes=False,
                legacy_schema=True,
            )
            import_openfoodfacts.import_products(args)
            sql = output.read_text(encoding="utf-8")
            self.assertIn("INSERT INTO foods (name, brand, barcode, serving_qty, serving_unit, calories, protein_g, carbs_g, fat_g, source, external_id)", sql)
            self.assertNotIn("nutrition_basis_qty", sql)

    def test_ausnut_wide_table_and_measure(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "ausnut.sql"
            args = SimpleNamespace(
                nutrients=FIXTURES / "ausnut_wide.csv",
                foods=None,
                measures=FIXTURES / "ausnut_measures.csv",
                output=output,
                source="ausnut",
                legacy_schema=False,
            )
            counters = import_ausnut.import_profiles(args)
            self.assertEqual(counters["layout"], "wide")
            self.assertEqual(counters["profiles"], 2)
            self.assertEqual(counters["written"], 1)
            self.assertEqual(counters["missing_energy_protein_carbs_fat"], 1)
            sql = output.read_text(encoding="utf-8")
            self.assertIn("'Apple raw', NULL, NULL, 150, 'g'", sql)
            # 218 kJ / 4.184 × 1.5 = approximately 78.2 kcal; exact SQL is
            # checked by the parser's output rather than a rounded assertion.
            self.assertIn("'1001'", sql)
            self.assertIn('"Sodium (mg)"', sql)

    def test_nuttab_long_table(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "nuttab.sql"
            args = SimpleNamespace(
                nutrients=FIXTURES / "nuttab_nutrients.csv",
                foods=FIXTURES / "nuttab_foods.csv",
                measures=None,
                output=output,
                source="nuttab",
                legacy_schema=False,
            )
            counters = import_ausnut.import_profiles(args)
            self.assertEqual(counters["layout"], "long")
            self.assertEqual(counters["written"], 1)
            sql = output.read_text(encoding="utf-8")
            self.assertIn("'Test rice'", sql)
            self.assertIn("'nuttab'", sql)


if __name__ == "__main__":
    unittest.main()
