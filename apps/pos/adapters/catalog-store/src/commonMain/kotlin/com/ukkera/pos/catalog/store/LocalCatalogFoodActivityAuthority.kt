package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.store.LocalCommerceOriginalSources
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.PreparedFoodActivityAdmission

/**
 * Exact branch-applied activity prerequisite for FOOD, additional to current employee
 * authorization. Original verification consumes literal sources, never today's configuration
 * or a reconstructed historical CommandReadView. Native BEFORE qualification stays separate.
 */
internal class LocalCatalogFoodActivityAuthority(private val signatures: CompanyPublicationSignatureVerifier) {
    suspend fun prepare(view: CommandReadView, activity: LocalSalesActivity): PreparedFoodActivityAdmission {
        requireFoodActivity(activity)
        val reader = CurrentBranchActivityReader(view, signatures)
        // Finish the selected row/claim/original/companion graph before unavailable trust.
        val applied = reader.active()
        val enabled = try {
            reader.requireAuthenticSources()
            applied != null && activity in applied.signed.definition.activities
        } catch (_: BranchActivitySourceUnavailable) {
            false
        }
        // False is only this admission conclusion, not an ABSENT/APPLIED read projection.
        return PreparedFoodActivityAdmission(enabled, reader.resources)
    }

    suspend fun requireOriginal(sources: LocalCommerceOriginalSources,
        activity: LocalSalesActivity): Set<RecoveryResourceKey> {
        requireFoodActivity(activity)
        val scope = sources.scope
        val active = sources.aggregate(branchActivityKey(LocalBranchActivityProfileV1.ACTIVE, scope.branchId))
            ?: error("food.activity.original_active_missing")
        val activeRecord = decodeBranchActivityRecord(scope, active.key, active.generation, active.schema,
            active.payload, active.digest)
        val reference = (activeRecord.body as BranchActivityBodyV1.Active).reference
        val publication = sources.aggregate(branchActivityKey(LocalBranchActivityProfileV1.PUBLICATION, reference.publicationId))
            ?: error("food.activity.original_publication_missing")
        val publicationRecord = decodeBranchActivityRecord(scope, publication.key, publication.generation, publication.schema,
            publication.payload, publication.digest)
        val signed = branchActivitySigned((publicationRecord.body as BranchActivityBodyV1.Publication).request)
        val branchOwner = sources.claim(branchActivityBranchClaim(scope))
        val publicationOwner = sources.claim(branchActivityPublicationClaim(reference.publicationId))
        check(branchOwner == reference.publicationId && publicationOwner == reference.publicationId)
        check(branchActivityReference(signed) == reference && activeRecord.producer == publicationRecord.producer &&
            activeRecord.recordedAt == publicationRecord.recordedAt &&
            activeRecord.permissionPolicyRevision == publicationRecord.permissionPolicyRevision)

        // The companion equality selects one actual activation original, not caller provenance.
        val original = (sources.original(activeRecord.producer.operationId) as? RetainedOriginalV2.Legacy)?.evidence
            ?: error("food.activity.original_activation_missing")
        requireBranchActivityStoredWrite(scope, active.key, active.generation, active.schema, active.payload, active.digest,
            activeRecord, original)
        requireBranchActivityStoredWrite(scope, publication.key, publication.generation, publication.schema,
            publication.payload, publication.digest, publicationRecord, original)
        val food = sources.original.identity
        check(food.producer.command == StockStreamingCommandsV2.FOOD && original.committed &&
            original.scope == food.scope && original.assignmentId == food.assignmentId &&
            original.authorityEpoch == food.authorityEpoch && original.sequence < food.sequence &&
            original.operationId != food.producer.operationId)

        // Unsupported native intake is not an employee activity activation. All literal
        // companions, claims and matching writes above still fail structurally when corrupt.
        if (original.nativeOriginalProvenance == null) check(requireBranchActivityProducerShape(original) == signed)
        requireBranchActivitySource(original.nativeOriginalProvenance == null)
        requireBranchActivitySource(authenticBranchActivity(signatures, scope, signed))
        check(activity in signed.definition.activities) { "food.activity.original_requested_activity_disabled" }
        return setOf(branchActivityResource(scope.branchId)) + branchActivityRecordResources(activeRecord) +
            branchActivityRecordResources(publicationRecord)
    }
}

private fun requireFoodActivity(activity: LocalSalesActivity) {
    require(activity == LocalSalesActivity.RESTAURANT || activity == LocalSalesActivity.CAFE)
}
