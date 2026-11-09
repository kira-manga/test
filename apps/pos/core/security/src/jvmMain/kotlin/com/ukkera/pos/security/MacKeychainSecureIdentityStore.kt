package com.ukkera.pos.security

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import dev.p2pkit.core.security.JvmSecureIdentityStore
import java.security.MessageDigest
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * Official SDK opaque identity storage in the macOS data-protection Keychain. Atomic
 * SecItemAdd arbitrates concurrent creators across processes; no password or adjacent file key
 * is used. The signed host must have genuine access to its default Keychain access group.
 * A missing entitlement, locked Keychain or denied access throws; only errSecItemNotFound is
 * absence. This store supplies neither installed-app authority nor hardware-protection proof.
 *
 * Namespace strings (including the SDK's separate reset marker) select distinct items. The
 * service is fixed, items do not synchronize or migrate, and calls never display an auth prompt.
 */
public class MacKeychainSecureIdentityStore private constructor() : JvmSecureIdentityStore {
    override fun read(namespace: String): ByteArray? = query(namespace) { query ->
        query.put("kSecReturnData", MacSecurityNative.constant("kCFBooleanTrue", core = true))
        query.put("kSecReturnAttributes", MacSecurityNative.constant("kCFBooleanTrue", core = true))
        query.put("kSecMatchLimit", MacSecurityNative.constant("kSecMatchLimitOne"))
        val result = PointerByReference()
        val status = MacSecurityNative.security.SecItemCopyMatching(query.dictionary, result)
        val value = result.value
        try {
            when (status) {
                NOT_FOUND -> null
                SUCCESS -> {
                    check(value != null && MacSecurityNative.cf.CFGetTypeID(value) ==
                        MacSecurityNative.cf.CFDictionaryGetTypeID()) { "Keychain identity has invalid native attributes" }
                    val accessibility = checkNotNull(MacSecurityNative.cf.CFDictionaryGetValue(value,
                        MacSecurityNative.constant("kSecAttrAccessible")))
                    check(MacSecurityNative.cf.CFEqual(accessibility,
                        MacSecurityNative.constant("kSecAttrAccessibleWhenUnlockedThisDeviceOnly")) != 0.toByte()) {
                        "Keychain identity protection differs"
                    }
                    val data = checkNotNull(MacSecurityNative.cf.CFDictionaryGetValue(value,
                        MacSecurityNative.constant("kSecValueData")))
                    check(MacSecurityNative.cf.CFGetTypeID(data) == MacSecurityNative.cf.CFDataGetTypeID()) {
                        "Keychain identity has an invalid native data type"
                    }
                    val count = MacSecurityNative.cf.CFDataGetLength(data).toLong()
                    check(count in 0..Int.MAX_VALUE.toLong()) { "Keychain identity exceeds JVM capacity" }
                    if (count == 0L) ByteArray(0) else checkNotNull(
                        MacSecurityNative.cf.CFDataGetBytePtr(data)).getByteArray(0, count.toInt())
                }
                else -> error("Keychain identity read failed ($status)")
            }
        } finally {
            if (value != null) MacSecurityNative.cf.CFRelease(value)
        }
    }

    override fun putIfAbsent(namespace: String, value: ByteArray): ByteArray {
        val snapshot = value.copyOf()
        try {
            val status = query(namespace) { query ->
                query.put("kSecAttrAccessible", MacSecurityNative.constant("kSecAttrAccessibleWhenUnlockedThisDeviceOnly"))
                // Memory passed to the native allocator is cleared even when SecItemAdd fails.
                Memory(maxOf(1, snapshot.size).toLong()).use { memory ->
                    if (snapshot.isNotEmpty()) memory.write(0, snapshot, 0, snapshot.size)
                    try {
                        val data = checkNotNull(MacSecurityNative.cf.CFDataCreate(null, memory,
                            NativeLong(snapshot.size.toLong())))
                        try {
                            query.put("kSecValueData", data)
                            MacSecurityNative.security.SecItemAdd(query.dictionary, null)
                        } finally { MacSecurityNative.cf.CFRelease(data) }
                    } finally { memory.clear() }
                }
            }
            check(status == SUCCESS || status == DUPLICATE) { "Keychain identity creation failed ($status)" }
            val winner = checkNotNull(read(namespace)) { "Keychain identity disappeared during creation" }
            if (status == SUCCESS && !MessageDigest.isEqual(snapshot, winner)) {
                winner.fill(0)
                error("Keychain identity creation readback differs")
            }
            return winner
        } finally { snapshot.fill(0) }
    }

    override fun delete(namespace: String): Boolean = query(namespace) { query ->
        when (val status = MacSecurityNative.security.SecItemDelete(query.dictionary)) {
            SUCCESS -> true
            NOT_FOUND -> false
            else -> error("Keychain identity deletion failed ($status)")
        }
    }

    private inline fun <T> query(namespace: String, block: (Query) -> T): T {
        require(namespace.isNotEmpty())
        // Hash only the lookup key. SDK values remain opaque and unmodified.
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(namespace))
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(encoded)
        val account = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return Query().use { query ->
            query.put("kSecClass", MacSecurityNative.constant("kSecClassGenericPassword"))
            query.text("kSecAttrService", "com.ukkera.pos.sdk.identity.v1")
            query.text("kSecAttrAccount", account)
            query.put("kSecUseDataProtectionKeychain", MacSecurityNative.constant("kCFBooleanTrue", core = true))
            query.put("kSecAttrSynchronizable", MacSecurityNative.constant("kCFBooleanFalse", core = true))
            query.put("kSecUseAuthenticationUI", MacSecurityNative.constant("kSecUseAuthenticationUIFail"))
            block(query)
        }
    }

    private class Query : AutoCloseable {
        val dictionary: Pointer = checkNotNull(MacSecurityNative.cf.CFDictionaryCreateMutable(null,
            NativeLong(0), MacSecurityNative.core.getGlobalVariableAddress("kCFTypeDictionaryKeyCallBacks"),
            MacSecurityNative.core.getGlobalVariableAddress("kCFTypeDictionaryValueCallBacks")))
        fun put(key: String, value: Pointer) {
            MacSecurityNative.cf.CFDictionarySetValue(dictionary, MacSecurityNative.constant(key), value)
        }
        fun text(key: String, value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val string = checkNotNull(MacSecurityNative.cf.CFStringCreateWithBytes(null, bytes,
                NativeLong(bytes.size.toLong()), 0x08000100, 0))
            try { put(key, string) } finally { MacSecurityNative.cf.CFRelease(string) }
        }
        override fun close() { MacSecurityNative.cf.CFRelease(dictionary) }
    }

    public companion object {
        /** Opens the real platform API; unsupported/unsigned hosts receive no file fallback. */
        public fun open(): MacKeychainSecureIdentityStore {
            check(Platform.isMac()) { "macOS Keychain is required" }
            check(Native.POINTER_SIZE == 8) { "A 64-bit macOS host is required" }
            return MacKeychainSecureIdentityStore()
        }
        private const val SUCCESS = 0
        private const val NOT_FOUND = -25300
        private const val DUPLICATE = -25299
    }
}

internal object MacSecurityNative {
    val core: NativeLibrary = NativeLibrary.getInstance("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
    private val securityLibrary = NativeLibrary.getInstance("/System/Library/Frameworks/Security.framework/Security")
    val cf: KeychainCoreFoundation = Native.load(core.name, KeychainCoreFoundation::class.java)
    val security: KeychainSecurity = Native.load(securityLibrary.name, KeychainSecurity::class.java)
    fun constant(name: String, core: Boolean = false): Pointer = checkNotNull(
        (if (core) this.core else securityLibrary).getGlobalVariableAddress(name).getPointer(0))
}

internal interface KeychainSecurity : Library {
    fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int
    fun SecItemAdd(attributes: Pointer, result: PointerByReference?): Int
    fun SecItemDelete(query: Pointer): Int
    fun SecCodeCopySelf(flags: Int, result: PointerByReference): Int
    fun SecCodeCopyStaticCode(code: Pointer, flags: Int, result: PointerByReference): Int
    fun SecCodeCopySigningInformation(code: Pointer, flags: Int, result: PointerByReference): Int
    fun SecCodeCheckValidity(code: Pointer, flags: Int, requirement: Pointer): Int
    fun SecRequirementCreateWithString(text: Pointer, flags: Int, result: PointerByReference): Int
    fun SecRequirementCopyString(requirement: Pointer, flags: Int, result: PointerByReference): Int
    fun SecCodeCopyDesignatedRequirement(code: Pointer, flags: Int, result: PointerByReference): Int
    fun SecRequirementCopyData(requirement: Pointer, flags: Int, result: PointerByReference): Int
}

internal interface KeychainCoreFoundation : Library {
    fun CFDictionaryCreateMutable(allocator: Pointer?, capacity: NativeLong,
        keyCallbacks: Pointer, valueCallbacks: Pointer): Pointer?
    fun CFDictionarySetValue(dictionary: Pointer, key: Pointer, value: Pointer)
    fun CFDictionaryGetValue(dictionary: Pointer, key: Pointer): Pointer?
    fun CFDictionaryGetTypeID(): NativeLong
    fun CFEqual(first: Pointer, second: Pointer): Byte
    fun CFStringCreateWithBytes(allocator: Pointer?, bytes: ByteArray, count: NativeLong,
        encoding: Int, externalRepresentation: Byte): Pointer?
    fun CFDataCreate(allocator: Pointer?, bytes: Pointer, count: NativeLong): Pointer?
    fun CFDataGetLength(data: Pointer): NativeLong
    fun CFDataGetBytePtr(data: Pointer): Pointer?
    fun CFGetTypeID(value: Pointer): NativeLong
    fun CFDataGetTypeID(): NativeLong
    fun CFStringGetTypeID(): NativeLong
    fun CFStringGetLength(value: Pointer): NativeLong
    fun CFStringGetCString(value: Pointer, buffer: ByteArray, length: NativeLong, encoding: Int): Byte
    fun CFBundleGetMainBundle(): Pointer?
    fun CFBundleCopyExecutableURL(bundle: Pointer): Pointer?
    fun CFURLGetFileSystemRepresentation(url: Pointer, resolve: Byte, buffer: ByteArray, size: NativeLong): Byte
    fun CFRelease(value: Pointer)
}
