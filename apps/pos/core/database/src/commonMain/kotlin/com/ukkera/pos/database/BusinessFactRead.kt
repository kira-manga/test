package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** Read-only business DATA used by shared effect calculations. This has no current
 * principal, authentication, native action, writable loan or command preparation port.
 * Implementations must preserve their actual current/original cut and source ownership;
 * implementing this interface alone never qualifies a source or grants an action. */
public interface BusinessFactRead {
    public val scope: BranchScope
    public suspend fun aggregate(key: AggregateKey): StoredAggregate?
    public suspend fun claim(key: ClaimKey): DomainId?
    public suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence?
    public suspend fun aggregateKeys(kind: String, after: DomainId? = null, limit: Int = 50): AggregateKeyPage
    public suspend fun claimKeys(namespace: String, prefix: String, after: String? = null, limit: Int = 50): ClaimKeyPage
}

/** The real native owner's complete BEFORE cut for an effect calculation. Unlike a
 * CommandReadView this cannot be passed to a live handler or provide current/native ports.
 * Every present value is selected by the active core command's already joined ancestry. */
public class RuntimeHistoricalBusinessRead(private val command: RuntimeHistoricalCommand) : BusinessFactRead {
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
