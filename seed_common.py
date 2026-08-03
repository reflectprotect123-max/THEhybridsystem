#!/usr/bin/env python3
"""Small standard-library helpers shared by the food seed importers."""

from __future__ import annotations

import gzip
import json
import re
from decimal import Decimal, InvalidOperation
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, Optional, Sequence, Tuple


BATCH_SIZE = 500
ONE_HUNDRED = Decimal("100")
KJ_PER_KCAL = Decimal("4.184")

LEGACY_FOOD_COLUMNS = (
    "name",
    "brand",
    "barcode",
    "serving_qty",
    "serving_unit",
    "calories",
    "protein_g",
    "carbs_g",
    "fat_g",
    "source",
    "external_id",
)

# The migration in supabase/migrations/001_macro_foundation.sql adds these
# columns while retaining the original eleven-column foods contract.
ENHANCED_FOOD_COLUMNS = LEGACY_FOOD_COLUMNS + (
    "nutrition_basis_qty",
    "nutrition_basis_unit",
    "serving_size_text",
    "nutrients",
    "ingredients_text",
    "allergens",
    "categories",
    "countries",
    "image_url",
    "source_url",
    "source_updated_at",
    "data_quality",
)

MISSING = {"", "na", "n/a", "null", "none", "unknown", "-", "--", "nan", "—"}
SERVING_RE = re.compile(
    r"(?<![0-9])([0-9]+(?:[.,][0-9]+)?)\s*"
    r"(kilograms?|kg|grams?|g|milligrams?|mg|centilitres?|centiliters?|cl|"
    r"litres?|liters?|l|millilitres?|milliliters?|ml|ounces?|oz|pounds?|lb)\b",
    re.IGNORECASE,
)


def clean_text(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, (list, tuple, set)):
        return ", ".join(
            cleaned for item in value if (cleaned := clean_text(item))
        )
    text = str(value).replace("\x00", " ").replace("\r", " ").replace("\n", " ")
    return " ".join(text.split()).strip()


def parse_decimal(value: Any, allow_negative: bool = False) -> Optional[Decimal]:
    """Parse a finite source number without turning missing data into zero."""
    if value is None or isinstance(value, bool):
        return None
    text = clean_text(value).casefold()
    if text in MISSING or text in {"trace", "tr", "<lor", "below lor"}:
        return None
    # Handle both decimal-comma exports and thousands separators.
    if "," in text and "." not in text:
        text = text.replace(",", ".") if text.count(",") == 1 else text.replace(",", "")
    elif "," in text and "." in text:
        text = text.replace(",", "")
    text = text.strip("()")
    try:
        number = Decimal(text)
    except (InvalidOperation, ValueError):
        return None
    if not number.is_finite() or (not allow_negative and number < 0):
        return None
    return number


def decimal_sql(value: Any) -> str:
    number = value if isinstance(value, Decimal) else parse_decimal(value, allow_negative=True)
    if number is None or not number.is_finite():
        raise ValueError("Expected a finite numeric value")
    text = format(number, "f")
    if "." in text:
        text = text.rstrip("0").rstrip(".")
    return text or "0"


def sql_literal(value: Any) -> str:
    if value is None or value == "":
        return "NULL"
    if isinstance(value, Decimal):
        return decimal_sql(value)
    text = str(value).replace("'", "''")
    return "'{}'".format(text)


def sql_jsonb(value: Any) -> str:
    if value is None or value == {} or value == [] or value == "":
        return "NULL"
    payload = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return "{}::jsonb".format(sql_literal(payload))


def open_text(path: Path):
    if str(path).lower().endswith(".gz"):
        return gzip.open(path, "rt", encoding="utf-8-sig", newline="")
    return path.open("r", encoding="utf-8-sig", newline="")


def tag_values(value: Any) -> set[str]:
    if value is None:
        return set()
    if isinstance(value, (list, tuple, set)):
        return {clean_text(item).casefold() for item in value if clean_text(item)}
    return {
        item.strip().casefold()
        for item in re.split(r"[,;|]", clean_text(value))
        if item.strip()
    }


def normalise_quantity(quantity: Decimal, unit: str) -> Tuple[Decimal, str]:
    unit = unit.casefold()
    if unit in {"kg", "kilogram", "kilograms"}:
        return quantity * Decimal("1000"), "g"
    if unit in {"mg", "milligram", "milligrams"}:
        return quantity / Decimal("1000"), "g"
    if unit in {"oz", "ounce", "ounces"}:
        return quantity * Decimal("28.349523125"), "g"
    if unit in {"lb", "pound", "pounds"}:
        return quantity * Decimal("453.59237"), "g"
    if unit in {"cl", "centilitre", "centilitres", "centiliter", "centiliters"}:
        return quantity * Decimal("10"), "ml"
    if unit in {"l", "liter", "litre", "liters", "litres"}:
        return quantity * Decimal("1000"), "ml"
    if unit in {"ml", "milliliter", "milliliters", "millilitre", "millilitres"}:
        return quantity, "ml"
    return quantity, "g"


def parse_serving_size(value: Any, default_unit: str = "g") -> Tuple[Decimal, str, bool]:
    """Return (quantity, unit, had_explicit_mass_or_volume)."""
    text = clean_text(value)
    if text:
        # Prefer the final explicit mass/volume expression, e.g. 1 bar (40 g).
        matches = list(SERVING_RE.finditer(text))
        if matches:
            match = matches[-1]
            quantity = parse_decimal(match.group(1))
            if quantity is not None and quantity > 0:
                quantity, unit = normalise_quantity(quantity, match.group(2))
                return quantity, unit, True
    return ONE_HUNDRED, default_unit, False


def basis_from_text(value: Any) -> Tuple[Decimal, str]:
    text = clean_text(value).casefold()
    if "ml" in text or "millil" in text:
        return ONE_HUNDRED, "ml"
    return ONE_HUNDRED, "g"


def safe_numeric_map(values: Any) -> Dict[str, Any]:
    """Keep a compact JSONB profile while preserving numeric source values."""
    if not isinstance(values, dict):
        return {}
    result: Dict[str, Any] = {}
    for key, value in values.items():
        key_text = clean_text(key)
        if not key_text:
            continue
        number = parse_decimal(value, allow_negative=True)
        if number is not None:
            result[key_text] = float(number)
        elif isinstance(value, str) and clean_text(value):
            result[key_text] = clean_text(value)
    return result


def iter_delimited_rows(path: Path) -> Iterator[Dict[str, Any]]:
    """Yield rows from CSV/TSV/TAB/TXT exports using the first line to sniff."""
    import csv

    with open_text(path) as handle:
        sample = handle.read(8192)
        handle.seek(0)
        if path.suffix.casefold() in {".tsv", ".tab"}:
            delimiter = "\t"
        else:
            try:
                delimiter = csv.Sniffer().sniff(sample, delimiters="\t,;").delimiter
            except csv.Error:
                delimiter = ","
        for row in csv.DictReader(handle, delimiter=delimiter):
            yield row


def write_food_sql(rows: Sequence[Dict[str, Any]], output_path: Path, legacy_schema: bool = False) -> None:
    columns = LEGACY_FOOD_COLUMNS if legacy_schema else ENHANCED_FOOD_COLUMNS
    with output_path.open("w", encoding="utf-8", newline="\n") as output:
        output.write("-- Generated by the nutrition seed importer.\n")
        output.write("-- Batches contain at most 500 rows. Review source licensing before distribution.\n")
        output.write("BEGIN;\n\n")
        for start in range(0, len(rows), BATCH_SIZE):
            batch = rows[start : start + BATCH_SIZE]
            output.write("INSERT INTO foods ({})\nVALUES\n".format(", ".join(columns)))
            rendered = []
            for row in batch:
                values = []
                for column in columns:
                    value = row.get(column)
                    if column == "nutrients":
                        values.append(sql_jsonb(value))
                    elif column in {"allergens", "categories", "countries"}:
                        if not value:
                            values.append("NULL")
                        else:
                            values.append("ARRAY[{}]::text[]".format(
                                ", ".join(sql_literal(item) for item in value)
                            ))
                    else:
                        values.append(sql_literal(value))
                rendered.append("  ({})".format(", ".join(values)))
            output.write(",\n".join(rendered))
            output.write(";\n\n")
        output.write("COMMIT;\n")

