@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.returnpendingprofilecompatibilityv1

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.store.LocalCashEvidenceV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptProfileV1
import com.ukkera.pos.sales.store.LocalReturnCaseActionV1
import com.ukkera.pos.sales.store.LocalReturnCaseAssessmentV1
import com.ukkera.pos.sales.store.LocalReturnCaseCommandCodecV1
import com.ukkera.pos.sales.store.LocalReturnCaseEventV1
import com.ukkera.pos.sales.store.LocalReturnCaseFactsV1
import com.ukkera.pos.sales.store.LocalReturnCaseProfileV1
import com.ukkera.pos.sales.store.LocalReturnCaseRequestV1
import com.ukkera.pos.sales.store.LocalReturnCaseResultV1
import com.ukkera.pos.sales.store.LocalReturnCaseSourceReferenceV1
import com.ukkera.pos.sales.store.LocalReturnCaseStageV1
import com.ukkera.pos.sales.store.LocalReturnCaseStateV1
import com.ukkera.pos.sales.store.LocalReturnClaimFactsV1
import com.ukkera.pos.sales.store.LocalReturnCommercialTimingV1
import com.ukkera.pos.sales.store.LocalReturnRequestedRemedyV1
import com.ukkera.pos.sales.store.LocalSalesReturnPolicyCaptureCodecV2
import com.ukkera.pos.sales.store.LocalSalesScope
import com.ukkera.pos.sales.store.LocalSalesStoreProfile
import com.ukkera.pos.sales.store.localSalesEncode
import com.ukkera.pos.sales.store.returnCaseSourceResources
import com.ukkera.pos.sales.store.returnCaseUnavailableAssessment
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Inert DTO mappings only. Suppressed constructor visibility is confined to retained
 * evidence/write DATA; no scan, principal, authorization or native capability is built.
 * The synthetic committed label and bookkeeping hashes do not prove a commit.
 * This fixture has only one event write, not a complete production history bundle.
 *
 * L/S0/S1 scope, logical original identities and assigned timing/source IDs come from
 * the frozen inputs. Case/unit/append/capture/session/assignment IDs, case/entitlement
 * revisions, before digests and unassigned producer coordinates are shape fillers.
 * Source kind/schema names map current DTOs; no source payload or signature is supplied.
 * The unavailable helper supplies a supported assessment shape, not a new wire oracle.
 */
internal object ReturnPendingProfileDataFixture {
    private val frozen = FrozenReturnPendingProfileInputs
    val scope = BranchScope(DomainId(frozen.scope.text("organization")),
        DomainId(frozen.scope.text("company")), DomainId(frozen.scope.text("branch")))
    val actor = DomainId(frozen.scope.text("originalActor"))

    data class Profile(val logicalId: String, val operationId: OperationId,
        val request: LocalReturnCaseRequestV1.Qualify, val event: LocalReturnCaseEventV1)

    fun profile(logicalId: String): Profile {
        val original = frozen.original(logicalId)
        val ordinal = when (logicalId) { "L" -> 1; "S0" -> 2; "S1" -> 3; else -> error("Unknown logical fixture") }
        val operationId = OperationId(original.text("operation"))
        val caseId = shapeId(200 + ordinal)
        val intakeId = if (logicalId == "L") OperationId(shapeId(301).value)
            else OperationId(frozen.sources.text("intakeOperation$logicalId"))
        val appendId = OperationId(shapeId(310 + ordinal).value)
        val submittedAt = UtcInstant.parseCanonical(frozen.sources.text("originalMasterSubmissionAt"))
        val processingAt = UtcInstant.parseCanonical(frozen.sources.text("initialQualificationProcessingAt"))
        val evidence = LocalCashEvidenceV1(shapeId(320 + ordinal), submittedAt)
        val assessmentShape = returnCaseUnavailableAssessment(originalReceiptPresent = true)
        val facts = LocalReturnCaseFactsV1(LocalReturnClaimFactsV1(
            DomainId(original.text("sale")), DomainId(original.text("portion")),
            Quantity.parse(shapeId(330), frozen.sources.text("soldQuantity"), frozen.sources.text("originalPrecision").toInt()),
            assertedReceivedAt = null, claimedAt = submittedAt, reason = assessmentShape.reasonCode, evidence = evidence),
            condition = assessmentShape.route, requestedRemedy = LocalReturnRequestedRemedyV1.REFUND,
            claimTimeZoneId = frozen.sources.text("customerReceiptZone"))
        val after = LocalReturnCaseStateV1(LocalSalesScope.from(scope), caseId, actor, Revision(2),
            intakeId, operationId, submittedAt, facts, LocalReturnCaseStageV1.PENDING,
            listOf(intakeId, appendId, operationId), appendId, operationId)
        val request = LocalReturnCaseRequestV1.Qualify(caseId, Revision(1), Revision(0),
            recordOriginalCommercialTiming = logicalId == "S1")
        val assessment = assessment(logicalId, intakeId)
        val references = if (logicalId == "L") emptyList() else
            listOfNotNull(assessment.salePolicyCapture) + assessment.applicableRights + assessment.customerReceipts
        val result = LocalReturnCaseResultV1.Applied(LocalReturnCaseActionV1.ASSESSMENT_PENDING,
            caseId, after.revision, after.stage, operationId, entitlementRevision = Revision(0))
        val event = LocalReturnCaseEventV1(after, LocalReturnCaseActionV1.ASSESSMENT_PENDING,
            operationId, processingAt, appendId, shapeDigest("case before $logicalId"), appendId,
            shapeDigest("index before $logicalId"), assessment = assessment,
            assessedBindingDigest = shapeDigest("binding $logicalId"), expectedEntitlementRevision = Revision(0),
            entitlementRevision = Revision(0), beforeEntitlementDigest = shapeDigest("entitlement before $logicalId"),
            qualificationSources = references, sourceRecoveryResources = sortedResources(returnCaseSourceResources(references)),
            result = result)
        return Profile(logicalId, operationId, request, event)
    }

    private fun assessment(logicalId: String, intakeId: OperationId): LocalReturnCaseAssessmentV1 {
        val absent = returnCaseUnavailableAssessment(originalReceiptPresent = true)
        if (logicalId == "L") return absent
        val capture = source(LocalSalesReturnPolicyCaptureCodecV2.KIND, shapeId(if (logicalId == "S0") 402 else 403),
            LocalSalesReturnPolicyCaptureCodecV2.SCHEMA, OperationId(shapeId(if (logicalId == "S0") 412 else 413).value))
        val rights = source("local.return-rights.publication", DomainId(frozen.sources.text("retainedRightsPublication")),
            "local.return-rights.publication/v1", OperationId(frozen.sources.text("retainedRightsActivationOperation")))
        val receiptId = DomainId(frozen.sources.text("receiptEvent$logicalId"))
        val receipt = source(LocalRetailCustomerReceiptProfileV1.EVENT, receiptId,
            LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA, OperationId(receiptId.value))
        val timing = if (logicalId == "S1") LocalReturnCommercialTimingV1.observe(
            frozen.sources.text("capturedOrdinaryCommercialDays").toInt(), receiptId,
            UtcInstant.parseCanonical(frozen.sources.text("customerReceiptAt")), frozen.sources.text("customerReceiptZone"),
            intakeId, UtcInstant.parseCanonical(frozen.sources.text("originalMasterSubmissionAt"))) else null
        return absent.copy(policyId = DomainId(frozen.sources.text("capturedPromisePolicy")),
            policyRevision = Revision(frozen.sources.text("capturedPromiseRevision").toLong()),
            salePolicyCapture = capture, applicableRights = listOf(rights), customerReceipts = listOf(receipt),
            customerReceivedAt = UtcInstant.parseCanonical(frozen.sources.text("customerReceiptAt")),
            receiptTimeZoneId = frozen.sources.text("customerReceiptZone"), originalCommercialTiming = timing)
    }

    private fun source(kind: String, id: DomainId, schema: String, operationId: OperationId): LocalReturnCaseSourceReferenceV1 =
        LocalReturnCaseSourceReferenceV1(kind, id, schema, Revision(1), shapeDigest("source $kind ${id.value}"),
            operationId, actor, LocalReturnCaseProfileV1.COMMAND.name, LocalReturnCaseProfileV1.COMMAND.version)

    fun retained(profile: Profile, event: LocalReturnCaseEventV1 = profile.event,
        writes: List<RetainedAggregateWrite> = listOf(write(profile, event))): RetainedCommandEvidence {
        val normalized = LocalReturnCaseCommandCodecV1.encode(profile.request)
        val result = LocalReturnCaseCommandCodecV1.result(event.result)
        return RetainedCommandEvidence(scope, profile.operationId, actor, LocalReturnCaseProfileV1.COMMAND,
            shapeId(500), Revision(0), JournalSequence(1), event.recordedAt, shapeId(501), Revision(0),
            semanticDigest(scope, actor, LocalReturnCaseProfileV1.COMMAND, normalized), "committed", normalized,
            result, sha256(result.copyBytes()), shapeDigest("previous journal bookkeeping"),
            shapeDigest("journal bookkeeping"), writes, emptyList(), emptyList())
    }

    fun write(profile: Profile, event: LocalReturnCaseEventV1 = profile.event,
        key: AggregateKey = LocalReturnCaseProfileV1.key(LocalReturnCaseProfileV1.EVENT, DomainId(profile.operationId.value)),
        generation: Revision = Revision(1), schema: String = LocalSalesStoreProfile.schema(LocalReturnCaseProfileV1.EVENT)): RetainedAggregateWrite {
        val payload = localSalesEncode(event, LocalReturnCaseEventV1.serializer())
        return RetainedAggregateWrite(key, generation, schema, payload, sha256(payload.copyBytes()))
    }

    fun sortedResources(resources: Collection<RecoveryResourceKey>): List<RecoveryResourceKey> =
        resources.sortedWith(compareBy({ it.kind.name }, { it.resourceId.value }))

    private fun shapeId(serial: Int): DomainId = DomainId("22000000-0000-4000-8000-${serial.toString().padStart(12, '0')}")
    private fun shapeDigest(label: String): PayloadDigest = sha256("inert profile data: $label".encodeToByteArray())
}
