package be.lain.bloom.feature.body

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import be.lain.bloom.core.designsystem.NumberStepper

@Composable
fun LogWeightSheet(initialKg: Double, onDismiss: () -> Unit, onSave: (Double, java.time.LocalDate) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val haptics = LocalHapticFeedback.current
    var kg by remember { mutableDoubleStateOf(initialKg) }
    var date by remember { androidx.compose.runtime.mutableStateOf(java.time.LocalDate.now()) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Log weight", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Same time each day works best — ideally in the morning, before eating.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            NumberStepper(
                value = kg, onValueChange = { kg = it }, unit = "kg", step = 0.1, decimals = 1, min = 30.0, max = 300.0,
                label = null,
            )
            Spacer(Modifier.height(20.dp))
            be.lain.bloom.core.designsystem.LogDateRow(date, { date = it }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onSave(kg, date)
                    onDismiss()
                },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().height(ButtonDefaults.MediumContainerHeight),
            ) { Text("Save", style = ButtonDefaults.textStyleFor(ButtonDefaults.MediumContainerHeight)) }
            Spacer(Modifier.height(16.dp))
        }
    }
}
