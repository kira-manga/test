package com.ukkera.pos.android.independent.nativesupplierinvoicecapturev1

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureOwner
import com.ukkera.pos.android.independent.nativefixture.requireSameNativeFixtureCoordinates
import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.UnitDimension
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.*
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bounded native persistence/reopen leg of frozen SIDC-02(b), UNALLOCATED_MIXED.
 * The full frozen business BASE is provisioned by a separate, genuinely enrolled employee.
 * Capture uses the real public shared-application gateway, native intent/credential journals,
 * current production Master and independent protected reads. Application IDs and explicit
 * form inputs below are caller-generated, NOT editor/controller-generated or installed-UI proof.
 * No PO context, receipt, mapping, approval, payable posting or payment is supplied for capture.
 * A supplementary closed-native census never substitutes for the normal history-admitted reopen.
 * This method does not qualify the whole SIDC case, ICR, an additional Client/RPC, or device/owner
 * acceptance. Source wiring is not an execution result; those boundaries remain separately held.
 */
@RunWith(AndroidJUnit4::class)
class NativeInvoiceCaptureTest {
    @Test fun sidc02bUnallocatedMixedNativeCaptureAndOriginalReopen(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("syntheticNativeInvoiceCaptureSidc02b") == "true") {
            "Explicit syntheticNativeInvoiceCaptureSidc02b=true is required on an isolated synthetic localTesting installation"
        }
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing")
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 && Build.VERSION.SDK_INT >= 24)
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")) {
            "Select keyProtection=OS_ISOLATED, SECURE_HARDWARE or STRONGBOX explicitly; no fallback is used"
        })
        val evidence = NativeInvoiceCaptureEvidence(application)
        val originals = NativeInvoiceCaptureOriginals(evidence)
        val owner = NativeLocalFixtureOwner(application, protection, evidence, NativeInvoiceCaptureFixture.setup) {
                original, journals, publications ->
            NativeInvoiceCaptureClient(original, journals, publications, originals, evidence)
        }
        val secrets = mutableListOf<CharArray>()
        val censuses = mutableListOf<NativeInvoiceCaptureCensus>()
        fun secret(): CharArray = NativeInvoiceCaptureFixture.secret().also { secrets += it }
        suspend fun census(label: String): NativeInvoiceCaptureCensus = owner.readClosedNativeHistory { custody ->
            NativeInvoiceCaptureCensus.read(custody, owner.coordinates, label, evidence).also(censuses::add)
        }
        var failure: Throwable? = null
        var assertionsFinished = false
        var cleanupConfirmed = false
        fun remember(caught: Throwable) {
            val prior = failure
            if (prior == null) failure = caught else if (prior !== caught) prior.addSuppressed(caught)
        }
        try {
            val installer = secret()
            val head = secret()
            val capturePassword = secret()
            val fixturePassword = secret()
            evidence.event("BOUNDED_NATIVE_SCOPE", JSONObject().put("keyProtection", protection.name)
                .put("sdkInt", Build.VERSION.SDK_INT).put("osRelease", Build.VERSION.RELEASE)
                .put("inputOrigin", "PUBLIC_SHARED_APPLICATION_GATEWAY").put("controllerGenerated", false)
                .put("wholeCase", "NOT_QUALIFIED").put("icrVerification", false)
                .put("unasserted", JSONArray(listOf("editor/controller and installed shared-UI flow",
                    "SIDC-02(a) documentary PO context and other SIDC variants",
                    "foreign/security fixtures, races, faults and backup restoration",
                    "additional Client/RPC and transport Receipt", "device and owner campaign", "ICR", "central"))))
            owner.setup(installer, head, capturePassword)
            installer.fill('\u0000')

            // Enroll the first capture employee's exact native journal BEFORE another employee
            // is enrolled. Merely preparing BASE must not sign the capture employee in.
            val preparationClient = owner.openClient()
            check(preparationClient.signIns == 0 && !preparationClient.observer.hasNamedReadAccess)
            val base = NativeInvoiceCaptureBasePreparation(preparationClient.original,
                NativeInvoiceCaptureFixture.headLogin, evidence).prepare(head, fixturePassword)
            head.fill('\u0000')
            requireSameNativeFixtureCoordinates(owner.coordinates, base.coordinates)
            check(base.fixtureActorId != owner.coordinates.firstActorId && preparationClient.signIns == 0 &&
                !preparationClient.observer.hasNamedReadAccess && originals.all.isEmpty())
            requireFrozenBaseBusiness(base, base.snapshot)
            check(base.snapshot.invoiceDocuments.isEmpty())
            owner.closeOwned()
            check(!preparationClient.original.isCurrent() && !preparationClient.observer.hasNamedReadAccess)
            val before = census("SIDC02B_CLOSED_FULL_BUSINESS_BASE")
            before.requireBase(base)

            val captureClient = owner.openClient() // Genuine production admission of this complete original BASE.
            check(captureClient.signIns == 0 && !captureClient.observer.hasNamedReadAccess)
            evidence.event("SIDC02B_BASE_ADMITTED_BY_NORMAL_NATIVE_REOPEN")
            val captureSession = captureSignIn(captureClient, capturePassword)
            val issued = requireCaptureSession(captureClient, captureSession, "CAPTURE_SESSION_1", evidence)
            requireCapturePending(captureSession, emptySet())
            val supplier = readCaptureConfiguration(captureClient, captureSession, LocalConfigurationProfile.SUPPLIER,
                base.supplierId, "SIDC02B_CURRENT_SUPPLIER", evidence)
            check(supplier == base.snapshot.supplier)
            val b = readCaptureConfiguration(captureClient, captureSession, LocalConfigurationProfile.SKU,
                base.b.detail.skuId, "SIDC02B_CURRENT_SKU_B", evidence)
            val c = readCaptureConfiguration(captureClient, captureSession, LocalConfigurationProfile.SKU,
                base.c.detail.skuId, "SIDC02B_CURRENT_SKU_C", evidence)
            check(b.items.single() == base.b.item && b.detail == base.b.detail &&
                c.items.single() == base.c.item && c.detail == base.c.detail)
            check(readCaptureOrder(captureClient, captureSession, base.po.id,
                "SIDC02B_AVAILABLE_PO_NOT_SELECTED", evidence) == base.po)
            check(readCaptureInvoices(captureClient, captureSession, null,
                "SIDC02B_EMPTY_INVOICE_DIRECTORY", evidence).documents!!.isEmpty())

            val input = captureInput(base, supplier, b.detail as LocalConfigurationDetailV1.Sku,
                c.detail as LocalConfigurationDetailV1.Sku)
            requireFrozenCaptureInput(base, input.expected)
            evidence.bind("SIDC02B_EXPLICIT_APPLICATION_INPUT", JSONObject()
                .put("requestedOperationId", input.intent.operationId.value).put("requestedInvoiceId", input.expected.invoice.id.value)
                .put("requestedLineIds", JSONArray(input.expected.invoice.lines.map { it.lineId.value }))
                .put("dueDate", input.expected.invoice.dueDate.canonicalString())
                .put("sourceEvidenceId", input.expected.invoice.sourceEvidenceId.value)
                .put("dueDateAndEvidenceMeaning", "EXPLICIT_SYNTHETIC_INPUT_NOT_FROZEN_DEFAULT_OR_QUALIFIED_ATTACHMENT")
                .put("purchaseTaxSourceId", input.expected.invoice.lines.first().goods.tax.sourceId.value)
                .put("purchaseTaxRevision", input.expected.invoice.lines.first().goods.tax.revision.value)
                .put("purchaseTaxMeaning", "EXPLICIT_SYNTHETIC_ZERO_PURCHASING_INPUT_NOT_CATALOG_SALE_OR_FISCAL_AUTHORITY")
                .put("poContext", JSONObject.NULL).put("allocation", JSONObject.NULL).put("approval", JSONObject.NULL)
                .put("receipt", JSONObject.NULL).put("payment", JSONObject.NULL).put("controllerGenerated", false))
            val dispatchesBeforeStage = captureClient.observer.dispatches
            val staged = captureSession.workflows.stage(input.intent) as? LocalStageResult.Staged
                ?: error("The exact explicit capture input did not reach genuine native Stage")
            check(staged.operation.operationId == input.intent.operationId && captureClient.observer.dispatches == dispatchesBeforeStage)
            val original = originals.staged("K_SIDC02B", staged.operation, input.expected, captureClient.intentOwner())
            val savedPending = requireCapturePending(captureSession, setOf(original.operationId)).single()
            check(savedPending.phase == LocalPendingPhase.STAGED && savedPending.submitAllowed && savedPending.actionApproval == null)
            val savedIdentity = checkNotNull(original.identity)
            check(captureClient.observer.foundLookups == 0 && original.request == null && original.result == null)
            evidence.event("SIDC02B_ACTUAL_SAVED_ORIGINAL_BEFORE_SUBMIT", JSONObject()
                .put("operationId", savedIdentity.operationId.value).put("originalActorId", savedIdentity.originalActorId.value)
                .put("semanticDigestVersion", savedIdentity.semanticDigestVersion).put("semanticDigest", savedIdentity.semanticSha256)
                .put("dispatchesDuringStage", captureClient.observer.dispatches - dispatchesBeforeStage))
            val lookupsBeforeSubmit = captureClient.observer.lookups
            val applicationResult = requireCaptureOriginalResult(original,
                captureSession.workflows.submit(original.operationId), evidence)
            check(captureClient.observer.dispatches == dispatchesBeforeStage + 1 &&
                captureClient.observer.lookups == lookupsBeforeSubmit + 1 && originals.all.single() === original)
            val rawResult = checkNotNull(original.result)
            check(rawResult.authorityEpoch.value == before.epoch && !rawResult.replayed && original.identity == savedIdentity &&
                sameCaptureSession(issued, checkNotNull(original.session)))
            val immutableRequest = checkNotNull(original.request)
            val captured = readCaptureInvoices(captureClient, captureSession, original.expected.invoice.id,
                "SIDC02B_INDEPENDENT_PERSISTED_CAPTURE", evidence).documents!!.single()
            requireFrozenCapturedInvoice(base, original, captured)
            val afterCaptureSnapshot = NativeInvoiceCaptureBasePreparation(captureClient.original,
                NativeInvoiceCaptureFixture.headLogin, evidence).observeExisting(base, fixturePassword)
            requireFrozenBaseBusiness(base, afterCaptureSnapshot)
            requireSameBaseBusiness(base.snapshot, afterCaptureSnapshot)
            requireCaptureDirectory(afterCaptureSnapshot.invoiceDocuments, captured)
            check(requireCaptureSession(captureClient, captureSession, "CAPTURE_SESSION_1_AFTER_BASE_READS", evidence).sessionId == issued.sessionId)
            val recordedPending = requireCapturePending(captureSession, setOf(original.operationId)).single()
            check(recordedPending.phase == LocalPendingPhase.RECORDED_UNACKNOWLEDGED && !recordedPending.submitAllowed &&
                recordedPending.recordedDecision == LocalRecordedDecision.APPLIED && recordedPending.actionApproval == null)
            // No presentation ACK is substituted for persistence/recovery. Keep this actual
            // recorded native intent outstanding and recover it unchanged after real close.
            captureLogout(captureClient, captureSession, owner, evidence)
            owner.closeOwned()
            check(!captureClient.original.isCurrent() && !captureClient.observer.hasNamedReadAccess)
            val afterCapture = census("SIDC02B_CLOSED_ORIGINAL_CAPTURE_DELTA")
            afterCapture.requireCaptureDelta(before, base, original)

            val reopened = owner.openClient() // The complete capture history must pass the real scanner, too.
            check(reopened.signIns == 0 && !reopened.observer.hasNamedReadAccess && !captureSession.isCurrent())
            val recoveredSession = captureSignIn(reopened, capturePassword)
            val reissued = requireCaptureSession(reopened, recoveredSession, "CAPTURE_SESSION_2", evidence)
            check(recoveredSession !== captureSession && reissued.sessionId != issued.sessionId &&
                reissued.actorId == issued.actorId && reissued.scope == issued.scope && reissued.deviceId == issued.deviceId)
            val lookupsBeforeRecovery = reopened.observer.lookups
            val foundBeforeRecovery = reopened.observer.foundLookups
            val recovered = recoveredSession.workflows.lookup(original.operationId)
            requireSameCaptureApplicationResult(applicationResult, recovered)
            check(reopened.observer.lookups == lookupsBeforeRecovery + 1 && reopened.observer.foundLookups == foundBeforeRecovery + 1 &&
                reopened.observer.dispatches == 0 && original.identity == savedIdentity && original.request == immutableRequest)
            requireSameCaptureResult(rawResult, checkNotNull(original.result))
            check(requireCapturePending(recoveredSession, setOf(original.operationId)).single() == recordedPending)
            val recoveredInvoice = readCaptureInvoices(reopened, recoveredSession, original.expected.invoice.id,
                "SIDC02B_FRESH_REOPENED_INDEPENDENT_INVOICE", evidence).documents!!.single()
            requireFrozenCapturedInvoice(base, original, recoveredInvoice)
            check(recoveredInvoice == captured)
            val recoveredBase = NativeInvoiceCaptureBasePreparation(reopened.original,
                NativeInvoiceCaptureFixture.headLogin, evidence).observeExisting(base, fixturePassword)
            requireFrozenBaseBusiness(base, recoveredBase)
            requireSameBaseBusiness(base.snapshot, recoveredBase)
            requireCaptureDirectory(recoveredBase.invoiceDocuments, recoveredInvoice)
            check(reopened.observer.dispatches == 0)
            evidence.event("SIDC02B_FRESH_EXPLICIT_SESSION_ORIGINAL_FOUND", JSONObject()
                .put("operationId", original.operationId.value).put("originalSessionId", issued.sessionId.value)
                .put("newSessionId", reissued.sessionId.value).put("replacementStage", false).put("newCaptureDispatch", false)
                .put("clientPresentationAck", "NOT_RUN_NOT_USED_AS_PERSISTENCE_ORACLE"))
            captureLogout(reopened, recoveredSession, owner, evidence)
            owner.closeOwned()
            val final = census("SIDC02B_FINAL_NATIVE_CUT_AFTER_RECOVERY")
            afterCapture.requireSameBusiness(final)
            final.requireCaptureDelta(before, base, original)
            check(!reopened.original.isCurrent() && !reopened.observer.hasNamedReadAccess)
            evidence.event("SIDC02B_BOUNDED_NATIVE_ASSERTIONS_FINISHED", JSONObject()
                .put("distinctInvoiceClaimsAdded", 1).put("invoiceMinor", 34_000)
                .put("poStockValuePriceCashSettlementCreditApprovalEffects", 0).put("wholeCase", "NOT_QUALIFIED"))
            assertionsFinished = true
        } catch (caught: Throwable) { remember(caught) }
        finally {
            secrets.forEach { it.fill('\u0000') }
            try { owner.closeOwned(); cleanupConfirmed = true } catch (caught: Throwable) { remember(caught) }
            censuses.forEach { value -> try { value.close() } catch (caught: Throwable) { remember(caught) } }
            try {
                evidence.event("FINAL_CLEANUP", JSONObject().put("nativeCloseConfirmed", cleanupConfirmed)
                    .put("failureClass", failure?.javaClass?.name ?: JSONObject.NULL))
                evidence.status(if (assertionsFinished && cleanupConfirmed && failure == null)
                    "BOUNDED_NATIVE_ASSERTIONS_OBSERVED_WHOLE_CASE_NOT_QUALIFIED" else "HELD_NOT_VERIFIED")
            } catch (caught: Throwable) { remember(caught) }
        }
        failure?.let { throw it }
    }
}

private suspend fun captureSignIn(client: NativeInvoiceCaptureClient, password: CharArray): BranchUiSession {
    check(client.signIns == 0 && !client.observer.hasNamedReadAccess)
    val result = captureSecret(password) { client.gateway.signIn(NativeInvoiceCaptureFixture.login.value, it) }
    return when (result) {
        is SignInResult.Authenticated -> result.session
        is SignInResult.Rejected -> error("The capture employee did not acquire a genuine explicit application session")
    }
}

private suspend fun requireCaptureSession(client: NativeInvoiceCaptureClient, session: BranchUiSession,
    symbol: String, evidence: NativeInvoiceCaptureEvidence): LocalSessionView {
    val current = session.current() as? LocalSessionCheckResult.Current
        ?: error("The capture session failed fresh protected Current")
    val actual = client.observer.currentView
    val coordinates = client.original.material.coordinates
    check(client.signIns == 1 && session.isCurrent() && client.original.isCurrent() &&
        current.display == session.display && current.attachmentKey == session.attachmentKey &&
        actual.scope == coordinates.scope && actual.actorId == coordinates.firstActorId && actual.deviceId == coordinates.deviceId &&
        actual.login == NativeInvoiceCaptureFixture.login && current.display.actorId == actual.actorId &&
        current.display.companyId == actual.scope.companyId && current.display.branchId == actual.scope.branchId)
    NativeInvoiceCaptureFixture.requireCapturePolicy(actual.policy, coordinates)
    evidence.bind(symbol, JSONObject().put("sessionId", actual.sessionId.value).put("generation", actual.generation.value)
        .put("actorId", actual.actorId.value).put("branchId", actual.scope.branchId.value).put("deviceId", actual.deviceId.value)
        .put("policyRevision", actual.policy.revision.value).put("source", "ACTUAL_APPLICATION_ISSUANCE_AND_PROTECTED_CURRENT"))
    return actual
}

private suspend fun requireCapturePending(session: BranchUiSession, expected: Set<OperationId>): List<LocalPendingOperation> {
    val result = session.workflows.pending() as? LocalPendingResult.Available
        ?: error("The actual original native intent journal is unavailable")
    check(session.isCurrent() && result.nextCursor == null && result.operations.size == expected.size &&
        result.operations.map { it.operationId }.toSet() == expected)
    return result.operations
}

private suspend fun captureLogout(client: NativeInvoiceCaptureClient, session: BranchUiSession,
    owner: NativeLocalFixtureOwner<NativeInvoiceCaptureClient>, evidence: NativeInvoiceCaptureEvidence) {
    check(session.logout() == LocalSessionEndResult.REVOKED) { "The capture employee's authoritative logout is unconfirmed" }
    check(!session.isCurrent() && !client.observer.hasNamedReadAccess && session.current() is LocalSessionCheckResult.Unavailable)
    check(session.workflows.pending() is LocalPendingResult.Unavailable &&
        session.workflows.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
            purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.INVOICES))) is LocalReadResult.Unavailable)
    val cleanup = client.gateway.cleanupStatus()
    evidence.event("SIDC02B_ACTUAL_LOGOUT_CLEANUP", JSONObject().put("available", cleanup.available)
        .put("pendingRevocations", cleanup.pendingRevocations).put("authenticationUnresolved", cleanup.authenticationUnresolved))
    check(cleanup.clear) { "Authoritative capture credential cleanup remains pending" }
    owner.requireMasterStillOperational()
    owner.reobserveSetup()
}

private suspend fun captureNamedRead(client: NativeInvoiceCaptureClient, session: BranchUiSession,
    request: ReadRequest, label: String, evidence: NativeInvoiceCaptureEvidence): ReadReply.Data {
    check(session.isCurrent())
    val view = client.observer.currentView
    val reply = client.observer.namedRead(request)
    check(session.isCurrent() && sameCaptureSession(view, client.observer.currentView))
    evidence.event(label, JSONObject().put("query", request.query.name).put("queryVersion", request.query.version)
        .put("sessionId", view.sessionId.value).put("branchRevision", reply.branchRevision.value)
        .put("observedAt", reply.observedAt.canonicalString()).put("completeProtectedNativeReadReturned", true))
    return reply
}

private suspend fun readCaptureConfiguration(client: NativeInvoiceCaptureClient, session: BranchUiSession,
    kind: String, id: DomainId, label: String, evidence: NativeInvoiceCaptureEvidence): LocalConfigurationPageV1 {
    val request = ReadRequest(LocalConfigurationProfile.QUERY,
        LocalConfigurationCodec.query(LocalConfigurationQueryV1(kind, id = id, limit = 1)))
    val page = LocalConfigurationCodec.decodePage(captureNamedRead(client, session, request, label, evidence).payload)
    check(page.kind == kind && page.nextAfter == null && page.items.single().id == id && page.detail != null)
    return page
}

private suspend fun readCaptureOrder(client: NativeInvoiceCaptureClient, session: BranchUiSession,
    id: DomainId, label: String, evidence: NativeInvoiceCaptureEvidence): LocalPurchasingDocumentViewV1 {
    val request = ReadRequest(LocalPurchasingProfile.READ, LocalPurchasingCodec.query(LocalPurchasingQueryV1(
        LocalPurchasingSectionV1.ORDER, id = id, limit = 1, includeCosts = true, export = false)))
    val page = LocalPurchasingCodec.page(captureNamedRead(client, session, request, label, evidence).payload)
    check(page.availability == LocalPurchasingReadAvailabilityV1.AVAILABLE && page.smallerPageLimit == null &&
        page.section == LocalPurchasingSectionV1.ORDER && page.includesCosts && page.nextAfter == null)
    return checkNotNull(page.documents).single().also { check(it.id == id && it.orderId == id) }
}

private suspend fun readCaptureInvoices(client: NativeInvoiceCaptureClient, session: BranchUiSession,
    id: DomainId?, label: String, evidence: NativeInvoiceCaptureEvidence): LocalPurchasingInvoicePageV2 {
    val request = ReadRequest(LocalPurchasingProfile.READ_INVOICES, LocalPurchasingCodec.query(LocalPurchasingQueryV1(
        LocalPurchasingSectionV1.INVOICE, id = id, limit = 1, includeCosts = true, export = false)))
    val page = LocalPurchasingCodec.invoicePage(captureNamedRead(client, session, request, label, evidence).payload)
    check(page.availability == LocalPurchasingReadAvailabilityV1.AVAILABLE && page.smallerPageLimit == null &&
        page.includesCosts && page.nextAfter == null && page.documents != null)
    if (id != null) check(page.documents!!.single().let { it.legacy == null && it.capture?.id == id })
    return page
}

private class NativeCaptureInput(val intent: LocalCommandIntent, val expected: LocalCaptureSupplierInvoiceV2)

/** Exact form inputs, not a PO/default lookup or controller-generated draft. Purchase tax is a
 * separately authored synthetic zero snapshot. Its source/revision do not claim catalog authority. */
private fun captureInput(base: NativeInvoiceCaptureBase, supplier: LocalConfigurationPageV1,
    b: LocalConfigurationDetailV1.Sku, c: LocalConfigurationDetailV1.Sku): NativeCaptureInput {
    fun id(): DomainId = DomainId(UUID.randomUUID().toString())
    fun units(sku: LocalConfigurationDetailV1.Sku, purchaseUnit: DomainId): PurchaseUnitsV1 {
        val conversion = sku.conversions.single { it.sourceUnitId == purchaseUnit && it.targetUnitId == sku.baseUnitId }
        return PurchaseUnitsV1(sku.productId, purchaseUnit, sku.baseUnitId, sku.quantityScale,
            conversion.id, conversion.revision, conversion.rate, conversion.dimensionalEvidenceId)
    }
    val operation = OperationId(id().value)
    val invoiceId = id()
    val tax = PurchaseTaxV1(id(), Revision(0), "SYNTHETIC_ZERO", PercentageRate.parse("0"))
    val lines = listOf(
        PurchaseInvoiceCaptureLineV2(id(), PurchaseGoodsV1(b.skuId, "00000043-B", units(b, base.cartonUnitId),
            Quantity.parse(base.cartonUnitId, "2", 0), UnitPrice.parse(Currency.EGP, "120"), PercentageRate.parse("0"),
            tax, Money(Currency.EGP, 24_000), Money(Currency.EGP, 0)), PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY,
            internalAllocationId = null, supplierAllocationReference = null),
        PurchaseInvoiceCaptureLineV2(id(), PurchaseGoodsV1(c.skuId, "00000007-C", units(c, base.kilogramUnitId),
            Quantity.parse(base.kilogramUnitId, "2.5", 3), UnitPrice.parse(Currency.EGP, "40"), PercentageRate.parse("0"),
            tax, Money(Currency.EGP, 10_000), Money(Currency.EGP, 0)), PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY,
            internalAllocationId = null, supplierAllocationReference = null),
    )
    val invoice = PurchaseInvoiceCaptureV2(invoiceId, base.supplierId, NativeInvoiceCaptureFixture.invoiceReference,
        BusinessDate.parseCanonical(NativeInvoiceCaptureFixture.businessDate), BusinessDate.parseCanonical("2026-11-05"), id(),
        lines, emptyList(), internalOrderLink = null, supplierOrderReference = null)
    val expected = LocalCaptureSupplierInvoiceV2(invoice, supplier.items.single().storageRevision, NativeInvoiceCaptureFixture.reasonCode)
    fun value(field: LocalField, scalar: LocalScalarValue) = LocalScalarInput(field, scalar)
    fun lineInput(line: PurchaseInvoiceCaptureLineV2, quantity: String, net: String): LocalInputLine {
        val goods = line.goods
        return LocalInputLine(line.lineId, buildList {
            add(value(LocalField.MODE, LocalScalarValue.Choice("DOCUMENTARY_GOODS")))
            add(value(LocalField.SKU_ID, LocalScalarValue.Identifier(goods.skuId)))
            add(value(LocalField.PRODUCT_ID, LocalScalarValue.Identifier(goods.units.productId)))
            add(value(LocalField.UNIT, LocalScalarValue.Identifier(goods.units.purchaseUnitId)))
            add(value(LocalField.PURCHASE_BASE_UNIT, LocalScalarValue.Identifier(goods.units.baseUnitId)))
            add(value(LocalField.PURCHASE_BASE_SCALE, LocalScalarValue.Integer(goods.units.baseScale.toLong())))
            add(value(LocalField.CONVERSION_ID, LocalScalarValue.Identifier(checkNotNull(goods.units.conversionId))))
            add(value(LocalField.PURCHASE_CONVERSION_REVISION, LocalScalarValue.Integer(checkNotNull(goods.units.conversionRevision).value)))
            add(value(LocalField.PURCHASE_CONVERSION_RATE, LocalScalarValue.Text(goods.units.rate.canonicalString())))
            goods.units.dimensionalEvidenceId?.let { add(value(LocalField.EVIDENCE_ID, LocalScalarValue.Identifier(it))) }
            add(value(LocalField.QUANTITY, LocalScalarValue.Decimal(quantity)))
            add(value(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(goods.quantity.scale.toLong())))
            add(value(LocalField.SUPPLIER_ITEM_REFERENCE, LocalScalarValue.Text(goods.supplierItemReference)))
            add(value(LocalField.UNIT_PRICE, LocalScalarValue.Decimal(goods.unitPrice.amount.canonicalString())))
            add(value(LocalField.DISCOUNT_PERCENT, LocalScalarValue.Decimal(goods.discount.percent.canonicalString())))
            add(value(LocalField.TAX_SOURCE_ID, LocalScalarValue.Identifier(tax.sourceId)))
            add(value(LocalField.TAX_REVISION, LocalScalarValue.Integer(tax.revision.value)))
            add(value(LocalField.TAX_CATEGORY, LocalScalarValue.Text(tax.categoryCode)))
            add(value(LocalField.TAX_RATE, LocalScalarValue.Decimal(tax.rate.percent.canonicalString())))
            add(value(LocalField.NET_AMOUNT, LocalScalarValue.Decimal(net)))
            add(value(LocalField.TAX_AMOUNT, LocalScalarValue.Decimal("0")))
        })
    }
    val intent = LocalCommandIntent(operation, LocalAction.SUPPLIER_INVOICE_CAPTURE, 1, reference = null,
        values = listOf(value(LocalField.INVOICE_ID, LocalScalarValue.Identifier(invoice.id)),
            value(LocalField.SUPPLIER_ID, LocalScalarValue.Reference(LocalRecordReference(LocalRecordKind.SUPPLIER,
                base.supplierId, supplier.items.single().revision))),
            value(LocalField.EXTERNAL_REFERENCE, LocalScalarValue.Text(invoice.reference)),
            value(LocalField.BUSINESS_DATE, LocalScalarValue.Date(invoice.invoiceDate.canonicalString())),
            value(LocalField.DUE_DATE, LocalScalarValue.Date(invoice.dueDate.canonicalString())),
            value(LocalField.EVIDENCE_ID, LocalScalarValue.Identifier(invoice.sourceEvidenceId)),
            value(LocalField.REASON, LocalScalarValue.Text(expected.reasonCode))),
        lineField = LocalField.LINES, lines = listOf(lineInput(lines[0], "2", "240"), lineInput(lines[1], "2.5", "100")))
    check(intent.reference == null && intent.values.none { it.field in setOf(LocalField.PURCHASE_ID,
        LocalField.EXPECTED_ORDER_REVISION, LocalField.SUPPLIER_ORDER_REFERENCE) })
    return NativeCaptureInput(intent, expected)
}

private fun requireFrozenCaptureInput(base: NativeInvoiceCaptureBase, actual: LocalCaptureSupplierInvoiceV2) {
    val invoice = actual.invoice
    check(actual.expectedSupplierRevision == base.supplierStorageRevision && actual.reasonCode == NativeInvoiceCaptureFixture.reasonCode &&
        invoice.supplierId == base.supplierId && invoice.reference == "000073-C/فاتورة" &&
        invoice.invoiceDate == BusinessDate.parseCanonical("2026-11-05") && invoice.internalOrderLink == null &&
        invoice.supplierOrderReference == null && invoice.charges.isEmpty() && invoice.lines.size == 2 &&
        invoice.lines.map { it.goods.skuId }.toSet() == setOf(base.b.detail.skuId, base.c.detail.skuId) &&
        invoice.lines.none { it.goods.skuId == base.a.detail.skuId } && invoice.total() == Money(Currency.EGP, 34_000))
    for (line in invoice.lines) {
        check(line.provenance == PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY && line.internalAllocationId == null &&
            line.supplierAllocationReference == null)
        val b = line.goods.skuId == base.b.detail.skuId
        val sku = if (b) base.b.detail else base.c.detail
        val purchaseUnit = if (b) base.cartonUnitId else base.kilogramUnitId
        val conversion = sku.conversions.single { it.sourceUnitId == purchaseUnit && it.targetUnitId == sku.baseUnitId }
        val goods = line.goods
        check(goods.supplierItemReference == if (b) "00000043-B" else "00000007-C")
        check(goods.units.productId == sku.productId && goods.units.purchaseUnitId == purchaseUnit &&
            goods.units.baseUnitId == sku.baseUnitId && goods.units.baseScale == 0 && goods.units.conversionId == conversion.id &&
            goods.units.conversionRevision == conversion.revision && goods.units.dimensionalEvidenceId == null &&
            goods.units.rate == UnitConversionRate.ratio(if (b) 12 else 1_000, 1) && goods.units.rate == conversion.rate &&
            goods.quantity == if (b) Quantity(base.cartonUnitId, 2, 0) else Quantity(base.kilogramUnitId, 2_500, 3))
        check(goods.baseQuantity() == if (b) Quantity(base.eaUnitId, 24, 0) else Quantity(base.gramUnitId, 2_500, 0))
        check(goods.unitPrice == UnitPrice.parse(Currency.EGP, if (b) "120" else "40") && goods.discount == PercentageRate.parse("0") &&
            goods.tax.rate == PercentageRate.parse("0") && goods.net == Money(Currency.EGP, if (b) 24_000 else 10_000) &&
            goods.taxAmount == Money(Currency.EGP, 0) && goods.amountsAgree())
    }
}

private fun requireCaptureOriginalResult(original: NativeInvoiceCaptureOriginal, reply: LocalOperationResult,
    evidence: NativeInvoiceCaptureEvidence): LocalOperationResult.Recorded {
    val actual = reply as? LocalOperationResult.Recorded ?: error("The real capture original result is unresolved")
    val raw = checkNotNull(original.result)
    val result = LocalPurchasingCodec.captureResult(raw.result)
    val record = checkNotNull(actual.result)
    check(raw.committed && raw.code == "committed" && raw.physicalStart == null && result.code == "purchasing.invoice_captured" &&
        result.id == original.expected.invoice.id && result.revision != null && actual.operationId == original.operationId &&
        actual.decision == LocalRecordedDecision.APPLIED && actual.originalAction == LocalAction.SUPPLIER_INVOICE_CAPTURE &&
        actual.food == null && actual.returnStock == null && record.invoiceEntryKind == LocalInvoiceEntryKind.CAPTURE_ONLY_V2 &&
        record.reference == LocalRecordReference(LocalRecordKind.PURCHASE_INVOICE, result.id) &&
        record.status?.english == "UNMATCHED_UNAPPROVED" && record.values.single().value.english == result.revision!!.value.toString())
    evidence.bind("SIDC02B_ORIGINAL_RESULT", JSONObject().put("operationId", raw.operationId.value)
        .put("invoiceId", result.id.value).put("invoiceRevision", result.revision!!.value)
        .put("originalActorId", raw.originalActorId.value).put("authorityEpoch", raw.authorityEpoch.value)
        .put("sequence", raw.sequence.value).put("recordedAt", raw.recordedAt.canonicalString())
        .put("canonicalOriginalResult", captureBase64(raw.result)).put("physicalStart", JSONObject.NULL))
    return actual
}

private fun requireSameCaptureApplicationResult(expected: LocalOperationResult.Recorded, actual: LocalOperationResult) {
    check(actual is LocalOperationResult.Recorded && actual.operationId == expected.operationId && actual.decision == expected.decision &&
        actual.message == expected.message && actual.result == expected.result && actual.food == expected.food &&
        actual.returnStock == expected.returnStock && actual.originalAction == expected.originalAction) {
        "Fresh native original lookup changed the immutable application result"
    }
}

private fun requireFrozenCapturedInvoice(base: NativeInvoiceCaptureBase, original: NativeInvoiceCaptureOriginal,
    document: LocalPurchasingInvoiceDocumentV2) {
    check(document.legacy == null)
    val actual = checkNotNull(document.capture)
    val input = original.expected.invoice
    val raw = checkNotNull(original.result)
    val session = checkNotNull(original.session)
    val result = LocalPurchasingCodec.captureResult(raw.result)
    requireFrozenCaptureInput(base, original.expected)
    check(actual.id == result.id && actual.id == input.id && actual.revision == result.revision &&
        actual.supplierId == base.supplierId && actual.status == "UNMATCHED_UNAPPROVED" &&
        actual.date == input.invoiceDate && actual.dueDate == input.dueDate && actual.reference == input.reference &&
        actual.sourceEvidenceId == input.sourceEvidenceId && actual.internalOrderLink == null && actual.supplierOrderReference == null &&
        actual.operationId == original.operationId && actual.originalActorId == base.coordinates.firstActorId &&
        actual.originalActorId == raw.originalActorId && actual.recordedAt == raw.recordedAt && actual.policyRevision == session.policy.revision &&
        actual.discrepancies == listOf(PurchaseDiscrepancyV1.ORDER_LINE_REQUIRED) && actual.charges == emptyList<PurchaseChargeV1>() &&
        actual.supplierClaim == Money(Currency.EGP, 34_000) && actual.lines.map { it.lineId } == input.lines.map { it.lineId })
    for (line in actual.lines) {
        val expected = input.lines.single { it.lineId == line.lineId }
        val goods = expected.goods
        check(line.provenance == PurchaseInvoiceEntryProvenanceV2.DOCUMENTARY && line.internalAllocationId == null &&
            line.supplierAllocationReference == null && line.originalOrderLine == null && line.skuId == goods.skuId &&
            line.supplierItemReference == goods.supplierItemReference && line.units == goods.units &&
            line.originalQuantity == goods.quantity && line.baseQuantity == goods.baseQuantity() && line.unitPrice == goods.unitPrice &&
            line.discount == goods.discount && line.purchaseTax == goods.tax && line.claimedNet == goods.net && line.claimedTax == goods.taxAmount)
    }
}

/** The documented directory is header-only; exact-ID reads above independently prove all lines.
 * Do not compare that legitimate projection to a detail object or treat its empty lines as a
 * missing claim. Exactly one directory entry must preserve every actual detail header field. */
private fun requireCaptureDirectory(values: List<LocalPurchasingInvoiceDocumentV2>, detail: LocalPurchasingInvoiceDocumentV2) {
    val entry = values.single()
    check(entry.legacy == null && detail.legacy == null)
    val listed = checkNotNull(entry.capture)
    check(listed.lines.isEmpty() && listed == checkNotNull(detail.capture).copy(lines = emptyList()))
}

/** BASE expectations are the frozen business cut, not calculations copied from a response.
 * Global document/approval/receipt absence is additionally checked by complete native census. */
private fun requireFrozenBaseBusiness(base: NativeInvoiceCaptureBase, snapshot: NativeInvoiceCaptureBaseSnapshot) {
    check(snapshot.observedSession.actorId == base.fixtureActorId && snapshot.observedSession.scope == base.coordinates.scope &&
        snapshot.observedSession.deviceId == base.coordinates.deviceId && snapshot.observedSession.login == base.fixtureLogin)
    check(snapshot.supplier == base.snapshot.supplier && snapshot.warehouse == base.snapshot.warehouse &&
        snapshot.supplier.items.single().id == base.supplierId && snapshot.supplier.items.single().storageRevision == base.supplierStorageRevision &&
        snapshot.warehouse.items.single().id == base.warehouseId)
    val definitions = listOf(snapshot.aSku, snapshot.bSku, snapshot.cSku)
    check(definitions.map { it.detail.skuId }.distinct().size == 3 && definitions.map { it.detail.productId }.distinct().size == 3)
    for ((observed, expected, description) in listOf(
        Triple(snapshot.aSku, base.a, "Synthetic still water 500 ml"),
        Triple(snapshot.bSku, base.b, "Synthetic sparkling water 500 ml"),
        Triple(snapshot.cSku, base.c, "Synthetic bulk rice"),
    )) {
        check(observed.item == expected.item && observed.detail == expected.detail && observed.definition == expected.definition)
        val sku = observed.detail
        check(sku.names.english == description && sku.active && sku.canPurchase && sku.tracking == LocalTrackingV1.QUANTITY &&
            sku.quantityScale == 0 && checkNotNull(sku.variantValues).isEmpty() &&
            checkNotNull(sku.productDefinition).variantAxes.isEmpty() && sku.productDefinition!!.id == sku.productId)
    }
    check(snapshot.aSku.detail.baseUnitId == base.eaUnitId && snapshot.bSku.detail.baseUnitId == base.eaUnitId &&
        snapshot.cSku.detail.baseUnitId == base.gramUnitId)
    fun requireUnit(sku: NativeInvoiceCaptureBaseSku, id: DomainId, code: String, dimension: UnitDimension, precision: Int) {
        val unit = checkNotNull(sku.detail.unitDefinitions).single { it.id == id }
        check(unit.code.original == code && unit.dimension == dimension && unit.maximumScale == precision)
    }
    for (sku in listOf(snapshot.aSku, snapshot.bSku)) {
        requireUnit(sku, base.eaUnitId, "EA", UnitDimension.COUNT, 0)
        requireUnit(sku, base.cartonUnitId, "CARTON", UnitDimension.COUNT, 0)
        check(sku.detail.conversions.single { it.sourceUnitId == base.cartonUnitId && it.targetUnitId == base.eaUnitId }
            .rate == UnitConversionRate.ratio(12, 1))
    }
    requireUnit(snapshot.aSku, base.trayUnitId, "TRAY", UnitDimension.COUNT, 0)
    check(snapshot.aSku.detail.conversions.single { it.sourceUnitId == base.trayUnitId && it.targetUnitId == base.eaUnitId }
        .rate == UnitConversionRate.ratio(6, 1))
    requireUnit(snapshot.cSku, base.gramUnitId, "G", UnitDimension.MASS, 0)
    requireUnit(snapshot.cSku, base.kilogramUnitId, "KG", UnitDimension.MASS, 3)
    check(snapshot.cSku.detail.conversions.single { it.sourceUnitId == base.kilogramUnitId && it.targetUnitId == base.gramUnitId }
        .rate == UnitConversionRate.ratio(1_000, 1))
    requireFrozenStock(base, snapshot.aSku, snapshot.a, 100, 100_000)
    requireFrozenStock(base, snapshot.bSku, snapshot.b, 40, 80_000)
    requireFrozenStock(base, snapshot.cSku, snapshot.c, 10_000, 40_000)
    val ordered = base.originalOrder.lines.single()
    val po = snapshot.po
    val line = po.lines.single()
    val zeroQuantity = Quantity(base.eaUnitId, 0, 0)
    val zeroMoney = Money(Currency.EGP, 0)
    check(base.originalOrder.reference == "PO-A" && base.originalOrder.supplierId == base.supplierId &&
        ordered.goods.skuId == base.a.detail.skuId && ordered.goods.supplierItemReference == "PO-000042" &&
        ordered.goods.units.productId == base.a.detail.productId && ordered.goods.units.purchaseUnitId == base.cartonUnitId &&
        ordered.goods.units.baseUnitId == base.eaUnitId && ordered.goods.units.rate == UnitConversionRate.ratio(12, 1) &&
        ordered.goods.quantity == Quantity(base.cartonUnitId, 2, 0) && ordered.goods.unitPrice == UnitPrice.parse(Currency.EGP, "120") &&
        ordered.goods.net == Money(Currency.EGP, 24_000) && ordered.goods.taxAmount == zeroMoney)
    check(po == base.po && po.status == "APPROVED" && po.orderId == base.originalOrder.id &&
        po.supplierId == base.supplierId && po.progressRevision == null && po.payableRevision == null && po.discrepancies.isEmpty() &&
        line.skuId == base.a.detail.skuId && line.warehouseId == base.warehouseId && line.lineId == ordered.allocationId &&
        line.allocationId == ordered.allocationId && line.units == ordered.goods.units && line.originalQuantity == ordered.goods.quantity &&
        line.baseQuantity == Quantity(base.eaUnitId, 24, 0) && line.matchedQuantity == zeroQuantity && line.orderBillingRevision != null &&
        line.unitPrice == ordered.goods.unitPrice && line.purchaseTax == ordered.goods.tax && line.discount == PercentageRate.parse("0") &&
        line.claimedNet == Money(Currency.EGP, 24_000) && line.claimedTax == zeroMoney && line.matchedNet == zeroMoney && line.matchedTax == zeroMoney)
    // Public received fields truthfully remain absent without stock consumption facts. Do not
    // manufacture a zero-valued allowance/receipt revision to make the projection look complete.
    check(line.receivedCommitted == null && line.receivedAccepted == null && line.inspectionPending == null)
    val basisPage = snapshot.poReceivingBasis
    val basis = (basisPage.detail as? LocalConfigurationDetailV1.ReceivingBasis)?.value
        ?: error("The real original PO receiving allowance is unavailable")
    val allocation = basis.lines.single()
    check(basisPage == base.snapshot.poReceivingBasis && basisPage.kind == LocalConfigurationProfile.BASIS && basisPage.nextAfter == null &&
        basisPage.items.single().id == base.originalOrder.id && basis.id == base.originalOrder.id &&
        basis.kind == LocalBasisKindV1.LOCAL_PURCHASE && basis.supplierId == base.supplierId &&
        basis.validFrom == base.originalOrder.validFrom && basis.validUntil == base.originalOrder.validUntil &&
        allocation.allocationId == ordered.allocationId && allocation.skuId == base.a.detail.skuId &&
        allocation.warehouseId == base.warehouseId && allocation.maximumBaseQuantity == Quantity(base.eaUnitId, 24, 0) &&
        allocation.maximumOwnedValue == Money(Currency.EGP, 24_000))
    requireFrozenOpening(base, snapshot.openingReceipt)
    val drawer = snapshot.drawer
    check(drawer.id == base.cashSessionId && drawer.drawerId == base.drawerId && drawer.custodianId == base.fixtureActorId &&
        drawer.status == "OPEN" && drawer.opening == Money(Currency.EGP, 500_000) && drawer.expectedCash == Money(Currency.EGP, 500_000) &&
        drawer.pendingAttemptIds.isEmpty() && drawer.pendingTransferIds.isEmpty() &&
        listOf(drawer.reservedCashOut, drawer.received, drawer.change, drawer.refunds, drawer.payIns, drawer.payOuts,
            drawer.safeDrops, drawer.externalCollections, drawer.externalRefunds).all { it == zeroMoney } &&
        drawer.closingExpected == null && drawer.counted == null && drawer.variance == null && drawer.retainedFloat == null &&
        drawer.actualSafeDelivery == null && drawer.closingTransferId == null && snapshot.ownCash.custodianId == base.fixtureActorId &&
        snapshot.ownCash.session == drawer && snapshot.ownCash.unsettledOrderIds.isEmpty() && snapshot.ownCash.unresolvedReturnIds.isEmpty())
    for ((sku, price) in listOf(snapshot.aSku to snapshot.aPrice, snapshot.bSku to snapshot.bPrice, snapshot.cSku to snapshot.cPrice))
        check(price.skuId == sku.detail.skuId && price.selected != null && price.effectiveUnitPrice != null)
    check(snapshot.aPrice == base.snapshot.aPrice && snapshot.bPrice == base.snapshot.bPrice && snapshot.cPrice == base.snapshot.cPrice &&
        snapshot.payables.isEmpty() && snapshot.supplierAdvances.isEmpty() && snapshot.ownSupplierCash.isEmpty())
}

private fun requireFrozenStock(base: NativeInvoiceCaptureBase, sku: NativeInvoiceCaptureBaseSku,
    stock: NativeInvoiceCaptureBaseStock, quantity: Long, minor: Long) {
    val expected = Quantity(sku.detail.baseUnitId, quantity, 0)
    val zero = Quantity(sku.detail.baseUnitId, 0, 0)
    val rows = stock.quantities.flatMap { it.rows }
    val costs = stock.costs.flatMap { it.rows }
    check(stock.quantities.isNotEmpty() && stock.costs.isNotEmpty() && stock.quantities.last().nextAfter == null && stock.costs.last().nextAfter == null &&
        stock.quantities.all { it.skuId == sku.detail.skuId && it.itemStorageRevision != null &&
            it.unitId == sku.detail.baseUnitId && it.quantityScale == 0 } &&
        stock.costs.all { it.skuId == sku.detail.skuId && it.itemStorageRevision == stock.quantities.first().itemStorageRevision })
    check(rows.isNotEmpty() && rows.map { it.positionId }.distinct().size == rows.size && rows.all {
        it.position.skuId == sku.detail.skuId && it.position.warehouseId == base.warehouseId && it.position.supplierId == null &&
            it.position.custody == StockCustody.RELEASED && it.position.lotId == null && it.position.serialId == null &&
            it.reserved == zero && it.safety == StockSafety.CLEAR && it.heldQuantity == zero && it.heldShortageQuantity == zero &&
            it.expiresAt == null && !it.expiredAtRead && it.serialCode == null && it.serialRegistrationStatus == null
    })
    check(rows.fold(zero) { total, row -> total + row.onHand } == expected &&
        rows.fold(zero) { total, row -> total + row.available } == expected && costs.isNotEmpty() &&
        costs.map { it.poolId }.distinct().size == costs.size && costs.all { it.warehouseId == base.warehouseId } &&
        costs.fold(zero) { total, row -> total + row.quantity } == expected &&
        costs.fold(Money(Currency.EGP, 0)) { total, row -> total + row.carryingValue } == Money(Currency.EGP, minor))
}

private fun requireFrozenOpening(base: NativeInvoiceCaptureBase, receipt: LocalStockReceiptView) {
    val original = base.operations.single { it.label == "opening_stock" }
    val body = LocalStockRelationalCommandsV2.decode(original.request.payload) as? LocalStockRelationalCommand.Receive
        ?: error("The retained real opening request is unavailable")
    val requested = body.request
    check(body.opening && body.operationId == original.request.operationId && receipt.receiptId == base.openingReceiptId &&
        receipt.receiptId == requested.receiptId && receipt.basisId == requested.basisId && receipt.basisId != base.po.id &&
        receipt.basisRevision == requested.expectedBasisRevision && receipt.basisKind == LocalStockBasisKind.OPENING &&
        receipt.supplierId == null && receipt.recordedBy == base.fixtureActorId && receipt.receivedAt == original.result.recordedAt &&
        receipt.reasonCode == requested.reasonCode && receipt.costsIncluded && receipt.lines.size == 3 &&
        receipt.lines.map { it.lineId }.toSet() == requested.lines.map { it.lineId }.toSet())
    for (line in receipt.lines) {
        val source = requested.lines.single { it.lineId == line.lineId }
        val index = listOf(base.a.detail.skuId, base.b.detail.skuId, base.c.detail.skuId).indexOf(line.skuId)
        check(index >= 0)
        val unit = if (index == 2) base.gramUnitId else base.eaUnitId
        check(line.allocationId == source.allocationId && line.sourceId == source.sourceId && line.warehouseId == base.warehouseId &&
            line.skuId == source.skuId && line.recordedQuantity == source.quantity && line.baseQuantity == source.quantity &&
            line.baseQuantity == Quantity(unit, listOf(100L, 40L, 10_000L)[index], 0) &&
            line.disposition == LocalReceiptDisposition.RELEASED && line.originalValue == source.originalValue &&
            line.originalValue == Money(Currency.EGP, listOf(100_000L, 80_000L, 40_000L)[index]) && line.conversion == null &&
            line.lotId == null && line.expiresAt == null && line.serialId == null && line.serialCode == null &&
            line.initialSerialRegistrationStatus == null && line.releasedFromQuarantine == Quantity(unit, 0, 0))
    }
}

/** Compare every immutable business projection, not wrapper object identity or new session.
 * The invoice directory is deliberately checked separately because one real claim is added. */
private fun requireSameBaseBusiness(before: NativeInvoiceCaptureBaseSnapshot, after: NativeInvoiceCaptureBaseSnapshot) {
    check(before.supplier == after.supplier && before.warehouse == after.warehouse && before.po == after.po &&
        before.poReceivingBasis == after.poReceivingBasis && before.drawer == after.drawer && before.ownCash == after.ownCash &&
        before.aPrice == after.aPrice && before.bPrice == after.bPrice && before.cPrice == after.cPrice &&
        before.payables == after.payables && before.supplierAdvances == after.supplierAdvances && before.ownSupplierCash == after.ownSupplierCash)
    for ((a, b) in listOf(before.aSku to after.aSku, before.bSku to after.bSku, before.cSku to after.cSku))
        check(a.item == b.item && a.detail == b.detail && a.definition == b.definition)
    fun stock(a: NativeInvoiceCaptureBaseStock, b: NativeInvoiceCaptureBaseStock) {
        check(a.quantities.size == b.quantities.size && a.costs.size == b.costs.size)
        a.quantities.zip(b.quantities).forEach { (x, y) ->
            check(x.skuId == y.skuId && x.itemStorageRevision == y.itemStorageRevision && x.unitId == y.unitId &&
                x.quantityScale == y.quantityScale && x.nextAfter == y.nextAfter && x.rows == y.rows)
        }
        a.costs.zip(b.costs).forEach { (x, y) ->
            check(x.skuId == y.skuId && x.itemStorageRevision == y.itemStorageRevision && x.nextAfter == y.nextAfter && x.rows == y.rows)
        }
    }
    stock(before.a, after.a); stock(before.b, after.b); stock(before.c, after.c)
    val a = before.openingReceipt
    val b = after.openingReceipt
    check(a.receiptId == b.receiptId && a.storageRevision == b.storageRevision && a.basisId == b.basisId &&
        a.basisRevision == b.basisRevision && a.basisKind == b.basisKind && a.supplierId == b.supplierId && a.recordedBy == b.recordedBy &&
        a.receivedAt == b.receivedAt && a.reasonCode == b.reasonCode && a.costsIncluded == b.costsIncluded && a.lines == b.lines)
}
