package io.worldloom.ui.game

import androidx.compose.ui.unit.dp
import io.worldloom.rules.QuestStatus
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameplayStylePresentationTest {
    @Test
    fun `map stays inline only when the shared breakpoint is expanded`() {
        assertFalse(gameplayUsesInlineMap(classifyWorldloomWindow(360.dp, 800.dp)))
        assertFalse(gameplayUsesInlineMap(classifyWorldloomWindow(800.dp, 600.dp)))
        assertTrue(gameplayUsesInlineMap(classifyWorldloomWindow(1_280.dp, 720.dp)))
    }

    @Test
    fun `quest fallback labels never expose enum names`() {
        QuestStatus.entries.forEach { status ->
            val label = questStatusLabel(status)
            assertFalse(label.contains(status.name))
            assertFalse(label.isBlank())
        }
    }

    @Test
    fun `unknown world imagery uses generic fallbacks`() {
        assertEquals(GameplayBackdropAsset.WAR_RUINS, gameplayBackdropAsset("worldloom.background.war-ruins"))
        assertEquals(GameplayBackdropAsset.STATION_CORE, gameplayBackdropAsset("worldloom.background.station-core"))
        assertEquals(GameplayBackdropAsset.GENERIC, gameplayBackdropAsset("future.background.unknown"))
        assertEquals(GameplayBackdropAsset.GENERIC, gameplayBackdropAsset(null))

        assertEquals(GameplayAvatarAsset.WAR_MARA, gameplayAvatarAsset("worldloom.avatar.war-mara"))
        assertEquals(GameplayAvatarAsset.STATION_LYRA, gameplayAvatarAsset("worldloom.avatar.station-lyra"))
        assertEquals(GameplayAvatarAsset.GENERIC, gameplayAvatarAsset("future.avatar.unknown"))
        assertEquals(GameplayAvatarAsset.GENERIC, gameplayAvatarAsset(null))
    }
}
