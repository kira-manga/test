package com.ukkera.pos.android.independent.androidapi24v1

import android.os.Build
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ukkera.pos.security.AndroidDevicePairingTarget
import com.ukkera.pos.security.AndroidDevicePairingTargetOpenResult
import com.ukkera.pos.security.AndroidDevicePairingTargetPhase
import com.ukkera.pos.security.AndroidDevicePairingTargetResult
import com.ukkera.pos.security.AndroidNativeKeyInfo
import com.ukkera.pos.security.AndroidNativeKeyPolicy
import com.ukkera.pos.security.AndroidNativeKeySecurity
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.SignatureException
import java.security.interfaces.ECPublicKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AC24-N02/N03/N07 native-key-only slices using real production target preparation
 * under its explicit OS-isolated profile, then independent actual AndroidKeyStore
 * signing/verification. Not an admitted identity, master appointment, arbitrary
 * production signing endpoint, full enrollment, protected SQLite, login or POS test.
 * Hardware facts are observations of this runtime, not physical qualification.
 * N04-N06 faults/context variants, corrupt state, N08 and N15 upgrades remain unrun.
 */
@RunWith(AndroidJUnit4::class)
class AndroidApi24V1NativeKeySlicesTest {
    @Test
    fun n02_n03_n07_originalNativeKeyNonexportabilityChallengeAndCloseReopenSlices() = runBlocking {
        val app = AndroidApi24V1NativeInputs.application()
        // This test is one explicit fresh synthetic preparation. A repeated invocation
        // never resets, replaces or attempts to repair a retained target/unknown result.
        check(!File(app.noBackupFilesDir, "ukkera-device-pairing-target-v1").exists()) {
            "Retained target exists; inspect it before any explicit disposable-install reset"
        }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val before = aliases(store)
        val target = opened(AndroidDevicePairingTarget.prepareNew(app, AndroidApi24V1NativeInputs.scope))
        val original = try {
            val reference = value(target.original())
            val descriptor = value(target.descriptor())
            assertEquals(AndroidApi24V1NativeInputs.scope, descriptor.admission.scope)
            assertEquals(reference.deviceId, descriptor.admission.deviceId)
            assertEquals(reference.keyId, descriptor.keyId)
            val point = descriptor.copyPublicPoint()
            try {
                val added = aliases(store) - before
                val alias = added.single { matchesPoint(store.getCertificate(it)?.publicKey, point) }
                inspectAndSign(store, alias)
                Triple(reference, descriptor.copyWireBytes(), alias)
            } finally { point.fill(0) }
        } finally { target.close() }
        val afterPreparation = aliases(store)
        val reopened = opened(AndroidDevicePairingTarget.openExisting(app))
        try {
            assertEquals("AC24-N07 original public key coordinates", original.first, value(reopened.original()))
            val wire = value(reopened.descriptor()).copyWireBytes()
            try { assertTrue("AC24-N07 same original public descriptor", original.second.contentEquals(wire)) }
            finally { wire.fill(0) }
            assertEquals("AC24-N07 read-only reopen creates no replacement alias", afterPreparation, aliases(store))
            inspectAndSign(store, original.third)
            assertEquals("AC24-N02 original native signing retains aliases", afterPreparation, aliases(store))
        } finally {
            original.second.fill(0)
            reopened.close()
        }
    }

    /**
     * Select only this method after the executor observes force-stop and a fresh process
     * on the preserved testing installation. This method never prepares or resumes state.
     * It proves usable retained signed native target state, not an independent comparison
     * with the pre-restart random reference/descriptor/alias lost by the first method.
     * It is not full AC24-N09/N12/N14, protected SQLite, an installed UI or master restart.
     */
    @Test
    fun n02_n07_existingOnlyFreshProcessStartupSlice_noIndependentPreRestartIdentityComparison() = runBlocking {
        val app = AndroidApi24V1NativeInputs.application()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val before = aliases(store)
        // The production existing-only acquisition refuses an absent/unproved namespace;
        // no preparation, key generation, orphan adoption or fixture reset is attempted.
        val target = opened(AndroidDevicePairingTarget.openExisting(app))
        try {
            val reference = value(target.original())
            // This reader is confined to the first method's known ready cut. Partial or
            // response-phase state is not permission to resume/sign an unknown operation.
            check(reference.phase == AndroidDevicePairingTargetPhase.KEY_READY) {
                "Existing-only startup slice requires known KEY_READY state; preserve all other state"
            }
            val descriptor = value(target.descriptor())
            assertEquals(AndroidApi24V1NativeInputs.scope, descriptor.admission.scope)
            assertEquals(reference.deviceId, descriptor.admission.deviceId)
            assertEquals(reference.keyId, descriptor.keyId)
            val point = descriptor.copyPublicPoint()
            try {
                val alias = aliases(store).single { matchesPoint(store.getCertificate(it)?.publicKey, point) }
                assertEquals("AC24-N07 existing-only reader creates no replacement alias", before, aliases(store))
                inspectAndSign(store, alias)
                assertEquals("AC24-N02 retained native signing creates no replacement alias", before, aliases(store))
            } finally { point.fill(0) }
        } finally { target.close() }
        assertEquals("AC24-N07 existing-only actual close retains aliases", before, aliases(store))
    }

    @Suppress("DEPRECATION")
    private fun inspectAndSign(store: KeyStore, alias: String) {
        val key = store.getKey(alias, null) as PrivateKey
        val exported = key.encoded
        try { assertNull("AC24-N02 native private bytes unavailable for export", exported) }
        finally { exported?.fill(0) }
        val info = KeyFactory.getInstance(key.algorithm, "AndroidKeyStore").getKeySpec(key, KeyInfo::class.java)
        assertEquals(alias, info.keystoreAlias)
        assertEquals(KeyProperties.ORIGIN_GENERATED, info.origin)
        assertFalse("AC24-N02 unrestricted selected service profile", AndroidNativeKeyInfo.hasServiceRestrictions(info))
        val observed = AndroidNativeKeyInfo.observe(info)
        assertTrue(AndroidNativeKeyPolicy.permits(SelfOwnedKeyProtectionV1.OS_ISOLATED, observed))
        if (Build.VERSION.SDK_INT < 31) {
            assertEquals("AC24-N03 legacy boolean cannot identify hardware kind",
                if (info.isInsideSecureHardware) AndroidNativeKeySecurity.HARDWARE_KIND_UNSPECIFIED
                else AndroidNativeKeySecurity.SOFTWARE, observed)
        } else {
            val expected = when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> AndroidNativeKeySecurity.SOFTWARE
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> AndroidNativeKeySecurity.TRUSTED_ENVIRONMENT
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> AndroidNativeKeySecurity.STRONGBOX
                KeyProperties.SECURITY_LEVEL_UNKNOWN_SECURE -> AndroidNativeKeySecurity.HARDWARE_KIND_UNSPECIFIED
                else -> error("Required actual security evidence unavailable; no default classification")
            }
            assertEquals("AC24-N03 actual current KeyInfo evidence", expected, observed)
        }
        val message = AndroidApi24V1NativeInputs.challenge1.encodeToByteArray()
        val changed = AndroidApi24V1NativeInputs.challenge2.encodeToByteArray()
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(key); update(message); sign() }
        val tampered = signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        try {
            val publicKey = store.getCertificate(alias).publicKey
            assertTrue("AC24-N02 original challenge", verifies(publicKey, message, signature))
            assertFalse("AC24-N02 changed challenge", verifies(publicKey, changed, signature))
            assertFalse("AC24-N02 tampered signature", verifies(publicKey, message, tampered))
            Log.i("AndroidApi24V1", "NATIVE_KEY_SLICE currentEvidence=${observed.name}; " +
                "explicit OS_ISOLATED only; physical hardware/StrongBox enrollment not qualified")
        } finally { message.fill(0); changed.fill(0); signature.fill(0); tampered.fill(0) }
    }

    private fun verifies(key: PublicKey, message: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(message); verify(signature) }
    } catch (_: SignatureException) { false }

    private fun matchesPoint(key: PublicKey?, point: ByteArray): Boolean = key is ECPublicKey &&
        point.size == 65 && point[0].toInt() == 4 &&
        key.w.affineX == BigInteger(1, point.copyOfRange(1, 33)) &&
        key.w.affineY == BigInteger(1, point.copyOfRange(33, 65))

    private fun aliases(store: KeyStore): Set<String> {
        val result = mutableSetOf<String>()
        val names = store.aliases()
        while (names.hasMoreElements()) {
            check(result.size < 65_536) { "Synthetic witness census is not safely bounded" }
            result.add(names.nextElement())
        }
        return result
    }

    private fun opened(result: AndroidDevicePairingTargetOpenResult): AndroidDevicePairingTarget =
        (result as? AndroidDevicePairingTargetOpenResult.Opened)?.target
            ?: error("Actual production native target open unresolved: " +
                (result as? AndroidDevicePairingTargetOpenResult.Unresolved)?.reason + "; no replacement or fallback")
    private fun <T> value(result: AndroidDevicePairingTargetResult<T>): T =
        (result as? AndroidDevicePairingTargetResult.Value<T>)?.value
            ?: error("Actual original native target observation unavailable: " +
                (result as? AndroidDevicePairingTargetResult.Unavailable)?.reason)
}
