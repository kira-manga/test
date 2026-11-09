package com.ukkera.pos.app.gateway.independent.capturedinvoicematchingv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.supplierinvoicecapturev2.ControlledInvoiceCaptureJournal
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json

internal enum class ScdDisplayFixture { SCD_F1, SCD_F2, SCD_F3 }

/**
 * Frozen SCD v1.0.0 inputs, separate from the unchanged CCM F-QUALITY fixture.
 * Positive revisions and extra catalog/session fields are controlled wire carriers,
 * not actual qualified sources or authoritative heads. The port is readonly and
 * the reused journal is memory-only. No original bytes, Master or ledger is owned.
 * Source handoff status: NOT RUN.
 */
internal class SupplierCapturedMatchDisplayGatewayFixture(val fixtureId: ScdDisplayFixture) {
    fun id(index: Int): DomainId = DomainId("00000000-0000-4000-8000-" + index.toString().padStart(12, '0'))
    val scope = BranchScope(id(100), id(101), id(102))
    val supplier = id(103)
    val invoice = id(1)
    val original = id(2)
    val order = id(3)
    val pricedAllocation = id(4)
    val freeAllocation = id(14)
    val receipt = id(5)
    val pricedLine = id(6)
    val freeLine = id(7)
    val pricedReceiptLine = id(8)
    val freeReceiptLine = id(9)
    val each = id(200)
    val actor = id(201)
    private val device = id(202)
    private val warehouse = id(207)
    private val taxSource = id(208)
    private val session = id(209)
    private val connection = id(210)
    private val boot = id(211)
    private val at = UtcInstant.parseCanonical("2026-11-05T12:00:00Z")
    private val date = BusinessDate.parseCanonical("2026-11-05")
    private val zero = Money(Currency.EGP, 0)
    private val goodsNet = Money(Currency.EGP, 50_000)
    private val goodsTax = Money(Currency.EGP, 7_000)
    private val goodsTotal = Money(Currency.EGP, 57_000)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    val capture = LocalPurchasingInvoiceCaptureViewV2(
        id = invoice, revision = Revision(1), supplierId = supplier, status = "UNMATCHED_UNAPPROVED",
        date = date, dueDate = date, internalOrderLink = PurchaseInvoiceOrderLinkV2(order, Revision(1)),
        reference = when (fixtureId) {
            ScdDisplayFixture.SCD_F1 -> "SCD-POSITIVE-001"
            ScdDisplayFixture.SCD_F2 -> "SCD-ZERO-CHARGE-001"
            ScdDisplayFixture.SCD_F3 -> "SCD-FREE-PARTIAL-001"
        },
        supplierOrderReference = null, sourceEvidenceId = original,
        operationId = OperationId(id(212).value), originalActorId = actor, recordedAt = at, policyRevision = Revision(1),
        lines = buildList {
            add(line(pricedLine, pricedAllocation, id(203), id(205), "priced", 5, "100", goodsNet, goodsTax))
            if (fixtureId == ScdDisplayFixture.SCD_F3)
                add(line(freeLine, freeAllocation, id(204), id(206), "free", 3, "0", zero, zero))
        },
        discrepancies = emptyList(),
        charges = when (fixtureId) {
            ScdDisplayFixture.SCD_F1 -> listOf(charge(21, "freight", Money(Currency.EGP, 5_000)))
            ScdDisplayFixture.SCD_F2 -> listOf(charge(22, "handling", zero))
            ScdDisplayFixture.SCD_F3 -> emptyList()
        },
        supplierClaim = if (fixtureId == ScdDisplayFixture.SCD_F1) Money(Currency.EGP, 62_000) else goodsTotal,
    )

    val recognition = LocalCapturedSupplierInvoiceRecognitionV2(
        invoiceId = invoice, invoiceRevision = Revision(1),
        status = if (fixtureId == ScdDisplayFixture.SCD_F3) "PARTIALLY_MATCHED_RECOGNIZED" else "MATCHED_RECOGNIZED",
        originalDocumentId = original, originalRevision = Revision(1), orderId = order, orderRevision = Revision(1),
        progressRevision = Revision(1), payableRevision = Revision(1),
        lines = buildList {
            add(LocalCapturedSupplierInvoiceRecognitionLineV2(pricedLine, pricedAllocation, Quantity(each, 5, 0), goodsNet, goodsTax))
            if (fixtureId == ScdDisplayFixture.SCD_F3)
                add(LocalCapturedSupplierInvoiceRecognitionLineV2(freeLine, freeAllocation, Quantity(each, 1, 0), zero, zero))
        },
        settlementEligible = false, recognizedPayable = goodsTotal,
        unmatchedClaim = if (fixtureId == ScdDisplayFixture.SCD_F1) Money(Currency.EGP, 5_000) else zero,
        remainingDue = goodsTotal, appliedCredit = zero, allocatedPayment = zero,
        reservedSupplierCash = zero, availableToAllocate = goodsTotal,
    )

    val installation = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.INVOICE_MATCHING)
    val journal = ControlledInvoiceCaptureJournal(LocalClientIdentity(scope, actor, device))
    val endpoint = SupplierCapturedMatchDisplayScriptedEndpoint(this)
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource { lease() }, installation)

    fun lease(): LocalWorkflowCredentialLease = LocalWorkflowCredentialLease(
        LocalGatewayCredentials(connection, OpaqueCredential(ByteArray(16))), { true })

    /** Current-view hints only; this fixture does not implement Master grant enforcement. */
    fun view(costs: Boolean = true): LocalSessionView {
        val actions = listOf(SecurityAction.PURCHASING_READ, SecurityAction.INVENTORY_QUANTITY_READ,
            SecurityAction.PURCHASING_DOCUMENT_READ) + if (costs) listOf(SecurityAction.INVENTORY_COST_READ) else emptyList()
        val grants = actions.map {
            PermissionGrant(Permission(it, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW)
        }
        return LocalSessionView(actor, scope, device, session, SessionGeneration(1), LocalLoginIdentifier("scd.reader"),
            ActivatedLocalPolicy(scope, actor, Revision(1), grants, LocalSessionPolicy(), ThrottlePolicy()),
            MonotonicMoment(boot, 1), MonotonicMoment(boot, 2))
    }

    fun query(costs: Boolean): LocalPurchasingQueryV1 =
        LocalPurchasingQueryV1(LocalPurchasingSectionV1.INVOICE, id = invoice, includeCosts = costs)

    fun uiQuery(costs: Boolean): LocalWorkflowQuery = LocalWorkflowQuery(LocalWorkflow.PURCHASING,
        reference = LocalRecordReference(LocalRecordKind.PURCHASE_INVOICE, invoice),
        purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.INVOICES, includeCosts = costs))

    fun sourceQuery(free: Boolean = false): LocalCapturedSupplierInvoiceMatchSourceQueryV2 {
        check(!free || fixtureId == ScdDisplayFixture.SCD_F3)
        return LocalCapturedSupplierInvoiceMatchSourceQueryV2(invoice, if (free) freeLine else pricedLine, Revision(1),
            original, Revision(1), order, Revision(1), if (free) freeAllocation else pricedAllocation,
            receipt, if (free) freeReceiptLine else pricedReceiptLine, Revision(1), Quantity(each, if (free) 1L else 5L, 0))
    }

    /** Scripted projection redaction happens before encoding, never in a hidden UI widget. */
    fun invoicePage(query: LocalPurchasingQueryV1): LocalPurchasingInvoicePageV2 {
        check(query.section == LocalPurchasingSectionV1.INVOICE && query.id == invoice && !query.export)
        val costs = query.includeCosts
        val raw = capture.copy(lines = capture.lines.map { line -> line.copy(
            unitPrice = line.unitPrice.takeIf { costs }, discount = line.discount.takeIf { costs },
            purchaseTax = line.purchaseTax.takeIf { costs }, claimedNet = line.claimedNet.takeIf { costs },
            claimedTax = line.claimedTax.takeIf { costs },
            originalOrderLine = line.originalOrderLine?.let { original -> original.copy(
                unitPrice = original.unitPrice.takeIf { costs }, discount = original.discount.takeIf { costs },
                purchaseTax = original.purchaseTax.takeIf { costs }, net = original.net.takeIf { costs },
                taxAmount = original.taxAmount.takeIf { costs },
            ) },
        ) }, charges = capture.charges.takeIf { costs }, supplierClaim = capture.supplierClaim.takeIf { costs })
        val overlay = recognition.copy(lines = recognition.lines.map { line -> line.copy(
            matchedNet = line.matchedNet.takeIf { costs }, matchedTax = line.matchedTax.takeIf { costs },
        ) }, recognizedPayable = recognition.recognizedPayable.takeIf { costs },
            unmatchedClaim = recognition.unmatchedClaim.takeIf { costs }, remainingDue = recognition.remainingDue.takeIf { costs },
            appliedCredit = recognition.appliedCredit.takeIf { costs }, allocatedPayment = recognition.allocatedPayment.takeIf { costs },
            reservedSupplierCash = recognition.reservedSupplierCash.takeIf { costs },
            availableToAllocate = recognition.availableToAllocate.takeIf { costs })
        return LocalPurchasingInvoicePageV2(listOf(LocalPurchasingInvoiceDocumentV2(capture = raw, recognition = overlay)), null, costs)
    }

    fun data(page: LocalPurchasingInvoicePageV2): ReadReply.Data = ReadReply.Data(
        StoredBytes(json.encodeToString(LocalPurchasingInvoicePageV2.serializer(), page).encodeToByteArray()), Revision(1), at)

    private fun line(lineId: DomainId, allocationId: DomainId, skuId: DomainId, productId: DomainId,
        reference: String, quantity: Long, price: String, net: Money, taxAmount: Money): LocalPurchasingInvoiceCaptureLineViewV2 {
        val units = PurchaseUnitsV1(productId, each, each, 0, null, null, UnitConversionRate.ratio(1, 1))
        val amount = Quantity(each, quantity, 0)
        val unitPrice = UnitPrice.parse(Currency.EGP, price)
        val discount = PercentageRate.parse("0")
        val tax = PurchaseTaxV1(taxSource, Revision(1), "ILLUSTRATIVE_14", PercentageRate.parse("14"))
        return LocalPurchasingInvoiceCaptureLineViewV2(lineId, PurchaseInvoiceEntryProvenanceV2.SELECTED_ORDER_LINE,
            allocationId, null, skuId, reference, units, amount, amount,
            LocalPurchasingInvoiceOriginalLineV2(allocationId, skuId, warehouse, units, amount, amount,
                unitPrice, discount, tax, net, taxAmount), unitPrice, discount, tax, net, taxAmount)
    }

    private fun charge(index: Int, label: String, net: Money): PurchaseChargeV1 = PurchaseChargeV1(
        id(index), label, original, PurchaseChargeTreatmentV1.ACQUISITION_COST_PENDING_ALLOCATION, net, zero)
}

/** Bounded wire replies only: source denial is scripted, not a security-service substitute. */
internal class SupplierCapturedMatchDisplayScriptedEndpoint(private val fixture: SupplierCapturedMatchDisplayGatewayFixture) : LocalMasterClient {
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("Endpoint health is outside this supporting slice")
    var currentReply: LocalSessionViewResult? = null
    var pageOverride: LocalPurchasingInvoicePageV2? = null
    val reads = mutableListOf<ReadRequest>()
    val readReplies = mutableListOf<ReadReply.Data>()
    val executions = mutableListOf<MutationRequest>()
    val lookups = mutableListOf<OperationIdentity>()

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        currentReply ?: LocalSessionViewResult.Current(fixture.view())

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        return when (request.query) {
            LocalPurchasingProfile.READ_INVOICES -> fixture.data(pageOverride ?: fixture.invoicePage(
                LocalPurchasingCodec.decodeQuery(request.payload))).also { readReplies += it }
            LocalPurchasingProfile.READ_CAPTURED_MATCH_SOURCE -> ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
            else -> error("Read is outside the readonly SCD harness")
        }
    }

    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executions += request
        error("Mutations are outside the readonly SCD harness")
    }

    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        error("Operation lookup is outside the readonly SCD harness")
    }

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("Authentication is outside this supporting slice")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("Refresh is outside this supporting slice")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("Logout is outside this supporting slice")
}
