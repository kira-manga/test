package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.types.Revision

/**
 * Complete creation/refusal producer over the genuine native BEFORE cut. It proves the
 * original supplier configuration and permanent documentary identities, not today's
 * grants, mutable supplier row, a source-free certificate or a replayed live handler.
 */
internal class LocalOpeningSupplierObligationHistoryProducer(private val configuration: LocalConfigurationRepository) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalOpeningSupplierObligationProfile.COMMAND)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.original !is RetainedOriginalV2.Legacy ||
            command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        val original = (command.original as RetainedOriginalV2.Legacy).evidence
        val request = openingSupplierObligationOriginalRequest(original)
        check(original.scope == command.scope && original.operationId == command.operationId && original.originalActorId == command.actorId &&
            original.command == command.command && original.semanticDigest == command.semanticDigest &&
            original.recordedAt == command.recordedAt && original.policyRevision == command.policyRevision)
        if (original.committed) requireOpeningSupplierObligationProducer(original)
        command.requireRequirement(purchasingRequirement(command.scope, request.obligationId, Revision(0), command.operationId,
            command.semanticDigest, request.reasonCode, request.amount))
        val reader = OpeningSupplierObligationReader(configuration, command)
        val cut = reader.creationCut(request)
        val resources = openingSupplierObligationResources(command.scope, command.actorId, request) + reader.consumedResources
        val refusal = openingSupplierObligationRefusal(request, cut)
        if (!original.committed) {
            check(refusal != null && refusal == original.code)
            command.requireOpeningSupplierObligationRejection(ProposedOutcome.Reject(refusal,
                openingSupplierObligationResult(refusal, request.obligationId)), resources)
        } else {
            check(refusal == null)
            val supplier = checkNotNull(cut.supplier)
            val metadata = openingSupplierObligationDocumentaryMetadata(command.scope, request, command.operationId, command.actorId, command.recordedAt)
            val record = OpeningSupplierObligationRecordV1(command.scope, Revision(1), request, supplier, metadata, command.operationId,
                command.actorId, command.recordedAt, command.policyRevision)
            command.requireLegacyEffects(ProposedOutcome.Apply(openingSupplierObligationResult(LocalOpeningSupplierObligationProfile.RECORDED, request.obligationId),
                listOf(AggregateChange(LocalOpeningSupplierObligationProfile.key(request.obligationId), null,
                    LocalOpeningSupplierObligationProfile.SCHEMA, openingSupplierObligationRecordBytes(record))),
                openingSupplierObligationClaims(command.scope, request)), resources)
        }
        return true
    }
}
