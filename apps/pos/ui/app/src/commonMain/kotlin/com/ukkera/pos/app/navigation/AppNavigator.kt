package com.ukkera.pos.app.navigation

import androidx.navigation3.runtime.NavKey
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.LocalRetailPhysicalDenial
import com.ukkera.pos.app.LocalRetailPhysicalLifetime
import com.ukkera.pos.app.LocalSessionCleanup
import com.ukkera.pos.app.LocalSessionWorkspaceMode
import com.ukkera.pos.app.invoiceCaptureUiRoute
import com.ukkera.pos.app.invoiceMatchingUiRoute
import com.ukkera.pos.app.paymentCorrectionUiRoute
import com.ukkera.pos.app.customerReceiptUiRoute
import com.ukkera.pos.app.foodCheckUiRoute
import com.ukkera.pos.app.foodCashFinancialUiRoute
import com.ukkera.pos.app.foodTableAssignmentUiRoute
import com.ukkera.pos.security.InteractiveSessionFence
import com.ukkera.pos.security.SessionFenceTicket
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class NavigationEntry(val id: Long, val route: AppRoute) : NavKey

data class NavigationState(
    val root: SecurityRoot,
    val rootGeneration: Long,
    val revision: Long,
    val selectedArea: AppArea,
    val stacks: Map<AppArea, List<NavigationEntry>>,
    val attached: Boolean = true,
    val guard: LeaveGuard = LeaveGuard.CLEAN,
    val rootHasDraft: Boolean = false,
    val confirmation: LeaveConfirmation? = null,
) {
    val activeStack: List<NavigationEntry> get() = stacks.getValue(selectedArea)
    val activeEntry: NavigationEntry get() = activeStack.last()
    val isWorkspace: Boolean get() = root == SecurityRoot.BRANCH_WORKSPACE || root == SecurityRoot.COMPANY_WORKSPACE
}

/** Identity-captured callbacks, including root, active stack, entry and navigation revision. */
data class NavigationToken internal constructor(
    internal val fence: SessionFenceTicket,
    val rootGeneration: Long,
    val area: AppArea,
    val entryId: Long,
    val revision: Long,
)

enum class LeaveGuard { CLEAN, DIRTY, CRITICAL, HANDLED_LOCALLY }

sealed interface NavigationAction {
    data class Push(val route: AppRoute) : NavigationAction
    data class SelectArea(val area: AppArea) : NavigationAction
    data object Back : NavigationAction
    data object ExitWorkspace : NavigationAction
    data object CloseHost : NavigationAction
}

data class LeaveConfirmation internal constructor(val id: Long, internal val token: NavigationToken, internal val action: NavigationAction)
data class GuardHandle internal constructor(val entryId: Long, internal val registration: Long, internal val rootGeneration: Long)

enum class NavigationResult { CHANGED, IGNORED, BLOCKED, HANDLED_LOCALLY, CONFIRM_DISCARD, LEAVE_WORKSPACE, LEAVE_AUTHENTICATION, EXIT_PLATFORM }

/** Pure policy used by toolbar, system/predictive Back, iOS gesture, Escape and programmatic Back. */
object BackCoordinator {
    fun disposition(state: NavigationState): NavigationResult = when {
        !state.attached -> NavigationResult.IGNORED
        state.confirmation != null -> NavigationResult.HANDLED_LOCALLY
        state.guard == LeaveGuard.CRITICAL -> NavigationResult.BLOCKED
        state.guard == LeaveGuard.HANDLED_LOCALLY -> NavigationResult.HANDLED_LOCALLY
        state.guard == LeaveGuard.DIRTY -> NavigationResult.CONFIRM_DISCARD
        state.activeStack.size > 1 -> NavigationResult.CHANGED
        state.rootHasDraft -> NavigationResult.CONFIRM_DISCARD
        state.isWorkspace -> NavigationResult.LEAVE_WORKSPACE
        state.root != SecurityRoot.BOOTSTRAP -> NavigationResult.LEAVE_AUTHENTICATION
        else -> NavigationResult.EXIT_PLATFORM
    }

    fun allowsPredictivePreview(state: NavigationState): Boolean =
        state.attached && state.confirmation == null && state.guard == LeaveGuard.CLEAN && state.activeStack.size > 1
}

/**
 * Sole route/history mutation owner. All mutations run on the UI event loop, never from worker
 * threads. No unbounded command queue, platform navigation controller or hidden saved backstack.
 * This is a UI fence, not backend authorization. Every gateway action still checks live policy.
 */
class AppNavigator(
    /** Negative UI cleanup only; may retire guards, never navigate or grant access. */
    private val beforeEntryDeparture: (Long) -> Unit = {},
) {
    private val fence = InteractiveSessionFence()
    private var nextIdentity = 0L
    private val guards = mutableMapOf<Long, Pair<GuardHandle, LeaveGuard>>()
    private val retailOriginals = mutableMapOf<Long, LocalRetailPhysicalLifetime>()
    private var restoreContext: RestoreContext? = null
    /** Current presentation subset only; never encoded into a checkpoint or used as authority. */
    private var workspaceMode: LocalSessionWorkspaceMode? = null
    private var quarantined: RouteCheckpoint? = null
    private val mutableState = MutableStateFlow(initialState(SecurityRoot.BOOTSTRAP, null))
    val state: StateFlow<NavigationState> = mutableState.asStateFlow()

    private fun identity(): Long {
        check(nextIdentity != Long.MAX_VALUE) { "Navigation identity exhausted" }
        return ++nextIdentity
    }

    private fun initialState(root: SecurityRoot, display: SessionDisplayContext?): NavigationState {
        val areas = presentationAreas(root, display)
        require(areas.isNotEmpty()) { "No permitted workspace areas" }
        return NavigationState(root, fence.generation.value, identity(), areas.first(),
            areas.associateWith { listOf(NavigationEntry(identity(), RouteRegistry.rootRoute(it, root))) })
    }

    private fun presentationAreas(root: SecurityRoot, display: SessionDisplayContext?): List<AppArea> {
        val areas = RouteRegistry.areas(root).filter { display == null || it in display.allowedAreas }
        // This fallback is not part of allowedAreas or the operational registry. It supplies
        // no business controller and cannot be restored as an authenticated route checkpoint.
        return if (root == SecurityRoot.BRANCH_WORKSPACE && display != null && areas.isEmpty())
            listOf(AppArea.SESSION) else areas
    }

    /** Called only after a verified gateway session, or an explicit separately labeled training entry. */
    fun replaceRoot(root: SecurityRoot, display: SessionDisplayContext? = null) = replaceRoot(root, display) {}

    /** Cleanup runs after old callback fencing and before any replacement can be published. */
    internal fun replaceRoot(root: SecurityRoot, display: SessionDisplayContext?, beforeReplacement: () -> Unit) {
        require((root == SecurityRoot.BRANCH_WORKSPACE || root == SecurityRoot.COMPANY_WORKSPACE) == (display != null))
        require(root != SecurityRoot.BRANCH_WORKSPACE || display?.branchId != null)
        require(root != SecurityRoot.COMPANY_WORKSPACE || display?.branchId == null)
        // Validate before tearing down. No previous-user entries survive this assignment.
        require(presentationAreas(root, display).isNotEmpty())
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { denyRetailPhysical() }
        fence.invalidate()
        cleanup.attempt { beforeEntryDeparture(state.value.activeEntry.id) }
        guards.clear()
        cleanup.attempt { beforeReplacement() }
        // A failed negative drain still hides private history, but never admits a replacement.
        val nextRoot = if (cleanup.failed) SecurityRoot.BOOTSTRAP else root
        val nextDisplay = display.takeUnless { cleanup.failed }
        if (cleanup.failed) quarantined = null
        restoreContext = nextDisplay?.let { RestoreContext(it.actorId, it.companyId, it.branchId) }
        workspaceMode = nextDisplay?.workspaceMode
        mutableState.value = initialState(nextRoot, nextDisplay)
        cleanup.throwIfFailed()
    }

    /**
     * Current same-principal presentation only. Preserve proven-safe entry identities, remove
     * withdrawn areas/history without a dirty-leave prompt, and never restore a removed route
     * merely because a later policy regrants its area. This is cleanup, not authorization.
     */
    internal fun reconcileLocalAreas(display: SessionDisplayContext, retainedEntries: Set<Long>) {
        val before = state.value
        require(before.root == SecurityRoot.BRANCH_WORKSPACE &&
            restoreContext == RestoreContext(display.actorId, display.companyId, display.branchId))
        val areas = presentationAreas(before.root, display)
        require(areas.isNotEmpty())
        val profileChanged = workspaceMode != display.workspaceMode
        val stacks = areas.associateWith { area ->
            before.stacks[area]?.takeWhile { it.id in retainedEntries &&
                (display.workspaceMode != LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY || paymentCorrectionUiRoute(it.route)) &&
                (display.workspaceMode != LocalSessionWorkspaceMode.CUSTOMER_RECEIPTS_ONLY || customerReceiptUiRoute(it.route)) &&
                (display.workspaceMode != LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY || foodCheckUiRoute(it.route)) &&
                (display.workspaceMode != LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY || foodCashFinancialUiRoute(it.route)) &&
                (display.workspaceMode != LocalSessionWorkspaceMode.FOOD_TABLE_ASSIGNMENTS_ONLY || foodTableAssignmentUiRoute(it.route)) &&
                (display.workspaceMode != LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY || invoiceCaptureUiRoute(it.route)) &&
                (display.workspaceMode != LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY || invoiceMatchingUiRoute(it.route)) }
                ?.takeIf { it.isNotEmpty() }
                ?: listOf(NavigationEntry(identity(), RouteRegistry.rootRoute(area, before.root)))
        }
        if (stacks == before.stacks && !profileChanged) return
        val kept = stacks.values.flatten().map { it.id }.toSet()
        val removed = before.stacks.values.flatten().filter { it.id !in kept }
        // A previously quarantined route list must not return after a later regrant, even if
        // nobody attempted restoration while the old entry/area was withdrawn.
        if (removed.isNotEmpty()) quarantined = null
        val cleanup = LocalSessionCleanup()
        cleanup.attempt { denyRetailPhysical() }
        // Include inactive entries: a later tab/back operation must not revive their holders.
        removed.forEach { entry ->
            cleanup.attempt { beforeEntryDeparture(entry.id) }
            guards.remove(entry.id)
        }
        cleanup.throwIfFailed()
        workspaceMode = display.workspaceMode
        mutableState.value = state.value.copy(stacks = stacks,
            selectedArea = before.selectedArea.takeIf { it in areas } ?: areas.first(),
            revision = identity(), confirmation = null,
            rootHasDraft = before.rootHasDraft && removed.isEmpty())
        refreshGuard()
    }

    /** Host removal invalidates in-flight callbacks and immediately removes protected history. */
    fun detachHost() {
        try { replaceRoot(SecurityRoot.BOOTSTRAP) }
        finally { mutableState.value = state.value.copy(attached = false) }
    }

    fun attachHost() {
        if (!state.value.attached) mutableState.value = state.value.copy(attached = true, revision = identity())
    }

    fun token(): NavigationToken = state.value.let {
        NavigationToken(fence.capture(), it.rootGeneration, it.selectedArea, it.activeEntry.id, it.revision)
    }

    fun isCurrent(token: NavigationToken): Boolean = state.value.let {
        it.attached && fence.isCurrent(token.fence) && token.rootGeneration == it.rootGeneration &&
            token.area == it.selectedArea && token.entryId == it.activeEntry.id && token.revision == it.revision
    }

    /**
     * Entry-owned async rendering only. A controller separately fences its activation/request;
     * guard registration may change revision without changing its owner. User/navigation
     * callbacks must still use isCurrent, never this weaker presentation predicate.
     */
    internal fun isActiveEntry(token: NavigationToken): Boolean = state.value.let {
        it.attached && fence.isCurrent(token.fence) && token.rootGeneration == it.rootGeneration &&
            token.area == it.selectedArea && token.entryId == it.activeEntry.id
    }

    /** Negative-only, exact active entry. A guard revision change is not a new entry/lifetime. */
    internal fun attachRetailPhysical(token: NavigationToken, target: LocalRetailPhysicalDenial): LocalRetailPhysicalLifetime.Attachment? {
        if (!isActiveEntry(token)) { target.deny(); return null }
        return retailOriginals.getOrPut(token.entryId) { LocalRetailPhysicalLifetime() }.attach(target)
    }

    /** Attempts every REAL denial before mutation; failed exact holders remain retained. */
    internal fun denyRetailPhysical() {
        val cleanup = LocalSessionCleanup()
        retailOriginals.toMap().forEach { (entryId, lifetime) ->
            cleanup.attempt {
                lifetime.deny()
                if (retailOriginals[entryId] === lifetime) retailOriginals.remove(entryId)
            }
        }
        cleanup.throwIfFailed()
    }

    private fun denyOutgoingRetail(current: NavigationState) {
        retailOriginals[current.activeEntry.id]?.deny()
        retailOriginals.remove(current.activeEntry.id)
    }

    fun registerGuard(entryId: Long, kind: LeaveGuard): GuardHandle? {
        val current = state.value
        if (current.stacks.values.none { entries -> entries.any { it.id == entryId } }) return null
        val handle = GuardHandle(entryId, identity(), current.rootGeneration)
        guards[entryId] = handle to kind
        // Guard ownership changes are navigation-policy changes even when the visible enum is
        // unchanged. They must fence a delayed restoration and any previous discard consent.
        mutableState.value = current.copy(revision = identity(), confirmation = null)
        refreshGuard()
        return handle
    }

    fun removeGuard(handle: GuardHandle) {
        if (guards[handle.entryId]?.first != handle) return
        guards.remove(handle.entryId)
        mutableState.value = state.value.copy(revision = identity(), confirmation = null)
        refreshGuard()
    }

    fun setRootDraft(hasDraft: Boolean) {
        if (state.value.rootHasDraft == hasDraft) return
        mutableState.value = state.value.copy(rootHasDraft = hasDraft, revision = identity(), confirmation = null)
    }

    private fun refreshGuard() {
        val next = guards[state.value.activeEntry.id]?.second ?: LeaveGuard.CLEAN
        if (state.value.guard != next) mutableState.value = state.value.copy(guard = next, confirmation = null)
    }

    fun navigate(action: NavigationAction, token: NavigationToken): NavigationResult {
        if (!isCurrent(token)) return NavigationResult.IGNORED
        val current = state.value
        if (current.confirmation != null) return NavigationResult.IGNORED
        // Invalid/no-op forward actions must not discard a draft merely by asking for confirmation.
        when (action) {
            is NavigationAction.Push -> if (!presentationAllows(action.route) || !RouteRegistry.allows(current.root, action.route) ||
                RouteRegistry.area(action.route) != current.selectedArea || current.activeEntry.route == action.route ||
                current.activeStack.size >= MAX_STACK_DEPTH) return NavigationResult.IGNORED
            is NavigationAction.SelectArea -> if (action.area !in current.stacks ||
                action.area == current.selectedArea && current.activeStack.size == 1) return NavigationResult.IGNORED
            else -> Unit
        }
        when (current.guard) {
            LeaveGuard.CRITICAL -> return NavigationResult.BLOCKED
            LeaveGuard.HANDLED_LOCALLY -> return NavigationResult.HANDLED_LOCALLY
            LeaveGuard.DIRTY -> return confirm(action, token)
            LeaveGuard.CLEAN -> Unit
        }
        if (leavesRoot(action, current)) {
            if (current.rootHasDraft) return confirm(action, token)
        }
        return apply(action)
    }

    private fun confirm(action: NavigationAction, token: NavigationToken): NavigationResult {
        mutableState.value = state.value.copy(confirmation = LeaveConfirmation(identity(), token, action))
        return NavigationResult.CONFIRM_DISCARD
    }

    fun cancelLeave(confirmationId: Long) {
        if (state.value.confirmation?.id == confirmationId) mutableState.value = state.value.copy(confirmation = null)
    }

    fun confirmLeave(confirmationId: Long): NavigationResult {
        val pending = state.value.confirmation ?: return NavigationResult.IGNORED
        if (pending.id != confirmationId || !isCurrent(pending.token)) return NavigationResult.IGNORED
        val current = state.value
        if (leavesRoot(pending.action, current)) denyRetailPhysical() else denyOutgoingRetail(current)
        guards.remove(current.activeEntry.id)
        // Discarding entry-local edits cannot acknowledge a retained workspace basket. Only a
        // confirmed context exit transfers responsibility for clearing the root-owned draft.
        mutableState.value = current.copy(confirmation = null, guard = LeaveGuard.CLEAN,
            rootHasDraft = current.rootHasDraft && !leavesRoot(pending.action, current))
        return apply(pending.action)
    }

    private fun leavesRoot(action: NavigationAction, state: NavigationState): Boolean =
        action == NavigationAction.ExitWorkspace || action == NavigationAction.CloseHost ||
            action == NavigationAction.Back && state.activeStack.size == 1

    private fun presentationAllows(route: AppRoute): Boolean =
        (workspaceMode != LocalSessionWorkspaceMode.PAYMENT_CORRECTIONS_ONLY || paymentCorrectionUiRoute(route)) &&
            (workspaceMode != LocalSessionWorkspaceMode.CUSTOMER_RECEIPTS_ONLY || customerReceiptUiRoute(route)) &&
            (workspaceMode != LocalSessionWorkspaceMode.FOOD_CHECKS_ONLY || foodCheckUiRoute(route)) &&
            (workspaceMode != LocalSessionWorkspaceMode.FOOD_CASH_FINANCIAL_ONLY || foodCashFinancialUiRoute(route)) &&
            (workspaceMode != LocalSessionWorkspaceMode.FOOD_TABLE_ASSIGNMENTS_ONLY || foodTableAssignmentUiRoute(route)) &&
            (workspaceMode != LocalSessionWorkspaceMode.INVOICE_CAPTURE_ONLY || invoiceCaptureUiRoute(route)) &&
            (workspaceMode != LocalSessionWorkspaceMode.INVOICE_MATCHING_ONLY || invoiceMatchingUiRoute(route))

    private fun apply(action: NavigationAction): NavigationResult {
        val current = state.value
        when (action) {
            is NavigationAction.Push -> {
                if (!presentationAllows(action.route) || !RouteRegistry.allows(current.root, action.route) ||
                    RouteRegistry.area(action.route) != current.selectedArea) return NavigationResult.IGNORED
                // Single-top duplicate semantics; bounded history on every platform.
                if (current.activeEntry.route == action.route || current.activeStack.size >= MAX_STACK_DEPTH) return NavigationResult.IGNORED
                updateStack(current.activeStack + NavigationEntry(identity(), action.route))
            }
            is NavigationAction.SelectArea -> {
                if (action.area !in current.stacks) return NavigationResult.IGNORED
                if (action.area == current.selectedArea) {
                    if (current.activeStack.size == 1) return NavigationResult.IGNORED
                    updateStack(current.activeStack.take(1))
                } else {
                    denyOutgoingRetail(current)
                    beforeEntryDeparture(current.activeEntry.id)
                    mutableState.value = state.value.copy(selectedArea = action.area, revision = identity())
                    refreshGuard()
                }
            }
            NavigationAction.Back -> {
                if (current.activeStack.size > 1) updateStack(current.activeStack.dropLast(1))
                else return exitResult(current)
            }
            NavigationAction.ExitWorkspace -> return exitResult(current)
            NavigationAction.CloseHost -> {
                denyRetailPhysical()
                beforeEntryDeparture(current.activeEntry.id)
                mutableState.value = state.value.copy(revision = identity())
                return NavigationResult.EXIT_PLATFORM
            }
        }
        return NavigationResult.CHANGED
    }

    private fun exitResult(current: NavigationState): NavigationResult {
        denyRetailPhysical()
        beforeEntryDeparture(current.activeEntry.id)
        mutableState.value = state.value.copy(revision = identity())
        return when {
            current.isWorkspace -> NavigationResult.LEAVE_WORKSPACE
            current.root != SecurityRoot.BOOTSTRAP -> NavigationResult.LEAVE_AUTHENTICATION
            else -> NavigationResult.EXIT_PLATFORM
        }
    }

    private fun updateStack(entries: List<NavigationEntry>) {
        val current = state.value
        if (entries.last().id != current.activeEntry.id) {
            denyOutgoingRetail(current)
            beforeEntryDeparture(current.activeEntry.id)
        }
        val removed = current.activeStack.map { it.id }.toSet() - entries.map { it.id }.toSet()
        removed.forEach { guards.remove(it) }
        mutableState.value = state.value.copy(stacks = current.stacks + (current.selectedArea to entries), revision = identity())
        refreshGuard()
    }

    /** Optional route-only checkpoint. Platform hosts currently start at bootstrap, not auto-login. */
    fun checkpoint(): String? {
        val context = restoreContext ?: return null
        val current = state.value
        if (current.selectedArea == AppArea.SESSION) return null
        val stacks = current.stacks.map { (area, entries) -> RestoredStack(area, entries.map { it.route }.takeWhile(RouteRegistry::restorable)) }
        return checkpointJson.encodeToString(RouteCheckpoint(1, current.root, context, current.selectedArea, stacks))
    }

    fun quarantineRestore(encoded: String): Boolean {
        quarantined = null
        if (state.value.isWorkspace || encoded.length > MAX_CHECKPOINT_CHARS) return false
        val restored = try { checkpointJson.decodeFromString<RouteCheckpoint>(encoded) } catch (_: IllegalArgumentException) { return false }
        if (!structurallyValid(restored)) return false
        quarantined = restored
        return true // still only bootstrap/authentication is rendered
    }

    suspend fun restoreAfterAuthentication(authorize: suspend (AppRoute) -> Boolean): Boolean {
        val candidate = quarantined ?: return false
        quarantined = null
        val captured = token()
        val current = state.value
        // Restoration is not a leave-guard bypass. A user may already have begun working while
        // authentication settled; never replace that guarded/draft state with historical routes.
        if (current.rootHasDraft || current.confirmation != null || guards.values.any { it.second != LeaveGuard.CLEAN }) return false
        if (candidate.context != restoreContext || candidate.root != current.root || candidate.stacks.any { it.area !in current.stacks }) return false
        try {
            for (stack in candidate.stacks) for (route in stack.routes) {
                currentCoroutineContext().ensureActive()
                if (!isCurrent(captured) || !presentationAllows(route) || !authorize(route)) return false
            }
            currentCoroutineContext().ensureActive()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        if (!isCurrent(captured)) return false
        denyRetailPhysical()
        beforeEntryDeparture(current.activeEntry.id)
        guards.clear()
        mutableState.value = state.value.copy(
            revision = identity(), selectedArea = candidate.selectedArea,
            stacks = current.stacks + candidate.stacks.associate { it.area to it.routes.map { route -> NavigationEntry(identity(), route) } },
            guard = LeaveGuard.CLEAN, rootHasDraft = false, confirmation = null,
        )
        return true
    }

    private fun structurallyValid(checkpoint: RouteCheckpoint): Boolean {
        if (checkpoint.version != 1 || checkpoint.root !in setOf(SecurityRoot.BRANCH_WORKSPACE, SecurityRoot.COMPANY_WORKSPACE)) return false
        if ((checkpoint.root == SecurityRoot.BRANCH_WORKSPACE) != (checkpoint.context.branchId != null)) return false
        if (checkpoint.stacks.size !in 1..MAX_AREAS || checkpoint.stacks.map { it.area }.toSet().size != checkpoint.stacks.size) return false
        if (checkpoint.selectedArea !in checkpoint.stacks.map { it.area }) return false
        return checkpoint.stacks.all { stack ->
            stack.area in RouteRegistry.areas(checkpoint.root) && stack.routes.size in 1..MAX_STACK_DEPTH &&
                stack.routes.first() == RouteRegistry.rootRoute(stack.area, checkpoint.root) &&
                stack.routes.all { RouteRegistry.allows(checkpoint.root, it) && RouteRegistry.area(it) == stack.area && RouteRegistry.restorable(it) } &&
                stack.routes.zipWithNext().none { (a, b) -> a == b }
        }
    }

    companion object {
        const val MAX_STACK_DEPTH = 12
        const val MAX_AREAS = 10
        const val MAX_CHECKPOINT_CHARS = 16_384
        private val checkpointJson = Json { ignoreUnknownKeys = false; classDiscriminator = "route" }
    }
}

@Serializable
internal data class RestoreContext(val actorId: DomainId, val companyId: DomainId, val branchId: DomainId?)
@Serializable
internal data class RestoredStack(val area: AppArea, val routes: List<AppRoute>)
@Serializable
internal data class RouteCheckpoint(val version: Int, val root: SecurityRoot, val context: RestoreContext, val selectedArea: AppArea, val stacks: List<RestoredStack>)
