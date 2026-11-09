package com.ukkera.pos.android.independent.coldoriginalstartv1

import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureSetup
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.util.UUID
import org.json.JSONObject

/** Construction inputs only. Native identities and grants must be issued by actual setup.
 * Economics and ACTOR-F selectors come directly from the unchanged frozen v1 asset. */
internal class NativeColdOriginalFixturePlan(
    val frozen: JSONObject,
    val namespace: DomainId,
    val evidenceTime: UtcInstant,
    val businessDate: BusinessDate,
    val protection: SelfOwnedKeyProtectionV1,
) {
    private val inputs = frozen.getJSONObject("business_inputs")
    private val actor = frozen.getJSONArray("actors").let { values ->
        (0 until values.length()).map(values::getJSONObject).single { it.getString("alias") == "ACTOR-F" }
    }
    init {
        check(frozen.getString("design_version") == "cold-original-start-v1")
        check(frozen.getString("execution_status") == "UNRUN")
        check(inputs.getString("activity") == "CLOTHING" && inputs.getString("currency") == "EGP")
        check(inputs.getInt("currency_scale") == 2 && inputs.getString("unit") == "piece")
        check(listOf("tax_rate", "discount_minor", "other_charge_minor",
            "blocked_quarantined_expired_quantity", "other_reservations_quantity").all {
            inputs.getString(it).toBigDecimal().signum() == 0
        })
    }
    val installerLogin = "cold-original-installer"
    val headLogin = "cold-original-head"
    val employeeLogin = LocalLoginIdentifier("cold-original-founding")
    val companyName = "Synthetic cold original company"
    val branchName = "Synthetic cold original branch"
    val activity = LocalSalesActivity.CLOTHING
    val channel = LocalSalesChannel.COUNTER
    val authorityEpoch = Revision(1)
    val effectiveFrom = UtcInstant.parseCanonical("2000-01-01T00:00:00Z")
    val productId = DomainId(inputs.getString("product_id"))
    val warehouseId = DomainId(inputs.getString("warehouse_id"))
    val drawerId = DomainId(actor.getString("drawer_id"))
    val orderId = DomainId(actor.getString("order_id"))
    val startOperationId = OperationId(actor.getString("operation_id"))
    val skuId = id("sku")
    val baseUnitId = id("piece")
    val publicationId = id("sku-publication")
    val routeId = id("route")
    val stockInitializationId = id("stock-initialization")
    val openingApprovalId = id("opening-approval")
    val openingAllocationId = id("opening-allocation")
    val openingReceiptId = id("opening-receipt")
    val openingReceiptLineId = id("opening-receipt-line")
    val openingStockSourceId = id("opening-stock-source")
    val cashSessionId = id("cash-session")
    val cashOpeningSourceId = id("cash-opening-source")
    val orderLineId = id("order-line")
    val orderPortionId = id("order-portion")
    val collectionId = id("collection")
    val reservationId = id("reservation")
    val attemptId = id("cash-attempt")
    // These are unique selectors. The real native gate, not this plan, acquires capacity.
    val masterAllocationId = id("master-physical-allocation")
    val clientAllocationId = id("client-physical-allocation")
    val recordingOperationId = operation("cash-original")
    val resolutionOperationId = operation("cash-resolution")
    val applicationOperationId = operation("cash-application")
    val finalizeOperationId = operation("sale-finalization")
    val saleId = id("sale")
    val issueId = id("sale-stock-issue")
    val reasonCode = "cold.original.synthetic.opening"
    val initialQuantity = quantity("quantity_initial")
    val orderQuantity = quantity("quantity_each_order")
    val openingCash = money("opening_cash_each_drawer_minor")
    val inventoryValue = money("initial_inventory_carrying_value_minor")
    val payable = money("bill_gross_minor")
    val received = money("cash_received_minor")
    val change = money("cash_change_minor")
    val cashNet = money("cash_net_minor")
    val openingCashEvidence = LocalCashEvidenceV1(id("cash-opening-evidence"), evidenceTime)
    val paymentOutcome = LocalPaymentOutcomeV3.CashReceived(received, change,
        LocalCashEvidenceV1(id("cash-original-evidence"), evidenceTime))
    val warehouse = LocalWarehouseV1(warehouseId, LocalNamesV1("Synthetic clothing warehouse", ""), true)
    val route = LocalSalesRouteV1(routeId, activity, channel, warehouseId, true)
    val openingApproval = LocalReceivingApprovalV1(openingApprovalId, LocalBasisKindV1.OPENING,
        supplierId = null, validFrom = effectiveFrom, validUntil = null,
        lines = listOf(LocalApprovedStockLineV1(openingAllocationId, skuId, warehouseId,
            initialQuantity, inventoryValue, inspectionRequired = false)))

    val setup = NativeLocalFixtureSetup(companyName, branchName, installerLogin, headLogin,
        authorityEpoch, founding = { requestedProtection ->
            check(requestedProtection == protection)
            LocalCompanyFoundingFactsV1(
                SelfOwnedCompanyFactsV1(companyName, "Synthetic installer", installerLogin, requestedProtection),
                "Synthetic company head", headLogin, LocalCompanyHeadLifetimeV1.UNTIL_LOCAL_DISABLE,
                LocalCompanyHeadPurposeV1.entries.toSet())
        }, account = ::requestedFoundingAccount)

    fun requestedSku(company: CompanyScope): LocalPublishedSkuV1 = LocalPublishedSkuV1(
        company, publicationId, skuId, productId, Revision(0), Revision(0),
        LocalNamesV1("Synthetic clothing SKU", ""), "COLD-ORIGINAL-CLOTHING-1", emptyList(),
        baseUnitId, 0, LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL,
        requiresExpiry = false, canPurchase = true, canSell = true, active = true,
        conversions = emptyList(), prices = listOf(LocalPriceV1(
            id("price-rule"), Revision(0), channel.name, 0, Quantity(baseUnitId, 0, 0), null,
            effectiveFrom, null, UnitPrice(Currency.EGP, ExactDecimal.parse(
                inputs.getString("unit_price_minor").toBigDecimal().movePointLeft(2).toPlainString())),
            TaxInclusion.INCLUSIVE,
            TaxSnapshot(id("zero-tax-policy"), Revision(0), "SYNTHETIC_ZERO", PercentageRate.parse("0")))),
        returnPromise = null)

    fun operation(step: ColdPreparationCommand): OperationId = operation("preparation-${step.name}")
    fun checkpoint(name: String): JSONObject = frozen.getJSONObject("checkpoints").getJSONObject(name)
    fun businessCheckpoint(name: String): JSONObject {
        val own = checkpoint(name)
        return if (own.has("inherits_business_totals_from")) checkpoint(own.getString("inherits_business_totals_from")) else own
    }
    fun money(key: String): Money = Money(Currency.EGP, inputs.getString(key).toLong())
    fun quantity(key: String): Quantity = Quantity(baseUnitId, inputs.getString(key).toLong(), 0)

    private fun requestedFoundingAccount(c: SelfOwnedAllocationCoordinatesV1): LocalAccountProvisioning {
        val actorId = c.firstActorId
        fun grant(action: SecurityAction, verb: PermissionVerb = PermissionVerb.PERFORM,
            amount: Money? = null, targets: Set<DomainId>? = null): PermissionGrant = PermissionGrant(
            Permission(action, verb), c.scope, GrantEffect.ALLOW, PermissionLimits(maximumAmount = amount),
            ownerAccess = OwnerAccess.OWN_ONLY, namedSubjectId = actorId, targetIds = targets, delegationCeiling = null)
        val grants = listOf(
            grant(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER, targets = setOf(skuId)),
            grant(SecurityAction.WAREHOUSE_CONFIGURE, PermissionVerb.ADMINISTER, targets = setOf(warehouseId, routeId)),
            grant(SecurityAction.OPENING_INVENTORY_APPROVE, PermissionVerb.ADMINISTER,
                inventoryValue, setOf(openingApprovalId)),
            grant(SecurityAction.INVENTORY_ADJUST, targets = setOf(stockInitializationId, openingReceiptId)),
            grant(SecurityAction.CATALOG_READ), grant(SecurityAction.PURCHASING_READ),
            grant(SecurityAction.INVENTORY_QUANTITY_READ),
            // Independent permission; quantity/cash/paid-sale reads never imply cost access.
            grant(SecurityAction.INVENTORY_COST_READ),
            grant(SecurityAction.CASH_CONFIGURE, targets = setOf(actorId, drawerId)),
            grant(SecurityAction.CASH_OPEN, amount = openingCash, targets = setOf(drawerId)),
            grant(SecurityAction.CASH_READ, targets = setOf(actorId, cashSessionId)),
            grant(SecurityAction.ORDER_CREATE, targets = setOf(orderId)),
            grant(SecurityAction.ORDER_READ, targets = setOf(orderId, collectionId)),
            grant(SecurityAction.COLLECTION_BEGIN, amount = payable, targets = setOf(orderId)),
            grant(SecurityAction.COLLECTION_ATTEST, amount = payable, targets = setOf(collectionId)),
            grant(SecurityAction.COLLECTION_FINALIZE, amount = payable, targets = setOf(orderId)),
            grant(SecurityAction.RETURN_RECEIPT_LOOKUP, targets = setOf(saleId)),
            grant(SecurityAction.OPERATION_RESULT_RECOVERY, targets = setOf(startOperationId,
                recordingOperationId, applicationOperationId, finalizeOperationId).map { DomainId(it.value) }.toSet()),
        )
        return LocalAccountProvisioning(actorId, employeeLogin,
            ActivatedLocalPolicy(c.scope, actorId, Revision(0), grants,
                LocalSessionPolicy(maximumConcurrentSessions = 1),
                ThrottlePolicy(3, 1_000, 900_000, 120_000), approvalPolicy = null),
            LocalTokenPolicy(1_800_000, 28_800_000), admittedDeviceIds = setOf(c.deviceId))
    }

    private fun operation(label: String): OperationId = OperationId(id(label).value)
    private fun id(label: String): DomainId {
        val bytes = "cold-original-start-v1/${namespace.value}/$label".encodeToByteArray()
        return try { DomainId(UUID.nameUUIDFromBytes(bytes).toString()) } finally { bytes.fill(0) }
    }
    override fun toString() = "NativeColdOriginalFixturePlan([REDACTED])"
}

internal enum class ColdPreparationCommand {
    INSTALL_SKU, CONFIGURE_WAREHOUSE, CONFIGURE_ROUTE, APPROVE_OPENING,
    INITIALIZE_STOCK, OPENING_STOCK, CONFIGURE_CUSTODIAN, CONFIGURE_DRAWER,
    OPEN_DRAWER, CREATE_ORDER, BEGIN_COLLECTION,
}
