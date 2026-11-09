package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The exact approved definition and one CLOSED provisioning effect family, owned by one writer.
 * All protected rereads/health work precede the store's final synchronous permit/time sample.
 * This is not an authority factory or an extensible callback/dependency registry. Device pairing
 * and retirement of replaced physical peers remain the trusted ceremony adapter's responsibility.
 */
internal class RoomLocalProvisioningFrame private constructor(
    private val request: LocalProvisioningRequest,
    private val permit: Permit,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val context: CoroutineContext,
    effect: Effect,
    event: LocalAuthEvent?,
) {
    private enum class Phase { NEW, INITIALIZING, BODY, PREPARING, PREPARED, FINISHED, CLOSED }
    private var phase = Phase.NEW
    private var failure: Exception? = null
    private var owner: RoomLocalSecurityTransaction? = null
    private var finished = false
    private var ownedEffect: Effect? = effect
    private var initialObservation: RoomLocalAuthorizationObservation? = null
    private val eventDigest = event?.let { provisioningDigest(LocalAuthenticationCodec.encodeEvent(it)) }
    private val tokens = (effect as? Effect.Provision)?.tokens
    private val employeeCapture = (effect as? Effect.Provision)?.employeeCapture
    private val policyCapture = (effect as? Effect.Policy)?.policyCapture
    private val admissionCapture = (effect as? Effect.Admission)?.admissionCapture

    init {
        check(permit.request == request)
        if (permit is Permit.Receipt) check(effect is Effect.Lookup &&
            permit.value.expectedEventDigest == eventDigest && permit.value.permitsAbsence == (event == null))
        if (permit is Permit.PolicyReceipt) check(effect is Effect.Lookup &&
            request.action == LocalProvisioningAction.ACTIVATE_POLICY &&
            permit.value.expectedEventDigest == eventDigest && permit.value.permitsAbsence == (event == null))
        if (permit is Permit.DeviceAdmissionReceipt) check(effect is Effect.Lookup &&
            request.action == LocalProvisioningAction.ADMIT_DEVICE &&
            permit.value.expectedEventDigest == eventDigest && permit.value.permitsAbsence == (event == null))
        if (effect !is Effect.Lookup) {
            check(event != null && event.scope == request.scope && event.eventId == request.requestId &&
                event.targetId == request.targetId && event.contentDigest == request.contentDigest &&
                event.operatorId == null && event.sessionId == null)
            when (effect) {
                is Effect.Provision -> check(request.action == LocalProvisioningAction.PROVISION_ACCOUNT &&
                    request.scope == effect.scope && request.targetId == effect.actorId && event.kind == LocalAuthEventKind.ACCOUNT_PROVISIONED &&
                    event.accountRevision == Revision(0) && event.at == effect.at)
                is Effect.Admission -> check(request.action == LocalProvisioningAction.ADMIT_DEVICE &&
                    request.scope == effect.admission.scope && request.targetId == effect.admission.deviceId && event.kind == LocalAuthEventKind.DEVICE_ADMITTED &&
                    event.accountRevision == null && event.at == effect.at)
                is Effect.Policy -> check(request.action == LocalProvisioningAction.ACTIVATE_POLICY &&
                    request.scope == effect.scope && request.targetId == effect.actorId && event.kind == LocalAuthEventKind.POLICY_ACTIVATED &&
                    event.accountRevision == effect.afterRevision)
                is Effect.Reactivation -> check(request.action == LocalProvisioningAction.REACTIVATE_ACCOUNT &&
                    request.scope == effect.scope && request.targetId == effect.actorId && event.kind == LocalAuthEventKind.ACCOUNT_REACTIVATED &&
                    event.accountRevision == effect.afterRevision)
                is Effect.Lookup -> protectedLocalUnavailable()
            }
        }
    }

    /** The store attaches this frame before its first initialization suspension or any effect. */
    suspend fun initialize(transaction: RoomLocalSecurityTransaction): Unit = guarded {
        requirePhase(Phase.NEW)
        phase = Phase.INITIALIZING
        owner = transaction
        val selected = checkNotNull(ownedEffect)
        if (selected is Effect.Lookup) checkEvent(transaction)
        else if (transaction.readAuthEvent(request.scope, request.requestId) != null) transaction.refuse()
        when (selected) {
            is Effect.Provision -> {
                checkAdmissions(transaction, selected.admissions)
                if (transaction.readProtectedAccount(request.scope, selected.actorId) != null ||
                    transaction.readPolicy(request.scope, selected.actorId) != null ||
                    transaction.readAuthConfiguration(request.scope, selected.actorId) != null ||
                    transaction.resolveLogin(request.scope, selected.login) != null) transaction.refuse()
                for (dimension in selected.throttles.keys) {
                    if (transaction.readThrottle(ThrottleKey(request.scope, selected.actorId, dimension)) != null) transaction.refuse()
                }
                val ids = mutableSetOf<DomainId>()
                transaction.forEachAccount(request.scope) { ids.add(it.state.actorId) }
                if (ids.size >= 4096 || selected.actorId in ids) transaction.refuse()
                selected.accountIds = ids.toSet()
            }
            is Effect.Admission -> {
                checkAdmissions(transaction, selected.beforeAdmissions)
                val expected = mutableMapOf<DomainId, AccountDeviceEffect>()
                transaction.forEachAccount(request.scope) { account ->
                    val before = account.state
                    val deviceKey = ThrottleKey(request.scope, before.actorId, ThrottleDimension.Device(selected.admission.deviceId))
                    val next: LocalAccountSessionState
                    val deviceThrottle: PayloadDigest
                    val accountThrottle: PayloadDigest?
                    if (selected.isNew) {
                        if (transaction.readThrottle(deviceKey) != null) transaction.refuse()
                        next = before
                        deviceThrottle = throttleDigest(initialProvisioningThrottle(deviceKey, selected.at))
                        accountThrottle = null
                    } else {
                        deviceThrottle = throttleDigest(transaction.readThrottle(deviceKey) ?: transaction.refuse())
                        accountThrottle = throttleDigest(transaction.readThrottle(
                            ThrottleKey(request.scope, before.actorId, ThrottleDimension.Account)) ?: transaction.refuse())
                        val sessions = before.sessions.filter {
                            it.deviceId == selected.admission.deviceId && it.status == SessionStatus.ACTIVE
                        }.map { it.sessionId }.toSet()
                        next = if (sessions.isEmpty()) before else
                            (LocalSessions.revokeSelected(before, sessions) as? SessionTransition.Applied)?.state ?: transaction.refuse()
                    }
                    expected[before.actorId] = AccountDeviceEffect(accountDigest(
                        ProtectedLocalAccount(next, account.credentialRevision, account.credential)), deviceThrottle, accountThrottle)
                }
                // Enumeration enforces the existing 4096-account cap. Only hashes survive it.
                selected.accounts = expected.toMap()
            }
            is Effect.Policy -> {
                checkAccount(transaction, selected.actorId, selected.beforeAccount)
                checkPolicy(transaction, selected.actorId, selected.beforePolicy)
            }
            is Effect.Reactivation -> {
                checkAccount(transaction, selected.actorId, selected.beforeAccount)
                checkPolicy(transaction, selected.actorId, selected.policy)
                checkConfiguration(transaction, selected.actorId, selected.configuration)
                val admissions = transaction.readAdmissions(request.scope)
                selected.admissions = admissionDigests(admissions)
                val dimensions = listOf<ThrottleDimension>(ThrottleDimension.Account) + admissions.map { ThrottleDimension.Device(it.deviceId) }
                selected.throttles = dimensions.associateWith { dimension ->
                    throttleDigest(transaction.readThrottle(ThrottleKey(request.scope, selected.actorId, dimension)) ?: transaction.refuse())
                }
            }
            is Effect.Lookup -> Unit
        }
        if (!permit.isCurrent()) transaction.refuse()
        val observed = source.observeCurrent(context)
        initialObservation = observed
        if (!eligible(observed)) transaction.refuse()
        employeeCapture?.let { capture ->
            val provision = selected as? Effect.Provision ?: transaction.refuse()
            capture.bindPreflight(provision.accountIds, observed)
            transaction.beginHistoryEmployeeProvisioning(capture)
        }
        policyCapture?.let { capture ->
            check(selected is Effect.Policy && employeeCapture == null)
            capture.bindPreflight(observed)
            transaction.beginHistoryEmployeePolicyActivation(capture)
        }
        admissionCapture?.let { capture ->
            val admission = selected as? Effect.Admission ?: transaction.refuse()
            check(admission.isNew && employeeCapture == null && policyCapture == null)
            capture.bindPreflight(admission.accounts.map { it.key to it.value.account }.sortedBy { it.first.value }, observed)
            transaction.beginHistoryDeviceAdmission(capture)
        }
        phase = Phase.BODY
    }

    suspend fun prepareNormalReturn(transaction: RoomLocalSecurityTransaction): Unit = guarded {
        requirePhase(Phase.BODY)
        if (transaction !== owner) transaction.refuse()
        phase = Phase.PREPARING
        when (val selected = checkNotNull(ownedEffect)) {
            is Effect.Provision -> {
                checkAdmissions(transaction, selected.admissions)
                checkAccount(transaction, selected.actorId, selected.account)
                checkPolicy(transaction, selected.actorId, selected.policy)
                checkConfiguration(transaction, selected.actorId, selected.configuration)
                val resolved = transaction.resolveLogin(request.scope, selected.login) ?: transaction.refuse()
                if (configurationDigest(resolved) != selected.configuration) transaction.refuse()
                checkThrottles(transaction, selected.actorId, selected.throttles)
                val ids = mutableSetOf<DomainId>()
                transaction.forEachAccount(request.scope) { ids.add(it.state.actorId) }
                if (ids != selected.accountIds + selected.actorId) transaction.refuse()
            }
            is Effect.Admission -> {
                checkAdmissions(transaction, selected.afterAdmissions)
                var count = 0
                transaction.forEachAccount(request.scope) { account ->
                    val actorId = account.state.actorId
                    val expected = selected.accounts[actorId] ?: transaction.refuse()
                    if (accountDigest(account) != expected.account) transaction.refuse()
                    val device = transaction.readThrottle(ThrottleKey(request.scope, actorId,
                        ThrottleDimension.Device(selected.admission.deviceId))) ?: transaction.refuse()
                    if (throttleDigest(device) != expected.deviceThrottle) transaction.refuse()
                    expected.accountThrottle?.let { digest ->
                        val throttle = transaction.readThrottle(ThrottleKey(request.scope, actorId, ThrottleDimension.Account))
                            ?: transaction.refuse()
                        if (throttleDigest(throttle) != digest) transaction.refuse()
                    }
                    count++
                }
                if (count != selected.accounts.size) transaction.refuse()
            }
            is Effect.Policy -> {
                checkAccount(transaction, selected.actorId, selected.afterAccount)
                checkPolicy(transaction, selected.actorId, selected.afterPolicy)
            }
            is Effect.Reactivation -> {
                checkAccount(transaction, selected.actorId, selected.afterAccount)
                checkPolicy(transaction, selected.actorId, selected.policy)
                checkConfiguration(transaction, selected.actorId, selected.configuration)
                checkAdmissions(transaction, selected.admissions)
                checkThrottles(transaction, selected.actorId, selected.throttles)
            }
            is Effect.Lookup -> Unit
        }
        checkEvent(transaction)
        transaction.ensureAuthorizationHealthy()
        employeeCapture?.let(transaction::completeHistoryEmployeeProvisioning)
        policyCapture?.let(transaction::completeHistoryEmployeePolicyActivation)
        admissionCapture?.let(transaction::completeHistoryDeviceAdmission)
        phase = Phase.PREPARED
    }

    /** Callback first, clock last, then only captured comparisons. The driver still owns COMMIT. */
    fun finishNow(): Unit = guarded {
        requirePhase(Phase.PREPARED)
        if (!permit.isCurrent()) currentLocalAuthorizationDenied()
        val observed = source.observeCurrent(context)
        if (!eligible(observed)) currentLocalAuthorizationDenied()
        phase = Phase.FINISHED
        finished = true
    }

    /** Do not publish an Applied/status response before the real inner COMMIT and this sample. */
    fun finishAfterCommit(callerContext: CoroutineContext): Boolean {
        callerContext.ensureActive()
        failure?.let { throw it }
        if (!finished || phase != Phase.CLOSED || !permit.observedOwnCommitCurrent()) return false
        val observed = try { source.observeCurrent(callerContext) }
        catch (_: RoomCurrentLocalAuthorizationDenied) { return false }
        return eligible(observed)
    }

    fun close() { phase = Phase.CLOSED; owner = null; ownedEffect = null }

    private fun eligible(observed: RoomLocalAuthorizationObservation): Boolean {
        val initial = initialObservation ?: return false
        return initial.issuerContextId == observed.issuerContextId && initial.moment.bootId == observed.moment.bootId &&
            observed.moment.elapsedMillis >= initial.moment.elapsedMillis &&
            (tokens == null || tokens.workLifetimeMillis <= Long.MAX_VALUE - observed.moment.elapsedMillis)
    }

    private suspend fun checkAccount(transaction: RoomLocalSecurityTransaction, actor: DomainId, expected: PayloadDigest) {
        val value = transaction.readProtectedAccount(request.scope, actor) ?: transaction.refuse()
        if (accountDigest(value) != expected) transaction.refuse()
    }
    private suspend fun checkPolicy(transaction: RoomLocalSecurityTransaction, actor: DomainId, expected: PayloadDigest) {
        val value = transaction.readPolicy(request.scope, actor) ?: transaction.refuse()
        if (policyDigest(value) != expected) transaction.refuse()
    }
    private suspend fun checkConfiguration(transaction: RoomLocalSecurityTransaction, actor: DomainId, expected: PayloadDigest) {
        val value = transaction.readAuthConfiguration(request.scope, actor) ?: transaction.refuse()
        if (configurationDigest(value) != expected) transaction.refuse()
    }
    private suspend fun checkAdmissions(transaction: RoomLocalSecurityTransaction, expected: Map<DomainId, PayloadDigest>) {
        if (admissionDigests(transaction.readAdmissions(request.scope)) != expected) transaction.refuse()
    }
    private suspend fun checkThrottles(transaction: RoomLocalSecurityTransaction, actor: DomainId,
        expected: Map<ThrottleDimension, PayloadDigest>) {
        for ((dimension, digest) in expected) {
            val value = transaction.readThrottle(ThrottleKey(request.scope, actor, dimension)) ?: transaction.refuse()
            if (throttleDigest(value) != digest) transaction.refuse()
        }
    }
    private suspend fun checkEvent(transaction: RoomLocalSecurityTransaction) {
        val current = transaction.readAuthEvent(request.scope, request.requestId)
        if (current?.let { provisioningDigest(LocalAuthenticationCodec.encodeEvent(it)) } != eventDigest) transaction.refuse()
    }

    private fun requirePhase(expected: Phase) { context.ensureActive(); failure?.let { throw it }; check(phase == expected) }
    private inline fun <T> guarded(block: () -> T): T = try { failure?.let { throw it }; block() }
    catch (caught: Exception) { if (failure == null) failure = caught; throw caught }

    private sealed interface Permit {
        val request: LocalProvisioningRequest
        fun isCurrent(): Boolean
        fun observedOwnCommitCurrent(): Boolean
        class Effect(val value: LocalProvisioningPermit): Permit {
            override val request: LocalProvisioningRequest get() = value.request
            override fun isCurrent(): Boolean = value.isCurrent()
            override fun observedOwnCommitCurrent(): Boolean = value.observedOwnCommitCurrent()
        }
        class Receipt(val value: LocalProvisioningReceiptPermit): Permit {
            override val request: LocalProvisioningRequest get() = value.request
            override fun isCurrent(): Boolean = value.isCurrent()
            override fun observedOwnCommitCurrent(): Boolean = value.isCurrent()
        }
        class PolicyReceipt(val value: LocalEmployeePolicyActivationReceiptPermit): Permit {
            override val request: LocalProvisioningRequest get() = value.request
            override fun isCurrent(): Boolean = value.isCurrent()
            override fun observedOwnCommitCurrent(): Boolean = value.isCurrent()
        }
        class DeviceAdmissionReceipt(val value: LocalDeviceAdmissionReceiptPermit): Permit {
            override val request: LocalProvisioningRequest get() = value.request
            override fun isCurrent(): Boolean = value.isCurrent()
            override fun observedOwnCommitCurrent(): Boolean = value.isCurrent()
        }
    }

    private data class AccountDeviceEffect(val account: PayloadDigest, val deviceThrottle: PayloadDigest,
        val accountThrottle: PayloadDigest?)

    private sealed interface Effect {
        class Provision(definition: LocalAccountProvisioning, initialAccount: ProtectedLocalAccount,
            initialConfiguration: LocalAuthConfiguration, retained: List<LocalDeviceAdmission>, val at: MonotonicMoment,
            native: NativeEmployeeProvisioningFrame?, event: LocalAuthEvent) : Effect {
            val employeeCapture = native?.let { RuntimeEmployeeProvisioningCapture(it, definition, initialAccount, initialConfiguration, retained, event) }
            val scope = definition.policy.scope
            val actorId = definition.actorId
            val login = definition.login
            val tokens = definition.tokenPolicy
            val account = accountDigest(initialAccount)
            val policy = policyDigest(definition.policy)
            val configuration = configurationDigest(initialConfiguration)
            val admissions = admissionDigests(retained)
            val throttles = (listOf<ThrottleDimension>(ThrottleDimension.Account) + retained.map { ThrottleDimension.Device(it.deviceId) })
                .associateWith { throttleDigest(initialProvisioningThrottle(ThrottleKey(definition.policy.scope, actorId, it), at)) }
            var accountIds: Set<DomainId> = emptySet()
            init {
                val state = initialAccount.state
                check(state.scope == definition.policy.scope && state.actorId == actorId && state.enabled &&
                    state.revision == Revision(0) && state.generation == SessionGeneration(0) && state.sessions.isEmpty() &&
                    initialAccount.credentialRevision == Revision(0) && initialConfiguration.scope == state.scope &&
                    initialConfiguration.actorId == actorId && initialConfiguration.revision == Revision(0) &&
                    initialConfiguration.login == login && initialConfiguration.tokens == tokens &&
                    retained.all { it.scope == state.scope } &&
                    retained.filter { it.admitted }.map { it.deviceId }.toSet() == definition.admittedDeviceIds)
            }
        }

        class Admission(val admission: LocalDeviceAdmission, retained: List<LocalDeviceAdmission>, val at: MonotonicMoment,
            native: NativeDeviceAdmissionFrame?, event: LocalAuthEvent) : Effect {
            val admissionCapture = native?.let { RuntimeDeviceAdmissionCapture(it, admission, retained, event) }
            val beforeAdmissions = admissionDigests(retained)
            val afterAdmissions = beforeAdmissions + (admission.deviceId to admissionDigest(admission))
            val isNew = retained.none { it.deviceId == admission.deviceId }
            var accounts: Map<DomainId, AccountDeviceEffect> = emptyMap()
            init {
                check(admission.admitted && retained.all { it.scope == admission.scope })
                val previous = retained.singleOrNull { it.deviceId == admission.deviceId }
                if (previous == null) check(admission.revision == Revision(0) && retained.size < 256)
                else check(!previous.admitted && previous.revision.value < Long.MAX_VALUE &&
                    admission.revision == previous.revision.next())
                if (native != null) check(isNew && native.requested.sdkIdentity != null)
            }
        }

        class Policy(before: ProtectedLocalAccount, after: ProtectedLocalAccount,
            previous: ActivatedLocalPolicy, next: ActivatedLocalPolicy,
            native: NativeEmployeePolicyActivationFrame?, event: LocalAuthEvent) : Effect {
            val policyCapture = native?.let { RuntimeEmployeePolicyActivationCapture(it, before, after, previous, next, event) }
            val scope = next.scope
            val actorId = next.actorId
            val beforeAccount = accountDigest(before)
            val afterAccount = accountDigest(after)
            val beforePolicy = policyDigest(previous)
            val afterPolicy = policyDigest(next)
            val afterRevision = after.state.revision
            init {
                check(before.state.scope == next.scope && before.state.actorId == actorId &&
                    previous.scope == next.scope && previous.actorId == actorId &&
                    previous.revision.value < Long.MAX_VALUE && next.revision == previous.revision.next())
                val state = before.state
                val predicted = if (state.sessions.count { it.status == SessionStatus.ACTIVE } > next.sessionPolicy.maximumConcurrentSessions)
                    (LocalSessions.revokeAll(state) as? SessionTransition.Applied)?.state ?: protectedLocalUnavailable()
                else state
                check(afterAccount == accountDigest(ProtectedLocalAccount(predicted, before.credentialRevision, before.credential)))
            }
        }

        class Reactivation(target: LocalAccountReactivation, before: ProtectedLocalAccount, after: ProtectedLocalAccount,
            currentPolicy: ActivatedLocalPolicy, currentConfiguration: LocalAuthConfiguration) : Effect {
            val scope = target.scope
            val actorId = target.actorId
            val beforeAccount = accountDigest(before)
            val afterAccount = accountDigest(after)
            val policy = policyDigest(currentPolicy)
            val configuration = configurationDigest(currentConfiguration)
            val afterRevision = after.state.revision
            var admissions: Map<DomainId, PayloadDigest> = emptyMap()
            var throttles: Map<ThrottleDimension, PayloadDigest> = emptyMap()
            init {
                val state = before.state
                check(state.scope == target.scope && state.actorId == actorId && !state.enabled &&
                    state.revision == target.expectedAccountRevision && before.credentialRevision == target.expectedCredentialRevision &&
                    currentPolicy.scope == target.scope && currentPolicy.actorId == actorId && currentPolicy.revision == target.expectedPolicyRevision &&
                    currentConfiguration.scope == target.scope && currentConfiguration.actorId == actorId &&
                    currentConfiguration.revision == target.expectedConfigurationRevision &&
                    state.revision.value < Long.MAX_VALUE - 1 && state.generation.value < Long.MAX_VALUE - 1 &&
                    state.sessions.none { it.status == SessionStatus.ACTIVE })
                val predicted = LocalAccountSessionState(state.scope, actorId, true, state.revision.next(), state.generation.next(), state.sessions)
                check(afterAccount == accountDigest(ProtectedLocalAccount(predicted, before.credentialRevision, before.credential)))
            }
        }

        class Lookup : Effect
    }

    companion object {
        fun provisionAccount(request: LocalProvisioningRequest, permit: LocalProvisioningPermit, source: RoomCurrentLocalAuthorizationSource,
            context: CoroutineContext, definition: LocalAccountProvisioning, account: ProtectedLocalAccount,
            configuration: LocalAuthConfiguration, admissions: List<LocalDeviceAdmission>, event: LocalAuthEvent,
            native: NativeEmployeeProvisioningFrame? = null): RoomLocalProvisioningFrame =
            RoomLocalProvisioningFrame(request, Permit.Effect(permit), source, context,
                Effect.Provision(definition, account, configuration, admissions, event.at, native, event), event)

        fun admitDevice(request: LocalProvisioningRequest, permit: LocalProvisioningPermit, source: RoomCurrentLocalAuthorizationSource,
            context: CoroutineContext, admission: LocalDeviceAdmission, retained: List<LocalDeviceAdmission>,
            event: LocalAuthEvent, native: NativeDeviceAdmissionFrame? = null): RoomLocalProvisioningFrame =
            RoomLocalProvisioningFrame(request, Permit.Effect(permit), source, context, Effect.Admission(admission, retained, event.at, native, event), event)

        fun activatePolicy(request: LocalProvisioningRequest, permit: LocalProvisioningPermit, source: RoomCurrentLocalAuthorizationSource,
            context: CoroutineContext, before: ProtectedLocalAccount, after: ProtectedLocalAccount,
            previous: ActivatedLocalPolicy, next: ActivatedLocalPolicy, event: LocalAuthEvent,
            native: NativeEmployeePolicyActivationFrame? = null): RoomLocalProvisioningFrame =
            RoomLocalProvisioningFrame(request, Permit.Effect(permit), source, context, Effect.Policy(before, after, previous, next, native, event), event)

        fun reactivateAccount(request: LocalProvisioningRequest, permit: LocalProvisioningPermit, source: RoomCurrentLocalAuthorizationSource,
            context: CoroutineContext, target: LocalAccountReactivation, before: ProtectedLocalAccount, after: ProtectedLocalAccount,
            policy: ActivatedLocalPolicy, configuration: LocalAuthConfiguration, event: LocalAuthEvent): RoomLocalProvisioningFrame =
            RoomLocalProvisioningFrame(request, Permit.Effect(permit), source, context, Effect.Reactivation(target, before, after, policy, configuration), event)

        fun lookupOriginal(request: LocalProvisioningRequest, permit: LocalProvisioningReceiptPermit, source: RoomCurrentLocalAuthorizationSource,
            context: CoroutineContext, event: LocalAuthEvent?): RoomLocalProvisioningFrame =
            RoomLocalProvisioningFrame(request, Permit.Receipt(permit), source, context, Effect.Lookup(), event)

        fun lookupOriginalPolicy(request: LocalProvisioningRequest, permit: LocalEmployeePolicyActivationReceiptPermit,
            source: RoomCurrentLocalAuthorizationSource, context: CoroutineContext, event: LocalAuthEvent?): RoomLocalProvisioningFrame =
            RoomLocalProvisioningFrame(request, Permit.PolicyReceipt(permit), source, context, Effect.Lookup(), event)

        fun lookupOriginalDeviceAdmission(request: LocalProvisioningRequest, permit: LocalDeviceAdmissionReceiptPermit,
            source: RoomCurrentLocalAuthorizationSource, context: CoroutineContext, event: LocalAuthEvent?): RoomLocalProvisioningFrame =
            RoomLocalProvisioningFrame(request, Permit.DeviceAdmissionReceipt(permit), source, context, Effect.Lookup(), event)

        fun lookup(request: LocalProvisioningRequest, permit: LocalProvisioningPermit, source: RoomCurrentLocalAuthorizationSource,
            context: CoroutineContext, event: LocalAuthEvent?): RoomLocalProvisioningFrame =
            RoomLocalProvisioningFrame(request, Permit.Effect(permit), source, context, Effect.Lookup(), event)
    }
}

private fun initialProvisioningThrottle(key: ThrottleKey, moment: MonotonicMoment): PersistentThrottleState =
    PersistentThrottleState(key, Revision(0), 0, moment, 0, null)
private fun accountDigest(value: ProtectedLocalAccount): PayloadDigest = provisioningDigest(ProtectedLocalSecurityCodec.encodeAccount(value))
private fun policyDigest(value: ActivatedLocalPolicy): PayloadDigest = provisioningDigest(LocalEffectivePolicyCodec.encodePolicy(value))
private fun configurationDigest(value: LocalAuthConfiguration): PayloadDigest = provisioningDigest(LocalAuthenticationCodec.encodeConfiguration(value))
private fun throttleDigest(value: PersistentThrottleState): PayloadDigest = provisioningDigest(ProtectedLocalSecurityCodec.encodeThrottle(value))
private fun admissionDigest(value: LocalDeviceAdmission): PayloadDigest = provisioningDigest(ProtectedLocalSecurityCodec.encodeAdmission(value))
private fun admissionDigests(values: List<LocalDeviceAdmission>): Map<DomainId, PayloadDigest> {
    check(values.size <= 256 && values.map { it.deviceId }.toSet().size == values.size)
    return values.associate { it.deviceId to admissionDigest(it) }
}
private fun provisioningDigest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
