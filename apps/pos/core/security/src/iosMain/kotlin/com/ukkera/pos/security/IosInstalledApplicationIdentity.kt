package com.ukkera.pos.security

import cnames.structs.__CFDictionary
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*
import platform.UIKit.*

/**
 * Native App-ID-group marker observations only. These results prove neither key possession
 * nor accepted identity: the shared IOS_ACTIVE_APP factory still refuses while P1 is pending.
 * Capture never creates, repairs or deletes a marker. Installation creation is separate.
 */
@OptIn(ExperimentalForeignApi::class)
public object IosInstalledApplicationIdentity {
    /** Existing-only observation for an active full iPhone/iPad app; no Keychain mutation. */
    public fun captureMarker(
        expected: SelfOwnedIosActiveAppIdentityV2,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedIosActiveAppIdentityV2> {
        contextFailure(expected)?.let { return refused(it) }
        return try {
            Query(expected).use { query ->
                query.set(kSecUseAuthenticationUI, kSecUseAuthenticationUIFail)
                query.set(kSecReturnAttributes, kCFBooleanTrue)
                query.set(kSecReturnData, kCFBooleanTrue)
                query.set(kSecMatchLimit, kSecMatchLimitOne)
                memScoped {
                    val output = alloc<CFTypeRefVar>()
                    output.value = null
                    val status = SecItemCopyMatching(query.dictionary, output.ptr)
                    val result = output.value
                    try {
                        when (status) {
                            errSecItemNotFound -> refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
                            errSecSuccess -> {
                                if (result == null || CFGetTypeID(result) != CFDictionaryGetTypeID()) {
                                    return@memScoped refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_SCOPE)
                                }
                                val attributes = result.reinterpret<__CFDictionary>()
                                val group = CFDictionaryGetValue(attributes, kSecAttrAccessGroup)
                                val data = CFDictionaryGetValue(attributes, kSecValueData)
                                val accessible = CFDictionaryGetValue(attributes, kSecAttrAccessible)
                                val synchronized = CFDictionaryGetValue(attributes, kSecAttrSynchronizable)
                                if (group == null || data == null || accessible == null ||
                                    CFGetTypeID(group) != CFStringGetTypeID() ||
                                    CFGetTypeID(data) != CFDataGetTypeID() ||
                                    !sameString(group, query.group) ||
                                    !sameString(accessible, checkNotNull(kSecAttrAccessibleWhenUnlockedThisDeviceOnly)) ||
                                    synchronized == null || CFGetTypeID(synchronized) != CFBooleanGetTypeID() ||
                                    CFBooleanGetValue(synchronized.reinterpret()) ||
                                    !sameData(data, query.marker)) {
                                    refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_SCOPE)
                                } else {
                                    contextFailure(expected)?.let { refused(it) }
                                        ?: SelfOwnedInstalledIdentityResultV2.Data(expected)
                                }
                            }
                            else -> refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_AUTHORIZATION_UNRESOLVED)
                        }
                    } finally {
                        if (result != null) CFRelease(result)
                    }
                }
            }
        } catch (_: Exception) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
        }
    }

    /**
     * One durable non-secret marker for an explicit installation ceremony. Duplicate items
     * are observed unchanged; this method never overwrites or deletes an existing item.
     * It does not create an installation key, accept P1 or grant a native owner.
     */
    public fun establishMarkerForInstallation(
        expected: SelfOwnedIosActiveAppIdentityV2,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedIosActiveAppIdentityV2> {
        contextFailure(expected)?.let { return refused(it) }
        return try {
            val status = Query(expected).use { query ->
                query.set(kSecValueData, query.marker)
                query.set(kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                SecItemAdd(query.dictionary, null)
            }
            when (status) {
                errSecSuccess, errSecDuplicateItem -> captureMarker(expected)
                errSecMissingEntitlement -> refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_SCOPE)
                else -> refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_AUTHORIZATION_UNRESOLVED)
            }
        } catch (_: Exception) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
        }
    }

    private fun contextFailure(expected: SelfOwnedIosActiveAppIdentityV2): SelfOwnedInstalledIdentityRefusalV2? {
        if (!NSThread.isMainThread) return SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE
        val bundle = NSBundle.mainBundle
        if (bundle.objectForInfoDictionaryKey("NSExtension") != null ||
            bundle.objectForInfoDictionaryKey("NSAppClip") != null ||
            NSProcessInfo.processInfo.isMacCatalystApp() || NSProcessInfo.processInfo.isiOSAppOnMac() ||
            UIDevice.currentDevice.userInterfaceIdiom !in setOf(UIUserInterfaceIdiomPhone, UIUserInterfaceIdiomPad) ||
            UIApplication.sharedApplication.applicationState != UIApplicationState.UIApplicationStateActive) {
            return SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT
        }
        val actual = bundle.bundleIdentifier
            ?: return SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE
        return if (actual.equals(expected.bundleIdentifier, ignoreCase = true)) null
            else SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH
    }

    private fun sameString(actual: CFTypeRef, expected: CFStringRef): Boolean =
        CFGetTypeID(actual) == CFStringGetTypeID() &&
            CFStringCompare(actual.reinterpret(), expected, 0uL) == 0L

    private fun sameData(actual: CFTypeRef, expected: CFDataRef): Boolean {
        if (CFGetTypeID(actual) != CFDataGetTypeID()) return false
        val data: CFDataRef = actual.reinterpret()
        val size = CFDataGetLength(expected)
        if (CFDataGetLength(data) != size) return false
        return checkNotNull(CFDataGetBytePtr(data)).readBytes(size.toInt()).contentEquals(
            checkNotNull(CFDataGetBytePtr(expected)).readBytes(size.toInt()))
    }

    private class Query(expected: SelfOwnedIosActiveAppIdentityV2) : AutoCloseable {
        private val owned = mutableListOf<CFTypeRef>()
        val dictionary: CFMutableDictionaryRef = checkNotNull(CFDictionaryCreateMutable(null, 0, null, null))
        val group: CFStringRef
        val marker: CFDataRef
        init {
            // No dictionary callbacks: all referenced values stay retained for this query.
            // Constructor failure releases every native allocation already made.
            try {
                group = text(expected.appIdGroup)
                val bytes = "UKKERA_IOS_APP_ID_GROUP_MARKER_V1".encodeToByteArray()
                marker = bytes.usePinned {
                    checkNotNull(CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong()))
                }.also { owned += it }
                set(kSecClass, kSecClassGenericPassword)
                set(kSecAttrAccessGroup, group)
                set(kSecAttrService, text("ukkera.installation.identity.v1"))
                set(kSecAttrAccount, text("ios-app-marker-v1"))
                set(kSecAttrSynchronizable, kCFBooleanFalse)
            } catch (failure: Throwable) {
                close()
                throw failure
            }
        }
        private fun text(value: String): CFStringRef = memScoped {
            checkNotNull(CFStringCreateWithCString(null, value.cstr.ptr, kCFStringEncodingUTF8))
                .also { owned += it }
        }
        fun set(key: CFStringRef?, value: CFTypeRef?) {
            CFDictionarySetValue(dictionary, checkNotNull(key), checkNotNull(value))
        }
        override fun close() {
            CFRelease(dictionary)
            owned.asReversed().forEach { CFRelease(it) }
            owned.clear()
        }
    }

    private fun refused(code: SelfOwnedInstalledIdentityRefusalV2): SelfOwnedInstalledIdentityResultV2.Refused =
        SelfOwnedInstalledIdentityResultV2.Refused(code)
}
