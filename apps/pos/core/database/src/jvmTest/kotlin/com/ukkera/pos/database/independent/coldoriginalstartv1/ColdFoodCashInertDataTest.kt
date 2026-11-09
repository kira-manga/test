package com.ukkera.pos.database.independent.coldoriginalstartv1

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.NativeOriginalJournalProvenanceV1
import com.ukkera.pos.database.RuntimeHistoryColdMemory
import com.ukkera.pos.database.RuntimeHistoryColdReplayTarget
import com.ukkera.pos.database.RuntimeHistoryColdScanMemory
import com.ukkera.pos.database.RuntimeHistoryEventHeaderV2
import com.ukkera.pos.database.RuntimeHistoryFormatRejectedV2
import com.ukkera.pos.database.RuntimeHistoryParentKindV2
import com.ukkera.pos.database.RuntimeNativeOriginalImportEvidenceV1
import com.ukkera.pos.database.RuntimeWritePurposeV2
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.StreamedProducerIdentityV2
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Only FCC-DATA-01's 14 frozen v1.0.0 comparison vectors are bound here:
 * DATA comparison only; native authority not verified; current authorization not
 * checked; financial application not confirmed. No whole FCC case/scenario PASS,
 * native owner, committed Start, instruction, accepted result, actual history,
 * owner clock, current principal, close/reopen or financial counters are supplied.
 *
 * Every original-descriptor field has a separate, explicitly inert mapping:
 * - identity/branch/actor UUIDs occupy producer/scope and matching provenance slots;
 *   organization/company UUIDs are frozen synthetic scope context, not authority;
 * - original_device occupies clientSource.deviceId only as comparison DATA;
 * - original_presentation occupies clientSource.nativeIdentityDigest as SHA-256
 *   of the field name, NUL and exact UUID label, NOT a native source identity;
 * - family labels have distinct comparison.inert-food-* CommandKey name aliases;
 *   fixture-behavior-v1/v2 select integer aliases 1/2, NOT native CASH versions;
 * - profile occupies originalBodyDigest as the same field-specific label hash,
 *   NOT an original-body commitment or supported FOOD domain profile;
 * - requirement hex is decoded verbatim into canonicalRequirement and its digest;
 * - observation hex is decoded verbatim into the certificate byte slot and its
 *   digest, and hashed into nativeRecordDigest. It is NOT a Start certificate,
 *   native observation/record encoding, authenticated result or financial proof.
 * No preview JSON, production encoder or whole-descriptor hash substitutes for
 * these dedicated field/byte mappings. Native protocol binding remains null.
 *
 * Complete unasserted syntax-filler inventory (none is observed native evidence):
 * HistoricalStart's intent/authentication/assignment/master-device IDs are fixed
 * filler IDs 20/21/22/23; policy/epoch are 0, sequence 1, recordedAt is the fixed
 * 2000-01-01 timestamp; intent semantic/journal/result digests are syntaxDigest.
 * NativeOriginal's domainProfile is syntaxProfile and originalSemanticDigest is
 * syntaxDigest, as is the producer semantic digest. MASTER source uses filler
 * IDs 40..44 for installation/device/catalogue/key/allocation; CLIENT uses
 * 60/62/63/64 for installation/catalogue/key/allocation. Both roles and slot 0
 * are position/syntax fillers; all source payload/header/allocation digests and
 * MASTER nativeIdentityDigest are syntaxDigest. No source key/read is bound.
 * CurrentWriter uses filler IDs 30/23/31/32/33/22 for installation/master device/
 * store/service/invocation/assignment, epoch 0, syntaxProfile and syntaxDigest.
 * RuntimeParent uses sequence 1 and syntaxDigest. Header uses original scope;
 * filler IDs 50/51/52/22 for storage/attempt/transaction/assignment; all its
 * digests are syntaxDigest. Attempt/command/parent sequence are 1, event sequence
 * 2, epoch 0, RECORDED_EVENT/COMMAND_APPLY are syntax tags, and chunk/image/intent/
 * dependency/fact sizes are 1/0/1/0/0. Header changeCount and capturedChanges are
 * fixed 7, NOT mutation counts. eventDigest is a fixed label hash; afterLast 3
 * is a syntax offset, not a history cut. No validation, codec, intake, native or
 * business-execution API is called. Disposal clears only owned inert DATA.
 */
class ColdFoodCashInertDataTest {
    @Test
    fun fccData01EqualCopy() = comparison("DATA-EQUAL")

    @Test
    fun fccData01DifferentOriginalIdentity() = comparison("DATA-IDENTITY")

    @Test
    fun fccData01DifferentBranch() = comparison("DATA-BRANCH")

    @Test
    fun fccData01DifferentActor() = comparison("DATA-ACTOR")

    @Test
    fun fccData01DifferentOriginalDevice() = comparison("DATA-DEVICE")

    @Test
    fun fccData01DifferentOriginalPresentation() = comparison("DATA-PRESENTATION")

    @Test
    fun fccData01DifferentComparisonCommandFamily() = comparison("DATA-FAMILY")

    @Test
    fun fccData01DifferentFixtureCommandVersion() = comparison("DATA-VERSION")

    @Test
    fun fccData01DifferentRequirementAmountBytes() = comparison("DATA-REQUIREMENT-AMOUNT")

    @Test
    fun fccData01DifferentRequirementOrderBytes() = comparison("DATA-REQUIREMENT-ORDER")

    @Test
    fun fccData01DifferentObservationOutcomeBytes() = comparison("DATA-OBSERVATION-OUTCOME")

    @Test
    fun fccData01DifferentObservationReceivedBytes() = comparison("DATA-OBSERVATION-RECEIVED")

    @Test
    fun fccData01DifferentObservationChangeBytes() = comparison("DATA-OBSERVATION-CHANGE")

    @Test
    fun fccData01DifferentObservationTimeBytes() = comparison("DATA-OBSERVATION-TIME")

    private fun comparison(vectorId: String) {
        val vector = vectors.getValue(vectorId)
        val replacement = vector.getValue("candidate_single_field_replacement").jsonObject
        check(replacement.keys.all { it in reference.keys })
        check(replacement.size == if (vector.flag("expected_whole_equality")) 0 else 1)
        val candidateDescriptor = JsonObject(reference + replacement)
        val referenceBefore = reference.toString()
        val retained = bindComparisonData(reference)
        val snapshot = RetainedSnapshot(retained)
        val headerBefore = header.copy()
        val memory = RuntimeHistoryColdMemory()
        val bytes = RuntimeHistoryColdScanMemory.eventBytes(header)
        memory.reserve(bytes)
        val target = RuntimeHistoryColdReplayTarget(header, eventDigest, null, 3L,
            memory, bytes, retained)
        try {
            assertSame(retained, target.selectNative(header, eventDigest, retained), vectorId)
            // Candidate construction and all alias/hex binding are outside
            // assertFailsWith: only the real selectNative rejection counts.
            withCandidate(candidateDescriptor) { candidate ->
                assertNotSame(retained, candidate, vectorId)
                assertNotSame(retained.canonicalRequirement, candidate.canonicalRequirement, vectorId)
                assertNotSame(retained.certificate, candidate.certificate, vectorId)
                if (vector.flag("expected_whole_equality")) {
                    assertSame(candidate, target.selectNative(header, eventDigest, candidate), vectorId)
                } else {
                    assertFailsWith<RuntimeHistoryFormatRejectedV2>(vectorId) {
                        target.selectNative(header, eventDigest, candidate)
                    }
                }
            }
            snapshot.assertUnchanged(checkNotNull(target.nativeOriginal), vectorId)
            // The unchanged retained-original oracle is satisfied by another
            // actual comparison with a fresh independently owned original copy.
            withCandidate(reference) { originalCopy ->
                assertSame(originalCopy, target.selectNative(header, eventDigest, originalCopy), vectorId)
            }
            snapshot.assertUnchanged(checkNotNull(target.nativeOriginal), vectorId)
            assertSame(retained, target.nativeOriginal, vectorId)
            assertEquals(headerBefore, target.header, vectorId)
            assertEquals(eventDigest, target.eventDigest, vectorId)
            assertNull(target.evidence, vectorId)
            assertEquals(3L, target.afterLast, vectorId)
            assertEquals(referenceBefore, reference.toString(), vectorId)
        } finally {
            target.close()
            snapshot.clear()
        }
        // No assertion invents native retention, authority, settlement or
        // post-disposal comparison semantics beyond this inert DATA ownership.
    }

    private fun withCandidate(descriptor: JsonObject, block: (RuntimeNativeOriginalImportEvidenceV1) -> Unit) {
        val candidate = bindComparisonData(descriptor)
        try { block(candidate) } finally {
            candidate.canonicalRequirement.bytes().fill(0)
            candidate.certificate.bytes().fill(0)
        }
    }

    private fun bindComparisonData(descriptor: JsonObject): RuntimeNativeOriginalImportEvidenceV1 {
        val scope = scope(descriptor)
        val operation = OperationId(descriptor.text("original_identity"))
        val actor = DomainId(descriptor.text("actor"))
        val familyAlias = when (descriptor.text("command_family")) {
            "CASH_COLLECTION" -> "comparison.inert-food-cash-collection"
            "CASH_REFUND_COMPARISON_ONLY" -> "comparison.inert-food-cash-refund"
            else -> error("Unbound frozen FOOD command-family DATA label")
        }
        val versionAlias = when (descriptor.text("command_version")) {
            "fixture-behavior-v1" -> 1
            "fixture-behavior-v2" -> 2
            else -> error("Unbound frozen FOOD fixture-version DATA label")
        }
        val command = CommandKey(familyAlias, versionAlias)
        // Decode the frozen hex itself; previews are never a byte source.
        val requirementBytes = decodeHex(descriptor.text("requirement_bytes_hex"))
        val observationBytes = decodeHex(descriptor.text("observation_bytes_hex"))
        try {
            val requirement = StoredBytes(requirementBytes)
            val observation = StoredBytes(observationBytes)
            val provenance = NativeOriginalJournalProvenanceV1(
                scope = scope,
                historicalStart = NativeOriginalJournalProvenanceV1.HistoricalStart(
                    actor, OperationId(fillerId(20).value), command, syntaxDigest, fillerId(21),
                    Revision(0), fillerId(22), fillerId(23), Revision(0), JournalSequence(1),
                    syntaxDigest, syntaxDigest, UtcInstant.parseCanonical("2000-01-01T00:00:00Z")),
                nativeOriginal = NativeOriginalJournalProvenanceV1.NativeOriginal(
                    syntaxProfile, operation, sha256(requirementBytes), sha256(observationBytes),
                    labelDigest("profile", descriptor.text("profile")), syntaxDigest,
                    source(NativeOriginalJournalProvenanceV1.SourceRole.MASTER, descriptor),
                    source(NativeOriginalJournalProvenanceV1.SourceRole.CLIENT, descriptor)),
                currentWriter = NativeOriginalJournalProvenanceV1.CurrentWriter(
                    fillerId(30), fillerId(23), fillerId(31), fillerId(32), fillerId(33), fillerId(22),
                    Revision(0), syntaxProfile, syntaxDigest),
                runtimeParent = NativeOriginalJournalProvenanceV1.RuntimeParent(JournalSequence(1), syntaxDigest),
            )
            // The constructor is inert. In particular, do not call validate(),
            // a native codec, capture/intake or a supported-domain factory here.
            return RuntimeNativeOriginalImportEvidenceV1(scope,
                StreamedProducerIdentityV2(operation, command, actor, syntaxDigest), requirement, observation,
                provenance, sha256(observationBytes), syntaxChanges)
        } finally {
            requirementBytes.fill(0)
            observationBytes.fill(0)
        }
    }

    private fun source(role: NativeOriginalJournalProvenanceV1.SourceRole, descriptor: JsonObject):
        NativeOriginalJournalProvenanceV1.Source {
        val client = role == NativeOriginalJournalProvenanceV1.SourceRole.CLIENT
        return NativeOriginalJournalProvenanceV1.Source(
            role, fillerId(if (client) 60 else 40),
            if (client) DomainId(descriptor.text("original_device")) else fillerId(41),
            fillerId(if (client) 62 else 42), fillerId(if (client) 63 else 43),
            fillerId(if (client) 64 else 44), 0, syntaxDigest, syntaxDigest, syntaxDigest,
            if (client) labelDigest("original_presentation", descriptor.text("original_presentation")) else syntaxDigest,
        )
    }

    /** Actual target fields/owned bytes, not redacted toString() or a JSON-only snapshot. */
    private class RetainedSnapshot(value: RuntimeNativeOriginalImportEvidenceV1) {
        private val scope = value.scope.copy()
        private val producer = value.producer.copy()
        private val provenance = value.provenance.run {
            copy(scope = scope.copy(), historicalStart = historicalStart.copy(),
                nativeOriginal = nativeOriginal.copy(masterSource = nativeOriginal.masterSource.copy(),
                    clientSource = nativeOriginal.clientSource.copy()),
                currentWriter = currentWriter.copy(), runtimeParent = runtimeParent.copy())
        }
        private val nativeRecordDigest = value.nativeRecordDigest.copy()
        private val capturedChanges = value.capturedChanges
        private val requirementBytes = value.canonicalRequirement.copyBytes()
        private val observationBytes = value.certificate.copyBytes()

        fun assertUnchanged(value: RuntimeNativeOriginalImportEvidenceV1, message: String) {
            assertEquals(scope, value.scope, message)
            assertEquals(producer, value.producer, message)
            assertEquals(provenance, value.provenance, message)
            assertEquals(nativeRecordDigest, value.nativeRecordDigest, message)
            assertEquals(capturedChanges, value.capturedChanges, message)
            assertContentEquals(requirementBytes, value.canonicalRequirement.bytes(), message)
            assertContentEquals(observationBytes, value.certificate.bytes(), message)
        }

        fun clear() {
            requirementBytes.fill(0)
            observationBytes.fill(0)
        }
    }

    private companion object {
        const val fixtureBytes = 48_183
        const val fixtureSha256 = "a7e281c76cf650742b92ebb47ded9f7d7338d6d6cb5eb2b58f179b0972bfc0be"
        const val syntaxProfile = "comparison.inert-food-cash-data/v1"
        const val syntaxChanges = 7
        val vectorIds = listOf("DATA-EQUAL", "DATA-IDENTITY", "DATA-BRANCH", "DATA-ACTOR", "DATA-DEVICE",
            "DATA-PRESENTATION", "DATA-FAMILY", "DATA-VERSION", "DATA-REQUIREMENT-AMOUNT", "DATA-REQUIREMENT-ORDER",
            "DATA-OBSERVATION-OUTCOME", "DATA-OBSERVATION-RECEIVED", "DATA-OBSERVATION-CHANGE", "DATA-OBSERVATION-TIME")
        val frozen = checkNotNull(ColdFoodCashInertDataTest::class.java.getResourceAsStream(
            "/independent/coldoriginalstartv1/food-cash-cold-recovery-v1-fixtures.json")).use { stream ->
            val bytes = stream.readBytes()
            try {
                check(bytes.size == fixtureBytes)
                check(sha256(bytes).value == fixtureSha256)
                Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            } finally { bytes.fill(0) }
        }.also {
            check(it.text("schema_version") == "1.0.0" && it.text("expectation_version") == "1.0.0")
            check(it.text("status") == "NOT RUN" && it.flag("synthetic_only"))
            check(it.getValue("tested_revision") == JsonNull && it.getValue("execution_evidence").jsonArray.isEmpty())
            val catalogCase = it.getValue("case_catalog").jsonArray.map { entry -> entry.jsonObject }
                .single { entry -> entry.text("id") == "FCC-DATA-01" }
            check(catalogCase.text("expectation_version") == "1.0.0" && catalogCase.text("status") == "NOT RUN")
            check(catalogCase.getValue("results").jsonArray.isEmpty())
        }
        val inert = frozen.getValue("inert_data").jsonObject.also {
            check(it.text("profile_version") == "1.0.0" && it.text("status") == "NOT RUN")
            check(it.getValue("native_protocol_binding") == JsonNull)
            check(it.getValue("required_verification_labels").jsonArray.map { label -> label.jsonPrimitive.content } ==
                listOf("DATA comparison only", "native authority not verified", "current authorization not checked",
                    "financial application not confirmed"))
            // These flags/Receipt labels are frozen input metadata only, NOT
            // observed counters or an exercised transport/native Receipt control.
            check(it.flag("no_refund_or_other_tender_execution_from_comparison_vectors"))
            val receipt = it.getValue("transport_receipt_control").jsonObject
            check(receipt.text("receipt_label") == "RECEIVED")
            check(receipt.flag("original_descriptor_unchanged") && receipt.flag("expected_whole_equality"))
            check(!receipt.flag("expected_financial_application_confirmed") && !receipt.flag("expected_native_authority_verified"))
        }
        val reference = inert.getValue("original_descriptor").jsonObject.also {
            check(it.keys == setOf("profile", "original_identity", "branch", "actor", "original_device",
                "original_presentation", "command_family", "command_version", "requirement_bytes_hex", "observation_bytes_hex"))
        }
        val vectors = inert.getValue("vectors").jsonArray.map { it.jsonObject }.also { entries ->
            check(entries.map { it.text("id") } == vectorIds)
            for (entry in entries) {
                check(entry.text("status") == "NOT RUN")
                check(entry.flag("expected_whole_equality") == (entry.text("id") == "DATA-EQUAL"))
                check(entry.flag("expected_retained_original_equality_after_candidate"))
                check(!entry.flag("expected_retained_original_changed"))
                // Guard unchanged input oracles only; no authority/authorization/
                // financial operation is exercised to manufacture these flags.
                check(!entry.flag("expected_native_authority_verified"))
                check(!entry.flag("expected_current_authorization_checked"))
                check(!entry.flag("expected_financial_application_confirmed"))
            }
        }.associateBy { it.text("id") }
        val syntaxDigest = digest("Unasserted inert FOOD comparison syntax filler; not native evidence")
        val eventDigest = digest("Inert FOOD comparison event label; not actual native history")
        val header = RuntimeHistoryEventHeaderV2(
            scope = scope(reference).let { "${it.organizationId.value}/${it.companyId.value}/${it.branchId.value}" },
            storageId = fillerId(50), bindingDigest = syntaxDigest, originalAllocationDigest = syntaxDigest,
            definitionDigest = syntaxDigest, installationAttemptId = fillerId(51), installationAttemptSequence = 1,
            installationGenesisDigest = syntaxDigest, sequence = 2,
            parentKind = RuntimeHistoryParentKindV2.RECORDED_EVENT.tag, parentSequence = 1, parentDigest = syntaxDigest,
            transactionId = fillerId(52), assignmentId = fillerId(22), purpose = RuntimeWritePurposeV2.COMMAND_APPLY,
            commandEpoch = 0, commandSequence = 1, commandDigest = syntaxDigest, roleCommitment = syntaxDigest,
            recoveryCommitment = syntaxDigest, changeCount = syntaxChanges, chunkCount = 1, imageBytes = 0,
            intentBytes = 1, intentDigest = syntaxDigest, dependencyBytes = 0, dependencyDigest = syntaxDigest,
            factBytes = 0, factDigest = syntaxDigest,
        )

        fun scope(descriptor: JsonObject): BranchScope {
            val ids = frozen.getValue("synthetic_ids").jsonObject
            return BranchScope(DomainId(ids.text("organization")), DomainId(ids.text("restaurant_company")),
                DomainId(descriptor.text("branch")))
        }

        fun decodeHex(value: String): ByteArray {
            check(value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' })
            return ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        fun fillerId(value: Int): DomainId = DomainId("f1ccda7a-0000-4000-8000-${value.toString(16).padStart(12, '0')}")
        fun digest(value: String): PayloadDigest = sha256(value.encodeToByteArray())
        fun labelDigest(field: String, value: String): PayloadDigest = digest("$field\u0000$value")
        fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
        fun JsonObject.flag(key: String): Boolean = getValue(key).jsonPrimitive.boolean
    }
}
