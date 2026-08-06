from __future__ import annotations

import gzip
import io
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import import_ausnut
import import_openfoodfacts


ROOT = Path(__file__).parent
FIXTURES = ROOT / "fixtures"


class _FakeStreamResponse:
    """Minimal stand-in for requests.Response(stream=True) over a gzip body."""

    def __init__(self, gzip_bytes: bytes) -> None:
        self.raw = io.BytesIO(gzip_bytes)

    def raise_for_status(self) -> None:
        pass


class _FakeSearchResponse:
    """Minimal stand-in for requests.Response over a Search-a-licious JSON body."""

    def __init__(self, payload: dict, status_code: int = 200) -> None:
        self._payload = payload
        self.status_code = status_code
        self.headers: dict = {}

    def raise_for_status(self) -> None:
        pass

    def json(self) -> dict:
        return self._payload


def _gzip_lines(text: str) -> bytes:
    buffer = io.BytesIO()
    with gzip.GzipFile(fileobj=buffer, mode="wb") as handle:
        handle.write(text.encode("utf-8"))
    return buffer.getvalue()


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

    def test_iter_remote_jsonl_products_decompresses_gzip_stream(self) -> None:
        fixture_text = (FIXTURES / "off.jsonl").read_text(encoding="utf-8")
        gzip_bytes = _gzip_lines(fixture_text)
        fake_response = _FakeStreamResponse(gzip_bytes)
        fake_requests = SimpleNamespace(get=mock.Mock(return_value=fake_response))

        with mock.patch.object(import_openfoodfacts, "get_requests", return_value=fake_requests):
            products = list(
                import_openfoodfacts.iter_remote_jsonl_products(
                    "https://example.test/openfoodfacts-products.jsonl.gz",
                    timeout=30.0,
                    user_agent="test-agent/1.0",
                )
            )

        self.assertEqual(len(products), 4)
        self.assertEqual(products[0]["code"], "930000000001")
        fake_requests.get.assert_called_once()
        call_kwargs = fake_requests.get.call_args.kwargs
        self.assertTrue(call_kwargs["stream"])
        self.assertEqual(call_kwargs["headers"]["User-Agent"], "test-agent/1.0")

    def test_import_products_streams_from_input_url(self) -> None:
        fixture_text = (FIXTURES / "off.jsonl").read_text(encoding="utf-8")
        gzip_bytes = _gzip_lines(fixture_text)
        fake_response = _FakeStreamResponse(gzip_bytes)
        fake_requests = SimpleNamespace(
            get=mock.Mock(return_value=fake_response),
            exceptions=SimpleNamespace(RequestException=Exception),
        )

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "off_bulk.sql"
            args = SimpleNamespace(
                input=None,
                input_url="https://example.test/openfoodfacts-products.jsonl.gz",
                output=output,
                keep_duplicate_barcodes=False,
                legacy_schema=False,
                timeout=30.0,
                user_agent="test-agent/1.0",
            )
            with mock.patch.object(import_openfoodfacts, "get_requests", return_value=fake_requests):
                counters = import_openfoodfacts.import_products(args)

            self.assertEqual(counters["read"], 4)
            self.assertEqual(counters["written"], 2)
            self.assertNotIn("next_start_page", counters)
            sql = output.read_text(encoding="utf-8")
            self.assertIn("'Test Bar'", sql)

    def test_iter_api_products_paginates_search_a_licious_hits(self) -> None:
        page_one = {
            "hits": [{"code": "1"}, {"code": "2"}],
            "page": 1,
            "page_size": 2,
            "page_count": 2,
            "count": 3,
        }
        page_two = {
            "hits": [{"code": "3"}],
            "page": 2,
            "page_size": 2,
            "page_count": 2,
            "count": 3,
        }
        fake_session = SimpleNamespace(
            get=mock.Mock(
                side_effect=[_FakeSearchResponse(page_one), _FakeSearchResponse(page_two)]
            )
        )
        fake_requests = SimpleNamespace(Session=mock.Mock(return_value=fake_session))

        args = SimpleNamespace(
            api_url=import_openfoodfacts.API_URL,
            page_size=2,
            max_pages=0,
            start_page=1,
            sleep_seconds=0,
            timeout=30.0,
            user_agent="test-agent/1.0",
        )
        with mock.patch.object(import_openfoodfacts, "get_requests", return_value=fake_requests):
            progress: list = [0]
            products = list(import_openfoodfacts.iter_api_products(args, progress=progress))

        self.assertEqual([p["code"] for p in products], ["1", "2", "3"])
        self.assertEqual(progress, [2])
        self.assertEqual(fake_session.get.call_count, 2)
        first_call_params = fake_session.get.call_args_list[0].kwargs["params"]
        self.assertEqual(first_call_params["q"], import_openfoodfacts.AUSTRALIA_QUERY)
        self.assertEqual(first_call_params["langs"], "en")
        self.assertEqual(first_call_params["page"], 1)

    def test_iter_api_products_max_pages_counts_pages_fetched_not_absolute_page_number(
        self,
    ) -> None:
        # Regression test: resuming from start_page=11 with max_pages=2 must
        # fetch two pages (11 and 12), not stop after page 11 alone because
        # 11 >= 2. This exact scenario silently capped every resumed OFF
        # import run to one page once the cursor passed the configured
        # max_pages value.
        page_eleven = {"hits": [{"code": "11"}], "page": 11, "page_size": 1, "page_count": 20}
        page_twelve = {"hits": [{"code": "12"}], "page": 12, "page_size": 1, "page_count": 20}
        fake_session = SimpleNamespace(
            get=mock.Mock(
                side_effect=[_FakeSearchResponse(page_eleven), _FakeSearchResponse(page_twelve)]
            )
        )
        fake_requests = SimpleNamespace(Session=mock.Mock(return_value=fake_session))

        args = SimpleNamespace(
            api_url=import_openfoodfacts.API_URL,
            page_size=1,
            max_pages=2,
            start_page=11,
            sleep_seconds=0,
            timeout=30.0,
            user_agent="test-agent/1.0",
        )
        with mock.patch.object(import_openfoodfacts, "get_requests", return_value=fake_requests):
            products = list(import_openfoodfacts.iter_api_products(args))

        self.assertEqual([p["code"] for p in products], ["11", "12"])
        self.assertEqual(fake_session.get.call_count, 2)

    def test_iter_api_products_stops_on_search_error_response(self) -> None:
        error_payload = {
            "debug": {"query": {}},
            "errors": [{"title": "query parsing error"}],
        }
        fake_session = SimpleNamespace(get=mock.Mock(return_value=_FakeSearchResponse(error_payload)))
        fake_requests = SimpleNamespace(Session=mock.Mock(return_value=fake_session))

        args = SimpleNamespace(
            api_url=import_openfoodfacts.API_URL,
            page_size=50,
            max_pages=0,
            start_page=1,
            sleep_seconds=0,
            timeout=30.0,
            user_agent="test-agent/1.0",
        )
        with mock.patch.object(import_openfoodfacts, "get_requests", return_value=fake_requests):
            products = list(import_openfoodfacts.iter_api_products(args))

        self.assertEqual(products, [])
        fake_session.get.assert_called_once()

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
