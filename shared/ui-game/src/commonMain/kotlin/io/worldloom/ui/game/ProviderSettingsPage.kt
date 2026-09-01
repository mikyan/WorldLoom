package io.worldloom.ui.game

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.ExposedDropdownMenuBox
import androidx.compose.material.ExposedDropdownMenuDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.worldloom.platform.credentials.CredentialConfiguration
import io.worldloom.platform.credentials.CredentialConfigurationState
import io.worldloom.provider.api.ProviderConfiguration
import io.worldloom.provider.api.ProviderConfigurationCenter
import io.worldloom.provider.api.ProviderConfigurationId
import io.worldloom.provider.api.ProviderConnectionTestResult
import io.worldloom.provider.api.ProviderModelDescriptor
import io.worldloom.provider.api.ProviderModelDiscoveryResult
import io.worldloom.provider.openai.OpenAiSubscriptionSource
import kotlinx.coroutines.launch

private enum class SettingsSection(val label: String, val marker: String) {
    PROVIDERS("提供者", "P"),
    MODELS("模型", "M"),
}

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
    var expandedProviderId by remember(sources) { mutableStateOf<ProviderConfigurationId?>(null) }
    var modelProviderId by remember(sources) { mutableStateOf<ProviderConfigurationId?>(null) }
    var section by remember { mutableStateOf(SettingsSection.PROVIDERS) }
    var loading by remember(center) { mutableStateOf(true) }
    val discoveredModels = remember(center) {
        mutableStateMapOf<ProviderConfigurationId, List<ProviderModelDescriptor>>()
    }

    LaunchedEffect(center, sources) {
        configurations = center.configurations()
        selectedConfigurationId = center.selectedConfigurationId()
        val initialId = selectedConfigurationId?.takeIf { selected ->
            sources.any { it.configurationId == selected }
        } ?: sources.firstOrNull()?.configurationId
        expandedProviderId = initialId
        modelProviderId = initialId
        loading = false
    }

    fun updateConfiguration(updated: ProviderConfiguration, selected: Boolean) {
        configurations = configurations
            .filterNot { it.id == updated.id }
            .plus(updated)
            .sortedBy { it.id.value }
        if (selected) selectedConfigurationId = updated.id
        onConfigurationSaved()
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
                title = "设置",
                subtitle = "管理模型服务。API Key 只保存在平台凭据保险箱中。",
                modifier = Modifier.weight(1f),
            )
            WorldloomSecondaryButton("返回", onBack)
        }

        if (sources.isEmpty() || credentialConfigurations.isEmpty()) {
            WorldloomStatusBanner(
                message = "当前平台没有可用的提供者配置。",
                tone = WorldloomStatusTone.ERROR,
            )
            return@Column
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val compact = maxWidth < SETTINGS_NAVIGATION_BREAKPOINT
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Lg)) {
                    SettingsNavigation(section, true, { section = it })
                    SettingsSectionContent(
                        section = section,
                        center = center,
                        sources = sources,
                        credentialConfigurations = credentialConfigurations,
                        configurations = configurations,
                        selectedConfigurationId = selectedConfigurationId,
                        expandedProviderId = expandedProviderId,
                        onExpandedProviderChanged = { expandedProviderId = it },
                        modelProviderId = modelProviderId,
                        onModelProviderChanged = { modelProviderId = it },
                        discoveredModels = discoveredModels,
                        loading = loading,
                        onConfigurationUpdated = ::updateConfiguration,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Lg),
                    verticalAlignment = Alignment.Top,
                ) {
                    SettingsNavigation(
                        selected = section,
                        horizontal = false,
                        onSelected = { section = it },
                        modifier = Modifier.width(SETTINGS_NAVIGATION_WIDTH),
                    )
                    SettingsSectionContent(
                        section = section,
                        center = center,
                        sources = sources,
                        credentialConfigurations = credentialConfigurations,
                        configurations = configurations,
                        selectedConfigurationId = selectedConfigurationId,
                        expandedProviderId = expandedProviderId,
                        onExpandedProviderChanged = { expandedProviderId = it },
                        modelProviderId = modelProviderId,
                        onModelProviderChanged = { modelProviderId = it },
                        discoveredModels = discoveredModels,
                        loading = loading,
                        onConfigurationUpdated = ::updateConfiguration,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigation(
    selected: SettingsSection,
    horizontal: Boolean,
    onSelected: (SettingsSection) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = WorldloomPalette.SurfaceStrong,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, WorldloomPalette.BorderSubtle),
    ) {
        if (horizontal) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(WorldloomSpacing.Xs),
                horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs),
            ) {
                SettingsSection.entries.forEach { section ->
                    SettingsNavigationItem(
                        section = section,
                        selected = section == selected,
                        onClick = { onSelected(section) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(WorldloomSpacing.Sm),
                verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs),
            ) {
                SettingsSection.entries.forEach { section ->
                    SettingsNavigationItem(section, section == selected, { onSelected(section) })
                }
            }
        }
    }
}

@Composable
private fun SettingsNavigationItem(
    section: SettingsSection,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = if (selected) WorldloomPalette.BrandPrimary else WorldloomPalette.TextMuted
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (selected) WorldloomPalette.SurfaceRaised else Color.Transparent,
        shape = MaterialTheme.shapes.small,
        border = if (selected) BorderStroke(1.dp, WorldloomPalette.BorderFocus) else null,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = WorldloomSpacing.Md,
                vertical = WorldloomSpacing.Sm,
            ),
            horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(color = accent, shape = androidx.compose.foundation.shape.CircleShape) {
                Box(
                    modifier = Modifier.width(22.dp).padding(vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        section.marker,
                        color = if (selected) WorldloomPalette.OnBrandPrimary else WorldloomPalette.Canvas,
                        style = MaterialTheme.typography.caption,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                section.label,
                color = if (selected) WorldloomPalette.TextPrimary else WorldloomPalette.TextSecondary,
                style = MaterialTheme.typography.body2,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun SettingsSectionContent(
    section: SettingsSection,
    center: ProviderConfigurationCenter,
    sources: List<OpenAiSubscriptionSource>,
    credentialConfigurations: Map<ProviderConfigurationId, CredentialConfiguration>,
    configurations: List<ProviderConfiguration>,
    selectedConfigurationId: ProviderConfigurationId?,
    expandedProviderId: ProviderConfigurationId?,
    onExpandedProviderChanged: (ProviderConfigurationId?) -> Unit,
    modelProviderId: ProviderConfigurationId?,
    onModelProviderChanged: (ProviderConfigurationId) -> Unit,
    discoveredModels: MutableMap<ProviderConfigurationId, List<ProviderModelDescriptor>>,
    loading: Boolean,
    onConfigurationUpdated: (ProviderConfiguration, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Lg)) {
        when (section) {
            SettingsSection.PROVIDERS -> ProviderListSettings(
                center = center,
                sources = sources,
                credentialConfigurations = credentialConfigurations,
                configurations = configurations,
                selectedConfigurationId = selectedConfigurationId,
                expandedProviderId = expandedProviderId,
                loading = loading,
                onExpandedProviderChanged = onExpandedProviderChanged,
                onConfigurationUpdated = { onConfigurationUpdated(it, false) },
            )
            SettingsSection.MODELS -> ModelSelectionSettings(
                center = center,
                sources = sources,
                credentialConfigurations = credentialConfigurations,
                configurations = configurations,
                selectedConfigurationId = selectedConfigurationId,
                modelProviderId = modelProviderId,
                onModelProviderChanged = onModelProviderChanged,
                discoveredModels = discoveredModels,
                loading = loading,
                onConfigurationUpdated = { onConfigurationUpdated(it, true) },
            )
        }
    }
}

@Composable
private fun ProviderListSettings(
    center: ProviderConfigurationCenter,
    sources: List<OpenAiSubscriptionSource>,
    credentialConfigurations: Map<ProviderConfigurationId, CredentialConfiguration>,
    configurations: List<ProviderConfiguration>,
    selectedConfigurationId: ProviderConfigurationId?,
    expandedProviderId: ProviderConfigurationId?,
    loading: Boolean,
    onExpandedProviderChanged: (ProviderConfigurationId?) -> Unit,
    onConfigurationUpdated: (ProviderConfiguration) -> Unit,
) {
    WorldloomSectionHeading(
        title = "提供者",
        subtitle = "分别配置服务地址和 API Key。展开一项即可编辑，已保存的密钥不会回显。",
    )
    if (loading) {
        WorldloomStatusBanner("正在读取提供者配置…", WorldloomStatusTone.INFO)
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = WorldloomPalette.SurfaceStrong,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, WorldloomPalette.BorderSubtle),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            sources.forEachIndexed { index, source ->
                key(source.configurationId.value) {
                    ProviderListItem(
                        center = center,
                        source = source,
                        storedConfiguration = configurations.firstOrNull { it.id == source.configurationId },
                        credentialConfiguration = credentialConfigurations.getValue(source.configurationId),
                        selected = source.configurationId == selectedConfigurationId,
                        expanded = source.configurationId == expandedProviderId,
                        onExpandedChanged = {
                            onExpandedProviderChanged(if (it) source.configurationId else null)
                        },
                        onConfigurationUpdated = onConfigurationUpdated,
                    )
                }
                if (index < sources.lastIndex) Divider(color = WorldloomPalette.BorderSubtle)
            }
        }
    }
}

private data class ProviderEditorFeedback(
    val message: String,
    val tone: WorldloomStatusTone,
)

@Composable
private fun ProviderListItem(
    center: ProviderConfigurationCenter,
    source: OpenAiSubscriptionSource,
    storedConfiguration: ProviderConfiguration?,
    credentialConfiguration: CredentialConfiguration,
    selected: Boolean,
    expanded: Boolean,
    onExpandedChanged: (Boolean) -> Unit,
    onConfigurationUpdated: (ProviderConfiguration) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val credentialState by credentialConfiguration.state.collectAsState()
    var credential by remember(source.configurationId) { mutableStateOf("") }
    var baseUrl by remember(source.configurationId, storedConfiguration?.baseUrl) {
        mutableStateOf(storedConfiguration?.baseUrl ?: source.baseUrl)
    }
    var feedback by remember(source.configurationId) { mutableStateOf<ProviderEditorFeedback?>(null) }
    var saving by remember(source.configurationId) { mutableStateOf(false) }

    LaunchedEffect(credentialConfiguration) { credentialConfiguration.refresh() }

    fun saveThen(block: suspend (ProviderConfiguration) -> ProviderEditorFeedback) {
        if (credential.isBlank() && credentialState !is CredentialConfigurationState.Configured) {
            feedback = ProviderEditorFeedback("请输入 API Key。", WorldloomStatusTone.WARNING)
            return
        }
        saving = true
        val submittedCredential = credential
        credential = ""
        scope.launch {
            try {
                if (submittedCredential.isNotBlank() && !credentialConfiguration.configure(submittedCredential)) {
                    feedback = ProviderEditorFeedback("API Key 保存失败。", WorldloomStatusTone.ERROR)
                    return@launch
                }
                val updated = source.configurationWithProviderSettings(storedConfiguration, baseUrl)
                center.upsert(updated)
                onConfigurationUpdated(updated)
                feedback = block(updated)
            } catch (error: IllegalArgumentException) {
                feedback = ProviderEditorFeedback(
                    error.message ?: "提供者配置无效。",
                    WorldloomStatusTone.ERROR,
                )
            } finally {
                saving = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onExpandedChanged(!expanded) }
                .padding(horizontal = WorldloomSpacing.Md, vertical = WorldloomSpacing.Md),
            horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = when {
                    credentialState is CredentialConfigurationState.Configured -> WorldloomPalette.Success
                    selected -> WorldloomPalette.Warning
                    else -> WorldloomPalette.TextMuted
                },
                shape = androidx.compose.foundation.shape.CircleShape,
            ) {
                Box(modifier = Modifier.width(9.dp).padding(vertical = 4.5.dp))
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs),
            ) {
                Text(
                    source.displayName,
                    color = WorldloomPalette.TextPrimary,
                    style = MaterialTheme.typography.body1,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (expanded) {
                    Text(source.description, color = WorldloomPalette.TextSecondary, style = MaterialTheme.typography.caption)
                }
            }
            if (selected) Text("当前使用", color = WorldloomPalette.BrandPrimary, style = MaterialTheme.typography.caption)
            Text(
                credentialListStatus(credentialState),
                color = if (credentialState is CredentialConfigurationState.Failed) {
                    WorldloomPalette.Error
                } else {
                    WorldloomPalette.TextSecondary
                },
                style = MaterialTheme.typography.caption,
            )
            Text(
                if (expanded) "收起 ︿" else "配置 ﹀",
                color = WorldloomPalette.BrandPrimary,
                style = MaterialTheme.typography.caption,
                fontWeight = FontWeight.SemiBold,
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    start = WorldloomSpacing.Xl,
                    end = WorldloomSpacing.Md,
                    bottom = WorldloomSpacing.Lg,
                ),
                verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Md),
            ) {
                if (source.customEndpoint) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("兼容服务地址") },
                        singleLine = true,
                        enabled = !saving,
                    )
                } else {
                    Text(
                        "服务地址：${source.baseUrl}",
                        color = WorldloomPalette.TextSecondary,
                        style = MaterialTheme.typography.body2,
                    )
                }
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
                    enabled = !saving && credentialState !is CredentialConfigurationState.Loading,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WorldloomPrimaryButton(
                        label = if (saving) "正在保存…" else "保存",
                        enabled = !saving,
                        onClick = {
                            saveThen {
                                ProviderEditorFeedback("${it.displayName} 的连接配置已保存。", WorldloomStatusTone.SUCCESS)
                            }
                        },
                    )
                    WorldloomSecondaryButton(
                        label = "保存并测试",
                        enabled = !saving,
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
                    if (credentialState is CredentialConfigurationState.Configured) {
                        WorldloomDangerButton(
                            label = "删除密钥",
                            enabled = !saving,
                            onClick = {
                                saving = true
                                scope.launch {
                                    val cleared = credentialConfiguration.clear()
                                    feedback = if (cleared) {
                                        ProviderEditorFeedback("已删除此提供者的 API Key。", WorldloomStatusTone.SUCCESS)
                                    } else {
                                        ProviderEditorFeedback("API Key 删除失败。", WorldloomStatusTone.ERROR)
                                    }
                                    saving = false
                                }
                            },
                        )
                    }
                }
                feedback?.let { WorldloomStatusBanner(it.message, it.tone) }
            }
        }
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
private fun ModelSelectionSettings(
    center: ProviderConfigurationCenter,
    sources: List<OpenAiSubscriptionSource>,
    credentialConfigurations: Map<ProviderConfigurationId, CredentialConfiguration>,
    configurations: List<ProviderConfiguration>,
    selectedConfigurationId: ProviderConfigurationId?,
    modelProviderId: ProviderConfigurationId?,
    onModelProviderChanged: (ProviderConfigurationId) -> Unit,
    discoveredModels: MutableMap<ProviderConfigurationId, List<ProviderModelDescriptor>>,
    loading: Boolean,
    onConfigurationUpdated: (ProviderConfiguration) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val source = sources.firstOrNull { it.configurationId == modelProviderId } ?: sources.first()
    val storedConfiguration = configurations.firstOrNull { it.id == source.configurationId }
    val credentialConfiguration = credentialConfigurations.getValue(source.configurationId)
    val credentialState by credentialConfiguration.state.collectAsState()
    val availableModels = discoveredModels[source.configurationId].orEmpty()
    var modelId by remember(source.configurationId, storedConfiguration?.modelId) {
        mutableStateOf(storedConfiguration?.modelId ?: source.modelId)
    }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember(source.configurationId) { mutableStateOf(false) }
    var feedback by remember(source.configurationId) { mutableStateOf<ProviderEditorFeedback?>(null) }
    var busy by remember(source.configurationId) { mutableStateOf(false) }

    LaunchedEffect(credentialConfiguration) { credentialConfiguration.refresh() }

    WorldloomSectionHeading(
        title = "模型",
        subtitle = "先选择提供者，再选择该服务下的模型。应用后，新请求会立即使用这组配置。",
    )
    if (loading) {
        WorldloomStatusBanner("正在读取模型配置…", WorldloomStatusTone.INFO)
        return
    }

    WorldloomPanel(modifier = Modifier.fillMaxWidth(), strong = true) {
        Text("默认模型", color = WorldloomPalette.TextPrimary, fontWeight = FontWeight.SemiBold)
        ExposedDropdownMenuBox(
            expanded = providerMenuExpanded,
            onExpandedChange = { providerMenuExpanded = !providerMenuExpanded },
        ) {
            OutlinedTextField(
                value = source.displayName,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                label = { Text("提供者") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(providerMenuExpanded) },
                singleLine = true,
                readOnly = true,
                enabled = !busy,
            )
            ExposedDropdownMenu(
                expanded = providerMenuExpanded,
                onDismissRequest = { providerMenuExpanded = false },
            ) {
                sources.forEach { option ->
                    DropdownMenuItem(
                        onClick = {
                            providerMenuExpanded = false
                            onModelProviderChanged(option.configurationId)
                        },
                    ) {
                        Column {
                            Text(option.displayName)
                            Text(
                                credentialListStatus(
                                    credentialConfigurations.getValue(option.configurationId).state.value,
                                ),
                                color = WorldloomPalette.TextSecondary,
                                style = MaterialTheme.typography.caption,
                            )
                        }
                    }
                }
            }
        }

        ExposedDropdownMenuBox(
            expanded = modelMenuExpanded,
            onExpandedChange = {
                if (availableModels.isNotEmpty()) modelMenuExpanded = !modelMenuExpanded
            },
        ) {
            OutlinedTextField(
                value = modelId,
                onValueChange = { modelId = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelMenuExpanded) },
                singleLine = true,
                enabled = !busy,
            )
            ExposedDropdownMenu(
                expanded = modelMenuExpanded,
                onDismissRequest = { modelMenuExpanded = false },
            ) {
                availableModels.take(MAX_VISIBLE_MODELS).forEach { model ->
                    DropdownMenuItem(
                        onClick = {
                            modelId = model.id
                            modelMenuExpanded = false
                        },
                    ) {
                        Column {
                            Text(model.displayName)
                            if (model.displayName != model.id) {
                                Text(model.id, color = WorldloomPalette.TextSecondary, style = MaterialTheme.typography.caption)
                            }
                        }
                    }
                }
            }
        }
        Text(
            if (availableModels.isEmpty()) {
                "可直接填写模型 ID，或先从提供者获取模型列表。"
            } else {
                "已获取 ${availableModels.size} 个模型；可从列表选择，也可直接填写模型 ID。"
            },
            color = WorldloomPalette.TextSecondary,
            style = MaterialTheme.typography.caption,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorldloomPrimaryButton(
                label = if (busy) "正在应用…" else "应用模型",
                enabled = !busy && modelId.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val updated = source.configurationWithSelection(
                                storedConfiguration = storedConfiguration,
                                customBaseUrl = storedConfiguration?.baseUrl ?: source.baseUrl,
                                modelId = modelId,
                            )
                            center.upsert(updated)
                            center.select(updated.id)
                            onConfigurationUpdated(updated)
                            feedback = if (credentialState is CredentialConfigurationState.Configured) {
                                ProviderEditorFeedback(
                                    "已使用 ${source.displayName} · ${updated.modelId}。",
                                    WorldloomStatusTone.SUCCESS,
                                )
                            } else {
                                ProviderEditorFeedback(
                                    "模型已选择；请到“提供者”页配置 ${source.displayName} 的 API Key。",
                                    WorldloomStatusTone.WARNING,
                                )
                            }
                        } catch (error: IllegalArgumentException) {
                            feedback = ProviderEditorFeedback(
                                error.message ?: "模型配置无效。",
                                WorldloomStatusTone.ERROR,
                            )
                        } finally {
                            busy = false
                        }
                    }
                },
            )
            WorldloomSecondaryButton(
                label = if (busy) "正在获取…" else "获取模型列表",
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            when (val result = center.discoverModels(source.configurationId)) {
                                is ProviderModelDiscoveryResult.Success -> {
                                    discoveredModels[source.configurationId] = result.models
                                    feedback = ProviderEditorFeedback(
                                        "已获取 ${result.models.size} 个模型。",
                                        WorldloomStatusTone.SUCCESS,
                                    )
                                }
                                is ProviderModelDiscoveryResult.Failure -> {
                                    feedback = ProviderEditorFeedback(result.message, WorldloomStatusTone.ERROR)
                                }
                            }
                        } catch (error: IllegalArgumentException) {
                            feedback = ProviderEditorFeedback(
                                error.message ?: "无法获取模型列表。",
                                WorldloomStatusTone.ERROR,
                            )
                        } finally {
                            busy = false
                        }
                    }
                },
            )
        }

        Text(
            if (source.configurationId == selectedConfigurationId) {
                "当前使用：${source.displayName} · ${storedConfiguration?.modelId ?: source.modelId}"
            } else {
                "当前模型来自其他提供者；点击“应用模型”后切换。"
            },
            color = WorldloomPalette.TextSecondary,
            style = MaterialTheme.typography.caption,
        )
        feedback?.let { WorldloomStatusBanner(it.message, it.tone) }
    }
}

internal fun OpenAiSubscriptionSource.configurationWithProviderSettings(
    storedConfiguration: ProviderConfiguration?,
    customBaseUrl: String,
): ProviderConfiguration = configurationWithSelection(
    storedConfiguration = storedConfiguration,
    customBaseUrl = customBaseUrl,
    modelId = storedConfiguration?.modelId ?: modelId,
)

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

private fun credentialListStatus(state: CredentialConfigurationState): String = when (state) {
    CredentialConfigurationState.Unknown -> "检查中"
    CredentialConfigurationState.Loading -> "检查中"
    CredentialConfigurationState.Configured -> "已配置"
    CredentialConfigurationState.NotConfigured -> "添加密钥"
    is CredentialConfigurationState.Failed -> "状态不可用"
}

private val SETTINGS_NAVIGATION_BREAKPOINT = 720.dp
private val SETTINGS_NAVIGATION_WIDTH = 176.dp
private const val MAX_VISIBLE_MODELS = 50
