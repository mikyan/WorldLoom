package io.worldloom.ui.game

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldloomThemeTest {
    @Test
    fun `window classifier uses shared width boundaries`() {
        assertEquals(WorldloomWidthClass.COMPACT, classifyWorldloomWindow(719.dp, 800.dp).widthClass)
        assertEquals(WorldloomWidthClass.MEDIUM, classifyWorldloomWindow(720.dp, 800.dp).widthClass)
        assertEquals(WorldloomWidthClass.MEDIUM, classifyWorldloomWindow(1_199.dp, 800.dp).widthClass)
        assertEquals(WorldloomWidthClass.EXPANDED, classifyWorldloomWindow(1_200.dp, 800.dp).widthClass)
    }

    @Test
    fun `window classifier preserves short height boundary`() {
        assertTrue(classifyWorldloomWindow(800.dp, 619.dp).short)
        assertFalse(classifyWorldloomWindow(800.dp, 620.dp).short)
    }
}
