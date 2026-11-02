package tv.own.owntv.mobile.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tv.own.owntv.core.theme.AnimationLevel
import tv.own.owntv.mobile.R
import tv.own.owntv.mobile.ui.theme.LocalAnimations

/**
 * Recording, in the two shapes every recorder on earth uses: a **circle** to start, a **square** to
 * stop.
 *
 * Kept out of the icon font on purpose. A record control is recognised by its shape rather than by a
 * glyph, the two shapes are what the user is looking for, and both are one `Canvas` — so this does
 * not need an icon that would also be drawn, at 24 dp, in a bar that has no room for words.
 *
 * The toolbar's own button and the badge over the picture live together here because they share the
 * one red: a recording that is starting and a recording that is running must look like the same thing.
 */
private val RecordRed = Color(0xFFE3372F)

/**
 * The toolbar's Record: a red circle, and a red square while a recording is running.
 *
 * The same 48 dp square as every other tool, and the same rule — the name is never drawn, it is
 * published to accessibility services by [holdToReveal].
 */
@Composable
internal fun RecordButton(
    recording: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var held by remember { mutableStateOf(false) }
    val tint = if (held) Color.White else RecordRed
    Row(
        modifier
            .height(48.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .holdToReveal(label, onExpanded = { held = it }, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            if (recording) {
                // A square: "stop", and visibly not the circle that started it.
                val side = size.minDimension * 0.64f
                val corner = 2.dp.toPx()
                drawRoundRect(
                    color = tint,
                    topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2f),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(corner, corner),
                )
            } else {
                drawCircle(color = tint, radius = size.minDimension * 0.32f)
            }
        }
    }
}

/**
 * The recording itself, over the picture: a blinking red dot and the time it has been running.
 *
 * Independent of the controls, and that is the point — the bar fades out three seconds into watching,
 * and a recording is exactly the state that must stay visible after it has gone. Tapping it stops the
 * recording, so the one piece of the recording that is always on screen is also the way to end it.
 *
 * The blink is the universal "this is being written" cue and stops blinking — but stays visible —
 * when animations are turned off, which is what that setting promises.
 */
@Composable
internal fun RecordingBadge(
    startedAtMs: Long,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val animations = LocalAnimations.current != AnimationLevel.OFF
    var elapsedSec by remember(startedAtMs) { mutableLongStateOf(0L) }
    LaunchedEffect(startedAtMs) {
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L)
            delay(1_000L)
        }
    }
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
    )
    val dotAlpha = if (animations) pulse else 1f
    val stopLabel = stringResource(R.string.recording_stop)
    Row(
        modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClickLabel = stopLabel, onClick = onStop)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(12.dp)
                .graphicsLayer { alpha = dotAlpha }
                .clip(RoundedCornerShape(percent = 50))
                .background(RecordRed),
        )
        Text(
            // The app's own duration format, so an hour of recording reads as 1:02:07 and not 62:07.
            text = android.text.format.DateUtils.formatElapsedTime(elapsedSec),
            style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Content),
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
