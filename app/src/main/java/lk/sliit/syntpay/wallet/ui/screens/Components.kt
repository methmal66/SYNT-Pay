package lk.sliit.syntpay.wallet.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import lk.sliit.syntpay.wallet.ui.theme.SyntPayPalette

/** Flat fill, 1dp outline, 12dp radius, 12dp inner padding (section 8). */
@Composable
fun SyntCard(
    modifier: Modifier = Modifier,
    background: Color = MaterialTheme.colorScheme.surface,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        content = content,
    )
}

@Composable
fun CardLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun KeyValueRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(12.dp))
        Text(
            value,
            fontSize = 12.sp,
            color = valueColor,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** Neutral pill with a 6dp coloured dot: amber OFFLINE, green ONLINE (section 7). */
@Composable
fun StatusPill(online: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(
            Modifier
                .size(6.dp)
                .clip(RoundedCornerShape(100))
                .background(if (online) SyntPayPalette.Accent else SyntPayPalette.Warning),
        )
        Spacer(Modifier.size(7.dp))
        Text(
            text = if (online) "ONLINE" else "OFFLINE",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 4dp progress bar, accent fill on a surfaceVariant track (section 7). */
@Composable
fun ThinProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = SyntPayPalette.Accent) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(600),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
    }
}

/** One 8dp dot per key in the batch; filled means burnt (section 7). */
@Composable
fun KeyDots(total: Int, burnt: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(total) { index ->
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(100))
                    .then(
                        if (index < burnt) {
                            Modifier.background(SyntPayPalette.Accent)
                        } else {
                            Modifier.border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(100))
                        },
                    ),
            )
        }
    }
}

/** Pulsing concentric rings for the "hold near the terminal" state (section 7). */
@Composable
fun PulsingRings(modifier: Modifier = Modifier, color: Color = SyntPayPalette.Accent) {
    val transition = rememberInfiniteTransition(label = "rings")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200)),
        label = "phase",
    )
    Canvas(modifier) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = minOf(size.width, size.height) / 2f
        for (ring in 0 until 3) {
            val local = (phase + ring / 3f) % 1f
            drawCircle(
                color = color.copy(alpha = (1f - local) * 0.55f),
                radius = maxRadius * (0.25f + 0.75f * local),
                center = centre,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
        }
        drawCircle(color = color.copy(alpha = 0.9f), radius = maxRadius * 0.16f, center = centre)
    }
}

/** LKR major units from minor units, always two decimals, grouped. */
fun formatMinor(minor: Long): String = String.format("%,.2f", minor / 100.0)
