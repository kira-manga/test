package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Synthetic codec vectors, not authorization, complete history or current domain state. */
class RetainedCommandCodecTest {
    @Test fun validApplyExposesOnlyOriginalBindingsAndOpaqueAfterBytes() {
        val pair = retainedTestPair()
        val evidence = assertNotNull(pair.decode())
        assertEquals(RETAINED_TEST_SCOPE, evidence.scope)
        assertEquals(RETAINED_TEST_OPERATION, evidence.operationId)
        assertEquals(retainedTestId(6), evidence.originalActorId)
        assertEquals(CommandKey("qualification.original", 1), evidence.command)
        assertEquals(retainedTestId(4), evidence.assignmentId)
        assertEquals(0L, evidence.authorityEpoch.value)
        assertEquals(1L, evidence.sequence.value)
        assertEquals(retainedTestId(7), evidence.authenticationId)
        assertEquals(0L, evidence.policyRevision.value)
        assertEquals("2026-10-23T12:00:00Z", evidence.recordedAt.toString())
        assertEquals(pair.operation.digest, evidence.semanticDigest.value)
        assertEquals(pair.journal.hash, evidence.journalHash.value)
        assertEquals("0".repeat(64), evidence.previousJournalHash.value)
        assertTrue(evidence.committed)
        assertContentEquals(byteArrayOf(-1, 0, 10), evidence.normalized.copyBytes())
        assertContentEquals(byteArrayOf(0, -1), evidence.result.copyBytes())
        val write = evidence.aggregateWrites.single()
        assertEquals(1L, write.storageGeneration.value)
        assertEquals("unregistered.schema/v1", write.schema)
        assertContentEquals(byteArrayOf(-1, 0), write.payload.copyBytes())
        assertEquals(sha256(write.payload.copyBytes()), write.payloadDigest)
    }

    @Test fun rejectedOutcomeAllowsEmptyBinaryValuesButNeverEffectsOrJobs() {
        val evidence = assertNotNull(retainedTestPair(code = "business.refused", effects = emptyList(),
            normalized = byteArrayOf(), result = byteArrayOf()).decode())
        assertFalse(evidence.committed)
        assertEquals("business.refused", evidence.code)
        assertTrue(evidence.aggregateWrites.isEmpty() && evidence.claimChanges.isEmpty() && evidence.jobs.isEmpty())
        assertEquals(0, evidence.normalized.size)
        assertEquals(0, evidence.result.size)
        retainedTestPair(code = "business.refused").invalid()
        retainedTestPair(code = "business.refused", effects = listOf(retainedTestClaim())).invalid()
        retainedTestPair(code = "business.refused", effects = emptyList(), jobs = listOf(retainedTestJob())).invalid()
    }

    @Test fun committedOutcomeRequiresAnEffectButClaimOnlyIsValid() {
        retainedTestPair(effects = emptyList()).invalid()
        retainedTestPair(effects = emptyList(), jobs = listOf(retainedTestJob())).invalid()
        val evidence = assertNotNull(retainedTestPair(effects = listOf(retainedTestClaim(tag = "claim.release"))).decode())
        assertTrue(evidence.aggregateWrites.isEmpty())
        assertEquals(RetainedClaimAction.RELEASE, evidence.claimChanges.single().action)
        assertEquals(retainedTestId(10), evidence.claimChanges.single().ownerId)
    }

    @Test fun effectArityNotAReservedAggregateKindDeterminesClaimInterpretation() {
        for (kind in listOf("claim.acquire", "claim.release")) {
            val evidence = assertNotNull(retainedTestPair(effects = listOf(
                retainedTestWrite(kind = kind), retainedTestClaim(),
            )).decode())
            assertEquals(kind, evidence.aggregateWrites.single().key.kind)
            assertEquals(RetainedClaimAction.ACQUIRE, evidence.claimChanges.single().action)
        }
        retainedTestPair(effects = listOf(retainedTestClaim(tag = "claim.replace"))).invalid()
    }

    @Test fun aggregateOrderIsStrictByKindThenIdAndCannotFollowClaims() {
        val a = retainedTestWrite(id = 10, kind = "a")
        val b = retainedTestWrite(id = 11, kind = "a")
        val c = retainedTestWrite(id = 1, kind = "b")
        assertEquals(3, assertNotNull(retainedTestPair(effects = listOf(a, b, c)).decode()).aggregateWrites.size)
        for (effects in listOf(listOf(b, a), listOf(c, b), listOf(a, a), listOf(retainedTestClaim(), a))) {
            retainedTestPair(effects = effects).invalid()
        }
    }

    @Test fun claimOrderIsStrictByNamespaceThenKeyRegardlessOfActionOrOwner() {
        val a = retainedTestClaim(namespace = "a", key = "a")
        val b = retainedTestClaim(namespace = "a", key = "b", tag = "claim.release")
        val c = retainedTestClaim(namespace = "b", key = "a")
        assertEquals(3, assertNotNull(retainedTestPair(effects = listOf(a, b, c)).decode()).claimChanges.size)
        for (effects in listOf(listOf(b, a), listOf(c, b), listOf(a, a),
            listOf(a, retainedTestClaim(namespace = "a", key = "a", owner = 11, tag = "claim.release")))) {
            retainedTestPair(effects = effects).invalid()
        }
    }

    @Test fun independentLimitsAllow128WritesPlus128ClaimsNot128Combined() {
        val writes = List(128) { retainedTestWrite(id = 1000 + it, payload = byteArrayOf()) }
        val claims = List(128) { retainedTestClaim(key = it.toString().padStart(3, '0')) }
        val evidence = assertNotNull(retainedTestPair(effects = writes + claims).decode())
        assertEquals(128, evidence.aggregateWrites.size)
        assertEquals(128, evidence.claimChanges.size)
        retainedTestPair(effects = writes + retainedTestWrite(id = 2000)).invalid()
        retainedTestPair(effects = claims + retainedTestClaim(key = "999")).invalid()
        retainedTestPair(effects = writes + claims + retainedTestClaim(key = "999")).invalid()
    }

    @Test fun requestedJobsKeepDuplicateKindsAndOrderButExcludeAutomaticUpload() {
        val jobs = List(32) { retainedTestJob(body = byteArrayOf(it.toByte(), -1)) }
        val evidence = assertNotNull(retainedTestPair(jobs = jobs).decode())
        assertEquals(32, evidence.jobs.size)
        evidence.jobs.forEachIndexed { i, job ->
            assertEquals("qualification.work", job.kind)
            assertContentEquals(byteArrayOf(i.toByte(), -1), job.body.copyBytes())
        }
        retainedTestPair(jobs = jobs + retainedTestJob()).invalid()
        retainedTestPair(jobs = listOf(retainedTestJob(kind = "journal.upload"))).invalid()
    }

    @Test fun allFourOpaqueValueKindsAdmitExactly64KiBAndRejectTheNextByte() {
        val maximum = ByteArray(65_536) { (it % 256).toByte() }
        assertEquals(maximum.size, assertNotNull(retainedTestPair(normalized = maximum).decode()).normalized.size)
        assertEquals(maximum.size, assertNotNull(retainedTestPair(result = maximum).decode()).result.size)
        assertEquals(maximum.size, assertNotNull(retainedTestPair(effects = listOf(retainedTestWrite(payload = maximum)))
            .decode()).aggregateWrites.single().payload.size)
        assertEquals(maximum.size, assertNotNull(retainedTestPair(jobs = listOf(retainedTestJob(body = maximum)))
            .decode()).jobs.single().body.size)
        val excess = ByteArray(65_537)
        retainedTestPair(normalized = excess).invalid()
        retainedTestPair(result = excess).invalid()
        retainedTestPair(effects = listOf(retainedTestWrite(payload = excess))).invalid()
        retainedTestPair(jobs = listOf(retainedTestJob(body = excess))).invalid()
    }

    @Test fun exactProposalSumExcludesNormalizedClaimBytesAndFraming() {
        val maximum = ByteArray(65_536)
        val writes = List(15) { retainedTestWrite(id = 1000 + it, payload = maximum) }
        val claims = List(128) { retainedTestClaim(key = it.toString().padStart(3, '0') + "x".repeat(253)) }
        val evidence = assertNotNull(retainedTestPair(normalized = maximum, result = maximum, effects = writes + claims).decode())
        assertEquals(1_048_576, evidence.result.size + evidence.aggregateWrites.sumOf { it.payload.size })
        retainedTestPair(normalized = maximum, result = maximum, effects = writes + claims,
            jobs = listOf(retainedTestJob(body = byteArrayOf(1)))).invalid()
        assertNotNull(retainedTestPair(result = byteArrayOf(), effects = writes,
            jobs = listOf(retainedTestJob(body = maximum))).decode())
        retainedTestPair(result = byteArrayOf(1), effects = writes, jobs = listOf(retainedTestJob(body = maximum))).invalid()
    }

    @Test fun unsupportedEnvelopeVersionAndTopLevelArityFailClosed() {
        val pair = retainedTestPair()
        pair.withFields { this[0] = "ukkera.journal/v2".encodeToByteArray() }.invalid()
        pair.withFields { removeAt(lastIndex) }.invalid()
        pair.withFields { add(byteArrayOf()) }.invalid()
        pair.withEnvelope(byteArrayOf()).invalid()
    }

    @Test fun truncatedUnsignedOrExcessLengthsAndTrailingBytesCannotAllocateFields() {
        val pair = retainedTestPair()
        for (length in 1..3) pair.withEnvelope(ByteArray(length)).invalid()
        for (prefix in listOf(byteArrayOf(-1, -1, -1, -1), byteArrayOf(0x7f, -1, -1, -1),
            byteArrayOf(0, 0, 0, 1))) pair.withEnvelope(prefix).invalid()
        pair.withEnvelope(pair.journal.envelope + 0.toByte()).invalid()
        pair.withEnvelope(pair.journal.envelope.copyOf(pair.journal.envelope.size - 1)).invalid()
    }

    @Test fun oversizedEnvelopeFailsBeforeFramingEvenWithMatchingEnvelopeHash() {
        retainedTestPair().withEnvelope(ByteArray(4_194_305)).invalid()
    }

    @Test fun nestedFramesAreFixedDepthExactConsumptionAndKnownArityOnly() {
        val pair = retainedTestPair()
        for (index in listOf(18, 19)) {
            pair.withFields { this[index] = byteArrayOf(-1, -1, -1, -1) }.invalid()
            pair.withFields { this[index] = retainedTestFrame(listOf(byteArrayOf(0))) }.invalid()
            pair.withFields { this[index] = retainedTestFrame(listOf(retainedTestFrame(emptyList()))) }.invalid()
        }
        retainedTestPair(effects = listOf(retainedTestFrame(List(3) { byteArrayOf() }))).invalid()
        retainedTestPair(effects = listOf(retainedTestFrame(List(6) { byteArrayOf() }))).invalid()
        retainedTestPair(jobs = listOf(retainedTestFrame(List(1) { byteArrayOf() }))).invalid()
        retainedTestPair(jobs = listOf(retainedTestFrame(List(3) { byteArrayOf() }))).invalid()
    }

    @Test fun allSixteenMetadataFieldsRequireStrictUtf8WhileBinaryFieldsDoNot() {
        val pair = retainedTestPair()
        for (index in 0..15) {
            pair.withFields { this[index] = byteArrayOf(0xc0.toByte(), 0xaf.toByte()) }.invalid()
        }
        assertNotNull(retainedTestPair(normalized = byteArrayOf(-1), result = byteArrayOf(-1),
            effects = listOf(retainedTestWrite(payload = byteArrayOf(-1))),
            jobs = listOf(retainedTestJob(body = byteArrayOf(-1)))).decode())
    }

    @Test fun metadataByteBoundsRejectEmptyOversizedAndEmbeddedNulText() {
        val bounds = listOf(32, 110, 36, 19, 19, 36, 36, 96, 19, 36, 19, 64, 96, 30, 64, 64)
        val pair = retainedTestPair()
        for (index in bounds.indices) {
            pair.withFields { this[index] = byteArrayOf() }.invalid()
            pair.withFields { this[index] = ByteArray(bounds[index] + 1) { 'a'.code.toByte() } }.invalid()
            pair.withFields { this[index] = this[index] + byteArrayOf(0) }.invalid()
        }
    }

    @Test fun countersRequireCanonicalNonnegativeDecimalsAndPositivePositions() {
        val pair = retainedTestPair()
        for (index in listOf(3, 4, 8, 10)) {
            for (text in listOf("+1", "01", "-0", "-1", "1.0", "1e0", " 1", "1 ", "9223372036854775808")) {
                pair.withFields { this[index] = text.encodeToByteArray() }.invalid()
            }
        }
        for (value in listOf("0", "1001")) pair.withFields { this[8] = value.encodeToByteArray() }.invalid()
        pair.withFields { this[4] = "0".encodeToByteArray() }.invalid()
        for (generation in listOf("0", "-1", "+1", "01", "-0", "9223372036854775808")) {
            retainedTestPair(effects = listOf(retainedTestWrite(generation = generation))).invalid()
        }
    }

    @Test fun zeroEpochAndPolicyAndMaxLongPositionsRemainInTheExistingScalarDomain() {
        assertNotNull(retainedTestPair().decode())
        val pair = retainedTestPair(effects = listOf(retainedTestWrite(generation = Long.MAX_VALUE.toString())))
            .withFields { for (index in listOf(3, 4, 10)) this[index] = Long.MAX_VALUE.toString().encodeToByteArray() }
        val adjusted = pair.copy(operation = pair.operation.copy(epoch = Long.MAX_VALUE, sequence = Long.MAX_VALUE,
            policyRevision = Long.MAX_VALUE), journal = pair.journal.copy(epoch = Long.MAX_VALUE, sequence = Long.MAX_VALUE))
        val evidence = assertNotNull(adjusted.decode())
        assertEquals(Long.MAX_VALUE, evidence.authorityEpoch.value)
        assertEquals(Long.MAX_VALUE, evidence.sequence.value)
        assertEquals(Long.MAX_VALUE, evidence.aggregateWrites.single().storageGeneration.value)
    }

    @Test fun canonicalUuidSyntaxDoesNotInventUuidVersionOrVariantRestrictions() {
        val unusual = "abcdef01-ffff-ffff-ffff-abcdefabcdef"
        val pair = retainedTestPair().withFields { this[2] = unusual.encodeToByteArray() }
        assertEquals(unusual, assertNotNull(pair.copy(journal = pair.journal.copy(assignmentId = unusual)).decode()).assignmentId.value)
        for (index in listOf(2, 5, 6, 9)) {
            retainedTestPair().withFields { this[index] = unusual.uppercase().encodeToByteArray() }.invalid()
        }
        retainedTestPair(effects = listOf(retainedTestFrame(retainedTestTexts(
            "a", unusual.uppercase(), "1", "schema/v1",
        ) + byteArrayOf()))).invalid()
    }

    @Test fun timestampMustBeCanonicalAndWithinEvidenceV1Years() {
        for (time in listOf("0000-01-01T00:00:00Z", "9999-12-31T23:59:59.123456789Z")) {
            val pair = retainedTestPair().withFields { this[13] = time.encodeToByteArray() }
            assertEquals(time, assertNotNull(pair.copy(operation = pair.operation.copy(committedAt = time)).decode()).recordedAt.toString())
        }
        for (time in listOf("+10000-01-01T00:00:00Z", "-0001-01-01T00:00:00Z", "2026-10-23T12:00:00+00:00",
            "2026-10-23T12:00:00.0Z", "2026-02-30T12:00:00Z")) {
            val pair = retainedTestPair().withFields { this[13] = time.encodeToByteArray() }
            pair.copy(operation = pair.operation.copy(committedAt = time)).invalid()
        }
    }

    @Test fun protocolAndClaimScalarRulesApplyToNestedMetadataToo() {
        for (bad in listOf("", "Upper", "with space", "x\u0000y", "x".repeat(97), "ع")) {
            retainedTestPair(effects = listOf(retainedTestWrite(kind = bad))).invalid()
            retainedTestPair(effects = listOf(retainedTestWrite(schema = bad))).invalid()
            retainedTestPair(effects = listOf(retainedTestClaim(namespace = bad))).invalid()
            retainedTestPair(jobs = listOf(retainedTestJob(kind = bad))).invalid()
        }
        for (bad in listOf("", "a b", "a\u0000b", "ع", "x".repeat(257))) {
            retainedTestPair(effects = listOf(retainedTestClaim(key = bad))).invalid()
        }
        assertNotNull(retainedTestPair(effects = listOf(retainedTestClaim(key = "!".repeat(256)))).decode())
    }

    @Test fun bothMissingIsOnlyNoRetainedPairAndEitherMissingMemberIsInvalid() {
        val pair = retainedTestPair()
        assertNull(decodeRetainedCommand(RETAINED_TEST_SCOPE, RETAINED_TEST_OPERATION, null, null))
        assertSanitized { decodeRetainedCommand(RETAINED_TEST_SCOPE, RETAINED_TEST_OPERATION, pair.operation, null) }
        assertSanitized { decodeRetainedCommand(RETAINED_TEST_SCOPE, RETAINED_TEST_OPERATION, null, pair.journal) }
        assertSanitized { decodeRetainedCommand(RETAINED_TEST_SCOPE, OperationId(retainedTestId(99).value), pair.operation, pair.journal) }
        assertSanitized { decodeRetainedCommand(RETAINED_TEST_SCOPE.copy(branchId = retainedTestId(99)), RETAINED_TEST_OPERATION,
            pair.operation, pair.journal) }
    }

    @Test fun everyOperationRowBindingIsCheckedAgainstOriginalJournalHeader() {
        val p = retainedTestPair()
        val row = p.operation
        val changes = listOf(row.copy(scope = "wrong"), row.copy(operationId = retainedTestId(99).value),
            row.copy(actorId = retainedTestId(99).value), row.copy(command = "other.command"), row.copy(commandVersion = 2),
            row.copy(digest = "f".repeat(64)), row.copy(outcome = "other.outcome"), row.copy(result = byteArrayOf(42)),
            row.copy(resultHash = "f".repeat(64)), row.copy(sequence = 2), row.copy(epoch = 1),
            row.copy(committedAt = "2026-10-23T12:00:01Z"), row.copy(authenticationId = retainedTestId(99).value),
            row.copy(policyRevision = 1))
        for (change in changes) p.copy(operation = change).invalid()
    }

    @Test fun everyJournalRowBindingAndEnvelopeIntegrityIsChecked() {
        val p = retainedTestPair()
        val row = p.journal
        val changes = listOf(row.copy(scope = "wrong"), row.copy(operationId = retainedTestId(99).value),
            row.copy(assignmentId = retainedTestId(99).value), row.copy(sequence = 2), row.copy(epoch = 1),
            row.copy(previousHash = "f".repeat(64)), row.copy(hash = "f".repeat(64)), row.copy(envelope = byteArrayOf(42)))
        for (change in changes) p.copy(journal = change).invalid()
    }

    @Test fun independentlyRehashedEnvelopeStillCannotDisagreeOnAnyOverlappingHeaderField() {
        val p = retainedTestPair()
        val changes = mapOf(1 to RETAINED_TEST_SCOPE.copy(branchId = retainedTestId(99)).storageKey(),
            2 to retainedTestId(99).value, 3 to "1", 4 to "2", 5 to retainedTestId(99).value,
            6 to retainedTestId(99).value, 7 to "other.command", 8 to "2", 9 to retainedTestId(99).value,
            10 to "1", 11 to "f".repeat(64), 12 to "other.outcome", 13 to "2026-10-23T12:00:01Z",
            14 to "f".repeat(64), 15 to "f".repeat(64))
        for ((index, value) in changes) p.withFields { this[index] = value.encodeToByteArray() }.invalid()
        p.withFields { this[16] = byteArrayOf(42) }.invalid()
        p.withFields { this[17] = byteArrayOf(42) }.invalid()
    }

    @Test fun matchingStoredDigestStringsAreNotEnoughWithoutOriginalByteRecomputation() {
        val p = retainedTestPair()
        val badSemantic = p.withFields { this[11] = "f".repeat(64).encodeToByteArray() }
        badSemantic.copy(operation = badSemantic.operation.copy(digest = "f".repeat(64))).invalid()
        val badResult = p.withFields { this[14] = "f".repeat(64).encodeToByteArray() }
        badResult.copy(operation = badResult.operation.copy(resultHash = "f".repeat(64))).invalid()
        val badBothResultBytes = p.withFields { this[17] = byteArrayOf(42) }
        badBothResultBytes.copy(operation = badBothResultBytes.operation.copy(result = byteArrayOf(42))).invalid()
    }

    @Test fun decodedValuesAndListsDoNotAliasSourceRowsOrReturnedCopies() {
        val p = retainedTestPair(effects = listOf(retainedTestWrite(id = 10), retainedTestWrite(id = 11),
            retainedTestClaim(key = "a"), retainedTestClaim(key = "b")), jobs = listOf(retainedTestJob(), retainedTestJob()))
        val evidence = assertNotNull(p.decode())
        val originalResult = evidence.result.copyBytes()
        val originalNormalized = evidence.normalized.copyBytes()
        val originalWrite = evidence.aggregateWrites.first().payload.copyBytes()
        val originalJob = evidence.jobs.first().body.copyBytes()
        p.operation.result.fill(42)
        p.journal.envelope.fill(42)
        evidence.result.copyBytes().fill(42)
        evidence.normalized.copyBytes().fill(42)
        evidence.aggregateWrites.first().payload.copyBytes().fill(42)
        evidence.jobs.first().body.copyBytes().fill(42)
        (evidence.aggregateWrites as MutableList<*>).clear()
        (evidence.claimChanges as MutableList<*>).clear()
        (evidence.jobs as MutableList<*>).clear()
        assertContentEquals(originalResult, evidence.result.copyBytes())
        assertContentEquals(originalNormalized, evidence.normalized.copyBytes())
        assertContentEquals(originalWrite, evidence.aggregateWrites.first().payload.copyBytes())
        assertContentEquals(originalJob, evidence.jobs.first().body.copyBytes())
        assertEquals(2, evidence.aggregateWrites.size)
        assertEquals(2, evidence.claimChanges.size)
        assertEquals(2, evidence.jobs.size)
    }

    @Test fun scalarErrorsAreFixedReasonsWithoutPrivateTextOrCauses() {
        val privateText = "PRIVATE-PAYLOAD-SQL-DETAIL"
        retainedTestPair().withFields { this[13] = privateText.encodeToByteArray() }.invalid()
        retainedTestPair(effects = listOf(retainedTestWrite(schema = privateText))).invalid()
    }
}

internal val RETAINED_TEST_SCOPE = BranchScope(retainedTestId(1), retainedTestId(2), retainedTestId(3))
internal val RETAINED_TEST_OPERATION = OperationId(retainedTestId(800).value)
internal fun retainedTestId(id: Int): DomainId = DomainId("00000000-0000-4000-8000-${id.toString().padStart(12, '0')}")

internal data class RetainedTestPair(val operation: OperationRow, val journal: JournalRow) {
    fun decode(): RetainedCommandEvidence? = decodeRetainedCommand(RETAINED_TEST_SCOPE, RETAINED_TEST_OPERATION, operation, journal)
    fun invalid() { assertSanitized { decode() } }
    fun withEnvelope(bytes: ByteArray): RetainedTestPair = copy(journal = journal.copy(envelope = bytes, hash = sha256(bytes).value))
    fun withFields(edit: MutableList<ByteArray>.() -> Unit): RetainedTestPair =
        withEnvelope(retainedTestFrame(retainedTestFields(journal.envelope).toMutableList().apply(edit)))
}

internal fun retainedTestPair(
    code: String = "committed",
    effects: List<ByteArray> = listOf(retainedTestWrite()),
    jobs: List<ByteArray> = emptyList(),
    normalized: ByteArray = byteArrayOf(-1, 0, 10),
    result: ByteArray = byteArrayOf(0, -1),
): RetainedTestPair {
    val scope = RETAINED_TEST_SCOPE.storageKey()
    val actor = retainedTestId(6).value
    val command = "qualification.original"
    val digest = sha256(retainedTestFrame(retainedTestTexts("ukkera.command/v1", scope, actor, command, "1") + normalized)).value
    val resultHash = sha256(result).value
    val row = OperationRow(scope, RETAINED_TEST_OPERATION.value, actor, command, 1, digest, code, result.copyOf(), resultHash,
        1, 0, "2026-10-23T12:00:00Z", retainedTestId(7).value, 0)
    val envelope = retainedTestFrame(retainedTestTexts("ukkera.journal/v1", scope, retainedTestId(4).value, "0", "1",
        row.operationId, actor, command, "1", row.authenticationId, "0", digest, code, row.committedAt, resultHash, "0".repeat(64)) +
        listOf(normalized, result, retainedTestFrame(effects), retainedTestFrame(jobs)))
    return RetainedTestPair(row, JournalRow(scope, 1, row.operationId, retainedTestId(4).value, 0,
        "0".repeat(64), sha256(envelope).value, envelope))
}

internal fun retainedTestWrite(id: Int = 10, kind: String = "qualification.after", generation: String = "1",
    schema: String = "unregistered.schema/v1", payload: ByteArray = byteArrayOf(-1, 0)): ByteArray =
    retainedTestFrame(retainedTestTexts(kind, retainedTestId(id).value, generation, schema) + payload)

internal fun retainedTestClaim(tag: String = "claim.acquire", namespace: String = "qualification.claim", key: String = "key",
    owner: Int = 10): ByteArray = retainedTestFrame(retainedTestTexts(tag, namespace, key, retainedTestId(owner).value))

internal fun retainedTestJob(kind: String = "qualification.work", body: ByteArray = byteArrayOf(-1, 0)): ByteArray =
    retainedTestFrame(listOf(kind.encodeToByteArray(), body))

internal fun retainedTestTexts(vararg values: String): List<ByteArray> = values.map(String::encodeToByteArray)

/** Test framing independent of production frame(); frozen JVM wire goldens remain separate. */
internal fun retainedTestFrame(fields: List<ByteArray>): ByteArray {
    val output = ByteArray(fields.sumOf { it.size + 4 })
    var offset = 0
    for (field in fields) {
        val length = field.size
        val prefix = byteArrayOf((length / 16_777_216).toByte(), (length / 65_536).toByte(),
            (length / 256).toByte(), length.toByte())
        prefix.copyInto(output, offset)
        field.copyInto(output, offset + 4)
        offset += 4 + length
    }
    return output
}

/** Used only to mutate already valid test vectors, never by the decoder under test. */
internal fun retainedTestFields(bytes: ByteArray): List<ByteArray> = buildList {
    var offset = 0
    while (offset < bytes.size) {
        var length = 0
        repeat(4) { length = length * 256 + (bytes[offset++].toInt() and 255) }
        add(bytes.copyOfRange(offset, offset + length))
        offset += length
    }
}

private fun assertSanitized(block: () -> Unit) {
    val failure = assertFailsWith<IllegalStateException>(block = block)
    assertTrue(failure.message in RetainedCommandIssue.entries.map { "Retained command unavailable: ${it.name}" })
    assertNull(failure.cause)
    assertTrue(failure.suppressedExceptions.isEmpty())
}
