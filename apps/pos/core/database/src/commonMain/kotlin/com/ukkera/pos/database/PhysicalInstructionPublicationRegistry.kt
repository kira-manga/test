package com.ukkera.pos.database

import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAuthEvent
import com.ukkera.pos.security.LocalAuthEventKind
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.ProtectedLocalActionApproval
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.UtcInstant
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * ONE same-security owner. This private StateFlow has NO collectors, callbacks or exported
 * observable. It is used only as a cross-platform atom with referential Snapshot equality.
 * Every denial, continuity observation, publication, adoption and ORIGINAL event competes on
 * that same atom. A failed bounded CAS loop exchanges that atom for a permanently closed state;
 * it never falls back to an unrelated Boolean or an unbounded spin. Nothing here calls SQL,
 * native code, a listener or a request-supplied predicate. The one captured kernel MasterClock
 * is sampled BEFORE the final same-security monotonic callback, never supplied by an employee.
 *
 * The sole bounded synchronous environment is the one used by current protected authorization.
 * Its callback precedes the atomic phase. Normally returned observations are accounted for even
 * if the provider cancelled its caller. Concurrent out-of-order observations conservatively
 * lose continuity; a rollback/cancellation cannot restore an earlier high-water.
 *
 * At most 128 ACTIVE request identities are retained. Terminal denial and successful original
 * capture REMOVE the exact entry in the winning CAS. Absence permanently denies the old object;
 * no ID lookup, re-registration or rearm exists. The native port preowns immutable candidate
 * bytes and separate evidence leases/worker before capture, so an accepted token is independent
 * of this entry. This is not native-journal sustained capacity. The 1000ms writer-to-publication/
 * adoption bound is NOT a human-action or evidence-retention TTL. Finite business expiry only
 * shortens the original access window; it never erases accepted evidence or releases stock.
 */
internal class PhysicalInstructionPublicationRegistry(
    private val environment: RoomLocalAuthorizationEnvironment,
) {
    private enum class Phase { REQUESTED, STARTING, OFFERED, PREPARING, AUTHORIZED, PUBLISHED, ADOPTED }

    private data class Entry(
        val lifetime: PhysicalInteractiveRequestLifetime,
        val phase: Phase = Phase.REQUESTED,
        val identity: PhysicalInteractiveIdentity? = null,
        val operationId: OperationId? = null,
        val command: CommandKey? = null,
        val offer: PhysicalInstructionOffer? = null,
        val binding: PhysicalInstructionNativeBinding? = null,
        val dependencies: Set<PhysicalReadDependency> = emptySet(),
        val window: PhysicalInteractiveAccessWindow? = null,
        val authorizedAt: RoomLocalAuthorizationObservation? = null,
        val publication: PhysicalPublishedInstruction? = null,
    )

    private class KernelClockOwner(val store: AtomicMasterStore, val clock: MasterClock)

    // NOT a data class: compareAndSet must compare the actual snapshot, never equal contents.
    private class Snapshot(
        val last: RoomLocalAuthorizationObservation? = null,
        val lastWall: UtcInstant? = null,
        val kernelClock: KernelClockOwner? = null,
        val continuityUnavailable: Boolean = false,
        val serviceDenied: Boolean = false,
        val entries: List<Entry> = emptyList(),
    )

    private val atom = MutableStateFlow(Snapshot())
    private val terminal = Snapshot(continuityUnavailable = true, serviceDenied = true)

    internal fun ownsEnvironment(value: RoomLocalAuthorizationEnvironment): Boolean = environment === value

    /** Called once by the actual kernel with its existing clock, not a new injected provider. */
    internal fun bindKernel(store: AtomicMasterStore, clock: MasterClock): Boolean {
        for (attempt in 0 until MAX_CAS_ATTEMPTS) {
            val before = atom.value
            if (before.serviceDenied || before.continuityUnavailable) return false
            val existing = before.kernelClock
            if (existing != null) {
                if (existing.store === store && existing.clock === clock) return true
                denyService()
                return false
            }
            val next = Snapshot(before.last, before.lastWall, KernelClockOwner(store, clock),
                before.continuityUnavailable, before.serviceDenied, before.entries)
            if (atom.compareAndSet(before, next)) return true
        }
        failClosed()
        return false
    }

    internal fun newLifetime(store: AtomicMasterStore, endpoint: LocalMasterEndpoint): PhysicalInteractiveRequestLifetime {
        val lifetime = PhysicalInteractiveRequestLifetime(this, store, endpoint, DomainId.random())
        val state = atom.value
        if (state.serviceDenied || state.continuityUnavailable || state.kernelClock?.store !== store) return lifetime
        // Reclaim genuinely expired entries even if no old handle is polled again. REQUESTED/
        // STARTING/OFFERED owners still MUST explicitly deny abandonment; no invented TTL.
        if (observeOwnedWall() == null) return lifetime
        val observed = sampleEnvironment() // LAST open callback before the bounded atom loop.
        for (attempt in 0 until MAX_CAS_ATTEMPTS) {
            val before = atom.value
            val sampled = accounting(before, observed)
            val allowed = !sampled.serviceDenied && !sampled.continuityUnavailable &&
                sampled.kernelClock?.store === store && sampled.entries.size < MAX_ACTIVE_ENTRIES
            val next = if (allowed) replace(sampled, sampled.entries + Entry(lifetime)) else sampled
            if (atom.compareAndSet(before, next)) return lifetime
        }
        failClosed()
        return lifetime // Unregistered means denied, never a fresh pool.
    }

    internal fun beginStart(lifetime: PhysicalInteractiveRequestLifetime, principal: LocalEmployeePrincipal,
        request: MutationRequest): Boolean {
        val identity = PhysicalInteractiveIdentity(principal)
        for (attempt in 0 until MAX_CAS_ATTEMPTS) {
            val before = atom.value
            val index = before.index(lifetime)
            if (index < 0) return false
            val entry = before.entries[index]
            val allowed = !before.serviceDenied && !before.continuityUnavailable && entry.phase == Phase.REQUESTED &&
                identity.interactive && identity.scope == lifetime.store.scope
            val next = if (allowed) entry.copy(phase = Phase.STARTING, identity = identity,
                operationId = request.operationId, command = request.command) else null
            if (atom.compareAndSet(before, replace(before, before.entries.replacingOrRetiring(index, next)))) return allowed
        }
        failClosed()
        return false
    }

    internal fun offer(lifetime: PhysicalInteractiveRequestLifetime,
        start: PhysicalRecordingCommittedStart): PhysicalInstructionOffer? {
        val offer = PhysicalInstructionOffer(lifetime, start)
        for (attempt in 0 until MAX_CAS_ATTEMPTS) {
            val before = atom.value
            val index = before.index(lifetime)
            if (index < 0) return null
            val entry = before.entries[index]
            val allowed = !before.serviceDenied && !before.continuityUnavailable && entry.phase == Phase.STARTING &&
                start.interactiveLifetime === lifetime && start.interactiveOrigin.storeOwner === lifetime.store &&
                entry.identity?.matches(start.interactiveOrigin) == true && entry.operationId == start.operationId &&
                entry.command == start.command
            val next = if (allowed) entry.copy(phase = Phase.OFFERED, offer = offer) else null
            if (atom.compareAndSet(before, replace(before, before.entries.replacingOrRetiring(index, next)))) return if (allowed) offer else null
        }
        failClosed()
        return null
    }

    /** Burn the exact offer BEFORE native preparation; a failed preparation can never rearm. */
    internal fun claimPreparation(offer: PhysicalInstructionOffer, lifetime: PhysicalInteractiveRequestLifetime,
        ingress: PhysicalRecordingOwnedIngress, gate: PhysicalRecordingInteractiveGate): PhysicalInstructionNativeBinding? {
        if (offer.lifetime !== lifetime) { offer.deny(); lifetime.deny(); return null }
        val binding = PhysicalInstructionNativeBinding(offer.committedStart, lifetime, ingress, gate)
        for (attempt in 0 until MAX_CAS_ATTEMPTS) {
            val before = atom.value
            val index = before.index(lifetime)
            if (index < 0) return null
            val entry = before.entries[index]
            val allowed = !before.serviceDenied && !before.continuityUnavailable && entry.phase == Phase.OFFERED && entry.offer === offer
            val next = if (allowed) entry.copy(phase = Phase.PREPARING, binding = binding) else null
            if (atom.compareAndSet(before, replace(before, before.entries.replacingOrRetiring(index, next)))) return if (allowed) binding else null
        }
        failClosed()
        return null
    }

    /** Called ONLY by the typed real-writer finalizer, after its last owned environment sample. */
    internal fun finishAuthorization(binding: PhysicalInstructionNativeBinding, dependencies: Set<PhysicalReadDependency>,
        window: PhysicalInteractiveAccessWindow, observed: RoomLocalAuthorizationObservation): Boolean {
        if (dependencies.isEmpty() || dependencies.size > MAX_DEPENDENCIES || !window.identity.matches(binding.origin)) {
            deny(binding.lifetime)
            return false
        }
        val captured = dependencies.toSet()
        for (attempt in 0 until MAX_CAS_ATTEMPTS) {
            val before = atom.value
            val index = before.index(binding.lifetime)
            if (index < 0) return false
            val entry = before.entries[index]
            // observed was ALREADY accepted by this same atom in the binding's final sample.
            // A later same-source observation is not a newly sampled elapsed regression here.
            val high = before.last
            val allowed = !before.serviceDenied && !before.continuityUnavailable && entry.phase == Phase.PREPARING &&
                entry.binding === binding && high != null && sameContinuity(high, observed) &&
                high.moment.elapsedMillis >= observed.moment.elapsedMillis && window.current(high, before.lastWall) &&
                high.moment.elapsedMillis - observed.moment.elapsedMillis <= MAX_PUBLICATION_LAG_MILLIS
            val next = if (allowed) entry.copy(phase = Phase.AUTHORIZED, dependencies = captured, window = window,
                authorizedAt = observed) else null
            if (atom.compareAndSet(before, replace(before, before.entries.replacingOrRetiring(index, next)))) return allowed
        }
        failClosed()
        return false
    }

    /** OUTSIDE the fully returned Room loan AND after temporary native release. No later callback. */
    internal fun publishAfterNormalReturn(binding: PhysicalInstructionNativeBinding,
        context: CoroutineContext): PhysicalPublishedInstruction? {
        context.ensureActive()
        var transferred = false
        try {
            if (observeOwnedWall() == null) return null
            val observed = sampleEnvironment()
            val publication = PhysicalPublishedInstruction(binding)
            for (attempt in 0 until MAX_CAS_ATTEMPTS) {
                val before = atom.value
                val sampled = accounting(before, observed)
                val index = sampled.index(binding.lifetime)
                val entry = sampled.entries.getOrNull(index)
                val allowed = entry != null && !sampled.serviceDenied && !sampled.continuityUnavailable &&
                    entry.binding === binding && entry.phase == Phase.AUTHORIZED && withinPublicationWindow(entry, observed, sampled.lastWall)
                val next = if (entry == null) sampled else replace(sampled, sampled.entries.replacingOrRetiring(index,
                    if (allowed) entry.copy(phase = Phase.PUBLISHED, publication = publication) else null))
                if (atom.compareAndSet(before, next)) {
                    // A returned sample is accounted for even when the provider cancelled this
                    // actual clean worker. No untransferred object survives the finally denial.
                    context.ensureActive()
                    transferred = allowed
                    return if (allowed) publication else null
                }
            }
            failClosed()
            return null
        } finally {
            if (!transferred) deny(binding.lifetime)
        }
    }

    internal fun adopt(instruction: PhysicalPublishedInstruction, original: PhysicalInteractiveRequestLifetime): Boolean =
        observedUse(instruction, original, Use.ADOPT, null) != null

    internal fun deliverable(instruction: PhysicalPublishedInstruction, original: PhysicalInteractiveRequestLifetime): Boolean =
        observedUse(instruction, original, Use.SAMPLE, null) != null

    internal fun captureOriginal(instruction: PhysicalPublishedInstruction, digest: PayloadDigest): PhysicalOriginalEventCapture? =
        observedUse(instruction, instruction.binding.lifetime, Use.CAPTURE, digest) as? PhysicalOriginalEventCapture

    private enum class Use { ADOPT, SAMPLE, CAPTURE }

    private fun observedUse(instruction: PhysicalPublishedInstruction, original: PhysicalInteractiveRequestLifetime,
        use: Use, digest: PayloadDigest?): Any? {
        val lifetime = instruction.binding.lifetime
        if (original !== lifetime) { deny(lifetime); return null }
        var accepted = false
        try {
            if (observeOwnedWall() == null) return null
            val observed = sampleEnvironment()
            val capture = if (use == Use.CAPTURE && digest != null) PhysicalOriginalEventCapture(digest) else null
            for (attempt in 0 until MAX_CAS_ATTEMPTS) {
                val before = atom.value
                val sampled = accounting(before, observed)
                val index = sampled.index(lifetime)
                val entry = sampled.entries.getOrNull(index)
                val allowedPhase = when (use) {
                    Use.ADOPT -> entry?.phase == Phase.PUBLISHED
                    Use.SAMPLE -> entry?.phase == Phase.PUBLISHED || entry?.phase == Phase.ADOPTED
                    Use.CAPTURE -> entry?.phase == Phase.ADOPTED && capture != null
                }
                val allowedTime = entry != null && if (entry.phase == Phase.PUBLISHED) withinPublicationWindow(entry, observed, sampled.lastWall)
                    else observed != null && entry.window?.current(observed, sampled.lastWall) == true
                val allowed = entry != null && !sampled.serviceDenied && !sampled.continuityUnavailable &&
                    entry.binding === instruction.binding && entry.publication === instruction && allowedPhase && allowedTime
                // Native ALREADY owns its Deciding(candidate) and evidence leases. The winning
                // token has no lookup back into this registry; entry removal is terminal even
                // before native installs Accepted. A concurrent withdrawal cannot erase it.
                val nextEntry = if (!allowed || entry == null) null else when (use) {
                    Use.ADOPT -> entry.copy(phase = Phase.ADOPTED)
                    Use.SAMPLE -> entry
                    Use.CAPTURE -> null
                }
                val next = if (entry == null) sampled else replace(sampled, sampled.entries.replacingOrRetiring(index, nextEntry))
                if (atom.compareAndSet(before, next)) {
                    accepted = allowed
                    return if (!allowed) null else capture ?: instruction
                }
            }
            failClosed()
            return null
        } finally {
            if (!accepted) deny(lifetime)
        }
    }

    /** Association only. Adoption/capture MUST still use their own same-source atomic phase. */
    internal fun matchesPublished(instruction: PhysicalPublishedInstruction): Boolean {
        val state = atom.value
        val entry = state.entries.getOrNull(state.index(instruction.binding.lifetime)) ?: return false
        return !state.serviceDenied && !state.continuityUnavailable && entry.binding === instruction.binding &&
            entry.publication === instruction && (entry.phase == Phase.PUBLISHED || entry.phase == Phase.ADOPTED)
    }

    internal fun denied(lifetime: PhysicalInteractiveRequestLifetime): Boolean {
        val state = atom.value
        if (state.index(lifetime) < 0) return true
        return state.serviceDenied || state.continuityUnavailable
    }

    internal fun deny(lifetime: PhysicalInteractiveRequestLifetime) { invalidate(Invalidation.Lifetime(lifetime)) }
    internal fun denyScope(scope: BranchScope) { invalidate(Invalidation.Scope(scope)) }
    internal fun denyService() { invalidate(Invalidation.Service) }

    /** Denial BEFORE provider/SQL mutation, including the borrowed approval writer. */
    internal fun denyProtectedWrite(key: ProtectedLocalRecordKey, event: LocalAuthEvent?, approval: ProtectedLocalActionApproval?) {
        val actors = mutableSetOf<DomainId>()
        val sessions = mutableSetOf<DomainId>()
        val devices = mutableSetOf<DomainId>()
        val resources = mutableSetOf<RecoveryResourceKey>()
        when (key) {
            is ProtectedLocalRecordKey.Account -> actors += key.actorId
            is ProtectedLocalRecordKey.EffectivePolicy -> actors += key.actorId
            is ProtectedLocalRecordKey.AuthConfiguration -> actors += key.actorId
            is ProtectedLocalRecordKey.Throttle -> actors += key.key.actorId
            is ProtectedLocalRecordKey.Admission -> devices += key.deviceId
            is ProtectedLocalRecordKey.SessionIssuance -> sessions += key.sessionId
            is ProtectedLocalRecordKey.RefreshIssuance -> sessions += key.sessionId
            is ProtectedLocalRecordKey.SessionArchive -> sessions += key.sessionId
            is ProtectedLocalRecordKey.AuthEvent -> {
                if (event == null || event.scope != key.scope || event.eventId != key.eventId) { denyScope(key.scope); return }
                when (event.kind) {
                    LocalAuthEventKind.RESTORE_QUARANTINED, LocalAuthEventKind.BRANCH_INSTALLED -> { denyScope(key.scope); return }
                    LocalAuthEventKind.ADMINISTRATION_CANCELLED -> {
                        // A no-effect tombstone changes only this audit identity. It does not
                        // revoke the target/operator's unrelated physical instructions. Actual
                        // account/policy/admission writes invalidate their own keys separately.
                        resources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, event.eventId)
                    }
                    LocalAuthEventKind.SERIAL_PUBLICATION_REQUEST -> {
                        // Private request/access is audit-only. It changes no account,
                        // session, grant, stock or already-observed physical instruction.
                        resources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, event.eventId)
                    }
                    LocalAuthEventKind.MASTER_LIFECYCLE -> {
                        // Explicit lifecycle audit only; STOP/RESUME changes runtime ownership,
                        // not this employee's account, grants, stock or physical instructions.
                        resources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, event.eventId)
                    }
                    LocalAuthEventKind.ACTION_APPROVAL_RETIRED -> {
                        // The retired approval alone is fenced. Its original unresolved operation
                        // and any observed physical evidence remain owned by their original keys.
                        resources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, event.targetId)
                    }
                    LocalAuthEventKind.ACCOUNT_PROVISIONED, LocalAuthEventKind.DEVICE_ADMITTED,
                    LocalAuthEventKind.SIGNED_IN, LocalAuthEventKind.REFRESHED, LocalAuthEventKind.LOGGED_OUT,
                    LocalAuthEventKind.CREDENTIAL_RESET, LocalAuthEventKind.SESSION_REVOKED,
                    LocalAuthEventKind.ACCOUNT_DISABLED, LocalAuthEventKind.SESSIONS_ARCHIVED,
                    LocalAuthEventKind.POLICY_ACTIVATED, LocalAuthEventKind.ACCOUNT_REACTIVATED,
                    LocalAuthEventKind.ACTION_APPROVAL_ISSUED, LocalAuthEventKind.ACTION_APPROVAL_CONSUMED,
                    LocalAuthEventKind.DEVICE_REVOKED -> {
                        // The closed event profile has actor/device/approval target variants.
                        // Union their actual IDs conservatively; do not globally end other logins.
                        actors += event.targetId
                        devices += event.targetId
                        sessions += event.targetId
                        event.operatorId?.let { actors += it }
                        event.sessionId?.let { sessions += it }
                        resources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, event.targetId)
                    }
                }
            }
            is ProtectedLocalRecordKey.ActionApproval -> {
                val actual = approval ?: run { denyScope(key.scope); return }
                val record = actual.record
                if (record.requirement.scope != key.scope || record.approvalId != key.approvalId) {
                    denyScope(key.scope); return
                }
                actors += record.requestorId
                actors += record.approverId
                sessions += actual.requestorSessionId
                devices += actual.requestorDeviceId
                devices += actual.approverDeviceId
                resources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, key.approvalId)
            }
        }
        actors.forEach { resources += RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it) }
        invalidate(Invalidation.Security(key.scope, actors.toSet(), sessions.toSet(), devices.toSet(), resources.toSet()))
    }

    /** Actual EFFECTIVE proposal + actual prepared/approval resources; never a relevance callback. */
    internal fun denyBusinessWrite(scope: BranchScope, operationId: OperationId, aggregates: Set<AggregateKey>,
        claims: Set<ClaimKey>, resources: Set<RecoveryResourceKey>) {
        if (aggregates.size + claims.size + resources.size + 1 > MAX_DEPENDENCIES) { denyScope(scope); return }
        val footprint = mutableSetOf<PhysicalReadDependency>(PhysicalReadDependency.Retained(operationId))
        aggregates.forEach { footprint += PhysicalReadDependency.Aggregate(it); footprint += PhysicalReadDependency.Range(it.kind) }
        claims.forEach { footprint += PhysicalReadDependency.Claim(it) }
        resources.forEach { footprint += PhysicalReadDependency.Resource(it) }
        if (footprint.size > MAX_DEPENDENCIES) { denyScope(scope); return }
        invalidate(Invalidation.Business(scope, footprint.toSet()))
    }

    private sealed interface Invalidation {
        class Lifetime(val lifetime: PhysicalInteractiveRequestLifetime) : Invalidation
        class Scope(val scope: BranchScope) : Invalidation
        class Security(val scope: BranchScope, val actors: Set<DomainId>, val sessions: Set<DomainId>,
            val devices: Set<DomainId>, val resources: Set<RecoveryResourceKey>) : Invalidation
        class Business(val scope: BranchScope, val footprint: Set<PhysicalReadDependency>) : Invalidation
        data object Service : Invalidation
    }

    private fun invalidate(change: Invalidation) {
        for (attempt in 0 until MAX_CAS_ATTEMPTS) {
            val before = atom.value
            if (before === terminal) return
            val entries = before.entries.filterNot { entry ->
                val intersects = when (change) {
                    is Invalidation.Lifetime -> entry.lifetime === change.lifetime
                    is Invalidation.Scope -> entry.lifetime.store.scope == change.scope
                    is Invalidation.Business -> entry.lifetime.store.scope == change.scope &&
                        entry.dependencies.any { it in change.footprint }
                    is Invalidation.Security -> entry.lifetime.store.scope == change.scope && (entry.identity == null ||
                        entry.identity.actorId in change.actors || entry.identity.sessionId in change.sessions ||
                        entry.identity.deviceId in change.devices ||
                        entry.dependencies.any { it is PhysicalReadDependency.Resource && it.key in change.resources })
                    Invalidation.Service -> true
                }
                intersects
            }
            // Even a currently empty/nonintersecting denial advances this SAME snapshot identity.
            // A concurrent attachment/publication cannot commit against its pre-denial snapshot.
            val next = Snapshot(before.last, before.lastWall, before.kernelClock, before.continuityUnavailable,
                before.serviceDenied || change === Invalidation.Service, entries)
            if (atom.compareAndSet(before, next)) return
        }
        failClosed()
    }

    /** Shared by ALL actual current-auth/verifier/admin observations, not just instructions. */
    internal fun observeCurrent(context: CoroutineContext): RoomLocalAuthorizationObservation {
        context.ensureActive()
        if (atom.value.continuityUnavailable) currentLocalAuthorizationDenied()
        val observed = sampleEnvironment()
        var accepted = false
        for (attempt in 0 until MAX_CAS_ATTEMPTS) {
            val before = atom.value
            val next = accounting(before, observed)
            if (atom.compareAndSet(before, next)) { accepted = !next.continuityUnavailable; break }
        }
        if (!accepted) failClosed()
        context.ensureActive()
        if (!accepted || observed == null) currentLocalAuthorizationDenied()
        return observed
    }

    private fun sampleEnvironment(): RoomLocalAuthorizationObservation? = try { environment.observe() }
    catch (cancelled: CancellationException) { throw cancelled } // No returned observation is not continuity evidence.
    catch (_: Exception) { null }

    /** M0 precedes W0; the original access and business deadline are NEVER refreshed. */
    internal fun prepareWindow(binding: PhysicalInstructionNativeBinding, access: PhysicalInteractiveAccessWindow,
        businessDeadlineExclusive: UtcInstant?, context: CoroutineContext): PhysicalInteractiveAccessWindow {
        val state = atom.value
        if (state.kernelClock?.store !== binding.origin.storeOwner || binding.denial.denied) throw PhysicalRecordingFinalDenied()
        val prelude = observeCurrent(context) // M0, same security continuity/high-water owner.
        if (!access.current(prelude, state.lastWall)) throw PhysicalRecordingFinalDenied()
        val wall = observeOwnedWall() ?: throw PhysicalRecordingFinalDenied() // W0, same kernel clock.
        // A returned wall observation was accounted even if its callback cancelled this loan.
        context.ensureActive()
        return access.boundToBusinessDeadline(businessDeadlineExclusive, prelude, wall)
    }

    /** Account W immediately, even if the subsequent final environment callback throws. */
    private fun observeOwnedWall(): UtcInstant? {
        val wall = sampleOwnedWall()
        for (attempt in 0 until MAX_CAS_ATTEMPTS) {
            val before = atom.value
            val next = accountingWall(before, wall)
            if (atom.compareAndSet(before, next)) return if (!next.serviceDenied && !next.continuityUnavailable) wall else null
        }
        failClosed()
        return null
    }

    private fun sampleOwnedWall(): UtcInstant? {
        val owner = atom.value.kernelClock ?: return null
        return try { owner.clock.now().also(::requireEvidenceV1Time) }
        catch (cancelled: CancellationException) { denyService(); throw cancelled }
        catch (_: Exception) { null }
    }

    private fun accounting(before: Snapshot, observed: RoomLocalAuthorizationObservation?): Snapshot {
        val previous = before.last
        if (before.continuityUnavailable || observed == null || (previous != null &&
            (!sameContinuity(previous, observed) || observed.moment.elapsedMillis < previous.moment.elapsedMillis))) return terminal
        return retireExpired(Snapshot(observed, before.lastWall, before.kernelClock, false, before.serviceDenied, before.entries))
    }

    private fun accountingWall(before: Snapshot, wall: UtcInstant?): Snapshot {
        if (before.continuityUnavailable) return terminal
        val previous = before.lastWall
        if (wall == null || (previous != null && wall < previous)) {
            // Clock qualification/regression closes physical arming, not accepted evidence.
            // Ordinary monotonic protected-auth observation still has its SAME high-water.
            return Snapshot(before.last, previous, before.kernelClock, false, true, emptyList())
        }
        return retireExpired(Snapshot(before.last, wall, before.kernelClock, false, before.serviceDenied, before.entries))
    }

    private fun retireExpired(before: Snapshot): Snapshot {
        val observed = before.last ?: return before
        val retained = before.entries.filter { entry ->
            val window = entry.window
            if (window == null) true // Never invent an expiry for an unbound request or offer.
            else if (entry.phase == Phase.AUTHORIZED || entry.phase == Phase.PUBLISHED)
                withinPublicationWindow(entry, observed, before.lastWall)
            else window.current(observed, before.lastWall)
        }
        return if (retained.size == before.entries.size) before else replace(before, retained)
    }

    private fun withinPublicationWindow(entry: Entry, observed: RoomLocalAuthorizationObservation?, wall: UtcInstant?): Boolean {
        val at = entry.authorizedAt ?: return false
        if (observed == null || !sameContinuity(at, observed) || entry.window?.current(observed, wall) != true ||
            observed.moment.elapsedMillis < at.moment.elapsedMillis) return false
        return observed.moment.elapsedMillis - at.moment.elapsedMillis <= MAX_PUBLICATION_LAG_MILLIS
    }

    private fun failClosed() { atom.value = terminal }
    private fun Snapshot.index(lifetime: PhysicalInteractiveRequestLifetime): Int =
        if (lifetime.registry !== this@PhysicalInstructionPublicationRegistry) -1 else entries.indexOfFirst { it.lifetime === lifetime }
    private fun replace(before: Snapshot, entries: List<Entry>): Snapshot =
        Snapshot(before.last, before.lastWall, before.kernelClock, before.continuityUnavailable, before.serviceDenied, entries)
    private fun List<Entry>.replacingOrRetiring(index: Int, value: Entry?): List<Entry> = toMutableList().also {
        if (value == null) it.removeAt(index) else it[index] = value
    }.toList()

    internal companion object {
        const val MAX_DEPENDENCIES = 512
        private const val MAX_ACTIVE_ENTRIES = 128
        private const val MAX_CAS_ATTEMPTS = 32
        private const val MAX_PUBLICATION_LAG_MILLIS = 1_000L
        private fun sameContinuity(first: RoomLocalAuthorizationObservation, second: RoomLocalAuthorizationObservation): Boolean =
            first.issuerContextId == second.issuerContextId && first.moment.bootId == second.moment.bootId
    }
}

/** Captured scalar identity only; never a retained principal or current authorization decision. */
internal class PhysicalInteractiveIdentity(principal: LocalEmployeePrincipal) {
    val scope = principal.scope
    val actorId = principal.actorId
    val deviceId = principal.deviceId
    val sessionId = principal.sessionId
    val authenticationId = principal.authenticationId
    val generation = principal.sessionGeneration
    val admissionRevision = principal.admissionRevision
    val interactive = principal.authenticationPurpose == AuthenticationPurpose.INTERACTIVE
    fun matches(origin: PhysicalInteractiveOrigin): Boolean = interactive && origin.interactive && scope == origin.scope &&
        actorId == origin.actorId && deviceId == origin.deviceId && sessionId == origin.sessionId &&
        authenticationId == origin.authenticationId && generation == origin.generation && admissionRevision == origin.admissionRevision
}

/** Protected original ACCESS expiry plus an OPTIONAL earlier authoritative business deadline. */
internal class PhysicalInteractiveAccessWindow(
    val identity: PhysicalInteractiveIdentity,
    private val issuerContextId: DomainId,
    private val bootId: DomainId,
    private val issuedAtElapsedMillis: Long,
    private val originalExpiresAtElapsedMillis: Long,
    private val businessDeadlineExclusive: UtcInstant? = null,
    private val businessExpiresAtElapsedMillis: Long? = null,
) {
    fun current(observed: RoomLocalAuthorizationObservation, wall: UtcInstant? = null): Boolean =
        observed.issuerContextId == issuerContextId && observed.moment.bootId == bootId &&
            observed.moment.elapsedMillis >= issuedAtElapsedMillis &&
            observed.moment.elapsedMillis < originalExpiresAtElapsedMillis &&
            (businessExpiresAtElapsedMillis == null || observed.moment.elapsedMillis < businessExpiresAtElapsedMillis) &&
            (businessDeadlineExclusive == null || (wall != null && wall < businessDeadlineExclusive))

    fun boundToBusinessDeadline(deadline: UtcInstant?, prelude: RoomLocalAuthorizationObservation,
        wall: UtcInstant): PhysicalInteractiveAccessWindow {
        if (businessDeadlineExclusive != null || businessExpiresAtElapsedMillis != null || !current(prelude))
            throw PhysicalRecordingFinalDenied()
        if (deadline == null) return this // Refund has no fabricated business/human-action TTL.
        requireEvidenceV1Time(deadline)
        if (wall >= deadline) throw PhysicalRecordingFinalDenied()
        // Floor the exclusive deadline and CEIL the wall sample to milliseconds: earlier-safe,
        // including submillisecond sources. M0 was sampled BEFORE W0, so clock/callback latency
        // and all following protected-finalizer time consume validity instead of extending it.
        val end = deadline.value.toEpochMilliseconds()
        val wallFloor = wall.value.toEpochMilliseconds()
        val wallCeiling = if (wall.value.nanosecondsOfSecond % 1_000_000 == 0) wallFloor
            else checkedAddPositive(wallFloor, 1L)
        val remaining = checkedPositiveDifference(end, wallCeiling)
        val projected = checkedAddPositive(prelude.moment.elapsedMillis, remaining)
        return PhysicalInteractiveAccessWindow(identity, issuerContextId, bootId, issuedAtElapsedMillis,
            originalExpiresAtElapsedMillis, deadline, minOf(projected, originalExpiresAtElapsedMillis))
    }

    private fun checkedPositiveDifference(later: Long, earlier: Long): Long {
        if (later <= earlier || (earlier < 0 && later > Long.MAX_VALUE + earlier)) throw PhysicalRecordingFinalDenied()
        return later - earlier
    }
    private fun checkedAddPositive(value: Long, amount: Long): Long {
        if (amount <= 0 || value > Long.MAX_VALUE - amount) throw PhysicalRecordingFinalDenied()
        return value + amount
    }
}

internal sealed interface PhysicalReadDependency {
    data class Aggregate(val key: AggregateKey) : PhysicalReadDependency
    data class Range(val kind: String) : PhysicalReadDependency
    data class Claim(val key: ClaimKey) : PhysicalReadDependency
    data class Retained(val operationId: OperationId) : PhysicalReadDependency
    data class Resource(val key: RecoveryResourceKey) : PhysicalReadDependency
}

/** Actual TransactionView read tracing, INCLUDING absence and whole-kind range dependencies. */
internal class PhysicalInstructionReadTrace {
    private val reads = mutableSetOf<PhysicalReadDependency>()
    private var failed = false
    private var frozen = false
    private val stockPages = mutableMapOf<StockQueryV2, StockPageCursor?>()
    private val stockKeys = mutableMapOf<String, DomainId?>()
    fun stockPageStart(query: StockQueryV2, after: StockPageCursor?) {
        if (failed || frozen || (after == null && query in stockPages) ||
            (after != null && (!stockPages.containsKey(query) || stockPages[query] !== after))) {
            fail(); throw PhysicalRecordingFinalDenied()
        }
        if (after == null) stockPages[query] = null
        for (kind in StockRelationalKindsV2.all) add(PhysicalReadDependency.Range(kind))
    }
    fun stockPageEnd(query: StockQueryV2, next: StockPageCursor?) {
        if (failed || frozen || query !in stockPages) { fail(); throw PhysicalRecordingFinalDenied() }
        if (next == null) stockPages.remove(query) else stockPages[query] = next
    }
    fun stockKeysStart(kind: String, after: DomainId?) {
        if (failed || frozen || (after == null && kind in stockKeys) ||
            (after != null && (!stockKeys.containsKey(kind) || stockKeys[kind] != after))) {
            fail(); throw PhysicalRecordingFinalDenied()
        }
        if (after == null) stockKeys[kind] = null
        add(PhysicalReadDependency.Range(kind))
    }
    fun stockKeysEnd(kind: String, next: DomainId?) {
        if (failed || frozen || kind !in stockKeys) { fail(); throw PhysicalRecordingFinalDenied() }
        if (next == null) stockKeys.remove(kind) else stockKeys[kind] = next
    }
    fun add(dependency: PhysicalReadDependency) {
        if (failed || frozen) { fail(); throw PhysicalRecordingFinalDenied() }
        reads += dependency
        if (reads.size > PhysicalInstructionPublicationRegistry.MAX_DEPENDENCIES) { fail(); throw PhysicalRecordingFinalDenied() }
    }
    fun fail() { failed = true }
    fun readyForFinalization(): Boolean = frozen && !failed
    fun freeze(resources: Set<RecoveryResourceKey>): Set<PhysicalReadDependency> {
        if (failed || frozen || stockPages.isNotEmpty() || stockKeys.isNotEmpty() || resources.size > PhysicalInstructionPublicationRegistry.MAX_DEPENDENCIES) throw PhysicalRecordingFinalDenied()
        resources.forEach { add(PhysicalReadDependency.Resource(it)) }
        frozen = true
        return reads.toSet()
    }
}

/** The typed finalizer's captured data, not a caller-provided finalization callback. */
internal class PhysicalInstructionAuthorizationFrame(
    private val registry: PhysicalInstructionPublicationRegistry,
    internal val binding: PhysicalInstructionNativeBinding,
    private val dependencies: Set<PhysicalReadDependency>,
    private val businessDeadlineExclusive: UtcInstant?,
) {
    private var window: PhysicalInteractiveAccessWindow? = null
    fun prepare(authorization: RoomCurrentLocalAuthorizationBinding, context: CoroutineContext) {
        check(window == null)
        val access = authorization.physicalAccessWindow(registry, binding.origin)
        window = registry.prepareWindow(binding, access, businessDeadlineExclusive, context)
    }
    fun finishAt(observed: RoomLocalAuthorizationObservation) {
        val captured = window ?: throw PhysicalRecordingFinalDenied()
        if (!registry.finishAuthorization(binding, dependencies, captured, observed)) throw PhysicalRecordingFinalDenied()
    }
}
