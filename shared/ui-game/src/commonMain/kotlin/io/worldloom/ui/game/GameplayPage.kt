package io.worldloom.ui.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.worldloom.agent.runtime.GameAgentController
import io.worldloom.agent.runtime.GameAgentState
import io.worldloom.agent.runtime.NpcDialogueResult
import io.worldloom.application.GamePresentation
import io.worldloom.application.GuidanceTargetKind
import io.worldloom.application.PresentedNpc
import io.worldloom.application.PresentedGuidanceSuggestion
import io.worldloom.rules.ExplorationKnowledgeLevel
import io.worldloom.application.SessionError
import io.worldloom.definition.DefinitionId
import io.worldloom.ui.game.generated.resources.Res
import io.worldloom.ui.game.generated.resources.gameplay_station_core
import io.worldloom.ui.game.generated.resources.gameplay_war_ruins
import io.worldloom.ui.game.generated.resources.npc_station_lyra
import io.worldloom.ui.game.generated.resources.npc_station_soren
import io.worldloom.ui.game.generated.resources.npc_war_mara
import io.worldloom.ui.game.generated.resources.npc_war_tomas
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

private val GlassColor = WorldloomPalette.Surface.copy(alpha = 0.84f)
private val GlassStrong = WorldloomPalette.SurfaceStrong.copy(alpha = 0.94f)
private val FineBorder = WorldloomPalette.BorderSubtle
private val PmAccent = WorldloomPalette.BrandPrimary
private val NpcAccent = WorldloomPalette.NarrativeNpc
private val PlayerBubble = WorldloomPalette.BrandPrimaryVariant.copy(alpha = 0.86f)
private val MutedText = WorldloomPalette.TextSecondary
private const val PM_MEMBER_ID = "worldloom.member.pm"

private enum class GuidanceStrength(val label: String) { NEWCOMER("新手"), STANDARD("标准"), IMMERSIVE("沉浸") }
private enum class GameContextPane { MAP, HUD }
internal enum class GameplayBackdropAsset { WAR_RUINS, STATION_CORE, GENERIC }
internal enum class GameplayAvatarAsset { WAR_MARA, WAR_TOMAS, STATION_LYRA, STATION_SOREN, GENERIC }

@Composable
internal fun GameplayPage(
    presentation: GamePresentation,
    notice: SessionError?,
    agentController: GameAgentController?,
    interactive: Boolean,
    reduceMotion: Boolean,
    runKey: String,
    historyKey: String,
    onExit: () -> Unit,
    onReplay: () -> Unit,
    onCheck: (DefinitionId) -> Unit,
    onWait: (Long) -> Unit,
    onNpcBusyChanged: (Boolean) -> Unit,
) {
    var mapOpen by remember(runKey) { mutableStateOf(false) }
    var contextPane by remember(runKey) { mutableStateOf(GameContextPane.MAP) }
    var composerDraft by remember(runKey) { mutableStateOf("") }
    var sendOrdinal by remember(historyKey) { mutableIntStateOf(0) }
    val conversationListState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        GameBackdrop(presentation.scene?.backgroundAssetId ?: presentation.opening?.backgroundAssetId)
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val window = classifyWorldloomWindow(maxWidth, maxHeight)
            if (window.widthClass == WorldloomWidthClass.COMPACT) {
                PortraitGameplay(
                    presentation,
                    notice,
                    agentController,
                    interactive,
                    runKey,
                    historyKey,
                    onExit,
                    onNpcBusyChanged,
                    mapOpen,
                    { mapOpen = it },
                    composerDraft,
                    { composerDraft = it },
                    sendOrdinal,
                    { sendOrdinal += 1 },
                    conversationListState,
                    reduceMotion,
                )
            } else {
                LandscapeGameplay(
                    window,
                    presentation,
                    notice,
                    agentController,
                    interactive,
                    runKey,
                    historyKey,
                    onExit,
                    onReplay,
                    onCheck,
                    onWait,
                    onNpcBusyChanged,
                    mapOpen,
                    { mapOpen = it },
                    composerDraft,
                    { composerDraft = it },
                    sendOrdinal,
                    { sendOrdinal += 1 },
                    conversationListState,
                    reduceMotion,
                )
            }
            if (mapOpen && !gameplayUsesInlineMap(window)) {
                GameContextOverlay(
                    presentation = presentation,
                    interactive = interactive,
                    pane = contextPane,
                    onPaneChanged = { contextPane = it },
                    onClose = { mapOpen = false },
                    onReplay = onReplay,
                    onCheck = onCheck,
                    onWait = onWait,
                    onChatPrefix = { composerDraft = it },
                    modifier = Modifier.fillMaxSize()
                        .background(WorldloomPalette.Scrim)
                        .padding(window.pagePadding),
                )
            }
        }
    }
}

@Composable
private fun GameBackdrop(assetId: String?) {
    val painter = when (gameplayBackdropAsset(assetId)) {
        GameplayBackdropAsset.WAR_RUINS -> painterResource(Res.drawable.gameplay_war_ruins)
        GameplayBackdropAsset.STATION_CORE -> painterResource(Res.drawable.gameplay_station_core)
        GameplayBackdropAsset.GENERIC -> null
    }
    Box(Modifier.fillMaxSize().background(WorldloomPalette.Canvas)) {
        painter?.let {
            Image(
                painter = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alignment = Alignment.Center,
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to WorldloomPalette.Canvas.copy(alpha = 0.54f),
                    0.48f to WorldloomPalette.Canvas.copy(alpha = 0.32f),
                    1f to WorldloomPalette.Canvas.copy(alpha = 0.91f),
                ),
            ),
        )
    }
}

internal fun gameplayBackdropAsset(assetId: String?): GameplayBackdropAsset = when (assetId) {
    "worldloom.background.war-ruins" -> GameplayBackdropAsset.WAR_RUINS
    "worldloom.background.station-core" -> GameplayBackdropAsset.STATION_CORE
    else -> GameplayBackdropAsset.GENERIC
}

@Composable
private fun LandscapeGameplay(
    window: WorldloomWindowSize,
    presentation: GamePresentation,
    notice: SessionError?,
    controller: GameAgentController?,
    interactive: Boolean,
    runKey: String,
    historyKey: String,
    onExit: () -> Unit,
    onReplay: () -> Unit,
    onCheck: (DefinitionId) -> Unit,
    onWait: (Long) -> Unit,
    onNpcBusyChanged: (Boolean) -> Unit,
    mapOpen: Boolean,
    onMapOpenChanged: (Boolean) -> Unit,
    composerDraft: String,
    onComposerDraftChanged: (String) -> Unit,
    sendOrdinal: Int,
    onSendOrdinalConsumed: () -> Unit,
    conversationListState: LazyListState,
    reduceMotion: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(if (window.short) WorldloomSpacing.Sm else WorldloomSpacing.Lg),
        horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Md),
    ) {
        if (mapOpen && gameplayUsesInlineMap(window)) {
            SceneMapPanel(
                presentation = presentation,
                modifier = Modifier.width(WorldloomDimensions.MapPanelWidth).fillMaxHeight(),
                onClose = { onMapOpenChanged(false) },
            )
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            GameTopBar(presentation, onExit, onMap = { onMapOpenChanged(!mapOpen) })
            Spacer(Modifier.height(WorldloomSpacing.Sm))
            GameConversation(
                presentation = presentation,
                notice = notice,
                controller = controller,
                interactive = interactive,
                runKey = runKey,
                historyKey = historyKey,
                modifier = Modifier.weight(1f),
                onNpcBusyChanged = onNpcBusyChanged,
                composerDraft = composerDraft,
                onComposerDraftChanged = onComposerDraftChanged,
                sendOrdinal = sendOrdinal,
                onSendOrdinalConsumed = onSendOrdinalConsumed,
                listState = conversationListState,
                reduceMotion = reduceMotion,
            )
        }
        WorldHud(
            presentation = presentation,
            interactive = interactive,
            modifier = Modifier.width(
                if (window.widthClass == WorldloomWidthClass.EXPANDED) {
                    WorldloomDimensions.HudPanelWidth
                } else {
                    WorldloomDimensions.HudPanelMediumWidth
                },
            ).fillMaxHeight(),
            onReplay = onReplay,
            onCheck = onCheck,
            onWait = onWait,
            onChatPrefix = onComposerDraftChanged,
        )
    }
}

internal fun gameplayUsesInlineMap(window: WorldloomWindowSize): Boolean =
    window.widthClass == WorldloomWidthClass.EXPANDED

@Composable
private fun PortraitGameplay(
    presentation: GamePresentation,
    notice: SessionError?,
    controller: GameAgentController?,
    interactive: Boolean,
    runKey: String,
    historyKey: String,
    onExit: () -> Unit,
    onNpcBusyChanged: (Boolean) -> Unit,
    mapOpen: Boolean,
    onMapOpenChanged: (Boolean) -> Unit,
    composerDraft: String,
    onComposerDraftChanged: (String) -> Unit,
    sendOrdinal: Int,
    onSendOrdinalConsumed: () -> Unit,
    conversationListState: LazyListState,
    reduceMotion: Boolean,
) {
    Column(modifier = Modifier.fillMaxSize().padding(WorldloomSpacing.Sm)) {
        GameTopBar(presentation, onExit, compact = true, onMap = { onMapOpenChanged(!mapOpen) })
        Spacer(Modifier.height(WorldloomSpacing.Sm))
        presentation.opening?.objective?.let { objective ->
            Text(
                text = "目标 · $objective",
                modifier = Modifier.fillMaxWidth()
                    .background(GlassColor, androidx.compose.material.MaterialTheme.shapes.medium)
                    .padding(WorldloomSpacing.Sm),
                color = WorldloomPalette.TextPrimary,
                style = androidx.compose.material.MaterialTheme.typography.caption,
                maxLines = 2,
            )
            Spacer(Modifier.height(WorldloomSpacing.Sm))
        }
        CharacterMemberPanel(
            presentation.characters,
            onChatPrefix = onComposerDraftChanged,
        )
        Spacer(Modifier.height(WorldloomSpacing.Sm))
        GameConversation(
            presentation = presentation,
            notice = notice,
            controller = controller,
            interactive = interactive,
            runKey = runKey,
            historyKey = historyKey,
            modifier = Modifier.weight(1f),
            onNpcBusyChanged = onNpcBusyChanged,
            composerDraft = composerDraft,
            onComposerDraftChanged = onComposerDraftChanged,
            sendOrdinal = sendOrdinal,
            onSendOrdinalConsumed = onSendOrdinalConsumed,
            listState = conversationListState,
            reduceMotion = reduceMotion,
        )
    }
}

@Composable
private fun GameTopBar(
    presentation: GamePresentation,
    onExit: () -> Unit,
    compact: Boolean = false,
    onMap: () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
    ) {
        Box(
            modifier = Modifier.size(
                if (compact) WorldloomDimensions.GameMarkCompactSize else WorldloomDimensions.GameMarkSize,
            )
                .clip(CircleShape)
                .background(PmAccent.copy(alpha = 0.2f))
                .border(1.dp, PmAccent.copy(alpha = 0.65f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text("W", color = PmAccent, fontWeight = FontWeight.Black)
        }
        Column(Modifier.weight(1f)) {
            Text(
                presentation.title,
                color = WorldloomPalette.TextPrimary,
                style = if (compact) {
                    androidx.compose.material.MaterialTheme.typography.h3
                } else {
                    androidx.compose.material.MaterialTheme.typography.h2
                },
                maxLines = 1,
            )
            Text(
                presentation.scene?.let {
                    val exits = presentation.exploration.knownExitCount
                    "${it.label} · ${exits} 个已知去处"
                } ?: "故事进行中",
                color = WorldloomPalette.TextSecondary,
                style = androidx.compose.material.MaterialTheme.typography.caption,
                maxLines = 1,
            )
        }
        if (presentation.exploration.situation != null) {
            WorldloomSecondaryButton(
                label = if (compact) "场景" else "地图与档案",
                onClick = onMap,
            )
        }
        WorldloomSecondaryButton(
            label = if (compact) "退出" else "世界与存档",
            onClick = onExit,
        )
    }
}

@Composable
private fun SceneMapPanel(
    presentation: GamePresentation,
    modifier: Modifier,
    onClose: () -> Unit,
    showClose: Boolean = true,
) {
    val exploration = presentation.exploration
    WorldloomPanel(modifier = modifier, strong = true, padding = WorldloomSpacing.Md) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("场景与地图", color = PmAccent, style = androidx.compose.material.MaterialTheme.typography.h3)
                Text(
                    presentation.scene?.label ?: "当前位置",
                    color = MutedText,
                    style = androidx.compose.material.MaterialTheme.typography.caption,
                )
            }
            if (showClose) WorldloomSecondaryButton("关闭", onClose)
        }
        exploration.situation?.let { situation ->
            Column(
                Modifier.fillMaxWidth()
                    .background(PmAccent.copy(alpha = 0.09f), androidx.compose.material.MaterialTheme.shapes.small)
                    .padding(WorldloomSpacing.Md),
                verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs),
            ) {
                Text("当前目标", color = PmAccent, style = androidx.compose.material.MaterialTheme.typography.subtitle1)
                Text(situation.objective, color = WorldloomPalette.TextPrimary)
                Text("压力 · ${situation.pressure}", color = WorldloomPalette.Warning)
                Text(situation.question, color = WorldloomPalette.TextSecondary)
            }
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm)) {
            if (exploration.nodes.isNotEmpty()) {
                item("map-heading") {
                    Text("已知地点", color = MutedText, style = androidx.compose.material.MaterialTheme.typography.subtitle1)
                }
                items(exploration.nodes, key = { it.id.value }) { node ->
                    val accent = explorationLevelColor(node.level)
                    Row(
                        Modifier.fillMaxWidth()
                            .background(accent.copy(alpha = 0.12f), androidx.compose.material.MaterialTheme.shapes.small)
                            .border(1.dp, accent.copy(alpha = 0.42f), androidx.compose.material.MaterialTheme.shapes.small)
                            .padding(WorldloomSpacing.Sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
                    ) {
                        Box(Modifier.size(WorldloomDimensions.StatusDotSize).background(accent, CircleShape))
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (node.current) "当前位置 · " else "") + node.label,
                                color = WorldloomPalette.TextPrimary,
                                style = androidx.compose.material.MaterialTheme.typography.body2,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                node.description,
                                color = MutedText,
                                style = androidx.compose.material.MaterialTheme.typography.caption,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            explorationLevelLabel(node.level),
                            color = accent,
                            style = androidx.compose.material.MaterialTheme.typography.caption,
                        )
                    }
                }
            }
            if (exploration.connections.isNotEmpty()) {
                item("route-heading") {
                    Text("已知路线", color = MutedText, style = androidx.compose.material.MaterialTheme.typography.subtitle1)
                }
                items(exploration.connections, key = { it.id.value }) { route ->
                    Column(
                        Modifier.fillMaxWidth()
                            .background(WorldloomPalette.SurfaceRaised.copy(alpha = 0.52f), androidx.compose.material.MaterialTheme.shapes.small)
                            .padding(WorldloomSpacing.Sm),
                    ) {
                        Text(route.label, color = WorldloomPalette.TextPrimary, fontWeight = FontWeight.SemiBold)
                        val detail = listOfNotNull(route.directionSummary, route.travelMinutes?.let { "约 $it 分钟" }, route.riskSummary).joinToString(" · ")
                        if (detail.isNotBlank()) {
                            Text(detail, color = MutedText, style = androidx.compose.material.MaterialTheme.typography.caption)
                        }
                    }
                }
            }
            if (exploration.affordances.isNotEmpty()) {
                item("affordance-heading") {
                    Text("眼前可互动", color = MutedText, style = androidx.compose.material.MaterialTheme.typography.subtitle1)
                }
                items(exploration.affordances, key = { it.id.value }) { affordance ->
                    Text("• ${affordance.label} · ${affordance.description}", color = WorldloomPalette.TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun GameContextOverlay(
    presentation: GamePresentation,
    interactive: Boolean,
    pane: GameContextPane,
    onPaneChanged: (GameContextPane) -> Unit,
    onClose: () -> Unit,
    onReplay: () -> Unit,
    onCheck: (DefinitionId) -> Unit,
    onWait: (Long) -> Unit,
    onChatPrefix: (String) -> Unit,
    modifier: Modifier,
) {
    WorldloomPanel(modifier = modifier, strong = true, padding = WorldloomSpacing.Sm) {
        WorldloomSectionHeading(
            title = "场景信息",
            subtitle = "地图与任务档案保持同一局状态。",
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
        ) {
            item { WorldloomSecondaryButton("地图", { onPaneChanged(GameContextPane.MAP) }) }
            item { WorldloomSecondaryButton("任务档案", { onPaneChanged(GameContextPane.HUD) }) }
            item { WorldloomSecondaryButton("关闭", onClose) }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (pane) {
                GameContextPane.MAP -> SceneMapPanel(
                    presentation = presentation,
                    modifier = Modifier.fillMaxSize(),
                    onClose = onClose,
                    showClose = false,
                )
                GameContextPane.HUD -> WorldHud(
                    presentation = presentation,
                    interactive = interactive,
                    modifier = Modifier.fillMaxSize(),
                    onReplay = onReplay,
                    onCheck = onCheck,
                    onWait = onWait,
                    onChatPrefix = onChatPrefix,
                )
            }
        }
    }
}

private fun explorationLevelLabel(level: ExplorationKnowledgeLevel): String = when (level) {
    ExplorationKnowledgeLevel.VISITED -> "到过"
    ExplorationKnowledgeLevel.DISCOVERED -> "已发现"
    ExplorationKnowledgeLevel.RUMORED -> "传闻"
    ExplorationKnowledgeLevel.BLOCKED -> "封锁"
}

private fun explorationLevelColor(level: ExplorationKnowledgeLevel): Color = when (level) {
    ExplorationKnowledgeLevel.VISITED -> PmAccent
    ExplorationKnowledgeLevel.DISCOVERED -> NpcAccent
    ExplorationKnowledgeLevel.RUMORED -> WorldloomPalette.Info
    ExplorationKnowledgeLevel.BLOCKED -> WorldloomPalette.Error
}

@Composable
private fun GameConversation(
    presentation: GamePresentation,
    notice: SessionError?,
    controller: GameAgentController?,
    interactive: Boolean,
    runKey: String,
    historyKey: String,
    modifier: Modifier,
    onNpcBusyChanged: (Boolean) -> Unit,
    composerDraft: String,
    onComposerDraftChanged: (String) -> Unit,
    sendOrdinal: Int,
    onSendOrdinalConsumed: () -> Unit,
    listState: LazyListState,
    reduceMotion: Boolean,
) {
    val agentState = controller?.state?.collectAsState()?.value ?: GameAgentState.Idle
    val history = controller?.history?.collectAsState()?.value
        ?: io.worldloom.agent.runtime.GameAgentHistoryState()
    val messages = remember(presentation, history) { buildGameChatMessages(presentation, history) }
    val pendingTurn = history.items.lastOrNull { it.pendingCheck != null }
    val scope = rememberCoroutineScope()
    val hasPlayedMessages = history.items.isNotEmpty() || presentation.timeline.any { it.chatMessage != null }
    LaunchedEffect(controller, runKey) { controller?.recoverInterruptedHistory() }
    LaunchedEffect(messages.size, (agentState as? GameAgentState.Running)?.partialText) {
        val extra = (if (pendingTurn != null) 1 else 0) + (if (agentState is GameAgentState.Running) 1 else 0)
        if (messages.isNotEmpty() && (hasPlayedMessages || extra > 0)) {
            listState.scrollToItem(messages.lastIndex + extra)
        }
    }

    Column(
        modifier = modifier.background(GlassColor, androidx.compose.material.MaterialTheme.shapes.large)
            .border(1.dp, FineBorder, androidx.compose.material.MaterialTheme.shapes.large)
            .padding(WorldloomSpacing.Sm),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
        ) {
            items(messages, key = GameChatMessage::id) { ChatBubble(it, reduceMotion = reduceMotion) }
            pendingTurn?.let { turn ->
                val pending = requireNotNull(turn.pendingCheck)
                item("pending-check-${turn.turnId.value}") {
                    PendingCheckCard(
                        check = pending,
                        rolling = agentState is GameAgentState.Running,
                        enabled = interactive && controller != null && agentState !is GameAgentState.Running,
                        onRoll = { scope.launch { controller?.rollPendingCheck(turn.turnId) } },
                    )
                }
            }
            notice?.let { message ->
                item("notice-${message.code}") {
                    WorldloomStatusBanner(message.message, WorldloomStatusTone.ERROR)
                }
            }
            if (agentState is GameAgentState.Running) {
                item("pm-running") {
                    ChatBubble(
                        GameChatMessage(
                            id = "pm-running",
                            order = Long.MAX_VALUE,
                            speaker = "PM",
                            kind = GameChatSpeakerKind.PM,
                            content = agentState.partialText.ifBlank { "正在编织下一段故事…" },
                        ),
                        typing = true,
                        reduceMotion = reduceMotion,
                    )
                }
            }
        }
        Spacer(Modifier.height(WorldloomSpacing.Sm))
        SceneSuggestions(
            presentation = presentation,
            enabled = interactive && controller != null && agentState !is GameAgentState.Running && pendingTurn == null,
            onSuggestionSelected = onComposerDraftChanged,
        )
        Spacer(Modifier.height(WorldloomSpacing.Sm))
        ChatComposer(
            controller = controller,
            characters = presentation.characters,
            historyKey = historyKey,
            enabled = interactive && agentState !is GameAgentState.Running && pendingTurn == null,
            onNpcBusyChanged = onNpcBusyChanged,
            input = composerDraft,
            onInputChanged = onComposerDraftChanged,
            sendOrdinal = sendOrdinal,
            onSendOrdinalConsumed = onSendOrdinalConsumed,
        )
    }
}

@Composable
private fun PendingCheckCard(
    check: io.worldloom.agent.runtime.PendingPlayerCheck,
    rolling: Boolean,
    enabled: Boolean,
    onRoll: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        ChatMessageAvatar(
            GameChatMessage(
                id = "pending-check-avatar",
                order = Long.MAX_VALUE,
                speaker = "PM",
                kind = GameChatSpeakerKind.PM,
                content = "",
            ),
            PmAccent,
        )
        Spacer(Modifier.width(WorldloomSpacing.Sm))
        Column(
            modifier = Modifier.fillMaxWidth(0.78f)
                .background(WorldloomPalette.SurfaceRaised.copy(alpha = 0.94f), RoundedCornerShape(16.dp))
                .border(1.dp, PmAccent.copy(alpha = 0.58f), RoundedCornerShape(16.dp))
                .padding(WorldloomSpacing.Md),
            verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
        ) {
            Text("需要判定", color = PmAccent, fontWeight = FontWeight.Bold)
            Text(check.actionLabel, color = WorldloomPalette.TextPrimary)
            Text(
                listOfNotNull(check.profileLabel, check.diceNotation).joinToString(" · "),
                color = MutedText,
                style = androidx.compose.material.MaterialTheme.typography.caption,
            )
            Text(
                "点击后由规则引擎掷骰并记录结果，再继续故事。",
                color = MutedText,
                style = androidx.compose.material.MaterialTheme.typography.caption,
            )
            WorldloomPrimaryButton(
                label = if (rolling) "结算中…" else check.diceNotation?.let { "掷骰 · $it" } ?: "进行判定",
                onClick = onRoll,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ChatBubble(
    message: GameChatMessage,
    typing: Boolean = false,
    reduceMotion: Boolean = false,
) {
    if (message.kind == GameChatSpeakerKind.SYSTEM) {
        SystemChatBubble(message)
        return
    }
    val player = message.kind == GameChatSpeakerKind.PLAYER
    val bubbleColor = when (message.kind) {
        GameChatSpeakerKind.PM -> WorldloomPalette.SurfaceRaised.copy(alpha = 0.88f)
        GameChatSpeakerKind.PLAYER -> PlayerBubble
        GameChatSpeakerKind.NPC -> WorldloomPalette.NarrativeNpc.copy(alpha = 0.2f)
        GameChatSpeakerKind.SYSTEM -> Color.Transparent
    }
    val accent = when (message.kind) {
        GameChatSpeakerKind.PM -> PmAccent
        GameChatSpeakerKind.PLAYER -> WorldloomPalette.NarrativePlayer
        GameChatSpeakerKind.NPC -> NpcAccent
        GameChatSpeakerKind.SYSTEM -> WorldloomPalette.Info
    }
    val bubbleShape = if (player) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * if (player) 0.72f else 0.78f
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (player) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (!player) {
                ChatMessageAvatar(message, accent)
                Spacer(Modifier.width(WorldloomSpacing.Sm))
            }
            Column(
                modifier = Modifier.widthIn(max = bubbleMaxWidth),
                horizontalAlignment = if (player) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        message.speaker,
                        color = accent,
                        style = androidx.compose.material.MaterialTheme.typography.caption,
                        fontWeight = FontWeight.Bold,
                    )
                    message.audienceLabel?.let { label ->
                        Spacer(Modifier.width(WorldloomSpacing.Sm))
                        Text(
                            label,
                            color = if (message.private) PmAccent else MutedText,
                            style = androidx.compose.material.MaterialTheme.typography.caption,
                            modifier = Modifier.background(
                                if (message.private) PmAccent.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.07f),
                                RoundedCornerShape(50),
                            ).padding(horizontal = WorldloomSpacing.Sm, vertical = WorldloomSpacing.Xs),
                        )
                    }
                    if (typing && !reduceMotion) {
                        Spacer(Modifier.width(WorldloomSpacing.Sm))
                        CircularProgressIndicator(
                            modifier = Modifier.size(WorldloomDimensions.TypingIndicatorSize),
                            strokeWidth = 1.5.dp,
                            color = accent,
                        )
                    } else if (typing) {
                        Spacer(Modifier.width(WorldloomSpacing.Sm))
                        Text("正在回应", color = accent, style = androidx.compose.material.MaterialTheme.typography.caption)
                    }
                }
                Box(
                    modifier = Modifier.clip(bubbleShape)
                        .background(bubbleColor)
                        .border(1.dp, accent.copy(alpha = 0.3f), bubbleShape)
                        .padding(horizontal = WorldloomSpacing.Md, vertical = WorldloomSpacing.Sm),
                ) {
                    Text(
                        message.content,
                        color = WorldloomPalette.TextPrimary,
                        style = androidx.compose.material.MaterialTheme.typography.body2,
                    )
                }
            }
            if (player) {
                Spacer(Modifier.width(WorldloomSpacing.Sm))
                ChatMessageAvatar(message, accent)
            }
        }
    }
}

@Composable
private fun ChatMessageAvatar(message: GameChatMessage, accent: Color) {
    val painter = gameplayAvatarPainter(message.avatarAssetId)
    val fallback = when (message.kind) {
        GameChatSpeakerKind.PM -> "PM"
        GameChatSpeakerKind.PLAYER -> "我"
        GameChatSpeakerKind.NPC -> message.speaker.take(1).uppercase()
        GameChatSpeakerKind.SYSTEM -> "i"
    }
    Box(
        modifier = Modifier.size(WorldloomDimensions.ChatAvatarSize)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.7f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = "${message.speaker} 头像",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                fallback,
                color = accent,
                fontWeight = FontWeight.Black,
                style = androidx.compose.material.MaterialTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun SystemChatBubble(message: GameChatMessage) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Row(
            modifier = Modifier.fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(50))
                .background(WorldloomPalette.Info.copy(alpha = 0.12f))
                .border(1.dp, WorldloomPalette.Info.copy(alpha = 0.24f), RoundedCornerShape(50))
                .padding(horizontal = WorldloomSpacing.Md, vertical = WorldloomSpacing.Sm),
            horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(18.dp).clip(CircleShape)
                    .background(WorldloomPalette.Info.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("i", color = WorldloomPalette.Info, fontWeight = FontWeight.Bold)
            }
            Text(
                message.content,
                color = WorldloomPalette.TextSecondary,
                style = androidx.compose.material.MaterialTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun SceneSuggestions(
    presentation: GamePresentation,
    enabled: Boolean,
    onSuggestionSelected: (String) -> Unit,
) {
    var strength by remember(presentation.worldId) { mutableStateOf(GuidanceStrength.NEWCOMER) }
    var hintRequested by remember(presentation.worldId, presentation.scene?.id) { mutableStateOf(false) }
    val authored = gameplaySuggestions(presentation)
    val suggestions = when (strength) {
        GuidanceStrength.NEWCOMER -> authored
        GuidanceStrength.STANDARD -> authored.take(2)
        GuidanceStrength.IMMERSIVE -> emptyList()
    }
    val hint = presentation.guidance.hints.firstOrNull()?.suggestion
    if (authored.isEmpty() && hint == null) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm)) {
        item {
            WorldloomSecondaryButton(
                label = "引导 · ${strength.label}",
                onClick = {
                    strength = when (strength) {
                        GuidanceStrength.NEWCOMER -> GuidanceStrength.STANDARD
                        GuidanceStrength.STANDARD -> GuidanceStrength.IMMERSIVE
                        GuidanceStrength.IMMERSIVE -> GuidanceStrength.NEWCOMER
                    }
                },
            )
        }
        items(suggestions, key = { "${it.targetKind}:${it.targetId.value}" }) { suggestion ->
            WorldloomSecondaryButton(
                label = suggestion.label,
                onClick = { onSuggestionSelected(suggestion.inputDraft) },
                enabled = enabled,
            )
        }
        if (hint != null) {
            item("request-hint") {
                WorldloomSecondaryButton(
                    label = if (hintRequested) "收起提示" else "需要提示",
                    onClick = { hintRequested = !hintRequested },
                )
            }
            if (hintRequested) {
                item("hint-draft-${hint.targetId.value}") {
                    WorldloomSecondaryButton(
                        label = hint.label,
                        onClick = { onSuggestionSelected(hint.inputDraft) },
                        enabled = enabled,
                    )
                }
            }
        }
    }
    if (strength == GuidanceStrength.NEWCOMER) {
        suggestions.firstOrNull()?.let { suggestion ->
            val detail = listOfNotNull(suggestion.rationale, suggestion.tradeoff).joinToString(" · ")
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    color = MutedText,
                    style = androidx.compose.material.MaterialTheme.typography.caption,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

internal fun gameplaySuggestions(presentation: GamePresentation): List<PresentedGuidanceSuggestion> =
    presentation.guidance.suggestions

internal fun gameplaySuggestionDraft(
    presentation: GamePresentation,
    targetKind: GuidanceTargetKind,
    targetId: DefinitionId,
    fallback: String,
): String = presentation.guidance.suggestions
    .firstOrNull { it.targetKind == targetKind && it.targetId == targetId }
    ?.inputDraft
    ?: fallback

@Composable
private fun ChatComposer(
    controller: GameAgentController?,
    characters: List<PresentedNpc>,
    historyKey: String,
    enabled: Boolean,
    onNpcBusyChanged: (Boolean) -> Unit,
    input: String,
    onInputChanged: (String) -> Unit,
    sendOrdinal: Int,
    onSendOrdinalConsumed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var sendingNpc by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusTone by remember { mutableStateOf(WorldloomStatusTone.INFO) }
    val canInput = enabled && controller != null && !sendingNpc
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm)) {
        OutlinedTextField(
            value = input,
            onValueChange = { if (it.length <= 2_000) onInputChanged(it) },
            modifier = Modifier.weight(1f),
            label = { Text("行动 / @公开 / #私聊") },
            enabled = canInput,
            maxLines = 2,
        )
        WorldloomPrimaryButton(
            label = if (sendingNpc) "发送中" else "发送",
            enabled = canInput && input.isNotBlank(),
            onClick = {
                val submitted = input.trim()
                status = null
                when (val parsed = parseChatInput(submitted, characters)) {
                    is ParsedChatInput.Invalid -> {
                        status = parsed.message
                        statusTone = WorldloomStatusTone.WARNING
                    }
                    is ParsedChatInput.ToPm -> {
                        onInputChanged("")
                        statusTone = WorldloomStatusTone.INFO
                        scope.launch { controller?.send(parsed.content) }
                    }
                    is ParsedChatInput.ToNpc -> {
                        onInputChanged("")
                        sendingNpc = true
                        onNpcBusyChanged(true)
                        val idempotencyKey = "ui:$historyKey:${parsed.npc.id.value}:$sendOrdinal"
                        onSendOrdinalConsumed()
                        scope.launch {
                            try {
                                status = when (
                                    val result = controller?.addressNpc(
                                        parsed.npc.id,
                                        parsed.content,
                                        idempotencyKey,
                                        parsed.audience,
                                        parsed.communicationMethodId,
                                    )
                                ) {
                                    is NpcDialogueResult.Committed -> {
                                        statusTone = WorldloomStatusTone.SUCCESS
                                        if (result.worldChanged) "消息已送达。" else "消息已处理。"
                                    }
                                    is NpcDialogueResult.Failed -> {
                                        statusTone = WorldloomStatusTone.ERROR
                                        result.message
                                    }
                                    null -> {
                                        statusTone = WorldloomStatusTone.ERROR
                                        "主持服务不可用。"
                                    }
                                }
                            } finally {
                                sendingNpc = false
                                onNpcBusyChanged(false)
                            }
                        }
                    }
                }
            },
        )
    }
    if (controller == null) {
        WorldloomStatusBanner(
            "请先在世界与服务页面配置主持模型。",
            WorldloomStatusTone.WARNING,
        )
    } else {
        status?.let { WorldloomStatusBanner(it, statusTone) }
    }
}

@Composable
private fun WorldHud(
    presentation: GamePresentation,
    interactive: Boolean,
    modifier: Modifier,
    onReplay: () -> Unit,
    onCheck: (DefinitionId) -> Unit,
    onWait: (Long) -> Unit,
    onChatPrefix: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.background(GlassColor, androidx.compose.material.MaterialTheme.shapes.large)
            .border(1.dp, FineBorder, androidx.compose.material.MaterialTheme.shapes.large)
            .padding(WorldloomSpacing.Md),
        verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
    ) {
        item {
            Text("任务档案", color = PmAccent, style = androidx.compose.material.MaterialTheme.typography.h3)
            presentation.opening?.let {
                Text(it.objective, color = WorldloomPalette.TextPrimary)
            }
        }
        presentation.scene?.let { scene ->
            item {
                HudSection("当前场景 · ${scene.label}") {
                    scene.description?.let { Text(it, color = WorldloomPalette.TextSecondary) }
                }
            }
            if (scene.actions.isNotEmpty()) {
                item {
                    HudSection("行动") {
                        scene.actions.forEach { action ->
                            WorldloomSecondaryButton(
                                label = action.label,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    onChatPrefix(
                                        gameplaySuggestionDraft(
                                            presentation,
                                            GuidanceTargetKind.ACTION,
                                            action.id,
                                            "我想${action.label}。",
                                        ),
                                    )
                                },
                                enabled = interactive,
                            )
                        }
                    }
                }
            }
        }
        item {
            CharacterMemberPanel(
                characters = presentation.characters,
                onChatPrefix = onChatPrefix,
            )
        }
        if (presentation.fields.isNotEmpty()) {
            item {
                HudSection("状态") {
                    presentation.fields.forEach { field ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(field.label, color = MutedText, modifier = Modifier.weight(1f))
                            Text(field.value.toString(), color = WorldloomPalette.TextPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        if (presentation.checks.isNotEmpty()) {
            item {
                HudSection("判定") {
                    presentation.checks.forEach { check ->
                        WorldloomSecondaryButton(
                            label = check.label,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { onCheck(check.presentationId) },
                            enabled = interactive,
                        )
                    }
                }
            }
        }
        if (presentation.activities.isNotEmpty() || presentation.travelRoutes.isNotEmpty() || presentation.worldTimeMinutes != null) {
            item {
                HudSection("时间与移动") {
                    presentation.worldTimeMinutes?.let {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("第 $it 分钟", modifier = Modifier.weight(1f), color = MutedText)
                            WorldloomSecondaryButton("等待", { onWait(60) }, enabled = interactive)
                        }
                    }
                    presentation.activities.forEach { activity ->
                        WorldloomSecondaryButton(
                            label = "${activity.label} · ${activity.durationMinutes} 分钟",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onChatPrefix(
                                    gameplaySuggestionDraft(
                                        presentation,
                                        GuidanceTargetKind.ACTIVITY,
                                        activity.id,
                                        "我想先${activity.label}。",
                                    ),
                                )
                            },
                            enabled = interactive,
                        )
                    }
                    presentation.travelRoutes.forEach { route ->
                        WorldloomSecondaryButton(
                            label = "${route.label} · ${route.durationMinutes} 分钟",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                onChatPrefix(
                                    gameplaySuggestionDraft(
                                        presentation,
                                        GuidanceTargetKind.TRAVEL,
                                        route.id,
                                        "我想沿${route.label}前进。",
                                    ),
                                )
                            },
                            enabled = interactive,
                        )
                    }
                }
            }
        }
        presentation.adventureState?.let { adventure ->
            item {
                HudSection("世界状态") {
                    adventure.quests.forEach { quest ->
                        Text(
                            "${quest.label} · ${quest.stageLabel ?: questStatusLabel(quest.status)}",
                            color = WorldloomPalette.TextSecondary,
                        )
                    }
                    adventure.conditions.forEach { condition ->
                        Text("${condition.label} · ${condition.stacks}", color = WorldloomPalette.TextSecondary)
                    }
                }
            }
        }
        presentation.endingSummary?.let { summary ->
            item { HudSection("结局") { Text(summary, color = WorldloomPalette.TextPrimary) } }
        }
        item {
            WorldloomSecondaryButton(
                label = "回放校验 · ${presentation.lastSequence} 个事件",
                modifier = Modifier.fillMaxWidth(),
                onClick = onReplay,
            )
        }
    }
}

internal fun questStatusLabel(status: io.worldloom.rules.QuestStatus): String = when (status) {
    io.worldloom.rules.QuestStatus.NOT_STARTED -> "尚未开始"
    io.worldloom.rules.QuestStatus.ACTIVE -> "进行中"
    io.worldloom.rules.QuestStatus.COMPLETED -> "已完成"
    io.worldloom.rules.QuestStatus.FAILED -> "已失败"
}

@Composable
private fun CharacterMemberPanel(
    characters: List<PresentedNpc>,
    onChatPrefix: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var nearbyOnly by remember { mutableStateOf(true) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val visibleCharacters = if (nearbyOnly) characters.filter(PresentedNpc::nearby) else characters
    val selectedCharacter = visibleCharacters.firstOrNull { it.id.value == selectedId }
    Column(
        modifier = modifier.fillMaxWidth()
            .background(GlassStrong, androidx.compose.material.MaterialTheme.shapes.medium)
            .padding(WorldloomSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "群聊成员",
                color = PmAccent,
                style = androidx.compose.material.MaterialTheme.typography.subtitle1,
            )
            Spacer(Modifier.weight(1f))
            CharacterRosterTab(
                label = "身边 ${visibleNearbyCount(characters)}",
                selected = nearbyOnly,
                onClick = {
                    nearbyOnly = true
                    selectedId = null
                },
            )
            Spacer(Modifier.width(WorldloomSpacing.Xs))
            CharacterRosterTab(
                label = "全部 ${characters.size}",
                selected = !nearbyOnly,
                onClick = {
                    nearbyOnly = false
                    selectedId = null
                },
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs)) {
            if (nearbyOnly) {
                item(PM_MEMBER_ID) {
                    CharacterMember(
                        name = "PM",
                        avatarAssetId = null,
                        reachable = true,
                        selected = selectedId == PM_MEMBER_ID,
                        onClick = {
                            selectedId = if (selectedId == PM_MEMBER_ID) null else PM_MEMBER_ID
                        },
                    )
                }
            }
            items(visibleCharacters, key = { it.id.value }) { character ->
                CharacterMember(
                    name = character.displayName,
                    avatarAssetId = character.avatarAssetId,
                    reachable = character.nearby || character.remoteCommunicationMethods.isNotEmpty(),
                    selected = selectedId == character.id.value,
                    onClick = {
                        selectedId = if (selectedId == character.id.value) null else character.id.value
                    },
                )
            }
            if (!nearbyOnly && visibleCharacters.isEmpty()) {
                item {
                    Text(
                        "暂无角色",
                        color = MutedText,
                        style = androidx.compose.material.MaterialTheme.typography.caption,
                        modifier = Modifier.padding(WorldloomSpacing.Md),
                    )
                }
            }
        }
        when {
            nearbyOnly && selectedId == PM_MEMBER_ID -> CharacterMemberProfile(
                name = "PM",
                status = "始终可见",
                introduction = "主持人负责介绍场景、扮演角色并裁定游戏进展。",
                canPublic = true,
                canPrivate = false,
                onPublic = { onChatPrefix("@PM ") },
                onPrivate = {},
            )
            selectedCharacter != null -> {
                val character = selectedCharacter
                val remote = character.remoteCommunicationMethods.firstOrNull()
                CharacterMemberProfile(
                    name = character.displayName,
                    status = when {
                        character.nearby && remote != null -> "在身边 · ${remote.label}"
                        character.nearby -> "在身边"
                        remote != null -> "远程 · ${remote.label}"
                        else -> "未联络"
                    },
                    introduction = character.publicIntroduction ?: "暂无公开人物介绍。",
                    canPublic = character.nearby,
                    canPrivate = character.nearby || remote != null,
                    onPublic = { onChatPrefix("@${character.displayName} ") },
                    onPrivate = { onChatPrefix("#${character.displayName} ") },
                )
            }
            else -> Text(
                "点击头像查看人物介绍",
                color = MutedText.copy(alpha = 0.78f),
                style = androidx.compose.material.MaterialTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun CharacterRosterTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier.heightIn(min = WorldloomDimensions.DesktopTouchTarget)
            .clip(androidx.compose.material.MaterialTheme.shapes.small)
            .background(if (selected) PmAccent.copy(alpha = 0.2f) else WorldloomPalette.SurfaceRaised.copy(alpha = 0.48f))
            .border(
                1.dp,
                if (selected) PmAccent.copy(alpha = 0.5f) else FineBorder,
                androidx.compose.material.MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = WorldloomSpacing.Sm, vertical = WorldloomSpacing.Xs),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) PmAccent else MutedText,
            style = androidx.compose.material.MaterialTheme.typography.caption,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CharacterMember(
    name: String,
    avatarAssetId: String?,
    reachable: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier.width(WorldloomDimensions.AvatarControlWidth)
            .heightIn(min = WorldloomDimensions.TouchTarget)
            .clip(androidx.compose.material.MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .background(if (selected) PmAccent.copy(alpha = 0.09f) else Color.Transparent)
            .padding(vertical = WorldloomSpacing.Xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CharacterAvatar(name, avatarAssetId, reachable, selected)
        Spacer(Modifier.height(WorldloomSpacing.Xs))
        Text(
            name,
            color = if (selected) PmAccent else WorldloomPalette.TextPrimary,
            style = androidx.compose.material.MaterialTheme.typography.caption,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CharacterAvatar(
    name: String,
    avatarAssetId: String?,
    reachable: Boolean,
    selected: Boolean,
) {
    val painter = gameplayAvatarPainter(avatarAssetId)
    Box(Modifier.size(WorldloomDimensions.AvatarSize)) {
        Box(
            modifier = Modifier.fillMaxSize().clip(CircleShape)
                .background(if (name == "PM") PmAccent.copy(alpha = 0.18f) else NpcAccent.copy(alpha = 0.14f))
                .border(if (selected) 2.dp else 1.dp, if (selected) PmAccent else FineBorder, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (painter != null) {
                Image(
                    painter = painter,
                    contentDescription = "$name 头像",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    name.take(1).uppercase(),
                    color = if (name == "PM") PmAccent else NpcAccent,
                    fontWeight = FontWeight.Black,
                    style = androidx.compose.material.MaterialTheme.typography.subtitle1,
                )
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).size(WorldloomDimensions.StatusDotSize).clip(CircleShape)
                .background(if (reachable) NpcAccent else WorldloomPalette.TextMuted)
                .border(2.dp, GlassStrong, CircleShape),
        )
    }
}

internal fun gameplayAvatarAsset(assetId: String?): GameplayAvatarAsset = when (assetId) {
    "worldloom.avatar.war-mara" -> GameplayAvatarAsset.WAR_MARA
    "worldloom.avatar.war-tomas" -> GameplayAvatarAsset.WAR_TOMAS
    "worldloom.avatar.station-lyra" -> GameplayAvatarAsset.STATION_LYRA
    "worldloom.avatar.station-soren" -> GameplayAvatarAsset.STATION_SOREN
    else -> GameplayAvatarAsset.GENERIC
}

@Composable
private fun gameplayAvatarPainter(assetId: String?): Painter? = when (gameplayAvatarAsset(assetId)) {
    GameplayAvatarAsset.WAR_MARA -> painterResource(Res.drawable.npc_war_mara)
    GameplayAvatarAsset.WAR_TOMAS -> painterResource(Res.drawable.npc_war_tomas)
    GameplayAvatarAsset.STATION_LYRA -> painterResource(Res.drawable.npc_station_lyra)
    GameplayAvatarAsset.STATION_SOREN -> painterResource(Res.drawable.npc_station_soren)
    GameplayAvatarAsset.GENERIC -> null
}

@Composable
private fun CharacterMemberProfile(
    name: String,
    status: String,
    introduction: String,
    canPublic: Boolean,
    canPrivate: Boolean,
    onPublic: () -> Unit,
    onPrivate: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(WorldloomPalette.SurfaceRaised.copy(alpha = 0.48f), androidx.compose.material.MaterialTheme.shapes.small)
            .padding(WorldloomSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm)) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = WorldloomPalette.TextPrimary,
                    style = androidx.compose.material.MaterialTheme.typography.body2,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    status,
                    color = if (canPrivate) NpcAccent else MutedText,
                    style = androidx.compose.material.MaterialTheme.typography.caption,
                    maxLines = 1,
                )
            }
            if (canPublic) RosterActionButton("@ 对话", onPublic)
            if (canPrivate) RosterActionButton("# 私聊", onPrivate)
        }
        Text(
            introduction,
            color = WorldloomPalette.TextSecondary,
            style = androidx.compose.material.MaterialTheme.typography.caption,
            maxLines = 3,
        )
    }
}

@Composable
private fun RosterActionButton(label: String, onClick: () -> Unit) {
    WorldloomSecondaryButton(label = label, onClick = onClick)
}

private fun visibleNearbyCount(characters: List<PresentedNpc>): Int = characters.count(PresentedNpc::nearby)

@Composable
private fun HudSection(title: String, content: @Composable () -> Unit) {
    WorldloomPanel(modifier = Modifier.fillMaxWidth(), strong = true, padding = WorldloomSpacing.Sm) {
        Text(title, color = PmAccent, style = androidx.compose.material.MaterialTheme.typography.subtitle1)
        content()
    }
}
