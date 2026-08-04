package com.macrotrack.app.data

import com.macrotrack.app.data.model.Recipe
import com.macrotrack.app.domain.MacroResolution
import com.macrotrack.app.domain.ScaledMacros
import com.macrotrack.app.domain.ServingScaler

/**
 * Resolves a recipe's per-serving macros by fetching each ingredient's
 * Food/CustomFood, scaling it (with the FoodServing fallback for Food
 * ingredients — CustomFood has no food_servings support in the schema, so
 * a CustomFood ingredient only resolves via a direct unit match), summing,
 * then dividing by the recipe's total servings. All the actual math is
 * MacroResolution's — this class is I/O only.
 */
class RecipeMacroResolver(
    private val recipeRepository: RecipeRepository,
    private val foodRepository: FoodRepository,
    private val customFoodRepository: CustomFoodRepository,
) {
    suspend fun resolvePerServingMacros(recipe: Recipe): ScaledMacros {
        val items = recipeRepository.getItems(recipe.id)
        val itemMacros = items.map { item ->
            val foodId = item.foodId
            val customFoodId = item.customFoodId
            when {
                foodId != null -> {
                    val food = foodRepository.getById(foodId)
                        ?: error("Recipe item ${item.id} references missing food $foodId")
                    val servings = foodRepository.getServings(food.id)
                    MacroResolution.resolveFoodMacros(food, servings, item.quantity, item.unit)
                }
                customFoodId != null -> {
                    val customFood = customFoodRepository.getById(customFoodId)
                        ?: error("Recipe item ${item.id} references missing custom food $customFoodId")
                    ServingScaler.scale(customFood, item.quantity, item.unit)
                }
                else -> error("Recipe item ${item.id} has neither food_id nor custom_food_id (violates the DB check constraint)")
            }
        }
        val total = MacroResolution.sumMacros(itemMacros)
        return MacroResolution.perServing(total, recipe.servings)
    }
}
