package be.lain.bloom.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import be.lain.bloom.feature.onboarding.BodySection
import be.lain.bloom.feature.onboarding.GoalSection
import be.lain.bloom.feature.onboarding.LifestyleSection
import be.lain.bloom.feature.onboarding.NameSection
import be.lain.bloom.feature.onboarding.PaceSection
import be.lain.bloom.feature.onboarding.ProfileDraft
import be.lain.bloom.feature.onboarding.TargetsSummary
import be.lain.bloom.feature.onboarding.TrainingSection
import be.lain.bloom.feature.onboarding.WeightSection

@Composable
fun EditProfileScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    if (!state.loaded) return
    var draft by remember { mutableStateOf<ProfileDraft?>(null) }
    if (draft == null) draft = vm.draft()
    val d = draft ?: return
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val errors = d.errors(vm.today)

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text("Edit profile") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                scrollBehavior = scroll,
            )
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(16.dp).imePadding()) {
                errors.firstOrNull()?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                Button(
                    onClick = { vm.saveProfile(d, onBack) },
                    enabled = errors.isEmpty(),
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
                ) { Text("Save & update plan", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        ) {
            val onChange: (ProfileDraft) -> Unit = { draft = it }
            Heading("Name")
            NameSection(d, onChange)
            Heading("Goal")
            GoalSection(d, onChange)
            Heading("About you")
            BodySection(d, onChange)
            if (d.hasPace) {
                Heading("Goal weight")
                WeightSection(d, onChange, showCurrent = false)
            }
            Heading("Daily activity")
            LifestyleSection(d, onChange)
            if (d.hasPace) {
                Heading("Pace")
                PaceSection(d, onChange, vm.today)
            }
            Heading("Training week")
            TrainingSection(d, onChange)
            Heading("New targets")
            TargetsSummary(d, vm.today)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Heading(text: String) {
    Spacer(Modifier.height(28.dp))
    Text(text, style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
}
