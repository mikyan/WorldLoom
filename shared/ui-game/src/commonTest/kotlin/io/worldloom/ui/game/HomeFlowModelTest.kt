package io.worldloom.ui.game

import io.worldloom.platform.credentials.CredentialConfigurationState
import io.worldloom.provider.api.ProviderConfigurationId
import io.worldloom.application.SessionError
import io.worldloom.application.SessionErrorCode
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

    @Test
    fun homeErrorsStayPlayerFacingAndDoNotExposePersistentIds() {
        val error = SessionError(
            SessionErrorCode.PERSISTENCE_REJECTED,
            "Run already exists: local.run.1",
        )

        val message = error.homeMessage()

        assertEquals("存档无法初始化或恢复，请返回首页后重试。", message)
        assertFalse(message.contains("local.run"))
    }
}
