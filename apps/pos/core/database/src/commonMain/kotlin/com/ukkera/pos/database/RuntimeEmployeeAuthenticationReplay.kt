package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Separate bounded reducer for one genuinely native-enrolled employee. It is seeded
 * ONLY from the actual native-selected original enrollment images,
 * never a current account/credential or the founding employee's firstActor derivations.
 * Each actor retains at most its existing 256 session identities; immutable rows/archives
 * are fully scanned and counted after their complete native image joins. This is not a
 * session store: it restores no bearer, signs in nobody and executes no business command.
 * Ordinary commands additionally need an exact registered historical authorization proof;
 * complete business/read/physical sources and effects are independently joined by core.
 * Credentials change only through captured reset and actual native AFTER. Arbitrary changed
 * policy/credential/admission/configuration and unjoined business
 * producers remain held by the full scanner rather than being guessed or re-seeded. */
internal class RuntimeEmployeeAuthenticationReplay private constructor(
    private val source: NativeEmployeeEnrollmentHistorySource,
    private var account: ProtectedLocalAccount,
    private var policy: ActivatedLocalPolicy,
    private val configuration: LocalAuthConfiguration,
    initialThrottles: Map<ThrottleKey, PersistentThrottleState>,
    private val earlierReplayTarget: RuntimeEarlierOrdinaryCommandReplayTarget?,
) {
    private val scope = source.requested.request.scope
    private val actor = source.requested.request.targetId
    private val cohostDeviceId = source.originalCohostDeviceId
    private var admission = source.requested.admissions.single { it.deviceId == cohostDeviceId }
    // Each added device needs its exact joined native CONSUMED origin. Admissions after
    // enrollment supply their own bucket; earlier admissions bind the actual enrollment seed.
    // Current rows or a retained admission list alone cannot create either lane.
    private val additionalAdmissions = linkedMapOf<DomainId, LocalDeviceAdmission>()
    private data class EnrollmentDeviceAdmissionOrigin(val session: SelfOwnedNativeHistoryReadSession,
        val source: NativeDeviceAdmissionHistorySource, val admissionDigest: PayloadDigest,
        val throttleSeed: LocalEmployeeEnrollmentProtectedImage)
    private val enrollmentDeviceAdmissionOrigins = linkedMapOf<DomainId, EnrollmentDeviceAdmissionOrigin>()
    private var policyDigest = source.evidence.images[1].plaintextDigest
    private val configurationDigest = source.evidence.images[2].plaintextDigest
    private val admissionDigest = source.evidence.admissionDigests.single { it.first == cohostDeviceId }.second
    private data class Issuance(val access: LocalAccessIssuance, val refresh: LocalRefreshIssuance)
    private val issuances = linkedMapOf<DomainId, Issuance>()
    // Ordinary proofs are retained only while their exact original/core/producer join is
    // active. Coverage and event order survive their removal; no stored proof is reissued.
    private val commands = linkedMapOf<Long, RuntimeEmployeeCommandHistoryAuthorization>()
    private var issuedCommands = 0L
    private var joinedCommands = 0L
    private var retiredCommands = 0L
    private var issuedCommandMutations = 0L
    private var lastIssuedProofSequence = source.child.historySequence
    private class NativeOriginalPrefixMode(val session: SelfOwnedNativeHistoryReadSession, val cutoffSequence: Long)
    private var nativeOriginalPrefixMode: NativeOriginalPrefixMode? = null
    private val supervisorReviews = linkedMapOf<Long, RuntimeEmployeeSupervisorReviewHistoryAuthorization>()
    private val purchasingPolicyRejections = linkedMapOf<Long, RuntimeEmployeePurchasingPolicyRejectionHistoryAuthorization>()
    private val orderReassignmentRejections = linkedMapOf<Long, RuntimeEmployeeOrderReassignmentRejectionHistoryAuthorization>()
    private val openingSupplierObligationRejections = linkedMapOf<Long, RuntimeEmployeeOpeningSupplierObligationRejectionHistoryAuthorization>()
    private val invoiceCaptureRejections = linkedMapOf<Long, RuntimeEmployeeInvoiceCaptureRejectionHistoryAuthorization>()
    private val capturedInvoiceMatchRejections = linkedMapOf<Long, RuntimeEmployeeCapturedInvoiceMatchRejectionHistoryAuthorization>()
    private val branchActivityActivationRejections = linkedMapOf<Long, RuntimeEmployeeBranchActivityActivationRejectionHistoryAuthorization>()
    private val floorConfigurationRejections = linkedMapOf<Long, RuntimeEmployeeFloorConfigurationRejectionHistoryAuthorization>()
    private val configurationRejections = linkedMapOf<Long, RuntimeEmployeeConfigurationRejectionHistoryAuthorization>()
    private val returnRightsActivationRejections = linkedMapOf<Long, RuntimeEmployeeReturnRightsActivationRejectionHistoryAuthorization>()
    private val retailCustomerReceiptRejections = linkedMapOf<Long, RuntimeEmployeeRetailCustomerReceiptRejectionHistoryAuthorization>()
    private val foodCheckRejections = linkedMapOf<Long, RuntimeEmployeeFoodCheckRejectionHistoryAuthorization>()
    private val foodPaymentRejections = linkedMapOf<Long, RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization>()
    private var issuedFoodPaymentRejections = 0L
    private var issuedFoodPaymentRejectionMutations = 0L
    private var joinedFoodPaymentRejections = 0L
    private var retiredFoodPaymentRejections = 0L
    private val returnCaseQualificationRejections = linkedMapOf<Long, RuntimeEmployeeReturnCaseQualificationRejectionHistoryAuthorization>()
    private val returnStockDispositionRejections = linkedMapOf<Long, RuntimeEmployeeReturnStockDispositionRejectionHistoryAuthorization>()
    private var enabled = true
    private val throttles = initialThrottles.toMutableMap()
    private val rows = LongArray(RuntimeHistoryTableV2.entries.size).also { counts ->
        for (table in setOf(RuntimeHistoryTableV2.ACCOUNT,RuntimeHistoryTableV2.POLICY,
                RuntimeHistoryTableV2.CONFIGURATION,RuntimeHistoryTableV2.AUTH_EVENT)) counts[table.ordinal] = 1L
        counts[RuntimeHistoryTableV2.THROTTLE.ordinal] = initialThrottles.size.toLong()
    }
    var mutationCount: Long = source.evidence.images.size.toLong()
        private set
    val sessionCount: Long get() = account.state.sessions.size.toLong()
    val tipCount: Long get() = rows.sum()
    val commandCount: Long get() = issuedCommands
    val joinedCommandCount: Long get() = joinedCommands
    val retainedCommandCount: Int get() = commands.size
    val commandMutationCount: Long get() = issuedCommandMutations
    val supervisorReviewCount: Long get() = supervisorReviews.size.toLong()
    val supervisorReviewMutationCount: Long get() = supervisorReviews.values.sumOf { it.evidence.capturedChanges.toLong() }
    val purchasingPolicyRejectionCount: Long get() = purchasingPolicyRejections.size.toLong()
    val purchasingPolicyRejectionMutationCount: Long get() = purchasingPolicyRejections.values.sumOf { it.evidence.capturedChanges.toLong() }
    val orderReassignmentRejectionCount: Long get() = orderReassignmentRejections.size.toLong()
    val orderReassignmentRejectionMutationCount: Long get() = orderReassignmentRejections.values.sumOf { it.evidence.capturedChanges.toLong() }
    val openingSupplierObligationRejectionCount: Long get() = openingSupplierObligationRejections.size.toLong()
    val openingSupplierObligationRejectionMutationCount: Long get() = openingSupplierObligationRejections.values.sumOf { it.evidence.capturedChanges.toLong() }
    val invoiceCaptureRejectionCount: Long get() = invoiceCaptureRejections.size.toLong()
    val invoiceCaptureRejectionMutationCount: Long get() = invoiceCaptureRejections.values.sumOf { it.evidence.capturedChanges.toLong() }
    val capturedInvoiceMatchRejectionCount: Long get() = capturedInvoiceMatchRejections.size.toLong()
    val capturedInvoiceMatchRejectionMutationCount: Long get() = capturedInvoiceMatchRejections.values.sumOf { it.evidence.capturedChanges.toLong() }
    val branchActivityActivationRejectionCount: Long get() = branchActivityActivationRejections.size.toLong()
    val branchActivityActivationRejectionMutationCount: Long get() = branchActivityActivationRejections.values.sumOf { it.evidence.capturedChanges.toLong() }
    val floorConfigurationRejectionCount: Long get() = floorConfigurationRejections.size.toLong()
    val floorConfigurationRejectionMutationCount: Long get() = floorConfigurationRejections.values.sumOf { it.evidence.capturedChanges.toLong() }
    val configurationRejectionCount: Long get() = configurationRejections.size.toLong()
    val configurationRejectionMutationCount: Long get() = configurationRejections.values.sumOf { it.evidence.capturedChanges.toLong() }
    val returnRightsActivationRejectionCount: Long get() = returnRightsActivationRejections.size.toLong()
    val returnRightsActivationRejectionMutationCount: Long get() = returnRightsActivationRejections.values.sumOf { it.evidence.capturedChanges.toLong() }
    val retailCustomerReceiptRejectionCount: Long get() = retailCustomerReceiptRejections.size.toLong()
    val retailCustomerReceiptRejectionMutationCount: Long get() = retailCustomerReceiptRejections.values.sumOf { it.evidence.capturedChanges.toLong() }
    val foodCheckRejectionCount: Long get() = foodCheckRejections.size.toLong()
    val foodCheckRejectionMutationCount: Long get() = foodCheckRejections.values.sumOf { it.evidence.capturedChanges.toLong() }
    val foodPaymentRejectionCount: Long get() = issuedFoodPaymentRejections
    val foodPaymentRejectionMutationCount: Long get() = issuedFoodPaymentRejectionMutations
    val returnCaseQualificationRejectionCount: Long get() = returnCaseQualificationRejections.size.toLong()
    val returnStockDispositionRejectionCount: Long get() = returnStockDispositionRejections.size.toLong()
    val returnCaseQualificationRejectionMutationCount: Long get() = returnCaseQualificationRejections.values.sumOf { it.evidence.capturedChanges.toLong() }
    val returnStockDispositionRejectionMutationCount: Long get() = returnStockDispositionRejections.values.sumOf { it.evidence.capturedChanges.toLong() }
    fun count(table: RuntimeHistoryTableV2): Long = rows[table.ordinal]

    /** Exact account BEFORE at this activated original's event cut. */
    internal fun deviceAdmissionAccountDigest(session: SelfOwnedNativeHistoryReadSession): PayloadDigest {
        requireHandoffRecipientSource(session)
        return digest(ProtectedLocalSecurityCodec.encodeAccount(account))
    }

    /** Admission owns this INSERT's row/mutation counts. Keep its fresh bucket in this
     * actor's closure projection without resetting any existing authentication penalty. */
    internal fun deviceAdmissionThrottle(source: NativeDeviceAdmissionHistorySource) {
        val next = source.requested.admission
        historyShapeV2(enabled && source.requested.request.scope == scope &&
            source.child.historySequence > this.source.child.historySequence &&
            source.originalCohostDeviceId == cohostDeviceId && next.deviceId != cohostDeviceId &&
            next.admitted && next.revision == Revision(0) && next.deviceId !in additionalAdmissions &&
            additionalAdmissions.size < 255 && source.evidence.accountDigests.singleOrNull { it.first == actor }?.second ==
                digest(ProtectedLocalSecurityCodec.encodeAccount(account)))
        val key = ThrottleKey(scope, actor, ThrottleDimension.Device(next.deviceId))
        val fresh = PersistentThrottleState(key, Revision(0), 0, source.evidence.eventAt, 0, null)
        historyShapeV2(key !in throttles && source.evidence.images.singleOrNull { it.table == ProtectedLocalTable.THROTTLE &&
            it.recordId == actor && it.dimension == ProtectedLocalRecordKey.Throttle(key).dimensionKey }?.plaintextDigest ==
            digest(ProtectedLocalSecurityCodec.encodeThrottle(fresh)))
        additionalAdmissions[next.deviceId] = next
        throttles[key] = fresh
    }

    /** Admission preceded this enrollment. Bind only its genuine still-admitted original
     * to the already-opened enrollment seeds; no bucket, account state or count is changed. */
    internal fun enrollmentDeviceAdmission(session: SelfOwnedNativeHistoryReadSession,
        origin: NativeDeviceAdmissionHistorySource, commitment: PayloadDigest) {
        requireHandoffRecipientSource(session)
        historyShapeV2(matchesEnrollmentDeviceAdmissionOrigin(session, source, origin, commitment))
        val next = source.requested.admissions.single { it.deviceId == origin.requested.request.targetId }
        historyShapeV2(next.deviceId !in additionalAdmissions && next.deviceId !in enrollmentDeviceAdmissionOrigins &&
            additionalAdmissions.size < 255 &&
            digest(ProtectedLocalSecurityCodec.encodeAccount(account)) == source.evidence.images[0].plaintextDigest &&
            digest(LocalEffectivePolicyCodec.encodePolicy(policy)) == source.evidence.images[1].plaintextDigest &&
            policyDigest == source.evidence.images[1].plaintextDigest &&
            digest(LocalAuthenticationCodec.encodeConfiguration(configuration)) == configurationDigest)
        val accountKey = ThrottleKey(scope, actor, ThrottleDimension.Account)
        val deviceKey = ThrottleKey(scope, actor, ThrottleDimension.Device(next.deviceId))
        val accountSeed = PersistentThrottleState(accountKey, Revision(0), 0, source.evidence.eventAt, 0, null)
        val deviceSeed = PersistentThrottleState(deviceKey, Revision(0), 0, source.evidence.eventAt, 0, null)
        val seed = source.evidence.images.single { it.table == ProtectedLocalTable.THROTTLE && it.recordId == actor &&
            it.dimension == ProtectedLocalRecordKey.Throttle(deviceKey).dimensionKey }
        historyShapeV2(throttles[accountKey] == accountSeed && throttles[deviceKey] == deviceSeed &&
            source.evidence.images[3].plaintextDigest == digest(ProtectedLocalSecurityCodec.encodeThrottle(accountSeed)) &&
            seed.plaintextDigest == digest(ProtectedLocalSecurityCodec.encodeThrottle(deviceSeed)))
        additionalAdmissions[next.deviceId] = next
        enrollmentDeviceAdmissionOrigins[next.deviceId] = EnrollmentDeviceAdmissionOrigin(session, origin, commitment, seed)
    }

    /** Ownership only, after the coordinator's chronological operation join. Keep the
     * immutable seed binding when its real bucket advances; withdrawal removes positivity. */
    internal fun ownsEnrollmentDeviceThrottle(session: SelfOwnedNativeHistoryReadSession,
        origin: NativeDeviceAdmissionHistorySource, commitment: PayloadDigest, sequence: Long): Boolean {
        val deviceId = origin.requested.request.targetId
        val binding = enrollmentDeviceAdmissionOrigins[deviceId] ?: return false
        val device = additionalAdmissions[deviceId] ?: return false
        val key = ThrottleKey(scope, actor, ThrottleDimension.Device(deviceId))
        return enabled && binding.session === session && binding.source === origin && binding.admissionDigest == commitment &&
            session.employeeEnrollments.any { it === source } && session.deviceAdmissions.any { it === origin } &&
            origin.child.historySequence < source.child.historySequence && source.child.historySequence < sequence &&
            device.admitted && device.revision == Revision(0) &&
            digest(ProtectedLocalSecurityCodec.encodeAdmission(device)) == commitment && throttles[key]?.key == key &&
            source.evidence.images.singleOrNull { it.table == ProtectedLocalTable.THROTTLE && it.recordId == actor &&
                it.dimension == ProtectedLocalRecordKey.Throttle(key).dimensionKey } === binding.throttleSeed
    }

    /** The coordinator calls this only after ordinary EVENTS activates this enrollment.
     * Native prepass construction alone does not publish an earlier recipient origin. */
    internal fun nativeHandoffRecipientOrigin(session: SelfOwnedNativeHistoryReadSession,
        header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest): RuntimeHistoricalLocalHandoffRecipientFacts {
        requireHandoffRecipientSource(session)
        historyShapeV2(header.purpose == RuntimeWritePurposeV2.PROVISIONING && header.transactionId == source.frameId &&
            NativeHistoryRecordedHeadV2(header.sequence, eventDigest, header.installationGenesisDigest, header.commandEpoch,
                header.commandSequence, header.commandDigest, header.roleCommitment, header.recoveryCommitment) == source.child)
        return RuntimeHistoricalLocalHandoffRecipientFacts.capture(account, policy, configuration)
    }

    /** Own already-activated native prefix, after this complete account-changing event.
     * Only secret-free account facets and the unchanged original dependencies are copied. */
    internal fun handoffRecipientAccountAfter(session: SelfOwnedNativeHistoryReadSession,
        header: RuntimeHistoryEventHeaderV2): RuntimeHistoricalLocalHandoffRecipientFacts {
        requireHandoffRecipientSource(session)
        historyShapeV2(source.child.historySequence < header.sequence && source.child.commandEpoch == header.commandEpoch &&
            source.child.installationGenesisDigest == header.installationGenesisDigest)
        return RuntimeHistoricalLocalHandoffRecipientFacts.capture(account, policy, configuration)
    }

    private fun requireHandoffRecipientSource(session: SelfOwnedNativeHistoryReadSession) {
        val coordinates = session.original.record.allocation.coordinates
        historyShapeV2(enabled && session.employeeEnrollments.any { it === source } && scope == coordinates.scope &&
            actor != coordinates.firstActorId && cohostDeviceId == coordinates.deviceId)
    }

    /** Called only by the enrollment coordinator after its prepass and ordinary original
     * enrollment event have BOTH joined. No current credential/account seeds this DATA. */
    internal fun approvalActor(session: SelfOwnedNativeHistoryReadSession,
        header: RuntimeHistoryEventHeaderV2): RuntimeActionApprovalHistoryActorFacts {
        requireHandoffRecipientSource(session)
        return RuntimeActionApprovalHistoryActorFacts.enrolled(session, source, header, account.state,
            account.credentialRevision, account.credential != null, policy, admission,
            RuntimeThrottleDependenciesV1(digest(ProtectedLocalSecurityCodec.encodeAccount(account)), policyDigest,
                digest(ProtectedLocalSecurityCodec.encodeAdmission(admission)), configurationDigest),
            issuances.mapValues { it.value.access })
    }

    /** Audit accounting only, through the registered serial-specific final/actor proof.
     * No session, principal, policy grant or business command is restored or changed. */
    internal fun serialPublicationRequest(proof: RuntimeSerialPublicationHistoryRequest) {
        historyShapeV2(enabled && proof.actorId == actor)
        proof.accountEmployee(source)
        mutationCount = historyAddV2(mutationCount, 1L, RuntimeHistoryProfileV2.MAX_MUTATIONS)
        rows[RuntimeHistoryTableV2.AUTH_EVENT.ordinal] = historyAddV2(rows[RuntimeHistoryTableV2.AUTH_EVENT.ordinal],
            1L, RuntimeHistoryProfileV2.MAX_MUTATIONS)
    }

    private fun dependencies(): RuntimeThrottleDependenciesV1 = RuntimeThrottleDependenciesV1(
        digest(ProtectedLocalSecurityCodec.encodeAccount(account)), policyDigest, admissionDigest, configurationDigest)

    private fun authenticationAdmission(deviceId: DomainId): LocalDeviceAdmission =
        if (deviceId == cohostDeviceId) admission else additionalAdmissions[deviceId] ?: malformed()

    private fun authenticationDependencies(device: LocalDeviceAdmission): RuntimeThrottleDependenciesV1 =
        if (device.deviceId == cohostDeviceId) dependencies()
        else RuntimeThrottleDependenciesV1(digest(ProtectedLocalSecurityCodec.encodeAccount(account)), policyDigest,
            digest(ProtectedLocalSecurityCodec.encodeAdmission(device)), configurationDigest)

    internal fun policyActivation(original: NativeEmployeePolicyActivationHistorySource) {
        historyShapeV2(enabled && original.requested.request.scope == scope && original.requested.request.targetId == actor &&
            source.child.historySequence < original.child.historySequence && source.child.commandEpoch == original.child.commandEpoch &&
            source.child.installationGenesisDigest == original.child.installationGenesisDigest)
        val transition = RuntimeEmployeePolicyActivationTransition(original, account, policy)
        policy = transition.policy; policyDigest = transition.policyDigest; account = transition.account
        accountImages(transition.images)
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
        historyShapeV2(throttles[value.accountKey] == beforeAccount && throttles[value.deviceKey] == beforeDevice)
        throttles[value.accountKey] = value.after(value.accountKey)
        throttles[value.deviceKey] = value.after(value.deviceKey)
        // Exact event-order continuity proves an unchanged peer as well as a changed bucket.
        // Each changed bucket still has its own genuine native image/lineage proof.
        return value.changed().map { image(ProtectedLocalRecordKey.Throttle(it.key), it.revision,
            ProtectedLocalSecurityCodec.encodeThrottle(it)) }
    }

    fun authentication(value: RuntimeAuthenticationEvidenceV1) {
        value.validate()
        val device = authenticationAdmission(value.deviceId)
        historyShapeV2(account.credential != null && value.scope == scope && value.actorId == actor && device.admitted &&
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

    private fun replaceAccount(next: LocalAccountSessionState, images: MutableList<RuntimeAuthenticationImageV1>) {
        account = ProtectedLocalAccount(next, account.credentialRevision, account.credential)
        images += image(ProtectedLocalRecordKey.Account(scope, actor), next.revision, ProtectedLocalSecurityCodec.encodeAccount(account))
    }

    /** Genuine original access and account/policy facts at THIS event cut. The returned
     * object must remain registered with this native-seeded reducer and bind the exact
     * native event/read. It is inert history DATA, not a restored session or accepting grant.
     * Action approvals, changed security and a different device stay outside this profile. */
    fun command(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeCommandHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        if (value.actionApprovalId != null || value.purposeTag != RuntimeWritePurposeV2.COMMAND_APPLY.tag) return null
        historyShapeV2(header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY)
        earlierReplayTarget?.requireTarget(source, header, eventDigest, value)
        if (!originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence, ordinary = true)
        val nextIssued = historyAddV2(issuedCommands, 1L, RuntimeHistoryProfileV2.MAX_EVENTS)
        val nextMutations = historyAddV2(issuedCommandMutations, value.capturedChanges.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
        val proof = RuntimeEmployeeCommandHistoryAuthorization(this, source, header, eventDigest, value, policyDigest)
        commands[header.sequence] = proof
        issuedCommands = nextIssued
        issuedCommandMutations = nextMutations
        return proof
    }

    /** A separate native-seeded earlier replay may authenticate read-only ordinary
     * commands preceding its target without retaining their proofs. State-changing
     * events must still run their own real reducers and native image joins. */
    internal fun observeEarlierOrdinaryCommand(value: RuntimeCommandAuthorizationEvidenceV1,
        header: RuntimeHistoryEventHeaderV2): Boolean {
        historyShapeV2(enabled && issuedCommands == 0L)
        val target = earlierReplayTarget ?: malformed()
        value.validate()
        if (value.actionApprovalId != null || value.purposeTag != RuntimeWritePurposeV2.COMMAND_APPLY.tag) return false
        historyShapeV2(header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY)
        target.requirePrior(source, header, value)
        if (!originalInteractiveRequirement(value, header)) return false
        requireCommandOrder(header.sequence)
        return true
    }

    /** Authentication-only observation in the coordinator's exact native-target prefix.
     * Native account/security reducers still advance normally, but this ordinary command
     * issues and retains no proof and changes no command or mutation coverage count. */
    internal fun observeEarlierNativeOriginalPrefixCommand(value: RuntimeCommandAuthorizationEvidenceV1,
        header: RuntimeHistoryEventHeaderV2, session: SelfOwnedNativeHistoryReadSession,
        cutoffHeader: RuntimeHistoryEventHeaderV2): Boolean {
        requireNativeOriginalPrefixMode(session, cutoffHeader.sequence)
        value.validate()
        if (value.actionApprovalId != null || value.purposeTag != RuntimeWritePurposeV2.COMMAND_APPLY.tag) return false
        historyShapeV2(header.purpose == RuntimeWritePurposeV2.COMMAND_APPLY && header.sequence < cutoffHeader.sequence &&
            header.commandSequence > source.child.commandSequence && header.commandSequence < cutoffHeader.commandSequence &&
            header.scope == cutoffHeader.scope && header.assignmentId == cutoffHeader.assignmentId &&
            header.installationGenesisDigest == source.child.installationGenesisDigest &&
            header.installationGenesisDigest == cutoffHeader.installationGenesisDigest && header.commandEpoch == cutoffHeader.commandEpoch)
        if (!originalInteractiveRequirement(value, header)) return false
        requireCommandOrder(header.sequence)
        return true
    }

    /** Distinct CLOSED enrolled-employee supervisor Review lane. It uses the same exact
     * original interactive authorization comparison; neither Apply nor a generic rejection
     * can enter it. The proof is registered at THIS actual native enrollment/event cut. */
    fun supervisorReview(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeSupervisorReviewHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalSupervisorReviewProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeeSupervisorReviewHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        supervisorReviews[header.sequence] = proof
        return proof
    }

    /** Separate closed policy lane. Native original policy bytes, BEFORE sources, exact
     * result and all five non-domain mutations must still join in the business pass. */
    fun purchasingPolicyRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeePurchasingPolicyRejectionHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalPurchasingPolicyRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeePurchasingPolicyRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        purchasingPolicyRejections[header.sequence] = proof
        return proof
    }

    /** Closed candidate reassignment lane, distinct from Apply and other rejections. Only
     * original operator authority is registered here; exact Legacy subtype/refusal/source
     * and all nonbusiness mutations remain provisional until the business join completes. */
    fun orderReassignmentRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeOrderReassignmentRejectionHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalOrderReassignmentRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeeOrderReassignmentRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        orderReassignmentRejections[header.sequence] = proof
        return proof
    }

    /** Separate closed opening-obligation lane with the exact original amount-bound local
     * employee authority. Canonical request/refusal/source and native closure remain pending. */
    fun openingSupplierObligationRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeOpeningSupplierObligationRejectionHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalOpeningSupplierObligationRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeeOpeningSupplierObligationRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        openingSupplierObligationRejections[header.sequence] = proof
        return proof
    }

    /** Separate V2 capture-refusal proof at this original employee account/policy cut.
     * Documentary sources, canonical first refusal and native closure remain mandatory. */
    fun invoiceCaptureRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeInvoiceCaptureRejectionHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalInvoiceCaptureRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeeInvoiceCaptureRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        invoiceCaptureRejections[header.sequence] = proof
        return proof
    }

    /** Separate closed monetary refusal at this genuine original employee account/policy
     * cut. Full raw claim/revision, exact first refusal and native closure still must join. */
    fun capturedInvoiceMatchRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeCapturedInvoiceMatchRejectionHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalCapturedInvoiceMatchRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeeCapturedInvoiceMatchRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        capturedInvoiceMatchRejections[header.sequence] = proof
        return proof
    }

    /** Distinct CLOSED initial-activity lane, issued only after this genuine employee's
     * original account/policy/admission/issuance proves the whole direct requirement.
     * Candidate authenticity, prior activation and every native/business join remain
     * mandatory; neither Apply nor another rejection proof is registered or reused. */
    fun branchActivityActivationRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeBranchActivityActivationRejectionHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalBranchActivityActivationRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeeBranchActivityActivationRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        branchActivityActivationRejections[header.sequence] = proof
        return proof
    }

    /** Distinct CLOSED directory refusal candidate from this employee's original native
     * account/policy/admission/issuance cut. Exact directory sources, first refusal and
     * nonbusiness native mutations remain mandatory in the separate business join. */
    fun floorConfigurationRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeFloorConfigurationRejectionHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalFloorConfigurationRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeeFloorConfigurationRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        floorConfigurationRejections[header.sequence] = proof
        return proof
    }

    /** Separate CLOSED configuration/pricing refusal candidate from this employee's original native
     * account/policy/admission/issuance cut. The exact subtype, BEFORE target, first refusal
     * and all nonbusiness mutations remain pending the installed business join. */
    fun configurationRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeConfigurationRejectionHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalConfigurationRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeeConfigurationRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        configurationRejections[header.sequence] = proof
        return proof
    }

    /** Distinct CLOSED documentary return-rights refusal candidate from this employee's
     * original native account/policy/admission/issuance cut. Positive signed sources,
     * exact first refusal and every nonbusiness mutation remain pending the business join. */
    fun returnRightsActivationRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeReturnRightsActivationRejectionHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalReturnRightsActivationRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeeReturnRightsActivationRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        returnRightsActivationRejections[header.sequence] = proof
        return proof
    }

    /** Distinct CLOSED counter-observation lane from this employee's exact original
     * interactive account/policy/admission/issuance cut. The immutable paid Finalize,
     * complete receipt ancestry and exact first refusal remain pending the business join. */
    fun retailCustomerReceiptRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeRetailCustomerReceiptRejectionHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalRetailCustomerReceiptRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeeRetailCustomerReceiptRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        retailCustomerReceiptRejections[header.sequence] = proof
        return proof
    }

    /** Separate closed FOOD refusal registration from this employee's actual original
     * interactive account/policy/admission/issuance cut. Ordinary command() remains Apply-only. */
    fun foodCheckRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeFoodCheckRejectionHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalFoodCheckRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeeFoodCheckRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        foodCheckRejections[header.sequence] = proof
        return proof
    }

    /** CLOSED ordinary AlreadyApplied registration at this original enrolled employee's
     * real interactive account/policy/admission/issuance cut. The separate proof remains
     * bounded and cannot be reused as an ordinary Apply or another refusal authorization. */
    fun foodPaymentRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization? {
        historyShapeV2(enabled && nativeOriginalPrefixMode == null)
        value.validate()
        val profile = RuntimeHistoricalFoodPaymentRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        val target = earlierReplayTarget
        if (target == null) requireCommandCapacity(header.sequence)
        else {
            // Only this closed, strictly earlier refusal may issue a prefix proof.
            // The actual successful target keeps its separate ordinary accounting.
            historyShapeV2(issuedCommands == 0L)
            requireFoodPaymentRejectionsSettled()
            target.requirePriorFoodPaymentRejection(source, header, value)
            requireCommandRetentionCapacity(header.sequence)
        }
        return issueFoodPaymentRejection(value, header, eventDigest, profile)
    }

    /** Full-source refusal qualification in one exact native-original prefix. Unlike
     * ordinary observations, this issues a bounded proof that must join and retire
     * before another event; it cannot authorize or lend the successful native target. */
    internal fun foodPaymentRejectionEarlierNativeOriginalPrefix(value: RuntimeCommandAuthorizationEvidenceV1,
        header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        session: SelfOwnedNativeHistoryReadSession, cutoffHeader: RuntimeHistoryEventHeaderV2):
        RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization? {
        requireNativeOriginalPrefixMode(session, cutoffHeader.sequence)
        value.validate()
        val profile = RuntimeHistoricalFoodPaymentRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        historyShapeV2(header.sequence < cutoffHeader.sequence &&
            header.commandSequence > source.child.commandSequence && header.commandSequence < cutoffHeader.commandSequence &&
            header.scope == cutoffHeader.scope && header.assignmentId == cutoffHeader.assignmentId &&
            header.installationGenesisDigest == source.child.installationGenesisDigest &&
            header.installationGenesisDigest == cutoffHeader.installationGenesisDigest && header.commandEpoch == cutoffHeader.commandEpoch)
        requireCommandRetentionCapacity(header.sequence)
        return issueFoodPaymentRejection(value, header, eventDigest, profile)
    }

    private fun issueFoodPaymentRejection(value: RuntimeCommandAuthorizationEvidenceV1,
        header: RuntimeHistoryEventHeaderV2, eventDigest: PayloadDigest,
        profile: RuntimeHistoricalFoodPaymentRejectionProfile): RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization {
        val nextIssued = historyAddV2(issuedFoodPaymentRejections, 1L, RuntimeHistoryProfileV2.MAX_EVENTS)
        val nextMutations = historyAddV2(issuedFoodPaymentRejectionMutations, value.capturedChanges.toLong(), RuntimeHistoryProfileV2.MAX_MUTATIONS)
        val proof = RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        foodPaymentRejections[header.sequence] = proof
        issuedFoodPaymentRejections = nextIssued
        issuedFoodPaymentRejectionMutations = nextMutations
        return proof
    }

    /** Distinct CLOSED initial-entitlement revision-guard QUALIFY candidate from this employee's exact original
     * interactive account/policy/admission/issuance cut. Genuine CASE, initial entitlement,
     * complete qualification sources and the first refusal still require the business join. */
    fun returnCaseQualificationRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeReturnCaseQualificationRejectionHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalReturnCaseQualificationRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeeReturnCaseQualificationRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        returnCaseQualificationRejections[header.sequence] = proof
        return proof
    }

    /** Separate original enrolled-employee stale-disposition RETURN refusal lane.
     * The exact first refusal and genuine source/native footprint still join separately. */
    fun returnStockDispositionRejection(value: RuntimeCommandAuthorizationEvidenceV1, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest): RuntimeEmployeeReturnStockDispositionRejectionHistoryAuthorization? {
        historyShapeV2(enabled)
        value.validate()
        val profile = RuntimeHistoricalReturnStockDispositionRejectionProfile.fromCommand(CommandKey(value.commandName, value.commandVersion)) ?: return null
        if (!profile.matches(header, value) || !originalInteractiveRequirement(value, header)) return null
        requireCommandCapacity(header.sequence)
        val proof = RuntimeEmployeeReturnStockDispositionRejectionHistoryAuthorization(this, source, header, eventDigest, profile, value, policyDigest)
        returnStockDispositionRejections[header.sequence] = proof
        return proof
    }

    /** Shared comparison of the reducer's actual original interactive facts. Callers keep
     * their own closed purpose/profile gates; this primitive never registers a proof. */
    private fun originalInteractiveRequirement(value: RuntimeCommandAuthorizationEvidenceV1,
        header: RuntimeHistoryEventHeaderV2): Boolean {
        val supplied = value.authorization
        if (supplied.scope != scope || supplied.actorId != actor || supplied.deviceId != cohostDeviceId ||
            supplied.principalKind != PrincipalKind.LOCAL_EMPLOYEE || supplied.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) return false
        val originalAccess = issuances[supplied.sessionId]?.access ?: return false
        val identity = VerifiedLocalIdentity(actor, scope, cohostDeviceId, supplied.sessionId,
            supplied.generation, supplied.admissionRevision, AuthenticationPurpose.INTERACTIVE)
        historyShapeV2(header.changeCount == value.capturedChanges && header.sequence > source.child.historySequence &&
            header.commandEpoch == source.child.commandEpoch &&
            supplied.accountRevision == account.state.revision && supplied.credentialRevision == account.credentialRevision &&
            supplied.policyRevision == policy.revision && supplied.admissionRevision == admission.revision &&
            supplied.dependencies == RuntimeCommandAuthorizationDependenciesV1(
                digest(ProtectedLocalSecurityCodec.encodeAccount(account)), policyDigest, admissionDigest,
                digest(ProtectedLocalSecurityCodec.encodeIssuance(originalAccess))) &&
            supplied.access.issuerContextId == originalAccess.issuerContextId && supplied.access.issuedAt == originalAccess.issuedAt &&
            supplied.access.expiresAtElapsedMillis == originalAccess.expiresAtElapsedMillis &&
            supplied.access.credentialRevisionAtIssue == originalAccess.credentialRevisionAtIssue &&
            supplied.access.admissionRevisionAtIssue == originalAccess.admissionRevisionAtIssue)
        historyShapeV2(LocalAccessEligibility.matchesCurrentFacts(identity, originalAccess, account.state,
            account.credentialRevision, account.credential != null, admission, supplied.access.issuerContextId, supplied.access.observedAt))
        return AuthorizationEvaluator.permitsHistoricalInteractiveLocalRequirement(policy, actor, scope, value.requirement.value())
    }

    private fun requireCommandCapacity(sequence: Long, ordinary: Boolean = false) {
        historyShapeV2(nativeOriginalPrefixMode == null)
        earlierReplayTarget?.let { target ->
            historyShapeV2(ordinary)
            target.requireIssuableTarget(source, sequence)
        }
        requireCommandRetentionCapacity(sequence)
    }

    /** Retained population and chronology only. Prefix callers must first prove their
     * dedicated closed refusal and exact ordinary/native cutoff; generic gates stay above. */
    private fun requireCommandRetentionCapacity(sequence: Long) {
        historyCapacityV2(commands.size + supervisorReviews.size + purchasingPolicyRejections.size +
            orderReassignmentRejections.size + openingSupplierObligationRejections.size + invoiceCaptureRejections.size +
            capturedInvoiceMatchRejections.size +
            branchActivityActivationRejections.size + floorConfigurationRejections.size + configurationRejections.size +
            returnRightsActivationRejections.size + retailCustomerReceiptRejections.size + foodCheckRejections.size + foodPaymentRejections.size +
            returnCaseQualificationRejections.size + returnStockDispositionRejections.size < RuntimeBusinessHistoryJoin.MAX_COMMANDS)
        requireCommandOrder(sequence)
    }

    private fun requireCommandOrder(sequence: Long) {
        historyShapeV2(sequence > lastIssuedProofSequence && sequence !in commands && sequence !in supervisorReviews && sequence !in purchasingPolicyRejections &&
            sequence !in orderReassignmentRejections && sequence !in openingSupplierObligationRejections &&
            sequence !in invoiceCaptureRejections &&
            sequence !in capturedInvoiceMatchRejections &&
            sequence !in branchActivityActivationRejections && sequence !in floorConfigurationRejections && sequence !in configurationRejections &&
            sequence !in returnRightsActivationRejections && sequence !in retailCustomerReceiptRejections && sequence !in foodCheckRejections &&
            sequence !in foodPaymentRejections &&
            sequence !in returnCaseQualificationRejections && sequence !in returnStockDispositionRejections)
        lastIssuedProofSequence = sequence
    }

    internal fun requireIssuedReturnCaseQualificationRejection(proof: RuntimeEmployeeReturnCaseQualificationRejectionHistoryAuthorization) {
        historyShapeV2(enabled && returnCaseQualificationRejections[proof.sequence] === proof)
    }

    internal fun requireIssuedReturnStockDispositionRejection(proof: RuntimeEmployeeReturnStockDispositionRejectionHistoryAuthorization) {
        historyShapeV2(enabled && returnStockDispositionRejections[proof.sequence] === proof)
    }

    internal fun requireIssuedRetailCustomerReceiptRejection(proof: RuntimeEmployeeRetailCustomerReceiptRejectionHistoryAuthorization) {
        historyShapeV2(enabled && retailCustomerReceiptRejections[proof.sequence] === proof)
    }

    internal fun requireIssuedFoodCheckRejection(proof: RuntimeEmployeeFoodCheckRejectionHistoryAuthorization) {
        historyShapeV2(enabled && foodCheckRejections[proof.sequence] === proof)
    }

    internal fun requireIssuedFoodPaymentRejection(proof: RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization) {
        historyShapeV2(enabled && foodPaymentRejections[proof.sequence] === proof)
    }

    internal fun noteFoodPaymentRejectionJoined(proof: RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization) {
        requireIssuedFoodPaymentRejection(proof)
        historyShapeV2(!proof.joined && joinedFoodPaymentRejections < issuedFoodPaymentRejections)
        joinedFoodPaymentRejections = historyAddV2(joinedFoodPaymentRejections, 1L, RuntimeHistoryProfileV2.MAX_EVENTS)
    }

    /** Retire only this same-read proof after the complete native/source/business join.
     * Cumulative closure counts survive retirement; no grant or reusable proof remains. */
    internal fun retireJoinedFoodPaymentRejection(proof: RuntimeEmployeeFoodPaymentRejectionHistoryAuthorization,
        session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1) {
        requireIssuedFoodPaymentRejection(proof)
        proof.requireCaptured(session, header, eventDigest, evidence)
        historyShapeV2(proof.joined && retiredFoodPaymentRejections < joinedFoodPaymentRejections)
        val nextRetired = historyAddV2(retiredFoodPaymentRejections, 1L, RuntimeHistoryProfileV2.MAX_EVENTS)
        historyShapeV2(foodPaymentRejections.remove(proof.sequence) === proof)
        retiredFoodPaymentRejections = nextRetired
        proof.clear()
    }

    internal fun requireIssuedReturnRightsActivationRejection(proof: RuntimeEmployeeReturnRightsActivationRejectionHistoryAuthorization) {
        historyShapeV2(enabled && returnRightsActivationRejections[proof.sequence] === proof)
    }

    internal fun requireIssuedFloorConfigurationRejection(proof: RuntimeEmployeeFloorConfigurationRejectionHistoryAuthorization) {
        historyShapeV2(enabled && floorConfigurationRejections[proof.sequence] === proof)
    }

    internal fun requireIssuedConfigurationRejection(proof: RuntimeEmployeeConfigurationRejectionHistoryAuthorization) {
        historyShapeV2(enabled && configurationRejections[proof.sequence] === proof)
    }

    internal fun requireIssuedBranchActivityActivationRejection(proof: RuntimeEmployeeBranchActivityActivationRejectionHistoryAuthorization) {
        historyShapeV2(enabled && branchActivityActivationRejections[proof.sequence] === proof)
    }

    internal fun requireIssuedOpeningSupplierObligationRejection(proof: RuntimeEmployeeOpeningSupplierObligationRejectionHistoryAuthorization) {
        historyShapeV2(enabled && openingSupplierObligationRejections[proof.sequence] === proof)
    }

    internal fun requireIssuedInvoiceCaptureRejection(proof: RuntimeEmployeeInvoiceCaptureRejectionHistoryAuthorization) {
        historyShapeV2(enabled && invoiceCaptureRejections[proof.sequence] === proof)
    }

    internal fun requireIssuedCapturedInvoiceMatchRejection(proof: RuntimeEmployeeCapturedInvoiceMatchRejectionHistoryAuthorization) {
        historyShapeV2(enabled && capturedInvoiceMatchRejections[proof.sequence] === proof)
    }

    internal fun requireIssuedOrderReassignmentRejection(proof: RuntimeEmployeeOrderReassignmentRejectionHistoryAuthorization) {
        historyShapeV2(enabled && orderReassignmentRejections[proof.sequence] === proof)
    }

    internal fun requireIssuedPurchasingPolicyRejection(proof: RuntimeEmployeePurchasingPolicyRejectionHistoryAuthorization) {
        historyShapeV2(enabled && purchasingPolicyRejections[proof.sequence] === proof)
    }

    internal fun requireIssuedSupervisorReview(proof: RuntimeEmployeeSupervisorReviewHistoryAuthorization) {
        historyShapeV2(enabled && supervisorReviews[proof.sequence] === proof)
    }

    internal fun requireIssuedCommand(proof: RuntimeEmployeeCommandHistoryAuthorization) {
        historyShapeV2(enabled && commands[proof.sequence] === proof)
    }

    internal fun requireCommandSession(session: SelfOwnedNativeHistoryReadSession) {
        historyShapeV2(enabled)
        earlierReplayTarget?.requireSameSession(session)
        nativeOriginalPrefixMode?.let { historyShapeV2(it.session === session) }
    }

    internal fun markCommandJoined(proof: RuntimeEmployeeCommandHistoryAuthorization) {
        requireIssuedCommand(proof)
        historyShapeV2(!proof.joined && joinedCommands < issuedCommands)
        joinedCommands = historyAddV2(joinedCommands, 1L, RuntimeHistoryProfileV2.MAX_EVENTS)
    }

    /** Release only after every original/core/producer consumer has finished with this
     * exact registered proof. Retirement never creates a reusable historical grant. */
    internal fun retireJoinedCommand(proof: RuntimeEmployeeCommandHistoryAuthorization,
        session: SelfOwnedNativeHistoryReadSession, header: RuntimeHistoryEventHeaderV2,
        eventDigest: PayloadDigest, evidence: RuntimeCommandAuthorizationEvidenceV1) {
        requireIssuedCommand(proof)
        proof.requireCaptured(session, header, eventDigest, evidence)
        historyShapeV2(proof.joined && retiredCommands < joinedCommands)
        val nextRetired = historyAddV2(retiredCommands, 1L, RuntimeHistoryProfileV2.MAX_EVENTS)
        historyShapeV2(commands.remove(proof.sequence) === proof)
        retiredCommands = nextRetired
        proof.clear()
    }

    /** Scope settlement only. It deliberately cannot assert full native EOF/current
     * closure or admission for a replay that stopped at an earlier historical cut. */
    internal fun requireEarlierOrdinaryCommandSettled() {
        historyShapeV2(enabled)
        val target = earlierReplayTarget ?: malformed()
        val expected = if (target.owns(source)) 1L else 0L
        requireFoodPaymentRejectionsSettled()
        historyShapeV2(issuedCommands == expected && joinedCommands == expected && retiredCommands == expected &&
            (expected != 0L || issuedCommandMutations == 0L) &&
            commands.isEmpty() && supervisorReviews.isEmpty() && purchasingPolicyRejections.isEmpty() &&
            orderReassignmentRejections.isEmpty() && openingSupplierObligationRejections.isEmpty() &&
            invoiceCaptureRejections.isEmpty() && capturedInvoiceMatchRejections.isEmpty() &&
            branchActivityActivationRejections.isEmpty() && floorConfigurationRejections.isEmpty() &&
            configurationRejections.isEmpty() && returnRightsActivationRejections.isEmpty() &&
            retailCustomerReceiptRejections.isEmpty() && foodCheckRejections.isEmpty() && foodPaymentRejections.isEmpty() &&
            returnCaseQualificationRejections.isEmpty() && returnStockDispositionRejections.isEmpty())
    }

    /** Settlement of one native-seeded prefix. Requested Start proofs belong to separate
     * ordinary-target scopes; only fully joined and retired closed payment refusals may
     * have issued here. This cannot authorize its native target or close full EOF. */
    internal fun requireEarlierNativeOriginalPrefixSettled(session: SelfOwnedNativeHistoryReadSession,
        cutoffSequence: Long) {
        requireNativeOriginalPrefixMode(session, cutoffSequence)
        historyShapeV2(if (source.child.historySequence < cutoffSequence) lastIssuedProofSequence < cutoffSequence
            else lastIssuedProofSequence == source.child.historySequence)
    }

    private fun requireNativeOriginalPrefixMode(session: SelfOwnedNativeHistoryReadSession, cutoffSequence: Long) {
        requireHandoffRecipientSource(session)
        val cut = checkNotNull(session.original.record.parent)
        requireFoodPaymentRejectionsSettled()
        historyShapeV2(earlierReplayTarget == null &&
            cutoffSequence > 1L && cutoffSequence <= cut.historySequence &&
            issuedCommands == 0L && joinedCommands == 0L && retiredCommands == 0L && issuedCommandMutations == 0L &&
            commands.isEmpty() && supervisorReviews.isEmpty() && purchasingPolicyRejections.isEmpty() &&
            orderReassignmentRejections.isEmpty() && openingSupplierObligationRejections.isEmpty() &&
            invoiceCaptureRejections.isEmpty() && capturedInvoiceMatchRejections.isEmpty() &&
            branchActivityActivationRejections.isEmpty() && floorConfigurationRejections.isEmpty() &&
            configurationRejections.isEmpty() && returnRightsActivationRejections.isEmpty() &&
            retailCustomerReceiptRejections.isEmpty() && foodCheckRejections.isEmpty() && foodPaymentRejections.isEmpty() &&
            returnCaseQualificationRejections.isEmpty() && returnStockDispositionRejections.isEmpty())
        val retained = nativeOriginalPrefixMode
        if (retained == null) nativeOriginalPrefixMode = NativeOriginalPrefixMode(session, cutoffSequence)
        else historyShapeV2(retained.session === session && retained.cutoffSequence == cutoffSequence)
    }

    private fun requireFoodPaymentRejectionsSettled() {
        val changes = RuntimeHistoricalFoodPaymentRejectionProfile.CAPTURED_CHANGES.toLong()
        historyShapeV2(foodPaymentRejections.isEmpty() && issuedFoodPaymentRejections == joinedFoodPaymentRejections &&
            joinedFoodPaymentRejections == retiredFoodPaymentRejections && issuedFoodPaymentRejections <= Long.MAX_VALUE / changes &&
            issuedFoodPaymentRejectionMutations == issuedFoodPaymentRejections * changes)
    }

    /** Historical self-revocation for THIS native-enrolled employee only. Its own original
     * interactive issuance and complete retained policy must permit the exact request. The
     * actual v4 final observation remains mandatory for restart; a missing witness stays held. */
    fun selfSessionRevocation(value: RuntimeSessionRevocationEvidenceV1,
        finalDecision: NativeHistoryProtectedFinalDecision?): Boolean {
        value.validate()
        val supplied = value.authorization
        if (supplied.scope != scope || supplied.actorId != actor || supplied.deviceId != cohostDeviceId ||
            supplied.principalKind != PrincipalKind.LOCAL_EMPLOYEE ||
            supplied.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) return false
        val originalAccess = issuances[supplied.sessionId]?.access ?: return false
        val identity = VerifiedLocalIdentity(actor, scope, cohostDeviceId, supplied.sessionId,
            supplied.generation, supplied.admissionRevision, AuthenticationPurpose.INTERACTIVE)
        historyShapeV2(supplied.accountRevision == account.state.revision &&
            supplied.credentialRevision == account.credentialRevision && supplied.policyRevision == policy.revision &&
            supplied.admissionRevision == admission.revision &&
            supplied.dependencies == RuntimeCommandAuthorizationDependenciesV1(
                digest(ProtectedLocalSecurityCodec.encodeAccount(account)), policyDigest, admissionDigest,
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
    /** THIS genuinely enrolled operator's event-cut authority. The native final observation
     * must match its original interactive access, not a target's token or current-policy read. */
    internal fun retainedSessionRevocationAuthority(value: RuntimeRetainedSessionRevocationEvidenceV1,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        historyShapeV2(enabled)
        value.validate()
        return accountAdministrationAuthority(value.authorization, value.requirement, value.protectedFinalDeclaration(), finalDecision)
    }

    /** Separate disable authority at this employee's actual enrollment/event cut; both
     * observed access samples must join the same unextended original interactive issuance. */
    internal fun accountDisableAuthority(value: RuntimeAccountDisableEvidenceV1,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        historyShapeV2(enabled)
        value.validate()
        return accountAdministrationAuthority(value.authorization, value.requirement, value.protectedFinalDeclaration(), finalDecision)
    }

    /** A genuine credential-free enrollment can be a reset TARGET, never its operator.
     * Authority still joins this original interactive issuance at both event/final cuts. */
    internal fun credentialResetAuthority(value: RuntimeCredentialResetEvidenceV1,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        historyShapeV2(enabled)
        value.validate()
        if (account.credential == null) return false
        return accountAdministrationAuthority(value.authorization, value.requirement, value.protectedFinalDeclaration(), finalDecision)
    }

    /** Prove this original operator/final cut, then predict ONLY the immutable audit.
     * No target read/state, account/session/credential change, issuance or dispatch occurs. */
    internal fun administrationCancellation(value: RuntimeAdministrationCancellationEvidenceV1,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        historyShapeV2(enabled)
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

    /** Same original enrolled-operator authority helper, at BOTH event and native-final
     * observations, before the coordinator advances any account or admission prediction. */
    internal fun deviceRevocationAuthority(value: RuntimeDeviceRevocationEvidenceV1,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        historyShapeV2(enabled)
        value.validate()
        if (account.credential == null) return false
        return accountAdministrationAuthority(value.authorization, value.requirement, value.protectedFinalDeclaration(), finalDecision)
    }

    /** Separate additional-device profile. Target history is proved by the admission
     * coordinator; this helper proves only this original operator's current event cut. */
    internal fun additionalDeviceRevocationAuthority(value: RuntimeDeviceRevocationEvidenceV2,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        historyShapeV2(enabled)
        value.validate()
        if (account.credential == null) return false
        return accountAdministrationAuthority(value.authorization, value.requirement, value.protectedFinalDeclaration(), finalDecision)
    }

    /** Whole canonical account BEFORE for a distinct target; no target session is seeded
     * or authenticated here. The coordinator checks every account before any effect. */
    internal fun additionalDeviceRevocationCensus(deviceId: DomainId): RuntimeDeviceRevocationAccountV1 {
        historyShapeV2(deviceId != cohostDeviceId && account.state.scope == scope && account.state.actorId == actor &&
            issuances.keys == account.state.sessions.map { it.sessionId }.toSet())
        return RuntimeDeviceRevocationAccountV1.capture(account, deviceId)
    }

    /** Exact account effect only after the coordinator's complete PRE-effect census and
     * original operator/native-final proof. Credentials and immutable issuances stay intact. */
    internal fun additionalDeviceRevocationAccount(value: RuntimeDeviceRevocationEvidenceV2,
        record: RuntimeDeviceRevocationAccountV1) {
        historyShapeV2(enabled && record.actorId == actor && record == additionalDeviceRevocationCensus(value.command.targetId))
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

    /** Negative-only projection after every complete target-session account effect,
     * including origins bound to later enrollment seeds. No positive lane is manufactured. */
    internal fun additionalDeviceRevocationAdmission(value: RuntimeDeviceRevocationEvidenceV2) {
        val before = additionalAdmissions[value.command.targetId] ?: return
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
        historyShapeV2(deviceId == cohostDeviceId && account.state.scope == scope && account.state.actorId == actor &&
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

    /** Project this actor's historical dependency without duplicating the shared admission
     * mutation/count. Current admission closure belongs to the chronological coordinator. */
    internal fun deviceRevocationAdmission(value: RuntimeDeviceRevocationEvidenceV1) {
        historyShapeV2(value.command.targetId == cohostDeviceId &&
            digest(ProtectedLocalSecurityCodec.encodeAdmission(admission)) == value.admissionBeforeDigest &&
            value.admissionBeforeDigest == admissionDigest)
        val next = deviceRevocationAdmissionAfterV1(admission)
        historyShapeV2(RuntimeDeviceRevocationImageV1.capture(ProtectedLocalRecordKey.Admission(scope, cohostDeviceId), next.revision,
            ProtectedLocalSecurityCodec.encodeAdmission(next)) == value.images.first())
        admission = next
    }

    private fun accountAdministrationAuthority(supplied: RuntimeCommandAuthorizationSnapshotV1,
        requirement: RuntimeCommandRequirementV1, declaration: NativeHistoryProtectedFinalDeclaration,
        finalDecision: NativeHistoryProtectedFinalDecision): Boolean {
        if (supplied.scope != scope || supplied.actorId != actor || supplied.deviceId != cohostDeviceId ||
            supplied.principalKind != PrincipalKind.LOCAL_EMPLOYEE ||
            supplied.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) return false
        val originalAccess = issuances[supplied.sessionId]?.access ?: return false
        val identity = VerifiedLocalIdentity(actor, scope, cohostDeviceId, supplied.sessionId,
            supplied.generation, supplied.admissionRevision, AuthenticationPurpose.INTERACTIVE)
        historyShapeV2(supplied.accountRevision == account.state.revision &&
            supplied.credentialRevision == account.credentialRevision && supplied.policyRevision == policy.revision &&
            supplied.admissionRevision == admission.revision &&
            supplied.dependencies == RuntimeCommandAuthorizationDependenciesV1(
                digest(ProtectedLocalSecurityCodec.encodeAccount(account)), policyDigest, admissionDigest,
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

    /** This target is seeded from its own actual enrollment, never from the operator's
     * founding identity. The coordinator proves authority before applying the pure reducer. */
    internal fun retainedSessionRevocationTarget(value: RuntimeRetainedSessionRevocationEvidenceV1): Boolean {
        historyShapeV2(enabled)
        value.validate()
        if (value.authorization.scope != scope || value.command.targetId != actor ||
            value.target.session.deviceId != cohostDeviceId) return false
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

    /** Own actual native-enrolled prefix, never a target reconstructed from current rows.
     * All retained interactive identities are known before the real disable reducer runs;
     * immutable access/refresh rows stay unchanged and cannot become renewed credentials. */
    internal fun accountDisableTarget(value: RuntimeAccountDisableEvidenceV1): Boolean {
        historyShapeV2(enabled)
        value.validate()
        if (value.authorization.scope != scope || value.command.targetId != actor ||
            value.authorization.deviceId != cohostDeviceId) return false
        val before = account.state
        val target = value.target
        historyShapeV2(account.credential != null && before.revision == value.command.expectedRevision &&
            before.generation == target.accountGeneration && account.credentialRevision == target.credentialRevision &&
            digest(ProtectedLocalSecurityCodec.encodeAccount(account)) == target.accountBeforeDigest &&
            issuances.keys == before.sessions.map { it.sessionId }.toSet() && before.sessions.all {
                it.scope == scope && it.actorId == actor && it.deviceId == cohostDeviceId &&
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

    /** Own complete actual native enrollment prefix only, after operator/final authority.
     * Credential-free enabled seeds are legitimate. AFTER supplies only the replacement
     * credential; the real revokeAll reducer must predict the entire account and audit. */
    internal fun credentialResetTarget(value: RuntimeCredentialResetEvidenceV1,
        actualAfter: ProtectedLocalAccount): Boolean {
        historyShapeV2(enabled)
        value.validate()
        if (value.authorization.scope != scope || value.command.targetId != actor ||
            value.authorization.deviceId != cohostDeviceId) return false
        val before = account.state
        val target = value.target
        historyShapeV2(before.enabled && before.revision == value.command.expectedRevision &&
            before.generation == target.accountGeneration && account.credentialRevision == target.credentialRevision &&
            digest(ProtectedLocalSecurityCodec.encodeAccount(account)) == target.accountBeforeDigest &&
            issuances.keys == before.sessions.map { it.sessionId }.toSet() && before.sessions.all {
                it.scope == scope && it.actorId == actor && it.deviceId == cohostDeviceId &&
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
    fun finish(currentAccount: PayloadDigest?, currentPolicy: PayloadDigest?, currentConfiguration: PayloadDigest?,
        currentThrottles: Map<ThrottleKey, PayloadDigest>) {
        historyShapeV2(enabled && earlierReplayTarget == null && nativeOriginalPrefixMode == null && issuedCommands == joinedCommands &&
            issuedCommands - retiredCommands == commands.size.toLong() && commands.values.all { it.joined } &&
            supervisorReviews.values.all { it.joined } &&
            purchasingPolicyRejections.values.all { it.joined } && orderReassignmentRejections.values.all { it.joined } &&
            openingSupplierObligationRejections.values.all { it.joined } && invoiceCaptureRejections.values.all { it.joined } &&
            capturedInvoiceMatchRejections.values.all { it.joined } &&
            branchActivityActivationRejections.values.all { it.joined } &&
            floorConfigurationRejections.values.all { it.joined } && configurationRejections.values.all { it.joined } &&
            returnRightsActivationRejections.values.all { it.joined } &&
            retailCustomerReceiptRejections.values.all { it.joined } && foodCheckRejections.values.all { it.joined } &&
            issuedFoodPaymentRejections == joinedFoodPaymentRejections &&
            issuedFoodPaymentRejections - retiredFoodPaymentRejections == foodPaymentRejections.size.toLong() &&
            foodPaymentRejections.values.all { it.joined } &&
            returnCaseQualificationRejections.values.all { it.joined } && returnStockDispositionRejections.values.all { it.joined } &&
            currentAccount == digest(ProtectedLocalSecurityCodec.encodeAccount(account)) &&
            currentPolicy == policyDigest && digest(LocalEffectivePolicyCodec.encodePolicy(policy)) == policyDigest &&
            currentConfiguration == configurationDigest &&
            issuances.keys == account.state.sessions.map { it.sessionId }.toSet() &&
            currentThrottles.keys == throttles.keys)
        for ((key, value) in throttles) historyShapeV2(currentThrottles[key] == digest(ProtectedLocalSecurityCodec.encodeThrottle(value)))
    }
    fun clear() { enabled = false; commands.values.forEach { it.clear() }; commands.clear()
        supervisorReviews.values.forEach { it.clear() }; supervisorReviews.clear()
        purchasingPolicyRejections.values.forEach { it.clear() }; purchasingPolicyRejections.clear()
        orderReassignmentRejections.values.forEach { it.clear() }; orderReassignmentRejections.clear()
        openingSupplierObligationRejections.values.forEach { it.clear() }; openingSupplierObligationRejections.clear()
        invoiceCaptureRejections.values.forEach { it.clear() }; invoiceCaptureRejections.clear()
        capturedInvoiceMatchRejections.values.forEach { it.clear() }; capturedInvoiceMatchRejections.clear()
        branchActivityActivationRejections.values.forEach { it.clear() }; branchActivityActivationRejections.clear()
        floorConfigurationRejections.values.forEach { it.clear() }; floorConfigurationRejections.clear()
        configurationRejections.values.forEach { it.clear() }; configurationRejections.clear()
        returnRightsActivationRejections.values.forEach { it.clear() }; returnRightsActivationRejections.clear()
        retailCustomerReceiptRejections.values.forEach { it.clear() }; retailCustomerReceiptRejections.clear()
        foodCheckRejections.values.forEach { it.clear() }; foodCheckRejections.clear()
        foodPaymentRejections.values.forEach { it.clear() }; foodPaymentRejections.clear()
        returnCaseQualificationRejections.values.forEach { it.clear() }; returnCaseQualificationRejections.clear()
        returnStockDispositionRejections.values.forEach { it.clear() }; returnStockDispositionRejections.clear(); issuances.clear(); throttles.clear()
        additionalAdmissions.clear(); enrollmentDeviceAdmissionOrigins.clear(); nativeOriginalPrefixMode = null }

    companion object {
        /** Construction is confined to the actual seed-prepass join, after every retained
         * definition/seed/image has matched. Current census hashes are closure targets only. */
        fun fromNativeSeeds(source: NativeEmployeeEnrollmentHistorySource, account: ProtectedLocalAccount,
            policy: ActivatedLocalPolicy, configuration: LocalAuthConfiguration,
            throttles: Map<ThrottleKey, PersistentThrottleState>,
            earlierReplayTarget: RuntimeEarlierOrdinaryCommandReplayTarget? = null): RuntimeEmployeeAuthenticationReplay =
            RuntimeEmployeeAuthenticationReplay(source, account, policy, configuration, throttles, earlierReplayTarget)

        /** Immutable native-origin/image selection for admission BEFORE enrollment. This
         * grants no replay ownership or permission without the separate chronological bind. */
        internal fun matchesEnrollmentDeviceAdmissionOrigin(session: SelfOwnedNativeHistoryReadSession,
            enrollment: NativeEmployeeEnrollmentHistorySource, origin: NativeDeviceAdmissionHistorySource,
            commitment: PayloadDigest): Boolean {
            val coordinates = session.original.record.allocation.coordinates
            val scope = coordinates.scope
            val actor = enrollment.requested.request.targetId
            val device = origin.requested.request.targetId
            val retained = enrollment.requested.admissions.singleOrNull { it.deviceId == device } ?: return false
            if (!session.hasEmployeeSeedTraversal || !session.hasDeviceAdmissionHistoryTraversal ||
                !session.hasChronologicalAccountImageTraversal || !session.employeeEnrollments.any { it === enrollment } ||
                !session.deviceAdmissions.any { it === origin } || actor == coordinates.firstActorId ||
                device == coordinates.deviceId || enrollment.requested.request.scope != scope ||
                origin.requested.request.scope != scope || enrollment.originalCohostDeviceId != coordinates.deviceId ||
                origin.originalCohostDeviceId != coordinates.deviceId ||
                origin.child.historySequence >= enrollment.child.historySequence ||
                origin.child.commandEpoch != enrollment.child.commandEpoch ||
                origin.child.installationGenesisDigest != enrollment.child.installationGenesisDigest ||
                origin.evidence.accountDigests.any { it.first == actor } || retained.scope != scope ||
                !retained.admitted || retained.revision != Revision(0) ||
                enrollment.evidence.admissionDigests.singleOrNull { it.first == device }?.second != commitment ||
                origin.evidence.images.first().plaintextDigest != commitment) return false
            val key = ThrottleKey(scope, actor, ThrottleDimension.Device(device))
            val seed = enrollment.evidence.images.singleOrNull { it.table == ProtectedLocalTable.THROTTLE && it.recordId == actor &&
                it.dimension == ProtectedLocalRecordKey.Throttle(key).dimensionKey } ?: return false
            val native = origin.requested.copyCanonicalServiceAdmission()
            val requested = ProtectedLocalSecurityCodec.encodeAdmission(origin.requested.admission)
            val retainedBytes = ProtectedLocalSecurityCodec.encodeAdmission(retained)
            return try {
                requested.contentEquals(native) && retainedBytes.contentEquals(native) && sha256(native) == commitment &&
                    seed.plaintextDigest == digest(ProtectedLocalSecurityCodec.encodeThrottle(
                        PersistentThrottleState(key, Revision(0), 0, enrollment.evidence.eventAt, 0, null)))
            } finally { native.fill(0); requested.fill(0); retainedBytes.fill(0) }
        }
        private fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
        private fun image(key: ProtectedLocalRecordKey, revision: Revision, bytes: ByteArray): RuntimeAuthenticationImageV1 =
            RuntimeAuthenticationImageV1.capture(key, revision, bytes)
        private fun malformed(): Nothing = throw RuntimeHistoryFormatRejectedV2(RuntimeHistoryFormatFailureV2.MALFORMED)
    }
}
