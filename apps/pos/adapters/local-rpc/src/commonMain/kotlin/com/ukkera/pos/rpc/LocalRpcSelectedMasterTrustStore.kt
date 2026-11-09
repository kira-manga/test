package com.ukkera.pos.rpc

import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingProfileV2
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcLocalIdentity
import dev.p2pkit.rpc.RpcTrustPurpose
import dev.p2pkit.rpc.RpcTrustStore

/**
 * Same-target existing-only native read/close seam. Recovery must read both the target's
 * signed SDK original/name and its qualified exact-action Master before fencing native work.
 * Close awaits that exact native target; it cannot import, sign, repair or acquire an original.
 */
internal interface LocalRpcSelectedMasterClientSource {
    suspend fun recover(): LocalRpcSelectedMasterClientReadback
    suspend fun close()
}

/**
 * Joins genuine native readback to the caller's explicit original action and the complete
 * current target SDK original. The qualified Master has no public accepting constructor;
 * decoded challenges, discovery fingerprints and supplied SDK DATA cannot substitute for it.
 * This immutable identification does not establish admission or any current employee grant.
 */
internal class LocalRpcSelectedMasterClientReadback(
    val original: LocalDevicePairingSdkOriginal,
    val approvedMaster: LocalDevicePairingApprovedMaster,
    val originalPairingActionId: DomainId,
) {
    init {
        require(approvedMaster.originalPairingActionId == originalPairingActionId) {
            "Approved Master action differs"
        }
        val sdk = original.identity
        val current = sdk.copyWireBytes()
        val selected = approvedMaster.targetSdkIdentity.copyWireBytes()
        try {
            // Covers native application, full target descriptor, both original IDs and
            // every SDK transport field. Fingerprint-only correspondence is insufficient.
            require(current.contentEquals(selected)) { "Approved Master target original differs" }
        } finally { current.fill(0); selected.fill(0) }
        val target = sdk.target
        val scope = target.admission.scope
        val master = approvedMaster.masterSdkOriginal
        require(approvedMaster.scope == scope && approvedMaster.targetDeviceId == target.admission.deviceId &&
            approvedMaster.targetKeyId == target.keyId && master.allocation.coordinates.scope == scope) {
            "Approved Master target scope differs"
        }
        val appId = master.compositionProfile.appId(scope)
        require(master.appId == appId && sdk.appId == appId) { "Approved Master AppId differs" }
        require(master.originalAcquisitionId != sdk.originalAcquisitionId &&
            master.originalAcquisitionId != sdk.originalPreparationId &&
            master.peerId != sdk.peerId && master.fingerprint != sdk.fingerprint && master.pairingQr != sdk.pairingQr) {
            "Approved Master is not distinct from its target"
        }
        LocalDevicePairingProfileV2.validateAcquisitionText(original.exactDeviceName)
    }

    fun matchesFactoryIdentity(actual: RpcLocalIdentity): Boolean =
        original.identity.matchesSdkTransportIdentity(actual.appId, actual.peerId,
            actual.fingerprint, actual.pairingQr) && actual.deviceName == original.exactDeviceName

    /** Called only under the exact retained owner's active source/factory gate. */
    fun project(appId: AppId, purpose: RpcTrustPurpose, expected: Set<PeerFingerprint>?): Set<PeerFingerprint> {
        require(appId == original.identity.appId) { "Selected Master trust AppId differs" }
        require(purpose == RpcTrustPurpose.SelectedHosts || purpose == RpcTrustPurpose.SelectedHostPreference) {
            "Only selected Master trust purposes are supported"
        }
        val pins = setOf(approvedMaster.masterSdkOriginal.fingerprint)
        require(expected == null || expected == pins) { "Selected Master trust replacement differs" }
        return pins
    }

    override fun toString(): String = "LocalRpcSelectedMasterClientReadback([REDACTED])"
}

/**
 * Fixed singleton projection of the original native approval evidence, never a second trust
 * database. Both purposes use the same singleton; preference does not grant authorization.
 * Replacements only assert the exact already retained set and perform no native write.
 * Each operation passes through the same original owner's current lifecycle/identity gate.
 * The owner exposes no raw role, networking, admission or employee-authority capability.
 */
internal class LocalRpcSelectedMasterTrustStore(
    private val owner: LocalRpcOriginalClientOwner,
    private val readback: LocalRpcSelectedMasterClientReadback,
) : RpcTrustStore {
    override suspend fun load(appId: AppId, purpose: RpcTrustPurpose): Set<PeerFingerprint> =
        owner.readSelectedMasterTrust(this, readback, appId, purpose, expected = null)

    override suspend fun replace(appId: AppId, purpose: RpcTrustPurpose, fingerprints: Set<PeerFingerprint>) {
        owner.readSelectedMasterTrust(this, readback, appId, purpose, fingerprints.toSet())
    }

    override fun toString(): String = "LocalRpcSelectedMasterTrustStore([REDACTED])"
}
