package com.ukkera.pos.app.independent.catalogreleasesourcev1

import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.LocalCatalogReleaseAuthoringSource
import com.ukkera.pos.app.LocalCatalogReleasePublicationDraft
import com.ukkera.pos.app.LocalCatalogReleasePublicationGateway
import com.ukkera.pos.app.LocalCatalogReleasePublicationResult
import com.ukkera.pos.app.LocalCatalogReleasePublicationReview
import com.ukkera.pos.app.LocalCatalogReleasePublicationStatus
import com.ukkera.pos.app.LocalCatalogReleasePublicationStatusResult
import com.ukkera.pos.app.LocalCompanyCatalogGateway
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSessionAttachmentKey
import com.ukkera.pos.app.LocalSessionCheckResult
import com.ukkera.pos.app.LocalSessionDraftEpochs
import com.ukkera.pos.app.LocalStageResult
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.UnavailableLocalCompanyCatalog
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.UtcInstant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Controlled UI attachment/display only, never a native session or authenticated Master grant. */
internal class ControlledCatalogReleaseCardUi {
    val releases = ControlledCatalogReleaseCardPort()
    private val session = ControlledCatalogReleaseDisplaySession(releases)
    val runtime = UkkeraRuntime(AppDependencies(branchGateway = object : BranchUiGateway {
        override val configured = true
        override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
            try { SignInResult.Authenticated(session) } finally { secret.fill('\u0000') }
    }, platform = PlatformCapabilities(PlatformFamily.DESKTOP)))

    suspend fun attach() {
        runtime.openSignIn(company = false, token = runtime.navigator.token())
        runtime.signIn("synthetic-release-ui-display", charArrayOf('s'), runtime.navigator.token())
    }

    fun close() {
        releases.prepareGate?.complete(Unit)
        runtime.close()
    }
}

private class ControlledCatalogReleaseDisplaySession(port: ControlledCatalogReleaseCardPort) : BranchUiSession {
    private val inputs = FrozenCatalogReleaseUiContentInputs
    private var live = true
    private val attachment = MutableStateFlow<LocalSessionAttachmentKey?>(LocalSessionAttachmentKey(inputs.token(800), 1))
    override val display = SessionDisplayContext(inputs.token(801), inputs.company, inputs.branch,
        "Synthetic UI employee", "Synthetic UI company", "Synthetic UI branch", setOf(AppArea.CATALOG),
        LocalSessionDraftEpochs(1, 1))
    override val companyCatalog: LocalCompanyCatalogGateway = object : LocalCompanyCatalogGateway by UnavailableLocalCompanyCatalog {
        override val configured = true
        override val releases: LocalCatalogReleasePublicationGateway = port
    }
    override val attachmentUpdates: StateFlow<LocalSessionAttachmentKey?> get() = attachment
    override fun isCurrent(): Boolean = live && attachment.value != null
    override suspend fun current(): LocalSessionCheckResult = LocalSessionCheckResult.Current(display, attachment.value)
    override suspend fun mayRestore(route: AppRoute): Boolean = false
    override fun close() { live = false; attachment.value = null }
}

/**
 * Controlled public UI DATA responses and unavailable preparation, not a native publication
 * implementation. There are deliberately no references, reviews, completed outputs or approvals.
 */
internal class ControlledCatalogReleaseCardPort : LocalCatalogReleasePublicationGateway {
    override val configured = true
    @Volatile var source: LocalCatalogReleaseAuthoringSource = FrozenCatalogReleaseUiContentInputs.authoringSource(1)
    @Volatile var prepareGate: CompletableDeferred<Unit>? = null
    val statusCalls = CopyOnWriteArrayList<DomainId?>()
    val preparedDrafts = CopyOnWriteArrayList<LocalCatalogReleasePublicationDraft>()
    val cancelledPreparations = AtomicInteger()
    val forbiddenNativeOrActivationCalls = AtomicInteger()

    override suspend fun status(catalogId: DomainId?): LocalCatalogReleasePublicationStatusResult {
        statusCalls += catalogId
        val visibleSource = source.takeIf { catalogId == it.catalogId }
        return LocalCatalogReleasePublicationStatusResult.Available(LocalCatalogReleasePublicationStatus(
            references = emptyList(), source = visibleSource, mayPrepare = visibleSource != null,
            masterObservedAt = UtcInstant.parseCanonical("2026-01-01T00:00:00Z")))
    }

    override suspend fun prepare(draft: LocalCatalogReleasePublicationDraft): LocalCatalogReleasePublicationResult {
        preparedDrafts += draft.snapshot()
        try { prepareGate?.await() }
        catch (cancelled: CancellationException) { cancelledPreparations.incrementAndGet(); throw cancelled }
        return LocalCatalogReleasePublicationResult.Unavailable(LocalServiceProblem.UNAVAILABLE)
    }

    override suspend fun review(originalActionId: DomainId): LocalCatalogReleasePublicationResult = forbidden()
    override suspend fun completed(originalActionId: DomainId): LocalCatalogReleasePublicationResult = forbidden()
    override suspend fun approve(review: LocalCatalogReleasePublicationReview, headLogin: String,
        headSecret: CharArray): LocalCatalogReleasePublicationResult {
        headSecret.fill('\u0000')
        return forbidden()
    }
    override suspend fun stageActivation(originalActionId: DomainId): LocalStageResult = forbidden()

    private fun forbidden(): Nothing {
        forbiddenNativeOrActivationCalls.incrementAndGet()
        throw AssertionError("This bounded UI slice must not request native review, signing, completion or activation")
    }
}
