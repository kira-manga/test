package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.UtcInstant
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Same-owner service ingress, not a second P4 owner. Only the actual registered endpoint creates
 * this locator-only ingress. A queue acknowledgement discloses no existence/outcome and is not
 * SQL/native completion. The endpoint must use its independent bounded clean service worker.
 */
public class NativeOriginalIntake internal constructor(
    private val endpoint: LocalMasterEndpoint,
    internal val registeredSource: NativeOriginalIntakeSource,
) {
    public fun enqueue(locator: NativeOriginalIntakeLocator): NativeOriginalIntakeSubmission =
        endpoint.enqueueNativeOriginal(this, locator)
    /** Association only: no public object constructor/factory can register a source. */
    public fun owns(source: NativeOriginalIntakeSource): Boolean = registeredSource === source
}

/** Kernel-created on the exact clean service worker, before Room or native IO. */
public class NativeOriginalIntakeInvocation internal constructor(
    internal val store: AtomicMasterStore,
    internal val endpoint: LocalMasterEndpoint,
    internal val source: NativeOriginalIntakeSource,
    public val locator: NativeOriginalIntakeLocator,
    public val scope: BranchScope,
    public val storeIncarnationId: DomainId,
    public val serviceIncarnationId: DomainId,
    public val invocationId: DomainId,
) {
    private val denial = MutableStateFlow(false)
    public val denied: Boolean get() = denial.value
    public fun deny() { denial.value = true }
    public fun belongsTo(source: NativeOriginalIntakeSource): Boolean = this.source === source
    override public fun toString(): String = "NativeOriginalIntakeInvocationV1([REDACTED])"
}

/**
 * Installed SPI, never selected by locator/RPC. The real physical gate ITSELF implements this
 * interface. Registration must require source === the actual B4 gate on the same complete P4
 * owner, not a wrapper with a gate property. Implementing the interface confers no authority.
 * Every callback runs outside Room. No currentness callback is invoked inside the transaction.
 */
public interface NativeOriginalIntakeSource {
    public fun installedIntake(intake: NativeOriginalIntake): Boolean
    public suspend fun acquireOriginal(invocation: NativeOriginalIntakeInvocation): NativeOriginalAcquire
    public suspend fun recheckOriginal(lease: NativeOriginalRetainedLease): PhysicalRecordingNativeCheck
    public suspend fun releaseOriginal(lease: NativeOriginalRetainedLease,
        disposition: NativeOriginalIntakeDisposition): PhysicalRecordingNativeRelease
    /** Restart wake only: existing authenticated catalogue entries, bounded, no new allocation. */
    public suspend fun enqueueRetainedOriginals(): Unit
}

/** Immutable data from an actual held native acquisition; no constructor implies authenticity. */
public class NativeOriginalRetained(
    public val command: CommandKey,
    public val domainProfile: String,
    public val canonicalRequirement: StoredBytes,
    public val certificate: StoredBytes,
    public val originalBody: StoredBytes,
    sourceRows: List<AggregateChange>,
) {
    private val sources = sourceRows.toList()
    public val sourceRows: List<AggregateChange> get() = sources.toList()
    init {
        require(canonicalRequirement.size in 1..8192 && certificate.size in 1..16384 && originalBody.size in 1..49152)
        require(sources.size == 2 && sources.map { it.key }.distinct().size == 2)
        require(sources.all { it.key.kind == PhysicalRecordingSourceProfileV1.KIND && it.expectedRevision == null &&
            it.schema == PhysicalRecordingSourceProfileV1.SCHEMA })
    }
    override public fun toString(): String = "NativeOriginalRetainedV1([REDACTED])"
}

/** Only the registered SAME gate's private exact-object registry can recheck/release this lease. */
public class NativeOriginalRetainedLease(public val invocation: NativeOriginalIntakeInvocation,
    public val leaseId: DomainId, public val retained: NativeOriginalRetained) {
    override public fun toString(): String = "NativeOriginalRetainedLeaseV1([REDACTED])"
}
public sealed interface NativeOriginalAcquire {
    public class Retained(public val lease: NativeOriginalRetainedLease) : NativeOriginalAcquire
    public data object Held : NativeOriginalAcquire
    public data object StorageUncertain : NativeOriginalAcquire
}

/**
 * Only the core, after actual P4 outer SQL cleanup/settlement, creates this end-only disposition.
 * Native release never claims commit, erases evidence, releases a financial hold or reclaims space.
 */
public class NativeOriginalIntakeDisposition internal constructor(
    public val invocation: NativeOriginalIntakeInvocation,
    public val state: State,
) {
    public enum class State { SETTLED, DISPOSED, UNCERTAIN }
}

/** Body DATA only. The real outer history owner alone determines settlement/disposal fate. */
internal enum class NativeOriginalHistoryBodyResult { IMPORTED, ALREADY_PRESENT, HELD }

/** Internal scheduling only; never returned to the employee/client as an operation outcome. */
internal enum class NativeOriginalWorkerResult { SETTLED, HELD, STORAGE_UNCERTAIN }

/** Closed original profiles only. No arbitrary provider, Start, resolution or financial Apply. */
public enum class NativeOriginalDomain(
    public val command: CommandKey,
    public val domainProfile: String,
    public val startKind: PhysicalRecordingStartKind,
) {
    RETAIL_PAYMENT_V3(CommandKey("sales.collection.write", 3), "sales.retail-payment/v3", PhysicalRecordingStartKind.PAYMENT),
    RETAIL_REFUND_V3(CommandKey("sales.return.write", 3), "sales.retail-refund/v3", PhysicalRecordingStartKind.REFUND),
    FOOD_PAYMENT_V1(CommandKey("sales.food.payment", 1), "sales.food-payment/v1", PhysicalRecordingStartKind.PAYMENT),
    FOOD_REFUND_V1(CommandKey("sales.food.refund", 1), "sales.food-refund/v1", PhysicalRecordingStartKind.REFUND),
    /** Supplier outflow has its own closed codec; PAYMENT is the existing custody tag only. */
    SUPPLIER_CASH_V1(CommandKey("purchasing.supplier-cash", 1), "purchasing.supplier-cash/v1", PhysicalRecordingStartKind.PAYMENT);

    // Closed per-domain insert vocabulary. A new PAYMENT domain is never silently treated
    // as retail or refund. Existing retail persisted keys/schemas remain byte-for-byte stable.
    internal val originalKind: String get() = when (this) {
        RETAIL_PAYMENT_V3, RETAIL_REFUND_V3 -> "sales.journal.original"
        FOOD_PAYMENT_V1 -> "sales.food.payment-original"
        FOOD_REFUND_V1 -> "sales.food.refund-original"
        SUPPLIER_CASH_V1 -> "purchasing.supplier-cash-original"
    }
    internal val originalSchema: String get() = when (this) {
        RETAIL_PAYMENT_V3, RETAIL_REFUND_V3 -> "sales.journal.original/v3"
        FOOD_PAYMENT_V1 -> "sales.food.payment-original/v1"
        FOOD_REFUND_V1 -> "sales.food.refund-original/v1"
        SUPPLIER_CASH_V1 -> "purchasing.supplier-cash-original/v1"
    }
    internal val applicationKind: String get() = when (this) {
        RETAIL_PAYMENT_V3, RETAIL_REFUND_V3 -> "sales.journal.application"
        FOOD_PAYMENT_V1 -> "sales.food.payment-application"
        FOOD_REFUND_V1 -> "sales.food.refund-application"
        SUPPLIER_CASH_V1 -> "purchasing.supplier-cash-application"
    }
    internal val applicationSchema: String get() = when (this) {
        RETAIL_PAYMENT_V3, RETAIL_REFUND_V3 -> "sales.journal.application/v3"
        FOOD_PAYMENT_V1 -> "sales.food.payment-application/v1"
        FOOD_REFUND_V1 -> "sales.food.refund-application/v1"
        SUPPLIER_CASH_V1 -> "purchasing.supplier-cash-application/v1"
    }
}

public interface NativeOriginalImportInterpreter {
    public val domain: NativeOriginalDomain
    /** Pure bounded decoding of the actual native lease's bytes. Never proof of custody. */
    public fun project(native: NativeOriginalRetained): NativeOriginalProjection
    public suspend fun prepare(view: CommandReadView, context: NativeOriginalImportContext): NativeOriginalImportPlan?
    /**
     * Read/verify the genuine current immutable original AND its current application lineage.
     * No rematerialization, replay of money effects, permission or private result disclosure.
     * Core still binds the exact original operation/journal pair and the native retained bytes.
     */
    public suspend fun verifyExisting(view: CommandReadView, context: NativeOriginalImportContext,
        original: RetainedCommandEvidence): Boolean
}

/**
 * Closed domain codec projection DATA, not an accepting adapter/retained-command/native proof.
 * Source attributes are parsed by the same native format codec as the domain, not a second
 * retail JSON/business machine in core. Core joins all source digests to the actual held lease,
 * current generation-one SQL sources and the whole historical Start before any write.
 */
public class NativeOriginalProjection(
    public val domain: NativeOriginalDomain,
    public val scope: BranchScope,
    public val originalActorId: DomainId,
    public val intentOperationId: OperationId,
    public val intentSemanticDigest: PayloadDigest,
    public val recordingOperationId: OperationId,
    public val resolutionOperationId: OperationId,
    public val reservedBinding: PayloadDigest,
    public val masterSource: NativeOriginalJournalProvenanceV1.Source,
    public val clientSource: NativeOriginalJournalProvenanceV1.Source,
    resources: Set<RecoveryResourceKey>,
) {
    private val recoveryResources = resources.toSet()
    /** Literal domain recovery aliases, not employee authorization or native custody. */
    public val resources: Set<RecoveryResourceKey> get() = recoveryResources.toSet()
    init {
        require(recoveryResources.isNotEmpty() && recoveryResources.size <= com.ukkera.pos.security.SelfOwnedNativeHistoryWireV2.MAX_RESOURCES)
        require(setOf(intentOperationId, recordingOperationId, resolutionOperationId).size == 3)
        require(masterSource.role == NativeOriginalJournalProvenanceV1.SourceRole.MASTER &&
            clientSource.role == NativeOriginalJournalProvenanceV1.SourceRole.CLIENT &&
            masterSource.allocationId != clientSource.allocationId)
    }
    override public fun toString(): String = "NativeOriginalProjectionV1([REDACTED])"
}

/** Core validated exact retained Start + native ticket on genuine original-parent transaction. */
public class NativeOriginalImportContext internal constructor(
    public val native: NativeOriginalRetained,
    public val historicalStart: RetainedCommandEvidence,
    public val recordedAt: UtcInstant,
)

/**
 * Non-authoritative plan data. Core validates the two CLOSED insert-only keys/schemas, reserved
 * original operation binding and body/digest; only its own private validated plan is executable.
 * No claims, jobs, source changes, domain/cash/stock writes or AuthorizedAction are representable.
 */
public class NativeOriginalImportPlan(
    public val domain: NativeOriginalDomain,
    public val operationId: OperationId,
    public val originalActorId: DomainId,
    public val command: CommandKey,
    public val canonicalBody: StoredBytes,
    public val semanticDigest: PayloadDigest,
    public val reservedBinding: PayloadDigest,
    public val rawOriginal: AggregateChange,
    public val initialApplication: AggregateChange,
    public val result: StoredBytes,
) {
    init { require(rawOriginal.expectedRevision == null && initialApplication.expectedRevision == null) }
}
