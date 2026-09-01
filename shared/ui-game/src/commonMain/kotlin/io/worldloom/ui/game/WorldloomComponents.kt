package io.worldloom.ui.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun WorldloomPanel(
    modifier: Modifier = Modifier,
    strong: Boolean = false,
    padding: Dp = WorldloomSpacing.Lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        color = if (strong) WorldloomPalette.SurfaceStrong else WorldloomPalette.Surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, WorldloomPalette.BorderSubtle),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(padding),
            verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Md),
            content = content,
        )
    }
}

@Composable
internal fun WorldloomPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = WorldloomDimensions.TouchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = WorldloomPalette.BrandPrimary,
            contentColor = WorldloomPalette.OnBrandPrimary,
            disabledBackgroundColor = WorldloomPalette.SurfaceRaised,
            disabledContentColor = WorldloomPalette.TextMuted,
        ),
        contentPadding = PaddingValues(horizontal = WorldloomSpacing.Lg, vertical = WorldloomSpacing.Sm),
    ) {
        Text(label, style = MaterialTheme.typography.button)
    }
}

@Composable
internal fun WorldloomSecondaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = WorldloomDimensions.TouchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, WorldloomPalette.BorderFocus),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = WorldloomPalette.SurfaceStrong,
            contentColor = WorldloomPalette.TextPrimary,
            disabledBackgroundColor = WorldloomPalette.Surface,
            disabledContentColor = WorldloomPalette.TextMuted,
        ),
        contentPadding = PaddingValues(horizontal = WorldloomSpacing.Lg, vertical = WorldloomSpacing.Sm),
    ) {
        Text(label, style = MaterialTheme.typography.button)
    }
}

@Composable
internal fun WorldloomDangerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = WorldloomDimensions.TouchTarget),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, WorldloomPalette.Error.copy(alpha = 0.78f)),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = WorldloomPalette.Error.copy(alpha = 0.14f),
            contentColor = WorldloomPalette.Error,
            disabledBackgroundColor = WorldloomPalette.Surface,
            disabledContentColor = WorldloomPalette.TextMuted,
        ),
        contentPadding = PaddingValues(horizontal = WorldloomSpacing.Lg, vertical = WorldloomSpacing.Sm),
    ) {
        Text(label, style = MaterialTheme.typography.button)
    }
}

@Composable
internal fun WorldloomSectionHeading(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs),
    ) {
        Text(title, color = WorldloomPalette.TextPrimary, style = MaterialTheme.typography.h3)
        subtitle?.let {
            Text(it, color = WorldloomPalette.TextSecondary, style = MaterialTheme.typography.body2)
        }
    }
}

internal enum class WorldloomStatusTone { INFO, SUCCESS, WARNING, ERROR }

@Composable
internal fun WorldloomStatusBanner(
    message: String,
    tone: WorldloomStatusTone,
    modifier: Modifier = Modifier,
) {
    val (accent, marker) = when (tone) {
        WorldloomStatusTone.INFO -> WorldloomPalette.Info to "i"
        WorldloomStatusTone.SUCCESS -> WorldloomPalette.Success to "✓"
        WorldloomStatusTone.WARNING -> WorldloomPalette.Warning to "!"
        WorldloomStatusTone.ERROR -> WorldloomPalette.Error to "×"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.12f), MaterialTheme.shapes.small)
            .border(1.dp, accent.copy(alpha = 0.62f), MaterialTheme.shapes.small)
            .padding(horizontal = WorldloomSpacing.Md, vertical = WorldloomSpacing.Sm),
        horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(color = accent, shape = androidx.compose.foundation.shape.CircleShape) {
            Box(
                modifier = Modifier.height(WorldloomDimensions.StatusBadgeSize)
                    .widthIn(min = WorldloomDimensions.StatusBadgeSize),
                contentAlignment = Alignment.Center,
            ) {
                Text(marker, color = WorldloomPalette.Canvas, fontWeight = FontWeight.Bold)
            }
        }
        Text(message, modifier = Modifier.weight(1f), color = WorldloomPalette.TextPrimary)
    }
}

@Composable
internal fun WorldloomChoiceCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val accent = if (selected) WorldloomPalette.BrandPrimary else WorldloomPalette.BorderSubtle
    Surface(
        modifier = modifier
            .heightIn(min = WorldloomDimensions.TouchTarget)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            ),
        color = if (selected) WorldloomPalette.SurfaceRaised else WorldloomPalette.Surface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(if (selected) 2.dp else 1.dp, accent),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = WorldloomSpacing.Md, vertical = WorldloomSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .background(if (selected) WorldloomPalette.BrandPrimary else Color.Transparent, androidx.compose.foundation.shape.CircleShape)
                    .border(1.dp, accent, androidx.compose.foundation.shape.CircleShape)
                    .height(WorldloomDimensions.ChoiceMarkSize)
                    .widthIn(min = WorldloomDimensions.ChoiceMarkSize),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs)) {
                Text(
                    title,
                    color = WorldloomPalette.TextPrimary,
                    style = MaterialTheme.typography.body2,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.let {
                    Text(it, color = WorldloomPalette.TextSecondary, style = MaterialTheme.typography.caption)
                }
            }
            if (selected) Text("已选择", color = WorldloomPalette.BrandPrimary, style = MaterialTheme.typography.caption)
        }
    }
}

@Composable
internal fun WorldloomFocusedPage(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    subtitle: String? = null,
    content: @Composable BoxScope.(WorldloomWindowSize) -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        WorldloomPalette.Canvas,
                        WorldloomPalette.SurfaceStrong,
                        WorldloomPalette.Canvas,
                    ),
                ),
            )
            .safeDrawingPadding(),
    ) {
        val window = classifyWorldloomWindow(maxWidth, maxHeight)
        Column(
            modifier = Modifier.fillMaxSize().padding(window.pagePadding),
            verticalArrangement = Arrangement.spacedBy(if (window.short) WorldloomSpacing.Sm else WorldloomSpacing.Lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs)) {
                eyebrow?.let {
                    Text(it, color = WorldloomPalette.BrandPrimary, style = MaterialTheme.typography.subtitle1)
                }
                Text(title, color = WorldloomPalette.TextPrimary, style = MaterialTheme.typography.h2)
                subtitle?.let {
                    Text(it, color = WorldloomPalette.TextSecondary, style = MaterialTheme.typography.body2)
                }
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                Box(
                    modifier = Modifier
                        .widthIn(max = WorldloomDimensions.FocusedContentMaxWidth)
                        .fillMaxWidth()
                        .fillMaxHeight(),
                ) {
                    content(window)
                }
            }
        }
    }
}
