package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Closed original-cohost bridge on the SAME protected store/source/credential service,
 * endpoint, native owner and sole writer. Approval DATA is never converted by a callback,
 * generic provisioning provider or a second Room/native composition. */
internal class RoomSelfOwnedDeviceAdmissions(
    private val owner: RoomRuntimeHistoryOwner,
    private val store: RoomLocalSecurityStore,
    private val source: RoomCurrentLocalAuthorizationSource,
    val administration: RoomLocalCredentialAdministration,
    val native: LocalCompanyDevicePairings,
    private val endpoint: LocalMasterEndpoint,
) {
    init {
        check(store.database === owner.database && source.belongsTo(store) && native.belongsTo(owner.originalCustody))
    }

    suspend fun admit(approval: LocalDevicePairingApproval): LocalCredentialAdministrationResult {
        currentCoroutineContext().ensureActive()
        if (!owner.operational || approval.requested.request.scope != owner.originalScope ||
            approval.requested.sdkIdentity == null || approval.requested.admission.revision != Revision(0))
            return LocalCredentialAdministrationResult.Denied
        // Recover the exact retained approval DATA, never a new signature/deadline/action.
        val retained = native.recoverApproval(approval.originalActionId)
        if (retained !is LocalCompanyAuthorityResult.Value) return LocalCredentialAdministrationResult.Unavailable
        if (retained.value.authorityDigest != approval.authorityDigest ||
            retained.value.requested.definitionDigest != approval.requested.definitionDigest)
            return LocalCredentialAdministrationResult.Denied

        // An already completed original is a read-only retry, not another dispatch.
        val terminal = native.lookupOriginalDeviceAdmission(approval.originalActionId)
        if (terminal != null) {
            if (terminal.requested.definitionDigest != retained.value.requested.definitionDigest)
                return LocalCredentialAdministrationResult.Unavailable
            return when (val status = endpoint.lookupOriginalDeviceAdmission(this, approval.originalActionId)) {
                is LocalProvisioningStatus.Applied -> LocalCredentialAdministrationResult.Applied(status.eventId)
                LocalProvisioningStatus.NotRecorded, LocalProvisioningStatus.Denied -> LocalCredentialAdministrationResult.Denied
                LocalProvisioningStatus.Busy -> LocalCredentialAdministrationResult.Busy
                LocalProvisioningStatus.StorageUncertain -> LocalCredentialAdministrationResult.StorageUncertain
                LocalProvisioningStatus.Unavailable -> LocalCredentialAdministrationResult.Unavailable
            }
        }

        qualifyAbsent(retained.value)
        check(owner.operational && owner.originalRecord.record.pending == null)
        // This follows the actual no-child/outer/native settlement of the preceding read.
        // Native independently requires its full original approval, head, cut and census.
        val dispatch = native.beginDeviceAdmission(approval.originalActionId)
        check(dispatch.requested.definitionDigest == retained.value.requested.definitionDigest)
        val result = endpoint.admitOriginalDevice(this, dispatch, retained.value.requested.admission)
        // The endpoint waits for THIS worker's actual cleanup, not its watchdog reply.
        // Native alone classifies an unused/rollback no-effect; missing evidence stays held.
        if (result !is LocalCredentialAdministrationResult.Applied) native.finishKnownNoEffect(dispatch)
        return result
    }

    suspend fun lookup(originalActionId: DomainId): LocalProvisioningStatus =
        endpoint.lookupOriginalDeviceAdmission(this, originalActionId)

    private suspend fun qualifyAbsent(approval: LocalDevicePairingApproval) {
        val requested = approval.requested
        store.transaction { transaction ->
            val scope = owner.originalScope; val admission = requested.admission
            val sdk = requested.sdkIdentity ?: transaction.refuse()
            if (!admission.admitted || admission.revision != Revision(0) || admission.deviceId == owner.originalAuthority.deviceId ||
                transaction.readAdmission(scope, admission.deviceId) != null) transaction.refuse()
            val references = setOf(requested.request.requestId, admission.deviceId, requested.target.keyId, approval.originalActionId,
                sdk.originalAcquisitionId, sdk.originalPreparationId)
            for (id in references) if (transaction.readAuthEvent(scope, id) != null) transaction.refuse()
            val census = transaction.readAdmissions(scope).sortedBy { it.deviceId.value }
            if (census.isEmpty() || census.size >= 256 || census.any { it.deviceId in references ||
                    it.publicKeyIdentifier == admission.publicKeyIdentifier || it.publicFingerprint == admission.publicFingerprint })
                transaction.refuse()
            val service = ProtectedLocalSecurityCodec.encodeAdmission(admission)
            val retainedService = requested.copyCanonicalServiceAdmission()
            try {
                if (!service.contentEquals(retainedService)) transaction.refuse()
                val captured = requested.masterSdkOriginal?.let { master ->
                    LocalDevicePairingDefinition.captureMasterBound(requested.request.requestId, requested.target,
                        service, census, sdk, master)
                } ?: LocalDevicePairingDefinition.captureSdkBound(requested.request.requestId, requested.target, service, census, sdk)
                val actualDefinition = captured.copyWireBytes(); val retainedDefinition = requested.copyWireBytes()
                try { if (!actualDefinition.contentEquals(retainedDefinition)) transaction.refuse() }
                finally { actualDefinition.fill(0); retainedDefinition.fill(0) }
            } finally { service.fill(0); retainedService.fill(0) }
            val accountIds = mutableSetOf<DomainId>()
            transaction.forEachAccount(scope) { account ->
                val actor = account.state.actorId
                if (!accountIds.add(actor) || actor in references || transaction.readThrottle(
                        ThrottleKey(scope, actor, ThrottleDimension.Device(admission.deviceId))) != null) transaction.refuse()
            }
            if (owner.originalRecord.record.allocation.coordinates.firstActorId !in accountIds) transaction.refuse()
            transaction.ensureAuthorizationHealthy(); transaction.requireAdministrationWriteCount(0)
        }
    }
}
