package io.worldloom.ui.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.worldloom.application.CharacterCreationFieldPresentation
import io.worldloom.application.CharacterCreationPresentation
import io.worldloom.application.SessionError
import io.worldloom.application.SessionErrorCode
import io.worldloom.application.request
import io.worldloom.content.schema.CharacterCreationMode
import io.worldloom.content.schema.CharacterCreationRequest
import io.worldloom.content.schema.CharacterProfileProblem
import io.worldloom.content.schema.CharacterProfileProblemCode
import io.worldloom.content.schema.CharacterValueAssignment
import io.worldloom.definition.BooleanValue
import io.worldloom.definition.DecimalValue
import io.worldloom.definition.DefinitionReferenceValue
import io.worldloom.definition.IntegerValue
import io.worldloom.definition.TextValue
import io.worldloom.definition.TypedValue

internal data class CharacterCreationFieldUi(
    val label: String,
    val value: String,
)

internal data class CharacterCreationUiModel(
    val worldTitle: String,
    val modeLabel: String,
    val modeDescription: String,
    val optionLabel: String?,
    val fields: List<CharacterCreationFieldUi>,
    val budgetLabel: String?,
    val backgroundPreview: String?,
    val problems: List<String>,
    val canConfirm: Boolean,
)

internal fun CharacterCreationPresentation.toCharacterCreationUiModel(): CharacterCreationUiModel =
    CharacterCreationUiModel(
        worldTitle = worldTitle,
        modeLabel = characterCreationModeLabel(selectedMode),
        modeDescription = characterCreationModeDescription(selectedMode),
        optionLabel = selectedOptionId?.let { selectedId ->
            options.firstOrNull { it.id == selectedId }?.label
        },
        fields = fields.map { CharacterCreationFieldUi(it.label, characterValueLabel(it.value)) },
        budgetLabel = pointBuyBudget?.let { "已分配 $pointsSpent / $it 点" },
        backgroundPreview = narrativeBackground.trim().takeIf(String::isNotEmpty)?.let { background ->
            if (background.length <= BACKGROUND_PREVIEW_LIMIT) background else background.take(BACKGROUND_PREVIEW_LIMIT) + "…"
        },
        problems = problems.map(::characterProblemMessage),
        canConfirm = problems.isEmpty(),
    )

@Composable
internal fun CharacterCreationPage(
    presentation: CharacterCreationPresentation,
    onUpdate: (CharacterCreationRequest) -> Unit,
    onConfirm: () -> Unit,
) {
    val uiModel = remember(presentation) { presentation.toCharacterCreationUiModel() }
    WorldloomFocusedPage(
        title = "创建角色",
        eyebrow = uiModel.worldTitle,
        subtitle = uiModel.modeDescription,
    ) { window ->
        if (window.widthClass == WorldloomWidthClass.COMPACT) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(if (window.short) WorldloomSpacing.Sm else WorldloomSpacing.Md),
            ) {
                presentation.notice?.let { notice ->
                    item("notice") {
                        WorldloomStatusBanner(characterNoticeMessage(notice), WorldloomStatusTone.ERROR)
                    }
                }
                characterEditorItems(presentation, onUpdate)
                item("summary") {
                    CharacterCreationSummary(uiModel, onConfirm, Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Lg),
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(if (window.short) WorldloomSpacing.Sm else WorldloomSpacing.Md),
                ) {
                    presentation.notice?.let { notice ->
                        item("notice") {
                            WorldloomStatusBanner(characterNoticeMessage(notice), WorldloomStatusTone.ERROR)
                        }
                    }
                    characterEditorItems(presentation, onUpdate)
                }
                Column(
                    modifier = Modifier
                        .width(WorldloomDimensions.SummaryWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                ) {
                    CharacterCreationSummary(uiModel, onConfirm, Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.characterEditorItems(
    presentation: CharacterCreationPresentation,
    onUpdate: (CharacterCreationRequest) -> Unit,
) {
    item("mode-title") { CharacterSectionTitle("创建方式", "选择这个世界允许的角色建立方式。") }
    items(presentation.modes, key = { "mode-${it.name}" }) { mode ->
        WorldloomChoiceCard(
            title = characterCreationModeLabel(mode),
            subtitle = characterCreationModeDescription(mode),
            selected = mode == presentation.selectedMode,
            onClick = { onUpdate(presentation.request(mode = mode, optionId = null)) },
        )
    }
    if (presentation.options.isNotEmpty()) {
        item("option-title") { CharacterSectionTitle("角色选择", "选择一个由世界提供的公开角色方案。") }
        items(presentation.options, key = { "option-${it.id.value}" }) { option ->
            WorldloomChoiceCard(
                title = option.label,
                selected = option.id == presentation.selectedOptionId,
                onClick = { onUpdate(presentation.request(optionId = option.id)) },
            )
        }
    }
    item("field-title") { CharacterSectionTitle("角色状态", "这些数值将在确认后写入角色创建命令。") }
    items(presentation.fields, key = { "field-${it.componentId.value}-${it.fieldId.value}" }) { field ->
        CharacterFieldCard(presentation, field, onUpdate)
    }
    if (presentation.selectedMode == CharacterCreationMode.NARRATIVE) {
        item("narrative") {
            WorldloomPanel {
                CharacterSectionTitle("角色背景", "用自然语言描述经历、信念或目标。")
                OutlinedTextField(
                    value = presentation.narrativeBackground,
                    onValueChange = { onUpdate(presentation.request(narrativeBackground = it)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = WorldloomDimensions.NarrativeFieldMinHeight),
                    label = { Text("角色背景") },
                )
            }
        }
    }
}

@Composable
private fun CharacterSectionTitle(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs)) {
        Text(title, color = WorldloomPalette.TextPrimary, style = MaterialTheme.typography.h3)
        Text(description, color = WorldloomPalette.TextSecondary, style = MaterialTheme.typography.body2)
    }
}

@Composable
private fun CharacterFieldCard(
    presentation: CharacterCreationPresentation,
    field: CharacterCreationFieldPresentation,
    onUpdate: (CharacterCreationRequest) -> Unit,
) {
    val fieldValue = field.value
    val minimum = field.minimumInteger
    val maximum = field.maximumInteger
    WorldloomPanel(padding = WorldloomSpacing.Md) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(WorldloomSpacing.Md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs)) {
                Text(field.label, color = WorldloomPalette.TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(
                    characterValueLabel(field.value),
                    color = WorldloomPalette.BrandPrimary,
                    style = MaterialTheme.typography.h3,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (fieldValue is IntegerValue && (minimum != null || maximum != null)) {
                    Text(
                        characterRangeLabel(minimum, maximum),
                        color = WorldloomPalette.TextMuted,
                        style = MaterialTheme.typography.caption,
                    )
                }
            }
            if (presentation.selectedMode == CharacterCreationMode.POINT_BUY && fieldValue is IntegerValue) {
                val current = fieldValue.value
                WorldloomSecondaryButton(
                    label = "−",
                    onClick = { onUpdate(presentation.adjustInteger(field, -1)) },
                    enabled = minimum == null || current > minimum,
                )
                WorldloomPrimaryButton(
                    label = "+",
                    onClick = { onUpdate(presentation.adjustInteger(field, 1)) },
                    enabled = maximum == null || current < maximum,
                )
            } else if (presentation.selectedMode == CharacterCreationMode.POINT_BUY && fieldValue is BooleanValue) {
                WorldloomSecondaryButton(
                    label = if (fieldValue.value) "设为否" else "设为是",
                    onClick = { onUpdate(presentation.withValue(field, BooleanValue(!fieldValue.value))) },
                )
            }
        }
    }
}

@Composable
private fun CharacterCreationSummary(
    model: CharacterCreationUiModel,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WorldloomPanel(modifier = modifier, strong = true) {
        Text("角色摘要", color = WorldloomPalette.BrandPrimary, style = MaterialTheme.typography.h3)
        SummaryRow("创建方式", model.modeLabel)
        model.optionLabel?.let { SummaryRow("角色方案", it) }
        model.fields.forEach { SummaryRow(it.label, it.value) }
        model.budgetLabel?.let { SummaryRow("分配进度", it) }
        model.backgroundPreview?.let { SummaryRow("背景摘要", it) }
        model.problems.forEach { problem ->
            WorldloomStatusBanner(problem, WorldloomStatusTone.WARNING)
        }
        Spacer(Modifier.height(WorldloomSpacing.Xs))
        WorldloomPrimaryButton(
            label = "确认角色并开始游戏",
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            enabled = model.canConfirm,
        )
        if (!model.canConfirm) {
            Text(
                "完成上方要求后即可确认。",
                color = WorldloomPalette.TextSecondary,
                style = MaterialTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(WorldloomSpacing.Xs)) {
        Text(label, color = WorldloomPalette.TextMuted, style = MaterialTheme.typography.caption)
        Text(value, color = WorldloomPalette.TextPrimary, style = MaterialTheme.typography.body2)
    }
}

internal fun characterValueLabel(value: TypedValue): String = when (value) {
    is BooleanValue -> if (value.value) "是" else "否"
    is IntegerValue -> value.value.toString()
    is DecimalValue -> decimalLabel(value)
    is TextValue -> value.value.ifBlank { "未填写" }
    is DefinitionReferenceValue -> "已选择"
}

private fun decimalLabel(value: DecimalValue): String {
    if (value.scale == 0) return value.unscaledValue.toString()
    val raw = value.unscaledValue.toString()
    val negative = raw.startsWith('-')
    val digits = raw.removePrefix("-").padStart(value.scale + 1, '0')
    val whole = digits.dropLast(value.scale)
    val fraction = digits.takeLast(value.scale).trimEnd('0')
    val sign = if (negative) "-" else ""
    return if (fraction.isEmpty()) "$sign$whole" else "$sign$whole.$fraction"
}

private fun characterRangeLabel(minimum: Long?, maximum: Long?): String = when {
    minimum != null && maximum != null -> "范围 $minimum–$maximum"
    minimum != null -> "最低 $minimum"
    maximum != null -> "最高 $maximum"
    else -> ""
}

internal fun characterCreationModeLabel(mode: CharacterCreationMode): String = when (mode) {
    CharacterCreationMode.FIXED -> "固定角色"
    CharacterCreationMode.TEMPLATE -> "角色模板"
    CharacterCreationMode.POINT_BUY -> "点数分配"
    CharacterCreationMode.NARRATIVE -> "叙事背景"
}

internal fun characterCreationModeDescription(mode: CharacterCreationMode): String = when (mode) {
    CharacterCreationMode.FIXED -> "从世界提供的完整角色中选择。"
    CharacterCreationMode.TEMPLATE -> "从预设起点继续塑造角色。"
    CharacterCreationMode.POINT_BUY -> "在允许范围内分配角色能力。"
    CharacterCreationMode.NARRATIVE -> "用背景经历建立角色起点。"
}

internal fun characterProblemMessage(problem: CharacterProfileProblem): String = when (problem.code) {
    CharacterProfileProblemCode.UNSUPPORTED_SCHEMA -> "角色规则版本不受支持。"
    CharacterProfileProblemCode.NO_CREATION_MODE -> "这个世界没有可用的角色创建方式。"
    CharacterProfileProblemCode.DUPLICATE_FIELD -> "角色状态定义出现重复字段。"
    CharacterProfileProblemCode.UNKNOWN_FIELD -> "角色状态引用了不可用的字段。"
    CharacterProfileProblemCode.VALUE_TYPE_MISMATCH -> "有一项角色状态使用了错误的值类型。"
    CharacterProfileProblemCode.INVALID_INTEGER_BOUNDS -> "有一项角色状态的数值范围无效。"
    CharacterProfileProblemCode.MODE_CONFIGURATION_MISSING -> "所选创建方式缺少必要配置。"
    CharacterProfileProblemCode.DUPLICATE_OPTION -> "角色方案出现重复选项。"
    CharacterProfileProblemCode.DUPLICATE_ASSIGNMENT -> "同一角色状态被重复分配。"
    CharacterProfileProblemCode.ASSIGNMENT_OUT_OF_RANGE -> "有一项角色状态超出允许范围。"
}

internal fun characterNoticeMessage(notice: SessionError): String = when (notice.code) {
    SessionErrorCode.CHARACTER_CREATION_REJECTED -> "角色还不能确认，请检查未完成的选择。"
    SessionErrorCode.PERSISTENCE_REJECTED,
    SessionErrorCode.EVENT_STORE_REJECTED,
    -> "角色草稿保存失败，当前输入仍保留在页面中。请重试。"
    else -> "角色创建暂时无法继续，请返回后重试。"
}

private fun CharacterCreationPresentation.adjustInteger(
    field: CharacterCreationFieldPresentation,
    delta: Long,
): CharacterCreationRequest {
    val current = field.value as? IntegerValue ?: return request()
    val adjusted = IntegerValue(
        (current.value + delta).coerceIn(
            field.minimumInteger ?: Long.MIN_VALUE,
            field.maximumInteger ?: Long.MAX_VALUE,
        ),
    )
    return withValue(field, adjusted)
}

private fun CharacterCreationPresentation.withValue(
    selectedField: CharacterCreationFieldPresentation,
    selectedValue: TypedValue,
): CharacterCreationRequest = request(
    values = fields.map { field ->
        CharacterValueAssignment(
            componentId = field.componentId,
            fieldId = field.fieldId,
            value = if (
                field.componentId == selectedField.componentId && field.fieldId == selectedField.fieldId
            ) {
                selectedValue
            } else {
                field.value
            },
        )
    },
)

private const val BACKGROUND_PREVIEW_LIMIT = 160
