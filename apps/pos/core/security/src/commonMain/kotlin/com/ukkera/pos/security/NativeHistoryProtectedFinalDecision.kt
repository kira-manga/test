package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId

/** The captured protected access window, bound before native preparation.
 * This is DATA, not authority: the actual protected writer still verifies its complete
 * dependency/effect cut, and the native owner must sample its own current environment.
 * Neither a paid-stock wall witness nor the earlier authorization observation substitutes
 * for that final issuer/boot/elapsed decision. */
public data class NativeHistoryProtectedFinalDeclaration(
    public val originalRequestId: DomainId,
    public val originalRequestDigest: PayloadDigest,
    public val actorId: DomainId,
    public val sessionId: DomainId,
    public val issuerContextId: DomainId,
    public val issuedAt: MonotonicMoment,
    public val lastObservedAt: MonotonicMoment,
    public val expiresAtElapsedMillis: Long,
) {
    init {
        require(issuedAt.bootId == lastObservedAt.bootId &&
            lastObservedAt.elapsedMillis >= issuedAt.elapsedMillis &&
            lastObservedAt.elapsedMillis < expiresAtElapsedMillis)
    }

    internal fun requireIntent(intent: NativeHistoryOriginalIntentV2) {
        require(intent.originalRequestId == originalRequestId && intent.originalActorId == actorId &&
            intent.originalRequestDigest == originalRequestDigest)
        when (intent.purposeTag) {
            // Existing administration declarations keep their exact no-operation profile.
            10, 12 -> require(intent.originalOperationId == null && intent.resources.isEmpty())
            // Original inline issuance has no business operation or effects/resources.
            14 -> require(intent.originalOperationId == null && intent.resources.isEmpty())
            // Explicit immutable retirement is separately bound to its old approval and before cut.
            18 -> require(intent.originalOperationId == null && intent.resources.isEmpty())
            // Closed private serial requester access. It never carries head approval or effects.
            NativeSerialPrivatePublicationRequestCodecV1.PURPOSE_TAG ->
                require(intent.originalOperationId == null && intent.resources.isEmpty())
            // The separate approved-command capture binds the entire original requirement,
            // protected images and resources. A direct/old command cannot enter this lane.
            2, 3 -> require(intent.originalOperationId == originalRequestId)
            else -> throw IllegalArgumentException("Unsupported protected final intent")
        }
    }

    internal fun requireObservation(observed: LocalAuthenticationObservation) {
        require(observed.issuerContextId == issuerContextId && observed.moment.bootId == issuedAt.bootId &&
            observed.moment.elapsedMillis >= lastObservedAt.elapsedMillis &&
            observed.moment.elapsedMillis < expiresAtElapsedMillis)
    }

    override fun toString(): String = "NativeHistoryProtectedFinalDeclaration([REDACTED])"
}

/** The wire already retains both optional declarations. Their coexistence is restricted
 * to ONE approved Apply; independent declarations cannot be transplanted between commands. */
internal fun requireNativeApprovalFinalPair(intent: NativeHistoryOriginalIntentV2,
    wall: NativeHistoryFinalDeadlineDeclaration?, protected: NativeHistoryProtectedFinalDeclaration?) {
    wall?.requireIntent(intent); protected?.requireIntent(intent)
    if (wall != null && protected != null) require(intent.purposeTag == 2 &&
        intent.originalOperationId == wall.originalOperationId && intent.originalRequestId == wall.originalRequestId &&
        protected.originalRequestId == wall.originalRequestId && protected.originalRequestDigest == wall.originalRequestDigest &&
        protected.actorId == intent.originalActorId)
}

/** Actual native final observation, retained only after the SAME outer frame COMMITTED.
 * The complete original intent binds the command, dependency hashes and every protected
 * effect image. This proves a pre-COMMIT decision, not response/fsync timing or a renewed
 * session. A restarted reader cannot create a missing decision from the SQL result. */
public data class NativeHistoryProtectedFinalDecision internal constructor(
    public val scope: BranchScope,
    public val originalAllocationDigest: PayloadDigest,
    public val child: NativeHistoryRecordedHeadV2,
    public val originalIntentDigest: PayloadDigest,
    public val declaration: NativeHistoryProtectedFinalDeclaration,
    public val observed: LocalAuthenticationObservation,
) {
    init {
        require(child.historySequence > 1)
        declaration.requireObservation(observed)
    }

    override fun toString(): String = "NativeHistoryProtectedFinalDecision([REDACTED])"
}
