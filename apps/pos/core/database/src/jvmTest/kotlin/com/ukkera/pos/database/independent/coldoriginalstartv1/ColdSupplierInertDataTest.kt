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
 * Frozen CSP-DATA-001..003, supporting comparison only. No native owner, session,
 * committed Start, authenticated principal, accepted invoice/payable, protected
 * source read, actual close proof or dispatcher exists here. No supplier business
 * workflow, physical payout or cash/debt/stock/value/expense effect is exercised.
 *
 * All eight descriptor groups and every nested field are bound. Actor/command
 * have distinct explicit comparison-only DATA aliases; operation/posting branch
 * retain their UUIDs. The complete source JSON, including supplier, invoice,
 * payable, receipt allocation and original amounts, is retained in the source
 * payload digest; the source cut also has a separate parent digest. Complete
 * Start/certificate DATA are retained in certificate bytes, requirement DATA in
 * requirement bytes, and complete outcome DATA in nativeRecordDigest. These are
 * synthetic JSON/label DATA, NOT valid certificates, requirements or native-record
 * encodings. No authenticity, production codec or supported-domain test is made.
 * Unspecified provenance/header/profile fields are constant syntax fillers, not
 * evidence or a supplier-native machine profile. The existing generic comparator
 * is exercised directly; no native validation, intake or financial API is called.
 */
class ColdSupplierInertDataTest {
    @Test
    fun cspData001CompleteSelfAndSeparateCopyMatch() = comparison { target ->
        assertEquals("MATCH", frozen.text("self_comparison_expected"))
        assertEquals(reference, identical)
        target.selectNative(header, eventDigest, checkNotNull(target.nativeOriginal))
        withCandidate(identical) { target.selectNative(header, eventDigest, it) }
        requireUnchangedDescriptors()
    }

    @Test
    fun cspData002EveryTopLevelAndNestedSupplierChangeDoesNotMatch() = comparison { target ->
        val topLevel = frozen.getValue("top_level_one_field_changes").jsonArray
        val nested = frozen.getValue("nested_one_field_changes").jsonArray
        assertEquals(8, topLevel.size)
        assertEquals(29, nested.size)
        for (raw in topLevel + nested) {
            val variant = raw.jsonObject
            assertEquals("DOES_NOT_MATCH", variant.text("expected"))
            // Construction/alias binding are outside assertFailsWith: only the
            // production DATA comparison rejection may satisfy this assertion.
            withCandidate(changed(identical, variant)) { candidate ->
                assertFailsWith<RuntimeHistoryFormatRejectedV2>(variant.text("id")) {
                    target.selectNative(header, eventDigest, candidate)
                }
            }
            withCandidate(identical) { target.selectNative(header, eventDigest, it) }
        }
        requireUnchangedDescriptors()
    }

    @Test
    fun cspData003RepeatedComparisonAndDisposalReturnOnlyInertData() = comparison { target ->
        val variants = frozen.getValue("top_level_one_field_changes").jsonArray +
            frozen.getValue("nested_one_field_changes").jsonArray
        repeat(2) {
            target.selectNative(header, eventDigest, checkNotNull(target.nativeOriginal))
            withCandidate(identical) { target.selectNative(header, eventDigest, it) }
            for (raw in variants) {
                val variant = raw.jsonObject
                assertEquals("DOES_NOT_MATCH", variant.text("expected"))
                withCandidate(changed(identical, variant)) { candidate ->
                    assertFailsWith<RuntimeHistoryFormatRejectedV2>(variant.text("id")) {
                        target.selectNative(header, eventDigest, candidate)
                    }
                }
                withCandidate(identical) { target.selectNative(header, eventDigest, it) }
            }
        }
        requireUnchangedDescriptors()
        // comparison() disposes only the inert DATA target. No authority,
        // physical publication or business-execution interface exists here.
        // Do not invent zero payout/debt/stock/expense counters, native settlement
        // or current-close evidence, or post-disposal comparison behavior.
    }

    private fun requireUnchangedDescriptors() {
        assertEquals(referenceSnapshot, reference.toString())
        assertEquals(identicalSnapshot, identical.toString())
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
        val sourceData = descriptor.getValue("source_descriptor").jsonObject
        val scope = BranchScope(id(1), id(2), DomainId(sourceData.text("designated_posting_branch_id")))
        val actor = when (descriptor.text("original_actor_descriptor")) {
            "ACTOR-SUPPLIER-PAYMENT-original" -> id(10)
            "different-original_actor_descriptor" -> id(11)
            else -> error("Unbound frozen supplier actor DATA label")
        }
        val command = when (descriptor.text("command_descriptor")) {
            "opaque-original-supplier-cash-payment-command" -> CommandKey("comparison.inert-supplier-payment-data", 1)
            "different-command_descriptor" -> CommandKey("comparison.different-supplier-payment-data", 1)
            else -> error("Unbound frozen supplier command DATA label")
        }
        val operation = OperationId(descriptor.text("operation_id"))
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
            NativeOriginalJournalProvenanceV1.NativeOriginal(syntaxProfile, operation,
                sha256(requirement.bytes()), sha256(certificate.bytes()), fillerDigest, fillerDigest,
                source(NativeOriginalJournalProvenanceV1.SourceRole.MASTER, sourceData),
                source(NativeOriginalJournalProvenanceV1.SourceRole.CLIENT, sourceData)),
            NativeOriginalJournalProvenanceV1.CurrentWriter(id(30), id(23), id(31), id(32), id(33), id(22),
                Revision(0), syntaxProfile, fillerDigest),
            NativeOriginalJournalProvenanceV1.RuntimeParent(JournalSequence(1), digest(sourceData.text("source_cut_label"))),
        )
        return RuntimeNativeOriginalImportEvidenceV1(scope,
            StreamedProducerIdentityV2(operation, command, actor, fillerDigest), requirement, certificate,
            provenance, digest(descriptor.getValue("captured_outcome_descriptor").toString()), 7)
    }

    private fun source(role: NativeOriginalJournalProvenanceV1.SourceRole, descriptor: JsonObject) =
        NativeOriginalJournalProvenanceV1.Source(role, id(40), id(41), id(42), id(43),
            id(if (role == NativeOriginalJournalProvenanceV1.SourceRole.MASTER) 44 else 45), 0,
            digest(descriptor.toString()), fillerDigest, fillerDigest, fillerDigest)

    private companion object {
        val frozen = checkNotNull(ColdSupplierInertDataTest::class.java.getResourceAsStream(
            "/independent/coldoriginalstartv1/supporting-inert-supplier-payment-data-fixtures-v1.json")).use {
            Json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
        }.also {
            check(it.text("schema_version") == "1")
            check(it.text("design_version") == "supporting-inert-supplier-payment-data-v1")
            check(it.text("execution_status") == "UNRUN")
            check(it.getValue("case_ids").jsonArray.map { id -> id.jsonPrimitive.content } ==
                listOf("CSP-DATA-001", "CSP-DATA-002", "CSP-DATA-003"))
            check(it.getValue("identical_candidate").jsonObject.text("expected") == "MATCH")
            // Guard the frozen no-effect expectations as input DATA only. These
            // flags are not observed counters or evidence of real business effects.
            check(it.getValue("comparison_effect_expectation").jsonObject.values.all { value ->
                !value.jsonPrimitive.boolean
            })
        }
        val reference = frozen.getValue("reference_descriptor").jsonObject
        val identical = frozen.getValue("identical_candidate").jsonObject.getValue("descriptor").jsonObject
        val referenceSnapshot = reference.toString()
        val identicalSnapshot = identical.toString()
        const val syntaxProfile = "comparison.inert-data/v1"
        val fillerDigest = digest("Unasserted inert supplier syntax filler; not native evidence")
        val eventDigest = digest("Inert supplier comparison EVENT; not a native event")
        val header = RuntimeHistoryEventHeaderV2(
            scope = "${id(1).value}/${id(2).value}/${reference.getValue("source_descriptor").jsonObject.text("designated_posting_branch_id")}",
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
