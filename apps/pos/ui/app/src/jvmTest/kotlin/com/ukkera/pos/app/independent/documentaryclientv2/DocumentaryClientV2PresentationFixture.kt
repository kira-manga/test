@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.independent.documentaryclientv2

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.koin.core.parameter.parametersOf
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Delayed presentation callback, including delivery after its original UI owner ends. */
internal class DocumentaryClientV2HeldReply<T>(private val cleanupReply: T) {
    val requested = CompletableDeferred<Unit>()
    val returned = CompletableDeferred<Unit>()
    private val response = CompletableDeferred<T>()

    suspend fun await(): T = withContext(NonCancellable) {
        requested.complete(Unit)
        response.await().also { returned.complete(Unit) }
    }
    fun release(value: T) { response.complete(value) }
    fun releaseForCleanup() { response.complete(cleanupReply) }
}

internal data class DocumentaryClientV2SaveObservation(
    val capture: LocalPurchasingDocumentCapture,
    val bytes: ByteArray,
) {
    override fun toString(): String = "DocumentaryClientV2SaveObservation([REDACTED])"
}

internal data class DocumentaryClientV2RetryObservation(
    val documentId: DomainId,
    val refusedOperationId: OperationId,
    val revision: Revision,
)

/** Scripted UI inputs/call observations only: no Master, RPC, grants, journal or ledger. */
internal class DocumentaryClientV2Port : LocalWorkflowGateway {
    val saves = mutableListOf<DocumentaryClientV2SaveObservation>()
    val retries = mutableListOf<DocumentaryClientV2RetryObservation>()
    val transfers = mutableListOf<DomainId>()
    val unrelatedCalls = mutableListOf<String>()
    var sourcesInput: LocalPurchasingDocumentSourcesResult = LocalPurchasingDocumentSourcesResult.Available(emptyList())
    var saveInput: LocalPurchasingDocumentSourceResult? = null
    var retryInput: LocalPurchasingDocumentSourceResult? = null
    var transferInput: LocalPurchasingDocumentTransferResult? = null
    private var nextSave: DocumentaryClientV2HeldReply<LocalPurchasingDocumentSourceResult>? = null
    private var nextTransfer: DocumentaryClientV2HeldReply<LocalPurchasingDocumentTransferResult>? = null
    private val held = mutableListOf<() -> Unit>()

    fun holdNextSave(documentId: DomainId): DocumentaryClientV2HeldReply<LocalPurchasingDocumentSourceResult> =
        DocumentaryClientV2HeldReply<LocalPurchasingDocumentSourceResult>(LocalPurchasingDocumentSourceResult.Unavailable(
            LocalServiceProblem.STORAGE_UNCERTAIN, documentId)).also {
            check(nextSave == null)
            nextSave = it
            held += it::releaseForCleanup
        }

    fun holdNextTransfer(documentId: DomainId): DocumentaryClientV2HeldReply<LocalPurchasingDocumentTransferResult> =
        DocumentaryClientV2HeldReply<LocalPurchasingDocumentTransferResult>(LocalPurchasingDocumentTransferResult.Unresolved(
            documentId, null, LocalServiceProblem.UNAVAILABLE)).also {
            check(nextTransfer == null)
            nextTransfer = it
            held += it::releaseForCleanup
        }

    override suspend fun savePurchasingDocument(capture: LocalPurchasingDocumentCapture,
        originalBytes: ByteArray): LocalPurchasingDocumentSourceResult {
        saves += DocumentaryClientV2SaveObservation(capture, originalBytes.copyOf())
        nextSave?.let { pending -> nextSave = null; return pending.await() }
        return saveInput ?: LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.RETRY_LATER, capture.documentId)
    }

    override suspend fun purchasingDocumentSources(): LocalPurchasingDocumentSourcesResult = sourcesInput

    override suspend fun transferPurchasingDocument(documentId: DomainId): LocalPurchasingDocumentTransferResult {
        transfers += documentId
        nextTransfer?.let { pending -> nextTransfer = null; return pending.await() }
        return transferInput ?: LocalPurchasingDocumentTransferResult.Unresolved(documentId, null, LocalServiceProblem.UNAVAILABLE)
    }

    override suspend fun retryPurchasingDocumentCompletion(documentId: DomainId,
        refusedCompleteOperationId: OperationId, newParentAttachmentsRevision: Revision): LocalPurchasingDocumentSourceResult {
        retries += DocumentaryClientV2RetryObservation(documentId, refusedCompleteOperationId, newParentAttachmentsRevision)
        return retryInput ?: LocalPurchasingDocumentSourceResult.Unavailable(LocalServiceProblem.RETRY_LATER, documentId)
    }

    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult =
        LocalReadResult.Available(LocalWorkflowPage(query.workflow, emptyList(), emptyList()))

    override suspend fun pending(cursor: String?): LocalPendingResult = LocalPendingResult.Available(emptyList())

    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult {
        unrelatedCalls += "stage"
        return LocalStageResult.NotStaged(LocalServiceProblem.NOT_IMPLEMENTED)
    }
    override suspend fun submit(operationId: OperationId): LocalOperationResult {
        unrelatedCalls += "submit"
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.NOT_IMPLEMENTED)
    }
    override suspend fun lookup(operationId: OperationId): LocalOperationResult {
        unrelatedCalls += "lookup"
        return LocalOperationResult.Unresolved(operationId, LocalServiceProblem.NOT_IMPLEMENTED)
    }
    override suspend fun acknowledgeRecorded(operationId: OperationId): LocalAcknowledgementResult {
        unrelatedCalls += "acknowledge"
        return LocalAcknowledgementResult.Unresolved(operationId, LocalServiceProblem.NOT_IMPLEMENTED)
    }

    fun releaseHeld() { held.forEach { it() } }
    fun clearObservations() { saves.forEach { it.bytes.fill(0) } }
}

/** Scripted source selection only; never a native permission or saved-output assertion. */
internal class DocumentaryClientV2Files : LocalPurchasingDocumentFiles {
    override val bound: Boolean = true
    var importInput: LocalPurchasingDocumentFileSelection = LocalPurchasingDocumentFileSelection.Cancelled
    var imports = 0
        private set
    private var nextImport: DocumentaryClientV2HeldReply<LocalPurchasingDocumentFileSelection>? = null
    private val held = mutableListOf<DocumentaryClientV2HeldReply<LocalPurchasingDocumentFileSelection>>()

    fun holdNextImport(): DocumentaryClientV2HeldReply<LocalPurchasingDocumentFileSelection> =
        DocumentaryClientV2HeldReply<LocalPurchasingDocumentFileSelection>(LocalPurchasingDocumentFileSelection.Cancelled).also {
            check(nextImport == null)
            nextImport = it
            held += it
        }

    override suspend fun importOriginal(isCurrent: () -> Boolean): LocalPurchasingDocumentFileSelection {
        imports++
        nextImport?.let { pending -> nextImport = null; return pending.await() }
        if (!isCurrent()) return LocalPurchasingDocumentFileSelection.Unavailable(LocalPurchasingDocumentFileProblem.ACCESS_CHANGED)
        return importInput.also { importInput = LocalPurchasingDocumentFileSelection.Cancelled }
    }

    override suspend fun chooseDestination(originalFileName: String,
        media: com.ukkera.pos.security.PurchasingEvidenceMediaV1,
        isCurrent: () -> Boolean): LocalPurchasingDocumentFileDestinationSelection =
        LocalPurchasingDocumentFileDestinationSelection.Unavailable(LocalPurchasingDocumentFileProblem.NOT_BOUND)

    fun releaseHeld() { held.forEach { it.releaseForCleanup() } }
    fun clearSelection() { (importInput as? LocalPurchasingDocumentFileSelection.Selected)?.destroy() }
}

internal class DocumentaryClientV2Session(
    actor: DomainId,
    override var workflows: LocalWorkflowGateway,
) : BranchUiSession {
    override val display = SessionDisplayContext(actor, FrozenDocumentaryClientV2Inputs.companyId,
        FrozenDocumentaryClientV2Inputs.branchId, "Synthetic documentary employee", "Synthetic company",
        "Synthetic branch", setOf(AppArea.PURCHASING), LocalSessionDraftEpochs(1, 1))
    override val lifecycle = MutableStateFlow(LocalSessionLifecycle.ACTIVE)
    override val attachmentUpdates = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(actor, 1))
    private var active = true
    private var replacement: DocumentaryClientV2Port? = null

    override fun isCurrent(): Boolean = active && lifecycle.value == LocalSessionLifecycle.ACTIVE
    override suspend fun current(): LocalSessionCheckResult = LocalSessionCheckResult.Current(display, attachmentKey)
    override suspend fun refresh(): LocalSessionCheckResult {
        workflows = checkNotNull(replacement)
        replacement = null
        attachmentUpdates.value = LocalSessionAttachmentKey(display.actorId, checkNotNull(attachmentKey).generation + 1)
        return LocalSessionCheckResult.Current(display, attachmentKey)
    }
    fun replaceWith(port: DocumentaryClientV2Port) { replacement = port }
    override suspend fun mayRestore(route: AppRoute): Boolean = isCurrent()
    override fun close() { active = false; lifecycle.value = LocalSessionLifecycle.LOCKED }
}

private class DocumentaryClientV2SignIn : BranchUiGateway {
    override val configured: Boolean = true
    var next: DocumentaryClientV2Session? = null
    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
        SignInResult.Authenticated(checkNotNull(next).also { next = null })
}

/** Actual entry holder and runtime fencing around scripted documentary UI ports. */
internal class DocumentaryClientV2UiFixture private constructor(
    val runtime: UkkeraRuntime,
    val files: DocumentaryClientV2Files,
    val port: DocumentaryClientV2Port,
    val session: DocumentaryClientV2Session,
    private val signIn: DocumentaryClientV2SignIn,
    private val scope: TestScope,
) {
    lateinit var controller: LocalWorkflowController
        private set
    private lateinit var model: EntryViewModel
    private val ports = mutableListOf(port)

    fun token(): NavigationToken = runtime.navigator.token()
    fun drain() { scope.runCurrent() }

    private fun currentController(): LocalWorkflowController {
        val entry = runtime.navigator.state.value.activeEntry
        val root = runtime.rootScope
        val provider = ViewModelProvider.create(root.viewModelStore, factory = viewModelFactory {
            initializer { root.koinScope.get<EntryViewModel> { parametersOf(entry.id) } }
        })
        model = provider["entry-${entry.id}", EntryViewModel::class]
        return assertNotNull(model.localWorkflows(runtime, token()))
    }

    private suspend fun enter(session: DocumentaryClientV2Session) {
        signIn.next = session
        runtime.openSignIn(false, token())
        runtime.signIn("synthetic-documentary", charArrayOf('x'), token())
        assertEquals(SecurityRoot.BRANCH_WORKSPACE, runtime.navigator.state.value.root)
        controller = currentController()
        controller.activate(LocalWorkflow.PURCHASING)
        drain()
        controller.selectPurchasing(LocalPurchasingUiQuery(LocalPurchasingSection.DOCUMENTS), token())
        drain()
        assertTrue(controller.purchasingDocumentReady(token()))
    }

    suspend fun enterOtherEmployee(): DocumentaryClientV2Port {
        runtime.signOut()
        drain()
        val next = DocumentaryClientV2Port()
        ports += next
        enter(DocumentaryClientV2Session(FrozenDocumentaryClientV2Inputs.staffX, next))
        return next
    }

    fun replaceAttachment(next: DocumentaryClientV2Port): LocalWorkflowController {
        ports += next
        session.replaceWith(next)
        runtime.refreshLocalSession(token())
        drain()
        controller = currentController()
        controller.activate(LocalWorkflow.PURCHASING)
        drain()
        return controller
    }

    fun fillDraft(document: String) {
        val input = FrozenDocumentaryClientV2Inputs.draft(document)
        controller.openPurchasingDocument(input.kind, token())
        val values = linkedMapOf(
            LocalPurchasingDocumentField.DOCUMENT_ID to input.documentId,
            LocalPurchasingDocumentField.SUPPLIER_ID to input.supplierId,
            LocalPurchasingDocumentField.SUPPLIER_REVISION to input.supplierRevision,
            LocalPurchasingDocumentField.SUPPLIER_REFERENCE to input.supplierReferenceOriginal,
            LocalPurchasingDocumentField.DOCUMENT_DATE to input.documentDate,
        )
        if (input.kind == LocalPurchasingDocumentKind.ATTACHMENT) {
            values[LocalPurchasingDocumentField.PARENT_ORIGINAL_ID] = input.parentOriginalId
            values[LocalPurchasingDocumentField.PARENT_ATTACHMENTS_REVISION] = input.parentAttachmentsRevision
        }
        if (input.kind == LocalPurchasingDocumentKind.CORRECTION) {
            values[LocalPurchasingDocumentField.CORRECTION_OF] = input.correctionOf
            values[LocalPurchasingDocumentField.CORRECTION_REASON] = input.correctionReason
        }
        values.forEach { (field, value) -> controller.editPurchasingDocument(field, value, token()) }
        controller.choosePurchasingDocumentPurpose(checkNotNull(input.purpose), token())
    }

    fun import(document: String): LocalPurchasingDocumentFileSelection.Selected {
        val selected = FrozenDocumentaryClientV2Inputs.selection(document)
        files.importInput = selected
        controller.importPurchasingDocument(token())
        drain()
        return selected
    }

    private fun releaseHeld() { files.releaseHeld(); ports.forEach { it.releaseHeld() } }
    private fun clearInputs() { files.clearSelection(); ports.forEach { it.clearObservations() } }

    companion object {
        suspend fun TestScope.withDocumentaryClientV2(block: suspend (DocumentaryClientV2UiFixture) -> Unit) {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val files = DocumentaryClientV2Files()
            val port = DocumentaryClientV2Port()
            val session = DocumentaryClientV2Session(FrozenDocumentaryClientV2Inputs.staffA, port)
            val signIn = DocumentaryClientV2SignIn()
            val runtime = UkkeraRuntime(AppDependencies(branchGateway = signIn,
                platform = PlatformCapabilities(PlatformFamily.DESKTOP), documentFiles = files))
            val fixture = DocumentaryClientV2UiFixture(runtime, files, port, session, signIn, this)
            try {
                fixture.enter(session)
                block(fixture)
            } finally {
                fixture.releaseHeld()
                try { runtime.close(); runCurrent() }
                finally { fixture.clearInputs(); Dispatchers.resetMain() }
            }
        }
    }
}
