package be.lain.bloom.feature.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import be.lain.bloom.core.designsystem.BloomCard
import be.lain.bloom.core.designsystem.MarkdownText
import be.lain.bloom.core.designsystem.StatTile
import be.lain.bloom.core.util.fmt
import be.lain.bloom.core.util.short
import be.lain.bloom.core.util.signed
import be.lain.bloom.data.ai.AiActions
import be.lain.bloom.data.db.BloomDatabase
import be.lain.bloom.data.db.WeeklyReviewEntity
import be.lain.bloom.feature.coach.ActionCard
import be.lain.bloom.feature.coach.ChatItem
import be.lain.bloom.feature.coach.buildItems
import be.lain.bloom.ui.Route
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = ReviewDetailViewModel.Factory::class)
class ReviewDetailViewModel @AssistedInject constructor(
    @Assisted id: Long,
    db: BloomDatabase,
    private val actions: AiActions,
) : ViewModel() {
    @AssistedFactory interface Factory { fun create(id: Long): ReviewDetailViewModel }

    val review = flow { emit(db.reviewDao().get(id)) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val items = review.flatMapLatest { r ->
        val chat = r?.chatId ?: return@flatMapLatest flowOf(emptyList<ChatItem>())
        combine(db.chatDao().observeMessages(chat), db.chatDao().observeActions(chat), ::buildItems)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun accept(id: Long) = viewModelScope.launch { actions.accept(id) }
    fun decline(id: Long) = viewModelScope.launch { actions.decline(id) }
    fun undo(id: Long) = viewModelScope.launch { actions.undo(id) }
}

@Composable
fun ReviewDetailScreen(
    key: Route.ReviewDetail,
    onBack: () -> Unit,
    vm: ReviewDetailViewModel = hiltViewModel<ReviewDetailViewModel, ReviewDetailViewModel.Factory>(key = "review-${key.id}") { it.create(key.id) },
) {
    val r by vm.review.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(r?.let { "Week of ${it.weekStart.short()}" } ?: "Weekly review") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        val review = r ?: return@Scaffold
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Summary(review) }
            items(items, key = { it.key }) { item ->
                when (item) {
                    is ChatItem.Assistant -> BloomCard(Modifier.fillMaxWidth()) { MarkdownText(item.m.content) }
                    is ChatItem.Action -> ActionCard(item.a, { vm.undo(item.a.id) }, { vm.accept(item.a.id) }, { vm.decline(item.a.id) })
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun Summary(r: WeeklyReviewEntity) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile("Target", r.proposedKcal.takeIf { r.applied }?.fmt() ?: r.oldKcal.fmt(), Modifier.weight(1f), unit = "kcal",
            supporting = if (r.applied && r.proposedKcal != r.oldKcal) "was ${r.oldKcal.fmt()}" else if (!r.applied) "kept" else "unchanged")
        StatTile("Weight trend", r.weeklyRateKg?.signed(2) ?: "–", Modifier.weight(1f), unit = "kg/wk", supporting = r.trendKg?.let { "${it.fmt()} kg" })
    }
    Spacer(Modifier.height(12.dp))
    BloomCard(Modifier.fillMaxWidth()) {
        Line("Fully logged days", "${r.completeDays} of 7")
        Line("Eaten on those days", r.avgIntakeKcal?.let { "${it.fmt()} kcal" } ?: "–")
        Line("Burn estimate", "${r.newExpenditure.fmt()} kcal/day")
        Line("Strength", "${r.workoutsDone} of ${r.workoutsPlanned}")
        Line("Run-walk", "${r.runsDone} of ${r.runsPlanned}" + if (r.runKm > 0) " · ${r.runKm.fmt(1)} km" else "")
        r.avgSteps?.let { Line("Steps", "${it.fmt()} a day") }
        val feel = listOfNotNull(r.energy?.let { "energy $it" }, r.hunger?.let { "hunger $it" }, r.sleep?.let { "sleep $it" }, r.soreness?.let { "soreness $it" })
        if (feel.isNotEmpty()) Line("Check-in (1–5)", feel.joinToString(", "))
        if (r.notes.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text("“${r.notes}”", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End)
    }
}
