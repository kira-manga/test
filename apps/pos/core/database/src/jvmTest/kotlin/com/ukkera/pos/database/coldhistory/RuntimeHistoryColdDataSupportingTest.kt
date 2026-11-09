package com.ukkera.pos.database.coldhistory

import com.ukkera.pos.database.RuntimeHistoryColdMemory
import com.ukkera.pos.database.RuntimeHistoryColdMetadataCodec
import com.ukkera.pos.database.RuntimeHistoryFormatFailureV2
import com.ukkera.pos.database.RuntimeHistoryFormatRejectedV2
import com.ukkera.pos.database.RuntimeHistoryProfileV2
import com.ukkera.pos.database.RuntimeHistoryReadItem
import com.ukkera.pos.database.RuntimeHistoryReadMetadata
import com.ukkera.pos.database.RuntimeHistoryRowCodecV2
import com.ukkera.pos.database.RuntimeHistoryRowViewV2
import com.ukkera.pos.database.RuntimeHistoryTableV2
import com.ukkera.pos.database.RuntimeSqliteTypeV2
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.NativeHistoryCellMetadataV2
import com.ukkera.pos.security.NativeHistoryPhysicalCellV2
import com.ukkera.pos.security.NativeHistoryPhysicalRowV2
import com.ukkera.pos.security.NativeHistoryReadPassV2
import com.ukkera.pos.security.NativeHistoryRowKindV2
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.security.ThrottleDimension
import com.ukkera.pos.security.ThrottleKey
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Supporting DATA checks for frozen cold-history-bounded-workspace-v1:
 * CHB-I01/I02 (exact copied coordinates), CHB-I04/I06/W02 (scratch refusal),
 * CHB-I07/I08 (source-bound byte/capture-count accounting). These are not native admission,
 * production workspace integration, restart, original recovery or measured-resource tests.
 *
 * Public cell/physical-row values below are inert DATA. No native row metadata,
 * source, session, custody, authority or close evidence is constructed or imported.
 * Scratch framing is an executor binding, not an independent persisted-wire oracle.
 * Count fixtures cover retained captures, not transient DATA hydration. Sharing the
 * arithmetic helper does not qualify actual recursive join ownership or retirement.
 *
 * UNBOUND: native schema/payload validity, complete census and event/lineage/original
 * closure, tombstone BEFORE, current authorization/business deltas, real read/close,
 * confidential workspace lifecycle, and qualified platform memory/queue/handle/disk
 * profiles. A supplied decode scope is context, not proof of cross-scope authority.
 */
class RuntimeHistoryColdDataSupportingTest {
    @Test
    fun supportingDeclaredDataLimitsRemainBoundWithoutAPlatformBudgetClaim() {
        assertEquals(METADATA_BYTE_LIMIT, RuntimeHistoryColdMetadataCodec.MAX_BYTES)
        assertEquals(MEMORY_BYTE_LIMIT, RuntimeHistoryColdMemory.MAX_BYTES)
        assertEquals(ROW_BYTE_LIMIT, RuntimeHistoryProfileV2.MAX_ROW_BYTES.toLong())
        assertEquals(65_536, RuntimeHistoryProfileV2.MAX_CHUNK_BYTES)
        assertEquals(1024, RuntimeHistoryProfileV2.MAX_KEY_BYTES)
    }

    @Test
    fun supportingPhysicalCoordinatesRoundTripAllPassesAndSignedRowIds() {
        for (pass in NativeHistoryReadPassV2.entries) {
            for (rowId in listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)) {
                val item = physicalItem(pass, rowId)
                try { assertRoundTrip(item, item.encodedBytes) } finally { item.clear() }
            }
        }
    }

    @Test
    fun supportingImageCoordinatesRoundTripWithoutReplacingTheRowPayload() {
        val item = imageItem()
        try {
            val before = RuntimeHistoryRowCodecV2.encode(item.row.physical)
            try {
                assertRoundTrip(item, item.encodedBytes)
                assertEquals(RuntimeHistoryTableV2.CLAIM, item.row.table)
                assertEquals(-3L, item.row.rowId)
                val after = RuntimeHistoryRowCodecV2.encode(item.row.physical)
                try { assertContentEquals(before, after) } finally { after.fill(0) }
            } finally { before.fill(0) }
        } finally { item.clear() }
    }

    @Test
    fun supportingProtectedBindingCoordinatesRoundTripEverySupportedKey() {
        for ((table, binding) in bindingFixtures()) {
            val bytes = byteArrayOf(1, 2, 3)
            val metadata = coordinates(NativeHistoryRowKindV2.PLAINTEXT, table, -7L,
                listOf(cell("plaintext", RuntimeSqliteTypeV2.BLOB, bytes.size.toLong())), binding)
            val item = RuntimeHistoryReadItem.Plaintext(metadata, bytes)
            try {
                assertRoundTrip(item, 0L)
                assertContentEquals(byteArrayOf(1, 2, 3), bytes)
            } finally { item.clear() }
        }
    }

    @Test
    fun supportingBoundaryCoordinatesRoundTripWithoutInventedRows() {
        for (pass in NativeHistoryReadPassV2.entries) {
            for (kind in listOf(NativeHistoryRowKindV2.GROUP_END, NativeHistoryRowKindV2.PASS_END)) {
                for (table in listOf(null, RuntimeHistoryTableV2.OPERATION)) {
                    val item = RuntimeHistoryReadItem.Boundary(coordinates(kind, table, null, pass = pass))
                    try { assertRoundTrip(item, 0L) } finally { item.clear() }
                }
            }
        }
    }

    @Test
    fun supportingScratchCellAndUtf8NameBoundariesAreExactNotTruncated() {
        // A scratch coordinate vector does not qualify a native table's physical schema.
        val cells = List(CELL_LIMIT) { cell("x".repeat(CELL_NAME_BYTE_LIMIT), RuntimeSqliteTypeV2.BLOB, 1L) }
        val metadata = coordinates(cells = cells)
        assertDecoded(scratch(metadata, CELL_LIMIT.toLong()), metadata, CELL_LIMIT.toLong())

        val utf8Metadata = coordinates(cells = listOf(cell("\u00e9".repeat(64), RuntimeSqliteTypeV2.TEXT, 1L)))
        assertDecoded(scratch(utf8Metadata), utf8Metadata, 1L)
        assertRefused(scratch(coordinates(cells = List(CELL_LIMIT + 1) { cell("x", RuntimeSqliteTypeV2.BLOB, 1L) })),
            RuntimeHistoryFormatFailureV2.MALFORMED)

        for (name in listOf("x".repeat(CELL_NAME_BYTE_LIMIT + 1), "\u00e9".repeat(65))) {
            val invalid = coordinates(cells = listOf(cell(name, RuntimeSqliteTypeV2.TEXT, 1L)))
            assertRefused(scratch(invalid), RuntimeHistoryFormatFailureV2.CAPACITY)
            assertEncodeRefused(RuntimeHistoryReadItem.Boundary(invalid), RuntimeHistoryFormatFailureV2.CAPACITY)
        }
        val excessCells = coordinates(cells = List(CELL_LIMIT + 1) { cell("x", RuntimeSqliteTypeV2.BLOB, 1L) })
        assertEncodeRefused(RuntimeHistoryReadItem.Boundary(excessCells), RuntimeHistoryFormatFailureV2.CAPACITY)

        val nulName = coordinates(cells = listOf(cell("x\u0000y", RuntimeSqliteTypeV2.TEXT, 1L)))
        assertRefused(scratch(nulName), RuntimeHistoryFormatFailureV2.MALFORMED)
        assertEncodeRefused(RuntimeHistoryReadItem.Boundary(nulName), RuntimeHistoryFormatFailureV2.MALFORMED)
        val invalidUtf8 = scratch(coordinates(cells = listOf(cell("x", RuntimeSqliteTypeV2.TEXT, 1L))))
        invalidUtf8[FIRST_CELL_NAME_BYTES_OFFSET] = 0x80.toByte()
        assertRefused(invalidUtf8, RuntimeHistoryFormatFailureV2.MALFORMED)
    }

    @Test
    fun supportingDecoderRefusesOversizeBeforeTreatingItAsShorterData() {
        // At the byte ceiling, malformed content is still malformed, not a valid record.
        assertRefused(ByteArray(METADATA_BYTE_LIMIT), RuntimeHistoryFormatFailureV2.MALFORMED)
        assertRefused(ByteArray(METADATA_BYTE_LIMIT + 1), RuntimeHistoryFormatFailureV2.CAPACITY)
    }

    @Test
    fun supportingDecoderRefusesEveryTruncatedRecordAndTrailingData() {
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.Throttle(
            ThrottleKey(scope, recordId, ThrottleDimension.Device(deviceId))), Revision(0L))
        val items = listOf(
            physicalItem(NativeHistoryReadPassV2.EVENT_CHUNKS, 1L),
            imageItem(),
            RuntimeHistoryReadItem.Plaintext(coordinates(NativeHistoryRowKindV2.PLAINTEXT,
                RuntimeHistoryTableV2.THROTTLE, 1L,
                listOf(cell("plaintext", RuntimeSqliteTypeV2.BLOB, 3L)), binding), byteArrayOf(1, 2, 3)),
            RuntimeHistoryReadItem.Boundary(coordinates(NativeHistoryRowKindV2.PASS_END, null, null)),
        )
        try {
            for (item in items) {
                val encoded = RuntimeHistoryColdMetadataCodec.encode(item, scope)
                try {
                    for (length in 0 until encoded.size) {
                        assertRefused(encoded.copyOf(length), RuntimeHistoryFormatFailureV2.MALFORMED)
                    }
                    assertRefused(encoded + byteArrayOf(0), RuntimeHistoryFormatFailureV2.MALFORMED)
                } finally { encoded.fill(0) }
            }
        } finally { items.forEach { it.clear() } }
    }

    @Test
    fun supportingDecoderRefusesInvalidDiscriminatorsAndPresenceTags() {
        val base = scratch(coordinates())
        try {
            for ((offset, values) in listOf(
                0 to listOf(0, 2),
                4 to listOf(-1, NativeHistoryReadPassV2.entries.size),
                8 to listOf(-1, NativeHistoryRowKindV2.entries.size),
            )) {
                for (value in values) assertRefused(replaceInt(base, offset, value), RuntimeHistoryFormatFailureV2.MALFORMED)
            }
            // Unknown table tags have no independently bound native denial family here.
            assertRefused(replaceInt(base, TABLE_TAG_OFFSET, Int.MAX_VALUE))
            for (offset in listOf(TABLE_PRESENCE_OFFSET, ROW_PRESENCE_OFFSET, base.lastIndex)) {
                assertRefused(base.copyOf().also { it[offset] = 2 }, RuntimeHistoryFormatFailureV2.MALFORMED)
            }
        } finally { base.fill(0) }

        val name = "value"
        val withInteger = scratch(coordinates(cells = listOf(cell(name, RuntimeSqliteTypeV2.INTEGER, 0L, 1L))))
        try {
            val typeOffset = FIRST_CELL_NAME_BYTES_OFFSET + name.length
            assertRefused(replaceInt(withInteger, typeOffset, Int.MAX_VALUE), RuntimeHistoryFormatFailureV2.MALFORMED)
            val integerPresenceOffset = typeOffset + 4 + 8
            assertRefused(withInteger.copyOf().also { it[integerPresenceOffset] = 2 }, RuntimeHistoryFormatFailureV2.MALFORMED)
            assertRefused(replaceInt(withInteger, FIRST_CELL_NAME_LENGTH_OFFSET, -1), RuntimeHistoryFormatFailureV2.MALFORMED)
        } finally { withInteger.fill(0) }
    }

    @Test
    fun supportingDecoderRefusesNegativeExcessLengthsAndCellCounts() {
        for (length in listOf(-1L, Long.MIN_VALUE, ROW_BYTE_LIMIT + 1, Long.MAX_VALUE)) {
            assertRefused(scratch(coordinates(), length), RuntimeHistoryFormatFailureV2.MALFORMED)
            assertRefused(scratch(coordinates(cells = listOf(cell("x", RuntimeSqliteTypeV2.BLOB, length)))),
                RuntimeHistoryFormatFailureV2.MALFORMED)
        }
        val bounded = coordinates(cells = listOf(cell("x", RuntimeSqliteTypeV2.BLOB, ROW_BYTE_LIMIT)))
        assertDecoded(scratch(bounded, ROW_BYTE_LIMIT), bounded, ROW_BYTE_LIMIT)
        val base = scratch(coordinates())
        try {
            for (count in listOf(-1, CELL_LIMIT + 1, Int.MAX_VALUE)) {
                assertRefused(replaceInt(base, CELL_COUNT_OFFSET, count), RuntimeHistoryFormatFailureV2.MALFORMED)
            }
        } finally { base.fill(0) }
    }

    @Test
    fun supportingDecoderRefusesContradictoryRowKindCoordinates() {
        val blob = cell("image", RuntimeSqliteTypeV2.BLOB, 1L)
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.Account(scope, recordId), Revision(0L))
        for (kind in listOf(NativeHistoryRowKindV2.GROUP_END, NativeHistoryRowKindV2.PASS_END)) {
            assertRefused(scratch(coordinates(kind, null, 1L), 0L), RuntimeHistoryFormatFailureV2.MALFORMED)
            assertRefused(scratch(coordinates(kind, null, null, listOf(blob)), 0L), RuntimeHistoryFormatFailureV2.MALFORMED)
            assertRefused(scratch(coordinates(kind, null, null, binding = binding), 0L), RuntimeHistoryFormatFailureV2.MALFORMED)
            assertRefused(scratch(coordinates(kind, null, null), 1L), RuntimeHistoryFormatFailureV2.MALFORMED)
        }
        for (metadata in listOf(coordinates(table = null), coordinates(rowId = null), coordinates(binding = binding))) {
            assertRefused(scratch(metadata), RuntimeHistoryFormatFailureV2.MALFORMED)
        }
        assertRefused(scratch(coordinates(), 0L), RuntimeHistoryFormatFailureV2.MALFORMED)

        for (metadata in listOf(
            coordinates(NativeHistoryRowKindV2.IMAGE, RuntimeHistoryTableV2.CHUNK, 1L, listOf(blob)),
            coordinates(NativeHistoryRowKindV2.IMAGE, RuntimeHistoryTableV2.CLAIM, null, listOf(blob)),
            coordinates(NativeHistoryRowKindV2.IMAGE, RuntimeHistoryTableV2.CLAIM, 1L, listOf(blob), binding),
            coordinates(NativeHistoryRowKindV2.IMAGE, RuntimeHistoryTableV2.CLAIM, 1L),
            coordinates(NativeHistoryRowKindV2.IMAGE, RuntimeHistoryTableV2.CLAIM, 1L, listOf(blob, blob)),
            coordinates(NativeHistoryRowKindV2.IMAGE, RuntimeHistoryTableV2.CLAIM, 1L, listOf(cell("other", RuntimeSqliteTypeV2.BLOB, 1L))),
            coordinates(NativeHistoryRowKindV2.IMAGE, RuntimeHistoryTableV2.CLAIM, 1L, listOf(cell("image", RuntimeSqliteTypeV2.TEXT, 1L))),
            coordinates(NativeHistoryRowKindV2.IMAGE, RuntimeHistoryTableV2.CLAIM, 1L, listOf(cell("image", RuntimeSqliteTypeV2.BLOB, 2L))),
        )) assertRefused(scratch(metadata), RuntimeHistoryFormatFailureV2.MALFORMED)

        val plaintext = cell("plaintext", RuntimeSqliteTypeV2.BLOB, 3L)
        for (metadata in listOf(
            coordinates(NativeHistoryRowKindV2.PLAINTEXT, RuntimeHistoryTableV2.ACCOUNT, 1L, listOf(plaintext)),
            coordinates(NativeHistoryRowKindV2.PLAINTEXT, RuntimeHistoryTableV2.POLICY, 1L, listOf(plaintext), binding),
            coordinates(NativeHistoryRowKindV2.PLAINTEXT, RuntimeHistoryTableV2.ACCOUNT, null, listOf(plaintext), binding),
            coordinates(NativeHistoryRowKindV2.PLAINTEXT, RuntimeHistoryTableV2.ACCOUNT, 1L, binding = binding),
            coordinates(NativeHistoryRowKindV2.PLAINTEXT, RuntimeHistoryTableV2.ACCOUNT, 1L, listOf(blob), binding),
            coordinates(NativeHistoryRowKindV2.PLAINTEXT, RuntimeHistoryTableV2.ACCOUNT, 1L,
                listOf(cell("plaintext", RuntimeSqliteTypeV2.TEXT, 3L)), binding),
        )) assertRefused(scratch(metadata, 0L), RuntimeHistoryFormatFailureV2.MALFORMED)
        val validPlaintext = coordinates(NativeHistoryRowKindV2.PLAINTEXT, RuntimeHistoryTableV2.ACCOUNT, 1L, listOf(plaintext), binding)
        assertRefused(scratch(validPlaintext, 1L), RuntimeHistoryFormatFailureV2.MALFORMED)
    }

    @Test
    fun supportingBindingEncodingRefusesForeignScopeAndMalformedSelectors() {
        val foreignScope = scope.copy(branchId = otherBranchId)
        val foreignBinding = ProtectedLocalBinding(ProtectedLocalRecordKey.Account(foreignScope, recordId), Revision(0L))
        val plaintext = listOf(cell("plaintext", RuntimeSqliteTypeV2.BLOB, 3L))
        assertEncodeRefused(RuntimeHistoryReadItem.Plaintext(coordinates(NativeHistoryRowKindV2.PLAINTEXT,
            RuntimeHistoryTableV2.ACCOUNT, 1L, plaintext, foreignBinding), byteArrayOf(1, 2, 3)),
            RuntimeHistoryFormatFailureV2.MALFORMED)

        for ((table, binding) in bindingFixtures()) {
            val metadata = coordinates(NativeHistoryRowKindV2.PLAINTEXT, table, 1L, plaintext, binding)
            assertRefused(scratch(metadata, 0L, bindingRevision = -1L))
        }
        val account = ProtectedLocalBinding(ProtectedLocalRecordKey.Account(scope, recordId), Revision(0L))
        val accountMetadata = coordinates(NativeHistoryRowKindV2.PLAINTEXT, RuntimeHistoryTableV2.ACCOUNT, 1L, plaintext, account)
        assertRefused(scratch(accountMetadata, 0L, bindingId = "A" + recordId.value.drop(1)), RuntimeHistoryFormatFailureV2.MALFORMED)
        assertRefused(scratch(accountMetadata, 0L, bindingTableOrdinal = -1), RuntimeHistoryFormatFailureV2.MALFORMED)

        for ((table, binding) in bindingFixtures().filter {
            it.second.key is ProtectedLocalRecordKey.SessionIssuance || it.second.key is ProtectedLocalRecordKey.RefreshIssuance ||
                it.second.key is ProtectedLocalRecordKey.AuthEvent || it.second.key is ProtectedLocalRecordKey.SessionArchive
        }) {
            val metadata = coordinates(NativeHistoryRowKindV2.PLAINTEXT, table, 1L, plaintext, binding)
            assertRefused(scratch(metadata, 0L, bindingRevision = 1L))
        }
        val approval = ProtectedLocalBinding(ProtectedLocalRecordKey.ActionApproval(scope, recordId), Revision(0L))
        assertRefused(scratch(coordinates(NativeHistoryRowKindV2.PLAINTEXT, RuntimeHistoryTableV2.APPROVAL,
            1L, plaintext, approval), 0L, bindingRevision = 2L))
        val throttle = ProtectedLocalBinding(ProtectedLocalRecordKey.Throttle(
            ThrottleKey(scope, recordId, ThrottleDimension.Account)), Revision(0L))
        val invalidDimension = scratch(coordinates(NativeHistoryRowKindV2.PLAINTEXT,
            RuntimeHistoryTableV2.THROTTLE, 1L, plaintext, throttle), 0L)
        invalidDimension[invalidDimension.lastIndex] = 2
        assertRefused(invalidDimension, RuntimeHistoryFormatFailureV2.MALFORMED)
    }

    @Test
    fun supportingMemoryReserveReleaseIsExactAtTheDeclaredCeiling() {
        val memory = RuntimeHistoryColdMemory()
        assertEquals(0L, memory.retainedBytes)
        memory.reserve(0L)
        memory.release(0L)
        assertEquals(0L, memory.retainedBytes)
        val metadata = 4L * METADATA_BYTE_LIMIT
        memory.reserve(metadata)
        assertEquals(metadata, memory.retainedBytes)
        memory.reserve(65_536L)
        assertEquals(metadata + 65_536L, memory.retainedBytes)
        memory.reserve(ROW_BYTE_LIMIT)
        val subtotal = metadata + 65_536L + ROW_BYTE_LIMIT
        assertEquals(subtotal, memory.retainedBytes)
        memory.reserve(MEMORY_BYTE_LIMIT - subtotal)
        assertEquals(MEMORY_BYTE_LIMIT, memory.retainedBytes)
        memory.reserve(0L)
        memory.release(0L)
        assertEquals(MEMORY_BYTE_LIMIT, memory.retainedBytes)
        memory.release(ROW_BYTE_LIMIT)
        assertEquals(MEMORY_BYTE_LIMIT - ROW_BYTE_LIMIT, memory.retainedBytes)
        memory.release(MEMORY_BYTE_LIMIT - subtotal)
        assertEquals(metadata + 65_536L, memory.retainedBytes)
        memory.release(metadata)
        assertEquals(65_536L, memory.retainedBytes)
        memory.release(65_536L)
        assertEquals(0L, memory.retainedBytes)
    }

    @Test
    fun supportingMemoryRefusesInvalidTransitionsWithoutChangingOwnedBytes() {
        val memory = RuntimeHistoryColdMemory()
        memory.reserve(7L)
        for (bytes in listOf(-1L, Long.MIN_VALUE)) {
            assertEquals(RuntimeHistoryFormatFailureV2.MALFORMED,
                assertFailsWith<RuntimeHistoryFormatRejectedV2> { memory.reserve(bytes) }.failure)
            assertEquals(7L, memory.retainedBytes)
        }
        for (bytes in listOf(MEMORY_BYTE_LIMIT - 6L, MEMORY_BYTE_LIMIT + 1L, Long.MAX_VALUE)) {
            assertEquals(RuntimeHistoryFormatFailureV2.CAPACITY,
                assertFailsWith<RuntimeHistoryFormatRejectedV2> { memory.reserve(bytes) }.failure)
            assertEquals(7L, memory.retainedBytes)
        }
        for (bytes in listOf(-1L, Long.MIN_VALUE, 8L, Long.MAX_VALUE)) {
            assertEquals(RuntimeHistoryFormatFailureV2.MALFORMED,
                assertFailsWith<RuntimeHistoryFormatRejectedV2> { memory.release(bytes) }.failure)
            assertEquals(7L, memory.retainedBytes)
        }
        memory.reserve(MEMORY_BYTE_LIMIT - 7L)
        assertEquals(MEMORY_BYTE_LIMIT, memory.retainedBytes)
        assertEquals(RuntimeHistoryFormatFailureV2.CAPACITY,
            assertFailsWith<RuntimeHistoryFormatRejectedV2> { memory.reserve(1L) }.failure)
        assertEquals(MEMORY_BYTE_LIMIT, memory.retainedBytes)
        memory.release(MEMORY_BYTE_LIMIT)
        assertEquals(0L, memory.retainedBytes)
        assertFailsWith<RuntimeHistoryFormatRejectedV2> { memory.release(1L) }
        assertEquals(0L, memory.retainedBytes)
    }

    @Test
    fun supportingRepeatedAccountingScopesReturnToZeroAndStayIndependent() {
        val first = RuntimeHistoryColdMemory()
        val second = RuntimeHistoryColdMemory()
        repeat(10) {
            first.reserve(MEMORY_BYTE_LIMIT)
            second.reserve(1L)
            assertEquals(MEMORY_BYTE_LIMIT, first.retainedBytes)
            assertEquals(1L, second.retainedBytes)
            first.release(MEMORY_BYTE_LIMIT)
            assertEquals(0L, first.retainedBytes)
            assertEquals(1L, second.retainedBytes)
            second.release(1L)
            assertEquals(0L, second.retainedBytes)
        }
        // Arithmetic reuse is not evidence of real buffer clearing, teardown or handle cleanup.
    }

    @Test
    fun supportingRetainedCaptureCountLimitsRemainUnchangedWithoutPlatformBudgetClaim() {
        assertEquals(COMMAND_COUNT_LIMIT, RuntimeHistoryColdMemory.MAX_COMMANDS)
        assertEquals(CAPTURE_VERSION_COUNT_LIMIT, RuntimeHistoryColdMemory.MAX_VERSIONS)
        assertCountBudget(RuntimeHistoryColdMemory(), 0, 0)
    }

    @Test
    fun supportingCommandCountCeilingRejectsAtomicallyAndAllowsReuse() {
        val memory = RuntimeHistoryColdMemory()
        memory.reserve(17L)
        repeat(COMMAND_COUNT_LIMIT) { at ->
            memory.reserveOwnedCommand(1)
            assertCountBudget(memory, at + 1, at + 1, 17L)
        }
        for (versions in listOf(0, 1, CAPTURE_VERSION_COUNT_LIMIT, Int.MAX_VALUE)) {
            assertCountRefused(memory, RuntimeHistoryFormatFailureV2.CAPACITY) { memory.reserveOwnedCommand(versions) }
        }
        memory.releaseOwnedCommand(1)
        assertCountBudget(memory, 255, 255, 17L)
        memory.reserveOwnedCommand(1)
        assertCountBudget(memory, COMMAND_COUNT_LIMIT, 256, 17L)
        repeat(COMMAND_COUNT_LIMIT) { memory.releaseOwnedCommand(1) }
        assertCountBudget(memory, 0, 0, 17L)
        memory.release(17L)
        assertCountBudget(memory, 0, 0)
    }

    @Test
    fun supportingCaptureVersionCeilingRejectsAtomicallyAndAllowsReuse() {
        val memory = RuntimeHistoryColdMemory()
        memory.reserve(29L)
        // 128 accounting captures of 32 retained versions: no command payload is constructed.
        repeat(128) { memory.reserveOwnedCommand(32) }
        assertCountBudget(memory, 128, CAPTURE_VERSION_COUNT_LIMIT, 29L)
        for (versions in listOf(1, 32, CAPTURE_VERSION_COUNT_LIMIT, Int.MAX_VALUE)) {
            assertCountRefused(memory, RuntimeHistoryFormatFailureV2.CAPACITY) { memory.reserveOwnedCommand(versions) }
        }
        memory.releaseOwnedCommand(32)
        assertCountBudget(memory, 127, 4064, 29L)
        assertCountRefused(memory, RuntimeHistoryFormatFailureV2.CAPACITY) { memory.reserveOwnedCommand(33) }
        memory.reserveOwnedCommand(32)
        assertCountBudget(memory, 128, CAPTURE_VERSION_COUNT_LIMIT, 29L)
        repeat(128) { memory.releaseOwnedCommand(32) }
        assertCountBudget(memory, 0, 0, 29L)
        memory.release(29L)
        assertCountBudget(memory, 0, 0)
    }

    @Test
    fun supportingSharedCaptureCallersCannotEachClaimAnIndependentCountBudget() {
        val shared = RuntimeHistoryColdMemory()
        val separate = RuntimeHistoryColdMemory()
        // These are two callers of ONE arithmetic budget, not native original/join owners.
        val firstCaller: (Int) -> Unit = shared::reserveOwnedCommand
        val secondCaller: (Int) -> Unit = shared::reserveOwnedCommand
        shared.reserve(83L)
        separate.reserve(7L)
        separate.reserveOwnedCommand(1)
        firstCaller(1024)
        assertCountBudget(shared, 1, 1024, 83L)
        secondCaller(1024)
        assertCountBudget(shared, 2, 2048, 83L)
        firstCaller(1024)
        assertCountBudget(shared, 3, 3072, 83L)
        secondCaller(1024)
        assertCountBudget(shared, 4, CAPTURE_VERSION_COUNT_LIMIT, 83L)
        assertCountBudget(separate, 1, 1, 7L)
        assertCountRefused(shared, RuntimeHistoryFormatFailureV2.CAPACITY) { firstCaller(1) }
        assertCountRefused(shared, RuntimeHistoryFormatFailureV2.CAPACITY) { secondCaller(1) }
        shared.releaseOwnedCommand(1024)
        assertCountBudget(shared, 3, 3072, 83L)
        secondCaller(1024)
        assertCountBudget(shared, 4, CAPTURE_VERSION_COUNT_LIMIT, 83L)
        repeat(4) { shared.releaseOwnedCommand(1024) }
        assertCountBudget(shared, 0, 0, 83L)
        assertCountBudget(separate, 1, 1, 7L)
        separate.releaseOwnedCommand(1)
        shared.release(83L)
        separate.release(7L)
        assertCountBudget(shared, 0, 0)
        assertCountBudget(separate, 0, 0)
    }

    @Test
    fun supportingCountBudgetInvalidTransitionsLeaveAllCountersUnchanged() {
        val memory = RuntimeHistoryColdMemory()
        memory.reserve(11L)
        for (versions in listOf(-1, Int.MIN_VALUE)) {
            assertCountRefused(memory, RuntimeHistoryFormatFailureV2.MALFORMED) { memory.reserveOwnedCommand(versions) }
        }
        for (versions in listOf(CAPTURE_VERSION_COUNT_LIMIT + 1, Int.MAX_VALUE)) {
            assertCountRefused(memory, RuntimeHistoryFormatFailureV2.CAPACITY) { memory.reserveOwnedCommand(versions) }
        }
        for (versions in listOf(-1, Int.MIN_VALUE, 0, 1, CAPTURE_VERSION_COUNT_LIMIT, Int.MAX_VALUE)) {
            assertCountRefused(memory, RuntimeHistoryFormatFailureV2.MALFORMED) { memory.releaseOwnedCommand(versions) }
        }
        memory.reserveOwnedCommand(2)
        assertCountBudget(memory, 1, 2, 11L)
        for (versions in listOf(-1, Int.MIN_VALUE)) {
            assertCountRefused(memory, RuntimeHistoryFormatFailureV2.MALFORMED) { memory.reserveOwnedCommand(versions) }
        }
        for (versions in listOf(-1, Int.MIN_VALUE, 3, Int.MAX_VALUE)) {
            assertCountRefused(memory, RuntimeHistoryFormatFailureV2.MALFORMED) { memory.releaseOwnedCommand(versions) }
        }
        assertCountRefused(memory, RuntimeHistoryFormatFailureV2.CAPACITY) { memory.reserve(MEMORY_BYTE_LIMIT) }
        assertCountRefused(memory, RuntimeHistoryFormatFailureV2.MALFORMED) { memory.release(12L) }
        memory.releaseOwnedCommand(2)
        assertCountBudget(memory, 0, 0, 11L)
        // Zero versions still consumes one accounting command slot; this is not a valid-native-command claim.
        memory.reserveOwnedCommand(0)
        assertCountBudget(memory, 1, 0, 11L)
        assertCountRefused(memory, RuntimeHistoryFormatFailureV2.MALFORMED) { memory.releaseOwnedCommand(1) }
        memory.releaseOwnedCommand(0)
        assertCountBudget(memory, 0, 0, 11L)
        memory.release(11L)
        assertCountBudget(memory, 0, 0)
    }

    @Test
    fun supportingCountBudgetReleaseCyclesReturnToZeroAndLeaveOtherBudgetsUntouched() {
        val first = RuntimeHistoryColdMemory()
        val second = RuntimeHistoryColdMemory()
        repeat(10) {
            first.reserve(31L)
            second.reserve(7L)
            repeat(COMMAND_COUNT_LIMIT) { first.reserveOwnedCommand(16) }
            second.reserveOwnedCommand(1)
            assertCountBudget(first, COMMAND_COUNT_LIMIT, CAPTURE_VERSION_COUNT_LIMIT, 31L)
            assertCountBudget(second, 1, 1, 7L)
            repeat(COMMAND_COUNT_LIMIT) { first.releaseOwnedCommand(16) }
            assertCountBudget(first, 0, 0, 31L)
            assertCountBudget(second, 1, 1, 7L)
            first.reserveOwnedCommand(1)
            assertCountBudget(first, 1, 1, 31L)
            first.releaseOwnedCommand(1)
            second.releaseOwnedCommand(1)
            first.release(31L)
            second.release(7L)
            assertCountBudget(first, 0, 0)
            assertCountBudget(second, 0, 0)
        }
        // Ten arithmetic cycles do not prove native teardown, cancellation or measured-resource bounds.
    }

    private fun assertCountBudget(memory: RuntimeHistoryColdMemory, commands: Int, versions: Int, bytes: Long = 0L) {
        assertEquals(commands, memory.retainedCommands)
        assertEquals(versions, memory.retainedVersions)
        assertEquals(bytes, memory.retainedBytes)
    }

    private fun assertCountRefused(memory: RuntimeHistoryColdMemory, failure: RuntimeHistoryFormatFailureV2, transition: () -> Unit) {
        val commands = memory.retainedCommands
        val versions = memory.retainedVersions
        val bytes = memory.retainedBytes
        assertEquals(failure, assertFailsWith<RuntimeHistoryFormatRejectedV2> { transition() }.failure)
        assertCountBudget(memory, commands, versions, bytes)
    }

    private val scope = BranchScope(id(1), id(2), id(3))
    private val recordId = id(4)
    private val deviceId = id(5)
    private val otherBranchId = id(6)

    private fun bindingFixtures(): List<Pair<RuntimeHistoryTableV2, ProtectedLocalBinding>> {
        val mutableRevisions = listOf(0L, Long.MAX_VALUE)
        val keys: List<Triple<RuntimeHistoryTableV2, ProtectedLocalRecordKey, List<Long>>> = listOf(
            Triple(RuntimeHistoryTableV2.ACCOUNT, ProtectedLocalRecordKey.Account(scope, recordId), mutableRevisions),
            Triple(RuntimeHistoryTableV2.POLICY, ProtectedLocalRecordKey.EffectivePolicy(scope, recordId), mutableRevisions),
            Triple(RuntimeHistoryTableV2.ADMISSION, ProtectedLocalRecordKey.Admission(scope, recordId), mutableRevisions),
            Triple(RuntimeHistoryTableV2.THROTTLE, ProtectedLocalRecordKey.Throttle(ThrottleKey(scope, recordId, ThrottleDimension.Account)), mutableRevisions),
            Triple(RuntimeHistoryTableV2.THROTTLE, ProtectedLocalRecordKey.Throttle(ThrottleKey(scope, recordId, ThrottleDimension.Device(deviceId))), mutableRevisions),
            Triple(RuntimeHistoryTableV2.ACCESS, ProtectedLocalRecordKey.SessionIssuance(scope, recordId), listOf(0L)),
            Triple(RuntimeHistoryTableV2.CONFIGURATION, ProtectedLocalRecordKey.AuthConfiguration(scope, recordId), mutableRevisions),
            Triple(RuntimeHistoryTableV2.REFRESH, ProtectedLocalRecordKey.RefreshIssuance(scope, recordId), listOf(0L)),
            Triple(RuntimeHistoryTableV2.AUTH_EVENT, ProtectedLocalRecordKey.AuthEvent(scope, recordId), listOf(0L)),
            Triple(RuntimeHistoryTableV2.ARCHIVE, ProtectedLocalRecordKey.SessionArchive(scope, recordId), listOf(0L)),
            Triple(RuntimeHistoryTableV2.APPROVAL, ProtectedLocalRecordKey.ActionApproval(scope, recordId), listOf(0L, 1L)),
        )
        return keys.flatMap { (table, key, revisions) -> revisions.map { table to ProtectedLocalBinding(key, Revision(it)) } }
    }

    private fun physicalItem(pass: NativeHistoryReadPassV2, rowId: Long): RuntimeHistoryReadItem.Row = rowItem(
        pass, NativeHistoryRowKindV2.PHYSICAL, RuntimeHistoryTableV2.CHUNK, rowId,
        listOf(NativeHistoryPhysicalCellV2.Integer(1L), NativeHistoryPhysicalCellV2.Integer(0L),
            NativeHistoryPhysicalCellV2.Text("0".repeat(64).encodeToByteArray()),
            NativeHistoryPhysicalCellV2.Blob(byteArrayOf(0, 1, 2))),
    ) {
        listOf(cell("sequence", RuntimeSqliteTypeV2.INTEGER, 0L, 1L), cell("ordinal", RuntimeSqliteTypeV2.INTEGER, 0L, 0L),
            cell("digest", RuntimeSqliteTypeV2.TEXT, 64L), cell("bytes", RuntimeSqliteTypeV2.BLOB, 3L))
    }

    private fun imageItem(): RuntimeHistoryReadItem.Row = rowItem(
        NativeHistoryReadPassV2.LINEAGE_IMAGE, NativeHistoryRowKindV2.IMAGE, RuntimeHistoryTableV2.CLAIM, -3L,
        listOf("${scope.organizationId.value}/${scope.companyId.value}/${scope.branchId.value}",
            "supporting", "scratch-image", recordId.value).map { NativeHistoryPhysicalCellV2.Text(it.encodeToByteArray()) },
    ) { length -> listOf(cell("image", RuntimeSqliteTypeV2.BLOB, length)) }

    private fun rowItem(pass: NativeHistoryReadPassV2, kind: NativeHistoryRowKindV2, table: RuntimeHistoryTableV2,
        rowId: Long, cells: List<NativeHistoryPhysicalCellV2>, metadataCells: (Long) -> List<NativeHistoryCellMetadataV2>): RuntimeHistoryReadItem.Row {
        val physical = NativeHistoryPhysicalRowV2(table, rowId, cells)
        try {
            // Production row encoding binds synthetic input bytes/length, not a native acceptance oracle.
            val bytes = RuntimeHistoryRowCodecV2.encode(physical)
            return try {
                val length = bytes.size.toLong()
                RuntimeHistoryReadItem.Row(coordinates(kind, table, rowId, metadataCells(length), pass = pass),
                    RuntimeHistoryRowViewV2(physical), length)
            } finally { bytes.fill(0) }
        } catch (failure: Throwable) { physical.clear(); throw failure }
    }

    private fun coordinates(kind: NativeHistoryRowKindV2 = NativeHistoryRowKindV2.PHYSICAL,
        table: RuntimeHistoryTableV2? = RuntimeHistoryTableV2.CHUNK, rowId: Long? = 1L,
        cells: List<NativeHistoryCellMetadataV2> = emptyList(), binding: ProtectedLocalBinding? = null,
        pass: NativeHistoryReadPassV2 = NativeHistoryReadPassV2.CENSUS): RuntimeHistoryReadMetadata =
        RuntimeHistoryReadMetadata(pass, kind, table, rowId, cells, binding)

    private fun cell(name: String, type: RuntimeSqliteTypeV2, bytes: Long, integer: Long? = null): NativeHistoryCellMetadataV2 =
        NativeHistoryCellMetadataV2(name, type, bytes, integer)

    private fun assertRoundTrip(item: RuntimeHistoryReadItem, encodedBytes: Long) =
        assertDecoded(RuntimeHistoryColdMetadataCodec.encode(item, scope), item.metadata, encodedBytes)

    private fun assertDecoded(bytes: ByteArray, expected: RuntimeHistoryReadMetadata, encodedBytes: Long) {
        val before = bytes.copyOf()
        try {
            val decoded = RuntimeHistoryColdMetadataCodec.decode(bytes, scope)
            assertEquals(expected.pass, decoded.metadata.pass)
            assertEquals(expected.kind, decoded.metadata.kind)
            assertEquals(expected.table, decoded.metadata.table)
            assertEquals(expected.rowId, decoded.metadata.rowId)
            assertEquals(expected.cells, decoded.metadata.cells)
            assertEquals(expected.protectedBinding, decoded.metadata.protectedBinding)
            assertEquals(encodedBytes, decoded.encodedBytes)
            assertContentEquals(before, bytes, "Decode must not clear or mutate borrowed encoded DATA")
        } finally { before.fill(0); bytes.fill(0) }
    }

    private fun assertRefused(bytes: ByteArray, expectedFailure: RuntimeHistoryFormatFailureV2? = null) {
        val before = bytes.copyOf()
        try {
            if (expectedFailure == null) {
                assertFailsWith<Exception> { RuntimeHistoryColdMetadataCodec.decode(bytes, scope) }
            } else {
                assertEquals(expectedFailure,
                    assertFailsWith<RuntimeHistoryFormatRejectedV2> { RuntimeHistoryColdMetadataCodec.decode(bytes, scope) }.failure)
            }
            assertContentEquals(before, bytes, "Refusal must not clear or mutate borrowed encoded DATA")
        } finally { before.fill(0); bytes.fill(0) }
    }

    private fun assertEncodeRefused(item: RuntimeHistoryReadItem, expectedFailure: RuntimeHistoryFormatFailureV2) {
        try {
            assertEquals(expectedFailure, assertFailsWith<RuntimeHistoryFormatRejectedV2> {
                RuntimeHistoryColdMetadataCodec.encode(item, scope).also { it.fill(0) }
            }.failure)
        } finally { item.clear() }
    }

    /** Test-side big-endian scratch framing only; never a native persisted frame or proof. */
    private fun scratch(metadata: RuntimeHistoryReadMetadata, encodedBytes: Long = 1L,
        bindingRevision: Long = metadata.protectedBinding?.revision?.value ?: 0L,
        bindingId: String = metadata.protectedBinding?.key?.recordId?.value ?: "",
        bindingTableOrdinal: Int = metadata.protectedBinding?.table?.ordinal ?: 0): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeInt(metadata.pass.ordinal)
            out.writeInt(metadata.kind.ordinal)
            out.writeByte(if (metadata.table == null) 0 else 1)
            metadata.table?.let { out.writeInt(it.tag) }
            out.writeByte(if (metadata.rowId == null) 0 else 1)
            metadata.rowId?.let { out.writeLong(it) }
            out.writeLong(encodedBytes)
            out.writeInt(metadata.cells.size)
            for (cell in metadata.cells) {
                writeText(out, cell.name)
                out.writeInt(cell.type.tag)
                out.writeLong(cell.byteCount)
                out.writeByte(if (cell.integerValue == null) 0 else 1)
                cell.integerValue?.let { out.writeLong(it) }
            }
            val binding = metadata.protectedBinding
            out.writeByte(if (binding == null) 0 else 1)
            if (binding != null) {
                out.writeInt(bindingTableOrdinal)
                writeText(out, bindingId)
                out.writeLong(bindingRevision)
                val key = binding.key
                if (key is ProtectedLocalRecordKey.Throttle) {
                    when (val dimension = key.key.dimension) {
                        ThrottleDimension.Account -> out.writeByte(0)
                        is ThrottleDimension.Device -> { out.writeByte(1); writeText(out, dimension.deviceId.value) }
                    }
                }
            }
        }
        return bytes.toByteArray()
    }

    private fun writeText(out: DataOutputStream, value: String) {
        val bytes = value.encodeToByteArray()
        try { out.writeInt(bytes.size); out.write(bytes) } finally { bytes.fill(0) }
    }

    private fun replaceInt(bytes: ByteArray, offset: Int, value: Int): ByteArray = bytes.copyOf().also { result ->
        for (index in 0..3) result[offset + index] = (value ushr (24 - index * 8)).toByte()
    }

    private fun id(value: Int): DomainId = DomainId("00000000-0000-4000-8000-${value.toString().padStart(12, '0')}")

    private companion object {
        // Source-bound DATA limits, not a qualified platform resource profile.
        const val METADATA_BYTE_LIMIT = 16 * 1024
        const val MEMORY_BYTE_LIMIT = 64L * 1024 * 1024
        const val COMMAND_COUNT_LIMIT = 256
        const val CAPTURE_VERSION_COUNT_LIMIT = 4096
        const val ROW_BYTE_LIMIT = 5L * 1024 * 1024
        const val CELL_LIMIT = 64
        const val CELL_NAME_BYTE_LIMIT = 128
        // Offsets apply only to this scratch fixture with present table and signed row ID.
        const val TABLE_PRESENCE_OFFSET = 12
        const val TABLE_TAG_OFFSET = 13
        const val ROW_PRESENCE_OFFSET = 17
        const val CELL_COUNT_OFFSET = 34
        const val FIRST_CELL_NAME_LENGTH_OFFSET = 38
        const val FIRST_CELL_NAME_BYTES_OFFSET = 42
    }
}
