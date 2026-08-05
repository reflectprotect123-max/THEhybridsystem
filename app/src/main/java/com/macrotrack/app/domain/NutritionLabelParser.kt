package com.macrotrack.app.domain

/**
 * One recognized line of text and its bounding box, decoupled from ML Kit's
 * own `Text.Line` type so this parser has zero Android/ML-Kit dependency and
 * can be unit-tested with plain fixtures. The UI layer maps ML Kit's result
 * into these before calling [NutritionLabelParser.parse].
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/**
 * Result of attempting to read a nutrition panel photo. Every field is
 * independently nullable: a field the parser isn't confident about is left
 * null rather than guessed (CLAUDE.md rule #1), and the caller must leave
 * that form field blank/unchanged rather than inventing a value.
 */
data class ParsedNutritionLabel(
    val calories: Double? = null,
    val proteinG: Double? = null,
    val carbsG: Double? = null,
    val fatG: Double? = null,
    val servingQty: Double? = null,
    val servingUnit: String? = null,
) {
    val isEmpty: Boolean
        get() = calories == null && proteinG == null && carbsG == null &&
            fatG == null && servingQty == null && servingUnit == null
}

/**
 * Parses OCR'd nutrition-panel text into structured macros. Australian
 * panels lay the "per serving" and "per 100g" columns out as a table; OCR
 * frequently returns the label and its two value columns as separate lines
 * rather than one line of table-aware text, and can return them out of
 * left-to-right/top-to-bottom order. This groups lines into rows by vertical
 * position first (not by text order), then reads left-to-right within a row,
 * so table-shape errors in the OCR pass don't silently produce a wrong
 * label/value pairing.
 */
object NutritionLabelParser {
    /** Same conversion this repo's importer uses (`seed_common.KJ_PER_KCAL`), kept in sync for consistency. */
    private const val KJ_PER_KCAL = 4.184

    fun parse(lines: List<OcrLine>): ParsedNutritionLabel {
        val rows = groupIntoRows(lines)
        var calories: Double? = null
        var proteinG: Double? = null
        var fatG: Double? = null
        var carbsG: Double? = null

        for (row in rows) {
            val label = row.getOrNull(0)?.text ?: continue
            val valueCell = row.getOrNull(1)?.text ?: continue
            when {
                calories == null && isEnergyLabel(label) -> calories = parseEnergyKcal(valueCell)
                proteinG == null && isProteinLabel(label) -> proteinG = firstNumber(valueCell)
                fatG == null && isFatTotalLabel(label) -> fatG = firstNumber(valueCell)
                carbsG == null && isCarbohydrateTotalLabel(label) -> carbsG = firstNumber(valueCell)
            }
        }

        val serving = parseServingSize(lines)
        return ParsedNutritionLabel(
            calories = calories,
            proteinG = proteinG,
            carbsG = carbsG,
            fatG = fatG,
            servingQty = serving?.first,
            servingUnit = serving?.second,
        )
    }

    private fun groupIntoRows(lines: List<OcrLine>): List<List<OcrLine>> {
        val verticalTolerancePx = estimateRowTolerancePx(lines)
        val sorted = lines.sortedBy { verticalCenter(it) }
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in sorted) {
            val center = verticalCenter(line)
            val currentRow = rows.lastOrNull()
            val rowCenter = currentRow?.map { verticalCenter(it) }?.average()
            if (currentRow != null && rowCenter != null && kotlin.math.abs(center - rowCenter) <= verticalTolerancePx) {
                currentRow.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }
        return rows.map { row -> row.sortedBy { it.left } }
    }

    private const val MIN_ROW_TOLERANCE_PX = 12.0

    /**
     * A fixed pixel tolerance only makes sense at one photo resolution. This
     * screen captures a full-resolution still (often thousands of pixels
     * tall) rather than a small preview frame, so the tolerance is scaled to
     * the label's own line height instead - keeping row grouping correct
     * regardless of the source photo's resolution. The *minimum* line height
     * is used rather than the median/average: a photo can pick up as much
     * unrelated, much taller text (a title, ingredients list) as there are
     * lines in the macro table itself, and a median/average is only safe
     * while the table's own lines are a majority - once roughly half the
     * recognized lines are taller outliers, a median-based tolerance can tip
     * wide enough to chain-merge separate macro rows into one, misreading a
     * value from the wrong row entirely. The table's own text is normally
     * the smallest, most tightly and consistently set text on the panel, so
     * anchoring on the minimum keeps the tolerance tied to the table
     * regardless of how much larger unrelated text also appears.
     */
    private fun estimateRowTolerancePx(lines: List<OcrLine>): Double {
        if (lines.isEmpty()) return MIN_ROW_TOLERANCE_PX
        val minHeight = lines.minOf { (it.bottom - it.top).toDouble() }
        return maxOf(MIN_ROW_TOLERANCE_PX, minHeight * 0.5)
    }

    private fun verticalCenter(line: OcrLine): Double = (line.top + line.bottom) / 2.0

    private fun isEnergyLabel(text: String) = text.trim().lowercase().startsWith("energy")
    private fun isProteinLabel(text: String) = text.trim().lowercase().startsWith("protein")

    private fun isFatTotalLabel(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.startsWith("fat") && "saturated" !in normalized
    }

    private fun isCarbohydrateTotalLabel(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return normalized.startsWith("carbohydrate") && "sugar" !in normalized
    }

    private val NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")

    private fun firstNumber(text: String): Double? = NUMBER_REGEX.find(text)?.value?.toDoubleOrNull()

    private fun parseEnergyKcal(cellText: String): Double? {
        val lower = cellText.lowercase()
        val calMatch = Regex("""(\d+(?:\.\d+)?)\s*(?:kcal|cal)\b""").find(lower)
        if (calMatch != null) return calMatch.groupValues[1].toDoubleOrNull()
        val kjMatch = Regex("""(\d+(?:\.\d+)?)\s*kj\b""").find(lower)
        return kjMatch?.groupValues?.get(1)?.toDoubleOrNull()?.let { it / KJ_PER_KCAL }
    }

    private val SERVING_SIZE_REGEX = Regex("""(\d+(?:\.\d+)?)\s*(g|ml|mg|kg|l)\b""", RegexOption.IGNORE_CASE)

    private fun parseServingSize(lines: List<OcrLine>): Pair<Double, String>? {
        val servingLine = lines.firstOrNull { "serving size" in it.text.lowercase() } ?: return null
        val parenMatch = Regex("""\(([^)]*)\)""").find(servingLine.text)
        val searchText = parenMatch?.groupValues?.get(1) ?: servingLine.text
        val match = SERVING_SIZE_REGEX.find(searchText) ?: return null
        val qty = match.groupValues[1].toDoubleOrNull() ?: return null
        return qty to match.groupValues[2].lowercase()
    }
}
