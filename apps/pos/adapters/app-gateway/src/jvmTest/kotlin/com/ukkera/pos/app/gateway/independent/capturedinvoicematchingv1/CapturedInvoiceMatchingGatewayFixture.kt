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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Unchanged CCM v1.0.1 F-QUALITY and binding v1.0.0: raw24000, selected22000, unresolved2000.
 * Public UUID/revision carriers are controlled wiring, not actual authoritative producers.
 * Defaults to the existing memory-only journal; storage tests may inject the production
 * encrypted journal. The bounded scripted port establishes no Master, SQLite, native
 * document store, admitted RPC or business effect.
 */
internal class CapturedInvoiceMatchingGatewayFixture {
    fun id(index: Int): DomainId = DomainId("c1000000-0000-4000-8000-" + index.toString().padStart(12, '0'))
    val scope = BranchScope(id(1), id(2), id(3))
    val actor = id(4)
    val otherActor = id(5)
    val device = id(6)
    val invoice = id(7)
    val invoiceLine = id(8)
    val original = id(9)
    val order = id(10)
    val allocation = id(11)
    val receipt = id(12)
    val receiptLine = id(13)
    val match = id(14)
    val each = id(15)
    private val carton = id(16)
    private val product = id(17)
    private val sku = id(18)
    private val taxSource = id(19)
    private val session = id(20)
    private val connection = id(21)
    private val boot = id(22)
    private val conversion = id(23)
    val source = id(24)
    val operation = OperationId(id(25).value)
    val at = UtcInstant.parseCanonical("2026-11-05T12:00:00Z")
    val zero = Money(Currency.EGP, 0)
    val rawClaim = Money(Currency.EGP, 24_000)
    val recognized = Money(Currency.EGP, 22_000)
    val unresolved = Money(Currency.EGP, 2_000)
    val selectedQuantity = Quantity(each, 22, 0)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val units = PurchaseUnitsV1(product, carton, each, 0, conversion, Revision(1), UnitConversionRate.ratio(12, 1))
    private val tax = PurchaseTaxV1(taxSource, Revision(1), "ZERO", PercentageRate.parse("0"))
    private val price = UnitPrice.parse(Currency.EGP, "120")
    private val discount = PercentageRate.parse("0")
    val capture = LocalPurchasingInvoiceCaptureViewV2(
        id = invoice, revision = Revision(1), supplierId = id(26), status = "UNMATCHED_UNAPPROVED",
        date = BusinessDate.parseCanonical("2026-11-05"), dueDate = BusinessDate.parseCanonical("2026-11-05"),
        internalOrderLink = PurchaseInvoiceOrderLinkV2(order, Revision(1)),
        reference = "00042-QUALITY", supplierOrderReference = null, sourceEvidenceId = original,
        operationId = OperationId(id(27).value), originalActorId = actor, recordedAt = at, policyRevision = Revision(1),
        lines = listOf(LocalPurchasingInvoiceCaptureLineViewV2(
            invoiceLine, PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY, null, null, sku, "G-A", units,
            Quantity(carton, 2, 0), Quantity(each, 24, 0), null, price, discount, tax, rawClaim, zero,
        )), discrepancies = emptyList(), charges = emptyList(), supplierClaim = rawClaim,
    )
    val journal = ControlledInvoiceCaptureJournal(LocalClientIdentity(scope, actor, device))
    val endpoint = CapturedInvoiceMatchingScriptedEndpoint(this)
    private val installed = LocalWorkflowInstallation(setOf(LocalPurchasingProfile.MATCH_CAPTURED_INVOICE),
        setOf(LocalPurchasingProfile.READ_INVOICES, LocalPurchasingProfile.READ_CAPTURED_MATCH_SOURCE))
    val gateway = gateway()

    fun gateway(selectedJournal: LocalClientIntentJournal = journal,
        installation: LocalWorkflowInstallation = installed): LocalEndpointWorkflowGateway =
        LocalEndpointWorkflowGateway(endpoint, selectedJournal, LocalWorkflowCredentialSource {
            LocalWorkflowCredentialLease(LocalGatewayCredentials(connection, OpaqueCredential(ByteArray(16)))) { true }
        }, installation)

    fun view(actorId: DomainId = actor, combined: Boolean = true): LocalSessionView {
        val grants = buildList {
            if (combined) add(PermissionGrant(Permission(SecurityAction.PURCHASING_INVOICE_MATCH_RECOGNIZE,
                PermissionVerb.PERFORM), scope, GrantEffect.ALLOW, PermissionLimits(maximumAmount = rawClaim)))
            for (action in listOf(SecurityAction.PURCHASING_READ, SecurityAction.INVENTORY_QUANTITY_READ,
                    SecurityAction.INVENTORY_COST_READ, SecurityAction.PURCHASING_DOCUMENT_READ)) {
                add(PermissionGrant(Permission(action, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW))
            }
        }
        return LocalSessionView(actorId, scope, device, session, SessionGeneration(1), LocalLoginIdentifier("ccm.fin.a"),
            ActivatedLocalPolicy(scope, actorId, Revision(1), grants, LocalSessionPolicy(), ThrottlePolicy()),
            MonotonicMoment(boot, 1), MonotonicMoment(boot, 2))
    }

    fun intent(quantity: String = "22", operationId: OperationId = operation): LocalCommandIntent = LocalCommandIntent(
        operationId, LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE, 1,
        LocalRecordReference(LocalRecordKind.PURCHASE_INVOICE, invoice), listOf(
            identifier(LocalField.MATCH_ID, match), identifier(LocalField.INVOICE_ID, invoice),
            integer(LocalField.EXPECTED_STORAGE_REVISION, 1),
            LocalScalarInput(LocalField.INVOICE_LINE_ID, LocalScalarValue.Reference(
                LocalRecordReference(LocalRecordKind.PURCHASE_LINE, invoiceLine, parentId = invoice))),
            identifier(LocalField.EVIDENCE_ID, original), integer(LocalField.EXPECTED_SOURCE_REVISION, 1),
            identifier(LocalField.PURCHASE_ID, order), integer(LocalField.EXPECTED_ORDER_REVISION, 1),
            identifier(LocalField.ALLOCATION_ID, allocation), identifier(LocalField.RECEIPT_ID, receipt),
            identifier(LocalField.RECEIPT_LINE_ID, receiptLine), integer(LocalField.EXPECTED_RECEIPT_REVISION, 1),
            LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal(quantity)),
            LocalScalarInput(LocalField.REASON, LocalScalarValue.Text("purchasing.exact_goods")),
        ),
    )

    fun sourceQuote(query: LocalCapturedSupplierInvoiceMatchSourceQueryV2): LocalCapturedSupplierInvoiceMatchSourceV2 =
        LocalCapturedSupplierInvoiceMatchSourceV2(query, LocalCapturedSupplierInvoiceMatchCodesV2.SOURCE_READY,
            LocalPurchasingReadAvailabilityV1.AVAILABLE, true, true,
            Revision(1), Revision(1), Revision(1), Revision(1), null, Revision(1), Revision(1), null, null,
            source, Quantity(each, 24, 0), Quantity(each, 24, 0), selectedQuantity, rawClaim, recognized, zero, null)

    fun result(request: MutationRequest): LocalCapturedSupplierInvoiceMatchResultV2 {
        val submitted = LocalPurchasingCodec.decodeCapturedMatch(request.payload)
        return LocalCapturedSupplierInvoiceMatchResultV2(LocalCapturedSupplierInvoiceMatchCodesV2.SUCCESS,
            submitted.matchId, invoice, Revision(1), rawClaim, Revision(1), Revision(1), Revision(1),
            selectedQuantity, recognized, zero)
    }

    fun recognition(): LocalCapturedSupplierInvoiceRecognitionV2 = LocalCapturedSupplierInvoiceRecognitionV2(
        invoice, Revision(1), "PARTIALLY_MATCHED_RECOGNIZED", original, Revision(1), order, Revision(1),
        Revision(1), Revision(1), listOf(LocalCapturedSupplierInvoiceRecognitionLineV2(
            invoiceLine, allocation, selectedQuantity, recognized, zero)), false,
        recognized, unresolved, recognized, zero, zero, zero, recognized,
    )

    fun invoicePage(query: LocalPurchasingQueryV1, includeRecognition: Boolean): LocalPurchasingInvoicePageV2 {
        check(query.section == LocalPurchasingSectionV1.INVOICE && (query.id == null || query.id == invoice))
        val costs = query.includeCosts
        val detailed = query.id != null
        val original = capture.copy(
            lines = if (!detailed) emptyList() else capture.lines.map { line -> line.copy(
                unitPrice = line.unitPrice.takeIf { costs }, discount = line.discount.takeIf { costs },
                purchaseTax = line.purchaseTax.takeIf { costs }, claimedNet = line.claimedNet.takeIf { costs },
                claimedTax = line.claimedTax.takeIf { costs },
            ) },
            charges = capture.charges.takeIf { costs }, supplierClaim = capture.supplierClaim.takeIf { costs },
        )
        val overlay = if (!includeRecognition) null else recognition().let { value -> value.copy(
            lines = if (!detailed) emptyList() else value.lines.map { line -> line.copy(
                matchedNet = line.matchedNet.takeIf { costs }, matchedTax = line.matchedTax.takeIf { costs },
            ) },
            recognizedPayable = value.recognizedPayable.takeIf { costs }, unmatchedClaim = value.unmatchedClaim.takeIf { costs },
            remainingDue = value.remainingDue.takeIf { costs }, appliedCredit = value.appliedCredit.takeIf { costs },
            allocatedPayment = value.allocatedPayment.takeIf { costs }, reservedSupplierCash = value.reservedSupplierCash.takeIf { costs },
            availableToAllocate = value.availableToAllocate.takeIf { costs },
        ) }
        return LocalPurchasingInvoicePageV2(listOf(LocalPurchasingInvoiceDocumentV2(capture = original,
            recognition = overlay)), null, costs)
    }

    fun <T> data(value: T, serializer: KSerializer<T>): ReadReply.Data =
        ReadReply.Data(StoredBytes(json.encodeToString(serializer, value).encodeToByteArray()), Revision(1), at)

    private fun identifier(field: LocalField, id: DomainId): LocalScalarInput = LocalScalarInput(field, LocalScalarValue.Identifier(id))
    private fun integer(field: LocalField, value: Long): LocalScalarInput = LocalScalarInput(field, LocalScalarValue.Integer(value))
}

/** Bounded scripted callbacks only; these outcomes are not production Master evidence. */
internal class CapturedInvoiceMatchingScriptedEndpoint(private val fixture: CapturedInvoiceMatchingGatewayFixture) : LocalMasterClient {
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("Endpoint health is outside this supporting slice")
    var currentReply: LocalSessionViewResult? = null
    var rejectReads = false
    var loseNextReply = false
    var includeRecognition = false
    var found: MutationReply.Recorded? = null
    var resultOverride: LocalCapturedSupplierInvoiceMatchResultV2? = null
    var rawResultOverride: StoredBytes? = null
    val reads = mutableListOf<ReadRequest>()
    val readReplies = mutableListOf<ReadReply.Data>()
    val executions = mutableListOf<MutationRequest>()
    val lookups = mutableListOf<OperationIdentity>()

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        currentReply ?: LocalSessionViewResult.Current(fixture.view())

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        check(!rejectReads) { "Original retry must not rebuild source selection" }
        return when (request.query) {
            LocalPurchasingProfile.READ_INVOICES -> {
                val query = Json.decodeFromString(LocalPurchasingQueryV1.serializer(), request.payload.copyBytes().decodeToString())
                fixture.data(fixture.invoicePage(query, includeRecognition), LocalPurchasingInvoicePageV2.serializer())
            }
            LocalPurchasingProfile.READ_CAPTURED_MATCH_SOURCE -> {
                val query = Json.decodeFromString(LocalCapturedSupplierInvoiceMatchSourceQueryV2.serializer(), request.payload.copyBytes().decodeToString())
                fixture.data(fixture.sourceQuote(query), LocalCapturedSupplierInvoiceMatchSourceV2.serializer())
            }
            else -> error("Read is outside the bounded matching harness")
        }.also { readReplies += it }
    }

    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executions += request
        check(request.command == LocalPurchasingProfile.MATCH_CAPTURED_INVOICE)
        val value = resultOverride ?: fixture.result(request)
        val reply = MutationReply.Recorded(request.operationId, fixture.actor, true, "committed",
            rawResultOverride ?: LocalPurchasingCodec.capturedMatchResult(value), Revision(1), JournalSequence(1), fixture.at, false)
        found = reply
        if (loseNextReply) {
            loseNextReply = false
            return MutationReply.Deferred(request.operationId, DeferredReason.STORAGE_UNCERTAIN)
        }
        return reply
    }

    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        val reply = found ?: return LookupReply.AbsentAtCurrentWriter(identity.operationId)
        check(reply.operationId == identity.operationId && reply.originalActorId == identity.originalActorId)
        return LookupReply.Found(MutationReply.Recorded(reply.operationId, reply.originalActorId, reply.committed,
            reply.code, reply.result, reply.authorityEpoch, reply.sequence, reply.recordedAt, true))
    }

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("Authentication is outside this supporting slice")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("Refresh is outside this supporting slice")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("Logout is outside this supporting slice")
}
