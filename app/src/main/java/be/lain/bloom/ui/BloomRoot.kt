package be.lain.bloom.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import be.lain.bloom.core.AppMessages
import be.lain.bloom.data.ProfileRepository
import be.lain.bloom.feature.coach.CoachScreen
import be.lain.bloom.feature.run.RunActiveScreen
import be.lain.bloom.feature.run.RunDetailScreen
import be.lain.bloom.feature.run.RunPlanEditorScreen
import be.lain.bloom.feature.run.RunningPane
import be.lain.bloom.feature.train.ExerciseDetailScreen
import be.lain.bloom.feature.train.ExerciseLibraryScreen
import be.lain.bloom.feature.train.TrainScreen
import be.lain.bloom.feature.train.WorkoutPlayerScreen
import be.lain.bloom.feature.train.WorkoutPreviewScreen
import be.lain.bloom.feature.coach.QuickLogScreen
import be.lain.bloom.feature.food.DiaryScreen
import be.lain.bloom.feature.food.FoodEditorScreen
import be.lain.bloom.feature.food.FoodSearchScreen
import be.lain.bloom.feature.food.LogFoodScreen
import be.lain.bloom.feature.food.RecipeEditorScreen
import be.lain.bloom.feature.food.RecipesScreen
import be.lain.bloom.feature.food.ScanScreen
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import be.lain.bloom.feature.common.ComingSoonScreen
import be.lain.bloom.feature.onboarding.OnboardingScreen
import be.lain.bloom.feature.progress.ProgressScreen
import be.lain.bloom.feature.settings.EditProfileScreen
import be.lain.bloom.feature.settings.SettingsScreen
import be.lain.bloom.feature.today.TodayScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class RootState { LOADING, ONBOARDING, READY }

@HiltViewModel
class RootViewModel @Inject constructor(
    profiles: ProfileRepository,
    val messages: AppMessages,
    val deepLinks: be.lain.bloom.service.DeepLinks,
    val updater: be.lain.bloom.data.update.Updater,
    private val milestones: be.lain.bloom.data.MilestoneWatcher,
) : ViewModel() {
    val celebration = milestones.pending.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun dismissCelebration() = viewModelScope.launch { milestones.dismiss() }
    val state: StateFlow<RootState> = profiles.profile
        .map { if (it == null) RootState.ONBOARDING else RootState.READY }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RootState.LOADING)
}

@Composable
fun BloomRoot(vm: RootViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    // Surface provides the content colour that plain Text inherits.
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        when (state) {
            RootState.LOADING -> Unit
            RootState.ONBOARDING -> OnboardingScreen()
            RootState.READY -> {
                MainShell(vm.messages, vm.deepLinks)
                UpdateDialog(vm.updater)
                val celebrate by vm.celebration.collectAsStateWithLifecycle()
                celebrate?.let { Celebration(it, onDone = vm::dismissCelebration) }
            }
        }
    }
}

private fun NavBackStack<NavKey>.switchTo(route: Route) {
    clear()
    add(route)
}

@Composable
fun MainShell(messages: AppMessages, deepLinks: be.lain.bloom.service.DeepLinks) {
    val backStack = rememberNavBackStack(Route.Today)
    val haptics = LocalHapticFeedback.current
    val current = backStack.lastOrNull()
    val currentTab = Tab.entries.firstOrNull { it.route == current }
    val navigate: (Route) -> Unit = { backStack.add(it) }
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    val replace: (Route) -> Unit = { r ->
        if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex)
        backStack.add(r)
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        messages.flow.collect { m ->
            snackbar.currentSnackbarData?.dismiss()
            scope.launch {
                val r = snackbar.showSnackbar(m.text, actionLabel = m.actionLabel, withDismissAction = m.actionLabel != null,
                    duration = if (m.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) m.onAction?.invoke()
            }
        }
    }

    // Notification taps.
    val link by deepLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(link) {
        val l = link ?: return@LaunchedEffect
        deepLinks.pending.value = null
        when (l) {
            be.lain.bloom.service.DeepLinks.REVIEW -> { backStack.switchTo(Route.Today); backStack.add(Route.WeeklyReview) }
            be.lain.bloom.service.DeepLinks.WEIGH_IN -> backStack.switchTo(Route.Progress)
            be.lain.bloom.service.DeepLinks.FOOD -> backStack.switchTo(Route.Food)
            be.lain.bloom.service.DeepLinks.TRAIN -> backStack.switchTo(Route.Train)
            be.lain.bloom.service.DeepLinks.COACH -> backStack.switchTo(Route.Coach)
        }
    }

    // From another tab's root, back returns to Today before leaving the app.
    BackHandler(enabled = backStack.size == 1 && current != Route.Today) { backStack.switchTo(Route.Today) }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.imePadding()) },
        bottomBar = {
            AnimatedVisibility(
                visible = currentTab != null,
                enter = slideInVertically { it },
                exit = slideOutVertically { it },
            ) {
                ShortNavigationBar {
                    Tab.entries.forEach { tab ->
                        val selected = tab == currentTab
                        ShortNavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    backStack.switchTo(tab.route)
                                }
                            },
                            icon = { Icon(if (selected) tab.selectedIcon else tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = backStack,
            // Consuming the bar's space stops imePadding() below from adding it a second time
            // (that was the gap between the chat box and the keyboard).
            modifier = Modifier
                .padding(bottom = padding.calculateBottomPadding())
                .consumeWindowInsets(androidx.compose.foundation.layout.PaddingValues(bottom = padding.calculateBottomPadding())),
            onBack = back,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry<Route.Today> { TodayScreen(navigate = navigate, openTab = { backStack.switchTo(it) }) }
                entry<Route.Food> { DiaryScreen(navigate = navigate) }
                entry<Route.FoodSearch> { key -> FoodSearchScreen(key, navigate = navigate, onBack = back) }
                entry<Route.LogFood> { key -> LogFoodScreen(key, onBack = back, navigate = navigate) }
                entry<Route.EditEntry> { key -> LogFoodScreen(key, onBack = back, navigate = navigate) }
                entry<Route.Scan> { key -> ScanScreen(key, onBack = back, replace = replace) }
                entry<Route.FoodEditor> { key -> FoodEditorScreen(key, onBack = back, replace = replace) }
                entry<Route.Recipes> { RecipesScreen(onBack = back, navigate = navigate) }
                entry<Route.RecipeEditor> { key -> RecipeEditorScreen(key, onBack = back) }
                entry<Route.Train> {
                    TrainScreen(navigate = navigate, running = { RunningPane(navigate = navigate) })
                }
                entry<Route.WorkoutPreview> { key -> WorkoutPreviewScreen(key, onBack = back, navigate = navigate, replace = replace) }
                entry<Route.WorkoutPlayer> { WorkoutPlayerScreen(onBack = back) }
                entry<Route.Programs> { be.lain.bloom.feature.train.ProgramsScreen(onBack = back) }
                entry<Route.ExerciseLibrary> { ExerciseLibraryScreen(onBack = back, navigate = navigate) }
                entry<Route.ExerciseDetail> { key -> ExerciseDetailScreen(key, onBack = back, navigate = navigate) }
                entry<Route.RunActive> { key -> RunActiveScreen(key, onBack = back, replace = replace) }
                entry<Route.RunDetail> { key -> RunDetailScreen(key, onBack = back) }
                entry<Route.RunPlanEditor> { key -> RunPlanEditorScreen(key, onBack = back) }
                entry<Route.Progress> { ProgressScreen(navigate = navigate) }
                entry<Route.Coach> { CoachScreen() }
                entry<Route.QuickLog> { key -> QuickLogScreen(key, onBack = back) }
                entry<Route.Settings> { SettingsScreen(onBack = back, navigate = navigate) }
                entry<Route.EditProfile> { EditProfileScreen(onBack = back) }
                entry<Route.PhotoCompare> { be.lain.bloom.feature.progress.PhotoCompareScreen(onBack = back) }
                entry<Route.ProgressCamera> { be.lain.bloom.feature.progress.ProgressCameraScreen(onBack = back) }
                entry<Route.Cycle> { be.lain.bloom.feature.cycle.CycleScreen(onBack = back) }
                entry<Route.WeeklyReview> { be.lain.bloom.feature.review.WeeklyReviewScreen(onClose = back) }
                entry<Route.ReviewDetail> { key -> be.lain.bloom.feature.review.ReviewDetailScreen(key, onBack = back) }
            },
        )
    }
}
