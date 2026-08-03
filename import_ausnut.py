#!/usr/bin/env python3
"""Create PostgreSQL seed INSERTs from AUSNUT/NUTTAB-style source files.

The importer accepts CSV/TSV/TAB/TXT and XLSX files using only the Python
standard library.  It understands:

* AUSNUT/AFCD-style wide nutrient profiles (one row per food); and
* NUTTAB-style long nutrient tables (one row per food/nutrient), with an
  optional food-details file and optional food-measures file.

The default output targets the enhanced ``foods`` table.  Use
``--legacy-schema`` to emit the original eleven-column contract.
"""

from __future__ import annotations

import argparse
import csv
import itertools
import json
import posixpath
import re
import sys
import unicodedata
import zipfile
import xml.etree.ElementTree as ET
from collections import Counter
from decimal import Decimal
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Optional, Sequence, Tuple

from seed_common import (
    KJ_PER_KCAL,
    ONE_HUNDRED,
    clean_text,
    open_text,
    parse_decimal,
    write_food_sql,
)


SOURCE_URL = "https://www.foodstandards.gov.au/science-data/food-nutrient-databases/ausnut"
ID_ALIASES = (
    "food identification code",
    "food identification id",
    "food id",
    "survey food id",
    "food code",
    "food identification",
)
NAME_ALIASES = ("food name", "food description", "standard name", "name", "description")
ENERGY_ALIASES = (
    "energy with dietary fibre kj",
    "energy with dietary fiber kj",
    "energy without dietary fibre kj",
    "energy without dietary fiber kj",
    "energy kj",
    "energy kilojoules",
    "energy kcal",
    "energy kilocalories",
    "energy",
)
PROTEIN_ALIASES = ("protein g", "protein grams", "protein")
CARB_ALIASES = (
    "available carbohydrates without sugar alcohol g",
    "available carbohydrate excluding sugar alcohols g",
    "available carbohydrate excluding sugar alcohol g",
    "available carbohydrates excluding sugar alcohols g",
    "available carbohydrates excluding sugar alcohol g",
    "available carbohydrate including sugar alcohols g",
    "available carbohydrate including sugar alcohol g",
    "total carbohydrate g",
    "total carbohydrates g",
    "carbohydrate by difference g",
    "carbohydrate g",
    "carbohydrates g",
    "carbohydrate",
)
FAT_ALIASES = ("total fat g", "total lipid g", "fat g", "total fat", "fat")


def normalize(value: Any) -> str:
    text = unicodedata.normalize("NFKD", clean_text(value))
    text = text.encode("ascii", "ignore").decode("ascii").casefold()
    return " ".join(re.sub(r"[^a-z0-9]+", " ", text).split())


def id_text(value: Any) -> str:
    text = clean_text(value)
    if re.fullmatch(r"[0-9]+\.0+", text):
        return text.split(".", 1)[0]
    return text


def col_key(headers: Sequence[str], aliases: Iterable[str]) -> Optional[str]:
    normalized = [normalize(header) for header in headers]
    for alias in aliases:
        wanted = normalize(alias)
        if wanted in normalized:
            return wanted
    for alias in aliases:
        wanted = normalize(alias)
        for key in normalized:
            if key.startswith(wanted + " ") or key.endswith(" " + wanted):
                return key
    return None


def row_value(row: Dict[str, Any], key: Optional[str]) -> Any:
    return row.get(key) if key else None


def col_letters_to_index(reference: str) -> Optional[int]:
    match = re.match(r"([A-Za-z]+)", reference or "")
    if not match:
        return None
    result = 0
    for character in match.group(1).upper():
        result = result * 26 + ord(character) - ord("A") + 1
    return result - 1


def xlsx_shared_strings(archive: zipfile.ZipFile) -> List[str]:
    try:
        root = ET.fromstring(archive.read("xl/sharedStrings.xml"))
    except KeyError:
        return []
    ns = {"x": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
    return [
        "".join(node.text or "" for node in item.iterfind(".//x:t", ns))
        for item in root.findall("x:si", ns)
    ]


def iter_xlsx_sheets(path: Path) -> Iterator[Tuple[str, List[List[str]]]]:
    ns = {
        "x": "http://schemas.openxmlformats.org/spreadsheetml/2006/main",
        "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
        "pr": "http://schemas.openxmlformats.org/package/2006/relationships",
    }
    with zipfile.ZipFile(path) as archive:
        shared = xlsx_shared_strings(archive)
        workbook = ET.fromstring(archive.read("xl/workbook.xml"))
        relationships = ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))
        rel_map = {
            rel.attrib["Id"]: rel.attrib["Target"]
            for rel in relationships.findall("pr:Relationship", ns)
        }
        for sheet in workbook.findall("x:sheets/x:sheet", ns):
            name = sheet.attrib.get("name", "Sheet")
            rel_id = sheet.attrib.get("{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id")
            target = rel_map.get(rel_id)
            if not target:
                continue
            target = target.replace("\\", "/")
            if target.startswith("/"):
                target = target.lstrip("/")
            else:
                target = posixpath.normpath(posixpath.join("xl", target))
            try:
                root = ET.fromstring(archive.read(target))
            except KeyError:
                continue
            matrix: List[List[str]] = []
            for row_element in root.findall(".//x:sheetData/x:row", ns):
                cells: List[str] = []
                for cell in row_element.findall("x:c", ns):
                    index = col_letters_to_index(cell.attrib.get("r", ""))
                    if index is None:
                        continue
                    while len(cells) <= index:
                        cells.append("")
                    cell_type = cell.attrib.get("t")
                    if cell_type == "inlineStr":
                        cell_value = "".join(
                            node.text or "" for node in cell.findall("x:is/.//x:t", ns)
                        )
                    else:
                        value_element = cell.find("x:v", ns)
                        cell_value = value_element.text if value_element is not None else ""
                        if cell_type == "s" and cell_value:
                            try:
                                cell_value = shared[int(cell_value)]
                            except (ValueError, IndexError):
                                cell_value = ""
                        elif cell_type == "b":
                            cell_value = "TRUE" if cell_value == "1" else "FALSE"
                    cells[index] = cell_value
                matrix.append(cells)
            yield name, matrix


def iter_text_sheets(path: Path) -> Iterator[Tuple[str, List[List[str]]]]:
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
        yield "Sheet1", list(csv.reader(handle, delimiter=delimiter))


def source_matrices(path: Path) -> Iterator[Tuple[str, List[List[str]]]]:
    lower = str(path).lower()
    if lower.endswith((".xlsx", ".xlsm")):
        yield from iter_xlsx_sheets(path)
    elif lower.endswith(".xls"):
        raise ValueError("Binary .xls is not supported without third-party packages; save it as XLSX or CSV first.")
    else:
        yield from iter_text_sheets(path)


def header_score(headers: Sequence[str], kind: str) -> int:
    id_key = col_key(headers, ID_ALIASES)
    name_key = col_key(headers, NAME_ALIASES)
    if kind == "foods":
        return (2 if id_key else 0) + (2 if name_key else 0)
    if kind == "measures":
        desc = col_key(headers, ("measure description 1", "measure description", "measure", "description"))
        grams = col_key(headers, ("weight in grams", "weight in grams 1", "weight g", "grams"))
        volume = col_key(headers, ("volume in mls", "volume in ml", "volume ml", "millilitres", "milliliters"))
        return (2 if id_key else 0) + (1 if desc else 0) + (1 if grams or volume else 0)
    wide = sum(
        1
        for aliases in (ENERGY_ALIASES, PROTEIN_ALIASES, CARB_ALIASES, FAT_ALIASES)
        if col_key(headers, aliases)
    )
    long = sum(
        1
        for aliases in (
            ("nutrient id", "component id"),
            ("nutrient description", "nutrient", "component", "description"),
            ("value", "nutrient value", "amount"),
        )
        if col_key(headers, aliases)
    )
    return (2 if id_key else 0) + (2 if name_key else 0) + wide + long


def find_table(path: Optional[Path], kind: str) -> Optional[Tuple[List[str], Dict[str, str], List[Dict[str, Any]], str]]:
    if path is None:
        return None
    best: Optional[Tuple[int, str, int, List[str], List[List[str]]]] = None
    for sheet_name, matrix in source_matrices(path):
        for index, cells in enumerate(matrix[:100]):
            headers = [clean_text(cell) for cell in cells]
            score = header_score(headers, kind)
            if best is None or score > best[0]:
                best = (score, sheet_name, index, headers, matrix[index + 1 :])
    minimum = 3 if kind == "nutrients" else 2
    if best is None or best[0] < minimum:
        raise ValueError("Could not identify a {} table in {}.".format(kind, path))
    _, sheet_name, _, headers, matrix = best
    normalized = [normalize(header) for header in headers]
    original_by_key: Dict[str, str] = {}
    for original, key in zip(headers, normalized):
        if key and key not in original_by_key:
            original_by_key[key] = original
    rows: List[Dict[str, Any]] = []
    for cells in matrix:
        if not any(clean_text(cell) for cell in cells):
            continue
        row: Dict[str, Any] = {}
        for index, key in enumerate(normalized):
            if key and key not in row:
                row[key] = cells[index] if index < len(cells) else ""
        rows.append(row)
    return normalized, original_by_key, rows, sheet_name


def build_food_names(table: Optional[Tuple[List[str], Dict[str, str], List[Dict[str, Any]], str]]) -> Dict[str, str]:
    if table is None:
        return {}
    headers, _, rows, _ = table
    id_key = col_key(headers, ID_ALIASES)
    name_key = col_key(headers, NAME_ALIASES)
    if not id_key or not name_key:
        return {}
    return {
        id_text(row_value(row, id_key)): clean_text(row_value(row, name_key))
        for row in rows
        if id_text(row_value(row, id_key)) and clean_text(row_value(row, name_key))
    }


def build_measures(table: Optional[Tuple[List[str], Dict[str, str], List[Dict[str, Any]], str]]) -> Dict[str, Tuple[Decimal, str, str]]:
    if table is None:
        return {}
    headers, _, rows, _ = table
    id_key = col_key(headers, ID_ALIASES)
    desc_key = col_key(headers, ("measure description 1", "measure description", "measure", "description"))
    grams_key = col_key(headers, ("weight in grams", "weight in grams 1", "weight g", "grams"))
    volume_key = col_key(headers, ("volume in mls", "volume in ml", "volume ml", "millilitres", "milliliters"))
    if not id_key:
        return {}
    result: Dict[str, Tuple[Decimal, str, str]] = {}
    for row in rows:
        food_id = id_text(row_value(row, id_key))
        if not food_id or food_id in result:
            continue
        qty = parse_decimal(row_value(row, grams_key)) if grams_key else None
        unit = "g"
        if qty is None or qty <= 0:
            qty = parse_decimal(row_value(row, volume_key)) if volume_key else None
            unit = "ml"
        if qty is not None and qty > 0:
            description = clean_text(row_value(row, desc_key)) or "{} {}".format(qty, unit)
            result[food_id] = (qty, unit, description)
    return result


def numeric_nutrients(row: Dict[str, Any], headers: Sequence[str], original_by_key: Dict[str, str]) -> Dict[str, Any]:
    result: Dict[str, Any] = {}
    for key in headers:
        if not key:
            continue
        number = parse_decimal(row.get(key), allow_negative=True)
        if number is not None:
            result[original_by_key.get(key, key)] = float(number)
    return result


def energy_to_kcal(value: Any, unit_text: Any) -> Tuple[Optional[Decimal], str]:
    number = parse_decimal(value)
    if number is None:
        return None, ""
    text = normalize(unit_text)
    if "kcal" in text or "kilocalorie" in text:
        return number, "source_kcal"
    # AUSNUT/NUTTAB energy columns are normally explicitly kJ.  Treat an
    # otherwise ambiguous energy header as kJ rather than silently pretending
    # it is kcal; the output records the conversion in data_quality.
    return number / KJ_PER_KCAL, "energy_converted_from_kj"


def infer_basis(unit_text: Any) -> str:
    return "ml" if "ml" in normalize(unit_text) or "millil" in normalize(unit_text) else "g"


def macro_from_description(description: Any) -> Tuple[Optional[str], int]:
    text = normalize(description)
    if text.startswith("energy"):
        return "calories", 0
    if text == "protein" or text.startswith("protein "):
        return "protein", 0
    if text.startswith("total fat") or text.startswith("total lipid") or text == "fat" or text.startswith("fat "):
        return "fat", 0
    if "available carbohydrate" in text and (
        "excluding sugar alcohol" in text or "without sugar alcohol" in text
    ):
        return "carbs", 0
    if text.startswith("total carbohydrate") or text.startswith("carbohydrate by difference"):
        return "carbs", 1
    if "available carbohydrate" in text or text in {"carbohydrate", "carbohydrates"}:
        return "carbs", 2
    return None, 99


def profiles_from_wide(table: Tuple[List[str], Dict[str, str], List[Dict[str, Any]], str], names: Dict[str, str]) -> List[Dict[str, Any]]:
    headers, original_by_key, rows, _ = table
    id_key = col_key(headers, ID_ALIASES)
    name_key = col_key(headers, NAME_ALIASES)
    energy_key = col_key(headers, ENERGY_ALIASES)
    protein_key = col_key(headers, PROTEIN_ALIASES)
    carbs_key = col_key(headers, CARB_ALIASES)
    fat_key = col_key(headers, FAT_ALIASES)
    if not id_key or not all((energy_key, protein_key, carbs_key, fat_key)):
        return []
    profiles = []
    for row in rows:
        food_id = id_text(row_value(row, id_key))
        if not food_id:
            continue
        name = clean_text(row_value(row, name_key)) if name_key else names.get(food_id, "")
        energy, energy_quality = energy_to_kcal(row_value(row, energy_key), original_by_key.get(energy_key, energy_key))
        profiles.append({
            "id": food_id,
            "name": name,
            "energy": energy,
            "protein": parse_decimal(row_value(row, protein_key)),
            "carbs": parse_decimal(row_value(row, carbs_key)),
            "fat": parse_decimal(row_value(row, fat_key)),
            "basis_unit": infer_basis(original_by_key.get(energy_key, energy_key)),
            "energy_quality": energy_quality,
            "nutrients": numeric_nutrients(row, headers, original_by_key),
        })
    return profiles


def profiles_from_long(table: Tuple[List[str], Dict[str, str], List[Dict[str, Any]], str], names: Dict[str, str]) -> List[Dict[str, Any]]:
    headers, _, rows, _ = table
    id_key = col_key(headers, ID_ALIASES)
    description_key = col_key(headers, ("nutrient description", "nutrient", "component", "description"))
    value_key = col_key(headers, ("value", "nutrient value", "amount"))
    scale_key = col_key(headers, ("scale", "unit", "units"))
    if not id_key or not description_key or not value_key:
        return []
    grouped: Dict[str, Dict[str, Any]] = {}
    for row in rows:
        food_id = id_text(row_value(row, id_key))
        description = clean_text(row_value(row, description_key))
        nutrient_name, priority = macro_from_description(description)
        number = parse_decimal(row_value(row, value_key))
        if not food_id or not description or number is None:
            continue
        profile = grouped.setdefault(
            food_id,
            {"id": food_id, "name": names.get(food_id, ""), "macros": {}, "nutrients": {}, "scales": {}},
        )
        profile["nutrients"][description] = float(number)
        if nutrient_name is None:
            continue
        old = profile["macros"].get(nutrient_name)
        if old is None or priority < old[0]:
            profile["macros"][nutrient_name] = (priority, number)
            profile["scales"][nutrient_name] = clean_text(row_value(row, scale_key)) if scale_key else description
    profiles = []
    for profile in grouped.values():
        macros = profile["macros"]
        energy_scale = profile["scales"].get("calories", "energy kJ")
        energy, energy_quality = energy_to_kcal(
            macros.get("calories", (99, None))[1], energy_scale
        )
        profiles.append({
            "id": profile["id"],
            "name": profile["name"],
            "energy": energy,
            "protein": macros.get("protein", (99, None))[1],
            "carbs": macros.get("carbs", (99, None))[1],
            "fat": macros.get("fat", (99, None))[1],
            "basis_unit": infer_basis(energy_scale),
            "energy_quality": energy_quality,
            "nutrients": profile["nutrients"],
        })
    return profiles


def make_row(profile: Dict[str, Any], measures: Dict[str, Tuple[Decimal, str, str]], source: str) -> Dict[str, Any]:
    name = clean_text(profile.get("name"))
    if not name:
        raise ValueError("missing_name")
    values = [profile.get("energy"), profile.get("protein"), profile.get("carbs"), profile.get("fat")]
    if any(value is None for value in values):
        raise ValueError("missing_energy_protein_carbs_fat")
    basis_unit = profile.get("basis_unit") or "g"
    serving = measures.get(profile["id"])
    quality = [profile.get("energy_quality") or "source_kcal"]
    if serving is None or serving[1] != basis_unit:
        serving_qty, serving_unit, serving_text = ONE_HUNDRED, basis_unit, "100 {}".format(basis_unit)
        quality.append("serving_fallback_to_source_basis")
    else:
        serving_qty, serving_unit, serving_text = serving
    multiplier = serving_qty / ONE_HUNDRED
    return {
        "name": name,
        "brand": None,
        "barcode": None,
        "serving_qty": serving_qty,
        "serving_unit": serving_unit,
        "calories": profile["energy"] * multiplier,
        "protein_g": profile["protein"] * multiplier,
        "carbs_g": profile["carbs"] * multiplier,
        "fat_g": profile["fat"] * multiplier,
        "source": source,
        "external_id": profile["id"],
        "nutrition_basis_qty": ONE_HUNDRED,
        "nutrition_basis_unit": basis_unit,
        "serving_size_text": serving_text,
        "nutrients": profile.get("nutrients", {}),
        "ingredients_text": None,
        "allergens": [],
        "categories": [],
        "countries": ["australia"],
        "image_url": None,
        "source_url": SOURCE_URL,
        "source_updated_at": None,
        "data_quality": ",".join(sorted(set(quality))),
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Import AUSNUT/NUTTAB nutrient data into PostgreSQL INSERTs.")
    parser.add_argument("--nutrients", type=Path, required=True, help="Wide nutrient profiles or long NUTTAB nutrient table")
    parser.add_argument("--foods", type=Path, help="Optional food-details file for names and IDs")
    parser.add_argument("--measures", type=Path, help="Optional food-measures file for serving quantities")
    parser.add_argument("--output", type=Path, default=Path("seed_foods_ausnut.sql"))
    parser.add_argument("--source", choices=("ausnut", "nuttab", "afcd"), default="ausnut")
    parser.add_argument("--legacy-schema", action="store_true")
    return parser.parse_args()


def import_profiles(args: argparse.Namespace) -> Counter:
    food_table = find_table(args.foods, "foods")
    nutrient_table = find_table(args.nutrients, "nutrients")
    measure_table = find_table(args.measures, "measures")
    names = build_food_names(food_table)
    measures = build_measures(measure_table)
    profiles = profiles_from_wide(nutrient_table, names)
    layout = "wide"
    if not profiles:
        profiles = profiles_from_long(nutrient_table, names)
        layout = "long"
    if not profiles:
        raise ValueError("The nutrient file is neither a supported wide nor long nutrient table.")

    rows: List[Dict[str, Any]] = []
    counters: Counter = Counter(layout=layout, profiles=len(profiles))
    for profile in profiles:
        try:
            rows.append(make_row(profile, measures, args.source))
            counters["written"] += 1
        except ValueError as exc:
            counters[str(exc)] += 1
    counters["skipped"] = len(profiles) - counters["written"]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    write_food_sql(rows, args.output, legacy_schema=args.legacy_schema)
    return counters


def main() -> int:
    args = parse_args()
    try:
        counters = import_profiles(args)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print("ERROR: {}".format(exc), file=sys.stderr)
        return 1
    print("FSANZ SQL written to: {}".format(args.output))
    print("Layout: {}".format(counters["layout"]))
    print("Profiles read: {}".format(counters["profiles"]))
    print("Rows written: {}".format(counters["written"]))
    print("Profiles skipped: {}".format(counters["skipped"]))
    for reason, count in sorted(counters.items()):
        if reason not in {"layout", "profiles", "written", "skipped"}:
            print("  {}: {}".format(reason, count))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
