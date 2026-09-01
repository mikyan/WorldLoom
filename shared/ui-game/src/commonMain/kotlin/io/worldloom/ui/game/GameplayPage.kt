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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val GlassColor = Color(0xD6171B1D)
private val GlassStrong = Color(0xEE111518)
private val FineBorder = Color(0x38E8D7AE)
private val PmAccent = Color(0xFFE2BD72)
private val NpcAccent = Color(0xFF80B8B3)
private val PlayerBubble = Color(0xDD6E5329)
private val MutedText = Color(0xFFB9B7B0)
private const val PM_MEMBER_ID = "worldloom.member.pm"

private enum class GuidanceStrength(val label: String) { NEWCOMER("新手"), STANDARD("标准"), IMMERSIVE("沉浸") }

@Composable
internal fun GameplayPage(
    presentation: GamePresentation,
    notice: SessionError?,
    agentController: GameAgentController?,
    interactive: Boolean,
    runKey: String,
    historyKey: String,
    onExit: () -> Unit,
    onReplay: () -> Unit,
    onAdjust: (DefinitionId) -> Unit,
    onCheck: (DefinitionId) -> Unit,
    onWait: (Long) -> Unit,
    onNpcBusyChanged: (Boolean) -> Unit,
) {
    var mapOpen by remember(runKey) { mutableStateOf(false) }
    var composerDraft by remember(runKey) { mutableStateOf("") }
    var sendOrdinal by remember(historyKey) { mutableIntStateOf(0) }
    val conversationListState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        GameBackdrop(presentation.scene?.backgroundAssetId ?: presentation.opening?.backgroundAssetId)
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
            val portrait = maxHeight > maxWidth
            if (portrait) {
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
                )
            } else {
                LandscapeGameplay(
                    presentation,
                    notice,
                    agentController,
                    interactive,
                    runKey,
                    historyKey,
                    onExit,
                    onReplay,
                    onAdjust,
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
                )
            }
        }
        if (mapOpen) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (maxHeight > maxWidth) {
                    Box(Modifier.fillMaxSize().background(Color(0xF20B0E10)).padding(10.dp)) {
                        SceneMapPanel(
                            presentation = presentation,
                            modifier = Modifier.fillMaxSize(),
                            onClose = { mapOpen = false },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GameBackdrop(assetId: String?) {
    val painter = when (assetId) {
        "worldloom.background.war-ruins" -> painterResource(Res.drawable.gameplay_war_ruins)
        "worldloom.background.station-core" -> painterResource(Res.drawable.gameplay_station_core)
        else -> null
    }
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0E10))) {
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
                    0f to Color(0x8A050708),
                    0.48f to Color(0x52050708),
                    1f to Color(0xE8050708),
                ),
            ),
        )
    }
}

@Composable
private fun LandscapeGameplay(
    presentation: GamePresentation,
    notice: SessionError?,
    controller: GameAgentController?,
    interactive: Boolean,
    runKey: String,
    historyKey: String,
    onExit: () -> Unit,
    onReplay: () -> Unit,
    onAdjust: (DefinitionId) -> Unit,
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
) {
    Row(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (mapOpen) {
            SceneMapPanel(
                presentation = presentation,
                modifier = Modifier.width(300.dp).fillMaxHeight(),
                onClose = { onMapOpenChanged(false) },
            )
        }
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            GameTopBar(presentation, onExit, onMap = { onMapOpenChanged(!mapOpen) })
            Spacer(Modifier.height(8.dp))
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
            )
        }
        WorldHud(
            presentation = presentation,
            interactive = interactive,
            modifier = Modifier.width(292.dp).fillMaxHeight(),
            onReplay = onReplay,
            onAdjust = onAdjust,
            onCheck = onCheck,
            onWait = onWait,
            onChatPrefix = onComposerDraftChanged,
        )
    }
}

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
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 8.dp)) {
        GameTopBar(presentation, onExit, compact = true, onMap = { onMapOpenChanged(!mapOpen) })
        Spacer(Modifier.height(6.dp))
        presentation.opening?.objective?.let { objective ->
            Text(
                text = "目标 · $objective",
                modifier = Modifier.fillMaxWidth().background(GlassColor, RoundedCornerShape(10.dp)).padding(8.dp),
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 12.sp,
                maxLines = 2,
            )
            Spacer(Modifier.height(6.dp))
        }
        CharacterMemberPanel(
            presentation.characters,
            onChatPrefix = onComposerDraftChanged,
        )
        Spacer(Modifier.height(6.dp))
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(if (compact) 30.dp else 36.dp)
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
                color = Color.White,
                fontSize = if (compact) 16.sp else 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                presentation.scene?.let {
                    val exits = presentation.exploration.knownExitCount
                    "${it.label} · ${exits} 个已知去处"
                } ?: "故事进行中",
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        if (presentation.exploration.situation != null) {
            Button(
                onClick = onMap,
                modifier = Modifier.height(34.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 11.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = PmAccent.copy(alpha = 0.2f), contentColor = PmAccent),
            ) { Text(if (compact) "地图" else "地图与目标", fontSize = 12.sp) }
        }
        Button(
            onClick = onExit,
            modifier = Modifier.height(34.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(backgroundColor = GlassStrong, contentColor = Color.White),
        ) { Text(if (compact) "退出" else "世界与存档", fontSize = 12.sp) }
    }
}

@Composable
private fun SceneMapPanel(
    presentation: GamePresentation,
    modifier: Modifier,
    onClose: () -> Unit,
) {
    val exploration = presentation.exploration
    Column(
        modifier = modifier.background(GlassStrong, RoundedCornerShape(16.dp))
            .border(1.dp, FineBorder, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("场景与地图", color = PmAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(presentation.scene?.label ?: "当前位置", color = MutedText, fontSize = 11.sp)
            }
            Button(
                onClick = onClose,
                modifier = Modifier.height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xD5464B45), contentColor = Color.White),
            ) { Text("关闭", fontSize = 12.sp) }
        }
        exploration.situation?.let { situation ->
            Column(
                Modifier.fillMaxWidth().background(PmAccent.copy(alpha = 0.09f), RoundedCornerShape(10.dp)).padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("当前目标", color = PmAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(situation.objective, color = Color.White, fontSize = 13.sp)
                Text("压力 · ${situation.pressure}", color = Color(0xFFFFBE8A), fontSize = 12.sp)
                Text(situation.question, color = Color.White.copy(alpha = 0.76f), fontSize = 12.sp)
            }
        }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (exploration.nodes.isNotEmpty()) {
                item("map-heading") { Text("已知地点", color = MutedText, fontSize = 11.sp) }
                items(exploration.nodes, key = { it.id.value }) { node ->
                    val accent = explorationLevelColor(node.level)
                    Row(
                        Modifier.fillMaxWidth().background(accent.copy(alpha = 0.12f), RoundedCornerShape(9.dp))
                            .border(1.dp, accent.copy(alpha = 0.42f), RoundedCornerShape(9.dp)).padding(9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.size(10.dp).background(accent, CircleShape))
                        Column(Modifier.weight(1f)) {
                            Text((if (node.current) "当前位置 · " else "") + node.label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(node.description, color = MutedText, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(explorationLevelLabel(node.level), color = accent, fontSize = 10.sp)
                    }
                }
            }
            if (exploration.connections.isNotEmpty()) {
                item("route-heading") { Text("已知路线", color = MutedText, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }
                items(exploration.connections, key = { it.id.value }) { route ->
                    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp)).padding(9.dp)) {
                        Text(route.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        val detail = listOfNotNull(route.directionSummary, route.travelMinutes?.let { "约 $it 分钟" }, route.riskSummary).joinToString(" · ")
                        if (detail.isNotBlank()) Text(detail, color = MutedText, fontSize = 11.sp)
                    }
                }
            }
            if (exploration.affordances.isNotEmpty()) {
                item("affordance-heading") { Text("眼前可互动", color = MutedText, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp)) }
                items(exploration.affordances, key = { it.id.value }) { affordance ->
                    Text("• ${affordance.label} · ${affordance.description}", color = Color.White.copy(alpha = 0.84f), fontSize = 12.sp)
                }
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
    ExplorationKnowledgeLevel.RUMORED -> Color(0xFFAAA4C8)
    ExplorationKnowledgeLevel.BLOCKED -> Color(0xFFE47D6D)
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
) {
    val agentState = controller?.state?.collectAsState()?.value ?: GameAgentState.Idle
    val history = controller?.history?.collectAsState()?.value
        ?: io.worldloom.agent.runtime.GameAgentHistoryState()
    val messages = remember(presentation, history) { buildGameChatMessages(presentation, history) }
    val hasPlayedMessages = history.items.isNotEmpty() || presentation.timeline.any { it.chatMessage != null }
    LaunchedEffect(controller, runKey) { controller?.recoverInterruptedHistory() }
    LaunchedEffect(messages.size, (agentState as? GameAgentState.Running)?.partialText) {
        val extra = if (agentState is GameAgentState.Running) 1 else 0
        if (messages.isNotEmpty() && (hasPlayedMessages || extra > 0)) {
            listState.scrollToItem(messages.lastIndex + extra)
        }
    }

    Column(
        modifier = modifier.background(GlassColor, RoundedCornerShape(16.dp))
            .border(1.dp, FineBorder, RoundedCornerShape(16.dp))
            .padding(9.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(messages, key = GameChatMessage::id) { ChatBubble(it) }
            notice?.let { message ->
                item("notice-${message.code}") {
                    ChatBubble(
                        GameChatMessage("notice", Long.MAX_VALUE, "系统", GameChatSpeakerKind.SYSTEM, message.message),
                    )
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
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        SceneSuggestions(
            presentation = presentation,
            enabled = interactive && controller != null && agentState !is GameAgentState.Running,
            onSuggestionSelected = onComposerDraftChanged,
        )
        Spacer(Modifier.height(6.dp))
        ChatComposer(
            controller = controller,
            characters = presentation.characters,
            historyKey = historyKey,
            enabled = interactive && agentState !is GameAgentState.Running,
            onNpcBusyChanged = onNpcBusyChanged,
            input = composerDraft,
            onInputChanged = onComposerDraftChanged,
            sendOrdinal = sendOrdinal,
            onSendOrdinalConsumed = onSendOrdinalConsumed,
        )
    }
}

@Composable
private fun ChatBubble(message: GameChatMessage, typing: Boolean = false) {
    val player = message.kind == GameChatSpeakerKind.PLAYER
    val bubbleColor = when (message.kind) {
        GameChatSpeakerKind.PM -> Color(0xE2262927)
        GameChatSpeakerKind.PLAYER -> PlayerBubble
        GameChatSpeakerKind.NPC -> Color(0xE21E3031)
        GameChatSpeakerKind.SYSTEM -> Color(0xC52B2A2C)
    }
    val accent = when (message.kind) {
        GameChatSpeakerKind.PM -> PmAccent
        GameChatSpeakerKind.PLAYER -> Color(0xFFFFE4AF)
        GameChatSpeakerKind.NPC -> NpcAccent
        GameChatSpeakerKind.SYSTEM -> Color(0xFFB7AEB3)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (player) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(if (player) 0.76f else 0.86f)
                .background(bubbleColor, RoundedCornerShape(13.dp))
                .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(13.dp))
                .padding(horizontal = 11.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.speaker, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                message.audienceLabel?.let { label ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        label,
                        color = if (message.private) PmAccent else MutedText,
                        fontSize = 10.sp,
                        modifier = Modifier.background(
                            if (message.private) PmAccent.copy(alpha = 0.13f) else Color.White.copy(alpha = 0.07f),
                            RoundedCornerShape(8.dp),
                        ).padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                if (typing) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = accent)
                }
            }
            Text(message.content, color = Color.White.copy(alpha = 0.92f), fontSize = 14.sp, lineHeight = 19.sp)
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
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Button(
                onClick = {
                    strength = when (strength) {
                        GuidanceStrength.NEWCOMER -> GuidanceStrength.STANDARD
                        GuidanceStrength.STANDARD -> GuidanceStrength.IMMERSIVE
                        GuidanceStrength.IMMERSIVE -> GuidanceStrength.NEWCOMER
                    }
                },
                modifier = Modifier.height(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xB92B302E), contentColor = MutedText),
            ) { Text("引导·${strength.label}", fontSize = 10.sp) }
        }
        items(suggestions, key = { "${it.targetKind}:${it.targetId.value}" }) { suggestion ->
            Button(
                onClick = { onSuggestionSelected(suggestion.inputDraft) },
                enabled = enabled,
                modifier = Modifier.height(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xD5464B45), contentColor = Color.White),
            ) { Text(suggestion.label, fontSize = 11.sp) }
        }
        if (hint != null) {
            item("request-hint") {
                Button(
                    onClick = { hintRequested = !hintRequested },
                    modifier = Modifier.height(32.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = PmAccent.copy(alpha = 0.18f), contentColor = PmAccent),
                ) { Text(if (hintRequested) "收起提示" else "需要提示", fontSize = 11.sp) }
            }
            if (hintRequested) {
                item("hint-draft-${hint.targetId.value}") {
                    Button(
                        onClick = { onSuggestionSelected(hint.inputDraft) },
                        enabled = enabled,
                        modifier = Modifier.height(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xD65B4D36), contentColor = Color.White),
                    ) { Text(hint.label, fontSize = 11.sp) }
                }
            }
        }
    }
    if (strength == GuidanceStrength.NEWCOMER) {
        suggestions.firstOrNull()?.let { suggestion ->
            val detail = listOfNotNull(suggestion.rationale, suggestion.tradeoff).joinToString(" · ")
            if (detail.isNotBlank()) Text(detail, color = MutedText, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
    val canInput = enabled && controller != null && !sendingNpc
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { if (it.length <= 2_000) onInputChanged(it) },
            modifier = Modifier.weight(1f),
            label = { Text("行动 / @公开 / #私聊", fontSize = 12.sp) },
            enabled = canInput,
            maxLines = 2,
        )
        Button(
            enabled = canInput && input.isNotBlank(),
            modifier = Modifier.height(40.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 13.dp, vertical = 0.dp),
            onClick = {
                val submitted = input.trim()
                status = null
                when (val parsed = parseChatInput(submitted, characters)) {
                    is ParsedChatInput.Invalid -> status = parsed.message
                    is ParsedChatInput.ToPm -> {
                        onInputChanged("")
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
                                    is NpcDialogueResult.Committed -> if (result.worldChanged) "消息已送达" else "消息已处理"
                                    is NpcDialogueResult.Failed -> result.message
                                    null -> "主持服务不可用"
                                }
                            } finally {
                                sendingNpc = false
                                onNpcBusyChanged(false)
                            }
                        }
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(backgroundColor = PmAccent, contentColor = Color(0xFF20170A)),
        ) { Text(if (sendingNpc) "…" else "发送") }
    }
    if (controller == null) {
        Text("请先在世界与服务页面配置主持模型。", color = MutedText, fontSize = 11.sp)
    } else {
        status?.let { Text(it, color = MutedText, fontSize = 11.sp) }
    }
}

@Composable
private fun WorldHud(
    presentation: GamePresentation,
    interactive: Boolean,
    modifier: Modifier,
    onReplay: () -> Unit,
    onAdjust: (DefinitionId) -> Unit,
    onCheck: (DefinitionId) -> Unit,
    onWait: (Long) -> Unit,
    onChatPrefix: (String) -> Unit,
) {
    LazyColumn(
        modifier = modifier.background(GlassColor, RoundedCornerShape(16.dp))
            .border(1.dp, FineBorder, RoundedCornerShape(16.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("任务档案", color = PmAccent, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            presentation.opening?.let {
                Text(it.objective, color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        presentation.scene?.let { scene ->
            item {
                HudSection("当前场景 · ${scene.label}") {
                    scene.description?.let { Text(it, color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp) }
                }
            }
            if (scene.actions.isNotEmpty()) {
                item {
                    HudSection("行动") {
                        scene.actions.forEach { action ->
                            Button(
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
                            ) {
                                Text(action.label)
                            }
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
                            Text(field.value.toString(), color = Color.White, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Button(onClick = { onAdjust(field.presentationId) }, enabled = interactive) {
                                Text(field.adjustmentStep.signedLabel)
                            }
                        }
                    }
                }
            }
        }
        if (presentation.checks.isNotEmpty()) {
            item {
                HudSection("判定") {
                    presentation.checks.forEach { check ->
                        Button(modifier = Modifier.fillMaxWidth(), onClick = { onCheck(check.presentationId) }, enabled = interactive) {
                            Text(check.label)
                        }
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
                            Button(onClick = { onWait(60) }, enabled = interactive) { Text("等待") }
                        }
                    }
                    presentation.activities.forEach { activity ->
                        Button(
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
                        ) {
                            Text("${activity.label} · ${activity.durationMinutes} 分钟")
                        }
                    }
                    presentation.travelRoutes.forEach { route ->
                        Button(
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
                        ) {
                            Text("${route.label} · ${route.durationMinutes} 分钟")
                        }
                    }
                }
            }
        }
        presentation.adventureState?.let { adventure ->
            item {
                HudSection("世界状态") {
                    adventure.quests.forEach { quest ->
                        Text("${quest.label} · ${quest.stageLabel ?: quest.status.name}", color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp)
                    }
                    adventure.conditions.forEach { condition ->
                        Text("${condition.label} · ${condition.stacks}", color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp)
                    }
                }
            }
        }
        presentation.endingSummary?.let { summary ->
            item { HudSection("结局") { Text(summary, color = Color.White.copy(alpha = 0.86f)) } }
        }
        item {
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onReplay,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xD537403E), contentColor = Color.White),
            ) { Text("回放校验 · #${presentation.lastSequence}") }
        }
    }
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
        modifier = modifier.fillMaxWidth().background(GlassStrong, RoundedCornerShape(12.dp)).padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("群聊成员", color = PmAccent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            CharacterRosterTab(
                label = "身边 ${visibleNearbyCount(characters)}",
                selected = nearbyOnly,
                onClick = {
                    nearbyOnly = true
                    selectedId = null
                },
            )
            Spacer(Modifier.width(4.dp))
            CharacterRosterTab(
                label = "全部 ${characters.size}",
                selected = !nearbyOnly,
                onClick = {
                    nearbyOnly = false
                    selectedId = null
                },
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                item { Text("暂无角色", color = MutedText, fontSize = 11.sp, modifier = Modifier.padding(12.dp)) }
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
            else -> Text("点击头像查看人物介绍", color = MutedText.copy(alpha = 0.78f), fontSize = 9.sp)
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
        modifier = Modifier.clip(RoundedCornerShape(9.dp))
            .background(if (selected) PmAccent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.045f))
            .border(1.dp, if (selected) PmAccent.copy(alpha = 0.5f) else FineBorder, RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) PmAccent else MutedText, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
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
        modifier = Modifier.width(54.dp).clip(RoundedCornerShape(9.dp)).clickable(onClick = onClick)
            .background(if (selected) PmAccent.copy(alpha = 0.09f) else Color.Transparent)
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CharacterAvatar(name, avatarAssetId, reachable, selected)
        Spacer(Modifier.height(2.dp))
        Text(
            name,
            color = if (selected) PmAccent else Color.White,
            fontSize = 10.sp,
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
    val painter = when (avatarAssetId) {
        "worldloom.avatar.war-mara" -> painterResource(Res.drawable.npc_war_mara)
        "worldloom.avatar.war-tomas" -> painterResource(Res.drawable.npc_war_tomas)
        "worldloom.avatar.station-lyra" -> painterResource(Res.drawable.npc_station_lyra)
        "worldloom.avatar.station-soren" -> painterResource(Res.drawable.npc_station_soren)
        else -> null
    }
    Box(Modifier.size(40.dp)) {
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
                    fontSize = 12.sp,
                )
            }
        }
        Box(
            Modifier.align(Alignment.BottomEnd).size(10.dp).clip(CircleShape)
                .background(if (reachable) NpcAccent else Color(0xFF626769))
                .border(2.dp, GlassStrong, CircleShape),
        )
    }
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
        modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.045f), RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Column(Modifier.weight(1f)) {
                Text(name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(status, color = if (canPrivate) NpcAccent else MutedText, fontSize = 9.sp, maxLines = 1)
            }
            if (canPublic) RosterActionButton("@ 对话", onPublic)
            if (canPrivate) RosterActionButton("# 私聊", onPrivate)
        }
        Text(introduction, color = Color.White.copy(alpha = 0.76f), fontSize = 10.sp, lineHeight = 14.sp, maxLines = 3)
    }
}

@Composable
private fun RosterActionButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(28.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (label.startsWith("#")) PmAccent.copy(alpha = 0.82f) else NpcAccent.copy(alpha = 0.75f),
            contentColor = Color(0xFF111718),
        ),
    ) { Text(label, fontSize = 9.sp, fontWeight = FontWeight.Black) }
}

private fun visibleNearbyCount(characters: List<PresentedNpc>): Int = characters.count(PresentedNpc::nearby)

@Composable
private fun HudSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(GlassStrong, RoundedCornerShape(12.dp)).padding(9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, color = PmAccent, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        content()
    }
}

private val Long.signedLabel: String get() = if (this > 0) "+$this" else toString()
