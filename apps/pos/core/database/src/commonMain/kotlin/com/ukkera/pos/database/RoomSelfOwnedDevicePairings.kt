package com.ukkera.pos.database

import androidx.room3.Transactor
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Isolated READ bridge on the ACTUAL original Room owner/DAO/native protection key.
 * No second security store/source/authority, admission effect, password session or endpoint.
 * The existing native writer loan still owns outer return, Room task drain and no-child fate;
 * a caught inner failure is not settlement. Only DATA escapes a zero-write transaction. */
internal class RoomSelfOwnedDevicePairings(
    private val owner: RoomRuntimeHistoryOwner,
    private val custody: SelfOwnedNativeHistoryCustody,
    val native: LocalCompanyDevicePairings,
) {
    private val actualSecurity = custody.runtimeSecurity
    init {
        check(owner.database.runtimeHistoryOwner === owner && native.belongsTo(custody) &&
            actualSecurity.storageKey.keyId == owner.originalRecord.record.allocation.coordinates.storageKeyId)
    }

    suspend fun prepare(requestId: DomainId, target: LocalDevicePairingTargetDescriptor,
        sdkIdentity: LocalDevicePairingSdkIdentityV2? = null):
        LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        currentCoroutineContext().ensureActive()
        check(owner.operational && target.admission.scope == owner.originalScope)
        val master = if (sdkIdentity == null) null else {
            val record = owner.originalRecord.record
            check(record.recordFormat == SelfOwnedNativeHistoryWireV2.MASTER_SDK_ORIGINAL_RECORD_FORMAT)
            checkNotNull(record.masterSdkOriginal)
        }
        val requested = readProtected { transaction ->
            val serviceAdmission = ProtectedLocalSecurityCodec.encodeAdmission(target.admission)
            try {
                val census = qualifyAbsent(transaction, requestId, target, sdkIdentity)
                captureDefinition(requestId, target, serviceAdmission, census, sdkIdentity, master)
            } finally { serviceAdmission.fill(0) }
        }
        // This is the ACTUAL post-no-child/outer/task-drain settled original, not a caller cut.
        val original = owner.originalRecord
        check(owner.operational && original.record.pending == null)
        return native.prepare(requested, original)
    }

    suspend fun proveTarget(action: LocalDevicePairingAction, possession: LocalDevicePairingPossession):
        LocalCompanyAuthorityResult<LocalDevicePairingAction> {
        qualifyRetained(action.requested)
        val original = owner.originalRecord
        check(owner.operational && original.record.pending == null)
        return native.proveTarget(action, possession, original)
    }

    /** Fresh one-action explicit head consent AFTER the full current protected read.
     * Credential/throttle/root work runs only after the actual Room loan has finished. */
    suspend fun approve(action: LocalDevicePairingAction, headLogin: String, secret: CharArray):
        LocalCompanyAuthorityResult<LocalDevicePairingApproval> {
        try {
            qualifyRetained(action.requested)
            check(owner.operational && owner.originalRecord.record.pending == null)
            return native.approve(action, headLogin, secret)
        } finally { secret.fill('\u0000') }
    }

    suspend fun retireKnownUnsigned(id: DomainId): LocalCompanyAuthorityResult<LocalDevicePairingUnsignedRetirement> {
        val reviewed = native.review(id)
        if (reviewed !is LocalCompanyAuthorityResult.Value) return when (reviewed) {
            is LocalCompanyAuthorityResult.Unavailable -> reviewed
            else -> error("Unreachable original review result")
        }
        check(reviewed.value.requested.request.scope == owner.originalScope)
        // Terminal DATA records only the old original's no-signature fate. Do not require
        // an unchanged historical census/request-absence cut or cancel a current row.
        // A later fresh preparation independently performs its actual current census.
        readProtected { transaction ->
            transaction.ensureAuthorizationHealthy(); transaction.requireAdministrationWriteCount(0)
        }
        check(owner.operational && owner.originalRecord.record.pending == null)
        return native.retireKnownUnsigned(id)
    }

    private suspend fun qualifyRetained(requested: LocalDevicePairingDefinition) {
        currentCoroutineContext().ensureActive()
        check(owner.operational && requested.request.scope == owner.originalScope)
        readProtected { transaction ->
            val census = qualifyAbsent(transaction, requested.request.requestId, requested.target, requested.sdkIdentity)
            val service = ProtectedLocalSecurityCodec.encodeAdmission(requested.admission)
            val retainedService = requested.copyCanonicalServiceAdmission()
            try {
                if (!service.contentEquals(retainedService)) transaction.refuse()
                val captured = captureDefinition(requested.request.requestId, requested.target, service, census,
                    requested.sdkIdentity, requested.masterSdkOriginal)
                val fresh = captured.copyWireBytes(); val retained = requested.copyWireBytes()
                try { if (!fresh.contentEquals(retained)) transaction.refuse() }
                finally { fresh.fill(0); retained.fill(0) }
            } finally { service.fill(0); retainedService.fill(0) }
        }
    }

    private suspend fun qualifyAbsent(transaction: RoomLocalSecurityTransaction, requestId: DomainId,
        target: LocalDevicePairingTargetDescriptor, sdkIdentity: LocalDevicePairingSdkIdentityV2?): List<LocalDeviceAdmission> {
        val scope = owner.originalScope; val admission = target.admission
        if (admission.scope != scope || admission.deviceId == owner.originalAuthority.deviceId ||
            transaction.readAuthEvent(scope, requestId) != null || transaction.readAdmission(scope, admission.deviceId) != null)
            transaction.refuse()
        val census = transaction.readAdmissions(scope).sortedBy { it.deviceId.value }
        val references = setOf(requestId, admission.deviceId, target.keyId) + sdkIdentity?.let {
            setOf(it.originalAcquisitionId, it.originalPreparationId) }.orEmpty()
        if (census.any { it.deviceId in references ||
                it.publicKeyIdentifier == admission.publicKeyIdentifier || it.publicFingerprint == admission.publicFingerprint })
            transaction.refuse()
        transaction.ensureAuthorizationHealthy()
        transaction.requireAdministrationWriteCount(0)
        return census
    }

    /** Preserve the original profile and complete association on every protected recapture.
     * Descriptor DATA does not replace actual target possession or fresh head approval. */
    private fun captureDefinition(requestId: DomainId, target: LocalDevicePairingTargetDescriptor,
        serviceAdmission: ByteArray, census: List<LocalDeviceAdmission>, sdkIdentity: LocalDevicePairingSdkIdentityV2?,
        masterSdkOriginal: LocalMasterSdkOriginal?): LocalDevicePairingDefinition = if (sdkIdentity == null) {
            check(masterSdkOriginal == null)
            LocalDevicePairingDefinition.capture(requestId, target, serviceAdmission, census)
        } else if (masterSdkOriginal == null)
            LocalDevicePairingDefinition.captureSdkBound(requestId, target, serviceAdmission, census, sdkIdentity)
        else LocalDevicePairingDefinition.captureMasterBound(requestId, target, serviceAdmission, census, sdkIdentity, masterSdkOriginal)

    /** Same production transaction/codec boundary, with no store or accepting source creation.
     * Every entrance/finalization/close is joined to the registered original history frame. */
    private suspend fun <T> readProtected(block: suspend (RoomLocalSecurityTransaction) -> T): T {
        currentCoroutineContext().ensureActive()
        check(owner.operational && owner.database.runtimeHistoryOwner === owner && native.belongsTo(custody))
        val database = owner.database
        val value = localSecurityWriter(database) { connection ->
            requireDurableWriter(connection)
            currentCoroutineContext().ensureActive()
            try { connection.withTransaction<RoomLocalSecurityOutcome<T>>(Transactor.SQLiteTransactionType.IMMEDIATE) {
                val history = owner.frameFor(connection)
                history.enterTransaction(currentCoroutineContext())
                val transaction = RoomLocalSecurityTransaction(database, connection, database.localSecurity(),
                    actualSecurity.protection, actualSecurity.storageKey)
                history.bindSecurity(transaction)
                try {
                    val result = localSecurityBody {
                        transaction.ensureCurrent(); transaction.requireAdministrationWriteCount(0)
                        val captured = block(transaction)
                        transaction.requireAdministrationWriteCount(0)
                        transaction.finishNormalReturn()
                        captured
                    }
                    if (result !is RoomLocalSecurityOutcome.Value) rollback(result)
                    result
                } finally { transaction.close() }
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { localStorageUncertain() }
        }
        currentCoroutineContext().ensureActive()
        check(owner.operational && owner.originalRecord.record.pending == null)
        return value.valueOrThrow()
    }
}
