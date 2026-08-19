@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.dimension.flare.data.network.discourse.session

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecRandomDefault
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

private const val APPLE_REFERENCE_PREFIX = "apple-keychain-v1:"
private const val APPLE_KEYCHAIN_SERVICE = "io.github.zhongjianhui.flaredo.discourse.credentials.v1"
private const val RANDOM_REFERENCE_BYTES = 16
private const val MAX_REFERENCE_ATTEMPTS = 32
private const val MAX_APPLE_CREDENTIAL_BYTES = 128 * 1024

private val appleReferenceIdRegex = Regex("[0-9a-f]{32}")

/** Thrown when Security.framework cannot securely complete a Keychain operation. */
public class AppleCredentialStoreException public constructor(
    message: String,
) : IllegalStateException(message)

/**
 * Apple generic-password Keychain implementation for iOS and macOS.
 *
 * The Keychain service is fixed by the application and each opaque reference becomes only the
 * generic-password account attribute. A reference cannot inject an access group, synchronizable
 * flag, or accessibility policy. Items use a device-only accessibility class and are never marked
 * synchronizable, so Linux.do web-session material does not migrate through iCloud Keychain.
 */
public class AppleKeychainCredentialStore private constructor(
    private val backend: AppleKeychainBackend,
    private val ioDispatcher: CoroutineDispatcher,
    private val randomBytes: () -> ByteArray,
) : SecureCredentialStore {
    /** Creates a store backed by this process's default application Keychain access group. */
    public constructor(
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        backend = AppleSecurityKeychainBackend(APPLE_KEYCHAIN_SERVICE),
        ioDispatcher = ioDispatcher,
        randomBytes = ::secureAppleRandomBytes,
    )

    /** Test seam; production callers cannot alter the fixed Keychain service. */
    internal constructor(
        backend: AppleKeychainBackend,
        ioDispatcher: CoroutineDispatcher,
        randomBytes: () -> ByteArray,
        @Suppress("UNUSED_PARAMETER") testMarker: Unit,
    ) : this(backend, ioDispatcher, randomBytes)

    override suspend fun save(
        accountId: String,
        secret: ByteArray,
    ): SecureCredentialRef {
        requireValidAccountId(accountId)
        require(secret.isNotEmpty()) { "Credential bytes must not be empty" }
        require(secret.size <= MAX_APPLE_CREDENTIAL_BYTES) {
            "Credential bytes exceed the Apple Keychain limit"
        }
        val ownedSecret = secret.copyOf()
        try {
            return withContext(ioDispatcher) {
                repeat(MAX_REFERENCE_ATTEMPTS) {
                    val reference = newAppleReference(randomBytes())
                    when (backend.add(reference.appleReferenceId(), ownedSecret)) {
                        AppleKeychainStatus.SUCCESS -> return@withContext reference

                        AppleKeychainStatus.DUPLICATE -> Unit

                        AppleKeychainStatus.NOT_FOUND,
                        AppleKeychainStatus.UNAVAILABLE,
                        -> throw AppleCredentialStoreException("Apple Keychain could not store the credential")
                    }
                }
                throw AppleCredentialStoreException("Unable to allocate a unique Keychain reference")
            }
        } finally {
            ownedSecret.fill(0)
        }
    }

    override suspend fun load(reference: SecureCredentialRef): ByteArray? =
        withContext(ioDispatcher) {
            when (val result = backend.load(reference.appleReferenceId())) {
                is AppleKeychainLoadResult.Found -> {
                    val value = result.value
                    try {
                        if (value.isEmpty() || value.size > MAX_APPLE_CREDENTIAL_BYTES) {
                            throw AppleCredentialStoreException("Apple Keychain returned invalid credential data")
                        }
                        value.copyOf()
                    } finally {
                        value.fill(0)
                    }
                }

                AppleKeychainLoadResult.Missing -> {
                    null
                }

                AppleKeychainLoadResult.Unavailable -> {
                    throw AppleCredentialStoreException("Apple Keychain could not load the credential")
                }
            }
        }

    override suspend fun delete(reference: SecureCredentialRef): Unit =
        withContext(ioDispatcher) {
            when (backend.delete(reference.appleReferenceId())) {
                AppleKeychainStatus.SUCCESS,
                AppleKeychainStatus.NOT_FOUND,
                -> Unit

                AppleKeychainStatus.DUPLICATE,
                AppleKeychainStatus.UNAVAILABLE,
                -> throw AppleCredentialStoreException("Apple Keychain could not delete the credential")
            }
        }
}

internal enum class AppleKeychainStatus {
    SUCCESS,
    DUPLICATE,
    NOT_FOUND,
    UNAVAILABLE,
}

internal sealed interface AppleKeychainLoadResult {
    data class Found(
        val value: ByteArray,
    ) : AppleKeychainLoadResult

    data object Missing : AppleKeychainLoadResult

    data object Unavailable : AppleKeychainLoadResult
}

internal interface AppleKeychainBackend {
    fun add(
        referenceId: String,
        secret: ByteArray,
    ): AppleKeychainStatus

    fun load(referenceId: String): AppleKeychainLoadResult

    fun delete(referenceId: String): AppleKeychainStatus
}

private class AppleSecurityKeychainBackend(
    private val service: String,
) : AppleKeychainBackend {
    override fun add(
        referenceId: String,
        secret: ByteArray,
    ): AppleKeychainStatus {
        val valueData =
            secret.usePinned { pinned ->
                CFDataCreate(
                    kCFAllocatorDefault,
                    pinned.addressOf(0).reinterpret(),
                    secret.size.convert(),
                )
            } ?: return AppleKeychainStatus.UNAVAILABLE
        val query = baseQuery(referenceId)
        return try {
            CFDictionaryAddValue(query, kSecValueData, valueData)
            CFDictionaryAddValue(
                query,
                kSecAttrAccessible,
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            )
            SecItemAdd(query, null).toAppleKeychainStatus()
        } finally {
            CFRelease(query)
            CFRelease(valueData)
        }
    }

    override fun load(referenceId: String): AppleKeychainLoadResult =
        memScoped {
            val query = baseQuery(referenceId)
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
            val result = alloc<CFTypeRefVar>()
            val status = SecItemCopyMatching(query, result.ptr)
            CFRelease(query)

            if (status == errSecItemNotFound) {
                result.value?.let(::CFRelease)
                return@memScoped AppleKeychainLoadResult.Missing
            }
            if (status != errSecSuccess) {
                result.value?.let(::CFRelease)
                return@memScoped AppleKeychainLoadResult.Unavailable
            }

            val data: CFDataRef =
                result.value?.reinterpret()
                    ?: return@memScoped AppleKeychainLoadResult.Unavailable
            try {
                val length = CFDataGetLength(data).toInt()
                if (length <= 0 || length > MAX_APPLE_CREDENTIAL_BYTES) {
                    return@memScoped AppleKeychainLoadResult.Unavailable
                }
                val source =
                    CFDataGetBytePtr(data)
                        ?: return@memScoped AppleKeychainLoadResult.Unavailable
                val bytes = ByteArray(length)
                for (index in bytes.indices) bytes[index] = source[index].toByte()
                AppleKeychainLoadResult.Found(bytes)
            } finally {
                CFRelease(data)
            }
        }

    override fun delete(referenceId: String): AppleKeychainStatus {
        val query = baseQuery(referenceId)
        return try {
            SecItemDelete(query).toAppleKeychainStatus()
        } finally {
            CFRelease(query)
        }
    }

    private fun baseQuery(referenceId: String): CFMutableDictionaryRef {
        val query =
            checkNotNull(
                CFDictionaryCreateMutable(
                    kCFAllocatorDefault,
                    0,
                    kCFTypeDictionaryKeyCallBacks.ptr,
                    kCFTypeDictionaryValueCallBacks.ptr,
                ),
            )
        CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
        addString(query, kSecAttrService, service)
        addString(query, kSecAttrAccount, referenceId)
        return query
    }

    private fun addString(
        query: CFMutableDictionaryRef,
        key: CFStringRef?,
        value: String,
    ) {
        val string =
            CFStringCreateWithCString(
                kCFAllocatorDefault,
                value,
                kCFStringEncodingUTF8,
            ) ?: throw AppleCredentialStoreException("Apple Keychain attribute encoding failed")
        try {
            CFDictionaryAddValue(query, key, string)
        } finally {
            CFRelease(string)
        }
    }
}

private fun Int.toAppleKeychainStatus(): AppleKeychainStatus =
    when (this) {
        errSecSuccess -> AppleKeychainStatus.SUCCESS
        errSecDuplicateItem -> AppleKeychainStatus.DUPLICATE
        errSecItemNotFound -> AppleKeychainStatus.NOT_FOUND
        else -> AppleKeychainStatus.UNAVAILABLE
    }

private fun secureAppleRandomBytes(): ByteArray {
    val bytes = ByteArray(RANDOM_REFERENCE_BYTES)
    val status =
        bytes.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, bytes.size.convert(), pinned.addressOf(0))
        }
    if (status != errSecSuccess) {
        bytes.fill(0)
        throw AppleCredentialStoreException("Apple secure random source is unavailable")
    }
    return bytes
}

private fun newAppleReference(randomBytes: ByteArray): SecureCredentialRef {
    require(randomBytes.size == RANDOM_REFERENCE_BYTES) {
        "An Apple credential reference requires exactly $RANDOM_REFERENCE_BYTES random bytes"
    }
    val id = randomBytes.toHexString()
    randomBytes.fill(0)
    return SecureCredentialRef(APPLE_REFERENCE_PREFIX + id)
}

private fun SecureCredentialRef.appleReferenceId(): String {
    require(value.startsWith(APPLE_REFERENCE_PREFIX)) {
        "Credential reference belongs to another vault"
    }
    val id = value.removePrefix(APPLE_REFERENCE_PREFIX)
    require(appleReferenceIdRegex.matches(id)) { "Credential reference has an invalid format" }
    return id
}

private fun ByteArray.toHexString(): String =
    buildString(size * 2) {
        this@toHexString.forEach { value ->
            val unsigned = value.toInt() and 0xff
            append("0123456789abcdef"[unsigned ushr 4])
            append("0123456789abcdef"[unsigned and 0x0f])
        }
    }
