package com.ukkera.pos.security.independent.rpcfactoryidentity

import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.independent.sdkassociationv1.BufferOverwrite
import com.ukkera.pos.security.independent.sdkassociationv1.IdentityFacts
import com.ukkera.pos.security.independent.sdkassociationv1.captureOriginalIdentity
import com.ukkera.pos.security.independent.sdkassociationv1.identityFacts
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * DATA-only supporting RFIR-DATA-02 preservation/buffer assertions and unchanged F-NAMES oracles
 * from rpc-factory-identity-readback v1. The existing inert descriptor carrier supplies represented
 * original fields and a captured byte/digest baseline, not independent golden native evidence.
 * Internal construction grants no Kit correspondence, original-target recovery, custody, admission
 * or live authority. Actual SDK/native/installed cases remain separately required.
 */
public class RpcFactorySdkOriginalDataSupportingTest {
    @Test
    public fun retainedDataKeepsExactEnglishNameAndCompleteOriginal(): Unit {
        assertExactName("Front Counter")
    }

    @Test
    public fun retainedDataKeepsExactArabicNameAndCompleteOriginal(): Unit {
        assertExactName("كاونتر البيع")
    }

    @Test
    public fun retainedDataKeepsExactNfcNameAndCompleteOriginal(): Unit {
        assertExactName("Caf\u00e9 Counter")
    }

    @Test
    public fun retainedDataKeepsExactDecomposedNameAndCompleteOriginal(): Unit {
        assertExactName("Cafe\u0301 Counter")
    }

    @Test
    public fun nfcAndDecomposedNamesStaySeparateFromUnchangedIdentityBytes(): Unit {
        val identity = readbackDescriptorData()
        val fields = identityFacts(identity)
        val bytes = identity.copyWireBytes()
        val digest = identity.descriptorDigest
        val nfc = LocalDevicePairingSdkOriginal(identity, "Caf\u00e9 Counter")
        val decomposed = LocalDevicePairingSdkOriginal(identity, "Cafe\u0301 Counter")

        assertNotEquals(nfc.exactDeviceName, decomposed.exactDeviceName)
        assertPreserved(nfc, "Caf\u00e9 Counter", fields, bytes, digest)
        assertPreserved(decomposed, "Cafe\u0301 Counter", fields, bytes, digest)
        // Separate name DATA does not retrofit a field into the retained V2 descriptor format.
        assertContentEquals(nfc.identity.copyWireBytes(), decomposed.identity.copyWireBytes())
    }

    @Test
    public fun callerDecodeAndReturnedWireBufferEditsPreserveRetainedOriginalAndName(): Unit {
        for (name in frozenNames) {
            for (overwrite in BufferOverwrite.entries) {
                val identity = readbackDescriptorData()
                val fields = identityFacts(identity)
                val bytes = identity.copyWireBytes()
                val digest = identity.descriptorDigest
                val callerInput = bytes.copyOf()
                val retained = LocalDevicePairingSdkOriginal(LocalDevicePairingSdkIdentityV2.decode(callerInput), name)

                overwrite.apply(callerInput)
                overwrite.apply(retained.identity.copyWireBytes())
                assertPreserved(retained, name, fields, bytes, digest)
            }
        }
    }

    @Test
    public fun returnedPublicPointBufferEditsPreserveCompleteOriginalAndName(): Unit {
        for (name in frozenNames) {
            val identity = readbackDescriptorData()
            val fields = identityFacts(identity)
            val bytes = identity.copyWireBytes()
            val digest = identity.descriptorDigest
            val retained = LocalDevicePairingSdkOriginal(identity, name)
            for (overwrite in BufferOverwrite.entries) {
                overwrite.apply(retained.identity.target.copyPublicPoint())
                assertPreserved(retained, name, fields, bytes, digest)
            }
        }
    }

    private fun assertExactName(name: String) {
        val identity = readbackDescriptorData()
        val fields = identityFacts(identity)
        val bytes = identity.copyWireBytes()
        val digest = identity.descriptorDigest
        val retained = LocalDevicePairingSdkOriginal(identity, name)
        repeat(2) { assertPreserved(retained, name, fields, bytes, digest) }
    }

    private fun assertPreserved(original: LocalDevicePairingSdkOriginal, name: String,
        fields: IdentityFacts, bytes: ByteArray, digest: PayloadDigest) {
        assertEquals(name, original.exactDeviceName, "The separately retained name remains exact")
        assertContentEquals(name.encodeToByteArray(), original.exactDeviceName.encodeToByteArray())
        assertEquals(fields, identityFacts(original.identity), "Every represented original DATA field remains exact")
        assertContentEquals(bytes, original.identity.copyWireBytes(), "Captured original V2 bytes remain exact")
        assertEquals(digest, original.identity.descriptorDigest, "Captured original digest remains exact")
    }

    private fun readbackDescriptorData(): LocalDevicePairingSdkIdentityV2 {
        val zero32Bytes = "a".repeat(52)
        return captureOriginalIdentity(
            appId = "pos.synthetic.rpc.readback",
            peerId = "p2id2-" + zero32Bytes,
            fingerprint = "p2f1-" + zero32Bytes,
            qr = "p2pkit:v2:p2a1-" + zero32Bytes + ":p2f1-" + zero32Bytes,
        )
    }

    private companion object {
        val frozenNames = listOf("Front Counter", "كاونتر البيع", "Caf\u00e9 Counter", "Cafe\u0301 Counter")
    }
}
