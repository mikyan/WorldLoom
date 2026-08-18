package io.worldloom.platform.credentials

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.JvmInline

private val CREDENTIAL_KEY_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9._:-]*$")

@JvmInline
value class CredentialKey(val value: String) {
    init {
        require(CREDENTIAL_KEY_PATTERN.matches(value)) { "CredentialKey must be a stable identifier" }
    }
}

/** A deliberately non-serializable secret whose textual representation is always redacted. */
class SecretValue private constructor(
    private val value: String,
) {
    suspend fun <T> access(block: suspend (String) -> T): T = block(value)

    override fun toString(): String = "[REDACTED]"

    companion object {
        fun create(value: String): SecretValue {
            require(value.isNotBlank()) { "Secret value must not be blank" }
            return SecretValue(value)
        }
    }
}

enum class CredentialVaultErrorCode {
    NOT_FOUND,
    UNSUPPORTED_PLATFORM,
    STORAGE_FAILURE,
    DECRYPTION_FAILURE,
}

data class CredentialVaultError(
    val code: CredentialVaultErrorCode,
    val message: String,
)

sealed interface CredentialReadResult {
    data class Success(val secret: SecretValue) : CredentialReadResult

    data class Failure(val error: CredentialVaultError) : CredentialReadResult
}

sealed interface CredentialWriteResult {
    data object Success : CredentialWriteResult

    data class Failure(val error: CredentialVaultError) : CredentialWriteResult
}

interface CredentialVault {
    suspend fun read(key: CredentialKey): CredentialReadResult

    suspend fun write(
        key: CredentialKey,
        secret: SecretValue,
    ): CredentialWriteResult

    suspend fun delete(key: CredentialKey): CredentialWriteResult
}

/** Safe non-persistent fallback for platforms without an available OS credential store. */
class SessionCredentialVault : CredentialVault {
    private val values = mutableMapOf<CredentialKey, SecretValue>()
    private val mutex = Mutex()

    override suspend fun read(key: CredentialKey): CredentialReadResult = mutex.withLock {
        values[key]?.let(CredentialReadResult::Success)
            ?: CredentialReadResult.Failure(
                CredentialVaultError(CredentialVaultErrorCode.NOT_FOUND, "Credential is not configured"),
            )
    }

    override suspend fun write(
        key: CredentialKey,
        secret: SecretValue,
    ): CredentialWriteResult = mutex.withLock {
        values[key] = secret
        CredentialWriteResult.Success
    }

    override suspend fun delete(key: CredentialKey): CredentialWriteResult = mutex.withLock {
        values.remove(key)
        CredentialWriteResult.Success
    }
}

/** Public credential state intentionally never carries the configured secret. */
sealed interface CredentialConfigurationState {
    data object Unknown : CredentialConfigurationState

    data object Loading : CredentialConfigurationState

    data object Configured : CredentialConfigurationState

    data object NotConfigured : CredentialConfigurationState

    data class Failed(val message: String) : CredentialConfigurationState
}

/** UI-facing credential operations that keep secret reads inside the provider boundary. */
class CredentialConfiguration(
    private val vault: CredentialVault,
    private val key: CredentialKey,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<CredentialConfigurationState>(CredentialConfigurationState.Unknown)

    val state: StateFlow<CredentialConfigurationState> = mutableState.asStateFlow()

    suspend fun refresh() = mutex.withLock {
        mutableState.value = CredentialConfigurationState.Loading
        mutableState.value = when (val result = vault.read(key)) {
            is CredentialReadResult.Success -> CredentialConfigurationState.Configured
            is CredentialReadResult.Failure -> when (result.error.code) {
                CredentialVaultErrorCode.NOT_FOUND -> CredentialConfigurationState.NotConfigured
                else -> CredentialConfigurationState.Failed(result.error.message)
            }
        }
    }

    suspend fun configure(value: String): Boolean = mutex.withLock {
        if (value.isBlank()) {
            mutableState.value = CredentialConfigurationState.Failed("Credential must not be blank")
            return@withLock false
        }
        mutableState.value = CredentialConfigurationState.Loading
        when (val result = vault.write(key, SecretValue.create(value))) {
            CredentialWriteResult.Success -> {
                mutableState.value = CredentialConfigurationState.Configured
                true
            }

            is CredentialWriteResult.Failure -> {
                mutableState.value = CredentialConfigurationState.Failed(result.error.message)
                false
            }
        }
    }

    suspend fun clear(): Boolean = mutex.withLock {
        mutableState.value = CredentialConfigurationState.Loading
        when (val result = vault.delete(key)) {
            CredentialWriteResult.Success -> {
                mutableState.value = CredentialConfigurationState.NotConfigured
                true
            }

            is CredentialWriteResult.Failure -> {
                mutableState.value = CredentialConfigurationState.Failed(result.error.message)
                false
            }
        }
    }
}
