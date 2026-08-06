package com.macroplus.app.data

import com.macroplus.app.data.model.CustomFood
import com.macroplus.app.data.model.Food
import com.macroplus.app.data.model.FoodServing
import com.macroplus.app.data.model.Recipe
import com.macroplus.app.data.model.RecipeItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Minimal hand-written fakes of the three repository interfaces
 * RecipeMacroResolver depends on. Only the methods RecipeMacroResolver
 * actually calls (getById/getServings on FoodRepository, getById on
 * CustomFoodRepository, getItems on RecipeRepository) are implemented for
 * real; the rest are TODO() since Kotlin requires every interface member
 * to have a body but nothing here exercises them. TODO()'s NotImplementedError
 * is deliberately NOT IllegalStateException, so a fake accidentally being
 * reached can never be mistaken for the IllegalStateException the throwing
 * tests below assert on.
 */
private class FakeFoodRepository(
    private val foods: Map<String, Food> = emptyMap(),
    private val servings: Map<String, List<FoodServing>> = emptyMap(),
) : FoodRepository {
    override suspend fun findByBarcode(barcode: String): Food? = TODO("not used in this test")
    override suspend fun search(query: String, limit: Int): List<Food> = TODO("not used in this test")
    override suspend fun getServings(foodId: String): List<FoodServing> = servings[foodId] ?: emptyList()
    override suspend fun getById(id: String): Food? = foods[id]
}

private class FakeCustomFoodRepository(
    private val customFoods: Map<String, CustomFood> = emptyMap(),
) : CustomFoodRepository {
    override suspend fun list(): List<CustomFood> = TODO("not used in this test")
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
    ): CustomFood = TODO("not used in this test")
    override suspend fun delete(id: String): Unit = TODO("not used in this test")
    override suspend fun getById(id: String): CustomFood? = customFoods[id]
}

private class FakeRecipeRepository(
    private val items: Map<String, List<RecipeItem>> = emptyMap(),
) : RecipeRepository {
    override suspend fun list(): List<Recipe> = TODO("not used in this test")
    override suspend fun create(name: String, description: String?, instructions: String?, servings: Double): Recipe =
        TODO("not used in this test")
    override suspend fun addItem(
        recipeId: String,
        foodId: String?,
        customFoodId: String?,
        quantity: Double,
        unit: String,
        sortOrder: Int,
    ): RecipeItem = TODO("not used in this test")
    override suspend fun getItems(recipeId: String): List<RecipeItem> = items[recipeId] ?: emptyList()
    override suspend fun getById(id: String): Recipe? = TODO("not used in this test")
    override suspend fun delete(id: String): Unit = TODO("not used in this test")
}

class RecipeMacroResolverTest {

    // 100 g basis: 165 kcal / 31 g protein / 0 g carbs / 3.6 g fat.
    private val chickenBreast = Food(
        id = "food-1", name = "Chicken Breast", brand = null, barcode = null,
        servingQty = 100.0, servingUnit = "g", calories = 165.0, proteinG = 31.0,
        carbsG = 0.0, fatG = 3.6, source = "ausnut", externalId = "1-1",
        nutritionBasisQty = 100.0, nutritionBasisUnit = "g", servingSizeText = "100 g",
    )

    // 250 ml basis: 180 kcal / 30 g protein / 9 g carbs / 2 g fat.
    private val proteinShake = CustomFood(
        id = "custom-1", userId = "user-1", name = "Protein Shake", brand = null, barcode = null,
        servingQty = 250.0, servingUnit = "ml", calories = 180.0, proteinG = 30.0, carbsG = 9.0, fatG = 2.0,
    )

    @Test
    fun resolvesAFoodOnlyRecipeUsingADirectUnitMatch() {
        // 200 g of chicken = 2x the 100 g basis -> 330 / 62 / 0 / 7.2 total.
        // Recipe makes 2 servings -> per serving is exactly the 100 g basis: 165 / 31 / 0 / 3.6.
        val recipe = Recipe(id = "recipe-1", userId = "user-1", name = "Chicken Only", servings = 2.0)
        val item = RecipeItem(
            id = "item-1", recipeId = "recipe-1", foodId = "food-1", customFoodId = null,
            quantity = 200.0, unit = "g", sortOrder = 0,
        )
        val resolver = RecipeMacroResolver(
            recipeRepository = FakeRecipeRepository(mapOf("recipe-1" to listOf(item))),
            foodRepository = FakeFoodRepository(foods = mapOf("food-1" to chickenBreast), servings = mapOf("food-1" to emptyList())),
            customFoodRepository = FakeCustomFoodRepository(),
        )

        val result = runBlocking { resolver.resolvePerServingMacros(recipe) }

        assertEquals(165.0, result.calories, 0.001)
        assertEquals(31.0, result.proteinG, 0.001)
        assertEquals(0.0, result.carbsG, 0.001)
        assertEquals(3.6, result.fatG, 0.001)
    }

    @Test
    fun resolvesACustomFoodOnlyRecipeUsingServingScalerDirectly() {
        // 500 ml of shake = 2x the 250 ml basis -> 360 / 60 / 18 / 4 total.
        // Recipe makes 4 servings -> per serving is half the 250 ml basis: 90 / 15 / 4.5 / 1.
        val recipe = Recipe(id = "recipe-2", userId = "user-1", name = "Shake Only", servings = 4.0)
        val item = RecipeItem(
            id = "item-2", recipeId = "recipe-2", foodId = null, customFoodId = "custom-1",
            quantity = 500.0, unit = "ml", sortOrder = 0,
        )
        val resolver = RecipeMacroResolver(
            recipeRepository = FakeRecipeRepository(mapOf("recipe-2" to listOf(item))),
            foodRepository = FakeFoodRepository(),
            customFoodRepository = FakeCustomFoodRepository(mapOf("custom-1" to proteinShake)),
        )

        val result = runBlocking { resolver.resolvePerServingMacros(recipe) }

        assertEquals(90.0, result.calories, 0.001)
        assertEquals(15.0, result.proteinG, 0.001)
        assertEquals(4.5, result.carbsG, 0.001)
        assertEquals(1.0, result.fatG, 0.001)
    }

    @Test
    fun combinesAFoodItemAndACustomFoodItemInTheSameRecipe() {
        // 100 g chicken (1x basis) = 165 / 31 / 0 / 3.6.
        // 250 ml shake (1x basis) = 180 / 30 / 9 / 2.
        // Sum = 345 / 61 / 9 / 5.6. Recipe makes 2 servings -> per serving = 172.5 / 30.5 / 4.5 / 2.8.
        val recipe = Recipe(id = "recipe-3", userId = "user-1", name = "Chicken and Shake", servings = 2.0)
        val foodItem = RecipeItem(
            id = "item-3a", recipeId = "recipe-3", foodId = "food-1", customFoodId = null,
            quantity = 100.0, unit = "g", sortOrder = 0,
        )
        val customFoodItem = RecipeItem(
            id = "item-3b", recipeId = "recipe-3", foodId = null, customFoodId = "custom-1",
            quantity = 250.0, unit = "ml", sortOrder = 1,
        )
        val resolver = RecipeMacroResolver(
            recipeRepository = FakeRecipeRepository(mapOf("recipe-3" to listOf(foodItem, customFoodItem))),
            foodRepository = FakeFoodRepository(foods = mapOf("food-1" to chickenBreast), servings = mapOf("food-1" to emptyList())),
            customFoodRepository = FakeCustomFoodRepository(mapOf("custom-1" to proteinShake)),
        )

        val result = runBlocking { resolver.resolvePerServingMacros(recipe) }

        assertEquals(172.5, result.calories, 0.001)
        assertEquals(30.5, result.proteinG, 0.001)
        assertEquals(4.5, result.carbsG, 0.001)
        assertEquals(2.8, result.fatG, 0.001)
    }

    @Test
    fun throwsWhenAFoodItemReferencesAFoodThatNoLongerExists() {
        val recipe = Recipe(id = "recipe-4", userId = "user-1", name = "Dangling Food Reference", servings = 1.0)
        val item = RecipeItem(
            id = "item-4", recipeId = "recipe-4", foodId = "food-missing", customFoodId = null,
            quantity = 100.0, unit = "g", sortOrder = 0,
        )
        val resolver = RecipeMacroResolver(
            recipeRepository = FakeRecipeRepository(mapOf("recipe-4" to listOf(item))),
            foodRepository = FakeFoodRepository(), // getById("food-missing") returns null
            customFoodRepository = FakeCustomFoodRepository(),
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { resolver.resolvePerServingMacros(recipe) }
        }
    }

    @Test
    fun throwsWhenARecipeItemHasNeitherFoodIdNorCustomFoodId() {
        val recipe = Recipe(id = "recipe-5", userId = "user-1", name = "Malformed Item", servings = 1.0)
        val item = RecipeItem(
            id = "item-5", recipeId = "recipe-5", foodId = null, customFoodId = null,
            quantity = 100.0, unit = "g", sortOrder = 0,
        )
        val resolver = RecipeMacroResolver(
            recipeRepository = FakeRecipeRepository(mapOf("recipe-5" to listOf(item))),
            foodRepository = FakeFoodRepository(),
            customFoodRepository = FakeCustomFoodRepository(),
        )

        assertThrows(IllegalStateException::class.java) {
            runBlocking { resolver.resolvePerServingMacros(recipe) }
        }
    }
}
