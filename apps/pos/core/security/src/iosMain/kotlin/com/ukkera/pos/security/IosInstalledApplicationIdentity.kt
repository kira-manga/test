package com.ukkera.pos.security

import cnames.structs.__CFDictionary
import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Foundation.*
import platform.Security.*
import platform.UIKit.*

/**
 * Native iOS active-full-app identity observation for the owner-approved P1 boundary.
 *
 * The runtime proof is the OS-enforced code-signing consequence: Keychain Services only
 * returns an item queried with an explicit App ID access group when the running process
 * holds the matching code-signed `application-identifier`/`keychain-access-groups`
 * entitlement. This is not a macOS `SecCode`/`SecTask` substitute; iOS exposes no public
 * signer/Team ID/entitlement-list introspection API. The result proves neither key
 * possession nor Master/device/employee authority. Capture never creates, repairs or
 * deletes a marker; installation creation is separate (E3-028).
 */
@OptIn(ExperimentalForeignApi::class)
public object IosInstalledApplicationIdentity {
    /** Existing-only observation for an active full iPhone/iPad app; no Keychain mutation. */
    public fun captureMarker(
        expected: SelfOwnedIosActiveAppIdentityV2,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedIosActiveAppIdentityV2> =
        when (val observed = readMarker(expected)) {
            is SelfOwnedInstalledIdentityResultV2.Data ->
                SelfOwnedInstalledIdentityResultV2.Data(expected)
            is SelfOwnedInstalledIdentityResultV2.Refused -> observed
        }

    /**
     * Genuine native code-signature-backed observation. Success requires the active full app,
     * the expected bundle ID, and an existing marker returned through the explicit App ID
     * group with the exact OS-maintained `kSecAttrAccessGroup` and fixed marker value.
     */
    public fun verifyActiveAppCodeSignature(
        expected: SelfOwnedIosActiveAppIdentityV2,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedIosNativeCodeSignatureEvidenceV1> =
        try {
            when (val observed = readMarker(expected)) {
                is SelfOwnedInstalledIdentityResultV2.Data ->
                    SelfOwnedInstalledIdentityResultV2.Data(
                        SelfOwnedIosNativeCodeSignatureEvidenceV1(
                            expected,
                            observed.value.bundleIdentifier,
                            observed.value.appIdGroup,
                            reobserve = {
                                when (val current = readMarker(expected)) {
                                    is SelfOwnedInstalledIdentityResultV2.Data -> null
                                    is SelfOwnedInstalledIdentityResultV2.Refused -> current.code
                                }
                            },
                        ),
                    )
                is SelfOwnedInstalledIdentityResultV2.Refused -> observed
            }
        } catch (_: IllegalArgumentException) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        } catch (_: Exception) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
        }

    /**
     * Production consumer of the approved policy: native observation first, then the exact
     * expected target and native-evidence policy boundary. The result is target DATA only;
     * key custody and admission remain separate E3-009/010 requirements.
     */
    public fun acceptActiveAppTarget(
        expected: SelfOwnedIosActiveAppIdentityV2,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedIosActiveAppIdentityV2> =
        when (val evidence = verifyActiveAppCodeSignature(expected)) {
            is SelfOwnedInstalledIdentityResultV2.Data ->
                SelfOwnedInstalledIdentityIosPolicyV1.acceptTarget(expected, evidence.value)
            is SelfOwnedInstalledIdentityResultV2.Refused -> evidence
        }

    /** Production binding consumer: native evidence plus the exact expected target as DATA. */
    public fun acceptActiveAppTargetBinding(
        expected: SelfOwnedIosActiveAppIdentityV2,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedIosInstalledTargetBindingV1> =
        when (val evidence = verifyActiveAppCodeSignature(expected)) {
            is SelfOwnedInstalledIdentityResultV2.Data ->
                SelfOwnedInstalledTargetBindingV1.acceptApprovedIos(expected, evidence.value)
            is SelfOwnedInstalledIdentityResultV2.Refused -> evidence
        }

    /**
     * One durable non-secret marker for an explicit installation ceremony. Duplicate items
     * are observed unchanged; this method never overwrites or deletes an existing item.
     * It does not create an installation key, admit an installation identity or grant a
     * native owner.
     */
    public fun establishMarkerForInstallation(
        expected: SelfOwnedIosActiveAppIdentityV2,
    ): SelfOwnedInstalledIdentityResultV2<SelfOwnedIosActiveAppIdentityV2> {
        when (val context = contextObservation(expected)) {
            is SelfOwnedInstalledIdentityResultV2.Refused -> return context
            is SelfOwnedInstalledIdentityResultV2.Data -> Unit
        }
        return try {
            val status = Query(expected).use { query ->
                query.set(kSecValueData, query.marker)
                query.set(kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
                SecItemAdd(query.dictionary, null)
            }
            when (status) {
                errSecSuccess, errSecDuplicateItem -> captureMarker(expected)
                errSecMissingEntitlement ->
                    refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_SCOPE)
                else -> refused(
                    SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_AUTHORIZATION_UNRESOLVED,
                )
            }
        } catch (_: Exception) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
        }
    }

    private fun readMarker(
        expected: SelfOwnedIosActiveAppIdentityV2,
    ): SelfOwnedInstalledIdentityResultV2<MarkerObservation> {
        val before = contextObservation(expected)
        if (before is SelfOwnedInstalledIdentityResultV2.Refused) return before
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
                            errSecItemNotFound ->
                                refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
                            errSecSuccess -> {
                                if (result == null ||
                                    CFGetTypeID(result) != CFDictionaryGetTypeID()
                                ) {
                                    return@memScoped refused(
                                        SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_SCOPE,
                                    )
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
                                    !sameString(
                                        accessible,
                                        checkNotNull(kSecAttrAccessibleWhenUnlockedThisDeviceOnly),
                                    ) ||
                                    (synchronized != null &&
                                        (CFGetTypeID(synchronized) != CFBooleanGetTypeID() ||
                                            CFBooleanGetValue(synchronized.reinterpret()))) ||
                                    !sameData(data, query.marker)
                                ) {
                                    refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_SCOPE)
                                } else {
                                    val after = contextObservation(expected)
                                    val observedBundle = when (after) {
                                        is SelfOwnedInstalledIdentityResultV2.Data -> after.value
                                        is SelfOwnedInstalledIdentityResultV2.Refused ->
                                            return@memScoped after
                                    }
                                    val observedGroup = cfStringValue(group)
                                        ?: return@memScoped refused(
                                            SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_SCOPE,
                                        )
                                    SelfOwnedInstalledIdentityResultV2.Data(
                                        MarkerObservation(observedBundle, observedGroup),
                                    )
                                }
                            }
                            errSecMissingEntitlement -> refused(
                                SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_AUTHORIZATION_UNRESOLVED,
                            )
                            else -> refused(
                                SelfOwnedInstalledIdentityRefusalV2.REFUSE_KEYCHAIN_AUTHORIZATION_UNRESOLVED,
                            )
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

    private fun contextObservation(
        expected: SelfOwnedIosActiveAppIdentityV2,
    ): SelfOwnedInstalledIdentityResultV2<String> =
        try {
            observeContext(expected)
        } catch (_: Exception) {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
        }

    private fun observeContext(
        expected: SelfOwnedIosActiveAppIdentityV2,
    ): SelfOwnedInstalledIdentityResultV2<String> {
        if (!NSThread.isMainThread) {
            return refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
        }
        val bundle = NSBundle.mainBundle
        if (bundle.objectForInfoDictionaryKey("NSExtension") != null ||
            bundle.objectForInfoDictionaryKey("NSAppClip") != null ||
            isSimulator() ||
            NSProcessInfo.processInfo.isMacCatalystApp() ||
            NSProcessInfo.processInfo.isiOSAppOnMac() ||
            UIDevice.currentDevice.userInterfaceIdiom !in
            setOf(UIUserInterfaceIdiomPhone, UIUserInterfaceIdiomPad) ||
            UIApplication.sharedApplication.applicationState !=
            UIApplicationState.UIApplicationStateActive
        ) {
            return refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_UNSUPPORTED_CONTEXT)
        }
        val actual = bundle.bundleIdentifier
            ?: return refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_NATIVE_OBSERVATION_UNAVAILABLE)
        return if (actual.equals(expected.bundleIdentifier, ignoreCase = true)) {
            SelfOwnedInstalledIdentityResultV2.Data(actual)
        } else {
            refused(SelfOwnedInstalledIdentityRefusalV2.REFUSE_IDENTITY_MISMATCH)
        }
    }

    /** Simulator builds are not installed, code-signed device identities in this profile. */
    private fun isSimulator(): Boolean {
        val environment = NSProcessInfo.processInfo.environment
        if (environment.keys.any {
                when (it.toString()) {
                    "SIMULATOR_DEVICE_NAME",
                    "SIMULATOR_UDID",
                    "SIMULATOR_MODEL_IDENTIFIER",
                    "SIMULATOR_ROOT",
                    "SIMULATOR_HOST_HOME",
                    "SIMULATOR_SHARED_RESOURCES_DIRECTORY",
                    -> true
                    else -> false
                }
            }
        ) {
            return true
        }
        // Simulator app bundles live under CoreSimulator containers; device bundles do not.
        val path = NSBundle.mainBundle.bundlePath
        return "/CoreSimulator/" in path || "/Simulator/" in path
    }

    private class MarkerObservation(
        val bundleIdentifier: String,
        val appIdGroup: String,
    )

    private fun cfStringValue(value: CFTypeRef): String? {
        if (CFGetTypeID(value) != CFStringGetTypeID()) return null
        val string: CFStringRef = value.reinterpret()
        val length = CFStringGetLength(string)
        if (length < 0 || length > 4096) return null
        val maximum = CFStringGetMaximumSizeForEncoding(length, kCFStringEncodingUTF8)
        if (maximum <= 0 || maximum > 16_384) return null
        val size = maximum.toInt() + 1
        val bytes = ByteArray(size)
        val converted = bytes.usePinned { pinned ->
            CFStringGetCString(string, pinned.addressOf(0), maximum + 1, kCFStringEncodingUTF8)
        }
        if (!converted) return null
        val end = bytes.indexOf(0.toByte())
        if (end <= 0) return null
        return bytes.decodeToString(0, end)
    }

    private fun sameString(actual: CFTypeRef, expected: CFStringRef): Boolean =
        CFGetTypeID(actual) == CFStringGetTypeID() &&
            CFStringCompare(actual.reinterpret(), expected, 0uL) == 0L

    private fun sameData(actual: CFTypeRef, expected: CFDataRef): Boolean {
        if (CFGetTypeID(actual) != CFDataGetTypeID()) return false
        val data: CFDataRef = actual.reinterpret()
        val size = CFDataGetLength(expected)
        if (size !in 0L..1024L || CFDataGetLength(data) != size) return false
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
