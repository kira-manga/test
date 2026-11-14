package com.ukkera.pos.security.frozen.nativeprovisionerv2

import com.ukkera.pos.security.NativeHistoryDatabaseIdentity
import com.ukkera.pos.security.NativeHistoryDatabaseIdentityV2
import com.ukkera.pos.security.NativeHistoryDatabaseIdentityWindowsV1
import com.ukkera.pos.security.NativeHistoryDatabaseIdentityWireWindowsV1
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SelfOwnedBootOrdinalLedgerV2
import com.ukkera.pos.security.SelfOwnedBootOrdinalRefusalV2
import com.ukkera.pos.security.SelfOwnedBootOrdinalStepV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityRefusalV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedNativeBootSessionV2
import com.ukkera.pos.security.SelfOwnedNativeOriginalProfileV2
import com.ukkera.pos.security.SelfOwnedNativeProvisionerWireV2
import com.ukkera.pos.security.frozen.androidv1baseline.AndroidV1BaselineFixture as A
import com.ukkera.pos.security.frozen.androidv1baseline.DocumentedLayout
import com.ukkera.pos.security.frozen.nativeprovisionerv2.NativeProvisionerV2Fixture as F
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Frozen expectations for the desktop boot-ordinal ledger (replacing the Android integer boot
 * count without fabricating one) and the Windows history-database identity, authored BEFORE the
 * code. Layouts are written from the documented framing. Synthetic DATA. NOT independent (same
 * author, owner instruction).
 */
class NativeBootLedgerAndHistoryIdentityFrozenTest {
    private val profile = "ukkera.installation/self-owned-native-original/v2"
    private fun <T> data(result: SelfOwnedInstalledIdentityResultV2<T>): T =
        assertIs<SelfOwnedInstalledIdentityResultV2.Data<T>>(result).value
    private fun refused(result: SelfOwnedInstalledIdentityResultV2<*>, code: SelfOwnedInstalledIdentityRefusalV2) =
        assertEquals(code, assertIs<SelfOwnedInstalledIdentityResultV2.Refused>(result).code)

    private val genesis = SelfOwnedBootOrdinalLedgerV2(0, F.windowsBoot, null)
    private val genesisLayout = DocumentedLayout().string(profile).string("native-boot-ordinal-ledger/v2").int(2)
        .int(0).int(1).string(F.WINDOWS_BOOT).int(0).bytes()

    @Test
    fun ledgerUsesExactDocumentedCanonicalEncoding() {
        val bytes = SelfOwnedNativeProvisionerWireV2.encodeBootOrdinalLedger(genesis)
        assertContentEquals(genesisLayout, bytes)
        assertEquals(genesis, data(SelfOwnedNativeProvisionerWireV2.decodeBootOrdinalLedger(bytes)))
        assertEquals(A.sha256Hex(genesisLayout), genesis.digest())
        val next = SelfOwnedBootOrdinalLedgerV2(1, SelfOwnedNativeBootSessionV2.Windows(F.WINDOWS_NEXT_BOOT), A.sha256Hex(genesisLayout))
        assertContentEquals(DocumentedLayout().string(profile).string("native-boot-ordinal-ledger/v2").int(2)
            .int(1).int(1).string(F.WINDOWS_NEXT_BOOT).int(1).string(A.sha256Hex(genesisLayout)).bytes(),
            SelfOwnedNativeProvisionerWireV2.encodeBootOrdinalLedger(next))
        refused(SelfOwnedNativeProvisionerWireV2.decodeBootOrdinalLedger(bytes + byteArrayOf(0)), SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRAILING)
        refused(SelfOwnedNativeProvisionerWireV2.decodeBootOrdinalLedger(bytes.copyOf(bytes.size - 1)), SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRUNCATED)
        refused(SelfOwnedNativeProvisionerWireV2.decodeBootOrdinalLedger(ByteArray(SelfOwnedNativeOriginalProfileV2.MAXIMUM_LEDGER_BYTES + 1)),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
        // Genesis with a previous digest is not canonical.
        refused(SelfOwnedNativeProvisionerWireV2.decodeBootOrdinalLedger(DocumentedLayout().string(profile)
            .string("native-boot-ordinal-ledger/v2").int(2).int(0).int(1).string(F.WINDOWS_BOOT).int(1)
            .string("a".repeat(64)).bytes()), SelfOwnedInstalledIdentityRefusalV2.REFUSE_GRAMMAR)
        assertEquals("SelfOwnedBootOrdinalLedgerV2([REDACTED])", genesis.toString())
    }

    @Test
    fun ledgerConstructionFailsClosed() {
        assertFails { SelfOwnedBootOrdinalLedgerV2(-1, F.windowsBoot, null) }
        assertFails { SelfOwnedBootOrdinalLedgerV2(0, F.windowsBoot, "a".repeat(64)) }
        assertFails { SelfOwnedBootOrdinalLedgerV2(1, F.windowsBoot, null) }
        assertFails { SelfOwnedBootOrdinalLedgerV2(1, F.windowsBoot, "A".repeat(64)) }
    }

    @Test
    fun sameSessionKeepsTheOrdinalAndANewSessionAdvancesByExactlyOne() {
        assertSame(genesis, assertIs<SelfOwnedBootOrdinalStepV2.Same>(genesis.advance(F.windowsBoot)).ledger)
        val nextBoot = SelfOwnedNativeBootSessionV2.Windows(F.WINDOWS_NEXT_BOOT)
        val advanced = assertIs<SelfOwnedBootOrdinalStepV2.Advanced>(genesis.advance(nextBoot)).ledger
        assertEquals(1, advanced.ordinal)
        assertEquals(nextBoot, advanced.bootSession)
        assertEquals(A.sha256Hex(genesisLayout), advanced.previousDigest)
        assertIs<SelfOwnedBootOrdinalStepV2.Same>(advanced.advance(nextBoot))
        assertEquals(2, assertIs<SelfOwnedBootOrdinalStepV2.Advanced>(advanced.advance(F.windowsBoot)).ledger.ordinal)
        assertEquals(SelfOwnedBootOrdinalRefusalV2.PLATFORM_MISMATCH,
            assertIs<SelfOwnedBootOrdinalStepV2.Refused>(genesis.advance(F.macBoot)).reason)
        val last = SelfOwnedBootOrdinalLedgerV2(Int.MAX_VALUE, F.windowsBoot, "b".repeat(64))
        assertEquals(SelfOwnedBootOrdinalRefusalV2.CAPACITY_EXHAUSTED,
            assertIs<SelfOwnedBootOrdinalStepV2.Refused>(last.advance(nextBoot)).reason)
    }

    @Test
    fun successorVerificationRefusesDecreasingGapMismatchedAndReplayedLedgers() {
        val nextBoot = SelfOwnedNativeBootSessionV2.Windows(F.WINDOWS_NEXT_BOOT)
        val one = SelfOwnedBootOrdinalLedgerV2(1, nextBoot, genesis.digest())
        assertIs<SelfOwnedBootOrdinalStepV2.Advanced>(genesis.verifySuccessor(one))
        assertIs<SelfOwnedBootOrdinalStepV2.Same>(one.verifySuccessor(SelfOwnedBootOrdinalLedgerV2(1, nextBoot, genesis.digest())))
        fun reason(step: SelfOwnedBootOrdinalStepV2) = assertIs<SelfOwnedBootOrdinalStepV2.Refused>(step).reason
        assertEquals(SelfOwnedBootOrdinalRefusalV2.ORDINAL_DECREASED, reason(one.verifySuccessor(genesis)))
        assertEquals(SelfOwnedBootOrdinalRefusalV2.ORDINAL_GAP,
            reason(genesis.verifySuccessor(SelfOwnedBootOrdinalLedgerV2(2, nextBoot, genesis.digest()))))
        assertEquals(SelfOwnedBootOrdinalRefusalV2.DIGEST_MISMATCH,
            reason(genesis.verifySuccessor(SelfOwnedBootOrdinalLedgerV2(1, nextBoot, "c".repeat(64)))))
        assertEquals(SelfOwnedBootOrdinalRefusalV2.SESSION_NOT_ADVANCED,
            reason(genesis.verifySuccessor(SelfOwnedBootOrdinalLedgerV2(1, F.windowsBoot, genesis.digest()))))
        assertEquals(SelfOwnedBootOrdinalRefusalV2.ORDINAL_CONFLICT,
            reason(one.verifySuccessor(SelfOwnedBootOrdinalLedgerV2(1, F.windowsBoot, genesis.digest()))))
        assertEquals(SelfOwnedBootOrdinalRefusalV2.PLATFORM_MISMATCH,
            reason(genesis.verifySuccessor(SelfOwnedBootOrdinalLedgerV2(1, F.macBoot, genesis.digest()))))
    }

    private val volumeSerial = "0123456789abcdef"
    private val fileId = "00112233445566778899aabbccddeeff"

    @Test
    fun windowsHistoryDatabaseIdentityUsesExactFramingAndSharesTheSealedParent() {
        val identity = NativeHistoryDatabaseIdentityWindowsV1(volumeSerial, fileId, F.OWNER_SID)
        val bytes = NativeHistoryDatabaseIdentityWireWindowsV1.encode(identity)
        assertContentEquals(DocumentedLayout().string("ukkera.history/native-database-identity")
            .string("native-history-database-identity/windows-v1").int(1)
            .string(volumeSerial).string(fileId).string(F.OWNER_SID).bytes(), bytes)
        assertEquals(identity, data(NativeHistoryDatabaseIdentityWireWindowsV1.decode(bytes)))
        refused(NativeHistoryDatabaseIdentityWireWindowsV1.decode(bytes + byteArrayOf(0)), SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRAILING)
        refused(NativeHistoryDatabaseIdentityWireWindowsV1.decode(bytes.copyOf(bytes.size - 1)), SelfOwnedInstalledIdentityRefusalV2.REFUSE_TRUNCATED)
        refused(NativeHistoryDatabaseIdentityWireWindowsV1.decode(ByteArray(NativeHistoryDatabaseIdentityWireWindowsV1.MAXIMUM_BYTES + 1)),
            SelfOwnedInstalledIdentityRefusalV2.REFUSE_OVERSIZE)
        assertFails { NativeHistoryDatabaseIdentityWindowsV1(volumeSerial.uppercase(), fileId, F.OWNER_SID) }
        assertFails { NativeHistoryDatabaseIdentityWindowsV1(volumeSerial, fileId.substring(2), F.OWNER_SID) }
        assertFails { NativeHistoryDatabaseIdentityWindowsV1(volumeSerial, fileId, "S-1") }
        val parents: List<NativeHistoryDatabaseIdentity> = listOf(identity,
            NativeHistoryDatabaseIdentityV2(1L, 2L, 3, PayloadDigest("d".repeat(64)), "0123456789abcdef0123456789abcdef"))
        assertEquals(2, parents.size)
        assertNull(parents.firstOrNull { it !is NativeHistoryDatabaseIdentityV2 && it !is NativeHistoryDatabaseIdentityWindowsV1 })
    }
}
