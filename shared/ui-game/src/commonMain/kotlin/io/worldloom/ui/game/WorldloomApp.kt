package io.worldloom.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.worldloom.agent.runtime.GameAgentController
import io.worldloom.agent.runtime.GameAgentState
import io.worldloom.application.GamePresentation
import io.worldloom.application.CharacterCreationPresentation
import io.worldloom.application.request
import io.worldloom.application.GameSession
import io.worldloom.application.GameSessionAction
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.LoadResult
import io.worldloom.application.SessionError
import io.worldloom.application.WorldCatalogEntry
import io.worldloom.content.schema.CharacterCreationMode
import io.worldloom.content.schema.CharacterValueAssignment
import io.worldloom.definition.IntegerValue
import io.worldloom.platform.credentials.CredentialConfiguration
import io.worldloom.platform.credentials.CredentialConfigurationState
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConfigurationCenter
import io.worldloom.provider.api.ProviderConfigurationId
import io.worldloom.provider.api.ProviderConnectionTestResult
import io.worldloom.provider.api.ProviderModelDiscoveryResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val WorldloomColors = darkColors(
    primary = Color(0xFFD6B56E),
    primaryVariant = Color(0xFFA9843A),
    secondary = Color(0xFF8FA79A),
    background = Color(0xFF121513),
    surface = Color(0xFF1C211E),
    error = Color(0xFFD98282),
    onPrimary = Color(0xFF211B0F),
    onSecondary = Color(0xFF111713),
    onBackground = Color(0xFFE7E4DA),
    onSurface = Color(0xFFE7E4DA),
    onError = Color(0xFF241010),
)

@Composable
fun WorldloomApp(
    session: GameSession,
    agentController: GameAgentController? = null,
    credentialConfiguration: CredentialConfiguration? = null,
    providerConfigurationCenter: ProviderConfigurationCenter? = null,
    providerConfigurationId: ProviderConfigurationId? = null,
) {
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()

    MaterialTheme(colors = WorldloomColors) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AppHeader()
                WorldSelector(
                    worlds = session.availableWorlds,
                    onSelected = { world ->
                        scope.launch {
                            if (session.load(world.id) is LoadResult.Success) agentController?.reset()
                        }
                    },
                )
                credentialConfiguration?.let { CredentialPanel(it) }
                if (providerConfigurationCenter != null && providerConfigurationId != null) {
                    ProviderConfigurationPanel(providerConfigurationCenter, providerConfigurationId)
                }
                when (val current = state) {
                    GameSessionUiState.Idle -> EmptyState("选择一个契约世界，开始验证权威运行管线。")
                    is GameSessionUiState.Loading -> LoadingState()
                    is GameSessionUiState.Ready -> ReadyState(
                        presentation = current.presentation,
                        notice = current.notice,
                        onAdjust = { presentationId ->
                            scope.launch {
                                session.perform(GameSessionAction.AdjustPresentedField(presentationId))
                            }
                        },
                        onCheck = { presentationId ->
                            scope.launch {
                                session.perform(GameSessionAction.ResolvePresentedCheck(presentationId))
                            }
                        },
                        onReplay = { scope.launch { session.replay() } },
                        onAction = { actionId ->
                            scope.launch { session.perform(GameSessionAction.PerformAvailableAction(actionId)) }
                        },
                        onWait = { minutes ->
                            scope.launch { session.perform(GameSessionAction.AdvanceWorldTime(minutes)) }
                        },
                        onActivity = { activityId ->
                            scope.launch { session.perform(GameSessionAction.PerformActivity(activityId)) }
                        },
                        onTravel = { routeId ->
                            scope.launch { session.perform(GameSessionAction.Travel(routeId)) }
                        },
                        agentController = agentController,
                    )

                    is GameSessionUiState.CharacterCreation -> CharacterCreationState(
                        presentation = current.presentation,
                        onUpdate = { request -> scope.launch { session.updateCharacter(request) } },
                        onConfirm = { scope.launch { session.confirmCharacter() } },
                    )

                    is GameSessionUiState.Ended -> EmptyState("本次游戏已${if (current.lifecycle.name == "COMPLETED") "完成" else "放弃"}。")

                    is GameSessionUiState.Failed -> EmptyState(current.error.message, isError = true)
                }
            }
        }
    }
}

@Composable
private fun CharacterCreationState(
    presentation: CharacterCreationPresentation,
    onUpdate: (io.worldloom.content.schema.CharacterCreationRequest) -> Unit,
    onConfirm: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("创建角色", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${presentation.profileId} · 确认后进入主持人回合",
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.58f),
            )
        }
        presentation.notice?.let { notice -> item { EmptyState(notice.message, isError = true) } }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(presentation.modes, key = { it.name }) { mode ->
                    Button(
                        onClick = { onUpdate(presentation.request(mode = mode, optionId = null)) },
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = if (mode == presentation.selectedMode) {
                                MaterialTheme.colors.primary
                            } else {
                                MaterialTheme.colors.secondary
                            },
                        ),
                    ) { Text(modeLabel(mode)) }
                }
            }
        }
        if (presentation.options.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(presentation.options, key = { it.id.value }) { option ->
                        Button(
                            onClick = { onUpdate(presentation.request(optionId = option.id)) },
                        ) { Text(option.label) }
                    }
                }
            }
        }
        items(presentation.fields, key = { "${it.componentId}:${it.fieldId}" }) { field ->
            Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(field.fieldId.value, fontWeight = FontWeight.SemiBold)
                        Text(field.value.toString(), color = MaterialTheme.colors.onSurface.copy(alpha = 0.68f))
                    }
                    if (presentation.selectedMode == CharacterCreationMode.POINT_BUY && field.value is IntegerValue) {
                        Button(onClick = { onUpdate(presentation.withInteger(field.fieldId, -1)) }) { Text("−") }
                        Button(onClick = { onUpdate(presentation.withInteger(field.fieldId, 1)) }) { Text("+") }
                    }
                }
            }
        }
        if (presentation.selectedMode == CharacterCreationMode.NARRATIVE) {
            item {
                OutlinedTextField(
                    value = presentation.narrativeBackground,
                    onValueChange = { onUpdate(presentation.request(narrativeBackground = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("角色背景") },
                )
            }
        }
        item {
            val budget = presentation.pointBuyBudget
            if (budget != null) Text("点数：${presentation.pointsSpent} / $budget")
            presentation.problems.firstOrNull()?.let { Text(it.message, color = MaterialTheme.colors.error) }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onConfirm, enabled = presentation.problems.isEmpty()) { Text("确认角色并开始游戏") }
        }
    }
}

private fun CharacterCreationPresentation.withInteger(
    fieldId: io.worldloom.definition.DefinitionId,
    delta: Long,
): io.worldloom.content.schema.CharacterCreationRequest {
    val values = fields.map { field ->
        val current = field.value
        val value = if (field.fieldId == fieldId && current is IntegerValue) {
            IntegerValue((current.value + delta).coerceIn(field.minimumInteger ?: Long.MIN_VALUE, field.maximumInteger ?: Long.MAX_VALUE))
        } else {
            current
        }
        CharacterValueAssignment(field.componentId, field.fieldId, value)
    }
    return request(values = values)
}

private fun modeLabel(mode: CharacterCreationMode): String = when (mode) {
    CharacterCreationMode.FIXED -> "固定角色"
    CharacterCreationMode.TEMPLATE -> "角色模板"
    CharacterCreationMode.POINT_BUY -> "点数分配"
    CharacterCreationMode.NARRATIVE -> "叙事背景"
}

@Composable
private fun ProviderConfigurationPanel(
    center: ProviderConfigurationCenter,
    configurationId: ProviderConfigurationId,
) {
    val scope = rememberCoroutineScope()
    var configuration by remember { mutableStateOf<ProviderConfiguration?>(null) }
    var baseUrl by remember { mutableStateOf("") }
    var modelId by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("正在读取配置…") }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(center, configurationId) {
        configuration = center.configurations().firstOrNull { it.id == configurationId }
        configuration?.let {
            baseUrl = it.baseUrl
            modelId = it.modelId
            status = "当前模型：${it.modelId}"
        } ?: run { status = "Provider 配置不存在" }
    }

    fun saveThen(block: suspend (ProviderConfiguration) -> Unit) {
        val current = configuration ?: return
        loading = true
        scope.launch {
            try {
                val updated = current.copy(baseUrl = baseUrl.trim(), modelId = modelId.trim())
                center.upsert(updated)
                center.select(updated.id)
                configuration = updated
                block(updated)
            } catch (error: IllegalArgumentException) {
                status = error.message ?: "Provider 配置无效"
            } finally {
                loading = false
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("模型 Provider", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Base URL") },
                singleLine = true,
                enabled = !loading,
            )
            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Model ID") },
                singleLine = true,
                enabled = !loading,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !loading && configuration != null,
                    onClick = {
                        saveThen { updated ->
                            status = "已保存 ${updated.modelId}"
                        }
                    },
                ) { Text("保存并切换") }
                Button(
                    enabled = !loading && configuration != null,
                    onClick = {
                        saveThen { updated ->
                            status = when (val result = center.test(updated.id)) {
                                is ProviderConnectionTestResult.Connected -> "连接成功"
                                is ProviderConnectionTestResult.Failed -> result.message
                            }
                        }
                    },
                ) { Text("测试连接") }
                Button(
                    enabled = !loading && configuration != null,
                    onClick = {
                        saveThen { updated ->
                            when (val result = center.discoverModels(updated.id)) {
                                is ProviderModelDiscoveryResult.Success -> {
                                    models = result.models.map { it.id }
                                    status = "发现 ${models.size} 个模型"
                                }
                                is ProviderModelDiscoveryResult.Failure -> status = result.message
                            }
                        }
                    },
                ) { Text("发现模型") }
            }
            Text(status, color = MaterialTheme.colors.onSurface.copy(alpha = 0.68f), fontSize = 12.sp)
            if (models.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(models, key = { it }) { model ->
                        Button(onClick = { modelId = model }, enabled = !loading) { Text(model) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("WORLDLOOM / 织境", color = MaterialTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 22.sp)
        Text("确定性世界引擎 · 初始化竖切", color = MaterialTheme.colors.onBackground.copy(alpha = 0.68f))
    }
}

@Composable
private fun WorldSelector(
    worlds: List<WorldCatalogEntry>,
    onSelected: (WorldCatalogEntry) -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(worlds, key = { it.id.value }) { world ->
            Button(onClick = { onSelected(world) }) {
                Text(world.title)
            }
        }
    }
}

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colors.primary)
    }
}

@Composable
private fun EmptyState(
    message: String,
    isError: Boolean = false,
) {
    Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
        Text(
            text = message,
            modifier = Modifier.padding(20.dp),
            color = if (isError) MaterialTheme.colors.error else MaterialTheme.colors.onSurface,
        )
    }
}

@Composable
private fun ReadyState(
    presentation: GamePresentation,
    notice: SessionError?,
    onAdjust: (io.worldloom.definition.DefinitionId) -> Unit,
    onCheck: (io.worldloom.definition.DefinitionId) -> Unit,
    onReplay: () -> Unit,
    onAction: (io.worldloom.definition.DefinitionId) -> Unit,
    onWait: (Long) -> Unit,
    onActivity: (io.worldloom.definition.DefinitionId) -> Unit,
    onTravel: (io.worldloom.definition.DefinitionId) -> Unit,
    agentController: GameAgentController?,
) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(presentation.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Event sequence ${presentation.lastSequence}",
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.58f),
                )
            }
            Button(
                onClick = onReplay,
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary),
            ) {
                Text("回放校验")
            }
        }

        notice?.let { EmptyState(it.message, isError = true) }

        agentController?.let { AgentPanel(it) }

        presentation.scene?.let { scene ->
            Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("当前场景 · ${scene.label}", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
                    if (scene.participantIds.isNotEmpty()) {
                        Text("参与者：${scene.participantIds.joinToString { it.value }}")
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(scene.actions, key = { it.id.value }) { action ->
                            Button(onClick = { onAction(action.id) }) { Text(action.label) }
                        }
                    }
                }
            }
        }

        if (presentation.worldTimeMinutes != null || presentation.activities.isNotEmpty() || presentation.travelRoutes.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presentation.worldTimeMinutes?.let { Text("世界时间 · 第 $it 分钟", fontWeight = FontWeight.SemiBold) }
                        Spacer(Modifier.weight(1f))
                        if (presentation.worldTimeMinutes != null) Button(onClick = { onWait(60) }) { Text("等待 1 小时") }
                    }
                    if (presentation.activities.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(presentation.activities, key = { it.id.value }) { activity ->
                                Button(onClick = { onActivity(activity.id) }) {
                                    Text("${activity.label} · ${activity.durationMinutes} 分钟")
                                }
                            }
                        }
                    }
                    if (presentation.travelRoutes.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(presentation.travelRoutes, key = { it.id.value }) { route ->
                                Button(onClick = { onTravel(route.id) }) {
                                    Text("${route.label} · ${route.durationMinutes} 分钟")
                                }
                            }
                        }
                    }
                }
            }
        }

        presentation.adventureState?.let { adventure ->
            Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("冒险状态", fontWeight = FontWeight.SemiBold)
                    if (adventure.inventory.isNotEmpty()) Text(
                        "库存 · ${adventure.inventory.joinToString { "${it.label} × ${it.quantity}" }}",
                    )
                    if (adventure.conditions.isNotEmpty()) Text(
                        "状态 · ${adventure.conditions.joinToString { "${it.label} ${it.stacks} 层" }}",
                    )
                    if (adventure.relationships.isNotEmpty()) Text(
                        "关系 · ${adventure.relationships.joinToString { "${it.label} ${it.value}" }}",
                    )
                    adventure.quests.forEach { quest ->
                        Text("任务 · ${quest.label} · ${quest.stageLabel ?: "尚未开始"} · ${quest.status.name}")
                    }
                    adventure.clocks.forEach { clock ->
                        Text("${clock.label} · ${clock.value}/${clock.segments}")
                        LinearProgressIndicator(
                            progress = if (clock.segments == 0L) 0f else clock.value.toFloat() / clock.segments.toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(presentation.fields, key = { it.presentationId.value }) { field ->
                Card(backgroundColor = MaterialTheme.colors.surface) {
                    Column(
                        modifier = Modifier.width(220.dp).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(field.label, color = MaterialTheme.colors.onSurface.copy(alpha = 0.72f))
                        Text(field.value.toString(), fontSize = 32.sp, fontWeight = FontWeight.Bold)
                        Button(onClick = { onAdjust(field.presentationId) }) {
                            Text("推进 ${signed(field.adjustmentStep)}")
                        }
                    }
                }
            }
        }

        if (presentation.checks.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(presentation.checks, key = { it.presentationId.value }) { check ->
                    Button(onClick = { onCheck(check.presentationId) }) {
                        Text(check.label)
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text("事件时间线", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
        if (presentation.timeline.isEmpty()) {
            Text("尚无事件。所有事实变化都会在这里留下可回放记录。", color = MaterialTheme.colors.onSurface.copy(alpha = 0.58f))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(presentation.timeline, key = { it.sequence }) { event ->
                    Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
                        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("#${event.sequence}", color = MaterialTheme.colors.primary, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(16.dp))
                            Text(event.summary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialPanel(configuration: CredentialConfiguration) {
    val state by configuration.state.collectAsState()
    val scope = rememberCoroutineScope()
    var credential by remember { mutableStateOf("") }

    LaunchedEffect(configuration) {
        configuration.refresh()
    }

    Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("模型凭据", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(
                    credentialStatus(state),
                    color = if (state is CredentialConfigurationState.Failed) {
                        MaterialTheme.colors.error
                    } else {
                        MaterialTheme.colors.onSurface.copy(alpha = 0.62f)
                    },
                    fontSize = 12.sp,
                )
            }
            OutlinedTextField(
                value = credential,
                onValueChange = { credential = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("OpenAI API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                enabled = state !is CredentialConfigurationState.Loading,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = {
                        val submitted = credential
                        credential = ""
                        scope.launch { configuration.configure(submitted) }
                    },
                    enabled = credential.isNotBlank() && state !is CredentialConfigurationState.Loading,
                ) {
                    Text("保存")
                }
                if (state is CredentialConfigurationState.Configured) {
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { scope.launch { configuration.clear() } },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary),
                    ) {
                        Text("删除")
                    }
                }
            }
        }
    }
}

@Composable
private fun AgentPanel(controller: GameAgentController) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var runningJob by remember { mutableStateOf<Job?>(null) }
    val running = state is GameAgentState.Running

    Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("自然语言行动", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("描述你要做的事") },
                    enabled = !running,
                    singleLine = true,
                )
                if (running) {
                    Button(
                        onClick = { runningJob?.cancel() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary),
                    ) {
                        Text("停止")
                    }
                } else {
                    Button(
                        onClick = {
                            val submitted = input
                            input = ""
                            runningJob = scope.launch { controller.send(submitted) }
                        },
                        enabled = input.isNotBlank(),
                    ) {
                        Text("发送")
                    }
                }
            }
            when (val current = state) {
                GameAgentState.Idle -> Text(
                    "Agent 的写操作同样必须经过 Tool → Command → Event。",
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.58f),
                )

                is GameAgentState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp).height(18.dp),
                        color = MaterialTheme.colors.primary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(current.partialText.ifBlank { "正在思考…" })
                }

                is GameAgentState.Completed -> Text(current.text)
                is GameAgentState.AwaitingPlayer -> Text(
                    current.question,
                    color = MaterialTheme.colors.primary,
                )
                is GameAgentState.Failed -> Text(
                    if (current.worldChanged) "${current.message}（部分工具操作已写入事件）" else current.message,
                    color = MaterialTheme.colors.error,
                )
            }
        }
    }
}

private fun credentialStatus(state: CredentialConfigurationState): String = when (state) {
    CredentialConfigurationState.Unknown -> "尚未检查"
    CredentialConfigurationState.Loading -> "正在更新…"
    CredentialConfigurationState.Configured -> "已安全配置"
    CredentialConfigurationState.NotConfigured -> "未配置"
    is CredentialConfigurationState.Failed -> state.message
}

private fun signed(value: Long): String = if (value > 0) "+$value" else value.toString()
