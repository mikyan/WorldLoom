package io.worldloom.platform.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android credential vault using a non-exportable Keystore key and AES-GCM ciphertext. */
class AndroidKeystoreCredentialVault(
    context: Context,
    private val serviceName: String = "io.worldloom.credentials",
) : CredentialVault {
    private val preferences = context.applicationContext.getSharedPreferences(serviceName, Context.MODE_PRIVATE)

    override suspend fun read(key: CredentialKey): CredentialReadResult {
        val encoded = preferences.getString(key.value, null)
            ?: return CredentialReadResult.Failure(
                CredentialVaultError(CredentialVaultErrorCode.NOT_FOUND, "Credential is not configured"),
            )
        return try {
            val parts = encoded.split(':', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                existingKey(alias(key)),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)),
            )
            val clear = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
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
    ): CredentialWriteResult = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, existingKey(alias(key)) ?: createKey(alias(key)))
        val encrypted = secret.access { value ->
            cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        }
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        if (preferences.edit().putString(key.value, encoded).commit()) {
            CredentialWriteResult.Success
        } else {
            CredentialWriteResult.Failure(
                CredentialVaultError(CredentialVaultErrorCode.STORAGE_FAILURE, "Credential could not be stored"),
            )
        }
    } catch (_: Exception) {
        CredentialWriteResult.Failure(
            CredentialVaultError(CredentialVaultErrorCode.STORAGE_FAILURE, "Credential could not be stored"),
        )
    }

    override suspend fun delete(key: CredentialKey): CredentialWriteResult = try {
        val preferencesDeleted = preferences.edit().remove(key.value).commit()
        val keyStore = keyStore()
        if (keyStore.containsAlias(alias(key))) keyStore.deleteEntry(alias(key))
        if (preferencesDeleted) {
            CredentialWriteResult.Success
        } else {
            CredentialWriteResult.Failure(
                CredentialVaultError(CredentialVaultErrorCode.STORAGE_FAILURE, "Credential could not be deleted"),
            )
        }
    } catch (_: Exception) {
        CredentialWriteResult.Failure(
            CredentialVaultError(CredentialVaultErrorCode.STORAGE_FAILURE, "Credential could not be deleted"),
        )
    }

    private fun existingKey(alias: String): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun createKey(alias: String): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun alias(key: CredentialKey): String = "$serviceName.${key.value}"

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
