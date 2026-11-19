package dev.hardwaredisplay.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hardwaredisplay.data.HistoryPointEntity
import dev.hardwaredisplay.ui.MetricTone
import dev.hardwaredisplay.ui.MetricUi
import dev.hardwaredisplay.ui.theme.Dimens
import dev.hardwaredisplay.ui.theme.Palette

fun toneColor(tone: MetricTone): Color = when (tone) {
    MetricTone.FRESH -> Palette.Accent
    MetricTone.WAITING -> Palette.Series1
    MetricTone.MISSING -> Palette.Warning
    MetricTone.STALE -> Palette.Warning
    MetricTone.OFFLINE -> Palette.Inactive
}

@Composable
fun StatusPill(text: String, tone: MetricTone, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Palette.Raised)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(toneColor(tone))
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextSecondary,
        )
    }
}

@Composable
fun MainMetricCard(
    ui: MetricUi,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    points: List<HistoryPointEntity>? = null,
) {
    val accent = toneColor(ui.tone)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.CardRadius))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.13f), Palette.Card)))
            .border(1.dp, accent.copy(alpha = 0.38f), RoundedCornerShape(Dimens.CardRadius))
            .clickable(onClick = onOpen)
            .padding(20.dp),
    ) {
        Text(
            text = ui.title,
            style = MaterialTheme.typography.titleMedium,
            color = Palette.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = ui.value ?: "—",
                style = MaterialTheme.typography.displayLarge,
                color = if (ui.tone == MetricTone.FRESH) Palette.Accent else Palette.TextSecondary,
            )
            if (ui.unit != null) {
                Spacer(Modifier.size(8.dp))
                Text(
                    text = ui.unit,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.TextSecondary,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }
        if (points != null && points.size > 1) {
            Spacer(Modifier.height(12.dp))
            MiniSparkline(points = points, color = accent)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = ui.note ?: ui.subtitle.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = if (ui.note != null) toneColor(ui.tone) else Palette.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun CompactMetricCard(
    ui: MetricUi,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    points: List<HistoryPointEntity>? = null,
    compact: Boolean = false,
) {
    Column(
        modifier = modifier
            .heightIn(min = if (compact) 84.dp else 104.dp)
            .clip(RoundedCornerShape(Dimens.CompactRadius))
            .background(Palette.Card)
            .border(1.dp, Palette.Border, RoundedCornerShape(Dimens.CompactRadius))
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = if (compact) 10.dp else 14.dp),
    ) {
        Text(
            text = ui.title,
            style = MaterialTheme.typography.labelLarge,
            color = Palette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = ui.value ?: "—",
                style = MaterialTheme.typography.headlineMedium,
                color = if (ui.tone == MetricTone.OFFLINE) Palette.TextSecondary else Palette.Text,
            )
            if (ui.unit != null) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = ui.unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        if (points != null && points.size > 1) {
            Spacer(Modifier.height(6.dp))
            MiniSparkline(points = points, color = toneColor(ui.tone))
        }
        if (ui.note != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = ui.note,
                style = MaterialTheme.typography.labelMedium,
                color = toneColor(ui.tone),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun OutlinedAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Box(
        modifier = modifier
            .heightIn(min = Dimens.MinTouch)
            .clip(RoundedCornerShape(50))
            .background(if (accent) Palette.Accent else Palette.Raised)
            .border(
                1.dp,
                if (accent) Palette.Accent else Palette.Border,
                RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium),
            color = if (accent) Palette.Background else Palette.Text,
        )
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Palette.Text,
        modifier = modifier,
    )
}
