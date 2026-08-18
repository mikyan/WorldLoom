package io.worldloom.platform.credentials

import com.sun.jna.Platform
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class WindowsDpapiCredentialVaultTest {
    @Test
    fun roundTripsOnlyDpapiProtectedCiphertextOnWindows() = runTest {
        if (!Platform.isWindows()) return@runTest
        val directory = Files.createTempDirectory("worldloom-dpapi-test-")
        try {
            val vault = WindowsDpapiCredentialVault(directory)
            val key = CredentialKey("openai.api-key")

            assertIs<CredentialWriteResult.Success>(vault.write(key, SecretValue.create("private-api-key")))
            val storedFile = Files.list(directory).use { files -> files.findFirst().orElseThrow() }
            assertFalse("private-api-key" in storedFile.readText())
            val loaded = assertIs<CredentialReadResult.Success>(vault.read(key))
            assertEquals("private-api-key", loaded.secret.access { it })
            assertIs<CredentialWriteResult.Success>(vault.delete(key))
            assertIs<CredentialReadResult.Failure>(vault.read(key))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
