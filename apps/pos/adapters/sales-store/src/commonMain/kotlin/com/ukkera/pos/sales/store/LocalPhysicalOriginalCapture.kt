package com.ukkera.pos.sales.store

import com.ukkera.pos.database.PhysicalInstructionNativeBinding
import com.ukkera.pos.database.PhysicalInstructionProjection
import com.ukkera.pos.database.PhysicalInteractiveRequestLifetime
import com.ukkera.pos.database.PhysicalNativeInstructionLease
import com.ukkera.pos.database.PhysicalOriginalEventCapture
import com.ukkera.pos.database.PhysicalPublishedInstruction
import com.ukkera.pos.database.PhysicalRecordingNativeRelease
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The native half of ONE genuine adopted publication. No constructor, decoder, allocation-ID
 * lookup or principal creates this handle. Its projection/Boolean alone is not action authority.
 * The gateway/UI must also keep the exact original request lifetime and deny it BEFORE changing
 * root/session/presentation. The core owns adoption lag, access expiry and security continuity.
 */
public class LocalPhysicalPublishedInstruction internal constructor(
    private val entry: LocalPhysicalOriginalCaptureEntry,
    internal val published: PhysicalPublishedInstruction,
    internal val original: PhysicalInteractiveRequestLifetime,
) {
    public val projection: PhysicalInstructionProjection = published.projection

    /** Necessary-only live sampling, never a cached authorization or another instruction take. */
    public fun deliverable(): Boolean = entry.deliverable(this)
    public fun deny() { entry.withdraw() }

    /** Synchronous canonical-body ownership BEFORE any worker hop; no caller target or actor. */
    public fun capturePayment(outcome: LocalPaymentOutcomeV3): LocalPhysicalOriginalCaptureResult =
        entry.capturePayment(this, outcome)

    /** Distinct FOOD evidence; the genuine publication supplies its original target. */
    public fun captureFoodPayment(outcome: LocalFoodCashOutcome): LocalPhysicalOriginalCaptureResult =
        entry.captureFoodPayment(this, outcome)

    /** Distinct accepted FOOD payout observation, bound only by its actual native publication. */
    public fun captureFoodRefund(outcome: LocalFoodRefundOutcome): LocalPhysicalOriginalCaptureResult =
        entry.captureFoodRefund(this, outcome)

    /** Captures only this original refund observation, never resolution or another physical act. */
    public fun captureRefund(outcome: LocalRefundOutcomeV3): LocalPhysicalOriginalCaptureResult =
        entry.captureRefund(this, outcome)

    /** Supplier cash-out, never a customer collection or an accepted-return alias. */
    public fun captureSupplierCash(outcome: LocalSupplierCashOutcome): LocalPhysicalOriginalCaptureResult =
        entry.captureSupplierCash(this, outcome)

    override public fun toString(): String = "LocalPhysicalPublishedInstructionV1([REDACTED])"
}

public sealed interface LocalPhysicalOriginalCaptureResult {
    /**
     * Exact immutable event is owned in process memory, NOT yet promised native durability,
     * SQL import, financial application or result disclosure. The independent owner may be HELD.
     * This terminal capture can never grant another instruction, even after later rights loss.
     */
    public class CapturedInMemory internal constructor(public val operationId: OperationId,
        public val bodyDigest: PayloadDigest) : LocalPhysicalOriginalCaptureResult
    /** No event grant: missing original remains unresolved, never implicitly UNKNOWN/NotPaid. */
    public data object Rejected : LocalPhysicalOriginalCaptureResult
    /** This call accepted no body; malformed/oversized input never rearms the original handle. */
    public data object InvalidInput : LocalPhysicalOriginalCaptureResult
    /** Capture disposition is uncertain. Preserve the private candidate; do not retry/rearm. */
    public data object Uncertain : LocalPhysicalOriginalCaptureResult
}

private sealed interface PhysicalOriginalMemoryState {
    data object Preparing : PhysicalOriginalMemoryState
    data object AwaitingAdoption : PhysicalOriginalMemoryState
    class Adopted(val handle: LocalPhysicalPublishedInstruction) : PhysicalOriginalMemoryState
    class Deciding(val body: StoredBytes, val digest: PayloadDigest) : PhysicalOriginalMemoryState {
        val granted = MutableStateFlow<PhysicalOriginalEventCapture?>(null)
    }
    class Accepted(val candidate: Deciding) : PhysicalOriginalMemoryState
    class Finished(val accepted: Accepted, val delivery: LocalPhysicalDelivery) : PhysicalOriginalMemoryState
    class Uncertain(val candidate: Deciding) : PhysicalOriginalMemoryState
    data object Withdrawn : PhysicalOriginalMemoryState
}

/**
 * Service-owned entry, not a UI job. The gate registers it before starting its pre-reserved
 * one-event worker and before a dispatcher return can lose delivery. Both native lease sets
 * already exist. Temporary preparation release must not release evidence custody. Finite128,
 * non-evicting ownership is a bounded source cut, not sustained storage/worker qualification.
 */
internal class LocalPhysicalOriginalCaptureEntry(
    val prepared: PhysicalNativeInstructionLease,
    private val continuation: LocalPhysicalActionContinuation,
    temporaryOwnership: List<PhysicalSpoolLease>,
    evidenceOwnership: List<PhysicalSpoolLease>,
    private val nativeDenial: PhysicalJournalDenial,
    private val temporaryExternal: PhysicalJournalExternalCustodyOwnership?,
    private val wakeDurableOriginal: () -> Unit,
) {
    val binding: PhysicalInstructionNativeBinding = prepared.binding
    private val requirement = continuation.nativeRequirement
    private val state = MutableStateFlow<PhysicalOriginalMemoryState>(PhysicalOriginalMemoryState.Preparing)
    private val uncertainCandidate = MutableStateFlow<PhysicalOriginalMemoryState.Deciding?>(null)
    // The bounded mailbox carries a wake only. The actual immutable body is already owned in state.
    private val mailbox = Channel<Unit>(capacity = 1)
    private val worker = MutableStateFlow<Job?>(null)
    private val workerStarted = MutableStateFlow(false)
    private val temporaryOwnership = PhysicalJournalLeaseOwnership(temporaryOwnership)
    private val evidenceOwnership = PhysicalJournalLeaseOwnership(evidenceOwnership)
    private val cleanupUncertain = MutableStateFlow(false)
    private var employeeDenial: LocalPhysicalExternalDenialRegistration? = null
    init {
        // This is end-only notification, not a verifier/grant callback. The exact upper
        // owner was independently validated before this backend captured native ownership.
        employeeDenial = temporaryExternal?.custody?.observeLifetimeDenial { withdraw() }
        if (temporaryExternal?.lifetimeDenied == true) withdraw()
    }

    val cleanupCertain: Boolean get() = !cleanupUncertain.value && continuation.cleanupCertain &&
        temporaryOwnership.cleanupCertain && evidenceOwnership.cleanupCertain && temporaryExternal?.cleanupCertain != false

    /** A failed/in-progress capture prevents native-owner close from discarding memory custody. */
    val canCloseNativeOwners: Boolean get() = cleanupCertain && temporaryOwnership.released && evidenceOwnership.released &&
        temporaryExternal?.released != false &&
        worker.value?.isCompleted != false && uncertainCandidate.value == null && when (val current = state.value) {
            PhysicalOriginalMemoryState.Withdrawn -> true
            is PhysicalOriginalMemoryState.Finished -> hasDurableClientOriginal(current.delivery, current.accepted.candidate)
            else -> false
        }

    fun startWorker(scope: CoroutineScope): Boolean {
        check(worker.value == null)
        // The independent, clean service scope is created by the native gate, not inherited from
        // an employee/UI/borrowed-connection coroutine. UNDISPATCHED reaches the first wait now.
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            workerStarted.value = true
            runEvidenceWorker()
        }
        worker.value = job
        job.invokeOnCompletion {
            withdraw()
        }
        return workerStarted.value && job.isActive && !binding.denial.denied && !nativeDenial.denied && temporaryExternal?.lifetimeDenied != true
    }

    fun markPrepared(): Boolean = worker.value?.isActive == true && cleanupCertain && !temporaryOwnership.released &&
        !evidenceOwnership.released && !binding.denial.denied && !nativeDenial.denied && !continuation.denied &&
        temporaryExternal?.denied != true &&
        state.compareAndSet(PhysicalOriginalMemoryState.Preparing, PhysicalOriginalMemoryState.AwaitingAdoption)

    fun releaseTemporary(lease: PhysicalNativeInstructionLease): PhysicalRecordingNativeRelease {
        if (lease !== prepared || lease.binding !== binding) { withdraw(); return PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN }
        releaseTemporaryOwnership()
        if (!temporaryOwnership.released || !cleanupCertain) withdraw()
        if (binding.denial.denied || nativeDenial.denied || temporaryExternal?.lifetimeDenied == true) withdraw()
        return if (temporaryOwnership.released && cleanupCertain) PhysicalRecordingNativeRelease.RELEASED
            else PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
    }

    /** Called only with the genuine core publication, after normal temporary release. No IO. */
    fun adopt(published: PhysicalPublishedInstruction,
        original: PhysicalInteractiveRequestLifetime): LocalPhysicalPublishedInstruction? {
        if (!temporaryOwnership.released || !cleanupCertain || evidenceOwnership.released || worker.value?.isActive != true ||
            binding.denial.denied || nativeDenial.denied || continuation.denied || temporaryExternal?.lifetimeDenied == true ||
            state.value !== PhysicalOriginalMemoryState.AwaitingAdoption || !published.matchesNativePreparation(binding)) return null
        return try {
            val projection = published.adoptTo(original) ?: run { withdraw(); return null }
            if (projection.canonicalRequirement != requirement.canonicalBytes || projection.domainProfile != requirement.domainProfile ||
                projection.originalOperationId != requirement.intentOperationId) { withdraw(); return null }
            val handle = LocalPhysicalPublishedInstruction(this, published, original)
            if (!state.compareAndSet(PhysicalOriginalMemoryState.AwaitingAdoption, PhysicalOriginalMemoryState.Adopted(handle)) ||
                !deliverable(handle)) { withdraw(); null } else handle
        } catch (_: Exception) { withdraw(); null }
    }

    fun deliverable(handle: LocalPhysicalPublishedInstruction): Boolean {
        val current = state.value as? PhysicalOriginalMemoryState.Adopted ?: return false
        if (current.handle !== handle || binding.denial.denied || nativeDenial.denied || continuation.denied ||
            temporaryExternal?.lifetimeDenied == true || evidenceOwnership.released || worker.value?.isActive != true) return false
        return try { handle.published.deliverableTo(handle.original).also { if (!it) withdraw() } }
            catch (_: Exception) { withdraw(); false }
    }

    fun capturePayment(handle: LocalPhysicalPublishedInstruction, outcome: LocalPaymentOutcomeV3): LocalPhysicalOriginalCaptureResult {
        if (!deliverable(handle)) return LocalPhysicalOriginalCaptureResult.Rejected
        val body = try {
            LocalRetailPaymentJournalDomain.validateRequirement(requirement)
            val target = retailDomainRequirementV3(requirement).target
            check(target.kind == SalesRecordingKind.PAYMENT_ATTESTATION)
            LocalCollectionCommandCodecV3.encode(LocalCollectionRequestV3.RecordOriginal(target, outcome))
        } catch (_: Exception) { withdraw(); return LocalPhysicalOriginalCaptureResult.InvalidInput }
        return captureBody(handle, body)
    }

    fun captureFoodPayment(handle: LocalPhysicalPublishedInstruction, outcome: LocalFoodCashOutcome): LocalPhysicalOriginalCaptureResult {
        if (!deliverable(handle)) return LocalPhysicalOriginalCaptureResult.Rejected
        val body = try {
            LocalFoodCashJournalDomain.validateRequirement(requirement)
            LocalFoodPaymentCodec.encode(LocalFoodPaymentRequest.RecordOriginal(foodPaymentDomain(requirement).target, outcome))
        } catch (_: Exception) { withdraw(); return LocalPhysicalOriginalCaptureResult.InvalidInput }
        return captureBody(handle, body)
    }

    fun captureFoodRefund(handle: LocalPhysicalPublishedInstruction, outcome: LocalFoodRefundOutcome): LocalPhysicalOriginalCaptureResult {
        if (!deliverable(handle)) return LocalPhysicalOriginalCaptureResult.Rejected
        val body = try {
            LocalFoodRefundCashJournalDomain.validateRequirement(requirement)
            LocalFoodRefundCodec.encode(LocalFoodRefundRequest.RecordOriginal(foodRefundDomain(requirement).target, outcome))
        } catch (_: Exception) { withdraw(); return LocalPhysicalOriginalCaptureResult.InvalidInput }
        return captureBody(handle, body)
    }

    fun captureRefund(handle: LocalPhysicalPublishedInstruction, outcome: LocalRefundOutcomeV3): LocalPhysicalOriginalCaptureResult {
        if (!deliverable(handle)) return LocalPhysicalOriginalCaptureResult.Rejected
        val body = try {
            LocalRetailRefundJournalDomain.validateRequirement(requirement)
            val target = retailDomainRequirementV3(requirement).target
            check(target.kind == SalesRecordingKind.REFUND_ATTESTATION)
            LocalReturnCommandCodecV3.encode(LocalReturnRequestV3.RecordOriginal(target, outcome))
        } catch (_: Exception) { withdraw(); return LocalPhysicalOriginalCaptureResult.InvalidInput }
        return captureBody(handle, body)
    }

    fun captureSupplierCash(handle: LocalPhysicalPublishedInstruction, outcome: LocalSupplierCashOutcome): LocalPhysicalOriginalCaptureResult {
        if (!deliverable(handle)) return LocalPhysicalOriginalCaptureResult.Rejected
        val body = try {
            LocalSupplierCashJournalDomain.validateRequirement(requirement)
            LocalSupplierCashCodec.encode(LocalSupplierCashRequest.RecordOriginal(supplierCashDomain(requirement).target, outcome))
        } catch (_: Exception) { withdraw(); return LocalPhysicalOriginalCaptureResult.InvalidInput }
        return captureBody(handle, body)
    }

    private fun captureBody(handle: LocalPhysicalPublishedInstruction, encoded: StoredBytes): LocalPhysicalOriginalCaptureResult {
        val current = state.value as? PhysicalOriginalMemoryState.Adopted ?: return LocalPhysicalOriginalCaptureResult.Rejected
        if (current.handle !== handle || !deliverable(handle)) { withdraw(); return LocalPhysicalOriginalCaptureResult.Rejected }
        val body = try {
            check(encoded.size in 1..LocalPhysicalJournalFormatV1.MAX_ORIGINAL_BYTES)
            StoredBytes(encoded.copyBytes()).also { continuation.validateCapturedOriginal(it) }
        } catch (_: Exception) { withdraw(); return LocalPhysicalOriginalCaptureResult.InvalidInput }
        // No suspend, native call or later UI closure before exact immutable body ownership.
        val candidate = PhysicalOriginalMemoryState.Deciding(body, journalBodyDigest(body))
        if (!state.compareAndSet(current, candidate)) return LocalPhysicalOriginalCaptureResult.Rejected
        val captured = try { handle.published.captureOriginalEvent(binding, candidate.digest) }
        catch (_: Exception) {
            uncertainCandidate.value = candidate
            state.compareAndSet(candidate, PhysicalOriginalMemoryState.Uncertain(candidate))
            binding.denial.deny(); continuation.deny(); mailbox.trySend(Unit)
            return LocalPhysicalOriginalCaptureResult.Uncertain
        }
        if (captured == null) {
            state.compareAndSet(candidate, PhysicalOriginalMemoryState.Withdrawn)
            withdraw(); releaseEvidenceIfSafe()
            return LocalPhysicalOriginalCaptureResult.Rejected
        }
        candidate.granted.value = captured
        if (captured.immutableBodyDigest != candidate.digest ||
            !state.compareAndSet(candidate, PhysicalOriginalMemoryState.Accepted(candidate))) {
            uncertainCandidate.value = candidate
            cleanupUncertain.value = true
            state.compareAndSet(candidate, PhysicalOriginalMemoryState.Uncertain(candidate))
            binding.denial.deny(); continuation.deny(); mailbox.trySend(Unit)
            return LocalPhysicalOriginalCaptureResult.Uncertain
        }
        // The grant has WON the core atom. Later logout/cancel/revoke must NOT clear this body,
        // cancel its worker or release its separate evidence leases. The instruction is finished.
        continuation.deny()
        mailbox.trySend(Unit) // Failure leaves the owned accepted body HELD, never un-captured.
        return LocalPhysicalOriginalCaptureResult.CapturedInMemory(requirement.recordingOperationId, candidate.digest)
    }

    /** End interactive ownership only. A deciding/accepted/uncertain event remains owned. */
    fun withdraw() {
        binding.denial.deny()
        continuation.deny()
        releaseTemporaryOwnership()
        try { employeeDenial?.release() } catch (_: Exception) { cleanupUncertain.value = true }
        while (true) {
            val current = state.value
            if (current !== PhysicalOriginalMemoryState.Preparing && current !== PhysicalOriginalMemoryState.AwaitingAdoption &&
                current !is PhysicalOriginalMemoryState.Adopted) break
            if (state.compareAndSet(current, PhysicalOriginalMemoryState.Withdrawn)) break
        }
        mailbox.trySend(Unit)
        releaseEvidenceIfSafe()
    }

    private suspend fun runEvidenceWorker() {
        // Scheduling backoff only; never a human-action TTL, a new capture or an evidence expiry.
        var retryDelayMillis = 100L
        try {
            while (true) {
                when (val current = state.value) {
                    is PhysicalOriginalMemoryState.Accepted -> {
                        // No employee/principal/session/lifetime reauthorization after end-only
                        // capture. These are the same canonical bytes, client force/readback FIRST.
                        val granted = current.candidate.granted.value
                        if (granted == null || granted.immutableBodyDigest != current.candidate.digest) {
                            cleanupUncertain.value = true
                            return
                        }
                        var delivery: LocalPhysicalDelivery = LocalPhysicalDelivery.Held
                        for (attempt in 0 until 3) {
                            // A later master/native denial must not suppress a possible healthy
                            // client write. Actual owners decide each access; no Boolean bypass.
                            delivery = continuation.retainCapturedOriginal(current.candidate.body)
                            if (hasDurableClientOriginal(delivery, current.candidate)) break
                            if (attempt < 2) delay(25)
                        }
                        if (!hasDurableClientOriginal(delivery, current.candidate)) {
                            // Held includes transient native contention or a busy readback after
                            // a possible write. The existing-only intake pump cannot supply these
                            // still-owned memory bytes. Keep this SAME Accepted body and its
                            // evidence leases; reschedule another bounded batch, even after UI
                            // denial/logout, instead of stranding it in terminal Finished(Held).
                            delay(retryDelayMillis)
                            retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(5_000L)
                            continue
                        }
                        if (!state.compareAndSet(current, PhysicalOriginalMemoryState.Finished(current, delivery))) {
                            cleanupUncertain.value = true
                            return
                        }
                        // Scheduling only, AFTER immutable client force/readback. Neither a
                        // UI lifetime nor a revoked employee owns this service wake. A full
                        // queue is retried by the independent existing-catalogue restart pump.
                        wakeDurableOriginal()
                        return
                    }
                    PhysicalOriginalMemoryState.Withdrawn, is PhysicalOriginalMemoryState.Finished,
                    is PhysicalOriginalMemoryState.Uncertain -> return
                    is PhysicalOriginalMemoryState.Deciding -> Unit // Never race away synchronous body ownership.
                    else -> if (binding.denial.denied || nativeDenial.denied || continuation.denied) withdraw()
                }
                // Scheduling/polling only, not an authorization clock or human-action TTL.
                // A core denial has no callback: the already-reserved worker observes it here.
                withTimeoutOrNull(100) { mailbox.receive() }
            }
        } catch (_: CancellationException) {
            // Only the independent native service owns this job. Accepted/uncertain bodies stay
            // in this non-evicting entry and forbid native-owner close if not durably retained.
            binding.denial.deny()
        } catch (_: Exception) {
            binding.denial.deny()
        } finally {
            binding.denial.deny()
            continuation.deny()
            mailbox.close()
            releaseEvidenceIfSafe()
        }
    }

    private fun releaseEvidenceIfSafe() {
        val current = state.value
        val safe = uncertainCandidate.value == null && (current === PhysicalOriginalMemoryState.Withdrawn ||
            current is PhysicalOriginalMemoryState.Finished && hasDurableClientOriginal(current.delivery, current.accepted.candidate))
        if (safe) evidenceOwnership.release()
    }

    private fun hasDurableClientOriginal(delivery: LocalPhysicalDelivery,
        candidate: PhysicalOriginalMemoryState.Deciding): Boolean = when (delivery) {
        is LocalPhysicalDelivery.ClientRetained -> delivery.operationId == requirement.recordingOperationId &&
            delivery.bodyDigest == candidate.digest
        is LocalPhysicalDelivery.MasterRawRetained -> delivery.retention.let { retained ->
            !retained.resolution && retained.requirement.canonicalBytes == requirement.canonicalBytes &&
                retained.originalOperationId == requirement.recordingOperationId && retained.originalDigest == candidate.digest &&
                retained.originalBody == candidate.body
        }
        LocalPhysicalDelivery.Held -> false
    }

    private fun releaseTemporaryOwnership() {
        temporaryOwnership.release()
        // Retention uses independent physical evidence leases. The upper client journal
        // loan only drains AFTER its temporary native preparation ownership certainly did.
        if (temporaryOwnership.released) {
            if (temporaryExternal?.releaseAfterNativeCleanup() == false && temporaryExternal?.cleanupUncertain == true) cleanupUncertain.value = true
        } else if (temporaryOwnership.cleanupUncertain) temporaryExternal?.retainNativeUncertainty()
    }

    override fun toString(): String = "LocalPhysicalOriginalCaptureEntryV1([REDACTED])"
}
