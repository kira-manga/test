package com.ukkera.pos.app.gateway.independent.managerorderreviewv1

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.MasterReadHandler
import com.ukkera.pos.database.PreparedRead
import com.ukkera.pos.database.ReadContext
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.queryDigest
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.pricing.AdjustmentStage
import com.ukkera.pos.pricing.DiscountLimits
import com.ukkera.pos.pricing.DiscountValue
import com.ukkera.pos.pricing.EligibleDiscountPortion
import com.ukkera.pos.pricing.OperationalPricingEngine
import com.ukkera.pos.pricing.PriceAdjustment
import com.ukkera.pos.pricing.PricingRequest
import com.ukkera.pos.pricing.PricingSnapshot
import com.ukkera.pos.pricing.ResolvedPriceLine
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.SalesSnapshotBinding
import com.ukkera.pos.sales.SalesSnapshotCodec
import com.ukkera.pos.sales.SalesSnapshotKind
import com.ukkera.pos.sales.store.LocalManagerOrderReviewCodec
import com.ukkera.pos.sales.store.LocalManagerOrderReviewQueryV1
import com.ukkera.pos.sales.store.LocalOrderComponentV1
import com.ukkera.pos.sales.store.LocalOrderLineV1
import com.ukkera.pos.sales.store.LocalOrderPriceLineV1
import com.ukkera.pos.sales.store.LocalOrderSelectionV1
import com.ukkera.pos.sales.store.LocalOrderStockProfileV2
import com.ukkera.pos.sales.store.LocalOrderViewV1
import com.ukkera.pos.sales.store.LocalProfiledOrderV2
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.sales.store.LocalSalesHandlers
import com.ukkera.pos.sales.store.LocalSalesStoreProfile
import com.ukkera.pos.sales.store.SalesStorageRevision
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationEvaluator
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CentralGatewayCredentials
import com.ukkera.pos.security.DeviceGatewayCredentials
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PrincipalKind
import com.ukkera.pos.security.PrincipalVerifier
import com.ukkera.pos.security.ServiceGatewayCredentials
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.TrustedPrincipalFactory
import com.ukkera.pos.security.VerifiedCentralIdentity
import com.ukkera.pos.security.VerifiedDeviceIdentity
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.security.VerifiedServiceIdentity
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Frozen semantic values only. These are not native rows, provisioned actors or digest vectors. */
internal object FrozenManagerReview {
    val branchA = DomainId("11111111-1111-4111-8111-111111110001")
    val branchB = DomainId("11111111-1111-4111-8111-111111110002")
    val owner = DomainId("33333333-3333-4333-8333-333333330001")
    val manager = DomainId("33333333-3333-4333-8333-333333330002")
    val legacyOrder = DomainId("22222222-2222-4222-8222-222222220101")
    val normalizedOrder = DomainId("22222222-2222-4222-8222-222222220102")
    val absentOrder = DomainId("22222222-2222-4222-8222-222222220104")
    val foreignBranchOrder = DomainId("22222222-2222-4222-8222-222222220107")
    val collection = DomainId("44444444-4444-4444-8444-444444440401")
    val sale = DomainId("44444444-4444-4444-8444-444444440501")
    val legacyRevision = Revision(7)
    val normalizedRevision = Revision(23)
    const val reviewPurpose = "order.manager-review"
}

/**
 * Supplemental constructor coordinates, not independent fixture oracles. The frozen design
 * does not supply these IDs, quote/work/catalog revisions, UOM encoding or name bindings.
 */
private object WiringCoordinates {
    fun id(ordinal: Int): DomainId = DomainId("7f310000-0000-4000-8000-${ordinal.toString().padStart(12, '0')}")
    val organization = id(1)
    val company = id(2)
    val device = id(3)
    val session = id(4)
    val connection = id(5)
    val unit = id(6)
    val line = id(7)
    val portion = id(8)
    val item = id(9)
    val variant = id(10)
    val warehouse = id(11)
    val allocation = id(12)
    val taxPolicy = id(13)
    val discount = id(14)
    val quoteRevision = Revision(0)
    val catalogRevision = Revision(3)
    val workRevision = Revision(1)
    val observedAt = UtcInstant.parseCanonical("2026-10-30T09:00:00Z")
}

internal val controlledManagerReviewScope = BranchScope(
    WiringCoordinates.organization, WiringCoordinates.company, FrozenManagerReview.branchA,
)
internal val controlledForeignReviewScope = BranchScope(
    WiringCoordinates.organization, WiringCoordinates.company, FrozenManagerReview.branchB,
)
internal val controlledManagerReviewObservedAt: UtcInstant = WiringCoordinates.observedAt

/** Current serializers supply transport/storage wiring, never independently expected canonical bytes. */
internal val managerReviewWiringJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}

internal fun installedManagerReviewReaders(): List<MasterReadHandler> =
    // No accepting composite verifier or relational authority is installed. Legacy/empty
    // slices do not open stock; normalized native producer coverage stays unbound.
    LocalSalesHandlers.reads(LocalStockRepository())

internal fun installedManagerReviewReader(): MasterReadHandler =
    installedManagerReviewReaders().single { it.key == LocalManagerOrderReviewCodec.QUERY }

internal data class ControlledPreparedReview(
    val prepared: PreparedRead,
    val context: ReadContext,
    val query: LocalManagerOrderReviewQueryV1,
)

internal suspend fun prepareControlledReview(
    orderId: DomainId = FrozenManagerReview.legacyOrder,
    actorId: DomainId = FrozenManagerReview.manager,
    view: ControlledManagerReviewView = controlledLegacyReviewView(),
): ControlledPreparedReview {
    val handler = installedManagerReviewReader()
    val query = LocalManagerOrderReviewQueryV1(orderId)
    val normalized = handler.normalize(LocalManagerOrderReviewCodec.encode(query))
    // Production computation is a supplied context value for forwarding checks only;
    // it does not bind the independently specified D_L/D_N/D_A symbols.
    val context = ReadContext(view.scope, actorId, handler.key,
        queryDigest(view.scope, actorId, handler.key, normalized))
    return ControlledPreparedReview(handler.prepare(normalized, view, context), context, query)
}

/** Read-only controlled rows. No native persistence, committed business cut or stock proof exists here. */
internal class ControlledManagerReviewView(
    override val scope: BranchScope = controlledManagerReviewScope,
    rows: List<StoredAggregate> = emptyList(),
    claims: Map<ClaimKey, DomainId> = emptyMap(),
) : CommandReadView {
    private val rowsByKey = rows.associateBy { it.key }
    private val claimOwners = claims.toMap()
    val aggregateCalls = mutableListOf<AggregateKey>()
    val claimCalls = mutableListOf<ClaimKey>()
    val retainedCalls = mutableListOf<OperationId>()

    override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        aggregateCalls += key
        return rowsByKey[key]
    }

    override suspend fun claim(key: ClaimKey): DomainId? {
        claimCalls += key
        return claimOwners[key]
    }

    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? {
        retainedCalls += operationId
        throw AssertionError("No retained original is bound for this controlled legacy/empty slice")
    }
}

/**
 * Production public snapshot validation constructs the inaccessible immutable order.
 * These DTOs reproduce only the source packet needed for harness wiring; they are
 * not independently validated source/canonical vectors or a historical native commit.
 */
@Serializable
private data class WiringScope(val organizationId: DomainId, val companyId: DomainId, val branchId: DomainId)

@Serializable
private data class WiringLegacyAllocation(
    val allocationId: DomainId,
    val portionId: DomainId,
    val companyId: DomainId,
    val branchId: DomainId,
    val skuId: DomainId,
    val warehouseId: DomainId,
    val supplierOwnerId: DomainId?,
    val lotId: DomainId?,
    val custody: String,
    val quantity: Quantity,
)

@Serializable
private data class WiringLegacyOrderState(
    val originalCreatorId: DomainId,
    val ownerId: DomainId,
    val quote: String,
    val stockRequirements: List<WiringLegacyAllocation>,
    val handoff: String?,
    val collectionId: DomainId?,
    val paidSaleId: DomainId?,
)

@Serializable
private data class WiringLegacyOrderEnvelope(
    val schemaVersion: Int,
    val kind: String,
    val scope: WiringScope,
    val id: DomainId,
    val revision: Revision,
    val state: WiringLegacyOrderState,
)

@Serializable
private data class WiringOrderContext(
    val scope: WiringScope,
    val orderId: DomainId,
    val revision: Revision,
    val quoteRevision: Revision,
    val selection: LocalOrderSelectionV1,
)

@Serializable
private data class WiringWorkState(
    val scope: WiringScope,
    val id: DomainId,
    val revision: Revision,
    val orderIds: List<DomainId> = emptyList(),
    val returnIds: List<DomainId> = emptyList(),
    val pendingClaimIds: List<DomainId> = emptyList(),
)

internal fun controlledLegacyReviewView(): ControlledManagerReviewView {
    val scope = WiringScope(controlledManagerReviewScope.organizationId,
        controlledManagerReviewScope.companyId, controlledManagerReviewScope.branchId)
    val quote = controlledQuote(normalized = false)
    val quantity = quote.lines.single().quantity
    val state = WiringLegacyOrderState(FrozenManagerReview.owner, FrozenManagerReview.owner,
        StoredPricingSnapshotCodec.encode(quote), listOf(WiringLegacyAllocation(
            WiringCoordinates.allocation, WiringCoordinates.portion, scope.companyId, scope.branchId,
            WiringCoordinates.item, WiringCoordinates.warehouse, null, null, StockCustody.RELEASED.name, quantity,
        )), null, null, null)
    val envelope = WiringLegacyOrderEnvelope(SalesSnapshotCodec.SCHEMA_VERSION, SalesSnapshotKind.ORDER.name,
        scope, FrozenManagerReview.legacyOrder, FrozenManagerReview.legacyRevision, state)
    val text = managerReviewWiringJson.encodeToString(WiringLegacyOrderEnvelope.serializer(), envelope)
    val order = SalesSnapshotCodec.decodeOrder(text, SalesSnapshotBinding(SalesSnapshotKind.ORDER,
        controlledManagerReviewScope, FrozenManagerReview.legacyOrder, FrozenManagerReview.legacyRevision))
    val context = WiringOrderContext(scope, order.id, FrozenManagerReview.legacyRevision,
        quote.revision, controlledSelection(normalized = false))
    val work = WiringWorkState(scope, FrozenManagerReview.owner, WiringCoordinates.workRevision,
        orderIds = listOf(FrozenManagerReview.legacyOrder))
    return ControlledManagerReviewView(rows = listOf(
        controlledRow(LocalSalesStoreProfile.ORDER_KIND, order.id, FrozenManagerReview.legacyRevision,
            SalesSnapshotCodec.encodeOrder(order)),
        controlledRow(LocalSalesStoreProfile.ORDER_CONTEXT_KIND, order.id, context.revision,
            managerReviewWiringJson.encodeToString(WiringOrderContext.serializer(), context)),
        controlledRow(LocalSalesStoreProfile.WORK_KIND, work.id, work.revision,
            managerReviewWiringJson.encodeToString(WiringWorkState.serializer(), work)),
    ), claims = mapOf(
        LocalSalesStoreProfile.unique("order", order.id) to order.id,
        ClaimKey(LocalSalesStoreProfile.CONFIGURED_NAMESPACE,
            "${LocalSalesStoreProfile.CUSTODIAN_KIND}/${work.id.value}") to work.id,
    ))
}

private fun controlledRow(kind: String, id: DomainId, businessRevision: Revision, text: String): StoredAggregate {
    val payload = StoredBytes(text.encodeToByteArray())
    // This public adapter is source wiring, not an independently prescribed native generation pair.
    return StoredAggregate(LocalSalesStoreProfile.key(kind, id), SalesStorageRevision.toStorage(businessRevision),
        LocalSalesStoreProfile.schema(kind), payload, sha256(payload.copyBytes()))
}

private fun controlledSelection(normalized: Boolean): LocalOrderSelectionV1 = LocalOrderSelectionV1(
    if (normalized) LocalSalesActivity.SUPERMARKET else LocalSalesActivity.CLOTHING,
    LocalSalesChannel.COUNTER, Currency.EGP, WiringCoordinates.catalogRevision,
    listOf(LocalOrderLineV1(WiringCoordinates.line, WiringCoordinates.portion, WiringCoordinates.item,
        Quantity.parse(WiringCoordinates.unit, if (normalized) "3" else "2", 0), WiringCoordinates.variant)),
)

private fun controlledQuote(normalized: Boolean): PricingSnapshot {
    val currency = Currency.EGP
    val quantity = controlledSelection(normalized).lines.single().quantity
    val limits = if (normalized) DiscountLimits(Money(currency, 250), PercentageRate.parse("100"))
        else DiscountLimits.none(currency)
    val line = ResolvedPriceLine(WiringCoordinates.line, WiringCoordinates.portion, Revision(1), quantity,
        UnitPrice.parse(currency, if (normalized) "12.5" else "100"), TaxInclusion.EXCLUSIVE,
        TaxSnapshot(WiringCoordinates.taxPolicy, Revision(1), "ZERO", PercentageRate.parse("0")), limits)
    val adjustments = if (normalized) listOf(PriceAdjustment(WiringCoordinates.discount, Revision(1),
        AdjustmentStage.ORDER_OR_MANUAL_DISCOUNT, DiscountValue.FixedPayable(Money(currency, 250)),
        listOf(EligibleDiscountPortion(WiringCoordinates.portion)), limits)) else emptyList()
    return OperationalPricingEngine.calculate(PricingRequest(
        if (normalized) FrozenManagerReview.normalizedOrder else FrozenManagerReview.legacyOrder,
        WiringCoordinates.quoteRevision, currency, listOf(line), adjustments = adjustments, discountLimits = limits,
    ))
}

/**
 * Typed result construction for decoder-only slices. In particular the normalized form
 * is NOT an order source, accepting stock history, handler response or native read proof.
 */
internal fun controlledTypedReviewProjection(normalized: Boolean): LocalProfiledOrderV2 {
    val quote = controlledQuote(normalized)
    val view = LocalOrderViewV1(
        if (normalized) FrozenManagerReview.normalizedOrder else FrozenManagerReview.legacyOrder,
        FrozenManagerReview.owner,
        if (normalized) FrozenManagerReview.normalizedRevision else FrozenManagerReview.legacyRevision,
        quote.revision, controlledSelection(normalized), quote.beforeDiscountPayable,
        quote.advertisedDiscount, quote.payable,
        quote.lines.map { LocalOrderPriceLineV1(it.portionId, it.quantity, it.payable) },
        quote.components.map { LocalOrderComponentV1(it.key, it.portionId, it.chargeId,
            it.kind.name, it.posted, it.tax.categoryCode) },
        if (normalized) FrozenManagerReview.collection else null,
        if (normalized) FrozenManagerReview.sale else null,
        null, null,
    )
    return LocalProfiledOrderV2(view,
        if (normalized) LocalOrderStockProfileV2.NORMALIZED else LocalOrderStockProfileV2.LEGACY_BOUNDED)
}

/** Scripted identities/snapshots issue typed proofs only; no current Master provisioning is exercised. */
internal object ControlledReviewAuthority {
    suspend fun employee(actorId: DomainId = FrozenManagerReview.manager): LocalEmployeePrincipal {
        val verifier = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? =
                if (credentials.connectionId == WiringCoordinates.connection &&
                    credentials.bearer.useBytes { bytes -> bytes.all { it == 0.toByte() } })
                    VerifiedLocalIdentity(actorId, controlledManagerReviewScope, WiringCoordinates.device,
                        WiringCoordinates.session, SessionGeneration(1), Revision(1), AuthenticationPurpose.INTERACTIVE)
                else null
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val bytes = ByteArray(16)
        val bearer = try { OpaqueCredential(bytes) } finally { bytes.fill(0) }
        return try {
            assertNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(
                LocalGatewayCredentials(WiringCoordinates.connection, bearer)))
        } finally {
            bearer.destroy()
        }
    }

    fun grant(principal: LocalEmployeePrincipal, permission: Permission, targetId: DomainId): PermissionGrant =
        PermissionGrant(permission, principal.scope, GrantEffect.ALLOW,
            namedSubjectId = principal.actorId, targetIds = setOf(targetId))

    fun snapshot(principal: LocalEmployeePrincipal, grants: List<PermissionGrant>): AuthorizationSnapshot =
        AuthorizationSnapshot(principal.actorId, PrincipalKind.LOCAL_EMPLOYEE, principal.scope,
            principal.authenticationId, principal.generation, principal.deviceId, principal.admissionRevision,
            Revision(1), accountEnabled = true, sessionActive = true, deviceAdmitted = true,
            authenticationPurpose = AuthenticationPurpose.INTERACTIVE, grants = grants)

    fun proof(principal: LocalEmployeePrincipal, requirement: AuthorizationRequirement): AuthorizedAction {
        val targetId = assertNotNull(requirement.binding).documentId
        val decision = AuthorizationEvaluator.evaluate(principal,
            snapshot(principal, listOf(grant(principal, requirement.permission, targetId))), requirement)
        return assertIs<AuthorizationDecision.Allowed>(decision,
            "Only a controlled typed proof is expected; this is not current Master authority").authorization
    }
}
