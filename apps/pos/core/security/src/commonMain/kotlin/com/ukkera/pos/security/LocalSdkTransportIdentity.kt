package com.ukkera.pos.security

import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId
import dev.p2pkit.core.PeerPairingQr

/** Canonical authenticated-v2 public syntax only; never key possession or authorization. */
internal fun requireAuthenticatedSdkTransportText(peerId: PeerId, fingerprint: PeerFingerprint, pairingQr: String) {
    val peerText = peerId.value
    require(peerText.length == 58 && peerText.startsWith("p2id2-") &&
        peerText.substring(6).all { it in 'a'..'z' || it in '2'..'7' } &&
        peerText.last() in "aq")
    val qr = PeerPairingQr.parse(pairingQr)
    require(qr.fingerprint == fingerprint && qr.encode() == pairingQr)
}
