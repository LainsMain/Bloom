package be.lain.bloom

import be.lain.bloom.data.food.GenericFoodDb
import be.lain.bloom.data.food.OpenFoodFacts
import be.lain.bloom.domain.Meal
import be.lain.bloom.domain.Nutrients
import be.lain.bloom.domain.Recipes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class FoodTest {
    @Test fun unknownStaysUnknownWhenBothSidesUnknown() {
        val a = Nutrients(kcal = 100.0, fiber = null)
        val b = Nutrients(kcal = 50.0, fiber = 2.0)
        val sum = a + b
        assertEquals(150.0, sum.kcal!!, 1e-9)
        assertEquals(2.0, sum.fiber!!, 1e-9)
        assertNull((a + Nutrients(kcal = 1.0)).fiber)
    }

    @Test fun scalingForGrams() {
        val per100 = Nutrients(kcal = 89.0, protein = 1.1)
        val n = per100.forGrams(118.0)
        assertEquals(105.0, n.kcal!!, 0.1)
        assertEquals(1.3, n.protein!!, 0.01)
    }

    @Test fun recipeUsesCookedWeight() {
        // 100 g dry rice (360 kcal) + 300 g water -> 330 g cooked
        val ing = listOf(Recipes.Ingredient(100.0, Nutrients(kcal = 360.0)), Recipes.Ingredient(300.0, Nutrients(kcal = 0.0)))
        assertEquals(109.1, Recipes.per100(ing, 330.0).kcal!!, 0.1)
        assertEquals(90.0, Recipes.per100(ing, null).kcal!!, 0.1)
        assertEquals(400.0, Recipes.totalWeight(ing, null), 0.0)
    }

    @Test fun mealForTime() {
        assertEquals(Meal.BREAKFAST, Meal.forTime(LocalTime.of(7, 30)))
        assertEquals(Meal.LUNCH, Meal.forTime(LocalTime.of(12, 15)))
        assertEquals(Meal.SNACKS, Meal.forTime(LocalTime.of(16, 0)))
        assertEquals(Meal.DINNER, Meal.forTime(LocalTime.of(19, 0)))
        assertEquals(Meal.SNACKS, Meal.forTime(LocalTime.of(23, 0)))
    }

    @Test fun ftsQueryPrefixesWords() {
        assertEquals("brown* rice*", GenericFoodDb.ftsQuery("Brown rice"))
        assertEquals("greek* yoghurt*", GenericFoodDb.ftsQuery("greek-yoghurt!"))
        assertNull(GenericFoodDb.ftsQuery(" a "))
    }

    @Test fun parsesOpenFoodFactsProduct() {
        val json = """{"code":"5410188031072","product_name":"Volle melk","product_name_en":"","brands":"Inza, Delhaize",
            "quantity":"1 l","serving_quantity":"250","serving_size":"250 ml",
            "nutriments":{"energy-kcal_100g":64,"proteins_100g":3.3,"carbohydrates_100g":"4,7","sugars_100g":4.7,
            "fat_100g":3.6,"saturated-fat_100g":2.4,"salt_100g":0.1}}"""
        val c = OpenFoodFacts.parse(Json.parseToJsonElement(json).jsonObject, null)!!
        assertEquals("Volle melk", c.name)
        assertEquals("Inza", c.brand)
        assertEquals("5410188031072", c.barcode)
        assertTrue(c.liquid)
        assertEquals(64.0, c.per100.kcal!!, 0.0)
        assertEquals(4.7, c.per100.carbs!!, 0.0)
        assertNull(c.per100.fiber)
        assertEquals(250.0, c.servings.first().grams, 0.0)
    }

    @Test fun offEnergyFromKilojoulesAndMissingNameRejected() {
        val kj = """{"product_name":"Bar","quantity":"40 g","nutriments":{"energy_100g":2092}}"""
        val c = OpenFoodFacts.parse(Json.parseToJsonElement(kj).jsonObject, "123")!!
        assertEquals(500.0, c.per100.kcal!!, 0.1)
        assertFalse(c.liquid)
        assertEquals(40.0, c.servings.single().grams, 0.0)
        val noName = """{"nutriments":{"energy-kcal_100g":10}}"""
        assertNull(OpenFoodFacts.parse(Json.parseToJsonElement(noName).jsonObject, "1"))
    }
}
