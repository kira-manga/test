package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

@Serializable
internal data class LocalRetailSourceReferenceV3(
    val allocationId: DomainId, val sourceDigest: String, val allocationDigest: String,
    val headerDigest: String, val slotIndex: Int,
)

/** Immutable logical revision zero / storage generation one. It is never consumed or recycled. */
@Serializable
internal data class LocalRetailJournalSlotV3(
    val schemaVersion: Int, val scope: LocalSalesScope, val actorId: DomainId,
    val intentSemanticDigest: String, val domain: LocalRetailDomainRequirementV3,
    val masterAllocationId: DomainId, val clientAllocationId: DomainId,
    val masterStorageProfile: String, val clientStorageProfile: String,
    val masterSource: LocalRetailSourceReferenceV3, val clientSource: LocalRetailSourceReferenceV3,
) {
    val target: LocalJournalTargetV3 get() = domain.target
    val id: DomainId get() = DomainId(target.recordingOperationId.value)
    fun requirement(): LocalPhysicalJournalRequirementV1 = LocalPhysicalJournalRequirementV1(scope.value(), actorId,
        target.intentOperationId, PayloadDigest(intentSemanticDigest), target.recordingOperationId, target.resolutionOperationId,
        target.attemptId, retailJournalProfileV3(target.kind), retailJournalCommandV3(target.kind), retailJournalTargetBindingV3(target),
        masterAllocationId, clientAllocationId, domain.masterOwner.deviceId, domain.clientOwner.deviceId,
        LocalPhysicalSpoolStorageProfileV1.valueOf(masterStorageProfile), LocalPhysicalSpoolStorageProfileV1.valueOf(clientStorageProfile),
        retailDomainBytesV3(domain))
}

/** Exact original normalized bytes, encoded as text without interpreting them as an older wire. */
@Serializable
internal data class LocalRetailJournalFactV3(
    val schemaVersion: Int, val scope: LocalSalesScope, val target: LocalJournalTargetV3,
    val phase: LocalJournalFactPhaseV3, val originalActorId: DomainId, val recordingActorId: DomainId,
    val normalizedBody: String, val bodyDigest: String, val semanticDigest: String,
    val slotDigest: String, val originalBodyDigest: String?, val recordedAt: UtcInstant,
) {
    fun body(): StoredBytes = StoredBytes(normalizedBody.encodeToByteArray(throwOnInvalidSequence = true))
}

@Serializable
public enum class LocalJournalApplicationStateV3 { PENDING_RECONCILIATION, APPLIED }

@Serializable
internal data class LocalRetailJournalApplicationV3(
    val schemaVersion: Int, val scope: LocalSalesScope, val target: LocalJournalTargetV3,
    val phase: LocalJournalFactPhaseV3, val actorId: DomainId, val revision: Revision,
    val state: LocalJournalApplicationStateV3, val reason: LocalJournalPendingReasonV3?,
    val updatedAt: UtcInstant, val lastApplicationOperationId: OperationId?,
    val appliedAction: LocalSalesAction?, val financialRevisions: List<LocalSalesRevision>,
    val financialProof: LocalRetailApplicationProofV3? = null,
)

internal data class LocalRetailJournalFactCutV3(
    val slot: LocalSalesRow<LocalRetailJournalSlotV3>, val fact: LocalSalesRow<LocalRetailJournalFactV3>,
    val application: LocalSalesRow<LocalRetailJournalApplicationV3>,
)

internal fun retailSlotWriteV3(value: LocalRetailJournalSlotV3): AggregateChange =
    AggregateChange(retailJournalSlotKeyV3(value.target.recordingOperationId), null, LocalFinancialJournalProfileV3.SLOT_SCHEMA,
        localSalesEncode(value, LocalRetailJournalSlotV3.serializer(), LocalFinancialJournalProfileV3.MAX_SLOT_BYTES))

internal fun retailFactWriteV3(value: LocalRetailJournalFactV3): AggregateChange = AggregateChange(
    retailJournalFactKeyV3(value.phase, value.target.factOperation(value.phase)), null,
    if (value.phase == LocalJournalFactPhaseV3.ORIGINAL) LocalFinancialJournalProfileV3.ORIGINAL_SCHEMA else LocalFinancialJournalProfileV3.RESOLUTION_SCHEMA,
    localSalesEncode(value, LocalRetailJournalFactV3.serializer(), LocalFinancialJournalProfileV3.MAX_FACT_BYTES))

internal fun retailApplicationWriteV3(value: LocalRetailJournalApplicationV3, before: StoredAggregate?): AggregateChange {
    check(value.revision == if (before == null) Revision(0) else before.domainRevision().next()) { "sales.journal.application_revision" }
    return AggregateChange(retailJournalApplicationKeyV3(value.target.factOperation(value.phase)), before?.revision,
        LocalFinancialJournalProfileV3.APPLICATION_SCHEMA,
        localSalesEncode(value, LocalRetailJournalApplicationV3.serializer(),
            if (value.state == LocalJournalApplicationStateV3.PENDING_RECONCILIATION)
                LocalFinancialJournalProfileV3.MAX_PENDING_APPLICATION_BYTES else LocalFinancialJournalProfileV3.MAX_APPLICATION_BYTES))
}

/** Original source generations are explicit; a companion's generation one is NOT the old row's revision. */
@Serializable
internal data class LocalRetailBeforeV3(
    val scope: LocalSalesScope, val capturingOperationId: OperationId, val role: String,
    val sourceKind: String, val sourceId: DomainId, val storageRevision: Revision,
    val sourceSchema: String, val sourceDigest: String, val sourceBody: String,
) {
    fun body(): StoredBytes = StoredBytes(sourceBody.encodeToByteArray(throwOnInvalidSequence = true))
}

/** Exact CURRENT source images, no nested prior application/status row and no manufactured predecessor. */
@Serializable
internal data class LocalRetailApplicationProofV3(
    val sources: List<LocalRetailBeforeV3>, val parentApplication: LocalRetailParentApplicationV3?,
)

/** Small exact reference, never a recursively embedded old application or a cross-authority clock. */
@Serializable
internal data class LocalRetailParentApplicationV3(val operationId: OperationId, val storageRevision: Revision, val digest: String)

internal fun retailParentApplicationV3(parent: LocalRetailJournalFactCutV3?): LocalRetailParentApplicationV3? = parent?.let {
    check(it.application.value.state == LocalJournalApplicationStateV3.APPLIED)
    LocalRetailParentApplicationV3(checkNotNull(it.application.value.lastApplicationOperationId), it.application.stored.revision,
        it.application.stored.digest.value)
}

internal fun retailBeforeImageV3(operationId: OperationId, scope: LocalSalesScope,
    role: String, row: StoredAggregate): LocalRetailBeforeV3 = LocalRetailBeforeV3(scope, operationId, role,
    row.key.kind, row.key.id, row.revision, row.schema, row.digest.value,
    row.payload.copyBytes().decodeToString(throwOnInvalidSequence = true))

internal fun retailApplicationProofV3(operationId: OperationId, scope: LocalSalesScope,
    rows: List<Pair<String, StoredAggregate>>, parentApplication: LocalRetailParentApplicationV3?): LocalRetailApplicationProofV3 {
    check(rows.map { it.first }.distinct().size == rows.size && rows.none { it.second.key.kind in
        LocalFinancialJournalProfileV3.gatedKinds + setOf(LocalFinancialJournalProfileV3.APPLICATION_KIND,
            LocalFinancialJournalProfileV3.BEFORE_KIND, LocalSalesStoreProfile.COLLECTION_BEFORE_KIND) })
    return LocalRetailApplicationProofV3(rows.map { (role, row) -> retailBeforeImageV3(operationId, scope, role, row) },
        parentApplication)
}

internal val retailRefundBeforeRolesV3: Set<String> = setOf("accepted", "cash", "entitlement", "claim", "sale", "work", "drawer", "custodian")
internal fun retailBeforeKeyV3(operationId: OperationId, role: String): AggregateKey {
    check(role in retailRefundBeforeRolesV3)
    val hex = sha256("ukkera.sales.retail-refund-before/v3\u0000${operationId.value}\u0000$role".encodeToByteArray()).value
    return AggregateKey(LocalFinancialJournalProfileV3.BEFORE_KIND,
        DomainId("${hex.substring(0, 8)}-${hex.substring(8, 12)}-5${hex.substring(13, 16)}-8${hex.substring(17, 20)}-${hex.substring(20, 32)}"))
}

internal fun retailRefundBeforeV3(operationId: OperationId, scope: LocalSalesScope,
    cut: LocalReturnCut, cash: LocalCashCut): List<AggregateChange> = retailRefundBeforeRowsV3(cut, cash).map { (role, row) ->
    val value = retailBeforeImageV3(operationId, scope, role, row)
    AggregateChange(retailBeforeKeyV3(operationId, role), null, "sales.journal.before/v3",
        localSalesEncode(value, LocalRetailBeforeV3.serializer(), LocalFinancialJournalProfileV3.MAX_BEFORE_BYTES))
}

internal fun retailRefundBeforeRowsV3(cut: LocalReturnCut, cash: LocalCashCut): List<Pair<String, StoredAggregate>> = listOf(
    "accepted" to cut.accepted.stored, "cash" to cash.session.stored,
    "entitlement" to cut.original.original.entitlement.stored, "claim" to cut.original.claim.stored,
    "sale" to cut.original.original.sale.stored, "work" to cut.work.stored,
    "drawer" to cash.drawer.stored, "custodian" to cash.custodian.stored,
)
