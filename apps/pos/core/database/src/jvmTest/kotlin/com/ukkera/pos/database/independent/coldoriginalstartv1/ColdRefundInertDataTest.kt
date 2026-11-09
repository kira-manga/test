package com.ukkera.pos.database.independent.coldoriginalstartv1

import com.ukkera.pos.database.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Frozen CRF-DATA-001..004, supporting comparison only. No native owner, session,
 * committed Start, authenticated principal, accepted return/obligation, protected
 * read, actual close proof or dispatcher exists here. No CRF business workflow is
 * exercised, and no physical payout, refund application or stock effect is tested.
 *
 * All eight descriptor groups and every nested field are bound. Actor/command
 * have distinct explicit DATA aliases; operation/branch retain their UUIDs. The
 * complete source JSON, including original sale/receipt/return/obligation/cash
 * allocation, is retained in sourcePayloadDigest; origin/cut also have separate
 * provenance digests. The complete Start and certificate label are retained in
 * certificate bytes, the requirement label in requirement bytes, and the complete
 * payout outcome in nativeRecordDigest. These are synthetic JSON/label DATA, NOT
 * valid physical certificates, requirements or native-record/source encodings.
 * This exercises comparison, not authenticity or any of those production codecs.
 * Unspecified provenance/header fields are constant syntax fillers, not evidence.
 */
class ColdRefundInertDataTest {
    @Test
    fun crfData001CompleteSelfAndSeparateCopyMatch() = comparison { target ->
        val selected = checkNotNull(target.nativeOriginal)
        // Binding sanity for the frozen refund semantics only. A DATA-family
        // classification does not validate a certificate or admit an original.
        assertEquals(NativeOriginalDomain.RETAIL_REFUND_V3,
            RuntimeBusinessHistoryJoin.supportedColdNativeOriginalDomain(selected))
        target.selectNative(header, eventDigest, selected)
        withCandidate(reference) { target.selectNative(header, eventDigest, it) }
    }

    @Test
    fun crfData002EveryTopLevelChangeDoesNotMatch() = comparison { target ->
        val variants = frozen.getValue("top_level_one_field_changes").jsonArray
        assertEquals(8, variants.size)
        for (raw in variants) {
            val variant = raw.jsonObject
            assertEquals("DOES_NOT_MATCH", variant.text("expected"))
            // Only production comparison rejection may satisfy this assertion;
            // input construction and alias binding occur outside assertFailsWith.
            withCandidate(changed(reference, variant)) { candidate ->
                assertFailsWith<RuntimeHistoryFormatRejectedV2>(variant.text("id")) {
                    target.selectNative(header, eventDigest, candidate)
                }
            }
            withCandidate(reference) { target.selectNative(header, eventDigest, it) }
        }
        assertEquals(referenceSnapshot, reference.toString())
    }

    @Test
    fun crfData003EveryNestedRefundChangeDoesNotMatchAndOriginalStillMatches() = comparison { target ->
        val variants = frozen.getValue("nested_one_field_changes").jsonArray
        assertEquals(25, variants.size)
        for (raw in variants) {
            val variant = raw.jsonObject
            assertEquals("DOES_NOT_MATCH", variant.text("expected"))
            withCandidate(changed(reference, variant)) { candidate ->
                assertFailsWith<RuntimeHistoryFormatRejectedV2>(variant.text("id")) {
                    target.selectNative(header, eventDigest, candidate)
                }
            }
        }
        withCandidate(reference) { target.selectNative(header, eventDigest, it) }
        assertEquals(referenceSnapshot, reference.toString())
    }

    @Test
    fun crfData004RepeatedComparisonAndDisposalReturnOnlyInertData() = comparison { target ->
        val variants = frozen.getValue("top_level_one_field_changes").jsonArray +
            frozen.getValue("nested_one_field_changes").jsonArray
        repeat(2) {
            withCandidate(reference) { target.selectNative(header, eventDigest, it) }
            for (raw in variants) {
                withCandidate(changed(reference, raw.jsonObject)) { candidate ->
                    assertFailsWith<RuntimeHistoryFormatRejectedV2> {
                        target.selectNative(header, eventDigest, candidate)
                    }
                }
            }
        }
        assertEquals(referenceSnapshot, reference.toString())
        // comparison() disposes only the inert target. No authority, physical
        // publication or business-execution interface exists here. Do not invent
        // zero payout/stock counters, native settlement/current-close evidence,
        // or unspecified post-disposal comparison behavior.
    }

    private fun comparison(block: (RuntimeHistoryColdReplayTarget) -> Unit) {
        val memory = RuntimeHistoryColdMemory()
        val bytes = RuntimeHistoryColdScanMemory.eventBytes(header)
        memory.reserve(bytes)
        val target = RuntimeHistoryColdReplayTarget(header, eventDigest, null, 3L,
            memory, bytes, evidence(reference))
        try { block(target) } finally { target.close() }
    }

    private fun withCandidate(descriptor: JsonObject, block: (RuntimeNativeOriginalImportEvidenceV1) -> Unit) {
        val candidate = evidence(descriptor)
        try { block(candidate) } finally {
            candidate.canonicalRequirement.bytes().fill(0)
            candidate.certificate.bytes().fill(0)
        }
    }

    private fun changed(base: JsonObject, variant: JsonObject): JsonObject {
        val path = variant.text("field").split('.')
        val replacement = variant.getValue("replacement")
        return if (path.size == 1) JsonObject(base + (path.single() to replacement))
        else {
            check(path.size == 2)
            JsonObject(base + (path[0] to JsonObject(base.getValue(path[0]).jsonObject + (path[1] to replacement))))
        }
    }

    private fun evidence(descriptor: JsonObject): RuntimeNativeOriginalImportEvidenceV1 {
        val sourceData = descriptor.getValue("original_source_descriptor").jsonObject
        val scope = BranchScope(id(1), id(2), DomainId(sourceData.text("branch_id")))
        val actor = when (descriptor.text("original_actor_descriptor")) {
            "ACTOR-REFUND-original" -> id(10)
            "different-original-refund-actor" -> id(11)
            else -> error("Unbound frozen refund actor DATA label")
        }
        val command = when (descriptor.text("original_command_descriptor")) {
            "same-branch-retail-cash-refund-original-command-R" -> CommandKey("sales.return.write", 3)
            "different-original-refund-command" -> CommandKey("comparison.different-original-refund-command", 3)
            else -> error("Unbound frozen refund command DATA label")
        }
        val operation = OperationId(descriptor.text("original_operation_descriptor"))
        val requirement = StoredBytes(descriptor.text("captured_requirement_descriptor").encodeToByteArray())
        val certificate = StoredBytes(JsonObject(linkedMapOf(
            "captured_start_descriptor" to descriptor.getValue("captured_start_descriptor"),
            "captured_certificate_descriptor" to descriptor.getValue("captured_certificate_descriptor"),
        )).toString().encodeToByteArray())
        val provenance = NativeOriginalJournalProvenanceV1(
            scope,
            NativeOriginalJournalProvenanceV1.HistoricalStart(actor, OperationId(id(20).value), command,
                fillerDigest, id(21), Revision(0), id(22), id(23), Revision(0), JournalSequence(1),
                fillerDigest, fillerDigest, UtcInstant.parseCanonical("2026-11-06T00:00:00Z")),
            NativeOriginalJournalProvenanceV1.NativeOriginal("sales.retail-refund/v3", operation,
                sha256(requirement.bytes()), sha256(certificate.bytes()), fillerDigest, fillerDigest,
                source(NativeOriginalJournalProvenanceV1.SourceRole.MASTER, sourceData),
                source(NativeOriginalJournalProvenanceV1.SourceRole.CLIENT, sourceData)),
            NativeOriginalJournalProvenanceV1.CurrentWriter(id(30), id(23), id(31), id(32), id(33), id(22),
                Revision(0), "comparison.inert-refund-data/v1", fillerDigest),
            NativeOriginalJournalProvenanceV1.RuntimeParent(JournalSequence(1), digest(sourceData.text("original_cut_label"))),
        )
        return RuntimeNativeOriginalImportEvidenceV1(scope,
            StreamedProducerIdentityV2(operation, command, actor, fillerDigest), requirement, certificate,
            provenance, digest(descriptor.getValue("captured_outcome_descriptor").toString()), 7)
    }

    private fun source(role: NativeOriginalJournalProvenanceV1.SourceRole, descriptor: JsonObject) =
        NativeOriginalJournalProvenanceV1.Source(role, id(40), id(41), id(42), id(43),
            id(if (role == NativeOriginalJournalProvenanceV1.SourceRole.MASTER) 44 else 45), 0,
            digest(descriptor.toString()), fillerDigest, fillerDigest,
            digest(descriptor.text("origin_label")))

    private companion object {
        val frozen = checkNotNull(ColdRefundInertDataTest::class.java.getResourceAsStream(
            "/independent/coldoriginalstartv1/supporting-inert-refund-data-fixtures-v1.json")).use {
            Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
        }.also {
            check(it.getValue("schema_version").jsonPrimitive.int == 1)
            check(it.text("design_version") == "supporting-inert-refund-data-v1")
            check(it.text("execution_status") == "UNRUN")
            check(it.getValue("identical_candidate").jsonObject.text("expected") == "MATCH")
        }
        val reference = frozen.getValue("reference_descriptor").jsonObject
        val referenceSnapshot = reference.toString()
        val fillerDigest = digest("Unasserted inert refund syntax filler; not native evidence")
        val eventDigest = digest("Inert refund comparison EVENT; not a native event")
        val header = RuntimeHistoryEventHeaderV2(
            scope = "${id(1).value}/${id(2).value}/${reference.getValue("original_source_descriptor").jsonObject.text("branch_id")}",
            storageId = id(50), bindingDigest = fillerDigest, originalAllocationDigest = fillerDigest,
            definitionDigest = fillerDigest, installationAttemptId = id(51), installationAttemptSequence = 1,
            installationGenesisDigest = fillerDigest, sequence = 2,
            parentKind = RuntimeHistoryParentKindV2.RECORDED_EVENT.tag, parentSequence = 1, parentDigest = fillerDigest,
            transactionId = id(52), assignmentId = id(22), purpose = RuntimeWritePurposeV2.COMMAND_APPLY,
            commandEpoch = 0, commandSequence = 1, commandDigest = fillerDigest, roleCommitment = fillerDigest,
            recoveryCommitment = fillerDigest, changeCount = 7, chunkCount = 1, imageBytes = 0,
            intentBytes = 1, intentDigest = fillerDigest, dependencyBytes = 0, dependencyDigest = fillerDigest,
            factBytes = 0, factDigest = fillerDigest,
        )
        fun id(value: Int): DomainId = DomainId("6ed0da7a-0000-4000-8000-${value.toString(16).padStart(12, '0')}")
        fun digest(value: String): PayloadDigest = sha256(value.encodeToByteArray())
        fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    }
}
