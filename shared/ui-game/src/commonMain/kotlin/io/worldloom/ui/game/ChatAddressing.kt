package io.worldloom.ui.game

import io.worldloom.application.PresentedNpc
import io.worldloom.definition.DefinitionId
import io.worldloom.world.NpcDialogueAudience

internal sealed interface ParsedChatInput {
    data class ToPm(val content: String) : ParsedChatInput

    data class ToNpc(
        val npc: PresentedNpc,
        val content: String,
        val audience: NpcDialogueAudience,
        val communicationMethodId: DefinitionId? = null,
    ) : ParsedChatInput

    data class Invalid(val message: String) : ParsedChatInput
}

internal fun parseChatInput(raw: String, characters: List<PresentedNpc>): ParsedChatInput {
    val input = raw.trim()
    if (input.isEmpty()) return ParsedChatInput.Invalid("请输入对话或行动。")
    val marker = input.first()
    if (marker != '@' && marker != '#') return ParsedChatInput.ToPm(input)
    val addressed = input.drop(1).trimStart()
    val pmPrefix = "PM"
    if (addressed == pmPrefix || addressed.startsWith("$pmPrefix ")) {
        val content = addressed.removePrefix(pmPrefix).trim()
        return if (content.isEmpty()) ParsedChatInput.Invalid("请在 @PM 后输入内容。") else ParsedChatInput.ToPm(content)
    }
    val npc = characters.sortedByDescending { it.displayName.length }
        .firstOrNull { candidate ->
            addressed == candidate.displayName || addressed.startsWith("${candidate.displayName} ") ||
                addressed == candidate.id.value || addressed.startsWith("${candidate.id.value} ")
        } ?: return ParsedChatInput.Invalid("没有找到该角色；请从右侧角色清单选择。")
    val matchedName = if (addressed.startsWith(npc.displayName)) npc.displayName else npc.id.value
    val content = addressed.removePrefix(matchedName).trim()
    if (content.isEmpty()) return ParsedChatInput.Invalid("请在角色名后输入对话内容。")
    return when (marker) {
        '@' -> if (npc.nearby) {
            ParsedChatInput.ToNpc(npc, content, NpcDialogueAudience.NEARBY_GROUP)
        } else {
            ParsedChatInput.Invalid("@ 公开对话只能发送给玩家身边的角色。")
        }
        else -> when {
            npc.nearby -> ParsedChatInput.ToNpc(npc, content, NpcDialogueAudience.PRIVATE)
            npc.remoteCommunicationMethods.isNotEmpty() -> ParsedChatInput.ToNpc(
                npc,
                content,
                NpcDialogueAudience.PRIVATE,
                npc.remoteCommunicationMethods.first().id,
            )
            else -> ParsedChatInput.Invalid("该角色不在身边，且双方没有可用的远程通讯手段。")
        }
    }
}
