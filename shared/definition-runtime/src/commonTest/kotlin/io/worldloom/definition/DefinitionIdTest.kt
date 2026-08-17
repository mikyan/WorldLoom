package io.worldloom.definition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DefinitionIdTest {
    @Test
    fun acceptsNamespacedStableIdentifier() {
        assertEquals("station.energy", DefinitionId("station.energy").value)
        assertEquals("contract.war-survival", DefinitionId("contract.war-survival").value)
    }

    @Test
    fun rejectsUnnamespacedOrUnstableIdentifier() {
        assertFailsWith<IllegalArgumentException> { DefinitionId("health") }
        assertFailsWith<IllegalArgumentException> { DefinitionId("War.Health") }
        assertFailsWith<IllegalArgumentException> { DefinitionId("war health") }
    }
}
