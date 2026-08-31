package io.worldloom.ui.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

private val GlassColor = Color(0xD6171B1D)
private val GlassStrong = Color(0xEE111518)
private val FineBorder = Color(0x38E8D7AE)
private val PmAccent = Color(0xFFE2BD72)
private val NpcAccent = Color(0xFF80B8B3)
private val PlayerBubble = Color(0xDD6E5329)
private val MutedText = Color(0xFFB9B7B0)

@Composable
internal fun GameplayPage(
    presentation: GamePresentation,
    notice: SessionError?,
    agentController: GameAgentController?,
    interactive: Boolean,
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
    Row(
        modifier = Modifier.fillMaxSize().padding(22.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
            GameTopBar(presentation, onExit)
            Spacer(Modifier.height(12.dp))
            GameConversation(
                presentation = presentation,
                notice = notice,
                controller = controller,
                interactive = interactive,
                historyKey = historyKey,
                modifier = Modifier.weight(1f),
                onAction = onAction,
                onNpcBusyChanged = onNpcBusyChanged,
            )
        }
        WorldHud(
            presentation = presentation,
            interactive = interactive,
            modifier = Modifier.width(330.dp).fillMaxHeight(),
            onReplay = onReplay,
            onAction = onAction,
            onAdjust = onAdjust,
            onCheck = onCheck,
            onWait = onWait,
            onActivity = onActivity,
            onTravel = onTravel,
        )
    }
}

@Composable
private fun PortraitGameplay(
    presentation: GamePresentation,
    notice: SessionError?,
    controller: GameAgentController?,
    interactive: Boolean,
    historyKey: String,
    onExit: () -> Unit,
    onAction: (DefinitionId) -> Unit,
    onNpcBusyChanged: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        GameTopBar(presentation, onExit, compact = true)
        Spacer(Modifier.height(8.dp))
        presentation.opening?.objective?.let { objective ->
            Text(
                text = "目标 · $objective",
                modifier = Modifier.fillMaxWidth().background(GlassColor, RoundedCornerShape(12.dp)).padding(10.dp),
                color = Color.White.copy(alpha = 0.82f),
                fontSize = 12.sp,
                maxLines = 2,
            )
            Spacer(Modifier.height(8.dp))
        }
        GameConversation(
            presentation = presentation,
            notice = notice,
            controller = controller,
            interactive = interactive,
            historyKey = historyKey,
            modifier = Modifier.weight(1f),
            onAction = onAction,
            onNpcBusyChanged = onNpcBusyChanged,
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(if (compact) 34.dp else 42.dp)
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
                fontSize = if (compact) 17.sp else 23.sp,
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
            colors = ButtonDefaults.buttonColors(backgroundColor = GlassStrong, contentColor = Color.White),
        ) { Text(if (compact) "退出" else "世界与存档") }
    }
}

@Composable
private fun GameConversation(
    presentation: GamePresentation,
    notice: SessionError?,
    controller: GameAgentController?,
    interactive: Boolean,
    historyKey: String,
    modifier: Modifier,
    onAction: (DefinitionId) -> Unit,
    onNpcBusyChanged: (Boolean) -> Unit,
) {
    val agentState = controller?.state?.collectAsState()?.value ?: GameAgentState.Idle
    val history = controller?.history?.collectAsState()?.value
        ?: io.worldloom.agent.runtime.GameAgentHistoryState()
    val messages = remember(presentation, history) { buildGameChatMessages(presentation, history) }
    val hasPlayedMessages = history.items.isNotEmpty() || presentation.timeline.any { it.chatMessage != null }
    val listState = rememberLazyListState()
    LaunchedEffect(controller, historyKey) { controller?.refreshHistory() }
    LaunchedEffect(messages.size, (agentState as? GameAgentState.Running)?.partialText) {
        val extra = if (agentState is GameAgentState.Running) 1 else 0
        if (messages.isNotEmpty() && (hasPlayedMessages || extra > 0)) {
            listState.scrollToItem(messages.lastIndex + extra)
        }
    }

    Column(
        modifier = modifier.background(GlassColor, RoundedCornerShape(20.dp))
            .border(1.dp, FineBorder, RoundedCornerShape(20.dp))
            .padding(12.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
        Spacer(Modifier.height(8.dp))
        SceneSuggestions(presentation, interactive && agentState !is GameAgentState.Running, onAction)
        Spacer(Modifier.height(8.dp))
        ChatComposer(
            controller = controller,
            npcs = presentation.scene?.addressableNpcs.orEmpty(),
            historyKey = historyKey,
            enabled = interactive && agentState !is GameAgentState.Running,
            onNpcBusyChanged = onNpcBusyChanged,
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
            modifier = Modifier.fillMaxWidth(if (player) 0.78f else 0.88f)
                .background(bubbleColor, RoundedCornerShape(16.dp))
                .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(message.speaker, color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                if (typing) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = accent)
                }
            }
            Text(message.content, color = Color.White.copy(alpha = 0.92f), lineHeight = 21.sp)
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
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("可尝试", color = MutedText, fontSize = 12.sp, modifier = Modifier.padding(top = 11.dp)) }
        items(actions, key = { it.id.value }) { action ->
            Button(
                onClick = { onAction(action.id) },
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xD5464B45), contentColor = Color.White),
            ) { Text(action.label, fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ChatComposer(
    controller: GameAgentController?,
    npcs: List<PresentedNpc>,
    historyKey: String,
    enabled: Boolean,
    onNpcBusyChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var targetNpcId by remember(npcs) { mutableStateOf<DefinitionId?>(null) }
    var sendingNpc by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var sendOrdinal by remember(historyKey) { mutableIntStateOf(0) }
    val selectedNpc = npcs.firstOrNull { it.id == targetNpcId }
    val canInput = enabled && controller != null && !sendingNpc

    if (npcs.isNotEmpty()) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                TargetButton("PM", targetNpcId == null, canInput) { targetNpcId = null }
            }
            items(npcs, key = { it.id.value }) { npc ->
                TargetButton(npc.displayName, targetNpcId == npc.id, canInput) { targetNpcId = npc.id }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = input,
            onValueChange = { if (it.length <= 2_000) input = it },
            modifier = Modifier.weight(1f),
            label = { Text(selectedNpc?.let { "对 ${it.displayName} 说" } ?: "描述你的行动") },
            enabled = canInput,
            maxLines = 3,
        )
        Button(
            enabled = canInput && input.isNotBlank(),
            onClick = {
                val submitted = input.trim()
                input = ""
                status = null
                if (selectedNpc == null) {
                    scope.launch { controller?.send(submitted) }
                } else {
                    sendingNpc = true
                    onNpcBusyChanged(true)
                    val idempotencyKey = "ui:$historyKey:${selectedNpc.id.value}:${sendOrdinal++}"
                    scope.launch {
                        try {
                            status = when (val result = controller?.addressNpc(selectedNpc.id, submitted, idempotencyKey)) {
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
private fun TargetButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            backgroundColor = if (selected) PmAccent else Color(0xD5343B3B),
            contentColor = if (selected) Color(0xFF21180A) else Color.White,
        ),
    ) { Text(label, fontSize = 11.sp) }
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
) {
    LazyColumn(
        modifier = modifier.background(GlassColor, RoundedCornerShape(20.dp))
            .border(1.dp, FineBorder, RoundedCornerShape(20.dp))
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("任务档案", color = PmAccent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            presentation.opening?.let {
                Text(it.objective, color = Color.White.copy(alpha = 0.82f), fontSize = 13.sp)
            }
        }
        presentation.scene?.let { scene ->
            item {
                HudSection("当前场景 · ${scene.label}") {
                    scene.description?.let { Text(it, color = Color.White.copy(alpha = 0.78f), fontSize = 12.sp) }
                    if (scene.addressableNpcs.isNotEmpty()) {
                        Text("在场角色 · ${scene.addressableNpcs.joinToString { it.displayName }}", color = NpcAccent, fontSize = 12.sp)
                    }
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
private fun HudSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().background(GlassStrong, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = PmAccent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        content()
    }
}

private val Long.signedLabel: String get() = if (this > 0) "+$this" else toString()
