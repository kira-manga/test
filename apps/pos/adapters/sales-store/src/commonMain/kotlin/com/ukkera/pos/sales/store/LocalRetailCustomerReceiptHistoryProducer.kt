package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalAuthority
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.database.RuntimeHistoryBusinessProducer
import com.ukkera.pos.database.RuntimeRetailCustomerReceiptEventClassifier
import com.ukkera.pos.database.semanticDigest

/**
 * Exact counter-observation history, using the same pure producer as live writes.
 * Core supplies the actual native BEFORE cut and already fully joined earlier originals;
 * it separately closes employee authority, every literal effect and affected resource.
 * Only source-joinable deterministic refusals enter the dedicated closed rejection lane;
 * unavailable/unsupported originals, native-original and streamed profiles remain held. This creates
 * no current session, physical delivery proof, return qualification or replacement operation.
 */
public class LocalRetailCustomerReceiptHistoryProducer : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalRetailCustomerReceiptCodec.COMMAND)
    override val retailCustomerReceiptEventClassifier: RuntimeRetailCustomerReceiptEventClassifier =
        LocalRetailCustomerReceiptEventClassifier

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        if (command.command !in commands || command.authority != RuntimeHistoricalAuthority.EMPLOYEE_COMMAND ||
            command.supervisorReview != null) return false
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (original.nativeOriginalProvenance != null || original.jobs.isNotEmpty()) return false
        val result = LocalRetailCustomerReceiptCodec.decodeResult(original.result)
        when (result) {
            is LocalRetailCustomerReceiptResultV1.Applied -> if (!original.committed) return false
            is LocalRetailCustomerReceiptResultV1.Refused -> {
                if (original.committed || original.code != "sales.customer_receipt.refused" ||
                    result.reason == LocalRetailCustomerReceiptRefusalV1.ORIGINAL_UNAVAILABLE ||
                    result.reason == LocalRetailCustomerReceiptRefusalV1.ORIGINAL_UNSUPPORTED ||
                    original.aggregateWrites.isNotEmpty() || original.claimChanges.isNotEmpty()) return false
            }
        }
        check(original.scope == command.scope && original.operationId == command.operationId && original.command == command.command &&
            original.originalActorId == command.actorId && original.authenticationId == command.authenticationId &&
            original.policyRevision == command.policyRevision && original.semanticDigest == command.semanticDigest &&
            original.recordedAt == command.recordedAt && original.normalized == command.normalized &&
            LocalRetailCustomerReceiptCodec.normalize(command.normalized) == command.normalized &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)

        val request = LocalRetailCustomerReceiptCodec.decode(command.normalized)
        val sale = readRetailCustomerReceiptHistoryOriginal(command, request.saleId) ?: return false
        val sources = LocalRetailCustomerReceiptHistorySources(command)
        val before = sources.findHead(sale)
        sources.requireNewEvent(request.saleId)
        val identity = LocalRetailCustomerReceiptProductionIdentity(command.scope, command.actorId,
            command.operationId, command.semanticDigest)
        val production = retailCustomerReceiptProduction(identity, request, sale, before?.head, before?.lastEventDigest)
        // These literal fields come from the actual native certificate, not an event DTO.
        val authorization = LocalRetailCustomerReceiptAuthorizationV1(command.actorId, command.authenticationId,
            command.authorizationGeneration, command.policyRevision)
        val expected = production.propose(command.recordedAt, authorization)
        command.requireRequirement(production.requirement)
        when (result) {
            is LocalRetailCustomerReceiptResultV1.Applied -> {
                if (expected !is ProposedOutcome.Apply) return false
                command.requireLegacyEffects(expected, production.affectedResources)
            }
            is LocalRetailCustomerReceiptResultV1.Refused -> {
                if (expected !is ProposedOutcome.Reject) return false
                // The actual native capture, original employee proof, five nonbusiness
                // mutations and complete literal result/resources close independently.
                command.requireRetailCustomerReceiptRejection(expected, production.affectedResources)
            }
        }
        return true
    }
}
