package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerIdentity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Original native admission and its separately associated SDK identity, published only after
 * the same Master's complete protected census and native receipt have settled. Native key
 * descriptors are preserved unchanged; the SDK fingerprint describes a different key.
 * This is not transport evidence, employee authority or a protected-operation permit.
 */
public class LocalMasterRpcPeerAssociation internal constructor(
    public val admission: LocalDeviceAdmission,
    public val sdkIdentity: LocalDevicePairingSdkIdentityV2,
    private val owner: RoomRuntimeHistoryOwner,
    private val native: LocalCompanyDevicePairings,
    private val originalReceipt: LocalDeviceAdmissionReceiptPermit,
) {
    /** Exact original owner/receipt lifetime only; current grants still require the Master. */
    public fun isCurrent(): Boolean = owner.operational && native.belongsTo(owner.originalCustody) &&
        originalReceipt.isCurrent()

    internal fun belongsTo(original: RoomRuntimeHistoryOwner): Boolean = owner === original &&
        native.belongsTo(original.originalCustody)

    override fun toString(): String = "LocalMasterRpcPeerAssociation([REDACTED])"
}

/**
 * Host-client transport pins from this exact Master's protected, settled admission state.
 * There is no public constructor, supplied census, approval callback or separate trust database.
 * This is not employee authorization, a SelectedHosts store or proof of a live connection.
 */
public class LocalMasterRpcTrust internal constructor(private val controller: LocalMasterRpcTrustController) {
    public suspend fun loadCurrentHostClients(exactAppId: AppId): Set<PeerFingerprint> =
        controller.readCurrent(exactAppId, expected = null)

    /** Only an idempotent assertion of the already durably authorized complete set.
     * Transport APIs cannot add, withdraw or readmit a POS device by replacing this set. */
    public suspend fun assertCurrentHostClients(exactAppId: AppId, fingerprints: Set<PeerFingerprint>) {
        controller.readCurrent(exactAppId, fingerprints.toSet())
    }

    /**
     * Read one original association using the exact canonical branch AppId and SDK peer/key.
     * Missing or withdrawn membership returns null only after complete settled verification.
     * The supplied descriptor is a selector, never proof of a callback or physical lifetime.
     */
    public suspend fun readPeerAssociation(exactAppId: AppId, peer: PeerIdentity): LocalMasterRpcPeerAssociation? =
        controller.readPeerAssociation(exactAppId, peer)

    override fun toString(): String = "LocalMasterRpcTrust([REDACTED])"
}

/** Native composition must retain the actual read worker through settlement, independently
 * of an SDK caller abandoning its wait. Membership is decided by the same Room reader. */
internal interface LocalMasterRpcTrustController {
    suspend fun readCurrent(exactAppId: AppId, expected: Set<PeerFingerprint>?): Set<PeerFingerprint>
    suspend fun readPeerAssociation(exactAppId: AppId, peer: PeerIdentity): LocalMasterRpcPeerAssociation?
}

/** Shared membership rules on the sole protected writer; only its retained native service
 * worker calls this reader. It is never exported directly to an SDK caller. */
internal class RoomLocalMasterRpcTrust(
    private val owner: RoomRuntimeHistoryOwner,
    private val store: RoomLocalSecurityStore,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val native: LocalCompanyDevicePairings,
) {
    init {
        check(store.database === owner.database && source.belongsTo(store) && native.belongsTo(owner.originalCustody))
    }

    internal suspend fun readCurrent(exactAppId: AppId, expected: Set<PeerFingerprint>?): Set<PeerFingerprint> =
        readSettled(exactAppId, expected, peer = null).first

    internal suspend fun readPeerAssociation(exactAppId: AppId, peer: PeerIdentity): LocalMasterRpcPeerAssociation? {
        val receipt = readSettled(exactAppId, expected = null, peer = peer).second ?: return null
        currentCoroutineContext().ensureActive()
        check(owner.operational && native.belongsTo(owner.originalCustody) && receipt.isCurrent())
        val requested = receipt.requested
        return LocalMasterRpcPeerAssociation(requested.admission, checkNotNull(requested.sdkIdentity), owner, native, receipt)
    }

    private suspend fun readSettled(exactAppId: AppId, expected: Set<PeerFingerprint>?, peer: PeerIdentity?):
        Pair<Set<PeerFingerprint>, LocalDeviceAdmissionReceiptPermit?> {
        currentCoroutineContext().ensureActive()
        LocalDevicePairingProfileV2.validateAcquisitionText(exactAppId.value)
        check(owner.operational && native.belongsTo(owner.originalCustody))
        return owner.serialPublicationLoan { loan ->
            val before = loan.original
            check(before.record.pending == null)
            val masterOriginal = checkNotNull(before.record.masterSdkOriginal)
            check(masterOriginal.allocation.digest == before.record.allocation.digest &&
                masterOriginal.appId == owner.compositionProfile.appId(owner.originalScope) &&
                exactAppId == masterOriginal.appId)
            val observed = source.observeCurrent(currentCoroutineContext())
            val retained = when (val result = native.retained()) {
                is LocalCompanyAuthorityResult.Value -> result.value
                is LocalCompanyAuthorityResult.Unavailable -> protectedLocalUnavailable()
            }
            val originals = LinkedHashMap<DomainId, LocalDeviceAdmissionReceiptPermit>()
            val actionIds = HashSet<DomainId>()
            for (reference in retained) {
                check(actionIds.add(reference.originalActionId))
                // Null is not certified absence. Only a consumed original can contribute a
                // member; the complete protected census below must join every admission.
                val receipt = native.lookupOriginalDeviceAdmission(reference.originalActionId) ?: continue
                check(receipt.isCurrent())
                if (receipt.permitsAbsence) continue
                val requested = receipt.requested
                check(requested.request.scope == owner.originalScope && requested.request.action == LocalProvisioningAction.ADMIT_DEVICE &&
                    requested.request.targetId == requested.admission.deviceId && receipt.request == requested.request &&
                    requested.admission.scope == owner.originalScope && requested.admission.admitted &&
                    requested.admission.revision == Revision(0) && requested.sdkIdentity != null &&
                    requested.admission.deviceId != owner.originalAuthority.deviceId)
                check(originals.put(requested.admission.deviceId, receipt) == null)
            }
            val outcome = loan.writer { connection ->
                store.transactionAtWriter(connection) { transaction ->
                    transaction.requireAdministrationWriteCount(0)
                    val census = transaction.readAdmissions(owner.originalScope)
                    val byDevice = census.associateBy { it.deviceId }
                    check(byDevice.size == census.size && byDevice.keys == originals.keys + owner.originalAuthority.deviceId)
                    val cohost = checkNotNull(before.record.definition).definition.masterAdmission
                    requireSameAdmission(checkNotNull(byDevice[owner.originalAuthority.deviceId]), cohost)
                    val pins = LinkedHashSet<PeerFingerprint>()
                    var selected: LocalDeviceAdmissionReceiptPermit? = null
                    for ((device, receipt) in originals) {
                        val requested = receipt.requested
                        val event = transaction.readAuthEvent(owner.originalScope, requested.request.requestId)
                            ?: protectedLocalUnavailable()
                        val encoded = LocalAuthenticationCodec.encodeEvent(event)
                        try { check(sha256(encoded) == receipt.expectedEventDigest) } finally { encoded.fill(0) }
                        check(event.kind == LocalAuthEventKind.DEVICE_ADMITTED && event.targetId == device &&
                            event.contentDigest == requested.request.contentDigest && event.operatorId == null &&
                            event.sessionId == null && event.accountRevision == null)
                        val current = checkNotNull(byDevice[device])
                        val original = requested.admission
                        // The current native history profile supports original admission and
                        // its first withdrawal only. Later readmission/rotation is not guessed.
                        val supported = if (current.admitted) original else LocalDeviceAdmission(original.scope,
                            original.deviceId, Revision(1), false, original.publicKeyIdentifier,
                            original.publicFingerprint, original.capabilities)
                        requireSameAdmission(current, supported)
                        val sdk = checkNotNull(requested.sdkIdentity)
                        if (current.admitted && sdk.appId == exactAppId) {
                            check(pins.add(sdk.fingerprint))
                            if (peer != null && sdk.peerId == peer.peerId && sdk.fingerprint == peer.fingerprint) {
                                check(selected == null)
                                selected = receipt
                            }
                        }
                        check(receipt.isCurrent())
                    }
                    if (expected != null) check(expected == pins)
                    transaction.ensureAuthorizationHealthy()
                    transaction.requireAdministrationWriteCount(0)
                    pins.toSet() to selected
                }
            }
            // The value cannot escape before the actual outer driver/native settlement.
            val projection = outcome.valueOrThrow()
            val settled = loan.original
            val actual = owner.originalCustody.snapshotOriginal()
            check(owner.operational && native.belongsTo(owner.originalCustody) && actual.record.pending == null &&
                actual.lifetimeId == settled.lifetimeId && actual.recordDigest == settled.recordDigest &&
                actual.record.masterSdkOriginal?.descriptorDigest == masterOriginal.descriptorDigest &&
                actual.record.parent == before.record.parent && originals.values.all { it.isCurrent() })
            val final = source.observeCurrent(currentCoroutineContext())
            check(final.issuerContextId == observed.issuerContextId && final.moment.bootId == observed.moment.bootId &&
                final.moment.elapsedMillis >= observed.moment.elapsedMillis)
            currentCoroutineContext().ensureActive()
            projection
        }
    }

    private fun requireSameAdmission(actual: LocalDeviceAdmission, expected: LocalDeviceAdmission) {
        val left = ProtectedLocalSecurityCodec.encodeAdmission(actual)
        val right = ProtectedLocalSecurityCodec.encodeAdmission(expected)
        try { check(left.contentEquals(right)) } finally { left.fill(0); right.fill(0) }
    }

    override fun toString(): String = "RoomLocalMasterRpcTrust([REDACTED])"
}
