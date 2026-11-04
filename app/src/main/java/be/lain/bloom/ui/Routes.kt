package be.lain.bloom.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Restaurant
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation3.runtime.NavKey
import be.lain.bloom.domain.Meal
import kotlinx.serialization.Serializable

sealed interface Route : NavKey {
    @Serializable data object Today : Route
    @Serializable data object Food : Route
    @Serializable data object Train : Route
    @Serializable data object Progress : Route
    @Serializable data object Coach : Route
    @Serializable data object Settings : Route
    @Serializable data object EditProfile : Route
    @Serializable data object WeeklyReview : Route
    @Serializable data object Cycle : Route
    @Serializable data object PhotoCompare : Route
    @Serializable data object ProgressCamera : Route
    @Serializable data class ReviewDetail(val id: Long) : Route

    // Food
    @Serializable data class FoodSearch(val date: Long, val meal: Meal) : Route
    @Serializable data class LogFood(val foodId: Long, val date: Long, val meal: Meal, val entryId: Long? = null) : Route
    @Serializable data class EditEntry(val entryId: Long) : Route
    @Serializable data class Scan(val date: Long, val meal: Meal) : Route
    @Serializable data class FoodEditor(
        val foodId: Long? = null,
        val barcode: String? = null,
        val date: Long? = null,
        val meal: Meal? = null,
        /** Start by asking the AI to find this barcode / use a label photo. */
        val aiMode: String? = null,
    ) : Route
    @Serializable data object Recipes : Route
    @Serializable data class RecipeEditor(val recipeId: Long? = null) : Route
    @Serializable data object MyFoods : Route
    @Serializable data class QuickLog(val date: Long, val meal: Meal, val camera: Boolean = false) : Route

    // Training
    @Serializable data class WorkoutPreview(val phase: Int, val session: String, val program: String = be.lain.bloom.domain.training.Program.ID) : Route
    @Serializable data object Programs : Route
    @Serializable data object WorkoutPlayer : Route
    @Serializable data object ExerciseLibrary : Route
    @Serializable data class ExerciseDetail(val id: String) : Route
    @Serializable data class RunActive(val planKey: String, val title: String) : Route
    @Serializable data class RunDetail(val id: Long) : Route
    @Serializable data class RunPlanEditor(val id: Long? = null) : Route
}

enum class Tab(val route: Route, val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    TODAY(Route.Today, "Today", Icons.Outlined.WbSunny, Icons.Rounded.WbSunny),
    FOOD(Route.Food, "Food", Icons.Outlined.Restaurant, Icons.Rounded.Restaurant),
    TRAIN(Route.Train, "Train", Icons.Outlined.FitnessCenter, Icons.Rounded.FitnessCenter),
    PROGRESS(Route.Progress, "Progress", Icons.Outlined.ShowChart, Icons.Rounded.ShowChart),
    COACH(Route.Coach, "Coach", Icons.Outlined.AutoAwesome, Icons.Rounded.AutoAwesome),
}
