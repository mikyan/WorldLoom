package io.worldloom.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import io.worldloom.platform.credentials.CredentialConfiguration
import io.worldloom.platform.credentials.CredentialConfigurationState
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConfigurationCenter
import io.worldloom.provider.api.ProviderConfigurationId
import io.worldloom.provider.api.ProviderConnectionTestResult
import io.worldloom.provider.api.ProviderModelDiscoveryResult
import io.worldloom.provider.openai.OpenAiSubscriptionSource
import kotlinx.coroutines.launch

@Composable
internal fun ProviderSettingsPage(
    center: ProviderConfigurationCenter,
    sources: List<OpenAiSubscriptionSource>,
    credentialConfigurations: Map<ProviderConfigurationId, CredentialConfiguration>,
    onBack: () -> Unit,
    onConfigurationSaved: () -> Unit = {},
) {
    var configurations by remember(center) { mutableStateOf(emptyList<ProviderConfiguration>()) }
    var selectedConfigurationId by remember(center) { mutableStateOf<ProviderConfigurationId?>(null) }
    var editingSourceId by remember(sources) { mutableStateOf(sources.firstOrNull()?.configurationId) }
    var loading by remember(center) { mutableStateOf(true) }

    LaunchedEffect(center, sources) {
        configurations = center.configurations()
        selectedConfigurationId = center.selectedConfigurationId()
        editingSourceId = sources.firstOrNull { it.configurationId == selectedConfigurationId }?.configurationId
            ?: sources.firstOrNull()?.configurationId
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Lg),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Md),
        ) {
            WorldloomSectionHeading(
                title = "订阅与模型",
                subtitle = "密钥由平台保险箱独立保存；世界内容与存档无法读取它。",
                modifier = Modifier.weight(1f),
            )
            WorldloomSecondaryButton("返回", onBack)
        }

        if (sources.isEmpty() || credentialConfigurations.isEmpty()) {
            WorldloomStatusBanner(
                message = "当前平台没有可用的订阅源配置。",
                tone = WorldloomStatusTone.ERROR,
            )
            return@Column
        }

        WorldloomSectionHeading(
            title = "服务来源",
            subtitle = "先选择要编辑的来源，再保存并启用。",
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
        ) {
            items(sources, key = { it.configurationId.value }) { source ->
                val editing = source.configurationId == editingSourceId
                val selected = source.configurationId == selectedConfigurationId
                WorldloomChoiceCard(
                    title = source.displayName,
                    subtitle = if (selected) "当前正在使用" else source.description,
                    selected = editing,
                    enabled = !loading,
                    modifier = Modifier.widthIn(
                        min = WorldloomDimensions.SelectorCardMinWidth,
                        max = WorldloomDimensions.SelectorCardMaxWidth,
                    ),
                    onClick = { editingSourceId = source.configurationId },
                )
            }
        }

        if (loading) {
            WorldloomStatusBanner("正在读取订阅配置…", WorldloomStatusTone.INFO)
        }

        val source = sources.firstOrNull { it.configurationId == editingSourceId }
        val credentialConfiguration = source?.let { credentialConfigurations[it.configurationId] }
        if (!loading && source != null && credentialConfiguration != null) {
            key(source.configurationId.value) {
                ProviderSourceEditor(
                    center = center,
                    source = source,
                    storedConfiguration = configurations.firstOrNull { it.id == source.configurationId },
                    credentialConfiguration = credentialConfiguration,
                    selected = source.configurationId == selectedConfigurationId,
                    onSaved = { updated ->
                        configurations = configurations
                            .filterNot { it.id == updated.id }
                            .plus(updated)
                            .sortedBy { it.id.value }
                        selectedConfigurationId = updated.id
                        onConfigurationSaved()
                    },
                )
            }
        }
    }
}

private data class ProviderEditorFeedback(
    val message: String,
    val tone: WorldloomStatusTone,
)

@Composable
private fun ProviderSourceEditor(
    center: ProviderConfigurationCenter,
    source: OpenAiSubscriptionSource,
    storedConfiguration: ProviderConfiguration?,
    credentialConfiguration: CredentialConfiguration,
    selected: Boolean,
    onSaved: (ProviderConfiguration) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val credentialState by credentialConfiguration.state.collectAsState()
    var credential by remember(source.configurationId) { mutableStateOf("") }
    var baseUrl by remember(source.configurationId) {
        mutableStateOf(storedConfiguration?.baseUrl ?: source.baseUrl)
    }
    var modelId by remember(source.configurationId) {
        mutableStateOf(storedConfiguration?.modelId ?: source.modelId)
    }
    var discoveredModels by remember(source.configurationId) { mutableStateOf(emptyList<String>()) }
    var modelFilter by remember(source.configurationId) { mutableStateOf("") }
    var feedback by remember(source.configurationId) {
        mutableStateOf(
            ProviderEditorFeedback(
                message = if (selected) "当前正在使用此订阅源。" else "保存后将启用此订阅源。",
                tone = if (selected) WorldloomStatusTone.SUCCESS else WorldloomStatusTone.INFO,
            ),
        )
    }
    var loading by remember(source.configurationId) { mutableStateOf(false) }

    LaunchedEffect(credentialConfiguration) {
        credentialConfiguration.refresh()
    }

    fun saveThen(block: suspend (ProviderConfiguration) -> ProviderEditorFeedback) {
        if (credential.isBlank() && credentialState !is CredentialConfigurationState.Configured) {
            feedback = ProviderEditorFeedback("请输入 API Key。", WorldloomStatusTone.WARNING)
            return
        }
        loading = true
        val submittedCredential = credential
        credential = ""
        scope.launch {
            try {
                if (submittedCredential.isNotBlank() && !credentialConfiguration.configure(submittedCredential)) {
                    feedback = ProviderEditorFeedback("API Key 保存失败。", WorldloomStatusTone.ERROR)
                    return@launch
                }
                val updated = source.configurationWithSelection(storedConfiguration, baseUrl, modelId)
                center.upsert(updated)
                center.select(updated.id)
                onSaved(updated)
                feedback = block(updated)
            } catch (error: IllegalArgumentException) {
                feedback = ProviderEditorFeedback(
                    error.message ?: "订阅源配置无效。",
                    WorldloomStatusTone.ERROR,
                )
            } finally {
                loading = false
            }
        }
    }

    fun activateModel(selectedModel: String) {
        modelId = selectedModel
        loading = true
        scope.launch {
            try {
                val updated = source.configurationWithSelection(
                    storedConfiguration = storedConfiguration,
                    customBaseUrl = baseUrl,
                    modelId = selectedModel,
                )
                center.upsert(updated)
                center.select(updated.id)
                onSaved(updated)
                feedback = ProviderEditorFeedback(
                    "已切换到模型 $selectedModel。",
                    WorldloomStatusTone.SUCCESS,
                )
            } catch (error: IllegalArgumentException) {
                feedback = ProviderEditorFeedback(
                    error.message ?: "模型配置无效。",
                    WorldloomStatusTone.ERROR,
                )
            } finally {
                loading = false
            }
        }
    }

    WorldloomPanel(modifier = Modifier.fillMaxWidth(), strong = true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Md),
        ) {
            WorldloomSectionHeading(
                title = source.displayName,
                subtitle = source.description,
                modifier = Modifier.weight(1f),
            )
            Text(
                credentialStatus(credentialState),
                color = if (credentialState is CredentialConfigurationState.Failed) {
                    WorldloomPalette.Error
                } else {
                    WorldloomPalette.TextSecondary
                },
                style = MaterialTheme.typography.caption,
            )
        }

        if (source.customEndpoint) {
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("兼容服务地址") },
                singleLine = true,
                enabled = !loading,
            )
        } else {
            Text("服务地址由内置来源管理。", color = WorldloomPalette.TextSecondary)
        }

        OutlinedTextField(
            value = modelId,
            onValueChange = { modelId = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("模型") },
            singleLine = true,
            enabled = !loading,
        )

        OutlinedTextField(
            value = credential,
            onValueChange = { credential = it },
            modifier = Modifier.fillMaxWidth(),
            label = {
                Text(
                    if (credentialState is CredentialConfigurationState.Configured) {
                        "API Key（留空则保留现有密钥）"
                    } else {
                        "API Key"
                    },
                )
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !loading && credentialState !is CredentialConfigurationState.Loading,
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
        ) {
            item {
                WorldloomPrimaryButton(
                    label = if (loading) "正在保存…" else "保存并启用",
                    enabled = !loading,
                    onClick = {
                        saveThen { updated ->
                            ProviderEditorFeedback("已启用 ${updated.displayName}。", WorldloomStatusTone.SUCCESS)
                        }
                    },
                )
            }
            item {
                WorldloomSecondaryButton(
                    label = "保存并测试",
                    enabled = !loading,
                    onClick = {
                        saveThen { updated ->
                            when (val result = center.test(updated.id)) {
                                is ProviderConnectionTestResult.Connected ->
                                    ProviderEditorFeedback("连接成功。", WorldloomStatusTone.SUCCESS)
                                is ProviderConnectionTestResult.Failed ->
                                    ProviderEditorFeedback(result.message, WorldloomStatusTone.ERROR)
                            }
                        }
                    },
                )
            }
            item {
                WorldloomSecondaryButton(
                    label = "获取模型列表",
                    enabled = !loading,
                    onClick = {
                        saveThen { updated ->
                            when (val result = center.discoverModels(updated.id)) {
                                is ProviderModelDiscoveryResult.Success -> {
                                    discoveredModels = result.models.map { it.id }
                                    modelFilter = ""
                                    if (modelId in discoveredModels || discoveredModels.isEmpty()) {
                                        ProviderEditorFeedback(
                                            "已获取 ${discoveredModels.size} 个模型。",
                                            WorldloomStatusTone.SUCCESS,
                                        )
                                    } else {
                                        ProviderEditorFeedback(
                                            "已获取 ${discoveredModels.size} 个模型；请重新选择当前模型。",
                                            WorldloomStatusTone.WARNING,
                                        )
                                    }
                                }
                                is ProviderModelDiscoveryResult.Failure ->
                                    ProviderEditorFeedback(result.message, WorldloomStatusTone.ERROR)
                            }
                        }
                    },
                )
            }
            if (credentialState is CredentialConfigurationState.Configured) {
                item {
                    WorldloomDangerButton(
                        label = "删除密钥",
                        enabled = !loading,
                        onClick = {
                            loading = true
                            scope.launch {
                                val cleared = credentialConfiguration.clear()
                                feedback = if (cleared) {
                                    ProviderEditorFeedback("已删除此订阅源的 API Key。", WorldloomStatusTone.SUCCESS)
                                } else {
                                    ProviderEditorFeedback("API Key 删除失败。", WorldloomStatusTone.ERROR)
                                }
                                loading = false
                            }
                        },
                    )
                }
            }
        }

        WorldloomStatusBanner(feedback.message, feedback.tone)
        if (discoveredModels.isNotEmpty()) {
            OutlinedTextField(
                value = modelFilter,
                onValueChange = { modelFilter = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("筛选模型") },
                singleLine = true,
                enabled = !loading,
            )
            val visibleModels = discoveredModels
                .filter { model -> modelFilter.isBlank() || model.contains(modelFilter, ignoreCase = true) }
                .take(MAX_VISIBLE_MODELS)
            Text(
                "显示 ${visibleModels.size} / ${discoveredModels.size}，选择后立即启用。",
                color = WorldloomPalette.TextSecondary,
                style = MaterialTheme.typography.caption,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm)) {
                items(visibleModels, key = { it }) { model ->
                    WorldloomChoiceCard(
                        title = model,
                        selected = model == modelId,
                        enabled = !loading,
                        modifier = Modifier.widthIn(
                            min = WorldloomDimensions.DenseSelectorCardMinWidth,
                            max = WorldloomDimensions.SelectorCardMaxWidth,
                        ),
                        onClick = { activateModel(model) },
                    )
                }
            }
        }
    }
}

internal fun OpenAiSubscriptionSource.configurationWithSelection(
    storedConfiguration: ProviderConfiguration?,
    customBaseUrl: String,
    modelId: String,
): ProviderConfiguration = (storedConfiguration ?: defaultConfiguration()).copy(
    displayName = displayName,
    baseUrl = if (customEndpoint) customBaseUrl.trim() else baseUrl,
    modelId = modelId.trim(),
    credentialKey = credentialKey.value,
)

private fun credentialStatus(state: CredentialConfigurationState): String = when (state) {
    CredentialConfigurationState.Unknown -> "尚未检查"
    CredentialConfigurationState.Loading -> "正在更新…"
    CredentialConfigurationState.Configured -> "密钥已配置"
    CredentialConfigurationState.NotConfigured -> "密钥未配置"
    is CredentialConfigurationState.Failed -> state.message
}

private const val MAX_VISIBLE_MODELS = 50
