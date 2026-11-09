package com.ukkera.pos.sales.store

import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.RuntimeRetailCustomerReceiptEventClassifier
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Installed canonical classification of a core-selected row and its genuine original AFTER.
 * Returns a literal sale ID only. Core owns complete membership, original writer provenance
 * and native settlement; the source readers still close paid-sale identity and chronology. */
internal object LocalRetailCustomerReceiptEventClassifier : RuntimeRetailCustomerReceiptEventClassifier {
    override fun saleId(scope: BranchScope, row: StoredAggregate, original: RetainedCommandEvidence): DomainId {
        val event = retailCustomerReceiptEventMembershipValue(row.key, row.revision, row.schema,
            row.payload, row.digest, scope)
        requireLocalSalesSource(original.committed && original.nativeOriginalProvenance == null &&
            original.scope == scope && original.command == LocalRetailCustomerReceiptCodec.COMMAND &&
            original.operationId == event.operationId && original.originalActorId == event.actorId &&
            event.actorId == event.authorization.actorId && event.authorization.generation.value < Long.MAX_VALUE &&
            original.authenticationId == event.authorization.authenticationId &&
            original.policyRevision == event.authorization.policyRevision &&
            original.semanticDigest == event.semanticDigest && original.recordedAt == event.recordedAt &&
            original.jobs.isEmpty())
        requireLocalSalesSource(original.aggregateWrites.singleOrNull { it.key == row.key }?.let {
            it.storageGeneration == row.revision && it.schema == row.schema && it.payload == row.payload &&
                it.payloadDigest == row.digest
        } == true)

        val request = LocalRetailCustomerReceiptCodec.decode(original.normalized)
        requireLocalSalesSource(LocalRetailCustomerReceiptCodec.encode(request) == original.normalized &&
            semanticDigest(scope, original.originalActorId, original.command, original.normalized) == original.semanticDigest &&
            request.saleId == event.after.source.saleId && request.observation == event.observation &&
            request.portions == event.portions && request.receivedAt == event.receivedAt &&
            request.receiptTimeZoneId == event.receiptTimeZoneId &&
            request.expectedHeadRevision.value in 0L until LocalRetailCustomerReceiptProfileV1.MAX_EVENTS.toLong() &&
            request.expectedHeadRevision == (event.beforeHeadGeneration ?: Revision(0)) &&
            event.after.headRevision == request.expectedHeadRevision.next() &&
            event.after.schema == LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA &&
            event.after.source.schema == LocalRetailCustomerReceiptProfileV1.SOURCE_SCHEMA)
        requireLocalSalesSource(original.result == LocalRetailCustomerReceiptCodec.result(event.result) &&
            sha256(original.result.copyBytes()) == original.resultHash &&
            event.result.saleId == request.saleId && event.result.eventOperationId == event.operationId &&
            event.result.headRevision == event.after.headRevision && event.result.receivedAt == event.receivedAt &&
            event.result.receiptTimeZoneId == event.receiptTimeZoneId && event.result.recordedAt == event.recordedAt)
        return request.saleId
    }
}
