package com.ukkera.pos.app.gateway

import android.app.Application
import com.ukkera.pos.rpc.AndroidOriginalRpcClientOwner
import com.ukkera.pos.rpc.LocalRpcRemoteAuthenticationService
import com.ukkera.pos.security.PairedDeviceCredentialJournalCustody
import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A private, fresh actual Master Current result on the SAME final retained original RPC
 * owner. No LocalAuthenticationService interface, Current DATA, actor scalar or positive
 * lifetime/verifier callback can construct this capture. It supplies acquisition identity
 * only, never grants future business permission or restores an interactive login. */
internal class AndroidPairedDeviceCurrentActor private constructor(
    private val nativeOriginal: PairedDeviceCredentialJournalCustody,
    private val rpc: AndroidOriginalRpcClientOwner,
    private val service: LocalRpcRemoteAuthenticationService,
    private val sdkOriginal: LocalDevicePairingSdkOriginal,
    private val approvedOriginal: LocalDevicePairingApprovedMaster,
    val identity: LocalClientIdentity,
) {
    fun belongsTo(original: PairedDeviceCredentialJournalCustody): Boolean = nativeOriginal === original

    /** Exact retained selected-original/physical connection observation only. The fresh
     * authorization is the actual Current exchange below, not this synchronous sample. */
    fun isCurrent(): Boolean = service.isCurrent() && rpc.original.value === sdkOriginal &&
        rpc.approvedMaster.value === approvedOriginal

    companion object {
        suspend fun authenticate(original: PairedDeviceCredentialJournalCustody,
            rpc: AndroidOriginalRpcClientOwner, expected: LocalClientIdentity,
            credentials: LocalGatewayCredentials): AndroidPairedDeviceCurrentActor? {
            currentCoroutineContext().ensureActive()
            if (expected.scope != original.scope || expected.deviceId != original.deviceId ||
                !ANDROID_CLIENT_UUID_V1.matches(expected.actorId.value)) return null
            return try {
                val service = rpc.authenticationService()
                val sdk = rpc.original.value ?: return null
                val approved = rpc.approvedMaster.value ?: return null
                if (credentials.connectionId != service.connectionId || !service.isCurrent() ||
                    !sameOriginal(original, sdk, approved)) return null
                // The expected scalar is only a mismatch guard. Actor identity comes from
                // this NEW actual selected-original Master exchange, not a credential record.
                val result = service.current(credentials)
                if (result !is LocalSessionViewResult.Current) return null
                val actual = LocalClientIdentity(result.view.scope, result.view.actorId, result.view.deviceId)
                if (actual != expected || !service.isCurrent() || rpc.original.value !== sdk ||
                    rpc.approvedMaster.value !== approved || !sameOriginal(original, sdk, approved)) return null
                currentCoroutineContext().ensureActive()
                AndroidPairedDeviceCurrentActor(original, rpc, service, sdk, approved, actual)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }

        private fun sameOriginal(native: PairedDeviceCredentialJournalCustody,
            sdk: LocalDevicePairingSdkOriginal, approved: LocalDevicePairingApprovedMaster): Boolean {
            val retained = native.approvedOriginal
            if (sdk.exactDeviceName != native.sdkOriginal.exactDeviceName ||
                approved.scope != retained.scope || approved.targetDeviceId != retained.targetDeviceId ||
                approved.targetKeyId != retained.targetKeyId ||
                approved.originalPairingActionId != retained.originalPairingActionId ||
                approved.originalRequestId != retained.originalRequestId || approved.rootId != retained.rootId ||
                approved.rootKeyId != retained.rootKeyId || approved.foundingActionId != retained.foundingActionId ||
                approved.rootFingerprint != retained.rootFingerprint || approved.rootDocumentDigest != retained.rootDocumentDigest ||
                approved.approvalCertificateDigest != retained.approvalCertificateDigest ||
                approved.challengeDigest != retained.challengeDigest || approved.possessionDigest != retained.possessionDigest) return false
            return sdk.identity.copyWireBytes().useWiped { actual ->
                native.sdkOriginal.identity.copyWireBytes().useWiped(actual::contentEquals)
            } && approved.targetSdkIdentity.copyWireBytes().useWiped { actual ->
                retained.targetSdkIdentity.copyWireBytes().useWiped(actual::contentEquals)
            } && approved.masterSdkOriginal.copyWireBytes().useWiped { actual ->
                retained.masterSdkOriginal.copyWireBytes().useWiped(actual::contentEquals)
            }
        }
    }
}

/** One original exact actor allocation from a genuine protected device ledger and actual
 * fresh Current. Its open claim is consumed before actor-native IO, and its final handoff
 * follows full typed intent/history/head admission. Successful INTENTS subsequently owns
 * an independent file/key/head lifetime; old RPC/target close cannot erase pending history.
 * Its immutable actor disposition is selected independently of device custody opening. */
internal class AndroidPairedDeviceActorJournalCustody private constructor(
    private val ledger: AndroidPairedDeviceActorAllocationJournal,
    private val current: AndroidPairedDeviceCurrentActor,
    val action: LocalPairedDeviceCredentialJournalAction,
    val original: PairedDeviceActorAllocationRecord,
) {
    val compositionProfile: com.ukkera.pos.security.LocalCompositionSdkProfileV1
        get() = ledger.nativeOriginal.approvedOriginal.masterSdkOriginal.compositionProfile
    val identity: LocalClientIdentity get() = current.identity
    val journalId: DomainId get() = original.journalId
    val keyId: DomainId get() = original.keyId
    val protection: SelfOwnedKeyProtectionV1 get() = ledger.nativeOriginal.protection
    val bindingCommitment: String get() = original.bindingCommitment
    val deviceBindingCommitment: String get() = ledger.nativeOriginal.bindingCommitment
    private enum class Phase { ISSUED, OPEN_STARTED, HANDED_OFF, RETIRED }
    private val gate = Any()
    private var phase = Phase.ISSUED
    private val validations = mutableSetOf<NativeValidation>()
    private var closure: CompletableDeferred<Unit>? = null

    fun belongsTo(application: Application): Boolean = ledger.nativeOriginal.belongsTo(application)

    fun isCurrent(): Boolean = synchronized(gate) { phase == Phase.ISSUED || phase == Phase.OPEN_STARTED } &&
        ledger.isCurrent() && current.belongsTo(ledger.nativeOriginal) && current.isCurrent() &&
        synchronized(gate) { phase == Phase.ISSUED || phase == Phase.OPEN_STARTED }

    fun originalOpenClaimed(): Boolean = synchronized(gate) { phase == Phase.OPEN_STARTED } && isCurrent()

    suspend fun claimOriginalJournalOpen(application: Application): Boolean {
        currentCoroutineContext().ensureActive()
        if (!belongsTo(application) || !isCurrent()) return false
        val validation = synchronized(gate) {
            if (phase != Phase.ISSUED) return false
            phase = Phase.OPEN_STARTED
            NativeValidation().also { validations.add(it) }
        }
        return validateOriginal(validation) && originalOpenClaimed()
    }

    suspend fun finishNativeHandoff(application: Application): Boolean {
        currentCoroutineContext().ensureActive()
        if (!belongsTo(application) || !originalOpenClaimed()) return false
        val validation = synchronized(gate) {
            if (phase != Phase.OPEN_STARTED) return false
            NativeValidation().also { validations.add(it) }
        }
        if (!validateOriginal(validation) || !current.isCurrent() || !ledger.isCurrent()) return false
        return synchronized(gate) {
            if (phase != Phase.OPEN_STARTED) false else { phase = Phase.HANDED_OFF; true }
        }
    }

    private suspend fun validateOriginal(validation: NativeValidation): Boolean =
        ledger.recheckOriginal(original, validation) && current.isCurrent()

    fun denyUndelivered() { synchronized(gate) { phase = Phase.RETIRED } }

    /** Drain captured qualification work only. The root separately retains/closes the
     * actual actor and parent native owners; unknown close is never retried or replaced. */
    suspend fun close(): Unit = withContext(NonCancellable) {
        val result: CompletableDeferred<Unit>
        val retained: List<NativeValidation>
        val ownsClose: Boolean
        synchronized(gate) {
            val existing = closure
            if (existing != null) { result = existing; retained = emptyList(); ownsClose = false }
            else {
                phase = Phase.RETIRED
                result = CompletableDeferred()
                closure = result; retained = validations.toList(); ownsClose = true
            }
        }
        if (ownsClose) {
            try {
                retained.forEach { it.settled.await() }
                synchronized(gate) { check(validations.isEmpty()) }
                result.complete(Unit)
            } catch (failure: Throwable) { result.completeExceptionally(failure) }
        }
        result.await()
    }

    internal inner class NativeValidation {
        val settled = CompletableDeferred<Unit>()
        @Volatile var prepared: Boolean = false
        fun finish() {
            synchronized(gate) { validations.remove(this) }
            settled.complete(Unit)
        }
    }

    override fun toString(): String = "AndroidPairedDeviceActorJournalCustody([REDACTED])"

    companion object {
        fun issue(ledger: AndroidPairedDeviceActorAllocationJournal, current: AndroidPairedDeviceCurrentActor,
            action: LocalPairedDeviceCredentialJournalAction, record: PairedDeviceActorAllocationRecord):
            AndroidPairedDeviceActorJournalCustody {
            check(ledger.isCurrent() && current.belongsTo(ledger.nativeOriginal) && current.isCurrent() &&
                current.identity.actorId == record.actorId)
            return AndroidPairedDeviceActorJournalCustody(ledger, current, action, record)
        }
    }
}
