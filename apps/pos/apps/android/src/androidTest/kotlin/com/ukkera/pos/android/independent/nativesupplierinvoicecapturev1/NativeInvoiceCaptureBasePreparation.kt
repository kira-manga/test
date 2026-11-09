package com.ukkera.pos.android.independent.nativesupplierinvoicecapturev1

import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureEvidence
import com.ukkera.pos.android.independent.nativefixture.nativeFixtureSecret
import com.ukkera.pos.android.independent.nativefixture.nativeFixtureValue
import com.ukkera.pos.android.independent.nativefixture.requireNativeFixturePolicy
import com.ukkera.pos.android.independent.nativefixture.requireSameNativeFixtureCoordinates
import com.ukkera.pos.catalog.CatalogUnit
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.util.UUID
import kotlin.time.Clock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Author only frozen SIDC BASE, through the original production endpoint/native ceremonies.
 * Economics and absence assertions are from supplier-invoice-discrepancy-capture-v1 BASE;
 * runtime identities are permitted bindings, not revisions inferred from fixture labels.
 * This is not a case, an alternate journal, a native owner or a passing execution record.
 * One owning coroutine invokes prepare once. A lost result never permits a replacement setup.
 * observeExisting performs fresh employee authentication, protected reads and actual logout only. */
internal class NativeInvoiceCaptureBasePreparation(
    private val original: AndroidOriginalLocalClient,
    private val headLogin: String,
    private val evidence: NativeLocalFixtureEvidence,
) {
    private val inputs = NativeInvoiceCaptureBaseInputs(original.material.coordinates)
    private var preparationAttempted = false
    private var authenticationUnconfirmed = false
    private var logoutUnconfirmed = false
    private var tokens: LocalSessionTokens? = null
    private var pending: NativeInvoiceCaptureBaseAttempt? = null
    private val attempts = mutableListOf<NativeInvoiceCaptureBaseAttempt>()
    private val operations = mutableListOf<NativeInvoiceCaptureBaseOperation>()
    private val publications = mutableListOf<NativeInvoiceCaptureBasePublication>()

    suspend fun prepare(headSecret: CharArray, fixtureActorPassword: CharArray): NativeInvoiceCaptureBase {
        check(!preparationAttempted && original.isCurrent() && original.peer.isCurrent())
        preparationAttempted = true
        check(inputs.actorId != original.material.coordinates.firstActorId)
        evidence.bind("SIDC_BASE_NAMESPACE", inputs.namespace.value)
        evidence.bind("SIDC_BASE_REQUESTED_IDENTITIES", JSONObject()
            .put("fixtureActorId", inputs.actorId.value).put("supplierId", inputs.supplier.id.value)
            .put("warehouseId", inputs.warehouse.id.value).put("poId", inputs.orderId.value)
            .put("poAllocationId", inputs.orderAllocationId.value).put("openingReceiptId", inputs.openingReceiptId.value)
            .put("drawerId", inputs.drawerId.value).put("cashSessionId", inputs.cashSessionId.value))

        // A company-head-approved, native-retained full employee definition, not DAO provisioning.
        val action = nativeFixtureValue(original.prepareEmployeeEnrollment(
            inputs.enrollmentRequestId, inputs.account, credentialWasProvided = true))
        check(action.requested.request.requestId == inputs.enrollmentRequestId &&
            action.requested.request.targetId == inputs.actorId && action.requested.credentialWasProvided)
        requireAccount(inputs.account, action.requested.definition)
        evidence.bind("SIDC_BASE_EMPLOYEE_ENROLLMENT", JSONObject()
            .put("requestId", inputs.enrollmentRequestId.value).put("originalActionId", action.originalActionId.value)
            .put("definitionDigest", action.requested.definitionDigest.value))
        val enrollment = nativeFixtureSecret(headSecret) {
            nativeFixtureValue(original.approveEmployeeEnrollment(action, headLogin, it))
        }
        check(enrollment.belongsTo(original) && !enrollment.isLookupOnly &&
            enrollment.originalActionId == action.originalActionId &&
            enrollment.originalRequestId == inputs.enrollmentRequestId &&
            enrollment.requested.definitionDigest == action.requested.definitionDigest)
        requireAccount(inputs.account, enrollment.requested.definition)
        val provisioned = nativeFixtureSecret(fixtureActorPassword) {
            enrollment.provisionAccount(inputs.enrollmentRequestId, inputs.account, it)
        } as? LocalCredentialAdministrationResult.Applied
            ?: error("The genuine BASE employee enrollment did not settle")
        val retained = enrollment.lookupAccountProvisioning(inputs.enrollmentRequestId,
            inputs.account, credentialWasProvided = true) as? LocalProvisioningStatus.Applied
            ?: error("The actual BASE employee original is unavailable")
        check(retained.eventId == provisioned.eventId)
        val employee = NativeInvoiceCaptureBaseEnrollment(inputs.enrollmentRequestId,
            action.originalActionId, action.requested.definitionDigest, provisioned.eventId)
        evidence.bind("SIDC_BASE_EMPLOYEE_APPLIED_EVENT", provisioned.eventId.value)

        val nativePublications = original.originalBusinessPublications()
            ?: error("Original native company publications are unavailable")
        check(nativePublications.trust.company == inputs.coordinates.scope.company())
        val signed = inputs.definitions.map { definition ->
            val canonical = LocalConfigurationCodec.canonicalPublication(definition)
            val proposal = try {
                CompanyBusinessPublicationProposal(definition.company, CompanyBusinessPublicationKind.CATALOG_SKU_V1,
                    definition.publicationId, definition.skuId, canonical)
            } finally { canonical.fill(0) }
            val prepared = nativeFixtureValue(nativePublications.prepare(proposal))
            check(prepared.proposal.company == definition.company && prepared.proposal.kind == proposal.kind &&
                prepared.proposal.publicationId == definition.publicationId && prepared.proposal.entityId == definition.skuId &&
                prepared.proposal.contentDigest == proposal.contentDigest && prepared.rootFingerprint == nativePublications.trust.rootFingerprint)
            evidence.bind("SIDC_BASE_PUBLICATION_${definition.code}", JSONObject()
                .put("originalActionId", prepared.originalActionId.value).put("publicationId", definition.publicationId.value)
                .put("skuId", definition.skuId.value).put("productId", definition.productId.value)
                .put("contentDigest", proposal.contentDigest.value))
            val output = nativeFixtureSecret(headSecret) {
                nativeFixtureValue(nativePublications.approve(prepared, headLogin, it))
            }
            check(output.originalActionId == prepared.originalActionId &&
                output.proposal.company == definition.company && output.proposal.kind == proposal.kind &&
                output.proposal.publicationId == definition.publicationId && output.proposal.entityId == definition.skuId &&
                output.proposal.contentDigest == proposal.contentDigest && output.signingKeyId == prepared.signingKeyId &&
                output.rootFingerprint == nativePublications.trust.rootFingerprint)
            val publication = output.readNativeCompanySku()
            check(publication.definition == definition)
            val verifiedBytes = LocalConfigurationCodec.canonicalPublication(definition)
            try {
                check(NativeCompanyPublicationSignatureVerifier(nativePublications.trust).verify(
                    definition.company, output.signingKeyId, verifiedBytes, output.signatureHex))
            } finally { verifiedBytes.fill(0) }
            publications += NativeInvoiceCaptureBasePublication(output.originalActionId, definition.publicationId,
                output.signingKeyId, output.proposal.contentDigest)
            publication
        }

        return asFixture(inputs.actorId, inputs.login, inputs.account, fixtureActorPassword) {
            requireFreshBusiness()
            for (publication in signed) {
                val definition = publication.definition
                configuration("activate_${definition.code}", LocalConfigurationRequestV1.InstallCompanySku(publication), definition.skuId)
            }
            configuration("configure_warehouse", LocalConfigurationRequestV1.ConfigureWarehouse(null, inputs.warehouse), inputs.warehouse.id)
            configuration("configure_supplier", LocalConfigurationRequestV1.ConfigureSupplier(null, inputs.supplier), inputs.supplier.id)
            configuration("approve_independent_opening", LocalConfigurationRequestV1.ApproveReceiving(inputs.openingApproval), inputs.openingApproval.id)

            val initialize = LocalStockRelationalCommandsV2.initializeFresh(inputs.operation("initialize_stock"),
                inputs.stockInitializationId, inputs.reasonCode)
            val initialized = execute("initialize_stock", initialize)
            val initialization = LocalStockRelationalCommandsV2.decodeInitializationResult(initialize.payload, initialized.result)
            check(initialization.initializationId == inputs.stockInitializationId && initialization.freshEmpty)

            val approval = configurationPage(LocalConfigurationProfile.BASIS, inputs.openingApproval.id)
            check((approval.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value == inputs.openingApproval)
            // Receiving consumes this configuration's domain revision, not its storage generation.
            val basisRevision = approval.items.single().revision
            val emptyStock = inputs.definitions.map { definition -> stock(definition.skuId) }
            check(emptyStock.all { observed -> observed.quantities.all {
                it.itemStorageRevision == null && it.unitId == null && it.quantityScale == null && it.rows.isEmpty()
            } && observed.costs.all { it.itemStorageRevision == null && it.rows.isEmpty() } })
            val opening = LocalGoodsReceiptRequest(inputs.openingReceiptId, inputs.openingApproval.id, basisRevision,
                inputs.definitions.mapIndexed { index, definition ->
                    LocalStockItemVersion(definition.skuId, emptyStock[index].quantities.single().itemStorageRevision)
                }, inputs.openingLines, inputs.reasonCode)
            val openingRequest = LocalStockRelationalCommandsV2.opening(inputs.operation("opening_stock"), opening)
            val opened = execute("opening_stock", openingRequest)
            val openingResult = LocalStockRelationalCommandsV2.decodeResult(openingRequest.payload, opened.result)
            check(openingResult.kind == LocalStockResultKind.OPENED && openingResult.documentId == inputs.openingReceiptId &&
                openingResult.failure == null && openingResult.items.map { it.skuId }.toSet() == inputs.definitions.map { it.skuId }.toSet() &&
                openingResult.items.all { it.storageRevision != null })

            cash("configure_fixture_custodian", LocalCashRequestV1.ConfigureCustodian(inputs.actorId),
                LocalSalesAction.CUSTODIAN_CONFIGURED, inputs.actorId)
            cash("configure_fixture_drawer", LocalCashRequestV1.ConfigureDrawer(inputs.drawerId,
                "Synthetic SIDC baseline drawer", Currency.EGP), LocalSalesAction.DRAWER_CONFIGURED, inputs.drawerId)
            val own = ownCash()
            check(own.custodianId == inputs.actorId && own.session == null && own.unsettledOrderIds.isEmpty() && own.unresolvedReturnIds.isEmpty())
            val drawer = drawers().single { it.id == inputs.drawerId }
            check(drawer.currency == Currency.EGP && !drawer.assigned && !drawer.awaitingClosingReceipt)
            cash("open_fixture_drawer", LocalCashRequestV1.Open(inputs.cashSessionId, inputs.drawerId, drawer.revision,
                own.assignmentRevision, inputs.businessDate, inputs.cashSourceId, inputs.cashOpening,
                LocalCashEvidenceV1(inputs.cashEvidenceId, UtcInstant(Clock.System.now()))), LocalSalesAction.CASH_OPENED, inputs.cashSessionId)

            val a = sku(inputs.definitions[0])
            val b = sku(inputs.definitions[1])
            val c = sku(inputs.definitions[2])
            bindSku("A", a)
            bindSku("B", b)
            bindSku("C", c)
            val supplier = configurationPage(LocalConfigurationProfile.SUPPLIER, inputs.supplier.id)
            check((supplier.detail as? LocalConfigurationDetailV1.Supplier)?.value == inputs.supplier)
            val supplierRevision = supplier.items.single().storageRevision
            val order = inputs.order(a)
            val approve = LocalPurchasingCommands.approve(inputs.operation("approve_po_a"),
                LocalApprovePurchaseOrderV1(order, supplierRevision, inputs.reasonCode))
            val approved = execute("approve_po_a", approve)
            val orderResult = LocalPurchasingCodec.result(approved.result)
            check(orderResult.code == "purchasing.order_approved" && orderResult.id == order.id && orderResult.revision != null)

            val snapshot = snapshot(inputs.account, a, b, c, order, inputs.supplier.id,
                inputs.warehouse.id, inputs.openingReceiptId, inputs.drawerId, inputs.cashSessionId)
            check(snapshot.invoiceDocuments.isEmpty() && snapshot.payables.isEmpty() &&
                snapshot.supplierAdvances.isEmpty() && snapshot.ownSupplierCash.isEmpty())
            requireFrozenBase(snapshot, order, inputs.supplier, inputs.warehouse, inputs.actorId, inputs.drawerId,
                inputs.cashSessionId, opening, opened)
            check(snapshot.supplier.items.single().storageRevision == supplierRevision && snapshot.po.revision == orderResult.revision)
            evidence.bind("SIDC_BASE_BOUND_RESOURCES", JSONObject()
                .put("supplierStorageRevision", supplierRevision.value).put("poStorageRevision", snapshot.po.revision.value)
                .put("poAllocationId", snapshot.po.lines.single().allocationId.value)
                .put("poBillingRevision", checkNotNull(snapshot.po.lines.single().orderBillingRevision).value)
                .put("poReceivingBasisId", snapshot.poReceivingBasis.items.single().id.value)
                .put("poReceivingBasisDomainRevision", snapshot.poReceivingBasis.items.single().revision.value)
                .put("poReceivingBasisStorageRevision", snapshot.poReceivingBasis.items.single().storageRevision.value)
                .put("openingBasisDomainRevision", basisRevision.value)
                .put("openingBasisStorageRevision", approval.items.single().storageRevision.value)
                .put("aStorageRevision", a.item.storageRevision.value).put("bStorageRevision", b.item.storageRevision.value)
                .put("cStorageRevision", c.item.storageRevision.value))
            evidence.event("SIDC_BASE_AUTHORED_AND_PROTECTED_READS_BOUND_NOT_CASE_PASS")
            NativeInvoiceCaptureBase(inputs.coordinates, inputs.actorId, inputs.login, inputs.account,
                inputs.supplier.id, supplierRevision, inputs.warehouse.id, inputs.ea.id, inputs.carton.id,
                inputs.tray.id, inputs.gram.id, inputs.kilogram.id, a, b, c, order, snapshot.po,
                inputs.openingReceiptId, inputs.drawerId, inputs.cashSessionId, employee, publications, operations, snapshot)
        }
    }

    suspend fun observeExisting(base: NativeInvoiceCaptureBase, password: CharArray): NativeInvoiceCaptureBaseSnapshot {
        requireSameNativeFixtureCoordinates(base.coordinates, original.material.coordinates)
        check(base.fixtureActorId != base.coordinates.firstActorId)
        return asFixture(base.fixtureActorId, base.fixtureLogin, base.fixtureAccount, password) {
            val observed = snapshot(base.fixtureAccount, base.a, base.b, base.c, base.originalOrder,
                base.supplierId, base.warehouseId, base.openingReceiptId, base.drawerId, base.cashSessionId)
            val supplier = (base.snapshot.supplier.detail as? LocalConfigurationDetailV1.Supplier)?.value
                ?: error("The actual retained BASE supplier definition is unavailable")
            val warehouse = (base.snapshot.warehouse.detail as? LocalConfigurationDetailV1.Warehouse)?.value
                ?: error("The actual retained BASE warehouse definition is unavailable")
            val opening = base.operations.single { it.label == "opening_stock" }
            check(opening.request.command == LocalStockRelationalProfileV2.COMMAND)
            val originalOpening = LocalStockRelationalCommandsV2.decode(opening.request.payload)
                as? LocalStockRelationalCommand.Receive ?: error("The original BASE opening request is unavailable")
            check(originalOpening.opening && originalOpening.operationId == opening.request.operationId)
            requireFrozenBase(observed, base.originalOrder, supplier, warehouse, base.fixtureActorId,
                base.drawerId, base.cashSessionId, originalOpening.request, opening.result)
            check(observed.supplier == base.snapshot.supplier && observed.warehouse == base.snapshot.warehouse &&
                observed.aPrice == base.snapshot.aPrice && observed.bPrice == base.snapshot.bPrice && observed.cPrice == base.snapshot.cPrice &&
                observed.po == base.po && observed.poReceivingBasis == base.snapshot.poReceivingBasis &&
                observed.openingReceipt.storageRevision == base.snapshot.openingReceipt.storageRevision)
            evidence.event("SIDC_BASE_FRESH_PROTECTED_OBSERVATION", JSONObject()
                .put("sessionId", observed.observedSession.sessionId.value)
                .put("invoiceCount", observed.invoiceDocuments.size))
            observed
        }
    }

    private suspend fun requireFreshBusiness() {
        for (kind in listOf(LocalConfigurationProfile.SKU, LocalConfigurationProfile.WAREHOUSE,
            LocalConfigurationProfile.SUPPLIER, LocalConfigurationProfile.BASIS)) {
            val page = configurationPage(kind, null)
            check(page.items.isEmpty() && page.nextAfter == null && page.detail == null) {
                "BASE preparation requires the genuine fresh business installation"
            }
        }
        check(invoices().isEmpty() && purchasing(LocalPurchasingSectionV1.ORDER).isEmpty() &&
            purchasing(LocalPurchasingSectionV1.PAYABLE).isEmpty())
        val items = LocalStockQueries.decodeItems(read(LocalStockCommands.items()).payload)
        check(items.items.isEmpty() && items.nextAfter == null)
    }

    private suspend fun snapshot(account: LocalAccountProvisioning, a: NativeInvoiceCaptureBaseSku,
        b: NativeInvoiceCaptureBaseSku, c: NativeInvoiceCaptureBaseSku, order: PurchaseOrderV1,
        supplierId: DomainId, warehouseId: DomainId, receiptId: DomainId,
        drawerId: DomainId, sessionId: DomainId): NativeInvoiceCaptureBaseSnapshot {
        val supplier = configurationPage(LocalConfigurationProfile.SUPPLIER, supplierId)
        val warehouse = configurationPage(LocalConfigurationProfile.WAREHOUSE, warehouseId)
        val aCurrent = sku(a.definition)
        val bCurrent = sku(b.definition)
        val cCurrent = sku(c.definition)
        check(aCurrent.item == a.item && bCurrent.item == b.item && cCurrent.item == c.item)
        val po = purchasing(LocalPurchasingSectionV1.ORDER, order.id).singleOrNull()
            ?: error("The original BASE PO is unavailable through its protected reader")
        val poReceivingBasis = configurationPage(LocalConfigurationProfile.BASIS, order.id)
        val receipt = LocalStockQueries.decodeReceipt(read(LocalStockCommands.receiptCosts(receiptId)).payload)
            ?: error("The actual independent opening receipt is unavailable")
        val own = ownCash()
        val cash = LocalCashReadCodec.decode(read(LocalCashReadCodec.request(LocalCashQueryV1.Session(sessionId))).payload)
            as? LocalCashReadResultV1.Session ?: error("The BASE drawer projection is unavailable")
        check(cash.session.id == sessionId && cash.session.drawerId == drawerId &&
            cash.session.custodianId == account.actorId && own.session == cash.session)
        val advances = advances(supplierId)
        val physicalSupplierCash = ownSupplierCash()
        return NativeInvoiceCaptureBaseSnapshot(current().view, supplier, warehouse, aCurrent, bCurrent, cCurrent,
            stock(a.detail.skuId), stock(b.detail.skuId), stock(c.detail.skuId),
            price(aCurrent), price(bCurrent), price(cCurrent), po, poReceivingBasis, receipt, cash.session, own,
            invoices(), purchasing(LocalPurchasingSectionV1.PAYABLE), advances, physicalSupplierCash)
    }

    private suspend fun configuration(label: String, value: LocalConfigurationRequestV1, id: DomainId) {
        val request = MutationRequest(inputs.operation(label), LocalConfigurationProfile.COMMAND, LocalConfigurationCodec.encode(value))
        val result = LocalConfigurationCodec.decodeResult(execute(label, request).result)
        check(result.code == "configuration.applied" && result.id == id && result.revision != null)
    }

    private suspend fun cash(label: String, value: LocalCashRequestV1, action: LocalSalesAction, id: DomainId) {
        val request = MutationRequest(inputs.operation(label), LocalSalesStoreProfile.CASH_COMMAND, LocalCashCommandCodec.encode(value))
        val result = LocalSalesResultCodec.decode(execute(label, request).result) as? LocalSalesResultV1.Applied
            ?: error("The actual BASE cash setup outcome is unavailable")
        check(result.action == action && result.documentId == id && result.stockItems.isEmpty())
    }

    private suspend fun execute(label: String, request: MutationRequest): MutationReply.Recorded {
        check(pending == null)
        val session = current().view
        val digest = semanticDigest(session.scope, session.actorId, request.command, request.payload)
        val attempt = NativeInvoiceCaptureBaseAttempt(label, request,
            OperationIdentity(session.scope.branchId, request.operationId, session.actorId, 1, digest.value), session)
        attempts += attempt
        pending = attempt // Save this exact request/original before the first suspendable dispatch.
        evidence.bind("SIDC_BASE_OPERATION_$label", JSONObject()
            .put("operationId", request.operationId.value).put("originalActorId", session.actorId.value)
            .put("command", request.command.name).put("commandVersion", request.command.version)
            .put("semanticDigest", digest.value).put("sessionId", session.sessionId.value)
            .put("policyRevision", session.policy.revision.value))
        attempt.callStarted = true
        val reply = original.client.execute(credentials(), request)
        attempt.reply = reply // Preserve an actual rejected/uncertain reply before classifying it.
        val recorded = reply as? MutationReply.Recorded
            ?: error("BASE setup has no conclusive original result; replacement or redispatch is not authorized")
        check(recorded.committed && !recorded.replayed && recorded.operationId == request.operationId &&
            recorded.originalActorId == session.actorId && recorded.authorityEpoch == original.receipt.core.authorityEpoch) {
            "The genuine BASE command did not produce its own complete committed original"
        }
        operations += NativeInvoiceCaptureBaseOperation(label, request, attempt.identity, session, recorded)
        pending = null
        evidence.event("SIDC_BASE_OPERATION_RECORDED", JSONObject()
            .put("operationId", recorded.operationId.value).put("sequence", recorded.sequence.value).put("code", recorded.code))
        return recorded
    }

    private suspend fun configurationPage(kind: String, id: DomainId?): LocalConfigurationPageV1 {
        val page = LocalConfigurationCodec.decodePage(read(ReadRequest(LocalConfigurationProfile.QUERY,
            LocalConfigurationCodec.query(LocalConfigurationQueryV1(kind, id = id, limit = 50)))).payload)
        check(page.kind == kind && (id == null || page.nextAfter == null && page.items.size <= 1 && page.items.all { it.id == id }))
        return page
    }

    private suspend fun sku(definition: LocalPublishedSkuV1): NativeInvoiceCaptureBaseSku {
        val page = configurationPage(LocalConfigurationProfile.SKU, definition.skuId)
        val item = page.items.singleOrNull() ?: error("The BASE SKU current identity is unavailable")
        val detail = page.detail as? LocalConfigurationDetailV1.Sku ?: error("The typed BASE SKU detail is unavailable")
        check(item.id == definition.skuId && item.names == definition.names && item.active == definition.active && item.code == definition.code)
        check(detail.skuId == definition.skuId && detail.productId == definition.productId &&
            detail.skuRevision == definition.skuRevision && detail.publicationRevision == definition.catalogRevision &&
            detail.names == definition.names && detail.code == definition.code && detail.barcodes == definition.barcodes &&
            detail.baseUnitId == definition.baseUnitId && detail.quantityScale == definition.quantityScale &&
            detail.tracking == definition.tracking && detail.fulfillment == definition.fulfillment &&
            detail.canPurchase && detail.canSell && detail.active && !detail.requiresExpiry &&
            detail.conversions == definition.conversions && detail.prices == definition.prices &&
            detail.unitDefinitions == definition.unitDefinitions && detail.productDefinition == definition.productDefinition &&
            detail.variantValues == definition.variantValues)
        val product = checkNotNull(detail.productDefinition)
        val variants = checkNotNull(detail.variantValues)
        check(variants.isEmpty() && product.variantAxes.isEmpty() && detail.unitDefinitions != null)
        return NativeInvoiceCaptureBaseSku(definition, item, detail)
    }

    private fun bindSku(symbol: String, sku: NativeInvoiceCaptureBaseSku) {
        val detail = sku.detail
        val product = checkNotNull(detail.productDefinition)
        val units = JSONArray()
        for (unit in checkNotNull(detail.unitDefinitions)) units.put(JSONObject()
            .put("unitId", unit.id.value).put("code", unit.code.original)
            .put("dimension", unit.dimension.name).put("maximumScale", unit.maximumScale))
        val conversions = JSONArray()
        for (conversion in detail.conversions) conversions.put(JSONObject()
            .put("conversionId", conversion.id.value).put("revision", conversion.revision.value)
            .put("sourceUnitId", conversion.sourceUnitId.value).put("targetUnitId", conversion.targetUnitId.value)
            .put("numerator", conversion.rate.numerator).put("denominator", conversion.rate.denominator)
            .put("dimensionalEvidenceId", conversion.dimensionalEvidenceId?.value ?: JSONObject.NULL))
        check(checkNotNull(detail.variantValues).isEmpty())
        evidence.bind("SIDC_BASE_SKU_$symbol", JSONObject()
            .put("skuId", detail.skuId.value).put("productId", detail.productId.value)
            .put("productRevision", product.revision.value).put("skuRevision", detail.skuRevision.value)
            .put("publicationRevision", detail.publicationRevision.value).put("storageRevision", sku.item.storageRevision.value)
            .put("configurationDomainRevision", sku.item.revision.value)
            .put("baseUnitId", detail.baseUnitId.value).put("baseScale", detail.quantityScale)
            .put("variantValues", JSONArray()).put("units", units).put("conversions", conversions))
    }

    private suspend fun stock(skuId: DomainId): NativeInvoiceCaptureBaseStock {
        val quantities = mutableListOf<LocalStockQuantityPage>()
        val costs = mutableListOf<LocalStockCostPage>()
        var after: DomainId? = null
        do {
            val page = LocalStockQueries.decodeQuantities(read(LocalStockCommands.quantities(skuId, after, 25)).payload)
            check(page.skuId == skuId && page.rows.all { after == null || it.positionId.value > checkNotNull(after).value })
            quantities += page
            val next = page.nextAfter
            check(next == null || page.rows.isNotEmpty() && next == page.rows.last().positionId &&
                (after == null || next.value > checkNotNull(after).value))
            after = next
        } while (after != null)
        do {
            val page = LocalStockQueries.decodeCosts(read(LocalStockCommands.costs(skuId, after, 25)).payload)
            check(page.skuId == skuId && page.rows.all { after == null || it.poolId.value > checkNotNull(after).value })
            costs += page
            val next = page.nextAfter
            check(next == null || page.rows.isNotEmpty() && next == page.rows.last().poolId &&
                (after == null || next.value > checkNotNull(after).value))
            after = next
        } while (after != null)
        val revision = quantities.first().itemStorageRevision
        check(quantities.all { it.itemStorageRevision == revision } && costs.all { it.itemStorageRevision == revision })
        return NativeInvoiceCaptureBaseStock(quantities, costs)
    }

    private suspend fun price(sku: NativeInvoiceCaptureBaseSku): LocalPricePreviewV1 {
        val quantity = Quantity(sku.detail.baseUnitId, 1, sku.detail.quantityScale)
        val query = LocalPricingQueryV1.Preview(sku.detail.skuId, "COUNTER", quantity)
        val result = LocalPricingCodec.decodePage(read(ReadRequest(LocalPricingProfile.QUERY, LocalPricingCodec.query(query))).payload)
            .preview ?: error("The genuine effective BASE selling-price snapshot is unavailable")
        check(result.skuId == sku.detail.skuId && result.channel == "COUNTER" && result.quantity == quantity &&
            result.selected != null && result.effectiveUnitPrice != null && result.code == "catalog.price_selected")
        return result
    }

    private suspend fun purchasing(section: LocalPurchasingSectionV1, id: DomainId? = null): List<LocalPurchasingDocumentViewV1> {
        val documents = mutableListOf<LocalPurchasingDocumentViewV1>()
        var after: DomainId? = null
        do {
            val query = LocalPurchasingQueryV1(section, id, after, 5, includeCosts = true)
            val page = LocalPurchasingCodec.page(read(ReadRequest(LocalPurchasingProfile.READ, LocalPurchasingCodec.query(query))).payload)
            check(page.section == section && page.includesCosts && page.availability == LocalPurchasingReadAvailabilityV1.AVAILABLE)
            val values = page.documents ?: error("Unavailable purchasing read is not known-empty")
            requireDirectory(values.map { it.id }, page.nextAfter, after, id, 5)
            documents += values
            after = page.nextAfter
        } while (after != null)
        return documents.toList()
    }

    private suspend fun invoices(): List<LocalPurchasingInvoiceDocumentV2> {
        val documents = mutableListOf<LocalPurchasingInvoiceDocumentV2>()
        var after: DomainId? = null
        do {
            val query = LocalPurchasingQueryV1(LocalPurchasingSectionV1.INVOICE, after = after, limit = 5, includeCosts = true)
            val page = LocalPurchasingCodec.invoicePage(read(ReadRequest(LocalPurchasingProfile.READ_INVOICES,
                LocalPurchasingCodec.query(query))).payload)
            check(page.includesCosts && page.availability == LocalPurchasingReadAvailabilityV1.AVAILABLE)
            val values = page.documents ?: error("Unavailable invoice read is not known-empty")
            requireDirectory(values.map { it.legacy?.id ?: checkNotNull(it.capture).id }, page.nextAfter, after, null, 5)
            documents += values
            after = page.nextAfter
        } while (after != null)
        return documents.toList()
    }

    private suspend fun advances(supplierId: DomainId): List<LocalSupplierAdvanceViewV1> {
        val entries = mutableListOf<LocalSupplierAdvanceViewV1>()
        var after: DomainId? = null
        do {
            val page = LocalSupplierSettlementCodec.advances(read(ReadRequest(LocalSupplierSettlementProfile.READ_ADVANCES,
                LocalSupplierSettlementCodec.advances(LocalSupplierAdvanceQueryV1(supplierId, after = after)))).payload)
            check(page.unavailableCode == null)
            val values = page.advances ?: error("Unavailable advance read is not known-empty")
            requireFilteredDirectory(values.map { it.id }, page.nextAfter, after, 5)
            check(values.all { it.supplierId == supplierId })
            entries += values
            after = page.nextAfter
        } while (after != null)
        return entries.toList()
    }

    private suspend fun ownSupplierCash(): List<LocalSupplierCashViewV1> {
        val entries = mutableListOf<LocalSupplierCashViewV1>()
        var after: DomainId? = null
        do {
            val page = LocalSupplierSettlementCodec.ownCash(read(ReadRequest(LocalSupplierSettlementProfile.READ_OWN_CASH,
                LocalSupplierSettlementCodec.ownCash(LocalSupplierOwnCashQueryV1(after)))).payload)
            check(page.unavailableCode == null)
            val values = page.entries ?: error("Unavailable supplier-cash read is not known-empty")
            // This cursor belongs to the scanned native slot directory, not the own-filtered entries.
            requireFilteredDirectory(values.map { DomainId(it.recordingOperationId.value) }, page.nextAfter, after, 5)
            check(values.all { it.unavailableCode == null })
            entries += values
            after = page.nextAfter
        } while (after != null)
        return entries.toList()
    }

    private suspend fun ownCash(): LocalCashReadResultV1.OwnCash =
        LocalCashReadCodec.decode(read(LocalCashReadCodec.request(LocalCashQueryV1.OwnCash)).payload)
            as? LocalCashReadResultV1.OwnCash ?: error("The fixture employee's actual cash projection is unavailable")

    private suspend fun drawers(): List<LocalDrawerChoiceV1> {
        val entries = mutableListOf<LocalDrawerChoiceV1>()
        var after: DomainId? = null
        do {
            val page = LocalCashReadCodec.decode(read(LocalCashReadCodec.request(LocalCashQueryV1.Drawers(after, 25))).payload)
                as? LocalCashReadResultV1.Drawers ?: error("The actual drawer selector is unavailable")
            requireDirectory(page.drawers.map { it.id }, page.nextAfter, after, null, 25)
            entries += page.drawers
            after = page.nextAfter
        } while (after != null)
        return entries.toList()
    }

    private fun requireDirectory(ids: List<DomainId>, next: DomainId?, after: DomainId?, id: DomainId?, limit: Int) {
        check(ids.size <= limit && ids.distinct().size == ids.size && ids.zipWithNext().all { (a, b) -> a.value < b.value } &&
            ids.all { after == null || it.value > after.value } &&
            (id == null || ids.all { it == id } && next == null) && (next == null || next == ids.lastOrNull()))
    }

    /** Filtered projections retain the real last-scanned key even for an empty selected page. */
    private fun requireFilteredDirectory(ids: List<DomainId>, next: DomainId?, after: DomainId?, limit: Int) {
        check(ids.size <= limit && ids.distinct().size == ids.size &&
            ids.zipWithNext().all { (a, b) -> a.value < b.value } &&
            ids.all { after == null || it.value > after.value } &&
            (next == null || (after == null || next.value > after.value) && ids.all { it.value <= next.value }))
    }

    private suspend fun read(request: ReadRequest): ReadReply.Data {
        val result = original.client.read(credentials(), request)
        return result as? ReadReply.Data ?: error("Required BASE fact is unavailable through its genuine authorized reader")
    }

    private fun credentials(): LocalGatewayCredentials = LocalGatewayCredentials(original.peer.connectionId, current().access)

    private fun current(): LocalSessionTokens {
        check(original.isCurrent() && original.peer.isCurrent() && !authenticationUnconfirmed && !logoutUnconfirmed)
        return checkNotNull(tokens)
    }

    private suspend fun <T> asFixture(actor: DomainId, login: LocalLoginIdentifier, account: LocalAccountProvisioning,
        password: CharArray, block: suspend () -> T): T {
        check(tokens == null && !authenticationUnconfirmed && !logoutUnconfirmed && original.isCurrent() && original.peer.isCurrent())
        var failure: Throwable? = null
        try {
            authenticationUnconfirmed = true
            val authenticated = nativeFixtureSecret(password) { original.client.signIn(original.peer.connectionId, login, it) }
            val issued = when (authenticated) {
                is LocalAuthenticationResult.Authenticated -> authenticated.tokens
                is LocalAuthenticationResult.Denied, LocalAuthenticationResult.Unavailable -> {
                    authenticationUnconfirmed = false
                    error("The genuine BASE employee session is unavailable")
                }
                LocalAuthenticationResult.StorageUncertain ->
                    error("BASE employee authentication is storage-uncertain; this helper cannot be reused")
            }
            tokens = issued // Retain actual secret owner before any subsequent observation can fail.
            authenticationUnconfirmed = false
            check(issued.view.actorId == actor && issued.view.login == login && issued.view.scope == original.material.coordinates.scope &&
                issued.view.deviceId == original.peer.deviceId)
            requireNativeFixturePolicy(account.policy, issued.view.policy)
            evidence.event("SIDC_BASE_FIXTURE_EMPLOYEE_AUTHENTICATED", JSONObject()
                .put("actorId", actor.value).put("sessionId", issued.view.sessionId.value)
                .put("policyRevision", issued.view.policy.revision.value))
            return block()
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            withContext(NonCancellable) {
                val issued = tokens
                var cleanup: Throwable? = null
                if (issued != null) {
                    logoutUnconfirmed = true
                    try {
                        val outcome = original.client.logout(LocalGatewayCredentials(original.peer.connectionId, issued.access))
                        check(outcome == LocalSignOutResult.Revoked) { "The actual BASE employee logout is unconfirmed" }
                        logoutUnconfirmed = false
                        evidence.event("SIDC_BASE_FIXTURE_EMPLOYEE_LOGOUT_REVOKED", JSONObject()
                            .put("sessionId", issued.view.sessionId.value))
                    } catch (caught: Throwable) { cleanup = caught }
                    finally {
                        try { issued.destroy() } catch (caught: Throwable) {
                            if (cleanup == null) cleanup = caught
                            else if (cleanup !== caught) checkNotNull(cleanup).addSuppressed(caught)
                        }
                        tokens = null
                    }
                }
                cleanup?.let { caught ->
                    if (failure == null) throw caught
                    else if (failure !== caught) checkNotNull(failure).addSuppressed(caught)
                }
            }
        }
    }

    private fun requireAccount(expected: LocalAccountProvisioning, actual: LocalAccountProvisioning) {
        check(actual.actorId == expected.actorId && actual.login == expected.login &&
            actual.tokenPolicy == expected.tokenPolicy && actual.admittedDeviceIds == expected.admittedDeviceIds)
        requireNativeFixturePolicy(expected.policy, actual.policy)
    }

    private fun requireFrozenBase(snapshot: NativeInvoiceCaptureBaseSnapshot, order: PurchaseOrderV1,
        supplier: LocalSupplierV1, warehouse: LocalWarehouseV1, actorId: DomainId,
        drawerId: DomainId, sessionId: DomainId, opening: LocalGoodsReceiptRequest, opened: MutationReply.Recorded) {
        val warehouseId = warehouse.id
        check(snapshot.supplier.kind == LocalConfigurationProfile.SUPPLIER && snapshot.supplier.nextAfter == null &&
            snapshot.supplier.items.single().let { it.id == supplier.id && it.names == supplier.names && it.active == supplier.active } &&
            (snapshot.supplier.detail as? LocalConfigurationDetailV1.Supplier)?.value == supplier && supplier.id == order.supplierId)
        check(snapshot.warehouse.kind == LocalConfigurationProfile.WAREHOUSE && snapshot.warehouse.nextAfter == null &&
            snapshot.warehouse.items.single().let { it.id == warehouseId && it.names == warehouse.names && it.active == warehouse.active } &&
            (snapshot.warehouse.detail as? LocalConfigurationDetailV1.Warehouse)?.value == warehouse)
        // These exact quantities/values are unchanged_business_cut, not implementation-derived math.
        for ((sku, observed, quantity, minor) in listOf(
            NativeInvoiceCaptureBaseExpectedStock(snapshot.aSku, snapshot.a, 100, 100_000),
            NativeInvoiceCaptureBaseExpectedStock(snapshot.bSku, snapshot.b, 40, 80_000),
            NativeInvoiceCaptureBaseExpectedStock(snapshot.cSku, snapshot.c, 10_000, 40_000),
        )) {
            val unit = sku.detail.baseUnitId
            val expected = Quantity(unit, quantity, 0)
            val zero = Quantity(unit, 0, 0)
            val rows = observed.quantities.flatMap { it.rows }
            val costs = observed.costs.flatMap { it.rows }
            check(observed.quantities.all { it.itemStorageRevision != null && it.unitId == unit && it.quantityScale == 0 })
            check(rows.map { it.positionId }.distinct().size == rows.size && rows.isNotEmpty() &&
                rows.all { it.position.skuId == sku.detail.skuId && it.position.warehouseId == warehouseId &&
                    it.position.custody == StockCustody.RELEASED && it.position.supplierId == null &&
                    it.position.lotId == null && it.position.serialId == null && it.reserved == zero &&
                    it.safety == StockSafety.CLEAR && it.heldQuantity == zero && it.heldShortageQuantity == zero &&
                    !it.expiredAtRead && it.expiresAt == null })
            check(rows.fold(zero) { total, row -> total + row.onHand } == expected &&
                rows.fold(zero) { total, row -> total + row.available } == expected)
            check(costs.map { it.poolId }.distinct().size == costs.size && costs.isNotEmpty() &&
                costs.all { it.warehouseId == warehouseId } && costs.fold(zero) { total, row -> total + row.quantity } == expected &&
                costs.fold(Money(Currency.EGP, 0)) { total, row -> total + row.carryingValue } == Money(Currency.EGP, minor))
        }
        val po = snapshot.po
        val ordered = order.lines.single()
        val line = po.lines.single()
        val zeroOrdered = Quantity(ordered.goods.units.baseUnitId, 0, ordered.goods.units.baseScale)
        check(po.id == order.id && po.orderId == order.id && po.supplierId == order.supplierId && po.status == "APPROVED" &&
            po.reference == order.reference && po.paymentTerms == order.paymentTerms && po.date == order.orderDate &&
            po.charges == order.charges && po.progressRevision == null && po.payableRevision == null &&
            po.discrepancies.isEmpty() && line.allocationId == ordered.allocationId && line.lineId == ordered.allocationId &&
            line.skuId == ordered.goods.skuId && line.warehouseId == ordered.warehouseId && line.units == ordered.goods.units &&
            line.originalQuantity == ordered.goods.quantity && line.baseQuantity == Quantity(ordered.goods.units.baseUnitId, 24, 0) &&
            line.unitPrice == ordered.goods.unitPrice && line.discount == ordered.goods.discount && line.purchaseTax == ordered.goods.tax &&
            line.claimedNet == Money(Currency.EGP, 24_000) && line.claimedTax == Money(Currency.EGP, 0) &&
            line.orderBillingRevision != null && line.matchedQuantity == zeroOrdered &&
            line.matchedNet == Money(Currency.EGP, 0) && line.matchedTax == Money(Currency.EGP, 0))
        val poReceivingBasis = snapshot.poReceivingBasis
        val receivingApproval = (poReceivingBasis.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value
            ?: error("The actual approved BASE PO receiving BASIS is unavailable")
        val approvedLine = receivingApproval.lines.single()
        check(poReceivingBasis.kind == LocalConfigurationProfile.BASIS && poReceivingBasis.nextAfter == null &&
            poReceivingBasis.items.single().id == order.id && receivingApproval.id == order.id &&
            receivingApproval.kind == LocalBasisKindV1.LOCAL_PURCHASE && receivingApproval.supplierId == order.supplierId &&
            receivingApproval.validFrom == order.validFrom && receivingApproval.validUntil == order.validUntil &&
            approvedLine.allocationId == ordered.allocationId && approvedLine.allocationId == line.allocationId &&
            approvedLine.skuId == ordered.goods.skuId && approvedLine.skuId == snapshot.aSku.detail.skuId &&
            approvedLine.warehouseId == ordered.warehouseId && approvedLine.warehouseId == warehouseId &&
            approvedLine.maximumBaseQuantity == Quantity(snapshot.aSku.detail.baseUnitId, 24, 0) &&
            approvedLine.maximumBaseQuantity == line.baseQuantity &&
            approvedLine.maximumOwnedValue == Money(Currency.EGP, 24_000) && approvedLine.maximumOwnedValue == ordered.goods.net &&
            approvedLine.maximumOwnedValue == line.claimedNet &&
            approvedLine.inspectionRequired == ordered.inspectionRequired)
        // The approved PO allowance exists; no stock purchase-allowance consumption facts do.
        // Null consumption fields retain observed absence, not invented zero quantities.
        check(line.receivedCommitted == null && line.receivedAccepted == null && line.inspectionPending == null)
        val receipt = snapshot.openingReceipt
        check(receipt.receiptId == opening.receiptId && receipt.basisId == opening.basisId &&
            receipt.basisRevision == opening.expectedBasisRevision && receipt.basisKind == LocalStockBasisKind.OPENING &&
            receipt.supplierId == null && receipt.costsIncluded && receipt.recordedBy == actorId &&
            receipt.receivedAt == opened.recordedAt && receipt.reasonCode == opening.reasonCode &&
            receipt.lines.size == opening.lines.size && receipt.lines.map { it.lineId }.toSet() == opening.lines.map { it.lineId }.toSet() &&
            receipt.lines.map { it.skuId }.toSet() == setOf(snapshot.aSku.detail.skuId, snapshot.bSku.detail.skuId, snapshot.cSku.detail.skuId))
        for (recorded in receipt.lines) {
            val requested = opening.lines.single { it.lineId == recorded.lineId }
            check(recorded.allocationId == requested.allocationId && recorded.sourceId == requested.sourceId &&
                recorded.skuId == requested.skuId && recorded.warehouseId == requested.warehouseId &&
                recorded.recordedQuantity == requested.quantity && recorded.baseQuantity == requested.quantity &&
                recorded.disposition == requested.disposition && recorded.originalValue == requested.originalValue &&
                requested.warehouseId == warehouseId && requested.disposition == LocalReceiptDisposition.RELEASED &&
                requested.conversion == null && recorded.conversion == null && recorded.lotId == null && recorded.expiresAt == null &&
                recorded.serialId == null && recorded.serialCode == null && recorded.initialSerialRegistrationStatus == null &&
                recorded.releasedFromQuarantine == Quantity(requested.quantity.unitId, 0, requested.quantity.scale))
        }
        val drawer = snapshot.drawer
        val cashZero = Money(Currency.EGP, 0)
        check(drawer.id == sessionId && drawer.drawerId == drawerId && drawer.custodianId == actorId && drawer.status == "OPEN" &&
            drawer.opening == Money(Currency.EGP, 500_000) && drawer.expectedCash == Money(Currency.EGP, 500_000) &&
            drawer.pendingAttemptIds.isEmpty() && drawer.pendingTransferIds.isEmpty() &&
            listOf(drawer.reservedCashOut, drawer.received, drawer.change, drawer.refunds, drawer.payIns, drawer.payOuts,
                drawer.safeDrops, drawer.externalCollections, drawer.externalRefunds).all { it == cashZero } &&
            drawer.closingExpected == null && drawer.counted == null && drawer.variance == null && drawer.retainedFloat == null &&
            drawer.actualSafeDelivery == null && drawer.closingTransferId == null && snapshot.ownCash.session == drawer &&
            snapshot.ownCash.unsettledOrderIds.isEmpty() && snapshot.ownCash.unresolvedReturnIds.isEmpty())
        check(snapshot.payables.isEmpty() && snapshot.supplierAdvances.isEmpty() && snapshot.ownSupplierCash.isEmpty())
    }
}

/** Setup-only requested facts. New IDs/revision-zero definitions are explicit input, never proofs.
 * Shared EA/CARTON unit bodies are identical; each product's conversion has its own identity.
 * Explicit empty variant selections mean none, never omitted/guessed variant authority.
 * Finite employee/session settings, exact unit-code normalization, revision-zero new definitions,
 * display identifiers and the three effective selling prices are synthetic construction choices,
 * not extra frozen expectations. Only the actual protected price snapshots are conserved. */
private class NativeInvoiceCaptureBaseInputs(val coordinates: SelfOwnedAllocationCoordinatesV1) {
    val namespace = DomainId(UUID.randomUUID().toString())
    val actorId = id("fixture-employee")
    val login = LocalLoginIdentifier("invoice.capture.base.fixture")
    val enrollmentRequestId = id("fixture-employee-enrollment")
    val ea = CatalogUnit(id("unit-ea"), Code.exact("EA"), UnitDimension.COUNT, 0)
    val carton = CatalogUnit(id("unit-carton"), Code.exact("CARTON"), UnitDimension.COUNT, 0)
    val tray = CatalogUnit(id("unit-tray"), Code.exact("TRAY"), UnitDimension.COUNT, 0)
    val gram = CatalogUnit(id("unit-g"), Code.exact("G"), UnitDimension.MASS, 0)
    val kilogram = CatalogUnit(id("unit-kg"), Code.exact("KG"), UnitDimension.MASS, 3)
    val warehouse = LocalWarehouseV1(id("warehouse"), LocalNamesV1("Synthetic SIDC warehouse", ""), active = true)
    val supplier = LocalSupplierV1(id("supplier"), LocalNamesV1("Synthetic SIDC supplier", ""), active = true)
    val stockInitializationId = id("stock-initialization")
    val openingReceiptId = id("independent-opening-receipt")
    val drawerId = id("fixture-drawer")
    val cashSessionId = id("fixture-cash-session")
    val cashSourceId = id("fixture-opening-cash-source")
    val cashEvidenceId = id("fixture-opening-cash-evidence")
    val cashOpening = Money(Currency.EGP, 500_000)
    val orderId = id("po-a")
    val orderAllocationId = id("po-a-line-a-allocation")
    val reasonCode = "synthetic.invoice.capture.base"
    val effectiveFrom = UtcInstant.parseCanonical("2000-01-01T00:00:00Z")
    val businessDate = BusinessDate.parseCanonical("2026-11-05")

    val definitions = listOf(
        definition("A", "Synthetic still water 500 ml", ea, listOf(ea, carton, tray), listOf(carton to 12L, tray to 6L), "15"),
        definition("B", "Synthetic sparkling water 500 ml", ea, listOf(ea, carton), listOf(carton to 12L), "25"),
        definition("C", "Synthetic bulk rice", gram, listOf(gram, kilogram), listOf(kilogram to 1_000L), "0.05"),
    )
    val openingLines = definitions.mapIndexed { index, sku ->
        LocalGoodsReceiptLine(id("opening-line-${sku.code}"), id("opening-allocation-${sku.code}"), id("opening-source-${sku.code}"),
            sku.skuId, warehouse.id, Quantity(sku.baseUnitId, listOf(100L, 40L, 10_000L)[index], 0), null, null, null,
            LocalReceiptDisposition.RELEASED, Money(Currency.EGP, listOf(100_000L, 80_000L, 40_000L)[index]))
    }
    val openingApproval = LocalReceivingApprovalV1(id("independent-opening-approval"), LocalBasisKindV1.OPENING, null,
        effectiveFrom, null, openingLines.map { line -> LocalApprovedStockLineV1(line.allocationId, line.skuId,
            line.warehouseId, line.quantity, checkNotNull(line.originalValue), inspectionRequired = false) })

    val account = LocalAccountProvisioning(actorId, login, ActivatedLocalPolicy(coordinates.scope, actorId, Revision(0), listOf(
        grant(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER, targets = definitions.map { it.skuId }.toSet()),
        grant(SecurityAction.WAREHOUSE_CONFIGURE, PermissionVerb.ADMINISTER, targets = setOf(warehouse.id)),
        grant(SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER, targets = setOf(supplier.id)),
        grant(SecurityAction.OPENING_INVENTORY_APPROVE, PermissionVerb.ADMINISTER, Money(Currency.EGP, 220_000), setOf(openingApproval.id)),
        grant(SecurityAction.INVENTORY_ADJUST, PermissionVerb.PERFORM, targets = setOf(stockInitializationId, openingReceiptId)),
        grant(SecurityAction.PURCHASING_APPROVE, PermissionVerb.PERFORM, Money(Currency.EGP, 24_000), setOf(orderId)),
        grant(SecurityAction.CASH_CONFIGURE, PermissionVerb.PERFORM, targets = setOf(actorId, drawerId)),
        grant(SecurityAction.CASH_OPEN, PermissionVerb.PERFORM, cashOpening, setOf(drawerId)),
        grant(SecurityAction.CASH_READ, PermissionVerb.PERFORM),
        grant(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM),
        grant(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM),
        grant(SecurityAction.INVENTORY_COST_READ, PermissionVerb.PERFORM),
        grant(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM),
    ), LocalSessionPolicy(maximumConcurrentSessions = 1),
        ThrottlePolicy(freeFailures = 3, initialDelayMillis = 1_000, maximumDelayMillis = 900_000, attemptTimeoutMillis = 120_000),
        approvalPolicy = null), LocalTokenPolicy(accessLifetimeMillis = 1_800_000, workLifetimeMillis = 28_800_000), setOf(coordinates.deviceId))

    fun order(a: NativeInvoiceCaptureBaseSku): PurchaseOrderV1 {
        val conversion = a.detail.conversions.single { it.sourceUnitId == carton.id && it.targetUnitId == ea.id }
        val units = PurchaseUnitsV1(a.detail.productId, carton.id, a.detail.baseUnitId, a.detail.quantityScale,
            conversion.id, conversion.revision, conversion.rate, conversion.dimensionalEvidenceId)
        return PurchaseOrderV1(orderId, supplier.id, "PO-A", businessDate, null, "Synthetic original PO terms",
            effectiveFrom, null, listOf(PurchaseOrderLineV1(orderAllocationId, warehouse.id,
                PurchaseReceiptCostBasisV1.NET_GOODS_ONLY, inspectionRequired = false, requestedDelivery = null,
                PurchaseGoodsV1(a.detail.skuId, "PO-000042", units, Quantity(carton.id, 2, 0),
                    UnitPrice(Currency.EGP, ExactDecimal.parse("120")), PercentageRate.parse("0"),
                    PurchaseTaxV1(id("purchase-zero-tax"), Revision(0), "SYNTHETIC_ZERO", PercentageRate.parse("0")),
                    Money(Currency.EGP, 24_000), Money(Currency.EGP, 0)))), emptyList())
    }

    fun operation(label: String) = OperationId(id("operation-$label").value)

    private fun definition(label: String, description: String, base: CatalogUnit, units: List<CatalogUnit>,
        conversions: List<Pair<CatalogUnit, Long>>, sellingPrice: String): LocalPublishedSkuV1 {
        val productId = id("product-$label")
        return LocalPublishedSkuV1(coordinates.scope.company(), id("publication-$label"), id("sku-$label"), productId,
            Revision(0), Revision(0), LocalNamesV1(description, ""), "SIDC-$label", emptyList(), base.id, 0,
            LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL, requiresExpiry = false, canPurchase = true, canSell = true,
            active = true, conversions = conversions.map { (unit, rate) -> LocalUnitConversionV1(id("conversion-$label-${unit.code.original}"),
                Revision(0), unit.id, base.id, UnitConversionRate.ratio(rate, 1)) },
            prices = listOf(LocalPriceV1(id("selling-price-$label"), Revision(0), "COUNTER", 0, Quantity(base.id, 0, 0),
                effectiveFrom = effectiveFrom, unitPrice = UnitPrice(Currency.EGP, ExactDecimal.parse(sellingPrice)),
                inclusion = TaxInclusion.INCLUSIVE, tax = TaxSnapshot(id("selling-zero-tax-$label"), Revision(0), "SYNTHETIC_ZERO", PercentageRate.parse("0")))),
            unitDefinitions = units, productDefinition = LocalPublishedProductV1(productId, Revision(0), LocalNamesV1(description, ""),
                emptyList(), emptyList(), emptyList(), LocalProductStatusV1.ACTIVE), variantValues = emptyList())
    }

    private fun grant(action: SecurityAction, verb: PermissionVerb, amount: Money? = null,
        targets: Set<DomainId>? = null) = PermissionGrant(Permission(action, verb), coordinates.scope, GrantEffect.ALLOW,
        PermissionLimits(maximumAmount = amount), OwnerAccess.OWN_ONLY, actorId, targets, null)

    private fun id(label: String): DomainId {
        val bytes = "supplier-invoice-discrepancy-capture-v1/base/${namespace.value}/$label".encodeToByteArray()
        return try { DomainId(UUID.nameUUIDFromBytes(bytes).toString()) } finally { bytes.fill(0) }
    }
}

private class NativeInvoiceCaptureBaseAttempt(val label: String, val request: MutationRequest,
    val identity: OperationIdentity, val session: LocalSessionView) {
    var callStarted = false
    var reply: MutationReply? = null
}

private data class NativeInvoiceCaptureBaseExpectedStock(val sku: NativeInvoiceCaptureBaseSku,
    val observed: NativeInvoiceCaptureBaseStock, val quantity: Long, val minor: Long)
