package com.ukkera.pos.app

import com.ukkera.pos.app.setup.LocalSetupEmployeePolicyTarget
import com.ukkera.pos.app.navigation.GuardHandle
import com.ukkera.pos.app.navigation.LeaveGuard
import com.ukkera.pos.app.navigation.NavigationToken
import com.ukkera.pos.security.LocalAdministrationAction
import com.ukkera.pos.security.LocalDelegatedEmployeeCreationV1
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.security.LocalEmployeeCreationTargetV1
import com.ukkera.pos.security.LocalEmployeeCreationDraftV1
import com.ukkera.pos.security.LocalAdministrationReactivationRevisions
import com.ukkera.pos.security.LocalAdministrationActivationRevisions
import com.ukkera.pos.security.LocalAdministrationCommand
import com.ukkera.pos.security.LocalAdministrationDirectory
import com.ukkera.pos.security.LocalAdministrationQuery
import com.ukkera.pos.security.LocalAdministrationReadResult
import com.ukkera.pos.security.LocalAdministrationView
import com.ukkera.pos.security.SessionStatus
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal enum class LocalAdministrationBusy { NONE, READING, RECOVERING, PREPARING, SUBMITTING, CANCELLING, ACKNOWLEDGING }

internal data class LocalAdministrationWorkspace(
    val query: LocalAdministrationQuery = LocalAdministrationQuery(LocalAdministrationDirectory.ACCOUNTS),
    val view: LocalAdministrationView? = null,
    val targetInput: String = "",
    val invalidTarget: Boolean = false,
    val readProblem: LocalServiceProblem? = null,
    val restartRequired: Boolean = false,
    val busy: LocalAdministrationBusy = LocalAdministrationBusy.NONE,
    val pendingLoaded: Boolean = false,
    val pendingProblem: LocalServiceProblem? = null,
    /** Exact metadata retained even when local preparation has an uncertain response. No secret. */
    val original: LocalAdministrationCommand? = null,
    val pending: LocalAdministrationPending? = null,
    val operationProblem: LocalServiceProblem? = null,
    val preparationUncertain: Boolean = false,
    val acknowledgementAttempted: Boolean = false,
    val passwordEntryOpen: Boolean = false,
    /** Keep the exact protected original for recovery without redisplaying denied metadata. */
    val originalDetailsRestricted: Boolean = false,
    val creation: LocalEmployeeCreationWorkspace = LocalEmployeeCreationWorkspace(),
) {
    val displayedOriginal: LocalAdministrationCommand? get() = original.takeUnless { originalDetailsRestricted }
    val working: Boolean get() = busy != LocalAdministrationBusy.NONE
    val creationOwnsInput: Boolean get() = creation.target != null || creation.passwordEntryOpen
    val canPrepare: Boolean get() = !working && pendingLoaded && pendingProblem == null && original == null &&
        pending == null && view != null && readProblem == null && !restartRequired &&
        !creationOwnsInput
    val canSubmit: Boolean get() = !working && !originalDetailsRestricted && !preparationUncertain && operationProblem == null &&
        !creationOwnsInput && pending?.let { !it.terminal && !it.cancellationRequested &&
            (!it.command.action.requiresNewPassword || it.phase == LocalAdministrationPhase.PREPARED) } == true
}

/**
 * Nav3-entry/employee-owned presentation. The dedicated gateway owns durable requests and
 * protected authorization; this controller never uses the generic business command journal.
 * No password is stored in state, saved state, a draft, a log or an administrative command.
 */
internal class LocalAdministrationController(
    private val runtime: UkkeraRuntime,
    private val session: BranchUiSession,
    private val owner: NavigationToken,
    private val scope: CoroutineScope,
    private var continuation: LocalAdministrationWorkspace? = null,
) {
    private val attachmentKey = session.attachmentKey
    private val gateway = session.administration
    private val creationGateway = gateway.employeeCreation
    val employeeCreationConfigured: Boolean get() = creationGateway.configured
    val operatorId: DomainId get() = session.display.actorId
    private val mutableState = MutableStateFlow(continuation ?: LocalAdministrationWorkspace())
    val state: StateFlow<LocalAdministrationWorkspace> = mutableState.asStateFlow()
    private var active = false
    private var closed = false
    private var activation = 0L
    private var work: Job? = null
    private var monitor: Job? = null
    private var guard: GuardHandle? = null
    private var guardKind = LeaveGuard.CLEAN
    private data class Ticket(val activation: Long, val navigation: NavigationToken)

    fun activate(directory: LocalAdministrationDirectory) {
        if (closed || active || !runtime.ownsLocalAttachment(session, owner, attachmentKey)) return
        active = true
        activation++
        mutableState.value = continuation ?: LocalAdministrationWorkspace(query = LocalAdministrationQuery(directory))
        continuation = null
        val lifecycle = session.lifecycle
        if (!gateway.configured || lifecycle == null) {
            mutableState.value = state.value.copy(readProblem = LocalServiceProblem.NOT_CONFIGURED,
                pendingProblem = LocalServiceProblem.NOT_CONFIGURED)
            return
        }
        val generation = activation
        monitor = scope.launch {
            lifecycle.collect { value ->
                if (active && !closed && generation == activation && value != LocalSessionLifecycle.ACTIVE) {
                    val problem = when (value) {
                        LocalSessionLifecycle.STORAGE_UNCERTAIN -> LocalServiceProblem.STORAGE_UNCERTAIN
                        LocalSessionLifecycle.AUTHORITY_UNAVAILABLE -> LocalServiceProblem.UNAVAILABLE
                        else -> LocalServiceProblem.SESSION_ENDED
                    }
                    // Clear even if the synchronous capability has already become invalid.
                    endSession(problem)
                }
            }
        }
        launchWork(LocalAdministrationBusy.RECOVERING) { ticket ->
            val saved = gateway.pending()
            if (current(ticket)) adoptPending(saved)
            if (!current(ticket)) return@launchWork
            if (directory == LocalAdministrationDirectory.ACCOUNTS && creationGateway.configured) {
                val creation = creationGateway.pending()
                if (current(ticket)) adoptCreationPending(creation)
                if (!current(ticket)) return@launchWork
            }
            val read = gateway.read(state.value.query)
            if (current(ticket)) adoptRead(read)
        }
    }

    fun deactivate() {
        active = false
        activation++ // Fence non-cooperative late replies before cancelling or clearing views.
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { work?.cancel(); work = null }
        cleanup.attempt { monitor?.cancel(); monitor = null }
        mutableState.value = LocalAdministrationWorkspace()
        continuation = null
        cleanup.attempt { removeGuard() }
        cleanup.throwIfFailed()
    }

    fun close() { try { deactivate() } finally { closed = true } }
    fun retireForAttachment(): LocalAdministrationWorkspace? {
        if (closed) { close(); return null }
        if (!active) return continuation.also { close() }
        val before = state.value
        val retained = before.copy(query = before.query.copy(cursor = null), view = null,
            busy = LocalAdministrationBusy.NONE, pendingLoaded = false, pendingProblem = null,
            readProblem = null, passwordEntryOpen = false,
            // Even known metadata is reread before submission; cancellation/ACK uncertainty
            // retains its exact original request. No credential input crosses attachments.
            operationProblem = before.original?.let { LocalServiceProblem.STORAGE_UNCERTAIN },
            creation = before.creation.retire())
        close()
        return retained
    }
    private fun current(generation: Long = activation): Boolean = !closed && active && generation == activation &&
        runtime.ownsLocalAttachment(session, owner, attachmentKey) && session.lifecycle?.value == LocalSessionLifecycle.ACTIVE
    private fun current(ticket: Ticket): Boolean = current(ticket.activation) && runtime.navigator.isCurrent(ticket.navigation)
    private fun accepts(token: NavigationToken): Boolean = current() && runtime.navigator.isCurrent(token) &&
        token.rootGeneration == owner.rootGeneration && token.entryId == owner.entryId && token.area == owner.area
    fun displayCurrent(token: NavigationToken): Boolean = accepts(token)

    /** A new request/target pair is selected once for the no-effect input phase. */
    fun beginCreation(token: NavigationToken, actorId: DomainId? = null) {
        if (!creationActionAvailable(token) || state.value.original != null || state.value.creation.original != null ||
            !state.value.creation.loaded || state.value.creation.restricted || state.value.creation.problem != null) return
        val target = state.value.creation.target ?: LocalEmployeeCreationTargetV1(DomainId.random(), actorId ?: DomainId.random())
        if (target.actorId == operatorId) return
        mutableState.value = state.value.copy(creation = state.value.creation.copy(target = target, context = null))
        launchCreationWork(LocalAdministrationBusy.READING) { ticket ->
            when (val result = creationGateway.context(target)) {
                is LocalEmployeeCreationContextResult.Available -> if (current(ticket)) {
                    val context = result.context
                    if (context.target != target || context.operatorId != operatorId ||
                        context.scope.companyId != session.display.companyId || context.scope.branchId != session.display.branchId)
                        creationFailed(LocalServiceProblem.INVALID_INPUT)
                    else mutableState.value = state.value.copy(creation = state.value.creation.copy(context = context))
                }
                is LocalEmployeeCreationContextResult.Refused -> if (current(ticket)) creationFailed(LocalServiceProblem.INVALID_INPUT)
                is LocalEmployeeCreationContextResult.Unavailable -> if (current(ticket)) creationFailed(result.problem)
            }
        }
    }

    fun discardCreationInputs(token: NavigationToken) {
        if (!creationActionAvailable(token) || state.value.creation.original != null) return
        mutableState.value = state.value.copy(creation = state.value.creation.copy(target = null, context = null))
        syncGuard()
    }

    /** Capture with actual protected codecs/admissions, retain BEFORE attempting journal IO. */
    fun prepareCreation(token: NavigationToken, definition: LocalAccountProvisioning, credentialWasProvided: Boolean) {
        if (!creationActionAvailable(token) || state.value.creation.original != null) return
        val context = state.value.creation.context ?: return
        if (context.scope != definition.policy.scope || context.target.actorId != definition.actorId ||
            context.admittedDeviceIds != definition.admittedDeviceIds) return
        val draft = LocalEmployeeCreationDraftV1(context.target.requestId, definition, credentialWasProvided)
        launchCreationWork(LocalAdministrationBusy.PREPARING) { ticket ->
            when (val result = creationGateway.capture(draft)) {
                is LocalEmployeeCreationDefinitionResult.Captured -> if (current(ticket)) {
                    val original = result.original
                    if (original.operatorId != operatorId || !draft.matchesRequested(original)) {
                        creationFailed(LocalServiceProblem.INVALID_INPUT)
                        return@launchCreationWork
                    }
                    mutableState.value = state.value.copy(creation = state.value.creation.copy(original = original,
                        target = null, context = null, pending = null, loaded = false, restricted = true))
                    val prepared = creationGateway.prepare(original)
                    if (current(ticket)) adoptCreationControl(prepared, original)
                }
                is LocalEmployeeCreationDefinitionResult.Refused -> if (current(ticket)) creationFailed(LocalServiceProblem.INVALID_INPUT)
                is LocalEmployeeCreationDefinitionResult.Unavailable -> if (current(ticket)) creationFailed(result.problem)
            }
        }
    }

    /** Idempotent local preparation only; the gateway never rearms DISPATCHED originals. */
    fun retryCreationPreparation(token: NavigationToken) {
        if (!creationActionAvailable(token)) return
        val original = state.value.creation.original ?: return
        if (state.value.creation.displayed != null) return
        launchCreationWork(LocalAdministrationBusy.PREPARING) { ticket ->
            val result = creationGateway.prepare(original)
            if (current(ticket)) adoptCreationControl(result, original)
        }
    }

    fun openCreationPasswordEntry(token: NavigationToken) {
        if (!creationActionAvailable(token) || !state.value.creation.canSubmit ||
            state.value.creation.displayed?.original?.copyRequestedDefinition()?.credentialWasProvided != true) return
        mutableState.value = state.value.copy(creation = state.value.creation.copy(passwordEntryOpen = true))
        syncGuard()
    }

    fun closeCreationPasswordEntry(token: NavigationToken) {
        if (!accepts(token) || state.value.working) return
        mutableState.value = state.value.copy(creation = state.value.creation.copy(passwordEntryOpen = false))
        syncGuard()
    }

    /** Consumes every caller buffer; the completion hook covers cancelled lazy coroutines. */
    fun submitCreation(token: NavigationToken, password: CharArray? = null) {
        var owned: CharArray? = null
        try {
            if (!accepts(token) || state.value.working || state.value.passwordEntryOpen || !state.value.creation.canSubmit) return
            val selected = state.value.creation.displayed ?: return
            val passwordRequired = selected.original.copyRequestedDefinition().credentialWasProvided
            if (passwordRequired != (password != null) || (passwordRequired &&
                (!state.value.creation.passwordEntryOpen || password!!.size !in 4..1024))) return
            owned = password?.copyOf()
            mutableState.value = state.value.copy(creation = state.value.creation.copy(passwordEntryOpen = false))
            val input = owned
            launchCreationWork(LocalAdministrationBusy.SUBMITTING, secret = input) { ticket ->
                val result = creationGateway.submit(selected.original, input)
                if (current(ticket)) when (result) {
                    is LocalEmployeeCreationSubmissionResult.Inserted -> adoptCreationKnown(result.operation, selected.original)
                    is LocalEmployeeCreationSubmissionResult.Existing -> adoptCreationKnown(result.operation, selected.original)
                    is LocalEmployeeCreationSubmissionResult.NeedsRecovery -> adoptCreationKnown(result.operation, selected.original)
                    is LocalEmployeeCreationSubmissionResult.NotDispatched -> creationFailed(result.problem)
                    is LocalEmployeeCreationSubmissionResult.Refused -> creationFailed(
                        if (result.requestId == selected.original.requestId) LocalServiceProblem.INVALID_INPUT else LocalServiceProblem.STORAGE_UNCERTAIN)
                    is LocalEmployeeCreationSubmissionResult.Unresolved -> creationFailed(
                        if (result.requestId == selected.original.requestId) result.problem else LocalServiceProblem.STORAGE_UNCERTAIN)
                }
            }
            owned = null
        } finally { password?.fill('\u0000'); owned?.fill('\u0000') }
    }

    fun reloadCreation(token: NavigationToken) {
        if (!creationActionAvailable(token) || state.value.creation.target != null) return
        launchCreationWork(LocalAdministrationBusy.RECOVERING) { ticket ->
            val result = creationGateway.pending()
            if (current(ticket)) adoptCreationPending(result)
        }
    }

    /** Lookup may restore a restricted projection only through the exact protected result.
     * An unavailable/missing result never removes or rearms the selected original. */
    fun recoverCreation(token: NavigationToken) {
        if (!creationActionAvailable(token)) return
        val original = state.value.creation.original ?: return
        launchCreationWork(LocalAdministrationBusy.RECOVERING) { ticket ->
            val result = creationGateway.lookup(original)
            if (current(ticket)) adoptCreationRecovery(result, original)
        }
    }

    fun stopCreation(token: NavigationToken) {
        if (!creationActionAvailable(token)) return
        val selected = state.value.creation.displayed?.takeUnless { it.terminal } ?: return
        launchCreationWork(LocalAdministrationBusy.CANCELLING) { ticket ->
            val result = creationGateway.stop(selected.original)
            if (current(ticket)) adoptCreationControl(result, selected.original)
        }
    }

    fun cancelCreation(token: NavigationToken) {
        if (!creationActionAvailable(token)) return
        val selected = state.value.creation.displayed?.takeIf {
            it.phase == LocalEmployeeCreationPhase.DISPATCHED && it.stopRequested } ?: return
        launchCreationWork(LocalAdministrationBusy.CANCELLING) { ticket ->
            val result = creationGateway.cancel(selected.original)
            if (current(ticket)) adoptCreationRecovery(result, selected.original)
        }
    }

    fun acknowledgeCreation(token: NavigationToken) {
        if (!creationActionAvailable(token)) return
        val selected = state.value.creation.displayed?.takeIf { it.terminal } ?: return
        mutableState.value = state.value.copy(creation = state.value.creation.copy(
            acknowledgementAttempted = true, terminalBeforeAcknowledgement = true))
        launchCreationWork(LocalAdministrationBusy.ACKNOWLEDGING) { ticket ->
            val result = creationGateway.acknowledge(selected.original)
            if (current(ticket)) adoptCreationControl(result, selected.original)
        }
    }

    private fun creationActionAvailable(token: NavigationToken): Boolean = accepts(token) &&
        !state.value.working && !state.value.passwordEntryOpen && !state.value.creation.passwordEntryOpen && creationGateway.configured &&
        state.value.query.directory == LocalAdministrationDirectory.ACCOUNTS

    private fun launchCreationWork(kind: LocalAdministrationBusy, secret: CharArray? = null, block: suspend (Ticket) -> Unit) {
        mutableState.value = state.value.copy(creation = state.value.creation.copy(problem = null, observation = null))
        launchWork(kind, secret = secret, failed = { creationFailed(LocalServiceProblem.UNAVAILABLE) }, block = block)
    }

    private fun adoptCreationPending(result: LocalEmployeeCreationPendingResult) {
        when (result) {
            is LocalEmployeeCreationPendingResult.Unavailable -> creationFailed(result.problem)
            is LocalEmployeeCreationPendingResult.Available -> {
                val before = state.value.creation
                val selected = result.operation
                if (selected != null && (selected.original.operatorId != operatorId ||
                    selected.original.scope.companyId != session.display.companyId ||
                    selected.original.scope.branchId != session.display.branchId)) {
                    creationFailed(LocalServiceProblem.STORAGE_UNCERTAIN)
                    return
                }
                if (before.original == null) {
                    mutableState.value = state.value.copy(creation = LocalEmployeeCreationWorkspace(
                        original = selected?.original, pending = selected, loaded = true, restricted = false))
                } else if (selected != null && sameEmployeeCreationOriginal(before.original, selected.original)) {
                    adoptCreationKnown(selected, before.original)
                } else if (selected == null && before.acknowledgementAttempted && before.terminalBeforeAcknowledgement) {
                    // Only a previously selected terminal record plus its explicit local ACK
                    // can use this current local absence to retire presentation custody.
                    mutableState.value = state.value.copy(creation = LocalEmployeeCreationWorkspace(
                        loaded = true, restricted = false,
                        observation = LocalEmployeeCreationObservation.TERMINAL_ACKNOWLEDGED))
                } else {
                    // Do not replace an unresolved original with another journal row, or
                    // turn local absence into server rollback/cancellation/redispatch.
                    creationFailed(LocalServiceProblem.STORAGE_UNCERTAIN)
                }
            }
        }
    }

    private fun adoptCreationKnown(selected: LocalEmployeeCreationPending, original: LocalDelegatedEmployeeCreationV1) {
        val before = state.value.creation
        if (before.original?.let { sameEmployeeCreationOriginal(it, original) } != true ||
            !sameEmployeeCreationOriginal(selected.original, original)) {
            creationFailed(LocalServiceProblem.STORAGE_UNCERTAIN)
            return
        }
        mutableState.value = state.value.copy(creation = before.copy(pending = selected, loaded = true,
            restricted = false, problem = null, observation = null))
    }

    private fun adoptCreationRecovery(result: LocalEmployeeCreationRecoveryResult, original: LocalDelegatedEmployeeCreationV1) {
        when (result) {
            is LocalEmployeeCreationRecoveryResult.Known -> adoptCreationKnown(result.operation, original)
            is LocalEmployeeCreationRecoveryResult.NotObserved -> {
                if (result.requestId != original.requestId) creationFailed(LocalServiceProblem.STORAGE_UNCERTAIN)
                else mutableState.value = state.value.copy(creation = state.value.creation.copy(
                    problem = null, observation = LocalEmployeeCreationObservation.NOT_OBSERVED))
            }
            LocalEmployeeCreationRecoveryResult.NoLocalOriginal -> creationFailed(LocalServiceProblem.UNAVAILABLE)
            is LocalEmployeeCreationRecoveryResult.Unresolved -> creationFailed(
                if (result.requestId == original.requestId) result.problem else LocalServiceProblem.STORAGE_UNCERTAIN)
        }
    }

    private fun adoptCreationControl(result: LocalEmployeeCreationControlResult, original: LocalDelegatedEmployeeCreationV1) {
        when (result) {
            is LocalEmployeeCreationControlResult.Retained -> adoptCreationKnown(result.operation, original)
            LocalEmployeeCreationControlResult.RemovedPrepared -> mutableState.value = state.value.copy(
                creation = LocalEmployeeCreationWorkspace(loaded = true, restricted = false,
                    observation = LocalEmployeeCreationObservation.PREPARED_REMOVED))
            LocalEmployeeCreationControlResult.AcknowledgedTerminal -> mutableState.value = state.value.copy(
                creation = LocalEmployeeCreationWorkspace(loaded = true, restricted = false,
                    observation = LocalEmployeeCreationObservation.TERMINAL_ACKNOWLEDGED))
            LocalEmployeeCreationControlResult.NoLocalOriginal -> creationFailed(LocalServiceProblem.UNAVAILABLE)
            is LocalEmployeeCreationControlResult.Refused -> creationFailed(LocalServiceProblem.INVALID_INPUT)
            is LocalEmployeeCreationControlResult.NotPrepared -> creationFailed(result.problem)
            is LocalEmployeeCreationControlResult.Unresolved -> creationFailed(
                if (result.requestId == original.requestId) result.problem else LocalServiceProblem.STORAGE_UNCERTAIN)
        }
    }

    private fun creationFailed(problem: LocalServiceProblem) {
        val before = state.value.creation
        mutableState.value = state.value.copy(creation = before.copy(pending = null, loaded = false,
            restricted = true, problem = problem, observation = null, context = null, passwordEntryOpen = false))
        handleSessionProblem(problem)
    }

    /** A bounded navigation hint from this exact displayed detail, never an effect permit. */
    fun employeePolicyTarget(displayed: LocalAdministrationView, token: NavigationToken):
        LocalSetupEmployeePolicyTarget? {
        if (!accepts(token) || !state.value.canPrepare || state.value.passwordEntryOpen || state.value.creationOwnsInput ||
            state.value.view !== displayed || displayed.directory != LocalAdministrationDirectory.ACCOUNTS) return null
        val detail = displayed.accountDetail ?: return null
        val account = detail.account
        if (state.value.query.targetId != account.actorId || detail.policy.scope != displayed.scope ||
            detail.policy.actorId != account.actorId || detail.policy.revision != account.policyRevision ||
            account.policyRevision.value == Long.MAX_VALUE) return null
        return LocalSetupEmployeePolicyTarget(displayed.scope, account.actorId, account.policyRevision)
    }

    fun refresh(token: NavigationToken, firstPage: Boolean = false, nextPage: Boolean = false) {
        if (!accepts(token) || state.value.working || state.value.passwordEntryOpen || state.value.creationOwnsInput) return
        val query = when {
            firstPage -> LocalAdministrationQuery(state.value.query.directory)
            nextPage -> state.value.query.copy(cursor = state.value.view?.next ?: return)
            else -> state.value.query.copy(cursor = null)
        }
        read(query)
    }

    fun select(targetId: DomainId, displayed: LocalAdministrationView, token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.passwordEntryOpen || state.value.creationOwnsInput || state.value.view !== displayed) return
        val present = when (displayed.directory) {
            LocalAdministrationDirectory.ACCOUNTS -> displayed.accounts.any { it.actorId == targetId }
            LocalAdministrationDirectory.DEVICES -> displayed.devices.any { it.deviceId == targetId }
        }
        if (present) read(LocalAdministrationQuery(displayed.directory, targetId = targetId))
    }

    fun changeTarget(value: String, token: NavigationToken) {
        if (accepts(token) && !state.value.working && !state.value.passwordEntryOpen && value.length <= 36)
            mutableState.value = state.value.copy(targetInput = value, invalidTarget = false)
    }

    /** An exact target can be authorized even when the operator may not enumerate the directory. */
    fun selectExactTarget(token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.passwordEntryOpen || state.value.creationOwnsInput) return
        val id = try { DomainId(state.value.targetInput) } catch (_: IllegalArgumentException) {
            mutableState.value = state.value.copy(invalidTarget = true)
            return
        }
        read(LocalAdministrationQuery(state.value.query.directory, targetId = id))
    }

    private fun read(query: LocalAdministrationQuery) {
        mutableState.value = state.value.copy(query = query, view = null, readProblem = null, restartRequired = false,
            targetInput = query.targetId?.value.orEmpty(), invalidTarget = false)
        launchWork(LocalAdministrationBusy.READING) { ticket ->
            val result = gateway.read(query)
            if (current(ticket)) adoptRead(result)
        }
    }

    fun prepare(action: LocalAdministrationAction, displayed: LocalAdministrationView, token: NavigationToken,
        sessionId: DomainId? = null) {
        if (!accepts(token) || !state.value.canPrepare || state.value.view !== displayed || state.value.query.targetId == null) return
        val command = if (action == LocalAdministrationAction.REVOKE_DEVICE) {
            val device = displayed.devices.singleOrNull()?.takeIf { it.admitted } ?: return
            if (displayed.directory != LocalAdministrationDirectory.DEVICES || sessionId != null) return
            LocalAdministrationCommand(DomainId.random(), action, device.deviceId, device.revision)
        } else {
            val detail = displayed.accountDetail ?: return
            if (displayed.directory != LocalAdministrationDirectory.ACCOUNTS) return
            if (action == LocalAdministrationAction.REVOKE_SESSION) {
                if (detail.retainedSessions.none { it.sessionId == sessionId && it.status == SessionStatus.ACTIVE }) return
            } else if (action == LocalAdministrationAction.ACTIVATE_ACCOUNT) {
                if (detail.account.enabled || detail.account.credentialPresent || detail.account.actorId == operatorId ||
                    sessionId != null || detail.retainedSessions.any { it.status == SessionStatus.ACTIVE }) return
            } else if (action == LocalAdministrationAction.REACTIVATE_ACCOUNT) {
                if (detail.account.enabled || !detail.account.credentialPresent || detail.account.actorId == operatorId ||
                    sessionId != null || detail.retainedSessions.any { it.status == SessionStatus.ACTIVE }) return
            } else if (!detail.account.enabled || sessionId != null) return
            LocalAdministrationCommand(DomainId.random(), action, detail.account.actorId, detail.account.accountRevision,
                sessionId, detail.account.credentialRevision.takeIf { action.requiresNewPassword },
                if (action == LocalAdministrationAction.REACTIVATE_ACCOUNT) LocalAdministrationReactivationRevisions(
                    detail.account.credentialRevision, detail.account.policyRevision, detail.account.configurationRevision) else null,
                if (action == LocalAdministrationAction.ACTIVATE_ACCOUNT) LocalAdministrationActivationRevisions(
                    detail.account.policyRevision, detail.account.configurationRevision) else null)
        }
        mutableState.value = state.value.copy(original = command, pending = null, operationProblem = null,
            preparationUncertain = true, acknowledgementAttempted = false)
        prepareOriginal(command)
    }

    /** Uncertain staging is retried only with the exact metadata and ID already selected. */
    fun retryPreparation(token: NavigationToken) {
        if (!accepts(token) || state.value.working || !state.value.preparationUncertain) return
        prepareOriginal(state.value.original ?: return)
    }

    private fun prepareOriginal(command: LocalAdministrationCommand) {
        launchWork(LocalAdministrationBusy.PREPARING) { ticket ->
            val result = gateway.prepare(command)
            if (current(ticket)) adopt(result, command)
        }
    }

    fun openPasswordEntry(token: NavigationToken) {
        if (!accepts(token) || !state.value.canSubmit || state.value.original?.action?.requiresNewPassword != true) return
        mutableState.value = state.value.copy(passwordEntryOpen = true)
        syncGuard()
    }

    fun closePasswordEntry(token: NavigationToken) {
        if (!accepts(token) || state.value.working) return
        mutableState.value = state.value.copy(passwordEntryOpen = false)
        syncGuard()
    }

    /** Consumes input on every path; completion cleanup also covers a never-started coroutine. */
    fun submit(token: NavigationToken, password: CharArray? = null) {
        var owned: CharArray? = null
        try {
            if (!accepts(token) || !state.value.canSubmit) return
            val original = state.value.original ?: return
            val secretRequired = original.action.requiresNewPassword
            if (secretRequired != (password != null) || (secretRequired && (!state.value.passwordEntryOpen || password!!.size !in 4..1024))) return
            owned = password?.copyOf()
            mutableState.value = state.value.copy(passwordEntryOpen = false, operationProblem = null)
            val input = owned
            launchWork(LocalAdministrationBusy.SUBMITTING, secret = input) { ticket ->
                val result = gateway.submit(original.requestId, input)
                if (current(ticket)) adopt(result, original)
            }
            owned = null // The job/completion hook now owns this transient copy.
        } finally { password?.fill('\u0000'); owned?.fill('\u0000') }
    }

    fun recover(token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.passwordEntryOpen || state.value.creationOwnsInput) return
        val original = state.value.original
        launchWork(LocalAdministrationBusy.RECOVERING) { ticket ->
            if (original == null || state.value.acknowledgementAttempted) {
                val result = gateway.pending()
                if (current(ticket)) adoptPending(result)
            } else {
                val result = gateway.lookup(original.requestId)
                if (current(ticket)) adopt(result, original)
            }
        }
    }

    fun cancel(token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.passwordEntryOpen || state.value.creationOwnsInput || state.value.originalDetailsRestricted || state.value.pending?.terminal == true) return
        val original = state.value.original ?: return
        launchWork(LocalAdministrationBusy.CANCELLING) { ticket ->
            val result = gateway.cancel(original.requestId)
            if (current(ticket)) adopt(result, original)
        }
    }

    fun acknowledge(token: NavigationToken) {
        if (!accepts(token) || state.value.working || state.value.creationOwnsInput || state.value.pending?.terminal != true || state.value.operationProblem != null) return
        val original = state.value.original ?: return
        mutableState.value = state.value.copy(acknowledgementAttempted = true)
        launchWork(LocalAdministrationBusy.ACKNOWLEDGING) { ticket ->
            val acknowledged = gateway.acknowledge(original.requestId)
            if (current(ticket)) {
                if (acknowledged) mutableState.value = state.value.copy(original = null, pending = null,
                    pendingLoaded = true, pendingProblem = null, operationProblem = null, acknowledgementAttempted = false,
                    originalDetailsRestricted = false)
                else mutableState.value = state.value.copy(operationProblem = LocalServiceProblem.STORAGE_UNCERTAIN)
                // Old target revisions are not reused after an applied operation or an uncertain ACK.
                mutableState.value = state.value.copy(view = null)
            }
        }
    }

    private fun adoptPending(result: LocalAdministrationPendingResult) {
        mutableState.value = when (result) {
            is LocalAdministrationPendingResult.Available -> {
                val previous = state.value.original
                // Absence from a refresh never erases a known uncertain, nonterminal intent.
                if (result.operation == null && previous != null && !state.value.acknowledgementAttempted)
                    state.value.copy(pendingLoaded = true, pendingProblem = null, operationProblem = LocalServiceProblem.STORAGE_UNCERTAIN)
                else state.value.copy(pendingLoaded = true, pendingProblem = null, original = result.operation?.command,
                    pending = result.operation, operationProblem = null, preparationUncertain = false, acknowledgementAttempted = false,
                    originalDetailsRestricted = false)
            }
            is LocalAdministrationPendingResult.Unavailable -> state.value.copy(pendingLoaded = false, pendingProblem = result.problem)
        }
        if (result is LocalAdministrationPendingResult.Unavailable) handleSessionProblem(result.problem)
    }

    private fun adopt(result: LocalAdministrationResult, expected: LocalAdministrationCommand) {
        mutableState.value = when (result) {
            is LocalAdministrationResult.Known -> {
                check(result.operation.command == expected)
                state.value.copy(original = expected, pending = result.operation, pendingLoaded = true, pendingProblem = null,
                    operationProblem = null, preparationUncertain = false, originalDetailsRestricted = false,
                    view = if (result.operation.terminal) null else state.value.view)
            }
            is LocalAdministrationResult.NotPrepared -> {
                check(state.value.busy == LocalAdministrationBusy.PREPARING)
                state.value.copy(original = null, pending = null, preparationUncertain = false, operationProblem = result.problem,
                    originalDetailsRestricted = false, view = null)
            }
            is LocalAdministrationResult.Unresolved -> {
                check(result.requestId == expected.requestId)
                state.value.copy(operationProblem = result.problem, view = null)
            }
        }
        when (result) {
            is LocalAdministrationResult.Unresolved -> handleSessionProblem(result.problem)
            is LocalAdministrationResult.NotPrepared -> handleSessionProblem(result.problem)
            else -> Unit
        }
    }

    private fun adoptRead(result: LocalAdministrationReadResult) {
        mutableState.value = when (result) {
            is LocalAdministrationReadResult.Available -> state.value.copy(view = result.view, readProblem = null, restartRequired = false)
            LocalAdministrationReadResult.StaleSnapshot -> state.value.copy(view = null, readProblem = null, restartRequired = true)
            else -> state.value.copy(view = null, restartRequired = false, readProblem = when (result) {
                LocalAdministrationReadResult.Denied -> LocalServiceProblem.DENIED
                LocalAdministrationReadResult.StorageUncertain -> LocalServiceProblem.STORAGE_UNCERTAIN
                LocalAdministrationReadResult.Busy -> LocalServiceProblem.RETRY_LATER
                else -> LocalServiceProblem.UNAVAILABLE
            })
        }
        if (result == LocalAdministrationReadResult.Denied) handleSessionProblem(LocalServiceProblem.DENIED)
    }

    private fun handleSessionProblem(problem: LocalServiceProblem) {
        if (problem == LocalServiceProblem.DENIED) mutableState.value = state.value.copy(
            query = LocalAdministrationQuery(state.value.query.directory), view = null,
            targetInput = "", invalidTarget = false, readProblem = problem, restartRequired = false,
            // A denied observation cannot erase or acknowledge an already selected original.
            // Its exact metadata stays negative recovery custody; only an authorized original
            // lookup may expose it again. Directory recovery alone does not restore that grant.
            pending = null, pendingLoaded = false, pendingProblem = problem, operationProblem = problem,
            originalDetailsRestricted = true, preparationUncertain = false, passwordEntryOpen = false,
            creation = state.value.creation.copy(pending = null, loaded = false, restricted = true,
                problem = problem, observation = null, target = null, context = null, passwordEntryOpen = false))
        if (problem == LocalServiceProblem.SESSION_ENDED) endSession(problem)
    }

    private fun endSession(problem: LocalServiceProblem) {
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { deactivate() }
        // Failed child cancellation/guard removal cannot leave the old runtime root attached.
        cleanup.attempt { runtime.localSessionEnded(session, problem) }
        cleanup.throwIfFailed()
    }

    private fun launchWork(kind: LocalAdministrationBusy, secret: CharArray? = null,
        failed: (() -> Unit)? = null, block: suspend (Ticket) -> Unit) {
        if (!current() || state.value.working) { secret?.fill('\u0000'); return }
        mutableState.value = state.value.copy(busy = kind)
        syncGuard()
        val ticket = Ticket(activation, runtime.navigator.token())
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try { if (current(ticket)) block(ticket); currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) {
                if (current(ticket)) { if (failed != null) failed() else unavailable(kind) }
                throw cancelled
            }
            catch (_: Exception) { if (current(ticket)) { if (failed != null) failed() else unavailable(kind) } }
            finally {
                if (current(ticket.activation)) {
                    if (!runtime.navigator.isCurrent(ticket.navigation)) mutableState.value = LocalAdministrationWorkspace(
                        query = LocalAdministrationQuery(state.value.query.directory), readProblem = LocalServiceProblem.UNAVAILABLE)
                    mutableState.value = state.value.copy(busy = LocalAdministrationBusy.NONE)
                    work = null
                    syncGuard()
                }
            }
        }
        job.invokeOnCompletion { secret?.fill('\u0000') }
        work = job
        job.start()
    }

    private fun unavailable(kind: LocalAdministrationBusy) {
        mutableState.value = when (kind) {
            LocalAdministrationBusy.READING -> state.value.copy(view = null, readProblem = LocalServiceProblem.UNAVAILABLE)
            LocalAdministrationBusy.RECOVERING -> state.value.copy(view = null, pendingLoaded = false, pendingProblem = LocalServiceProblem.UNAVAILABLE)
            else -> state.value.copy(view = null, passwordEntryOpen = false, operationProblem = LocalServiceProblem.UNAVAILABLE)
        }
    }

    private fun syncGuard() {
        if (!current()) return
        val desired = when {
            state.value.busy !in setOf(LocalAdministrationBusy.NONE, LocalAdministrationBusy.READING) -> LeaveGuard.CRITICAL
            state.value.passwordEntryOpen || state.value.creation.passwordEntryOpen || state.value.creation.target != null -> LeaveGuard.DIRTY
            else -> LeaveGuard.CLEAN // Durable pending metadata survives navigation; no secret does.
        }
        if (desired == guardKind) return
        if (desired == LeaveGuard.CLEAN) removeGuard()
        else { guard = runtime.navigator.registerGuard(owner.entryId, desired); guardKind = if (guard == null) LeaveGuard.CLEAN else desired }
    }

    private fun removeGuard() { guard?.let(runtime.navigator::removeGuard); guard = null; guardKind = LeaveGuard.CLEAN }
}
