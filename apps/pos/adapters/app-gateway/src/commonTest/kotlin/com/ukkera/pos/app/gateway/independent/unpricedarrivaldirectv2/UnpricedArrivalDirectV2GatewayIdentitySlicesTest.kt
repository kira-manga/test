package com.ukkera.pos.app.gateway.independent.unpricedarrivaldirectv2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.inputDigest
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.PurchasingEvidenceMediaV1
import com.ukkera.pos.security.PurchasingEvidencePurposeV1
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pure gateway input-identity slices of frozen UAD2-03/UAD2-05.
 * Same-ID changed original/policy/rule/retention content must not share staged input
 * identity even when the caller repeats old scalar refresh pins. This does not run
 * stage, conflict handling, authorization, commit, retry, races, history or native UI.
 * All seven WHOLE cases remain NOT_RUN; those genuine production lanes are UNBOUND.
 */
public class UnpricedArrivalDirectV2GatewayIdentitySlicesTest {
    @Test
    public fun uad203And05UnchangedCompleteTypedSelectionHasStableInputIdentity(): Unit {
        val first = Uad2GatewayInputs.intent(Uad2GatewayInputs.selection())
        val repeated = Uad2GatewayInputs.intent(Uad2GatewayInputs.selection())
        assertEquals(first.operationId, repeated.operationId)
        assertEquals(first.arrivalEvidence?.equalityDigest, repeated.arrivalEvidence?.equalityDigest)
        assertEquals(inputDigest(first), inputDigest(repeated))
        assertEquals(inputDigest(first), inputDigest(intentWith(first, values = first.values.reversed())))
        assertNotEquals(inputDigest(first), inputDigest(intentWith(first, arrivalEvidence = null)),
            "The complete typed carrier contributes independently of scalar display pins")
        assertNotEquals(inputDigest(first), inputDigest(intentWith(first, arrivalEvidence =
            Uad2GatewayInputs.selection(refreshPin = Uad2GatewayInputs.changedDigest))))
    }

    @Test
    public fun uad203And05SameIdOriginalManifestChangesCannotReuseScalarOnlyIdentity(): Unit {
        val originals = Uad2GatewayInputs.documents()
        val first = originals[0]
        val changes = listOf(
            "document ID" to first.copy(documentId = Uad2GatewayInputs.id("DOC-S")),
            "storage revision" to first.copy(storageRevision = Revision(2)),
            "exact bytes digest" to first.copy(exactBytesDigest = Uad2GatewayInputs.changedDigest),
            "exact byte length" to first.copy(byteLength = 615),
            "media" to first.copy(media = PurchasingEvidenceMediaV1.JPEG),
            "purpose" to first.copy(purpose = PurchasingEvidencePurposeV1.INSPECTION),
            "supplier" to first.copy(supplierId = Uad2GatewayInputs.id("SUPPLIER-OTHER")),
        )
        assertChangedIdentities(Uad2GatewayInputs.selection(), changes.map { (label, original) ->
            label to Uad2GatewayInputs.selection(documents = listOf(original, originals[1]))
        })
    }

    @Test
    public fun uad203And05OriginalOrderAndSupplementalParentHeadAreIdentityMaterial(): Unit {
        assertChangedIdentities(Uad2GatewayInputs.selection(), listOf(
            "reverse DOC-A/DOC-B" to Uad2GatewayInputs.selection(documents = Uad2GatewayInputs.documents().reversed())))
        val original = Uad2GatewayInputs.documents()[0]
        val supplemental = Uad2GatewayInputs.supplemental()
        val head = requireNotNull(supplemental.parentHead)
        val baseline = Uad2GatewayInputs.selection(documents = listOf(original, supplemental))
        val changes = listOf(
            "parent head storage" to supplemental.copy(parentHead = head.copy(storageRevision = Revision(3))),
            "parent head digest" to supplemental.copy(parentHead = head.copy(digest = Uad2GatewayInputs.changedDigest)),
            "parent head kind" to supplemental.copy(parentHead = head.copy(kind = "uad2.changed-parent-head-carrier")),
            "parent original" to supplemental.copy(parentDocumentId = Uad2GatewayInputs.id("DOC-B")),
            "parent head ID" to supplemental.copy(parentHead = head.copy(id = Uad2GatewayInputs.id("DOC-B"))),
            "omitted supplemental coordinates" to supplemental.copy(parentDocumentId = null, parentHead = null),
        )
        assertChangedIdentities(baseline, changes.map { (label, child) ->
            label to Uad2GatewayInputs.selection(documents = listOf(original, child))
        })
    }

    @Test
    public fun uad203And05PolicyCatalogAndRequesterCoordinatesCannotShareIdentity(): Unit {
        val policy = Uad2GatewayInputs.policy
        val catalog = Uad2GatewayInputs.catalog
        val changes = listOf(
            "policy ID" to Uad2GatewayInputs.selection(policy = policy.copy(policyId = Uad2GatewayInputs.id("CATALOG-A"))),
            "policy publication ID" to Uad2GatewayInputs.selection(policy = policy.copy(publicationId = Uad2GatewayInputs.id("CATALOG-RELEASE-7"),
                publication = policy.publication.copy(id = Uad2GatewayInputs.id("CATALOG-RELEASE-7")))),
            "policy publication revision" to Uad2GatewayInputs.selection(policy = policy.copy(publicationRevision = Revision(9))),
            "policy active kind" to Uad2GatewayInputs.selection(policy = policy.copy(activePointer = policy.activePointer.copy(kind = "uad2.changed-policy-active-carrier"))),
            "policy active ID" to Uad2GatewayInputs.selection(policy = policy.copy(activePointer = policy.activePointer.copy(id = Uad2GatewayInputs.id("BRANCH-B")))),
            "policy active storage" to Uad2GatewayInputs.selection(policy = policy.copy(activePointer = policy.activePointer.copy(storageRevision = Revision(0)))),
            "policy active digest" to Uad2GatewayInputs.selection(policy = policy.copy(activePointer = policy.activePointer.copy(digest = Uad2GatewayInputs.changedDigest))),
            "policy publication kind" to Uad2GatewayInputs.selection(policy = policy.copy(publication = policy.publication.copy(kind = "uad2.changed-policy-publication-carrier"))),
            "policy publication storage" to Uad2GatewayInputs.selection(policy = policy.copy(publication = policy.publication.copy(storageRevision = Revision(2)))),
            "policy publication digest" to Uad2GatewayInputs.selection(policy = policy.copy(publication = policy.publication.copy(digest = Uad2GatewayInputs.changedDigest))),
            "policy valid from" to Uad2GatewayInputs.selection(policy = policy.copy(validFrom = UtcInstant.parseCanonical("2026-11-02T00:00:00Z"))),
            "policy valid until" to Uad2GatewayInputs.selection(policy = policy.copy(validUntil = UtcInstant.parseCanonical("2026-11-03T00:00:00Z"))),
            "catalog ID" to Uad2GatewayInputs.selection(catalog = catalog.copy(catalogId = Uad2GatewayInputs.id("POLICY-A"))),
            "catalog publication ID" to Uad2GatewayInputs.selection(catalog = catalog.copy(publicationId = Uad2GatewayInputs.id("PUBLICATION-A"),
                publication = catalog.publication.copy(id = Uad2GatewayInputs.id("PUBLICATION-A")))),
            "catalog release revision" to Uad2GatewayInputs.selection(catalog = catalog.copy(revision = Revision(8))),
            "catalog active kind" to Uad2GatewayInputs.selection(catalog = catalog.copy(activePointer = catalog.activePointer.copy(kind = "uad2.changed-catalog-active-carrier"))),
            "catalog active ID" to Uad2GatewayInputs.selection(catalog = catalog.copy(activePointer = catalog.activePointer.copy(id = Uad2GatewayInputs.id("BRANCH-B")))),
            "catalog active storage" to Uad2GatewayInputs.selection(catalog = catalog.copy(activePointer = catalog.activePointer.copy(storageRevision = Revision(0)))),
            "catalog active digest" to Uad2GatewayInputs.selection(catalog = catalog.copy(activePointer = catalog.activePointer.copy(digest = Uad2GatewayInputs.changedDigest))),
            "catalog publication kind" to Uad2GatewayInputs.selection(catalog = catalog.copy(publication = catalog.publication.copy(kind = "uad2.changed-catalog-publication-carrier"))),
            "catalog publication storage" to Uad2GatewayInputs.selection(catalog = catalog.copy(publication = catalog.publication.copy(storageRevision = Revision(2)))),
            "catalog publication digest" to Uad2GatewayInputs.selection(catalog = catalog.copy(publication = catalog.publication.copy(digest = Uad2GatewayInputs.changedDigest))),
            "requester employee-policy revision" to Uad2GatewayInputs.selection(requester = Revision(12)),
            "coherent company scope" to scopedSelection(Uad2GatewayInputs.scope.copy(companyId = Uad2GatewayInputs.id("COMPANY-B"))),
            "coherent branch scope" to scopedSelection(Uad2GatewayInputs.scope.copy(branchId = Uad2GatewayInputs.id("BRANCH-B"))),
        )
        assertChangedIdentities(Uad2GatewayInputs.selection(), changes)
    }

    @Test
    public fun uad203And05ExactRuleAndMatchingRetentionChoicesCannotShareIdentity(): Unit {
        val changedClass = Uad2GatewayInputs.retention.copy(classId = DomainId("90000000-0000-4000-8000-000000000008"))
        val changedRevision = Uad2GatewayInputs.retention.copy(revision = Revision(3))
        val changes = listOf(
            "selected rule" to Uad2GatewayInputs.rule(id = Uad2GatewayInputs.id("RULE-OPTIONAL")),
            "direct-only mode" to Uad2GatewayInputs.rule(direct = false),
            "required originals" to Uad2GatewayInputs.rule(minimum = 0),
            "maximum originals" to Uad2GatewayInputs.rule(maximum = 1),
            "allowed media" to Uad2GatewayInputs.rule(media = listOf(PurchasingEvidenceMediaV1.JPEG)),
            "allowed purpose" to Uad2GatewayInputs.rule(purposes = listOf(PurchasingEvidencePurposeV1.INSPECTION)),
            "matching retention class" to Uad2GatewayInputs.rule(retention = changedClass),
            "matching retention revision" to Uad2GatewayInputs.rule(retention = changedRevision),
        )
        assertChangedIdentities(Uad2GatewayInputs.selection(), changes.map { (label, rule) ->
            label to Uad2GatewayInputs.selection(rule = rule)
        })
        val optional = Uad2GatewayInputs.rule(id = Uad2GatewayInputs.id("RULE-OPTIONAL"), minimum = 0)
        val emptyOriginals = Uad2GatewayInputs.selection(rule = optional, documents = emptyList())
        assertChangedIdentities(emptyOriginals, listOf("zero-file retention still exact" to
            Uad2GatewayInputs.selection(rule = Uad2GatewayInputs.rule(id = Uad2GatewayInputs.id("RULE-OPTIONAL"),
                minimum = 0, retention = changedRevision), documents = emptyList())))
    }

    @Test
    public fun nullCarrierPreservesIndependentPreChangeUnscopedAndScopedRecipes(): Unit {
        val unscoped = LocalCommandIntent(Uad2GatewayInputs.operation, LocalAction.ARRIVAL_RECORD, 1,
            null, listOf(LocalScalarInput(LocalField.REASON, LocalScalarValue.Text("syn.record"))))
        val scoped = intentWith(unscoped, reference = LocalRecordReference(LocalRecordKind.ARRIVAL_SLICE,
            Uad2GatewayInputs.custodyCarrier, Revision(2), Uad2GatewayInputs.id("DELIVERY-MAIN")))
        // The original recipe is explicitly enumerated, without calling the production framer.
        val commonTail = listOf("1", "REASON", "text", "syn.record", "", "0")
        val unscopedRecipe = listOf("ukkera.local-ui-intent/v1", Uad2GatewayInputs.operation.value,
            "ARRIVAL_RECORD", "1", "", "", "") + commonTail
        val scopedRecipe = listOf("ukkera.local-ui-intent/v2", Uad2GatewayInputs.operation.value,
            "ARRIVAL_RECORD", "1", "ARRIVAL_SLICE", Uad2GatewayInputs.custodyCarrier.value, "2",
            Uad2GatewayInputs.id("DELIVERY-MAIN").value) + commonTail
        assertEquals(independentFramedDigest(unscopedRecipe), inputDigest(unscoped))
        assertEquals(independentFramedDigest(scopedRecipe), inputDigest(scoped))
        assertNotEquals(inputDigest(unscoped), inputDigest(scoped))
    }

    private fun assertChangedIdentities(baseline: LocalArrivalEvidenceInputV2,
        changes: List<Pair<String, LocalArrivalEvidenceInputV2>>) {
        val original = Uad2GatewayInputs.intent(baseline)
        val originalDigest = inputDigest(original)
        for ((label, changed) in changes) {
            val sameScalarInput = intentWith(original, arrivalEvidence = changed)
            assertEquals(original.operationId, sameScalarInput.operationId, label)
            assertEquals(original.reference, sameScalarInput.reference, label)
            assertEquals(original.values, sameScalarInput.values, label)
            assertEquals(original.lines, sameScalarInput.lines, label)
            assertEquals(baseline.formSourceDigest, changed.formSourceDigest,
                "$label: repeating a display pin cannot stand in for complete carrier identity")
            assertNotEquals(baseline.equalityDigest, changed.equalityDigest, label)
            assertNotEquals(originalDigest, inputDigest(sameScalarInput), label)
        }
    }

    private fun scopedSelection(scope: BranchScope): LocalArrivalEvidenceInputV2 = Uad2GatewayInputs.selection(
        policy = Uad2GatewayInputs.policy.copy(scope = scope), catalog = Uad2GatewayInputs.catalog.copy(scope = scope),
        documents = Uad2GatewayInputs.documents().map { it.copy(scope = scope) })

    private fun intentWith(original: LocalCommandIntent,
        reference: LocalRecordReference? = original.reference,
        values: List<LocalScalarInput> = original.values,
        arrivalEvidence: LocalArrivalEvidenceInputV2? = original.arrivalEvidence): LocalCommandIntent =
        LocalCommandIntent(original.operationId, original.action, original.formVersion, reference,
            values, original.lineField, original.lines, arrivalEvidence)

    private fun independentFramedDigest(values: List<String>): PayloadDigest =
        sha256(values.joinToString("") { "${it.length}:$it" }.encodeToByteArray())
}
