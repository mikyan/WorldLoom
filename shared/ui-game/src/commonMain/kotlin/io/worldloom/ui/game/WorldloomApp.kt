package io.worldloom.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.worldloom.agent.runtime.GameAgentController
import io.worldloom.agent.runtime.GameAgentState
import io.worldloom.agent.runtime.GameTurnRecoveryKind
import io.worldloom.agent.runtime.GameTurnStatus
import io.worldloom.agent.runtime.HostedTurnHistoryItem
import io.worldloom.agent.runtime.NpcDialogueResult
import io.worldloom.application.GamePresentation
import io.worldloom.application.GuidancePresentation
import io.worldloom.application.GameSession
import io.worldloom.application.GameSessionAction
import io.worldloom.application.GameSessionUiState
import io.worldloom.application.LoadResult
import io.worldloom.application.SessionError
import io.worldloom.application.SaveCoordinator
import io.worldloom.application.SaveLibraryState
import io.worldloom.application.RunSaveStatus
import io.worldloom.application.ReplayInspector
import io.worldloom.application.TimelinePageResult
import io.worldloom.application.PublicReplayResult
import io.worldloom.application.WorldCatalogEntry
import io.worldloom.content.generation.RecognitionCandidatePresentation
import io.worldloom.content.generation.RecognitionWorkspacePresentation
import io.worldloom.rules.AdventureStatePresentation
import io.worldloom.platform.credentials.CredentialConfiguration
import io.worldloom.provider.api.ProviderConfigurationCenter
import io.worldloom.provider.api.ProviderConfigurationId
import io.worldloom.provider.openai.OpenAiSubscriptionSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WorldloomApp(
    session: GameSession,
    saveCoordinator: SaveCoordinator? = null,
    reduceMotion: Boolean = false,
    agentController: GameAgentController? = null,
    providerConfigurationCenter: ProviderConfigurationCenter? = null,
    providerSources: List<OpenAiSubscriptionSource> = emptyList(),
    providerCredentialConfigurations: Map<ProviderConfigurationId, CredentialConfiguration> = emptyMap(),
    recognitionWorkspace: RecognitionWorkspacePresentation? = null,
    onCancelRecognition: () -> Unit = {},
    onResumeRecognition: () -> Unit = {},
    onSelectRecognitionCandidate: (RecognitionCandidatePresentation) -> Unit = {},
) {
    val state by session.state.collectAsState()
    val agentState = agentController?.state?.collectAsState()
    val scope = rememberCoroutineScope()
    var showGameplay by remember { mutableStateOf(false) }
    var homePane by remember { mutableStateOf(HomePane.MENU) }
    var selectedDreamIndex by remember { mutableStateOf(0) }
    var showProviderSettings by remember { mutableStateOf(false) }
    var tokenGateActive by remember { mutableStateOf(false) }
    var tokenAccepted by remember { mutableStateOf(false) }
    var pendingEntry by remember { mutableStateOf<PendingDreamEntry?>(null) }
    var enteringDream by remember { mutableStateOf(false) }
    var homeError by remember { mutableStateOf<String?>(null) }
    var npcDialogueBusy by remember(agentController) { mutableStateOf(false) }
    val gameplayPresentation = when (val current = state) {
        is GameSessionUiState.Ready -> current.presentation
        is GameSessionUiState.Ended -> current.presentation
        else -> null
    }
    val gameplayNotice = when (val current = state) {
        is GameSessionUiState.Ready -> current.notice
        is GameSessionUiState.Ended -> current.notice
        else -> null
    }
    val gameplayInteractive = state is GameSessionUiState.Ready
    LaunchedEffect(saveCoordinator, state) { saveCoordinator?.refresh() }

    suspend fun providerIsReady(): Boolean {
        val center = providerConfigurationCenter ?: return true
        val selectedId = center.selectedConfigurationId()
        val credentialConfiguration = selectedId?.let(providerCredentialConfigurations::get)
        credentialConfiguration?.refresh()
        return providerEntryReady(selectedId, credentialConfiguration?.state?.value)
    }

    suspend fun enterPendingDream(entry: PendingDreamEntry) {
        enteringDream = true
        homeError = null
        if (!reduceMotion) delay(220)
        val failureMessage = when (entry) {
            is PendingDreamEntry.NewDream -> if (saveCoordinator == null) {
                when (val result = session.load(entry.world.id)) {
                    LoadResult.Success -> null
                    is LoadResult.Failure -> "梦境无法展开：${result.error.homeMessage()}"
                }
            } else {
                when (val result = saveCoordinator.create(entry.world.id)) {
                    is io.worldloom.application.SaveOperationResult.Success -> null
                    is io.worldloom.application.SaveOperationResult.Failure -> result.error.message
                }
            }

            PendingDreamEntry.QuickContinue -> when (val result = saveCoordinator?.quickContinue()) {
                is io.worldloom.application.SaveOperationResult.Success -> null
                is io.worldloom.application.SaveOperationResult.Failure -> result.error.message
                null -> "当前平台没有可用的存档目录。"
            }

            is PendingDreamEntry.Continue -> when (val result = saveCoordinator?.continueRun(entry.runId)) {
                is io.worldloom.application.SaveOperationResult.Success -> null
                is io.worldloom.application.SaveOperationResult.Failure -> result.error.message
                null -> "当前平台没有可用的存档目录。"
            }
        }
        if (failureMessage == null) {
            agentController?.reset()
            if (!reduceMotion) delay(520)
            showGameplay = true
            homePane = HomePane.MENU
        } else {
            homeError = failureMessage
        }
        enteringDream = false
    }

    fun requestDreamEntry(entry: PendingDreamEntry) {
        if (enteringDream) return
        scope.launch {
            if (providerIsReady()) {
                enterPendingDream(entry)
            } else {
                pendingEntry = entry
                tokenAccepted = false
                tokenGateActive = true
                showProviderSettings = true
            }
        }
    }

    fun closeProviderSettings() {
        showProviderSettings = false
        if (tokenGateActive) {
            tokenGateActive = false
            tokenAccepted = false
            pendingEntry = null
        }
    }

    fun handleProviderSaved() {
        if (!tokenGateActive) return
        scope.launch {
            if (!providerIsReady()) return@launch
            val entry = pendingEntry ?: return@launch
            showProviderSettings = false
            tokenGateActive = false
            tokenAccepted = true
            if (!reduceMotion) delay(720)
            pendingEntry = null
            tokenAccepted = false
            enterPendingDream(entry)
        }
    }

    WorldloomTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colors.background) {
            if (showGameplay) {
                when (val current = state) {
                    is GameSessionUiState.Ready,
                    is GameSessionUiState.Ended,
                    -> if (gameplayPresentation != null) {
                        GameplayPage(
                            presentation = gameplayPresentation,
                            notice = gameplayNotice,
                            agentController = agentController.takeIf { gameplayInteractive },
                            interactive = gameplayInteractive,
                            reduceMotion = reduceMotion,
                            runKey = session.currentRunId?.value.orEmpty(),
                            historyKey = "${session.currentRunId?.value}:${gameplayPresentation.lastSequence}",
                            onExit = {
                                if (agentState?.value !is GameAgentState.Running && !npcDialogueBusy) {
                                    showGameplay = false
                                    homePane = HomePane.MENU
                                }
                            },
                            onReplay = { scope.launch { session.replay() } },
                            onCheck = { presentationId ->
                                scope.launch { session.perform(GameSessionAction.ResolvePresentedCheck(presentationId)) }
                            },
                            onWait = { minutes ->
                                scope.launch { session.perform(GameSessionAction.AdvanceWorldTime(minutes)) }
                            },
                            onNpcBusyChanged = { npcDialogueBusy = it },
                        )
                    }

                    is GameSessionUiState.CharacterCreation -> CharacterCreationPage(
                        presentation = current.presentation,
                        onUpdate = { request -> scope.launch { session.updateCharacter(request) } },
                        onConfirm = { scope.launch { session.confirmCharacter() } },
                    )

                    is GameSessionUiState.Loading -> LoadingState(reduceMotion)
                    is GameSessionUiState.Failed -> Box(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
                    ) {
                        EmptyState(current.error.message, isError = true)
                    }

                    GameSessionUiState.Idle -> Box(
                        Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp),
                    ) {
                        EmptyState("梦境尚未展开，请返回首页重新选择。")
                    }
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val compact = maxWidth < 720.dp
                    val speech = when {
                        tokenAccepted -> "我收下了。"
                        tokenGateActive -> "献上你的 token 吧。"
                        homePane == HomePane.MENU -> "旅人，准备好进入梦境了吗？"
                        homePane == HomePane.DREAMS -> "挑选一个梦茧，我会替你剪开入口。"
                        else -> "旧梦没有消失，它们只是睡着了。"
                    }
                    HomeExperiencePage(
                        worlds = session.availableWorlds,
                        pane = homePane,
                        selectedDreamIndex = selectedDreamIndex,
                        speechText = speech,
                        reduceMotion = reduceMotion,
                        transitioning = enteringDream,
                        errorMessage = homeError,
                        onDismissError = { homeError = null },
                        onPaneChanged = { pane ->
                            homeError = null
                            homePane = pane
                        },
                        onDreamIndexChanged = { selectedDreamIndex = it },
                        onEnterDream = { requestDreamEntry(PendingDreamEntry.NewDream(it)) },
                        onOpenSettings = {
                            if (providerConfigurationCenter == null || providerSources.isEmpty()) {
                                homeError = "当前平台没有可用的订阅源配置。"
                            } else {
                                tokenGateActive = false
                                showProviderSettings = true
                            }
                        },
                        saveContent = {
                            saveCoordinator?.let { coordinator ->
                                SaveLibraryPanel(
                                    coordinator = coordinator,
                                    onQuickContinueRequested = {
                                        requestDreamEntry(PendingDreamEntry.QuickContinue)
                                    },
                                    onContinueRequested = { runId ->
                                        requestDreamEntry(PendingDreamEntry.Continue(runId))
                                    },
                                )
                            } ?: Text("当前平台没有可用的存档目录。")
                        },
                    )

                    if (showProviderSettings && providerConfigurationCenter != null) {
                        ProviderSettingsOverlay(
                            compact = compact,
                            onDismiss = ::closeProviderSettings,
                        ) {
                            ProviderSettingsPage(
                                center = providerConfigurationCenter,
                                sources = providerSources,
                                credentialConfigurations = providerCredentialConfigurations,
                                onBack = ::closeProviderSettings,
                                onConfigurationSaved = ::handleProviderSaved,
                            )
                            recognitionWorkspace?.let { workspace ->
                                RecognitionWorkspacePanel(
                                    workspace = workspace,
                                    onCancel = onCancelRecognition,
                                    onResume = onResumeRecognition,
                                    onSelectCandidate = onSelectRecognitionCandidate,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveLibraryPanel(
    coordinator: SaveCoordinator,
    onQuickContinueRequested: () -> Unit,
    onContinueRequested: (io.worldloom.world.RunId) -> Unit,
) {
    val state by coordinator.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(coordinator) { coordinator.refresh() }
    when (val library = state) {
        SaveLibraryState.Loading -> WorldloomStatusBanner("正在读取存档…", WorldloomStatusTone.INFO)
        is SaveLibraryState.Failed -> WorldloomStatusBanner(library.message, WorldloomStatusTone.ERROR)
        is SaveLibraryState.Ready -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Md),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WorldloomSectionHeading(
                        title = "存档",
                        subtitle = "继续、整理或修复本地梦境记录。",
                        modifier = Modifier.weight(1f),
                    )
                    library.quickContinueRunId?.let {
                        WorldloomPrimaryButton("快速继续", onQuickContinueRequested)
                    }
                }
                library.operationError?.let {
                    WorldloomStatusBanner(it.message, WorldloomStatusTone.ERROR)
                }
                if (library.runs.isEmpty()) {
                    WorldloomStatusBanner("还没有可以继续的梦境。", WorldloomStatusTone.INFO)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
                    ) {
                        items(library.runs, key = { it.runId.value }) { run ->
                            var name by remember(run.runId) { mutableStateOf(run.displayName) }
                            WorldloomPanel(modifier = Modifier.fillMaxWidth(), strong = !run.archived) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Md),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    OutlinedTextField(
                                        value = name,
                                        onValueChange = { name = it },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        label = { Text("存档名称") },
                                    )
                                    Text(
                                        if (run.archived) "已归档" else runLifecycleLabel(run.lifecycle),
                                        color = if (run.archived) {
                                            WorldloomPalette.TextMuted
                                        } else {
                                            WorldloomPalette.BrandPrimary
                                        },
                                        style = MaterialTheme.typography.subtitle1,
                                    )
                                }
                                Text(
                                    "${run.lastSequence} 个事件 · 内容版本 ${run.worldContentVersion}",
                                    color = WorldloomPalette.TextSecondary,
                                    style = MaterialTheme.typography.body2,
                                )
                                when (run.saveStatus) {
                                    RunSaveStatus.SAVED -> WorldloomStatusBanner(
                                        message = "自动存档已保存至事件 ${run.lastPersistedEventSequence}。",
                                        tone = WorldloomStatusTone.SUCCESS,
                                    )
                                    RunSaveStatus.FACTS_SAVED_DIRECTORY_PENDING -> WorldloomStatusBanner(
                                        message = "世界事实已保存，但存档目录需要修复。",
                                        tone = WorldloomStatusTone.WARNING,
                                    )
                                }
                                run.diagnostic?.let {
                                    WorldloomStatusBanner(it, WorldloomStatusTone.ERROR)
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm)) {
                                    item {
                                        WorldloomPrimaryButton(
                                            label = if (run.lifecycle == io.worldloom.world.RunLifecycle.COMPLETED) {
                                                "查看结局"
                                            } else {
                                                "继续"
                                            },
                                            onClick = { onContinueRequested(run.runId) },
                                        )
                                    }
                                    item {
                                        WorldloomSecondaryButton(
                                            label = "保存名称",
                                            onClick = { scope.launch { coordinator.rename(run.runId, name) } },
                                        )
                                    }
                                    item {
                                        WorldloomSecondaryButton(
                                            label = if (run.archived) "恢复到列表" else "归档",
                                            onClick = { scope.launch { coordinator.archive(run.runId, !run.archived) } },
                                        )
                                    }
                                    if (run.saveStatus == RunSaveStatus.FACTS_SAVED_DIRECTORY_PENDING) {
                                        item {
                                            WorldloomSecondaryButton(
                                                label = "修复存档目录",
                                                onClick = { scope.launch { coordinator.repairDirectory(run.runId) } },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun runLifecycleLabel(lifecycle: io.worldloom.world.RunLifecycle): String = when (lifecycle) {
    io.worldloom.world.RunLifecycle.CREATED -> "等待创建角色"
    io.worldloom.world.RunLifecycle.CHARACTER_CREATION -> "正在创建角色"
    io.worldloom.world.RunLifecycle.ACTIVE -> "旅程进行中"
    io.worldloom.world.RunLifecycle.COMPLETED -> "旅程已完成"
    io.worldloom.world.RunLifecycle.ABANDONED -> "旅程已结束"
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
private fun LoadingState(reduceMotion: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (reduceMotion) Text("正在加载…") else CircularProgressIndicator(color = MaterialTheme.colors.primary)
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
    onCheck: (io.worldloom.definition.DefinitionId) -> Unit,
    onReplay: () -> Unit,
    onAction: (io.worldloom.definition.DefinitionId) -> Unit,
    onWait: (Long) -> Unit,
    onActivity: (io.worldloom.definition.DefinitionId) -> Unit,
    onTravel: (io.worldloom.definition.DefinitionId) -> Unit,
    agentController: GameAgentController?,
    saveCoordinator: SaveCoordinator? = null,
    agentHistoryKey: String = "",
    guidanceRunKey: String = "",
    interactive: Boolean = true,
    replayInspector: ReplayInspector? = null,
    reduceMotion: Boolean = false,
    onNpcBusyChanged: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var displayedTimeline by remember(presentation.worldId, presentation.lastSequence) {
        mutableStateOf(presentation.timeline)
    }
    var hasEarlier by remember(presentation.worldId, presentation.lastSequence) {
        mutableStateOf(presentation.timelineTruncated)
    }
    var replayVerification by remember(presentation.worldId, presentation.lastSequence) {
        mutableStateOf<String?>(null)
    }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(presentation.title, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Card(backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.14f)) {
                        Text(
                            "事件序号 #${presentation.lastSequence}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = MaterialTheme.colors.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        "${displayedTimeline.size} 条可回放事件",
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.58f),
                        fontSize = 12.sp,
                    )
                }
            }
            Button(
                onClick = onReplay,
                colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary),
            ) {
                Text("回放校验")
            }
            if (replayInspector != null) {
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    scope.launch {
                        replayVerification = when (val result = replayInspector.exportVerifiedPublicReplay()) {
                            is PublicReplayResult.Verified -> "公开回放已离线校验 · ${result.document.events.size} 个事件"
                            is PublicReplayResult.Failure -> "公开回放校验失败：${result.message}"
                        }
                    }
                }) { Text("公开回放") }
            }
        }

        notice?.let { EmptyState(it.message, isError = true) }
        replayVerification?.let { EmptyState(it, isError = it.contains("失败")) }

        if (interactive) agentController?.let {
            AgentPanel(it, saveCoordinator, agentHistoryKey, guidanceRunKey, presentation.guidance, reduceMotion)
        }

        presentation.scene?.let { scene ->
            Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("当前场景 · ${scene.label}", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
                    scene.description?.let {
                        Text(it, color = MaterialTheme.colors.onSurface.copy(alpha = 0.78f))
                    }
                    if (scene.participantIds.isNotEmpty()) {
                        Text("参与者：${scene.participantIds.joinToString { it.value }}")
                    }
                    if (interactive && agentController != null && scene.addressableNpcs.isNotEmpty()) {
                        NpcDialoguePanel(
                            npcs = scene.addressableNpcs,
                            controller = agentController,
                            idempotencyPrefix = agentHistoryKey,
                            onBusyChanged = onNpcBusyChanged,
                        )
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(scene.actions, key = { it.id.value }) { action ->
                            Button(onClick = { onAction(action.id) }, enabled = interactive) { Text(action.label) }
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
                        if (presentation.worldTimeMinutes != null) Button(
                            onClick = { onWait(60) },
                            enabled = interactive,
                        ) { Text("等待 1 小时") }
                    }
                    if (presentation.activities.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(presentation.activities, key = { it.id.value }) { activity ->
                                Button(onClick = { onActivity(activity.id) }, enabled = interactive) {
                                    Text("${activity.label} · ${activity.durationMinutes} 分钟")
                                }
                            }
                        }
                    }
                    if (presentation.travelRoutes.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(presentation.travelRoutes, key = { it.id.value }) { route ->
                                Button(onClick = { onTravel(route.id) }, enabled = interactive) {
                                    Text("${route.label} · ${route.durationMinutes} 分钟")
                                }
                            }
                        }
                    }
                }
            }
        }

        presentation.adventureState?.let { AdventureCards(it) }

        presentation.endingSummary?.let { summary ->
            Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.secondary.copy(alpha = 0.16f)) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("本次结局", color = MaterialTheme.colors.secondary, fontWeight = FontWeight.Bold)
                    Text(summary)
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
                    Button(onClick = { onCheck(check.presentationId) }, enabled = interactive) {
                        Text(check.label)
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.72f),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("事件时间线", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
                        Text(
                            "世界事实按发生顺序完整展示，可滚动页面查看，不再压缩成底部窄条。",
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.56f),
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        "${displayedTimeline.size} 条",
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.58f),
                        fontSize = 12.sp,
                    )
                }
                if (presentation.timeline.isEmpty()) {
                    Text(
                        "尚无事件。所有事实变化都会在这里留下可回放记录。",
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.58f),
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 640.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (hasEarlier && replayInspector != null) {
                            item(key = "load-earlier") {
                                Button(onClick = {
                                    scope.launch {
                                        val before = displayedTimeline.firstOrNull()?.sequence
                                        when (val result = replayInspector.timelinePage(before, 100)) {
                                            is TimelinePageResult.Success -> {
                                                displayedTimeline = (result.page.events + displayedTimeline)
                                                    .distinctBy { it.sequence }
                                                    .sortedBy { it.sequence }
                                                hasEarlier = result.page.hasEarlier
                                            }
                                            is TimelinePageResult.Failure -> {
                                                replayVerification = "时间线读取失败：${result.message}"
                                            }
                                        }
                                    }
                                }) { Text("加载更早事件") }
                            }
                        }
                        items(displayedTimeline, key = { it.sequence }) { event ->
                            Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            "#${event.sequence}",
                                            color = MaterialTheme.colors.primary,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Spacer(Modifier.width(16.dp))
                                        Text(event.summary)
                                    }
                                    Text(
                                        "${event.eventType} · cause ${event.causationId ?: "-"}",
                                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.48f),
                                        fontSize = 11.sp,
                                    )
                                    event.randomRecord?.let { random ->
                                        Text(
                                            "Random ${random.recordId}: " +
                                                "${random.results.joinToString(" + ")} = ${random.total} → " +
                                                random.outcomeId.value,
                                            color = MaterialTheme.colors.secondary,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NpcDialoguePanel(
    npcs: List<io.worldloom.application.PresentedNpc>,
    controller: GameAgentController,
    idempotencyPrefix: String,
    onBusyChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var selectedId by remember(npcs) { mutableStateOf(npcs.first().id) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    val selected = npcs.firstOrNull { it.id == selectedId } ?: npcs.first()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("和场景角色交谈", fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(npcs, key = { it.id.value }) { npc ->
                Button(
                    onClick = { selectedId = npc.id },
                    enabled = !sending,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (npc.id == selected.id) {
                            MaterialTheme.colors.primary
                        } else {
                            MaterialTheme.colors.secondary
                        },
                    ),
                ) { Text(npc.displayName) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= 500) input = it },
                modifier = Modifier.weight(1f),
                label = { Text("对 ${selected.displayName} 说") },
                enabled = !sending,
            )
            Button(
                enabled = !sending && input.isNotBlank(),
                onClick = {
                    val message = input.trim()
                    val idempotencyKey = "ui:$idempotencyPrefix:${selected.id.value}"
                    sending = true
                    onBusyChanged(true)
                    status = null
                    scope.launch {
                        try {
                            when (val result = controller.addressNpc(selected.id, message, idempotencyKey)) {
                                is NpcDialogueResult.Committed -> {
                                    status = if (result.worldChanged) "发言已记录。" else "该发言已处理，没有重复写入。"
                                    input = ""
                                }
                                is NpcDialogueResult.Failed -> status = if (result.worldChanged) {
                                    "发言已记录，但角色回应暂时不可用。"
                                } else {
                                    result.message
                                }
                            }
                        } finally {
                            sending = false
                            onBusyChanged(false)
                        }
                    }
                },
            ) { Text(if (sending) "发送中" else "发送") }
        }
        status?.let { Text(it, color = MaterialTheme.colors.onSurface.copy(alpha = 0.68f), fontSize = 12.sp) }
    }
}

@Composable
private fun AdventureCards(adventure: AdventureStatePresentation) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("角色与目标", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(adventure.inventory, key = { "inventory:${it.id.value}" }) { item ->
                StateCard("库存 · ${item.label}", "× ${item.quantity}")
            }
            items(adventure.conditions, key = { "condition:${it.id.value}" }) { condition ->
                StateCard("状态 · ${condition.label}", "${condition.stacks} 层")
            }
            items(adventure.relationships, key = { "relationship:${it.id.value}" }) { relationship ->
                StateCard("关系 · ${relationship.label}", relationship.value.toString())
            }
            items(adventure.quests, key = { "quest:${it.id.value}" }) { quest ->
                StateCard("任务 · ${quest.label}", "${quest.stageLabel ?: "尚未开始"} · ${quest.status.name}")
            }
            items(adventure.clocks, key = { "clock:${it.id.value}" }) { clock ->
                Card(backgroundColor = MaterialTheme.colors.surface) {
                    Column(
                        modifier = Modifier.width(230.dp).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(clock.label, color = MaterialTheme.colors.onSurface.copy(alpha = 0.72f))
                        Text("${clock.value}/${clock.segments}", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        LinearProgressIndicator(
                            progress = if (clock.segments == 0L) 0f else clock.value.toFloat() / clock.segments.toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StateCard(title: String, value: String) {
    Card(backgroundColor = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.width(230.dp).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, color = MaterialTheme.colors.onSurface.copy(alpha = 0.72f))
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AgentPanel(
    controller: GameAgentController,
    saveCoordinator: SaveCoordinator?,
    historyKey: String,
    guidanceRunKey: String,
    guidance: GuidancePresentation,
    reduceMotion: Boolean,
) {
    val state by controller.state.collectAsState()
    val history by controller.history.collectAsState()
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var runningJob by remember { mutableStateOf<Job?>(null) }
    var guidanceState by remember(guidanceRunKey) { mutableStateOf(GuidanceInteractionState()) }
    val running = state is GameAgentState.Running
    val tutorial = guidanceState.visibleTutorials(guidance).firstOrNull()
    LaunchedEffect(controller, guidanceRunKey) { controller.recoverInterruptedHistory() }
    LaunchedEffect(saveCoordinator, state, history.items.size) { saveCoordinator?.refresh() }

    Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("主持人", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (history.loading && !reduceMotion) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp).height(16.dp),
                        color = MaterialTheme.colors.primary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        if (history.error == null) "${history.items.size} 个回合" else "历史不可用",
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.58f),
                        fontSize = 12.sp,
                    )
                }
            }
            tutorial?.let { step ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    backgroundColor = MaterialTheme.colors.secondary.copy(alpha = 0.14f),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("游玩引导", fontWeight = FontWeight.SemiBold)
                        Text(step.text)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item {
                                Button(onClick = { input = step.suggestion.inputDraft }, enabled = !running) {
                                    Text("采用建议 · ${step.suggestion.label}")
                                }
                            }
                            item {
                                Button(onClick = { guidanceState = guidanceState.complete(step.id, guidance) }) {
                                    Text("知道了")
                                }
                            }
                            item {
                                Button(
                                    onClick = { guidanceState = guidanceState.skip() },
                                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary),
                                ) { Text("跳过引导") }
                            }
                        }
                    }
                }
            } ?: run {
                if (guidance.tutorials.isNotEmpty()) {
                    Button(
                        onClick = { guidanceState = guidanceState.review() },
                        colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary),
                    ) { Text("重新查看引导") }
                }
            }
            guidance.diagnostic?.let { Text(it, color = MaterialTheme.colors.error) }
            guidance.hints.firstOrNull()?.let { hint ->
                Text("场景提示 · ${hint.text}", color = MaterialTheme.colors.onSurface.copy(alpha = 0.78f))
            }
            if (guidance.suggestions.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(guidance.suggestions, key = { "${it.targetKind}:${it.targetId.value}" }) { suggestion ->
                        Button(
                            onClick = { input = suggestion.inputDraft },
                            enabled = !running,
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary),
                        ) { Text("建议 · ${suggestion.label}") }
                    }
                }
            }
            if (history.hasEarlier) {
                Button(
                    onClick = { scope.launch { controller.loadEarlierHistory() } },
                    enabled = !history.loading && !running,
                    colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary),
                ) {
                    Text("加载较早回合")
                }
            }
            if (history.items.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().height(280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(history.items, key = { it.turnId.value }) { item ->
                        HostedTurnRow(
                            item = item,
                            enabled = !running,
                            onRetry = {
                                runningJob = scope.launch { controller.retry(item.turnId) }
                            },
                            onRecoverNarration = {
                                runningJob = scope.launch { controller.recoverNarration(item.turnId) }
                            },
                        )
                    }
                }
            } else if (!history.loading) {
                Text(
                    "描述行动后，玩家输入和主持公开叙事会保存在当前 Run。",
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.58f),
                )
            }
            history.issues.forEach { issue ->
                Text(issue.message, color = MaterialTheme.colors.error, fontSize = 12.sp)
            }
            history.error?.let { Text(it, color = MaterialTheme.colors.error, fontSize = 12.sp) }
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
                GameAgentState.Idle -> Unit

                is GameAgentState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(18.dp).height(18.dp),
                        color = MaterialTheme.colors.primary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(current.partialText.ifBlank { "正在思考…" })
                }

                is GameAgentState.Completed -> Unit
                is GameAgentState.AwaitingPlayer -> Unit
                is GameAgentState.Failed -> Text(
                    when (current.recoveryKind) {
                        GameTurnRecoveryKind.RETRY_SAFE -> "${current.message} 可以安全重试。"
                        GameTurnRecoveryKind.NARRATION_REQUIRED -> "${current.message} 事实已保存，可补叙述。"
                        GameTurnRecoveryKind.NONE -> if (current.worldChanged) {
                            "${current.message}（权威事实已写入事件）"
                        } else {
                            current.message
                        }
                    },
                    color = MaterialTheme.colors.error,
                )
            }
        }
    }
}

@Composable
private fun HostedTurnRow(
    item: HostedTurnHistoryItem,
    enabled: Boolean,
    onRetry: () -> Unit,
    onRecoverNarration: () -> Unit,
) {
    Card(backgroundColor = MaterialTheme.colors.background) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text("你 · ${item.playerInput}", fontWeight = FontWeight.SemiBold)
            item.publicOutput?.let { Text("主持人 · $it") }
            item.safeFailureMessage?.let { Text(it, color = MaterialTheme.colors.error) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    hostedTurnStatus(item.status),
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.56f),
                    fontSize = 12.sp,
                )
                item.evidence?.let {
                    Text(
                        "事件 (${it.fromSequenceExclusive}, ${it.throughSequenceInclusive}]",
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.56f),
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                when (item.recoveryKind) {
                    GameTurnRecoveryKind.RETRY_SAFE -> Button(onClick = onRetry, enabled = enabled) { Text("重试") }
                    GameTurnRecoveryKind.NARRATION_REQUIRED -> Button(
                        onClick = onRecoverNarration,
                        enabled = enabled,
                    ) { Text("补叙述") }
                    GameTurnRecoveryKind.NONE -> Unit
                }
            }
        }
    }
}

private fun hostedTurnStatus(status: GameTurnStatus): String = when (status) {
    GameTurnStatus.ACCEPTED -> "已接受"
    GameTurnStatus.RUNNING -> "主持中"
    GameTurnStatus.AWAITING_PLAYER -> "等待你的选择"
    GameTurnStatus.COMPLETED -> "已完成"
    GameTurnStatus.CANCELLED -> "已取消"
    GameTurnStatus.FAILED -> "未完成"
}
