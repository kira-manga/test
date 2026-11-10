package com.ukkera.pos.app.gateway

import android.app.Application
import com.ukkera.pos.database.AndroidEmployeeClientCustody
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.NativeOriginalAcquire
import com.ukkera.pos.database.NativeOriginalIntake
import com.ukkera.pos.database.NativeOriginalIntakeDisposition
import com.ukkera.pos.database.NativeOriginalIntakeInvocation
import com.ukkera.pos.database.NativeOriginalRetainedLease
import com.ukkera.pos.database.OriginalPhysicalEmployeeEnrollment
import com.ukkera.pos.database.PhysicalInstructionNativeBinding
import com.ukkera.pos.database.PhysicalInteractiveRequestLifetime
import com.ukkera.pos.database.PhysicalNativeInstructionLease
import com.ukkera.pos.database.PhysicalNativeInstructionPreparation
import com.ukkera.pos.database.PhysicalPublishedInstruction
import com.ukkera.pos.database.PhysicalRecordingAcquire
import com.ukkera.pos.database.PhysicalRecordingCommittedStart
import com.ukkera.pos.database.PhysicalRecordingInvocationBinding
import com.ukkera.pos.database.PhysicalRecordingNativeCheck
import com.ukkera.pos.database.PhysicalRecordingNativeLease
import com.ukkera.pos.database.PhysicalRecordingNativeRelease
import com.ukkera.pos.database.PhysicalRecordingParticipants
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.sales.store.AndroidOriginalRetailPhysicalAction
import com.ukkera.pos.sales.store.AndroidOriginalRetailPhysicalRecording
import com.ukkera.pos.sales.store.AndroidPhysicalCompositionNamespaceV1
import com.ukkera.pos.sales.store.LocalPhysicalDelivery
import com.ukkera.pos.sales.store.LocalPhysicalExternalCustody
import com.ukkera.pos.sales.store.LocalPhysicalJournalExecutionOwner
import com.ukkera.pos.sales.store.LocalPhysicalPublishedInstruction
import com.ukkera.pos.sales.store.LocalRetailJournalParticipants
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The actual original Android gate, registered ONCE before master activation. This same
 * gateway boundary privately acquires and fully typed-validates employee intent journals.
 * The lower original backend implements no authorizing SPI and has no employee mint/finish/
 * head/proof/grant API. Actor selection can only select a retained genuine successful owner;
 * it never constructs, reopens, replaces or falls back to another employee's journal.
 *
 * Immutable genuine consumed lineage permits historical native census/retention. Present
 * Start/retention/resolution/B4 additionally require the exact cohost-delivered successful
 * journal owner and full native/typed recheck outside Room. It issues no remote/provisioning,
 * company-head, device-credential, generic account-row or additional-device authority. */
internal class AndroidOriginalPhysicalJournalExecutionGate private constructor(
    private val application: Application,
    internal val composition: AndroidPhysicalCompositionNamespaceV1,
) : LocalPhysicalJournalExecutionOwner {
    private val backend = AndroidOriginalRetailPhysicalRecording.createBackend(this)
    private class OriginalOwner(val original: AndroidOriginalLocalClient) {
        val scope = original.material.coordinates.scope
        val deviceId = original.material.coordinates.deviceId
        val firstActorId = original.material.coordinates.firstActorId
        val authority = AuthorityIdentity(original.material.coordinates.assignmentId,
            deviceId, original.receipt.core.authorityEpoch)
    }
    private class EmployeeOwner(val custody: AndroidEmployeeClientCustody,
        val history: OriginalPhysicalEmployeeEnrollment, val native: AndroidLocalClientJournalOwner)
    private class State(val denied: Boolean, val original: OriginalOwner?, val employees: List<EmployeeOwner>)
    private class Participant(val original: OriginalOwner, val employee: EmployeeOwner?)
    private val state = AtomicReference(State(false, null, emptyList()))
    private val admissions = Mutex()

    internal companion object {
        fun create(application: Application,
            composition: AndroidPhysicalCompositionNamespaceV1 = AndroidPhysicalCompositionNamespaceV1.LIVE):
            AndroidOriginalPhysicalJournalExecutionGate =
            AndroidOriginalPhysicalJournalExecutionGate(application, composition)
    }

    /** The gate is created for exactly one already-created setup composition. */
    internal fun ownsComposition(mode: com.ukkera.pos.security.LocalSetupCompositionMode): Boolean =
        composition.mode == mode

    /** Original native acquisition preserves all namespace/catalogue/key/census checks. The
     * exact original reference is nonreplaceable even when acquisition or close is uncertain. */
    internal suspend fun acquireOriginalOwner(original: AndroidOriginalLocalClient,
        action: AndroidOriginalRetailPhysicalAction): Boolean = admissions.withLock {
        currentCoroutineContext().ensureActive()
        if (!original.belongsTo(application) || original.physicalEndpoint(this) == null) return@withLock false
        while (true) {
            val selected = state.get()
            if (selected.denied) return@withLock false
            selected.original?.let { if (it.original !== original) return@withLock false }
            if (selected.original != null || state.compareAndSet(selected, State(false, OriginalOwner(original), selected.employees))) break
        }
        val acquired = AndroidOriginalRetailPhysicalRecording.acquire(application, original, backend, action, composition)
        currentCoroutineContext().ensureActive()
        acquired && !state.get().denied && state.get().original?.original === original &&
            original.belongsTo(application) && original.physicalEndpoint(this) != null && backend.belongsToOriginalOwner(original)
    }

    /** Only the cohost's exact private successful entry invokes this. Genuine custody alone,
     * claimed-open allocation, a parsed/structural journal, ID/Boolean or SDK alias cannot join.
     * The history refresh opens no new physical roots, keys, capacity or allocation. */
    internal suspend fun admitEmployee(original: AndroidOriginalLocalClient, custody: AndroidEmployeeClientCustody,
        native: AndroidLocalClientJournalOwner): Boolean = admissions.withLock {
        currentCoroutineContext().ensureActive()
        val before = state.get()
        val originalOwner = before.original ?: return@withLock false
        if (before.denied || originalOwner.original !== original || !backend.belongsToOriginalOwner(original) ||
            !custody.belongsTo(original) || !custody.belongsTo(application) || native.physicalDenied ||
            !native.ownsEmployeePhysical(custody)) return@withLock false
        before.employees.singleOrNull { it.custody.actorId == custody.actorId }?.let { previous ->
            if (previous.custody !== custody || previous.native !== native ||
                !previous.history.sameOriginalEnrollment(custody.physicalEnrollmentHistory)) return@withLock false
        }
        if (!native.recheckEmployeePhysical(custody)) return@withLock false
        if (!AndroidOriginalRetailPhysicalRecording.acquire(application, original, backend,
                AndroidOriginalRetailPhysicalAction.REOPEN_EXISTING, composition)) return@withLock false
        currentCoroutineContext().ensureActive()
        val history = custody.physicalEnrollmentHistory
        if (!backend.hasOriginalEnrollment(history) || !history.belongsToOriginalOwner(original) ||
            history.actorId == originalOwner.firstActorId || native.physicalDenied || !native.ownsEmployeePhysical(custody) ||
            !custody.belongsTo(original) || !custody.belongsTo(application)) return@withLock false
        while (true) {
            val selected = state.get()
            if (selected.denied || selected.original !== before.original || native.physicalDenied) return@withLock false
            selected.employees.singleOrNull { it.custody.actorId == custody.actorId }?.let { previous ->
                return@withLock previous.custody === custody && previous.native === native &&
                    previous.history.sameOriginalEnrollment(history) && !previous.native.physicalDenied
            }
            if (selected.employees.size >= MAX_CLIENT_ENROLLMENTS) return@withLock false
            val entry = EmployeeOwner(custody, history, native)
            if (state.compareAndSet(selected, State(false, selected.original, selected.employees + entry)))
                return@withLock !state.get().denied && !native.physicalDenied
        }
        @Suppress("UNREACHABLE_CODE") false
    }

    /** Room-safe: ONLY immutable actual binding/history references and final denial scalars.
     * No service/custody/native callback, owner monitor, IO, dispatcher, mutex or worker call. */
    override suspend fun current(view: CommandReadView, participants: PhysicalRecordingParticipants): LocalRetailJournalParticipants? {
        val selected = participant(view.scope, participants.originalActorId, participants.clientDeviceId, participants.authority)
            ?: return null
        val installed = backend.current(view, participants) ?: return null
        return installed.takeIf { !state.get().denied && selected.employee?.native?.physicalDenied != true }
    }

    private fun participant(scope: BranchScope, actorId: DomainId, deviceId: DomainId,
        authority: AuthorityIdentity): Participant? {
        val selected = state.get()
        val original = selected.original ?: return null
        if (selected.denied || original.scope != scope || original.deviceId != deviceId || original.authority != authority ||
            !backend.belongsToOriginalOwner(original.original)) return null
        if (actorId == original.firstActorId) return Participant(original, null)
        // Nonreplaceable entries hold actual genuine custody AND the private success object,
        // not an actor allowlist, account-row predicate or a reconstructed/opened-on-lookup owner.
        val employee = selected.employees.singleOrNull { it.history.actorId == actorId } ?: return null
        if (employee.native.physicalDenied || employee.history.scope != scope || employee.history.deviceId != deviceId ||
            employee.history.authority != authority || !employee.history.belongsToOriginalOwner(original.original) ||
            !backend.hasOriginalEnrollment(employee.history)) return null
        return Participant(original, employee)
    }

    private fun participant(binding: PhysicalRecordingInvocationBinding): Participant? =
        participant(binding.scope, binding.originalActorId, binding.clientDeviceId, binding.authority)
            ?.takeIf { it.original.original.ownsPhysicalInvocation(this, binding) }
    private fun participant(binding: PhysicalInstructionNativeBinding): Participant? =
        participant(binding.scope, binding.originalActorId, binding.clientDeviceId, binding.authority)
            ?.takeIf { it.original.original.ownsPhysicalInstruction(this, binding) }

    /** Present native/service and unchanged full journal codec recheck OUTSIDE Room. */
    private suspend fun recheckParticipant(selected: Participant): Boolean {
        if (state.get().denied || state.get().original !== selected.original ||
            !selected.original.original.belongsTo(application) || selected.original.original.physicalEndpoint(this) == null ||
            !backend.belongsToOriginalOwner(selected.original.original)) return false
        val employee = selected.employee ?: return true // Original founding behavior is unchanged.
        return !employee.native.physicalDenied && employee.custody.belongsTo(selected.original.original) &&
            employee.custody.belongsTo(application) && backend.hasOriginalEnrollment(employee.history) &&
            employee.native.recheckEmployeePhysical(employee.custody) && !state.get().denied && !employee.native.physicalDenied
    }

    private suspend fun captureEmployee(selected: Participant): LocalPhysicalExternalCustody? {
        val employee = selected.employee ?: return null
        val loan = employee.native.captureEmployeePhysical(employee.custody) ?: return null
        var delivered = false
        try {
            if (!recheckParticipant(selected) || loan.denied || loan.lifetimeDenied) return null
            delivered = true
            return loan
        } finally { if (!delivered) { loan.deny(); check(loan.release()) { "physical.employee.loan_cleanup" } } }
    }

    override suspend fun acquire(binding: PhysicalRecordingInvocationBinding): PhysicalRecordingAcquire {
        val selected = participant(binding) ?: run { binding.latch.deny(); return PhysicalRecordingAcquire.Held }
        var loan: LocalPhysicalExternalCustody? = null
        var transferred = false
        try {
            if (selected.employee == null) {
                if (!recheckParticipant(selected)) { binding.latch.deny(); return PhysicalRecordingAcquire.Held }
            } else {
                loan = captureEmployee(selected) ?: run { binding.latch.deny(); return PhysicalRecordingAcquire.Held }
            }
            currentCoroutineContext().ensureActive()
            // The backend consumes this lifecycle loan before its first cancellation check/IO.
            // It retains it on unknown cleanup, never releases it with an undelivered response.
            transferred = true
            return backend.acquire(binding, loan)
        } catch (cancelled: CancellationException) { binding.latch.deny(); throw cancelled }
          catch (_: Exception) { binding.latch.deny(); return PhysicalRecordingAcquire.StorageUncertain }
        finally { if (!transferred) { loan?.deny(); check(loan?.release() != false) { "physical.employee.loan_cleanup" } } }
    }

    override suspend fun recheck(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeCheck {
        try {
            val selected = participant(lease.binding)
            if (selected == null || !recheckParticipant(selected)) { lease.binding.latch.deny(); return PhysicalRecordingNativeCheck.HELD }
            return backend.recheck(lease)
        } catch (cancelled: CancellationException) { lease.binding.latch.deny(); throw cancelled }
          catch (_: Exception) { lease.binding.latch.deny(); return PhysicalRecordingNativeCheck.STORAGE_UNCERTAIN }
    }

    override suspend fun persistStart(lease: PhysicalRecordingNativeLease,
        start: PhysicalRecordingCommittedStart): PhysicalRecordingNativeCheck = withContext(NonCancellable) {
        // SQL already committed. Employee withdrawal/failed typed recheck denies Fresh/B4, NOT
        // preservation of either genuine committed certificate or existing physical evidence.
        val live = try { participant(lease.binding)?.let { recheckParticipant(it) } == true }
            catch (_: Exception) { false }
        if (!live) lease.binding.latch.deny()
        backend.persistStart(lease, start)
    }

    override suspend fun release(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeRelease = backend.release(lease)

    override suspend fun prepareInstruction(binding: PhysicalInstructionNativeBinding): PhysicalNativeInstructionPreparation {
        val selected = participant(binding) ?: run { binding.denial.deny(); return PhysicalNativeInstructionPreparation.Held }
        var loan: LocalPhysicalExternalCustody? = null
        var transferred = false
        try {
            if (selected.employee == null) {
                if (!recheckParticipant(selected)) { binding.denial.deny(); return PhysicalNativeInstructionPreparation.Held }
            } else {
                loan = captureEmployee(selected) ?: run { binding.denial.deny(); return PhysicalNativeInstructionPreparation.Held }
            }
            currentCoroutineContext().ensureActive()
            transferred = true
            return backend.prepareInstruction(binding, loan)
        } catch (cancelled: CancellationException) { binding.denial.deny(); throw cancelled }
          catch (_: Exception) { binding.denial.deny(); return PhysicalNativeInstructionPreparation.StorageUncertain }
        finally { if (!transferred) { loan?.deny(); check(loan?.release() != false) { "physical.employee.loan_cleanup" } } }
    }

    override suspend fun releaseInstruction(prepared: PhysicalNativeInstructionLease): PhysicalRecordingNativeRelease = backend.releaseInstruction(prepared)
    override fun adoptPublished(published: PhysicalPublishedInstruction,
        original: PhysicalInteractiveRequestLifetime): LocalPhysicalPublishedInstruction? {
        if (state.get().denied) { published.deny(); return null }
        // No IO/currentness callback after the core's final publication time sample. The
        // exact private continuation already carries the parent's synchronous denial view.
        return backend.adoptPublished(published, original)
    }

    // Native-original intake/redelivery is independent end-only historical custody. It must
    // survive employee withdrawal, and still uses this SAME core-registered owner association.
    override fun installedIntake(intake: NativeOriginalIntake): Boolean = backend.installedIntake(intake)
    override suspend fun acquireOriginal(invocation: NativeOriginalIntakeInvocation): NativeOriginalAcquire = backend.acquireOriginal(invocation)
    override suspend fun recheckOriginal(lease: NativeOriginalRetainedLease): PhysicalRecordingNativeCheck = backend.recheckOriginal(lease)
    override suspend fun releaseOriginal(lease: NativeOriginalRetainedLease,
        disposition: NativeOriginalIntakeDisposition): PhysicalRecordingNativeRelease = backend.releaseOriginal(lease, disposition)
    override suspend fun enqueueRetainedOriginals(): Unit = backend.enqueueRetainedOriginals()
    internal suspend fun redeliverRetainedOriginal(clientAllocationId: DomainId): LocalPhysicalDelivery = backend.redeliverRetainedOriginal(clientAllocationId)
    internal suspend fun redeliverRetainedResolution(clientAllocationId: DomainId): LocalPhysicalDelivery = backend.redeliverRetainedResolution(clientAllocationId)
    override fun denyNewWork() {
        while (true) {
            val selected = state.get()
            if (selected.denied || state.compareAndSet(selected, State(true, selected.original, selected.employees))) break
        }
        backend.denyNewWork()
    }
    override suspend fun closeOwnedGate(): PhysicalRecordingNativeRelease { denyNewWork(); return backend.closeOwnedGate() }
    override fun toString(): String = "AndroidOriginalPhysicalJournalExecutionGate([REDACTED])"
}
