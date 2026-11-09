package com.ukkera.pos.security.independent.rpcfactoryidentity

import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.independent.sdkassociationv1.captureOriginalIdentity
import com.ukkera.pos.security.independent.sdkassociationv1.identityFacts
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.PeerId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * RFIR-DATA-01 from qa/pos/designs/rpc-factory-identity-readback, frozen v1.
 * Inputs are the unchanged F-DATA-ORIGINAL/F-TRANSPORT-MUTATIONS recipes.
 * The existing association helper supplies inert synthetic context DATA only;
 * no actual Kit, native custody, admission, LAN or factory readback is asserted.
 */
public class RpcFactoryIdentityDataSlicesTest {
    @Test
    public fun rfirData01ExactDecodedTupleCloneMatchesAndPreservesOriginal(): Unit {
        assertFrozenComparison(FrozenRfirData01.original.copy(), expected = true)
    }

    @Test
    public fun rfirData01ChangedAppIdAloneRejectsAndPreservesOriginal(): Unit {
        assertFrozenMutation("appId")
    }

    @Test
    public fun rfirData01ChangedPeerIdAloneRejectsAndPreservesOriginal(): Unit {
        assertFrozenMutation("peerId")
    }

    @Test
    public fun rfirData01ChangedFingerprintAloneRejectsAndPreservesOriginal(): Unit {
        assertFrozenMutation("fingerprint")
    }

    @Test
    public fun rfirData01ChangedPairingQrAloneRejectsAndPreservesOriginal(): Unit {
        assertFrozenMutation("pairingQr")
    }

    private fun assertFrozenMutation(field: String) {
        val original = FrozenRfirData01.original
        val candidate = FrozenRfirData01.mutation(field)
        val differences = original.fields().keys.filter { name ->
            original.fields().getValue(name) != candidate.fields().getValue(name)
        }
        assertEquals(listOf(field), differences, "Only the frozen selected field changes")
        assertFrozenComparison(candidate, expected = false)
    }

    private fun assertFrozenComparison(candidate: TransportTuple, expected: Boolean) {
        val input = FrozenRfirData01.original
        val captured = captureOriginalIdentity(
            appId = input.appId,
            peerId = input.peerId,
            fingerprint = input.fingerprint,
            qr = input.pairingQr,
        )
        val capturedBytes = captured.copyWireBytes()
        val original = LocalDevicePairingSdkIdentityV2.decode(capturedBytes)
        assertContentEquals(capturedBytes, original.copyWireBytes())
        assertEquals(input, TransportTuple.read(original))

        val originalBytes = original.copyWireBytes()
        val originalFields = identityFacts(original)
        val originalDigest = original.descriptorDigest
        val observed = original.matchesSdkTransportIdentity(
            actualAppId = AppId(candidate.appId),
            actualPeerId = PeerId(candidate.peerId),
            actualFingerprint = PeerFingerprint(candidate.fingerprint),
            actualPairingQr = candidate.pairingQr,
        )

        assertEquals(expected, observed, "Frozen RFIR-DATA-01 original correspondence")
        assertEquals(originalFields, identityFacts(original), "Retained synthetic fields stay exact")
        assertContentEquals(originalBytes, original.copyWireBytes(), "Retained V2 bytes stay exact")
        assertEquals(originalDigest, original.descriptorDigest, "Retained digest stays exact")
    }
}

private data class TransportTuple(
    val appId: String,
    val peerId: String,
    val fingerprint: String,
    val pairingQr: String,
) {
    fun fields(): Map<String, String> = linkedMapOf(
        "appId" to appId,
        "peerId" to peerId,
        "fingerprint" to fingerprint,
        "pairingQr" to pairingQr,
    )

    companion object {
        fun read(value: LocalDevicePairingSdkIdentityV2): TransportTuple = TransportTuple(
            value.appId.value,
            value.peerId.value,
            value.fingerprint.value,
            value.pairingQr,
        )
    }
}

/** Literal expansion of the frozen recipes; these are syntax vectors, not real key identities. */
private object FrozenRfirData01 {
    private val zero32Bytes = "a".repeat(52)
    private val lastBitOne32Bytes = "a".repeat(51) + "q"
    val original: TransportTuple = TransportTuple(
        appId = "pos.synthetic.rpc.readback",
        peerId = "p2id2-" + zero32Bytes,
        fingerprint = "p2f1-" + zero32Bytes,
        pairingQr = "p2pkit:v2:p2a1-" + zero32Bytes + ":p2f1-" + zero32Bytes,
    )

    fun mutation(field: String): TransportTuple = when (field) {
        "appId" -> original.copy(appId = "pos.synthetic.rpc.readback.other")
        "peerId" -> original.copy(peerId = "p2id2-" + lastBitOne32Bytes)
        "fingerprint" -> original.copy(fingerprint = "p2f1-" + lastBitOne32Bytes)
        "pairingQr" -> original.copy(
            pairingQr = "p2pkit:v2:p2a1-" + lastBitOne32Bytes + ":p2f1-" + zero32Bytes,
        )
        else -> error("No frozen RFIR-DATA-01 mutation for $field")
    }
}
