package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoricalFoodCheckRejectedSources
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.sales.store.LocalFoodCheckHistoricalActivitySource
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.PreparedFoodActivityAdmission

/** Typed native BEFORE activity source for the closed FOOD scalar-refusal owner. Genuine
 * earlier activation originals/claims and signature trust are required; no historical view,
 * replayed activation, employee grant or accepting unavailable default is constructed. */
public class LocalCatalogFoodCheckRefusalSupport(private val signatures: CompanyPublicationSignatureVerifier) :
    LocalFoodCheckHistoricalActivitySource {
    override suspend fun before(command: RuntimeHistoricalCommand, activity: LocalSalesActivity): PreparedFoodActivityAdmission? {
        require(activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE))
        val scope = command.scope
        val resources = mutableSetOf<RecoveryResourceKey>(branchActivityResource(scope.branchId))
        try {
            val activeKey = branchActivityKey(LocalBranchActivityProfileV1.ACTIVE, scope.branchId)
            val active = command.aggregateSource(activeKey)
            val activeOwner = command.claim(branchActivityBranchClaim(scope))
            if (active == null) {
                check(activeOwner == null)
                return PreparedFoodActivityAdmission(false, resources)
            }
            val activeRecord = decodeBranchActivityRecord(scope, active.row)
            resources += branchActivityRecordResources(activeRecord)
            val reference = (activeRecord.body as BranchActivityBodyV1.Active).reference
            val publicationKey = branchActivityKey(LocalBranchActivityProfileV1.PUBLICATION, reference.publicationId)
            resources += branchActivityResource(reference.publicationId)
            val publication = command.aggregateSource(publicationKey) ?: error("food.check.activity_publication_missing")
            val publicationOwner = command.claim(branchActivityPublicationClaim(reference.publicationId))
            val publicationRecord = decodeBranchActivityRecord(scope, publication.row)
            resources += branchActivityRecordResources(publicationRecord)
            check(activeOwner == reference.publicationId && publicationOwner == reference.publicationId &&
                activeRecord.producer == publicationRecord.producer && activeRecord.recordedAt == publicationRecord.recordedAt &&
                activeRecord.permissionPolicyRevision == publicationRecord.permissionPolicyRevision)
            val original = (active.original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
            check(publication.original === active.original &&
                command.earlierOriginal(original.operationId) === active.original)
            requireBranchActivityStoredWrite(active.row, activeRecord, original)
            requireBranchActivityStoredWrite(publication.row, publicationRecord, original)
            requireBranchActivitySource(original.nativeOriginalProvenance == null)
            val signed = requireBranchActivityProducerShape(original)
            check(branchActivitySigned((publicationRecord.body as BranchActivityBodyV1.Publication).request) == signed &&
                branchActivityReference(signed) == reference)
            requireBranchActivitySource(authenticBranchActivity(signatures, scope, signed))
            return PreparedFoodActivityAdmission(activity in signed.definition.activities, resources)
        } catch (_: BranchActivitySourceUnavailable) {
            return null
        }
    }

    override suspend fun before(sources: RuntimeHistoricalFoodCheckRejectedSources,
        activity: LocalSalesActivity): PreparedFoodActivityAdmission? {
        require(activity in setOf(LocalSalesActivity.RESTAURANT, LocalSalesActivity.CAFE))
        val rejected = sources.evidence
        val scope = rejected.scope
        val resources = mutableSetOf<RecoveryResourceKey>(branchActivityResource(scope.branchId))
        try {
            val activeKey = branchActivityKey(LocalBranchActivityProfileV1.ACTIVE, scope.branchId)
            val active = sources.beforeAggregateSources.singleOrNull { it.row.key == activeKey } ?: return null
            val activeClaim = sources.beforeClaimSources.singleOrNull { it.key == branchActivityBranchClaim(scope) }
                ?: return null
            val original = (active.original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
            requireBranchActivitySource(original.nativeOriginalProvenance == null)
            check(original.committed && original.command == LocalBranchActivityProfileV1.ACTIVATE_INITIAL &&
                original.scope == scope && original.assignmentId == rejected.assignmentId &&
                original.authorityEpoch == rejected.authorityEpoch && original.sequence < rejected.sequence &&
                original.operationId != rejected.operationId)

            // The reference only selects a literal companion for verification; it does not
            // qualify either native source or grant activity before the whole producer pass.
            val activeRecord = decodeBranchActivityRecord(scope, active.row)
            resources += branchActivityRecordResources(activeRecord)
            val reference = (activeRecord.body as BranchActivityBodyV1.Active).reference
            val publicationKey = branchActivityKey(LocalBranchActivityProfileV1.PUBLICATION, reference.publicationId)
            val publication = sources.beforeAggregateSources.singleOrNull { it.row.key == publicationKey } ?: return null
            val publicationClaim = sources.beforeClaimSources.singleOrNull {
                it.key == branchActivityPublicationClaim(reference.publicationId)
            } ?: return null
            val publicationRecord = decodeBranchActivityRecord(scope, publication.row)
            resources += branchActivityRecordResources(publicationRecord)
            check(activeClaim.ownerId == reference.publicationId && publicationClaim.ownerId == reference.publicationId &&
                activeRecord.producer == publicationRecord.producer && activeRecord.recordedAt == publicationRecord.recordedAt &&
                activeRecord.permissionPolicyRevision == publicationRecord.permissionPolicyRevision)

            // Both rows and both claim acquisitions must be from this exact earlier native
            // activation event, not merely have favorable current values or equal owners.
            check(publication.original === active.original && activeClaim.original === active.original &&
                publicationClaim.original === active.original &&
                publication.writerHistorySequence == active.writerHistorySequence &&
                activeClaim.writerHistorySequence == active.writerHistorySequence &&
                publicationClaim.writerHistorySequence == active.writerHistorySequence)
            requireBranchActivityStoredWrite(active.row, activeRecord, original)
            requireBranchActivityStoredWrite(publication.row, publicationRecord, original)
            val signed = requireBranchActivityProducerShape(original)
            check(branchActivitySigned((publicationRecord.body as BranchActivityBodyV1.Publication).request) == signed &&
                branchActivityReference(signed) == reference)
            requireBranchActivitySource(authenticBranchActivity(signatures, scope, signed))
            return PreparedFoodActivityAdmission(activity in signed.definition.activities, resources)
        } catch (_: BranchActivitySourceUnavailable) {
            return null
        }
    }
}
