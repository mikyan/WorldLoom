package io.worldloom.agent.runtime

import io.worldloom.application.GamePresentation
import io.worldloom.rules.QuestStatus

/**
 * Keeps provider-authored narration at the presentation boundary. Stable IDs remain available to
 * tools and replay data, but are never useful prose for the player.
 */
internal fun sanitizePlayerFacingNarration(
    text: String,
    presentation: GamePresentation,
): String {
    var sanitized = text.trim()
    if (sanitized.isEmpty()) return sanitized

    presentation.playerFacingLabels()
        .entries
        .sortedByDescending { it.key.length }
        .forEach { (identifier, label) ->
            sanitized = sanitized.replace(identifier, label)
        }
    sanitized = INTERNAL_IDENTIFIER_PATTERN.replace(sanitized) { match ->
        val prefix = match.groupValues[1]
        val identifier = match.groupValues[2]
        val replacement = identifier.playerFacingFallback() ?: identifier
        prefix + replacement
    }
    sanitized = EVENT_RECORD_ID_PATTERN.replace(sanitized, "剧情记录")
    sanitized = COMMAND_RECORD_ID_PATTERN.replace(sanitized, "规则操作")
    sanitized = EVENT_SEQUENCE_PATTERN.replace(sanitized, "")
    sanitized = sanitized.lineSequence()
        .filterNot { line ->
            val trimmed = line.trim()
            INTERNAL_METADATA_LINE.matches(trimmed) || INTERNAL_WRAPPER_LINE.matches(trimmed)
        }
        .joinToString("\n") { it.trimEnd() }
        .trim()
    return INLINE_METADATA_KEY.replace(sanitized, "").trim()
}

internal fun QuestStatus.playerFacingLabel(): String = when (this) {
    QuestStatus.NOT_STARTED -> "未开始"
    QuestStatus.ACTIVE -> "进行中"
    QuestStatus.COMPLETED -> "已完成"
    QuestStatus.FAILED -> "失败"
}

internal fun HostedTurnHistoryPage.withPlayerFacingNarration(
    presentation: GamePresentation,
): HostedTurnHistoryPage = copy(
    items = items.map { item ->
        item.copy(
            publicOutput = item.publicOutput?.let { sanitizePlayerFacingNarration(it, presentation) },
        )
    },
)

private fun GamePresentation.playerFacingLabels(): Map<String, String> = buildMap {
    put(worldId.value, title)
    scene?.let { currentScene ->
        put(currentScene.id.value, currentScene.label)
        currentScene.actions.forEach { put(it.id.value, it.label) }
    }
    exploration.nodes.forEach { put(it.id.value, it.label) }
    exploration.connections.forEach { put(it.id.value, it.label) }
    exploration.affordances.forEach { put(it.id.value, it.label) }
    characters.forEach { character ->
        put(character.id.value, character.displayName)
        put(character.entityId.value, character.displayName)
        character.remoteCommunicationMethods.forEach { put(it.id.value, it.label) }
    }
    fields.forEach { put(it.presentationId.value, it.label) }
    checks.forEach { put(it.presentationId.value, it.label) }
    activities.forEach { put(it.id.value, it.label) }
    travelRoutes.forEach { put(it.id.value, it.label) }
    adventureState?.let { adventure ->
        adventure.inventory.forEach { put(it.id.value, it.label) }
        adventure.conditions.forEach { put(it.id.value, it.label) }
        adventure.relationships.forEach { put(it.id.value, it.label) }
        adventure.quests.forEach { put(it.id.value, it.label) }
        adventure.clocks.forEach { put(it.id.value, it.label) }
    }
}

private fun String.playerFacingFallback(): String? {
    val segments = lowercase().split('.')
    return when {
        "event" in segments -> "剧情变化"
        "action" in segments -> "该行动"
        "scene" in segments -> "当前场景"
        "outcome" in segments || "choice" in segments -> "本次结果"
        "objective" in segments -> "当前目标"
        "ending" in segments -> "故事结局"
        "activity" in segments -> "该活动"
        "travel" in segments || "route" in segments -> "该路线"
        "trigger" in segments || "schedule" in segments -> "剧情变化"
        "item" in segments -> "该物品"
        "condition" in segments -> "当前状态"
        "relationship" in segments -> "人物关系"
        "quest" in segments || "quest-stage" in segments -> "当前任务"
        "clock" in segments -> "进度"
        "check" in segments || "random" in segments -> "检定结果"
        "npc" in segments -> "角色"
        "communication" in segments -> "通讯方式"
        "actor" in segments || "entity" in segments -> "角色"
        "presentation" in segments || "profile" in segments -> "公开状态"
        "command" in segments || "tool" in segments -> "规则操作"
        else -> null
    }
}

private val INTERNAL_IDENTIFIER_PATTERN = Regex(
    "(^|[^A-Za-z0-9_-])([A-Za-z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_:-]+)+)",
)
private val EVENT_RECORD_ID_PATTERN = Regex("\\b[A-Za-z0-9_-]+-event-\\d+\\b", RegexOption.IGNORE_CASE)
private val COMMAND_RECORD_ID_PATTERN = Regex("\\b[A-Za-z0-9_-]+-command-\\d+\\b", RegexOption.IGNORE_CASE)
private val EVENT_SEQUENCE_PATTERN = Regex("#(\\d+)")
private val INTERNAL_METADATA_LINE = Regex(
    "^(?:[-*]\\s*)?[\"']?(?:事件\\s*(?:编码|代码|id|type|类型)?|event(?:id|type|code)?|actionid|sceneid|outcomeid|commandid|causationid|correlationid|toolname|foregroundresults)[\"']?\\s*[:：=].*$",
    RegexOption.IGNORE_CASE,
)
private val INLINE_METADATA_KEY = Regex(
    "[\"']?(?:eventId|eventType|eventCode|actionId|sceneId|outcomeId|commandId|causationId|correlationId|toolName)[\"']?\\s*[:：=]\\s*",
    RegexOption.IGNORE_CASE,
)
private val INTERNAL_WRAPPER_LINE = Regex("^(?:```(?:json)?|```|[{}\\[\\]],?)$", RegexOption.IGNORE_CASE)
