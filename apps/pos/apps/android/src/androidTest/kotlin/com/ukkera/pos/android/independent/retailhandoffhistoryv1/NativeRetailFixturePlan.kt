package com.ukkera.pos.android.independent.retailhandoffhistoryv1

import com.ukkera.pos.catalog.store.LocalApprovedStockLineV1
import com.ukkera.pos.catalog.store.LocalBasisKindV1
import com.ukkera.pos.catalog.store.LocalFulfillmentV1
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.catalog.store.LocalPriceV1
import com.ukkera.pos.catalog.store.LocalPublishedSkuV1
import com.ukkera.pos.catalog.store.LocalReceivingApprovalV1
import com.ukkera.pos.catalog.store.LocalSalesRouteV1
import com.ukkera.pos.catalog.store.LocalTrackingV1
import com.ukkera.pos.catalog.store.LocalWarehouseV1
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.store.LocalCashEvidenceV1
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalCompanyFoundingFactsV1
import com.ukkera.pos.security.LocalCompanyHeadLifetimeV1
import com.ukkera.pos.security.LocalCompanyHeadPurposeV1
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalTokenPolicy
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedCompanyFactsV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.ExactDecimal
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.PercentageRate
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UnitPrice
import com.ukkera.pos.types.UtcInstant
import java.util.UUID

/**
 * Requested synthetic INPUTS for one isolated installation, not enrollment, authorization,
 * signed catalog trust or evidence of a committed baseline. Retain this namespace and plan
 * before invoking preparation. Never choose a replacement namespace after an unknown result.
 *
 * The frozen economics are 2 x EGP100, stock 10 x EGP20, and own drawer balances EGP1000/500.
 * Display names, exact logins, SUPERMARKET/COUNTER, scale zero, zero operational tax, revision
 * zero for new definitions, and the finite session/throttle settings below are explicit
 * synthetic construction choices, not additional frozen business rules or expected outcomes.
 * The real production installer, verifiers and current permission checks must accept them.
 * No password, root/key ID, session/token ID, native allocation or endpoint is generated here.
 */
internal class NativeRetailFixturePlan(
    val namespace: DomainId,
    val evidenceTime: UtcInstant,
    val businessDate: BusinessDate,
    val protection: SelfOwnedKeyProtectionV1,
) {
    val installerLogin: String = "retail-fixture-installer"
    val headLogin: String = "retail-fixture-head"
    val loginA: LocalLoginIdentifier = LocalLoginIdentifier("retail-fixture-a")
    val loginB: LocalLoginIdentifier = LocalLoginIdentifier("retail-fixture-b")
    val branchName: String = "Synthetic retail handoff branch"
    val authorityEpoch: Revision = Revision(1)
    val actorB: DomainId = id("employee-b")
    val enrollmentRequestB: DomainId = id("employee-b-enrollment")
    val publicationId: DomainId = id("sku-publication")
    val skuId: DomainId = id("sku")
    val productId: DomainId = id("product")
    val baseUnitId: DomainId = id("base-unit")
    val warehouseId: DomainId = id("warehouse")
    val routeId: DomainId = id("sales-route")
    val openingApprovalId: DomainId = id("opening-approval")
    val openingAllocationId: DomainId = id("opening-allocation")
    val stockInitializationId: DomainId = id("stock-initialization")
    val stockReceiptId: DomainId = id("stock-receipt")
    val stockReceiptLineId: DomainId = id("stock-receipt-line")
    val stockSourceId: DomainId = id("stock-source")
    val drawerA: DomainId = id("drawer-a")
    val drawerB: DomainId = id("drawer-b")
    val cashSessionA: DomainId = id("cash-session-a")
    val cashSessionB: DomainId = id("cash-session-b")
    val cashOpeningSourceA: DomainId = id("cash-opening-source-a")
    val cashOpeningSourceB: DomainId = id("cash-opening-source-b")
    val orderId: DomainId = id("order-r")
    val orderLineId: DomainId = id("order-r-line")
    val orderPortionId: DomainId = id("order-r-portion")
    val quantityTen: Quantity = Quantity(baseUnitId, 10, 0)
    val quantityTwo: Quantity = Quantity(baseUnitId, 2, 0)
    val stockValue: Money = Money(Currency.EGP, 20_000)
    val orderPayable: Money = Money(Currency.EGP, 20_000)
    val cashOpeningA: Money = Money(Currency.EGP, 100_000)
    val cashOpeningB: Money = Money(Currency.EGP, 50_000)
    val cashEvidenceA: LocalCashEvidenceV1 = LocalCashEvidenceV1(id("cash-evidence-a"), evidenceTime)
    val cashEvidenceB: LocalCashEvidenceV1 = LocalCashEvidenceV1(id("cash-evidence-b"), evidenceTime)
    val activity: LocalSalesActivity = LocalSalesActivity.SUPERMARKET
    val channel: LocalSalesChannel = LocalSalesChannel.COUNTER
    val reasonCode: String = "retail.fixture.opening"
    val effectiveFrom: UtcInstant = UtcInstant.parseCanonical("2000-01-01T00:00:00Z")

    val foundingFacts: LocalCompanyFoundingFactsV1 = LocalCompanyFoundingFactsV1(
        company = SelfOwnedCompanyFactsV1(
            "Synthetic retail handoff company", "Synthetic installer", installerLogin, protection,
        ),
        headName = "Synthetic company head",
        headLogin = headLogin,
        headLifetime = LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
        purposes = LocalCompanyHeadPurposeV1.entries.toSet(),
    )

    val warehouse: LocalWarehouseV1 = LocalWarehouseV1(
        warehouseId, LocalNamesV1("Synthetic retail warehouse", ""), active = true,
    )
    val route: LocalSalesRouteV1 = LocalSalesRouteV1(routeId, activity, channel, warehouseId, active = true)
    val openingApproval: LocalReceivingApprovalV1 = LocalReceivingApprovalV1(
        openingApprovalId, LocalBasisKindV1.OPENING, supplierId = null,
        validFrom = effectiveFrom, validUntil = null,
        lines = listOf(LocalApprovedStockLineV1(
            openingAllocationId, skuId, warehouseId, quantityTen, stockValue, inspectionRequired = false,
        )),
    )

    fun operation(step: NativeRetailFixtureCommand): OperationId = OperationId(id("operation-${step.name}").value)

    fun requestedSku(company: CompanyScope): LocalPublishedSkuV1 = LocalPublishedSkuV1(
        company = company, publicationId = publicationId, skuId = skuId, productId = productId,
        catalogRevision = Revision(0), skuRevision = Revision(0),
        names = LocalNamesV1("Synthetic handoff retail SKU", ""), code = "RETAIL-HANDOFF-1", barcodes = emptyList(),
        baseUnitId = baseUnitId, quantityScale = 0, tracking = LocalTrackingV1.QUANTITY,
        fulfillment = LocalFulfillmentV1.RETAIL, requiresExpiry = false,
        canPurchase = true, canSell = true, active = true, conversions = emptyList(),
        prices = listOf(LocalPriceV1(
            ruleId = id("unit-price-rule"), revision = Revision(0), channel = channel.name, priority = 0,
            minimumQuantity = Quantity(baseUnitId, 0, 0), maximumQuantityExclusive = null,
            effectiveFrom = effectiveFrom, effectiveUntil = null,
            unitPrice = UnitPrice(Currency.EGP, ExactDecimal.parse("100")), inclusion = TaxInclusion.INCLUSIVE,
            tax = TaxSnapshot(id("operational-tax-policy"), Revision(0), "SYNTHETIC_ZERO", PercentageRate.parse("0")),
        )),
        returnPromise = null,
    )

    fun requestedAccountA(coordinates: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning {
        require(coordinates.firstActorId != actorB)
        val actor = coordinates.firstActorId
        val scope = coordinates.scope
        val grants = listOf(
            grant(actor, scope, SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER, targets = setOf(skuId)),
            grant(actor, scope, SecurityAction.WAREHOUSE_CONFIGURE, PermissionVerb.ADMINISTER,
                targets = setOf(warehouseId, routeId)),
            grant(actor, scope, SecurityAction.OPENING_INVENTORY_APPROVE, PermissionVerb.ADMINISTER,
                amount = stockValue, targets = setOf(openingApprovalId)),
            grant(actor, scope, SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM,
                targets = setOf(stockInitializationId, stockReceiptId)),
            grant(actor, scope, SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM),
            grant(actor, scope, SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM),
            grant(actor, scope, SecurityAction.CATALOG_READ, PermissionVerb.PERFORM),
            grant(actor, scope, SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM),
            grant(actor, scope, SecurityAction.CASH_CONFIGURE, PermissionVerb.PERFORM,
                targets = setOf(actor, actorB, drawerA, drawerB)),
            grant(actor, scope, SecurityAction.CASH_OPEN, PermissionVerb.PERFORM,
                amount = cashOpeningA, targets = setOf(drawerA)),
            grant(actor, scope, SecurityAction.CASH_READ, PermissionVerb.PERFORM, targets = setOf(actor, cashSessionA)),
            grant(actor, scope, SecurityAction.ORDER_CREATE, PermissionVerb.PERFORM, targets = setOf(orderId)),
            grant(actor, scope, SecurityAction.ORDER_READ, PermissionVerb.PERFORM, targets = setOf(orderId)),
            grant(actor, scope, SecurityAction.ORDER_HANDOFF, PermissionVerb.PERFORM, targets = setOf(orderId)),
        )
        return account(coordinates, actor, loginA, grants)
    }

    fun requestedAccountB(coordinates: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning {
        require(coordinates.firstActorId != actorB)
        val scope = coordinates.scope
        return account(coordinates, actorB, loginB, listOf(
            grant(actorB, scope, SecurityAction.CASH_OPEN, PermissionVerb.PERFORM,
                amount = cashOpeningB, targets = setOf(drawerB)),
            grant(actorB, scope, SecurityAction.CASH_READ, PermissionVerb.PERFORM, targets = setOf(actorB, cashSessionB)),
            grant(actorB, scope, SecurityAction.ORDER_READ, PermissionVerb.PERFORM, targets = setOf(orderId)),
            grant(actorB, scope, SecurityAction.ORDER_HANDOFF, PermissionVerb.PERFORM, targets = setOf(orderId)),
        ))
    }

    private fun account(coordinates: SelfOwnedAllocationCoordinatesV1, actor: DomainId,
        login: LocalLoginIdentifier, grants: List<PermissionGrant>): LocalAccountProvisioning = LocalAccountProvisioning(
        actor, login, ActivatedLocalPolicy(
            coordinates.scope, actor, Revision(0), grants,
            sessionPolicy = LocalSessionPolicy(maximumConcurrentSessions = 1),
            throttlePolicy = ThrottlePolicy(
                freeFailures = 3, initialDelayMillis = 1_000, maximumDelayMillis = 900_000, attemptTimeoutMillis = 120_000,
            ),
            approvalPolicy = null,
        ),
        tokenPolicy = LocalTokenPolicy(accessLifetimeMillis = 1_800_000, workLifetimeMillis = 28_800_000),
        admittedDeviceIds = setOf(coordinates.deviceId),
    )

    private fun grant(actor: DomainId, scope: BranchScope, action: SecurityAction, verb: PermissionVerb,
        amount: Money? = null, targets: Set<DomainId>? = null): PermissionGrant = PermissionGrant(
        Permission(action, verb), scope, GrantEffect.ALLOW, PermissionLimits(maximumAmount = amount),
        ownerAccess = OwnerAccess.OWN_ONLY, namedSubjectId = actor, targetIds = targets, delegationCeiling = null,
    )

    private fun id(label: String): DomainId {
        val bytes = "retail-handoff-history-v1/${namespace.value}/$label".encodeToByteArray()
        return try { DomainId(UUID.nameUUIDFromBytes(bytes).toString()) } finally { bytes.fill(0) }
    }

    override fun toString(): String = "NativeRetailFixturePlan([REDACTED])"
}

internal enum class NativeRetailFixtureCommand {
    INSTALL_SKU, CONFIGURE_WAREHOUSE, CONFIGURE_ROUTE, APPROVE_OPENING, INITIALIZE_STOCK, OPENING_STOCK,
    CONFIGURE_CUSTODIAN_A, CONFIGURE_CUSTODIAN_B, CONFIGURE_DRAWER_A, CONFIGURE_DRAWER_B,
    OPEN_DRAWER_A, OPEN_DRAWER_B, CREATE_ORDER_R,
}
