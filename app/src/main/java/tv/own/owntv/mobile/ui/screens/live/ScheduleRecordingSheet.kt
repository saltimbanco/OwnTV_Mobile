package tv.own.owntv.mobile.ui.screens.live

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.recording.RecordingSchedule
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.components.MobileBottomSheet
import tv.own.owntv.mobile.ui.theme.MobileDimens
import java.text.SimpleDateFormat
import java.util.Calendar

/**
 * "Schedule recording…" from a channel's menu (#2): a day, a start and an end, guide or not.
 *
 * Day chips from today to [DAYS_AHEAD] days out and two typed times. An end at or before the start
 * is the next day, and says so. A window already over is refused in words; one already under way
 * records from now. A clash on the playlist is a warning, never a block.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScheduleRecordingSheet(
    channelName: String,
    clashesFor: suspend (startMs: Long, stopMs: Long) -> List<RecordingEntity>,
    onSchedule: (startMs: Long, stopMs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // Frozen for the life of the sheet, as in the catch-up time sheet.
    val nowMs = remember { System.currentTimeMillis() }
    val locale = LocalConfiguration.current.locales[0]
    val dayFormat = remember(locale) {
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale)
    }
    val is24h = android.text.format.DateFormat.is24HourFormat(androidx.compose.ui.platform.LocalContext.current)
    // Start at the next five minutes, for an hour.
    val first = remember { Calendar.getInstance().apply { timeInMillis = nowMs; add(Calendar.MINUTE, 5 - get(Calendar.MINUTE) % 5) } }
    val startTime = rememberTimePickerState(first.get(Calendar.HOUR_OF_DAY), first.get(Calendar.MINUTE), is24h)
    val endTime = rememberTimePickerState((first.get(Calendar.HOUR_OF_DAY) + 1) % 24, first.get(Calendar.MINUTE), is24h)
    var day by remember { mutableIntStateOf(if (first.get(Calendar.DAY_OF_YEAR) == Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) 0 else 1) }

    fun instantOf(dayOffset: Int, hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, dayOffset)
        add(Calendar.MINUTE, hour * 60 + minute)
    }.timeInMillis

    val startMs = instantOf(day, startTime.hour, startTime.minute)
    val stopMs = instantOf(day, endTime.hour, endTime.minute)
    val window = RecordingSchedule.manualWindow(startMs, stopMs, nowMs)
    val clashes by produceState(emptyList<RecordingEntity>(), startMs, stopMs) { value = clashesFor(startMs, stopMs) }
    val nextDay = endTime.hour * 60 + endTime.minute <= startTime.hour * 60 + startTime.minute

    MobileBottomSheet(onDismissRequest = onDismiss, title = stringResource(R.string.recording_schedule)) {
        Text(
            text = channelName,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = MobileDimens.ScreenPaddingH),
        )
        Text(
            text = stringResource(R.string.recording_schedule_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = MobileDimens.ScreenPaddingH),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = MobileDimens.ScreenPaddingH, vertical = MobileDimens.GapSmall),
            horizontalArrangement = Arrangement.spacedBy(MobileDimens.GapSmall),
        ) {
            (0..DAYS_AHEAD).forEach { offset ->
                FilterChip(
                    selected = offset == day,
                    onClick = { day = offset },
                    label = { Text(dayFormat.format(instantOf(offset, 0, 0))) },
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = MobileDimens.ScreenPaddingH)) {
            Text(stringResource(R.string.recording_schedule_start), style = MaterialTheme.typography.labelLarge)
            TimeInput(state = startTime)
            Text(stringResource(R.string.recording_schedule_end), style = MaterialTheme.typography.labelLarge)
            TimeInput(state = endTime)
            if (nextDay) {
                Text(
                    stringResource(R.string.recording_schedule_next_day),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val note = when {
                window == null -> stringResource(R.string.recording_schedule_past)
                clashes.isNotEmpty() -> stringResource(R.string.recording_clash_with, clashes.first().title)
                else -> null
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = MobileDimens.ScreenPaddingH),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            TextButton(onClick = { onSchedule(startMs, stopMs) }, enabled = window != null) {
                Text(stringResource(R.string.recording_record))
            }
        }
    }
}

/** How far ahead the day chips go: a week. */
private const val DAYS_AHEAD = 6
