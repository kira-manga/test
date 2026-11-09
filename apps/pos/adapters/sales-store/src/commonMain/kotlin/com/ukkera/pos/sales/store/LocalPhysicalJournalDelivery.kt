package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.OriginalPhysicalEmployeeEnrollment
import com.ukkera.pos.database.NativeOriginalAcquire
import com.ukkera.pos.database.NativeOriginalIntake
import com.ukkera.pos.database.NativeOriginalIntakeInvocation
import com.ukkera.pos.database.NativeOriginalIntakeDisposition
import com.ukkera.pos.database.NativeOriginalIntakeLocator
import com.ukkera.pos.database.NativeOriginalIntakeSubmission
import com.ukkera.pos.database.NativeOriginalRetained
import com.ukkera.pos.database.NativeOriginalRetainedLease
import com.ukkera.pos.database.PhysicalInstructionNativeBinding
import com.ukkera.pos.database.PhysicalInteractiveRequestLifetime
import com.ukkera.pos.database.PhysicalNativeInstructionLease
import com.ukkera.pos.database.PhysicalNativeInstructionPreparation
import com.ukkera.pos.database.PhysicalPublishedInstruction
import com.ukkera.pos.database.PhysicalRecordingCommittedStart
import com.ukkera.pos.database.PhysicalRecordingAction
import com.ukkera.pos.database.PhysicalRecordingAcquire
import com.ukkera.pos.database.PhysicalRecordingInvocationBinding
import com.ukkera.pos.database.PhysicalRecordingNativeCheck
import com.ukkera.pos.database.PhysicalRecordingNativeLatch
import com.ukkera.pos.database.PhysicalRecordingNativeLease
import com.ukkera.pos.database.PhysicalRecordingNativeRelease
import com.ukkera.pos.database.PhysicalRecordingParticipants
import com.ukkera.pos.database.PhysicalRecordingServiceLifetime
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalEmployeeEnrollmentProfileV1
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public sealed interface LocalPhysicalJournalLeaseOpen {
    public class Acquired internal constructor(public val lease: LocalPhysicalJournalInvocationLease) : LocalPhysicalJournalLeaseOpen
    public data object Held : LocalPhysicalJournalLeaseOpen
}

/**
 * A real native-owned invocation sample. The kernel still creates its OWN internal ticket and
 * compares its exact invocation object, requirement, planned SQL contributions and origin. The
 * loan checks only its FINAL denying latch; it never invokes this object's IO methods in Room.
 */
public class LocalPhysicalJournalInvocationLease internal constructor(
    public val invocationBinding: PhysicalRecordingInvocationBinding,
    public val retirementLatch: PhysicalRecordingNativeLatch,
    internal val master: LocalPhysicalJournal,
    internal val client: LocalPhysicalJournal,
    ownership: List<PhysicalSpoolLease>,
) {
    private val nativeOwnership = PhysicalJournalLeaseOwnership(ownership)
    public val requirementBytes: StoredBytes = master.receipt.binding.requirement.canonicalBytes
    public val masterReceipt: LocalPhysicalJournalReceipt = master.receipt
    public val clientReceipt: LocalPhysicalJournalReceipt = client.receipt
    public val masterSourceBody: StoredBytes = master.receipt.sourceCommitment
    public val clientSourceBody: StoredBytes = client.receipt.sourceCommitment

    /** Real key/header/namespace/descriptor force/readback. OUTSIDE the borrowed transaction only. */
    public fun recheck(): Boolean {
        // The pair helper owns both catalogue lists and both journal leases before flattening.
        var temporary: List<PhysicalSpoolLease> = emptyList()
        var complete = false
        try {
            if (retirementLatch !== invocationBinding.latch || retirementLatch.denied || !samePhysicalPair(master, client)) return false
            // New physical work requires every promised future cell in BOTH actual owners.
            // Existing original/resolution retention keeps the separate tolerant custody path.
            val start = invocationBinding.admission.action.isJournalStart()
            temporary = captureLocalPairNative(master, client, newAction = start) { retirementLatch.deny() }
                ?: return false
            complete = !retirementLatch.denied
            return complete
        } finally {
            if (!complete) retirementLatch.deny()
            if (!releasePhysicalSpoolLeases(temporary)) {
                retirementLatch.deny()
                error("physical.journal.lease_cleanup")
            }
        }
    }

    /** Certificate retention survives ordinary invocation denial; it does not grant UI rights. */
    internal fun recheckRetainedCustody(): Boolean {
        val sample = PhysicalJournalDenial()
        val temporary = captureLocalPairNative(master, client) { sample.deny(); retirementLatch.deny() }
            ?: return false
        return try { !sample.denied } finally {
            if (!releasePhysicalSpoolLeases(temporary)) {
                retirementLatch.deny()
                error("physical.journal.lease_cleanup")
            }
        }
    }

    /** Deny first; release captured ownership only. The service owns actual native close/retirement. */
    public fun release(): Unit {
        retirementLatch.deny()
        nativeOwnership.release()
        check(nativeOwnership.released) { "physical.journal.lease_cleanup" }
    }

    override fun toString(): String = "LocalPhysicalJournalInvocationLeaseV1([REDACTED])"
}

public sealed interface LocalPhysicalBothStartWrite {
    public class Fresh internal constructor(internal val lease: LocalPhysicalJournalInvocationLease,
        internal val committedStart: PhysicalRecordingCommittedStart,
        internal val master: LocalPhysicalStartWrite.Fresh,
        internal val client: LocalPhysicalStartWrite.Fresh) : LocalPhysicalBothStartWrite
    /** Both certificates were forced; historical/cancelled/capacity-denied delivery cannot arm. */
    public data object Retained : LocalPhysicalBothStartWrite
    /** Historical or uncertain start remains an obligation, never a new instruction capability. */
    public data object Held : LocalPhysicalBothStartWrite
}

/**
 * Native-owner continuation, NOT an interactive capability. Only the installed interactive SPI
 * may burn its INTERNAL take, using the SAME genuine committed Start, current core authorization
 * and one-shot publication into the SAME original lifetime. No public take, decoder, Boolean
 * grant or recovery/rearming factory exists.
 *
 * Normal kernel invocation release does not cancel this distinct lifetime. Store retirement,
 * native poison/close or explicit cancellation does. Both durable consumed markers already
 * exist before construction. Owned native leases are independent from the completed invocation.
 */
public class LocalPhysicalActionContinuation internal constructor(
    private val masterStart: LocalPhysicalStartWrite.Fresh,
    private val clientStart: LocalPhysicalStartWrite.Fresh,
    private val committedStart: PhysicalRecordingCommittedStart,
    private val service: PhysicalRecordingServiceLifetime,
    private val denial: PhysicalJournalDenial,
    private val originatingJob: Job,
    ownership: List<PhysicalSpoolLease>,
    origin: PhysicalRecordingInvocationBinding,
    private val employeeLifetime: LocalPhysicalExternalCustody?,
) {
    private val taken = MutableStateFlow(false)
    private val ownership = PhysicalJournalLeaseOwnership(ownership)
    private val cleanupUncertain = MutableStateFlow(false)
    // Copy scalars now; do NOT retain an invocation/principal, borrow, bearer or decision.
    private val admissionRevision = origin.admissionRevision
    private val storeIncarnationId = origin.storeIncarnationId
    private val serviceIncarnationId = origin.serviceIncarnationId
    private val invocationId = origin.invocationId
    internal val cleanupCertain: Boolean get() = !cleanupUncertain.value && ownership.cleanupCertain && employeeDenial.cleanupCertain
    /** No remaining instruction rights or native leases; never inferred from IDs or a timeout. */
    internal val retired: Boolean get() = denied && cleanupCertain && ownership.released && employeeDenial.released
    internal val nativeRequirement: LocalPhysicalJournalRequirementV1 = clientStart.journal.receipt.binding.requirement
    public val scope: BranchScope = service.scope
    public val originalActorId: DomainId = service.originalActorId
    public val clientDeviceId: DomainId = service.clientDeviceId
    public val authenticationId: DomainId = service.authenticationId
    public val sessionGeneration: SessionGeneration = service.sessionGeneration
    public val operationId: OperationId = service.operationId
    // The parent view, NOT the completed invocation loan's released flag, denies fresh B4.
    private val employeeDenial = PhysicalJournalDenialOwnership()
    init {
        employeeDenial.attach(employeeLifetime?.observeLifetimeDenial { deny() })
        if (employeeLifetime?.lifetimeDenied == true) deny()
    }
    public val denied: Boolean get() = denial.denied || service.denied || originatingJob.isCancelled ||
        employeeLifetime?.lifetimeDenied == true

    /** Synchronous denial and memory-only lease release; actual FD close belongs to the owner. */
    public fun deny() {
        denial.deny()
        service.deny()
        ownership.release()
        employeeDenial.release()
    }

    /**
     * Association, not authorization. Equal IDs/certificate bytes never substitute for the SAME
     * core object and the one service lifetime already captured on its first native delivery.
     */
    internal fun matchesInstruction(binding: PhysicalInstructionNativeBinding): Boolean =
        binding.committedStart === committedStart && binding.canonicalRequirement == nativeRequirement.canonicalBytes &&
            committedStart.requirementBytes == nativeRequirement.canonicalBytes && binding.scope == scope &&
            binding.originalActorId == originalActorId && binding.clientDeviceId == clientDeviceId &&
            binding.authenticationId == authenticationId && binding.sessionGeneration == sessionGeneration &&
            binding.admissionRevision == admissionRevision && binding.operationId == operationId &&
            binding.authority == committedStart.authority && binding.storeIncarnationId == storeIncarnationId &&
            binding.serviceIncarnationId == serviceIncarnationId && binding.invocationId == invocationId

    /** Distinct real native ownership for temporary preparation OR evidence; no public IO port. */
    internal fun pinsJournal(journal: LocalPhysicalJournal): Boolean =
        masterStart.journal === journal || clientStart.journal === journal

    internal fun captureInstructionOwnership(onRetirement: () -> Unit): List<PhysicalSpoolLease>? {
        if (denied) return null
        return captureLocalPairNative(masterStart.journal, clientStart.journal, newAction = true) {
            denial.deny(); service.deny(); onRetirement()
        }
    }

    /** Pure installed-domain validation for synchronous immutable original-event capture. */
    internal fun validateCapturedOriginal(body: StoredBytes) {
        require(body.size in 1..LocalPhysicalJournalFormatV1.MAX_ORIGINAL_BYTES)
        clientStart.journal.domain.classifyOriginal(nativeRequirement, body)
        masterStart.journal.domain.classifyOriginal(nativeRequirement, body)
    }

    /** Accepted end-only evidence outlives interaction rights; never rechecks an employee here. */
    internal fun retainCapturedOriginal(body: StoredBytes): LocalPhysicalDelivery =
        LocalPhysicalJournalDelivery.captureAndDeliverLocalOriginal(clientStart.journal, masterStart.journal,
            nativeRequirement.recordingOperationId, body)

    /** Worker-only, one-shot native preparation. NOT payment completion or result disclosure. */
    internal fun takeInstruction(): LocalPhysicalJournalRequirementV1? {
        if (!taken.compareAndSet(false, true)) return null
        var complete = false
        var temporary: List<PhysicalSpoolLease> = emptyList()
        try {
            if (denied) return null
            temporary = captureLocalPairNative(masterStart.journal, clientStart.journal, newAction = true) { denial.deny(); service.deny() }
                ?: return null
            if (denied || !masterStart.journal.takeInstruction(masterStart) ||
                !clientStart.journal.takeInstruction(clientStart) || denied) return null
            complete = true
            return clientStart.journal.receipt.binding.requirement
        } finally {
            if (!releasePhysicalSpoolLeases(temporary)) {
                cleanupUncertain.value = true
                deny()
                error("physical.journal.lease_cleanup")
            }
            if (!complete) deny()
        }
    }
    override fun toString(): String = "LocalPhysicalActionContinuationV1([REDACTED])"
}

/**
 * Exact native-retained master evidence with immutable original origin, NEVER financial/result
 * authority. Created only after real force/readback, not from a wire decoder or a public receipt.
 * The separate authenticated native-owner/pairing route may deliver these SAME original bytes
 * after employee rights loss; it must not impersonate/mint an employee principal. Import,
 * application, resolver authority and disclosure are separately gated. The certificate only
 * describes the committed intent, never attests that a physical outcome is true or applied.
 */
public class LocalPhysicalRawRetention internal constructor(
    original: LocalPhysicalOriginalSnapshot,
    public val linkedResolution: LocalPhysicalLinkedResolution?,
) {
    public val masterReceipt: LocalPhysicalJournalReceipt = original.receipt
    public val requirement: LocalPhysicalJournalRequirementV1 = masterReceipt.binding.requirement
    public val certificate: StoredBytes = requireNotNull(original.certificate)
    public val originalBody: StoredBytes = requireNotNull(original.original)
    public val originalOperationId: OperationId = requirement.recordingOperationId
    public val originalDigest: PayloadDigest = journalBodyDigest(originalBody)
    public val resolution: Boolean = linkedResolution != null
    public val operationId: OperationId = linkedResolution?.resolutionOperationId ?: originalOperationId
    public val body: StoredBytes = linkedResolution?.body ?: originalBody
    public val bodyDigest: PayloadDigest = journalBodyDigest(body)
    public val bodySemanticDigest: PayloadDigest = semanticDigest(requirement.scope,
        linkedResolution?.resolverActorId ?: requirement.originalActorId, requirement.command, body)

    init {
        require(masterReceipt.binding.owner.role == LocalPhysicalSpoolRole.MASTER && original.actionMayHaveStarted)
        linkedResolution?.let { linked -> require(original.originalKind == LocalPhysicalOriginalKind.UNKNOWN &&
            linked.originalOperationId == originalOperationId && linked.originalDigest == originalDigest &&
            linked.originalActorId == requirement.originalActorId && linked.attemptId == requirement.attemptId &&
            linked.resolutionOperationId == requirement.resolutionOperationId) }
    }
    override fun toString(): String = "LocalPhysicalRawRetentionV1([REDACTED])"
}

public sealed interface LocalPhysicalDelivery {
    public class MasterRawRetained internal constructor(public val retention: LocalPhysicalRawRetention) : LocalPhysicalDelivery
    /** Client has exact evidence; master/SQL outcome is not asserted. */
    public class ClientRetained internal constructor(public val operationId: OperationId,
        public val bodyDigest: PayloadDigest) : LocalPhysicalDelivery
    public data object Held : LocalPhysicalDelivery
}

/**
 * Concrete two-local-role orchestration, including the legitimate one-device product mode.
 * Each object owns a REAL local FD; a remote role cannot be replaced by another master file or a
 * proxy asserting success. Distributed pairing must deliver the same opaque core start/evidence
 * through its genuinely authenticated owner route and persist each role before acknowledging.
 * This class does not invent that transport or signing algorithm, perform SQL, or grant cashier,
 * disclosure or resolver privileges. Those remain the mandatory kernel/gateway composition.
 */
public object LocalPhysicalJournalDelivery {
    public fun acquireLocalPair(invocationBinding: PhysicalRecordingInvocationBinding,
        retirementLatch: PhysicalRecordingNativeLatch, master: LocalPhysicalJournal,
        client: LocalPhysicalJournal): LocalPhysicalJournalLeaseOpen {
        var leases: List<PhysicalSpoolLease> = emptyList()
        var transferred = false
        try {
            if (retirementLatch !== invocationBinding.latch || retirementLatch.denied ||
                !samePhysicalPair(master, client) || !matchesInvocation(invocationBinding, master, client)) return LocalPhysicalJournalLeaseOpen.Held
            val start = invocationBinding.admission.action.isJournalStart()
            // ALLOCATED state and complete authenticated vacancy/force/readback belong to the
            // SAME native access, including catalogue-reopened journals; no stale DTO shortcut.
            // Retain the complete returned list immediately, before constructing the public loan.
            leases = captureLocalPairNative(master, client, newAction = start, allocatedOnly = start) {
                retirementLatch.deny()
            } ?: return LocalPhysicalJournalLeaseOpen.Held
            if (retirementLatch.denied) return LocalPhysicalJournalLeaseOpen.Held
            // Keep the SAME actual native captures across client-first retention and master
            // force/readback. A current authenticated resolver supplies this genuine binding,
            // not an accepting descriptor/actor callback or a UI write into raw journals.
            when (invocationBinding.admission.action) {
                PhysicalRecordingAction.RETAIN_ORIGINAL ->
                    if (deliverLocalOriginal(client, master) !is LocalPhysicalDelivery.MasterRawRetained) return LocalPhysicalJournalLeaseOpen.Held
                PhysicalRecordingAction.RETAIN_RESOLUTION ->
                    if (!retainAuthorizedLocalResolution(invocationBinding, client, master)) return LocalPhysicalJournalLeaseOpen.Held
                PhysicalRecordingAction.START_PAYMENT, PhysicalRecordingAction.START_REFUND -> Unit
            }
            if (retirementLatch.denied || !matchesInvocation(invocationBinding, master, client)) return LocalPhysicalJournalLeaseOpen.Held
            val value = LocalPhysicalJournalInvocationLease(invocationBinding, retirementLatch, master, client, leases.toList())
            val acquired = LocalPhysicalJournalLeaseOpen.Acquired(value)
            transferred = true
            return acquired
        } finally {
            if (!transferred) {
                retirementLatch.deny()
                check(releasePhysicalSpoolLeases(leases)) { "physical.journal.lease_cleanup" }
            }
        }
    }

    /** End-only current-supervisor observation. The original UNKNOWN and custody never change. */
    private fun retainAuthorizedLocalResolution(binding: PhysicalRecordingInvocationBinding,
        client: LocalPhysicalJournal, master: LocalPhysicalJournal): Boolean {
        if (binding.admission.action != PhysicalRecordingAction.RETAIN_RESOLUTION || binding.latch.denied ||
            !samePhysicalPair(master, client) || !matchesInvocation(binding, master, client)) return false
        val original = (deliverLocalOriginal(client, master) as? LocalPhysicalDelivery.MasterRawRetained)?.retention ?: return false
        val requirement = original.requirement
        val descriptor = localPhysicalResolutionDescriptor(master.domain, requirement, original.originalBody,
            binding.originalActorId, binding.normalized)
        if (binding.latch.denied || !client.retainResolution(requirement.resolutionOperationId,
                binding.originalActorId, descriptor, binding.normalized).retained()) return false
        val retained = (deliverLocalResolution(client, master) as? LocalPhysicalDelivery.MasterRawRetained)?.retention ?: return false
        return retained.resolution && retained.operationId == binding.operationId && retained.body == binding.normalized &&
            retained.bodySemanticDigest == binding.semanticDigest && retained.linkedResolution?.let {
                it.resolverActorId == binding.originalActorId && it.resolverAuthorization == descriptor &&
                    it.originalDigest == original.originalDigest
            } == true
    }

    /** Master forced BEFORE client delivery. A repeated/late certificate never returns Fresh. */
    public fun persistLocalPairStart(lease: LocalPhysicalJournalInvocationLease,
        certificate: PhysicalRecordingCommittedStart): LocalPhysicalBothStartWrite {
        if (lease.invocationBinding.admission.action !in setOf(PhysicalRecordingAction.START_PAYMENT, PhysicalRecordingAction.START_REFUND) ||
            lease.invocationBinding.operationId != certificate.operationId ||
            lease.invocationBinding.authority != certificate.authority || !lease.recheckRetainedCustody()) return LocalPhysicalBothStartWrite.Held
        val freshInvocation = lease.recheck()
        // Genuine committed obligation retention is independent of ordinary invocation release.
        // Capacity denial also must not suppress either certificate. Only the separate Fresh
        // branch can ever capture an ephemeral client continuation.
        val master = lease.master.persistTrueStart(certificate)
        if (!master.retained()) return LocalPhysicalBothStartWrite.Held
        val client = lease.client.persistTrueStart(certificate)
        if (!client.retained() || !lease.recheckRetainedCustody()) return LocalPhysicalBothStartWrite.Held
        // Propagate capacity denial even when one role retained without a Fresh right. This
        // native fresh recheck cannot undo the already-forced certificates or retained custody.
        val freshAfterRetention = lease.recheck()
        return if (freshInvocation && master is LocalPhysicalStartWrite.Fresh && client is LocalPhysicalStartWrite.Fresh && freshAfterRetention)
            LocalPhysicalBothStartWrite.Fresh(lease, certificate, master, client) else LocalPhysicalBothStartWrite.Retained
    }

    /** Both durable markers precede construction; only a real first delivery has this right. */
    internal fun consumeLocalPairStart(start: LocalPhysicalBothStartWrite.Fresh,
        originatingJob: Job, employeeLifetime: LocalPhysicalExternalCustody? = null): LocalPhysicalActionContinuation? {
        if (!originatingJob.isActive || employeeLifetime?.lifetimeDenied == true || !start.lease.recheck()) return null
        val service = start.committedStart.captureServiceLifetime() ?: return null
        val binding = start.lease.invocationBinding
        val denial = PhysicalJournalDenial()
        var ownership: List<PhysicalSpoolLease> = emptyList()
        var transferred = false
        try {
            if (!originatingJob.isActive || service.denied || service.scope != binding.scope || service.originalActorId != binding.originalActorId ||
                service.clientDeviceId != binding.clientDeviceId || service.authenticationId != binding.authenticationId ||
                service.sessionGeneration != binding.sessionGeneration || service.operationId != binding.operationId) return null
            ownership = captureLocalPairNative(start.lease.master, start.lease.client) { denial.deny(); service.deny() }
                ?: return null
            if (!originatingJob.isActive || denial.denied || service.denied || !start.lease.recheck()) return null
            if (start.master.journal.consumeFresh(start.master) != LocalPhysicalJournalWrite.RETAINED) return null
            if (start.client.journal.consumeFresh(start.client) != LocalPhysicalJournalWrite.RETAINED) return null
            if (!originatingJob.isActive || denial.denied || service.denied || !start.lease.recheck()) return null
            val value = LocalPhysicalActionContinuation(start.master, start.client, start.committedStart,
                service, denial, originatingJob, ownership.toList(), binding, employeeLifetime)
            transferred = true
            return value
        } finally {
            if (!transferred) {
                denial.deny()
                service.deny()
                check(releasePhysicalSpoolLeases(ownership)) { "physical.journal.lease_cleanup" }
            }
        }
    }

    /** Actual original client force/readback precedes offering the SAME original bytes to master. */
    public fun captureAndDeliverLocalOriginal(client: LocalPhysicalJournal, master: LocalPhysicalJournal,
        operationId: OperationId, body: StoredBytes): LocalPhysicalDelivery {
        if (!samePhysicalPair(master, client)) return LocalPhysicalDelivery.Held
        if (!client.retainOriginal(operationId, body).retained()) return LocalPhysicalDelivery.Held
        return deliverLocalOriginal(client, master)
    }

    /** Service-owned retry of already captured evidence; no current-cashier impersonation. */
    public fun deliverLocalOriginal(client: LocalPhysicalJournal, master: LocalPhysicalJournal): LocalPhysicalDelivery {
        val snapshot = (client.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot ?: return LocalPhysicalDelivery.Held
        val body = snapshot.original ?: return LocalPhysicalDelivery.Held
        val digest = snapshot.originalDigest ?: return LocalPhysicalDelivery.Held
        val operation = client.receipt.binding.requirement.recordingOperationId
        val pending = LocalPhysicalDelivery.ClientRetained(operation, digest)
        if (!samePhysicalPair(master, client)) return pending
        val masterBefore = (master.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot ?: return pending
        if (!snapshot.actionMayHaveStarted || !masterBefore.actionMayHaveStarted ||
            snapshot.certificate == null || masterBefore.certificate != snapshot.certificate) return pending
        if (!master.retainOriginal(operation, body).retained()) return pending
        val retained = (master.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot ?: return pending
        if (retained.original != body || retained.originalDigest != digest) return pending
        return LocalPhysicalDelivery.MasterRawRetained(LocalPhysicalRawRetention(retained, null))
    }

    /** Original UNKNOWN is retained on master FIRST; linked resolution never rewrites or charges. */
    public fun deliverLocalResolution(client: LocalPhysicalJournal, master: LocalPhysicalJournal): LocalPhysicalDelivery {
        val original = deliverLocalOriginal(client, master)
        val resolution = (client.readResolution() as? LocalPhysicalResolutionRead.Read)?.resolution
            ?: return LocalPhysicalDelivery.Held
        val pending = LocalPhysicalDelivery.ClientRetained(resolution.resolutionOperationId, resolution.bodyDigest)
        if (original !is LocalPhysicalDelivery.MasterRawRetained) return pending
        if (!master.retainResolution(resolution.resolutionOperationId, resolution.resolverActorId,
                resolution.resolverAuthorization, resolution.body).retained()) return pending
        val retained = (master.readResolution() as? LocalPhysicalResolutionRead.Read)?.resolution ?: return pending
        if (retained.originalDigest != resolution.originalDigest || retained.body != resolution.body ||
            retained.resolverActorId != resolution.resolverActorId || retained.resolverAuthorization != resolution.resolverAuthorization) return pending
        val originalSnapshot = (master.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot ?: return pending
        if (originalSnapshot.original != original.retention.originalBody || originalSnapshot.originalDigest != resolution.originalDigest)
            return pending
        return LocalPhysicalDelivery.MasterRawRetained(LocalPhysicalRawRetention(originalSnapshot, retained))
    }
}

/**
 * Native catalogue/journal engine only. It implements NO authorizing SPI. The registered
 * execution owner is either the existing generic retail facade or the gateway-owned original
 * Android wrapper; only that upper wrapper can join a successful typed employee journal.
 * This is not the old capacity-reader SPI, an accepting lambda or an RPC-selected provider. The retail
 * factory installs exactly the two v3 domain codecs; food/exchange/remote routes stay unbound.
 *
 * All native phases run on service workers, outside the kernel's Room loan. Dispatchers are
 * trusted threading configuration, never storage authority. Event custody has a distinct clean
 * service root, NOT a child of an employee/UI/borrowed-connection or publication coroutine.
 * Cancellation after acquisition but before delivery releases the undelivered invocation lease;
 * native obligations stay in the protected catalogue. No reset, eviction, orphan adoption or
 * reclaim is provided. The 128-slot lifetime cut is bounded, NOT sustained production operation.
 *
 * The interactive SPI binds only genuine core publication to the original private continuation.
 * Native qualification and broader history recovery remain separate. Positive SQL recovery
 * never depends on native files. The original Android recording lane stays held until its actual
 * original master installs the two native catalogue owners on this SAME registered object.
 */
internal class PhysicalJournalNativeBackend private constructor(
    private val authorityOwner: LocalPhysicalJournalExecutionOwner,
    owners: PhysicalJournalGateNativeOwners?,
    private val nativeDispatcher: CoroutineDispatcher,
    evidenceDispatcher: CoroutineDispatcher,
    private val domains: List<LocalPhysicalJournalDomain>,
    private val requiresOriginalAndroidOwners: Boolean,
) {
    private val mutex = Mutex()
    private val nativeOwners = MutableStateFlow(owners)
    private val employeeHistory = MutableStateFlow<List<OriginalPhysicalEmployeeEnrollment>>(emptyList())
    private val masterCatalogue: LocalPhysicalAllocationCatalogue get() = checkNotNull(nativeOwners.value).master
    private val clientCatalogue: LocalPhysicalAllocationCatalogue get() = checkNotNull(nativeOwners.value).client
    // Retain partial acquisition before native IO. Unknown close cannot turn it into vacancy.
    private var nativeOwnerAttempt: PhysicalJournalGateOwnerAttempt? = null
    private var unreturnedJournalOwner = false
    private var journalAwaitingTransfer: LocalPhysicalJournal? = null
    private val intake = MutableStateFlow<NativeOriginalIntake?>(null)
    private val intakeEntries = MutableStateFlow<List<LocalNativeOriginalIntakeEntry>>(emptyList())
    // Cursor is only fair scheduling state; it confers no origin, absence, capacity or progress.
    private var intakeAfter: DomainId? = null
    private val ownerDenial = PhysicalJournalDenial()
    private val evidenceRoot = SupervisorJob()
    private val evidenceScope = CoroutineScope(evidenceRoot + evidenceDispatcher)
    private val masterJournals = mutableMapOf<DomainId, LocalPhysicalJournal>()
    private val clientJournals = mutableMapOf<DomainId, LocalPhysicalJournal>()
    private val invocations = MutableStateFlow<List<PhysicalJournalGateEntry>>(emptyList())
    private val continuations = MutableStateFlow<List<LocalPhysicalActionContinuation>>(emptyList())
    private val originalCaptures = MutableStateFlow<List<LocalPhysicalOriginalCaptureEntry>>(emptyList())
    // Exact returned-object identities retain idempotent cleanup without pinning native journals.
    // One instruction can be consumed per immutable allocation in this bounded catalogue family.
    private val retiredPreparations = mutableListOf<PhysicalNativeInstructionLease>()
    // Registered before first native IO. Uncertain native/loan cleanup retains these real
    // upper journal loans; employee close may not release its OS/key/process lease first.
    private val externalCustodies = MutableStateFlow<List<PhysicalJournalExternalCustodyOwnership>>(emptyList())
    internal companion object {
        /**
         * Exclusive service ownership of real catalogues. The evidence dispatcher must remain
         * available until close returns CLOSED, even after logout/cancel or a HELD close. It is
         * configuration, not proof of durable custody, platform support or sustainable capacity.
         */
        fun forRetail(authorityOwner: LocalPhysicalJournalExecutionOwner, master: LocalPhysicalAllocationCatalogue, client: LocalPhysicalAllocationCatalogue,
            nativeDispatcher: CoroutineDispatcher, evidenceDispatcher: CoroutineDispatcher): PhysicalJournalNativeBackend {
            requireLocalCataloguePair(master, client)
            return PhysicalJournalNativeBackend(authorityOwner, PhysicalJournalGateNativeOwners(master, client, null),
                nativeDispatcher, evidenceDispatcher, listOf(LocalRetailPaymentJournalDomain, LocalRetailRefundJournalDomain), false)
        }

        /**
         * Installed only by the SAME genuine Android original owner. Refund admission still
         * requires the actual already accepted retail obligation, original tender entitlement
         * and today's paying custody. This domain set creates no accepted return, original
         * rights/delivery qualification, capacity permit or FOOD refund authority.
         */
        fun awaitingOriginalAndroidRecording(authorityOwner: LocalPhysicalJournalExecutionOwner, nativeDispatcher: CoroutineDispatcher,
            evidenceDispatcher: CoroutineDispatcher): PhysicalJournalNativeBackend =
            PhysicalJournalNativeBackend(authorityOwner, null, nativeDispatcher, evidenceDispatcher,
                listOf(LocalRetailPaymentJournalDomain, LocalRetailRefundJournalDomain, LocalFoodCashJournalDomain,
                    LocalFoodRefundCashJournalDomain, LocalSupplierCashJournalDomain), true)
    }

    /**
     * Pure installed native metadata; the registered upper owner separately admits the actor. Both command prepares may
     * call this in a borrowed Room transaction: no native capture, IO, capacity observation,
     * mutex, dispatcher switch, original-service callback or caller-selected owner is involved.
     * Null before enrollment and after one-way denial never qualifies missing physical backing.
     * Actual native custody is independently acquired/rechecked outside the loan by the kernel.
     */
    public suspend fun current(view: CommandReadView,
        participants: PhysicalRecordingParticipants): LocalRetailJournalParticipants? {
        if (ownerDenial.denied) return null
        val selected = nativeOwners.value ?: return null
        val installed = selected.participants
        if (installed.masterOwner.scope != view.scope || installed.clientOwner.scope != view.scope ||
            installed.masterOwner.deviceId != participants.authority.deviceId ||
            installed.clientOwner.deviceId != participants.clientDeviceId) return null
        val original = selected.original
        if (requiresOriginalAndroidOwners && original == null) return null
        if (original != null && (original.authority != participants.authority ||
                installed.masterOwner != original.master || installed.clientOwner != original.client)) return null
        return installed.takeIf { !ownerDenial.denied && nativeOwners.value === selected }
    }

    /**
     * Module-private actual Android enrollment/reopen. The owner factory captures every opened
     * native catalogue in attempt BEFORE further IO. Registration and close share this mutex,
     * so master retirement cannot close the original native owner while enrollment is running.
     * The callback is installed native code only, never RPC/user-provided custody authority.
     */
    internal suspend fun installOriginalAndroidOwners(identity: OriginalAndroidPhysicalOwnerIdentity,
        history: List<OriginalPhysicalEmployeeEnrollment>, acquire: (PhysicalJournalGateOwnerAttempt) -> Boolean): Boolean = withContext(nativeDispatcher) {
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            if (!requiresOriginalAndroidOwners || ownerDenial.denied || nativeOwnerAttempt != null) return@withLock false
            if (!history.all { matchesOriginalEmployee(it, identity) }) return@withLock false
            val current = nativeOwners.value
            if (current != null) {
                if (current.original != identity || !recheckCatalogueOwners(current) || ownerDenial.denied)
                    return@withLock false
                // Existing-only reacquisition refreshes genuine consumed lineage without
                // allocating catalogues/journals or supplying any present employee grant.
                mergeEmployeeHistory(history)
                return@withLock !ownerDenial.denied
            }
            val attempt = PhysicalJournalGateOwnerAttempt()
            nativeOwnerAttempt = attempt
            var transferred = false
            try {
                if (!acquire(attempt) || ownerDenial.denied || attempt.journals.isNotEmpty() ||
                    attempt.nativePreparation != null || attempt.unreturnedNativeOwner)
                    return@withLock false
                val master = checkNotNull(attempt.master)
                val client = checkNotNull(attempt.client)
                requireLocalCataloguePair(master, client)
                require(master.binding == identity.master && client.binding == identity.client)
                val selected = PhysicalJournalGateNativeOwners(master, client, identity)
                if (!recheckCatalogueOwners(selected) || ownerDenial.denied) return@withLock false
                mergeEmployeeHistory(history)
                nativeOwners.value = selected
                nativeOwnerAttempt = null
                transferred = true
                true
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { false }
            finally {
                if (!transferred) {
                    if (attempt.close() == LocalPhysicalSpoolClose.CLOSED) nativeOwnerAttempt = null
                    else deny()
                }
            }
        }
    }

    /** Immutable consumed-history census, never a present participant/journal permission. */
    private fun mergeEmployeeHistory(history: List<OriginalPhysicalEmployeeEnrollment>) {
        var selected = employeeHistory.value
        for (witness in history) {
            val previous = selected.singleOrNull { it.actorId == witness.actorId }
            if (previous == null) {
                check(selected.size < LocalEmployeeEnrollmentProfileV1.MAXIMUM_ACTIONS)
                selected = selected + witness
            } else check(previous.sameOriginalEnrollment(witness))
        }
        employeeHistory.value = selected
    }

    private fun matchesOriginalEmployee(witness: OriginalPhysicalEmployeeEnrollment,
        original: OriginalAndroidPhysicalOwnerIdentity): Boolean = witness.belongsToOriginalOwner(original.originalClientOwner) &&
        witness.authority == original.authority && witness.receiptDigest == original.receiptDigest &&
        witness.firstActorId == original.firstActorId && witness.actorId != original.firstActorId &&
        witness.scope == original.master.scope && witness.deviceId == original.client.deviceId &&
        witness.installationId == original.master.installationId

    internal fun belongsToOriginalOwner(original: Any): Boolean = !ownerDenial.denied &&
        nativeOwners.value?.original?.originalClientOwner === original

    internal fun hasOriginalEnrollment(witness: OriginalPhysicalEmployeeEnrollment): Boolean {
        val original = nativeOwners.value?.original ?: return false
        return !ownerDenial.denied && matchesOriginalEmployee(witness, original) && employeeHistory.value.any {
            it.sameOriginalEnrollment(witness)
        }
    }

    private fun recheckCatalogueOwners(owners: PhysicalJournalGateNativeOwners): Boolean {
        var master: List<PhysicalSpoolLease> = emptyList()
        var client: List<PhysicalSpoolLease> = emptyList()
        try {
            master = owners.master.capture { ownerDenial.deny() } ?: return false
            client = owners.client.capture { ownerDenial.deny() } ?: return false
            return !ownerDenial.denied
        } finally {
            val first = releasePhysicalSpoolLeases(master)
            val second = releasePhysicalSpoolLeases(client)
            if (!first || !second) { deny(); error("physical.catalogue.owner_cleanup") }
        }
    }

    public suspend fun acquire(binding: PhysicalRecordingInvocationBinding,
        custody: LocalPhysicalExternalCustody? = null): PhysicalRecordingAcquire {
        val originatingContext = currentCoroutineContext()
        val originatingJob = originatingContext[Job]
        var native: LocalPhysicalJournalInvocationLease? = null
        var acquired: PhysicalJournalGateEntry? = null
        var external: PhysicalJournalExternalCustodyOwnership? = null
        var nativePhaseEntered = false
        var nativeWorkReturned = false
        try {
            if (custody != null) {
                val owned = PhysicalJournalExternalCustodyOwnership(custody)
                external = owned
                registerExternal(owned)
                owned.observeInvocation { binding.latch.deny() }
                if (owned.denied) { binding.latch.deny(); return PhysicalRecordingAcquire.Held }
            }
            originatingContext.ensureActive()
            if (originatingJob == null) { binding.latch.deny(); return PhysicalRecordingAcquire.Held }
            val result = withContext(nativeDispatcher) {
                mutex.withLock {
                    val answer = run nativeWork@ {
                        currentCoroutineContext().ensureActive()
                        if (ownerDenial.denied || nativeOwners.value == null || binding.latch.denied || !originatingJob.isActive)
                            return@nativeWork PhysicalRecordingAcquire.Held
                        if (invocations.value.size >= LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES) return@nativeWork PhysicalRecordingAcquire.Busy
                        val domain = domains.singleOrNull { it.command == binding.command && it.domainProfile == binding.admission.domainProfile }
                            ?: return@nativeWork PhysicalRecordingAcquire.Held
                        val requirement = requirementFor(binding, domain) ?: return@nativeWork PhysicalRecordingAcquire.Held
                        if (intakeEntries.value.any { it.allocationId == requirement.clientAllocationId } ||
                            invocations.value.any { it.native.masterReceipt.binding.allocationId == requirement.masterAllocationId ||
                                it.native.clientReceipt.binding.allocationId == requirement.clientAllocationId }) return@nativeWork PhysicalRecordingAcquire.Busy
                        val start = binding.admission.action.isJournalStart()
                        // The lifecycle loan is already owned even if the following native open
                        // refuses or loses cleanup before returning a physical capture.
                        nativePhaseEntered = true
                        val master = ownedJournal(masterCatalogue, masterJournals, requirement, domain, start)
                        if (master !is LocalPhysicalJournalOpen.Opened)
                            return@nativeWork if (master === LocalPhysicalJournalOpen.Busy) PhysicalRecordingAcquire.Busy else PhysicalRecordingAcquire.Held
                        val client = ownedJournal(clientCatalogue, clientJournals, requirement, domain, start)
                        if (client !is LocalPhysicalJournalOpen.Opened)
                            return@nativeWork if (client === LocalPhysicalJournalOpen.Busy) PhysicalRecordingAcquire.Busy else PhysicalRecordingAcquire.Held
                        // acquireLocalPair checks complete ALLOCATED native capacity for each start.
                        val opened = LocalPhysicalJournalDelivery.acquireLocalPair(binding, binding.latch, master.journal, client.journal)
                        if (opened !is LocalPhysicalJournalLeaseOpen.Acquired) return@nativeWork PhysicalRecordingAcquire.Held
                        native = opened.lease
                        val lease = PhysicalRecordingNativeLease(binding, DomainId.random(), if (start)
                            listOf(opened.lease.masterReceipt.sourceChange, opened.lease.clientReceipt.sourceChange) else emptyList())
                        val entry = PhysicalJournalGateEntry(lease, opened.lease, originatingJob, external)
                        invocations.value = invocations.value + entry
                        acquired = entry
                        native = null
                        if (ownerDenial.denied || binding.latch.denied || !originatingJob.isActive) {
                            if (releaseEntry(entry)) PhysicalRecordingAcquire.Held else PhysicalRecordingAcquire.StorageUncertain
                        } else PhysicalRecordingAcquire.Ready(lease)
                    }
                    nativeWorkReturned = true
                    answer
                }
            }
            originatingContext.ensureActive()
            if (result !is PhysicalRecordingAcquire.Ready) binding.latch.deny()
            return result
        } catch (cancelled: CancellationException) {
            binding.latch.deny()
            cleanupUndelivered(acquired, native)
            throw cancelled
        } catch (_: Exception) {
            binding.latch.deny()
            cleanupUndelivered(acquired, native)
            return PhysicalRecordingAcquire.StorageUncertain
        } finally {
            // A delivered entry owns its loan until native release; known refusals without
            // such an entry may release it only after certain physical capture cleanup.
            external?.let { owned ->
                if (acquired == null && !owned.released)
                    settleUntransferredExternal(owned, nativePhaseEntered, nativeWorkReturned)
            }
        }
    }

    public suspend fun recheck(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeCheck = try {
        withContext(nativeDispatcher) {
            mutex.withLock {
                val entry = exactEntry(lease) ?: return@withLock PhysicalRecordingNativeCheck.HELD
                if (ownerDenial.denied || entry.external?.denied == true || !entry.originatingJob.isActive || !entry.native.recheck()) {
                    lease.binding.latch.deny()
                    PhysicalRecordingNativeCheck.HELD
                } else PhysicalRecordingNativeCheck.VERIFIED
            }
        }
    } catch (cancelled: CancellationException) {
        lease.binding.latch.deny()
        throw cancelled
    } catch (_: Exception) {
        lease.binding.latch.deny()
        PhysicalRecordingNativeCheck.STORAGE_UNCERTAIN
    }

    public suspend fun persistStart(lease: PhysicalRecordingNativeLease,
        start: PhysicalRecordingCommittedStart): PhysicalRecordingNativeCheck {
        var entry: PhysicalJournalGateEntry? = null
        return try {
            // The SQL start has already committed. Retain its genuine certificate even if the
            // original invocation is cancelled; cancellation must not manufacture a fresh action.
            withContext(NonCancellable + nativeDispatcher) {
                mutex.withLock {
                    val owned = exactEntry(lease) ?: return@withLock PhysicalRecordingNativeCheck.HELD
                    entry = owned
                    if (ownerDenial.denied) return@withLock PhysicalRecordingNativeCheck.HELD
                    // Parent withdrawal denies only the fresh continuation. Genuine committed
                    // certificates still follow the independent retained-custody path below.
                    if (owned.external?.denied == true) lease.binding.latch.deny()
                    when (val retained = LocalPhysicalJournalDelivery.persistLocalPairStart(owned.native, start)) {
                        LocalPhysicalBothStartWrite.Held -> PhysicalRecordingNativeCheck.HELD
                        LocalPhysicalBothStartWrite.Retained -> PhysicalRecordingNativeCheck.VERIFIED
                        is LocalPhysicalBothStartWrite.Fresh -> {
                            val continuation = LocalPhysicalJournalDelivery.consumeLocalPairStart(retained, owned.originatingJob, owned.external?.custody)
                            if (continuation != null) {
                                // Native consumption is one-shot. Never replace/evict an earlier
                                // continuation or recover one from certificate/lookup metadata.
                                retireTerminalInstructionOwners()
                                if (ownerDenial.denied || continuations.value.any { it.operationId == continuation.operationId } ||
                                    continuations.value.size >= LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES) continuation.deny()
                                else {
                                    continuations.value = continuations.value + continuation
                                    owned.continuation = continuation
                                    if (ownerDenial.denied || owned.originatingJob.isCancelled) continuation.deny()
                                }
                            }
                            // Metadata/retention only. No B4 action permission is returned here.
                            PhysicalRecordingNativeCheck.VERIFIED
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            lease.binding.latch.deny()
            entry?.continuation?.deny()
            throw cancelled
        } catch (_: Exception) {
            lease.binding.latch.deny()
            entry?.continuation?.deny()
            PhysicalRecordingNativeCheck.STORAGE_UNCERTAIN
        }
    }

    public suspend fun release(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeRelease {
        lease.binding.latch.deny()
        return try {
            withContext(NonCancellable + nativeDispatcher) {
                mutex.withLock {
                    val entry = exactEntry(lease) ?: return@withLock PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
                    if (releaseEntry(entry)) PhysicalRecordingNativeRelease.RELEASED else PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
                }
            }
        } catch (_: Exception) {
            deny()
            PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
        }
    }

    /**
     * Installed core SPI only: no allocation, lookup/replay or ID-based reconstruction. Both
     * native lease sets, the immutable-body slot, bounded mailbox and independent worker exist
     * BEFORE the original private take is burned and this SAME-binding lease can be returned.
     */
    public suspend fun prepareInstruction(binding: PhysicalInstructionNativeBinding,
        custody: LocalPhysicalExternalCustody? = null): PhysicalNativeInstructionPreparation {
        val caller = currentCoroutineContext()
        var continuation: LocalPhysicalActionContinuation? = null
        var registered: LocalPhysicalOriginalCaptureEntry? = null
        // Keep each already-owned capture list immediately. addAll/toArray after capture can
        // fail before a growable accumulator owns the returned leases.
        var untransferredTemporary: List<PhysicalSpoolLease> = emptyList()
        var untransferredEvidence: List<PhysicalSpoolLease> = emptyList()
        var delivered = false
        var cleanupCertain = true
        var result: PhysicalNativeInstructionPreparation = PhysicalNativeInstructionPreparation.Held
        var external: PhysicalJournalExternalCustodyOwnership? = null
        var nativePhaseEntered = false
        var nativeWorkReturned = false
        try {
            if (custody != null) {
                val owned = PhysicalJournalExternalCustodyOwnership(custody)
                external = owned
                registerExternal(owned)
                owned.observeInvocation { binding.denial.deny() }
                if (owned.denied) { binding.denial.deny(); return PhysicalNativeInstructionPreparation.Held }
            }
            caller.ensureActive()
            result = withContext(nativeDispatcher) {
                mutex.withLock {
                    val answer = run nativeWork@ {
                        currentCoroutineContext().ensureActive()
                        if (ownerDenial.denied || nativeOwners.value == null || binding.denial.denied || caller[Job]?.isActive != true || !evidenceRoot.isActive)
                            return@nativeWork PhysicalNativeInstructionPreparation.Held
                        retireTerminalInstructionOwners()
                        val original = continuations.value.singleOrNull { it.matchesInstruction(binding) }
                            ?: return@nativeWork PhysicalNativeInstructionPreparation.Held
                        continuation = original
                        if (original.denied || !original.cleanupCertain ||
                            originalCaptures.value.size >= LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES ||
                            originalCaptures.value.any { it.binding.committedStart === binding.committedStart })
                            return@nativeWork PhysicalNativeInstructionPreparation.Held
                        nativePhaseEntered = true
                        val nativeDenial = PhysicalJournalDenial()
                        val temporary = original.captureInstructionOwnership { nativeDenial.deny(); binding.denial.deny() }
                            ?: return@nativeWork PhysicalNativeInstructionPreparation.Held
                        untransferredTemporary = temporary
                        val evidence = original.captureInstructionOwnership { nativeDenial.deny(); binding.denial.deny() }
                            ?: return@nativeWork PhysicalNativeInstructionPreparation.Held
                        untransferredEvidence = evidence
                        val prepared = PhysicalNativeInstructionLease(binding, DomainId.random(), original.nativeRequirement.canonicalBytes)
                        val entry = LocalPhysicalOriginalCaptureEntry(prepared, original, temporary, evidence, nativeDenial, external) {
                            intake.value?.enqueue(NativeOriginalIntakeLocator(original.nativeRequirement.clientAllocationId))
                        }
                        // Non-evicting native ownership is registered BEFORE any worker/dispatch can
                        // fail to deliver. The caller never owns or cancels the evidence root/job.
                        originalCaptures.value = originalCaptures.value + entry
                        registered = entry
                        untransferredTemporary = emptyList()
                        untransferredEvidence = emptyList()
                        if (!entry.startWorker(evidenceScope) || ownerDenial.denied || binding.denial.denied || original.denied ||
                            original.takeInstruction()?.canonicalBytes != binding.canonicalRequirement ||
                            caller[Job]?.isActive != true || ownerDenial.denied || binding.denial.denied || nativeDenial.denied ||
                            original.denied || !entry.markPrepared()) {
                            entry.withdraw()
                            if (entry.cleanupCertain) PhysicalNativeInstructionPreparation.Held
                            else PhysicalNativeInstructionPreparation.StorageUncertain
                        } else PhysicalNativeInstructionPreparation.Prepared(prepared)
                    }
                    nativeWorkReturned = true
                    answer
                }
            }
            caller.ensureActive()
            if (result is PhysicalNativeInstructionPreparation.Prepared &&
                (ownerDenial.denied || binding.denial.denied || !evidenceRoot.isActive))
                result = PhysicalNativeInstructionPreparation.Held
            delivered = result is PhysicalNativeInstructionPreparation.Prepared
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            result = PhysicalNativeInstructionPreparation.StorageUncertain
        } finally {
            if (!delivered) {
                binding.denial.deny()
                continuation?.deny()
                registered?.withdraw()
            }
            // Lists not yet transferred to a registered entry have no possible accepted event.
            // This is memory release only; no native owner is closed on an undelivered response.
            val temporaryReleased = releasePhysicalSpoolLeases(untransferredTemporary)
            val evidenceReleased = releasePhysicalSpoolLeases(untransferredEvidence)
            if (!temporaryReleased || !evidenceReleased) {
                cleanupCertain = false
                external?.retainNativeUncertainty()
                binding.denial.deny()
                deny()
            }
            external?.let { owned ->
                if (registered == null && !owned.released) {
                    if (!temporaryReleased || !evidenceReleased) owned.retainNativeUncertainty()
                    else settleUntransferredExternal(owned, nativePhaseEntered, nativeWorkReturned)
                }
                if (owned.released) forgetReleasedExternal(owned)
            }
        }
        if (!cleanupCertain || continuation?.cleanupCertain == false || registered?.cleanupCertain == false) {
            binding.denial.deny()
            registered?.withdraw()
            return PhysicalNativeInstructionPreparation.StorageUncertain
        }
        return result
    }

    /** Normal prepublication release is NOT denial and never releases separate event custody. */
    public suspend fun releaseInstruction(prepared: PhysicalNativeInstructionLease): PhysicalRecordingNativeRelease = try {
        withContext(NonCancellable + nativeDispatcher) {
            mutex.withLock {
                if (retiredPreparations.any { it === prepared })
                    return@withLock PhysicalRecordingNativeRelease.RELEASED
                val entry = exactOriginalCapture(prepared) ?: run {
                    prepared.binding.denial.deny()
                    return@withLock PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
                }
                if (ownerDenial.denied) entry.withdraw()
                entry.releaseTemporary(prepared).also {
                    externalCustodies.value.filter { it.released }.forEach(::forgetReleasedExternal)
                }
            }
        }
    } catch (_: Exception) {
        prepared.binding.denial.deny()
        exactOriginalCapture(prepared)?.withdraw()
        PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
    }

    /**
     * Sole native adoption entry point. The UI supplies the genuine published object and SAME
     * original lifetime, never an ID/grant/principal. It must NOT separately call core adoptTo.
     * The core enforces publication lag and later protected access expiry; no human-action TTL
     * is invented here. A late/duplicate/wrong-lifetime publication cannot be rebound or rearmed.
     */
    public fun adoptPublished(published: PhysicalPublishedInstruction,
        original: PhysicalInteractiveRequestLifetime): LocalPhysicalPublishedInstruction? {
        if (ownerDenial.denied) { published.deny(); return null }
        val entry = try { originalCaptures.value.singleOrNull { published.matchesNativePreparation(it.binding) } }
            catch (_: Exception) { published.deny(); return null }
        val handle = entry?.adopt(published, original)
        if (handle == null || ownerDenial.denied || !handle.deliverable()) {
            published.deny()
            entry?.withdraw()
            return null
        }
        return handle
    }

    public fun installedIntake(intake: NativeOriginalIntake): Boolean =
        intake.owns(authorityOwner) && (this.intake.compareAndSet(null, intake) || this.intake.value === intake)

    public suspend fun enqueueRetainedOriginals() {
        val owner = intake.value ?: return
        withContext(nativeDispatcher) { mutex.withLock {
            if (ownerDenial.denied || nativeOwners.value == null) return@withLock
            val selectors = clientCatalogue.retainedAllocationSelectors() ?: return@withLock
            if (selectors.isEmpty()) return@withLock
            val start = ((intakeAfter?.let { selectors.indexOf(it) } ?: -1) + 1) % selectors.size
            // The core service calls this again after bounded work/retry. Advance even at BUSY
            // so one already-enqueued/held entry cannot starve later authentic allocations.
            // Fixed128 only: no guessed IDs, file scan, new allocation or capacity reuse.
            for (offset in selectors.indices) {
                currentCoroutineContext().ensureActive()
                val id = selectors[(start + offset) % selectors.size]
                intakeAfter = id
                if (owner.enqueue(NativeOriginalIntakeLocator(id)) != NativeOriginalIntakeSubmission.QUEUED) break
            }
        } }
    }

    public suspend fun acquireOriginal(invocation: NativeOriginalIntakeInvocation): NativeOriginalAcquire {
        var registered: LocalNativeOriginalIntakeEntry? = null
        var delivered = false
        try {
            val result = withContext(nativeDispatcher) { mutex.withLock {
                currentCoroutineContext().ensureActive()
                if (!invocation.belongsTo(authorityOwner) || invocation.denied || ownerDenial.denied || nativeOwners.value == null ||
                    intake.value == null || invocation.scope != clientCatalogue.binding.scope || intakeEntries.value.size >= 128)
                    return@withLock NativeOriginalAcquire.Held
                val id = invocation.locator.clientAllocationId
                if (intakeEntries.value.any { it.allocationId == id } ||
                    invocations.value.any { it.native.clientReceipt.binding.allocationId == id }) return@withLock NativeOriginalAcquire.Held
                val expected = clientCatalogue.readExpectedReceipt(id) ?: return@withLock NativeOriginalAcquire.Held
                val requirement = expected.binding.requirement
                val domain = domains.singleOrNull { it.command == requirement.command && it.domainProfile == requirement.domainProfile }
                    ?: return@withLock NativeOriginalAcquire.Held
                domain.validateRequirement(requirement)
                if (expected.binding.owner != clientCatalogue.binding || requirement.scope != invocation.scope ||
                    requirement.masterDeviceId != masterCatalogue.binding.deviceId || requirement.clientDeviceId != clientCatalogue.binding.deviceId ||
                    requirement.masterStorageProfile != masterCatalogue.storageProfile || requirement.clientStorageProfile != clientCatalogue.storageProfile ||
                    !matchesInstalledDomainOwners(requirement))
                    return@withLock NativeOriginalAcquire.Held
                val client = ownedJournal(clientCatalogue, clientJournals, requirement, domain, create = false)
                    as? LocalPhysicalJournalOpen.Opened ?: return@withLock NativeOriginalAcquire.Held
                if (client.journal.receipt.sourceCommitment != expected.sourceCommitment) return@withLock NativeOriginalAcquire.Held
                val master = ownedJournal(masterCatalogue, masterJournals, requirement, domain, create = false)
                    as? LocalPhysicalJournalOpen.Opened ?: return@withLock NativeOriginalAcquire.Held
                val denial = PhysicalJournalDenial()
                val leases = captureLocalPairNative(master.journal, client.journal) { denial.deny(); invocation.deny() }
                    ?: return@withLock NativeOriginalAcquire.Held
                var transferred = false
                try {
                    val retained = (LocalPhysicalJournalDelivery.deliverLocalOriginal(client.journal, master.journal)
                        as? LocalPhysicalDelivery.MasterRawRetained)?.retention ?: return@withLock NativeOriginalAcquire.Held
                    if (retained.resolution || retained.requirement.canonicalBytes != requirement.canonicalBytes ||
                        invocation.denied || denial.denied || ownerDenial.denied) return@withLock NativeOriginalAcquire.Held
                    val data = NativeOriginalRetained(requirement.command, requirement.domainProfile, requirement.canonicalBytes,
                        retained.certificate, retained.originalBody, listOf(master.journal.receipt.sourceChange, client.journal.receipt.sourceChange))
                    val lease = NativeOriginalRetainedLease(invocation, DomainId.random(), data)
                    val entry = LocalNativeOriginalIntakeEntry(lease, master.journal, client.journal, leases, denial)
                    intakeEntries.value = intakeEntries.value + entry
                    registered = entry
                    transferred = true
                    NativeOriginalAcquire.Retained(lease)
                } finally {
                    if (!transferred && !releasePhysicalSpoolLeases(leases)) { deny(); error("physical.intake.cleanup") }
                }
            } }
            currentCoroutineContext().ensureActive()
            if (invocation.denied || ownerDenial.denied) return NativeOriginalAcquire.Held
            delivered = result is NativeOriginalAcquire.Retained
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { invocation.deny(); return NativeOriginalAcquire.StorageUncertain }
        finally {
            if (!delivered) {
                invocation.deny()
                withContext(NonCancellable + nativeDispatcher) { mutex.withLock {
                    registered?.let { entry ->
                        if (entry.abandonUndelivered() == PhysicalRecordingNativeRelease.RELEASED)
                            intakeEntries.value = intakeEntries.value.filterNot { it === entry }
                        else deny()
                    }
                } }
            }
        }
    }

    public suspend fun recheckOriginal(lease: NativeOriginalRetainedLease): PhysicalRecordingNativeCheck =
        withContext(nativeDispatcher) { mutex.withLock {
            val entry = intakeEntries.value.singleOrNull { it.lease === lease && it.lease.invocation === lease.invocation }
                ?: return@withLock PhysicalRecordingNativeCheck.HELD
            if (ownerDenial.denied || !lease.invocation.belongsTo(authorityOwner)) PhysicalRecordingNativeCheck.HELD
            else entry.recheck()
        } }

    public suspend fun releaseOriginal(lease: NativeOriginalRetainedLease,
        disposition: NativeOriginalIntakeDisposition): PhysicalRecordingNativeRelease = withContext(NonCancellable + nativeDispatcher) {
        mutex.withLock {
            val entry = intakeEntries.value.singleOrNull { it.lease === lease && it.lease.invocation === lease.invocation }
                ?: return@withLock PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
            val released = entry.settle(disposition)
            if (released == PhysicalRecordingNativeRelease.RELEASED) intakeEntries.value = intakeEntries.value.filterNot { it === entry }
            released
        }
    }

    /**
     * Native-service retention retry, NOT an unauthenticated gateway/RPC entry point. The caller
     * must own the authentic local/pairing evidence route; this does not authorize disclosure or
     * SQL import/application. No body, profile, actor or employee principal is accepted. All
     * immutable origin and exact original bytes come from the protected old allocation only.
     */
    public suspend fun redeliverRetainedOriginal(clientAllocationId: DomainId): LocalPhysicalDelivery =
        redeliverRetained(clientAllocationId, resolution = false)

    /** The immutable UNKNOWN is delivered first; this can never rewrite it or prompt again. */
    public suspend fun redeliverRetainedResolution(clientAllocationId: DomainId): LocalPhysicalDelivery =
        redeliverRetained(clientAllocationId, resolution = true)

    private suspend fun redeliverRetained(clientAllocationId: DomainId, resolution: Boolean): LocalPhysicalDelivery {
        var pending: LocalPhysicalDelivery.ClientRetained? = null
        return try {
            withContext(nativeDispatcher) {
                mutex.withLock {
                    currentCoroutineContext().ensureActive()
                    if (ownerDenial.denied || nativeOwners.value == null) return@withLock LocalPhysicalDelivery.Held
                    val expected = clientCatalogue.readExpectedReceipt(clientAllocationId) ?: return@withLock LocalPhysicalDelivery.Held
                    val requirement = expected.binding.requirement
                    val domain = domains.singleOrNull { it.command == requirement.command && it.domainProfile == requirement.domainProfile }
                        ?: return@withLock LocalPhysicalDelivery.Held
                    if (!validJournalInput {
                        domain.validateRequirement(requirement)
                        require(expected.binding.owner == clientCatalogue.binding && requirement.scope == masterCatalogue.binding.scope &&
                            requirement.masterDeviceId == masterCatalogue.binding.deviceId && requirement.clientDeviceId == clientCatalogue.binding.deviceId &&
                            requirement.masterStorageProfile == masterCatalogue.storageProfile && requirement.clientStorageProfile == clientCatalogue.storageProfile &&
                            matchesInstalledDomainOwners(requirement))
                    }) return@withLock LocalPhysicalDelivery.Held
                    // Explicit existing-only lookup. Even a missing/corrupt master can never
                    // cause fresh capacity/allocation or erase the client's retained obligation.
                    val client = ownedJournal(clientCatalogue, clientJournals, requirement, domain, create = false)
                    if (client !is LocalPhysicalJournalOpen.Opened || client.journal.receipt.sourceCommitment != expected.sourceCommitment)
                        return@withLock LocalPhysicalDelivery.Held
                    val original = (client.journal.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot
                        ?: return@withLock LocalPhysicalDelivery.Held
                    val originalBody = original.original ?: return@withLock LocalPhysicalDelivery.Held
                    if (resolution) {
                        val linked = (client.journal.readResolution() as? LocalPhysicalResolutionRead.Read)?.resolution
                            ?: return@withLock LocalPhysicalDelivery.Held
                        pending = LocalPhysicalDelivery.ClientRetained(linked.resolutionOperationId, linked.bodyDigest)
                    } else pending = LocalPhysicalDelivery.ClientRetained(requirement.recordingOperationId, journalBodyDigest(originalBody))
                    val master = ownedJournal(masterCatalogue, masterJournals, requirement, domain, create = false)
                    if (master !is LocalPhysicalJournalOpen.Opened) return@withLock checkNotNull(pending)
                    val sample = PhysicalJournalDenial()
                    val captured = captureLocalPairNative(master.journal, client.journal) { sample.deny() }
                        ?: return@withLock checkNotNull(pending)
                    try {
                        if (ownerDenial.denied || sample.denied) return@withLock checkNotNull(pending)
                        val delivered = if (resolution) LocalPhysicalJournalDelivery.deliverLocalResolution(client.journal, master.journal)
                            else LocalPhysicalJournalDelivery.deliverLocalOriginal(client.journal, master.journal)
                        if (ownerDenial.denied || sample.denied) checkNotNull(pending) else delivered
                    } finally {
                        if (!releasePhysicalSpoolLeases(captured)) {
                            deny()
                            error("physical.journal.lease_cleanup")
                        }
                    }
                    // Journals were captured in the service-owned maps before the dispatch can
                    // suspend/lose delivery. Only temporary memory leases leave this method.
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (_: Exception) { pending ?: LocalPhysicalDelivery.Held }
    }

    /** Immediate one-way denial before waiting for worker/native cleanup. No native IO here. */
    public fun deny() {
        ownerDenial.deny()
        intakeEntries.value.forEach { it.lease.invocation.deny() }
        invocations.value.forEach { it.lease.binding.latch.deny() }
        externalCustodies.value.forEach { it.deny() }
        continuations.value.forEach { it.deny() }
        // No evidenceRoot.cancel(): accepted original bytes are NOT an employee/UI job.
        originalCaptures.value.forEach { it.withdraw() }
    }

    public fun denyNewWork(): Unit = deny()

    public suspend fun closeOwnedGate(): PhysicalRecordingNativeRelease {
        // The service closes only once. A normally draining worker is not an uncertain
        // native close: wait for its actual completion before attempting any owner close.
        // Admission denial plus the registration mutex makes this a complete worker cut.
        // Accepted evidence keeps its independent worker; never cancel it to unblock close.
        deny()
        return withContext(NonCancellable + nativeDispatcher) {
            val workers = mutex.withLock { evidenceRoot.children.toList() }
            workers.forEach { it.join() } // Outside the mutex needed by native evidence work.
            // Completion is NOT proof of durability. Keep all native/capture/intake checks
            // in close(), including the holds for undurable or uncertain original facts.
            if (close() == LocalPhysicalSpoolClose.CLOSED) PhysicalRecordingNativeRelease.RELEASED
            else PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
        }
    }

    /**
     * Service-owner close, never a Room callback. Pending evidence returns HELD BEFORE any
     * native close, keeping owners and the worker root alive. Later native-cleanup failure may
     * leave some owners closed and others HELD; that is not successful service shutdown. No
     * unlink, reset, eviction or automatic reclaim exists.
     */
    public suspend fun close(): LocalPhysicalSpoolClose {
        deny()
        return try {
            withContext(NonCancellable + nativeDispatcher) {
                mutex.withLock {
                    // Do not wait/join under this mutex. A later service close may observe worker
                    // completion; accepted/uncertain undurable custody is intentionally HELD.
                    if (intakeEntries.value.isNotEmpty() || !originalCaptures.value.all { it.canCloseNativeOwners }) return@withLock LocalPhysicalSpoolClose.HELD
                    var complete = continuations.value.all { it.cleanupCertain }
                    invocations.value.toList().forEach { if (!releaseEntry(it)) complete = false }
                    if (!complete) return@withLock LocalPhysicalSpoolClose.HELD
                    val partial = nativeOwnerAttempt
                    if (partial != null) {
                        if (partial.close() != LocalPhysicalSpoolClose.CLOSED) return@withLock LocalPhysicalSpoolClose.HELD
                        nativeOwnerAttempt = null
                    }
                    // An undifferentiated native HELD/throw can conceal an uncertain close
                    // before any handle was returned. Neither successful catalogue close nor
                    // absence from the owned maps can prove that hidden owner was released.
                    if (unreturnedJournalOwner) return@withLock LocalPhysicalSpoolClose.HELD
                    journalAwaitingTransfer?.let { journal ->
                        val closed = try { journal.close() } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
                        if (closed != LocalPhysicalSpoolClose.CLOSED) return@withLock LocalPhysicalSpoolClose.HELD
                        journalAwaitingTransfer = null
                    }
                    (masterJournals.values + clientJournals.values).forEach { journal ->
                        val closed = try { journal.close() } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
                        if (closed != LocalPhysicalSpoolClose.CLOSED) complete = false
                    }
                    nativeOwners.value?.let { owners ->
                        val masterClosed = try { owners.master.close() } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
                        val clientClosed = try { owners.client.close() } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
                        if (masterClosed != LocalPhysicalSpoolClose.CLOSED || clientClosed != LocalPhysicalSpoolClose.CLOSED) complete = false
                    }
                    if (complete) {
                        // Only after every native capture/journal/catalogue has certainly
                        // closed may a held native-only external uncertainty be resolved.
                        for (external in externalCustodies.value) {
                            if (!external.releaseAfterNativeCleanup(allNativeClosed = true)) complete = false
                            else forgetReleasedExternal(external)
                        }
                    }
                    if (complete) {
                        evidenceRoot.cancel()
                        LocalPhysicalSpoolClose.CLOSED
                    } else LocalPhysicalSpoolClose.HELD
                }
            }
        } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
    }

    private fun exactEntry(lease: PhysicalRecordingNativeLease): PhysicalJournalGateEntry? =
        invocations.value.singleOrNull { it.lease === lease && it.lease.binding === lease.binding }

    private fun exactOriginalCapture(prepared: PhysicalNativeInstructionLease): LocalPhysicalOriginalCaptureEntry? =
        originalCaptures.value.singleOrNull { it.prepared === prepared && it.binding === prepared.binding }

    private fun releaseEntry(entry: PhysicalJournalGateEntry): Boolean {
        entry.lease.binding.latch.deny()
        if (entry.originatingJob.isCancelled) entry.continuation?.deny()
        return try {
            entry.native.release()
            // Physical ownership releases BEFORE the actual upper client journal loan.
            if (entry.external?.releaseAfterNativeCleanup() == false) { deny(); return false }
            entry.external?.let(::forgetReleasedExternal)
            invocations.value = invocations.value.filterNot { it === entry }
            true
        } catch (_: Exception) { entry.external?.retainNativeUncertainty(); deny(); false }
    }

    private fun registerExternal(owner: PhysicalJournalExternalCustodyOwnership) {
        while (true) {
            val selected = externalCustodies.value
            if (externalCustodies.compareAndSet(selected, selected + owner)) return
        }
    }

    private fun forgetReleasedExternal(owner: PhysicalJournalExternalCustodyOwnership) {
        if (!owner.released) return
        while (true) {
            val selected = externalCustodies.value
            if (selected.none { it === owner } || externalCustodies.compareAndSet(selected, selected.filterNot { it === owner })) return
        }
    }

    private suspend fun settleUntransferredExternal(owner: PhysicalJournalExternalCustodyOwnership,
        nativePhaseEntered: Boolean, nativeWorkReturned: Boolean) {
        withContext(NonCancellable + nativeDispatcher) { mutex.withLock {
            if (!nativePhaseEntered || (nativeWorkReturned && !unreturnedJournalOwner &&
                    journalAwaitingTransfer == null && nativeOwnerAttempt?.unreturnedNativeOwner != true)) {
                if (owner.releaseAfterNativeCleanup()) forgetReleasedExternal(owner) else deny()
            } else { owner.retainNativeUncertainty(); deny() }
        } }
    }

    private suspend fun cleanupUndelivered(entry: PhysicalJournalGateEntry?, native: LocalPhysicalJournalInvocationLease?) {
        try {
            withContext(NonCancellable + nativeDispatcher) {
                mutex.withLock {
                    native?.release()
                    if (entry != null && invocations.value.any { it === entry }) releaseEntry(entry)
                }
            }
        } catch (_: Exception) { deny() }
    }

    private fun requirementFor(binding: PhysicalRecordingInvocationBinding,
        domain: LocalPhysicalJournalDomain): LocalPhysicalJournalRequirementV1? = try {
        LocalPhysicalJournalRequirementV1.decode(binding.admission.requirementBytes).also { requirement ->
            require(requirement.scope == binding.scope && requirement.scope == masterCatalogue.binding.scope &&
                requirement.command == binding.command && requirement.domainProfile == binding.admission.domainProfile &&
                requirement.masterDeviceId == binding.authority.deviceId && requirement.clientDeviceId == binding.clientDeviceId &&
                requirement.masterDeviceId == masterCatalogue.binding.deviceId && requirement.clientDeviceId == clientCatalogue.binding.deviceId &&
                requirement.masterStorageProfile == masterCatalogue.storageProfile && requirement.clientStorageProfile == clientCatalogue.storageProfile)
            val owners = localPhysicalDomainOwners(requirement)
            require(owners.domain === domain && matchesInstalledDomainOwners(requirement, owners) && owners.authority == binding.authority)
        }
    } catch (_: Exception) { null }

    /** Full domain-owner linkage, not merely equal device/profile labels. Pure immutable read. */
    private fun matchesInstalledDomainOwners(requirement: LocalPhysicalJournalRequirementV1,
        value: LocalPhysicalDomainOwners = localPhysicalDomainOwners(requirement)): Boolean {
        val selected = nativeOwners.value ?: return false
        val installed = selected.participants
        if (value.domain !in domains || value.master != installed.masterOwner || value.client != installed.clientOwner) return false
        val original = selected.original
        return original == null || (value.authority == original.authority &&
            (requirement.originalActorId == original.firstActorId || employeeHistory.value.any { witness ->
                witness.actorId == requirement.originalActorId && matchesOriginalEmployee(witness, original)
            }))
    }

    /** Called under the native mutex. Unresolved accepted bodies and uncertain cleanup stay owned. */
    private fun retireTerminalInstructionOwners() {
        val completed = originalCaptures.value.filter { it.canCloseNativeOwners }
        if (completed.isNotEmpty()) {
            check(retiredPreparations.size + completed.size <=
                LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES * LocalPhysicalJournalFormatV1.MAX_CATALOGUE_GENERATIONS)
            completed.forEach { retiredPreparations += it.prepared }
            originalCaptures.value = originalCaptures.value.filterNot { it in completed }
        }
        continuations.value = continuations.value.filterNot { it.retired }
    }

    /**
     * The 128 bound is ACTIVE in-memory ownership, not a lifetime allocation allowance. A journal
     * with no invocation, continuation, capture, intake or transfer user is closed and forgotten;
     * its immutable catalogue/journal bytes remain the only authority for a later existing-only
     * reopen. A HELD close is retained and never treated as free capacity.
     */
    private fun retireIdleJournals(owned: MutableMap<DomainId, LocalPhysicalJournal>): Boolean {
        if (owned.size < LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES) return true
        val pinned = mutableSetOf<LocalPhysicalJournal>()
        invocations.value.forEach { entry ->
            pinned += entry.native.master
            pinned += entry.native.client
        }
        continuations.value.forEach { continuation ->
            owned.values.forEach { if (continuation.pinsJournal(it)) pinned += it }
        }
        originalCaptures.value.forEach { capture ->
            owned.values.forEach { if (capture.pinsJournal(it)) pinned += it }
        }
        intakeEntries.value.forEach { entry ->
            owned.values.forEach { if (entry.pinsJournal(it)) pinned += it }
        }
        journalAwaitingTransfer?.let { pinned += it }
        for ((id, journal) in owned.toList()) {
            if (journal in pinned) continue
            val closed = try { journal.close() } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
            if (closed == LocalPhysicalSpoolClose.CLOSED) owned.remove(id)
        }
        return owned.size < LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES
    }

    private fun ownedJournal(catalogue: LocalPhysicalAllocationCatalogue,
        owned: MutableMap<DomainId, LocalPhysicalJournal>, requirement: LocalPhysicalJournalRequirementV1,
        domain: LocalPhysicalJournalDomain, create: Boolean): LocalPhysicalJournalOpen {
        retireTerminalInstructionOwners()
        val id = if (catalogue.binding.role == LocalPhysicalSpoolRole.MASTER) requirement.masterAllocationId else requirement.clientAllocationId
        owned[id]?.let { journal -> return if (journal.receipt.binding.requirement.canonicalBytes == requirement.canonicalBytes)
            LocalPhysicalJournalOpen.Opened(journal) else LocalPhysicalJournalOpen.IdentityConflict }
        if (owned.size >= LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES && !retireIdleJournals(owned))
            return LocalPhysicalJournalOpen.Busy
        if (unreturnedJournalOwner || journalAwaitingTransfer != null) return LocalPhysicalJournalOpen.Held
        // Reserve the uncertainty slot BEFORE invoking a native factory. HELD conflates read
        // refusal and uncertain internal cleanup; only a returned owner or an explicit known
        // refusal can clear this state. No attempt to reconstruct/replace a hidden handle.
        unreturnedJournalOwner = true
        var transferred = false
        var result: LocalPhysicalJournalOpen = LocalPhysicalJournalOpen.Held
        try {
            val opened = if (create) when (val fresh = catalogue.allocate(requirement, domain)) {
                // Only explicit authenticated ID conflict permits protected-catalogue recovery.
                // BUSY/HELD/torn/unknown paths must NEVER fall back to a new or adopted allocation.
                LocalPhysicalJournalOpen.IdentityConflict -> catalogue.openExisting(id, domain)
                else -> fresh
            } else catalogue.openExisting(id, domain)
            if (opened !is LocalPhysicalJournalOpen.Opened) {
                if (opened !== LocalPhysicalJournalOpen.Held) unreturnedJournalOwner = false
                result = opened
            } else {
                val journal = opened.journal
                journalAwaitingTransfer = journal // own before comparison/map publication
                unreturnedJournalOwner = false
                result = if (journal.receipt.binding.requirement.canonicalBytes != requirement.canonicalBytes ||
                    journal.receipt.binding.owner != catalogue.binding) LocalPhysicalJournalOpen.IdentityConflict
                else {
                    owned[id] = journal
                    transferred = true
                    journalAwaitingTransfer = null
                    opened
                }
            }
        } catch (_: Exception) { deny() }
        finally {
            if (!transferred) {
                journalAwaitingTransfer?.let { journal ->
                    val closed = try { journal.close() } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
                    if (closed == LocalPhysicalSpoolClose.CLOSED) journalAwaitingTransfer = null
                    else { deny(); result = LocalPhysicalJournalOpen.Held }
                }
                if (unreturnedJournalOwner) { deny(); result = LocalPhysicalJournalOpen.Held }
            }
        }
        return result
    }

    override fun toString(): String = "PhysicalJournalNativeBackend([REDACTED])"
}

internal data class OriginalAndroidPhysicalOwnerIdentity(
    val master: LocalPhysicalCatalogueBinding,
    val client: LocalPhysicalCatalogueBinding,
    val authority: AuthorityIdentity,
    val firstActorId: DomainId,
    val receiptDigest: PayloadDigest,
    val originalClientOwner: Any,
)

internal class PhysicalJournalGateOwnerAttempt {
    var master: LocalPhysicalAllocationCatalogue? = null
    var client: LocalPhysicalAllocationCatalogue? = null
    val journals: MutableList<LocalPhysicalJournal> = ArrayList(LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES)
    var nativePreparation: PhysicalJournalNativePreparation? = null
    // Native factory HELD/throw may include an uncertain close before it can return an owner.
    // There is no safe way to reconstruct that handle or certify its absence. Keep this
    // lifetime held even after every object that WAS returned has closed successfully.
    var unreturnedNativeOwner: Boolean = false

    fun close(): LocalPhysicalSpoolClose {
        var complete = !unreturnedNativeOwner
        journals.forEach { journal ->
            val closed = try { journal.close() } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
            if (closed != LocalPhysicalSpoolClose.CLOSED) complete = false
        }
        val first = try { master?.close() ?: LocalPhysicalSpoolClose.CLOSED } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
        val second = try { client?.close() ?: LocalPhysicalSpoolClose.CLOSED } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
        val preparation = try { nativePreparation?.close() ?: LocalPhysicalSpoolClose.CLOSED }
            catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
        return if (complete && first == LocalPhysicalSpoolClose.CLOSED && second == LocalPhysicalSpoolClose.CLOSED &&
            preparation == LocalPhysicalSpoolClose.CLOSED)
            LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD
    }
}

internal fun interface PhysicalJournalNativePreparation {
    fun close(): LocalPhysicalSpoolClose
}

private class PhysicalJournalGateNativeOwners(val master: LocalPhysicalAllocationCatalogue,
    val client: LocalPhysicalAllocationCatalogue, val original: OriginalAndroidPhysicalOwnerIdentity?) {
    // All fields below are immutable observations of the actual opened native owners. Publish
    // this object only after native enrollment/reopen verification; never derive it in a loan.
    val participants = LocalRetailJournalParticipants(master.binding, client.binding,
        master.storageProfile, client.storageProfile)
}

private fun requireLocalCataloguePair(master: LocalPhysicalAllocationCatalogue, client: LocalPhysicalAllocationCatalogue) {
    require(master !== client && master.binding.role == LocalPhysicalSpoolRole.MASTER &&
        client.binding.role == LocalPhysicalSpoolRole.CLIENT && master.binding.scope == client.binding.scope &&
        master.binding.deviceId == client.binding.deviceId && master.binding.installationId == client.binding.installationId &&
        master.storageProfile == client.storageProfile)
}

private class PhysicalJournalGateEntry(val lease: PhysicalRecordingNativeLease,
    val native: LocalPhysicalJournalInvocationLease, val originatingJob: Job,
    val external: PhysicalJournalExternalCustodyOwnership?) {
    var continuation: LocalPhysicalActionContinuation? = null
}

/** Final, memory-only denying state; no caller-supplied currentness callback or reset. */
internal class PhysicalJournalDenial {
    private val denial = MutableStateFlow(false)
    val denied: Boolean get() = denial.value
    fun deny() { denial.value = true }
}

/** A synchronous denial during registration cannot lose the returned end-only observation. */
internal class PhysicalJournalDenialOwnership {
    private enum class State { REGISTERING, OWNED, REQUESTED, RELEASING, RELEASED, UNCERTAIN }
    private val state = MutableStateFlow(State.REGISTERING)
    private var registration: LocalPhysicalExternalDenialRegistration? = null
    val released: Boolean get() = state.value == State.RELEASED
    val cleanupCertain: Boolean get() = state.value == State.OWNED || state.value == State.RELEASED

    fun attach(value: LocalPhysicalExternalDenialRegistration?) {
        registration = value
        while (true) {
            when (val selected = state.value) {
                State.REGISTERING -> if (state.compareAndSet(selected, State.OWNED)) return
                State.REQUESTED -> if (state.compareAndSet(selected, State.RELEASING)) {
                    releaseCaptured()
                    return
                }
                else -> error("physical.journal.denial_registration")
            }
        }
    }

    fun release() {
        while (true) {
            when (val selected = state.value) {
                State.REGISTERING -> if (state.compareAndSet(selected, State.REQUESTED)) return
                State.OWNED -> if (state.compareAndSet(selected, State.RELEASING)) {
                    releaseCaptured()
                    return
                }
                else -> return // In-progress and unknown releases are never retried.
            }
        }
    }

    private fun releaseCaptured() {
        try {
            registration?.release()
            registration = null
            state.value = State.RELEASED
        } catch (_: Exception) {
            state.value = State.UNCERTAIN
        }
    }
}

/**
 * Idempotent memory ownership with an explicit in-progress state. A racing release may NOT
 * report RELEASED or permit native-owner close before the first real release has completed.
 * This does not perform native IO, retire an owner, or treat a failed cleanup as successful.
 */
internal class PhysicalJournalLeaseOwnership(leases: List<PhysicalSpoolLease>) {
    private val leases = leases.toList()
    private val state = MutableStateFlow(State.OWNED)
    private enum class State { OWNED, RELEASING, RELEASED, UNCERTAIN }
    val released: Boolean get() = state.value == State.RELEASED
    val cleanupUncertain: Boolean get() = state.value == State.UNCERTAIN
    val cleanupCertain: Boolean get() = state.value.let { it != State.RELEASING && it != State.UNCERTAIN }
    fun release() {
        if (state.compareAndSet(State.OWNED, State.RELEASING))
            state.value = if (releasePhysicalSpoolLeases(leases)) State.RELEASED else State.UNCERTAIN
    }
}

private fun captureLocalPairNative(master: LocalPhysicalJournal, client: LocalPhysicalJournal,
    newAction: Boolean = false, allocatedOnly: Boolean = false,
    onRetirement: () -> Unit): List<PhysicalSpoolLease>? {
    // Catalogue capture returns TWO already-owned leases. Keep each returned collection and
    // each journal lease in its own cleanup reference BEFORE any bulk append or allocation.
    var masterCatalogue: List<PhysicalSpoolLease> = emptyList()
    var clientCatalogue: List<PhysicalSpoolLease> = emptyList()
    var masterJournal: PhysicalSpoolLease? = null
    var clientJournal: PhysicalSpoolLease? = null
    var transferred = false
    try {
        if (!samePhysicalPair(master, client)) return null
        masterCatalogue = master.catalogue.capture(onRetirement) ?: return null
        clientCatalogue = client.catalogue.capture(onRetirement) ?: return null
        masterJournal = (if (newAction) master.captureNewAction(allocatedOnly = allocatedOnly, onRetirement = onRetirement)
            else master.capture(onRetirement)) ?: return null
        clientJournal = (if (newAction) client.captureNewAction(allocatedOnly = allocatedOnly, onRetirement = onRetirement)
            else client.capture(onRetirement)) ?: return null
        // This construction may allocate/copy; all six references still have local cleanup.
        // The hint is not the ownership proof. Transfer follows complete carrier construction.
        val captured = buildList<PhysicalSpoolLease>(6) {
            addAll(masterCatalogue)
            addAll(clientCatalogue)
            add(checkNotNull(masterJournal))
            add(checkNotNull(clientJournal))
        }
        transferred = true
        return captured
    } finally {
        if (!transferred) {
            try { onRetirement() } finally {
                val masterCatalogueReleased = releasePhysicalSpoolLeases(masterCatalogue)
                val clientCatalogueReleased = releasePhysicalSpoolLeases(clientCatalogue)
                val masterJournalReleased = releasePhysicalSpoolLease(masterJournal)
                val clientJournalReleased = releasePhysicalSpoolLease(clientJournal)
                check(masterCatalogueReleased && clientCatalogueReleased && masterJournalReleased && clientJournalReleased) {
                    "physical.journal.lease_cleanup"
                }
            }
        }
    }
}

private fun PhysicalRecordingAction.isJournalStart(): Boolean =
    this == PhysicalRecordingAction.START_PAYMENT || this == PhysicalRecordingAction.START_REFUND

private fun samePhysicalPair(master: LocalPhysicalJournal, client: LocalPhysicalJournal): Boolean {
    val m = master.receipt.binding
    val c = client.receipt.binding
    if (master === client || master.catalogue === client.catalogue ||
        m.owner.role != LocalPhysicalSpoolRole.MASTER || c.owner.role != LocalPhysicalSpoolRole.CLIENT ||
        m.owner.deviceId != c.owner.deviceId || m.owner.installationId != c.owner.installationId ||
        m.allocationId == c.allocationId || m.requirement.canonicalBytes != c.requirement.canonicalBytes) return false
    if (master.receipt.nativeIdentity.fileIdentity == client.receipt.nativeIdentity.fileIdentity ||
        master.receipt.nativeIdentity.directoryIdentity == client.receipt.nativeIdentity.directoryIdentity) return false
    return true
}

private fun matchesInvocation(binding: PhysicalRecordingInvocationBinding, master: LocalPhysicalJournal,
    client: LocalPhysicalJournal): Boolean {
    val r = master.receipt.binding.requirement
    if (binding.scope != r.scope || binding.command != r.command || binding.admission.domainProfile != r.domainProfile ||
        binding.admission.requirementBytes != r.canonicalBytes || binding.authority.deviceId != r.masterDeviceId) return false
    return when (binding.admission.action) {
        PhysicalRecordingAction.START_PAYMENT, PhysicalRecordingAction.START_REFUND ->
            binding.operationId == r.intentOperationId && binding.semanticDigest == r.intentSemanticDigest &&
                binding.originalActorId == r.originalActorId && binding.clientDeviceId == r.clientDeviceId &&
                (binding.admission.action == PhysicalRecordingAction.START_PAYMENT) == (master.domain.startKind == LocalPhysicalJournalStartKind.PAYMENT)
        PhysicalRecordingAction.RETAIN_ORIGINAL -> {
            val snapshot = (client.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot
            binding.operationId == r.recordingOperationId && snapshot?.original == binding.normalized &&
                snapshot?.originalSemanticDigest == binding.semanticDigest
        }
        PhysicalRecordingAction.RETAIN_RESOLUTION -> {
            val original = (client.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot ?: return false
            val body = original.original ?: return false
            val current = client.readResolution() as? LocalPhysicalResolutionRead.Read ?: return false
            if (binding.operationId != r.resolutionOperationId || original.originalKind != LocalPhysicalOriginalKind.UNKNOWN ||
                semanticDigest(r.scope, binding.originalActorId, r.command, binding.normalized) != binding.semanticDigest) return false
            val descriptor = try { localPhysicalResolutionDescriptor(master.domain, r, body, binding.originalActorId, binding.normalized) }
                catch (_: Exception) { return false }
            current.resolution?.let {
                it.body == binding.normalized && it.resolverActorId == binding.originalActorId &&
                    it.resolverAuthorization == descriptor && it.originalDigest == original.originalDigest
            } ?: true // Genuine binding may retain the single reserved linked resolution now.
        }
    }
}

private fun LocalPhysicalJournalWrite.retained(): Boolean =
    this == LocalPhysicalJournalWrite.RETAINED || this == LocalPhysicalJournalWrite.ALREADY_RETAINED

private fun LocalPhysicalStartWrite.retained(): Boolean =
    this is LocalPhysicalStartWrite.Fresh || this === LocalPhysicalStartWrite.Retained ||
        this === LocalPhysicalStartWrite.AlreadyRetained
