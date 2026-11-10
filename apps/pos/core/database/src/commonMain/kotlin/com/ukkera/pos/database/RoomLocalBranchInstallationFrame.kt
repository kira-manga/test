package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** One specific initial-install/receipt/empty-lookup proof, never a generic finalization registry. */
internal class RoomLocalBranchInstallationFrame(
    private val request: LocalBranchInstallationRequest,
    private val ceremony: VerifiedLocalBranchInstallationCeremony,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val context: CoroutineContext,
    definition: LocalBranchInstallationDefinition,
    record: LocalBranchInstallationRecord?,
    private val newInstallation: Boolean,
) {
    private enum class Phase { NEW, INITIALIZING, BODY, PREPARING, PREPARED, FINISHED, CLOSED }
    private var phase = Phase.NEW
    private var failure: Exception? = null
    private var owner: RoomLocalSecurityTransaction? = null
    private var ownedDefinition: LocalBranchInstallationDefinition? = definition
    private var ownedRecord: LocalBranchInstallationRecord? = record
    private var initialObservation: RoomLocalAuthorizationObservation? = null
    private var finished = false
    private val installedAt = record?.installedAt
    private val installationIssuer = record?.issuerContextId
    private val tokens = if (newInstallation) definition.firstAccount.tokenPolicy else null
    private val eventDigest = record?.let { installationDigest(LocalAuthenticationCodec.encodeEvent(LocalBranchInstallationCodec.event(it))) }

    init {
        check(ceremony.request == request && LocalBranchInstallationCodec.request(definition) == request)
        check(!newInstallation || record != null)
        if (record != null) {
            check(record.request == request)
            val identity = request.identity
            val configuration = LocalAuthConfiguration(identity.scope, identity.firstActorId, Revision(0),
                definition.firstAccount.login, definition.firstAccount.tokenPolicy)
            check(record.policyDigest == installationDigest(LocalEffectivePolicyCodec.encodePolicy(definition.firstAccount.policy)))
            check(record.configurationDigest == installationDigest(LocalAuthenticationCodec.encodeConfiguration(configuration)))
            check(record.admissionDigest == installationDigest(ProtectedLocalSecurityCodec.encodeAdmission(definition.masterAdmission)))
            check(record.accountThrottleDigest == throttleDigest(initialInstallationThrottle(request, ThrottleDimension.Account, record.installedAt)))
            check(record.deviceThrottleDigest == throttleDigest(initialInstallationThrottle(request,
                ThrottleDimension.Device(identity.masterDeviceId), record.installedAt)))
            check(record.recoveryStateDigest == installationDigest(RoomBranchInstallationStorage.recoveryJson(identity.scope).encodeToByteArray()))
        }
    }

    /** Attached before this first initialization suspension and before BranchRow or protected inserts. */
    suspend fun initialize(transaction: RoomLocalSecurityTransaction): Unit = guarded {
        requirePhase(Phase.NEW)
        phase = Phase.INITIALIZING
        owner = transaction
        val footprint = transaction.installationFootprint()
        if (newInstallation || ownedRecord == null) {
            if (!footprint.empty) transaction.refuse()
        } else {
            if (!footprint.initial) transaction.refuse()
            checkStored(transaction)
        }
        if (!ceremony.isCurrent()) transaction.refuse()
        val observed = source.observeCurrent(context)
        initialObservation = observed
        if (!eligible(observed)) transaction.refuse()
        phase = Phase.BODY
    }

    internal fun permitsBranchInsertion(transaction: RoomLocalSecurityTransaction, record: LocalBranchInstallationRecord): Boolean =
        phase == Phase.BODY && failure == null && owner === transaction && newInstallation && ownedRecord === record

    suspend fun prepareNormalReturn(transaction: RoomLocalSecurityTransaction): Unit = guarded {
        requirePhase(Phase.BODY)
        if (transaction !== owner) transaction.refuse()
        phase = Phase.PREPARING
        if (ownedRecord != null) checkStored(transaction)
        transaction.ensureAuthorizationHealthy()
        val footprint = transaction.installationFootprint()
        if (ownedRecord == null) {
            if (!footprint.empty) transaction.refuse()
        } else if (!footprint.initial) transaction.refuse()
        phase = Phase.PREPARED
    }

    /** The last external callback is current ceremony, then the same source's synchronous clock. */
    fun finishNow(): Unit = guarded {
        requirePhase(Phase.PREPARED)
        if (!ceremony.isCurrent()) currentLocalAuthorizationDenied()
        val observed = source.observeCurrent(context)
        if (!eligible(observed)) currentLocalAuthorizationDenied()
        phase = Phase.FINISHED
        finished = true
    }

    /** The inner Room Job is completed: use the still-running outer caller for BOTH observations. */
    fun finishAfterCommit(callerContext: CoroutineContext): Boolean {
        callerContext.ensureActive()
        failure?.let { throw it }
        if (!finished || phase != Phase.CLOSED || !ceremony.isCurrent()) return false
        val observed = try { source.observeCurrent(callerContext) }
        catch (_: RoomCurrentLocalAuthorizationDenied) { return false }
        return eligible(observed)
    }

    fun close() { phase = Phase.CLOSED; owner = null; ownedDefinition = null; ownedRecord = null }

    private suspend fun checkStored(transaction: RoomLocalSecurityTransaction) {
        val record = checkNotNull(ownedRecord)
        val definition = checkNotNull(ownedDefinition)
        val identity = request.identity
        val scope = identity.scope
        if (!transaction.matchesInstallationBranch(RoomBranchInstallationStorage.branch(record))) transaction.refuse()
        val account = transaction.readProtectedAccount(scope, identity.firstActorId) ?: transaction.refuse()
        val state = account.state
        val credential = account.credential ?: transaction.refuse()
        if (!state.enabled || state.revision != Revision(0) || state.generation != SessionGeneration(0) ||
            state.sessions.isNotEmpty() || account.credentialRevision != Revision(0) ||
            credential.binding != CredentialBinding(scope, identity.firstActorId, Revision(0)) ||
            credential.pepperReference.keyId != identity.pepperKeyId ||
            installationDigest(ProtectedLocalSecurityCodec.encodeAccount(account)) != record.accountDigest) transaction.refuse()
        val policy = transaction.readPolicy(scope, identity.firstActorId) ?: transaction.refuse()
        if (installationDigest(LocalEffectivePolicyCodec.encodePolicy(policy)) != record.policyDigest) transaction.refuse()
        val configuration = transaction.readAuthConfiguration(scope, identity.firstActorId) ?: transaction.refuse()
        if (installationDigest(LocalAuthenticationCodec.encodeConfiguration(configuration)) != record.configurationDigest) transaction.refuse()
        val login = transaction.resolveLogin(scope, definition.firstAccount.login) ?: transaction.refuse()
        if (installationDigest(LocalAuthenticationCodec.encodeConfiguration(login)) != record.configurationDigest) transaction.refuse()
        val admission = transaction.readAdmission(scope, identity.masterDeviceId) ?: transaction.refuse()
        if (installationDigest(ProtectedLocalSecurityCodec.encodeAdmission(admission)) != record.admissionDigest) transaction.refuse()
        val accountThrottle = transaction.readThrottle(ThrottleKey(scope, identity.firstActorId, ThrottleDimension.Account)) ?: transaction.refuse()
        val deviceThrottle = transaction.readThrottle(ThrottleKey(scope, identity.firstActorId,
            ThrottleDimension.Device(identity.masterDeviceId))) ?: transaction.refuse()
        if (throttleDigest(accountThrottle) != record.accountThrottleDigest || throttleDigest(deviceThrottle) != record.deviceThrottleDigest) transaction.refuse()
        val event = transaction.readAuthEvent(scope, identity.installationId) ?: transaction.refuse()
        if (event.kind != LocalAuthEventKind.BRANCH_INSTALLED ||
            installationDigest(LocalAuthenticationCodec.encodeEvent(event)) != eventDigest) transaction.refuse()
    }

    private fun eligible(observed: RoomLocalAuthorizationObservation): Boolean {
        val initial = initialObservation ?: return false
        if (initial.issuerContextId != observed.issuerContextId || initial.moment.bootId != observed.moment.bootId ||
            observed.moment.elapsedMillis < initial.moment.elapsedMillis ||
            !ceremony.validAt(LocalAuthenticationObservation(observed.issuerContextId, observed.moment))) return false
        if (newInstallation && (installationIssuer != observed.issuerContextId || installedAt?.bootId != observed.moment.bootId ||
                observed.moment.elapsedMillis < checkNotNull(installedAt).elapsedMillis)) return false
        return tokens == null || tokens.workLifetimeMillis <= Long.MAX_VALUE - observed.moment.elapsedMillis
    }

    private fun requirePhase(expected: Phase) { context.ensureActive(); failure?.let { throw it }; check(phase == expected) }
    private inline fun <T> guarded(block: () -> T): T = try { failure?.let { throw it }; block() }
    catch (caught: Exception) { if (failure == null) failure = caught; throw caught }
}

internal fun initialInstallationThrottle(request: LocalBranchInstallationRequest, dimension: ThrottleDimension,
    at: MonotonicMoment): PersistentThrottleState = PersistentThrottleState(
    ThrottleKey(request.identity.scope, request.identity.firstActorId, dimension), Revision(0), 0, at, 0, null)
private fun throttleDigest(value: PersistentThrottleState): PayloadDigest = installationDigest(ProtectedLocalSecurityCodec.encodeThrottle(value))

/** One original C1 transaction. Its exact frame is the only V2 seed authorization, not an
 * externally supplied callback. All AEAD rereads/complete seed census precede native pending. */
internal class RoomSelfOwnedBranchInstallationFrame(
    private val owner: RoomRuntimeHistoryOwner,
    private val prepared: RoomSelfOwnedInstallationVerifier,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val context: CoroutineContext,
    internal val receipt: LocalBranchInstallationReceiptV2,
    private val accountThrottle: PersistentThrottleState,
    private val deviceThrottle: PersistentThrottleState,
) {
    private var transaction: RoomLocalSecurityTransaction?=null
    private var finished=false
    private var preparedReturn=false
    private var closed=false
    suspend fun initialize(actual: RoomLocalSecurityTransaction) {
        context.ensureActive(); check(transaction==null && !closed && actual.belongsTo(owner.database))
        check(actual.originalInstallationFootprint(initial=false))
        check(owner.originalRecord.record.database!=null && owner.originalRecord.record.parent==null &&
            owner.originalRecord.record.attempt?.facts==prepared.attempt.facts && receipt.core.definitionDigest==prepared.snapshot.definitionDigest)
        requireCurrent()
        transaction=actual
        actual.captureOriginalInstallation(prepared.snapshot,receipt)
    }
    fun permits(actual: RoomLocalSecurityTransaction,value: LocalBranchInstallationReceiptV2): Boolean =
        transaction===actual && !closed && !preparedReturn && !finished && value===receipt
    suspend fun prepareNormalReturn(actual: RoomLocalSecurityTransaction) {
        context.ensureActive(); check(permits(actual,receipt))
        val c=receipt.core.allocation.coordinates; val seeds=receipt.core.seeds
        check(actual.originalInstallationFootprint(initial=true) && actual.matchesInstallationBranch(RoomBranchInstallationStorage.originalBranch(receipt)))
        check(installationDigest(ProtectedLocalSecurityCodec.encodeAccount(checkNotNull(actual.readProtectedAccount(c.scope,c.firstActorId))))==seeds.account)
        check(installationDigest(LocalEffectivePolicyCodec.encodePolicy(checkNotNull(actual.readPolicy(c.scope,c.firstActorId))))==seeds.policy)
        check(installationDigest(LocalAuthenticationCodec.encodeConfiguration(checkNotNull(actual.readAuthConfiguration(c.scope,c.firstActorId))))==seeds.configuration)
        check(installationDigest(LocalAuthenticationCodec.encodeConfiguration(checkNotNull(actual.resolveLogin(c.scope,prepared.configuration.login))))==seeds.configuration)
        check(installationDigest(ProtectedLocalSecurityCodec.encodeAdmission(checkNotNull(actual.readAdmission(c.scope,c.deviceId))))==seeds.admission)
        check(actual.readThrottle(accountThrottle.key)==accountThrottle && actual.readThrottle(deviceThrottle.key)==deviceThrottle)
        check(actual.readOriginalInstallationReceipt(c.scope,c.installationId)?.digest==receipt.digest)
        actual.ensureAuthorizationHealthy(); requireCurrent(); preparedReturn=true
    }
    fun finishNow() {
        context.ensureActive(); check(preparedReturn && !closed && !finished); requireCurrent(); finished=true
    }
    private fun requireCurrent() {
        val facts=prepared.attempt.facts; val observed=source.observeCurrent(context)
        // Separate static locations identify a refusal without recording protected values.
        // Every original predicate remains mandatory; the signed deadline never renews.
        check(observed.issuerContextId==facts.issuerContextId)
        check(observed.moment.bootId==facts.monotonicBootId)
        check(observed.moment.elapsedMillis>=receipt.core.installedAtElapsedMillis)
        check(observed.moment.elapsedMillis<facts.deadlineElapsedMillis)
    }
    fun close() { closed=true; transaction=null }
}
