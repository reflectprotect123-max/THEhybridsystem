package com.macroplus.app.domain

/**
 * Anything ServingScaler can scale: a row whose macros are stored per
 * `servingQty` of `servingUnit`. Food and CustomFood both implement this —
 * see ServingScaler's KDoc for why cross-unit conversion is never guessed.
 */
interface Scalable {
    val id: String
    val name: String
    val servingQty: Double
    val servingUnit: String
    val calories: Double
    val proteinG: Double
    val carbsG: Double
    val fatG: Double
}
