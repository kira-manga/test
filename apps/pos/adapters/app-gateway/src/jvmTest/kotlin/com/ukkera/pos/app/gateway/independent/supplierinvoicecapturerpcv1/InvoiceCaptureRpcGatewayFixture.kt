package com.ukkera.pos.app.gateway.independent.supplierinvoicecapturerpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.supplierinvoicecapturev2.ControlledInvoiceCaptureJournal
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

/** Frozen ICR v1 facts, independently specified before this controlled-port wiring. */
internal object IcrInputs {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private const val directory = "qa/pos/designs/supplier-invoice-capture-rpc-v1"
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { Files.isRegularFile(it.resolve(directory).resolve("fixtures.json")) }
        ?: error("Frozen ICR v1 fixtures are unavailable")
    val fixtures = json.parseToJsonElement(Files.readString(root.resolve(directory).resolve("fixtures.json"))).jsonObject
    val identities = fixtures.getValue("identities").jsonObject
    val claim = fixtures.getValue("claimBase").jsonObject
    val lines = claim.getValue("lines").jsonArray.map { it.jsonObject }
    val order = fixtures.getValue("purchaseOrders").jsonArray.first().jsonObject
    val supplier = fixtures.getValue("suppliers").jsonArray.first().jsonObject
    init { check(fixtures.getValue("designVersion").jsonPrimitive.int == 1) }

    fun text(value: JsonObject, key: String): String = value.getValue(key).jsonPrimitive.content
    fun identity(key: String): DomainId = DomainId(text(identities, key))
    fun operation(key: String): OperationId = OperationId(text(
        fixtures.getValue("claims").jsonObject.getValue(key).jsonObject, "operationId"))
    fun sku(key: String): DomainId = DomainId(text(fixtures.getValue("goods").jsonArray
        .map { it.jsonObject }.single { text(it, "key") == key }, "id"))
    fun quantityScale(key: String): Int = fixtures.getValue("goods").jsonArray
        .map { it.jsonObject }.single { text(it, "key") == key }.getValue("quantityScale").jsonPrimitive.int
    fun money(minor: String): String = minor.toLong().let { (it / 100).toString() + "." + (it % 100).toString().padStart(2, '0') }
    fun rate(line: JsonObject): String = line.getValue("purchaseToBase").jsonObject.let {
        text(it, "numerator") + "/" + text(it, "denominator")
    }
    fun unit(name: String): DomainId = when (name) {
        "each" -> mappedId(1)
        "carton" -> mappedId(2)
        "kg" -> mappedId(3)
        else -> error("Unmapped frozen unit")
    }
    // The specification supplies logical unit/product/evidence names, not these public UUID carriers.
    fun mappedId(number: Int): DomainId = DomainId("a1000000-0000-4000-8000-" + number.toString().padStart(12, '0'))
}

/**
 * Production gateway over scripted Current/read/result callbacks and the existing memory journal.
 * No authentication, Master authority, F0 storage, N0/N1 effects, native custody or RPC is established.
 */
internal class InvoiceCaptureRpcGatewayFixture {
    val scope = BranchScope(IcrInputs.identity("organization"), IcrInputs.identity("companyA"), IcrInputs.identity("branchA"))
    val actor = IcrInputs.identity("employeeA")
    val device = IcrInputs.identity("deviceA")
    val supplier = DomainId(IcrInputs.text(IcrInputs.supplier, "id"))
    val order = DomainId(IcrInputs.text(IcrInputs.order, "id"))
    val allocation = DomainId(IcrInputs.text(IcrInputs.order.getValue("ownAllocation").jsonObject, "id"))
    val invoice = DomainId.random() // R-original's request coordinate is captured once per reset.
    val at = UtcInstant.parseCanonical(IcrInputs.text(IcrInputs.claim, "invoiceDate") + "T12:00:00Z")
    val journal = ControlledInvoiceCaptureJournal(LocalClientIdentity(scope, actor, device))
    val endpoint = IcrControlledEndpoint(this)
    private val connection = IcrInputs.mappedId(90)
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(connection, OpaqueCredential(ByteArray(52) { it.toByte() }))) { true }
    }, LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE))

    fun current(): LocalSessionView = LocalSessionView(actor, scope, device, IcrInputs.identity("originalSessionA"),
        SessionGeneration(1), LocalLoginIdentifier("Synthetic ICR owner"),
        ActivatedLocalPolicy(scope, actor, Revision(IcrInputs.text(IcrInputs.identities, "activatedPolicyRevision").toLong()),
            listOf(PermissionGrant(Permission(SecurityAction.PURCHASING_INVOICE_CAPTURE, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW),
                PermissionGrant(Permission(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW),
                PermissionGrant(Permission(SecurityAction.PURCHASING_APPROVE, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW,
                    PermissionLimits(maximumAmount = Money(Currency.EGP, 100_000)))),
            LocalSessionPolicy(), ThrottlePolicy()),
        MonotonicMoment(IcrInputs.mappedId(91), 1), MonotonicMoment(IcrInputs.mappedId(91), 2))

    fun intent(key: String = "C0"): LocalCommandIntent {
        val linked = key == "C1"
        val values = buildList {
            add(LocalScalarInput(LocalField.INVOICE_ID, LocalScalarValue.Identifier(invoice)))
            add(LocalScalarInput(LocalField.SUPPLIER_ID, if (linked) LocalScalarValue.Identifier(supplier)
                else LocalScalarValue.Reference(LocalRecordReference(LocalRecordKind.SUPPLIER, supplier, Revision(1)))))
            if (linked) {
                add(LocalScalarInput(LocalField.PURCHASE_ID, LocalScalarValue.Identifier(order)))
                add(LocalScalarInput(LocalField.EXPECTED_ORDER_REVISION,
                    LocalScalarValue.Integer(IcrInputs.text(IcrInputs.order, "revision").toLong())))
            }
            add(LocalScalarInput(LocalField.EXTERNAL_REFERENCE, LocalScalarValue.Text(IcrInputs.text(IcrInputs.claim, "invoiceReferenceLiteral"))))
            add(LocalScalarInput(LocalField.SUPPLIER_ORDER_REFERENCE, LocalScalarValue.Text(IcrInputs.text(IcrInputs.claim, "supplierPoReferenceLiteral"))))
            add(LocalScalarInput(LocalField.BUSINESS_DATE, LocalScalarValue.Date(IcrInputs.text(IcrInputs.claim, "invoiceDate"))))
            add(LocalScalarInput(LocalField.DUE_DATE, LocalScalarValue.Date(IcrInputs.text(IcrInputs.claim, "invoiceDate"))))
            add(LocalScalarInput(LocalField.EVIDENCE_ID, LocalScalarValue.Identifier(IcrInputs.mappedId(92))))
            add(LocalScalarInput(LocalField.REASON, LocalScalarValue.Text("supplier.invoice.capture")))
        }
        val lines = IcrInputs.lines.mapIndexed { index, line ->
            val purchaseUnit = IcrInputs.text(line, "purchaseUnit")
            val baseUnit = IcrInputs.text(line, "baseUnit")
            val scale = IcrInputs.quantityScale(IcrInputs.text(line, "sku"))
            val input = buildList {
                add(LocalScalarInput(LocalField.MODE, LocalScalarValue.Choice(if (linked && index == 0)
                    "DOCUMENTARY_ORDER_LINE:" + allocation.value else "DOCUMENTARY_GOODS")))
                add(LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Identifier(IcrInputs.sku(IcrInputs.text(line, "sku")))))
                add(LocalScalarInput(LocalField.PRODUCT_ID, LocalScalarValue.Identifier(IcrInputs.mappedId(10 + index))))
                add(LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(IcrInputs.unit(purchaseUnit))))
                add(LocalScalarInput(LocalField.PURCHASE_BASE_UNIT, LocalScalarValue.Identifier(IcrInputs.unit(baseUnit))))
                add(LocalScalarInput(LocalField.PURCHASE_BASE_SCALE, LocalScalarValue.Integer(scale.toLong())))
                if (purchaseUnit != baseUnit) {
                    add(LocalScalarInput(LocalField.CONVERSION_ID, LocalScalarValue.Identifier(IcrInputs.mappedId(20 + index))))
                    add(LocalScalarInput(LocalField.PURCHASE_CONVERSION_REVISION, LocalScalarValue.Integer(IcrInputs.text(line, "unitRevision").toLong())))
                }
                add(LocalScalarInput(LocalField.PURCHASE_CONVERSION_RATE, LocalScalarValue.Text(IcrInputs.rate(line))))
                add(LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(IcrInputs.text(line, "purchaseQuantity"))))
                add(LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(scale.toLong())))
                add(LocalScalarInput(LocalField.SUPPLIER_ITEM_REFERENCE, LocalScalarValue.Text(IcrInputs.text(line, "supplierItemReferenceLiteral"))))
                add(LocalScalarInput(LocalField.UNIT_PRICE, LocalScalarValue.Decimal(IcrInputs.text(line, "unitPrice"))))
                add(LocalScalarInput(LocalField.DISCOUNT_PERCENT, LocalScalarValue.Decimal("0")))
                add(LocalScalarInput(LocalField.TAX_SOURCE_ID, LocalScalarValue.Identifier(IcrInputs.mappedId(93))))
                add(LocalScalarInput(LocalField.TAX_REVISION, LocalScalarValue.Integer(1)))
                add(LocalScalarInput(LocalField.TAX_CATEGORY, LocalScalarValue.Text("SYNTHETIC_ZERO")))
                add(LocalScalarInput(LocalField.TAX_RATE, LocalScalarValue.Decimal("0")))
                add(LocalScalarInput(LocalField.NET_AMOUNT, LocalScalarValue.Decimal(IcrInputs.money(IcrInputs.text(line, "netMinor")))))
                add(LocalScalarInput(LocalField.TAX_AMOUNT, LocalScalarValue.Decimal(IcrInputs.money(IcrInputs.text(line, "taxMinor")))))
            }
            LocalInputLine(IcrInputs.mappedId(30 + index), input)
        }
        return LocalCommandIntent(IcrInputs.operation(key), LocalAction.SUPPLIER_INVOICE_CAPTURE, 1,
            LocalRecordReference(LocalRecordKind.PURCHASE, order).takeIf { linked }, values, LocalField.LINES, lines)
    }

    fun supplierReply(): ReadReply.Data {
        val names = LocalNamesV1(IcrInputs.text(IcrInputs.supplier, "nameEn"), IcrInputs.text(IcrInputs.supplier, "nameAr"))
        return data(LocalConfigurationPageV1(LocalConfigurationProfile.SUPPLIER,
            listOf(LocalConfigurationListItemV1(supplier, Revision(1), Revision(2), names, true, null)), null,
            detail = LocalConfigurationDetailV1.Supplier(LocalSupplierV1(supplier, names, true))), LocalConfigurationPageV1.serializer())
    }

    fun orderReply(request: ReadRequest): ReadReply.Data {
        val query = LocalPurchasingCodec.decodeQuery(request.payload)
        val line = IcrInputs.order.getValue("lines").jsonArray.first().jsonObject
        val units = PurchaseUnitsV1(IcrInputs.mappedId(10), IcrInputs.unit(IcrInputs.text(line, "purchaseUnit")),
            IcrInputs.unit(IcrInputs.text(line, "baseUnit")), 0, IcrInputs.mappedId(21),
            Revision(IcrInputs.text(line, "unitRevision").toLong()), UnitConversionRate.parseCanonical(IcrInputs.rate(line)))
        val zero = Quantity(units.baseUnitId, 0, 0)
        val costs = query.includeCosts
        val detail = LocalPurchasingLineViewV1(IcrInputs.mappedId(40), allocation, IcrInputs.sku(IcrInputs.text(line, "sku")),
            null, IcrInputs.mappedId(41), units, Quantity.parse(units.purchaseUnitId, IcrInputs.text(line, "purchaseQuantity"), 0),
            Quantity.parse(units.baseUnitId, IcrInputs.text(line, "baseQuantity"), 0), zero, zero, zero, zero,
            UnitPrice.parse(Currency.EGP, IcrInputs.text(line, "unitPrice")).takeIf { costs }, PercentageRate.parse("0").takeIf { costs },
            PurchaseTaxV1(IcrInputs.mappedId(93), Revision(1), "SYNTHETIC_ZERO", PercentageRate.parse("0")).takeIf { costs },
            Money(Currency.EGP, IcrInputs.text(line, "netMinor").toLong()).takeIf { costs }, Money(Currency.EGP, 0).takeIf { costs }, null, null)
        val document = LocalPurchasingDocumentViewV1(order, Revision(IcrInputs.text(IcrInputs.order, "revision").toLong()),
            supplier, order, "ORDER_APPROVED", BusinessDate.parseCanonical(IcrInputs.text(IcrInputs.claim, "invoiceDate")),
            null, null, null, listOf(detail), emptyList(), IcrInputs.text(IcrInputs.order, "internalReference").takeIf { costs },
            null, null, emptyList<PurchaseChargeV1>().takeIf { costs },
            Money(Currency.EGP, IcrInputs.text(IcrInputs.order, "totalMinor").toLong()).takeIf { costs }, null, null, null)
        return data(LocalPurchasingPageV1(LocalPurchasingSectionV1.ORDER, listOf(document), null, costs), LocalPurchasingPageV1.serializer())
    }

    private fun <T> data(value: T, serializer: KSerializer<T>): ReadReply.Data =
        ReadReply.Data(StoredBytes(IcrInputs.json.encodeToString(serializer, value).encodeToByteArray()),
            Revision(IcrInputs.text(IcrInputs.identities, "branchRevision").toLong()), at)
}

/** Scripted replies and call observations only; constructor/DTO shape is not result authority. */
internal class IcrControlledEndpoint(private val fixture: InvoiceCaptureRpcGatewayFixture) : LocalMasterClient {
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("No endpoint health fixture")
    val reads = mutableListOf<ReadRequest>()
    val executions = mutableListOf<MutationRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    var refuseReads = false
    var loseReply = false
    var found: MutationReply.Recorded? = null
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        LocalSessionViewResult.Current(fixture.current())
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        check(!refuseReads) { "Saved-original lookup must not re-read PO/catalog" }
        reads += request
        if (request.query == LocalConfigurationProfile.QUERY) {
            val query = LocalConfigurationCodec.decodeQuery(request.payload)
            if (query.kind == LocalConfigurationProfile.SUPPLIER && query.id == fixture.supplier) return fixture.supplierReply()
        }
        if (request.query == LocalPurchasingProfile.READ && LocalPurchasingCodec.decodeQuery(request.payload).id == fixture.order)
            return fixture.orderReply(request)
        // No invented empty F0 invoice/directory success. These probes can observe the request only.
        return ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executions += request
        val value = LocalPurchasingCodec.decodeCaptureInvoice(request.payload)
        val reply = MutationReply.Recorded(request.operationId, fixture.actor, true, "committed",
            LocalPurchasingCodec.captureResult(LocalInvoiceCaptureResultV2("purchasing.invoice_captured", value.invoice.id, Revision(1))),
            Revision(1), JournalSequence(1), fixture.at, false)
        found = reply
        return if (loseReply) MutationReply.Deferred(request.operationId, DeferredReason.STORAGE_UNCERTAIN) else reply
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        // Scripted initial absence permits the explicit submit probe only, not qualified Master absence.
        val reply = found ?: return LookupReply.AbsentAtCurrentWriter(identity.operationId)
        check(reply.operationId == identity.operationId && reply.originalActorId == identity.originalActorId)
        return LookupReply.Found(MutationReply.Recorded(reply.operationId, reply.originalActorId, reply.committed,
            reply.code, reply.result, reply.authorityEpoch, reply.sequence, reply.recordedAt, true))
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No authentication fixture")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No refresh fixture")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No logout fixture")
}
