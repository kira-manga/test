package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** Read-only adapter over the genuine native BEFORE cut for shared setup effect verification.
 * It supplies neither a current saved original nor authentication, admission or mutable state. */
public class RuntimeHistoricalSetupReadView(private val command: RuntimeHistoricalCommand) : CommandReadView {
    override val scope: BranchScope get() = command.scope
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = command.aggregate(key)
    override suspend fun claim(key: ClaimKey): DomainId? = command.claim(key)
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
        (command.earlierOriginal(operationId) as? RetainedOriginalV2.Legacy)?.evidence

    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
        require(limit in 1..100)
        val keys = command.aggregates(kind).map { it.key }
            .filter { after == null || it.id.value > after.value }.sortedBy { it.id.value }.take(limit + 1)
        return AggregateKeyPage(keys.take(limit), if (keys.size > limit) keys[limit - 1].id else null)
    }

    override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage {
        require(limit in 1..100)
        val keys = command.claimKeys(namespace, prefix).filter { after == null || it.key > after }
            .sortedBy { it.key }.take(limit + 1)
        val entries = keys.take(limit).map { ClaimKeyEntry(it, checkNotNull(command.claim(it))) }
        return ClaimKeyPage(entries, if (keys.size > limit) entries.last().key.key else null)
    }
}
