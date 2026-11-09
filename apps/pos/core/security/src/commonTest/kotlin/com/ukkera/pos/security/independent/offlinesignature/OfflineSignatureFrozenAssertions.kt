package com.ukkera.pos.security.independent.offlinesignature

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.OfflineDocumentRole
import com.ukkera.pos.security.OfflineInstallationUtcWindow
import com.ukkera.pos.security.OfflineParsedDocument
import com.ukkera.pos.security.OfflineSignedBody
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.offlineInstallationVerifyEd25519
import com.ukkera.pos.security.offlineVerifiedNativeSignature
import com.ukkera.pos.types.DomainId
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** Calls the actual platform primitive; every supplied byte array gets a full snapshot. */
internal fun assertFrozenDirectSignature(input: OfflineSignatureFrozenInvocation) {
    val before = SignatureInputSnapshot(input.publicKey, input.message, input.signature)
    val actual = try {
        offlineInstallationVerifyEd25519(input.publicKey, input.message, input.signature)
    } finally {
        before.assertUnchanged(input.publicKey, input.message, input.signature, input.id)
    }
    assertEquals(input.expected, actual, "${input.id} actual platform verification")
}

/**
 * The callback returns only the pre-existing frozen response. It cannot sign an
 * arbitrary request and supplies no native owner, key qualification or authority.
 * The response is a fresh transferred copy; retention assertions concern the
 * caller's corpus bytes and actual message, not response-buffer ownership.
 */
internal suspend fun assertFrozenNativeSignature(input: OfflineSignatureFrozenInvocation) {
    val before = SignatureInputSnapshot(input.publicKey, input.message, input.signature)
    val actual = try {
        offlineVerifiedNativeSignature(OfflineSignatureFrozenFixtures.keyHex(input.publicKey), input.message) { supplied ->
            assertContentEquals(input.message, supplied, "${input.id} unchanged proof message")
            input.signature.copyOf()
        }
    } finally {
        before.assertUnchanged(input.publicKey, input.message, input.signature, "${input.id} native-proof caller corpus")
    }
    assertEquals(input.expected, actual, "${input.id} actual native-signature helper")
}

/**
 * Exact role/body/signature unit binding of the actual parsed-document method.
 * This type witness has no readable schema, identity, scope or validity. It does
 * not simulate typed document decoding, installed artifacts or native authority.
 */
internal fun assertFrozenParsedDocument(
    input: OfflineSignatureFrozenInvocation,
    roleName: String,
    body: ByteArray,
) {
    val key = input.publicKey.copyOf()
    val ownedBody = body.copyOf()
    val ownedSignature = input.signature.copyOf()
    val before = SignatureInputSnapshot(key, ownedBody, ownedSignature)
    val parsed = OfflineParsedDocument(InertSignedBody, PayloadDigest("0".repeat(64)), ownedBody, ownedSignature)
    try {
        val actual = try {
            parsed.verify(OfflineSignatureFrozenFixtures.keyHex(key), OfflineDocumentRole.valueOf(roleName))
        } finally {
            before.assertUnchanged(key, ownedBody, ownedSignature, "${input.id} parsed-document caller")
        }
        assertEquals(input.expected, actual, "${input.id} actual role-framed parsed-document verification")
    } finally {
        // Explicit test cleanup happens after the verification retention assertions.
        parsed.clear()
        key.fill(0)
    }
}

private class SignatureInputSnapshot(key: ByteArray, message: ByteArray, signature: ByteArray) {
    private val keyBefore = key.copyOf()
    private val messageBefore = message.copyOf()
    private val signatureBefore = signature.copyOf()

    fun assertUnchanged(key: ByteArray, message: ByteArray, signature: ByteArray, id: String) {
        // Evaluate all three complete-array comparisons even when one is false.
        val retained = listOf(keyBefore.contentEquals(key), messageBefore.contentEquals(message), signatureBefore.contentEquals(signature))
        assertEquals(listOf(true, true, true), retained, "$id full public-key, message/body, signature retention: zero changed bytes")
    }
}

private object InertSignedBody : OfflineSignedBody {
    override val schema: String get() = error("Signature-only fixture has no schema authority")
    override val documentId: DomainId get() = error("Signature-only fixture has no document identity")
    override val issuerKeyId: DomainId get() = error("Signature-only fixture has no issuer identity")
    override val scope: BranchScope get() = error("Signature-only fixture has no branch authority")
    override val validity: OfflineInstallationUtcWindow get() = error("Signature-only fixture has no validity authority")
}
