package io.worldloom.platform.credentials

import com.sun.jna.Platform
import com.sun.jna.platform.win32.Crypt32Util
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

fun createDesktopCredentialVault(directory: Path): CredentialVault =
    if (Platform.isWindows()) WindowsDpapiCredentialVault(directory) else SessionCredentialVault()

/** Windows credential vault backed by user-scoped DPAPI; only encrypted bytes are persisted. */
class WindowsDpapiCredentialVault(
    private val directory: Path,
) : CredentialVault {
    override suspend fun read(key: CredentialKey): CredentialReadResult {
        if (!Platform.isWindows()) return unsupported()
        val path = pathFor(key)
        if (!path.exists()) {
            return CredentialReadResult.Failure(
                CredentialVaultError(CredentialVaultErrorCode.NOT_FOUND, "Credential is not configured"),
            )
        }
        return try {
            val encrypted = Base64.getDecoder().decode(path.readText(StandardCharsets.US_ASCII))
            val clear = Crypt32Util.cryptUnprotectData(encrypted)
            CredentialReadResult.Success(SecretValue.create(clear.toString(StandardCharsets.UTF_8)))
        } catch (_: Exception) {
            CredentialReadResult.Failure(
                CredentialVaultError(CredentialVaultErrorCode.DECRYPTION_FAILURE, "Credential could not be decrypted"),
            )
        }
    }

    override suspend fun write(
        key: CredentialKey,
        secret: SecretValue,
    ): CredentialWriteResult {
        if (!Platform.isWindows()) return unsupportedWrite()
        return try {
            Files.createDirectories(directory)
            val encoded = secret.access { value ->
                Base64.getEncoder().encodeToString(
                    Crypt32Util.cryptProtectData(value.toByteArray(StandardCharsets.UTF_8)),
                )
            }
            val target = pathFor(key)
            val temporary = directory.resolve("${target.fileName}.tmp")
            temporary.writeText(encoded, StandardCharsets.US_ASCII)
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
            CredentialWriteResult.Success
        } catch (_: Exception) {
            CredentialWriteResult.Failure(
                CredentialVaultError(CredentialVaultErrorCode.STORAGE_FAILURE, "Credential could not be stored"),
            )
        }
    }

    override suspend fun delete(key: CredentialKey): CredentialWriteResult {
        if (!Platform.isWindows()) return unsupportedWrite()
        return try {
            Files.deleteIfExists(pathFor(key))
            CredentialWriteResult.Success
        } catch (_: Exception) {
            CredentialWriteResult.Failure(
                CredentialVaultError(CredentialVaultErrorCode.STORAGE_FAILURE, "Credential could not be deleted"),
            )
        }
    }

    private fun pathFor(key: CredentialKey): Path {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.value.toByteArray(StandardCharsets.UTF_8))
        val name = digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        return directory.resolve("$name.credential")
    }

    private fun unsupported(): CredentialReadResult.Failure = CredentialReadResult.Failure(
        CredentialVaultError(CredentialVaultErrorCode.UNSUPPORTED_PLATFORM, "Windows DPAPI is not available"),
    )

    private fun unsupportedWrite(): CredentialWriteResult.Failure = CredentialWriteResult.Failure(
        CredentialVaultError(CredentialVaultErrorCode.UNSUPPORTED_PLATFORM, "Windows DPAPI is not available"),
    )
}
