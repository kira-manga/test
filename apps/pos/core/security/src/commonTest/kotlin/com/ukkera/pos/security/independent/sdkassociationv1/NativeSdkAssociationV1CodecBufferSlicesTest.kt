package com.ukkera.pos.security.independent.sdkassociationv1

import com.ukkera.pos.security.*
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Captured DATA/returned-buffer observations only. Represented service bytes and
 * 64-byte proof signatures supply no native admission, signature verification,
 * head approval, original persistence/recovery or actual Kit authority.
 */
public class NativeSdkAssociationV1CodecBufferSlicesTest {
    @Test
    public fun nsa026ReturnedIdentityWireMutationPreservesActualOriginalFactsBytesAndDigest(): Unit {
        assertEquals(3, FrozenNativeSdkAssociationV1.data.objectAt("v2_returned_buffer_variants").arrayAt("actions").size)
        for (afterDecode in listOf(false, true)) for (overwrite in BufferOverwrite.entries) {
            val captured = captureOriginalIdentity()
            val value = if (afterDecode) LocalDevicePairingSdkIdentityV2.decode(captured.copyWireBytes()) else captured
            val before = identityFacts(value)
            val bytes = value.copyWireBytes()
            val digest = value.descriptorDigest
            val returned = value.copyWireBytes()
            mutateCallerBuffer(overwrite, returned)
            assertEquals(before, identityFacts(value))
            assertContentEquals(bytes, value.copyWireBytes())
            assertEquals(digest, value.descriptorDigest)
            val reread = LocalDevicePairingSdkIdentityV2.decode(value.copyWireBytes())
            assertEquals(before, identityFacts(reread))
            assertContentEquals(bytes, reread.copyWireBytes())
            assertEquals(digest, reread.descriptorDigest)
        }
    }

    @Test
    public fun nsa026DecodedIdentityStateSurvivesCallerInputBufferMutation(): Unit {
        for (overwrite in BufferOverwrite.entries) {
            val captured = captureOriginalIdentity()
            val callerInput = captured.copyWireBytes()
            val decoded = LocalDevicePairingSdkIdentityV2.decode(callerInput)
            val before = identityFacts(decoded)
            val bytes = decoded.copyWireBytes()
            val digest = decoded.descriptorDigest
            mutateCallerBuffer(overwrite, callerInput)
            assertEquals(before, identityFacts(decoded))
            assertContentEquals(bytes, decoded.copyWireBytes())
            assertEquals(digest, decoded.descriptorDigest)
            assertContentEquals(captured.copyWireBytes(), decoded.copyWireBytes())
        }
    }

    @Test
    public fun nsa026NestedTargetWireAndPointMutationCannotAlterParentDescriptor(): Unit {
        val views: List<Pair<String, (LocalDevicePairingSdkIdentityV2) -> ByteArray>> = listOf(
            "target_wire" to { it.target.copyWireBytes() },
            "target_public_point" to { it.target.copyPublicPoint() },
        )
        for ((name, view) in views) for (overwrite in BufferOverwrite.entries) {
            val value = captureOriginalIdentity()
            val before = identityFacts(value)
            val bytes = value.copyWireBytes()
            val targetBytes = value.target.copyWireBytes()
            val point = value.target.copyPublicPoint()
            val digest = value.descriptorDigest
            mutateCallerBuffer(overwrite, view(value))
            assertEquals(before, identityFacts(value), name)
            assertContentEquals(bytes, value.copyWireBytes(), name)
            assertContentEquals(targetBytes, value.target.copyWireBytes(), name)
            assertContentEquals(point, value.target.copyPublicPoint(), name)
            assertEquals(digest, value.descriptorDigest, name)
        }
    }

    @Test
    public fun nsa019And026DefinitionCodecsRetainExactSdkDataAndCapturedSubject(): Unit {
        val identity = captureOriginalIdentity()
        val definition = capturedDefinition(identity)
        val bytes = definition.copyWireBytes()
        val before = definitionFacts(definition)
        val parsed = NativeDevicePairingWireV2.decodeDefinition(bytes)
        val encoded = NativeDevicePairingWireV2.encodeDefinition(parsed)
        assertContentEquals(bytes, encoded)
        val decoded = LocalDevicePairingDefinition.decode(encoded)
        assertEquals(before, definitionFacts(decoded))
        assertEquals(FrozenNativeSdkAssociationV1.original.text("purpose"), decoded.purpose)
        val decodedIdentity = assertNotNull(decoded.sdkIdentity)
        assertContentEquals(identity.copyWireBytes(), decodedIdentity.copyWireBytes())
        assertEquals(identity.descriptorDigest, decodedIdentity.descriptorDigest)
        assertEquals(definition.definitionDigest, decoded.definitionDigest)

        val newAppId = FrozenNativeSdkAssociationV1.data.arrayAt("v2_app_id_variants")
            .map { it.jsonObject }.single { it.text("id") == "nonblank_spaces" }.text("value")
        val changedIdentity = LocalDevicePairingSdkIdentityV2.decode(rewriteIdentity(identity,
            appId = newAppId.encodeToByteArray()))
        val changedDefinition = capturedDefinition(changedIdentity)
        assertEquals(newAppId, assertNotNull(changedDefinition.sdkIdentity).appId.value)
        assertFalse(bytes.contentEquals(changedDefinition.copyWireBytes()))
        assertNotEquals(definition.definitionDigest, changedDefinition.definitionDigest)
        assertContentEquals(bytes, definition.copyWireBytes())
        assertEquals(before, definitionFacts(definition))
    }

    @Test
    public fun nsa026EveryReturnedDefinitionBufferPreservesActualCapturedSubject(): Unit {
        val views: List<Pair<String, (LocalDevicePairingDefinition) -> ByteArray>> = listOf(
            "definition_wire" to { it.copyWireBytes() },
            "represented_service_admission" to { it.copyCanonicalServiceAdmission() },
            "sdk_identity_wire" to { requireNotNull(it.sdkIdentity).copyWireBytes() },
            "target_wire" to { it.target.copyWireBytes() },
            "target_public_point" to { it.target.copyPublicPoint() },
        )
        for ((name, view) in views) for (overwrite in BufferOverwrite.entries) {
            val definition = capturedDefinition(captureOriginalIdentity())
            val before = definitionFacts(definition)
            val bytes = definition.copyWireBytes()
            val serviceBytes = definition.copyCanonicalServiceAdmission()
            val sdkBytes = assertNotNull(definition.sdkIdentity).copyWireBytes()
            val digest = definition.definitionDigest
            mutateCallerBuffer(overwrite, view(definition))
            assertEquals(before, definitionFacts(definition), name)
            assertContentEquals(bytes, definition.copyWireBytes(), name)
            assertContentEquals(serviceBytes, definition.copyCanonicalServiceAdmission(), name)
            assertContentEquals(sdkBytes, assertNotNull(definition.sdkIdentity).copyWireBytes(), name)
            assertEquals(digest, definition.definitionDigest, name)
            val reread = LocalDevicePairingDefinition.decode(definition.copyWireBytes())
            assertEquals(before, definitionFacts(reread), name)
            assertContentEquals(bytes, reread.copyWireBytes(), name)
        }
    }

    @Test
    public fun nsa019And026PossessionCodecsRetainOriginalSdkDigestAndCapturedBytes(): Unit {
        val identity = captureOriginalIdentity()
        val represented = possessionFields(identity)
        val encoded = NativeDevicePairingWireV2.encodePossession(represented)
        val parsed = NativeDevicePairingWireV2.decodePossession(encoded)
        assertContentEquals(encoded, NativeDevicePairingWireV2.encodePossession(parsed))
        val decoded = LocalDevicePairingPossession.decode(encoded)
        assertEquals(represented.originalActionId, decoded.originalActionId)
        assertEquals(represented.challengeDigest, decoded.challengeDigest)
        assertEquals(represented.deviceId, decoded.deviceId)
        assertEquals(represented.keyId, decoded.keyId)
        assertEquals(represented.descriptorDigest, decoded.descriptorDigest)
        assertEquals(identity.descriptorDigest, decoded.sdkIdentityDigest)
        assertContentEquals(represented.signature.copyBytes(), decoded.copySignature())
        assertContentEquals(encoded, decoded.copyWireBytes())

        val changedIdentity = LocalDevicePairingSdkIdentityV2.decode(rewriteIdentity(identity,
            appId = " app-a ".encodeToByteArray()))
        val changedBytes = NativeDevicePairingWireV2.encodePossession(
            represented.copy(sdkIdentityDigest = changedIdentity.descriptorDigest))
        val changed = LocalDevicePairingPossession.decode(changedBytes)
        assertEquals(changedIdentity.descriptorDigest, changed.sdkIdentityDigest)
        assertNotEquals(decoded.sdkIdentityDigest, changed.sdkIdentityDigest)
        assertFalse(encoded.contentEquals(changed.copyWireBytes()))
        assertContentEquals(encoded, decoded.copyWireBytes())
        // These signatures are represented DATA, never successful native possession evidence.
    }

    @Test
    public fun nsa026EveryReturnedPossessionBufferPreservesActualCapturedData(): Unit {
        val views: List<Pair<String, (LocalDevicePairingPossession) -> ByteArray>> = listOf(
            "possession_wire" to { it.copyWireBytes() },
            "represented_signature" to { it.copySignature() },
        )
        for ((name, view) in views) for (overwrite in BufferOverwrite.entries) {
            val possession = capturedPossession(captureOriginalIdentity())
            val before = possessionFacts(possession)
            val bytes = possession.copyWireBytes()
            val signature = possession.copySignature()
            mutateCallerBuffer(overwrite, view(possession))
            assertEquals(before, possessionFacts(possession), name)
            assertContentEquals(bytes, possession.copyWireBytes(), name)
            assertContentEquals(signature, possession.copySignature(), name)
            val reread = LocalDevicePairingPossession.decode(possession.copyWireBytes())
            assertEquals(before, possessionFacts(reread), name)
            assertContentEquals(bytes, reread.copyWireBytes(), name)
            assertContentEquals(signature, reread.copySignature(), name)
        }
    }
}

private fun mutateCallerBuffer(overwrite: BufferOverwrite, held: ByteArray) {
    val before = held.copyOf()
    overwrite.apply(held)
    assertFalse(before.contentEquals(held), "The caller-held buffer must actually be overwritten")
}

private data class DefinitionFacts(
    val purpose: String, val request: List<String>, val admission: AdmissionFacts,
    val retainedAdmissions: List<AdmissionFacts>, val sdk: IdentityFacts?, val serviceHex: String,
    val targetPointHex: String,
)

private fun definitionFacts(value: LocalDevicePairingDefinition): DefinitionFacts = DefinitionFacts(
    value.purpose,
    value.request.let { listOf(it.scope.organizationId.value, it.scope.companyId.value, it.scope.branchId.value,
        it.requestId.value, it.action.name, it.targetId.value, it.contentDigest.value) },
    admissionFacts(value.admission), value.retainedAdmissions.map(::admissionFacts),
    value.sdkIdentity?.let(::identityFacts), value.copyCanonicalServiceAdmission().hex(), value.target.copyPublicPoint().hex(),
)

private fun possessionFacts(value: LocalDevicePairingPossession): List<String?> = listOf(
    value.originalActionId.value, value.challengeDigest.value, value.deviceId.value, value.keyId.value,
    value.descriptorDigest.value, value.sdkIdentityDigest?.value, value.copySignature().hex(),
)
