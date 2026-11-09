@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.openingobligationv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * Frozen ODI-003/019/022 clauses through the actual gateway and runtime-owned controller,
 * with explicitly controlled sessions, client replies and memory-only journal. These are
 * neither protected Master/native persistence/RPC nor installed Arabic/English layout runs.
 */
class OpeningSupplierObligationFrozenUiSlicesTest {
    private val f = OpeningSupplierObligationUiInputs

    @Test
    fun odi003And022NoFinancialReadExposesNoOpeningDocumentAndAuthorizedProjectionRetainsExactA() = runTest {
        val control = OpeningSupplierObligationUiHarness()
        val noCost = assertIs<LocalReadResult.Available>(control.gateway.read(query(includeCosts = false))).page
        assertTrue(noCost.records.isEmpty())
        assertNull(noCost.nextCursor)
        assertTrue(control.endpoint.reads.isEmpty(), "No-cost opening view must not call a protected financial read")
        val notice = assertNotNull(noCost.notice)
        for (secret in listOf(f.document, f.reference, f.terms, "1250.75", "125075")) {
            assertFalse(notice.english.contains(secret))
            assertFalse(notice.arabic.contains(secret))
        }
        assertEquals(listOf(LocalAction.SUPPLIER_OPENING_RECORD), noCost.forms.map { it.action })
        val documentField = noCost.forms.single().fields.filterIsInstance<LocalFormField.Scalar>()
            .single { it.field == LocalField.SOURCE_DOCUMENT_TEXT }
        assertEquals(LocalInputKind.MULTILINE, documentField.kind)

        control.endpoint.currentReply = com.ukkera.pos.security.LocalSessionViewResult.Current(f.sessionView(
            listOf(f.hint(SecurityAction.PURCHASING_READ), f.hint(SecurityAction.INVENTORY_COST_READ))))
        val obligation = DomainId("81111111-1111-4111-8111-111111111111")
        val operation = OperationId("71111111-1111-4111-8111-111111111111")
        control.endpoint.openingReadReply = { request ->
            val selector = LocalOpeningSupplierObligationCodec.decodeQuery(request.payload)
            ReadReply.Data(LocalOpeningSupplierObligationCodec.page(LocalOpeningSupplierObligationPageV1(selector,
                listOf(f.view(obligation, operation)), null)), Revision(1), f.recordedAt)
        }
        val financial = assertIs<LocalReadResult.Available>(control.gateway.read(query(includeCosts = true))).page
        val record = financial.records.single()
        assertEquals(LocalRecordReference(LocalRecordKind.OPENING_SUPPLIER_OBLIGATION, obligation), record.reference)
        val values = record.values.map { it.value }
        for (original in listOf(f.document, f.reference, f.source, f.date, f.terms, operation.value, f.actor.value)) {
            assertTrue(values.any { it.english == original && it.arabic == original }, "Missing exact original $original")
        }
        assertTrue(values.any { it.english.replace(",", "") == "1250.75 EGP" })
        assertTrue(financial.forms.isEmpty(), "Read-only hints must not supply creation")
        assertEquals(listOf(LocalOpeningSupplierObligationProfile.READ), control.endpoint.reads.map { it.query })
        assertTrue(control.endpoint.executions.isEmpty())
        // Controlled grants are UI hints only. Current Master financial-field enforcement remains NOT_RUN.
    }

    @Test
    fun odi019And022ChangedOpeningDraftRequiresNewReviewAndPreservesExactDocument() = runTest {
        withController { slice ->
            fillOriginal(slice)
            val c = slice.controller
            c.confirmForm(true, slice.token())
            assertTrue(assertNotNull(c.state.value.form).confirmed)
            c.edit(LocalField.AMOUNT, LocalDraftValue("1251.75"), slice.token())
            assertFalse(assertNotNull(c.state.value.form).confirmed)
            c.stage(slice.token())
            runCurrent()
            assertEquals(LocalField.CONFIRMED, c.state.value.issue?.field)
            assertNull(c.state.value.operationId)
            assertEquals(0, slice.control.journal.stageWrites)
            assertTrue(slice.control.endpoint.executions.isEmpty())
            c.edit(LocalField.AMOUNT, LocalDraftValue("١٢٥٠.٧٥"), slice.token())
            assertEquals(f.document, c.state.value.form?.values?.get(LocalField.SOURCE_DOCUMENT_TEXT)?.text)
            assertEquals(f.reference, c.state.value.form?.values?.get(LocalField.EXTERNAL_REFERENCE)?.text)
            c.confirmForm(true, slice.token())
            c.stage(slice.token())
            runCurrent()
            val stored = slice.control.journal.entries.values.single()
            val request = LocalOpeningSupplierObligationCodec.decodeRequest(stored.request().payload)
            assertEquals(f.request(request.obligationId), request)
            assertEquals(125075L, request.amount.minor)
            assertEquals(777, request.sourceDocument.text.encodeToByteArray().size)
            assertEquals(f.hash, sha256(request.sourceDocument.text.encodeToByteArray()).value)
            assertTrue(slice.control.endpoint.executions.isEmpty(), "Staging does not submit opening debt")
        }
        // Actual locale switching, accessible rendering and economic zero effects require their full layers.
    }

    @Test
    fun odi019StagedAndUncertainRecoveryKeepSameOriginalBodyAndIdentity() = runTest {
        withController { slice ->
            fillOriginal(slice)
            val c = slice.controller
            c.confirmForm(true, slice.token())
            c.stage(slice.token())
            runCurrent()
            val id = assertNotNull(c.state.value.operationId)
            val original = slice.control.journal.entries.getValue(id)
            val originalBody = original.request().payload
            assertIs<LocalOperationResult.Pending>(c.state.value.operation)
            c.discardForm(slice.token())
            c.edit(LocalField.SOURCE_DOCUMENT_TEXT, LocalDraftValue("replacement"), slice.token())
            c.stage(slice.token())
            runCurrent()
            assertEquals(id, c.state.value.operationId)
            assertEquals(originalBody, slice.control.journal.entries.getValue(id).request().payload)
            assertEquals(1, slice.control.journal.stageWrites)
            assertTrue(slice.control.endpoint.executions.isEmpty())
            val recovery = assertIs<LocalPendingResult.Available>(slice.control.gateway.pending()).operations.single()
            assertEquals(id, recovery.operationId)
            assertTrue(recovery.summary.any { it.value.english == f.document && it.value.arabic == f.document })
            c.submit(id, slice.token())
            runCurrent()
            assertEquals(id, assertIs<LocalOperationResult.Unresolved>(c.state.value.operation).operationId)
            assertEquals(originalBody, slice.control.endpoint.executions.single().payload)
            slice.control.endpoint.lookupReply = { LookupReply.Deferred(it.operationId, DeferredReason.STORAGE_UNCERTAIN) }
            c.lookup(id, slice.token())
            runCurrent()
            c.edit(LocalField.AMOUNT, LocalDraftValue("1251.75"), slice.token())
            c.discardForm(slice.token())
            assertEquals(id, c.state.value.operationId)
            assertEquals(originalBody, slice.control.journal.entries.getValue(id).request().payload)
            assertEquals(125075L, LocalOpeningSupplierObligationCodec.decodeRequest(originalBody).amount.minor)
            assertEquals(f.document, LocalOpeningSupplierObligationCodec.decodeRequest(originalBody).sourceDocument.text)
            assertEquals(listOf(id), slice.control.journal.entries.keys.toList())
            assertTrue(slice.control.endpoint.lookups.all { it.operationId == id && it.originalActorId == f.actor })
        }
        // Memory-only client recovery; no crash-durable stage, real commit/lost response or restart is claimed.
    }

    @Test
    fun odi019MatchingRecordedAcknowledgementFollowsOnlyOriginalOpeningDocument() = runTest {
        withController { slice ->
            fillOriginal(slice)
            val c = slice.controller
            c.confirmForm(true, slice.token())
            c.stage(slice.token())
            runCurrent()
            val id = assertNotNull(c.state.value.operationId)
            val original = slice.control.journal.entries.getValue(id)
            val request = original.request()
            val expected = LocalOpeningSupplierObligationCodec.decodeRequest(request.payload).obligationId
            val committedMarker = f.recordedReply(request)
            slice.control.endpoint.executeReply = { committedMarker }
            c.submit(id, slice.token())
            runCurrent()
            assertIs<LocalOperationResult.Recorded>(c.state.value.operation)
            assertFalse(c.state.value.recordedAcknowledged)
            assertNull(c.state.value.reference, "Follow-up waits for explicit acknowledgement")
            slice.control.endpoint.lookupReply = { LookupReply.Found(committedMarker) }
            c.acknowledgeRecorded(id, slice.token())
            runCurrent()
            assertTrue(c.state.value.recordedAcknowledged)
            assertEquals(LocalRecordReference(LocalRecordKind.OPENING_SUPPLIER_OBLIGATION, expected), c.state.value.reference)
            assertEquals(LocalPurchasingSection.OPENING_OBLIGATIONS, c.state.value.purchasingQuery.section)
            assertEquals(id, c.state.value.operationId)
            assertTrue(slice.control.journal.entries.getValue(id).acknowledged)
            assertEquals(request.payload, slice.control.journal.entries.getValue(id).request().payload)
            assertEquals(listOf(id), slice.control.endpoint.executions.map { it.operationId })
            assertTrue(slice.control.endpoint.reads.all { it.query == LocalConfigurationProfile.QUERY },
                "A no-cost follow-up must not silently add financial read access")
        }
        // The reply/ACK marker is controlled. It does not establish a durable Master business outcome.
    }

    private fun query(includeCosts: Boolean) = LocalWorkflowQuery(LocalWorkflow.PURCHASING,
        purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.OPENING_OBLIGATIONS, includeCosts = includeCosts))

    private fun fillOriginal(slice: OpeningControllerSlice) {
        val c = slice.controller
        c.openForm(LocalAction.SUPPLIER_OPENING_RECORD, slice.token())
        assertNotNull(c.state.value.form)
        c.edit(LocalField.SUPPLIER_ID, LocalDraftValue(selected = LocalScalarValue.Reference(
            LocalRecordReference(LocalRecordKind.SUPPLIER, f.supplier, Revision(0)))), slice.token())
        for ((field, value) in listOf(LocalField.EVIDENCE_ID to f.attachment.value,
            LocalField.EXTERNAL_REFERENCE to f.reference, LocalField.SOURCE_DOCUMENT_IDENTITY to f.source,
            LocalField.SOURCE_DOCUMENT_TEXT to f.document, LocalField.BUSINESS_DATE to f.date,
            LocalField.AMOUNT to "1250.75", LocalField.PAYMENT_TERMS to f.terms, LocalField.REASON to f.reason))
            c.edit(field, LocalDraftValue(value), slice.token())
    }
}

private class OpeningControllerSession(override val workflows: LocalWorkflowGateway,
    private val control: OpeningSupplierObligationUiHarness) : BranchUiSession {
    private val f = OpeningSupplierObligationUiInputs
    override val display = SessionDisplayContext(f.actor, f.scope.companyId, f.scope.branchId,
        "Synthetic opening operator", "Synthetic company", "Synthetic branch", setOf(AppArea.PURCHASING))
    private var active = true
    private val attachments = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(f.session, 1))
    private val lifetimes = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() = attachments
    override val lifecycle: StateFlow<LocalSessionLifecycle> get() = lifetimes
    override fun isCurrent(): Boolean = active
    override suspend fun current(): LocalSessionCheckResult = if (active) LocalSessionCheckResult.Current(display, attachmentKey)
        else LocalSessionCheckResult.Unavailable(LocalServiceProblem.SESSION_ENDED, attachmentKey)
    override suspend fun mayRestore(route: AppRoute): Boolean = active && RouteRegistry.area(route) in display.allowedAreas
    override fun close() {
        active = false
        control.currentLifetime = false
        attachments.value = null
        lifetimes.value = LocalSessionLifecycle.LOCKED
    }
}

private class OpeningControllerSignIn(private val session: BranchUiSession) : BranchUiGateway {
    override val configured = true
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> = SignInResult.Authenticated(session)
}

private class OpeningControllerSlice(val runtime: UkkeraRuntime, val controller: LocalWorkflowController,
    val control: OpeningSupplierObligationUiHarness) {
    fun token(): NavigationToken = runtime.navigator.token()
}

private suspend fun TestScope.withController(block: suspend TestScope.(OpeningControllerSlice) -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    var runtime: UkkeraRuntime? = null
    var controller: LocalWorkflowController? = null
    try {
        val control = OpeningSupplierObligationUiHarness()
        val session = OpeningControllerSession(control.gateway, control)
        val actual = UkkeraRuntime(AppDependencies(branchGateway = OpeningControllerSignIn(session),
            platform = PlatformCapabilities(PlatformFamily.DESKTOP)))
        runtime = actual
        actual.openSignIn(false, actual.navigator.token())
        actual.signIn(session.display.actorName, charArrayOf('x'), actual.navigator.token())
        assertEquals(session.display, actual.presentation.value.display)
        val initial = LocalWorkspaceState(workflow = LocalWorkflow.PURCHASING,
            purchasingQuery = LocalPurchasingUiQuery(LocalPurchasingSection.OPENING_OBLIGATIONS, includeCosts = false))
        val c = LocalWorkflowController(actual, session, actual.navigator.token(), this,
            LocalWorkflowContinuation(initial, null, null, null, null))
        controller = c
        c.activate(LocalWorkflow.PURCHASING)
        runCurrent()
        assertTrue(c.state.value.canStart)
        block(OpeningControllerSlice(actual, c, control))
    } finally {
        try { controller?.close(); runtime?.close(); runCurrent() } finally { Dispatchers.resetMain() }
    }
}
