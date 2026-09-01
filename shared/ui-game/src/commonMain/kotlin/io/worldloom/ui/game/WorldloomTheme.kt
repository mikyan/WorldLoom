package io.worldloom.ui.game

import androidx.compose.material.MaterialTheme
import androidx.compose.material.Shapes
import androidx.compose.material.Typography
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared semantic colors. World content may provide imagery, but it cannot replace these interaction roles. */
internal object WorldloomPalette {
    val Canvas = Color(0xFF0B0E10)
    val Surface = Color(0xFF15191B)
    val SurfaceStrong = Color(0xFF111518)
    val SurfaceRaised = Color(0xFF202527)
    val Scrim = Color(0xB3050708)
    val BorderSubtle = Color(0x38E8D7AE)
    val BorderFocus = Color(0xB8E2BD72)
    val BrandPrimary = Color(0xFFE2BD72)
    val BrandPrimaryVariant = Color(0xFFAA843E)
    val OnBrandPrimary = Color(0xFF21170B)
    val NarrativeNpc = Color(0xFF80B8B3)
    val NarrativePlayer = Color(0xFFD5A75B)
    val TextPrimary = Color(0xFFF3EFE5)
    val TextSecondary = Color(0xFFB9B7B0)
    val TextMuted = Color(0xFF8E8C86)
    val Info = Color(0xFFAAA4C8)
    val Success = Color(0xFF79B99B)
    val Warning = Color(0xFFD9A85C)
    val Error = Color(0xFFE47D6D)
}

internal object WorldloomSpacing {
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
    val Xxl = 32.dp
}

internal object WorldloomDimensions {
    val DesktopTouchTarget = 44.dp
    val TouchTarget = 48.dp
    val FocusedContentMaxWidth = 1120.dp
    val FormMaxWidth = 760.dp
    val SummaryWidth = 320.dp
    val MapPanelWidth = 300.dp
    val HudPanelWidth = 292.dp
    val HudPanelMediumWidth = 252.dp
    val AvatarSize = 40.dp
    val ChatAvatarSize = 38.dp
    val AvatarControlWidth = 56.dp
    val StatusBadgeSize = 22.dp
    val ChoiceMarkSize = 12.dp
    val SelectorCardMinWidth = 220.dp
    val SelectorCardMaxWidth = 300.dp
    val DenseSelectorCardMinWidth = 200.dp
    val RecognitionCandidateWidth = 250.dp
    val NarrativeFieldMinHeight = 144.dp
    val GameMarkCompactSize = 30.dp
    val GameMarkSize = 36.dp
    val StatusDotSize = 10.dp
    val TypingIndicatorSize = 12.dp
}

internal object WorldloomMotion {
    const val Micro = 120
    const val Control = 180
    const val Panel = 240
    const val Scene = 360
    const val Transition = 520
}

internal enum class WorldloomWidthClass { COMPACT, MEDIUM, EXPANDED }

internal data class WorldloomWindowSize(
    val widthClass: WorldloomWidthClass,
    val short: Boolean,
) {
    val pagePadding: Dp
        get() = when (widthClass) {
            WorldloomWidthClass.COMPACT -> WorldloomSpacing.Md
            WorldloomWidthClass.MEDIUM -> WorldloomSpacing.Lg
            WorldloomWidthClass.EXPANDED -> WorldloomSpacing.Xl
        }
}

internal fun classifyWorldloomWindow(width: Dp, height: Dp): WorldloomWindowSize = WorldloomWindowSize(
    widthClass = when {
        width < 720.dp -> WorldloomWidthClass.COMPACT
        width < 1_200.dp -> WorldloomWidthClass.MEDIUM
        else -> WorldloomWidthClass.EXPANDED
    },
    short = height < 620.dp,
)

private val WorldloomMaterialColors = darkColors(
    primary = WorldloomPalette.BrandPrimary,
    primaryVariant = WorldloomPalette.BrandPrimaryVariant,
    secondary = WorldloomPalette.NarrativeNpc,
    background = WorldloomPalette.Canvas,
    surface = WorldloomPalette.Surface,
    error = WorldloomPalette.Error,
    onPrimary = WorldloomPalette.OnBrandPrimary,
    onSecondary = WorldloomPalette.Canvas,
    onBackground = WorldloomPalette.TextPrimary,
    onSurface = WorldloomPalette.TextPrimary,
    onError = WorldloomPalette.Canvas,
)

private val WorldloomTypography = Typography(
    h1 = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold),
    h2 = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
    h3 = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    body1 = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
    body2 = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    button = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    subtitle1 = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    caption = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
)

private val WorldloomShapes = Shapes(
    small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
)

@Composable
internal fun WorldloomTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = WorldloomMaterialColors,
        typography = WorldloomTypography,
        shapes = WorldloomShapes,
        content = content,
    )
}
