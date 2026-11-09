package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Synthetic plaintext vectors only: no bearer verification, login, key custody or authority. */
class LocalAccessIssuanceCodecTest {
    @Test fun independentGoldenFixesEveryIssuanceFieldAndItsOrder() {
        val value = issuanceCodecValue()
        val golden = ISSUANCE_GOLDEN.encodeToByteArray()
        assertContentEquals(golden, ProtectedLocalSecurityCodec.encodeIssuance(value))
        val before = golden.copyOf()
        val decoded = ProtectedLocalSecurityCodec.decodeIssuance(value.issuanceBinding(), golden)
        assertIssuanceFacts(value, decoded)
        assertEquals(1_000L, decoded.expiresAtElapsedMillis)
        assertContentEquals(before, golden)
    }

    @Test fun encodingIsStableAndDoesNotPersistDerivedExpiryOrPolicyOrBearer() {
        val original = issuanceCodecValue()
        val first = ProtectedLocalSecurityCodec.encodeIssuance(original)
        val decoded = ProtectedLocalSecurityCodec.decodeIssuance(original.issuanceBinding(), first)
        assertContentEquals(first, ProtectedLocalSecurityCodec.encodeIssuance(decoded))
        assertEquals(listOf("schema", "scope", "actorId", "sessionId", "deviceId", "generation",
            "credentialRevisionAtIssue", "admissionRevisionAtIssue", "issuerContextId", "issuedAt",
            "validForMillis", "tokenHash", "revision", "authenticationPurpose"), first.issuanceRoot().keys.toList())
        assertEquals(Revision(0), decoded.revision)
        assertEquals(AuthenticationPurpose.INTERACTIVE, decoded.authenticationPurpose)
    }

    @Test fun otherFourLookupTablesCannotDecodeIssuanceEvenWithTheSameRecordCoordinates() {
        val value = issuanceCodecValue()
        val keys = listOf(ProtectedLocalRecordKey.Account(ISSUANCE_SCOPE, value.sessionId),
            ProtectedLocalRecordKey.EffectivePolicy(ISSUANCE_SCOPE, value.sessionId),
            ProtectedLocalRecordKey.Admission(ISSUANCE_SCOPE, value.sessionId),
            ProtectedLocalRecordKey.Throttle(ThrottleKey(ISSUANCE_SCOPE, value.sessionId, ThrottleDimension.Account)))
        for (key in keys) issuanceUnavailable {
            ProtectedLocalSecurityCodec.decodeIssuance(ProtectedLocalBinding(key, Revision(0)), ISSUANCE_GOLDEN.encodeToByteArray())
        }
    }

    @Test fun lookupBindingSeparatelyRequiresOrganizationCompanyBranchAndSession() {
        val value = issuanceCodecValue()
        val scopes = listOf(ISSUANCE_SCOPE.copy(organizationId = issuanceCodecId(91)),
            ISSUANCE_SCOPE.copy(companyId = issuanceCodecId(92)), ISSUANCE_SCOPE.copy(branchId = issuanceCodecId(93)))
        for (scope in scopes) issuanceUnavailable {
            ProtectedLocalSecurityCodec.decodeIssuance(ProtectedLocalBinding(
                ProtectedLocalRecordKey.SessionIssuance(scope, value.sessionId), Revision(0)), ISSUANCE_GOLDEN.encodeToByteArray())
        }
        issuanceUnavailable { ProtectedLocalSecurityCodec.decodeIssuance(ProtectedLocalBinding(
            ProtectedLocalRecordKey.SessionIssuance(ISSUANCE_SCOPE, issuanceCodecId(94)), Revision(0)), ISSUANCE_GOLDEN.encodeToByteArray()) }
    }

    @Test fun payloadSessionCannotBeSubstitutedForTheSelectedBinding() {
        val bytes = ISSUANCE_GOLDEN.encodeToByteArray()
        issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonPrimitive(issuanceCodecId(90).value), "sessionId")) }
        for (field in listOf("organizationId", "companyId", "branchId")) {
            issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonPrimitive(issuanceCodecId(91).value), "scope", field)) }
        }
    }

    @Test fun rootAndNestedUnknownFieldsIncludingInventedAuthorityClaimsAreRefused() {
        val bytes = ISSUANCE_GOLDEN.encodeToByteArray()
        for (field in listOf("policyRevision", "status", "tokenId", "token", "expiresAtElapsedMillis", "algorithm", "PRIVATE extra")) {
            issuanceUnavailable { decodeIssuanceVector(JsonObject(bytes.issuanceRoot() + (field to JsonPrimitive("PRIVATE material"))).issuanceBytes()) }
        }
        for (parent in listOf("scope", "issuedAt")) {
            val inner = bytes.issuanceRoot().getValue(parent).jsonObject
            issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonObject(inner + ("extra" to JsonPrimitive(true))), parent)) }
        }
    }

    @Test fun everyRequiredRootAndNestedFieldMustBePresentAndNonNull() {
        val bytes = ISSUANCE_GOLDEN.encodeToByteArray()
        val root = bytes.issuanceRoot()
        for (field in root.keys) {
            issuanceUnavailable { decodeIssuanceVector(JsonObject(root - field).issuanceBytes()) }
            issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonNull, field)) }
        }
        for (parent in listOf("scope", "issuedAt")) for (field in root.getValue(parent).jsonObject.keys) {
            val inner = root.getValue(parent).jsonObject
            issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonObject(inner - field), parent)) }
            issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonNull, parent, field)) }
        }
    }

    @Test fun duplicateEqualAndConflictingFieldsAreNotAnotherEncoding() {
        for (duplicate in listOf("\"generation\":\"1\",", "\"generation\":\"2\",", "\"revision\":\"0\",")) {
            issuanceUnavailable { decodeIssuanceVector(("{" + duplicate + ISSUANCE_GOLDEN.drop(1)).encodeToByteArray()) }
        }
        val duplicateMoment = ISSUANCE_GOLDEN.replace("\"elapsedMillis\":\"0\"", "\"elapsedMillis\":\"0\",\"elapsedMillis\":\"0\"")
        issuanceUnavailable { decodeIssuanceVector(duplicateMoment.encodeToByteArray()) }
    }

    @Test fun reorderedWhitespaceAndEscapedEquivalentRepresentationsAreRefused() {
        val root = ISSUANCE_GOLDEN.encodeToByteArray().issuanceRoot()
        val alternatives = listOf(JsonObject(root.entries.reversed().associate { it.key to it.value }).toString(),
            " $ISSUANCE_GOLDEN", "$ISSUANCE_GOLDEN\n", ISSUANCE_GOLDEN.replace("\"schema\":", "\"schema\" : "),
            ISSUANCE_GOLDEN.replace("ukkera.local-access-issuance", "\\u0075kkera.local-access-issuance"))
        for (text in alternatives) {
            assertNotEquals(ISSUANCE_GOLDEN, text)
            issuanceUnavailable { decodeIssuanceVector(text.encodeToByteArray()) }
        }
    }

    @Test fun allLongScalarsRequireQuotedCanonicalDecimalRatherThanJsonNumbers() {
        val bytes = ISSUANCE_GOLDEN.encodeToByteArray()
        for (path in issuanceLongPaths) {
            var element: JsonElement = bytes.issuanceRoot()
            for (part in path) element = element.jsonObject.getValue(part)
            val sameValueAsNumber = JsonPrimitive(element.jsonPrimitive.content.toLong())
            issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(sameValueAsNumber, *path.toTypedArray())) }
        }
    }

    @Test fun alternativeDecimalStringsNeverNormalizeIntoValidFacts() {
        val bytes = ISSUANCE_GOLDEN.encodeToByteArray()
        for (path in issuanceLongPaths) for (text in listOf("+1", "01", "-0", "-1", "1.0", "1e3", " 1", "1 ", "", "9223372036854775808")) {
            issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonPrimitive(text), *path.toTypedArray())) }
        }
    }

    @Test fun tokenHashRequiresExactlyLowercaseCanonicalSha256Text() {
        val bytes = ISSUANCE_GOLDEN.encodeToByteArray()
        for (text in listOf(ISSUANCE_HASH.uppercase(), ISSUANCE_HASH.drop(1), ISSUANCE_HASH + "0",
            "g" + ISSUANCE_HASH.drop(1), " $ISSUANCE_HASH", "$ISSUANCE_HASH\n", "${ISSUANCE_HASH}\u0000tail", "")) {
            issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonPrimitive(text), "tokenHash")) }
        }
        issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonPrimitive(42), "tokenHash")) }
        assertEquals(ISSUANCE_HASH, decodeIssuanceVector(bytes).tokenHash.canonicalSha256())
    }

    @Test fun onlyTheFixedSchemaRevisionZeroAndInteractiveProfileAreAccepted() {
        val bytes = ISSUANCE_GOLDEN.encodeToByteArray()
        for (schema in listOf("ukkera.local-access-issuance/v2", "ukkera.protected-local-account/v1", "PRIVATE schema")) {
            issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonPrimitive(schema), "schema")) }
        }
        for (revision in listOf("1", "9223372036854775807")) {
            issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonPrimitive(revision), "revision")) }
        }
        for (purpose in listOf("PRIVILEGED_APPROVAL", "PREPARED_LOCAL_RECOVERY", "SERVICE", "interactive", "PRIVATE purpose")) {
            issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonPrimitive(purpose), "authenticationPurpose")) }
        }
        assertFailsWith<IllegalArgumentException> {
            ProtectedLocalBinding(ProtectedLocalRecordKey.SessionIssuance(ISSUANCE_SCOPE, issuanceCodecId(6)), Revision(1))
        }
    }

    @Test fun allUuidCoordinatesRejectMalformedTypesAndSpellings() {
        val bytes = ISSUANCE_GOLDEN.encodeToByteArray()
        val paths = listOf(listOf("actorId"), listOf("sessionId"), listOf("deviceId"), listOf("issuerContextId"),
            listOf("issuedAt", "bootId"), listOf("scope", "organizationId"), listOf("scope", "companyId"), listOf("scope", "branchId"))
        for (path in paths) for (value in listOf(JsonPrimitive("PRIVATE identity"), JsonPrimitive("00000000-0000-4000-8000-00000000000A"),
            JsonPrimitive("00000000-0000-4000-8000-000000000004\u0000tail"), JsonPrimitive(4))) {
            issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(value, *path.toTypedArray())) }
        }
    }

    @Test fun generationZeroAndExhaustionAreNotIssuableSessions() {
        val bytes = ISSUANCE_GOLDEN.encodeToByteArray()
        for (generation in listOf("0", "9223372036854775807")) {
            issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonPrimitive(generation), "generation")) }
        }
        val largest = issuanceCodecValue(generation = Long.MAX_VALUE - 1)
        assertIssuanceFacts(largest, ProtectedLocalSecurityCodec.decodeIssuance(largest.issuanceBinding(),
            ProtectedLocalSecurityCodec.encodeIssuance(largest)))
    }

    @Test fun exactLargeIssueRevisionsAndTimesSurviveWithoutFloatingPoint() {
        val value = issuanceCodecValue(generation = 9_007_199_254_740_993, credentialRevision = Long.MAX_VALUE,
            admissionRevision = Long.MAX_VALUE, elapsedMillis = 9_007_199_254_740_993, validForMillis = 1)
        val encoded = ProtectedLocalSecurityCodec.encodeIssuance(value)
        assertTrue(encoded.decodeToString().contains("\"generation\":\"9007199254740993\""))
        assertTrue(encoded.decodeToString().contains("\"credentialRevisionAtIssue\":\"9223372036854775807\""))
        assertTrue(encoded.decodeToString().contains("\"elapsedMillis\":\"9007199254740993\""))
        assertIssuanceFacts(value, ProtectedLocalSecurityCodec.decodeIssuance(value.issuanceBinding(), encoded))
    }

    @Test fun positiveLifetimeAndCheckedExclusiveExpiryRejectZeroAndOverflow() {
        val bytes = ISSUANCE_GOLDEN.encodeToByteArray()
        issuanceUnavailable { decodeIssuanceVector(bytes.issuanceChange(JsonPrimitive("0"), "validForMillis")) }
        val exact = issuanceCodecValue(elapsedMillis = Long.MAX_VALUE - 1, validForMillis = 1)
        val encoded = ProtectedLocalSecurityCodec.encodeIssuance(exact)
        assertEquals(Long.MAX_VALUE, decodeIssuanceVector(encoded).expiresAtElapsedMillis)
        issuanceUnavailable { decodeIssuanceVector(encoded.issuanceChange(JsonPrimitive("2"), "validForMillis")) }
        issuanceUnavailable { decodeIssuanceVector(encoded.issuanceChange(JsonPrimitive(Long.MAX_VALUE.toString()), "issuedAt", "elapsedMillis")) }
        val fullRange = issuanceCodecValue(elapsedMillis = 0, validForMillis = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, decodeIssuanceVector(ProtectedLocalSecurityCodec.encodeIssuance(fullRange)).expiresAtElapsedMillis)
    }

    @Test fun malformedUtf8TruncationAndTrailingContentAreSanitizedWithoutMutatingInput() {
        val valid = ISSUANCE_GOLDEN.encodeToByteArray()
        val invalid = listOf(byteArrayOf(0xc0.toByte(), 0xaf.toByte()), valid + byteArrayOf(0x80.toByte()),
            valid.copyOf(valid.size - 1), valid + "PRIVATE tail".encodeToByteArray(), "PRIVATE plaintext".encodeToByteArray())
        for (bytes in invalid) {
            val before = bytes.copyOf()
            issuanceUnavailable { decodeIssuanceVector(bytes) }
            assertContentEquals(before, bytes)
        }
    }

    @Test fun fourKiBProfileBoundsAreCheckedWithoutClaimingAPaddedDocumentIsCanonical() {
        val binding = issuanceCodecValue().issuanceBinding()
        assertEquals(4_096, ProtectedLocalTable.SESSION_ISSUANCE.maximumPlaintextBytes)
        binding.requirePlaintextSize(4_096)
        assertFailsWith<IllegalArgumentException> { binding.requirePlaintextSize(4_097) }
        issuanceUnavailable { decodeIssuanceVector(ByteArray(0)) }
        issuanceUnavailable { decodeIssuanceVector(ByteArray(4_097) { ' '.code.toByte() }) }
        val paddedAtBoundary = ISSUANCE_GOLDEN.padEnd(4_096, ' ').encodeToByteArray()
        assertEquals(4_096, paddedAtBoundary.size)
        issuanceUnavailable { decodeIssuanceVector(paddedAtBoundary) }
        val bounded = issuanceCodecValue(generation = Long.MAX_VALUE - 1, credentialRevision = Long.MAX_VALUE,
            admissionRevision = Long.MAX_VALUE, elapsedMillis = Long.MAX_VALUE - 1, validForMillis = 1)
        val bytes = ProtectedLocalSecurityCodec.encodeIssuance(bounded)
        assertTrue(bytes.size in 1..4_096)
        assertIssuanceFacts(bounded, decodeIssuanceVector(bytes))
    }

    @Test fun originalFourEmptyGoldenEncodingsRemainByteForByteUnchanged() {
        val account = ProtectedLocalAccount(LocalAccountSessionState(ISSUANCE_SCOPE, issuanceCodecId(4), true,
            Revision(0), SessionGeneration(0), emptyList()), Revision(0), null)
        val policy = ActivatedLocalPolicy(ISSUANCE_SCOPE, issuanceCodecId(4), Revision(0), emptyList(), LocalSessionPolicy(), ThrottlePolicy())
        val admission = LocalDeviceAdmission(ISSUANCE_SCOPE, issuanceCodecId(5), Revision(0), false, null, null, emptySet())
        val throttle = PersistentThrottleState(ThrottleKey(ISSUANCE_SCOPE, issuanceCodecId(4), ThrottleDimension.Account),
            Revision(0), 0, MonotonicMoment(issuanceCodecId(8), 0), 0, null)
        val expected = listOf(
            """{"schema":"ukkera.protected-local-account/v1","scope":$ISSUANCE_SCOPE_JSON,"actorId":"00000000-0000-4000-8000-000000000004","enabled":true,"revision":"0","generation":"0","sessions":[],"credentialRevision":"0","credential":null}""",
            """{"schema":"ukkera.activated-local-policy/v1","scope":$ISSUANCE_SCOPE_JSON,"actorId":"00000000-0000-4000-8000-000000000004","revision":"0","grants":[],"sessionPolicy":{"maximumConcurrentSessions":1},"throttlePolicy":{"freeFailures":3,"initialDelayMillis":"1000","maximumDelayMillis":"900000","attemptTimeoutMillis":"120000"}}""",
            """{"schema":"ukkera.local-device-admission/v1","scope":$ISSUANCE_SCOPE_JSON,"deviceId":"00000000-0000-4000-8000-000000000005","revision":"0","admitted":false,"publicKeyIdentifier":null,"publicFingerprint":null,"capabilities":[]}""",
            """{"schema":"ukkera.persistent-throttle/v1","scope":$ISSUANCE_SCOPE_JSON,"actorId":"00000000-0000-4000-8000-000000000004","dimension":{"kind":"account","deviceId":null},"revision":"0","failures":0,"lastObservation":{"bootId":"00000000-0000-4000-8000-000000000008","elapsedMillis":"0"},"remainingDelayMillis":"0","activeAttempt":null}""",
        )
        val encoded = listOf(ProtectedLocalSecurityCodec.encodeAccount(account), ProtectedLocalSecurityCodec.encodePolicy(policy),
            ProtectedLocalSecurityCodec.encodeAdmission(admission), ProtectedLocalSecurityCodec.encodeThrottle(throttle))
        for ((literal, bytes) in expected.zip(encoded)) {
            assertContentEquals(literal.encodeToByteArray(), bytes)
            issuanceUnavailable { decodeIssuanceVector(bytes) }
        }
    }
}

private const val ISSUANCE_SCOPE_JSON = """{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"00000000-0000-4000-8000-000000000002","branchId":"00000000-0000-4000-8000-000000000003"}"""
private const val ISSUANCE_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
private const val ISSUANCE_GOLDEN = """{"schema":"ukkera.local-access-issuance/v1","scope":$ISSUANCE_SCOPE_JSON,"actorId":"00000000-0000-4000-8000-000000000004","sessionId":"00000000-0000-4000-8000-000000000006","deviceId":"00000000-0000-4000-8000-000000000005","generation":"1","credentialRevisionAtIssue":"0","admissionRevisionAtIssue":"0","issuerContextId":"00000000-0000-4000-8000-000000000007","issuedAt":{"bootId":"00000000-0000-4000-8000-000000000008","elapsedMillis":"0"},"validForMillis":"1000","tokenHash":"$ISSUANCE_HASH","revision":"0","authenticationPurpose":"INTERACTIVE"}"""
private val ISSUANCE_SCOPE = BranchScope(issuanceCodecId(1), issuanceCodecId(2), issuanceCodecId(3))
private val issuanceLongPaths = listOf(listOf("generation"), listOf("credentialRevisionAtIssue"),
    listOf("admissionRevisionAtIssue"), listOf("issuedAt", "elapsedMillis"), listOf("validForMillis"), listOf("revision"))

private fun issuanceCodecId(number: Int): DomainId = DomainId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

private fun issuanceCodecValue(generation: Long = 1, credentialRevision: Long = 0, admissionRevision: Long = 0,
    elapsedMillis: Long = 0, validForMillis: Long = 1_000): LocalAccessIssuance = LocalAccessIssuance(
    ISSUANCE_SCOPE, issuanceCodecId(4), issuanceCodecId(6), issuanceCodecId(5), SessionGeneration(generation),
    Revision(credentialRevision), Revision(admissionRevision), issuanceCodecId(7), MonotonicMoment(issuanceCodecId(8), elapsedMillis),
    validForMillis, LocalAccessTokenHash.fromCanonicalSha256(ISSUANCE_HASH),
)

private fun LocalAccessIssuance.issuanceBinding(): ProtectedLocalBinding =
    ProtectedLocalBinding(ProtectedLocalRecordKey.SessionIssuance(scope, sessionId), revision)

private fun decodeIssuanceVector(bytes: ByteArray): LocalAccessIssuance =
    ProtectedLocalSecurityCodec.decodeIssuance(issuanceCodecValue().issuanceBinding(), bytes)

private fun assertIssuanceFacts(expected: LocalAccessIssuance, actual: LocalAccessIssuance) {
    assertEquals(expected.scope, actual.scope); assertEquals(expected.actorId, actual.actorId)
    assertEquals(expected.sessionId, actual.sessionId); assertEquals(expected.deviceId, actual.deviceId)
    assertEquals(expected.generation, actual.generation); assertEquals(expected.credentialRevisionAtIssue, actual.credentialRevisionAtIssue)
    assertEquals(expected.admissionRevisionAtIssue, actual.admissionRevisionAtIssue); assertEquals(expected.issuerContextId, actual.issuerContextId)
    assertEquals(expected.issuedAt, actual.issuedAt); assertEquals(expected.validForMillis, actual.validForMillis)
    assertEquals(expected.tokenHash.canonicalSha256(), actual.tokenHash.canonicalSha256())
    assertEquals(expected.expiresAtElapsedMillis, actual.expiresAtElapsedMillis)
    assertEquals(Revision(0), actual.revision); assertEquals(AuthenticationPurpose.INTERACTIVE, actual.authenticationPurpose)
}

private fun issuanceUnavailable(block: () -> Any?) {
    val error = assertFailsWith<IllegalStateException> { block() }
    assertEquals("Protected local record unavailable", error.message)
    assertNull(error.cause)
    assertTrue(error.suppressedExceptions.isEmpty())
}

private fun ByteArray.issuanceRoot(): JsonObject = Json.parseToJsonElement(decodeToString()).jsonObject
private fun JsonObject.issuanceBytes(): ByteArray = toString().encodeToByteArray()
private fun ByteArray.issuanceChange(value: JsonElement, vararg path: String): ByteArray {
    require(path.isNotEmpty())
    fun change(parent: JsonObject, depth: Int): JsonObject {
        val key = path[depth]
        require(key in parent)
        val replacement = if (depth == path.lastIndex) value else change(parent.getValue(key).jsonObject, depth + 1)
        return JsonObject(parent + (key to replacement))
    }
    return change(issuanceRoot(), 0).issuanceBytes()
}
