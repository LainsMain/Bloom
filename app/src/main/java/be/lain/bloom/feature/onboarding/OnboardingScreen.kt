package be.lain.bloom.feature.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.designsystem.ShapeIcon
import be.lain.bloom.data.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

enum class OnboardingStep { WELCOME, GOAL, BODY, WEIGHT, LIFESTYLE, PACE, TRAINING, SUMMARY }

data class OnboardingState(
    val draft: ProfileDraft = ProfileDraft(),
    val step: OnboardingStep = OnboardingStep.WELCOME,
    val forward: Boolean = true,
    val saving: Boolean = false,
    /** Errors are only shown after the user tries to continue. */
    val attempted: Boolean = false,
) {
    val steps: List<OnboardingStep>
        get() = OnboardingStep.entries.filter { it != OnboardingStep.PACE || draft.hasPace }
    val index: Int get() = steps.indexOf(step).coerceAtLeast(0)
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val clock: Clock,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()
    val today: LocalDate get() = LocalDate.now(clock)

    fun update(draft: ProfileDraft) = _state.update { it.copy(draft = draft) }

    fun stepError(s: OnboardingState): String? = when (s.step) {
        OnboardingStep.WELCOME -> s.draft.nameError()
        OnboardingStep.BODY -> s.draft.bodyError(today)
        OnboardingStep.WEIGHT -> s.draft.weightError()
        else -> null
    }

    fun next() {
        val s = _state.value
        if (stepError(s) != null) {
            _state.update { it.copy(attempted = true) }
            return
        }
        val i = s.index
        if (i < s.steps.lastIndex) _state.update { it.copy(step = s.steps[i + 1], forward = true, attempted = false) }
        else finish()
    }

    fun back() {
        val s = _state.value
        if (s.index > 0) _state.update { it.copy(step = s.steps[s.index - 1], forward = false, attempted = false) }
    }

    private fun finish() {
        val s = _state.value
        if (s.saving || s.draft.errors(today).isNotEmpty()) return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch { profiles.completeOnboarding(s.draft.toProfile(today)) }
    }
}

@Composable
fun OnboardingScreen(vm: OnboardingViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val progress by animateFloatAsState(
        (state.index + 1f) / state.steps.size, MaterialTheme.motionScheme.defaultSpatialSpec(), label = "progress",
    )
    BackHandler(enabled = state.index > 0) { vm.back() }
    val error = vm.stepError(state).takeIf { state.attempted }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::back, enabled = state.index > 0) {
                if (state.index > 0) Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
            }
            LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).padding(end = 20.dp))
        }
        AnimatedContent(
            targetState = state.step,
            transitionSpec = {
                val dir = if (state.forward) 1 else -1
                (slideInHorizontally { it * dir / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it * dir / 3 } + fadeOut())
            },
            modifier = Modifier.weight(1f),
            label = "step",
        ) { step ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
                StepContent(step, state.draft, vm::update, vm.today)
            }
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            Button(
                onClick = vm::next,
                enabled = !state.saving,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
                contentPadding = ButtonDefaults.contentPaddingFor(ButtonDefaults.MediumContainerHeight),
            ) {
                Text(
                    if (state.step == OnboardingStep.SUMMARY) "Start my plan" else "Continue",
                    style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight),
                )
            }
        }
    }
}

@Composable
private fun StepContent(step: OnboardingStep, draft: ProfileDraft, onChange: (ProfileDraft) -> Unit, today: LocalDate) {
    when (step) {
        OnboardingStep.WELCOME -> {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(24.dp))
                ShapeIcon(MaterialShapes.Flower, Icons.Rounded.LocalFlorist, size = 140.dp)
                Spacer(Modifier.height(32.dp))
            }
            SectionLead(
                "Hi! Let's set up your plan",
                "Food, training, runs and weekly check-ins — all in one place, all stored only on this phone.",
            )
            NameSection(draft, onChange)
        }
        OnboardingStep.GOAL -> {
            SectionLead("What do you want to work towards?")
            GoalSection(draft, onChange)
        }
        OnboardingStep.BODY -> {
            SectionLead("About you", "Used to estimate how much energy your body uses at rest.")
            BodySection(draft, onChange)
        }
        OnboardingStep.WEIGHT -> {
            SectionLead("Your weight", if (draft.hasPace) "Where you are now, and where you'd like to be." else "Where you are now.")
            WeightSection(draft, onChange)
        }
        OnboardingStep.LIFESTYLE -> {
            SectionLead("How active are your days?", "Not counting workouts — just your normal day.")
            LifestyleSection(draft, onChange)
        }
        OnboardingStep.PACE -> {
            SectionLead("Choose your pace", "You can change this any time.")
            PaceSection(draft, onChange, today)
        }
        OnboardingStep.TRAINING -> {
            SectionLead("Your training week", "Pick the days that fit your life. Rest days matter too.")
            TrainingSection(draft, onChange)
        }
        OnboardingStep.SUMMARY -> {
            SectionLead("Your starting plan", null)
            TargetsSummary(draft, today)
        }
    }
    Spacer(Modifier.height(24.dp))
}
