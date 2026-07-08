package eu.kanade.presentation.reader.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun LnReaderSliderRow(
    label: String,
    valueText: (Float) -> String,
    committedValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean = true,
    onCommit: (Float) -> Unit,
) {
    var draftValue by rememberSaveable { mutableStateOf(committedValue) }
    var previousCommittedValue by rememberSaveable { mutableStateOf(committedValue) }

    LaunchedEffect(committedValue) {
        val synced = syncLnReaderSliderDraft(
            committedValue = committedValue,
            previousCommittedValue = previousCommittedValue,
            currentDraftValue = draftValue,
        )
        draftValue = synced.draftValue
        previousCommittedValue = synced.committedValue
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueText(draftValue),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = draftValue,
            onValueChange = { draftValue = it },
            onValueChangeFinished = {
                resolveLnReaderSliderCommitValue(
                    committedValue = committedValue,
                    draftValue = draftValue,
                )?.let(onCommit)
            },
            enabled = enabled,
            valueRange = range,
            steps = steps,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.24f),
            ),
        )
    }
}

internal data class LnReaderSliderDraftState(
    val committedValue: Float,
    val draftValue: Float,
)

internal fun syncLnReaderSliderDraft(
    committedValue: Float,
    previousCommittedValue: Float,
    currentDraftValue: Float,
): LnReaderSliderDraftState {
    return if (abs(committedValue - previousCommittedValue) > 0.0001f) {
        LnReaderSliderDraftState(
            committedValue = committedValue,
            draftValue = committedValue,
        )
    } else {
        LnReaderSliderDraftState(
            committedValue = previousCommittedValue,
            draftValue = currentDraftValue,
        )
    }
}

internal fun resolveLnReaderSliderCommitValue(
    committedValue: Float,
    draftValue: Float,
): Float? {
    return draftValue.takeIf { abs(it - committedValue) > 0.0001f }
}
