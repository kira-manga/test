package com.ukkera.pos.database

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.database.setup.SavedOriginalSetupV1
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.AuthenticatedPrincipal
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.NativeCompanyBusinessPublicationSnapshot
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Bounded immutable bytes; never a bearer, password, key or authorization proof. */
public class StoredBytes(bytes: ByteArray) {
    init { require(bytes.size <= MAX_STORED_BYTES) { "Stored value exceeds bounded command profile" } }
    private val value: ByteArray = bytes.copyOf()
    public val size: Int get() = value.size
    public fun copyBytes(): ByteArray = value.copyOf()
    internal fun bytes(): ByteArray = value
    override fun equals(other: Any?): Boolean = other is StoredBytes && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "StoredBytes(size=$size)"
}

public data class CommandKey(public val name: String, public val version: Int) {
    init { requireProtocolName(name); require(version in 1..1000) }
}

/** Scope/actor/digest are deliberately absent: the gateway derives them. */
public class MutationRequest(
    public val operationId: OperationId,
    public val command: CommandKey,
    public val payload: StoredBytes,
    /**
     * Optional exact-action authority, not business data. Its persisted binding includes this
     * operation's normalized digest; adding/replacing an unconsumed approval cannot change the
     * operation identity or evade replay. Never serialize a reusable approval object here.
     */
    public val actionApprovalId: DomainId? = null,
)

public data class AggregateKey(public val kind: String, public val id: DomainId) {
    init { requireProtocolName(kind) }
}

public data class ClaimKey(public val namespace: String, public val key: String) {
    init {
        requireProtocolName(namespace)
        require(key.length in 1..256 && key.all { it.code in 0x21..0x7e })
    }
}

public class StoredAggregate internal constructor(
    public val key: AggregateKey,
    public val revision: Revision,
    public val schema: String,
    public val payload: StoredBytes,
    public val digest: PayloadDigest,
)

/** All reads are branch-bound and confined to the borrowed writer transaction. */
public interface CommandReadView {
    public val scope: BranchScope
    public suspend fun aggregate(key: AggregateKey): StoredAggregate?
    public suspend fun claim(key: ClaimKey): DomainId?
    /** Complete same-sale EVENT membership at this actual native writer's original cut.
     * Every returned point still requires aggregate() and exact row/source validation.
     * Null is unavailable, never empty membership or permission to trust claims alone. */
    public suspend fun retailCustomerReceiptEventMembership(saleId: DomainId): List<RuntimeRetailCustomerReceiptEventLocator>? = null
    /** Actual native owner's complete observation at this writer's original frame.
     * Consumption is recorded and validated by native child preparation. Null means
     * unavailable, never an empty ledger or permission to infer original membership. */
    public suspend fun originalNativeBusinessPublications(): NativeCompanyBusinessPublicationSnapshot? = null
    /** Serial-specific qualification from the actual kernel-owned clock in this writer.
     * Null is unavailable, never audit-time or client-time fallback. Current production
     * sources have no bound serial-time qualification and return null. */
    public suspend fun qualifiedSerialRegistrationTime(): QualifiedSerialRegistrationTime? = null
    /** CLOSED FOOD scalar-refusal sources from the actual native owner's complete original
     * parent/tail join. Original capture and BEFORE pairing are inert DATA, not current rights.
     * Null is unavailable (including incompatible originals), never an invented empty cut. */
    public suspend fun foodCheckRejectedOriginalSources(operationId: OperationId): RuntimeHistoricalFoodCheckRejectedSources? = null
    /** CLOSED table-assignment refusal sources on the same actual native writer loan.
     * Captured requirements and literal BEFORE pairings remain unqualified DATA. The
     * installed business producer must independently prove ownership before disclosure.
     * Null is unavailable, never a complete directory or an invented empty BEFORE cut. */
    public suspend fun foodTableAssignmentRejectedOriginalSources(operationId: OperationId): RuntimeHistoricalFoodTableAssignmentRejectedSources? = null
    /** CLOSED ordinary ApplyOriginal / AlreadyApplied sources on this actual native loan.
     * Captured requirement/resources and genuine literal BEFORE pairings are inert DATA.
     * The installed payment producer must independently close the earlier Apply/Start/native
     * original and exact application before disclosure. Null means unavailable, including
     * Pending, other payment forms and incomplete or incompatible original observations. */
    public suspend fun foodPaymentAlreadyAppliedOriginalSources(operationId: OperationId): RuntimeHistoricalFoodPaymentAlreadyAppliedSources? = null
    /** Separate ordinary ApplyOriginal value/tender-mismatch refusal. Only the actual
     * native initial application/raw BEFORE pairing is supplied as unqualified DATA.
     * Other Pending reasons, resolutions and current-row reconstructions are unavailable. */
    public suspend fun foodPaymentValueMismatchOriginalSources(operationId: OperationId): RuntimeHistoricalFoodPaymentValueMismatchSources? = null
    /**
     * Bounded keyset pagination for trusted typed adapters. Each selected row must still be read
     * and validated with aggregate(), and the handler must enforce its own projection policy.
     * Legacy/test views do not silently become a complete empty collection.
     */
    public suspend fun aggregateKeys(kind: String, after: DomainId? = null, limit: Int = 50): AggregateKeyPage =
        throw UnsupportedOperationException("Aggregate key pagination unavailable")
    /** Current native claim locators; trusted adapters still validate full sources and project permissions. */
    public suspend fun claimKeys(namespace: String, prefix: String, after: String? = null, limit: Int = 50): ClaimKeyPage =
        throw UnsupportedOperationException("Claim key pagination unavailable")
    /**
     * Original retained operation/journal facts, not a current projection or an
     * authorization, resource-frontier, retry or authoritative absence proof.
     * Null means only that neither member of the retained pair exists here.
     * Partial, unsupported or invalid pairs fail closed as storage uncertainty.
     */
    public suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence?
    /**
     * Exact durable LIVE original installation binding observed on this borrowed writer, never a
     * request-supplied original, caller readiness, cached projection or accepting default.
     * Null means the actual current writer cannot produce the saved original binding; it is not
     * authoritative absence of an installation and cannot authorize a replacement.
     */
    public suspend fun savedOriginalSetup(): SavedOriginalSetupV1? = null
}

public class AggregateChange(
    public val key: AggregateKey,
    public val expectedRevision: Revision?,
    public val schema: String,
    public val payload: StoredBytes,
) {
    init { requireProtocolName(schema) }
}

public sealed interface ClaimChange {
    public val key: ClaimKey
    public val ownerId: DomainId
    public data class Acquire(override val key: ClaimKey, override val ownerId: DomainId) : ClaimChange
    public data class Release(override val key: ClaimKey, override val ownerId: DomainId) : ClaimChange
}

public class JobIntent(public val kind: String, public val body: StoredBytes) {
    init {
        requireProtocolName(kind)
        require(kind != SYNC_JOB) { "The store owns the one journal upload intent" }
    }
}

/** A pure proposal. It is not evidence of a durable sale or external side effect. */
public sealed interface ProposedOutcome {
    public class Apply(
        public val result: StoredBytes,
        changes: List<AggregateChange>,
        claims: List<ClaimChange> = emptyList(),
        jobs: List<JobIntent> = emptyList(),
        public val mustCommitBefore: UtcInstant? = null,
    ) : ProposedOutcome {
        init {
            require(changes.size <= MAX_EFFECTS && claims.size <= MAX_EFFECTS && jobs.size <= MAX_JOBS)
            require(changes.sumOf { it.payload.size } + jobs.sumOf { it.body.size } + result.size <= 1_048_576)
            require(changes.map { it.key }.toSet().size == changes.size)
            // A transfer releases and acquires different claim keys. A same-key owner
            // replacement requires an explicit separate, reviewed transition profile.
            require(claims.map { it.key }.toSet().size == claims.size)
            require(changes.isNotEmpty() || claims.isNotEmpty()) { "Use a read route for no effects" }
        }
        private val changeValues: List<AggregateChange> = changes.toList()
        private val claimValues: List<ClaimChange> = claims.toList()
        private val jobValues: List<JobIntent> = jobs.toList()
        public val changes: List<AggregateChange> get() = changeValues.toList()
        public val claims: List<ClaimChange> get() = claimValues.toList()
        public val jobs: List<JobIntent> get() = jobValues.toList()
    }

    /** Stable machine code only; private evidence must not enter error text/logs. */
    public class Reject(public val code: String, public val result: StoredBytes) : ProposedOutcome {
        init { requireProtocolName(code) }
    }
}

/** An additional trusted-handler denial, never a source proof or request-selected grant. */
public enum class PreparedMutationAdmissionRestriction {
    SOURCE_UNAVAILABLE,
}

/** Installed producer contract, never a request flag or an approval/native proof. */
public enum class ActionApprovalBusinessAttributionSupport {
    UNSUPPORTED,
    PRESERVE_ORIGINAL_REQUESTER,
}

/** Inert business provenance released only from the kernel's exact current authorization binding. */
public class CommandBusinessAttribution internal constructor(
    public val authenticationId: DomainId,
    public val generation: SessionGeneration,
) {
    override fun toString(): String = "CommandBusinessAttribution([REDACTED])"
}

/**
 * Produced by a trusted, typed handler after reading current aggregates. No network,
 * printing, payment, UI or unbounded work belongs in prepare/evaluate. Full domain
 * validations and typed companion inventory effects remain the handler's obligation.
 */
public abstract class PreparedMutation {
    public abstract val requirement: AuthorizationRequirement
    public abstract val affectedResources: Set<RecoveryResourceKey>
    /**
     * Missing trustworthy business source evidence may hold NEW work without recording an
     * unverifiable refusal. Checked after current authorization/resource admission, before
     * evaluation, physical acquisition or streaming. Null adds no restriction; it supplies
     * no authority. Existing originals retain their separate protected recovery route.
     */
    public open val admissionRestriction: PreparedMutationAdmissionRestriction? get() = null
    /**
     * Trusted handler restriction, never a request flag. Authority-producing commands whose
     * retained source records identify only their direct actor cannot be authorized through a
     * separate action approval until that producer retains the actual approver's provenance.
     * The kernel checks this for both approval preview/issuance and execution. Disallowing an
     * approval does not grant the actor PERFORM; current direct authorization is still required.
     */
    public open val allowsActionApproval: Boolean get() = true
    /**
     * A trusted handler explicitly promises that ALL business provenance consumers use the
     * context's bound attribution. Unsupported handlers retain same-session approvals only.
     * This declaration cannot itself qualify an ancestor, current REQUEST or approval use.
     */
    public open val actionApprovalBusinessAttribution: ActionApprovalBusinessAttributionSupport
        get() = ActionApprovalBusinessAttributionSupport.UNSUPPORTED
    /**
     * Optional profile restriction: ANY retained hold of these kinds denies new
     * work, even after exact-key readmission. This only adds denial; it neither
     * supplies a complete footprint nor grants wildcard/resource authority.
     * Trusted bounded stock producers may require STOCK until their canonical
     * resource aliases and recovery provenance are qualified. Not request data.
     */
    public open val recoveryHoldBarrierKinds: Set<RecoveryResourceKind> get() = emptySet()
    /**
     * Pure exact domain requirement for a classified journal-first command. Called only AFTER
     * authorization, first in native-free preflight and again in the actual execution writer.
     * Extract it from real current domain data independently of consuming native capacity; never
     * fabricate a backing receipt merely to make preflight pass. Null REFUSES the physical path.
     */
    public open fun physicalRecordingAdmission(
        authorization: AuthorizedAction, recordedAt: UtcInstant,
    ): PhysicalRecordingAdmission? = null
    public abstract fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome
}

/** A trusted handler may further deny execute-replay when its live source has changed.
 * Explicit protected result recovery remains separate and retains the historical original. */
public interface MasterRetainedResultGuard {
    public suspend fun permitsRetainedResult(view: CommandReadView, result: StoredBytes): Boolean
}

public interface MasterCommandHandler {
    public val key: CommandKey
    public val writableKinds: Set<String>
    public val claimNamespaces: Set<String>
    /** Acquisition-required source/slot/raw-fact kinds only. SQL application bookkeeping is NOT
     * a raw physical fact; do not gate ordinary proof-backed application or legacy claim release. */
    public val physicalRecordingKinds: Set<String> get() = emptySet()
    public val physicalRecordingClaimNamespaces: Set<String> get() = emptySet()
    /**
     * Installed retained-start current-action authorizer, NOT prepare/evaluate of Start again.
     * Optional/unknown refuses the interactive companion. No caller chooses this provider.
     */
    public val physicalInteractiveAuthorizer: MasterPhysicalInteractiveAuthorizer? get() = null
    /** Decode a bounded typed schema and re-encode its canonical semantic form. */
    public fun normalize(input: StoredBytes): StoredBytes
    /** Installed typed classification only. Unknown profiles cannot become remote business scopes. */
    public fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean = false
    /** Exhaustive over the installed profile's closed physical subtypes; not a request flag. */
    public fun physicalRecordingAction(normalized: StoredBytes): PhysicalRecordingAction? = null
    /**
     * Interpret the ACTUAL effective post-CAS proposal/result using the typed domain codec.
     * Generic Apply/Recorded/committed is insufficient. Null refuses a classified physical
     * command; it never falls through into ordinary ungated execution. A NonArming declaration
     * may retain a genuine stock Held result but cannot contain physical source/fact effects.
     */
    public fun physicalRecordingOutcome(
        normalized: StoredBytes, outcome: ProposedOutcome, context: CommandContext,
    ): PhysicalRecordingOutcome? = null
    /**
     * Only closed financial-observation subtypes may return their immutable target binding.
     * A null/mismatched binding cannot consume a pre-reserved operation ID. Outcome data is not
     * omitted from the normal semantic digest: a RECORDED result still has exact-body identity.
     */
    public fun reservedOperationBinding(normalized: StoredBytes): PayloadDigest? = null
    public suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation
}

/** Server-derived binding for constructing the exact authorization requirement. */
public class CommandContext internal constructor(
    public val scope: BranchScope,
    public val originalActorId: DomainId,
    public val operationId: OperationId,
    public val key: CommandKey,
    public val semanticDigest: PayloadDigest,
    /** Kernel-created projection only in the actual writer, never supplied by request or preflight. */
    public val physicalRecording: PhysicalRecordingTransaction? = null,
    /** Actual immutable participant identities; populated in native-free and final physical prepare. */
    public val physicalParticipants: PhysicalRecordingParticipants? = null,
    /** Transient trusted preparation shape only. This is neither serialized nor authority;
     * the actual approval source must still issue/consume the exact protected proof. */
    public val actionApprovalRequested: Boolean = false,
) {
    private var boundAuthorization: AuthorizedAction? = null
    private var boundRequirement: AuthorizationRequirement? = null
    private var attribution: CommandBusinessAttribution? = null

    /**
     * Business DATA only. The exact proof object must have been bound by the kernel in this
     * context, once, after current authorization/approval preparation. Equal or copied proofs,
     * request-provided origins and preparation-only contexts cannot release attribution.
     */
    public fun businessAttribution(authorization: AuthorizedAction,
        requirement: AuthorizationRequirement): CommandBusinessAttribution {
        check(boundAuthorization === authorization && boundRequirement == requirement &&
            authorization.isBoundTo(requirement, originalActorId))
        return checkNotNull(attribution)
    }

    internal fun bindCurrentBusinessAttribution(authorization: AuthorizedAction,
        requirement: AuthorizationRequirement, principal: AuthenticatedPrincipal) {
        check(authorization.approvalId == null && principal.scope == scope && principal.subjectId == originalActorId &&
            authorization.principalKind == principal.kind && authorization.authenticationId == principal.authenticationId &&
            authorization.generation == principal.generation)
        bindBusinessAttribution(authorization, requirement,
            CommandBusinessAttribution(principal.authenticationId, principal.generation))
    }

    internal fun bindBusinessAttribution(authorization: AuthorizedAction,
        requirement: AuthorizationRequirement, value: CommandBusinessAttribution) {
        val binding = checkNotNull(requirement.binding)
        check(boundAuthorization == null && boundRequirement == null && attribution == null &&
            requirement.scope == scope && binding.operationId == operationId && binding.payloadDigest == semanticDigest &&
            authorization.isBoundTo(requirement, originalActorId))
        boundAuthorization = authorization; boundRequirement = requirement.copy(); attribution = value
    }
}

/**
 * Trusted store adapter: read activated identity/account/session/device/grants using
 * this transaction's view. Never return request flags or cached sign-in permissions.
 * The default denies. Durable protected login/account storage is a separate gate.
 */
public fun interface TransactionAuthorizationSource {
    public suspend fun current(principal: AuthenticatedPrincipal, view: CommandReadView): AuthorizationSnapshot?
}

public object UnavailableTransactionAuthorization : TransactionAuthorizationSource {
    override suspend fun current(principal: AuthenticatedPrincipal, view: CommandReadView): AuthorizationSnapshot? = null
}

public class BranchObservation internal constructor(
    public val scope: BranchScope,
    public val authority: AuthorityIdentity,
    public val revision: Revision,
    public val sequence: JournalSequence,
    public val journalHash: PayloadDigest,
    public val recoveryRevision: Revision,
    internal val runtimeFrame: RoomRuntimeHistoryFrame? = null,
)

public enum class RuntimeVerification { VERIFIED, UNAVAILABLE, REFUSED }

/**
 * A qualified protected-runtime adapter, not a request DTO or an epoch comparison.
 * It must establish local storage/writer ownership AND the accepted appointment/custody
 * assumptions. The absence check must cover genesis/authority chain/archives/unknown
 * tails, in this same serialized lookup. MAX(sequence) or a copied healthy flag is not
 * sufficient. No accepting production implementation is supplied by this foundation.
 */
public interface MasterRuntimeVerifier {
    public suspend fun writer(observation: BranchObservation): RuntimeVerification
    public suspend fun absentOperation(observation: BranchObservation, identity: OperationIdentity): RuntimeVerification
    /**
     * Complete-history absence for a future observation whose full semantic body does not yet
     * exist. This is NOT inherited from proof of a different current operation's absence. The
     * default refuses: a partial-history/recovered writer cannot reserve guessed new rights.
     */
    public suspend fun reservableOperation(
        observation: BranchObservation, operationId: OperationId, actorId: DomainId,
    ): RuntimeVerification = RuntimeVerification.UNAVAILABLE
}

public object UnqualifiedMasterRuntime : MasterRuntimeVerifier {
    override suspend fun writer(observation: BranchObservation): RuntimeVerification = RuntimeVerification.UNAVAILABLE
    override suspend fun absentOperation(observation: BranchObservation, identity: OperationIdentity): RuntimeVerification = RuntimeVerification.UNAVAILABLE
}

public fun interface MasterClock { public fun now(): UtcInstant }

public enum class DeferredReason {
    AUTHORIZATION_DENIED, WRONG_SCOPE, UNKNOWN_COMMAND, MALFORMED_REQUEST,
    WRITER_NOT_QUALIFIED, HISTORY_INCOMPLETE, RESOURCE_RECONCILIATION_REQUIRED,
    STORAGE_UNCERTAIN, SERVICE_BUSY, RESERVED_OPERATION_NOT_RECORDED, PRECONDITION_EXPIRED,
    PHYSICAL_RECORDING_HELD,
    /** Nonrecording trusted-source hold; not a storage error or durable identity claim. */
    CURRENT_SOURCE_UNAVAILABLE,
}

public sealed interface MutationReply {
    /** Only published after the OUTER commit successfully completes; construction is not publication. */
    public class Recorded internal constructor(
        public val operationId: OperationId,
        public val originalActorId: DomainId,
        public val committed: Boolean,
        public val code: String,
        public val result: StoredBytes,
        /** Sequence is meaningful only together with this original authority epoch. */
        public val authorityEpoch: Revision,
        public val sequence: JournalSequence,
        public val recordedAt: UtcInstant,
        public val replayed: Boolean,
        /** Exact master certificate, NOT a physical prompt. Always null on replay/lookup. */
        public val physicalStart: PhysicalRecordingCommittedStart? = null,
    ) : MutationReply
    /** Not a durable REJECTED or NOT_RECORDED. Retain the original operation ID. */
    public class Deferred(public val operationId: OperationId, public val reason: DeferredReason) : MutationReply
    public class IdentityConflict internal constructor(public val operationId: OperationId) : MutationReply
}

public sealed interface LookupReply {
    public class Found internal constructor(public val result: MutationReply.Recorded) : LookupReply
    public class AbsentAtCurrentWriter internal constructor(public val operationId: OperationId) : LookupReply
    public class Deferred(public val operationId: OperationId, public val reason: DeferredReason) : LookupReply
    public class IdentityConflict internal constructor(public val operationId: OperationId) : LookupReply
}

internal const val MAX_STORED_BYTES: Int = 65_536
internal const val MAX_EFFECTS: Int = 128
internal const val MAX_JOBS: Int = 32
internal const val SYNC_JOB: String = "journal.upload"
internal fun requireProtocolName(value: String) {
    require(value.length in 1..96 && value.all { it in 'a'..'z' || it in '0'..'9' || it in "._/-" })
}

internal fun BranchScope.storageKey(): String = "${organizationId.value}/${companyId.value}/${branchId.value}"
