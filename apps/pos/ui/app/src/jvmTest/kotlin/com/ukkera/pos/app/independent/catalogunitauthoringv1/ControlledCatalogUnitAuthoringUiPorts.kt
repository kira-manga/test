package com.ukkera.pos.app.independent.catalogunitauthoringv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.test.assertEquals

/** Controlled display and request-observation ports, never native custody, authentication or a journal. */
internal class ControlledCatalogUnitAuthoringUi {
    val catalog = ControlledCatalogUnitAuthoringPort()
    val workflow = NoCatalogUnitAuthoringBusinessCommands()
    private val session = object : BranchUiSession {
        private var live = true
        override val display = SessionDisplayContext(CatalogUnitAuthoringFrozenInputs.bindingId("display-employee"),
            CatalogUnitAuthoringFrozenInputs.id("company"), CatalogUnitAuthoringFrozenInputs.id("branch"),
            "Synthetic unit author", "Synthetic unit company", "Synthetic unit branch", setOf(AppArea.CATALOG),
            LocalSessionDraftEpochs(1, 1))
        override val companyCatalog: LocalCompanyCatalogGateway get() = catalog
        override val workflows: LocalWorkflowGateway get() = workflow
        override fun isCurrent(): Boolean = live
        override suspend fun current(): LocalSessionCheckResult = LocalSessionCheckResult.Current(display)
        override suspend fun mayRestore(route: AppRoute): Boolean = false
        override fun close() { live = false }
    }
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            SignInResult.Authenticated(session)
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))

    suspend fun attach() {
        runtime.openSignIn(company = false, token = runtime.navigator.token())
        runtime.signIn("synthetic-unit-editor", charArrayOf('s'), runtime.navigator.token())
        assertEquals(AppArea.CATALOG, runtime.navigator.state.value.selectedArea)
    }
    fun close() { runtime.close() }
    fun assertNoBusinessCommands() { assertEquals(0, workflow.commandCalls) }
}

internal class ControlledCatalogUnitAuthoringPort : LocalCompanyCatalogGateway {
    private val f = CatalogUnitAuthoringFrozenInputs
    override val configured: Boolean = true
    val originalId: DomainId = f.bindingId("opaque-controlled-original-coordinate")
    val preparations = mutableListOf<LocalCompanySkuDraft>()
    val originalReads = mutableListOf<DomainId>()
    val stageRequests = mutableListOf<DomainId>()
    var completedProjection: LocalCompanyCatalogCompleted? = null
    var approvalCalls = 0
        private set

    override suspend fun status(): LocalCompanyCatalogStatusResult = LocalCompanyCatalogStatusResult.Available(
        LocalCompanyCatalogStatus(emptyList(), true, f.observedAt,
            Revision(f.definition("F-SYNTHETIC-BASELINE").obj("active_catalog").text("revision").toLong())))

    override suspend fun prepare(draft: LocalCompanySkuDraft): LocalCompanyCatalogReviewResult {
        preparations += draft
        // Observation of a retention request is not completed approval. No native-owned
        // Action/Output is constructed to fabricate a positive review or signing result.
        return LocalCompanyCatalogReviewResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN)
    }
    override suspend fun review(originalActionId: DomainId): LocalCompanyCatalogReviewResult {
        originalReads += originalActionId
        val projection = completedProjection
        return if (projection == null) LocalCompanyCatalogReviewResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN)
        else LocalCompanyCatalogReviewResult.Completed(projection)
    }
    override suspend fun approve(review: LocalCompanyCatalogReview, headLogin: String, headSecret: CharArray): LocalCompanyCatalogApprovalResult {
        headSecret.fill('\u0000')
        approvalCalls++
        throw AssertionError("An original native Action is not available at this controlled display port")
    }
    override suspend fun completed(originalActionId: DomainId): LocalCompanyCatalogApprovalResult =
        completedProjection?.let { LocalCompanyCatalogApprovalResult.Completed(it) }
            ?: LocalCompanyCatalogApprovalResult.Unavailable(LocalServiceProblem.STORAGE_UNCERTAIN)

    override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult {
        stageRequests += originalActionId
        // Public DATA response only. This does not write a journal or submit activation.
        return LocalStageResult.Staged(LocalPendingOperation(OperationId(originalActionId.value), LocalAction.CATALOG_ACTIVATE,
            LocalText("Controlled staged-request projection — not durable", "عرض طلب حفظ مضبوط — ليس حفظًا دائمًا")))
    }

    fun observeCompletedProjection(legacyOmission: Boolean = false) {
        completedProjection = LocalCompanyCatalogCompleted(originalId,
            f.preview(legacyOmission = legacyOmission), "controlled-public-data-only")
    }
}

internal class NoCatalogUnitAuthoringBusinessCommands : LocalWorkflowGateway {
    var commandCalls = 0
        private set
    override suspend fun read(query: LocalWorkflowQuery): LocalReadResult = LocalReadResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
    override suspend fun pending(cursor: String?): LocalPendingResult = LocalPendingResult.Available(emptyList())
    override suspend fun stage(intent: LocalCommandIntent): LocalStageResult = forbidden()
    override suspend fun submit(operationId: OperationId): LocalOperationResult = forbidden()
    override suspend fun lookup(operationId: OperationId): LocalOperationResult = forbidden()
    private fun forbidden(): Nothing {
        commandCalls++
        throw AssertionError("Unit entry/review must not stage or submit business mutations at this port")
    }
}
