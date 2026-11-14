package com.ukkera.pos.security.frozen.historydatabaseidentityv2

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.LocalBranchInstallationWireV2
import com.ukkera.pos.security.LocalMasterSdkOriginal
import com.ukkera.pos.security.LocalMasterSdkOriginalV2
import com.ukkera.pos.security.NativeHistoryAnchorRecordV2
import com.ukkera.pos.security.NativeHistoryDatabaseBindingWindowsV1
import com.ukkera.pos.security.NativeHistoryDatabaseIdentity
import com.ukkera.pos.security.NativeHistoryDatabaseIdentityV2
import com.ukkera.pos.security.NativeHistoryDatabaseIdentityWindowsV1
import com.ukkera.pos.security.SelfOwnedInstalledIdentityFactoryV2
import com.ukkera.pos.security.SelfOwnedInstalledIdentityResultV2
import com.ukkera.pos.security.SelfOwnedInstalledTargetBindingV1
import com.ukkera.pos.security.SelfOwnedMacServiceIdentityV2
import com.ukkera.pos.security.SelfOwnedNativeHistoryWireV2
import com.ukkera.pos.security.SelfOwnedNativeInstalledTargetBindingV1
import com.ukkera.pos.security.SelfOwnedWindowsPackagedAppIdentityV2
import com.ukkera.pos.security.frozen.androidv1baseline.DocumentedLayout
import com.ukkera.pos.security.independent.masterboundpairing.MasterBoundPairingDataFixture as F
import com.ukkera.pos.security.independent.masterboundpairing.SupportingPairingId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Frozen expectations for retaining a platform database identity in [NativeHistoryAnchorRecordV2],
 * authored on 13 November 2026 BEFORE the record accepted the [NativeHistoryDatabaseIdentity] parent.
 * NOT independent (same author, owner instruction).
 *
 * Rules frozen here:
 * - The POSIX [NativeHistoryDatabaseIdentityV2] keeps its exact untagged record bytes
 *   (`bool 1, long device, long inode, int uid, string digest, string roomHash`) in every format.
 * - The Windows binding is written behind the impossible device sentinel `-1` (eight 0xFF bytes),
 *   tag `int 1`, the framed Windows file identity, then the same digest and Room hash tail.
 * - Only a Windows V2 Master original record may hold the Windows binding, and such a record may
 *   not hold the POSIX identity. The bare Windows file identity is never a record member.
 * - An unknown tag, a misplaced binding or truncation is refused; nothing falls back across variants.
 *
 * Expected bytes are composed from the documented layout, with only the unrelated record remainder
 * taken from the same record encoded without a database.
 */
class HistoryDatabaseIdentityRecordFrozenTest {
    private val digest = PayloadDigest("d".repeat(64))
    private val room = "0123456789abcdef0123456789abcdef"
    private val posix = NativeHistoryDatabaseIdentityV2(7L, 42L, 10_123, digest, room)
    private val windowsFile = NativeHistoryDatabaseIdentityWindowsV1("0123456789abcdef",
        "00112233445566778899aabbccddeeff", "S-1-5-21-1000-2000-3000-1001")
    private val windowsBinding = NativeHistoryDatabaseBindingWindowsV1(windowsFile, digest, room)

    private fun target(value: Any): SelfOwnedNativeInstalledTargetBindingV1 {
        val result = when (value) {
            is SelfOwnedWindowsPackagedAppIdentityV2 ->
                SelfOwnedInstalledTargetBindingV1.accept(SelfOwnedInstalledIdentityFactoryV2.WINDOWS_UI, value)
            is SelfOwnedMacServiceIdentityV2 ->
                SelfOwnedInstalledTargetBindingV1.accept(SelfOwnedInstalledIdentityFactoryV2.MACOS_SERVICE, value)
            else -> error("fixture")
        }
        return assertIs<SelfOwnedNativeInstalledTargetBindingV1>(assertIs<SelfOwnedInstalledIdentityResultV2.Data<*>>(result).value)
    }
    private fun desktop(value: Any): LocalMasterSdkOriginalV2 =
        LocalMasterSdkOriginalV2.capture(F.id(SupportingPairingId.MASTER_ACQUISITION), F.allocation(), target(value),
            F.master().appId, F.masterPeer, F.masterFingerprint, F.masterQr, "Master desktop")
    private val windowsMaster get() = desktop(SelfOwnedWindowsPackagedAppIdentityV2("Ukkera.POS.Test_n9kkbga0qmnm6",
        "Ukkera.POS.Test_n9kkbga0qmnm6!UkkeraPOS"))
    private val macMaster get() = desktop(SelfOwnedMacServiceIdentityV2("com.ukkera.pos.local.desktop.master", "ABCDE12345",
        "e".repeat(64)))

    private fun record(database: NativeHistoryDatabaseIdentity?, master: LocalMasterSdkOriginal?): NativeHistoryAnchorRecordV2 =
        NativeHistoryAnchorRecordV2(F.allocation(), 1L, 0L, null, database, null, null, null, null, emptyList(), null, null, null,
            recordFormat = if (master == null) SelfOwnedNativeHistoryWireV2.FORMAT
                else SelfOwnedNativeHistoryWireV2.MASTER_SDK_ORIGINAL_RECORD_FORMAT, masterSdkOriginal = master)

    private fun domain(master: LocalMasterSdkOriginal?) =
        if (master == null) "anchor-record-capacity/v2" else "anchor-record-master-sdk-original/v6"

    /** Documented prefix up to (excluding) the database presence byte. */
    private fun prefix(master: LocalMasterSdkOriginal?): ByteArray = DocumentedLayout()
        .string("ukkera.runtime-history/original-local/v2").string(domain(master))
        .int(if (master == null) 2 else 6).field(LocalBranchInstallationWireV2.encodeAllocation(F.allocation()))
        .long(1L).long(0L).bool(false).bytes()

    private fun expected(master: LocalMasterSdkOriginal?, database: ByteArray): ByteArray {
        val absent = SelfOwnedNativeHistoryWireV2.encodeRecord(record(null, master))
        val head = prefix(master)
        assertContentEquals(head, absent.copyOf(head.size))
        assertEquals(0, absent[head.size].toInt())
        return head + byteArrayOf(1) + database + absent.copyOfRange(head.size + 1, absent.size)
    }

    private val posixLayout get() = DocumentedLayout().long(7L).long(42L).int(10_123).string("d".repeat(64)).string(room).bytes()
    private val windowsLayout get() = ByteArray(8) { 0xFF.toByte() } + DocumentedLayout().int(1)
        .field(DocumentedLayout().string("ukkera.history/native-database-identity")
            .string("native-history-database-identity/windows-v1").int(1)
            .string("0123456789abcdef").string("00112233445566778899aabbccddeeff")
            .string("S-1-5-21-1000-2000-3000-1001").bytes())
        .string("d".repeat(64)).string(room).bytes()

    @Test
    fun posixIdentityKeepsItsExactUntaggedBytesInLegacyAndMasterRecords() {
        for (master in listOf<LocalMasterSdkOriginal?>(null, F.master(), macMaster)) {
            val value = record(posix, master)
            val bytes = SelfOwnedNativeHistoryWireV2.encodeRecord(value)
            assertContentEquals(expected(master, posixLayout), bytes)
            val decoded = SelfOwnedNativeHistoryWireV2.decodeRecord(bytes)
            assertEquals(posix, decoded.nativeDatabase)
            assertEquals(posix, decoded.database)
            assertContentEquals(bytes, SelfOwnedNativeHistoryWireV2.encodeRecord(decoded))
        }
    }

    @Test
    fun windowsBindingIsTagDispatchedBehindTheDeviceSentinel() {
        val master = windowsMaster
        val bytes = SelfOwnedNativeHistoryWireV2.encodeRecord(record(windowsBinding, master))
        assertContentEquals(expected(master, windowsLayout), bytes)
        val decoded = SelfOwnedNativeHistoryWireV2.decodeRecord(bytes)
        assertEquals(windowsBinding, decoded.nativeDatabase)
        assertNull(decoded.database) // The POSIX view never stands in for a Windows binding.
        assertContentEquals(bytes, SelfOwnedNativeHistoryWireV2.encodeRecord(decoded))
    }

    @Test
    fun misplacedOrBareIdentitiesAreRefusedAtConstruction() {
        for (master in listOf<LocalMasterSdkOriginal?>(null, F.master(), macMaster))
            assertFails { record(windowsBinding, master) }
        assertFails { record(posix, windowsMaster) }
        for (master in listOf<LocalMasterSdkOriginal?>(null, F.master(), macMaster, windowsMaster))
            assertFails { record(windowsFile, master) }
        assertFails { NativeHistoryDatabaseIdentityV2(-1L, 42L, 10_123, digest, room) }
    }

    @Test
    fun decoderRefusesUnknownTagsMisplacedBindingsAndTruncation() {
        val master = windowsMaster
        val good = expected(master, windowsLayout)
        val tagAt = prefix(master).size + 1 + 8
        val unknownTag = good.copyOf().also { it[tagAt + 3] = 2 }
        assertFails { SelfOwnedNativeHistoryWireV2.decodeRecord(unknownTag) }
        // The same Windows binding bytes inside a non-Windows record are refused, never reinterpreted.
        for (other in listOf<LocalMasterSdkOriginal?>(null, F.master(), macMaster))
            assertFails { SelfOwnedNativeHistoryWireV2.decodeRecord(expected(other, windowsLayout)) }
        // POSIX bytes inside a Windows Master record are refused.
        assertFails { SelfOwnedNativeHistoryWireV2.decodeRecord(expected(master, posixLayout)) }
        assertFails { SelfOwnedNativeHistoryWireV2.decodeRecord(good.copyOf(good.size - 1)) }
        assertFails { SelfOwnedNativeHistoryWireV2.decodeRecord(good + 0) }
    }
}
