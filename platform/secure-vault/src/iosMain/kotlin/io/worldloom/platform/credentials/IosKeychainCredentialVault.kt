@file:OptIn(
    kotlinx.cinterop.BetaInteropApi::class,
    kotlinx.cinterop.ExperimentalForeignApi::class,
)

package io.worldloom.platform.credentials

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/** iOS Keychain-backed credential vault scoped by service and account. */
class IosKeychainCredentialVault(
    private val serviceName: String = "io.worldloom.credentials",
) : CredentialVault {
    override suspend fun read(key: CredentialKey): CredentialReadResult {
        val query = createBaseQuery(key)
        CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
        CFDictionarySetValue(query, kSecMatchLimit, kSecMatchLimitOne)
        return memScoped {
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            CFRelease(query)
            when (status) {
                errSecSuccess -> {
                    val data: CFDataRef = result.value?.reinterpret()
                        ?: return@memScoped CredentialReadResult.Failure(
                            CredentialVaultError(
                                CredentialVaultErrorCode.DECRYPTION_FAILURE,
                                "Credential data has an unexpected format",
                            ),
                        )
                    val bytes = data.toByteArray()
                    CFRelease(data)
                    CredentialReadResult.Success(SecretValue.create(bytes.decodeToString()))
                }

                errSecItemNotFound -> CredentialReadResult.Failure(
                    CredentialVaultError(CredentialVaultErrorCode.NOT_FOUND, "Credential is not configured"),
                )

                else -> CredentialReadResult.Failure(
                    CredentialVaultError(CredentialVaultErrorCode.STORAGE_FAILURE, "Credential could not be read"),
                )
            }
        }
    }

    override suspend fun write(
        key: CredentialKey,
        secret: SecretValue,
    ): CredentialWriteResult {
        val clear = secret.access { it.encodeToByteArray() }
        val data = clear.usePinned { pinned ->
            CFDataCreate(kCFAllocatorDefault, pinned.addressOf(0).reinterpret(), clear.size.toLong())
        } ?: return CredentialWriteResult.Failure(
            CredentialVaultError(CredentialVaultErrorCode.STORAGE_FAILURE, "Credential data could not be created"),
        )
        val deleteQuery = createBaseQuery(key)
        SecItemDelete(deleteQuery)
        CFRelease(deleteQuery)

        val query = createBaseQuery(key)
        CFDictionarySetValue(query, kSecValueData, data)
        val status = SecItemAdd(query, null)
        CFRelease(query)
        CFRelease(data)
        return if (status == errSecSuccess) {
            CredentialWriteResult.Success
        } else {
            CredentialWriteResult.Failure(
                CredentialVaultError(CredentialVaultErrorCode.STORAGE_FAILURE, "Credential could not be stored"),
            )
        }
    }

    override suspend fun delete(key: CredentialKey): CredentialWriteResult {
        val query = createBaseQuery(key)
        val status = SecItemDelete(query)
        CFRelease(query)
        return if (status == errSecSuccess || status == errSecItemNotFound) {
            CredentialWriteResult.Success
        } else {
            CredentialWriteResult.Failure(
                CredentialVaultError(CredentialVaultErrorCode.STORAGE_FAILURE, "Credential could not be deleted"),
            )
        }
    }

    private fun createBaseQuery(key: CredentialKey): CFMutableDictionaryRef = memScoped {
        val query = checkNotNull(
            CFDictionaryCreateMutable(
                kCFAllocatorDefault,
                0,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            ),
        )
        val service = checkNotNull(
            CFStringCreateWithCString(kCFAllocatorDefault, serviceName, kCFStringEncodingUTF8),
        )
        val account = checkNotNull(
            CFStringCreateWithCString(kCFAllocatorDefault, key.value, kCFStringEncodingUTF8),
        )
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(query, kSecAttrService, service)
        CFDictionarySetValue(query, kSecAttrAccount, account)
        CFRelease(service)
        CFRelease(account)
        query
    }
}

private fun CFDataRef.toByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    if (length == 0) return ByteArray(0)
    val source = CFDataGetBytePtr(this)?.reinterpret<ByteVar>() ?: return ByteArray(0)
    return ByteArray(length) { index -> source[index] }
}
