package io.worldloom.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("订阅与模型", color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
                        Text(
                            "每个订阅源独立保存密钥。选择内置来源后只需填写 API Key；自定义来源可填写 OpenAI-compatible 地址。",
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.68f),
                        )
                    }
                    Button(onClick = onBack) { Text("返回") }
                }
            }
        }

        if (sources.isEmpty() || credentialConfigurations.isEmpty()) {
            Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
                Text(
                    "当前平台没有可用的订阅源配置。",
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    color = MaterialTheme.colors.error,
                )
            }
            return@Column
        }

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sources, key = { it.configurationId.value }) { source ->
                val editing = source.configurationId == editingSourceId
                val selected = source.configurationId == selectedConfigurationId
                Button(
                    onClick = { editingSourceId = source.configurationId },
                    enabled = !loading,
                    colors = ButtonDefaults.buttonColors(
                        backgroundColor = if (editing) {
                            MaterialTheme.colors.primary
                        } else {
                            MaterialTheme.colors.secondary
                        },
                    ),
                ) {
                    Text(if (selected) "${source.displayName} · 正在使用" else source.displayName)
                }
            }
        }

        val source = sources.firstOrNull { it.configurationId == editingSourceId }
        val credentialConfiguration = source?.let { credentialConfigurations[it.configurationId] }
        if (source != null && credentialConfiguration != null) {
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
                    },
                )
            }
        }
    }
}

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
    var status by remember(source.configurationId) {
        mutableStateOf(if (selected) "当前正在使用此订阅源" else "尚未启用")
    }
    var loading by remember(source.configurationId) { mutableStateOf(false) }

    LaunchedEffect(credentialConfiguration) {
        credentialConfiguration.refresh()
    }

    fun saveThen(block: suspend (ProviderConfiguration) -> Unit) {
        if (credential.isBlank() && credentialState !is CredentialConfigurationState.Configured) {
            status = "请输入 API Key"
            return
        }
        loading = true
        val submittedCredential = credential
        credential = ""
        scope.launch {
            try {
                if (submittedCredential.isNotBlank() && !credentialConfiguration.configure(submittedCredential)) {
                    status = "API Key 保存失败"
                    return@launch
                }
                val updated = source.configurationWithSelection(storedConfiguration, baseUrl, modelId)
                center.upsert(updated)
                center.select(updated.id)
                onSaved(updated)
                block(updated)
            } catch (error: IllegalArgumentException) {
                status = error.message ?: "订阅源配置无效"
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
                status = "已切换到模型 $selectedModel"
            } catch (error: IllegalArgumentException) {
                status = error.message ?: "模型配置无效"
            } finally {
                loading = false
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth(), backgroundColor = MaterialTheme.colors.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(source.displayName, color = MaterialTheme.colors.primary, fontWeight = FontWeight.SemiBold)
                    Text(
                        source.description,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.68f),
                        fontSize = 12.sp,
                    )
                }
                Text(
                    credentialStatus(credentialState),
                    color = if (credentialState is CredentialConfigurationState.Failed) {
                        MaterialTheme.colors.error
                    } else {
                        MaterialTheme.colors.onSurface.copy(alpha = 0.62f)
                    },
                    fontSize = 12.sp,
                )
            }

            if (source.customEndpoint) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("OpenAI-compatible Base URL") },
                    singleLine = true,
                    enabled = !loading,
                )
            } else {
                Text("服务地址：${source.baseUrl}", color = MaterialTheme.colors.onSurface.copy(alpha = 0.72f))
            }

            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Model ID（可从服务端模型列表选择）") },
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
                            "API Key（留空则保留已保存密钥）"
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    Button(
                        enabled = !loading,
                        onClick = {
                            saveThen { updated -> status = "已启用 ${updated.displayName}" }
                        },
                    ) { Text("保存并启用") }
                }
                item {
                    Button(
                        enabled = !loading,
                        onClick = {
                            saveThen { updated ->
                                status = when (val result = center.test(updated.id)) {
                                    is ProviderConnectionTestResult.Connected -> "连接成功"
                                    is ProviderConnectionTestResult.Failed -> result.message
                                }
                            }
                        },
                    ) { Text("保存并测试") }
                }
                item {
                    Button(
                        enabled = !loading,
                        onClick = {
                            saveThen { updated ->
                                when (val result = center.discoverModels(updated.id)) {
                                    is ProviderModelDiscoveryResult.Success -> {
                                        discoveredModels = result.models.map { it.id }
                                        modelFilter = ""
                                        status = if (modelId in discoveredModels || discoveredModels.isEmpty()) {
                                            "从 Models API 获取到 ${discoveredModels.size} 个模型"
                                        } else {
                                            "获取到 ${discoveredModels.size} 个模型；当前 Model ID 不在列表中，请重新选择"
                                        }
                                    }
                                    is ProviderModelDiscoveryResult.Failure -> status = result.message
                                }
                            }
                        },
                    ) { Text("获取模型列表") }
                }
                if (credentialState is CredentialConfigurationState.Configured) {
                    item {
                        Button(
                            enabled = !loading,
                            onClick = {
                                loading = true
                                scope.launch {
                                    val cleared = credentialConfiguration.clear()
                                    status = if (cleared) "已删除此订阅源的 API Key" else "API Key 删除失败"
                                    loading = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.secondary),
                        ) { Text("删除密钥") }
                    }
                }
            }

            Text(status, color = MaterialTheme.colors.onSurface.copy(alpha = 0.68f), fontSize = 12.sp)
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
                    "显示 ${visibleModels.size} / ${discoveredModels.size}，点击后立即启用",
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.62f),
                    fontSize = 12.sp,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(visibleModels, key = { it }) { model ->
                        Button(onClick = { activateModel(model) }, enabled = !loading) { Text(model) }
                    }
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
