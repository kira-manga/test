@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.capturedinvoicematchingv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.app.ui.localWorkflows
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Supporting shared UI wiring for CCM v1.0.1 and binding v1.0.0. See README for the bounded case map. */
class CapturedInvoiceMatchingUiProfileSupportingTest {
    /** CCM-08/11: installed presentation restricts routes; these display values grant no Master access. */
    @Test
    fun ccm08Ccm11MatchingRoutesAndWorkflowRemainRestricted() {
        val display = CapturedMatchingUiSession(CapturedMatchingUiPort()).display
        val navigator = AppNavigator()
        navigator.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, display)
        assertEquals(AppRoute.Purchasing, navigator.state.value.activeEntry.route)
        assertEquals(setOf(AppArea.PURCHASING), navigator.state.value.stacks.keys)
        assertTrue(invoiceMatchingUiRoute(AppRoute.Purchasing))
        assertTrue(invoiceMatchingUiRoute(AppRoute.SessionAccess))
        for (route in listOf(AppRoute.Start, AppRoute.BranchSignIn, AppRoute.CompanySignIn, AppRoute.LocalEnrollment,
            AppRoute.Cashier, AppRoute.QuotePreview, AppRoute.Orders, AppRoute.Order(matchingOrder),
            AppRoute.Food, AppRoute.FoodOrder(matchingOrder), AppRoute.FoodStation(matchingOrder),
            AppRoute.FoodHandoff(matchingOrder), AppRoute.Drawer, AppRoute.Stock, AppRoute.Reports,
            AppRoute.Setup, AppRoute.SetupStep(0), AppRoute.Catalog, AppRoute.People, AppRoute.Devices,
            AppRoute.CompanySettings)) {
            assertFalse(invoiceMatchingUiRoute(route), route.toString())
            assertEquals(NavigationResult.IGNORED,
                navigator.navigate(NavigationAction.Push(route), navigator.token()), route.toString())
        }
        assertEquals(listOf(LocalWorkflow.PURCHASING),
            localWorkflows(AppArea.PURCHASING, LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY))
        for (area in AppArea.entries.filterNot { it == AppArea.PURCHASING }) {
            assertTrue(localWorkflows(area, LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY).isEmpty(), area.name)
            assertEquals(NavigationResult.IGNORED,
                navigator.navigate(NavigationAction.SelectArea(area), navigator.token()), area.name)
        }
        assertEquals(matchingAction, LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY.invoiceUiAction())
        assertEquals(LocalAction.SUPPLIER_INVOICE_CAPTURE,
            LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY.invoiceUiAction())
        navigator.replaceRoot(SecurityRoot.BRANCH_WORKSPACE, display.copy(allowedAreas = emptySet()))
        assertEquals(AppRoute.SessionAccess, navigator.state.value.activeEntry.route)
        assertEquals(setOf(AppArea.SESSION), navigator.state.value.stacks.keys)
    }

    /** CCM-08/11: the actual controller issues invoice/PO reads and rejects the other sections. */
    @Test
    fun ccm08Ccm11ControllerReadsOnlyInvoiceAndPoSections() = runTest {
        withCapturedMatchingUi(initialWorkflow = LocalWorkflow.CATALOG) {
            assertTrue(port.reads.isEmpty())
            controller.activate(LocalWorkflow.PURCHASING)
            runCurrent()
            assertEquals(LocalPurchasingSection.INVOICES, controller.state.value.purchasingQuery.section)
            val calls = port.callCounts()
            for (workflow in LocalWorkflow.entries.filterNot { it == LocalWorkflow.PURCHASING })
                controller.selectWorkflow(workflow, runtime.navigator.token())
            for (section in LocalPurchasingSection.entries.filterNot {
                it in listOf(LocalPurchasingSection.INVOICES, LocalPurchasingSection.ORDERS)
            }) controller.selectPurchasing(LocalPurchasingUiQuery(section), runtime.navigator.token())
            runCurrent()
            assertEquals(calls, port.callCounts())
            controller.selectPurchasing(LocalPurchasingUiQuery(LocalPurchasingSection.ORDERS), runtime.navigator.token())
            runCurrent()
            assertEquals(LocalPurchasingSection.ORDERS, port.reads.last().purchasing?.section)
            controller.selectRecord(matchingOrderReference, runtime.navigator.token())
            runCurrent()
            assertEquals(matchingOrderReference, port.reads.last().reference)
            assertTrue(controller.state.value.page?.forms?.isEmpty() == true)
            controller.selectPurchasing(LocalPurchasingUiQuery(LocalPurchasingSection.INVOICES), runtime.navigator.token())
            runCurrent()
            assertTrue(port.reads.all { it.workflow == LocalWorkflow.PURCHASING &&
                it.purchasing?.section in listOf(LocalPurchasingSection.INVOICES, LocalPurchasingSection.ORDERS) })
            assertTrue(port.stages.isEmpty())
            assertTrue(port.submissions.isEmpty())
        }
    }

    /** CCM-08/11: a foreign incoming form cannot expose capture, legacy matching or configuration. */
    @Test
    fun ccm08Ccm11ExcludedPagesNeverOpenOrStage() = runTest {
        for (action in listOf(LocalAction.SUPPLIER_INVOICE_CAPTURE, LocalAction.SUPPLIER_INVOICE_MATCH,
            LocalAction.SUPPLIER_INVOICE_RECORD, LocalAction.SUPPLIER_CREATE)) {
            withCapturedMatchingUi(reference = matchingInvoiceAReference, configure = { port ->
                port.readReply = { LocalReadResult.Available(matchingPage(matchingInvoiceAReference)
                    .copy(forms = listOf(matchingForm().copy(action = action)))) }
            }) {
                assertNull(controller.state.value.page, action.name)
                assertNotNull(controller.state.value.readProblem, action.name)
                controller.openForm(action, runtime.navigator.token())
                controller.openForm(matchingAction, runtime.navigator.token())
                controller.openPurchasingDocument(LocalPurchasingDocumentKind.PRIMARY, runtime.navigator.token())
                controller.openReferencePicker(LocalField.INVOICE_LINE_ID, runtime.navigator.token())
                controller.stage(runtime.navigator.token())
                runCurrent()
                assertNull(controller.state.value.form)
                assertFalse(controller.state.value.purchasingDocuments.hasDraft)
                assertNull(controller.state.value.referencePicker)
                assertNull(controller.retailPhysical.snapshotDraft())
                assertNull(controller.supplierPhysical.snapshotDraft())
                assertTrue(port.stages.isEmpty())
                assertTrue(port.submissions.isEmpty())
                assertTrue(port.referenceReads.isEmpty())
            }
        }
    }

    /** CCM-03/11: preserve scripted raw24/240 and separate recognized22/220/unmatched2/20; no payment. */
    @Test
    fun ccm03Ccm11ReadOnlyOverlayPreservesRawClaimAndRecognizedProgress() = runTest {
        val quality = matchingQualityPage()
        withCapturedMatchingUi(reference = matchingInvoiceAReference, configure = { port ->
            port.readReply = { LocalReadResult.Available(quality) }
        }) {
            val page = assertNotNull(controller.state.value.page)
            val rawClaim = page.records.single { it.reference == matchingInvoiceAReference }
            assertEquals(matchingRawClaim, rawClaim)
            assertEquals(listOf("00042-QUALITY", "2026-11-05", "2 CARTON = 24 EACH", "EGP 240"),
                rawClaim.values.map { it.value.english })
            val overlay = page.records.single { it.reference.kind == LocalRecordKind.PURCHASE_MATCH }
            assertEquals(matchingRecognitionOverlay, overlay)
            assertEquals(listOf("22 EACH", "EGP 220", "2 EACH / EGP 20", "EGP 0", "EGP 220", "false"),
                overlay.values.map { it.value.english })
            assertEquals("UNMATCHED_UNAPPROVED", page.records.first().status?.english)
            assertEquals("PARTIALLY_MATCHED_RECOGNIZED", overlay.status?.english)
            assertFalse(overlay.selectable)
            assertFalse(quality.copy(records = quality.records.map {
                if (it.reference.kind == LocalRecordKind.PURCHASE_MATCH) it.copy(selectable = true) else it
            }).isInvoiceUiPage(LocalPurchasingSection.INVOICES, matchingAction, matchingInvoiceAReference))
            assertEquals(listOf(matchingAction), page.forms.map { it.action })
            val calls = port.callCounts()
            controller.selectRecord(overlay.reference, runtime.navigator.token())
            controller.openReferencePicker(LocalField.INVOICE_LINE_ID, runtime.navigator.token())
            runCurrent()
            assertEquals(matchingInvoiceAReference, controller.state.value.reference)
            assertEquals(calls, port.callCounts())
            assertNull(controller.state.value.referencePicker)
            assertNull(controller.retailPhysical.snapshotDraft())
            assertNull(controller.supplierPhysical.snapshotDraft())
            assertTrue(port.stages.isEmpty())
            assertTrue(port.submissions.isEmpty())
        }
    }

    /** CCM-11: changing or clearing the invoice removes its old form before a delayed protected read. */
    @Test
    fun ccm11ChangingAndClearingInvoiceRetiresPageBeforeReread() = runTest {
        var delayed: CompletableDeferred<LocalReadResult>? = null
        withCapturedMatchingUi(reference = matchingInvoiceAReference, configure = { port ->
            port.readReply = { query -> delayed?.await() ?: LocalReadResult.Available(matchingPage(query.reference)) }
        }) {
            for (selection in listOf(matchingInvoiceBReference, null)) {
                val oldPage = assertNotNull(controller.state.value.page)
                assertTrue(oldPage.forms.isNotEmpty())
                val response = CompletableDeferred<LocalReadResult>()
                delayed = response
                controller.selectRecord(selection, runtime.navigator.token())
                assertEquals(selection, controller.state.value.reference)
                assertNull(controller.state.value.page)
                assertNull(controller.state.value.form)
                runCurrent()
                assertEquals(selection, port.reads.last().reference)
                assertTrue(controller.state.value.working)
                assertNull(controller.state.value.page)
                controller.openForm(matchingAction, runtime.navigator.token())
                controller.stage(runtime.navigator.token())
                assertTrue(port.stages.isEmpty())
                delayed = null
                response.complete(LocalReadResult.Available(matchingPage(selection)))
                runCurrent()
                val freshPage = assertNotNull(controller.state.value.page)
                assertNotSame(oldPage, freshPage)
                assertNull(controller.state.value.readProblem)
                assertNull(controller.state.value.form)
                assertEquals(selection?.let { listOf(matchingAction) }.orEmpty(), freshPage.forms.map { it.action })
            }
            assertTrue(port.submissions.isEmpty())
        }
    }

    /** CCM-10/11: incompatible saved work is unavailable, never an apparently successful empty scan. */
    @Test
    fun ccm10Ccm11MixedPendingOriginalsRemainUnavailable() = runTest {
        val matching = LocalPendingOperation(OperationId("71000000-0000-4000-8000-000000000201"),
            matchingAction, LocalText("Matching original", ""), matchingSummary, submitAllowed = true)
        val capture = LocalPendingOperation(OperationId("71000000-0000-4000-8000-000000000202"),
            LocalAction.SUPPLIER_INVOICE_CAPTURE, LocalText("Capture original", ""), matchingSummary, submitAllowed = true)
        val mixed = LocalPendingResult.Available(listOf(matching, capture))
        withCapturedMatchingUi(reference = matchingInvoiceAReference, configure = { it.pendingReply = mixed }) {
            assertFalse(controller.state.value.pendingLoaded)
            assertNotNull(controller.state.value.pendingProblem)
            assertTrue(controller.state.value.recoveryRequired)
            assertFalse(controller.state.value.canStart)
            assertSame(mixed, port.pendingReply)
            assertEquals(listOf(matching, capture), mixed.operations)
            controller.openForm(matchingAction, runtime.navigator.token())
            controller.stage(runtime.navigator.token())
            for (id in listOf(matching.operationId, capture.operationId)) {
                controller.submit(id, runtime.navigator.token())
                controller.lookup(id, runtime.navigator.token())
                controller.acknowledgeRecorded(id, runtime.navigator.token())
            }
            runCurrent()
            assertTrue(port.stages.isEmpty())
            assertTrue(port.submissions.isEmpty())
            assertTrue(port.lookups.isEmpty())
            assertTrue(port.acknowledgements.isEmpty())
            assertEquals(listOf(matching, capture), mixed.operations)
        }
    }

    /** CCM-08/10/11: unavailable continuations preserve original identity/input across UI deactivation. */
    @Test
    fun ccm08Ccm10Ccm11IncompatibleOriginalSurvivesDeactivationAndRetirement() = runTest {
        val id = OperationId("71000000-0000-4000-8000-000000000201")
        val unresolved = LocalOperationResult.Unresolved(id, LocalServiceProblem.UNAVAILABLE)
        val base = LocalWorkflowContinuation(LocalWorkspaceState(workflow = LocalWorkflow.PURCHASING,
            reference = matchingInvoiceAReference, purchasingQuery = LocalPurchasingUiQuery(LocalPurchasingSection.INVOICES),
            operationId = id, operation = unresolved), null, matchingAction, null, null)
        val unsupportedVersion = LocalCommandIntent(id, matchingAction, 2, matchingInvoiceAReference, emptyList())
        val physical = LocalRetailDraftContinuation(LocalWorkflow.CHECKOUT, null, null, null, "", null,
            LocalRetailObservationDraft(), "", id)
        val variants = listOf(base.copy(originalAction = LocalAction.SUPPLIER_INVOICE_CAPTURE),
            base.copy(workspace = base.workspace.copy(workflow = LocalWorkflow.RECEIVING)),
            base.copy(frozen = unsupportedVersion), base.copy(retail = physical))
        for (original in variants) withCapturedMatchingUi(continuation = original) {
            repeat(2) { round ->
                assertFalse(controller.invoiceMatchingWorkspaceCompatible())
                assertEquals(original.workspace, controller.state.value)
                assertSame(unresolved, controller.state.value.operation)
                val token = runtime.navigator.token()
                controller.refresh(token)
                controller.selectWorkflow(LocalWorkflow.PURCHASING, token)
                controller.selectRecord(null, token)
                controller.selectPurchasing(LocalPurchasingUiQuery(LocalPurchasingSection.ORDERS), token)
                controller.openForm(matchingAction, token)
                controller.stage(token)
                controller.retryStage(token)
                controller.submit(id, token)
                controller.lookup(id, token)
                controller.acknowledgeRecorded(id, token)
                controller.reviewAttachment(token)
                runCurrent()
                assertEquals(List(7) { 0 }, port.callCounts(), "inert round $round")
                controller.deactivate()
                runCurrent()
                if (round == 0) { controller.activate(LocalWorkflow.PURCHASING); runCurrent() }
            }
            val retired = assertNotNull(controller.retireForAttachment())
            assertEquals(original, retired)
            assertSame(unresolved, retired.workspace.operation)
            val oldCallback = runtime.navigator.token()
            controller.activate(LocalWorkflow.PURCHASING)
            controller.submit(id, oldCallback)
            controller.lookup(id, oldCallback)
            runCurrent()
            assertEquals(List(7) { 0 }, port.callCounts())
            assertEquals(original, retired)
        }
    }

    /** CCM-10/11: rapid duplicate input and a lost reply retain one operation and explicit original lookup. */
    @Test
    fun ccm10Ccm11RapidStageAndLostReplyKeepSingleOriginalForLookup() = runTest {
        withCapturedMatchingUi(reference = matchingInvoiceAReference, configure = { port ->
            port.stageReply = { LocalStageResult.Staged(LocalPendingOperation(it.operationId, matchingAction,
                LocalText("Original matching request", ""), matchingSummary, submitAllowed = true)) }
            port.lookupReply = { LocalOperationResult.Recorded(it, LocalRecordedDecision.APPLIED,
                LocalText("purchasing.captured_invoice_matched_recognized", ""),
                matchingRecognitionOverlay.copy(reference = matchingInvoiceAReference), replayed = true, originalAction = matchingAction) }
        }) {
            controller.openForm(matchingAction, runtime.navigator.token())
            assertNotNull(controller.state.value.form)
            val token = runtime.navigator.token()
            controller.stage(token)
            controller.stage(token)
            runCurrent()
            val intent = port.stages.single()
            assertTrue(intent.isInvoiceUiIntent(matchingAction))
            assertEquals(matchingInvoiceAReference, intent.reference)
            assertEquals(LocalScalarValue.Reference(matchingLineReference()),
                intent.values.single { it.field == LocalField.INVOICE_LINE_ID }.value)
            assertEquals(LocalScalarValue.Decimal("22"), intent.values.single { it.field == LocalField.QUANTITY }.value)
            assertTrue(port.submissions.isEmpty())
            controller.edit(LocalField.QUANTITY, LocalDraftValue("24"), runtime.navigator.token())
            controller.selectRecord(matchingInvoiceBReference, runtime.navigator.token())
            assertEquals(matchingInvoiceAReference, controller.state.value.reference)
            assertEquals("22", controller.state.value.form?.values?.get(LocalField.QUANTITY)?.text)
            val submitToken = runtime.navigator.token()
            controller.submit(intent.operationId, submitToken)
            controller.submit(intent.operationId, submitToken)
            runCurrent()
            assertEquals(listOf(intent.operationId), port.submissions)
            assertEquals(intent.operationId, controller.state.value.operationId)
            assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation)
            controller.submit(intent.operationId, runtime.navigator.token())
            controller.stage(runtime.navigator.token())
            runCurrent()
            assertEquals(1, port.stages.size)
            assertEquals(1, port.submissions.size)
            controller.lookup(intent.operationId, runtime.navigator.token())
            runCurrent()
            val recovered = assertIs<LocalOperationResult.Recorded>(controller.state.value.operation)
            assertEquals(intent.operationId, recovered.operationId)
            assertEquals(matchingAction, recovered.originalAction)
            assertTrue(recovered.replayed)
            assertEquals(matchingRecognitionOverlay.values, recovered.result?.values)
            assertEquals(listOf("22 EACH", "EGP 220", "2 EACH / EGP 20", "EGP 0", "EGP 220", "false"),
                recovered.result?.values?.map { it.value.english })
            assertEquals(listOf(intent.operationId), port.lookups)
            assertTrue(port.acknowledgements.isEmpty())
            assertEquals("UNMATCHED_UNAPPROVED", matchingRawClaim.status?.english)
            assertEquals(1, port.stages.size)
            assertEquals(1, port.submissions.size)
        }
    }

    /** CCM-10/11: uncertain staging retries the identical frozen UI input, including its original ID. */
    @Test
    fun ccm10Ccm11UncertainStageRetriesExactFrozenInput() = runTest {
        withCapturedMatchingUi(reference = matchingInvoiceAReference, configure = { port ->
            port.stageReply = { intent ->
                if (port.stages.size == 1) LocalStageResult.Uncertain(OperationId("71000000-0000-4000-8000-000000000202"))
                else LocalStageResult.Staged(LocalPendingOperation(intent.operationId, matchingAction,
                    LocalText("Original matching request", ""), matchingSummary, submitAllowed = true))
            }
        }) {
            controller.openForm(matchingAction, runtime.navigator.token())
            controller.stage(runtime.navigator.token())
            runCurrent()
            val original = port.stages.single()
            assertEquals(original.operationId, controller.state.value.operationId)
            assertEquals(original.operationId, assertIs<LocalOperationResult.Unresolved>(controller.state.value.operation).operationId)
            assertTrue(controller.state.value.stageUncertain)
            controller.edit(LocalField.QUANTITY, LocalDraftValue("24"), runtime.navigator.token())
            controller.retryStage(runtime.navigator.token())
            runCurrent()
            assertEquals(2, port.stages.size)
            assertSame(original, port.stages[1])
            assertEquals(LocalScalarValue.Decimal("22"), original.values.single { it.field == LocalField.QUANTITY }.value)
            assertEquals(original.operationId, assertIs<LocalOperationResult.Pending>(controller.state.value.operation).operation.operationId)
            assertFalse(controller.state.value.stageUncertain)
            assertTrue(port.submissions.isEmpty())
            assertTrue(port.lookups.isEmpty())
        }
    }

    /** CCM-08/10/11: a known denial clears private projections while retaining the unresolved original. */
    @Test
    fun ccm08Ccm10Ccm11KnownDenialRetiresDetailsAndKeepsOriginal() = runTest {
        withCapturedMatchingUi(reference = matchingInvoiceAReference, configure = { port ->
            port.stageReply = { LocalStageResult.Staged(LocalPendingOperation(it.operationId, matchingAction,
                LocalText("Original matching request", ""), matchingSummary, submitAllowed = true)) }
            port.submitReply = { LocalOperationResult.Unresolved(it, LocalServiceProblem.DENIED) }
        }) {
            controller.openForm(matchingAction, runtime.navigator.token())
            controller.stage(runtime.navigator.token())
            runCurrent()
            val original = port.stages.single()
            controller.submit(original.operationId, runtime.navigator.token())
            runCurrent()
            val state = controller.state.value
            assertNull(state.page)
            assertNull(state.form)
            assertNull(state.reference)
            assertNull(state.referencePicker)
            assertEquals(LocalServiceProblem.DENIED, state.readProblem)
            assertEquals(original.operationId, state.operationId)
            assertEquals(LocalServiceProblem.DENIED, assertIs<LocalOperationResult.Unresolved>(state.operation).problem)
            controller.openForm(matchingAction, runtime.navigator.token())
            controller.stage(runtime.navigator.token())
            controller.selectRecord(matchingInvoiceBReference, runtime.navigator.token())
            runCurrent()
            assertEquals(1, port.stages.size)
            assertEquals(listOf(original.operationId), port.submissions)
            assertTrue(port.lookups.isEmpty())
            assertTrue(port.acknowledgements.isEmpty())
            val retained = assertNotNull(controller.retireForAttachment())
            assertEquals(original.operationId, retained.workspace.operationId)
            assertSame(original, retained.frozen)
            assertEquals(matchingAction, retained.originalAction)
            assertNull(retained.workspace.page)
        }
    }
}
