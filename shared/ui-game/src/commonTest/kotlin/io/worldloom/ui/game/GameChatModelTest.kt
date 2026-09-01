package io.worldloom.ui.game

import io.worldloom.agent.runtime.GameAgentHistoryState
import io.worldloom.application.GamePresentation
import io.worldloom.application.PresentedChatMessage
import io.worldloom.application.PresentedChatSpeakerKind
import io.worldloom.application.PresentedEvent
import io.worldloom.application.PresentedNpc
import io.worldloom.application.PresentedOpening
import io.worldloom.application.PresentedRandomRecord
import io.worldloom.definition.DefinitionId
import io.worldloom.world.EntityId
import kotlin.test.Test
import kotlin.test.assertEquals

class GameChatModelTest {
    @Test
    fun openingIntroducesPremiseGoalFirstActAndOnlyDeclaredNpcs() {
        val messages = buildGameChatMessages(
            presentation(
                listOf(
                    PresentedNpc(
                        DefinitionId("test.npc.guide"),
                        EntityId("guide"),
                        "向导",
                        "向导熟悉这片山谷。",
                        avatarAssetId = "worldloom.avatar.test-guide",
                    ),
                ),
            ),
            GameAgentHistoryState(),
        )

        assertEquals(listOf("PM", "PM", "PM", "向导"), messages.map(GameChatMessage::speaker))
        assertEquals("游戏目标\n找到失踪的队伍。", messages[1].content)
        assertEquals("第一幕 · 雾谷 · 山口\n雾覆盖了唯一的道路。", messages[2].content)
        assertEquals(GameChatSpeakerKind.NPC, messages.last().kind)
        assertEquals("worldloom.avatar.test-guide", messages.last().avatarAssetId)
    }

    @Test
    fun npcTimelineMessagesResolveTheStableCharacterAvatar() {
        val guide = PresentedNpc(
            DefinitionId("test.npc.guide"),
            EntityId("guide"),
            "向导",
            avatarAssetId = "worldloom.avatar.test-guide",
        )
        val current = presentation(emptyList()).copy(
            characters = listOf(guide),
            timeline = listOf(
                PresentedEvent(
                    sequence = 1,
                    summary = "向导回应",
                    chatMessage = PresentedChatMessage(
                        speakerKind = PresentedChatSpeakerKind.NPC,
                        speakerId = guide.entityId.value,
                        content = "跟紧我。",
                    ),
                ),
            ),
        )

        val message = buildGameChatMessages(current, GameAgentHistoryState()).last()

        assertEquals("向导", message.speaker)
        assertEquals("worldloom.avatar.test-guide", message.avatarAssetId)
    }

    @Test
    fun openingWithoutNpcsDoesNotInventAnIntroduction() {
        val messages = buildGameChatMessages(presentation(emptyList()), GameAgentHistoryState())

        assertEquals(3, messages.size)
        assertEquals(emptyList(), messages.filter { it.kind == GameChatSpeakerKind.NPC })
    }

    @Test
    fun emptyPresentationProducesAnEmptyConversation() {
        val messages = buildGameChatMessages(
            GamePresentation(
                worldId = DefinitionId("test.empty-world"),
                title = "空世界",
                lastSequence = 0,
                fields = emptyList(),
                checks = emptyList(),
                timeline = emptyList(),
            ),
            GameAgentHistoryState(),
        )

        assertEquals(emptyList(), messages)
    }

    @Test
    fun auditedDiceResultAppearsAsAPlayerFacingSystemMessage() {
        val current = presentation(emptyList()).copy(
            timeline = listOf(
                PresentedEvent(
                    sequence = 7,
                    summary = "求生检定: 10 · 完整成功",
                    randomRecord = PresentedRandomRecord(
                        recordId = "random.7",
                        results = listOf(4, 6),
                        total = 10,
                        outcomeId = DefinitionId("test.outcome.success"),
                    ),
                ),
            ),
        )

        val roll = buildGameChatMessages(current, GameAgentHistoryState()).last()

        assertEquals(GameChatSpeakerKind.SYSTEM, roll.kind)
        assertEquals("掷骰 4 + 6 = 10 · 求生检定: 10 · 完整成功", roll.content)
    }

    @Test
    fun `long authored text remains available to the scrollable conversation`() {
        val premise = "很久以前，".repeat(180)
        val longPresentation = presentation(emptyList()).copy(
            opening = presentation(emptyList()).opening?.copy(premise = premise),
        )

        val messages = buildGameChatMessages(longPresentation, GameAgentHistoryState())

        assertEquals(premise, messages.first().content)
    }

    private fun presentation(npcs: List<PresentedNpc>) = GamePresentation(
        worldId = DefinitionId("test.world"),
        title = "雾谷",
        lastSequence = 0,
        fields = emptyList(),
        checks = emptyList(),
        timeline = emptyList(),
        opening = PresentedOpening(
            premise = "你在陌生的山谷醒来。",
            objective = "找到失踪的队伍。",
            firstActLabel = "第一幕 · 雾谷",
            sceneLabel = "山口",
            sceneDescription = "雾覆盖了唯一的道路。",
            npcs = npcs,
        ),
    )
}
