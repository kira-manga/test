package com.ukkera.pos.rpc

import com.ukkera.pos.database.LocalMasterRpcTrust
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcTrustPurpose
import dev.p2pkit.rpc.RpcTrustStore

/**
 * HostClients-only projection of the exact native Master's protected admission state.
 * The capability has no public constructor; this adapter accepts no census, approval provider,
 * independent persistence namespace or accepting default. Missing or uncertain state throws.
 * A genuinely verified empty projection is valid denial data.
 *
 * Replacement only asserts that the complete proposed set is already durably authorized. It cannot
 * admit, withdraw or readmit a POS device, and transport trust grants no employee permissions.
 * SelectedHosts and SelectedHostPreference are deliberately unsupported.
 *
 * The SDK caches its loaded pins. Composition must finish native admission/withdrawal settlement
 * and release the Master/native/Room loans before invoking SDK approval/revocation. A stale cached
 * mutation that differs from the current projection fails closed; this adapter does not reload it.
 */
public class LocalRpcTrustStore(
    private val masterTrust: LocalMasterRpcTrust,
) : RpcTrustStore {
    @Throws(Exception::class)
    public override suspend fun load(appId: AppId, purpose: RpcTrustPurpose): Set<PeerFingerprint> {
        requireHostClients(purpose)
        return masterTrust.loadCurrentHostClients(appId)
    }

    @Throws(Exception::class)
    public override suspend fun replace(
        appId: AppId,
        purpose: RpcTrustPurpose,
        fingerprints: Set<PeerFingerprint>,
    ): Unit {
        requireHostClients(purpose)
        masterTrust.assertCurrentHostClients(appId, fingerprints)
    }

    private fun requireHostClients(purpose: RpcTrustPurpose) {
        require(purpose == RpcTrustPurpose.HostClients) { "Only HostClients trust is supported" }
    }

    override fun toString(): String = "LocalRpcTrustStore([REDACTED])"
}
