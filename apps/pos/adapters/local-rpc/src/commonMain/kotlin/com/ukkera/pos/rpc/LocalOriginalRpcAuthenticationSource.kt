package com.ukkera.pos.rpc

import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import kotlinx.coroutines.flow.StateFlow

/**
 * The retained selected-original Client RPC owner as seen by actor-intent acquisition: the exact
 * selected original, its approved Master and a fresh authentication service. Implementations are
 * the real shared owner and its platform wrappers only; observations here never grant authority.
 */
public interface LocalOriginalRpcAuthenticationSource {
    public val original: StateFlow<LocalDevicePairingSdkOriginal?>
    public val approvedMaster: StateFlow<LocalDevicePairingApprovedMaster?>
    public suspend fun authenticationService(): LocalRpcRemoteAuthenticationService
}
