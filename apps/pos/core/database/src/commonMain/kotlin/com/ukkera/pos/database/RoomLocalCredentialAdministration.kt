package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore

/** No generic credential/grant upsert. First provisioning requires a separately qualified ceremony. */
internal class RoomLocalCredentialAdministration(
    private val scope: BranchScope,
    private val store: RoomLocalSecurityStore,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val peers: RoomLocalAccessPeerSource,
    private val principals: TrustedPrincipalFactory,
    private val passwords: LocalPasswordHasher,
    private val pepper: ProtectedPepperReference,
    private val entropy: LocalAuthenticationEntropy,
    private val provisioning: LocalProvisioningAuthority,
    maximumConcurrentAuthentication: Int,
) : LocalCredentialAdministration {
    private val slots = Semaphore(maximumConcurrentAuthentication)
    init { check(source.belongsTo(store)) }

    override suspend fun provisionAccount(requestId: DomainId, definition: LocalAccountProvisioning,
        password: CharArray?): LocalCredentialAdministrationResult = provisionAccountOwned(requestId, definition, password, null)

    /** Only the original service's separately registered endpoint work can call this path. */
    internal suspend fun provisionOriginalEmployee(dispatch: NativeLocalEmployeeEnrollmentDispatch,
        definition: LocalAccountProvisioning, password: CharArray?): LocalCredentialAdministrationResult =
        provisionAccountOwned(dispatch.request.requestId, definition, password, dispatch)

    private suspend fun provisionAccountOwned(requestId: DomainId, definition: LocalAccountProvisioning,
        password: CharArray?, nativeDispatch: NativeLocalEmployeeEnrollmentDispatch?): LocalCredentialAdministrationResult = bounded {
        if (definition.policy.scope != scope || (password != null && password.size !in 4..1024)) return@bounded denied()
        val owned = password?.copyOf()
        try {
            val configuration = LocalAuthConfiguration(scope, definition.actorId, Revision(0), definition.login, definition.tokenPolicy)
            val requested = accountProvisioningRequest(requestId, definition, owned != null)
            val permit = (if (nativeDispatch != null) nativeDispatch.authorizeOriginal(requested)
                else provisioning.authorize(requested)) ?: return@bounded denied()
            if ((nativeDispatch != null) != (permit.nativeEmployee != null) || permit.nativePolicy != null ||
                permit.nativeDeviceAdmission != null) return@bounded denied()
            if (permit.request != requested || !permit.isCurrent()) return@bounded denied()
            val credential = if (owned != null) {
                when (val result = passwords.create(owned, CredentialBinding(scope, definition.actorId, Revision(0)), pepper)) {
                    is PasswordHashingResult.Created -> result.record
                    is PasswordHashingResult.Unavailable -> return@bounded credentialFailure(result.reason)
                }
            } else null
            writer {
                var finalization: RoomLocalProvisioningFrame? = null
                val applied = store.transaction { transaction ->
                    if (!permit.isCurrent() || transaction.readAuthEvent(scope, requestId) != null ||
                        transaction.readProtectedAccount(scope, definition.actorId) != null ||
                        transaction.resolveLogin(scope, definition.login) != null) return@transaction false
                    val admissions = transaction.readAdmissions(scope).sortedBy { it.deviceId.value }
                    val admitted = admissions.filter { it.admitted }.map { it.deviceId }.toSet()
                    if (admitted != definition.admittedDeviceIds) return@transaction false
                    var count = 0
                    transaction.forEachAccount(scope) { count++ }
                    if (count >= 4096) return@transaction false
                    val nativeFrame = permit.nativeEmployee?.let { transaction.bindOriginalEmployeeFrame(it) }
                    val now = observe().moment
                    if (!configuration.tokens.canStartAt(now)) return@transaction false
                    val seed = RuntimeEmployeeCreationSeed.companyHead(requested, definition, credential, now, admissions)
                    val account = seed.account
                    val event = seed.event
                    val frame = RoomLocalProvisioningFrame.provisionAccount(requested, permit, source, currentCoroutineContext(),
                        definition, account, configuration, admissions, event, nativeFrame)
                    finalization = frame
                    transaction.attachProvisioningFrame(frame)
                    transaction.insertAccount(account)
                    transaction.insertPolicy(seed.policy)
                    transaction.insertAuthConfiguration(seed.configuration)
                    // The shared initial reduction includes retained revoked-device buckets too;
                    // subsequent re-admission cannot invent/reset an existing account's bucket.
                    for (throttle in seed.throttles) transaction.insertThrottle(throttle)
                    transaction.insertAuthEvent(event)
                    true
                }
                if (applied && finalization?.finishAfterCommit(currentCoroutineContext()) != true) protectedLocalUnavailable()
                if (applied) LocalCredentialAdministrationResult.Applied(requestId) else denied()
            }
        } finally { owned?.fill('\u0000') }
    }

    override suspend fun admitDevice(requestId: DomainId, admission: LocalDeviceAdmission): LocalCredentialAdministrationResult =
        admitDeviceOwned(requestId, admission, null)

    /** Dedicated original-dispatch lane. Generic ceremony output cannot enter it, and the
     * actual native permit cannot be borrowed by the ordinary request-ID admission method. */
    internal suspend fun admitOriginalDevice(dispatch: NativeLocalDeviceAdmissionDispatch,
        admission: LocalDeviceAdmission): LocalCredentialAdministrationResult =
        admitDeviceOwned(dispatch.requested.request.requestId, admission, dispatch)

    private suspend fun admitDeviceOwned(requestId: DomainId, admission: LocalDeviceAdmission,
        nativeDispatch: NativeLocalDeviceAdmissionDispatch?): LocalCredentialAdministrationResult = bounded {
        if (admission.scope != scope || !admission.admitted) return@bounded denied()
        val request = deviceAdmissionRequest(requestId, admission)
        val permit = (if (nativeDispatch == null) provisioning.authorize(request) else nativeDispatch.authorizeOriginal(request))
            ?: return@bounded denied()
        if ((nativeDispatch != null) != (permit.nativeDeviceAdmission != null) || permit.nativeEmployee != null ||
            permit.nativePolicy != null || permit.request != request || !permit.isCurrent()) return@bounded denied()
        writer {
            var finalization: RoomLocalProvisioningFrame? = null
            val applied = store.transaction { transaction ->
                if (!permit.isCurrent() || transaction.readAuthEvent(scope, requestId) != null) return@transaction false
                val retained = transaction.readAdmissions(scope).sortedBy { it.deviceId.value }
                val previous = retained.singleOrNull { it.deviceId == admission.deviceId }
                if (previous == null) {
                    // Revoked admissions are retained; never exceed the total-row technical bound.
                    if (admission.revision != Revision(0) || retained.size >= 256) return@transaction false
                } else if (previous.admitted || previous.revision.value == Long.MAX_VALUE ||
                    admission.revision.value != previous.revision.value + 1) return@transaction false
                if (nativeDispatch != null) {
                    val requested = nativeDispatch.requested
                    if (previous != null || requested.sdkIdentity == null ||
                        requested.request != request) return@transaction false
                    val service = ProtectedLocalSecurityCodec.encodeAdmission(admission)
                    val approvedService = requested.copyCanonicalServiceAdmission()
                    val approvedTargetService = ProtectedLocalSecurityCodec.encodeAdmission(requested.admission)
                    try {
                        if (!service.contentEquals(approvedService) || !service.contentEquals(approvedTargetService)) return@transaction false
                        val captured = requested.masterSdkOriginal?.let { master ->
                            LocalDevicePairingDefinition.captureMasterBound(requestId, requested.target, service,
                                retained, checkNotNull(requested.sdkIdentity), master)
                        } ?: LocalDevicePairingDefinition.captureSdkBound(requestId, requested.target, service,
                            retained, checkNotNull(requested.sdkIdentity))
                        val actualDefinition = captured.copyWireBytes(); val approvedDefinition = requested.copyWireBytes()
                        try { if (!actualDefinition.contentEquals(approvedDefinition)) return@transaction false }
                        finally { actualDefinition.fill(0); approvedDefinition.fill(0) }
                    } finally { service.fill(0); approvedService.fill(0); approvedTargetService.fill(0) }
                    val references = setOf(requestId, admission.deviceId, requested.target.keyId, nativeDispatch.originalActionId) +
                        checkNotNull(requested.sdkIdentity).let { setOf(it.originalAcquisitionId, it.originalPreparationId) }
                    for (id in references) if (transaction.readAuthEvent(scope, id) != null) return@transaction false
                    if (retained.any { it.deviceId in references || it.publicKeyIdentifier == admission.publicKeyIdentifier ||
                            it.publicFingerprint == admission.publicFingerprint }) return@transaction false
                    var accounts = 0
                    transaction.forEachAccount(scope) { account ->
                        if (account.state.actorId in references || transaction.readThrottle(
                                ThrottleKey(scope, account.state.actorId, ThrottleDimension.Device(admission.deviceId))) != null)
                            transaction.refuse()
                        accounts++
                    }
                    if (accounts == 0) return@transaction false
                    transaction.ensureAuthorizationHealthy(); transaction.requireAdministrationWriteCount(0)
                }
                val nativeFrame = permit.nativeDeviceAdmission?.let { transaction.bindOriginalDeviceAdmissionFrame(it) }
                val now = observe().moment
                val event = LocalAuthEvent(scope, requestId, LocalAuthEventKind.DEVICE_ADMITTED, admission.deviceId,
                    null, null, null, now, request.contentDigest)
                val frame = RoomLocalProvisioningFrame.admitDevice(request, permit, source, currentCoroutineContext(), admission, retained, event, nativeFrame)
                finalization = frame
                transaction.attachProvisioningFrame(frame)
                if (previous == null) transaction.insertAdmission(admission)
                else if (!transaction.readmitAdmission(previous.revision, admission)) transaction.refuse()
                transaction.forEachAccount(scope) { account ->
                    val key = ThrottleKey(scope, account.state.actorId, ThrottleDimension.Device(admission.deviceId))
                    if (previous == null) {
                        // Only an actual new admission creates existing-account buckets.
                        transaction.insertThrottle(initialThrottle(account.state.actorId, key.dimension, now))
                    } else {
                        // Re-pairing cannot erase delays, failures, in-flight attempts or history.
                        if (transaction.readThrottle(key) == null ||
                            transaction.readThrottle(ThrottleKey(scope, account.state.actorId, ThrottleDimension.Account)) == null) transaction.refuse()
                        val oldSessions = account.state.sessions.filter {
                            it.deviceId == admission.deviceId && it.status == SessionStatus.ACTIVE
                        }.map { it.sessionId }.toSet()
                        if (oldSessions.isNotEmpty()) {
                            val next = LocalSessions.revokeSelected(account.state, oldSessions) as? SessionTransition.Applied ?: transaction.refuse()
                            if (!transaction.replaceAccount(account.state.revision, next.state)) transaction.refuse()
                        }
                    }
                }
                transaction.insertAuthEvent(event)
                true
            }
            if (applied && finalization?.finishAfterCommit(currentCoroutineContext()) != true) protectedLocalUnavailable()
            if (applied) LocalCredentialAdministrationResult.Applied(requestId) else denied()
        }
    }

    override suspend fun revokeDevice(operator: LocalGatewayCredentials, requestId: DomainId, deviceId: DomainId,
        expectedRevision: Revision): LocalCredentialAdministrationResult = bounded {
        // Authentication's full protected-reader loan returns before this separate writer.
        val principal = principals.authenticateLocal(operator) ?: return@bounded denied()
        val peer = capturePeer(operator.connectionId) ?: return@bounded denied()
        val digest = localAdministrationRequestDigest(scope, principal.actorId,
            LocalAdministrationCommand(requestId, LocalAdministrationAction.REVOKE_DEVICE, deviceId, expectedRevision))
        val requirement = AuthorizationRequirement(Permission(SecurityAction.DEVICE_ADMINISTRATION, PermissionVerb.ADMINISTER), scope,
            ActionBinding(deviceId, expectedRevision, digest, OperationId(requestId.value), "local-device-revocation"))
        writer {
            var finalization: RoomLocalDeviceRevocationFrame? = null
            val applied = store.transaction { transaction ->
                val permitted = authorizeRequirement(transaction, principal, peer, requirement) ?: return@transaction false
                val prior = transaction.readAuthEvent(scope, requestId)
                if (prior != null) {
                    // Historical receipt recovery precedes live target/revision checks. A stale
                    // retry after re-pairing must not revoke a newer admission or its sessions.
                    if (prior.kind != LocalAuthEventKind.DEVICE_REVOKED || prior.targetId != deviceId ||
                        prior.operatorId != principal.actorId || prior.contentDigest != digest ||
                        prior.sessionId == null || prior.accountRevision != null) return@transaction false
                    val frame = RoomLocalDeviceRevocationFrame(principal, permitted.facts, permitted.authorization,
                        source, peer, currentCoroutineContext(), null, prior)
                    transaction.attachDeviceRevocationFrame(frame)
                    finalization = frame
                    return@transaction true
                }
                val target = transaction.readAdmission(scope, deviceId) ?: return@transaction false
                if (!target.admitted || target.revision != expectedRevision || expectedRevision.value == Long.MAX_VALUE) return@transaction false
                val observed = observe()
                val at = observed.moment
                val event = LocalAuthEvent(scope, requestId, LocalAuthEventKind.DEVICE_REVOKED, deviceId,
                    principal.actorId, principal.sessionId, null, at, digest)
                // Attach before any effects. The finalizer predicts only the specifically
                // authorized revocation, preserving the original operator's pre-effect proof.
                val frame = RoomLocalDeviceRevocationFrame(principal, permitted.facts, permitted.authorization,
                    source, peer, currentCoroutineContext(), target, event,
                    LocalAdministrationCommand(requestId, LocalAdministrationAction.REVOKE_DEVICE, deviceId, expectedRevision), observed)
                transaction.attachDeviceRevocationFrame(frame)
                finalization = frame
                if (!transaction.revokeAdmission(scope, deviceId)) transaction.refuse()
                transaction.forEachAccount(scope) { account ->
                    val active = account.state.sessions.filter {
                        it.deviceId == deviceId && it.status == SessionStatus.ACTIVE
                    }.map { it.sessionId }.toSet()
                    if (active.isNotEmpty()) {
                        // Keep original identities/issuances, and retain the reducer's permanent
                        // whole-account lockdown if counters have reached their terminal boundary.
                        val next = LocalSessions.revokeSelected(account.state, active) as? SessionTransition.Applied ?: transaction.refuse()
                        if (!transaction.replaceAccount(account.state.revision, next.state)) transaction.refuse()
                    }
                }
                transaction.insertAuthEvent(event)
                true
            }
            if (applied && finalization?.finishAfterCommit(currentCoroutineContext()) != true) protectedLocalUnavailable()
            if (applied) LocalCredentialAdministrationResult.Applied(requestId) else denied()
        }
    }

    override suspend fun resetCredential(operator: LocalGatewayCredentials, actorId: DomainId, expectedRevision: Revision,
        newPassword: CharArray): LocalCredentialAdministrationResult = bounded {
        if (newPassword.size !in 4..1024) return@bounded denied()
        val owned = newPassword.copyOf()
        try {
            // Full factory call returns BEFORE the separate writer is acquired (F35).
            val principal = principals.authenticateLocal(operator) ?: return@bounded denied()
            val peer = capturePeer(operator.connectionId) ?: return@bounded denied()
            val eventId = freshAuthenticationId(entropy)
            val preflightDigest = actionDigest("credential-reset", actorId, null, expectedRevision)
            val credentialRevision = writer {
                store.transaction { transaction ->
                    if (authorize(transaction, principal, peer, actorId, expectedRevision, preflightDigest) == null) return@transaction null
                    val target = transaction.readProtectedAccount(scope, actorId) ?: return@transaction null
                    if (target.state.revision != expectedRevision || !target.state.enabled || target.credentialRevision.value == Long.MAX_VALUE) return@transaction null
                    target.credentialRevision
                }
            } ?: return@bounded denied()
            val credential = when (val hashed = passwords.create(owned, CredentialBinding(scope, actorId, credentialRevision.next()), pepper)) {
                is PasswordHashingResult.Created -> hashed.record
                is PasswordHashingResult.Unavailable -> return@bounded credentialFailure(hashed.reason)
            }
            val digest = authenticationDigest(preflightDigest.value.encodeToByteArray(), credential.copySalt(), credential.copyVerifier())
            writer {
                var finalization: RoomLocalAccountAdministrationFrame? = null
                val applied = store.transaction { transaction ->
                    val permitted = authorize(transaction, principal, peer, actorId, expectedRevision, digest) ?: return@transaction false
                    val before = transaction.readProtectedAccount(scope, actorId) ?: return@transaction false
                    if (before.state.revision != expectedRevision || before.credentialRevision != credentialRevision ||
                        credentialRevision.value == Long.MAX_VALUE || !before.state.enabled) return@transaction false
                    val next = (LocalSessions.revokeAll(before.state) as? SessionTransition.Applied)?.state ?: transaction.refuse()
                    if (!next.enabled) return@transaction false
                    val after = ProtectedLocalAccount(next, credentialRevision.next(), credential)
                    val event = LocalAuthEvent(scope, eventId, LocalAuthEventKind.CREDENTIAL_RESET, actorId,
                        principal.actorId, null, next.revision, observe().moment, digest)
                    val frame = RoomLocalAccountAdministrationFrame(principal, permitted.facts, permitted.authorization,
                        source, peer, currentCoroutineContext(), before, after, event)
                    finalization = frame
                    transaction.attachAccountAdministrationFrame(frame)
                    if (transaction.resetCredential(scope, actorId, expectedRevision, credentialRevision, credential) == null) transaction.refuse()
                    transaction.insertAuthEvent(event)
                    true
                }
                if (applied && finalization?.finishAfterCommit(currentCoroutineContext()) != true) protectedLocalUnavailable()
                if (applied) LocalCredentialAdministrationResult.Applied(eventId) else denied()
            }
        } finally { owned.fill('\u0000') }
    }

    override suspend fun lookupAccountProvisioning(requestId: DomainId, definition: LocalAccountProvisioning,
        credentialWasProvided: Boolean): LocalProvisioningStatus = lookupProvisioning(LocalAuthEventKind.ACCOUNT_PROVISIONED) {
        if (definition.policy.scope != scope) null
        else accountProvisioningRequest(requestId, definition, credentialWasProvided)
    }

    internal suspend fun lookupOriginalEmployee(authority: LocalProvisioningReceiptAuthority, requestId: DomainId,
        definition: LocalAccountProvisioning, credentialWasProvided: Boolean): LocalProvisioningStatus =
        lookupProvisioning(LocalAuthEventKind.ACCOUNT_PROVISIONED, authority) {
            if (definition.policy.scope != scope) null else accountProvisioningRequest(requestId, definition, credentialWasProvided)
        }

    override suspend fun lookupDeviceAdmission(requestId: DomainId, admission: LocalDeviceAdmission): LocalProvisioningStatus =
        lookupProvisioning(LocalAuthEventKind.DEVICE_ADMITTED) {
            if (admission.scope != scope || !admission.admitted) null
            else deviceAdmissionRequest(requestId, admission)
    }

    /** Terminal actual-original lookup only. An absence row is accepted only through the
     * owner's already settled no-effect receipt; neither this read nor SQL existence settles. */
    internal suspend fun lookupOriginalDeviceAdmission(native: LocalCompanyDevicePairings,
        originalActionId: DomainId): LocalProvisioningStatus {
        currentCoroutineContext().ensureActive()
        if (!slots.tryAcquire()) return LocalProvisioningStatus.Busy
        return try {
            val receipt = native.lookupOriginalDeviceAdmission(originalActionId) ?: return LocalProvisioningStatus.Unavailable
            val requested = receipt.requested
            if (requested.request.scope != scope || requested.sdkIdentity == null || requested.admission.revision != Revision(0) ||
                !requested.admission.admitted || receipt.request != deviceAdmissionRequest(requested.request.requestId, requested.admission) ||
                !receipt.isCurrent()) return LocalProvisioningStatus.Unavailable
            writer {
                var finalization: RoomLocalProvisioningFrame? = null
                val result = store.transaction { transaction ->
                    val event = transaction.readAuthEvent(scope, requested.request.requestId)
                    val frame = RoomLocalProvisioningFrame.lookupOriginalDeviceAdmission(requested.request, receipt, source,
                        currentCoroutineContext(), event)
                    finalization = frame
                    transaction.attachProvisioningFrame(frame)
                    transaction.requireAdministrationWriteCount(0)
                    when {
                        event == null && receipt.permitsAbsence -> LocalProvisioningStatus.NotRecorded
                        event != null && !receipt.permitsAbsence && event.kind == LocalAuthEventKind.DEVICE_ADMITTED &&
                            event.targetId == requested.admission.deviceId && event.contentDigest == requested.request.contentDigest &&
                            event.operatorId == null && event.sessionId == null && event.accountRevision == null ->
                            LocalProvisioningStatus.Applied(event.eventId)
                        else -> LocalProvisioningStatus.Unavailable
                    }
                }
                if (finalization?.finishAfterCommit(currentCoroutineContext()) != true) protectedLocalUnavailable()
                result
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RoomLocalStorageUncertain) { currentCoroutineContext().ensureActive(); LocalProvisioningStatus.StorageUncertain }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); LocalProvisioningStatus.Unavailable }
        finally { slots.release() }
    }

    private suspend fun lookupProvisioning(kind: LocalAuthEventKind, nativeReceiptAuthority: LocalProvisioningReceiptAuthority? = null,
        requested: () -> LocalProvisioningRequest?): LocalProvisioningStatus {
        currentCoroutineContext().ensureActive()
        if (!slots.tryAcquire()) return LocalProvisioningStatus.Busy
        return try {
            // Even canonical encoding/digest allocation and provider access follow bounded admission.
            val request = requested() ?: return LocalProvisioningStatus.Denied
            val receipt = nativeReceiptAuthority?.lookupOriginal(request)
            if (nativeReceiptAuthority != null && (receipt == null || receipt.request != request || !receipt.isCurrent()))
                return LocalProvisioningStatus.Unavailable
            val permit = if (nativeReceiptAuthority == null) provisioning.authorize(request) else null
            if (nativeReceiptAuthority == null && (permit == null || permit.request != request || !permit.isCurrent()))
                return LocalProvisioningStatus.Denied
            if (permit?.nativeDeviceAdmission != null) return LocalProvisioningStatus.Denied
            writer {
                var finalization: RoomLocalProvisioningFrame? = null
                val result = store.transaction { transaction ->
                    val event = transaction.readAuthEvent(scope, request.requestId)
                    val frame = if (receipt != null) RoomLocalProvisioningFrame.lookupOriginal(request, receipt, source, currentCoroutineContext(), event)
                        else RoomLocalProvisioningFrame.lookup(request, checkNotNull(permit), source, currentCoroutineContext(), event)
                    finalization = frame
                    transaction.attachProvisioningFrame(frame)
                    when {
                        event == null -> LocalProvisioningStatus.NotRecorded
                        event.kind == kind && event.targetId == request.targetId && event.contentDigest == request.contentDigest ->
                            LocalProvisioningStatus.Applied(event.eventId)
                        else -> LocalProvisioningStatus.Denied
                    }
                }
                if (finalization?.finishAfterCommit(currentCoroutineContext()) != true) protectedLocalUnavailable()
                result
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RoomLocalStorageUncertain) { currentCoroutineContext().ensureActive(); LocalProvisioningStatus.StorageUncertain }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); LocalProvisioningStatus.Unavailable }
        finally { slots.release() }
    }

    private fun accountProvisioningRequest(requestId: DomainId, definition: LocalAccountProvisioning,
        credentialWasProvided: Boolean): LocalProvisioningRequest {
        val configuration = LocalAuthConfiguration(scope, definition.actorId, Revision(0), definition.login, definition.tokenPolicy)
        return localAccountProvisioningRequestV1(scope, requestId, definition.actorId, credentialWasProvided,
            LocalEffectivePolicyCodec.encodePolicy(definition.policy), LocalAuthenticationCodec.encodeConfiguration(configuration),
            definition.admittedDeviceIds)
    }

    private fun deviceAdmissionRequest(requestId: DomainId, admission: LocalDeviceAdmission): LocalProvisioningRequest =
        LocalProvisioningRequest(scope, requestId, LocalProvisioningAction.ADMIT_DEVICE, admission.deviceId,
            authenticationDigest("ukkera.admit-local-device/v1\u0000".encodeToByteArray(), ProtectedLocalSecurityCodec.encodeAdmission(admission)))

    override suspend fun activatePolicy(requestId: DomainId, nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult =
        activatePolicyOwned(requestId, nextPolicy, null)

    internal suspend fun activateOriginalEmployeePolicy(dispatch: NativeLocalEmployeePolicyActivationDispatch,
        nextPolicy: ActivatedLocalPolicy): LocalCredentialAdministrationResult =
        activatePolicyOwned(dispatch.request.requestId, nextPolicy, dispatch)

    private suspend fun activatePolicyOwned(requestId: DomainId, nextPolicy: ActivatedLocalPolicy,
        nativeDispatch: NativeLocalEmployeePolicyActivationDispatch?): LocalCredentialAdministrationResult = bounded {
        if (nextPolicy.scope != scope || nextPolicy.revision.value == 0L) return@bounded denied()
        val request = policyActivationRequest(requestId, nextPolicy)
        val permit = (if (nativeDispatch != null) nativeDispatch.authorizeOriginal(request)
            else provisioning.authorize(request)) ?: return@bounded denied()
        if ((nativeDispatch != null) != (permit.nativePolicy != null) || permit.nativeEmployee != null || permit.nativeDeviceAdmission != null ||
            permit.request != request || !permit.isCurrent()) return@bounded denied()
        writer {
            var finalization: RoomLocalProvisioningFrame? = null
            val applied = store.transaction { transaction ->
                if (!permit.isCurrent() || transaction.readAuthEvent(scope, requestId) != null) return@transaction false
                val account = transaction.readProtectedAccount(scope, nextPolicy.actorId) ?: return@transaction false
                val previous = transaction.readPolicy(scope, nextPolicy.actorId) ?: return@transaction false
                if (previous.revision.value == Long.MAX_VALUE || previous.revision.value + 1 != nextPolicy.revision.value) return@transaction false
                var state = account.state
                val revokeSessions = state.sessions.count { it.status == SessionStatus.ACTIVE } > nextPolicy.sessionPolicy.maximumConcurrentSessions
                if (revokeSessions) {
                    // A lowered session cap does not silently grandfather excess active sessions.
                    val revoked = LocalSessions.revokeAll(state) as? SessionTransition.Applied ?: transaction.refuse()
                    state = revoked.state
                }
                val nativeFrame = permit.nativePolicy?.let { transaction.bindOriginalEmployeePolicyFrame(it) }
                val event = LocalAuthEvent(scope, requestId, LocalAuthEventKind.POLICY_ACTIVATED,
                    nextPolicy.actorId, null, null, state.revision, observe().moment, request.contentDigest)
                val frame = RoomLocalProvisioningFrame.activatePolicy(request, permit, source, currentCoroutineContext(), account,
                    ProtectedLocalAccount(state, account.credentialRevision, account.credential), previous, nextPolicy, event, nativeFrame)
                finalization = frame
                transaction.attachProvisioningFrame(frame)
                if (!transaction.replacePolicy(previous.revision, nextPolicy)) transaction.refuse()
                if (revokeSessions && !transaction.replaceAccount(account.state.revision, state)) transaction.refuse()
                transaction.insertAuthEvent(event)
                true
            }
            if (applied && finalization?.finishAfterCommit(currentCoroutineContext()) != true) protectedLocalUnavailable()
            if (applied) LocalCredentialAdministrationResult.Applied(requestId) else denied()
        }
    }

    override suspend fun lookupPolicyActivation(requestId: DomainId, nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus =
        lookupProvisioning(LocalAuthEventKind.POLICY_ACTIVATED) {
            if (nextPolicy.scope != scope || nextPolicy.revision.value == 0L) null else policyActivationRequest(requestId, nextPolicy)
        }

    /** Original policy receipt authority is terminal/read-only and cannot supply an effect permit. */
    internal suspend fun lookupOriginalEmployeePolicy(authority: NativeLocalEmployeePolicyActivationAuthority,
        requestId: DomainId, nextPolicy: ActivatedLocalPolicy): LocalProvisioningStatus {
        currentCoroutineContext().ensureActive()
        if (!slots.tryAcquire()) return LocalProvisioningStatus.Busy
        return try {
            if (nextPolicy.scope != scope || nextPolicy.revision.value == 0L) return LocalProvisioningStatus.Denied
            val request = policyActivationRequest(requestId, nextPolicy)
            val receipt = authority.lookupOriginal(request) ?: return LocalProvisioningStatus.Unavailable
            if (receipt.request != request || !receipt.isCurrent()) return LocalProvisioningStatus.Unavailable
            writer {
                var finalization: RoomLocalProvisioningFrame? = null
                val result = store.transaction { transaction ->
                    val event = transaction.readAuthEvent(scope, request.requestId)
                    val frame = RoomLocalProvisioningFrame.lookupOriginalPolicy(request, receipt, source,
                        currentCoroutineContext(), event)
                    finalization = frame
                    transaction.attachProvisioningFrame(frame)
                    when {
                        event == null -> LocalProvisioningStatus.NotRecorded
                        event.kind == LocalAuthEventKind.POLICY_ACTIVATED && event.targetId == request.targetId &&
                            event.contentDigest == request.contentDigest -> LocalProvisioningStatus.Applied(event.eventId)
                        else -> LocalProvisioningStatus.Denied
                    }
                }
                if (finalization?.finishAfterCommit(currentCoroutineContext()) != true) protectedLocalUnavailable()
                result
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RoomLocalStorageUncertain) { currentCoroutineContext().ensureActive(); LocalProvisioningStatus.StorageUncertain }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); LocalProvisioningStatus.Unavailable }
        finally { slots.release() }
    }

    override suspend fun reactivateAccount(requestId: DomainId, target: LocalAccountReactivation): LocalCredentialAdministrationResult = bounded {
        if (target.scope != scope) return@bounded denied()
        val request = accountReactivationRequest(requestId, target)
        val permit = provisioning.authorize(request) ?: return@bounded denied()
        if (permit.request != request || !permit.isCurrent()) return@bounded denied()
        writer {
            var finalization: RoomLocalProvisioningFrame? = null
            val applied = store.transaction { transaction ->
                if (!permit.isCurrent() || transaction.readAuthEvent(scope, requestId) != null) return@transaction false
                val account = transaction.readProtectedAccount(scope, target.actorId) ?: return@transaction false
                val before = account.state
                val policy = transaction.readPolicy(scope, target.actorId) ?: return@transaction false
                val configuration = transaction.readAuthConfiguration(scope, target.actorId) ?: return@transaction false
                if (before.enabled || before.revision != target.expectedAccountRevision ||
                    account.credentialRevision != target.expectedCredentialRevision || policy.revision != target.expectedPolicyRevision ||
                    configuration.revision != target.expectedConfigurationRevision || before.revision.value >= Long.MAX_VALUE - 1 ||
                    before.generation.value >= Long.MAX_VALUE - 1 || before.sessions.any { it.status == SessionStatus.ACTIVE }) return@transaction false
                val state = LocalAccountSessionState(scope, target.actorId, true, before.revision.next(), before.generation.next(), before.sessions)
                val event = LocalAuthEvent(scope, requestId, LocalAuthEventKind.ACCOUNT_REACTIVATED,
                    target.actorId, null, null, state.revision, observe().moment, request.contentDigest)
                val frame = RoomLocalProvisioningFrame.reactivateAccount(request, permit, source, currentCoroutineContext(), target, account,
                    ProtectedLocalAccount(state, account.credentialRevision, account.credential), policy, configuration, event)
                finalization = frame
                transaction.attachProvisioningFrame(frame)
                if (transaction.reactivateAccount(target) == null) transaction.refuse()
                transaction.insertAuthEvent(event)
                true
            }
            if (applied && finalization?.finishAfterCommit(currentCoroutineContext()) != true) protectedLocalUnavailable()
            if (applied) LocalCredentialAdministrationResult.Applied(requestId) else denied()
        }
    }

    override suspend fun lookupAccountReactivation(requestId: DomainId, target: LocalAccountReactivation): LocalProvisioningStatus =
        lookupProvisioning(LocalAuthEventKind.ACCOUNT_REACTIVATED) {
            if (target.scope != scope) null else accountReactivationRequest(requestId, target)
        }

    private fun policyActivationRequest(requestId: DomainId, policy: ActivatedLocalPolicy): LocalProvisioningRequest =
        LocalProvisioningRequest(scope, requestId, LocalProvisioningAction.ACTIVATE_POLICY, policy.actorId,
            authenticationDigest("ukkera.activate-local-policy/v1\u0000".encodeToByteArray(), LocalEffectivePolicyCodec.encodePolicy(policy)))

    private fun accountReactivationRequest(requestId: DomainId, target: LocalAccountReactivation): LocalProvisioningRequest =
        LocalProvisioningRequest(scope, requestId, LocalProvisioningAction.REACTIVATE_ACCOUNT, target.actorId,
            sha256(("ukkera.reactivate-local-account/v1\u0000${scope.storageKey()}\u0000${target.actorId.value}\u0000" +
                "${target.expectedAccountRevision.value}\u0000${target.expectedCredentialRevision.value}\u0000" +
                "${target.expectedPolicyRevision.value}\u0000${target.expectedConfigurationRevision.value}").encodeToByteArray()))

    override suspend fun revokeSession(operator: LocalGatewayCredentials, actorId: DomainId, sessionId: DomainId,
        expectedRevision: Revision): LocalCredentialAdministrationResult = changeAccount(operator, actorId, sessionId, expectedRevision, disable = false)

    override suspend fun disableAccount(operator: LocalGatewayCredentials, actorId: DomainId,
        expectedRevision: Revision): LocalCredentialAdministrationResult = changeAccount(operator, actorId, null, expectedRevision, disable = true)

    private suspend fun changeAccount(operator: LocalGatewayCredentials, actorId: DomainId, sessionId: DomainId?,
        expectedRevision: Revision, disable: Boolean): LocalCredentialAdministrationResult = bounded {
        val principal = principals.authenticateLocal(operator) ?: return@bounded denied()
        val peer = capturePeer(operator.connectionId) ?: return@bounded denied()
        val eventId = freshAuthenticationId(entropy)
        val digest = actionDigest(if (disable) "disable-account" else "revoke-session", actorId, sessionId, expectedRevision)
        writer {
            var finalization: RoomLocalAccountAdministrationFrame? = null
            val applied = store.transaction { transaction ->
                val permitted = authorize(transaction, principal, peer, actorId, expectedRevision, digest) ?: return@transaction false
                val account = transaction.readProtectedAccount(scope, actorId) ?: return@transaction false
                if (account.state.revision != expectedRevision) return@transaction false
                val archive = if (!disable && account.state.sessions.none { it.sessionId == sessionId }) {
                    transaction.readSessionArchive(scope, checkNotNull(sessionId))?.takeIf { it.session.actorId == actorId }
                        ?: return@transaction false
                } else null
                val transition = (if (disable) LocalSessions.disable(account.state) else LocalSessions.revoke(account.state, checkNotNull(sessionId)))
                    as? SessionTransition.Applied ?: transaction.refuse()
                val event = LocalAuthEvent(scope, eventId, if (disable) LocalAuthEventKind.ACCOUNT_DISABLED else LocalAuthEventKind.SESSION_REVOKED,
                    actorId, principal.actorId, sessionId, transition.state.revision, observe().moment, digest)
                val frame = RoomLocalAccountAdministrationFrame(principal, permitted.facts, permitted.authorization, source, peer,
                    currentCoroutineContext(), account, ProtectedLocalAccount(transition.state, account.credentialRevision, account.credential), event, archive)
                finalization = frame
                transaction.attachAccountAdministrationFrame(frame)
                if (!transaction.replaceAccount(expectedRevision, transition.state)) transaction.refuse()
                transaction.insertAuthEvent(event)
                true
            }
            if (applied && finalization?.finishAfterCommit(currentCoroutineContext()) != true) protectedLocalUnavailable()
            if (applied) LocalCredentialAdministrationResult.Applied(eventId) else denied()
        }
    }

    private suspend fun authorize(transaction: RoomLocalSecurityTransaction, principal: LocalEmployeePrincipal,
        peer: RoomLocalAccessPeerBinding, target: DomainId, revision: Revision, digest: PayloadDigest): AdministrationApproval? =
        authorizeRequirement(transaction, principal, peer,
            AuthorizationRequirement(Permission(SecurityAction.LOCAL_ACCOUNT_ADMINISTRATION, PermissionVerb.ADMINISTER), scope,
                ActionBinding(target, revision, digest, reasonCode = "local-credential-administration")))

    private suspend fun authorizeRequirement(transaction: RoomLocalSecurityTransaction, principal: LocalEmployeePrincipal,
        peer: RoomLocalAccessPeerBinding, requirement: AuthorizationRequirement): AdministrationApproval? {
        if (principal.scope != scope || peer.deviceId != principal.deviceId || !peer.isCurrent()) return null
        val facts = transaction.readAuthorizationFacts(principal) ?: return null
        if (!peer.matchesAdmission(facts.admission)) return null
        val observed = observe()
        if (!LocalAccessEligibility.matchesCurrentFacts(principal, facts.issuance, facts.account,
                facts.credentialRevision, facts.credentialPresent, facts.admission, observed.issuerContextId, observed.moment)) return null
        val snapshot = AuthorizationSnapshot(principal.actorId, principal.kind, principal.scope, principal.sessionId,
            principal.sessionGeneration, principal.deviceId, facts.admission.revision, facts.policy.revision, facts.account.enabled,
            true, facts.admission.admitted, facts.issuance.authenticationPurpose, facts.policy.grants)
        val allowed = AuthorizationEvaluator.evaluate(principal, snapshot, requirement) as? AuthorizationDecision.Allowed ?: return null
        return AdministrationApproval(principal, facts, allowed.authorization)
    }
    private fun initialThrottle(actor: DomainId, dimension: ThrottleDimension, moment: MonotonicMoment): PersistentThrottleState =
        PersistentThrottleState(ThrottleKey(scope, actor, dimension), Revision(0), 0, moment, 0, null)
    private fun actionDigest(action: String, actor: DomainId, session: DomainId?, revision: Revision): PayloadDigest =
        sha256("ukkera.local-credential-action/v1\u0000$action\u0000${actor.value}\u0000${session?.value.orEmpty()}\u0000${revision.value}".encodeToByteArray())
    private suspend fun capturePeer(id: DomainId): RoomLocalAccessPeerBinding? = peers.capture(id)?.takeIf {
        it.scope == scope && it.connectionId == id && it.isCurrent()
    }
    private suspend fun observe(): RoomLocalAuthorizationObservation = source.observeCurrent(currentCoroutineContext())
    private suspend fun <T> writer(block: suspend () -> T): T = localSecurityLane(store.database) { block() }
    private suspend fun bounded(block: suspend () -> LocalCredentialAdministrationResult): LocalCredentialAdministrationResult {
        currentCoroutineContext().ensureActive()
        if (!slots.tryAcquire()) return LocalCredentialAdministrationResult.Busy
        return try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RoomLocalStorageUncertain) { currentCoroutineContext().ensureActive(); LocalCredentialAdministrationResult.StorageUncertain }
        catch (_: RoomLocalAccessVerificationStorageUncertain) { currentCoroutineContext().ensureActive(); LocalCredentialAdministrationResult.StorageUncertain }
        catch (_: RoomLocalAccessVerificationBusy) { LocalCredentialAdministrationResult.Busy }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); LocalCredentialAdministrationResult.Unavailable }
        finally { slots.release() }
    }
}

private class AdministrationApproval(val principal: LocalEmployeePrincipal, val facts: RoomLocalAuthorizationFacts,
    val authorization: AuthorizedAction)
private fun denied(): LocalCredentialAdministrationResult = LocalCredentialAdministrationResult.Denied
private fun credentialFailure(reason: PasswordFailure): LocalCredentialAdministrationResult = when (reason) {
    PasswordFailure.HASHER_BUSY -> LocalCredentialAdministrationResult.Busy
    PasswordFailure.INVALID_INPUT -> LocalCredentialAdministrationResult.Denied
    else -> LocalCredentialAdministrationResult.Unavailable
}
/** Owned canonical buffers, not raw passwords, are hashed and wiped; no transcript/string logging. */
private fun authenticationDigest(vararg fields: ByteArray): PayloadDigest {
    val count = fields.sumOf { it.size.toLong() + 4 }
    require(count in 1..(2 * 1024 * 1024))
    val bytes = ByteArray(count.toInt())
    return try {
        var offset = 0
        for (field in fields) {
            bytes[offset++] = (field.size ushr 24).toByte(); bytes[offset++] = (field.size ushr 16).toByte()
            bytes[offset++] = (field.size ushr 8).toByte(); bytes[offset++] = field.size.toByte()
            field.copyInto(bytes, offset); offset += field.size
        }
        sha256(bytes)
    } finally { bytes.fill(0); fields.forEach { it.fill(0) } }
}
