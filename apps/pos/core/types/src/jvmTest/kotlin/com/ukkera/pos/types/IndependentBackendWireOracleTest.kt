package com.ukkera.pos.types

import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Instant as JdkInstant
import java.util.Base64
import java.util.HexFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Node produces this public fixture and Go consumes it. These independent Kotlin DTOs use the
 * actual scalar serializers; JDK SHA-256/Ed25519 check the exact resulting bytes. These TEST-ONLY
 * DTOs/range checks are not a production evidence writer, strict gateway, enrollment or store.
 */
class IndependentBackendWireOracleTest {
    private val wire = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun kotlinScalarEnvelopeReproducesFrozenNodeCanonicalBytes() {
        val fixture = fixture()
        assertEquals("public-test-only-not-a-credential", fixture.fixture)
        assertEquals("branch-pos-json-v1", fixture.canonicalEncoding)
        assertEquals("domain-scope-quantity-v2", fixture.candidateRevision)
        assertEquals("evidence-v1 UTC years 0000..9999; Kotlin 0/3/6/9 fraction groups", fixture.timeContract)
        assertEquals(SIGNATURE_DOMAIN, fixture.signatureDomain)
        assertEquals(10, fixture.signatureDomain.last().code) // A newline, not two literal backslash/n bytes.
        assertEquals("1", fixture.batch.schemaVersion)
        assertEquals(listOf(fixture.envelope), fixture.batch.envelopes)
        assertEquals(
            "sha256:070da7cf4fd7fed76953b38330bc689e3e75a1bc8aa4720f88a37488001e471c",
            fixture.envelope.contentHash,
        )

        val payload = fixture.envelope.payload
        assertEquals("1", payload.schemaVersion)
        assertEquals(DomainId("00000000-0000-4000-8000-00000000000b"), payload.scope.organizationId)
        assertEquals(DomainId("00000000-0000-4000-8000-00000000000c"), payload.scope.companyId)
        assertEquals(DomainId("00000000-0000-4000-8000-00000000000d"), payload.scope.branchId)
        assertEquals(Revision(9_007_199_254_740_993L), payload.catalogRevision)
        assertEquals(JournalSequence(1), payload.journalSequence)
        assertEquals(Revision(Long.MAX_VALUE), payload.result.revision)
        assertEquals(Money(Currency.EGP, 9_007_199_254_740_993L), payload.effects.single { it.kind == "cashPosted" }.money)
        assertEquals(
            Quantity(DomainId("00000000-0000-4000-8000-000000000043"), -1_234_567L, 6),
            payload.effects.single { it.kind == "stockPosted" }.quantity,
        )
        assertEquals("2026-10-23T10:20:30.100Z", payload.masterCommittedAt.canonicalString())
        assertEquals(fixture.canonicalPayload, wire.encodeToString(payload))
        assertTrue(fixture.canonicalPayload.all { it.code in 32..126 })
        assertEquals(fixture.envelope.contentHash, jdkContentHash(fixture.canonicalPayload.encodeToByteArray()))
    }

    @Test
    fun jdkHashAndEd25519VerifyEverySignedFractionalAndYearBoundaryVariant() {
        val fixture = fixture()
        assertEquals(fixture.timeVectors.valid, fixture.variants.map { it.name })
        assertEquals(6, fixture.variants.size)
        val key = publicKey(fixture)
        val variants = listOf(WireVariant("primary", fixture.canonicalPayload, fixture.envelope)) + fixture.variants
        for (variant in variants) {
            val canonical = wire.encodeToString(variant.envelope.payload)
            assertEquals(variant.canonicalPayload, canonical, variant.name)
            assertEquals(variant.envelope.contentHash, jdkContentHash(canonical.encodeToByteArray()), variant.name)
            assertTrue(Regex("sha256:[0-9a-f]{64}").matches(variant.envelope.contentHash))
            assertTrue(
                verifies(key, SIGNATURE_DOMAIN + canonical, exactBase64(variant.envelope.signature, 64)),
                variant.name,
            )
        }
    }

    @Test
    fun changedPayloadOrSignatureDomainCannotReuseOriginalHashAndSignature() {
        val fixture = fixture()
        val key = publicKey(fixture)
        val signature = exactBase64(fixture.envelope.signature, 64)
        val changed = fixture.canonicalPayload.replace("9007199254740993", "9007199254740994")
        val trimmedTime = fixture.canonicalPayload.replace(".100Z", ".1Z")
        for (payload in listOf(changed, trimmedTime, fixture.canonicalPayload + " ")) {
            assertNotEquals(fixture.canonicalPayload, payload)
            assertNotEquals(fixture.envelope.contentHash, jdkContentHash(payload.encodeToByteArray()))
            assertFalse(verifies(key, SIGNATURE_DOMAIN + payload, signature))
        }
        assertFalse(verifies(key, fixture.canonicalPayload, signature))
        assertFalse(verifies(key, "ukkera-branch-pos:evidence:v1\\n" + fixture.canonicalPayload, signature))
        assertFalse(verifies(key, "ukkera-branch-pos:evidence:v2\n" + fixture.canonicalPayload, signature))
    }

    @Test
    fun evidenceTimeVectorsUseAnExplicitTransportSubsetWithoutNarrowingGenericInstant() {
        val fixture = fixture()
        for (text in fixture.timeVectors.valid) {
            val instant = testOnlyEvidenceV1Instant(text)
            val oracle = JdkInstant.parse(text)
            assertEquals(text, instant.canonicalString())
            assertEquals(oracle.toString(), instant.canonicalString())
            assertEquals(oracle.epochSecond, instant.value.epochSeconds)
            assertEquals(oracle.nano, instant.value.nanosecondsOfSecond)
        }
        for (text in fixture.timeVectors.invalid) {
            assertFailsWith<IllegalArgumentException>(text) { testOnlyEvidenceV1Instant(text) }
        }
        // These are valid GENERIC scalar values, not evidence-v1 commit timestamps. A production
        // gateway must enforce its supported range before effects; this test helper is not one.
        for (text in listOf(
            "-1000000000-01-01T00:00:00Z", "-0001-01-01T00:00:00Z",
            "+10000-01-01T00:00:00Z", "+1000000000-12-31T23:59:59.999999999Z",
        )) {
            assertEquals(text, UtcInstant.parseCanonical(text).canonicalString())
            assertEquals(text, JdkInstant.parse(text).toString())
            assertFailsWith<IllegalArgumentException>(text) { testOnlyEvidenceV1Instant(text) }
        }
        for (text in listOf("-1000000001-01-01T00:00:00Z", "+1000000001-01-01T00:00:00Z")) {
            assertFailsWith<IllegalArgumentException>(text) { UtcInstant.parseCanonical(text) }
        }
    }

    @Test
    fun actualScalarSerializersRejectWireAliasesAndOverflowEvenInLenientJson() {
        val canonical = fixture().canonicalPayload
        val replacements = listOf(
            "\"minor\":\"9007199254740993\"" to "\"minor\":9007199254740993",
            "\"minor\":\"9007199254740993\"" to "\"minor\":\"9223372036854775808\"",
            "\"minor\":\"9007199254740993\"" to "\"minor\":\"-9223372036854775809\"",
            "\"minor\":\"9007199254740993\"" to "\"minor\":\"-0\"",
            "\"scaled\":\"-1234567\"" to "\"scaled\":-1234567",
            "\"scaled\":\"-1234567\"" to "\"scaled\":\"1e6\"",
            "\"scaled\":\"-1234567\"" to "\"scaled\":\"9223372036854775808\"",
            "\"scale\":6" to "\"scale\":\"6\"",
            "\"scale\":6" to "\"scale\":6.0",
            "\"scale\":6" to "\"scale\":6e0",
            "\"scale\":6" to "\"scale\":-0",
            "\"scale\":6" to "\"scale\":7",
            "\"scale\":6" to "\"scale\":null",
            ",\"scale\":6" to "",
            "\"unitId\":\"00000000-0000-4000-8000-000000000043\"," to "",
            "\"revision\":\"9223372036854775807\"" to "\"revision\":9223372036854775807",
            "\"journalSequence\":\"1\"" to "\"journalSequence\":\"-1\"",
            "\"journalSequence\":\"1\"" to "\"journalSequence\":\"9223372036854775808\"",
            "\"organizationId\":\"00000000-0000-4000-8000-00000000000b\"" to "\"organizationId\":\"org-a\"",
            "00000000-0000-4000-8000-00000000000b" to "00000000-0000-4000-8000-00000000000B",
            "2026-10-23T10:20:30.100Z" to "2026-10-23T10:20:30.1Z",
        )
        for ((original, replacement) in replacements) {
            val malformed = canonical.replaceFirst(original, replacement)
            assertNotEquals(canonical, malformed, original)
            for (decoder in listOf(wire, Json(wire) { isLenient = true })) {
                assertFailsWith<SerializationException>("$original -> $replacement") {
                    decoder.decodeFromString<WirePayload>(malformed)
                }
            }
        }
    }

    private fun fixture(): WireFixture {
        var directory: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(6) {
            val base = directory ?: error("Cannot locate frozen backend fixture")
            val candidate = base.resolve("backend/contracts/golden/evidence-v1.json")
            if (Files.isRegularFile(candidate)) return wire.decodeFromString(Files.readString(candidate))
            directory = base.parent
        }
        error("Cannot locate backend/contracts/golden/evidence-v1.json from the JVM test directory")
    }

    private fun publicKey(fixture: WireFixture): PublicKey {
        assertEquals("WJNmBKvaESvJSTNWnIL40MwN35Kj+DKfL0SPf0hKWUw=", fixture.publicKey)
        val ed25519SubjectPublicKeyInfo = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00)
        return KeyFactory.getInstance("Ed25519").generatePublic(
            X509EncodedKeySpec(ed25519SubjectPublicKeyInfo + exactBase64(fixture.publicKey, 32)),
        )
    }

    private fun exactBase64(value: String, size: Int): ByteArray {
        val decoded = Base64.getDecoder().decode(value)
        assertEquals(size, decoded.size)
        assertEquals(value, Base64.getEncoder().encodeToString(decoded))
        return decoded
    }

    private fun verifies(key: PublicKey, message: String, signature: ByteArray): Boolean =
        Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(message.encodeToByteArray())
            verify(signature)
        }

    private fun jdkContentHash(bytes: ByteArray): String =
        "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Independent named-protocol range oracle only; not a production admission adapter. */
    private fun testOnlyEvidenceV1Instant(text: String): UtcInstant {
        val instant = UtcInstant.parseCanonical(text)
        require(instant >= UtcInstant.parseCanonical("0000-01-01T00:00:00Z")) { "Outside evidence-v1 year range" }
        require(instant <= UtcInstant.parseCanonical("9999-12-31T23:59:59.999999999Z")) { "Outside evidence-v1 year range" }
        return instant
    }

    private companion object {
        const val SIGNATURE_DOMAIN = "ukkera-branch-pos:evidence:v1\n"
    }
}

@Serializable
private data class WireFixture(
    val fixture: String,
    val canonicalEncoding: String,
    val candidateRevision: String,
    val timeContract: String,
    val signatureDomain: String,
    val publicKey: String,
    val canonicalPayload: String,
    val envelope: WireEnvelope,
    val batch: WireBatch,
    val timeVectors: WireTimeVectors,
    val variants: List<WireVariant>,
)

@Serializable
private data class WireTimeVectors(val valid: List<String>, val invalid: List<String>)

@Serializable
private data class WireVariant(val name: String, val canonicalPayload: String, val envelope: WireEnvelope)

@Serializable
private data class WireBatch(val schemaVersion: String, val envelopes: List<WireEnvelope>)

@Serializable
private data class WireEnvelope(val payload: WirePayload, val contentHash: String, val signature: String)

// Declaration order is the fixed branch-pos-json-v1 order, not generic JSON canonicalization.
@Serializable
private data class WirePayload(
    val schemaVersion: String,
    val domainId: DomainId,
    val scope: WireScope,
    val operationId: OperationId,
    val authorityEpoch: Revision,
    val journalSequence: JournalSequence,
    val originMasterDeviceId: DomainId,
    val signingKeyId: DomainId,
    val authorityProofId: DomainId,
    val originalActorId: DomainId,
    val catalogRevision: Revision,
    val policyRevision: Revision,
    val masterCommittedAt: UtcInstant,
    val effects: List<WireEffect>,
    val result: WireResult,
)

@Serializable
private data class WireScope(val organizationId: DomainId, val companyId: DomainId, val branchId: DomainId)

@Serializable
private data class WireEffect(
    val effectId: DomainId,
    val kind: String,
    val resourceId: DomainId,
    val revision: Revision,
    val money: Money? = null,
    val quantity: Quantity? = null,
)

@Serializable
private data class WireResult(val documentId: DomainId, val revision: Revision)
