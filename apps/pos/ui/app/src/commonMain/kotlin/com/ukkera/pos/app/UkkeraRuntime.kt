package com.ukkera.pos.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppNavigator
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.NavigationResult
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.app.training.TrainingCategory
import com.ukkera.pos.app.training.TrainingWorkspace
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.LocalCompanyCatalogEntryDrafts
import com.ukkera.pos.app.ui.LocalFoodConfigurationEntryDrafts
import com.ukkera.pos.app.ui.LocalReturnRightsEntryDrafts
import com.ukkera.pos.app.ui.LocalPricePolicyEntryDrafts
import com.ukkera.pos.app.ui.LocalSerialRegistrationEntryDrafts
import com.ukkera.pos.app.ui.LocalCatalogReleaseEntryDrafts
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.koinApplication
import org.koin.dsl.module

data class RuntimePresentation(
    val language: AppLanguage = AppLanguage.ENGLISH,
    val display: SessionDisplayContext? = null,
    val training: Boolean = false,
    val signingIn: Boolean = false,
    val signInFailure: SignInFailure? = null,
    val cleanupNeedsAttention: Boolean = false,
    val revocationPending: Boolean = false,
    val locallyLocked: Boolean = false,
    val sessionProblem: LocalServiceProblem? = null,
    val cleanupStatus: LocalClientCleanupStatus? = null,
    val checkingCleanup: Boolean = false,
    val refreshingSession: Boolean = false,
    /** Last accepted presentation attachment; it never authorizes a service call. */
    val localAttachment: LocalSessionAttachmentKey? = null,
    val enrollment: LocalEnrollmentPresentation = LocalEnrollmentPresentation(),
)

data class LocalEnrollmentPresentation(
    val status: LocalEnrollmentStatus? = null,
    val busy: Boolean = false,
    val statusUnavailable: Boolean = false,
    val requestId: DomainId? = null,
    val result: LocalEnrollmentResult? = null,
)

/**
 * One process-owned client container, created by the native launcher, not during recomposition.
 * No branch-service container or authoritative database is created or stopped here. A future
 * platform service lives independently and supplies gateway adapters through AppDependencies.
 */
class UkkeraRuntime(val dependencies: AppDependencies) {
    val navigator = AppNavigator { entryId -> rootScope.clearDepartingEntry(entryId) }
    private val mutablePresentation = MutableStateFlow(RuntimePresentation())
    val presentation: StateFlow<RuntimePresentation> = mutablePresentation.asStateFlow()
    private val container = koinApplication { modules(clientModule()) }
    private var branchSession: BranchUiSession? = null
    private var centralSession: CentralUiSession? = null
    private var retailRootLifetime = LocalRetailPhysicalLifetime()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingRevocations = 0
    private var otherCleanupNeedsAttention = false
    // UI resources only. Failed exact holders stay retained for negative cleanup;
    // this neither closes a native master owner nor resolves durable operation evidence.
    private val pendingUiCleanup = mutableListOf<() -> Unit>()
    internal var rootScope = newRootScope(null, null)
        private set
    var closed: Boolean = false
        private set

    fun changeLanguage(language: AppLanguage) {
        if (!closed) mutablePresentation.value = presentation.value.copy(language = language)
    }

    fun openSignIn(company: Boolean, token: NavigationToken) {
        if (closed || !navigator.isCurrent(token) || company && !dependencies.centralServicesEnabled) return
        val root = navigator.state.value.root
        if (root != SecurityRoot.BOOTSTRAP && !(root == SecurityRoot.LOCAL_ENROLLMENT && !company &&
                presentation.value.enrollment.result == LocalEnrollmentResult.Created)) return
        changeRoot(if (company) SecurityRoot.COMPANY_SIGN_IN else SecurityRoot.BRANCH_SIGN_IN)
    }

    fun openEnrollment(token: NavigationToken) {
        if (!closed && navigator.isCurrent(token) && navigator.state.value.root == SecurityRoot.BOOTSTRAP)
            changeRoot(SecurityRoot.LOCAL_ENROLLMENT)
    }

    /** Explicit training entry is not a successful production sign-in and has no production session. */
    fun openTraining(company: Boolean, token: NavigationToken) {
        if (closed || !navigator.isCurrent(token) || navigator.state.value.root != SecurityRoot.BOOTSTRAP) return
        val training = TrainingWorkspace(navigator::setRootDraft)
        if (!changeRoot(if (company) SecurityRoot.COMPANY_WORKSPACE else SecurityRoot.BRANCH_WORKSPACE,
            TrainingWorkspace.display(company), training)) closeCapability { training.close() }
    }

    /** The entry owns sign-in work, including a credential buffer when launch never starts. */
    internal fun launchSignIn(scope: CoroutineScope, account: String, secret: CharArray, token: NavigationToken,
        explicitDeviceMove: Boolean = false): Job =
        scope.launch { signIn(account, secret, token, explicitDeviceMove) }.also { job ->
            job.invokeOnCompletion { secret.fill('\u0000') }
        }

    suspend fun signIn(account: String, secret: CharArray, token: NavigationToken, explicitDeviceMove: Boolean = false) {
        var ownsSignInState = false
        var unclaimedBranch: BranchUiSession? = null
        var unclaimedCentral: CentralUiSession? = null
        try {
            if (closed || !navigator.isCurrent(token) || presentation.value.signingIn) return
            val root = navigator.state.value.root
            if (root !in setOf(SecurityRoot.BRANCH_SIGN_IN, SecurityRoot.COMPANY_SIGN_IN)) return
            val configured = if (root == SecurityRoot.BRANCH_SIGN_IN) dependencies.branchGateway.configured
                else dependencies.centralServicesEnabled && dependencies.centralGateway.configured
            if (!configured) {
                mutablePresentation.value = presentation.value.copy(signInFailure = SignInFailure.NOT_CONFIGURED)
                return
            }
            val secretMaxLength = if (root == SecurityRoot.BRANCH_SIGN_IN) dependencies.branchGateway.signInSecretMaxLength
                else LOCAL_CREDENTIAL_SECRET_MAX_LENGTH
            if (!localSignInInputSupported(account, secret.size, secretMaxLength)) {
                mutablePresentation.value = presentation.value.copy(signInFailure = SignInFailure.DENIED)
                return
            }
            ownsSignInState = true
            mutablePresentation.value = presentation.value.copy(signingIn = true, signInFailure = null)
            if (root == SecurityRoot.BRANCH_SIGN_IN) {
                when (val result = dependencies.branchGateway.signIn(account, secret, explicitDeviceMove)) {
                    is SignInResult.Rejected -> if (navigator.isCurrent(token)) mutablePresentation.value = presentation.value.copy(signInFailure = result.reason)
                    is SignInResult.Authenticated -> {
                        unclaimedBranch = result.session
                        currentCoroutineContext().ensureActive()
                        if (!navigator.isCurrent(token) || closed) return
                        val display = result.session.display
                        require(display.branchId != null)
                        // Recheck the exact capability after async gateway delivery, not merely a
                        // potentially lagging lifecycle flow. No suspension before publication.
                        // Retirement after this sample is handled by the root-owned monitor.
                        val attachment = result.session.attachmentKey
                        if (!result.session.isCurrent() || result.session.attachmentUpdates != null && attachment == null) {
                            mutablePresentation.value = presentation.value.copy(signInFailure = SignInFailure.UNAVAILABLE)
                            return
                        }
                        if (!changeRoot(SecurityRoot.BRANCH_WORKSPACE, display)) {
                            mutablePresentation.value = presentation.value.copy(signInFailure = SignInFailure.UNAVAILABLE)
                            return
                        }
                        branchSession = result.session
                        mutablePresentation.value = presentation.value.copy(localAttachment = attachment)
                        unclaimedBranch = null
                    }
                }
            } else {
                when (val result = dependencies.centralGateway.signIn(account, secret)) {
                    is SignInResult.Rejected -> if (navigator.isCurrent(token)) mutablePresentation.value = presentation.value.copy(signInFailure = result.reason)
                    is SignInResult.Authenticated -> {
                        unclaimedCentral = result.session
                        currentCoroutineContext().ensureActive()
                        if (!navigator.isCurrent(token) || closed) return
                        val display = result.session.display
                        require(display.branchId == null)
                        if (!changeRoot(SecurityRoot.COMPANY_WORKSPACE, display)) {
                            mutablePresentation.value = presentation.value.copy(signInFailure = SignInFailure.UNAVAILABLE)
                            return
                        }
                        centralSession = result.session
                        unclaimedCentral = null
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (navigator.isCurrent(token)) mutablePresentation.value = presentation.value.copy(signInFailure = SignInFailure.UNAVAILABLE)
        } finally {
            secret.fill('\u0000')
            val cleanup = LocalSessionCleanup()
            // Also closes a FIRST slow reply, not just retries, if A has left or B now owns the UI.
            cleanup.attempt { closeCapability { unclaimedBranch?.close() } }
            cleanup.attempt { closeCapability { unclaimedCentral?.close() } }
            // An ignored duplicate owns only its own credential buffer, never the busy latch of
            // the still-running attempt. Root/entry identity also fences old-owner completion.
            cleanup.attempt {
                if (ownsSignInState && navigator.isCurrent(token)) mutablePresentation.value = presentation.value.copy(signingIn = false)
            }
            cleanup.throwIfFailed()
        }
    }

    internal suspend fun loadEnrollmentStatus(token: NavigationToken) {
        if (!canUseEnrollment(token) || presentation.value.enrollment.busy || presentation.value.enrollment.requestId != null) return
        setEnrollment { it.copy(busy = true, statusUnavailable = false) }
        try {
            val status = dependencies.enrollmentGateway.status()
            currentCoroutineContext().ensureActive()
            if (canUseEnrollment(token)) setEnrollment { it.copy(status = status,
                requestId = status.pendingRequestId,
                result = if (status.pendingRequestId != null) LocalEnrollmentResult.Unresolved else null) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (canUseEnrollment(token)) setEnrollment { it.copy(status = null, statusUnavailable = true) }
        } finally {
            if (canUseEnrollment(token)) setEnrollment { it.copy(busy = false) }
        }
    }

    internal fun launchEnrollment(scope: CoroutineScope, account: String, displayName: String, password: CharArray,
        approved: Boolean, token: NavigationToken): Job = scope.launch {
        enroll(account, displayName, password, approved, token)
    }.also { it.invokeOnCompletion { password.fill('\u0000') } }

    private suspend fun enroll(account: String, displayName: String, password: CharArray, approved: Boolean, token: NavigationToken) {
        var ownsBusy = false
        try {
            if (!canUseEnrollment(token)) return
            val current = presentation.value.enrollment
            val status = current.status ?: return
            if (current.busy || current.requestId != null || !status.available ||
                status.mode == LocalEnrollmentMode.DISABLED || !approved) return
            if (!localEnrollmentInputSupported(account, displayName, password.size, status.supportsDisplayName)) {
                setEnrollment { it.copy(result = LocalEnrollmentResult.Rejected(LocalServiceProblem.INVALID_INPUT)) }
                return
            }
            val requestId = DomainId.random()
            ownsBusy = true
            setEnrollment { it.copy(busy = true, requestId = requestId, result = null) }
            val result = dependencies.enrollmentGateway.enroll(requestId, account, displayName, password)
            currentCoroutineContext().ensureActive()
            if (canUseEnrollment(token)) applyEnrollmentResult(result)
        } catch (cancelled: CancellationException) {
            if (ownsBusy && canUseEnrollment(token)) setEnrollment { it.copy(result = LocalEnrollmentResult.Unresolved) }
            throw cancelled
        } catch (_: Exception) {
            if (ownsBusy && canUseEnrollment(token)) setEnrollment { it.copy(result = LocalEnrollmentResult.Unresolved) }
        } finally {
            password.fill('\u0000')
            if (ownsBusy && canUseEnrollment(token)) setEnrollment { it.copy(busy = false) }
        }
    }

    internal suspend fun recoverEnrollment(token: NavigationToken) {
        if (!canUseEnrollment(token) || presentation.value.enrollment.busy) return
        val requestId = presentation.value.enrollment.requestId ?: return
        setEnrollment { it.copy(busy = true) }
        try {
            val result = dependencies.enrollmentGateway.lookup(requestId)
            currentCoroutineContext().ensureActive()
            if (canUseEnrollment(token)) applyEnrollmentResult(result)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (canUseEnrollment(token)) setEnrollment { it.copy(result = LocalEnrollmentResult.Unresolved) }
        } finally {
            if (canUseEnrollment(token)) setEnrollment { it.copy(busy = false) }
        }
    }

    private fun applyEnrollmentResult(result: LocalEnrollmentResult) = setEnrollment { it.copy(result = result,
        requestId = if (result is LocalEnrollmentResult.Rejected) null else it.requestId) }

    private fun canUseEnrollment(token: NavigationToken): Boolean = !closed && navigator.isCurrent(token) &&
        navigator.state.value.root == SecurityRoot.LOCAL_ENROLLMENT

    private fun setEnrollment(transform: (LocalEnrollmentPresentation) -> LocalEnrollmentPresentation) {
        mutablePresentation.value = presentation.value.copy(enrollment = transform(presentation.value.enrollment))
    }

    /**
     * The authenticated root's effect owns restoration, not the sign-in entry removed on success.
     * It is canceled when that root leaves composition and cannot read through another session.
     * Training has no authenticated capability and never invokes these authorization reads.
     */
    internal suspend fun restoreAuthenticatedRoutes(): Boolean {
        currentCoroutineContext().ensureActive()
        if (closed || presentation.value.training) return false
        val token = navigator.token()
        return when (navigator.state.value.root) {
            SecurityRoot.BRANCH_WORKSPACE -> {
                val session = branchSession ?: return false
                val attachment = session.attachmentKey
                navigator.restoreAfterAuthentication { route ->
                    !closed && navigator.isCurrent(token) && branchSession === session &&
                        attachment == session.attachmentKey && session.mayRestore(route) &&
                        !closed && navigator.isCurrent(token) && branchSession === session && attachment == session.attachmentKey
                }
            }
            SecurityRoot.COMPANY_WORKSPACE -> {
                val session = centralSession ?: return false
                navigator.restoreAfterAuthentication { route ->
                    !closed && navigator.isCurrent(token) && centralSession === session && session.mayRestore(route)
                }
            }
            else -> false
        }
    }

    /** Root-owned protected-session monitoring, independent of the removed sign-in entry. */
    internal suspend fun monitorAuthenticatedSession(): Unit = coroutineScope {
        val session = branchSession ?: return@coroutineScope
        val lifecycle = session.lifecycle
        val attachments = session.attachmentUpdates
        if (lifecycle == null && attachments == null) return@coroutineScope
        val generation = navigator.state.value.rootGeneration
        fun currentOwner(): Boolean = !closed && branchSession === session &&
            navigator.state.value.rootGeneration == generation && navigator.state.value.root == SecurityRoot.BRANCH_WORKSPACE
        if (lifecycle != null) launch {
            lifecycle.collect { state ->
                if (currentOwner() && state != LocalSessionLifecycle.ACTIVE) localSessionEnded(session, when (state) {
                    LocalSessionLifecycle.STORAGE_UNCERTAIN -> LocalServiceProblem.STORAGE_UNCERTAIN
                    LocalSessionLifecycle.AUTHORITY_UNAVAILABLE -> LocalServiceProblem.UNAVAILABLE
                    else -> LocalServiceProblem.SESSION_ENDED
                })
            }
        }
        if (attachments != null) launch {
            attachments.collect { attachment ->
                if (!currentOwner()) return@collect
                if (attachment == null) localSessionEnded(session)
                else if (attachment != presentation.value.localAttachment) {
                    // This is a read, never another refresh. The adapter serializes it with
                    // credential replacement; a flow/presentation key is not Current evidence.
                    val requestedAttachment = session.attachmentKey
                    val result = try { session.current() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { LocalSessionCheckResult.Unavailable(LocalServiceProblem.UNAVAILABLE, requestedAttachment) }
                    if (currentOwner()) acceptLocalSessionCheck(session, result)
                }
            }
        }
        while (currentOwner()) {
            val requestedAttachment = session.attachmentKey
            val result = try { session.current() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { LocalSessionCheckResult.Unavailable(LocalServiceProblem.UNAVAILABLE, requestedAttachment) }
            if (!currentOwner()) break
            acceptLocalSessionCheck(session, result)
            delay(15_000) // scheduling only, never expiry/monotonic authority or a cloud lease
        }
    }

    internal fun refreshLocalSession(token: NavigationToken) {
        if (closed || !navigator.isCurrent(token) || presentation.value.refreshingSession) return
        val session = branchSession ?: return
        try { denyRetailPhysical() }
        catch (failure: Throwable) {
            // A failed pre-refresh denial is terminal cleanup, not permission to keep the
            // old private UI interactive or to start a successor attachment.
            localSessionRetirementFailed(session, failure)
            return
        }
        mutablePresentation.value = presentation.value.copy(refreshingSession = true)
        cleanupScope.launch {
            val requestedAttachment = session.attachmentKey
            try {
                val result = session.refresh()
                acceptLocalSessionCheck(session, result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                acceptLocalSessionCheck(session, LocalSessionCheckResult.Unavailable(LocalServiceProblem.UNAVAILABLE, requestedAttachment))
            }
            finally { if (branchSession === session && !closed) mutablePresentation.value = presentation.value.copy(refreshingSession = false) }
        }
    }

    private fun acceptLocalSessionCheck(session: BranchUiSession, result: LocalSessionCheckResult) {
        if (closed || branchSession !== session) return
        // Late success, denial or failure belongs to its captured attachment, not whichever
        // generation happens to be current when this suspended call resumes. In particular,
        // discard it before comparing display fields or ending a valid successor session.
        val next = result.attachmentKey
        if (next != session.attachmentKey) return
        if (result is LocalSessionCheckResult.Unavailable) {
            localSessionEnded(session, result.problem)
            return
        }
        result as LocalSessionCheckResult.Current
        // A concurrent transition can retire this read before delivery. Do not adopt it and do
        // not mistake an in-progress known refresh for logout; the root monitor observes its end.
        val current = try { session.isCurrent() } catch (_: Exception) {
            if (next == session.attachmentKey) localSessionEnded(session, LocalServiceProblem.UNAVAILABLE)
            return
        }
        if (!current || next != session.attachmentKey) return
        val before = presentation.value.display
        if (before == null || result.display.actorId != before.actorId || result.display.companyId != before.companyId ||
            result.display.branchId != before.branchId) {
            // A same-owner attachment cannot transfer private state to a different principal.
            localSessionEnded(session)
            return
        }
        val previous = presentation.value.localAttachment
        if (session.attachmentUpdates == null && next != null || session.attachmentUpdates != null &&
            (next == null || previous == null || next.ownerId != previous.ownerId || next.generation < previous.generation)) {
            localSessionEnded(session)
            return
        }
        if (next == previous) {
            if (result.display != before) localSessionEnded(session) // inconsistent same-attachment projection
            return
        }
        val oldEpochs = before.localDraftEpochs
        val newEpochs = result.display.localDraftEpochs
        if (oldEpochs != null && (newEpochs == null || newEpochs.general < oldEpochs.general || newEpochs.cashier < oldEpochs.cashier)) {
            localSessionEnded(session)
            return
        }
        val retainGeneral = if (oldEpochs != null && newEpochs != null) oldEpochs.general == newEpochs.general
            else result.display == before
        val retainCashier = if (oldEpochs != null && newEpochs != null) oldEpochs.cashier == newEpochs.cashier else retainGeneral
        val retainedEntries = navigator.state.value.stacks.filterKeys { it in result.display.allowedAreas }.values
            .flatten().filter { entry -> retainGeneral || retainCashier && when (entry.route) {
                AppRoute.Cashier, AppRoute.QuotePreview, AppRoute.Orders, is AppRoute.Order -> true
                else -> false
            } }.map { it.id }.toSet()
        try {
            denyRetailPhysical()
            // Retire jobs, physical originals and export lifetimes before publishing new UI
            // consumers. Only data-only continuation stays in the SAME entry ViewModel.
            rootScope.retireLocalAttachments(retainedEntries, retainGeneral)
            navigator.reconcileLocalAreas(result.display, retainedEntries)
        } catch (failure: Throwable) {
            // Root-owned cleanup uncertainty is terminal even if the attachment advanced.
            localSessionRetirementFailed(session, failure)
            return
        }
        val stillCurrent = try { session.isCurrent() } catch (_: Exception) {
            if (next == session.attachmentKey) localSessionEnded(session, LocalServiceProblem.UNAVAILABLE)
            return
        }
        if (closed || branchSession !== session || !stillCurrent || next != session.attachmentKey) return
        rootScope.session.display = result.display
        mutablePresentation.value = presentation.value.copy(display = result.display, localAttachment = next)
    }

    internal suspend fun loadCleanupStatus(token: NavigationToken, retry: Boolean = false) {
        if (closed || !navigator.isCurrent(token) || presentation.value.checkingCleanup ||
            navigator.state.value.root !in setOf(SecurityRoot.BOOTSTRAP, SecurityRoot.BRANCH_SIGN_IN) || !dependencies.branchGateway.configured) return
        mutablePresentation.value = presentation.value.copy(checkingCleanup = true)
        try {
            if (retry) {
                val cleanup = LocalSessionCleanup()
                cleanup.attempt { denyRetailPhysical() }
                cleanup.attempt { drainPendingUiCleanup() }
                cleanup.throwIfFailed()
                otherCleanupNeedsAttention = false
            }
            val status = if (retry) dependencies.branchGateway.retryCleanup() else dependencies.branchGateway.cleanupStatus()
            currentCoroutineContext().ensureActive()
            if (!closed && navigator.isCurrent(token)) mutablePresentation.value = presentation.value.copy(
                cleanupStatus = status, cleanupNeedsAttention = otherCleanupNeedsAttention || !status.clear)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (!closed && navigator.isCurrent(token)) mutablePresentation.value = presentation.value.copy(
                cleanupStatus = LocalClientCleanupStatus(available = false), cleanupNeedsAttention = true)
        } finally { if (!closed && navigator.isCurrent(token)) mutablePresentation.value = presentation.value.copy(checkingCleanup = false) }
    }

    fun navigate(action: NavigationAction, token: NavigationToken, exitPlatform: () -> Unit = {}) {
        val terminal = leavesRoot(action)
        val result = try { navigator.navigate(action, token) }
        catch (failure: Throwable) {
            if (terminal) terminalNavigationFailed(failure)
            throw failure
        }
        handle(result, exitPlatform)
    }

    fun confirmLeave(id: Long, exitPlatform: () -> Unit = {}) {
        val terminal = navigator.state.value.confirmation?.takeIf { it.id == id }?.action?.let(::leavesRoot) == true
        val result = try { navigator.confirmLeave(id) }
        catch (failure: Throwable) {
            if (terminal) terminalNavigationFailed(failure)
            throw failure
        }
        handle(result, exitPlatform)
    }

    private fun leavesRoot(action: NavigationAction): Boolean =
        action == NavigationAction.CloseHost || action == NavigationAction.ExitWorkspace ||
            action == NavigationAction.Back && navigator.state.value.activeStack.size == 1

    private fun terminalNavigationFailed(failure: Throwable): Nothing {
        try { if (!closed) changeRoot(SecurityRoot.BOOTSTRAP) }
        catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        otherCleanupNeedsAttention = true
        mutablePresentation.value = presentation.value.copy(locallyLocked = true,
            sessionProblem = LocalServiceProblem.STORAGE_UNCERTAIN, cleanupNeedsAttention = true)
        throw failure // Failed cleanup is not a successful departure or confirmed physical exit.
    }

    private fun handle(result: NavigationResult, exitPlatform: () -> Unit) {
        when (result) {
            NavigationResult.LEAVE_WORKSPACE, NavigationResult.LEAVE_AUTHENTICATION -> signOut()
            NavigationResult.EXIT_PLATFORM -> {
                try { denyRetailPhysical() }
                catch (failure: Throwable) { terminalNavigationFailed(failure) }
                exitPlatform()
            }
            else -> Unit
        }
    }

    /** Immediate UI clearance, then revocation-only cleanup. Unknown cleanup is NOT signed out remotely. */
    fun signOut() {
        if (closed) return
        val outgoing = branchSession
        changeRoot(SecurityRoot.BOOTSTRAP)
        if (outgoing == null) return
        pendingRevocations++
        mutablePresentation.value = presentation.value.copy(revocationPending = true, locallyLocked = true)
        cleanupScope.launch {
            var revoked = false
            try { revoked = outgoing.logout() == LocalSessionEndResult.REVOKED }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Deliberately no exception diagnostics or credential data in UI. */ }
            finally {
                pendingRevocations--
                if (!closed) mutablePresentation.value = presentation.value.copy(
                    revocationPending = pendingRevocations > 0,
                    cleanupNeedsAttention = presentation.value.cleanupNeedsAttention || !revoked,
                )
            }
        }
    }

    /** Lock bypasses dirty/critical leave guards so an operator can always hide private data. */
    fun lock(token: NavigationToken) {
        if (closed || !navigator.isCurrent(token)) return
        changeRoot(SecurityRoot.BOOTSTRAP)
        mutablePresentation.value = presentation.value.copy(locallyLocked = true)
    }

    internal fun localSessionFor(token: NavigationToken): BranchUiSession? =
        if (!closed && !presentation.value.training && navigator.isCurrent(token) &&
            navigator.state.value.root == SecurityRoot.BRANCH_WORKSPACE &&
            navigator.state.value.selectedArea != AppArea.SESSION) branchSession else null

    internal fun ownsLocalEntry(session: BranchUiSession, token: NavigationToken): Boolean =
        !closed && branchSession === session && navigator.state.value.root == SecurityRoot.BRANCH_WORKSPACE &&
            navigator.state.value.selectedArea != AppArea.SESSION && navigator.isActiveEntry(token) &&
            try { session.isCurrent() } catch (_: Exception) { false }

    /** An old callback cannot read the current key lazily and inherit a replacement adapter. */
    internal fun ownsLocalAttachment(session: BranchUiSession, token: NavigationToken, key: LocalSessionAttachmentKey?): Boolean =
        ownsLocalEntry(session, token) && key == presentation.value.localAttachment && key == session.attachmentKey &&
            (session.attachmentUpdates == null || key != null)

    /** Attach before any original Start await. The UI registry can only withdraw real rights. */
    internal fun retailPhysicalLifetime(session: BranchUiSession, token: NavigationToken,
        key: LocalSessionAttachmentKey? = session.attachmentKey): LocalRetailPhysicalOwner? {
        if (!ownsLocalAttachment(session, token, key) || presentation.value.refreshingSession) return null
        val lifetime = LocalRetailPhysicalLifetime()
        val root = retailRootLifetime.attach(lifetime) ?: return null
        val entry = navigator.attachRetailPhysical(token, lifetime)
        if (entry == null) { root.close(); return null }
        return LocalRetailPhysicalOwner(lifetime, root, entry)
    }

    private fun denyRetailPhysical() {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { retailRootLifetime.deny() }
        cleanup.attempt { navigator.denyRetailPhysical() }
        // Fresh negative attachment capacity only. No old core lifetime can ever be renewed.
        // Failed exact denial custody must remain reachable, never replaced by fresh capacity.
        if (!cleanup.failed) retailRootLifetime = LocalRetailPhysicalLifetime()
        cleanup.throwIfFailed()
    }

    internal fun localSessionEnded(session: BranchUiSession, problem: LocalServiceProblem = LocalServiceProblem.SESSION_ENDED) {
        if (!closed && branchSession === session) {
            changeRoot(SecurityRoot.BOOTSTRAP)
            mutablePresentation.value = presentation.value.copy(locallyLocked = true, sessionProblem = problem)
        }
    }

    private fun localSessionRetirementFailed(session: BranchUiSession, failure: Throwable) {
        try { localSessionEnded(session, LocalServiceProblem.STORAGE_UNCERTAIN) }
        catch (cleanupFailure: Throwable) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
        }
        otherCleanupNeedsAttention = true
        mutablePresentation.value = presentation.value.copy(cleanupNeedsAttention = true)
        if (failure !is Exception) throw failure
    }

    fun attachHost() { if (!closed) navigator.attachHost() }

    fun detachHost() {
        if (closed) return
        changeRoot(SecurityRoot.BOOTSTRAP)
        closeCapability { navigator.detachHost() }
    }

    private fun changeRoot(root: SecurityRoot, display: SessionDisplayContext? = null, training: TrainingWorkspace? = null): Boolean {
        retainCurrentUiCleanup()
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { denyRetailPhysical() }
        val requestedRoot = root.takeUnless { cleanup.failed } ?: SecurityRoot.BOOTSTRAP
        val requestedDisplay = display.takeUnless { cleanup.failed }
        val previousGeneration = navigator.state.value.rootGeneration
        cleanup.attempt {
            navigator.replaceRoot(requestedRoot, requestedDisplay) {
                // Old navigation callbacks are already fenced. Every pending child and
                // capability must retire before a requested authenticated root is published.
                branchSession = null
                centralSession = null
                drainPendingUiCleanup()
            }
        }
        if (cleanup.failed && navigator.state.value.rootGeneration == previousGeneration) {
            // Invalid replacement metadata may refuse before navigation retirement starts.
            // It must still retire the old root, rather than reuse its scope identity.
            cleanup.attempt {
                navigator.replaceRoot(SecurityRoot.BOOTSTRAP, null) {
                    branchSession = null
                    centralSession = null
                    drainPendingUiCleanup()
                }
            }
        }
        branchSession = null
        centralSession = null
        val accepted = !cleanup.failed
        val nextDisplay = display.takeIf { accepted }
        val nextTraining = training.takeIf { accepted }
        rootScope = newRootScope(nextDisplay, nextTraining)
        if (!accepted) otherCleanupNeedsAttention = true
        mutablePresentation.value = RuntimePresentation(
            language = presentation.value.language, display = nextDisplay, training = nextTraining != null,
            cleanupNeedsAttention = presentation.value.cleanupNeedsAttention || !accepted,
            revocationPending = presentation.value.revocationPending,
        )
        try { cleanup.throwIfFailed() }
        catch (_: Exception) { return false } // UI reports cleanup attention; no replacement was admitted.
        return true
    }

    private fun retainCurrentUiCleanup() {
        val outgoingRoot = rootScope
        pendingUiCleanup += { outgoingRoot.close() }
        branchSession?.let { outgoing -> pendingUiCleanup += { outgoing.close() } }
        centralSession?.let { outgoing -> pendingUiCleanup += { outgoing.close() } }
    }

    private fun drainPendingUiCleanup() {
        val cleanup = LocalSessionCleanup()
        pendingUiCleanup.toList().forEach { close ->
            if (cleanup.attempt(close)) pendingUiCleanup.remove(close)
        }
        cleanup.throwIfFailed()
    }

    private fun closeCapability(close: () -> Unit) {
        try { close() } catch (error: Throwable) {
            pendingUiCleanup += close
            otherCleanupNeedsAttention = true
            mutablePresentation.value = presentation.value.copy(cleanupNeedsAttention = true)
            if (error !is Exception) throw error
        }
    }

    private fun newRootScope(display: SessionDisplayContext?, training: TrainingWorkspace?): UiRootScope {
        val session = UiRootSession(display, training)
        val id = "client-root-${navigator.state.value.rootGeneration}"
        return UiRootScope(container.koin.createScope(id, ROOT_SCOPE, source = session), session)
    }

    fun close() {
        val wasClosed = closed
        closed = true // Fence reentrant and late UI callbacks before any negative traversal.
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { denyRetailPhysical() }
        if (!wasClosed) {
            retainCurrentUiCleanup()
            cleanup.attempt {
                navigator.replaceRoot(SecurityRoot.BOOTSTRAP, null) {
                    branchSession = null
                    centralSession = null
                    drainPendingUiCleanup()
                }
            }
            branchSession = null
            centralSession = null
            cleanup.attempt { navigator.detachHost() }
            val cancelScope = { cleanupScope.cancel() }
            if (!cleanup.attempt(cancelScope)) pendingUiCleanup += cancelScope
            val closeContainer = { container.close() }
            if (!cleanup.attempt(closeContainer)) pendingUiCleanup += closeContainer
            mutablePresentation.value = RuntimePresentation(language = presentation.value.language,
                cleanupNeedsAttention = presentation.value.cleanupNeedsAttention || cleanup.failed,
                revocationPending = presentation.value.revocationPending)
        } else {
            // Repeated close retries exact failed negative holders without reopening a UI scope.
            cleanup.attempt { drainPendingUiCleanup() }
        }
        if (cleanup.failed) {
            otherCleanupNeedsAttention = true
            mutablePresentation.value = presentation.value.copy(cleanupNeedsAttention = true)
        }
        cleanup.throwIfFailed()
    }
}

internal data class UiRootSession(var display: SessionDisplayContext?, val training: TrainingWorkspace?)

internal class UiRootScope(val koinScope: Scope, val session: UiRootSession) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
    private val localEntries = mutableSetOf<EntryViewModel>()
    fun register(entry: EntryViewModel) { localEntries += entry }
    fun unregister(entry: EntryViewModel) { localEntries -= entry }
    fun retireLocalAttachments(retainedEntries: Set<Long>, retainGeneral: Boolean) {
        val cleanup = LocalSessionCleanup()
        localEntries.toList().forEach { entry ->
            cleanup.attempt {
                if (entry.entryId in retainedEntries) entry.retireLocalAttachments(retainGeneral)
                else entry.clearLocalEntry()
            }
        }
        cleanup.throwIfFailed()
    }
    fun clearDepartingEntry(entryId: Long) {
        val cleanup = LocalSessionCleanup()
        localEntries.filter { it.entryId == entryId }.forEach { entry -> cleanup.attempt { entry.clearLocalEntry() } }
        cleanup.throwIfFailed()
    }
    fun close() {
        val cleanup = LocalSessionCleanup()
        // Nav3 may remove a store before child disposal fails. Close each known entry directly.
        localEntries.toList().forEach { entry -> cleanup.attempt { entry.closeResources() } }
        cleanup.attempt { viewModelStore.clear() }
        cleanup.attempt { session.training?.close() }
        cleanup.attempt { koinScope.close() }
        session.display = null
        // Failed exact holders remain reachable for a later negative-only cleanup attempt.
        if (!cleanup.failed) localEntries.clear()
        cleanup.throwIfFailed()
    }
}

internal data class EntryPresentation(
    val query: String = "",
    val category: TrainingCategory? = null,
    val countedCash: String = "",
    val rehearsalChecks: Set<Int> = emptySet(),
    val notice: Boolean = false,
    val quantityLimit: Boolean = false,
)

internal class EntryState {
    val value = MutableStateFlow(EntryPresentation())
}

/** Koin factory + Nav3 entry ViewModelStore own this, never a process singleton. */
internal class EntryViewModel(val entryId: Long, val resources: EntryState, private val entryScope: Scope) : ViewModel() {
    private var localController: LocalWorkflowController? = null
    private var administrationController: LocalAdministrationController? = null
    private var setupClosureController: LocalSetupClosureController? = null
    private var workflowContinuation: LocalWorkflowContinuation? = null
    private var administrationContinuation: LocalAdministrationWorkspace? = null
    private var localOwner: UiRootScope? = null
    // The entry, not a lazy-list child or replaceable adapter, owns typed draft data.
    val companyCatalogDrafts = LocalCompanyCatalogEntryDrafts()
    val foodConfigurationDrafts = LocalFoodConfigurationEntryDrafts()
    val returnRightsDrafts = LocalReturnRightsEntryDrafts()
    val pricePolicyDrafts = LocalPricePolicyEntryDrafts()
    val catalogReleaseDrafts = LocalCatalogReleaseEntryDrafts()
    val serialRegistrationDrafts = LocalSerialRegistrationEntryDrafts()
    val state: StateFlow<EntryPresentation> = resources.value.asStateFlow()
    val scopeClosed: Boolean get() = entryScope.closed
    fun change(transform: (EntryPresentation) -> EntryPresentation) {
        if (!entryScope.closed) resources.value.value = transform(resources.value.value)
    }
    fun localWorkflows(runtime: UkkeraRuntime, token: NavigationToken,
        attachmentKey: LocalSessionAttachmentKey? = runtime.presentation.value.localAttachment): LocalWorkflowController? {
        if (entryScope.closed) return null
        val session = runtime.localSessionFor(token) ?: return null
        register(runtime.rootScope)
        localController?.let { return it }
        if (!runtime.ownsLocalAttachment(session, token, attachmentKey)) return null
        return LocalWorkflowController(runtime, session, token, viewModelScope, workflowContinuation,
            employeeAccessDenied = { denyLocalConfigurationAccess(it) }).also {
            localController = it; workflowContinuation = null
        }
    }
    fun localAdministration(runtime: UkkeraRuntime, token: NavigationToken,
        attachmentKey: LocalSessionAttachmentKey? = runtime.presentation.value.localAttachment): LocalAdministrationController? {
        if (entryScope.closed) return null
        val session = runtime.localSessionFor(token) ?: return null
        register(runtime.rootScope)
        administrationController?.let { return it }
        if (!runtime.ownsLocalAttachment(session, token, attachmentKey)) return null
        return LocalAdministrationController(runtime, session, token, viewModelScope, administrationContinuation).also {
            administrationController = it; administrationContinuation = null
        }
    }
    fun setupClosure(runtime: UkkeraRuntime, token: NavigationToken,
        attachmentKey: LocalSessionAttachmentKey? = runtime.presentation.value.localAttachment): LocalSetupClosureController? {
        if (entryScope.closed) return null
        val session = runtime.localSessionFor(token) ?: return null
        register(runtime.rootScope)
        setupClosureController?.let { return it }
        if (!runtime.ownsLocalAttachment(session, token, attachmentKey)) return null
        return LocalSetupClosureController(runtime, session, token, viewModelScope).also {
            setupClosureController = it
        }
    }
    private fun register(owner: UiRootScope) {
        if (localOwner === owner) return
        localOwner?.unregister(this)
        localOwner = owner
        owner.register(this)
    }
    fun retireLocalAttachments(retainGeneral: Boolean = true) {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { localController?.let { workflowContinuation = it.retireForAttachment() }; localController = null }
        cleanup.attempt {
            administrationController?.let { administrationContinuation = it.retireForAttachment() }
            administrationController = null
        }
        cleanup.attempt { setupClosureController?.deactivate(); setupClosureController = null }
        if (retainGeneral) {
            cleanup.attempt { companyCatalogDrafts.retireAttachment() }
            cleanup.attempt { foodConfigurationDrafts.retireAttachment() }
            cleanup.attempt { returnRightsDrafts.retireAttachment() }
            cleanup.attempt { pricePolicyDrafts.retireAttachment() }
            cleanup.attempt { catalogReleaseDrafts.retireAttachment() }
            cleanup.attempt { serialRegistrationDrafts.retireAttachment() }
        } else {
            // Route and actual workflow must both be ordinary cashier contexts. A retained
            // Orders entry can otherwise still contain food/report/return editor material.
            workflowContinuation = workflowContinuation?.cashierPolicyContinuation()
            administrationContinuation = null
            cleanup.attempt { clearLocalConfigurationDrafts() }
            resources.value.value = EntryPresentation()
        }
        if (cleanup.failed) {
            // Failed cleanup cannot publish a continuation to any successor attachment.
            workflowContinuation = null
            administrationContinuation = null
            cleanup.attempt { clearLocalConfigurationDrafts() }
            resources.value.value = EntryPresentation()
        }
        cleanup.throwIfFailed()
    }
    fun clearLocalConfigurationDrafts() {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { companyCatalogDrafts.clear() }
        cleanup.attempt { foodConfigurationDrafts.clear() }
        cleanup.attempt { returnRightsDrafts.clear() }
        cleanup.attempt { pricePolicyDrafts.clear() }
        cleanup.attempt { catalogReleaseDrafts.clear() }
        cleanup.attempt { serialRegistrationDrafts.clear() }
        cleanup.throwIfFailed()
    }
    fun denyLocalConfigurationAccess(controller: LocalWorkflowController): Boolean {
        // A retired callback must never clear the replacement controller's holders.
        if (localController !== controller) return false
        try { companyCatalogDrafts.clear(denied = true) }
        finally {
            try { foodConfigurationDrafts.clear(denied = true) }
            finally {
                try { returnRightsDrafts.clear(denied = true) }
                finally {
                    try { pricePolicyDrafts.clear(denied = true) }
                    finally {
                        try { catalogReleaseDrafts.clear(denied = true) }
                        finally { serialRegistrationDrafts.clear(denied = true) }
                    }
                }
            }
        }
        return true
    }
    /** Called synchronously by the sole navigator, including coalesced away-and-back. */
    fun clearLocalEntry() {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { localController?.close(); localController = null }
        cleanup.attempt { administrationController?.close(); administrationController = null }
        cleanup.attempt { setupClosureController?.deactivate(); setupClosureController = null }
        workflowContinuation = null
        administrationContinuation = null
        cleanup.attempt { clearLocalConfigurationDrafts() }
        resources.value.value = EntryPresentation()
        cleanup.throwIfFailed()
    }
    /** Final negative-only disposal remains retryable after Nav3 removes the entry store. */
    fun closeResources() {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { clearLocalEntry() }
        cleanup.attempt { entryScope.close() }
        if (!cleanup.failed) {
            localOwner?.unregister(this)
            localOwner = null
        }
        cleanup.throwIfFailed()
    }
    override fun onCleared() { closeResources() }
}

private val ROOT_SCOPE = named("pos-client-root")
private val ENTRY_SCOPE = named("pos-navigation-entry")
private fun clientModule() = module {
    scope(ROOT_SCOPE) {
        factory { (entryId: Long) ->
            val child = getKoin().createScope("$id/entry/$entryId", ENTRY_SCOPE)
            child.linkTo(this)
            EntryViewModel(entryId, child.get(), child)
        }
    }
    scope(ENTRY_SCOPE) { scoped { EntryState() } }
}
