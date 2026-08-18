package io.worldloom.platform.credentials

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialVaultTest {
    @Test
    fun secretIsRedactedAndSessionVaultHonorsTheCredentialContract() = runTest {
        val vault = SessionCredentialVault()
        val key = CredentialKey("provider.api-key")
        val secret = SecretValue.create("private-value")

        assertEquals("[REDACTED]", secret.toString())
        assertIs<CredentialReadResult.Failure>(vault.read(key))
        assertIs<CredentialWriteResult.Success>(vault.write(key, secret))
        val loaded = assertIs<CredentialReadResult.Success>(vault.read(key))
        assertEquals("private-value", loaded.secret.access { it })
        assertIs<CredentialWriteResult.Success>(vault.delete(key))
        assertIs<CredentialReadResult.Failure>(vault.read(key))
    }

    @Test
    fun configurationPublishesStatusWithoutExposingTheSecret() = runTest {
        val configuration = CredentialConfiguration(SessionCredentialVault(), CredentialKey("provider.test"))

        configuration.refresh()
        assertIs<CredentialConfigurationState.NotConfigured>(configuration.state.value)
        assertFalse(configuration.configure("  "))
        assertIs<CredentialConfigurationState.Failed>(configuration.state.value)
        assertTrue(configuration.configure("private-value"))
        assertIs<CredentialConfigurationState.Configured>(configuration.state.value)
        assertFalse(configuration.state.value.toString().contains("private-value"))
        assertTrue(configuration.clear())
        assertIs<CredentialConfigurationState.NotConfigured>(configuration.state.value)
    }
}
