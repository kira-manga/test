package com.ukkera.pos.sales.store

import com.ukkera.pos.database.RetainedOriginalV2
import com.ukkera.pos.database.RuntimeHistoricalCommand
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Inert exact BEFORE image and complete immutable events/resources, never a read/write view. */
internal class LocalRetailCustomerReceiptHistoryHeadCut(
    val head: LocalRetailCustomerReceiptHeadImage,
    val lastEventDigest: PayloadDigest,
    events: List<LocalSalesRow<LocalRetailCustomerReceiptEventV1>>,
    recoveryResources: Set<RecoveryResourceKey>,
) {
    val events: List<LocalSalesRow<LocalRetailCustomerReceiptEventV1>> = events.toList()
    val recoveryResources: Set<RecoveryResourceKey> = recoveryResources.toSet()
}

/**
 * Complete native BEFORE membership and literal event/predecessor sources. Every present
 * row's writer has already completed its entire business/native join in this same scan.
 * Missing, orphan, unsupported or inconsistent evidence throws/holds; it cannot become a
 * revision-zero source. No current SQL rows, supplied retained reader or authority is used.
 */
internal class LocalRetailCustomerReceiptHistorySources(private val command: RuntimeHistoricalCommand) {
    private class EventSource(
        val row: LocalSalesRow<LocalRetailCustomerReceiptEventV1>,
        val original: RetainedOriginalV2.Legacy,
    )

    suspend fun findHead(original: LocalRetailCustomerReceiptOriginalSource): LocalRetailCustomerReceiptHistoryHeadCut? {
        val saleId = original.coordinates.saleId
        check(original.coordinates.scope.value() == command.scope)
        val key = retailCustomerReceiptHeadKey(saleId)
        val source = command.aggregateSource(key)
        val owner = command.claim(retailCustomerReceiptHeadClaim(saleId))
        val indexed = membership(saleId)
        val retainedEvents = eventMembership(saleId)
        if (source == null) {
            // Complete absence of this producer's observations, not physical non-delivery.
            check(owner == null && indexed.isEmpty() && retainedEvents.isEmpty())
            return null
        }
        check(source.row.key == key && owner == saleId)
        val head = retailCustomerReceiptHeadImage(source.row.revision, source.row.schema, source.row.payload,
            source.row.digest, command.scope, original)
        check(indexed == head.value.eventOperationIds.toSet() && retainedEvents == indexed)

        val events = mutableListOf<EventSource>()
        for ((ordinal, operationId) in head.value.eventOperationIds.withIndex()) {
            val event = readEvent(operationId, original)
            val value = event.row.value
            val previous = events.lastOrNull()
            check(value.after.headRevision == Revision(ordinal.toLong() + 1) &&
                value.after.eventOperationIds == head.value.eventOperationIds.take(ordinal + 1) &&
                value.beforeHeadOperationId == previous?.row?.value?.operationId &&
                value.beforeEventDigest == previous?.row?.stored?.digest)
            val witness = command.aggregateSource(retailCustomerReceiptBeforeKey(operationId))
            val before = if (previous == null) {
                check(witness == null && value.beforeHeadGeneration == null && value.beforeHeadDigest == null)
                null
            } else {
                val beforeSource = witness ?: error("sales.customer_receipt.history_before_head_missing")
                val priorWrite = previous.original.evidence.aggregateWrites.singleOrNull { it.key == key }
                    ?: error("sales.customer_receipt.history_before_head_original_missing")
                check(beforeSource.original === event.original && beforeSource.row.key == retailCustomerReceiptBeforeKey(operationId) &&
                    beforeSource.row.revision == Revision(1) && beforeSource.row.schema == LocalRetailCustomerReceiptProfileV1.HEAD_BEFORE_SCHEMA &&
                    beforeSource.row.payload == priorWrite.payload && beforeSource.row.digest == priorWrite.payloadDigest &&
                    value.beforeHeadGeneration == priorWrite.storageGeneration && value.beforeHeadDigest == priorWrite.payloadDigest)
                retailCustomerReceiptHeadImage(priorWrite.storageGeneration, priorWrite.schema, priorWrite.payload,
                    priorWrite.payloadDigest, command.scope, original).also { check(it.value == previous.row.value.after) }
            }
            // Whole prior authority was joined by core. This separately compares literal
            // pure-producer bytes against that exact earlier original and BEFORE witness.
            requireRetailCustomerReceiptEventProducer(event.original.evidence, event.row, original, before, previous?.row?.stored?.digest)
            events += event
        }
        val latest = events.lastOrNull() ?: error("sales.customer_receipt.history_latest_event_missing")
        val latestHead = latest.original.evidence.aggregateWrites.singleOrNull { it.key == key }
            ?: error("sales.customer_receipt.history_head_original_missing")
        check(source.original === latest.original && latest.row.value.after == head.value &&
            latestHead.storageGeneration == head.generation && latestHead.schema == head.schema &&
            latestHead.payload == head.payload && latestHead.payloadDigest == head.digest)
        val resources = original.recoveryResources + events.flatMap {
            retailCustomerReceiptResources(saleId, it.row.value.actorId, it.row.value.operationId)
        }
        return LocalRetailCustomerReceiptHistoryHeadCut(head, latest.row.stored.digest,
            events.map { it.row }, resources)
    }

    suspend fun requireNewEvent(saleId: DomainId) {
        check(command.aggregate(retailCustomerReceiptEventKey(command.operationId)) == null &&
            command.aggregate(retailCustomerReceiptBeforeKey(command.operationId)) == null &&
            command.claim(retailCustomerReceiptEventClaim(command.operationId)) == null &&
            command.claim(retailCustomerReceiptMembershipClaim(saleId, command.operationId)) == null)
    }

    private suspend fun membership(saleId: DomainId): Set<OperationId> {
        val prefix = retailCustomerReceiptMembershipPrefix(saleId)
        val operations = linkedSetOf<OperationId>()
        var previousKey: String? = null
        command.forEachClaimKey(LocalRetailCustomerReceiptProfileV1.NAMESPACE, prefix) { key ->
            check(operations.size < LocalRetailCustomerReceiptProfileV1.MAX_EVENTS &&
                (previousKey == null || checkNotNull(previousKey) < key.key))
            check(key.namespace == LocalRetailCustomerReceiptProfileV1.NAMESPACE && key.key.startsWith(prefix) &&
                command.claim(key) == saleId)
            val operationId = validateLocalSalesSource { OperationId(key.key.removePrefix(prefix)) }
            check(key == retailCustomerReceiptMembershipClaim(saleId, operationId))
            check(operations.add(operationId))
            previousKey = key.key
        }
        return operations.toSet()
    }

    /** Complete actual EVENT kind census also refuses a row surviving without membership. */
    private suspend fun eventMembership(saleId: DomainId): Set<OperationId> {
        val operations = mutableSetOf<OperationId>()
        command.forEachAggregate(LocalRetailCustomerReceiptProfileV1.EVENT) { row ->
            val event = retailCustomerReceiptEventMembershipValue(row.key, row.revision, row.schema,
                row.payload, row.digest, command.scope)
            if (event.after.source.saleId == saleId) {
                check(operations.size < LocalRetailCustomerReceiptProfileV1.MAX_EVENTS)
                check(operations.add(event.operationId))
            }
        }
        return operations
    }

    private suspend fun readEvent(operationId: OperationId,
        original: LocalRetailCustomerReceiptOriginalSource): EventSource {
        val key = retailCustomerReceiptEventKey(operationId)
        val source = command.aggregateSource(key) ?: error("sales.customer_receipt.history_event_missing")
        val producer = source.original as? RetainedOriginalV2.Legacy
            ?: error("sales.customer_receipt.history_event_profile_unsupported")
        check(producer.evidence.committed && producer.evidence.nativeOriginalProvenance == null &&
            producer.evidence.command == LocalRetailCustomerReceiptCodec.COMMAND &&
            producer.evidence.operationId == operationId && command.earlierOriginal(operationId) === producer &&
            source.row.key == key && source.row.revision == Revision(1) &&
            command.claim(retailCustomerReceiptEventClaim(operationId)) == original.coordinates.saleId &&
            command.claim(retailCustomerReceiptMembershipClaim(original.coordinates.saleId, operationId)) == original.coordinates.saleId)
        val value = retailCustomerReceiptEventValue(operationId, source.row.schema, source.row.payload, source.row.digest, original)
        return EventSource(LocalSalesRow(source.row, value), producer)
    }
}
