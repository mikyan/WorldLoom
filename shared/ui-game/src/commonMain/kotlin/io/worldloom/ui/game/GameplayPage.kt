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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import io.worldloom.application.PresentedNpc
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

private data class ComposerPrefill(val text: String, val token: Int)

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
    onAction: (DefinitionId) -> Unit,
    onAdjust: (DefinitionId) -> Unit,
    onCheck: (DefinitionId) -> Unit,
    onWait: (Long) -> Unit,
    onActivity: (DefinitionId) -> Unit,
    onTravel: (DefinitionId) -> Unit,
    onNpcBusyChanged: (Boolean) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val portrait = maxHeight > maxWidth
        GameBackdrop(presentation.scene?.backgroundAssetId ?: presentation.opening?.backgroundAssetId)
        if (portrait) {
            PortraitGameplay(
                presentation,
                notice,
                agentController,
                interactive,
                runKey,
                historyKey,
                onExit,
                onAction,
                onNpcBusyChanged,
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
                onAction,
                onAdjust,
                onCheck,
                onWait,
                onActivity,
                onTravel,
                onNpcBusyChanged,
            )
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
    onAction: (DefinitionId) -> Unit,
    onAdjust: (DefinitionId) -> Unit,
    onCheck: (DefinitionId) -> Unit,
    onWait: (Long) -> Unit,
    onActivity: (DefinitionId) -> Unit,
    onTravel: (DefinitionId) -> Unit,
    onNpcBusyChanged: (Boolean) -> Unit,
) {
    var composerPrefill by remember { mutableStateOf<ComposerPrefill?>(null) }
    var prefillToken by remember { mutableIntStateOf(0) }
    Row(
        modifier = Modifier.fillMaxSize().padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            GameTopBar(presentation, onExit)
            Spacer(Modifier.height(8.dp))
            GameConversation(
                presentation = presentation,
                notice = notice,
                controller = controller,
                interactive = interactive,
                runKey = runKey,
                historyKey = historyKey,
                modifier = Modifier.weight(1f),
                onAction = onAction,
                onNpcBusyChanged = onNpcBusyChanged,
                composerPrefill = composerPrefill,
                onComposerPrefillConsumed = { composerPrefill = null },
            )
        }
        WorldHud(
            presentation = presentation,
            interactive = interactive,
            modifier = Modifier.width(292.dp).fillMaxHeight(),
            onReplay = onReplay,
            onAction = onAction,
            onAdjust = onAdjust,
            onCheck = onCheck,
            onWait = onWait,
            onActivity = onActivity,
            onTravel = onTravel,
            onChatPrefix = { prefix -> composerPrefill = ComposerPrefill(prefix, prefillToken++) },
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
    onAction: (DefinitionId) -> Unit,
    onNpcBusyChanged: (Boolean) -> Unit,
) {
    var composerPrefill by remember { mutableStateOf<ComposerPrefill?>(null) }
    var prefillToken by remember { mutableIntStateOf(0) }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 9.dp, vertical = 8.dp)) {
        GameTopBar(presentation, onExit, compact = true)
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
            onChatPrefix = { prefix -> composerPrefill = ComposerPrefill(prefix, prefillToken++) },
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
            onAction = onAction,
            onNpcBusyChanged = onNpcBusyChanged,
            composerPrefill = composerPrefill,
            onComposerPrefillConsumed = { composerPrefill = null },
        )
    }
}

@Composable
private fun GameTopBar(
    presentation: GamePresentation,
    onExit: () -> Unit,
    compact: Boolean = false,
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
                presentation.scene?.let { "${it.label} · 群聊进行中" } ?: "故事进行中",
                color = Color.White.copy(alpha = 0.62f),
                fontSize = 11.sp,
                maxLines = 1,
            )
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
private fun GameConversation(
    presentation: GamePresentation,
    notice: SessionError?,
    controller: GameAgentController?,
    interactive: Boolean,
    runKey: String,
    historyKey: String,
    modifier: Modifier,
    onAction: (DefinitionId) -> Unit,
    onNpcBusyChanged: (Boolean) -> Unit,
    composerPrefill: ComposerPrefill?,
    onComposerPrefillConsumed: () -> Unit,
) {
    val agentState = controller?.state?.collectAsState()?.value ?: GameAgentState.Idle
    val history = controller?.history?.collectAsState()?.value
        ?: io.worldloom.agent.runtime.GameAgentHistoryState()
    val messages = remember(presentation, history) { buildGameChatMessages(presentation, history) }
    val hasPlayedMessages = history.items.isNotEmpty() || presentation.timeline.any { it.chatMessage != null }
    val listState = rememberLazyListState()
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
        SceneSuggestions(presentation, interactive && agentState !is GameAgentState.Running, onAction)
        Spacer(Modifier.height(6.dp))
        ChatComposer(
            controller = controller,
            characters = presentation.characters,
            historyKey = historyKey,
            enabled = interactive && agentState !is GameAgentState.Running,
            onNpcBusyChanged = onNpcBusyChanged,
            prefill = composerPrefill,
            onPrefillConsumed = onComposerPrefillConsumed,
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
    onAction: (DefinitionId) -> Unit,
) {
    val actions = presentation.scene?.actions.orEmpty()
    if (actions.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("可尝试", color = MutedText, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
        items(actions, key = { it.id.value }) { action ->
            Button(
                onClick = { onAction(action.id) },
                enabled = enabled,
                modifier = Modifier.height(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xD5464B45), contentColor = Color.White),
            ) { Text(action.label, fontSize = 11.sp) }
        }
    }
}

@Composable
private fun ChatComposer(
    controller: GameAgentController?,
    characters: List<PresentedNpc>,
    historyKey: String,
    enabled: Boolean,
    onNpcBusyChanged: (Boolean) -> Unit,
    prefill: ComposerPrefill?,
    onPrefillConsumed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var sendingNpc by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var sendOrdinal by remember(historyKey) { mutableIntStateOf(0) }
    val canInput = enabled && controller != null && !sendingNpc
    LaunchedEffect(prefill?.token) {
        prefill?.let {
            input = it.text
            status = null
            onPrefillConsumed()
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { if (it.length <= 2_000) input = it },
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
                        input = ""
                        scope.launch { controller?.send(parsed.content) }
                    }
                    is ParsedChatInput.ToNpc -> {
                        input = ""
                        sendingNpc = true
                        onNpcBusyChanged(true)
                        val idempotencyKey = "ui:$historyKey:${parsed.npc.id.value}:${sendOrdinal++}"
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
    onAction: (DefinitionId) -> Unit,
    onAdjust: (DefinitionId) -> Unit,
    onCheck: (DefinitionId) -> Unit,
    onWait: (Long) -> Unit,
    onActivity: (DefinitionId) -> Unit,
    onTravel: (DefinitionId) -> Unit,
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
                            Button(modifier = Modifier.fillMaxWidth(), onClick = { onAction(action.id) }, enabled = interactive) {
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
                        Button(modifier = Modifier.fillMaxWidth(), onClick = { onActivity(activity.id) }, enabled = interactive) {
                            Text("${activity.label} · ${activity.durationMinutes} 分钟")
                        }
                    }
                    presentation.travelRoutes.forEach { route ->
                        Button(modifier = Modifier.fillMaxWidth(), onClick = { onTravel(route.id) }, enabled = interactive) {
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
