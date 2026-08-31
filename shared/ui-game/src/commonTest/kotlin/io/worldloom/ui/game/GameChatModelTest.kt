package io.worldloom.ui.game

import io.worldloom.agent.runtime.GameAgentHistoryState
import io.worldloom.application.GamePresentation
import io.worldloom.application.PresentedNpc
import io.worldloom.application.PresentedOpening
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
                    ),
                ),
            ),
            GameAgentHistoryState(),
        )

        assertEquals(listOf("PM", "PM", "PM", "向导"), messages.map(GameChatMessage::speaker))
        assertEquals("游戏目标\n找到失踪的队伍。", messages[1].content)
        assertEquals("第一幕 · 雾谷 · 山口\n雾覆盖了唯一的道路。", messages[2].content)
        assertEquals(GameChatSpeakerKind.NPC, messages.last().kind)
    }

    @Test
    fun openingWithoutNpcsDoesNotInventAnIntroduction() {
        val messages = buildGameChatMessages(presentation(emptyList()), GameAgentHistoryState())

        assertEquals(3, messages.size)
        assertEquals(emptyList(), messages.filter { it.kind == GameChatSpeakerKind.NPC })
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
