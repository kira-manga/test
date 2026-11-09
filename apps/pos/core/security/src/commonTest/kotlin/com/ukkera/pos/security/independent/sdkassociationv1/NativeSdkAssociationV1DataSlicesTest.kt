package com.ukkera.pos.security.independent.sdkassociationv1

import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.security.SelfOwnedWireReaderV1
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/** Frozen NSA-019..025 public DATA slices, not native association or whole-case qualification. */
public class NativeSdkAssociationV1DataSlicesTest {
    @Test
    public fun nsa019CaptureAndDecodePreserveFrozenTopLevelFactsAndCapturedBytes(): Unit {
        val original = captureOriginalIdentity()
        val fixture = FrozenNativeSdkAssociationV1.original
        val expectedIds = identityIds(original).keys.associateWith { syntheticIdUuid(fixture.text(it)) }
        assertEquals(expectedIds, identityIds(original))
        assertEquals(fixture.text("sdk_app_id"), original.appId.value)
        assertEquals(fixture.text("sdk_peer_id"), original.peerId.value)
        assertEquals(fixture.text("sdk_fingerprint"), original.fingerprint.value)
        assertEquals(fixture.text("sdk_pairing_qr"), original.pairingQr)
        val originalBytes = original.copyWireBytes()
        val header = SelfOwnedWireReaderV1(originalBytes)
        header.string(128)
        assertEquals(fixture.text("domain"), header.string(128))
        assertEquals(fixture.text("purpose"), header.string(128))

        val before = identityFacts(original)
        val targetBytes = original.target.copyWireBytes()
        val decoded = LocalDevicePairingSdkIdentityV2.decode(originalBytes)
        assertEquals(before, identityFacts(decoded))
        assertContentEquals(targetBytes, decoded.target.copyWireBytes())
        assertContentEquals(originalBytes, decoded.copyWireBytes())
        // Compare captured digest values, not a separately invented digest algorithm/golden.
        assertEquals(original.descriptorDigest, decoded.descriptorDigest)
    }

    @Test
    public fun nsa019EachOriginalIdChangeRemainsDifferentExactData(): Unit {
        val fields = FrozenNativeSdkAssociationV1.data.objectAt("v2_original_fact_variants")
            .arrayAt("one_fact_changed_per_run").map { it.jsonPrimitive.content }
            .filter { it in identityIds(captureOriginalIdentity()).keys }
        assertEquals(7, fields.size)
        for (field in fields) {
            val original = captureOriginalIdentity()
            val before = identityFacts(original)
            val bytes = original.copyWireBytes()
            val digest = original.descriptorDigest.value
            val changedLabel = FrozenNativeSdkAssociationV1.original.text(field) + "-changed"
            val newValue = syntheticIdUuid(changedLabel)
            assertNotEquals(before.ids.getValue(field), newValue, field)
            val changedBytes = rewriteIdentity(original, changedIdLabels = mapOf(field to changedLabel))
            val changed = LocalDevicePairingSdkIdentityV2.decode(changedBytes)
            assertEquals(before.ids + (field to newValue), identityIds(changed), field)
            assertEquals(before.applicationFacts, identityFacts(changed).applicationFacts, field)
            assertEquals(before.pointHex, identityFacts(changed).pointHex, field)
            assertEquals(before.appId, changed.appId.value, field)
            assertEquals(before.peerId, changed.peerId.value, field)
            assertEquals(before.fingerprint, changed.fingerprint.value, field)
            assertEquals(before.qr, changed.pairingQr, field)
            assertContentEquals(changedBytes, changed.copyWireBytes(), field)
            assertFalse(bytes.contentEquals(changed.copyWireBytes()), field)
            assertNotEquals(digest, changed.descriptorDigest.value, field)
            assertOriginal(original, before, bytes, digest)
        }
    }

    @Test
    public fun nsa019CanonicalAppPeerAndQrChangesCannotNormalizeToOriginal(): Unit {
        val data = FrozenNativeSdkAssociationV1.data
        val changes = listOf(
            "sdk_app_id" to data.arrayAt("v2_app_id_variants")
                .map { it.jsonObject }.single { it.text("id") == "nonblank_spaces" }.text("value"),
            "sdk_peer_id" to data.objectAt("v2_peer_id_vectors").arrayAt("canonical")[1].jsonObject.text("text"),
            "sdk_pairing_qr" to data.objectAt("v2_fingerprint_qr_variants")
                .objectAt("canonical_changed_app_binding_qr").text("qr"),
        )
        for ((field, value) in changes) {
            val original = captureOriginalIdentity()
            val before = identityFacts(original)
            val bytes = original.copyWireBytes()
            val digest = original.descriptorDigest.value
            val changed = LocalDevicePairingSdkIdentityV2.decode(rewriteSdkField(original, field, value))
            val expected = when (field) {
                "sdk_app_id" -> before.copy(appId = value)
                "sdk_peer_id" -> before.copy(peerId = value)
                "sdk_pairing_qr" -> before.copy(qr = value)
                else -> error("Unassigned frozen field")
            }
            assertEquals(expected, identityFacts(changed), field)
            assertFalse(bytes.contentEquals(changed.copyWireBytes()), field)
            assertNotEquals(digest, changed.descriptorDigest.value, field)
            assertOriginal(original, before, bytes, digest)
        }
    }

    @Test
    public fun nsa020All21OriginalIdCollisionsRejectAtActualV2Decode(): Unit {
        val variants = FrozenNativeSdkAssociationV1.data.objectAt("v2_distinct_id_variants")
        val pairs = variants.arrayAt("collisions").map { it.jsonObject }
        assertEquals(21, variants.text("unordered_pair_count").toInt())
        assertEquals(21, pairs.size)
        assertEquals(21, pairs.map { it.text("left_field") to it.text("right_field") }.distinct().size)
        for (pair in pairs) {
            val original = captureOriginalIdentity()
            val before = identityFacts(original)
            val bytes = original.copyWireBytes()
            val digest = original.descriptorDigest.value
            assertEquals(7, before.ids.values.distinct().size)
            assertEquals(before.ids.getValue(pair.text("left_field")), syntheticIdUuid(pair.text("collision_value")))
            val changed = rewriteIdentity(original,
                changedIdLabels = mapOf(pair.text("right_field") to pair.text("collision_value")))
            // Even device/native-key collisions reach this real V2 decode, not a helper's constructor guard.
            assertFails(pair.text("left_field") + "/" + pair.text("right_field")) {
                LocalDevicePairingSdkIdentityV2.decode(changed)
            }
            assertOriginal(original, before, bytes, digest)
        }
    }

    @Test
    public fun nsa021Known16385ByteInputRejectsWithoutTruncatingCapturedOriginal(): Unit {
        val size = FrozenNativeSdkAssociationV1.data.arrayAt("v2_descriptor_size_variants")[1]
            .jsonObject.text("whole_encoded_byte_count").toInt()
        assertEquals(16_385, size)
        val original = captureOriginalIdentity()
        val before = identityFacts(original)
        val bytes = original.copyWireBytes()
        val digest = original.descriptorDigest.value
        val oversized = bytes.copyOf(size)
        assertEquals(size, oversized.size)
        assertFails { LocalDevicePairingSdkIdentityV2.decode(oversized) }
        assertOriginal(original, before, bytes, digest)
        // Negative noncanonical oversized input only. No valid 16,384-byte fixture is claimed.
    }

    @Test
    public fun nsa022Exact255ByteAsciiAndMultibyteAppIdsArePreserved(): Unit {
        val rows = FrozenNativeSdkAssociationV1.data.arrayAt("v2_app_id_variants")
            .map { it.jsonObject }.filter { it.text("id") in setOf("ascii_255", "unicode_255") }
        assertEquals(2, rows.size)
        for (row in rows) {
            val value = row.text("value")
            assertEquals(255, value.encodeToByteArray().size, row.text("id"))
            assertEquals(255, row.text("utf8_bytes").toInt())
            val captured = captureOriginalIdentity(appId = value)
            assertEquals(value, captured.appId.value)
            assertEquals(value, LocalDevicePairingSdkIdentityV2.decode(captured.copyWireBytes()).appId.value)
        }
    }

    @Test
    public fun nsa022256ByteAndBlankAppIdsRejectAtActualDecode(): Unit {
        val rows = FrozenNativeSdkAssociationV1.data.arrayAt("v2_app_id_variants")
            .map { it.jsonObject }.filter { it.text("id") in
                setOf("ascii_256", "unicode_256", "empty", "spaces_only", "tab_only") }
        assertEquals(5, rows.size)
        for (row in rows) {
            val original = captureOriginalIdentity()
            val encoded = row.text("value").encodeToByteArray()
            assertEquals(row.text("utf8_bytes").toInt(), encoded.size, row.text("id"))
            val candidate = rewriteIdentity(original, appId = encoded)
            assertFails(row.text("id")) { LocalDevicePairingSdkIdentityV2.decode(candidate) }
        }
    }

    @Test
    public fun nsa022NonblankLeadingAndTrailingSpacesAreNotTrimmed(): Unit {
        val value = FrozenNativeSdkAssociationV1.data.arrayAt("v2_app_id_variants")
            .map { it.jsonObject }.single { it.text("id") == "nonblank_spaces" }.text("value")
        val captured = captureOriginalIdentity(appId = value)
        assertEquals(" app-a ", value)
        assertEquals(value, captured.appId.value)
        assertEquals(value, LocalDevicePairingSdkIdentityV2.decode(captured.copyWireBytes()).appId.value)
        assertNotEquals(value.trim(), captured.appId.value)
    }

    @Test
    public fun nsa022ComposedAndDecomposedAppIdsRemainDifferentExactData(): Unit {
        val rows = FrozenNativeSdkAssociationV1.data.arrayAt("v2_app_id_variants").map { it.jsonObject }
        val composed = rows.single { it.text("id") == "composed" }.text("value")
        val decomposed = rows.single { it.text("id") == "decomposed" }.text("value")
        assertEquals(2, composed.encodeToByteArray().size)
        assertEquals(3, decomposed.encodeToByteArray().size)
        val a = captureOriginalIdentity(appId = composed)
        val b = captureOriginalIdentity(appId = decomposed)
        assertEquals(composed, LocalDevicePairingSdkIdentityV2.decode(a.copyWireBytes()).appId.value)
        assertEquals(decomposed, LocalDevicePairingSdkIdentityV2.decode(b.copyWireBytes()).appId.value)
        assertNotEquals(a.appId.value, b.appId.value)
        assertFalse(a.copyWireBytes().contentEquals(b.copyWireBytes()))
        assertNotEquals(a.descriptorDigest, b.descriptorDigest)
    }

    @Test
    public fun nsa023BothCanonicalPeerIdVectorsRemainExactData(): Unit {
        val rows = FrozenNativeSdkAssociationV1.data.objectAt("v2_peer_id_vectors").arrayAt("canonical")
        assertEquals(2, rows.size)
        for (element in rows) {
            val row = element.jsonObject
            val value = row.text("text")
            assertEquals(row.text("text_ascii_bytes").toInt(), value.encodeToByteArray().size)
            val captured = captureOriginalIdentity(peerId = value)
            assertEquals(value, captured.peerId.value)
            assertEquals(value, LocalDevicePairingSdkIdentityV2.decode(captured.copyWireBytes()).peerId.value)
        }
    }

    @Test
    public fun nsa023AllFrozenInvalidPeerIdsIncludingResidualBitsReject(): Unit {
        val rows = FrozenNativeSdkAssociationV1.data.objectAt("v2_peer_id_vectors").arrayAt("invalid")
        assertEquals(13, rows.size)
        for (element in rows) {
            val row = element.jsonObject
            val candidate = rewriteIdentity(captureOriginalIdentity(), peerId = row.text("text").encodeToByteArray())
            assertFails(row.text("id")) { LocalDevicePairingSdkIdentityV2.decode(candidate) }
        }
    }

    @Test
    public fun nsa024CanonicalFingerprintQrPairIsPreservedAsDataOnly(): Unit {
        val row = FrozenNativeSdkAssociationV1.data.objectAt("v2_fingerprint_qr_variants")
            .objectAt("canonical_syntax_only")
        val original = captureOriginalIdentity()
        val decoded = LocalDevicePairingSdkIdentityV2.decode(original.copyWireBytes())
        assertEquals(row.text("fingerprint"), decoded.fingerprint.value)
        assertEquals(row.text("qr"), decoded.pairingQr)
        assertEquals(57, decoded.fingerprint.value.encodeToByteArray().size)
        assertEquals(125, decoded.pairingQr.encodeToByteArray().size)
    }

    @Test
    public fun nsa024AllFrozenNoncanonicalFingerprintsRejectAtActualDecode(): Unit {
        val rows = FrozenNativeSdkAssociationV1.data.objectAt("v2_fingerprint_qr_variants")
            .arrayAt("noncanonical_fingerprints")
        assertEquals(10, rows.size)
        for (row in rows) {
            val candidate = rewriteIdentity(captureOriginalIdentity(), fingerprint = row.jsonPrimitive.content.encodeToByteArray())
            assertFails(row.jsonPrimitive.content) { LocalDevicePairingSdkIdentityV2.decode(candidate) }
        }
    }

    @Test
    public fun nsa024AllFrozenNoncanonicalQrVectorsReject(): Unit {
        val rows = FrozenNativeSdkAssociationV1.data.objectAt("v2_fingerprint_qr_variants")
            .arrayAt("noncanonical_qrs")
        assertEquals(12, rows.size)
        for (row in rows) {
            val candidate = rewriteIdentity(captureOriginalIdentity(), qr = row.jsonPrimitive.content.encodeToByteArray())
            assertFails(row.jsonPrimitive.content) { LocalDevicePairingSdkIdentityV2.decode(candidate) }
        }
    }

    @Test
    public fun nsa024CanonicalQrFingerprintInequalityRejects(): Unit {
        val row = FrozenNativeSdkAssociationV1.data.objectAt("v2_fingerprint_qr_variants")
            .objectAt("canonical_qr_fingerprint_mismatch")
        assertNotEquals(row.text("descriptor_fingerprint"), row.text("qr_fingerprint"))
        val candidate = rewriteIdentity(captureOriginalIdentity(),
            fingerprint = row.text("descriptor_fingerprint").encodeToByteArray(), qr = row.text("qr").encodeToByteArray())
        assertFails { LocalDevicePairingSdkIdentityV2.decode(candidate) }
    }

    @Test
    public fun nsa024ChangedCanonicalAppBindingRemainsDifferentExactData(): Unit {
        val qr = FrozenNativeSdkAssociationV1.data.objectAt("v2_fingerprint_qr_variants")
            .objectAt("canonical_changed_app_binding_qr").text("qr")
        val original = captureOriginalIdentity()
        val before = identityFacts(original)
        val changed = LocalDevicePairingSdkIdentityV2.decode(rewriteIdentity(original, qr = qr.encodeToByteArray()))
        assertEquals(before.copy(qr = qr), identityFacts(changed))
        assertFalse(original.copyWireBytes().contentEquals(changed.copyWireBytes()))
        assertNotEquals(original.descriptorDigest, changed.descriptorDigest)
        // No AppId-to-app-binding algorithm or actual Kit correspondence is inferred.
    }

    @Test
    public fun nsa025FrozenWrongPurposeAndDomainVectorsRejectAtActualDecode(): Unit {
        val rows = FrozenNativeSdkAssociationV1.data.objectAt("v2_invalid_encoding_variants")
        for (purpose in rows.arrayAt("wrong_purposes")) {
            val candidate = rewriteIdentity(captureOriginalIdentity(), purpose = purpose.jsonPrimitive.content.encodeToByteArray())
            assertFails { LocalDevicePairingSdkIdentityV2.decode(candidate) }
        }
        for (domain in rows.arrayAt("wrong_domains")) {
            val candidate = rewriteIdentity(captureOriginalIdentity(), domain = domain.jsonPrimitive.content.encodeToByteArray())
            assertFails { LocalDevicePairingSdkIdentityV2.decode(candidate) }
        }
    }

    @Test
    public fun nsa025FrozenMalformedUtf8VectorsRejectInRequiredAppIdField(): Unit {
        val rows = FrozenNativeSdkAssociationV1.data.objectAt("v2_invalid_encoding_variants")
            .arrayAt("malformed_utf8_hex")
        assertEquals(7, rows.size)
        for (row in rows) {
            val candidate = rewriteIdentity(captureOriginalIdentity(), appId = hexBytes(row.jsonPrimitive.content))
            assertFails(row.jsonPrimitive.content) { LocalDevicePairingSdkIdentityV2.decode(candidate) }
        }
        // Placement uses the captured runtime carrier, not independently frozen whole bytes.
    }

    @Test
    public fun nsa025EachFrozenTrailingByteRejectsInsteadOfBeingIgnored(): Unit {
        val rows = FrozenNativeSdkAssociationV1.data.objectAt("v2_invalid_encoding_variants")
            .arrayAt("trailing_byte_hex")
        assertEquals(2, rows.size)
        for (row in rows) {
            val original = captureOriginalIdentity()
            val bytes = original.copyWireBytes()
            val candidate = bytes + hexBytes(row.jsonPrimitive.content)
            assertEquals(bytes.size + 1, candidate.size)
            assertFails(row.jsonPrimitive.content) { LocalDevicePairingSdkIdentityV2.decode(candidate) }
            assertContentEquals(bytes, original.copyWireBytes())
        }
    }
}

private fun rewriteSdkField(original: LocalDevicePairingSdkIdentityV2, field: String, value: String): ByteArray =
    when (field) {
        "sdk_app_id" -> rewriteIdentity(original, appId = value.encodeToByteArray())
        "sdk_peer_id" -> rewriteIdentity(original, peerId = value.encodeToByteArray())
        "sdk_pairing_qr" -> rewriteIdentity(original, qr = value.encodeToByteArray())
        else -> error("Unassigned frozen SDK field")
    }

private fun assertOriginal(value: LocalDevicePairingSdkIdentityV2, facts: IdentityFacts, bytes: ByteArray, digest: String) {
    assertEquals(facts, identityFacts(value))
    assertContentEquals(bytes, value.copyWireBytes())
    assertEquals(digest, value.descriptorDigest.value)
}
