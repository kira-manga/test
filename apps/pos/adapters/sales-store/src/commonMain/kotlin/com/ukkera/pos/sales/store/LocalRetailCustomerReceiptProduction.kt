package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimChange
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.ProposedOutcome
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable

/** Inert normalized operation coordinates; this is neither a principal nor a grant. */
internal data class LocalRetailCustomerReceiptProductionIdentity(
    val scope: BranchScope,
    val actorId: DomainId,
    val operationId: OperationId,
    val semanticDigest: PayloadDigest,
)

/** Literal original paid Finalize coordinates, supplied by the genuine source reader. */
@Serializable
internal data class LocalRetailCustomerReceiptSaleSourceV1(
    val schema: String,
    val scope: LocalSalesScope,
    val saleId: DomainId,
    val orderId: DomainId,
    val originalCollectorId: DomainId,
    val saleGeneration: Revision,
    val saleSchema: String,
    val saleDigest: PayloadDigest,
    val finalizeOperationId: OperationId,
    val finalizeActorId: DomainId,
    val finalizeCommand: String,
    val finalizeVersion: Int,
    val finalizeSemanticDigest: PayloadDigest,
    val finalizeAssignmentId: DomainId,
    val finalizeAuthorityEpoch: Revision,
    val finalizeSequence: JournalSequence,
    val paidFinalizeAt: UtcInstant,
)

/** Immutable DATA for live production and literal history comparison, never remaining rights. */
internal class LocalRetailCustomerReceiptOriginalSource(
    val coordinates: LocalRetailCustomerReceiptSaleSourceV1,
    portions: List<LocalRetailCustomerReceiptPortionV1>,
    recoveryResources: Set<RecoveryResourceKey>,
) {
    val portions: List<LocalRetailCustomerReceiptPortionV1> = portions.toList()
    val recoveryResources: Set<RecoveryResourceKey> = recoveryResources.toSet()

    fun publicOriginal(): LocalRetailCustomerReceiptOriginalV1 = LocalRetailCustomerReceiptOriginalV1(
        coordinates.saleId, coordinates.originalCollectorId, coordinates.paidFinalizeAt, portions)
}

@Serializable
internal data class LocalRetailCustomerReceiptHeadV1(
    val schema: String,
    val source: LocalRetailCustomerReceiptSaleSourceV1,
    /** This new profile's logical revision equals its actual head storage generation. */
    val headRevision: Revision,
    val eventOperationIds: List<OperationId>,
    val recordedPortions: List<LocalRetailCustomerReceiptPortionV1>,
    val lastOperationId: OperationId,
)

/** Actual direct authorization fields. Historical copies describe evidence, not authority. */
@Serializable
internal data class LocalRetailCustomerReceiptAuthorizationV1(
    val actorId: DomainId,
    val authenticationId: DomainId,
    val generation: SessionGeneration,
    val policyRevision: Revision,
)

/** A separate immutable observation for every accepted original operation. */
@Serializable
internal data class LocalRetailCustomerReceiptEventV1(
    val schema: String,
    val operationId: OperationId,
    val actorId: DomainId,
    val semanticDigest: PayloadDigest,
    val authorization: LocalRetailCustomerReceiptAuthorizationV1,
    val observation: LocalRetailCustomerReceiptObservationV1,
    val portions: List<LocalRetailCustomerReceiptPortionV1>,
    val receivedAt: UtcInstant,
    val receiptTimeZoneId: String,
    val recordedAt: UtcInstant,
    val beforeHeadGeneration: Revision?,
    val beforeHeadDigest: PayloadDigest?,
    val beforeHeadOperationId: OperationId?,
    val beforeEventDigest: PayloadDigest?,
    val sourceRecoveryResources: List<RecoveryResourceKey>,
    val after: LocalRetailCustomerReceiptHeadV1,
    val result: LocalRetailCustomerReceiptResultV1.Applied,
)

/** Exact literal predecessor image. It cannot construct a historical CommandReadView. */
internal class LocalRetailCustomerReceiptHeadImage(
    val generation: Revision,
    val schema: String,
    val payload: StoredBytes,
    val digest: PayloadDigest,
    val value: LocalRetailCustomerReceiptHeadV1,
)

/**
 * Shared pure producer. Live callers independently authorize at the real writer boundary;
 * historical callers independently join original sources, BEFORE images and native authority.
 * propose() emits comparable bytes only: it never issues an AuthorizedAction or proves commit.
 */
internal class LocalRetailCustomerReceiptProduction(
    val requirement: AuthorizationRequirement,
    val affectedResources: Set<RecoveryResourceKey>,
    private val proposal: (UtcInstant, LocalRetailCustomerReceiptAuthorizationV1) -> ProposedOutcome,
) {
    fun propose(recordedAt: UtcInstant, authorization: LocalRetailCustomerReceiptAuthorizationV1): ProposedOutcome =
        proposal(recordedAt, authorization)
}

internal fun retailCustomerReceiptProduction(
    identity: LocalRetailCustomerReceiptProductionIdentity,
    request: LocalRetailCustomerReceiptRequestV1,
    original: LocalRetailCustomerReceiptOriginalSource,
    before: LocalRetailCustomerReceiptHeadImage?,
    beforeEventDigest: PayloadDigest?,
): LocalRetailCustomerReceiptProduction {
    val source = original.coordinates
    check(source.scope.value() == identity.scope && source.saleId == request.saleId &&
        source.schema == LocalRetailCustomerReceiptProfileV1.SOURCE_SCHEMA &&
        semanticDigest(identity.scope, identity.actorId, LocalRetailCustomerReceiptCodec.COMMAND,
            LocalRetailCustomerReceiptCodec.encode(request)) == identity.semanticDigest) { "sales.customer_receipt.production_identity" }
    requireReceiptPortions(original.portions, LocalRetailCustomerReceiptProfileV1.MAX_ORIGINAL_PORTIONS)
    check((before == null) == (beforeEventDigest == null) && (before == null ||
        before.value.source == source && before.generation == before.value.headRevision &&
            before.schema == LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA)) { "sales.customer_receipt.predecessor" }
    val observedRevision = before?.value?.headRevision ?: Revision(0)
    val requirement = retailCustomerReceiptRequirement(identity, request.saleId, observedRevision, source.originalCollectorId)
    val resources = original.recoveryResources + retailCustomerReceiptResources(request.saleId, identity.actorId, identity.operationId) +
        before?.value?.eventOperationIds.orEmpty().map { orderResource(DomainId(it.value)) }
    return LocalRetailCustomerReceiptProduction(requirement, resources) { now, authorization ->
        check(authorization.actorId == identity.actorId && authorization.generation.value < Long.MAX_VALUE) {
            "sales.customer_receipt.authorization_coordinates"
        }
        if (request.expectedHeadRevision != observedRevision) {
            return@LocalRetailCustomerReceiptProduction retailCustomerReceiptRefused(LocalRetailCustomerReceiptRefusalV1.REVISION_CONFLICT)
        }
        if (request.receiptTimeZoneId != LocalRetailCustomerReceiptProfileV1.RECEIPT_TIME_ZONE_ID) {
            return@LocalRetailCustomerReceiptProduction retailCustomerReceiptRefused(LocalRetailCustomerReceiptRefusalV1.INVALID_RECEIPT_TIME_ZONE)
        }
        if (request.receivedAt < source.paidFinalizeAt || request.receivedAt > now) {
            return@LocalRetailCustomerReceiptProduction retailCustomerReceiptRefused(LocalRetailCustomerReceiptRefusalV1.INVALID_OBSERVATION_TIME)
        }
        if (observedRevision.value >= LocalRetailCustomerReceiptProfileV1.MAX_EVENTS) {
            return@LocalRetailCustomerReceiptProduction retailCustomerReceiptRefused(LocalRetailCustomerReceiptRefusalV1.CAPACITY_REQUIRED)
        }
        val originalById = original.portions.associateBy { it.portionId }
        val quantities = before?.value?.recordedPortions.orEmpty().associateBy { it.portionId }.toMutableMap()
        for (portion in request.portions) {
            val sold = originalById[portion.portionId]?.quantity
            val supplied = portion.quantity
            if (sold == null || supplied.scaled <= 0 || supplied.unitId != sold.unitId || supplied.scale != sold.scale) {
                return@LocalRetailCustomerReceiptProduction retailCustomerReceiptRefused(LocalRetailCustomerReceiptRefusalV1.INVALID_QUANTITY)
            }
            val previous = quantities[portion.portionId]?.quantity?.scaled ?: 0L
            check(previous in 0L..sold.scaled) { "sales.customer_receipt.previous_quantity" }
            // Subtract first: neither signed overflow nor implicit precision conversion is possible.
            if (supplied.scaled > sold.scaled - previous) {
                return@LocalRetailCustomerReceiptProduction retailCustomerReceiptRefused(LocalRetailCustomerReceiptRefusalV1.QUANTITY_EXCEEDED)
            }
            quantities[portion.portionId] = LocalRetailCustomerReceiptPortionV1(portion.portionId,
                supplied.copy(scaled = previous + supplied.scaled))
        }
        check(identity.operationId !in before?.value?.eventOperationIds.orEmpty()) { "sales.customer_receipt.original_operation" }
        val after = LocalRetailCustomerReceiptHeadV1(LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA, source,
            observedRevision.next(), before?.value?.eventOperationIds.orEmpty() + identity.operationId,
            quantities.values.sortedBy { it.portionId.value }, identity.operationId)
        val result = LocalRetailCustomerReceiptResultV1.Applied(source.saleId, identity.operationId, after.headRevision,
            request.receivedAt, request.receiptTimeZoneId, now)
        val event = LocalRetailCustomerReceiptEventV1(LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA,
            identity.operationId, identity.actorId, identity.semanticDigest, authorization, request.observation,
            request.portions.toList(), request.receivedAt, request.receiptTimeZoneId, now, before?.generation,
            before?.digest, before?.value?.lastOperationId, beforeEventDigest,
            original.recoveryResources.sortedWith(compareBy({ it.kind.name }, { it.resourceId.value })), after, result)
        try {
            val headPayload = localSalesEncode(after, LocalRetailCustomerReceiptHeadV1.serializer(), LocalRetailCustomerReceiptProfileV1.MAX_ROW_BYTES)
            val eventPayload = localSalesEncode(event, LocalRetailCustomerReceiptEventV1.serializer(), LocalRetailCustomerReceiptProfileV1.MAX_ROW_BYTES)
            // Do not commit a head whose complete public original/cumulative projection cannot fit.
            LocalRetailCustomerReceiptReadCodec.result(LocalRetailCustomerReceiptReadResultV1.Head(retailCustomerReceiptHeadView(original, after)))
            val changes = listOf(
                AggregateChange(retailCustomerReceiptHeadKey(source.saleId), before?.generation,
                    LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA, headPayload),
                AggregateChange(retailCustomerReceiptEventKey(identity.operationId), null,
                    LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA, eventPayload),
            ) + if (before == null) emptyList() else listOf(AggregateChange(
                retailCustomerReceiptBeforeKey(identity.operationId), null,
                LocalRetailCustomerReceiptProfileV1.HEAD_BEFORE_SCHEMA, before.payload))
            val claims = listOf(
                ClaimChange.Acquire(retailCustomerReceiptEventClaim(identity.operationId), source.saleId),
                ClaimChange.Acquire(retailCustomerReceiptMembershipClaim(source.saleId, identity.operationId), source.saleId),
            ) + if (before == null) listOf(ClaimChange.Acquire(retailCustomerReceiptHeadClaim(source.saleId), source.saleId)) else emptyList()
            ProposedOutcome.Apply(LocalRetailCustomerReceiptCodec.result(result), changes, claims)
        } catch (_: LocalSalesWireCapacityExceeded) {
            retailCustomerReceiptRefused(LocalRetailCustomerReceiptRefusalV1.CAPACITY_REQUIRED)
        }
    }
}

internal fun retailCustomerReceiptRequirement(identity: LocalRetailCustomerReceiptProductionIdentity,
    saleId: DomainId, revision: Revision, collectorId: DomainId): AuthorizationRequirement = AuthorizationRequirement(
    Permission(SecurityAction.CUSTOMER_RECEIPT_RECORD, PermissionVerb.PERFORM), identity.scope,
    ActionBinding(saleId, revision, identity.semanticDigest, identity.operationId, "customer.receipt.record"),
    owner = OwnerConstraint.OwnerPrivate(collectorId))

internal fun retailCustomerReceiptResources(saleId: DomainId, actorId: DomainId, operationId: OperationId): Set<RecoveryResourceKey> =
    setOf(orderResource(saleId), entitlementResource(saleId), orderResource(DomainId(operationId.value)),
        RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, actorId))

internal fun retailCustomerReceiptHeadView(original: LocalRetailCustomerReceiptOriginalSource,
    head: LocalRetailCustomerReceiptHeadV1): LocalRetailCustomerReceiptHeadViewV1 = LocalRetailCustomerReceiptHeadViewV1(
    original.publicOriginal(), head.headRevision, head.eventOperationIds.size, head.recordedPortions, head.lastOperationId)

internal fun retailCustomerReceiptEventView(event: LocalRetailCustomerReceiptEventV1): LocalRetailCustomerReceiptEventViewV1 {
    val source = event.after.source
    return LocalRetailCustomerReceiptEventViewV1(source.saleId, event.operationId, event.after.headRevision,
        event.observation, event.portions, event.receivedAt, event.receiptTimeZoneId, event.recordedAt,
        source.originalCollectorId, source.paidFinalizeAt, event.actorId, source.finalizeOperationId,
        source.finalizeActorId, source.finalizeSemanticDigest, event.semanticDigest)
}

internal fun retailCustomerReceiptRefused(reason: LocalRetailCustomerReceiptRefusalV1): ProposedOutcome.Reject =
    ProposedOutcome.Reject("sales.customer_receipt.refused", LocalRetailCustomerReceiptCodec.result(LocalRetailCustomerReceiptResultV1.Refused(reason)))

internal fun retailCustomerReceiptHeadKey(saleId: DomainId): AggregateKey = AggregateKey(LocalRetailCustomerReceiptProfileV1.HEAD, saleId)
internal fun retailCustomerReceiptEventKey(operationId: OperationId): AggregateKey =
    AggregateKey(LocalRetailCustomerReceiptProfileV1.EVENT, DomainId(operationId.value))
internal fun retailCustomerReceiptBeforeKey(operationId: OperationId): AggregateKey =
    AggregateKey(LocalRetailCustomerReceiptProfileV1.HEAD_BEFORE, DomainId(operationId.value))
internal fun retailCustomerReceiptHeadClaim(saleId: DomainId): ClaimKey =
    ClaimKey(LocalRetailCustomerReceiptProfileV1.NAMESPACE, "head/${saleId.value}")
internal fun retailCustomerReceiptEventClaim(operationId: OperationId): ClaimKey =
    ClaimKey(LocalRetailCustomerReceiptProfileV1.NAMESPACE, "event/${operationId.value}")
internal fun retailCustomerReceiptMembershipPrefix(saleId: DomainId): String = "sale/${saleId.value}/event/"
internal fun retailCustomerReceiptMembershipClaim(saleId: DomainId, operationId: OperationId): ClaimKey =
    ClaimKey(LocalRetailCustomerReceiptProfileV1.NAMESPACE, retailCustomerReceiptMembershipPrefix(saleId) + operationId.value)
