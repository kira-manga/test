package com.ukkera.pos.sales.store

import com.ukkera.pos.database.LocalMasterPhysicalNativeStageV1
import com.ukkera.pos.database.LocalMasterPhysicalInvocationAttempt
import com.ukkera.pos.database.LocalMasterPhysicalClientNativeV1
import com.ukkera.pos.database.LocalMasterPhysicalRpcBodyV1
import com.ukkera.pos.database.LocalMasterPhysicalRpcClientV1
import com.ukkera.pos.database.LocalMasterPhysicalStageDelivery
import com.ukkera.pos.database.LocalMasterPhysicalStartRequestV1
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.LocalMasterPhysicalOriginalRequestV1
import com.ukkera.pos.database.LocalMasterPhysicalOriginalRetentionV1
import com.ukkera.pos.database.PhysicalCommittedStartProjectionV1
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Closed native acquisition purpose; raw recovery never obtains a fresh-action lane. */
internal enum class DistributedPhysicalClientPurpose { INTERACTIVE, RAW_ORIGINAL_RECOVERY, EXISTING_RESOLUTION }

public enum class DistributedPhysicalClientPhase {
    REQUESTED, RESERVED, START_RETAINED, CONSUMED, PUBLISHED,
    CAPTURING, ORIGINAL_RETAINED, HELD, UNCERTAIN, RESOLUTION_RETAINED,
}

/** Original local reference only; a copied operation ID cannot construct or replace it. */
public class DistributedPhysicalClientOperation internal constructor(
    internal val owner: DistributedPhysicalClientJournalOwner,
    internal val request: MutationRequest,
    internal val employee: PhysicalJournalExternalCustodyOwnership,
    internal val requestorActorId: DomainId,
    internal val foreignResolution: Boolean = false,
) {
    public val operationId: OperationId get() = request.operationId
    internal val denial = PhysicalJournalDenial()
    private val phaseState = MutableStateFlow(DistributedPhysicalClientPhase.REQUESTED)
    internal var phase: DistributedPhysicalClientPhase
        get() = phaseState.value
        set(value) { phaseState.value = value }
    internal lateinit var attempt: LocalMasterPhysicalInvocationAttempt
    internal var invocationId: DomainId? = null
    internal var journal: LocalPhysicalJournal? = null
    internal var fresh: LocalPhysicalStartWrite.Fresh? = null
    internal var leases: PhysicalJournalLeaseOwnership? = null
    internal var evidence: PhysicalJournalLeaseOwnership? = null
    internal var received: LocalMasterPhysicalStageDelivery? = null
    internal var lastOrdinal = 0L
    internal var lastStageDigest: PayloadDigest? = null
    internal var acknowledgment: LocalMasterPhysicalRpcBodyV1.NativeResult? = null
    internal var pending: Deferred<Boolean>? = null
    internal var capture: Deferred<LocalPhysicalJournalWrite>? = null
    internal var originalValidityMillis: Long? = null
    internal var startResult: MutationReply.Recorded? = null
    internal var masterStartReady = false
    internal var original: StoredBytes? = null
    internal var originalDigest: PayloadDigest? = null
    private val instructionState = MutableStateFlow<DistributedPhysicalClientInstruction?>(null)
    internal var instruction: DistributedPhysicalClientInstruction?
        get() = instructionState.value
        set(value) { instructionState.value = value }
    internal val captureState = MutableStateFlow<DistributedPhysicalCaptureState>(DistributedPhysicalCaptureState.Preparing)
    internal val evidenceWake = Channel<Unit>(capacity = 1)

    /** End-only UI withdrawal. Accepted/uncertain original bytes remain owned independently. */
    public fun deny(): Unit {
        denial.deny()
        val current = captureState.value
        if (current is DistributedPhysicalCaptureState.Ready || current === DistributedPhysicalCaptureState.Preparing)
            captureState.compareAndSet(current, DistributedPhysicalCaptureState.Withdrawn)
        evidenceWake.trySend(Unit)
    }
    override fun toString(): String = "DistributedPhysicalClientOperation([REDACTED])"
}

/** A private one-shot native continuation after actual original-Master publication delivery. */
public class DistributedPhysicalClientInstruction internal constructor(
    private val owner: DistributedPhysicalClientJournalOwner,
    internal val operation: DistributedPhysicalClientOperation,
    public val requirement: LocalPhysicalJournalRequirementV1,
) {
    /** Necessary-only original local continuity; never substitutes for Master authorization. */
    public fun current(): Boolean = owner.instructionCurrent(this)
    public fun deny(): Unit = operation.deny()

    /** Synchronous immutable ownership before any worker hop; no supplied target or actor. */
    public fun capturePayment(outcome: LocalPaymentOutcomeV3): LocalPhysicalOriginalCaptureResult = capture {
        LocalRetailPaymentJournalDomain.validateRequirement(requirement)
        val target = retailDomainRequirementV3(requirement).target
        check(target.kind == SalesRecordingKind.PAYMENT_ATTESTATION)
        LocalCollectionCommandCodecV3.encode(LocalCollectionRequestV3.RecordOriginal(target, outcome))
    }
    public fun captureRefund(outcome: LocalRefundOutcomeV3): LocalPhysicalOriginalCaptureResult = capture {
        LocalRetailRefundJournalDomain.validateRequirement(requirement)
        val target = retailDomainRequirementV3(requirement).target
        check(target.kind == SalesRecordingKind.REFUND_ATTESTATION)
        LocalReturnCommandCodecV3.encode(LocalReturnRequestV3.RecordOriginal(target, outcome))
    }
    public fun captureFoodPayment(outcome: LocalFoodCashOutcome): LocalPhysicalOriginalCaptureResult = capture {
        LocalFoodCashJournalDomain.validateRequirement(requirement)
        LocalFoodPaymentCodec.encode(LocalFoodPaymentRequest.RecordOriginal(foodPaymentDomain(requirement).target, outcome))
    }
    public fun captureFoodRefund(outcome: LocalFoodRefundOutcome): LocalPhysicalOriginalCaptureResult = capture {
        LocalFoodRefundCashJournalDomain.validateRequirement(requirement)
        LocalFoodRefundCodec.encode(LocalFoodRefundRequest.RecordOriginal(foodRefundDomain(requirement).target, outcome))
    }
    public fun captureSupplierCash(outcome: LocalSupplierCashOutcome): LocalPhysicalOriginalCaptureResult = capture {
        LocalSupplierCashJournalDomain.validateRequirement(requirement)
        LocalSupplierCashCodec.encode(LocalSupplierCashRequest.RecordOriginal(supplierCashDomain(requirement).target, outcome))
    }
    private inline fun capture(body: () -> StoredBytes): LocalPhysicalOriginalCaptureResult {
        if (!current()) return LocalPhysicalOriginalCaptureResult.Rejected
        val encoded = try { body() } catch (_: Exception) { deny(); return LocalPhysicalOriginalCaptureResult.InvalidInput }
        return owner.captureOriginal(this, encoded)
    }

    override fun toString(): String = "DistributedPhysicalClientInstruction([REDACTED])"
}

internal sealed interface DistributedPhysicalCaptureState {
    data object Preparing : DistributedPhysicalCaptureState
    class Ready(val instruction: DistributedPhysicalClientInstruction) : DistributedPhysicalCaptureState
    class Candidate(val body: StoredBytes, val digest: PayloadDigest) : DistributedPhysicalCaptureState
    class Finished(val candidate: Candidate) : DistributedPhysicalCaptureState
    data object Withdrawn : DistributedPhysicalCaptureState
}

/**
 * Concrete one-device Client-role journal engine. Only the platform's completed original
 * enrollment/reopen factory supplies its actual catalogue/FD/key owner. A received source
 * projection never creates one. It owns a dedicated native/evidence scope, independent of
 * employee/UI/RPC callers; loss of a wait cannot cancel an accepted force or original capture.
 * No Master-role journal is opened here and no core Start/offer is reconstructed.
 */
public class DistributedPhysicalClientJournalOwner internal constructor(
    private val catalogue: LocalPhysicalAllocationCatalogue,
    private val rpc: LocalMasterPhysicalRpcClientV1,
    public val originalActorId: DomainId,
    public val actorIntentJournalId: DomainId,
    private val nativeCustody: PhysicalJournalNativePreparation,
    private val purpose: DistributedPhysicalClientPurpose,
    nativeDispatcher: CoroutineDispatcher,
    evidenceDispatcher: CoroutineDispatcher,
) {
    private val gate = Mutex()
    private val nativeRoot = SupervisorJob()
    private val nativeScope = CoroutineScope(nativeRoot + nativeDispatcher)
    private val evidenceRoot = SupervisorJob()
    private val evidenceScope = CoroutineScope(evidenceRoot + evidenceDispatcher)
    private val nativeDispatcher = nativeDispatcher
    private val denied = MutableStateFlow(false)
    private val retainedOperations = MutableStateFlow<List<DistributedPhysicalClientOperation>>(emptyList())
    private val operations: List<DistributedPhysicalClientOperation> get() = retainedOperations.value
    // A returned recovery FD is retained before any read/close. Unknown opens never disappear.
    private val recoveryJournals = mutableMapOf<DomainId, LocalPhysicalJournal>()
    private val recoveryOpenUnknown = mutableSetOf<DomainId>()
    private var unreturnedNativeOwner = false
    private var closing: CompletableDeferred<LocalPhysicalSpoolClose>? = null

    public val binding: LocalPhysicalCatalogueBinding get() = catalogue.binding
    public val storageProfile: LocalPhysicalSpoolStorageProfileV1 get() = catalogue.storageProfile
    public val nativeProjection: LocalMasterPhysicalClientNativeV1 get() = LocalMasterPhysicalClientNativeV1(
        binding.installationId, binding.catalogueId, binding.keyId, actorIntentJournalId, storageProfile.name)

    init {
        require(binding.role == LocalPhysicalSpoolRole.CLIENT && binding.scope == rpc.scope &&
            binding.deviceId == rpc.originalDeviceId)
    }

    /** The upper native root supplies a loan of its SAME successful original actor intent owner. */
    public suspend fun begin(request: MutationRequest,
        employeeCustody: LocalPhysicalExternalCustody): DistributedPhysicalClientOperation? {
        val employee = PhysicalJournalExternalCustodyOwnership(employeeCustody)
        var transferred = false
        try {
            currentCoroutineContext().ensureActive()
            if (purpose != DistributedPhysicalClientPurpose.INTERACTIVE || denied.value ||
                !rpc.isCurrent() || employee.denied) return null
            operations.filter { it.phase == DistributedPhysicalClientPhase.ORIGINAL_RETAINED }.forEach { retire(it) }
            // This is an existing native-linked observation, never a retry of human action.
            // Only the exact same canonical request may release a settled previous owner;
            // the next invocation still needs fresh current authorization and native recheck.
            if (distributedResolutionRequest(request)) operations.filter {
                it.request.operationId == request.operationId && it.request.command == request.command &&
                    it.request.payload == request.payload && it.request.actionApprovalId == request.actionApprovalId
            }.forEach { retireInterruptedResolution(it) }
            transferred = true
            return beginOwned(request, employee, originalActorId)
        } finally { if (!transferred) check(employee.releaseAfterNativeCleanup()) }
    }

    /** Read-only selection from this SAME genuine original catalogue. No employee authority
     * is inferred, no file/key is enrolled, and every native original must be readable. */
    public suspend fun supportsLinkedResolution(request: MutationRequest, resolverActorId: DomainId): Boolean {
        fun unavailable(failure: Throwable? = null): Boolean {
            com.ukkera.pos.database.reportLocalMasterRpcReadFailure(
                com.ukkera.pos.database.LocalMasterRpcReadStage.PHYSICAL_ORIGINAL_SOURCE_UNAVAILABLE,
                failure ?: IllegalStateException())
            return false
        }
        if (purpose == DistributedPhysicalClientPurpose.RAW_ORIGINAL_RECOVERY || denied.value || !rpc.isCurrent() ||
            distributedResolutionActor(request) != resolverActorId) return unavailable()
        val originals = retainedOriginals() ?: return unavailable()
        val selected = originals.filter { it.receipt.binding.requirement.let { requirement ->
            requirement.command == request.command && requirement.resolutionOperationId == request.operationId } }
        if (selected.size != 1) return unavailable()
        val snapshot = selected.single()
        val requirement = snapshot.receipt.binding.requirement
        val original = snapshot.original ?: return unavailable()
        if (snapshot.receipt.binding.owner != binding || requirement.originalActorId != originalActorId ||
            requirement.clientDeviceId != rpc.originalDeviceId || !snapshot.actionMayHaveStarted || snapshot.certificate == null) return unavailable()
        return try {
            val domain = localPhysicalDomainOwners(requirement)
            if (domain.client != binding || domain.domain.classifyOriginal(requirement, original) != LocalPhysicalOriginalKind.UNKNOWN) unavailable()
            else { localPhysicalResolutionDescriptor(domain.domain, requirement, original, resolverActorId, request.payload); true }
        } catch (failure: IllegalArgumentException) { unavailable(failure) }
        catch (failure: IllegalStateException) { unavailable(failure) }
    }

    /** Current resolver custody is distinct from the recorded actor/catalogue custody. The
     * actual Master still authorizes this resolver before either native linked write. */
    public suspend fun beginLinkedResolution(request: MutationRequest, resolverActorId: DomainId,
        resolverCustody: LocalPhysicalExternalCustody): DistributedPhysicalClientResolution? {
        val employee = PhysicalJournalExternalCustodyOwnership(resolverCustody)
        var transferred = false
        try {
            currentCoroutineContext().ensureActive()
            if (employee.denied || !supportsLinkedResolution(request, resolverActorId)) return null
            operations.filter { it.phase == DistributedPhysicalClientPhase.ORIGINAL_RETAINED }.forEach { retire(it) }
            operations.filter { it.request.operationId == request.operationId && it.request.command == request.command &&
                it.request.payload == request.payload && it.request.actionApprovalId == request.actionApprovalId &&
                it.requestorActorId == resolverActorId }.forEach { retireInterruptedResolution(it) }
            transferred = true
            return beginOwned(request, employee, resolverActorId)?.let { DistributedPhysicalClientResolution(this, it) }
        } finally { if (!transferred) check(employee.releaseAfterNativeCleanup()) }
    }

    /** Resolution-only candidate on B's own real catalogue. The Master must separately
     * qualify A's original and this current resolver; no A owner or Start is returned. */
    public suspend fun beginForeignLinkedResolution(request: MutationRequest, resolverActorId: DomainId,
        resolverCustody: LocalPhysicalExternalCustody): DistributedPhysicalClientResolution? {
        val employee = PhysicalJournalExternalCustodyOwnership(resolverCustody)
        var transferred = false
        try {
            currentCoroutineContext().ensureActive()
            if (purpose != DistributedPhysicalClientPurpose.INTERACTIVE || denied.value || !rpc.isCurrent() ||
                employee.denied || resolverActorId != originalActorId || distributedResolutionActor(request) != resolverActorId ||
                retainedOriginals() == null) return null
            for (old in operations.filter { it.foreignResolution && it.operationId == request.operationId &&
                it.request.command == request.command && it.request.payload == request.payload && it.requestorActorId == resolverActorId })
                if (!retireInterruptedResolution(old)) return null
            transferred = true
            return beginOwned(request, employee, resolverActorId, foreignResolution = true)?.let { DistributedPhysicalClientResolution(this, it) }
        } finally { if (!transferred) check(employee.releaseAfterNativeCleanup()) }
    }

    private suspend fun beginOwned(request: MutationRequest,
        employee: PhysicalJournalExternalCustodyOwnership, requestorActorId: DomainId,
        foreignResolution: Boolean = false): DistributedPhysicalClientOperation? {
        val operation = DistributedPhysicalClientOperation(this, request, employee, requestorActorId, foreignResolution)
        var retained = false
        try {
            return gate.withLock {
                if (denied.value || employee.denied || operations.size >= MAX_ACTIVE ||
                    operations.any { it.operationId == request.operationId || it.phase in FRESH_PHASES }) return@withLock null
                retainedOperations.value = operations + operation
                retained = true // Retain the loan before observer registration can throw/retire it.
                try {
                    operation.attempt = rpc.newInvocationAttempt(operation)
                    employee.observeInvocation { operation.deny() }
                }
                catch (_: Exception) { operation.deny(); operation.phase = DistributedPhysicalClientPhase.UNCERTAIN }
                operation
            }
        } finally {
            if (!retained) employee.releaseAfterNativeCleanup()
        }
    }

    /**
     * One bounded Client-initiated exchange. The wire body is selected from owned local state.
     * The actual SDK response callback alone can enter a native stage. A duplicate stage returns
     * its retained acknowledgment without repeating allocation, first-force or consumption.
     */
    public suspend fun advance(operation: DistributedPhysicalClientOperation,
        credentials: LocalGatewayCredentials): DistributedPhysicalClientPhase? {
        currentCoroutineContext().ensureActive()
        if (!owns(operation) || denied.value || !rpc.isCurrent()) return null
        val body = gate.withLock {
            if (!owns(operation) || operation.denial.denied || operation.employee.denied) return@withLock null
            operation.acknowledgment ?: operation.invocationId?.let { LocalMasterPhysicalRpcBodyV1.Next(it) }
                ?: LocalMasterPhysicalRpcBodyV1.Begin(LocalMasterPhysicalStartRequestV1.capture(operation.request), nativeProjection)
        } ?: return null
        rpc.exchange(credentials, body, operation.requestorActorId, operation.operationId, operation, operation.attempt) { delivery ->
            acceptStage(operation, delivery)
        }
        return gate.withLock { operation.phase }
    }

    /** Original Publish drives consumption first; polls share the SAME before-Begin attempt. */
    public suspend fun publish(operation: DistributedPhysicalClientOperation,
        credentials: LocalGatewayCredentials): DistributedPhysicalClientInstruction? {
        currentCoroutineContext().ensureActive()
        val invocation = gate.withLock {
            if (!owns(operation) || denied.value || operation.denial.denied || operation.employee.denied ||
                operation.phase !in setOf(DistributedPhysicalClientPhase.START_RETAINED, DistributedPhysicalClientPhase.CONSUMED) ||
                !operation.attempt.preparationCurrent()) null else operation.invocationId
        } ?: return null
        // A Client certificate ACK may still be owed while the Master Start callback is
        // waiting. Flush that exact ACK and wait for its actual settled offer before Publish.
        for (poll in 0 until 32) {
            if (gate.withLock { operation.masterStartReady }) break
            if (!operation.attempt.preparationCurrent() || advance(operation, credentials) == null) {
                operation.deny(); return null
            }
        }
        if (!gate.withLock { operation.masterStartReady }) { operation.deny(); return null }
        rpc.exchange(credentials, LocalMasterPhysicalRpcBodyV1.Publish(invocation), originalActorId,
            operation.operationId, operation, operation.attempt) { delivery -> acceptStage(operation, delivery) }
        // Fixed vocabulary/ordinal bound. No unbounded busy-poll and no clock renewal.
        repeat(32) {
            val current = phase(operation)
            if (current == DistributedPhysicalClientPhase.PUBLISHED)
                return gate.withLock { operation.instruction?.takeIf { it.current() } }
            if (current !in setOf(DistributedPhysicalClientPhase.START_RETAINED, DistributedPhysicalClientPhase.CONSUMED) ||
                !operation.attempt.preparationCurrent()) { operation.deny(); return null }
            advance(operation, credentials) ?: return null
        }
        operation.deny()
        return null
    }

    public suspend fun phase(operation: DistributedPhysicalClientOperation): DistributedPhysicalClientPhase? =
        gate.withLock { operation.phase.takeIf { owns(operation) } }

    internal fun instructionCurrent(instruction: DistributedPhysicalClientInstruction): Boolean {
        val operation = instruction.operation
        return !denied.value && !operation.denial.denied && !operation.employee.denied && rpc.isCurrent() &&
            operation.owner === this && operation.instruction === instruction &&
            (operation.captureState.value as? DistributedPhysicalCaptureState.Ready)?.instruction === instruction &&
            operation.phase == DistributedPhysicalClientPhase.PUBLISHED &&
            operation.originalValidityMillis?.let(operation.attempt::withinOriginalWindow) == true
    }

    internal fun originalStartDelivery(operation: DistributedPhysicalClientOperation,
        delivery: LocalMasterPhysicalStageDelivery, journal: LocalPhysicalJournal): Boolean =
        operation.owner === this && operation.journal === journal && operation.received === delivery &&
            delivery.belongsTo(rpc, operation) && delivery.belongsTo(operation.attempt) &&
            delivery.response.stage == LocalMasterPhysicalNativeStageV1.RETAIN_START &&
            delivery.response.originalActorId == originalActorId && delivery.response.operationId == operation.operationId

    internal fun freshStartDelivery(operation: DistributedPhysicalClientOperation): Boolean =
        !denied.value && !operation.denial.denied && !operation.employee.denied && rpc.isCurrent() &&
            operation.phase == DistributedPhysicalClientPhase.RESERVED && operation.attempt.preparationCurrent()

    private suspend fun acceptStage(operation: DistributedPhysicalClientOperation,
        delivery: LocalMasterPhysicalStageDelivery): Boolean {
        if (!delivery.belongsTo(rpc, operation) || !delivery.belongsTo(operation.attempt)) return false
        val response = delivery.response
        if (response.originalActorId != operation.requestorActorId || response.operationId != operation.operationId) return false
        val digest = journalBodyDigest(StoredBytes(journalFrame(listOf(journalText(response.stage.name),
            journalText(response.invocationId.value), journalText(response.ordinal.toString()),
            journalText(response.originalValidityMillis?.toString() ?: ""), response.payload.copyBytes()),
            LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES)))
        val work = gate.withLock {
            if (!owns(operation) || operation.captureState.value is DistributedPhysicalCaptureState.Candidate ||
                operation.captureState.value is DistributedPhysicalCaptureState.Finished) return@withLock null
            delivery.recordedResult()?.let { operation.startResult = it }
            if (response.offerReady) operation.masterStartReady = true
            val invocation = operation.invocationId
            if (invocation != null && invocation != response.invocationId) { operation.deny(); return@withLock null }
            if (response.stage in TERMINAL_STAGES) {
                operation.invocationId = response.invocationId
                if (response.stage == LocalMasterPhysicalNativeStageV1.PENDING) return@withLock CompletableDeferred(true)
                operation.deny()
                operation.phase = if (response.stage == LocalMasterPhysicalNativeStageV1.UNCERTAIN)
                    DistributedPhysicalClientPhase.UNCERTAIN else DistributedPhysicalClientPhase.HELD
                return@withLock CompletableDeferred(true)
            }
            if (response.ordinal == operation.lastOrdinal) {
                if (digest != operation.lastStageDigest) { operation.deny(); return@withLock null }
                return@withLock operation.pending ?: CompletableDeferred(operation.acknowledgment != null)
            }
            if (response.ordinal != operation.lastOrdinal + 1 || operation.pending?.isCompleted == false ||
                denied.value || operation.denial.denied || operation.employee.denied) return@withLock null
            operation.invocationId = response.invocationId
            operation.received = delivery
            operation.lastOrdinal = response.ordinal
            operation.lastStageDigest = digest
            operation.acknowledgment = null
            nativeScope.async(start = CoroutineStart.LAZY) {
                performNativeStage(operation, delivery)
            }.also { operation.pending = it }
        } ?: return false
        work.start()
        // Caller cancellation does not cancel the separately owned native Deferred.
        return work.await()
    }

    private suspend fun performNativeStage(operation: DistributedPhysicalClientOperation,
        delivery: LocalMasterPhysicalStageDelivery): Boolean = withContext(nativeDispatcher) {
        gate.withLock {
            try {
                val response = delivery.response
                val result = when (response.stage) {
                    LocalMasterPhysicalNativeStageV1.RESERVE_CLIENT -> reserve(operation, response.payload)
                    LocalMasterPhysicalNativeStageV1.RECHECK_CLIENT -> recheck(operation, response.payload)
                    LocalMasterPhysicalNativeStageV1.RETAIN_RESOLUTION -> retainResolution(operation, response.payload)
                    LocalMasterPhysicalNativeStageV1.RETAIN_START -> retainStart(operation, delivery)
                    LocalMasterPhysicalNativeStageV1.CONSUME_START -> consume(operation, response.payload)
                    LocalMasterPhysicalNativeStageV1.PUBLICATION -> adopt(operation, delivery)
                    else -> null
                }
                if (result == null) {
                    operation.deny()
                    if (operation.phase != DistributedPhysicalClientPhase.UNCERTAIN)
                        operation.phase = DistributedPhysicalClientPhase.HELD
                    false
                } else {
                    operation.acknowledgment = LocalMasterPhysicalRpcBodyV1.NativeResult(response.invocationId,
                        response.ordinal, response.stage, result)
                    true
                }
            } catch (_: Exception) {
                operation.deny()
                operation.phase = DistributedPhysicalClientPhase.UNCERTAIN
                false
            }
        }
    }

    private fun reserve(operation: DistributedPhysicalClientOperation, bytes: StoredBytes): StoredBytes? {
        if (operation.phase != DistributedPhysicalClientPhase.REQUESTED || operation.journal != null ||
            unreturnedNativeOwner) return null
        val requirement = LocalPhysicalJournalRequirementV1.decode(bytes)
        val owners = localPhysicalDomainOwners(requirement)
        val resolution = distributedResolutionRequest(operation.request)
        val foreign = LocalPhysicalForeignResolutionDomain.fromRequirement(requirement)
        if ((foreign != null) != operation.foreignResolution || operation.foreignResolution && !resolution) return null
        val refs = foreign?.references(requirement)
        if (owners.client != binding || requirement.scope != rpc.scope || requirement.clientDeviceId != rpc.originalDeviceId ||
            (if (refs != null) refs.resolverActorId != originalActorId || refs.resolverIntentJournalId != actorIntentJournalId
                else requirement.originalActorId != originalActorId) ||
            (if (resolution) distributedResolutionActor(operation.request) != operation.requestorActorId else operation.requestorActorId != originalActorId) ||
            (if (resolution) requirement.resolutionOperationId else requirement.intentOperationId) != operation.operationId ||
            requirement.command != operation.request.command || requirement.clientStorageProfile != storageProfile) return null
        unreturnedNativeOwner = true // Retain uncertainty BEFORE attempting an actual native open.
        val opened = if (operation.foreignResolution) {
            val receipt = catalogue.readExpectedReceipt(requirement.clientAllocationId)
            if (operations.any { it !== operation && it.journal?.receipt?.binding?.allocationId == requirement.clientAllocationId }) {
                unreturnedNativeOwner = false
                return null
            }
            if (receipt == null) catalogue.allocate(requirement, owners.domain)
            else if (receipt.binding.requirement.canonicalBytes == requirement.canonicalBytes)
                catalogue.openExisting(requirement.clientAllocationId, owners.domain)
            else { unreturnedNativeOwner = false; return null }
        } else if (resolution) {
            val receipt = catalogue.readExpectedReceipt(requirement.clientAllocationId)
            if (receipt == null || receipt.binding.requirement.canonicalBytes != requirement.canonicalBytes ||
                operations.any { it !== operation && it.journal?.receipt?.binding?.allocationId == requirement.clientAllocationId }) {
                unreturnedNativeOwner = false
                return null
            }
            catalogue.openExisting(requirement.clientAllocationId, owners.domain)
        } else catalogue.allocate(requirement, owners.domain)
        if (opened !is LocalPhysicalJournalOpen.Opened) {
            if (opened !== LocalPhysicalJournalOpen.Held) unreturnedNativeOwner = false
            return null
        }
        val journal = opened.journal
        operation.journal = journal // Own the actual returned FD before any later comparison/IO.
        unreturnedNativeOwner = false
        val leases = (if (operation.foreignResolution) captureForeignResolutionJournal(journal, operation::deny)
            else if (resolution) captureResolutionJournal(journal, operation::deny)
            else captureSingleJournal(journal, allocatedOnly = true, operation::deny)) ?: return null
        operation.leases = PhysicalJournalLeaseOwnership(leases)
        operation.phase = DistributedPhysicalClientPhase.RESERVED
        return journal.receipt.sourceCommitment
    }

    private fun retainResolution(operation: DistributedPhysicalClientOperation, body: StoredBytes): StoredBytes? {
        if (!distributedResolutionRequest(operation.request) || operation.phase != DistributedPhysicalClientPhase.RESERVED ||
            operation.request.payload != body || operation.denial.denied || operation.employee.denied || denied.value) return null
        val journal = operation.journal ?: return null
        val requirement = journal.receipt.binding.requirement
        if (operation.foreignResolution) {
            val refs = checkNotNull(LocalPhysicalForeignResolutionDomain.fromRequirement(requirement)).references(requirement)
            if (refs.resolverActorId != operation.requestorActorId || refs.resolverIntentJournalId != actorIntentJournalId) return null
            val write = journal.retainForeignResolution(body)
            if (write !in RETAINED_WRITES || (journal.readForeignResolution() as? LocalForeignResolutionRead.Read)?.body != body) return null
            operation.originalDigest = refs.sourceOriginalDigest
            operation.phase = DistributedPhysicalClientPhase.RESOLUTION_RETAINED
            return foreignResolutionAcknowledgment(requirement, body)
        }
        val snapshot = (journal.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot ?: return null
        val original = snapshot.original ?: return null
        if (!snapshot.actionMayHaveStarted || snapshot.certificate == null ||
            journal.domain.classifyOriginal(requirement, original) != LocalPhysicalOriginalKind.UNKNOWN) return null
        val descriptor = localPhysicalResolutionDescriptor(journal.domain, requirement, original, operation.requestorActorId, body)
        val write = journal.retainResolution(requirement.resolutionOperationId, operation.requestorActorId, descriptor, body)
        if (write != LocalPhysicalJournalWrite.RETAINED && write != LocalPhysicalJournalWrite.ALREADY_RETAINED) return null
        val linked = (journal.readResolution() as? LocalPhysicalResolutionRead.Read)?.resolution ?: return null
        if (linked.body != body || linked.resolverActorId != operation.requestorActorId || linked.originalDigest != journalBodyDigest(original) ||
            linked.resolverAuthorization != descriptor) return null
        operation.original = original
        operation.originalDigest = journalBodyDigest(original)
        operation.phase = DistributedPhysicalClientPhase.RESOLUTION_RETAINED
        return distributedResolutionAcknowledgment(requirement.clientAllocationId, requirement.canonicalBytes, original, body)
    }

    private fun recheck(operation: DistributedPhysicalClientOperation, requirement: StoredBytes): StoredBytes? {
        val resolution = distributedResolutionRequest(operation.request)
        if (operation.phase != (if (resolution) DistributedPhysicalClientPhase.RESOLUTION_RETAINED else DistributedPhysicalClientPhase.RESERVED) ||
            denied.value || operation.denial.denied ||
            operation.employee.denied) return null
        val journal = operation.journal ?: return null
        if (journal.receipt.binding.requirement.canonicalBytes != requirement) return null
        if (operation.foreignResolution) {
            if ((journal.readForeignResolution() as? LocalForeignResolutionRead.Read)?.body != operation.request.payload) return null
        } else if (resolution && (journal.readResolution() as? LocalPhysicalResolutionRead.Read)?.resolution?.body != operation.request.payload) return null
        val leases = (if (operation.foreignResolution) captureForeignResolutionJournal(journal, operation::deny)
            else if (resolution) captureResolutionJournal(journal, operation::deny)
            else captureSingleJournal(journal, allocatedOnly = true, operation::deny)) ?: return null
        val owned = PhysicalJournalLeaseOwnership(leases)
        owned.release()
        if (!owned.released) return null
        if (resolution) {
            operation.leases?.release()
            if (operation.leases?.released != true) return null
        }
        return journal.receipt.sourceCommitment
    }

    private fun retainStart(operation: DistributedPhysicalClientOperation,
        delivery: LocalMasterPhysicalStageDelivery): StoredBytes? {
        val journal = operation.journal ?: return null
        if (operation.phase != DistributedPhysicalClientPhase.RESERVED) return null
        val original = DistributedPhysicalStartDelivery.fromOriginal(this, operation, delivery, journal) ?: return null
        when (val result = journal.persistDeliveredStart(original)) {
            is LocalPhysicalStartWrite.Fresh -> {
                operation.fresh = result
                operation.phase = DistributedPhysicalClientPhase.START_RETAINED
            }
            LocalPhysicalStartWrite.Retained, LocalPhysicalStartWrite.AlreadyRetained -> {
                operation.deny()
                operation.phase = DistributedPhysicalClientPhase.HELD
                return null // Evidence retained; never a positive Fresh acknowledgment.
            }
            else -> return null
        }
        return certificateAcknowledgment(journal, delivery.response.payload)
    }

    private fun consume(operation: DistributedPhysicalClientOperation, certificate: StoredBytes): StoredBytes? {
        if (denied.value || operation.denial.denied || operation.employee.denied || !rpc.isCurrent() ||
            operation.phase != DistributedPhysicalClientPhase.START_RETAINED) return null
        val journal = operation.journal ?: return null
        val fresh = operation.fresh ?: return null
        if (fresh.certificate != certificate || journal.consumeFresh(fresh) != LocalPhysicalJournalWrite.RETAINED) return null
        // Evidence custody is real and independent BEFORE the Master can publish an instruction.
        val evidence = captureSingleJournal(journal, allocatedOnly = false, operation::deny) ?: return null
        operation.evidence = PhysicalJournalLeaseOwnership(evidence)
        operation.leases?.release()
        if (operation.leases?.released != true) return null
        // The worker reaches its bounded wait BEFORE any Master publication/adoption.
        operation.capture = evidenceScope.async(start = CoroutineStart.UNDISPATCHED) { retainAcceptedOriginal(operation, journal) }
        if (operation.capture?.isActive != true) return null
        operation.phase = DistributedPhysicalClientPhase.CONSUMED
        return certificateAcknowledgment(journal, certificate)
    }

    private fun adopt(operation: DistributedPhysicalClientOperation,
        delivery: LocalMasterPhysicalStageDelivery): StoredBytes? {
        if (denied.value || operation.denial.denied || operation.employee.denied ||
            operation.phase != DistributedPhysicalClientPhase.CONSUMED || !delivery.currentForPublication()) return null
        val journal = operation.journal ?: return null
        val fresh = operation.fresh ?: return null
        val validity = delivery.response.originalValidityMillis ?: return null
        if (!operation.attempt.withinOriginalWindow(validity)) return null
        val requirement = journal.receipt.binding.requirement
        if (requirement.canonicalBytes != delivery.response.payload || operation.evidence?.released != false ||
            !journal.takeInstruction(fresh) || !delivery.currentForPublication()) return null
        val instruction = DistributedPhysicalClientInstruction(this, operation, requirement)
        operation.originalValidityMillis = validity
        operation.instruction = instruction
        if (operation.capture?.isActive != true || !operation.captureState.compareAndSet(
                DistributedPhysicalCaptureState.Preparing, DistributedPhysicalCaptureState.Ready(instruction))) return null
        operation.phase = DistributedPhysicalClientPhase.PUBLISHED
        return certificateAcknowledgment(journal, fresh.certificate)
    }

    internal fun captureOriginal(instruction: DistributedPhysicalClientInstruction,
        input: StoredBytes): LocalPhysicalOriginalCaptureResult {
        val operation = instruction.operation
        val current = operation.captureState.value as? DistributedPhysicalCaptureState.Ready
            ?: return LocalPhysicalOriginalCaptureResult.Rejected
        if (current.instruction !== instruction || !instructionCurrent(instruction)) {
            operation.deny(); return LocalPhysicalOriginalCaptureResult.Rejected
        }
        val candidate = try {
            check(input.size in 1..LocalPhysicalJournalFormatV1.MAX_ORIGINAL_BYTES)
            val body = StoredBytes(input.copyBytes())
            localPhysicalDomainOwners(instruction.requirement).domain.classifyOriginal(instruction.requirement, body)
            DistributedPhysicalCaptureState.Candidate(body, journalBodyDigest(body))
        } catch (_: Exception) { operation.deny(); return LocalPhysicalOriginalCaptureResult.InvalidInput }
        // Copy/classification/digest may span the original finite window. Sample this exact
        // still-published instruction again immediately before the one-shot acceptance cut.
        if (!instructionCurrent(instruction)) { operation.deny(); return LocalPhysicalOriginalCaptureResult.Rejected }
        // The actual immutable body, not a coroutine closure or UI input, wins the one-shot CAS.
        if (!operation.captureState.compareAndSet(current, candidate)) return LocalPhysicalOriginalCaptureResult.Rejected
        operation.phase = DistributedPhysicalClientPhase.CAPTURING
        if (operation.evidenceWake.trySend(Unit).isFailure) {
            operation.deny()
            operation.phase = DistributedPhysicalClientPhase.UNCERTAIN
            return LocalPhysicalOriginalCaptureResult.Uncertain
        }
        return LocalPhysicalOriginalCaptureResult.CapturedInMemory(instruction.requirement.recordingOperationId, candidate.digest)
    }

    private suspend fun retainAcceptedOriginal(operation: DistributedPhysicalClientOperation,
        journal: LocalPhysicalJournal): LocalPhysicalJournalWrite {
        operation.evidenceWake.receive()
        return withContext(nativeDispatcher) { gate.withLock {
            val candidate = operation.captureState.value as? DistributedPhysicalCaptureState.Candidate
            if (candidate == null) {
                operation.evidence?.release()
                if (operation.evidence?.released == true) operation.employee.releaseAfterNativeCleanup()
                return@withLock LocalPhysicalJournalWrite.HELD
            }
            operation.original = candidate.body
            operation.originalDigest = candidate.digest
            val result = try {
                val write = journal.retainOriginal(journal.receipt.binding.requirement.recordingOperationId, candidate.body)
                val retained = (journal.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot
                if (write in RETAINED_WRITES && retained?.original == candidate.body && retained.originalDigest == candidate.digest) {
                    operation.captureState.value = DistributedPhysicalCaptureState.Finished(candidate)
                    operation.phase = DistributedPhysicalClientPhase.ORIGINAL_RETAINED
                    operation.evidence?.release()
                    if (operation.evidence?.released == true) operation.employee.releaseAfterNativeCleanup()
                } else operation.phase = DistributedPhysicalClientPhase.UNCERTAIN
                write
            } catch (_: Exception) {
                operation.phase = DistributedPhysicalClientPhase.UNCERTAIN
                LocalPhysicalJournalWrite.HELD
            }
            operation.deny() // Candidate/Finished state is deliberately not erased.
            result
        } }
    }

    /**
     * Retire only after exact durable-original readback and certain native/loan closure. The
     * immutable catalogue allocation survives removal; allocate(existing ID) cannot rearm it.
     * Unresolved/unknown jobs, force outcomes and close outcomes are never evicted by age.
     */
    public suspend fun retire(operation: DistributedPhysicalClientOperation): Boolean = withContext(nativeDispatcher) {
        gate.withLock {
            if (!owns(operation) || operation.phase != DistributedPhysicalClientPhase.ORIGINAL_RETAINED ||
                operation.pending?.isCompleted == false || operation.capture?.isCompleted == false ||
                operation.captureState.value !is DistributedPhysicalCaptureState.Finished ||
                operation.leases?.released == false || operation.evidence?.released == false) return@withLock false
            val journal = operation.journal ?: return@withLock false
            val snapshot = (journal.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot ?: return@withLock false
            if (snapshot.original != operation.original || snapshot.originalDigest != operation.originalDigest ||
                snapshot.original == null || !operation.employee.releaseAfterNativeCleanup() ||
                journal.close() != LocalPhysicalSpoolClose.CLOSED) return@withLock false
            operation.deny()
            retainedOperations.value = operations.filterNot { it === operation }
            true
        }
    }

    public suspend fun retireResolution(operation: DistributedPhysicalClientOperation): Boolean = withContext(nativeDispatcher) {
        gate.withLock {
            if (!owns(operation) || !distributedResolutionRequest(operation.request) || operation.startResult == null ||
                operation.pending?.isCompleted == false || operation.leases?.released != true || operation.evidence?.released == false)
                return@withLock false
            val journal = operation.journal ?: return@withLock false
            if (operation.foreignResolution) {
                if ((journal.readForeignResolution() as? LocalForeignResolutionRead.Read)?.body != operation.request.payload ||
                    !operation.employee.releaseAfterNativeCleanup() || journal.close() != LocalPhysicalSpoolClose.CLOSED) return@withLock false
                operation.deny()
                retainedOperations.value = operations.filterNot { it === operation }
                return@withLock true
            }
            val linked = (journal.readResolution() as? LocalPhysicalResolutionRead.Read)?.resolution ?: return@withLock false
            if (linked.body != operation.request.payload || linked.resolverActorId != operation.requestorActorId ||
                !operation.employee.releaseAfterNativeCleanup() || journal.close() != LocalPhysicalSpoolClose.CLOSED) return@withLock false
            operation.deny()
            retainedOperations.value = operations.filterNot { it === operation }
            true
        }
    }

    private suspend fun retireInterruptedResolution(operation: DistributedPhysicalClientOperation): Boolean = withContext(nativeDispatcher) {
        gate.withLock {
            if (!owns(operation) || !distributedResolutionRequest(operation.request) ||
                operation.phase !in setOf(DistributedPhysicalClientPhase.RESOLUTION_RETAINED, DistributedPhysicalClientPhase.HELD) ||
                operation.pending?.isCompleted == false || operation.capture != null || operation.evidence != null)
                return@withLock false
            val journal = operation.journal ?: return@withLock false
            if (operation.foreignResolution) {
                val body = journal.readForeignResolution() as? LocalForeignResolutionRead.Read ?: return@withLock false
                if (body.body != operation.request.payload) return@withLock false
                val refs = checkNotNull(LocalPhysicalForeignResolutionDomain.fromRequirement(journal.receipt.binding.requirement))
                    .references(journal.receipt.binding.requirement)
                if (refs.resolverActorId != originalActorId || refs.resolverIntentJournalId != actorIntentJournalId) return@withLock false
                operation.leases?.release()
                if (operation.leases?.released != true || !operation.employee.releaseAfterNativeCleanup() ||
                    journal.close() != LocalPhysicalSpoolClose.CLOSED) return@withLock false
                operation.deny()
                retainedOperations.value = operations.filterNot { it === operation }
                return@withLock true
            }
            val snapshot = (journal.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot ?: return@withLock false
            val original = snapshot.original ?: return@withLock false
            val requirement = journal.receipt.binding.requirement
            val linked = (journal.readResolution() as? LocalPhysicalResolutionRead.Read)?.resolution ?: return@withLock false
            if (!snapshot.actionMayHaveStarted || snapshot.certificate == null || requirement.originalActorId != originalActorId ||
                requirement.resolutionOperationId != operation.operationId || requirement.command != operation.request.command ||
                journal.domain.classifyOriginal(requirement, original) != LocalPhysicalOriginalKind.UNKNOWN ||
                linked.body != operation.request.payload || linked.resolverActorId != operation.requestorActorId ||
                linked.originalDigest != journalBodyDigest(original) || linked.resolverAuthorization !=
                    localPhysicalResolutionDescriptor(journal.domain, requirement, original, operation.requestorActorId, operation.request.payload))
                return@withLock false
            operation.leases?.release()
            if (operation.leases?.released != true || !operation.employee.releaseAfterNativeCleanup() ||
                journal.close() != LocalPhysicalSpoolClose.CLOSED) return@withLock false
            operation.deny()
            retainedOperations.value = operations.filterNot { it === operation }
            true
        }
    }

    public suspend fun startResult(operation: DistributedPhysicalClientOperation): MutationReply.Recorded? = gate.withLock {
        operation.startResult.takeIf { owns(operation) && rpc.isCurrent() && !operation.employee.denied }
    }

    /** Native immutable originals only. A reconnected actual SDK owner must retain the complete
     * SAME original pairing DATA; an employee login or a copied DTO cannot select another root. */
    public suspend fun redeliverOriginals(recoveryRpc: LocalMasterPhysicalRpcClientV1): Map<DomainId, LocalMasterPhysicalOriginalRetentionV1>? {
        if (!rpc.sameNativeOriginalAs(recoveryRpc) || !recoveryRpc.isCurrent()) return null
        val originals = retainedOriginals() ?: return null
        val results = linkedMapOf<DomainId, LocalMasterPhysicalOriginalRetentionV1>()
        for (snapshot in originals) {
            val requirement = snapshot.receipt.binding.requirement
            val certificate = snapshot.certificate ?: return null
            val body = snapshot.original ?: return null
            if (requirement.originalActorId != originalActorId || requirement.clientDeviceId != binding.deviceId ||
                snapshot.receipt.binding.owner != binding || !snapshot.actionMayHaveStarted) return null
            val response = recoveryRpc.retainOriginal(LocalMasterPhysicalOriginalRequestV1(requirement.clientAllocationId,
                requirement.canonicalBytes, certificate, snapshot.receipt.sourceCommitment, body)) ?: return null
            results[requirement.clientAllocationId] = response
        }
        return results.toMap()
    }

    /** Immutable original enumeration is native-only and creates no fresh instruction/session. */
    internal suspend fun retainedOriginals(): List<LocalPhysicalOriginalSnapshot>? = withContext(nativeDispatcher) {
        gate.withLock {
            val selectors = catalogue.retainedAllocationSelectors() ?: return@withLock null
            val result = mutableListOf<LocalPhysicalOriginalSnapshot>()
            for (id in selectors) {
                val receipt = catalogue.readExpectedReceipt(id) ?: return@withLock null
                val domain = localPhysicalDomainOwners(receipt.binding.requirement).domain
                val known = operations.firstOrNull { it.journal?.receipt?.binding?.allocationId == id }?.journal
                var journal = known ?: recoveryJournals[id]
                if (journal == null) {
                    if (!recoveryOpenUnknown.add(id)) return@withLock null
                    val opened = catalogue.openExisting(id, domain)
                    if (opened !is LocalPhysicalJournalOpen.Opened) {
                        if (opened !== LocalPhysicalJournalOpen.Held) recoveryOpenUnknown.remove(id)
                        return@withLock null
                    }
                    journal = opened.journal
                    recoveryJournals[id] = journal // Own before any later read, close or comparison.
                    recoveryOpenUnknown.remove(id)
                }
                val foreign = LocalPhysicalForeignResolutionDomain.fromRequirement(receipt.binding.requirement)
                if (foreign != null) {
                    val refs = foreign.references(receipt.binding.requirement)
                    if (refs.resolverActorId != originalActorId || refs.resolverIntentJournalId != actorIntentJournalId ||
                        journal.readForeignResolution() !is LocalForeignResolutionRead.Read) return@withLock null
                    if (known == null) {
                        if (journal.close() != LocalPhysicalSpoolClose.CLOSED) return@withLock null
                        recoveryJournals.remove(id)
                    }
                    continue
                }
                val snapshot = (journal.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot
                if (known == null) {
                    if (journal.close() != LocalPhysicalSpoolClose.CLOSED) return@withLock null
                    recoveryJournals.remove(id)
                }
                if (snapshot == null) return@withLock null
                if (snapshot.original != null) result.add(snapshot)
            }
            result.toList()
        }
    }

    public fun denyNewWork(): Unit {
        denied.value = true
        operations.forEach { it.deny() }
    }

    public suspend fun close(): LocalPhysicalSpoolClose = withContext(NonCancellable) {
        denyNewWork()
        val selected = gate.withLock {
            closing?.let { return@withLock Pair(it, false) }
            operations.forEach { it.deny() }
            val result = CompletableDeferred<LocalPhysicalSpoolClose>()
            closing = result
            Pair(result, true)
        }
        if (selected.second) {
            try {
                val work = gate.withLock { operations.flatMap { listOfNotNull(it.pending, it.capture) } }
                work.forEach { it.start(); it.join() }
                val outcome = withContext(nativeDispatcher) { gate.withLock {
                    var complete = !unreturnedNativeOwner && recoveryOpenUnknown.isEmpty()
                    operations.forEach { operation ->
                        val capture = operation.captureState.value
                        var settled = capture !is DistributedPhysicalCaptureState.Candidate &&
                            operation.phase != DistributedPhysicalClientPhase.UNCERTAIN
                        operation.leases?.release()
                        if (capture !is DistributedPhysicalCaptureState.Candidate) operation.evidence?.release()
                        if (operation.leases?.released == false || operation.evidence?.released == false) settled = false
                        if (settled && operation.journal?.close() == LocalPhysicalSpoolClose.HELD) settled = false
                        if (settled && !operation.employee.releaseAfterNativeCleanup()) settled = false
                        if (!settled) complete = false
                    }
                    recoveryJournals.values.forEach { journal ->
                        if (journal.close() != LocalPhysicalSpoolClose.CLOSED) complete = false
                    }
                    if (complete && catalogue.close() != LocalPhysicalSpoolClose.CLOSED) complete = false
                    if (complete && nativeCustody.close() != LocalPhysicalSpoolClose.CLOSED) complete = false
                    if (complete) LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD
                } }
                if (outcome == LocalPhysicalSpoolClose.CLOSED) {
                    nativeRoot.cancel()
                    evidenceRoot.cancel()
                }
                selected.first.complete(outcome)
            } catch (_: Exception) { selected.first.complete(LocalPhysicalSpoolClose.HELD) }
        }
        selected.first.await()
    }

    private fun owns(operation: DistributedPhysicalClientOperation): Boolean =
        operation.owner === this && operations.any { it === operation }

    override fun toString(): String = "DistributedPhysicalClientJournalOwner([REDACTED])"

    private companion object {
        const val MAX_ACTIVE = 128
        val FRESH_PHASES = setOf(DistributedPhysicalClientPhase.REQUESTED, DistributedPhysicalClientPhase.RESERVED,
            DistributedPhysicalClientPhase.START_RETAINED, DistributedPhysicalClientPhase.CONSUMED,
            DistributedPhysicalClientPhase.PUBLISHED, DistributedPhysicalClientPhase.CAPTURING, DistributedPhysicalClientPhase.RESOLUTION_RETAINED)
        val TERMINAL_STAGES = setOf(LocalMasterPhysicalNativeStageV1.PENDING, LocalMasterPhysicalNativeStageV1.HELD,
            LocalMasterPhysicalNativeStageV1.UNCERTAIN, LocalMasterPhysicalNativeStageV1.RECOVERY_ONLY)
        val RETAINED_WRITES = setOf(LocalPhysicalJournalWrite.RETAINED, LocalPhysicalJournalWrite.ALREADY_RETAINED)
    }
}

/** Private native delivery joins the actual SDK response with the SAME local original FD. */
internal class DistributedPhysicalStartDelivery private constructor(
    private val owner: DistributedPhysicalClientJournalOwner,
    private val operation: DistributedPhysicalClientOperation,
    private val delivered: LocalMasterPhysicalStageDelivery,
    private val journal: LocalPhysicalJournal,
) {
    val bytes: StoredBytes get() = delivered.response.payload
    val projection: PhysicalCommittedStartProjectionV1 = PhysicalCommittedStartProjectionV1.parse(bytes)
    fun matches(journal: LocalPhysicalJournal): Boolean = this.journal === journal &&
        owner.originalStartDelivery(operation, delivered, journal)
    fun fresh(): Boolean = matches(journal) && owner.freshStartDelivery(operation)

    companion object {
        fun fromOriginal(owner: DistributedPhysicalClientJournalOwner, operation: DistributedPhysicalClientOperation,
            delivery: LocalMasterPhysicalStageDelivery, journal: LocalPhysicalJournal): DistributedPhysicalStartDelivery? =
            if (owner.originalStartDelivery(operation, delivery, journal))
                DistributedPhysicalStartDelivery(owner, operation, delivery, journal) else null
    }
}

internal fun captureSingleJournal(journal: LocalPhysicalJournal, allocatedOnly: Boolean,
    onRetirement: () -> Unit): List<PhysicalSpoolLease>? = captureJournalCustody(journal, onRetirement) {
        journal.captureNewAction(allocatedOnly, onRetirement)
    }

/** B's distinct critical retention record has no Start or original observation. */
internal fun captureForeignResolutionJournal(journal: LocalPhysicalJournal,
    onRetirement: () -> Unit): List<PhysicalSpoolLease>? = captureJournalCustody(journal, onRetirement) {
        journal.captureForeignResolution(onRetirement)
    }

/** A linked observation owns existing UNKNOWN evidence; it cannot acquire a new-action
 * lease, because that lease deliberately requires an absent original and vacant future cells.
 * The exact resolution descriptor/body and current resolver are checked by both native owners
 * and the protected kernel before retention. This loan exposes no physical action capability. */
internal fun captureResolutionJournal(journal: LocalPhysicalJournal,
    onRetirement: () -> Unit): List<PhysicalSpoolLease>? {
    val snapshot = (journal.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot ?: return null
    val original = snapshot.original ?: return null
    if (!snapshot.actionMayHaveStarted || snapshot.certificate == null ||
        journal.domain.classifyOriginal(journal.receipt.binding.requirement, original) != LocalPhysicalOriginalKind.UNKNOWN) return null
    return captureJournalCustody(journal, onRetirement) { journal.capture(onRetirement) }
}

private fun captureJournalCustody(journal: LocalPhysicalJournal, onRetirement: () -> Unit,
    capture: () -> PhysicalSpoolLease?): List<PhysicalSpoolLease>? {
    var catalogue: List<PhysicalSpoolLease> = emptyList()
    var native: PhysicalSpoolLease? = null
    var transferred = false
    try {
        catalogue = journal.catalogue.capture(onRetirement) ?: return null
        native = capture() ?: return null
        val result = catalogue + native
        transferred = true
        return result
    } finally {
        if (!transferred && !releasePhysicalSpoolLeases(catalogue + listOfNotNull(native))) onRetirement()
    }
}

internal fun certificateAcknowledgment(journal: LocalPhysicalJournal, certificate: StoredBytes): StoredBytes =
    StoredBytes(journalFrame(listOf(journalText("ukkera.distributed-physical-native-stage/v1"),
        journalText(journal.receipt.binding.allocationId.value), journalText(journalBodyDigest(certificate).value),
        journalText(journalBodyDigest(journal.receipt.sourceCommitment).value)), 4_096))
