package io.worldloom.ui.game

import io.worldloom.platform.credentials.CredentialConfigurationState
import io.worldloom.provider.api.ProviderConfigurationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeFlowModelTest {
    @Test
    fun dreamCarouselWrapsInBothDirections() {
        assertEquals(1, nextDreamIndex(currentIndex = 0, itemCount = 2, direction = 1))
        assertEquals(0, nextDreamIndex(currentIndex = 1, itemCount = 2, direction = 1))
        assertEquals(1, nextDreamIndex(currentIndex = 0, itemCount = 2, direction = -1))
        assertEquals(0, nextDreamIndex(currentIndex = 1, itemCount = 2, direction = -1))
    }

    @Test
    fun providerGateRequiresSelectedConfiguredCredential() {
        val selected = ProviderConfigurationId("provider.ready")

        assertTrue(providerEntryReady(selected, CredentialConfigurationState.Configured))
        assertFalse(providerEntryReady(null, CredentialConfigurationState.Configured))
        assertFalse(providerEntryReady(selected, CredentialConfigurationState.NotConfigured))
        assertFalse(providerEntryReady(selected, CredentialConfigurationState.Loading))
    }
}
