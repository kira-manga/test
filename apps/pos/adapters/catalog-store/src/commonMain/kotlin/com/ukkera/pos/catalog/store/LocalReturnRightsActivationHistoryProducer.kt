package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalAuthority
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Exact employee activation joins from genuine native BEFORE sources and their already
 * joined earlier originals. No handler re-preparation, historical CommandReadView, employee
 * session or native-original authority is reconstructed. Negative/unknown signature truth
 * stays unsupported; only positive signed sources and the closed deterministic refusals fit.
 */
internal class LocalReturnRightsActivationHistoryProducer(
    private val signatures: CompanyPublicationSignatureVerifier,
) : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> get() = setOf(LocalReturnRightsActivationProfileV1.ACTIVATE)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND) return false
        val retained = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (retained.nativeOriginalProvenance != null) return false
        try {
            requireReturnRightsStored(semanticDigest(command.scope, command.actorId, command.command,
                command.normalized) == command.semanticDigest)
            val request = returnRightsDecode(command.normalized, LocalActivateReturnRightsV1.serializer(),
                LocalReturnRightsActivationProfileV1.MAX_INPUT_BYTES)
            val signed = returnRightsSigned(request)
            requireReturnRightsSource(authenticReturnRights(signatures, command.scope, signed))
            val reference = returnRightsReference(signed)
            val reader = HistoricalReturnRightsReaderV1(command)
            val previous = reader.active()
            val publicationUsed = reader.publication(reference.publicationId) != null
            val versionUsed = reader.versionOwner(reference) != null
            requireReturnRightsSource(reader.sourcePolicies().all { authenticReturnRights(signatures, command.scope, it) })
            command.requireRequirement(returnRightsActivationRequirement(command.scope, request.expectedActiveRevision,
                command.operationId, command.semanticDigest))
            val resources = returnRightsCommandResources(command.scope, command.actorId, command.operationId, reference) + reader.resources
            val refusal = returnRightsActivationRefusal(request, reference, previous, publicationUsed, versionUsed)
            if (refusal != null) {
                if (retained.committed) return false
                command.requireReturnRightsActivationRejection(returnRightsRejected(reference, refusal), resources)
                return true
            }
            // The live writer holds overflow without recording a durable business result.
            if (!retained.committed || request.expectedActiveRevision.value == Long.MAX_VALUE) return false
            val originalSigned = requireReturnRightsProducerShape(retained)
            requireReturnRightsSource(authenticReturnRights(signatures, command.scope, originalSigned))
            command.requireLegacyEffects(ProposedOutcome.Apply(
                returnRightsSuccess(reference, returnRightsNextActive(request.expectedActiveRevision)),
                returnRightsChanges(command.scope, command.operationId, command.actorId, command.semanticDigest,
                    command.recordedAt, command.policyRevision, request, reference),
                returnRightsActivationClaims(command.scope, reference, previous == null)), resources)
            return true
        } catch (_: ReturnRightsSourceUnavailable) {
            return false
        } catch (_: ReturnRightsStorageUnavailable) {
            return false
        }
    }
}

/** Fixed native-selected rows only. Every present source must have its earlier joined writer. */
internal class HistoricalReturnRightsReaderV1(
    private val command: RuntimeHistoricalCommand,
) : ReturnRightsSourceReaderV1(command.scope) {
    override suspend fun select(key: AggregateKey): ReturnRightsSelectedRowV1? {
        val selected = command.aggregateSource(key)
        if (key.kind == LocalReturnRightsActivationProfileV1.ACTIVE) observedActiveRevision = selected?.row?.revision
        if (selected == null) return null
        val record = decodeReturnRightsRecord(scope, selected.row)
        val original = (selected.original as? RetainedOriginalV2.Legacy)?.evidence ?: throw ReturnRightsSourceUnavailable()
        return ReturnRightsSelectedRowV1(selected.row, record, original)
    }

    override suspend fun claim(key: ClaimKey): DomainId? = command.claim(key)

    override suspend fun requireEmptyProfileKinds() {
        requireReturnRightsStored(command.aggregates(LocalReturnRightsActivationProfileV1.ACTIVE).isEmpty() &&
            command.aggregates(LocalReturnRightsActivationProfileV1.PUBLICATION).isEmpty())
    }
}
