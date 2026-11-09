package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Bounded historical proof for the original employee with native original policy,
 * admission origins and configuration. Additional-device authentication requires its earlier
 * genuinely consumed native admission and complete chronological account/bucket joins.
 * The distinct first cohost revocation projects admission
 * only after original authority/whole-census proof; revoked-device startup remains held.
 * Credentials advance only through the distinct captured reset
 * and its actual native-opened AFTER; arbitrary replacements remain held. This is NOT an
 * application session store. No bearer is restored or command executed. Every predicted image still needs the independent
 * native-selected plaintext/key/lineage join in RuntimeHistoryScanner before admission.
 *
 * Retains at most the account's existing 256 session identities and their immutable issuance
 * facts. Archival removes them from this verification working set ONLY after predicting every
 * immutable archive insertion. Historical archive/issuance rows themselves are never removed.
 * Unknown identities and arbitrary changed policy/credentials invalidate the bounded proof. A captured
 * ordinary command may preserve it only after the original historical authorization join below;
 * its actual mutations and complete business producer remain separate mandatory joins.
 */
internal class RuntimeOriginalAuthenticationReplay private constructor(
    private val original: OriginalNativeHistorySnapshot,
    private var account: ProtectedLocalAccount,
    private var policy: ActivatedLocalPolicy,
    private var admission: LocalDeviceAdmission,
    private val configuration: LocalAuthConfiguration,
    private val currentAccountDigest: PayloadDigest,
    private val currentPolicyDigest: PayloadDigest,
    private val currentAccountThrottle: PersistentThrottleState,
    private val currentDeviceThrottle: PersistentThrottleState,
    private var nativeAccountSeedDigest: PayloadDigest?,
) {
    private val coordinates = original.record.allocation.coordinates
    private val scope = coordinates.scope
    private val actor = coordinates.firstActorId
    private val seeds = checkNotNull(original.record.receipt).core.seeds
    private var verifiedPolicyDigest = seeds.policy
    private var currentAdmissionDigest = digest(ProtectedLocalSecurityCodec.encodeAdmission(admission))
    private data class Issuance(val access: LocalAccessIssuance, val refresh: LocalRefreshIssuance)
    private val issuances = linkedMapOf<DomainId, Issuance>()
    private var accountThrottle: PersistentThrottleState? = null
    private var deviceThrottle: PersistentThrottleState? = null
    private val additionalAdmissions = linkedMapOf<DomainId, LocalDeviceAdmission>()
    private val additionalDeviceThrottles = linkedMapOf<DomainId, PersistentThrottleState>()
    private val rows = LongArray(RuntimeHistoryTableV2.entries.size).also { counts ->
        for (table in ORIGINAL_TABLES) counts[table.ordinal] = if (table == RuntimeHistoryTableV2.THROTTLE) 2L else 1L
    }
    var mutationCount: Long = 8L
        private set

    val sessionCount: Long get() = account.state.sessions.size.toLong()
    val tipCount: Long get() = rows.sum()
    fun count(table: RuntimeHistoryTableV2): Long = rows[table.ordinal]

    /** Account BEFORE from the genuine chronological C1 seed, not today's census. */
    internal fun deviceAdmissionAccountDigest(session: SelfOwnedNativeHistoryReadSession): PayloadDigest? {
        if (nativeAccountSeedDigest != seeds.account || session.original !== original) return null
        return digest(ProtectedLocalSecurityCodec.encodeAccount(account))
    }

    /** Only the coordinator's already-bound native admission may add a device origin.
     * Admission owns these INSERT counts; authentication preserves and advances this bucket
     * without resetting the account or any other device's guessing protection. */
    internal fun deviceAdmissionThrottle(source: NativeDeviceAdmissionHistorySource) {
        val next = source.requested.admission
        historyShapeV2(nativeAccountSeedDigest == seeds.account && source.requested.request.scope == scope &&
            source.originalCohostDeviceId == coordinates.deviceId && next.deviceId != coordinates.deviceId &&
            next.admitted && next.revision == Revision(0) && next.deviceId !in additionalAdmissions &&
            additionalAdmissions.size < 255 && source.evidence.accountDigests.singleOrNull { it.first == actor }?.second ==
                digest(ProtectedLocalSecurityCodec.encodeAccount(account)))
        val key = ThrottleKey(scope, actor, ThrottleDimension.Device(next.deviceId))
        val fresh = PersistentThrottleState(key, Revision(0), 0, source.evidence.eventAt, 0, null)
        historyShapeV2(source.evidence.images.singleOrNull { it.table == ProtectedLocalTable.THROTTLE &&
            it.recordId == actor && it.dimension == ProtectedLocalRecordKey.Throttle(key).dimensionKey }?.plaintextDigest ==
            digest(ProtectedLocalSecurityCodec.encodeThrottle(fresh)))
        additionalAdmissions[next.deviceId] = next
        additionalDeviceThrottles[next.deviceId] = fresh
    }

    /** Receiving projection only for the actual native C1 seed lane. The older census
     * candidate has no origin here and must never become proven recipient absence/state. */
    internal fun nativeHandoffRecipientFacts(session: SelfOwnedNativeHistoryReadSession): RuntimeHistoricalLocalHandoffRecipientFacts? {
        val seed = nativeAccountSeedDigest ?: return null
        historyShapeV2(session.original === original && seed == seeds.account)
        return RuntimeHistoricalLocalHandoffRecipientFacts.capture(account, policy, configuration)
    }

    /** New approval authority comparisons require the ACTUAL native chronological ACCOUNT
     * seed. The older census candidate cannot gain either human's approval history lane. */
    internal fun approvalActor(session: SelfOwnedNativeHistoryReadSession,
        header: RuntimeHistoryEventHeaderV2): RuntimeActionApprovalHistoryActorFacts? {
        val seed = nativeAccountSeedDigest ?: return null
        historyShapeV2(session.original === original && seed == seeds.account)
        return RuntimeActionApprovalHistoryActorFacts.founding(session, original, seed, header, account.state,
            account.credentialRevision, account.credential != null, policy, admission,
            RuntimeThrottleDependenciesV1(digest(ProtectedLocalSecurityCodec.encodeAccount(account)), verifiedPolicyDigest,
                digest(ProtectedLocalSecurityCodec.encodeAdmission(admission)), seeds.configuration),
            issuances.mapValues { it.value.access })
    }

    /** Predict only the one immutable serial audit after its dedicated authentic join.
     * Legacy census seeds and generic owned-decision facts cannot advance this count. */
    internal fun serialPublicationRequest(proof: RuntimeSerialPublicationHistoryRequest) {
        historyShapeV2(nativeAccountSeedDigest == seeds.account && proof.actorId == actor)
        proof.accountFounding(original)
        mutationCount = historyAddV2(mutationCount, 1L, RuntimeHistoryProfileV2.MAX_MUTATIONS)
        rows[RuntimeHistoryTableV2.AUTH_EVENT.ordinal] = historyAddV2(rows[RuntimeHistoryTableV2.AUTH_EVENT.ordinal],
            1L, RuntimeHistoryProfileV2.MAX_MUTATIONS)
    }

    private fun dependencies(): RuntimeThrottleDependenciesV1 = RuntimeThrottleDependenciesV1(
        digest(ProtectedLocalSecurityCodec.encodeAccount(account)), verifiedPolicyDigest, seeds.admission, seeds.configuration)

    private fun authenticationAdmission(deviceId: DomainId): LocalDeviceAdmission =
        if (deviceId == coordinates.deviceId) admission else additionalAdmissions[deviceId] ?: malformed()

    private fun authenticationDependencies(device: LocalDeviceAdmission): RuntimeThrottleDependenciesV1 =
        if (device.deviceId == coordinates.deviceId) dependencies()
        else RuntimeThrottleDependenciesV1(digest(ProtectedLocalSecurityCodec.encodeAccount(account)), verifiedPolicyDigest,
            digest(ProtectedLocalSecurityCodec.encodeAdmission(device)), seeds.configuration)

    /** The coordinator calls this only after the exact native original event/capsule binding.
     * Legacy census seeding cannot acquire this lane. Credentials, issuance, admission,
     * configuration and persistent throttle incarnations are preserved, never re-seeded. */
    internal fun policyActivation(source: NativeEmployeePolicyActivationHistorySource): Boolean {
        if (nativeAccountSeedDigest != seeds.account || source.requested.request.scope != scope ||
            source.requested.request.targetId != actor) return false
        val transition = RuntimeEmployeePolicyActivationTransition(source, account, policy)
        policy = transition.policy; verifiedPolicyDigest = transition.policyDigest; account = transition.account
        accountImages(transition.images)
        return true
    }

    fun throttle(value: RuntimeThrottleEvidenceV1) {
        val images = applyThrottle(value)
        historyShapeV2(images.isNotEmpty()) // A no-write request must not invent a history event.
        accountImages(images)
    }

    private fun applyThrottle(value: RuntimeThrottleEvidenceV1): List<RuntimeAuthenticationImageV1> {
        value.validate()
        val device = authenticationAdmission(value.deviceId)
        historyShapeV2(value.scope == scope && value.actorId == actor && device.admitted &&
            value.policy.value() == policy.throttlePolicy && value.dependencies == authenticationDependencies(device))
        val beforeAccount = value.before(value.accountKey)
        val beforeDevice = value.before(value.deviceKey)
        val previousAccount = accountThrottle
        val previousDevice = if (value.deviceId == coordinates.deviceId) deviceThrottle
            else additionalDeviceThrottles[value.deviceId] ?: malformed()
        if (previousAccount == null)
            historyShapeV2(digest(ProtectedLocalSecurityCodec.encodeThrottle(beforeAccount)) == seeds.accountThrottle)
        else historyShapeV2(previousAccount == beforeAccount)
        if (previousDevice == null) historyShapeV2(value.deviceId == coordinates.deviceId &&
            digest(ProtectedLocalSecurityCodec.encodeThrottle(beforeDevice)) == seeds.deviceThrottle)
        else historyShapeV2(previousDevice == beforeDevice)
        accountThrottle = value.after(value.accountKey)
        if (value.deviceId == coordinates.deviceId) deviceThrottle = value.after(value.deviceKey)
        else additionalDeviceThrottles[value.deviceId] = value.after(value.deviceKey)
        // Exact event-order continuity proves an unchanged peer as well as a changed bucket.
        // Each changed bucket still has its own genuine native image/lineage proof.
        return value.changed().map { image(ProtectedLocalRecordKey.Throttle(it.key), it.revision,
            ProtectedLocalSecurityCodec.encodeThrottle(it)) }
    }

    fun authentication(value: RuntimeAuthenticationEvidenceV1) {
        value.validate()
        val device = authenticationAdmission(value.deviceId)
        historyShapeV2(value.scope == scope && value.actorId == actor && device.admitted &&
            value.dependencies == authenticationDependencies(device))
        val images = mutableListOf<RuntimeAuthenticationImageV1>()
        value.throttle?.let { images += applyThrottle(it) }
        val now = LocalAuthenticationObservation(value.issuerContextId, value.at)
        if (value.action == RuntimeAuthenticationActionV1.LOGOUT) {
            val previous = checkNotNull(value.previousSessionId)
            val old = checkNotNull(issuances[previous])
            val session = account.state.sessions.singleOrNull { it.sessionId == previous }
            historyShapeV2(session?.status == SessionStatus.ACTIVE && old.access.deviceId == value.deviceId)
            // Expired access/work/old-service credentials may revoke this original identity,
            // but never obtain a new session. The actual writer separately verifies the secret.
            val transition = LocalSessions.revoke(account.state, previous) as? SessionTransition.Applied ?: malformed()
            replaceAccount(transition.state, images)
            images += event(value, LocalAuthEventKind.LOGGED_OUT, previous)
        } else {
            val previous = value.previousSessionId?.let { checkNotNull(issuances[it]) }
            if (value.action == RuntimeAuthenticationActionV1.REFRESH) {
                val retained = checkNotNull(previous)
                historyShapeV2(retained.access.deviceId == value.deviceId &&
                    LocalRefreshEligibility.matchesCurrentFacts(retained.refresh, retained.access,
                        account, device, configuration, now))
            } else {
                val expired = account.state.sessions.filter { session ->
                    if (session.status != SessionStatus.ACTIVE || session.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) false
                    else {
                        val retained = checkNotNull(issuances[session.sessionId])
                        LocalSessionMaintenance.retirementRequired(account, session, retained.access, retained.refresh,
                            authenticationAdmission(session.deviceId), now)
                    }
                }.map { it.sessionId }.toSet()
                if (expired.isNotEmpty()) {
                    val retired = LocalSessions.revokeSelected(account.state, expired) as? SessionTransition.Applied ?: malformed()
                    replaceAccount(retired.state, images)
                }
            }
            LocalSessionMaintenance.archiveWhenFull(account.state)?.let { archival ->
                for (session in archival.archived) {
                    historyShapeV2(issuances.remove(session.sessionId) != null)
                    images += image(ProtectedLocalRecordKey.SessionArchive(scope, session.sessionId), Revision(0),
                        LocalAuthenticationCodec.encodeArchive(LocalSessionArchive(session, value.at)))
                }
                replaceAccount(archival.retained, images)
            }
            val newId = checkNotNull(value.newSessionId)
            val transition = if (previous == null) LocalSessions.signIn(account.state, newId, value.deviceId,
                policy.sessionPolicy, value.explicitDeviceMove) else LocalSessions.rotate(account.state, previous.access.sessionId, newId)
            when (transition) {
                is SessionTransition.Denied -> historyShapeV2(value.denial == transition.reason)
                is SessionTransition.Applied -> {
                    historyShapeV2(value.denial == null)
                    val suppliedAccess = checkNotNull(value.accessValue())
                    val suppliedRefresh = checkNotNull(value.refreshValue())
                    val newSession = checkNotNull(transition.newSession)
                    val start = previous?.refresh?.workStartedAt ?: value.at
                    val workMillis = previous?.refresh?.workLifetimeMillis ?: configuration.tokens.workLifetimeMillis
                    historyShapeV2(workMillis <= Long.MAX_VALUE - start.elapsedMillis)
                    val end = start.elapsedMillis + workMillis
                    historyShapeV2(start.bootId == value.at.bootId && value.at.elapsedMillis >= start.elapsedMillis && value.at.elapsedMillis < end)
                    val access = LocalAccessIssuance(scope, actor, newId, value.deviceId, newSession.generation,
                        account.credentialRevision, device.revision, value.issuerContextId, value.at,
                        minOf(configuration.tokens.accessLifetimeMillis, end - value.at.elapsedMillis), suppliedAccess.tokenHash)
                    val refresh = LocalRefreshIssuance(scope, actor, newId, value.deviceId, newSession.generation,
                        account.credentialRevision, device.revision, configuration.revision, value.issuerContextId, value.at,
                        previous?.refresh?.originalSessionId ?: newId, start, workMillis, suppliedRefresh.tokenHash)
                    historyShapeV2(digest(ProtectedLocalSecurityCodec.encodeIssuance(access)) ==
                        digest(ProtectedLocalSecurityCodec.encodeIssuance(suppliedAccess)) &&
                        digest(LocalAuthenticationCodec.encodeRefresh(refresh)) == digest(LocalAuthenticationCodec.encodeRefresh(suppliedRefresh)))
                    replaceAccount(transition.state, images)
                    images += image(ProtectedLocalRecordKey.SessionIssuance(scope, newId), Revision(0), ProtectedLocalSecurityCodec.encodeIssuance(access))
                    images += image(ProtectedLocalRecordKey.RefreshIssuance(scope, newId), Revision(0), LocalAuthenticationCodec.encodeRefresh(refresh))
                    images += event(value, if (previous == null) LocalAuthEventKind.SIGNED_IN else LocalAuthEventKind.REFRESHED, newId)
                    historyShapeV2(issuances.put(newId, Issuance(access, refresh)) == null && issuances.size <= 256)
                }
            }
        }
        historyShapeV2(images.isNotEmpty() && images == value.images)
        accountImages(images)
    }

    /**
     * Verify a MASTER_LIFECYCLE audit against this original's genuine chronological session,
     * account, admission, policy and issuance evidence at the event cut. Non-null metadata is
     * not authority: the active session/device/generation, access window, policy revision and
     * MASTER_LIFECYCLE/ADMINISTER grant are all joined before returning true.
     */
    internal fun lifecycle(event: LocalAuthEvent, audit: LocalMasterLifecycleAudit): Boolean {
        val deviceId = audit.deviceId ?: return false
        val admissionRevision = audit.admissionRevision ?: return false
        val sessionGeneration = audit.sessionGeneration ?: return false
        val issuerContextId = audit.issuerContextId ?: return false
        if (event.operatorId != actor || event.sessionId == null || event.accountRevision != account.state.revision ||
            !account.state.enabled || account.credential == null || policy.revision != audit.policyRevision) return false
        val session = account.state.sessions.singleOrNull {
            it.sessionId == event.sessionId && it.status == SessionStatus.ACTIVE
        } ?: return false
        if (session.actorId != actor || session.scope != scope || session.deviceId != deviceId ||
            session.generation != sessionGeneration ||
            session.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) return false
        val device = authenticationAdmission(deviceId)
        if (!device.admitted || device.revision != admissionRevision) return false
        val access = issuances[session.sessionId]?.access ?: return false
        if (access.actorId != actor || access.sessionId != session.sessionId || access.deviceId != deviceId ||
            access.generation != sessionGeneration || access.admissionRevisionAtIssue != admissionRevision ||
            access.credentialRevisionAtIssue != account.credentialRevision ||
            access.issuerContextId != issuerContextId ||
            event.at.bootId != access.issuedAt.bootId ||
            event.at.elapsedMillis < access.issuedAt.elapsedMillis ||
            event.at.elapsedMillis >= access.expiresAtElapsedMillis) return false
        return AuthorizationEvaluator.historicalLocalEmployeeAllows(actor, scope, deviceId,
            session.sessionId, sessionGeneration, admissionRevision, AuthenticationPurpose.INTERACTIVE,
            audit.policyRevision, account.state.enabled, account.credential != null,
            session.status == SessionStatus.ACTIVE, device.admitted, policy.grants,
            runtimeLifecycleRequirement(scope, audit, event))
    }

    private fun replaceAccount(next: LocalAccountSessionState, images: MutableList<RuntimeAuthenticationImageV1>) {
        account = ProtectedLocalAccount(next, account.credentialRevision, account.credential)
        images += image(ProtectedLocalRecordKey.Account(scope, actor), next.revision, ProtectedLocalSecurityCodec.encodeAccount(account))
    }

    /** Original event-order facts, never today's authorization state or a restored principal.
     * Every predicted protected image is still joined to native plaintext/lineage by the scanner.
     * The supplied immutable access window must be exactly the original issuance, unextended. */
    fun command(value: RuntimeCommandAuthorizationEvidenceV1): Boolean {
        val supplied = value.authorization
        if (value.actionApprovalId != null || supplied.scope != scope || supplied.actorId != actor ||
            supplied.deviceId != coordinates.deviceId || supplied.principalKind != PrincipalKind.LOCAL_EMPLOYEE ||
            supplied.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) return false
        val originalAccess = issuances[supplied.sessionId]?.access ?: return false
        val identity = VerifiedLocalIdentity(actor, scope, coordinates.deviceId, supplied.sessionId,
            supplied.generation, supplied.admissionRevision, AuthenticationPurpose.INTERACTIVE)
        historyShapeV2(supplied.accountRevision == account.state.revision &&
            supplied.credentialRevision == account.credentialRevision && supplied.policyRevision == policy.revision &&
            supplied.admissionRevision == admission.revision &&
            supplied.dependencies == RuntimeCommandAuthorizationDependenciesV1(
                digest(ProtectedLocalSecurityCodec.encodeAccount(account)), verifiedPolicyDigest, seeds.admission,
                digest(ProtectedLocalSecurityCodec.encodeIssuance(originalAccess))) &&
            supplied.access.issuerContextId == originalAccess.issuerContextId && supplied.access.issuedAt == originalAccess.issuedAt &&
            supplied.access.expiresAtElapsedMillis == originalAccess.expiresAtElapsedMillis &&
            supplied.access.credentialRevisionAtIssue == originalAccess.credentialRevisionAtIssue &&
            supplied.access.admissionRevisionAtIssue == originalAccess.admissionRevisionAtIssue)
        historyShapeV2(LocalAccessEligibility.matchesCurrentFacts(identity, originalAccess, account.state,
            account.credentialRevision, account.credential != null, admission, supplied.access.issuerContextId,
            supplied.access.observedAt))
        return AuthorizationEvaluator.permitsHistoricalInteractiveLocalRequirement(policy, actor, scope, value.requirement.value())
    }

    /** Closed historical self-revocation. No policy/credential/admission change or second
     * actor is admitted. The original interactive issuance and complete original policy must
     * permit this exact target/revision/request before the reducer advances the account. */
    fun selfSessionRevocation(value: RuntimeSessionRevocationEvidenceV1,
        finalDecision: NativeHistoryProtectedFinalDecision?): Boolean {
        value.validate()
        val supplied = value.authorization
        if (supplied.scope != scope || supplied.actorId != actor || supplied.deviceId != coordinates.deviceId ||
            supplied.principalKind != PrincipalKind.LOCAL_EMPLOYEE ||
            supplied.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) return false
        val originalAccess = issuances[supplied.sessionId]?.access ?: return false
        val identity = VerifiedLocalIdentity(actor, scope, coordinates.deviceId, supplied.sessionId,
            supplied.generation, supplied.admissionRevision, AuthenticationPurpose.INTERACTIVE)
        historyShapeV2(supplied.accountRevision == account.state.revision &&
            supplied.credentialRevision == account.credentialRevision && supplied.policyRevision == policy.revision &&
            supplied.admissionRevision == admission.revision &&
            supplied.dependencies == RuntimeCommandAuthorizationDependenciesV1(
                digest(ProtectedLocalSecurityCodec.encodeAccount(account)), verifiedPolicyDigest, seeds.admission,
                digest(ProtectedLocalSecurityCodec.encodeIssuance(originalAccess))) &&
            supplied.access.issuerContextId == originalAccess.issuerContextId && supplied.access.issuedAt == originalAccess.issuedAt &&
            supplied.access.expiresAtElapsedMillis == originalAccess.expiresAtElapsedMillis &&
            supplied.access.credentialRevisionAtIssue == originalAccess.credentialRevisionAtIssue &&
            supplied.access.admissionRevisionAtIssue == originalAccess.admissionRevisionAtIssue)
        historyShapeV2(LocalAccessEligibility.matchesCurrentFacts(identity, originalAccess, account.state,
            account.credentialRevision, account.credential != null, admission, supplied.access.issuerContextId,
            supplied.access.observedAt))
        finalDecision?.let { decision ->
            historyShapeV2(decision.declaration == value.protectedFinalDeclaration() &&
                LocalAccessEligibility.matchesCurrentFacts(identity, originalAccess, account.state,
                    account.credentialRevision, account.credential != null, admission,
                    decision.observed.issuerContextId, decision.observed.moment))
        }
        if (!AuthorizationEvaluator.permitsHistoricalInteractiveLocalRequirement(policy, actor, scope, value.requirement.value())) return false
        val before = account.state
        historyShapeV2(before.revision.value < Long.MAX_VALUE - 1L && before.generation.value < Long.MAX_VALUE - 1L)
        val transition = LocalSessions.revoke(before, supplied.sessionId) as? SessionTransition.Applied ?: malformed()
        historyShapeV2(transition.state.enabled && transition.revokedSessionIds == setOf(supplied.sessionId))
        val images = mutableListOf<RuntimeAuthenticationImageV1>()
        replaceAccount(transition.state, images)
        images += image(ProtectedLocalRecordKey.AuthEvent(scope, value.command.requestId), Revision(0), LocalAuthenticationCodec.encodeEvent(
            LocalAuthEvent(scope, value.command.requestId, LocalAuthEventKind.SESSION_REVOKED, actor, actor,
                supplied.sessionId, account.state.revision, value.at, value.semanticDigest)))
        historyShapeV2(images == value.images)
        accountImages(images)
        return true
    }
    /** Operator proof at this actual event cut for the distinct retained-session profile.
     * This does not advance a target or substitute today's policy/access for original facts. */
    internal fun retainedSessionRevocationAuthority(value: RuntimeRetainedSessionRevocationEvidenceV1,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        value.validate()
        return accountAdministrationAuthority(value.authorization, value.requirement, value.protectedFinalDeclaration(), finalDecision)
    }

    /** Separate closed disable profile; its operator must be eligible at both original
     * observation and the actual native protected-final observation before any target moves. */
    internal fun accountDisableAuthority(value: RuntimeAccountDisableEvidenceV1,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        value.validate()
        return accountAdministrationAuthority(value.authorization, value.requirement, value.protectedFinalDeclaration(), finalDecision)
    }

    /** Reset never authorizes through its replacement credential or a null credential. The
     * operator proof is the identical original event-cut/native-final authority as disable. */
    internal fun credentialResetAuthority(value: RuntimeCredentialResetEvidenceV1,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        value.validate()
        if (account.credential == null) return false
        return accountAdministrationAuthority(value.authorization, value.requirement, value.protectedFinalDeclaration(), finalDecision)
    }

    /** Prove this original operator/final cut, then predict ONLY the immutable audit.
     * No target read/state, account/session/credential change, issuance or dispatch occurs. */
    internal fun administrationCancellation(value: RuntimeAdministrationCancellationEvidenceV1,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        value.validate()
        if (account.credential == null || !accountAdministrationAuthority(value.authorization, value.requirement,
                value.protectedFinalDeclaration(), finalDecision)) return false
        val images = listOf(image(ProtectedLocalRecordKey.AuthEvent(scope, value.command.requestId), Revision(0),
            LocalAuthenticationCodec.encodeEvent(LocalAuthEvent(scope, value.command.requestId, LocalAuthEventKind.ADMINISTRATION_CANCELLED,
                value.command.targetId, actor, value.command.sessionId, null, value.at, value.semanticDigest))))
        historyShapeV2(images == value.images)
        accountImages(images)
        return true
    }

    /** The original actual device ADMINISTER requirement and unchanged authority helper.
     * A revoked target is never used to re-authorize the operator at the native-final cut. */
    internal fun deviceRevocationAuthority(value: RuntimeDeviceRevocationEvidenceV1,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        value.validate()
        if (account.credential == null) return false
        return accountAdministrationAuthority(value.authorization, value.requirement, value.protectedFinalDeclaration(), finalDecision)
    }

    /** Separate additional-device profile. Target history is proved by the admission
     * coordinator; this helper proves only this original operator's current event cut. */
    internal fun additionalDeviceRevocationAuthority(value: RuntimeDeviceRevocationEvidenceV2,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        value.validate()
        if (account.credential == null) return false
        return accountAdministrationAuthority(value.authorization, value.requirement, value.protectedFinalDeclaration(), finalDecision)
    }

    /** Whole canonical account BEFORE for a distinct target; no target session is seeded
     * or authenticated here. The coordinator checks every account before any effect. */
    internal fun additionalDeviceRevocationCensus(deviceId: DomainId): RuntimeDeviceRevocationAccountV1 {
        historyShapeV2(deviceId != coordinates.deviceId && account.state.scope == scope && account.state.actorId == actor &&
            issuances.keys == account.state.sessions.map { it.sessionId }.toSet())
        return RuntimeDeviceRevocationAccountV1.capture(account, deviceId)
    }

    /** Exact account effect only after the coordinator's complete PRE-effect census and
     * original operator/native-final proof. The retained issuances are not credentials. */
    internal fun additionalDeviceRevocationAccount(value: RuntimeDeviceRevocationEvidenceV2,
        record: RuntimeDeviceRevocationAccountV1) {
        historyShapeV2(record.actorId == actor && record == additionalDeviceRevocationCensus(value.command.targetId))
        val next = deviceRevocationAccountAfterV1(account, value.command.targetId)
        if (!record.affected) { historyShapeV2(next === account); return }
        val target = additionalAdmissions[value.command.targetId] ?: malformed()
        historyShapeV2(target.admitted && digest(ProtectedLocalSecurityCodec.encodeAdmission(target)) == value.admissionBeforeDigest)
        val expected = RuntimeDeviceRevocationImageV1.capture(ProtectedLocalRecordKey.Account(scope, actor), next.state.revision,
            ProtectedLocalSecurityCodec.encodeAccount(next))
        historyShapeV2(expected == value.images.single { it.table == RuntimeHistoryTableV2.ACCOUNT && it.recordId == actor })
        account = next
        mutationCount = historyAddV2(mutationCount, 1L, RuntimeHistoryProfileV2.MAX_MUTATIONS)
    }

    /** Negative-only projection after every complete target-session account effect.
     * Retained issuance/rotation/logout facts remain; they cannot authenticate the device again. */
    internal fun additionalDeviceRevocationAdmission(value: RuntimeDeviceRevocationEvidenceV2) {
        val before = additionalAdmissions[value.command.targetId] ?: malformed()
        historyShapeV2(account.state.sessions.none {
            it.deviceId == before.deviceId && it.status == SessionStatus.ACTIVE
        } && digest(ProtectedLocalSecurityCodec.encodeAdmission(before)) == value.admissionBeforeDigest)
        val next = deviceRevocationAdmissionAfterV1(before)
        historyShapeV2(RuntimeDeviceRevocationImageV1.capture(ProtectedLocalRecordKey.Admission(scope, before.deviceId),
            next.revision, ProtectedLocalSecurityCodec.encodeAdmission(next)) == value.images.first())
        additionalAdmissions[before.deviceId] = next
    }

    internal fun additionalDeviceRevocationAudit(value: RuntimeDeviceRevocationEvidenceV2) {
        historyShapeV2(value.authorization.scope == scope && value.authorization.actorId == actor &&
            deviceRevocationAuditImageV2(value) == value.images.last())
        mutationCount = historyAddV2(mutationCount, 1L, RuntimeHistoryProfileV2.MAX_MUTATIONS)
        rows[RuntimeHistoryTableV2.AUTH_EVENT.ordinal] = historyAddV2(rows[RuntimeHistoryTableV2.AUTH_EVENT.ordinal], 1L,
            RuntimeHistoryProfileV2.MAX_MUTATIONS)
    }

    /** Genuine event-cut account census only, never a seed from current revoked rows.
     * Every retained identity already belongs to this actor's own native-seeded replay. */
    internal fun deviceRevocationCensus(deviceId: DomainId): RuntimeDeviceRevocationAccountV1 {
        historyShapeV2(deviceId == coordinates.deviceId && account.state.scope == scope && account.state.actorId == actor &&
            issuances.keys == account.state.sessions.map { it.sessionId }.toSet())
        return RuntimeDeviceRevocationAccountV1.capture(account, deviceId)
    }
    /** Called only after the coordinator has checked ALL census records and original
     * operator/native-final authority. Preserve private credentials and immutable issuances. */
    internal fun deviceRevocationAccount(value: RuntimeDeviceRevocationEvidenceV1, record: RuntimeDeviceRevocationAccountV1) {
        historyShapeV2(record.actorId == actor && record == deviceRevocationCensus(value.command.targetId))
        val next = deviceRevocationAccountAfterV1(account, value.command.targetId)
        if (!record.affected) { historyShapeV2(next === account); return }
        val expected = RuntimeDeviceRevocationImageV1.capture(ProtectedLocalRecordKey.Account(scope, actor), next.state.revision,
            ProtectedLocalSecurityCodec.encodeAccount(next))
        historyShapeV2(expected == value.images.single { it.table == RuntimeHistoryTableV2.ACCOUNT && it.recordId == actor })
        account = next
        mutationCount = historyAddV2(mutationCount, 1L, RuntimeHistoryProfileV2.MAX_MUTATIONS)
    }
    /** This one audit belongs to the ORIGINAL OPERATOR only, not every affected account. */
    internal fun deviceRevocationAudit(value: RuntimeDeviceRevocationEvidenceV1) {
        historyShapeV2(value.authorization.scope == scope && value.authorization.actorId == actor &&
            deviceRevocationAuditImageV1(value) == value.images.last())
        mutationCount = historyAddV2(mutationCount, 1L, RuntimeHistoryProfileV2.MAX_MUTATIONS)
        rows[RuntimeHistoryTableV2.AUTH_EVENT.ordinal] = historyAddV2(rows[RuntimeHistoryTableV2.AUTH_EVENT.ordinal], 1L,
            RuntimeHistoryProfileV2.MAX_MUTATIONS)
    }

    /** One SHARED admission UPDATE is attributed to the founding replay, regardless of
     * the original operator. Its complete public descriptors remain the immutable C1 ones. */
    internal fun deviceRevocationAdmission(value: RuntimeDeviceRevocationEvidenceV1) {
        historyShapeV2(value.command.targetId == coordinates.deviceId &&
            digest(ProtectedLocalSecurityCodec.encodeAdmission(admission)) == value.admissionBeforeDigest &&
            value.admissionBeforeDigest == seeds.admission)
        val next = deviceRevocationAdmissionAfterV1(admission)
        historyShapeV2(RuntimeDeviceRevocationImageV1.capture(ProtectedLocalRecordKey.Admission(scope, coordinates.deviceId), next.revision,
            ProtectedLocalSecurityCodec.encodeAdmission(next)) == value.images.first())
        admission = next
        mutationCount = historyAddV2(mutationCount, 1L, RuntimeHistoryProfileV2.MAX_MUTATIONS)
    }

    private fun accountAdministrationAuthority(supplied: RuntimeCommandAuthorizationSnapshotV1,
        requirement: RuntimeCommandRequirementV1, declaration: NativeHistoryProtectedFinalDeclaration,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        if (supplied.scope != scope || supplied.actorId != actor || supplied.deviceId != coordinates.deviceId ||
            supplied.principalKind != PrincipalKind.LOCAL_EMPLOYEE ||
            supplied.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) return false
        val originalAccess = issuances[supplied.sessionId]?.access ?: return false
        val identity = VerifiedLocalIdentity(actor, scope, coordinates.deviceId, supplied.sessionId,
            supplied.generation, supplied.admissionRevision, AuthenticationPurpose.INTERACTIVE)
        historyShapeV2(supplied.accountRevision == account.state.revision &&
            supplied.credentialRevision == account.credentialRevision && supplied.policyRevision == policy.revision &&
            supplied.admissionRevision == admission.revision &&
            supplied.dependencies == RuntimeCommandAuthorizationDependenciesV1(
                digest(ProtectedLocalSecurityCodec.encodeAccount(account)), verifiedPolicyDigest, seeds.admission,
                digest(ProtectedLocalSecurityCodec.encodeIssuance(originalAccess))) &&
            supplied.access.issuerContextId == originalAccess.issuerContextId && supplied.access.issuedAt == originalAccess.issuedAt &&
            supplied.access.expiresAtElapsedMillis == originalAccess.expiresAtElapsedMillis &&
            supplied.access.credentialRevisionAtIssue == originalAccess.credentialRevisionAtIssue &&
            supplied.access.admissionRevisionAtIssue == originalAccess.admissionRevisionAtIssue)
        historyShapeV2(LocalAccessEligibility.matchesCurrentFacts(identity, originalAccess, account.state,
            account.credentialRevision, account.credential != null, admission, supplied.access.issuerContextId,
            supplied.access.observedAt) && finalDecision.declaration == declaration &&
            LocalAccessEligibility.matchesCurrentFacts(identity, originalAccess, account.state,
                account.credentialRevision, account.credential != null, admission,
                finalDecision.observed.issuerContextId, finalDecision.observed.moment))
        return AuthorizationEvaluator.permitsHistoricalInteractiveLocalRequirement(policy, actor, scope, requirement.value())
    }

    /** Called only by the closed target/operator coordinator after genuine operator proof.
     * Target issuance is original retained data; expiry cannot make it a new credential. */
    internal fun retainedSessionRevocationTarget(value: RuntimeRetainedSessionRevocationEvidenceV1): Boolean {
        value.validate()
        if (value.authorization.scope != scope || value.command.targetId != actor ||
            value.target.session.deviceId != coordinates.deviceId) return false
        val before = account.state
        val target = value.target
        val retained = issuances[target.session.sessionId] ?: return false
        historyShapeV2(before.enabled && account.credential != null && before.revision == value.command.expectedRevision &&
            before.generation == target.accountGeneration && account.credentialRevision == target.credentialRevision &&
            before.sessions.singleOrNull { it.sessionId == target.session.sessionId } == target.session &&
            digest(ProtectedLocalSecurityCodec.encodeAccount(account)) == target.accountBeforeDigest &&
            target.matchesIssuances(retained.access, retained.refresh))
        val transition = LocalSessions.revoke(before, target.session.sessionId) as? SessionTransition.Applied ?: malformed()
        historyShapeV2(transition.state.enabled && transition.revokedSessionIds == setOf(target.session.sessionId))
        val images = mutableListOf<RuntimeAuthenticationImageV1>()
        replaceAccount(transition.state, images)
        images += image(ProtectedLocalRecordKey.AuthEvent(scope, value.command.requestId), Revision(0), LocalAuthenticationCodec.encodeEvent(
            LocalAuthEvent(scope, value.command.requestId, LocalAuthEventKind.SESSION_REVOKED, actor, value.authorization.actorId,
                target.session.sessionId, account.state.revision, value.at, value.semanticDigest)))
        historyShapeV2(images == value.images)
        accountImages(images)
        return true
    }

    /** Own complete native-founded prefix only, after the coordinator's operator proof.
     * Disable revokes every ACTIVE retained interactive identity without changing issuance
     * rows or restoring a bearer. A genuinely already-disabled prefix is not re-seeded. */
    internal fun accountDisableTarget(value: RuntimeAccountDisableEvidenceV1): Boolean {
        value.validate()
        if (value.authorization.scope != scope || value.command.targetId != actor ||
            value.authorization.deviceId != coordinates.deviceId) return false
        val before = account.state
        val target = value.target
        historyShapeV2(account.credential != null && before.revision == value.command.expectedRevision &&
            before.generation == target.accountGeneration && account.credentialRevision == target.credentialRevision &&
            digest(ProtectedLocalSecurityCodec.encodeAccount(account)) == target.accountBeforeDigest &&
            issuances.keys == before.sessions.map { it.sessionId }.toSet() && before.sessions.all {
                it.scope == scope && it.actorId == actor && it.deviceId == coordinates.deviceId &&
                    it.authenticationPurpose == AuthenticationPurpose.INTERACTIVE
            })
        val revoked = before.sessions.filter { it.status == SessionStatus.ACTIVE }.map { it.sessionId }.toSet()
        val transition = LocalSessions.disable(before) as? SessionTransition.Applied ?: malformed()
        historyShapeV2(!transition.state.enabled && transition.newSession == null &&
            transition.state.sessions.all { it.status == SessionStatus.REVOKED } && transition.revokedSessionIds == revoked)
        val images = mutableListOf<RuntimeAuthenticationImageV1>()
        replaceAccount(transition.state, images)
        images += image(ProtectedLocalRecordKey.AuthEvent(scope, value.command.requestId), Revision(0), LocalAuthenticationCodec.encodeEvent(
            LocalAuthEvent(scope, value.command.requestId, LocalAuthEventKind.ACCOUNT_DISABLED, actor, value.authorization.actorId,
                null, account.state.revision, value.at, value.semanticDigest)))
        historyShapeV2(images == value.images)
        accountImages(images)
        return true
    }

    /** Only the closed coordinator calls this after genuine operator/native-final proof.
     * The actual opened AFTER contributes its credential, never its account state. Reduce
     * this target's full original BEFORE, preserve all issuances and compare both images. */
    internal fun credentialResetTarget(value: RuntimeCredentialResetEvidenceV1,
        actualAfter: ProtectedLocalAccount): Boolean {
        value.validate()
        if (value.authorization.scope != scope || value.command.targetId != actor ||
            value.authorization.deviceId != coordinates.deviceId) return false
        val before = account.state
        val target = value.target
        historyShapeV2(before.enabled && before.revision == value.command.expectedRevision &&
            before.generation == target.accountGeneration && account.credentialRevision == target.credentialRevision &&
            digest(ProtectedLocalSecurityCodec.encodeAccount(account)) == target.accountBeforeDigest &&
            issuances.keys == before.sessions.map { it.sessionId }.toSet() && before.sessions.all {
                it.scope == scope && it.actorId == actor && it.deviceId == coordinates.deviceId &&
                    it.authenticationPurpose == AuthenticationPurpose.INTERACTIVE
            })
        val replacement = actualAfter.credential ?: malformed()
        historyShapeV2(actualAfter.state.scope == scope && actualAfter.state.actorId == actor &&
            actualAfter.credentialRevision == account.credentialRevision.next())
        val revoked = before.sessions.filter { it.status == SessionStatus.ACTIVE }.map { it.sessionId }.toSet()
        val transition = LocalSessions.revokeAll(before) as? SessionTransition.Applied ?: malformed()
        historyShapeV2(transition.state.enabled && transition.newSession == null &&
            transition.state.sessions.all { it.status == SessionStatus.REVOKED } && transition.revokedSessionIds == revoked)
        val expected = ProtectedLocalAccount(transition.state, account.credentialRevision.next(), replacement)
        historyShapeV2(digest(ProtectedLocalSecurityCodec.encodeAccount(expected)) ==
            digest(ProtectedLocalSecurityCodec.encodeAccount(actualAfter)))
        val images = listOf(
            image(ProtectedLocalRecordKey.Account(scope, actor), expected.state.revision, ProtectedLocalSecurityCodec.encodeAccount(expected)),
            image(ProtectedLocalRecordKey.AuthEvent(scope, value.command.requestId), Revision(0), LocalAuthenticationCodec.encodeEvent(
                LocalAuthEvent(scope, value.command.requestId, LocalAuthEventKind.CREDENTIAL_RESET, actor, value.authorization.actorId,
                    null, expected.state.revision, value.at, value.semanticDigest))))
        historyShapeV2(images == value.images)
        account = expected
        accountImages(images)
        return true
    }

    private fun event(value: RuntimeAuthenticationEvidenceV1, kind: LocalAuthEventKind, sessionId: DomainId): RuntimeAuthenticationImageV1 =
        image(ProtectedLocalRecordKey.AuthEvent(scope, value.eventId), Revision(0), LocalAuthenticationCodec.encodeEvent(
            LocalAuthEvent(scope, value.eventId, kind, actor, actor, sessionId, account.state.revision, value.at, null)))
    private fun accountImages(images: List<RuntimeAuthenticationImageV1>) {
        mutationCount = historyAddV2(mutationCount, images.size.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
        for (image in images) if (image.mutation == RuntimeHistoryMutationKindV2.INSERT)
            rows[image.table.ordinal] = historyAddV2(rows[image.table.ordinal], 1L, RuntimeHistoryProfileV2.MAX_MUTATIONS)
    }
    fun finish() {
        historyShapeV2(digest(ProtectedLocalSecurityCodec.encodeAccount(account)) == currentAccountDigest &&
            digest(LocalEffectivePolicyCodec.encodePolicy(policy)) == verifiedPolicyDigest && verifiedPolicyDigest == currentPolicyDigest &&
            digest(ProtectedLocalSecurityCodec.encodeAdmission(admission)) == currentAdmissionDigest &&
            issuances.keys == account.state.sessions.map { it.sessionId }.toSet() &&
            digest(ProtectedLocalSecurityCodec.encodeThrottle(currentAccountThrottle)) ==
                (accountThrottle?.let { digest(ProtectedLocalSecurityCodec.encodeThrottle(it)) } ?: seeds.accountThrottle) &&
            digest(ProtectedLocalSecurityCodec.encodeThrottle(currentDeviceThrottle)) ==
                (deviceThrottle?.let { digest(ProtectedLocalSecurityCodec.encodeThrottle(it)) } ?: seeds.deviceThrottle))
    }
    fun clear() { issuances.clear(); accountThrottle = null; deviceThrottle = null; nativeAccountSeedDigest = null
        additionalAdmissions.clear(); additionalDeviceThrottles.clear() }

    companion object {
        private val ORIGINAL_TABLES = setOf(RuntimeHistoryTableV2.BRANCH, RuntimeHistoryTableV2.ACCOUNT, RuntimeHistoryTableV2.POLICY,
            RuntimeHistoryTableV2.ADMISSION, RuntimeHistoryTableV2.CONFIGURATION, RuntimeHistoryTableV2.AUTH_EVENT, RuntimeHistoryTableV2.THROTTLE)
        private fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
        private fun image(key: ProtectedLocalRecordKey, revision: Revision, bytes: ByteArray): RuntimeAuthenticationImageV1 =
            RuntimeAuthenticationImageV1.capture(key, revision, bytes)
        private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)

        /** The chronological controller protocol supplied this ACTUAL opened C1 account.
         * Current census is only a final hash/unchanged dependency closure, never a source
         * of credential bytes. A missing original image has no fromCensus fallback. */
        fun fromNativeSeed(original: OriginalNativeHistorySnapshot, seed: ProtectedLocalAccount?, currentDigest: PayloadDigest?,
            policy: ActivatedLocalPolicy?, admission: LocalDeviceAdmission?, configuration: LocalAuthConfiguration?,
            accountThrottle: PersistentThrottleState?, deviceThrottle: PersistentThrottleState?): RuntimeOriginalAuthenticationReplay? {
            val receipt = original.record.receipt ?: return null
            if (seed == null || currentDigest == null || policy == null || admission == null || configuration == null ||
                accountThrottle == null || deviceThrottle == null || seed.credential == null || seed.credentialRevision != Revision(0)) return null
            val coordinates = original.record.allocation.coordinates
            val scope = coordinates.scope
            val actor = coordinates.firstActorId
            val state = seed.state
            val originPolicy = original.record.definition?.definition?.firstAccount?.policy ?: return null
            if (state.scope != scope || state.actorId != actor || !state.enabled || state.revision != Revision(0) ||
                state.generation != SessionGeneration(0) || state.sessions.isNotEmpty() || policy.scope != scope || policy.actorId != actor ||
                originPolicy.scope != scope || originPolicy.actorId != actor || originPolicy.revision != Revision(0) ||
                configuration.scope != scope || configuration.actorId != actor || admission.scope != scope || admission.deviceId != coordinates.deviceId ||
                accountThrottle.key != ThrottleKey(scope, actor, ThrottleDimension.Account) ||
                deviceThrottle.key != ThrottleKey(scope, actor, ThrottleDimension.Device(coordinates.deviceId))) return null
            val seeds = receipt.core.seeds
            if (digest(ProtectedLocalSecurityCodec.encodeAccount(seed)) != seeds.account ||
                digest(LocalEffectivePolicyCodec.encodePolicy(originPolicy)) != seeds.policy ||
                digest(ProtectedLocalSecurityCodec.encodeAdmission(admission)) != seeds.admission ||
                digest(LocalAuthenticationCodec.encodeConfiguration(configuration)) != seeds.configuration) return null
            return RuntimeOriginalAuthenticationReplay(original, seed, originPolicy, admission, configuration,
                currentDigest, digest(LocalEffectivePolicyCodec.encodePolicy(policy)), accountThrottle, deviceThrottle, seeds.account)
        }

        /** Explicit genuine C1/native ADMISSION seed lane for the chronological account
         * protocol. The native definition already retains the complete actual admission;
         * final current admission is a separate closure hash, NEVER reversed into rights.
         * Full C1 LINEAGE still independently opens/joins this original admission later. */
        fun fromNativeAdmissionSeed(original: OriginalNativeHistorySnapshot, seed: ProtectedLocalAccount?, currentDigest: PayloadDigest?,
            policy: ActivatedLocalPolicy?, currentAdmission: LocalDeviceAdmission?, configuration: LocalAuthConfiguration?,
            accountThrottle: PersistentThrottleState?, deviceThrottle: PersistentThrottleState?): RuntimeOriginalAuthenticationReplay? {
            val coordinates = original.record.allocation.coordinates
            if (currentAdmission == null || currentAdmission.scope != coordinates.scope ||
                currentAdmission.deviceId != coordinates.deviceId) return null
            val origin = original.record.definition?.definition?.masterAdmission ?: return null
            val seedDigest = original.record.receipt?.core?.seeds?.admission ?: return null
            if (origin.scope != coordinates.scope || origin.deviceId != coordinates.deviceId || !origin.admitted ||
                origin.revision != Revision(0) || digest(ProtectedLocalSecurityCodec.encodeAdmission(origin)) != seedDigest) return null
            return fromNativeSeed(original, seed, currentDigest, policy, origin, configuration, accountThrottle, deviceThrottle)?.also {
                it.currentAdmissionDigest = digest(ProtectedLocalSecurityCodec.encodeAdmission(currentAdmission))
            }
        }

        /**
         * The current protected credential is only a candidate for the original immutable
         * credential. Reconstructed original account bytes MUST match the native receipt's
         * original seed digest; the actual first historical seed is independently joined later.
         * A changed/reset/missing credential or policy yields no bounded proof, not re-seeding.
         */
        fun fromCensus(original: OriginalNativeHistorySnapshot, current: ProtectedLocalAccount?, policy: ActivatedLocalPolicy?,
            admission: LocalDeviceAdmission?, configuration: LocalAuthConfiguration?,
            accountThrottle: PersistentThrottleState?, deviceThrottle: PersistentThrottleState?): RuntimeOriginalAuthenticationReplay? {
            val receipt = original.record.receipt ?: return null
            if (current == null || policy == null || admission == null || configuration == null ||
                accountThrottle == null || deviceThrottle == null || current.credential == null || current.credentialRevision != Revision(0)) return null
            val coordinates = original.record.allocation.coordinates
            val scope = coordinates.scope
            val actor = coordinates.firstActorId
            if (current.state.scope != scope || current.state.actorId != actor || policy.scope != scope || policy.actorId != actor ||
                configuration.scope != scope || configuration.actorId != actor || admission.scope != scope || admission.deviceId != coordinates.deviceId ||
                accountThrottle.key != ThrottleKey(scope, actor, ThrottleDimension.Account) ||
                deviceThrottle.key != ThrottleKey(scope, actor, ThrottleDimension.Device(coordinates.deviceId))) return null
            val account = ProtectedLocalAccount(LocalAccountSessionState(scope, actor, true, Revision(0), SessionGeneration(0), emptyList()),
                Revision(0), current.credential)
            val seeds = receipt.core.seeds
            if (digest(ProtectedLocalSecurityCodec.encodeAccount(account)) != seeds.account ||
                digest(LocalEffectivePolicyCodec.encodePolicy(policy)) != seeds.policy ||
                digest(ProtectedLocalSecurityCodec.encodeAdmission(admission)) != seeds.admission ||
                digest(LocalAuthenticationCodec.encodeConfiguration(configuration)) != seeds.configuration) return null
            return RuntimeOriginalAuthenticationReplay(original, account, policy, admission, configuration,
                digest(ProtectedLocalSecurityCodec.encodeAccount(current)), seeds.policy, accountThrottle, deviceThrottle, null)
        }
    }
}
