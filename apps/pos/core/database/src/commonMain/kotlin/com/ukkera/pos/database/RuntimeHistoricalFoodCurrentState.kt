package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision

/** Typed adapter's literal decoded FOOD envelope identity, never an authority assertion.
 * Core compares every field to the actual native current row and its already joined writer. */
public data class RuntimeHistoricalFoodCurrentWriter(
    public val key: AggregateKey,
    public val revision: Revision,
    public val operationId: OperationId,
) {
    override fun toString(): String = "RuntimeHistoricalFoodCurrentWriter([REDACTED])"
}

/** Literal task identity decoded by the SAME FOOD owner. Core requires its actual retained
 * identity claim and all three current rows; the adapter closes the typed task relationships. */
public data class RuntimeHistoricalFoodCurrentTaskIdentity(
    public val taskId: DomainId,
    public val orderId: DomainId,
    public val portionId: DomainId,
    public val stationId: DomainId,
) {
    override fun toString(): String = "RuntimeHistoricalFoodCurrentTaskIdentity([REDACTED])"
}

/** Literal unissued portion/collection link. It cannot create or release a reservation. */
public data class RuntimeHistoricalFoodCurrentReservationIdentity(
    public val portionId: DomainId,
    public val reservationId: DomainId,
    public val collectionId: DomainId,
) {
    override fun toString(): String = "RuntimeHistoricalFoodCurrentReservationIdentity([REDACTED])"
}

/** Literal whole-unsent cancellation link, checked against the actual retained claim. */
public data class RuntimeHistoricalFoodCurrentCancellationIdentity(
    public val orderId: DomainId,
    public val collectionId: DomainId,
    public val refundId: DomainId,
    public val resolutionId: DomainId,
) {
    override fun toString(): String = "RuntimeHistoricalFoodCurrentCancellationIdentity([REDACTED])"
}

/** Decoded attempt identity only. Core compares the real native once-only claims; this
 * neither asserts that money moved nor supplies custody, an amount or a payment outcome. */
public data class RuntimeHistoricalFoodCurrentTenderIdentity(
    public val document: AggregateKey,
    public val attemptId: DomainId,
    public val originalCashSessionId: DomainId,
    public val recorded: Boolean,
    public val cash: Boolean,
) {
    override fun toString(): String = "RuntimeHistoricalFoodCurrentTenderIdentity([REDACTED])"
}

/** Core-created, bounded current FOOD traversal on the SAME fully traversed native read.
 * Each source includes the complete literal native AFTER row and its already joined original
 * writer. No current SQL, employee principal, command replay, arbitrary producer or historical
 * CommandReadView can supply this object. Streamed originals remain inert outside their
 * original command traversal; this adapter does not reopen their reader capabilities.
 *
 * The installed adapter must decode all FOOD rows and close their typed relationships, then
 * submit every decoded writer. Core independently requires exact complete row membership,
 * the actual last writer and every retained current FOOD identity claim. Unsupported incoming
 * handoff claims are explicitly held. Immutable quotation sidecars are documents in that same
 * census, with their own native writer and identity claim. Dropping immutable data is not
 * claimed to erase it. */
public class RuntimeHistoricalFoodCurrentState internal constructor(
    private val owner: RuntimeBusinessHistoryJoin,
    public val scope: BranchScope,
    sources: List<RuntimeHistoricalAggregateSource>,
    writers: Map<AggregateKey, OperationId>,
    claimOwners: Map<ClaimKey, DomainId>,
) {
    private var sources = sources.toList()
    private var writers = writers.toMap()
    private var claimOwners = claimOwners.toMap()
    private val issues = linkedMapOf<DomainId, RuntimeHistoricalFoodCurrentIssueSources>()
    private val reservations = linkedMapOf<DomainId, RuntimeHistoricalFoodCurrentReservationSources>()
    private var closed = false

    /** Complete fixed FOOD kind membership, including incompatible legacy/event rows.
     * The adapter must refuse unsupported rows; it cannot select a favorable prefix. */
    public suspend fun sources(): List<RuntimeHistoricalAggregateSource> {
        owner.requireCurrentFoodState(this)
        return sources.toList()
    }

    public suspend fun hasIncomingHandoffClaims(): Boolean {
        owner.requireCurrentFoodState(this)
        return claimOwners.keys.any { it.namespace == HANDOFF_CLAIM }
    }

    /** A single known current portion's linked stock facts, from the already closed native
     * lineages/indexes and original producer joins. Both member ranges are complete. This
     * creates no stock read view and never reopens an original's command reader capability. */
    public suspend fun issueSources(portionId: DomainId, reservationId: DomainId,
        issueId: DomainId): RuntimeHistoricalFoodCurrentIssueSources {
        owner.requireCurrentFoodState(this)
        historyShapeV2(!closed && portionId !in issues && portionId !in reservations &&
            sources.any { it.row.key == AggregateKey(PORTION, portionId) } &&
            issues.values.none { it.reservationId == reservationId || it.issueId == issueId } &&
            reservations.values.none { it.reservationId == reservationId || it.unusedIssueId == issueId })
        val value = owner.currentFoodIssueSources(this, portionId, reservationId, issueId)
        issues[portionId] = value
        return value
    }

    /** SAME current native reservation and complete allocation/hold range for an unissued
     * portion. Core also checks its deterministic issue and permanent issue claim are absent;
     * a missing issue is never inferred from the adapter's preparation label alone. */
    public suspend fun reservationSources(portionId: DomainId, reservationId: DomainId,
        unusedIssueId: DomainId): RuntimeHistoricalFoodCurrentReservationSources {
        owner.requireCurrentFoodState(this)
        historyShapeV2(!closed && portionId !in issues && portionId !in reservations &&
            sources.any { it.row.key == AggregateKey(PORTION, portionId) } &&
            issues.values.none { it.reservationId == reservationId || it.issueId == unusedIssueId } &&
            reservations.values.none { it.reservationId == reservationId || it.unusedIssueId == unusedIssueId })
        val value = owner.currentFoodReservationSources(this, portionId, reservationId, unusedIssueId)
        reservations[portionId] = value
        return value
    }

    /** Complete literal native current rows, actual writer operations and identity owners.
     * A matching count, selected row list or callback Boolean cannot replace this comparison. */
    public suspend fun requireExactWriters(expected: List<RuntimeHistoricalFoodCurrentWriter>,
        tasks: List<RuntimeHistoricalFoodCurrentTaskIdentity> = emptyList(),
        unissued: List<RuntimeHistoricalFoodCurrentReservationIdentity> = emptyList(),
        cancellations: List<RuntimeHistoricalFoodCurrentCancellationIdentity> = emptyList(),
        tenders: List<RuntimeHistoricalFoodCurrentTenderIdentity> = emptyList()) {
        owner.requireCurrentFoodState(this)
        historyCapacityV2(tasks.size <= RuntimeBusinessHistoryJoin.MAX_VERSIONS &&
            unissued.size <= RuntimeBusinessHistoryJoin.MAX_VERSIONS &&
            cancellations.size <= RuntimeBusinessHistoryJoin.MAX_VERSIONS &&
            tenders.size <= RuntimeBusinessHistoryJoin.MAX_VERSIONS)
        historyShapeV2(!closed && expected.size == sources.size && expected.map { it.key }.distinct().size == expected.size &&
            writers.keys == sources.map { it.row.key }.toSet() && claimOwners.keys.none { it.namespace == HANDOFF_CLAIM } &&
            tasks.map { it.taskId }.distinct().size == tasks.size && issues.keys == tasks.map { it.portionId }.toSet() &&
            unissued.map { it.portionId }.distinct().size == unissued.size && reservations.keys == unissued.map { it.portionId }.toSet() &&
            cancellations.map { it.refundId }.distinct().size == cancellations.size &&
            cancellations.map { it.resolutionId }.distinct().size == cancellations.size &&
            tenders.map { it.document.kind to it.attemptId }.distinct().size == tenders.size)
        val byKey = sources.associateBy { it.row.key }
        val expectedClaims = linkedMapOf<ClaimKey, DomainId>()
        for (value in expected) {
            val row = byKey[value.key]?.row ?: malformed()
            historyShapeV2(row.key.kind in KINDS && row.key == value.key && row.revision == value.revision &&
                writers[value.key] == value.operationId)
            val namespace = when (value.key.kind) {
                in DOCUMENT_KINDS -> IDENTITY_CLAIM
                in FINANCIAL_DOCUMENT_KINDS -> FINANCIAL_CLAIM
                else -> null // Immutable frame bytes have no separate document identity claim.
            }
            if (namespace != null) {
                val key = ClaimKey(namespace, "${value.key.kind}/${value.key.id.value}")
                historyShapeV2(expectedClaims.put(key, value.key.id) == null)
            }
        }
        for (task in tasks) {
            historyShapeV2(AggregateKey(ORDER, task.orderId) in byKey && AggregateKey(PORTION, task.portionId) in byKey &&
                AggregateKey(STATION_INDEX, task.stationId) in byKey &&
                expectedClaims.put(ClaimKey(IDENTITY_CLAIM, "task/${task.taskId.value}"), task.orderId) == null)
        }
        for (link in unissued) {
            historyShapeV2(AggregateKey(PORTION, link.portionId) in byKey && AggregateKey(COLLECTION, link.collectionId) in byKey &&
                reservations[link.portionId]?.reservationId == link.reservationId)
        }
        for (link in cancellations) {
            historyShapeV2(AggregateKey(ORDER, link.orderId) in byKey && AggregateKey(COLLECTION, link.collectionId) in byKey &&
                AggregateKey(REFUND, link.refundId) in byKey &&
                expectedClaims.put(ClaimKey(FINANCIAL_CLAIM, "unsent-resolution/${link.resolutionId.value}"), link.refundId) == null)
        }
        for (tender in tenders) historyShapeV2(tender.document.kind in setOf(COLLECTION, REFUND) && tender.document in byKey)
        issues.values.forEach { it.requireClosed() }
        reservations.values.forEach { it.requireClosed() }
        historyShapeV2(expectedClaims == claimOwners)
        owner.requireCurrentFoodTenderClaims(this, tenders)
        closed = true
    }

    internal suspend fun requireIssueSources(value: RuntimeHistoricalFoodCurrentIssueSources) {
        owner.requireCurrentFoodState(this)
        historyShapeV2(!closed && issues[value.portionId] === value)
    }

    internal suspend fun requireReservationSources(value: RuntimeHistoricalFoodCurrentReservationSources) {
        owner.requireCurrentFoodState(this)
        historyShapeV2(!closed && reservations[value.portionId] === value)
    }

    internal fun requireClosed() { historyShapeV2(closed) }
    internal fun clear() {
        issues.values.forEach { it.clear() }; issues.clear()
        reservations.values.forEach { it.clear() }; reservations.clear()
        sources = emptyList(); writers = emptyMap(); claimOwners = emptyMap(); closed = false
    }
    override fun toString(): String = "RuntimeHistoricalFoodCurrentState([REDACTED])"

    internal companion object {
        const val IDENTITY_CLAIM: String = "sales.food.identity"
        const val HANDOFF_CLAIM: String = "sales.food.handoff-incoming"
        const val FINANCIAL_CLAIM: String = "sales.food.financial-source"
        const val ORDER: String = "sales.food.order"
        const val PORTION: String = "sales.food.portion"
        const val RECIPE_QUOTATION: String = "sales.food.recipe-quotation"
        const val STATION_INDEX: String = "sales.food.station-index"
        const val COLLECTION: String = "sales.food.collection"
        const val REFUND: String = "sales.food.refund"
        val DOCUMENT_KINDS: Set<String> = setOf(ORDER, PORTION, RECIPE_QUOTATION, "sales.food.price-batch", "sales.food.owner-index", STATION_INDEX)
        val FINANCIAL_DOCUMENT_KINDS: Set<String> = setOf(COLLECTION, "sales.food.paid-sale", "sales.food.entitlement", REFUND,
            "sales.food.refund-index", "sales.food.quality-claim", "sales.food.claim-index")
        val KINDS: Set<String> = DOCUMENT_KINDS + FINANCIAL_DOCUMENT_KINDS + setOf("sales.food.before", "sales.food.event",
            "sales.food.financial-before", "sales.food.financial-event", "sales.food.financial-frame",
            "sales.food.physical-slot", "sales.food.physical-fact", "sales.food.physical-application")
        val CLAIM_NAMESPACES: Set<String> = setOf(IDENTITY_CLAIM, HANDOFF_CLAIM, FINANCIAL_CLAIM)
        private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    }
}
