#!/usr/bin/env python3
"""Create PostgreSQL seed INSERTs from Open Food Facts Australian products.

The importer supports the Open Food Facts Search-a-licious API
(search.openfoodfacts.org) and local JSON/JSONL or CSV/TSV exports.  It keeps
products tagged for Australia, requires a barcode, product name, and the four
macro values, and never invents a density for a serving whose unit cannot be
reconciled with the source denominator.

The legacy ``/api/v2/search`` endpoint (world.openfoodfacts.org) started
returning a persistent 401 on every page in August 2026, confirmed from three
independent networks (a sandboxed CI-style environment, a GitHub Actions
runner, and a residential connection) - not an IP-reputation block, and not
something an API key fixes (the endpoint requires none). Search-a-licious is
Open Food Facts' own recommended replacement for structured search and was
verified working (real Australian products, real nutriments) before this
importer was switched to it. ``--api-url`` can still override this if the
legacy endpoint ever recovers or another mirror is preferred.

The default output targets the enhanced ``foods`` table in
``supabase/migrations/001_macro_foundation.sql``.  ``--legacy-schema`` keeps
the original eleven-column output contract from the first prototype.
"""

from __future__ import annotations

import argparse
import csv
import gzip
import io
import json
import sys
import time
from collections import Counter
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Optional, Tuple

from seed_common import (
    KJ_PER_KCAL,
    ONE_HUNDRED,
    basis_from_text,
    clean_text,
    iter_delimited_rows,
    open_text,
    parse_decimal,
    parse_serving_size,
    safe_numeric_map,
    tag_values,
    write_food_sql,
)


API_URL = "https://search.openfoodfacts.org/search"
AUSTRALIA_QUERY = 'countries_tags:"en:australia"'
SOURCE_NAME = "openfoodfacts"
REQUIRED_KEYS = ("protein", "carbs", "fat")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Import Australian Open Food Facts products into PostgreSQL INSERT statements."
    )
    parser.add_argument(
        "--input",
        type=Path,
        help="Local OFF CSV/TSV, JSON, JSONL, or gzip-compressed export. Without it, use the API.",
    )
    parser.add_argument(
        "--input-url",
        help=(
            "Remote OFF bulk export (.jsonl or .jsonl.gz) to stream directly, e.g. the "
            "world.openfoodfacts.org/data full export. Decompressed on the fly, never "
            "written to disk in full. Ignored if --input is set; takes precedence over "
            "the live search API."
        ),
    )
    parser.add_argument("--output", type=Path, default=Path("seed_foods_off.sql"))
    parser.add_argument("--api-url", default=API_URL)
    parser.add_argument("--page-size", type=int, default=100)
    parser.add_argument("--max-pages", type=int, default=0, help="0 means all available API pages")
    parser.add_argument(
        "--start-page",
        type=int,
        default=1,
        help="API page to begin fetching from, for resuming a previous run instead of refetching page 1.",
    )
    parser.add_argument("--sleep-seconds", type=float, default=0.25)
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument(
        "--user-agent",
        default="macro-seed-import/1.1 (Open Food Facts API import; contact project owner)",
    )
    parser.add_argument("--keep-duplicate-barcodes", action="store_true")
    parser.add_argument(
        "--legacy-schema",
        action="store_true",
        help="Emit only the original foods(name, brand, barcode, serving..., source, external_id) columns.",
    )
    return parser.parse_args()


def iter_json_lines(handle: Iterable[str]) -> Iterator[Dict[str, Any]]:
    """Parse one JSON value per line, e.g. an OFF .jsonl/.jsonl.gz export."""
    for line_number, line in enumerate(handle, start=1):
        text = line.strip()
        if not text:
            continue
        try:
            value = json.loads(text)
        except json.JSONDecodeError:
            yield {"__parse_error__": "line {}".format(line_number)}
            continue
        if isinstance(value, dict):
            products = value.get("products")
            if isinstance(products, list):
                yield from (item for item in products if isinstance(item, dict))
            else:
                yield value
        elif isinstance(value, list):
            yield from (item for item in value if isinstance(item, dict))


def iter_json_products(path: Path) -> Iterator[Dict[str, Any]]:
    lower = str(path).lower()
    if lower.endswith((".jsonl", ".ndjson", ".jsonl.gz", ".ndjson.gz")):
        with open_text(path) as handle:
            yield from iter_json_lines(handle)
        return

    with open_text(path) as handle:
        value = json.load(handle)
    if isinstance(value, dict):
        products = value.get("products")
        if isinstance(products, list):
            yield from (item for item in products if isinstance(item, dict))
        else:
            yield value
    elif isinstance(value, list):
        yield from (item for item in value if isinstance(item, dict))


def iter_local_products(path: Path) -> Iterator[Dict[str, Any]]:
    if not path.exists():
        raise FileNotFoundError(path)
    lower = str(path).lower()
    if lower.endswith((".csv", ".tsv", ".tab", ".csv.gz", ".tsv.gz", ".tab.gz")):
        yield from iter_delimited_rows(path)
    elif lower.endswith((".json", ".jsonl", ".ndjson", ".json.gz", ".jsonl.gz", ".ndjson.gz")):
        yield from iter_json_products(path)
    else:
        raise ValueError("Unsupported --input type; use OFF CSV/TSV, JSON/JSONL, or .gz variants.")


def get_requests():
    try:
        import requests
    except ImportError as exc:  # pragma: no cover - only reached for API use
        raise RuntimeError("API mode needs requests; run python -m pip install requests") from exc
    return requests


def iter_remote_jsonl_products(url: str, timeout: float, user_agent: str) -> Iterator[Dict[str, Any]]:
    """Stream a remote OFF bulk export line by line, decompressing on the fly.

    Never buffers the compressed or decompressed export to disk or fully in
    memory - a multi-gigabyte world dump is read as a stream of lines, the
    same as a local --input file, so Australia-filtering happens per line in
    import_products() rather than after a full download.
    """
    requests = get_requests()
    response = requests.get(url, stream=True, timeout=timeout, headers={"User-Agent": user_agent})
    response.raise_for_status()
    raw = response.raw
    stream = gzip.GzipFile(fileobj=raw) if url.lower().endswith(".gz") else raw
    handle = io.TextIOWrapper(stream, encoding="utf-8-sig")
    yield from iter_json_lines(handle)


def iter_api_products(
    args: argparse.Namespace, progress: Optional[List[int]] = None
) -> Iterator[Dict[str, Any]]:
    requests = get_requests()
    page_size = max(1, min(args.page_size, 100))
    fields = [
        "code",
        "product_name",
        "product_name_en",
        "brands",
        "serving_size",
        "nutrition_data_per",
        "countries_tags",
        "nutriments",
        "ingredients_text",
        "allergens_tags",
        "categories_tags",
        "image_url",
        "last_modified_t",
    ]
    session = requests.Session()
    headers = {"User-Agent": args.user_agent, "Accept": "application/json"}
    page = max(1, getattr(args, "start_page", 1))
    pages_fetched = 0
    while True:
        params = {
            "q": AUSTRALIA_QUERY,
            "langs": "en",
            "fields": ",".join(fields),
            "page": page,
            "page_size": page_size,
        }
        response = None
        for attempt in range(4):
            response = session.get(args.api_url, params=params, headers=headers, timeout=args.timeout)
            if response.status_code not in {429, 500, 502, 503, 504} or attempt == 3:
                break
            retry_after = response.headers.get("Retry-After")
            try:
                delay = max(1.0, float(retry_after)) if retry_after else 2.0**attempt
            except ValueError:
                delay = 2.0**attempt
            time.sleep(min(delay, 60.0))
        assert response is not None
        response.raise_for_status()
        payload = response.json()
        if "errors" in payload:
            # Search-a-licious reports query-level problems as a 200 response
            # with an "errors" list (ErrorSearchResponse) instead of an HTTP
            # error status. Surface it rather than silently treating it as
            # "no more products" - that would hide a real broken query.
            print(
                "WARNING: search API returned errors instead of results: {}".format(
                    payload["errors"]
                ),
                file=sys.stderr,
            )
            break
        products = payload.get("hits", [])
        if not isinstance(products, list) or not products:
            break
        yield from (item for item in products if isinstance(item, dict))
        if progress is not None:
            # Only reached once the consumer has pulled every product from
            # this page, so a page is never marked done until it's actually
            # been handed off - a later failure on the *next* page can't
            # retroactively make this one look incomplete.
            progress[0] = page
        pages_fetched += 1
        page_count = payload.get("page_count")
        # max_pages counts pages fetched *this run*, not an absolute page
        # number - comparing directly against `page` broke every resumed
        # run once start_page passed max_pages (e.g. resuming at page 11
        # with max_pages=8 would stop after a single page every time).
        if args.max_pages and pages_fetched >= args.max_pages:
            break
        if isinstance(page_count, int) and page >= page_count:
            break
        if len(products) < page_size:
            break
        page += 1
        if args.sleep_seconds > 0:
            time.sleep(args.sleep_seconds)


def is_australian_product(product: Dict[str, Any]) -> bool:
    countries = tag_values(product.get("countries_tags"))
    if "en:australia" in countries:
        return True
    # The v2 API also exposes a language-stripped field in some responses.
    english = tag_values(product.get("countries_tags_en"))
    return "australia" in english or "en:australia" in english


def get_nutriment(product: Dict[str, Any], key: str) -> Any:
    values = product.get("nutriments")
    if isinstance(values, dict) and key in values:
        return values.get(key)
    return product.get(key)


def first_text(product: Dict[str, Any], *keys: str) -> str:
    for key in keys:
        text = clean_text(product.get(key))
        if text:
            return text
    return ""


def nutrient_candidate(product: Dict[str, Any], names: Iterable[str], unit: str) -> Tuple[Any, str]:
    suffixes = ["100{}".format(unit), "100g" if unit == "ml" else "100ml"]
    for name in names:
        for suffix in suffixes:
            key = "{}_{}".format(name, suffix)
            value = get_nutriment(product, key)
            if parse_decimal(value, allow_negative=False) is not None:
                return value, key
    return None, ""


def macro_values(product: Dict[str, Any]) -> Tuple[Dict[str, Decimal], str, List[str]]:
    _, declared_unit = basis_from_text(product.get("nutrition_data_per"))
    quality: List[str] = []
    energy, energy_key = nutrient_candidate(product, ("energy-kcal",), declared_unit)
    if energy is None:
        energy, energy_key = nutrient_candidate(product, ("energy-kj", "energy"), declared_unit)
        energy_number = parse_decimal(energy)
        if energy_number is not None:
            energy = energy_number / KJ_PER_KCAL
            quality.append("energy_converted_from_kj")
    else:
        energy = parse_decimal(energy)
    protein, protein_key = nutrient_candidate(product, ("proteins", "protein"), declared_unit)
    carbs, carbs_key = nutrient_candidate(
        product,
        ("carbohydrates", "carbohydrate", "carbohydrates-without-sugar-alcohols"),
        declared_unit,
    )
    fat, fat_key = nutrient_candidate(product, ("fat", "fats"), declared_unit)
    values = {
        "calories": energy if isinstance(energy, Decimal) else parse_decimal(energy),
        "protein": parse_decimal(protein),
        "carbs": parse_decimal(carbs),
        "fat": parse_decimal(fat),
    }
    if any(value is None for value in values.values()):
        raise ValueError("missing_required_macro")
    actual_key = next((key for key in (energy_key, protein_key, carbs_key, fat_key) if key), "")
    actual_unit = "ml" if actual_key.endswith("100ml") else declared_unit
    return values, actual_unit, quality


def iso_timestamp(value: Any) -> Optional[str]:
    number = parse_decimal(value)
    if number is None:
        return None
    try:
        return datetime.fromtimestamp(float(number), tz=timezone.utc).isoformat()
    except (OverflowError, OSError, ValueError):
        return None


def make_row(product: Dict[str, Any]) -> Dict[str, Any]:
    name = first_text(product, "product_name", "product_name_en")
    barcode = clean_text(product.get("code"))
    if not name:
        raise ValueError("missing_name")
    if not barcode:
        raise ValueError("missing_barcode")

    macros, basis_unit, quality = macro_values(product)
    basis_qty = ONE_HUNDRED
    serving_text = clean_text(product.get("serving_size"))
    serving_qty, serving_unit, explicit = parse_serving_size(serving_text, basis_unit)
    if not explicit or serving_unit != basis_unit:
        # A density conversion such as 1 cup -> grams would be an invention.
        serving_qty, serving_unit = basis_qty, basis_unit
        quality.append("serving_fallback_to_source_basis")
    multiplier = serving_qty / basis_qty
    nutrients = safe_numeric_map(product.get("nutriments"))
    countries = sorted(tag_values(product.get("countries_tags")))
    allergens = sorted(tag_values(product.get("allergens_tags")))
    categories = sorted(tag_values(product.get("categories_tags")))
    return {
        "name": name,
        "brand": first_text(product, "brands") or None,
        "barcode": barcode,
        "serving_qty": serving_qty,
        "serving_unit": serving_unit,
        "calories": macros["calories"] * multiplier,
        "protein_g": macros["protein"] * multiplier,
        "carbs_g": macros["carbs"] * multiplier,
        "fat_g": macros["fat"] * multiplier,
        "source": SOURCE_NAME,
        "external_id": barcode,
        "nutrition_basis_qty": basis_qty,
        "nutrition_basis_unit": basis_unit,
        "serving_size_text": serving_text or None,
        "nutrients": nutrients,
        "ingredients_text": clean_text(product.get("ingredients_text")) or None,
        "allergens": allergens,
        "categories": categories,
        "countries": countries,
        "image_url": clean_text(product.get("image_url")) or None,
        "source_url": "https://world.openfoodfacts.org/product/{}".format(barcode),
        "source_updated_at": iso_timestamp(product.get("last_modified_t")),
        "data_quality": ",".join(sorted(set(quality))) or "source_complete",
    }


def import_products(args: argparse.Namespace) -> Counter:
    last_completed_page = [getattr(args, "start_page", 1) - 1]
    input_url = getattr(args, "input_url", None)
    if args.input:
        products = iter_local_products(args.input)
    elif input_url:
        products = iter_remote_jsonl_products(input_url, args.timeout, args.user_agent)
    else:
        products = iter_api_products(args, progress=last_completed_page)
    counters: Counter = Counter()
    seen_barcodes: set[str] = set()
    rows: List[Dict[str, Any]] = []

    # A local file either parses or it doesn't, so leave that path's
    # exceptions to surface normally instead of masking a real bug in the
    # input file. The live API and a remote --input-url stream can both fail
    # mid-transfer on a transient HTTP error; write whatever was already
    # fetched instead of discarding it.
    recoverable_errors: Tuple[type, ...] = ()
    if args.input is None:
        recoverable_errors = (get_requests().exceptions.RequestException,)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        for product in products:
            counters["read"] += 1
            if "__parse_error__" in product:
                counters["malformed_json"] += 1
                continue
            if not is_australian_product(product):
                counters["not_australia"] += 1
                continue
            try:
                row = make_row(product)
            except ValueError as exc:
                counters[str(exc)] += 1
                continue
            barcode = row["barcode"]
            if not args.keep_duplicate_barcodes and barcode in seen_barcodes:
                counters["duplicate_barcode"] += 1
                continue
            seen_barcodes.add(barcode)
            rows.append(row)
            counters["written"] += 1
    except recoverable_errors as exc:
        print(
            "WARNING: stopping early after a request failure ({}); "
            "writing the {} product(s) already fetched instead of discarding them.".format(
                exc, counters["written"]
            ),
            file=sys.stderr,
        )
        counters["stopped_early_on_error"] = 1

    write_food_sql(rows, args.output, legacy_schema=args.legacy_schema)
    counters["skipped"] = counters["read"] - counters["written"]
    if args.input is None and not input_url:
        counters["next_start_page"] = last_completed_page[0] + 1
    return counters


def main() -> int:
    args = parse_args()
    try:
        counters = import_products(args)
    except Exception as exc:  # keep command-line failures readable
        print("ERROR: {}".format(exc), file=sys.stderr)
        return 1
    print("Open Food Facts SQL written to: {}".format(args.output))
    print("Products read: {}".format(counters["read"]))
    print("Rows written: {}".format(counters["written"]))
    print("Products skipped: {}".format(counters["skipped"]))
    for reason, count in sorted(counters.items()):
        if reason not in {"read", "written", "skipped", "next_start_page"}:
            print("  {}: {}".format(reason, count))
    if "next_start_page" in counters:
        # Machine-readable resume point: a caller (e.g. a CI workflow) can
        # grep this line to pick up from here next time instead of
        # refetching pages already completed by this run.
        print("NEXT_START_PAGE={}".format(counters["next_start_page"]))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
