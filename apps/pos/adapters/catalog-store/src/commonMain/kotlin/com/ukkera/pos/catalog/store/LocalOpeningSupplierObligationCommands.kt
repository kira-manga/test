package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Save this exact operation and payload in the client journal before first submission. */
public object LocalOpeningSupplierObligationCommands {
    public fun record(operationId: OperationId, request: LocalRecordOpeningSupplierObligationV1): MutationRequest =
        MutationRequest(operationId, LocalOpeningSupplierObligationProfile.COMMAND, LocalOpeningSupplierObligationCodec.request(request))
}

/** The real registry installs these together; this adapter creates no storage or authority. */
public class LocalOpeningSupplierObligationServices(configuration: LocalConfigurationRepository) {
    public val commands: List<MasterCommandHandler> = listOf(OpeningSupplierObligationHandler(configuration))
    public val reads: List<MasterReadHandler> = listOf(OpeningSupplierObligationReadHandler(configuration))
    public val history: RuntimeHistoryBusinessProducer = LocalOpeningSupplierObligationHistoryProducer(configuration)
}

private class OpeningSupplierObligationHandler(private val configuration: LocalConfigurationRepository) : MasterCommandHandler {
    override val key: CommandKey = LocalOpeningSupplierObligationProfile.COMMAND
    override val writableKinds: Set<String> = setOf(LocalOpeningSupplierObligationProfile.OBLIGATION)
    override val claimNamespaces: Set<String> = setOf(LocalOpeningSupplierObligationProfile.DOCUMENT_CLAIM,
        LocalOpeningSupplierObligationProfile.SOURCE_CLAIM, LocalOpeningSupplierObligationProfile.SOURCE_IDENTITY_CLAIM,
        LocalOpeningSupplierObligationProfile.REFERENCE_CLAIM)
    override fun normalize(input: StoredBytes): StoredBytes = LocalOpeningSupplierObligationCodec.normalize(input)

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key && LocalOpeningSupplierObligationCodec.normalize(normalized) == normalized &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalOpeningSupplierObligationCodec.decodeRequest(normalized)
        val reader = OpeningSupplierObligationReader(configuration, view)
        val cut = reader.creationCut(request)
        val refusal = openingSupplierObligationRefusal(request, cut)
        val resources = openingSupplierObligationResources(context.scope, context.originalActorId, request) + reader.consumedResources
        return object : PreparedMutation() {
            override val requirement: AuthorizationRequirement = purchasingRequirement(context.scope, request.obligationId, Revision(0),
                context.operationId, context.semanticDigest, request.reasonCode, request.amount)
            override val allowsActionApproval: Boolean = false
            override val affectedResources: Set<RecoveryResourceKey> = resources
            override val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> = setOf(RecoveryResourceKind.SUPPLIER_SETTLEMENT,
                RecoveryResourceKind.PURCHASE_CUSTODY, RecoveryResourceKind.AUTHORIZATION)
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                    authorization.isBoundTo(requirement, context.originalActorId) && authorization.approvalId == null)
                if (refusal != null) return ProposedOutcome.Reject(refusal, openingSupplierObligationResult(refusal, request.obligationId))
                val supplier = checkNotNull(cut.supplier)
                val metadata = openingSupplierObligationDocumentaryMetadata(context.scope, request, context.operationId, context.originalActorId, recordedAt)
                val record = OpeningSupplierObligationRecordV1(context.scope, Revision(1), request, supplier, metadata, context.operationId,
                    context.originalActorId, recordedAt, authorization.policyRevision)
                return ProposedOutcome.Apply(openingSupplierObligationResult(LocalOpeningSupplierObligationProfile.RECORDED, request.obligationId),
                    listOf(AggregateChange(LocalOpeningSupplierObligationProfile.key(request.obligationId), null,
                        LocalOpeningSupplierObligationProfile.SCHEMA, openingSupplierObligationRecordBytes(record))),
                    openingSupplierObligationClaims(context.scope, request))
            }
        }
    }
}
