package io.worldloom.ui.game

import io.worldloom.agent.runtime.GameAgentHistoryState
import io.worldloom.application.GamePresentation
import io.worldloom.application.PresentedChatSpeakerKind
import io.worldloom.world.NpcDialogueAudience

internal enum class GameChatSpeakerKind { PM, PLAYER, NPC, SYSTEM }

internal data class GameChatMessage(
    val id: String,
    val order: Long,
    val speaker: String,
    val kind: GameChatSpeakerKind,
    val content: String,
    val audienceLabel: String? = null,
    val private: Boolean = false,
)

/** Builds a public group-chat projection without treating presentation prose as world facts. */
internal fun buildGameChatMessages(
    presentation: GamePresentation,
    history: GameAgentHistoryState,
): List<GameChatMessage> {
    val messages = mutableListOf<GameChatMessage>()
    presentation.opening?.let { opening ->
        messages += GameChatMessage(
            id = "opening-premise",
            order = -40,
            speaker = "PM",
            kind = GameChatSpeakerKind.PM,
            content = opening.premise,
        )
        messages += GameChatMessage(
            id = "opening-objective",
            order = -30,
            speaker = "PM",
            kind = GameChatSpeakerKind.PM,
            content = "游戏目标\n${opening.objective}",
        )
        messages += GameChatMessage(
            id = "opening-first-act",
            order = -20,
            speaker = "PM",
            kind = GameChatSpeakerKind.PM,
            content = buildString {
                append(opening.firstActLabel)
                append(" · ")
                append(opening.sceneLabel)
                opening.sceneDescription?.let { append("\n").append(it) }
            },
        )
        opening.npcs.forEachIndexed { index, npc ->
            messages += GameChatMessage(
                id = "opening-npc-${npc.id.value}",
                order = -10 + index.toLong(),
                speaker = npc.displayName,
                kind = GameChatSpeakerKind.NPC,
                content = npc.publicIntroduction ?: "${npc.displayName} 已在本幕登场。",
            )
        }
    }

    presentation.timeline.mapNotNull { event ->
        event.chatMessage?.let { event.sequence to it }
    }.forEach { (sequence, chat) ->
        messages += GameChatMessage(
            id = "event-$sequence",
            order = sequence * ORDER_SCALE + EVENT_OFFSET,
            speaker = chat.speakerName ?: when (chat.speakerKind) {
                PresentedChatSpeakerKind.PLAYER -> "你"
                PresentedChatSpeakerKind.NPC -> "角色"
            },
            kind = when (chat.speakerKind) {
                PresentedChatSpeakerKind.PLAYER -> GameChatSpeakerKind.PLAYER
                PresentedChatSpeakerKind.NPC -> GameChatSpeakerKind.NPC
            },
            content = chat.content,
            audienceLabel = if (chat.audience == NpcDialogueAudience.PRIVATE) {
                buildString {
                    append("# ")
                    append(chat.targetName ?: "私密")
                    chat.communicationLabel?.let { append(" · ").append(it) }
                }
            } else if (chat.speakerKind == PresentedChatSpeakerKind.PLAYER && chat.targetName != null) {
                "@ ${chat.targetName} · 身边可见"
            } else null,
            private = chat.audience == NpcDialogueAudience.PRIVATE,
        )
    }

    history.items.forEach { turn ->
        messages += GameChatMessage(
            id = "turn-${turn.turnId.value}-player",
            order = turn.acceptedSequence * ORDER_SCALE + PLAYER_OFFSET,
            speaker = "你",
            kind = GameChatSpeakerKind.PLAYER,
            content = turn.playerInput,
        )
        turn.publicOutput?.let { output ->
            messages += GameChatMessage(
                id = "turn-${turn.turnId.value}-pm",
                order = (turn.evidence?.throughSequenceInclusive ?: turn.acceptedSequence) * ORDER_SCALE + PM_OFFSET,
                speaker = "PM",
                kind = GameChatSpeakerKind.PM,
                content = output,
            )
        }
        turn.safeFailureMessage?.let { failure ->
            messages += GameChatMessage(
                id = "turn-${turn.turnId.value}-failure",
                order = turn.acceptedSequence * ORDER_SCALE + PM_OFFSET,
                speaker = "系统",
                kind = GameChatSpeakerKind.SYSTEM,
                content = failure,
            )
        }
    }

    return messages.distinctBy(GameChatMessage::id).sortedWith(
        compareBy<GameChatMessage>(GameChatMessage::order).thenBy(GameChatMessage::id),
    )
}

private const val ORDER_SCALE = 10L
private const val PLAYER_OFFSET = 1L
private const val EVENT_OFFSET = 5L
private const val PM_OFFSET = 9L
