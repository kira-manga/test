package com.ukkera.pos.database

import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.NativeHistoryBusinessPublicationObservationProof
import com.ukkera.pos.security.NativeHistoryOriginalResourceV2
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.UtcInstant

/** One actual unmutated native writer's DATA traversal. A failed read irreversibly
 * denies reuse, including when its caller catches the exception. It grants no authority. */
internal class RuntimeLegacySourceLoan(private val guard: suspend () -> Unit) {
    private var open = true
    private var failed = false
    suspend fun <T> read(use: suspend () -> T): T = try {
        check(open && !failed); guard(); check(open && !failed)
        val result = use()
        check(open && !failed); guard(); check(open && !failed)
        result
    } catch (failure: Throwable) { failed = true; throw failure }
    fun close() { open = false }
}

/** Complete authenticated native tail shared only within this callback on the SAME
 * writer loan. This avoids retaining a new copy of the entire tail at each ancestor.
 * Every selected command still needs its own whole typed business qualification. */
public class RuntimeLegacyBusinessSourceGraph internal constructor(
    public val root: RetainedOriginalV2.Legacy,
    private val loan: RuntimeLegacySourceLoan,
    private val select: suspend (OperationId) -> RuntimeHistoricalLegacySources?,
) {
    public suspend fun source(operationId: OperationId): RuntimeHistoricalLegacySources? = loan.read { select(operationId) }
    override fun toString(): String = "RuntimeLegacyBusinessSourceGraph([REDACTED])"
}

/** Exact current literal and native-selected ordinary writer on one actual unmutated
 * loan. UNQUALIFIED DATA only: whole writer/source proof and current disclosure authority
 * remain separate requirements. Neither a current view nor a grant can be minted here. */
public class RuntimeCurrentLegacyBusinessSources internal constructor(
    public val row: StoredAggregate,
    public val graph: RuntimeLegacyBusinessSourceGraph,
)

/** Core-selected original business DATA. Every query uses the authenticated native
 * BEFORE cut and pairs present literals with strictly earlier immutable writers.
 * These pairings are UNQUALIFIED until the installed business program closes each
 * ancestor and compares the entire proposal. This is not a CommandReadView, current
 * session, employee proof or command context. Queries refuse after callback return. */
public class RuntimeHistoricalLegacySources internal constructor(
    public val original: RetainedOriginalV2.Legacy,
    requirement: AuthorizationRequirement,
    resources: List<NativeHistoryOriginalResourceV2>,
    public val mustCommitBefore: UtcInstant?,
    /** Exact ordinary captured business generation; this is inert audit provenance. */
    public val authorizationGeneration: com.ukkera.pos.security.SessionGeneration,
    private val publication: NativeHistoryBusinessPublicationObservationProof?,
    private val loan: RuntimeLegacySourceLoan,
    private val aggregateBefore: suspend (AggregateKey) -> RuntimeNativeLiteralAggregateSource?,
    private val claimBefore: suspend (ClaimKey) -> RuntimeNativeLiteralClaimSource?,
    private val originalBefore: suspend (OperationId) -> RetainedOriginalV2?,
    private val aggregatePage: suspend (String, DomainId?, Int) -> AggregateKeyPage,
    private val claimPage: suspend (String, String, String?, Int) -> ClaimKeyPage,
) {
    public val evidence: RetainedCommandEvidence get() = original.evidence
    public val requirement: AuthorizationRequirement = requirement.copy()
    private val resourceValues = resources.map { it.copy() }
    public val resources: List<NativeHistoryOriginalResourceV2> get() = resourceValues.toList()

    init {
        require(evidence.command in commands && evidence.committed && evidence.code == "committed" &&
            evidence.nativeOriginalProvenance == null && requirement.scope == evidence.scope &&
            requirement.binding?.operationId == evidence.operationId && requirement.binding?.payloadDigest == evidence.semanticDigest)
        require(resourceValues.isNotEmpty() && resourceValues.size <= com.ukkera.pos.security.SelfOwnedNativeHistoryWireV2.MAX_RESOURCES &&
            resourceValues.all { it.selectorTag == 3 && it.preserveHold && it.originalEvidenceDigest == evidence.semanticDigest } &&
            resourceValues.map { it.kind to it.resourceId }.distinct().size == resourceValues.size)
    }

    public suspend fun aggregateSource(key: AggregateKey): RuntimeNativeLiteralAggregateSource? = loan.read { aggregateBefore(key) }
    public suspend fun claimSource(key: ClaimKey): RuntimeNativeLiteralClaimSource? = loan.read { claimBefore(key) }
    public suspend fun earlierOriginal(operationId: OperationId): RetainedOriginalV2? = loan.read { originalBefore(operationId) }
    public suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage = loan.read { aggregatePage(kind, after, limit) }
    public suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage =
        loan.read { claimPage(namespace, prefix, after, limit) }
    /** Original signed membership only. Contents still require actual native-authenticated
     * immutable outputs; this proof cannot be promoted to a current completion snapshot. */
    public suspend fun publicationObservation(): NativeHistoryBusinessPublicationObservationProof? = loan.read { publication }
    override fun toString(): String = "RuntimeHistoricalLegacySources([REDACTED])"

    internal companion object {
        // Finite source selection, never a business pass or permission by key name.
        val commands: Set<CommandKey> = setOf(
            CommandKey("sales.order.write", 1),
            CommandKey("sales.collection.write", 1), CommandKey("sales.collection.write", 2),
            CommandKey("sales.return.write", 1), CommandKey("sales.return.write", 2),
            CommandKey("sales.exchange.write", 1), CommandKey("sales.return.case.write", 1),
            CommandKey("stock.opening.post", 1), CommandKey("purchasing.receipt.post", 1),
            CommandKey("local.configuration.write", 1), CommandKey("local.catalog.pricing", 1),
            CommandKey("local.return-rights.activate", 1), CommandKey("sales.customer-receipt.write", 1),
        )

        // Current legacy liability literals can have later V3 refund writers. These
        // are DATA roots only, not legacy BEFORE-program selections or qualification.
        // The installed consumer must close the exact whole financial producer.
        val currentWriterCommands: Set<CommandKey> = commands + setOf(
            CommandKey("sales.return.write", 3), CommandKey("sales.financial-supervisor", 1),
        )
    }
}
