package io.worldloom.ui.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.worldloom.application.WorldCatalogEntry
import io.worldloom.ui.game.generated.resources.Res
import io.worldloom.ui.game.generated.resources.home_dreamweaver_cave
import kotlin.math.abs
import org.jetbrains.compose.resources.painterResource

private val DreamGold = Color(0xFFF1BE63)
private val DreamGoldMuted = Color(0xFF9B7034)
private val DreamViolet = Color(0xFF4D315F)
private val DreamInk = Color(0xED09070E)

@Composable
internal fun HomeExperiencePage(
    worlds: List<WorldCatalogEntry>,
    pane: HomePane,
    selectedDreamIndex: Int,
    speechText: String,
    reduceMotion: Boolean,
    transitioning: Boolean,
    errorMessage: String?,
    onPaneChanged: (HomePane) -> Unit,
    onDreamIndexChanged: (Int) -> Unit,
    onEnterDream: (WorldCatalogEntry) -> Unit,
    onOpenSettings: () -> Unit,
    saveContent: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 720.dp
        Image(
            painter = painterResource(Res.drawable.home_dreamweaver_cave),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            alignment = Alignment.Center,
        )
        Box(
            Modifier.fillMaxSize().background(
                if (compact) {
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.16f),
                        0.48f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.90f),
                    )
                } else {
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.88f),
                        0.42f to Color.Black.copy(alpha = 0.24f),
                        1f to Color.Black.copy(alpha = 0.12f),
                    )
                },
            ),
        )

        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            DreamSpeechBubble(
                text = speechText,
                compact = compact,
                modifier = Modifier
                    .align(if (compact) Alignment.TopCenter else Alignment.TopEnd)
                    .padding(
                        start = if (compact) 18.dp else 0.dp,
                        top = if (compact) 22.dp else 42.dp,
                        end = if (compact) 18.dp else 54.dp,
                    ),
            )

            AnimatedContent(
                targetState = pane,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val duration = if (reduceMotion) 0 else 240
                    fadeIn(tween(duration)) togetherWith fadeOut(tween(duration))
                },
                label = "home-pane",
            ) { activePane ->
                when (activePane) {
                    HomePane.MENU -> HomeMenu(
                        compact = compact,
                        onNewDream = { onPaneChanged(HomePane.DREAMS) },
                        onContinueDream = { onPaneChanged(HomePane.SAVES) },
                        onSettings = onOpenSettings,
                    )

                    HomePane.DREAMS -> DreamSelection(
                        worlds = worlds,
                        selectedIndex = selectedDreamIndex,
                        compact = compact,
                        reduceMotion = reduceMotion,
                        onSelectedIndexChanged = onDreamIndexChanged,
                        onEnterDream = onEnterDream,
                        onBack = { onPaneChanged(HomePane.MENU) },
                    )

                    HomePane.SAVES -> DreamSaveShelf(
                        compact = compact,
                        onBack = { onPaneChanged(HomePane.MENU) },
                        content = saveContent,
                    )
                }
            }

            errorMessage?.let { message ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp),
                    color = MaterialTheme.colors.error.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(6.dp),
                ) {
                    Text(message, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = transitioning,
            modifier = Modifier.fillMaxSize(),
            enter = fadeIn(tween(if (reduceMotion) 0 else 360)),
            exit = fadeOut(tween(if (reduceMotion) 0 else 220)),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(
                        0f to Color(0xFF352142).copy(alpha = 0.96f),
                        0.55f to Color(0xFF120C1B).copy(alpha = 0.98f),
                        1f to Color.Black,
                    ),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("梦境正在展开", color = DreamGold, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Text("穿过茧层，别松开这根丝。", color = Color.White.copy(alpha = 0.68f))
                }
            }
        }
    }
}

@Composable
private fun HomeMenu(
    compact: Boolean,
    onNewDream: () -> Unit,
    onContinueDream: () -> Unit,
    onSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(if (compact) Alignment.BottomCenter else Alignment.CenterStart)
                .then(
                    if (compact) {
                        Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp)
                    } else {
                        Modifier.width(330.dp).padding(start = 52.dp)
                    },
                ),
            horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("WORLDLOOM", color = DreamGold, fontSize = if (compact) 24.sp else 31.sp, fontWeight = FontWeight.Bold)
            Text("织境 · 梦茧之间", color = Color.White.copy(alpha = 0.68f), fontSize = 13.sp)
            Spacer(Modifier.height(if (compact) 4.dp else 16.dp))
            DreamMenuButton("新梦境", onNewDream)
            DreamMenuButton("继续梦境", onContinueDream)
            DreamMenuButton("设置", onSettings)
        }
    }
}

@Composable
private fun DreamMenuButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick),
        color = DreamInk.copy(alpha = 0.78f),
        border = BorderStroke(1.dp, DreamGoldMuted.copy(alpha = 0.86f)),
        shape = RoundedCornerShape(4.dp),
        elevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).background(DreamGold, RoundedCornerShape(50)))
            Spacer(Modifier.width(11.dp))
            Text(label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text("›", color = DreamGold, fontSize = 24.sp)
        }
    }
}

@Composable
private fun DreamSpeechBubble(
    text: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(if (compact) 330.dp else 420.dp),
        color = Color(0xE5181220),
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
        border = BorderStroke(1.dp, DreamGold.copy(alpha = 0.58f)),
        elevation = 10.dp,
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 13.dp)) {
            Text("织梦者", color = DreamGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(text, color = Color.White, fontSize = if (compact) 15.sp else 17.sp, lineHeight = 23.sp)
        }
    }
}

@Composable
private fun DreamSelection(
    worlds: List<WorldCatalogEntry>,
    selectedIndex: Int,
    compact: Boolean,
    reduceMotion: Boolean,
    onSelectedIndexChanged: (Int) -> Unit,
    onEnterDream: (WorldCatalogEntry) -> Unit,
    onBack: () -> Unit,
) {
    if (worlds.isEmpty()) {
        Surface(
            modifier = Modifier.alignDreamPanel(compact).padding(20.dp),
            color = DreamInk,
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("还没有可进入的梦境。", color = Color.White)
                DreamInlineButton("返回", onBack)
            }
        }
        return
    }
    val normalizedIndex = selectedIndex.coerceIn(worlds.indices)
    val selected = worlds[normalizedIndex]
    BoxWithConstraints(
        modifier = Modifier
            .alignDreamPanel(compact)
            .padding(
                start = if (compact) 12.dp else 26.dp,
                top = if (compact) 128.dp else 112.dp,
                end = if (compact) 12.dp else 26.dp,
                bottom = 16.dp,
            )
            .pointerInput(worlds.size, normalizedIndex) {
                var dragDistance = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragDistance = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragDistance += amount
                    },
                    onDragEnd = {
                        if (abs(dragDistance) > 52f) {
                            onSelectedIndexChanged(
                                nextDreamIndex(
                                    currentIndex = normalizedIndex,
                                    itemCount = worlds.size,
                                    direction = if (dragDistance < 0) 1 else -1,
                                ),
                            )
                        }
                    },
                )
            },
    ) {
        val shortScreen = maxHeight < 620.dp
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DreamArrowButton("‹") {
                    onSelectedIndexChanged(nextDreamIndex(normalizedIndex, worlds.size, -1))
                }
                if (!compact && worlds.size > 2) {
                    MiniCocoon(worlds[nextDreamIndex(normalizedIndex, worlds.size, -1)]) {
                        onSelectedIndexChanged(nextDreamIndex(normalizedIndex, worlds.size, -1))
                    }
                } else if (!compact && worlds.size == 2) {
                    Spacer(Modifier.width(92.dp))
                }
                AnimatedContent(
                    targetState = selected,
                    transitionSpec = {
                        val duration = if (reduceMotion) 0 else 220
                        (fadeIn(tween(duration)) + scaleIn(tween(duration), initialScale = 0.94f)) togetherWith
                            (fadeOut(tween(duration)) + scaleOut(tween(duration), targetScale = 0.94f))
                    },
                    label = "dream-cocoon",
                ) { world ->
                    DreamCocoon(
                        world = world,
                        width = if (compact || shortScreen) 174.dp else 218.dp,
                        height = if (compact || shortScreen) 218.dp else 282.dp,
                    )
                }
                if (!compact && worlds.size > 1) {
                    MiniCocoon(worlds[nextDreamIndex(normalizedIndex, worlds.size, 1)]) {
                        onSelectedIndexChanged(nextDreamIndex(normalizedIndex, worlds.size, 1))
                    }
                }
                DreamArrowButton("›") {
                    onSelectedIndexChanged(nextDreamIndex(normalizedIndex, worlds.size, 1))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                worlds.forEachIndexed { index, _ ->
                    Box(
                        Modifier
                            .size(if (index == normalizedIndex) 8.dp else 6.dp)
                            .background(
                                if (index == normalizedIndex) DreamGold else Color.White.copy(alpha = 0.34f),
                                RoundedCornerShape(50),
                            ),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth().heightIn(max = if (compact) 164.dp else 150.dp),
                color = DreamInk.copy(alpha = 0.86f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, DreamViolet.copy(alpha = 0.9f)),
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(selected.title, color = DreamGold, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.weight(1f))
                        selected.estimatedPlayMinutes?.let {
                            Text("约 $it 分钟", color = Color.White.copy(alpha = 0.56f), fontSize = 11.sp)
                        }
                    }
                    Text(
                        selected.premise ?: "这个梦境仍被丝层遮掩，进入后才能看清它的形状。",
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    selected.objective?.let {
                        Text("目标 · $it", color = DreamGold.copy(alpha = 0.88f), fontSize = 12.sp, lineHeight = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DreamInlineButton("返回", onBack, secondary = true)
                DreamInlineButton("进入梦境", onClick = { onEnterDream(selected) })
            }
        }
    }
}

private fun Modifier.alignDreamPanel(compact: Boolean): Modifier = if (compact) {
    fillMaxSize()
} else {
    fillMaxWidth(0.76f).fillMaxHeight().padding(start = 190.dp)
}

@Composable
private fun DreamCocoon(world: WorldCatalogEntry, width: Dp, height: Dp) {
    val cocoonShape = RoundedCornerShape(percent = 50)
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFFF5D890).copy(alpha = 0.92f),
                        Color(0xFF8E5D86).copy(alpha = 0.94f),
                        Color(0xFF25162F).copy(alpha = 0.98f),
                    ),
                ),
                cocoonShape,
            )
            .border(2.dp, DreamGold.copy(alpha = 0.86f), cocoonShape)
            .padding(13.dp)
            .border(1.dp, Color.White.copy(alpha = 0.22f), cocoonShape)
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.13f), Color.Transparent, Color.Black.copy(alpha = 0.28f)),
                ),
                cocoonShape,
            ),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text("梦茧", color = Color.White.copy(alpha = 0.62f), fontSize = 11.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(7.dp))
            Text(
                world.title,
                color = Color.White,
                fontSize = 18.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text("轻触或滑动选择", color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun MiniCocoon(world: WorldCatalogEntry, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .width(92.dp)
            .height(138.dp)
            .background(Color(0x99301E3A), RoundedCornerShape(percent = 50))
            .border(1.dp, DreamGoldMuted.copy(alpha = 0.56f), RoundedCornerShape(percent = 50))
            .clip(RoundedCornerShape(percent = 50))
            .clickable(onClick = onClick)
            .padding(11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            world.title,
            color = Color.White.copy(alpha = 0.68f),
            fontSize = 10.sp,
            lineHeight = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun DreamArrowButton(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick),
        color = DreamInk.copy(alpha = 0.82f),
        shape = RoundedCornerShape(50),
        border = BorderStroke(1.dp, DreamGoldMuted),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = DreamGold, fontSize = 30.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DreamInlineButton(
    label: String,
    onClick: () -> Unit,
    secondary: Boolean = false,
) {
    Surface(
        modifier = Modifier.height(43.dp).clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick),
        color = if (secondary) DreamInk.copy(alpha = 0.80f) else DreamGold,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, DreamGoldMuted),
    ) {
        Box(Modifier.padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                color = if (secondary) Color.White else Color(0xFF21140A),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DreamSaveShelf(
    compact: Boolean,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(if (compact) 0.94f else 0.72f)
                .fillMaxHeight(if (compact) 0.70f else 0.72f)
                .padding(bottom = 16.dp),
            color = DreamInk.copy(alpha = 0.93f),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, DreamGoldMuted.copy(alpha = 0.78f)),
            elevation = 12.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("继续梦境", color = DreamGold, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text("选择仍在丝网上回响的旧梦。", color = Color.White.copy(alpha = 0.56f), fontSize = 12.sp)
                    }
                    DreamInlineButton("返回", onBack, secondary = true)
                }
                Box(Modifier.fillMaxSize()) { content() }
            }
        }
    }
}

@Composable
internal fun ProviderSettingsOverlay(
    compact: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.70f))
            .clickable(onClick = onDismiss),
    ) {
        Surface(
            modifier = Modifier
                .align(if (compact) Alignment.BottomCenter else Alignment.Center)
                .safeDrawingPadding()
                .fillMaxWidth(if (compact) 0.96f else 0.78f)
                .fillMaxHeight(if (compact) 0.78f else 0.82f)
                .padding(bottom = if (compact) 10.dp else 0.dp)
                .clickable(onClick = {}),
            color = Color(0xFA110D16),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, DreamGoldMuted),
            elevation = 16.dp,
        ) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
    }
}
