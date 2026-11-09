package com.ukkera.pos.database

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/**
 * Actual endpoint companion, never an arming method on LocalMasterClient. Construction is
 * restricted to the one endpoint/store/security/native composition. No caller principal,
 * current-status DTO, callback, certificate decoder or result lookup creates an offer.
 */
public class LocalMasterPhysicalActions internal constructor(
    private val endpoint: LocalMasterEndpoint,
    private val store: AtomicMasterStore,
) {
    /** A bounded negative lifetime, not authentication or a physical-action grant. */
    public fun newRequestLifetime(): PhysicalInteractiveRequestLifetime = store.newPhysicalRequestLifetime(endpoint)

    public suspend fun start(credentials: LocalGatewayCredentials, request: MutationRequest,
        lifetime: PhysicalInteractiveRequestLifetime): PhysicalFreshStartReply =
        endpoint.startPhysicalInstruction(credentials, request, lifetime)

    public suspend fun publishInstruction(credentials: LocalGatewayCredentials, offer: PhysicalInstructionOffer,
        lifetime: PhysicalInteractiveRequestLifetime): PhysicalInstructionReply =
        endpoint.publishPhysicalInstruction(credentials, offer, lifetime)

    override public fun toString(): String = "LocalMasterPhysicalActionsV1([REDACTED])"
}

/**
 * Exact original request/UI attachment. Denial is irreversible and runs no observer callback.
 * Terminal registry retirement makes this exact object permanently denied; IDs cannot recreate
 * it. A successfully captured original event is independent of this active-entry lifetime.
 */
public class PhysicalInteractiveRequestLifetime internal constructor(
    internal val registry: PhysicalInstructionPublicationRegistry,
    internal val store: AtomicMasterStore,
    internal val endpoint: LocalMasterEndpoint,
    internal val id: DomainId,
) {
    public fun deny() { registry.deny(this) }
    public val denied: Boolean get() = registry.denied(this)
    override public fun toString(): String = "PhysicalInteractiveRequestLifetimeV1([REDACTED])"
}

/** One exact fresh committed Start's offer. Neither bytes nor equal operation IDs recreate it. */
public class PhysicalInstructionOffer internal constructor(
    internal val lifetime: PhysicalInteractiveRequestLifetime,
    internal val committedStart: PhysicalRecordingCommittedStart,
) {
    public fun deny() { lifetime.deny() }
    override public fun toString(): String = "PhysicalInstructionOfferV1([REDACTED])"
}

public class PhysicalFreshStartReply internal constructor(
    public val recordedOrDeferred: MutationReply,
    public val offer: PhysicalInstructionOffer?,
)

public sealed interface PhysicalInstructionReply {
    public class Published internal constructor(public val instruction: PhysicalPublishedInstruction) : PhysicalInstructionReply
    public data object Held : PhysicalInstructionReply
    public data object StorageUncertain : PhysicalInstructionReply
}

/**
 * The genuine one-use publication, not a cached authorization. A private native entry and exact
 * original UI lifetime are ALSO required. Data projection alone is never an instruction.
 * Publication/adoption lag is at most 1000ms; after adoption, human event custody instead uses
 * the protected original access/work expiry, any earlier authoritative finite business deadline
 * and live denials, never a one-second action TTL. Business expiry never erases accepted custody.
 */
public class PhysicalPublishedInstruction internal constructor(
    internal val binding: PhysicalInstructionNativeBinding,
) {
    public val projection: PhysicalInstructionProjection = PhysicalInstructionProjection(binding.committedStart)
    public fun deny() { binding.denial.deny() }

    /** One successful adoption only. A delayed, copied or wrong-lifetime result cannot rearm. */
    public fun adoptTo(original: PhysicalInteractiveRequestLifetime): PhysicalInstructionProjection? =
        if (binding.lifetime.registry.adopt(this, original)) projection else null

    /** Necessary-only live sampling of the SAME security-owned continuity source. */
    public fun deliverableTo(original: PhysicalInteractiveRequestLifetime): Boolean =
        binding.lifetime.registry.deliverable(this, original)

    /** Association only, not an alternative take/adoption/capacity entry point. */
    public fun matchesNativePreparation(binding: PhysicalInstructionNativeBinding): Boolean =
        this.binding === binding && binding.lifetime.registry.matchesPublished(this)

    /**
     * End-only immutable ORIGINAL-event memory ownership, not native durability or permission
     * to act again. The installed native port must ALREADY own/copy the actual canonical body,
     * retain distinct evidence leases and reserve its independent worker before this call. A
     * successful capture atomically retires the active core entry (denial becomes true), while
     * the returned immutable token and native Deciding/Accepted custody remain valid. Never use
     * subsequent lifetime absence/logout/expiry to discard an accepted/uncertain original.
     */
    public fun captureOriginalEvent(binding: PhysicalInstructionNativeBinding,
        immutableBodyDigest: PayloadDigest): PhysicalOriginalEventCapture? =
        if (this.binding !== binding) null else binding.lifetime.registry.captureOriginal(this, immutableBodyDigest)

    override public fun toString(): String = "PhysicalPublishedInstructionV1([REDACTED])"
}

public class PhysicalOriginalEventCapture internal constructor(public val immutableBodyDigest: PayloadDigest) {
    override public fun toString(): String = "PhysicalOriginalEventCaptureV1([REDACTED])"
}

public class PhysicalInstructionProjection internal constructor(start: PhysicalRecordingCommittedStart) {
    public val kind: PhysicalRecordingStartKind = start.startKind
    public val originalOperationId: OperationId = start.operationId
    public val domainProfile: String = start.domainProfile
    public val canonicalRequirement: StoredBytes = start.requirementBytes
}

/** Core-issued SAME-object preparation identity; no public constructor/copy/decoder. */
public class PhysicalInstructionNativeBinding internal constructor(
    public val committedStart: PhysicalRecordingCommittedStart,
    internal val lifetime: PhysicalInteractiveRequestLifetime,
    internal val ingress: PhysicalRecordingOwnedIngress,
    internal val gate: PhysicalRecordingInteractiveGate,
) {
    internal val origin: PhysicalInteractiveOrigin = committedStart.interactiveOrigin
    public val denial: PhysicalInstructionNativeDenial = PhysicalInstructionNativeDenial(lifetime)
    public val scope: BranchScope get() = origin.scope
    public val originalActorId: DomainId get() = origin.actorId
    public val clientDeviceId: DomainId get() = origin.deviceId
    public val authenticationId: DomainId get() = origin.authenticationId
    public val sessionGeneration: SessionGeneration get() = origin.generation
    public val admissionRevision: Revision get() = origin.admissionRevision
    public val operationId: OperationId get() = committedStart.operationId
    public val authority: AuthorityIdentity get() = committedStart.authority
    public val storeIncarnationId: DomainId get() = origin.storeIncarnationId
    public val serviceIncarnationId: DomainId get() = origin.serviceIncarnationId
    public val invocationId: DomainId get() = origin.invocationId
    public val canonicalRequirement: StoredBytes get() = committedStart.requirementBytes
    override public fun toString(): String = "PhysicalInstructionNativeBindingV1([REDACTED])"
}

/** Every withdrawal competes in the SAME atom as publication/adoption/original-event capture. */
public class PhysicalInstructionNativeDenial internal constructor(private val lifetime: PhysicalInteractiveRequestLifetime) {
    public fun deny() { lifetime.deny() }
    public val denied: Boolean get() = lifetime.denied
}

/**
 * Installed native SPI, always OUTSIDE Room. The kernel accepts only its own exact gate call's
 * SAME-binding response and owns the resulting ticket. A constructed lease is not authority.
 * prepare burns only the original private continuation and reserves independent event/capture
 * custody BEFORE returning; it verifies complete fresh obligations, including resolution cells.
 * release relinquishes ONLY temporary preparation leases. Normal RELEASED must not deny the
 * binding or discard private published/event custody. Failed paths deny first. Release finishes
 * before the final owned time sample/atomic publication; no native callback follows that sample.
 */
public interface PhysicalRecordingInteractiveGate {
    public suspend fun prepareInstruction(binding: PhysicalInstructionNativeBinding): PhysicalNativeInstructionPreparation
    public suspend fun releaseInstruction(prepared: PhysicalNativeInstructionLease): PhysicalRecordingNativeRelease
}

public sealed interface PhysicalNativeInstructionPreparation {
    public class Prepared(public val lease: PhysicalNativeInstructionLease) : PhysicalNativeInstructionPreparation
    public data object Held : PhysicalNativeInstructionPreparation
    public data object StorageUncertain : PhysicalNativeInstructionPreparation
}

public class PhysicalNativeInstructionLease(
    public val binding: PhysicalInstructionNativeBinding,
    public val leaseId: DomainId,
    public val canonicalRequirement: StoredBytes,
) {
    init { require(canonicalRequirement.size in 1..8_192) }
    override public fun toString(): String = "PhysicalNativeInstructionLeaseV1([REDACTED])"
}

/** No effects, evaluation callback or AuthorizedAction may cross this installed read-only SPI. */
public interface MasterPhysicalInteractiveAuthorizer {
    public val command: CommandKey
    public val domainProfile: String
    public suspend fun prepareRetainedStartAuthorization(view: CommandReadView,
        context: PhysicalRetainedStartContext): PhysicalRetainedStartRequirement?
}

public class PhysicalRetainedStartContext internal constructor(
    public val original: CommandContext,
    public val committedStart: PhysicalRecordingCommittedStart,
    public val observedAt: UtcInstant,
) {
    public val requirement: StoredBytes get() = committedStart.requirementBytes
}

public class PhysicalRetainedStartRequirement(
    public val authorization: AuthorizationRequirement,
    public val canonicalRequirement: StoredBytes,
    affectedResources: Set<RecoveryResourceKey>,
    recoveryHoldBarrierKinds: Set<RecoveryResourceKind>,
    /**
     * API handoff v002: minimum EXCLUSIVE deadline from ALL actually re-read authoritative
     * reservation/allocated-lot dependencies. Null means those dependencies have no deadline,
     * never "unobserved" or "ignore finite expiry". No caller clock, grant or renewed TTL.
     * The real writer projects this once using M0-before-W0 and the SAME security monotonic
     * source, bounded by original protected access. Future publication/adoption/event checks
     * require BOTH immutable wall deadline and projected monotonic bound. Wall regression or
     * unavailable clock qualification fail closed; observed forward jumps can expire, not
     * extend. Unobserved hostile wall changes are not fenced by this in-process contract.
     */
    public val businessDeadlineExclusive: UtcInstant? = null,
) {
    private val resources = affectedResources.toSet()
    private val barriers = recoveryHoldBarrierKinds.toSet()
    public val affectedResources: Set<RecoveryResourceKey> get() = resources.toSet()
    public val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() = barriers.toSet()
    init { require(canonicalRequirement.size in 1..8_192 && resources.size <= 512) }
}

/** Original protected scalars only. No principal, bearer, policy decision or live loan escapes. */
internal class PhysicalInteractiveOrigin(binding: PhysicalRecordingInvocationBinding) {
    val storeOwner = binding.storeOwner
    val handlerOwner = binding.handlerOwner
    val storeIncarnationId = binding.storeIncarnationId
    val serviceIncarnationId = binding.serviceIncarnationId
    val invocationId = binding.invocationId
    val scope = binding.scope
    val actorId = binding.originalActorId
    val deviceId = binding.clientDeviceId
    val authenticationId = binding.authenticationId
    val sessionId = binding.principalOwner.sessionId
    val generation = binding.sessionGeneration
    val admissionRevision = binding.admissionRevision
    val normalized = binding.normalized
    val interactive = binding.principalOwner.authenticationPurpose == AuthenticationPurpose.INTERACTIVE

    fun matches(principal: LocalEmployeePrincipal): Boolean = interactive && principal.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
        principal.scope == scope && principal.actorId == actorId && principal.deviceId == deviceId && principal.sessionId == sessionId &&
        principal.authenticationId == authenticationId && principal.sessionGeneration == generation && principal.admissionRevision == admissionRevision
}
