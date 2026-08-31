package io.worldloom.ui.game

import io.worldloom.application.PresentedCommunicationMethod
import io.worldloom.application.PresentedNpc
import io.worldloom.definition.DefinitionId
import io.worldloom.world.EntityId
import io.worldloom.world.NpcDialogueAudience
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChatAddressingTest {
    private val nearby = PresentedNpc(
        id = DefinitionId("test.npc.nearby"),
        entityId = EntityId("nearby"),
        displayName = "近处角色",
        nearby = true,
    )
    private val remote = PresentedNpc(
        id = DefinitionId("test.npc.remote"),
        entityId = EntityId("remote"),
        displayName = "远程角色",
        remoteCommunicationMethods = listOf(
            PresentedCommunicationMethod(DefinitionId("test.communication.radio"), "电台"),
        ),
    )

    @Test
    fun atIsNearbyGroupSpeechAndHashIsPrivate() {
        val group = assertIs<ParsedChatInput.ToNpc>(parseChatInput("@近处角色  大家听我说", listOf(nearby, remote)))
        assertEquals(NpcDialogueAudience.NEARBY_GROUP, group.audience)
        assertEquals("大家听我说", group.content)

        val private = assertIs<ParsedChatInput.ToNpc>(parseChatInput("#近处角色 只告诉你", listOf(nearby, remote)))
        assertEquals(NpcDialogueAudience.PRIVATE, private.audience)
        assertEquals(null, private.communicationMethodId)
    }

    @Test
    fun remoteHashUsesAuthoredSharedCommunicationMethod() {
        val private = assertIs<ParsedChatInput.ToNpc>(parseChatInput("#远程角色 收到吗", listOf(nearby, remote)))

        assertEquals(NpcDialogueAudience.PRIVATE, private.audience)
        assertEquals(DefinitionId("test.communication.radio"), private.communicationMethodId)
        assertIs<ParsedChatInput.Invalid>(parseChatInput("@远程角色 大家听我说", listOf(nearby, remote)))
    }

    @Test
    fun plainTextAndAtPmRemainPmInput() {
        assertEquals(ParsedChatInput.ToPm("查看门锁"), parseChatInput("查看门锁", listOf(nearby)))
        assertEquals(ParsedChatInput.ToPm("介绍局势"), parseChatInput("@PM 介绍局势", listOf(nearby)))
    }
}
