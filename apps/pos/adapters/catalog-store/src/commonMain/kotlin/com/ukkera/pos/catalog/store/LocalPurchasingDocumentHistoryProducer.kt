package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision

/**
 * Compares the exact documentary producer to genuine native BEFORE records and their
 * already joined originals. It never prepares a handler, constructs a historical
 * CommandReadView, recreates employee authority, repairs content or substitutes a manifest.
 * Unavailable/corrupt/unsupported original dependencies remain unresolved native history.
 */
internal class LocalPurchasingDocumentHistoryProducer : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = LocalPurchasingDocumentProfileV1.commands

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            command.original !is RetainedOriginalV2.Legacy) return false
        val retained = (command.original as RetainedOriginalV2.Legacy).evidence
        check(purchasingDocumentNormalize(command.command, command.normalized) == command.normalized &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        if (retained.committed) {
            requirePurchasingDocumentProducer(retained)
            command.requireLegacyReadEvidence()
        }
        val reader = HistoricalPurchasingDocumentReader(command)
        val producer = PurchasingDocumentProducer(command.operationId, command.actorId, command.command, command.semanticDigest)
        val expected: ProposedOutcome
        when (command.command) {
            LocalPurchasingDocumentProfileV1.BEGIN -> {
                val request = purchasingDocumentDecode(command.normalized, LocalBeginPurchasingDocumentV1.serializer())
                val sources = purchasingDocumentBeginSources(reader, request)
                val action = if (request.parentOriginalId == null) SecurityAction.PURCHASING_DOCUMENT_CAPTURE else SecurityAction.PURCHASING_DOCUMENT_ATTACH
                command.requireRequirement(purchasingDocumentRequirement(command.scope, request.documentId, Revision(0),
                    command.operationId, command.semanticDigest, command.command, action))
                expected = purchasingDocumentBeginOutcome(command.scope, request, sources, producer, command.recordedAt, command.policyRevision)
            }
            LocalPurchasingDocumentProfileV1.CHUNK -> {
                val request = purchasingDocumentDecode(command.normalized, LocalWritePurchasingDocumentChunkV1.serializer())
                val sources = purchasingDocumentChunkSources(reader, request)
                command.requireRequirement(purchasingDocumentRequirement(command.scope, request.documentId,
                    sources.intake?.row?.revision ?: Revision(0), command.operationId, command.semanticDigest, command.command,
                    purchasingDocumentAction(sources.intake)))
                expected = purchasingDocumentChunkOutcome(command.scope, request, sources, producer, command.recordedAt, command.policyRevision)
            }
            LocalPurchasingDocumentProfileV1.COMPLETE -> {
                val request = purchasingDocumentDecode(command.normalized, LocalCompletePurchasingDocumentV1.serializer())
                val sources = purchasingDocumentCompleteSources(reader, request)
                command.requireRequirement(purchasingDocumentRequirement(command.scope, request.documentId,
                    sources.intake?.row?.revision ?: Revision(0), command.operationId, command.semanticDigest, command.command,
                    purchasingDocumentAction(sources.intake)))
                expected = purchasingDocumentCompleteOutcome(command.scope, request, sources, producer, command.recordedAt, command.policyRevision)
            }
            else -> return false
        }
        val resources = reader.resources + purchasingDocumentAuthResource(command.actorId)
        when (expected) {
            is ProposedOutcome.Apply -> {
                if (!retained.committed) return false
                command.requireLegacyEffects(expected, resources)
            }
            is ProposedOutcome.Reject -> {
                if (retained.committed) return false
                command.requirePurchasingDocumentRejection(expected, resources)
            }
        }
        return true
    }
}
