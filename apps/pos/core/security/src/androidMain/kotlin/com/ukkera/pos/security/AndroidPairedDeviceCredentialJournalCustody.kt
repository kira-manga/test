package com.ukkera.pos.security

import android.app.Application
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * One real protected acquisition from the exact approved original target. Only that target's
 * native worker constructs this final capability, after its actual key, C/P/root signatures,
 * original SDK association and append-only storage allocation have been revalidated.
 * Public identity/commitment fields are DATA, not constructors or live RPC authority.
 *
 * The target owns its native source resources. This capability owns only its original
 * acquisition/validation work. Successful encrypted-journal admission establishes a separate
 * actual AES/key-head/file owner BEFORE the target may be transferred/closed. Currentness here
 * is acquisition-only and deliberately ends at handoff; it is not storage/session currentness.
 */
public class AndroidPairedDeviceCredentialJournalCustody internal constructor(
    private val application: Application,
    private val target: AndroidDevicePairingTarget,
    public val action: LocalPairedDeviceCredentialJournalAction,
    public val approvedOriginal: LocalDevicePairingApprovedMaster,
    public val sdkOriginal: LocalDevicePairingSdkOriginal,
    internal val originalRecord: NativePairedDeviceCredentialJournalRecord,
    private val issueSettled: CompletableDeferred<Unit>,
) {
    public val scope: BranchScope get() = approvedOriginal.scope
    public val deviceId: DomainId get() = approvedOriginal.targetDeviceId
    public val targetKeyId: DomainId get() = approvedOriginal.targetKeyId
    public val originalPairingActionId: DomainId get() = approvedOriginal.originalPairingActionId
    public val journalId: DomainId get() = originalRecord.journalId
    public val keyId: DomainId get() = originalRecord.keyId
    public val protection: SelfOwnedKeyProtectionV1 get() = originalRecord.protection
    public val bindingCommitment: String get() = originalRecord.bindingDigest.value

    private enum class Phase { ISSUED, OPEN_STARTED, HANDED_OFF, RETIRED }
    private val gate = Any()
    private var phase = Phase.ISSUED
    private var actorAllocationOpenStarted = false
    private val validations = mutableSetOf<NativeValidation>()
    private var closure: CompletableDeferred<Unit>? = null

    /** Exact actual Application/source reference, not matching package/scope scalar DATA. */
    public fun belongsTo(application: Application): Boolean = this.application === application

    /** End-only acquisition gate. The protected native readback is rechecked under the
     * actual source worker before open and again after complete encrypted-journal admission. */
    public fun isCurrent(): Boolean {
        if (!synchronized(gate) { phase == Phase.ISSUED || phase == Phase.OPEN_STARTED } ||
            !target.credentialJournalCustodyCurrent(this)) return false
        return synchronized(gate) { phase == Phase.ISSUED || phase == Phase.OPEN_STARTED }
    }

    public fun originalOpenClaimed(): Boolean {
        if (!synchronized(gate) { phase == Phase.OPEN_STARTED } ||
            !target.credentialJournalCustodyCurrent(this)) return false
        return synchronized(gate) { phase == Phase.OPEN_STARTED }
    }

    /** The ONE original open is consumed before any client-key/file IO. A failed/abandoned
     * attempt cannot be claimed again or changed from existing-only to enrollment. */
    public suspend fun claimOriginalJournalOpen(application: Application): Boolean {
        currentCoroutineContext().ensureActive()
        if (!belongsTo(application) || !target.credentialJournalCustodyCurrent(this)) return false
        val validation = synchronized(gate) {
            if (phase != Phase.ISSUED) return false
            phase = Phase.OPEN_STARTED
            NativeValidation().also { validations.add(it) }
        }
        return target.revalidateCredentialJournalCustody(this, validation)
    }

    /** A separate ONE original actor-allocation-ledger open, while this actual source is
     * still held before transfer. This is allocation metadata custody, not an actor, financial
     * journal or enrollment inference from missing files. Failure consumes the same claim. */
    public suspend fun claimOriginalActorAllocationJournalOpen(application: Application): Boolean {
        currentCoroutineContext().ensureActive()
        if (!belongsTo(application) || !target.credentialJournalCustodyCurrent(this)) return false
        val validation = synchronized(gate) {
            if (phase != Phase.OPEN_STARTED || actorAllocationOpenStarted) return false
            actorAllocationOpenStarted = true
            NativeValidation().also { validations.add(it) }
        }
        return target.revalidateCredentialJournalCustody(this, validation)
    }

    /** Acquisition-only; a successfully handed-off ledger subsequently owns its own actual
     * AES/file/head lifetime, independent of the transferred target's denial and close. */
    public fun originalActorAllocationOpenClaimed(): Boolean {
        if (!synchronized(gate) { phase == Phase.OPEN_STARTED && actorAllocationOpenStarted } ||
            !target.credentialJournalCustodyCurrent(this)) return false
        return synchronized(gate) { phase == Phase.OPEN_STARTED && actorAllocationOpenStarted }
    }

    /** Final protected source recheck and withdrawal of this acquisition gate. This does
     * not prove a journal exists and cannot construct one: only the private fully validated
     * journal factory may publish its independently retained native owner afterwards. */
    public suspend fun finishNativeHandoff(application: Application): Boolean {
        currentCoroutineContext().ensureActive()
        if (!belongsTo(application) || !target.credentialJournalCustodyCurrent(this)) return false
        val validation = synchronized(gate) {
            if (phase != Phase.OPEN_STARTED) return false
            NativeValidation().also { validations.add(it) }
        }
        if (!target.revalidateCredentialJournalCustody(this, validation)) return false
        val sourceCurrent = target.credentialJournalCustodyCurrent(this)
        return synchronized(gate) {
            if (phase != Phase.OPEN_STARTED || !sourceCurrent) false
            else { phase = Phase.HANDED_OFF; true }
        }
    }

    internal fun denyUndelivered() { synchronized(gate) { phase = Phase.RETIRED } }

    /** Retire/drain only this capability. Never close the root/RPC-owned target or the
     * independently acquired journal. Root retains both actual resource owners separately. */
    public suspend fun close(): Unit = withContext(NonCancellable) {
        val result: CompletableDeferred<Unit>
        val retained: List<NativeValidation>
        val ownsClose: Boolean
        synchronized(gate) {
            val existing = closure
            if (existing != null) { result = existing; retained = emptyList(); ownsClose = false }
            else {
                phase = Phase.RETIRED
                result = CompletableDeferred()
                closure = result
                retained = validations.toList()
                ownsClose = true
            }
        }
        if (ownsClose) {
            try {
                issueSettled.await()
                for (validation in retained) validation.settled.await()
                synchronized(gate) { check(validations.isEmpty()) }
                result.complete(Unit)
            } catch (failure: Throwable) {
                // Unknown drainage remains the same terminal failure; no replacement claim.
                result.completeExceptionally(failure)
            }
        }
        result.await()
    }

    internal inner class NativeValidation {
        val settled = CompletableDeferred<Unit>()
        @Volatile var prepared = false
        fun finish() {
            synchronized(gate) { validations.remove(this) }
            settled.complete(Unit)
        }
    }

    override fun toString(): String = "AndroidPairedDeviceCredentialJournalCustody([REDACTED])"
}

/** Local append-only allocation DATA. It is neither remote approval nor an accepting head.
 * Existing-only acquisition requires this original AND the actual native AES/head/file state. */
internal data class NativePairedDeviceCredentialJournalRecord(
    val originalPairingActionId: DomainId,
    val bindingDigest: PayloadDigest,
    val journalId: DomainId,
    val keyId: DomainId,
    val protection: SelfOwnedKeyProtectionV1,
) {
    init { require(journalId != keyId) }
}

internal object NativePairedDeviceCredentialJournalWire {
    const val MAXIMUM_RECORD_BYTES = 2048
    private const val DOMAIN = "native-paired-device-credential-allocation/v1"
    private const val PROFILE = "EXACT_APPROVED_ORIGINAL_FIRST_OPEN_NO_COHOST_OR_MISSING_HISTORY_REPLACEMENT"

    fun record(value: NativePairedDeviceCredentialJournalRecord): ByteArray = selfOwnedEncode(DOMAIN, MAXIMUM_RECORD_BYTES) {
        string(PROFILE); id(value.originalPairingActionId); string(value.bindingDigest.value)
        id(value.journalId); id(value.keyId); int(value.protection.ordinal + 1)
    }

    fun decodeRecord(bytes: ByteArray): NativePairedDeviceCredentialJournalRecord = selfOwnedDecode(bytes, DOMAIN) {
        require(string(128) == PROFILE)
        val action = id(); val binding = PayloadDigest(string(64)); val journal = id(); val key = id()
        val protection = SelfOwnedKeyProtectionV1.entries.getOrNull(int() - 1) ?: error("paired.credentials.protection")
        NativePairedDeviceCredentialJournalRecord(action, binding, journal, key, protection)
    }.also { value ->
        val canonical = record(value)
        try { require(bytes.contentEquals(canonical)) } finally { canonical.fill(0) }
    }

    fun allocation(approved: LocalDevicePairingApprovedMaster, sdk: LocalDevicePairingSdkOriginal,
        nativeIdentityDigest: PayloadDigest, nativeReadyDigest: PayloadDigest,
        protection: SelfOwnedKeyProtectionV1): NativePairedDeviceCredentialJournalRecord {
        val name = sdk.exactDeviceName.encodeToByteArray(throwOnInvalidSequence = true)
        val nameDigest = try { sha256(name) } finally { name.fill(0) }
        val binding = selfOwnedEncode("native-paired-device-credential-original-binding/v1", 4096) {
            string(PROFILE)
            id(approved.scope.organizationId); id(approved.scope.companyId); id(approved.scope.branchId)
            id(approved.targetDeviceId); id(approved.targetKeyId)
            id(approved.originalPairingActionId); id(approved.originalRequestId)
            id(sdk.identity.originalPreparationId); id(sdk.identity.originalAcquisitionId)
            string(sdk.identity.descriptorDigest.value); string(nameDigest.value)
            id(approved.rootId); id(approved.rootKeyId); id(approved.foundingActionId)
            string(approved.rootFingerprint); string(approved.rootDocumentDigest.value)
            string(approved.approvalCertificateDigest.value); string(approved.challengeDigest.value)
            string(approved.possessionDigest.value); string(nativeIdentityDigest.value); string(nativeReadyDigest.value)
            int(protection.ordinal + 1)
        }
        val commitment = try { sha256(binding) } finally { binding.fill(0) }
        return NativePairedDeviceCredentialJournalRecord(approved.originalPairingActionId, commitment,
            id(commitment, "journal"), id(commitment, "key"), protection)
    }

    private fun id(binding: PayloadDigest, purpose: String): DomainId {
        val input = selfOwnedEncode("native-paired-device-credential-id/v1", 512) {
            string(binding.value); string(purpose)
        }
        val hex = try { sha256(input).value.take(32) } finally { input.fill(0) }
        return DomainId("${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
    }
}
