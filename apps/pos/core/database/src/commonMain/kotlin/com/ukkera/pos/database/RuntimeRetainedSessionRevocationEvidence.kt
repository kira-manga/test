package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Exact hashes of the target records read on the actual administrative writer loan.
 * The retained session is ACTIVE, interactive and on the operator's device. Startup also
 * requires both identities' genuine original-cohost history. The target's tokens
 * may already be expired: revoking it never establishes eligibility or renews a deadline.
 * Neither credential bytes nor token secrets/hashes are retained in this profile. */
@Serializable
internal data class RuntimeRetainedSessionRevocationTargetV1(
    val accountBeforeDigest: PayloadDigest,
    val credentialRevision: Revision,
    val accountGeneration: SessionGeneration,
    val session: LocalSessionRecord,
    val accessDigest: PayloadDigest,
    val refreshDigest: PayloadDigest,
) {
    fun validate(command: LocalAdministrationCommand, authorization: RuntimeCommandAuthorizationSnapshotV1) {
        require(command.expectedRevision.value < Long.MAX_VALUE - 1L && credentialRevision.value < Long.MAX_VALUE &&
            accountGeneration.value in 1L until Long.MAX_VALUE - 1L &&
            session.scope == authorization.scope && session.actorId == command.targetId &&
            session.sessionId == command.sessionId && session.sessionId != authorization.sessionId &&
            session.deviceId == authorization.deviceId && session.status == SessionStatus.ACTIVE &&
            session.authenticationPurpose == AuthenticationPurpose.INTERACTIVE &&
            session.generation.value in 1L..accountGeneration.value)
        if (command.targetId == authorization.actorId) require(command.expectedRevision == authorization.accountRevision &&
            credentialRevision == authorization.credentialRevision && accountBeforeDigest == authorization.dependencies.account)
    }

    /** Immutable record comparison only. In particular, this does not admit an expired token. */
    fun matchesIssuances(access: LocalAccessIssuance?, refresh: LocalRefreshIssuance?): Boolean =
        access != null && refresh != null &&
            retainedSessionIssuancesMatch(session, credentialRevision, access.admissionRevisionAtIssue, access, refresh) &&
            accessDigest == retainedRevocationDigest(ProtectedLocalSecurityCodec.encodeIssuance(access)) &&
            refreshDigest == retainedRevocationDigest(LocalAuthenticationCodec.encodeRefresh(refresh))

    override fun toString(): String = "RuntimeRetainedSessionRevocationTargetV1([REDACTED])"
}

@Serializable
internal data class RuntimeRetainedSessionRevocationDependenciesV1(
    val operator: RuntimeCommandAuthorizationDependenciesV1,
    val targetAccount: PayloadDigest,
    val targetAccess: PayloadDigest,
    val targetRefresh: PayloadDigest,
) {
    override fun toString(): String = "RuntimeRetainedSessionRevocationDependenciesV1([REDACTED])"
}

/** Distinct retained format for revoking ANOTHER retained interactive session. The existing
 * self-session V1 remains byte-exact and keeps its original validation. Operator authorization,
 * target BEFORE/issuance continuity, every actual AFTER/audit image and the native final
 * decision must all join before restart admission; these declarations grant nothing alone. */
@Serializable
internal data class RuntimeRetainedSessionRevocationEvidenceV1(
    val schema: String = PROFILE,
    val command: LocalAdministrationCommand,
    val requirement: RuntimeCommandRequirementV1,
    val authorization: RuntimeCommandAuthorizationSnapshotV1,
    val target: RuntimeRetainedSessionRevocationTargetV1,
    val at: MonotonicMoment,
    val images: List<RuntimeAuthenticationImageV1>,
) {
    val purpose: RuntimeWritePurposeV2 get() = RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION
    val semanticDigest: PayloadDigest get() = localAdministrationRequestDigest(authorization.scope, authorization.actorId, command)
    val dependencies: RuntimeRetainedSessionRevocationDependenciesV1 get() = RuntimeRetainedSessionRevocationDependenciesV1(
        authorization.dependencies, target.accountBeforeDigest, target.accessDigest, target.refreshDigest)

    fun protectedFinalDeclaration(): NativeHistoryProtectedFinalDeclaration {
        validate()
        return NativeHistoryProtectedFinalDeclaration(command.requestId, semanticDigest, authorization.actorId,
            authorization.sessionId, authorization.access.issuerContextId, authorization.access.issuedAt,
            at, authorization.access.expiresAtElapsedMillis)
    }

    fun validate() {
        require(schema == PROFILE && command.action == LocalAdministrationAction.REVOKE_SESSION)
        authorization.validate()
        target.validate(command, authorization)
        require(requirement.value() == sessionRevocationRequirement(authorization.scope, authorization.actorId, command))
        val access = authorization.access
        require(at.bootId == access.observedAt.bootId && at.elapsedMillis >= access.observedAt.elapsedMillis &&
            at.elapsedMillis < access.expiresAtElapsedMillis)
        require(images.size == MAX_IMAGES)
        images.forEach { it.validate() }
        require(images[0].table == RuntimeHistoryTableV2.ACCOUNT && images[0].recordId == command.targetId &&
            images[0].dimension == null && images[0].revision == command.expectedRevision.next())
        require(images[1].table == RuntimeHistoryTableV2.AUTH_EVENT && images[1].recordId == command.requestId &&
            images[1].dimension == null && images[1].revision == Revision(0))
    }

    override fun toString(): String = "RuntimeRetainedSessionRevocationEvidenceV1([REDACTED])"

    companion object {
        const val PROFILE: String = "ukkera.runtime-retained-session-revocation-evidence/v1"
        const val MAX_IMAGES: Int = 2
    }
}

/** Memory-only capture. Missing/legacy/archived/other-device target facts keep their existing
 * unsupported history route. Successful protection supplies AFTER images, never this producer. */
internal class RuntimeRetainedSessionRevocationCapture private constructor(
    private val command: LocalAdministrationCommand,
    private val requirement: RuntimeCommandRequirementV1,
    private val authorization: RuntimeCommandAuthorizationSnapshotV1,
    val target: RuntimeRetainedSessionRevocationTargetV1,
    private val at: MonotonicMoment,
) {
    val scope: BranchScope get() = authorization.scope
    val actorId: DomainId get() = authorization.actorId
    val requestId: DomainId get() = command.requestId
    val semanticDigest: PayloadDigest get() = localAdministrationRequestDigest(scope, actorId, command)
    val images = mutableListOf<RuntimeAuthenticationImageV1>()

    fun evidence(): RuntimeRetainedSessionRevocationEvidenceV1 = RuntimeRetainedSessionRevocationEvidenceV1(
        command = command, requirement = requirement, authorization = authorization, target = target,
        at = at, images = images.toList()).also { it.validate() }

    override fun toString(): String = "RuntimeRetainedSessionRevocationCapture([REDACTED])"

    companion object {
        fun capture(principal: LocalEmployeePrincipal, facts: RoomLocalAuthorizationFacts,
            permitted: AuthorizedAction, command: LocalAdministrationCommand,
            observed: RoomLocalAuthorizationObservation, event: LocalAuthEvent,
            targetBefore: ProtectedLocalAccount, access: LocalAccessIssuance?,
            refresh: LocalRefreshIssuance?): RuntimeRetainedSessionRevocationCapture? {
            val before = targetBefore.state
            if (command.action != LocalAdministrationAction.REVOKE_SESSION || command.sessionId == principal.sessionId ||
                before.scope != principal.scope || before.actorId != command.targetId || !before.enabled ||
                targetBefore.credential == null || targetBefore.credentialRevision.value >= Long.MAX_VALUE ||
                before.revision != command.expectedRevision ||
                before.revision.value >= Long.MAX_VALUE - 1L || before.generation.value >= Long.MAX_VALUE - 1L ||
                access == null || refresh == null) return null
            val session = before.sessions.singleOrNull { it.sessionId == command.sessionId } ?: return null
            if (session.status != SessionStatus.ACTIVE || session.authenticationPurpose != AuthenticationPurpose.INTERACTIVE ||
                session.deviceId != principal.deviceId ||
                !retainedSessionIssuancesMatch(session, targetBefore.credentialRevision, facts.admission.revision, access, refresh)) return null
            val requirement = sessionRevocationRequirement(principal.scope, principal.actorId, command)
            val snapshot = RuntimeCommandAuthorizationSnapshotV1.capture(principal, facts, observed)
            require(permitted.isBoundTo(requirement, principal.actorId) && permitted.approvalId == null &&
                permitted.principalKind == principal.kind && permitted.authenticationId == principal.sessionId &&
                permitted.generation == principal.generation && permitted.policyRevision == facts.policy.revision)
            require(event.scope == principal.scope && event.eventId == command.requestId &&
                event.kind == LocalAuthEventKind.SESSION_REVOKED && event.targetId == command.targetId &&
                event.operatorId == principal.actorId && event.sessionId == session.sessionId &&
                event.accountRevision == command.expectedRevision.next() &&
                event.contentDigest == localAdministrationRequestDigest(principal.scope, principal.actorId, command))
            val target = RuntimeRetainedSessionRevocationTargetV1(
                retainedRevocationDigest(ProtectedLocalSecurityCodec.encodeAccount(targetBefore)), targetBefore.credentialRevision,
                before.generation, session, retainedRevocationDigest(ProtectedLocalSecurityCodec.encodeIssuance(access)),
                retainedRevocationDigest(LocalAuthenticationCodec.encodeRefresh(refresh)))
            target.validate(command, snapshot)
            return RuntimeRetainedSessionRevocationCapture(command, RuntimeCommandRequirementV1.capture(requirement), snapshot, target, event.at)
        }
    }
}

/** Closed memory-only union. Each persisted profile keeps its own exact serializer/reader;
 * the self-session DTO and codec are not widened or reserialized through this container. */
internal class RuntimeSessionRevocationHistoryEvidence private constructor(
    val self: RuntimeSessionRevocationEvidenceV1?,
    val retained: RuntimeRetainedSessionRevocationEvidenceV1?,
    val disable: RuntimeAccountDisableEvidenceV1?,
    val reset: RuntimeCredentialResetEvidenceV1?,
    val reactivation: RuntimeAccountReactivationEvidenceV1?,
    val activation: RuntimeAccountActivationEvidenceV1?,
    val credentialFreeDisable: RuntimeCredentialFreeAccountDisableEvidenceV1?,
) {
    init { check(listOf(self, retained, disable, reset, reactivation, activation, credentialFreeDisable).count { it != null } == 1) }
    val command: LocalAdministrationCommand get() = self?.command ?: retained?.command ?: disable?.command ?: reset?.command ?: reactivation?.command ?: activation?.command ?: checkNotNull(credentialFreeDisable).command
    val authorization: RuntimeCommandAuthorizationSnapshotV1 get() = self?.authorization ?: retained?.authorization ?: disable?.authorization ?: reset?.authorization ?: reactivation?.authorization ?: activation?.authorization ?: checkNotNull(credentialFreeDisable).authorization
    val purpose: RuntimeWritePurposeV2 get() = self?.purpose ?: retained?.purpose ?: disable?.purpose ?: reset?.purpose ?: reactivation?.purpose ?: activation?.purpose ?: checkNotNull(credentialFreeDisable).purpose
    val semanticDigest: PayloadDigest get() = self?.semanticDigest ?: retained?.semanticDigest ?: disable?.semanticDigest ?: reset?.semanticDigest ?: reactivation?.semanticDigest ?: activation?.semanticDigest ?: checkNotNull(credentialFreeDisable).semanticDigest
    val images: List<RuntimeAuthenticationImageV1> get() = self?.images ?: retained?.images ?: disable?.images ?: reset?.images ?: reactivation?.images ?: activation?.images ?: checkNotNull(credentialFreeDisable).images
    fun protectedFinalDeclaration(): NativeHistoryProtectedFinalDeclaration =
        self?.protectedFinalDeclaration() ?: retained?.protectedFinalDeclaration() ?: disable?.protectedFinalDeclaration()
            ?: reset?.protectedFinalDeclaration() ?: reactivation?.protectedFinalDeclaration() ?: activation?.protectedFinalDeclaration() ?: checkNotNull(credentialFreeDisable).protectedFinalDeclaration()
    fun encode(): ByteArray = self?.let { RuntimeSessionRevocationEvidenceCodec.encode(it) }
        ?: retained?.let { RuntimeRetainedSessionRevocationEvidenceCodec.encode(it) }
        ?: disable?.let { RuntimeAccountDisableEvidenceCodec.encode(it) }
        ?: reset?.let { RuntimeCredentialResetEvidenceCodec.encode(it) }
        ?: reactivation?.let { RuntimeAccountReactivationEvidenceCodec.encode(it) }
        ?: activation?.let { RuntimeAccountActivationEvidenceCodec.encode(it) }
        ?: RuntimeCredentialFreeAccountDisableEvidenceCodec.encode(checkNotNull(credentialFreeDisable))
    fun dependencies(): ByteArray = self?.let { RuntimeSessionRevocationEvidenceCodec.dependencies(it) }
        ?: retained?.let { RuntimeRetainedSessionRevocationEvidenceCodec.dependencies(it) }
        ?: disable?.let { RuntimeAccountDisableEvidenceCodec.dependencies(it) }
        ?: reset?.let { RuntimeCredentialResetEvidenceCodec.dependencies(it) }
        ?: reactivation?.let { RuntimeAccountReactivationEvidenceCodec.dependencies(it) }
        ?: activation?.let { RuntimeAccountActivationEvidenceCodec.dependencies(it) }
        ?: RuntimeCredentialFreeAccountDisableEvidenceCodec.dependencies(checkNotNull(credentialFreeDisable))
    override fun toString(): String = "RuntimeSessionRevocationHistoryEvidence([REDACTED])"

    companion object {
        fun credentialFreeDisable(value: RuntimeCredentialFreeAccountDisableEvidenceV1): RuntimeSessionRevocationHistoryEvidence =
            RuntimeSessionRevocationHistoryEvidence(null, null, null, null, null, null, value.also { it.validate() })
        fun activation(value: RuntimeAccountActivationEvidenceV1): RuntimeSessionRevocationHistoryEvidence =
            RuntimeSessionRevocationHistoryEvidence(null, null, null, null, null, value.also { it.validate() }, null)
        fun reactivation(value: RuntimeAccountReactivationEvidenceV1): RuntimeSessionRevocationHistoryEvidence =
            RuntimeSessionRevocationHistoryEvidence(null, null, null, null, value.also { it.validate() }, null, null)
        const val MAX_IMAGES: Int = 2
        fun self(value: RuntimeSessionRevocationEvidenceV1): RuntimeSessionRevocationHistoryEvidence =
            RuntimeSessionRevocationHistoryEvidence(value.also { it.validate() }, null, null, null, null, null, null)
        fun retained(value: RuntimeRetainedSessionRevocationEvidenceV1): RuntimeSessionRevocationHistoryEvidence =
            RuntimeSessionRevocationHistoryEvidence(null, value.also { it.validate() }, null, null, null, null, null)
        fun disable(value: RuntimeAccountDisableEvidenceV1): RuntimeSessionRevocationHistoryEvidence =
            RuntimeSessionRevocationHistoryEvidence(null, null, value.also { it.validate() }, null, null, null, null)
        fun reset(value: RuntimeCredentialResetEvidenceV1): RuntimeSessionRevocationHistoryEvidence =
            RuntimeSessionRevocationHistoryEvidence(null, null, null, value.also { it.validate() }, null, null, null)
    }
}

internal class RuntimeSessionRevocationHistoryCapture private constructor(
    private val self: RuntimeSessionRevocationCapture?,
    private val retained: RuntimeRetainedSessionRevocationCapture?,
    private val disable: RuntimeAccountDisableCapture?,
    private val reset: RuntimeCredentialResetCapture?,
    private val reactivation: RuntimeAccountReactivationCapture?,
    private val activation: RuntimeAccountActivationCapture?,
    private val credentialFreeDisable: RuntimeCredentialFreeAccountDisableCapture?,
) {
    init { check(listOf(self, retained, disable, reset, reactivation, activation, credentialFreeDisable).count { it != null } == 1) }
    val scope: BranchScope get() = self?.scope ?: retained?.scope ?: disable?.scope ?: reset?.scope ?: reactivation?.scope ?: activation?.scope ?: checkNotNull(credentialFreeDisable).scope
    val actorId: DomainId get() = self?.actorId ?: retained?.actorId ?: disable?.actorId ?: reset?.actorId ?: reactivation?.actorId ?: activation?.actorId ?: checkNotNull(credentialFreeDisable).actorId
    val requestId: DomainId get() = self?.requestId ?: retained?.requestId ?: disable?.requestId ?: reset?.requestId ?: reactivation?.requestId ?: activation?.requestId ?: checkNotNull(credentialFreeDisable).requestId
    val semanticDigest: PayloadDigest get() = self?.semanticDigest ?: retained?.semanticDigest ?: disable?.semanticDigest ?: reset?.semanticDigest ?: reactivation?.semanticDigest ?: activation?.semanticDigest ?: checkNotNull(credentialFreeDisable).semanticDigest
    val images: MutableList<RuntimeAuthenticationImageV1> get() = self?.images ?: retained?.images ?: disable?.images ?: reset?.images ?: reactivation?.images ?: activation?.images ?: checkNotNull(credentialFreeDisable).images
    val retainedTarget: RuntimeRetainedSessionRevocationTargetV1? get() = retained?.target
    fun evidence(): RuntimeSessionRevocationHistoryEvidence =
        self?.let { RuntimeSessionRevocationHistoryEvidence.self(it.evidence()) }
            ?: retained?.let { RuntimeSessionRevocationHistoryEvidence.retained(it.evidence()) }
            ?: disable?.let { RuntimeSessionRevocationHistoryEvidence.disable(it.evidence()) }
            ?: reset?.let { RuntimeSessionRevocationHistoryEvidence.reset(it.evidence()) }
            ?: reactivation?.let { RuntimeSessionRevocationHistoryEvidence.reactivation(it.evidence()) }
            ?: activation?.let { RuntimeSessionRevocationHistoryEvidence.activation(it.evidence()) }
            ?: RuntimeSessionRevocationHistoryEvidence.credentialFreeDisable(checkNotNull(credentialFreeDisable).evidence())
    override fun toString(): String = "RuntimeSessionRevocationHistoryCapture([REDACTED])"

    companion object {
        fun credentialFreeDisable(value: RuntimeCredentialFreeAccountDisableCapture): RuntimeSessionRevocationHistoryCapture =
            RuntimeSessionRevocationHistoryCapture(null, null, null, null, null, null, value)
        fun activation(value: RuntimeAccountActivationCapture): RuntimeSessionRevocationHistoryCapture =
            RuntimeSessionRevocationHistoryCapture(null, null, null, null, null, value, null)
        fun reactivation(value: RuntimeAccountReactivationCapture): RuntimeSessionRevocationHistoryCapture =
            RuntimeSessionRevocationHistoryCapture(null, null, null, null, value, null, null)
        fun self(value: RuntimeSessionRevocationCapture): RuntimeSessionRevocationHistoryCapture =
            RuntimeSessionRevocationHistoryCapture(value, null, null, null, null, null, null)
        fun retained(value: RuntimeRetainedSessionRevocationCapture): RuntimeSessionRevocationHistoryCapture =
            RuntimeSessionRevocationHistoryCapture(null, value, null, null, null, null, null)
        fun disable(value: RuntimeAccountDisableCapture): RuntimeSessionRevocationHistoryCapture =
            RuntimeSessionRevocationHistoryCapture(null, null, value, null, null, null, null)
        fun reset(value: RuntimeCredentialResetCapture): RuntimeSessionRevocationHistoryCapture =
            RuntimeSessionRevocationHistoryCapture(null, null, null, value, null, null, null)
    }
}

/** Target ownership determines which native-seeded reducer accounts for the two mutations. */
internal enum class RuntimeRetainedSessionRevocationTargetOwner { ORIGINAL, ENROLLED_EMPLOYEE }

internal object RuntimeRetainedSessionRevocationEvidenceCodec {
    private const val MAX_BYTES: Int = 16 * 1024
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; isLenient = false
        coerceInputValues = false; allowSpecialFloatingPointValues = false; allowStructuredMapKeys = false; prettyPrint = false
    }
    private val prefix = ("{\"schema\":\"" + RuntimeRetainedSessionRevocationEvidenceV1.PROFILE + "\",").encodeToByteArray()
    fun hasProfile(bytes: ByteArray): Boolean = bytes.size in prefix.size..MAX_BYTES && prefix.indices.all { bytes[it] == prefix[it] }
    fun encode(value: RuntimeRetainedSessionRevocationEvidenceV1): ByteArray = guarded {
        value.validate()
        val bytes = json.encodeToString(RuntimeRetainedSessionRevocationEvidenceV1.serializer(), value)
            .encodeToByteArray(throwOnInvalidSequence = true)
        if (bytes.size !in 1..MAX_BYTES) { bytes.fill(0); historyCapacityV2(false) }
        bytes
    }
    fun decode(bytes: ByteArray): RuntimeRetainedSessionRevocationEvidenceV1 = guarded {
        historyCapacityV2(bytes.size in 1..MAX_BYTES)
        val value = json.decodeFromString(RuntimeRetainedSessionRevocationEvidenceV1.serializer(), bytes.decodeToString(throwOnInvalidSequence = true))
        val canonical = encode(value)
        try { historyShapeV2(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        value
    }
    fun dependencies(value: RuntimeRetainedSessionRevocationEvidenceV1): ByteArray = json
        .encodeToString(RuntimeRetainedSessionRevocationDependenciesV1.serializer(), value.dependencies).encodeToByteArray()
    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: RuntimeHistoryFormatRejectedV2) { throw failure }
    catch (_: Exception) { throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED) }
}

private fun retainedSessionIssuancesMatch(session: LocalSessionRecord, credentialRevision: Revision,
    admissionRevision: Revision, access: LocalAccessIssuance, refresh: LocalRefreshIssuance): Boolean =
    access.scope == session.scope && access.actorId == session.actorId && access.sessionId == session.sessionId &&
        access.deviceId == session.deviceId && access.generation == session.generation &&
        access.credentialRevisionAtIssue == credentialRevision && access.admissionRevisionAtIssue == admissionRevision &&
        refresh.scope == access.scope && refresh.actorId == access.actorId && refresh.sessionId == access.sessionId &&
        refresh.deviceId == access.deviceId && refresh.generation == access.generation &&
        refresh.credentialRevisionAtIssue == access.credentialRevisionAtIssue &&
        refresh.admissionRevisionAtIssue == access.admissionRevisionAtIssue &&
        refresh.issuerContextId == access.issuerContextId && refresh.issuedAt == access.issuedAt &&
        access.expiresAtElapsedMillis <= refresh.expiresAtElapsedMillis

private fun retainedRevocationDigest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
