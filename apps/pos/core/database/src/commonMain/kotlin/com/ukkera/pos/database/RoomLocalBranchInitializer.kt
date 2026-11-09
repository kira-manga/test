package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore

/**
 * Actual production initializer/lookup over one real Room/store/source. Trusted native
 * composition must provide the real verifier, key/pepper providers and same supervised writer
 * identity. This internal constructor is not a public raw-seed route or an accepting provider.
 * Neither a receipt nor writable intent qualifies AtomicMasterStore's independent runtime gate.
 */
internal class RoomLocalBranchInitializer(
    private val scope: BranchScope,
    private val store: RoomLocalSecurityStore,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val passwords: LocalPasswordHasher,
    private val pepper: ProtectedPepperReference,
    private val storageKey: ProtectedStorageKeyReference,
    private val verifier: LocalBranchInstallationVerifier,
    maximumConcurrentInstallation: Int,
) : LocalBranchInitializer {
    private val slots = Semaphore(maximumConcurrentInstallation)
    init {
        require(maximumConcurrentInstallation in 1..64)
        check(source.belongsTo(store) && store.usesInstallationKey(storageKey))
    }

    override suspend fun initialize(definition: LocalBranchInstallationDefinition,
        password: CharArray): LocalBranchInstallationResult = bounded {
        if (!matchesOwner(definition) || password.size !in 4..1024) return@bounded LocalBranchInstallationResult.Denied
        val owned = password.copyOf()
        try {
            val request = LocalBranchInstallationCodec.request(definition)
            val ceremony = verifier.verify(request) ?: return@bounded LocalBranchInstallationResult.Denied
            currentCoroutineContext().ensureActive()
            if (ceremony.request != request || !ceremony.isCurrent()) return@bounded LocalBranchInstallationResult.Denied
            currentCoroutineContext().ensureActive()
            // Outside EVERY Room writer loan. A retry never installs this new verifier over a retained credential.
            val binding = CredentialBinding(scope, request.identity.firstActorId, Revision(0))
            val credential = when (val result = passwords.create(owned, binding, pepper)) {
                is PasswordHashingResult.Created -> result.record
                is PasswordHashingResult.Unavailable -> return@bounded when (result.reason) {
                    PasswordFailure.HASHER_BUSY -> LocalBranchInstallationResult.Busy
                    PasswordFailure.INVALID_INPUT -> LocalBranchInstallationResult.Denied
                    else -> LocalBranchInstallationResult.Unavailable
                }
            }
            if (credential.binding != binding || credential.pepperReference != pepper) protectedLocalUnavailable()
            runWriter(definition, request, ceremony, credential)
        } finally { owned.fill('\u0000') }
    }

    override suspend fun lookup(definition: LocalBranchInstallationDefinition): LocalBranchInstallationResult = bounded {
        if (!matchesOwner(definition)) return@bounded LocalBranchInstallationResult.Denied
        val request = LocalBranchInstallationCodec.request(definition)
        val ceremony = verifier.verify(request) ?: return@bounded LocalBranchInstallationResult.Denied
        currentCoroutineContext().ensureActive()
        if (ceremony.request != request || !ceremony.isCurrent()) return@bounded LocalBranchInstallationResult.Denied
        runWriter(definition, request, ceremony, null)
    }

    private suspend fun runWriter(definition: LocalBranchInstallationDefinition, request: LocalBranchInstallationRequest,
        ceremony: VerifiedLocalBranchInstallationCeremony, credential: PasswordHashRecord?): LocalBranchInstallationResult =
        localSecurityLane(store.database) {
            var finalization: RoomLocalBranchInstallationFrame? = null
            val result = store.transaction { transaction ->
                val footprint = transaction.installationFootprint()
                if (!footprint.empty) {
                    // No adoption of a partially populated store, training data or a later operational history.
                    if (!footprint.initial) transaction.refuse()
                    val event = transaction.readAuthEvent(scope, request.identity.installationId) ?: transaction.refuse()
                    val record = event.installation ?: transaction.refuse()
                    if (event.kind != LocalAuthEventKind.BRANCH_INSTALLED || record.request != request) transaction.refuse()
                    val frame = RoomLocalBranchInstallationFrame(request, ceremony, source, currentCoroutineContext(),
                        definition, record, newInstallation = false)
                    finalization = frame
                    transaction.attachBranchInstallationFrame(frame)
                    return@transaction LocalBranchInstallationResult.Installed(record, resumed = true)
                }
                if (credential == null) {
                    val frame = RoomLocalBranchInstallationFrame(request, ceremony, source, currentCoroutineContext(),
                        definition, null, newInstallation = false)
                    finalization = frame
                    transaction.attachBranchInstallationFrame(frame)
                    return@transaction LocalBranchInstallationResult.NotRecorded
                }
                val observed = source.observeCurrent(currentCoroutineContext())
                if (!ceremony.validAt(LocalAuthenticationObservation(observed.issuerContextId, observed.moment))) transaction.refuse()
                val identity = request.identity
                val configuration = LocalAuthConfiguration(scope, identity.firstActorId, Revision(0),
                    definition.firstAccount.login, definition.firstAccount.tokenPolicy)
                val account = ProtectedLocalAccount(LocalAccountSessionState(scope, identity.firstActorId, true,
                    Revision(0), SessionGeneration(0), emptyList()), Revision(0), credential)
                val accountThrottle = initialInstallationThrottle(request, ThrottleDimension.Account, observed.moment)
                val deviceThrottle = initialInstallationThrottle(request, ThrottleDimension.Device(identity.masterDeviceId), observed.moment)
                val record = LocalBranchInstallationRecord(request, observed.issuerContextId, observed.moment,
                    installationDigest(ProtectedLocalSecurityCodec.encodeAccount(account)),
                    installationDigest(LocalEffectivePolicyCodec.encodePolicy(definition.firstAccount.policy)),
                    installationDigest(LocalAuthenticationCodec.encodeConfiguration(configuration)),
                    installationDigest(ProtectedLocalSecurityCodec.encodeAdmission(definition.masterAdmission)),
                    installationDigest(ProtectedLocalSecurityCodec.encodeThrottle(accountThrottle)),
                    installationDigest(ProtectedLocalSecurityCodec.encodeThrottle(deviceThrottle)),
                    installationDigest(RoomBranchInstallationStorage.recoveryJson(scope).encodeToByteArray()))
                val frame = RoomLocalBranchInstallationFrame(request, ceremony, source, currentCoroutineContext(),
                    definition, record, newInstallation = true)
                finalization = frame
                transaction.attachBranchInstallationFrame(frame)
                transaction.insertInstallationBranch(frame, record)
                transaction.insertAdmission(definition.masterAdmission)
                transaction.insertAccount(account)
                transaction.insertPolicy(definition.firstAccount.policy)
                transaction.insertAuthConfiguration(configuration)
                transaction.insertThrottle(accountThrottle)
                transaction.insertThrottle(deviceThrottle)
                transaction.insertAuthEvent(LocalBranchInstallationCodec.event(record))
                LocalBranchInstallationResult.Installed(record, resumed = false)
            }
            // The current OUTER context remains active; the nested transaction's Job has completed.
            if (finalization?.finishAfterCommit(currentCoroutineContext()) != true) protectedLocalUnavailable()
            result
        }

    private fun matchesOwner(definition: LocalBranchInstallationDefinition): Boolean =
        definition.identity.scope == scope && definition.identity.storageKeyId == storageKey.keyId &&
            definition.identity.pepperKeyId == pepper.keyId

    private suspend fun bounded(block: suspend () -> LocalBranchInstallationResult): LocalBranchInstallationResult {
        currentCoroutineContext().ensureActive()
        if (!slots.tryAcquire()) return LocalBranchInstallationResult.Busy
        return try {
            val result = block()
            currentCoroutineContext().ensureActive()
            result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: RoomLocalStorageUncertain) { currentCoroutineContext().ensureActive(); LocalBranchInstallationResult.StorageUncertain }
        catch (_: RoomCurrentLocalAuthorizationDenied) { currentCoroutineContext().ensureActive(); LocalBranchInstallationResult.Denied }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); LocalBranchInstallationResult.Unavailable }
        finally { slots.release() }
    }
}

/** Explicit schema7 path. The old V1 definition/record/initializer above remains byte-for-byte
 * versioned; it is never widened to accept later history or converted into a V2 receipt. */
internal class RoomSelfOwnedBranchInitializer(
    private val owner: RoomRuntimeHistoryOwner,
    private val store: RoomLocalSecurityStore,
    private val source: RoomCurrentLocalAuthorizationSource,
) {
    init { check(store.database===owner.database && source.belongsTo(store)) }
    suspend fun initialize(prepared: RoomSelfOwnedInstallationVerifier): LocalBranchInstallationReceiptV2 {
        try {
            val receipt=store.transaction { transaction ->
                val commit=prepared.begin(owner)
                val snapshot=prepared.snapshot; val d=snapshot.definition; val c=d.allocation.coordinates
                val moment=MonotonicMoment(commit.monotonicBootId,commit.elapsedMillis)
                val accountThrottle=PersistentThrottleState(ThrottleKey(c.scope,c.firstActorId,ThrottleDimension.Account),Revision(0),0,moment,0,null)
                val deviceThrottle=PersistentThrottleState(ThrottleKey(c.scope,c.firstActorId,ThrottleDimension.Device(c.deviceId)),Revision(0),0,moment,0,null)
                val seeds=LocalInstallationSeedDigestsV2(
                    installationDigest(ProtectedLocalSecurityCodec.encodeAccount(prepared.account)),prepared.templates.policyDigest,
                    prepared.templates.configurationDigest,prepared.templates.admissionDigest,
                    installationDigest(ProtectedLocalSecurityCodec.encodeThrottle(accountThrottle)),
                    installationDigest(ProtectedLocalSecurityCodec.encodeThrottle(deviceThrottle)),
                    installationDigest(RoomBranchInstallationStorage.recoveryJson(c.scope).encodeToByteArray()))
                val approvals=checkNotNull(owner.originalRecord.record.attempt?.approvals)
                val receipt=LocalBranchInstallationWireV2.receipt(LocalBranchInstallationWireV2.genesisCore(snapshot,prepared.attempt.facts,approvals,commit,seeds))
                val frame=RoomSelfOwnedBranchInstallationFrame(owner,prepared,source,currentCoroutineContext(),receipt,accountThrottle,deviceThrottle)
                transaction.attachOriginalInstallationFrame(frame)
                transaction.insertOriginalInstallationBranch(frame,receipt)
                transaction.insertAdmission(d.masterAdmission)
                transaction.insertAccount(prepared.account)
                transaction.insertPolicy(d.firstAccount.policy)
                transaction.insertAuthConfiguration(prepared.configuration)
                transaction.insertThrottle(accountThrottle)
                transaction.insertThrottle(deviceThrottle)
                transaction.insertOriginalInstallationReceipt(frame,receipt)
                receipt
            }
            // The actual outer Room return/native child settlement preceded this original P1
            // receipt acknowledgement. Only the same native completion registry can issue it.
            owner.acknowledgeInstallation()
            return receipt
        } finally { prepared.clear() }
    }
}
