package com.ukkera.pos.database

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/** Classification by installed typed handlers, never a request-selected provider/capability. */
public enum class PhysicalRecordingAction {
    START_PAYMENT, START_REFUND, RETAIN_ORIGINAL, RETAIN_RESOLUTION,
}

public enum class PhysicalRecordingStartKind { PAYMENT, REFUND }

/** Fixed native-source namespace; its bytes are data, not caller-supplied capacity authority. */
public object PhysicalRecordingSourceProfileV1 {
    public const val KIND: String = "physical.journal-source"
    public const val SCHEMA: String = "physical.journal-source/v1"
}

/** Actual participants derived by the kernel, available in BOTH native-free and final prepare. */
public class PhysicalRecordingParticipants internal constructor(
    public val authority: AuthorityIdentity,
    public val clientDeviceId: DomainId,
    /** Actual originating employee, never derived from an installed catalogue or request body. */
    public val originalActorId: DomainId,
)

/**
 * Created at the actual clean endpoint worker before it borrows any Room connection. Only the
 * registered endpoint gets this proof; direct execute, a domain handler and an RPC do not.
 * Equality of dispatcher/Job/IDs is insufficient: entry and each native phase must return to
 * THIS exact captured worker context. The deliberately different in-Room context is not used
 * for native work or compared to this token.
 */
internal class PhysicalRecordingOwnedIngress(
    val storeOwner: AtomicMasterStore,
    val endpointOwner: LocalMasterEndpoint,
    val registration: Any,
    private val workerContext: CoroutineContext,
) {
    init { check(workerContext[Job]?.isActive == true) }
    fun sameWorkerContext(context: CoroutineContext): Boolean = workerContext === context
    fun inWorker(context: CoroutineContext): Boolean = sameWorkerContext(context) && context[Job]?.isActive == true
}

/**
 * Closed release-one physical effect vocabulary. It is protected even if every installed
 * handler accidentally leaves its declaration at the default empty set. Declarations may ADD
 * guarded domains, not opt these out. Bookkeeping/application and old already-retained
 * financial facts are intentionally absent: their proven SQL application remains possible.
 * A new financial profile must extend this closed inventory before it becomes accepting.
 */
internal object PhysicalRecordingProtectedEffects {
    val kinds: Set<String> = setOf(PhysicalRecordingSourceProfileV1.KIND, ReservedOperations.KIND,
        "sales.journal.slot", "sales.journal.original", "sales.journal.resolution",
        "sales.food.payment-slot", "sales.food.payment-original", "sales.food.payment-resolution")
    val claims: Set<String> = setOf("sales.journal.operation.v3", "sales.journal.allocation.v3",
        "sales.journal.attempt.v3", "sales.food.payment-operation.v1", "sales.food.payment-allocation.v1",
        "sales.food.payment-attempt.v1")
}

/**
 * A native-free, current domain preflight. It is not a capacity receipt, future SQL guarantee,
 * durable attempt or physical-action permit. The complete canonical requirement contains the
 * original identity/amount/tender/target and both participant-role allocation bindings. The
 * domain must rederive exactly the same value in the later REAL execution transaction.
 */
public class PhysicalRecordingAdmission(
    public val action: PhysicalRecordingAction,
    public val domainProfile: String,
    public val requirementBytes: StoredBytes,
) {
    init {
        requireProtocolName(domainProfile)
        require(requirementBytes.size in 1..PHYSICAL_REQUIREMENT_MAX_BYTES)
    }

    public val requirementDigest: PayloadDigest = sha256(requirementBytes.copyBytes())

    override public fun equals(other: Any?): Boolean = other is PhysicalRecordingAdmission &&
        action == other.action && domainProfile == other.domainProfile && requirementBytes == other.requirementBytes

    override public fun hashCode(): Int = 31 * (31 * action.hashCode() + domainProfile.hashCode()) + requirementBytes.hashCode()
    override public fun toString(): String = "PhysicalRecordingAdmissionV1([REDACTED])"
}

/** Owned by the store. Stop/retirement is denying and cannot be reversed by a native adapter. */
internal class PhysicalRecordingServiceLatch(private val instructions: PhysicalInstructionPublicationRegistry? = null) {
    private val denied = MutableStateFlow(false)
    fun deny() { instructions?.denyService(); denied.value = true }
    fun current(): Boolean = !denied.value
}

/**
 * FINAL, synchronous and one-way. Native close/poison/custody withdrawal only calls deny().
 * The kernel's in-loan check reads these captured latches and immutable identities; it never
 * invokes an open callback, native IO, filesystem walk, Binder call or key/provider reentry.
 * This is not a universal observer of hostile OS mutations or rollback of every protected file.
 */
public class PhysicalRecordingNativeLatch internal constructor(
    internal val service: PhysicalRecordingServiceLatch,
) {
    private val denial = MutableStateFlow(false)
    public fun deny() { denial.value = true }
    public val denied: Boolean get() = denial.value || !service.current()
    internal fun current(): Boolean = !denied
}

/**
 * Final single-capture lifetime from an actual freshly committed start. It survives normal
 * invocation lease release but NOT store stop/retirement or an explicit client/native denial.
 * This object is NOT permission to collect money. The native client must additionally own both
 * exact first certificate writes, force one-shot consumed state and bind the genuine current
 * original interactive session before any instruction. No decoder or public constructor exists.
 */
public class PhysicalRecordingServiceLifetime internal constructor(
    private val service: PhysicalRecordingServiceLatch,
    public val scope: BranchScope,
    public val originalActorId: DomainId,
    public val clientDeviceId: DomainId,
    public val authenticationId: DomainId,
    public val sessionGeneration: SessionGeneration,
    public val operationId: OperationId,
    private val interactiveLifetime: PhysicalInteractiveRequestLifetime? = null,
) {
    private val denial = MutableStateFlow(false)
    public val denied: Boolean get() = denial.value || !service.current() || interactiveLifetime?.denied == true
    public fun deny() { interactiveLifetime?.deny(); denial.value = true }
    override public fun toString(): String = "PhysicalRecordingServiceLifetimeV1([REDACTED])"
}

/**
 * Kernel-issued invocation identity. Routing/session/store incarnation is deliberately NOT
 * added to the stable financial semantic digest. No public constructor, copy or deserializer.
 * A native acquisition must return THIS SAME object, not an equal/reconstructed request DTO.
 */
public class PhysicalRecordingInvocationBinding internal constructor(
    internal val storeOwner: Any,
    internal val handlerOwner: MasterCommandHandler,
    internal val principalOwner: LocalEmployeePrincipal,
    public val storeIncarnationId: DomainId,
    public val serviceIncarnationId: DomainId,
    public val invocationId: DomainId,
    public val authority: AuthorityIdentity,
    public val operationId: OperationId,
    public val command: CommandKey,
    public val semanticDigest: PayloadDigest,
    public val normalized: StoredBytes,
    public val admission: PhysicalRecordingAdmission,
    public val latch: PhysicalRecordingNativeLatch,
    internal val interactiveLifetime: PhysicalInteractiveRequestLifetime? = null,
) {
    public val scope: BranchScope get() = principalOwner.scope
    public val originalActorId: DomainId get() = principalOwner.actorId
    public val clientDeviceId: DomainId get() = principalOwner.deviceId
    public val authenticationId: DomainId get() = principalOwner.authenticationId
    public val sessionGeneration: SessionGeneration get() = principalOwner.sessionGeneration
    public val admissionRevision: Revision get() = principalOwner.admissionRevision
    override public fun toString(): String = "PhysicalRecordingInvocationBindingV1([REDACTED])"
}

/**
 * Native-owned, non-evictable acquisition, issued only through the installed gate. It keeps the
 * actual catalogue/journal/key owners alive until release returns; callers never supply it to
 * execute(). New START source rows are immutable prospective revision-one changes, not assumed
 * preexisting SQL receipts. Both rows are contributed to the actual intent transaction.
 * Original/resolution acquisition instead authenticates AND forces the exact original bytes
 * on the master before returning Ready; it cannot discard them if later SQL/auth fails.
 */
public class PhysicalRecordingNativeLease(
    public val binding: PhysicalRecordingInvocationBinding,
    public val leaseId: DomainId,
    sourceChanges: List<AggregateChange>,
) {
    private val sources = sourceChanges.toList()
    public val sourceChanges: List<AggregateChange> get() = sources.toList()

    init {
        val start = binding.admission.action.isStart()
        require(sources.size == if (start) 2 else 0)
        require(sources.map { it.key }.distinct().size == sources.size)
        require(sources.all { it.expectedRevision == null && it.payload.size in 1..16_384 &&
            it.key.kind == PhysicalRecordingSourceProfileV1.KIND && it.schema == PhysicalRecordingSourceProfileV1.SCHEMA })
    }

    override public fun toString(): String = "PhysicalRecordingNativeLeaseV1([REDACTED])"
}

public sealed interface PhysicalRecordingAcquire {
    public class Ready(public val lease: PhysicalRecordingNativeLease) : PhysicalRecordingAcquire
    public data object Held : PhysicalRecordingAcquire
    public data object Busy : PhysicalRecordingAcquire
    public data object StorageUncertain : PhysicalRecordingAcquire
}

public enum class PhysicalRecordingNativeCheck { VERIFIED, HELD, STORAGE_UNCERTAIN }
public enum class PhysicalRecordingNativeRelease { RELEASED, STORAGE_UNCERTAIN }

/**
 * Trusted process/native composition SPI, never client/RPC injection. Every method is called
 * OUTSIDE Room. Implementations own actual preallocated files, keys, required identity and
 * cancellation/undelivered-handle cleanup. Empty files, public receipt objects, a free-space
 * sample or test-only accepting lambda are not an implementation of this contract.
 *
 * acquire: original client evidence must already be authenticated and retained; for original
 * and linked resolution, force/readback those SAME exact bytes on the master before Ready.
 * recheck: actual native owners/cipher/header/custody, not just the synchronous denying latch.
 * persistStart: durably preserve the kernel-issued certificate on the master before success.
 * release: relinquish this invocation lease, never erase/reclaim original obligations/files.
 */
public interface PhysicalRecordingExecutionGate {
    public suspend fun acquire(binding: PhysicalRecordingInvocationBinding): PhysicalRecordingAcquire
    public suspend fun recheck(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeCheck
    public suspend fun persistStart(
        lease: PhysicalRecordingNativeLease, start: PhysicalRecordingCommittedStart,
    ): PhysicalRecordingNativeCheck
    public suspend fun release(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeRelease
}

/**
 * Actual service-owned native lifetime, separate from an invocation or UI connection. Denial
 * is synchronous and memory-only; close runs only after endpoint workers have quiesced. A
 * non-RELEASED close keeps native ownership and pending evidence held, never authorizes a
 * replacement owner or turns a cancelled wait into successful native shutdown.
 */
public interface PhysicalRecordingOwnedExecutionGate : PhysicalRecordingExecutionGate {
    public fun denyNewWork(): Unit
    public suspend fun closeOwnedGate(): PhysicalRecordingNativeRelease
}

public object UnavailablePhysicalRecordingExecutionGate : PhysicalRecordingExecutionGate {
    override public suspend fun acquire(binding: PhysicalRecordingInvocationBinding): PhysicalRecordingAcquire =
        PhysicalRecordingAcquire.Held
    override public suspend fun recheck(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeCheck =
        PhysicalRecordingNativeCheck.HELD
    override public suspend fun persistStart(
        lease: PhysicalRecordingNativeLease, start: PhysicalRecordingCommittedStart,
    ): PhysicalRecordingNativeCheck = PhysicalRecordingNativeCheck.HELD
    override public suspend fun release(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeRelease =
        PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
}

/** Exact typed-handler interpretation of the EFFECTIVE post-CAS proposal/result. */
public sealed interface PhysicalRecordingOutcome {
    /** Only an actual typed payment/refund ATTEMPT_STARTED result can produce this declaration. */
    public class Start(
        public val kind: PhysicalRecordingStartKind,
        requiredChanges: List<AggregateChange>,
        requiredClaims: List<ClaimChange>,
    ) : PhysicalRecordingOutcome {
        private val changes = requiredChanges.toList()
        private val claims = requiredClaims.toList()
        public val requiredChanges: List<AggregateChange> get() = changes.toList()
        public val requiredClaims: List<ClaimChange> get() = claims.toList()
        init {
            // ALL and ONLY gated inserts/lineage claims: both sources, slot, and two future
            // fences (immutable original and one linked resolution), not ordinary stock/money.
            require(changes.size in 5..MAX_EFFECTS && claims.size in 1..MAX_EFFECTS)
            require(changes.map { it.key }.distinct().size == changes.size)
            require(claims.map { it.key }.distinct().size == claims.size)
            require(changes.all { it.expectedRevision == null })
            require(changes.count { it.key.kind == PhysicalRecordingSourceProfileV1.KIND } == 2)
            require(changes.count { it.key.kind == ReservedOperations.KIND } == 2)
            require(claims.all { it is ClaimChange.Acquire })
        }
    }

    /** Raw fact imported, NOT payment/refund financially applied, covered or finalized.
     * requiredChanges is ALL and ONLY gated raw inserts, excluding ordinary bookkeeping. */
    public class FactImported(
        public val originalBodyDigest: PayloadDigest,
        requiredChanges: List<AggregateChange>,
    ) : PhysicalRecordingOutcome {
        private val changes = requiredChanges.toList()
        public val requiredChanges: List<AggregateChange> get() = changes.toList()
        init {
            require(changes.size in 1..MAX_EFFECTS && changes.map { it.key }.distinct().size == changes.size)
            require(changes.all { it.expectedRevision == null &&
                it.key.kind != PhysicalRecordingSourceProfileV1.KIND && it.key.kind != ReservedOperations.KIND })
        }
    }

    /** A closed typed reject/stock-Held result, with NO physical source/slot/fact/fence/claim effects. */
    public data object NonArming : PhysicalRecordingOutcome
}

/** A pure view of the kernel-owned acquisition for a trusted domain planner in the actual loan. */
public class PhysicalRecordingTransaction internal constructor(
    internal val ticket: PhysicalRecordingTicket,
) {
    public val admission: PhysicalRecordingAdmission get() = ticket.binding.admission
    public val sourceChanges: List<AggregateChange> get() = ticket.lease.sourceChanges
    public fun matches(admission: PhysicalRecordingAdmission): Boolean =
        ticket.binding.admission == admission && ticket.binding.latch.current()
    override public fun toString(): String = "PhysicalRecordingTransactionV1([REDACTED])"
}

public class PhysicalRecordingSourceStamp internal constructor(change: AggregateChange) {
    public val key: AggregateKey = change.key
    public val schema: String = change.schema
    public val revision: Revision = Revision(1)
    public val digest: PayloadDigest = sha256(change.payload.copyBytes())
}

/**
 * Created ONLY by the kernel after a successful outer commit and actual native recheck for a
 * NONREPLAYED typed attempt start. Persisting this on both role journals is still not permission
 * to prompt: the originating client must durably consume its one-shot marker first. Replayed
 * execute, lookup, late recovery, UNKNOWN resolution and generic Recorded/Held never mint one.
 * Projection is bounded canonical framing; authentication of remote delivery is separate.
 */
public class PhysicalRecordingCommittedStart internal constructor(
    binding: PhysicalRecordingInvocationBinding,
    public val startKind: PhysicalRecordingStartKind,
    public val sequence: JournalSequence,
    public val journalHash: PayloadDigest,
    public val resultDigest: PayloadDigest,
    public val recordedAt: UtcInstant,
    changes: List<AggregateChange>,
) {
    public val scope: BranchScope = binding.scope
    public val originalActorId: DomainId = binding.originalActorId
    public val operationId: OperationId = binding.operationId
    public val command: CommandKey = binding.command
    public val semanticDigest: PayloadDigest = binding.semanticDigest
    public val requirementBytes: StoredBytes = binding.admission.requirementBytes
    public val domainProfile: String = binding.admission.domainProfile
    public val authority: AuthorityIdentity = binding.authority
    // Scalar origin from the genuine invocation, NOT a retained principal or a recaptured B1
    // service lifetime. Generic execute never attaches a B4 request lifetime or creates offers.
    internal val interactiveOrigin: PhysicalInteractiveOrigin = PhysicalInteractiveOrigin(binding)
    internal val interactiveLifetime: PhysicalInteractiveRequestLifetime? = binding.interactiveLifetime
    private val sources = changes.map(::PhysicalRecordingSourceStamp)
    public val sourceCommitments: List<PhysicalRecordingSourceStamp> get() = sources.toList()
    public val projection: StoredBytes
    private val serviceLifetime = PhysicalRecordingServiceLifetime(binding.latch.service, binding.scope,
        binding.originalActorId, binding.clientDeviceId, binding.authenticationId, binding.sessionGeneration, binding.operationId,
        binding.interactiveLifetime)
    private val capturedLifetime = MutableStateFlow(false)

    /** Once only, on genuine first native delivery. Replay/serialized projection has no factory. */
    public fun captureServiceLifetime(): PhysicalRecordingServiceLifetime? =
        if (!serviceLifetime.denied && capturedLifetime.compareAndSet(false, true)) serviceLifetime else null

    /** An undelivered/retired response can only withdraw, never mint another action lifetime. */
    internal fun denyDelivery() { serviceLifetime.deny() }

    internal fun deliveryCurrent(): Boolean = !serviceLifetime.denied

    init {
        require(changes.size == 2 && changes.all { it.expectedRevision == null })
        val fields = listOf("ukkera.physical-recording-committed-start/v1", scope.storageKey(),
            originalActorId.value, operationId.value, command.name, command.version.toString(),
            semanticDigest.value, domainProfile, startKind.name, authority.assignmentId.value,
            authority.deviceId.value, authority.epoch.value.toString(), sequence.value.toString(),
            journalHash.value, resultDigest.value, recordedAt.canonicalString()).map(String::encodeToByteArray) +
            listOf(requirementBytes.copyBytes(), frame(sources.map { source -> frame(listOf(source.key.kind,
                source.key.id.value, source.schema, source.revision.value.toString(), source.digest.value).map(String::encodeToByteArray)) }))
        val bytes = frame(fields)
        require(bytes.size <= PHYSICAL_START_MAX_BYTES)
        projection = StoredBytes(bytes)
    }

    override public fun toString(): String = "PhysicalRecordingCommittedStartV1([REDACTED])"
}

internal class PhysicalRecordingTicket(
    val binding: PhysicalRecordingInvocationBinding,
    val lease: PhysicalRecordingNativeLease,
) {
    // Ownership is immutable; a withdrawn latch is checked only AFTER the actual writer's
    // authorized positive recovery probe. A concurrent committed winner stays recoverable.
    init { check(lease.binding === binding) }
    val transaction: PhysicalRecordingTransaction = PhysicalRecordingTransaction(this)

    fun current(store: Any, handler: MasterCommandHandler, principal: LocalEmployeePrincipal,
                request: MutationRequest, normalized: StoredBytes, digest: PayloadDigest): Boolean =
        binding.storeOwner === store && binding.handlerOwner === handler && binding.principalOwner === principal &&
            binding.operationId == request.operationId && binding.command == request.command &&
            binding.normalized == normalized && binding.semanticDigest == digest && binding.latch.current()

    /** No IO or callback: all checks are over immutable owned data and the final denying latch. */
    fun validate(proposal: ProposedOutcome, outcome: PhysicalRecordingOutcome?, handler: MasterCommandHandler,
                 allGatedKinds: Set<String>, allGatedClaims: Set<String>): Boolean {
        if (!binding.latch.current() || outcome == null) return false
        if (proposal is ProposedOutcome.Reject) return outcome === PhysicalRecordingOutcome.NonArming
        if (proposal !is ProposedOutcome.Apply) return false
        val gatedChanges = proposal.changes.filter { it.key.kind in allGatedKinds }
        val gatedClaims = proposal.claims.filter { it.key.namespace in allGatedClaims }
        fun exactChanges(required: List<AggregateChange>): Boolean = required.size == gatedChanges.size &&
            required.all { expected -> gatedChanges.singleOrNull { it.key == expected.key }?.sameAs(expected) == true } &&
            required.all { it.key.kind in handler.physicalRecordingKinds || it.key.kind == ReservedOperations.KIND }
        fun exactClaims(required: List<ClaimChange>): Boolean = required.size == gatedClaims.size &&
            required.all { expected -> gatedClaims.singleOrNull { it.key == expected.key } == expected } &&
            required.all { it.key.namespace in handler.physicalRecordingClaimNamespaces }
        return when (outcome) {
            is PhysicalRecordingOutcome.Start -> binding.admission.action.isStart() &&
                outcome.kind == binding.admission.action.startKind() &&
                lease.sourceChanges.all { required -> outcome.requiredChanges.any { it.sameAs(required) } } &&
                exactChanges(outcome.requiredChanges) && exactClaims(outcome.requiredClaims)
            is PhysicalRecordingOutcome.FactImported -> !binding.admission.action.isStart() &&
                outcome.originalBodyDigest == sha256(binding.normalized.copyBytes()) &&
                exactChanges(outcome.requiredChanges) && gatedClaims.isEmpty()
            PhysicalRecordingOutcome.NonArming -> gatedChanges.isEmpty() && gatedClaims.isEmpty()
        }
    }

    fun finishNow() {
        if (!binding.latch.current()) throw PhysicalRecordingFinalDenied()
    }
}

internal class PhysicalRecordingFinalDenied : Exception()
internal fun PhysicalRecordingAction.isStart(): Boolean =
    this == PhysicalRecordingAction.START_PAYMENT || this == PhysicalRecordingAction.START_REFUND
internal fun PhysicalRecordingAction.startKind(): PhysicalRecordingStartKind = when (this) {
    PhysicalRecordingAction.START_PAYMENT -> PhysicalRecordingStartKind.PAYMENT
    PhysicalRecordingAction.START_REFUND -> PhysicalRecordingStartKind.REFUND
    else -> error("Not a physical attempt start")
}
private fun AggregateChange.sameAs(other: AggregateChange): Boolean = key == other.key &&
    expectedRevision == other.expectedRevision && schema == other.schema && payload == other.payload
private const val PHYSICAL_REQUIREMENT_MAX_BYTES = 8_192
private const val PHYSICAL_START_MAX_BYTES = 16_384
