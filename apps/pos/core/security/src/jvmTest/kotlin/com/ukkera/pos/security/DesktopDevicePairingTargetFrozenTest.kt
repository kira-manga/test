package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Frozen expectations for the desktop Client target original preparation/open slice, authored
 * BEFORE the implementation on 12 November 2026. NOT independent (same author, owner instruction).
 * The software key below exists only in this test platform; production uses OS key custody.
 */
class DesktopDevicePairingTargetFrozenTest {
    private val root: Path = Files.createTempDirectory("desktop-target-test")
    private fun id(n: Int) = DomainId("00000000-0000-4000-8000-" + n.toString().padStart(12, '0'))
    private val scope = BranchScope(id(91), id(92), id(93))
    private val platform = TestPlatform(root)

    @AfterTest fun cleanup() { root.toFile().deleteRecursively() }

    @Test
    fun preparationRetainsReadyOriginalWithMatchingDescriptor() {
        val target = DesktopDevicePairingTarget.prepareNew(platform, scope, setOf("print", "cash"))
        val reference = target.original()
        assertEquals(LocalDevicePairingTargetPhase.KEY_READY, reference.phase)
        val descriptor = target.descriptor()
        assertEquals(reference.keyId, descriptor.keyId)
        assertEquals(reference.deviceId, descriptor.admission.deviceId)
        assertEquals(setOf("cash", "print"), descriptor.admission.capabilities)
        assertContentEquals(platform.keys.single().publicPoint(), descriptor.copyPublicPoint())
        target.close()
    }

    @Test
    fun reopeningReturnsTheSameOriginalWithoutNewKeys() {
        val first = DesktopDevicePairingTarget.prepareNew(platform, scope, emptySet())
        val reference = first.original(); val descriptor = first.descriptor().copyWireBytes()
        first.close()
        platform.boot = "7f1c2a3b-0000-4000-8000-0000000000bb" // reboot between sessions
        val reopened = DesktopDevicePairingTarget.openExisting(platform)
        assertEquals(reference, reopened.original())
        assertContentEquals(descriptor, reopened.descriptor().copyWireBytes())
        assertEquals(1, platform.created)
        reopened.close()
    }

    @Test
    fun preparationNeverAdoptsAnExistingTarget() {
        DesktopDevicePairingTarget.prepareNew(platform, scope, emptySet()).close()
        assertFails { DesktopDevicePairingTarget.prepareNew(platform, scope, emptySet()) }
        assertEquals(1, platform.created)
    }

    @Test
    fun interruptedKeyGenerationIsReviewableButNeverRetried() {
        platform.failCreate = true
        assertFails { DesktopDevicePairingTarget.prepareNew(platform, scope, emptySet()) }
        platform.failCreate = false
        val reopened = DesktopDevicePairingTarget.openExisting(platform)
        assertEquals(LocalDevicePairingTargetPhase.KEY_GENERATION_INTENT, reopened.original().phase)
        assertFails { reopened.descriptor() }
        assertEquals(0, platform.created)
        reopened.close()
    }

    @Test
    fun changedOriginIsRefused() {
        DesktopDevicePairingTarget.prepareNew(platform, scope, emptySet()).close()
        platform.owner = "S-1-5-21-1000-2000-3000-1002"
        assertFails { DesktopDevicePairingTarget.openExisting(platform) }
    }

    @Test
    fun deniedOrClosedTargetRefusesWork() {
        val target = DesktopDevicePairingTarget.prepareNew(platform, scope, emptySet())
        target.denyNewWork()
        assertFails { target.descriptor() }
        target.close()
        assertFails { target.original() }
    }

    @Test
    fun invalidCapabilitiesAreRefusedBeforeAnyStorage() {
        assertFails { DesktopDevicePairingTarget.prepareNew(platform, scope, setOf("bad space")) }
        assertTrue(Files.list(root).use { it.count() } == 0L)
    }

    private class TestKey(private val pair: java.security.KeyPair) : DesktopClientTargetKey {
        override val security get() = SelfOwnedNativeSecurityV1.SOFTWARE
        override fun publicPoint(): ByteArray {
            val w = (pair.public as ECPublicKey).w
            fun fixed(v: java.math.BigInteger) = v.toByteArray().let { b -> ByteArray(32).also { o ->
                val s = b.takeLast(32).toByteArray(); s.copyInto(o, 32 - s.size) } }
            return byteArrayOf(4) + fixed(w.affineX) + fixed(w.affineY)
        }
        override fun sign(message: ByteArray): ByteArray {
            val der = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(message) }.sign()
            return JvmP256Primitives.canonicalize(JvmP256Primitives.fromDer(der))
        }
        override fun close() = Unit
    }

    private class TestPlatform(private val parent: Path) : DesktopClientTargetPlatform {
        var boot = "6f1c2a3b-0000-4000-8000-0000000000aa"
        var owner = "S-1-5-21-1000-2000-3000-1001"
        var failCreate = false
        var created = 0
        val keys = mutableListOf<TestKey>()
        private val stored = mutableMapOf<String, java.security.KeyPair>()
        private var clock = 1_000L
        override val slot: LocalTrainingCompositionSlot? get() = null
        override fun parentDirectory(): Path = parent
        override fun origin(directory: Path): TargetOrigin.Desktop = TargetOrigin.Desktop(
            SelfOwnedWindowsPackagedAppIdentityV2("Ukkera.POS.Test_n9kkbga0qmnm6", "Ukkera.POS.Test_n9kkbga0qmnm6!UkkeraPOS"),
            TargetDesktopNamespaceV2(SelfOwnedNativePlatformV2.WINDOWS, "C:\\Users\\cashier\\AppData\\Local\\Packages\\" +
                "Ukkera.POS.Test_n9kkbga0qmnm6\\LocalState\\" + directory.fileName, owner,
                "0123abcd-0000-4000-8000-00000000abcd", "00000000000000000000000000000011", "00000000000000000000000000000012"))
        override fun bootSessionId(): String = boot
        override fun elapsedMillis(): Long = clock++
        override fun keyExists(preparationId: DomainId, keyId: DomainId): Boolean =
            "${preparationId.value}.${keyId.value}" in stored
        override fun newSdkOwner(installed: SelfOwnedInstalledIdentityTargetV2): DevicePairingTargetSdkOwner =
            throw UnsupportedOperationException("SDK owner is not part of this preparation slice")
        override fun createKey(preparationId: DomainId, keyId: DomainId): DesktopClientTargetKey {
            check(!failCreate) { "native generation outcome unknown" }
            val name = "${preparationId.value}.${keyId.value}"
            check(name !in stored)
            created++
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            stored[name] = pair
            return TestKey(pair).also { keys += it }
        }
        override fun openKey(preparationId: DomainId, keyId: DomainId, originalPoint: ByteArray): DesktopClientTargetKey {
            val key = TestKey(checkNotNull(stored["${preparationId.value}.${keyId.value}"]))
            check(key.publicPoint().contentEquals(originalPoint))
            return key
        }
    }
}
