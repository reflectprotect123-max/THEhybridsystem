package com.macroplus.app.ui.search

import com.macroplus.app.data.CustomFoodRepository
import com.macroplus.app.data.model.CustomFood
import com.macroplus.app.domain.ParsedNutritionLabel
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeCustomFoodRepository : CustomFoodRepository {
    override suspend fun list(): List<CustomFood> = emptyList()

    override suspend fun create(
        name: String,
        brand: String?,
        servingQty: Double,
        servingUnit: String,
        calories: Double,
        proteinG: Double,
        carbsG: Double,
        fatG: Double,
        barcode: String?,
    ): CustomFood = throw NotImplementedError("not exercised by this test")

    override suspend fun delete(id: String) {
        throw NotImplementedError("not exercised by this test")
    }

    override suspend fun getById(id: String): CustomFood? =
        throw NotImplementedError("not exercised by this test")
}

class CreateCustomFoodViewModelTest {

    @Test
    fun prefillsBlankFieldsFromAScannedLabelButNeverOverwritesUserEnteredValues() {
        val viewModel = CreateCustomFoodViewModel(FakeCustomFoodRepository())
        viewModel.onProteinChanged("50") // user already typed this in before scanning

        viewModel.onNutritionLabelScanned(
            ParsedNutritionLabel(
                calories = 200.0,
                proteinG = 8.0,
                carbsG = null,
                fatG = 3.2,
                servingQty = 30.0,
                servingUnit = "g",
            ),
        )

        val state = viewModel.uiState.value
        assertEquals("200.0", state.calories)
        assertEquals("50", state.proteinG) // untouched - user's own entry wins
        assertEquals("", state.carbsG) // parser was null, stays blank
        assertEquals("3.2", state.fatG)
        assertEquals("30.0", state.servingQty)
        assertEquals("g", state.servingUnit)
    }

    @Test
    fun leavesEveryFieldBlankWhenTheScannedLabelHasNoRecognizedValues() {
        val viewModel = CreateCustomFoodViewModel(FakeCustomFoodRepository())

        viewModel.onNutritionLabelScanned(ParsedNutritionLabel())

        val state = viewModel.uiState.value
        assertEquals("", state.calories)
        assertEquals("", state.proteinG)
        assertEquals("", state.carbsG)
        assertEquals("", state.fatG)
        assertEquals("", state.servingQty)
        assertEquals("", state.servingUnit)
    }
}
