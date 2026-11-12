package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Exact initial account reduction shared by the real enrollment writer and the separate
 * delegated-creation capture/replay calculation. This is DATA, never a native effect permit.
 * It creates no login session, issuance, approval or device admission, and has no BEFORE
 * account: its caller must prove complete absence on the actual writer and historical cut. */
internal class RuntimeEmployeeCreationSeed private constructor(
    val definition: LocalAccountProvisioning,
    credential: PasswordHashRecord?,
    val at: MonotonicMoment,
    retainedAdmissions: List<LocalDeviceAdmission>,
    val event: LocalAuthEvent,
) {
    val account = ProtectedLocalAccount(LocalAccountSessionState(definition.policy.scope, definition.actorId,
        true, Revision(0), SessionGeneration(0), emptyList()), Revision(0), credential)
    val policy: ActivatedLocalPolicy get() = definition.policy
    val configuration = LocalAuthConfiguration(policy.scope, definition.actorId, Revision(0),
        definition.login, definition.tokenPolicy)
    private val retained = retainedAdmissions.sortedBy { it.deviceId.value }
    private val initialThrottles: List<PersistentThrottleState>
    val throttles: List<PersistentThrottleState> get() = initialThrottles.toList()

    init {
        require(retained.size in 1..256 && retained.map { it.deviceId }.distinct().size == retained.size &&
            retained.all { it.scope == policy.scope } &&
            retained.filter { it.admitted }.map { it.deviceId }.toSet() == definition.admittedDeviceIds)
        require(configuration.tokens.canStartAt(at))
        require(event.scope == policy.scope && event.targetId == definition.actorId &&
            event.accountRevision == Revision(0) && event.at == at && event.contentDigest != null)
        initialThrottles = (listOf<ThrottleDimension>(ThrottleDimension.Account) +
            retained.map { ThrottleDimension.Device(it.deviceId) }).map { dimension ->
            PersistentThrottleState(ThrottleKey(policy.scope, definition.actorId, dimension),
                Revision(0), 0, at, 0, null)
        }
    }

    override fun toString(): String = "RuntimeEmployeeCreationSeed([REDACTED])"

    companion object {
        /** Preserves the original company-head enrollment's event kind, null operator/session,
         * digest, seed order and initial values. No delegated employee authority is inferred. */
        fun companyHead(request: LocalProvisioningRequest, definition: LocalAccountProvisioning,
            credential: PasswordHashRecord?, at: MonotonicMoment, admissions: List<LocalDeviceAdmission>): RuntimeEmployeeCreationSeed {
            val configuration = LocalAuthConfiguration(definition.policy.scope, definition.actorId,
                Revision(0), definition.login, definition.tokenPolicy)
            require(localAccountProvisioningRequestV1(definition.policy.scope, request.requestId,
                definition.actorId, credential != null, LocalEffectivePolicyCodec.encodePolicy(definition.policy),
                LocalAuthenticationCodec.encodeConfiguration(configuration), definition.admittedDeviceIds) == request)
            return RuntimeEmployeeCreationSeed(definition, credential, at, admissions,
                LocalAuthEvent(request.scope, request.requestId, LocalAuthEventKind.ACCOUNT_PROVISIONED,
                    request.targetId, null, null, Revision(0), at, request.contentDigest))
        }

        /** Distinct audited creation, with the original creator/session and complete original
         * request digest. A parsed authorization snapshot is not proof of an authenticated
         * chronological prefix; the actual writer/native scanner must independently join it. */
        fun delegated(original: LocalDelegatedEmployeeCreationV1,
            creator: RuntimeCommandAuthorizationSnapshotV1, credential: PasswordHashRecord?,
            at: MonotonicMoment): RuntimeEmployeeCreationSeed {
            creator.validate()
            require(creator.scope == original.scope && creator.actorId == original.operatorId &&
                creator.actorId != original.actorId && at.bootId == creator.access.observedAt.bootId &&
                at.elapsedMillis >= creator.access.observedAt.elapsedMillis &&
                at.elapsedMillis < creator.access.expiresAtElapsedMillis)
            val requested = original.copyRequestedDefinition()
            require(requested.credentialWasProvided == (credential != null))
            return RuntimeEmployeeCreationSeed(requested.definition, credential, at, requested.admissions,
                LocalAuthEvent(original.scope, original.requestId, LocalAuthEventKind.ACCOUNT_CREATED,
                    original.actorId, creator.actorId, creator.sessionId, Revision(0), at, original.contentDigest))
        }
    }
}
