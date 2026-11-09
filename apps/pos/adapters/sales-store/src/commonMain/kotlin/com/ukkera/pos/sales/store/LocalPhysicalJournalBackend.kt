package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.NativeOriginalAcquire
import com.ukkera.pos.database.NativeOriginalIntake
import com.ukkera.pos.database.NativeOriginalIntakeDisposition
import com.ukkera.pos.database.NativeOriginalIntakeInvocation
import com.ukkera.pos.database.NativeOriginalIntakeSource
import com.ukkera.pos.database.NativeOriginalRetainedLease
import com.ukkera.pos.database.OriginalPhysicalEmployeeEnrollment
import com.ukkera.pos.database.PhysicalInstructionNativeBinding
import com.ukkera.pos.database.PhysicalInteractiveRequestLifetime
import com.ukkera.pos.database.PhysicalNativeInstructionLease
import com.ukkera.pos.database.PhysicalNativeInstructionPreparation
import com.ukkera.pos.database.PhysicalPublishedInstruction
import com.ukkera.pos.database.PhysicalRecordingAcquire
import com.ukkera.pos.database.PhysicalRecordingCommittedStart
import com.ukkera.pos.database.PhysicalRecordingInteractiveGate
import com.ukkera.pos.database.PhysicalRecordingInvocationBinding
import com.ukkera.pos.database.PhysicalRecordingNativeCheck
import com.ukkera.pos.database.PhysicalRecordingNativeLease
import com.ukkera.pos.database.PhysicalRecordingNativeRelease
import com.ukkera.pos.database.PhysicalRecordingOwnedExecutionGate
import com.ukkera.pos.database.PhysicalRecordingParticipants
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow

/** Composition port only. A supplied implementation is not employee or native authority.
 * The original Android service registers its exact private gateway-owned implementation before
 * activation; the lower original backend deliberately does not implement this contract. */
public interface LocalPhysicalJournalExecutionOwner : PhysicalRecordingOwnedExecutionGate,
    PhysicalRecordingInteractiveGate, NativeOriginalIntakeSource, TransactionRetailJournalParticipantsSource {
    public fun adoptPublished(published: PhysicalPublishedInstruction,
        original: PhysicalInteractiveRequestLifetime): LocalPhysicalPublishedInstruction?
}

/** End-only lifecycle loan. It cannot validate, acquire, admit or authorize an employee.
 * Only the registered gateway captures its real, privately acquired successful journal owner
 * and independently executes the unchanged full typed validator before native work. Native
 * release must precede release of this loan, including cancellation and uncertain cleanup. */
public interface LocalPhysicalExternalCustody {
    public val denied: Boolean
    /** Parent denial persists after ordinary invocation release; release is not withdrawal. */
    public val lifetimeDenied: Boolean
    public fun deny(): Unit
    public fun observeLifetimeDenial(onDenial: () -> Unit): LocalPhysicalExternalDenialRegistration
    /** Memory-only cleanup result, not a positive authorization/currentness observation. */
    public fun release(): Boolean
}

/** Unregistering an end-only observer never revives either lifetime or invocation. */
public fun interface LocalPhysicalExternalDenialRegistration {
    public fun release(): Unit
}

/** Preserve the existing explicit two-local-role retail factory. No original Android admission
 * factory exists here: its only authorizing implementation now belongs to app-gateway. */
public class LocalPhysicalJournalExecutionGate private constructor(
    master: LocalPhysicalAllocationCatalogue,
    client: LocalPhysicalAllocationCatalogue,
    nativeDispatcher: CoroutineDispatcher,
    evidenceDispatcher: CoroutineDispatcher,
) : LocalPhysicalJournalExecutionOwner {
    private val engine = PhysicalJournalNativeBackend.forRetail(this, master, client, nativeDispatcher, evidenceDispatcher)
    public companion object {
        public fun forRetail(master: LocalPhysicalAllocationCatalogue, client: LocalPhysicalAllocationCatalogue,
            nativeDispatcher: CoroutineDispatcher, evidenceDispatcher: CoroutineDispatcher): LocalPhysicalJournalExecutionGate =
            LocalPhysicalJournalExecutionGate(master, client, nativeDispatcher, evidenceDispatcher)
    }
    override suspend fun current(view: CommandReadView, participants: PhysicalRecordingParticipants): LocalRetailJournalParticipants? =
        engine.current(view, participants)
    override suspend fun acquire(binding: PhysicalRecordingInvocationBinding): PhysicalRecordingAcquire = engine.acquire(binding)
    override suspend fun recheck(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeCheck = engine.recheck(lease)
    override suspend fun persistStart(lease: PhysicalRecordingNativeLease, start: PhysicalRecordingCommittedStart): PhysicalRecordingNativeCheck =
        engine.persistStart(lease, start)
    override suspend fun release(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeRelease = engine.release(lease)
    override suspend fun prepareInstruction(binding: PhysicalInstructionNativeBinding): PhysicalNativeInstructionPreparation = engine.prepareInstruction(binding)
    override suspend fun releaseInstruction(prepared: PhysicalNativeInstructionLease): PhysicalRecordingNativeRelease = engine.releaseInstruction(prepared)
    override fun adoptPublished(published: PhysicalPublishedInstruction, original: PhysicalInteractiveRequestLifetime): LocalPhysicalPublishedInstruction? =
        engine.adoptPublished(published, original)
    override fun installedIntake(intake: NativeOriginalIntake): Boolean = engine.installedIntake(intake)
    override suspend fun acquireOriginal(invocation: NativeOriginalIntakeInvocation): NativeOriginalAcquire = engine.acquireOriginal(invocation)
    override suspend fun recheckOriginal(lease: NativeOriginalRetainedLease): PhysicalRecordingNativeCheck = engine.recheckOriginal(lease)
    override suspend fun releaseOriginal(lease: NativeOriginalRetainedLease, disposition: NativeOriginalIntakeDisposition): PhysicalRecordingNativeRelease =
        engine.releaseOriginal(lease, disposition)
    override suspend fun enqueueRetainedOriginals(): Unit = engine.enqueueRetainedOriginals()
    public suspend fun redeliverRetainedOriginal(clientAllocationId: DomainId): LocalPhysicalDelivery = engine.redeliverRetainedOriginal(clientAllocationId)
    public suspend fun redeliverRetainedResolution(clientAllocationId: DomainId): LocalPhysicalDelivery = engine.redeliverRetainedResolution(clientAllocationId)
    public fun deny(): Unit = engine.deny()
    override fun denyNewWork(): Unit = engine.denyNewWork()
    public suspend fun close(): LocalPhysicalSpoolClose = engine.close()
    override suspend fun closeOwnedGate(): PhysicalRecordingNativeRelease = engine.closeOwnedGate()
    override fun toString(): String = "LocalPhysicalJournalExecutionGateV1([REDACTED])"
}

/** Closed native original backend, NOT an execution/participant/interactive/intake SPI.
 * It is bound once to the actual registered upper owner. History is immutable provenance only;
 * there is no employee proof getter, journal finish/head API, grant callback or admission API.
 * All paths, receipt identities, catalogues, keys, capacity and native retention remain here. */
public class LocalOriginalPhysicalJournalBackend private constructor(
    public val registeredOwner: LocalPhysicalJournalExecutionOwner,
    private val engine: PhysicalJournalNativeBackend,
) {
    internal companion object {
        fun awaitingOriginal(owner: LocalPhysicalJournalExecutionOwner, nativeDispatcher: CoroutineDispatcher,
            evidenceDispatcher: CoroutineDispatcher): LocalOriginalPhysicalJournalBackend =
            LocalOriginalPhysicalJournalBackend(owner,
                PhysicalJournalNativeBackend.awaitingOriginalAndroidRecording(owner, nativeDispatcher, evidenceDispatcher))
    }
    internal suspend fun installOriginalAndroidOwners(identity: OriginalAndroidPhysicalOwnerIdentity,
        history: List<OriginalPhysicalEmployeeEnrollment>, acquire: (PhysicalJournalGateOwnerAttempt) -> Boolean): Boolean =
        engine.installOriginalAndroidOwners(identity, history, acquire)
    /** Reference/history reads only; neither method observes present permission or native IO. */
    public fun belongsToOriginalOwner(original: Any): Boolean = engine.belongsToOriginalOwner(original)
    public fun hasOriginalEnrollment(witness: OriginalPhysicalEmployeeEnrollment): Boolean = engine.hasOriginalEnrollment(witness)
    public suspend fun current(view: CommandReadView, participants: PhysicalRecordingParticipants): LocalRetailJournalParticipants? =
        engine.current(view, participants)
    public suspend fun acquire(binding: PhysicalRecordingInvocationBinding, custody: LocalPhysicalExternalCustody?): PhysicalRecordingAcquire =
        engine.acquire(binding, custody)
    public suspend fun recheck(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeCheck = engine.recheck(lease)
    public suspend fun persistStart(lease: PhysicalRecordingNativeLease, start: PhysicalRecordingCommittedStart): PhysicalRecordingNativeCheck =
        engine.persistStart(lease, start)
    public suspend fun release(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeRelease = engine.release(lease)
    public suspend fun prepareInstruction(binding: PhysicalInstructionNativeBinding, custody: LocalPhysicalExternalCustody?): PhysicalNativeInstructionPreparation =
        engine.prepareInstruction(binding, custody)
    public suspend fun releaseInstruction(prepared: PhysicalNativeInstructionLease): PhysicalRecordingNativeRelease = engine.releaseInstruction(prepared)
    public fun adoptPublished(published: PhysicalPublishedInstruction, original: PhysicalInteractiveRequestLifetime): LocalPhysicalPublishedInstruction? =
        engine.adoptPublished(published, original)
    public fun installedIntake(intake: NativeOriginalIntake): Boolean = engine.installedIntake(intake)
    public suspend fun acquireOriginal(invocation: NativeOriginalIntakeInvocation): NativeOriginalAcquire = engine.acquireOriginal(invocation)
    public suspend fun recheckOriginal(lease: NativeOriginalRetainedLease): PhysicalRecordingNativeCheck = engine.recheckOriginal(lease)
    public suspend fun releaseOriginal(lease: NativeOriginalRetainedLease, disposition: NativeOriginalIntakeDisposition): PhysicalRecordingNativeRelease =
        engine.releaseOriginal(lease, disposition)
    public suspend fun enqueueRetainedOriginals(): Unit = engine.enqueueRetainedOriginals()
    public suspend fun redeliverRetainedOriginal(clientAllocationId: DomainId): LocalPhysicalDelivery = engine.redeliverRetainedOriginal(clientAllocationId)
    public suspend fun redeliverRetainedResolution(clientAllocationId: DomainId): LocalPhysicalDelivery = engine.redeliverRetainedResolution(clientAllocationId)
    public fun denyNewWork(): Unit = engine.denyNewWork()
    public suspend fun closeOwnedGate(): PhysicalRecordingNativeRelease = engine.closeOwnedGate()
    override fun toString(): String = "LocalOriginalPhysicalJournalBackend([REDACTED])"
}

/** Internal cleanup state; none of its results authorizes an employee. Unknown native or loan
 * cleanup retains the actual upper loan. A certain complete native-owner close may resolve a
 * native-only uncertainty; an uncertain release call is never automatically retried. */
internal class PhysicalJournalExternalCustodyOwnership(val custody: LocalPhysicalExternalCustody) {
    private enum class State { OWNED, NATIVE_UNCERTAIN, RELEASING, RELEASED, RELEASE_UNCERTAIN }
    private val state = MutableStateFlow(State.OWNED)
    private var invocationDenial: LocalPhysicalExternalDenialRegistration? = null
    val denied: Boolean get() = custody.denied || custody.lifetimeDenied
    val lifetimeDenied: Boolean get() = custody.lifetimeDenied
    val released: Boolean get() = state.value == State.RELEASED
    val cleanupCertain: Boolean get() = state.value == State.OWNED || state.value == State.RELEASED
    val cleanupUncertain: Boolean get() = state.value == State.NATIVE_UNCERTAIN || state.value == State.RELEASE_UNCERTAIN
    fun observeInvocation(onDenial: () -> Unit) {
        check(invocationDenial == null)
        invocationDenial = custody.observeLifetimeDenial(onDenial)
        if (denied) onDenial()
    }
    fun deny() {
        try { custody.deny() } catch (_: Exception) {
            while (true) {
                val selected = state.value
                if (selected == State.RELEASED || selected == State.RELEASE_UNCERTAIN ||
                    state.compareAndSet(selected, State.RELEASE_UNCERTAIN)) break
            }
        }
    }
    fun retainNativeUncertainty() {
        state.compareAndSet(State.OWNED, State.NATIVE_UNCERTAIN)
        deny()
    }
    fun releaseAfterNativeCleanup(allNativeClosed: Boolean = false): Boolean {
        while (true) {
            val selected = state.value
            if (selected == State.RELEASED) return true
            if (selected != State.OWNED && !(allNativeClosed && selected == State.NATIVE_UNCERTAIN)) return false
            if (state.compareAndSet(selected, State.RELEASING)) break
        }
        val complete = try {
            invocationDenial?.release()
            invocationDenial = null
            custody.release()
        } catch (_: Exception) { false }
        val published = state.compareAndSet(State.RELEASING, if (complete) State.RELEASED else State.RELEASE_UNCERTAIN)
        return complete && published
    }
}
