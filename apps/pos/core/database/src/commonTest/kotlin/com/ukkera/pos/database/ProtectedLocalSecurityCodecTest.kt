package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/** Synthetic storage-only vectors; no credential checking, encryption, login or authority. */
class ProtectedLocalSecurityCodecTest {
    @Test fun independentEmptyAccountGoldenRetainsExplicitNullAndZeroCounters() {
        val value = codecAccount()
        val golden = """{"schema":"ukkera.protected-local-account/v1","scope":$CODEC_SCOPE_JSON,"actorId":"$CODEC_ACTOR_TEXT","enabled":true,"revision":"0","generation":"0","sessions":[],"credentialRevision":"0","credential":null}""".encodeToByteArray()
        assertContentEquals(golden, ProtectedLocalSecurityCodec.encodeAccount(value))
        val decoded = ProtectedLocalSecurityCodec.decodeAccount(value.binding(), golden)
        assertEquals(Revision(0), decoded.state.revision)
        assertEquals(SessionGeneration(0), decoded.state.generation)
        assertEquals(Revision(0), decoded.credentialRevision)
        assertNull(decoded.credential)
        assertTrue(decoded.state.enabled && decoded.state.sessions.isEmpty())
    }

    @Test fun independentEmptyPolicyGoldenIncludesEveryDefaultWithoutNumericLongs() {
        val value = codecPolicy()
        val golden = """{"schema":"ukkera.activated-local-policy/v1","scope":$CODEC_SCOPE_JSON,"actorId":"$CODEC_ACTOR_TEXT","revision":"0","grants":[],"sessionPolicy":{"maximumConcurrentSessions":1},"throttlePolicy":{"freeFailures":3,"initialDelayMillis":"1000","maximumDelayMillis":"900000","attemptTimeoutMillis":"120000"}}""".encodeToByteArray()
        assertContentEquals(golden, ProtectedLocalSecurityCodec.encodePolicy(value))
        val decoded = ProtectedLocalSecurityCodec.decodePolicy(value.binding(), golden)
        assertEquals(LocalSessionPolicy(), decoded.sessionPolicy)
        assertEquals(ThrottlePolicy(), decoded.throttlePolicy)
        assertTrue(decoded.grants.isEmpty())
    }

    @Test fun independentUnadmittedDeviceGoldenDoesNotInventIdentityOrCapabilities() {
        val value = codecAdmission()
        val golden = """{"schema":"ukkera.local-device-admission/v1","scope":$CODEC_SCOPE_JSON,"deviceId":"$CODEC_DEVICE_TEXT","revision":"0","admitted":false,"publicKeyIdentifier":null,"publicFingerprint":null,"capabilities":[]}""".encodeToByteArray()
        assertContentEquals(golden, ProtectedLocalSecurityCodec.encodeAdmission(value))
        val decoded = ProtectedLocalSecurityCodec.decodeAdmission(value.binding(), golden)
        assertFalse(decoded.admitted)
        assertNull(decoded.publicKeyIdentifier)
        assertNull(decoded.publicFingerprint)
        assertTrue(decoded.capabilities.isEmpty())
    }

    @Test fun independentThrottleGoldenDistinguishesAccountDimensionAndNoAttempt() {
        val value = codecThrottle()
        val golden = """{"schema":"ukkera.persistent-throttle/v1","scope":$CODEC_SCOPE_JSON,"actorId":"$CODEC_ACTOR_TEXT","dimension":{"kind":"account","deviceId":null},"revision":"0","failures":0,"lastObservation":{"bootId":"00000000-0000-4000-8000-000000000008","elapsedMillis":"0"},"remainingDelayMillis":"0","activeAttempt":null}""".encodeToByteArray()
        assertContentEquals(golden, ProtectedLocalSecurityCodec.encodeThrottle(value))
        assertEquals(value, ProtectedLocalSecurityCodec.decodeThrottle(value.binding(), golden))
    }

    @Test fun everyCodecRequiresItsExpectedTableNotAnotherKindWithSimilarCoordinates() {
        val cases = codecCases()
        for (case in cases) for (other in cases.filter { it.table != case.table }) {
            assertCodecUnavailable { case.decode(other.binding, case.bytes) }
            assertCodecUnavailable { case.decode(case.binding, other.bytes) }
        }
    }

    @Test fun everyCodecRejectsUnsupportedOrAlternativeSchemaTags() {
        for (case in codecCases()) for (schema in listOf("unknown/v1", "ukkera.protected-local-account/v2", "PRIVATE schema")) {
            assertCodecUnavailable { case.decode(case.binding, case.bytes.change(JsonPrimitive(schema), "schema")) }
        }
    }

    @Test fun lookupRevisionMustExactlyMatchTheAuthenticatedPayloadRevision() {
        for (case in codecCases()) {
            assertCodecUnavailable { case.decode(case.binding.copy(revision = Revision(1)), case.bytes) }
            assertCodecUnavailable { case.decode(case.binding, case.bytes.change(JsonPrimitive("1"), "revision")) }
        }
    }

    @Test fun scopeBindingChecksOrganizationCompanyAndBranchSeparatelyForEveryTable() {
        val scopes = listOf(CODEC_SCOPE.copy(organizationId = codecId(90)), CODEC_SCOPE.copy(companyId = codecId(91)),
            CODEC_SCOPE.copy(branchId = codecId(92)))
        for (case in codecCases()) for (scope in scopes) {
            val binding = case.binding.copy(key = case.binding.key.rekey(scope = scope))
            assertCodecUnavailable { case.decode(binding, case.bytes) }
        }
    }

    @Test fun recordIdBindingChecksActorOrAdmittedDeviceRatherThanOnlyTenant() {
        for (case in codecCases()) {
            val binding = case.binding.copy(key = case.binding.key.rekey(id = codecId(99)))
            assertCodecUnavailable { case.decode(binding, case.bytes) }
        }
    }

    @Test fun unknownRootOrNestedFieldsAreNeverIgnored() {
        for (case in codecCases()) {
            val root = case.bytes.root()
            assertCodecUnavailable { case.decode(case.binding, JsonObject(root + ("PRIVATE unknown" to JsonPrimitive("PRIVATE material"))).bytes()) }
            val scope = root.getValue("scope").jsonObject
            assertCodecUnavailable { case.decode(case.binding,
                case.bytes.change(JsonObject(scope + ("extra" to JsonPrimitive(true))), "scope")) }
        }
    }

    @Test fun duplicateRootFieldsCannotBeSilentlyResolvedByTheJsonParser() {
        for (case in codecCases()) {
            val text = case.bytes.decodeToString()
            for (duplicate in listOf("\"revision\":\"0\",", "\"revision\":\"1\",")) {
                val changed = ("{" + duplicate + text.drop(1)).encodeToByteArray()
                assertCodecUnavailable { case.decode(case.binding, changed) }
            }
        }
    }

    @Test fun duplicateNestedCredentialAndThrottleFieldsAreRejectedEvenWhenEqual() {
        val account = codecAccount(credential = codecCredential())
        val accountBytes = ProtectedLocalSecurityCodec.encodeAccount(account).decodeToString()
            .replace("\"iterations\":600000", "\"iterations\":600000,\"iterations\":600000").encodeToByteArray()
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(account.binding(), accountBytes) }
        val throttle = codecThrottle()
        val throttleBytes = ProtectedLocalSecurityCodec.encodeThrottle(throttle).decodeToString()
            .replace("\"elapsedMillis\":\"0\"", "\"elapsedMillis\":\"0\",\"elapsedMillis\":\"0\"").encodeToByteArray()
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeThrottle(throttle.binding(), throttleBytes) }
    }

    @Test fun rootFieldReorderingIsNotAnotherCanonicalRepresentation() {
        for (case in codecCases()) {
            val reversed = JsonObject(case.bytes.root().entries.reversed().associate { it.key to it.value }).bytes()
            assertFalse(case.bytes.contentEquals(reversed))
            assertCodecUnavailable { case.decode(case.binding, reversed) }
        }
    }

    @Test fun whitespaceEscapesBomAndTrailingDataAreNotCanonicalAliases() {
        for (case in codecCases()) {
            val text = case.bytes.decodeToString()
            val variants = listOf(" $text", "$text\n", text.replaceFirst(":", ": "), "$text{}", "$text\u0000",
                text.replaceFirst("ukkera", "\\u0075kkera"))
            for (variant in variants) assertCodecUnavailable { case.decode(case.binding, variant.encodeToByteArray()) }
            assertCodecUnavailable { case.decode(case.binding, byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + case.bytes) }
        }
    }

    @Test fun invalidUtf8FailsWithoutReplacementDecodingOrPrivateCauses() {
        val invalid = listOf(byteArrayOf(0x80.toByte()), byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()), byteArrayOf(0xf4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()))
        for (case in codecCases()) for (bytes in invalid) {
            assertCodecUnavailable { case.decode(case.binding, bytes) }
        }
    }

    @Test fun missingAndNullRequiredFieldsCannotBecomeDefaultOrFreshState() {
        for (case in codecCases()) {
            assertCodecUnavailable { case.decode(case.binding, JsonObject(case.bytes.root() - "revision").bytes()) }
            assertCodecUnavailable { case.decode(case.binding, case.bytes.change(JsonNull, "scope")) }
        }
        val account = codecAccount()
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(account)
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(account.binding(), JsonObject(bytes.root() - "credential").bytes()) }
        val policy = codecPolicy()
        val encoded = ProtectedLocalSecurityCodec.encodePolicy(policy)
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodePolicy(policy.binding(), encoded.change(JsonObject(emptyMap()), "sessionPolicy")) }
    }

    @Test fun everyRowRevisionUsesAnExactCanonicalDecimalString() {
        val alternatives = listOf(JsonPrimitive(0), JsonPrimitive(0.0), JsonPrimitive("00"), JsonPrimitive("+0"),
            JsonPrimitive("-0"), JsonPrimitive("1e0"), JsonPrimitive(" 0"), JsonPrimitive("-1"),
            JsonPrimitive("9223372036854775808"), JsonPrimitive(true))
        for (case in codecCases()) for (value in alternatives) {
            assertCodecUnavailable { case.decode(case.binding, case.bytes.change(value, "revision")) }
        }
    }

    @Test fun scalarTypesAndCanonicalIdentifiersAreNotCoerced() {
        for (case in codecCases()) {
            assertCodecUnavailable { case.decode(case.binding, case.bytes.change(JsonPrimitive(3), "scope", "branchId")) }
            assertCodecUnavailable { case.decode(case.binding,
                case.bytes.change(JsonPrimitive("FFFFFFFF-FFFF-4FFF-8FFF-FFFFFFFFFFFF"), "scope", "branchId")) }
        }
        val account = codecAccount()
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(account)
        for (value in listOf(JsonPrimitive("true"), JsonPrimitive(1), JsonNull)) {
            assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(account.binding(), bytes.change(value, "enabled")) }
        }
    }

    @Test fun emptyAndOverBudgetPlaintextAreUnavailabilityNotAbsentRecords() {
        for (case in codecCases()) {
            assertCodecUnavailable { case.decode(case.binding, byteArrayOf()) }
            assertCodecUnavailable { case.decode(case.binding, ByteArray(case.table.maximumPlaintextBytes + 1) { ' '.code.toByte() }) }
        }
    }

    @Test fun inputArraysAreNotRetainedOrClearedBySuccessfulDecoding() {
        for (case in codecCases()) {
            val before = case.bytes.copyOf()
            case.decode(case.binding, case.bytes)
            assertContentEquals(before, case.bytes)
        }
        val account = codecAccount(credential = codecCredential())
        val input = ProtectedLocalSecurityCodec.encodeAccount(account)
        val expected = input.copyOf()
        val decoded = ProtectedLocalSecurityCodec.decodeAccount(account.binding(), input)
        input.fill(0)
        assertContentEquals(expected, ProtectedLocalSecurityCodec.encodeAccount(decoded))
    }

    @Test fun credentialRevisionZeroAndIndependentGoldenHexArePreserved() {
        val original = codecAccount(credential = codecCredential())
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(original)
        val text = bytes.decodeToString()
        assertTrue(text.contains("\"algorithm\":\"PBKDF2-HMAC-SHA256/POST-HMAC-SHA256/v1\""))
        assertTrue(text.contains("\"saltHex\":\"000102030405060708090a0b0c0d0e0f\""))
        assertTrue(text.contains("\"verifierHex\":\"a0a1a2a3a4a5a6a7a8a9aaabacadaeafb0b1b2b3b4b5b6b7b8b9babbbcbdbebf\""))
        val decoded = ProtectedLocalSecurityCodec.decodeAccount(original.binding(), bytes)
        val credential = assertNotNull(decoded.credential)
        assertEquals(CredentialBinding(CODEC_SCOPE, CODEC_ACTOR, Revision(0)), credential.binding)
        assertEquals(PasswordHashParameters(), credential.parameters)
        assertEquals(ProtectedPepperReference(codecId(6)), credential.pepperReference)
        assertContentEquals(ByteArray(16) { it.toByte() }, credential.copySalt())
        assertContentEquals(ByteArray(32) { (0xa0 + it).toByte() }, credential.copyVerifier())
    }

    @Test fun nullCredentialRetainsTheLatestCredentialRevisionWithoutInventingUsability() {
        val original = codecAccount(credentialRevision = Revision(29))
        val decoded = ProtectedLocalSecurityCodec.decodeAccount(original.binding(), ProtectedLocalSecurityCodec.encodeAccount(original))
        assertEquals(Revision(29), decoded.credentialRevision)
        assertNull(decoded.credential)
        assertEquals(Revision(0), decoded.state.revision)
    }

    @Test fun credentialMustBindExactScopeActorAndIndependentRevision() {
        val original = codecAccount(credential = codecCredential())
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(original)
        val variants = listOf(
            bytes.change(JsonPrimitive(codecId(97).value), "credential", "scope", "branchId"),
            bytes.change(JsonPrimitive(codecId(98).value), "credential", "actorId"),
            bytes.change(JsonPrimitive("1"), "credential", "credentialRevision"),
            bytes.change(JsonPrimitive("1"), "credentialRevision"),
        )
        for (variant in variants) assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(original.binding(), variant) }
    }

    @Test fun credentialSaltAllowsExactlyTheExistingSixteenToSixtyFourByteProfile() {
        for (length in listOf(16, 64)) {
            val original = codecAccount(credential = codecCredential(salt = ByteArray(length) { (255 - it).toByte() }))
            val decoded = ProtectedLocalSecurityCodec.decodeAccount(original.binding(), ProtectedLocalSecurityCodec.encodeAccount(original))
            assertContentEquals(assertNotNull(original.credential).copySalt(), assertNotNull(decoded.credential).copySalt())
        }
        val original = codecAccount(credential = codecCredential())
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(original)
        for (hex in listOf("aa".repeat(15), "aa".repeat(65), "a".repeat(33), "AA".repeat(16), "gg".repeat(16))) {
            assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(original.binding(), bytes.change(JsonPrimitive(hex), "credential", "saltHex")) }
        }
    }

    @Test fun verifierMustBeExactlyThirtyTwoBytesOfCanonicalLowercaseHex() {
        val original = codecAccount(credential = codecCredential())
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(original)
        for (hex in listOf("ab".repeat(31), "ab".repeat(33), "AB".repeat(32), "ab".repeat(31) + "a-")) {
            assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(original.binding(), bytes.change(JsonPrimitive(hex), "credential", "verifierHex")) }
        }
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(original.binding(),
            bytes.change(JsonArray(List(32) { JsonPrimitive(0) }), "credential", "verifierHex")) }
    }

    @Test fun credentialAlgorithmWorkFactorAndPepperReferenceAreNotBestEffort() {
        val original = codecAccount(credential = codecCredential())
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(original)
        val variants = listOf(bytes.change(JsonPrimitive("PBKDF2"), "credential", "algorithm"),
            bytes.change(JsonPrimitive(599_999), "credential", "iterations"),
            bytes.change(JsonPrimitive(5_000_001), "credential", "iterations"),
            bytes.change(JsonPrimitive("600000"), "credential", "iterations"),
            bytes.change(JsonPrimitive(600000.5), "credential", "iterations"),
            bytes.change(JsonPrimitive("PRIVATE invalid key"), "credential", "pepperKeyId"))
        for (variant in variants) assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(original.binding(), variant) }
        val maximum = codecAccount(credential = codecCredential(iterations = 5_000_000))
        assertEquals(5_000_000, assertNotNull(ProtectedLocalSecurityCodec.decodeAccount(maximum.binding(),
            ProtectedLocalSecurityCodec.encodeAccount(maximum)).credential).parameters.iterations)
    }

    @Test fun malformedCredentialDiagnosticsNeverExposeVerifierInputOrParserCauses() {
        val original = codecAccount(credential = codecCredential())
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(original)
            .change(JsonPrimitive("PRIVATE-VERIFIER-MATERIAL"), "credential", "verifierHex")
        val failure = assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(original.binding(), bytes) }
        assertFalse(failure.toString().contains("PRIVATE"))
        assertEquals("ProtectedLocalAccount([REDACTED])", original.toString())
        assertEquals("PasswordHashRecord([REDACTED])", assertNotNull(original.credential).toString())
    }

    @Test fun returnedCredentialCopiesCannotMutateStoredVerifierOrSalt() {
        val original = codecAccount(credential = codecCredential())
        val decoded = ProtectedLocalSecurityCodec.decodeAccount(original.binding(), ProtectedLocalSecurityCodec.encodeAccount(original))
        val credential = assertNotNull(decoded.credential)
        credential.copySalt().fill(0)
        credential.copyVerifier().fill(0)
        assertContentEquals(ByteArray(16) { it.toByte() }, credential.copySalt())
        assertContentEquals(ByteArray(32) { (0xa0 + it).toByte() }, credential.copyVerifier())
    }

    @Test fun allAccountAndSessionCountersBeyondDoublePrecisionRoundTripExactly() {
        val large = 9_007_199_254_740_993L
        val session = codecSession(1).copy(generation = SessionGeneration(large))
        val original = codecAccount(revision = Revision(large + 2), generation = SessionGeneration(large + 1),
            sessions = listOf(session), credentialRevision = Revision(large + 3),
            credential = codecCredential(revision = Revision(large + 3)))
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(original)
        val decoded = ProtectedLocalSecurityCodec.decodeAccount(original.binding(), bytes)
        assertEquals(Revision(large + 2), decoded.state.revision)
        assertEquals(SessionGeneration(large + 1), decoded.state.generation)
        assertEquals(SessionGeneration(large), decoded.state.sessions.single().generation)
        assertEquals(Revision(large + 3), decoded.credentialRevision)
        assertTrue(bytes.decodeToString().contains("\"generation\":\"9007199254740993\""))
        for (path in listOf(listOf("generation"), listOf("credentialRevision"), listOf("sessions", "0", "generation"),
            listOf("credential", "credentialRevision"))) {
            assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(original.binding(), bytes.change(JsonPrimitive(1), *path.toTypedArray())) }
        }
    }

    @Test fun allTwoHundredFiftySixHistoricalSessionsSurviveAboveTheCommandByteLimit() {
        val sessions = List(256) { codecSession(it + 1) }
        val original = codecAccount(revision = Revision(4), generation = SessionGeneration(256), sessions = sessions)
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(original)
        assertTrue(bytes.size > 65_536, "This is intentionally not the financial command profile")
        assertTrue(bytes.size <= ProtectedLocalTable.ACCOUNT.maximumPlaintextBytes)
        val decoded = ProtectedLocalSecurityCodec.decodeAccount(original.binding(), bytes)
        assertEquals(sessions, decoded.state.sessions)
        assertEquals(256, decoded.state.sessions.size)
    }

    @Test fun sessionHistoryOrderAndOriginalGenerationsAreNotSortedOrReallocated() {
        val sessions = listOf(codecSession(3), codecSession(1), codecSession(2))
        val original = codecAccount(generation = SessionGeneration(3), sessions = sessions)
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(original)
        assertEquals(sessions, ProtectedLocalSecurityCodec.decodeAccount(original.binding(), bytes).state.sessions)
        val reversed = codecAccount(generation = SessionGeneration(3), sessions = sessions.reversed())
        val reversedBytes = ProtectedLocalSecurityCodec.encodeAccount(reversed)
        assertFalse(bytes.contentEquals(reversedBytes))
        assertEquals(sessions.reversed(), ProtectedLocalSecurityCodec.decodeAccount(reversed.binding(), reversedBytes).state.sessions)
    }

    @Test fun twoHundredFiftySevenHistoryEntriesAreRejectedWithoutTruncation() {
        val original = codecAccount(generation = SessionGeneration(256), sessions = List(256) { codecSession(it + 1) })
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(original)
        val sessions = bytes.root().getValue("sessions").jsonArray
        val extra = JsonObject(sessions.first().jsonObject + ("sessionId" to JsonPrimitive(codecId(9999).value)))
        val invalid = bytes.change(JsonArray(sessions + extra), "sessions")
        assertTrue(invalid.size < ProtectedLocalTable.ACCOUNT.maximumPlaintextBytes)
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(original.binding(), invalid) }
    }

    @Test fun sessionActorScopeUniquenessAndAllocatedGenerationAreValidated() {
        val original = codecAccount(generation = SessionGeneration(2), sessions = listOf(codecSession(1), codecSession(2)))
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(original)
        val variants = listOf(bytes.change(JsonPrimitive(codecId(99).value), "sessions", "0", "actorId"),
            bytes.change(JsonPrimitive(codecId(99).value), "sessions", "0", "scope", "branchId"),
            bytes.change(JsonPrimitive(codecSession(1).sessionId.value), "sessions", "1", "sessionId"),
            bytes.change(JsonPrimitive("3"), "sessions", "0", "generation"),
            bytes.change(JsonPrimitive("UNKNOWN"), "sessions", "0", "status"),
            bytes.change(JsonPrimitive("UNKNOWN"), "sessions", "0", "authenticationPurpose"))
        for (variant in variants) assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(original.binding(), variant) }
    }

    @Test fun activeSessionOfDisabledAccountIsRejectedRatherThanSilentlyRevoked() {
        val original = codecAccount(generation = SessionGeneration(1), sessions = listOf(codecSession(1).copy(status = SessionStatus.ACTIVE)))
        val invalid = ProtectedLocalSecurityCodec.encodeAccount(original).change(JsonPrimitive(false), "enabled")
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(original.binding(), invalid) }
    }

    @Test fun terminalLockdownMayJumpFromLowRevisionWhenGenerationIsExhausted() {
        val before = LocalAccountSessionState(CODEC_SCOPE, CODEC_ACTOR, true, Revision(4),
            SessionGeneration(Long.MAX_VALUE - 1), listOf(codecSession(1).copy(status = SessionStatus.ACTIVE)))
        val transition = assertIs<SessionTransition.Applied>(LocalSessions.disable(before))
        assertEquals(Revision(Long.MAX_VALUE), transition.state.revision)
        val terminal = ProtectedLocalAccount(transition.state, Revision(19), null)
        val decoded = ProtectedLocalSecurityCodec.decodeAccount(terminal.binding(), ProtectedLocalSecurityCodec.encodeAccount(terminal))
        assertEquals(Revision(Long.MAX_VALUE), decoded.state.revision)
        assertEquals(SessionGeneration(Long.MAX_VALUE), decoded.state.generation)
        assertFalse(decoded.state.enabled)
        assertEquals(SessionStatus.REVOKED, decoded.state.sessions.single().status)
        assertEquals(codecSession(1).generation, decoded.state.sessions.single().generation)
        assertEquals(Revision(19), decoded.credentialRevision)
    }

    @Test fun neitherSingleMaxCounterNorEnabledMaxShapeIsAValidTerminalRecord() {
        val ordinary = codecAccount()
        val bytes = ProtectedLocalSecurityCodec.encodeAccount(ordinary)
        val variants = listOf(bytes.change(JsonPrimitive(Long.MAX_VALUE.toString()), "revision"),
            bytes.change(JsonPrimitive(Long.MAX_VALUE.toString()), "generation"),
            bytes.change(JsonPrimitive(Long.MAX_VALUE.toString()), "revision").change(JsonPrimitive(Long.MAX_VALUE.toString()), "generation"))
        for (variant in variants) {
            // Matching MAX row metadata makes the terminal-shape check, not a stale revision, relevant.
            val revision = if (variant.root().getValue("revision") == JsonPrimitive("0")) Revision(0) else Revision(Long.MAX_VALUE)
            assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAccount(ordinary.binding().copy(revision = revision), variant) }
        }
    }

    @Test fun policyRoundTripPreservesEveryGrantFieldIncludingCompanyScopeAndDelegation() {
        val grants = listOf(codecGrant(scope = CODEC_SCOPE.company(), effect = GrantEffect.DENY,
            limits = PermissionLimits(Money(Currency.EGP, Long.MAX_VALUE), 9999), owner = OwnerAccess.ANY_OWNER,
            named = CODEC_ACTOR, targets = linkedSetOf(codecId(12), codecId(11)),
            ceiling = PermissionLimits(Money(Currency.EGP, 12345), 4321)),
            codecGrant(scope = CODEC_SCOPE, action = SecurityAction.CASH_READ, verb = PermissionVerb.EXPORT))
        val original = codecPolicy(grants = grants, revision = Revision(9_007_199_254_740_993L),
            sessionPolicy = LocalSessionPolicy(16), throttlePolicy = ThrottlePolicy(0, 60_000, 86_400_000, 300_000))
        val bytes = ProtectedLocalSecurityCodec.encodePolicy(original)
        val decoded = ProtectedLocalSecurityCodec.decodePolicy(original.binding(), bytes)
        assertEquals(original.revision, decoded.revision)
        assertEquals(original.sessionPolicy, decoded.sessionPolicy)
        assertEquals(original.throttlePolicy, decoded.throttlePolicy)
        assertEquals(2, decoded.grants.size)
        grants.zip(decoded.grants).forEach { (expected, actual) -> assertGrantEquals(expected, actual) }
        assertIs<CompanyScope>(decoded.grants.first().scope)
        assertIs<BranchScope>(decoded.grants.last().scope)
        assertTrue(bytes.decodeToString().contains("\"minor\":\"9223372036854775807\""))
        assertTrue(bytes.decodeToString().contains("\"branchId\":null"))
    }

    @Test fun policyNullLimitsTargetsSubjectAndCeilingRemainNullNotUnlimitedDefaults() {
        val original = codecPolicy(grants = listOf(codecGrant()))
        val bytes = ProtectedLocalSecurityCodec.encodePolicy(original)
        val grant = ProtectedLocalSecurityCodec.decodePolicy(original.binding(), bytes).grants.single()
        assertNull(grant.limits.maximumAmount)
        assertNull(grant.limits.maximumPercentageBasisPoints)
        assertNull(grant.namedSubjectId)
        assertNull(grant.targetIds)
        assertNull(grant.delegationCeiling)
        assertTrue(bytes.decodeToString().contains("\"namedSubjectId\":null,\"targetIds\":null,\"delegationCeiling\":null"))
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodePolicy(original.binding(),
            bytes.change(JsonArray(emptyList()), "grants", "0", "targetIds")) }
    }

    @Test fun grantListOrderAndDuplicatesRemainMeaningfulInsteadOfSetNormalization() {
        val allow = codecGrant()
        val deny = codecGrant(effect = GrantEffect.DENY)
        val original = codecPolicy(grants = listOf(deny, allow, deny))
        val decoded = ProtectedLocalSecurityCodec.decodePolicy(original.binding(), ProtectedLocalSecurityCodec.encodePolicy(original))
        assertEquals(listOf(GrantEffect.DENY, GrantEffect.ALLOW, GrantEffect.DENY), decoded.grants.map { it.effect })
        assertEquals(3, decoded.grants.size)
    }

    @Test fun targetSetInsertionOrderHasOneSortedEncodingButDuplicateWireIdsAreInvalid() {
        val a = codecPolicy(grants = listOf(codecGrant(targets = linkedSetOf(codecId(12), codecId(11)))))
        val b = codecPolicy(grants = listOf(codecGrant(targets = linkedSetOf(codecId(11), codecId(12)))))
        val bytes = ProtectedLocalSecurityCodec.encodePolicy(a)
        assertContentEquals(bytes, ProtectedLocalSecurityCodec.encodePolicy(b))
        val targets = bytes.root().getValue("grants").jsonArray[0].jsonObject.getValue("targetIds").jsonArray
        for (invalid in listOf(JsonArray(targets.reversed()), JsonArray(listOf(targets.first(), targets.first())))) {
            assertCodecUnavailable { ProtectedLocalSecurityCodec.decodePolicy(a.binding(), bytes.change(invalid, "grants", "0", "targetIds")) }
        }
    }

    @Test fun maximumTargetCountRoundTripsAndOneMoreIsRejectedWithinByteBudget() {
        val original = codecPolicy(grants = listOf(codecGrant(targets = (1000..1255).map(::codecId).toSet())))
        val bytes = ProtectedLocalSecurityCodec.encodePolicy(original)
        assertEquals(256, assertNotNull(ProtectedLocalSecurityCodec.decodePolicy(original.binding(), bytes).grants.single().targetIds).size)
        val targets = bytes.root().getValue("grants").jsonArray[0].jsonObject.getValue("targetIds").jsonArray
        val invalid = bytes.change(JsonArray(targets + JsonPrimitive(codecId(9999).value)), "grants", "0", "targetIds")
        assertTrue(invalid.size < ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes)
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodePolicy(original.binding(), invalid) }
    }

    @Test fun allOneThousandTwentyFourGrantsRoundTripWithoutDroppingOrCombining() {
        val original = codecPolicy(grants = List(1024) { codecGrant(effect = if (it % 2 == 0) GrantEffect.ALLOW else GrantEffect.DENY) })
        val bytes = ProtectedLocalSecurityCodec.encodePolicy(original)
        assertTrue(bytes.size > 65_536 && bytes.size < ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes)
        val decoded = ProtectedLocalSecurityCodec.decodePolicy(original.binding(), bytes)
        assertEquals(1024, decoded.grants.size)
        assertEquals(original.grants.map { it.effect }, decoded.grants.map { it.effect })
        val grants = bytes.root().getValue("grants").jsonArray
        val invalid = bytes.change(JsonArray(grants + grants.first()), "grants")
        assertTrue(invalid.size < ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes)
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodePolicy(original.binding(), invalid) }
    }

    @Test fun legalGrantAndTargetCountsCanStillExceedTheIndependentPolicyByteBudget() {
        val grant = codecGrant(targets = (1000..1255).map(::codecId).toSet())
        val original = codecPolicy(grants = List(128) { grant }) // Every public count constraint is satisfied.
        assertEquals(128, original.grants.size)
        assertEquals(256, assertNotNull(original.grants.first().targetIds).size)
        val one = ProtectedLocalSecurityCodec.encodePolicy(codecPolicy(grants = listOf(grant)))
        val grantJson = one.root().getValue("grants").jsonArray.single()
        val oversized = one.change(JsonArray(List(128) { grantJson }), "grants")
        assertTrue(oversized.size > ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes)
        assertCodecUnavailable { ProtectedLocalSecurityCodec.encodePolicy(original) }
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodePolicy(original.binding(), oversized) }
    }

    @Test fun largeButBoundedPolicyKeepsAllTargetsRatherThanSilentlyNarrowing() {
        val targets = (1000..1255).map(::codecId).toSet()
        val original = codecPolicy(grants = List(80) { codecGrant(targets = targets) })
        val bytes = ProtectedLocalSecurityCodec.encodePolicy(original)
        assertTrue(bytes.size > 65_536 && bytes.size <= ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes)
        val decoded = ProtectedLocalSecurityCodec.decodePolicy(original.binding(), bytes)
        assertEquals(80, decoded.grants.size)
        assertTrue(decoded.grants.all { it.targetIds == targets })
    }

    @Test fun foreignGrantScopesAndNamedSubjectsRejectWholePolicyWithoutRetagging() {
        val original = codecPolicy(grants = listOf(codecGrant(scope = CODEC_SCOPE.company(), named = CODEC_ACTOR)))
        val bytes = ProtectedLocalSecurityCodec.encodePolicy(original)
        val variants = listOf(bytes.change(JsonPrimitive(codecId(98).value), "grants", "0", "scope", "organizationId"),
            bytes.change(JsonPrimitive(codecId(98).value), "grants", "0", "scope", "companyId"),
            bytes.change(JsonPrimitive(codecId(98).value), "grants", "0", "scope", "branchId"),
            bytes.change(JsonPrimitive(codecId(98).value), "grants", "0", "namedSubjectId"))
        for (variant in variants) assertCodecUnavailable { ProtectedLocalSecurityCodec.decodePolicy(original.binding(), variant) }
    }

    @Test fun everyGrantEnumAndMoneyLimitIsClosedAndValidated() {
        val original = codecPolicy(grants = listOf(codecGrant(limits = PermissionLimits(Money(Currency.EGP, 100), 500),
            ceiling = PermissionLimits(Money(Currency.EGP, 90), 400))))
        val bytes = ProtectedLocalSecurityCodec.encodePolicy(original)
        val variants = listOf(bytes.change(JsonPrimitive("UNKNOWN"), "grants", "0", "permission", "action"),
            bytes.change(JsonPrimitive("UNKNOWN"), "grants", "0", "permission", "verb"),
            bytes.change(JsonPrimitive("UNKNOWN"), "grants", "0", "effect"),
            bytes.change(JsonPrimitive("UNKNOWN"), "grants", "0", "ownerAccess"),
            bytes.change(JsonPrimitive("-1"), "grants", "0", "limits", "maximumAmount", "minor"),
            bytes.change(JsonPrimitive(100), "grants", "0", "limits", "maximumAmount", "minor"),
            bytes.change(JsonPrimitive("USD"), "grants", "0", "limits", "maximumAmount", "currency"),
            bytes.change(JsonPrimitive(10001), "grants", "0", "limits", "maximumPercentageBasisPoints"),
            bytes.change(JsonPrimitive(-1), "grants", "0", "delegationCeiling", "maximumPercentageBasisPoints"))
        for (variant in variants) assertCodecUnavailable { ProtectedLocalSecurityCodec.decodePolicy(original.binding(), variant) }
    }

    @Test fun sessionAndThrottlePolicyBoundsAndLongStringFormsAreEnforced() {
        val original = codecPolicy()
        val bytes = ProtectedLocalSecurityCodec.encodePolicy(original)
        val variants = listOf(bytes.change(JsonPrimitive(0), "sessionPolicy", "maximumConcurrentSessions"),
            bytes.change(JsonPrimitive(17), "sessionPolicy", "maximumConcurrentSessions"),
            bytes.change(JsonPrimitive(6), "throttlePolicy", "freeFailures"),
            bytes.change(JsonPrimitive("999"), "throttlePolicy", "initialDelayMillis"),
            bytes.change(JsonPrimitive("60001"), "throttlePolicy", "initialDelayMillis"),
            bytes.change(JsonPrimitive("86400001"), "throttlePolicy", "maximumDelayMillis"),
            bytes.change(JsonPrimitive("999"), "throttlePolicy", "maximumDelayMillis"),
            bytes.change(JsonPrimitive("999"), "throttlePolicy", "attemptTimeoutMillis"),
            bytes.change(JsonPrimitive("300001"), "throttlePolicy", "attemptTimeoutMillis"))
        for (variant in variants) assertCodecUnavailable { ProtectedLocalSecurityCodec.decodePolicy(original.binding(), variant) }
        for (field in listOf("initialDelayMillis", "maximumDelayMillis", "attemptTimeoutMillis")) {
            assertCodecUnavailable { ProtectedLocalSecurityCodec.decodePolicy(original.binding(), bytes.change(JsonPrimitive(1000), "throttlePolicy", field)) }
        }
    }

    @Test fun admittedDeviceKeepsOpaqueDescriptorsAndSortedCapabilitiesOnly() {
        val original = codecAdmission(admitted = true, identifier = "key:\\opaque\"identifier", fingerprint = "not-a-cryptographic-proof",
            capabilities = linkedSetOf("zebra", "Alpha", "scan.code-1"))
        val bytes = ProtectedLocalSecurityCodec.encodeAdmission(original)
        val decoded = ProtectedLocalSecurityCodec.decodeAdmission(original.binding(), bytes)
        assertTrue(decoded.admitted)
        assertEquals(original.publicKeyIdentifier, decoded.publicKeyIdentifier)
        assertEquals(original.publicFingerprint, decoded.publicFingerprint)
        assertEquals(original.capabilities, decoded.capabilities)
        assertEquals(listOf("Alpha", "scan.code-1", "zebra"), decoded.capabilities.toList())
        val reordered = codecAdmission(admitted = true, identifier = original.publicKeyIdentifier,
            fingerprint = original.publicFingerprint, capabilities = linkedSetOf("scan.code-1", "zebra", "Alpha"))
        assertContentEquals(bytes, ProtectedLocalSecurityCodec.encodeAdmission(reordered))
    }

    @Test fun admittedDeviceRequiresAtLeastOneDescriptorButUnadmittedNullsAreRetained() {
        val original = codecAdmission()
        val bytes = ProtectedLocalSecurityCodec.encodeAdmission(original)
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAdmission(original.binding(), bytes.change(JsonPrimitive(true), "admitted")) }
        for (oneDescriptor in listOf(codecAdmission(admitted = true, identifier = "k"), codecAdmission(admitted = true, fingerprint = "f"))) {
            val decoded = ProtectedLocalSecurityCodec.decodeAdmission(oneDescriptor.binding(), ProtectedLocalSecurityCodec.encodeAdmission(oneDescriptor))
            assertEquals(oneDescriptor.publicKeyIdentifier, decoded.publicKeyIdentifier)
            assertEquals(oneDescriptor.publicFingerprint, decoded.publicFingerprint)
        }
    }

    @Test fun maximumAdmissionDescriptorsAndSixtyFourCapabilitiesFitTheirOwnProfile() {
        val capabilities = (0..63).map { "cap_${it.toString().padStart(2, '0')}_" + "x".repeat(57) }.toSet()
        assertTrue(capabilities.all { it.length == 64 })
        val original = codecAdmission(admitted = true, identifier = "\"".repeat(512), fingerprint = "\\".repeat(512), capabilities = capabilities)
        val bytes = ProtectedLocalSecurityCodec.encodeAdmission(original)
        assertTrue(bytes.size <= ProtectedLocalTable.ADMISSION.maximumPlaintextBytes)
        val decoded = ProtectedLocalSecurityCodec.decodeAdmission(original.binding(), bytes)
        assertEquals(original.publicKeyIdentifier, decoded.publicKeyIdentifier)
        assertEquals(original.publicFingerprint, decoded.publicFingerprint)
        assertEquals(capabilities, decoded.capabilities)
        val extra = bytes.change(JsonArray(bytes.root().getValue("capabilities").jsonArray + JsonPrimitive("z_extra")), "capabilities")
        assertTrue(extra.size < ProtectedLocalTable.ADMISSION.maximumPlaintextBytes)
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAdmission(original.binding(), extra) }
    }

    @Test fun admissionDescriptorAndCapabilityCharacterBoundsRejectPrivateOrNonAsciiText() {
        val original = codecAdmission()
        val bytes = ProtectedLocalSecurityCodec.encodeAdmission(original)
        for (descriptor in listOf("", "x".repeat(513), "a b", "a\u0000b", "\u007f", "é")) {
            for (field in listOf("publicKeyIdentifier", "publicFingerprint")) {
                assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAdmission(original.binding(), bytes.change(JsonPrimitive(descriptor), field)) }
            }
        }
        for (capability in listOf("", "x".repeat(65), "contains space", "slash/name", "é", "a\u0000b")) {
            assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAdmission(original.binding(), bytes.change(JsonArray(listOf(JsonPrimitive(capability))), "capabilities")) }
        }
    }

    @Test fun duplicateOrNoncanonicalCapabilityOrderIsRejectedRatherThanDeduplicated() {
        val original = codecAdmission(capabilities = setOf("a", "b"))
        val bytes = ProtectedLocalSecurityCodec.encodeAdmission(original)
        for (capabilities in listOf(listOf("b", "a"), listOf("a", "a"))) {
            assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeAdmission(original.binding(),
                bytes.change(JsonArray(capabilities.map(::JsonPrimitive)), "capabilities")) }
        }
    }

    @Test fun deviceThrottlePreservesPendingAttemptBootAndExactLargeMonotonicCounters() {
        val original = codecThrottle(dimension = ThrottleDimension.Device(CODEC_DEVICE)).copy(
            revision = Revision(Long.MAX_VALUE), failures = Int.MAX_VALUE,
            lastObservation = MonotonicMoment(codecId(8), Long.MAX_VALUE), remainingDelayMillis = 86_400_000,
            activeAttempt = ThrottleAttempt(codecId(20), MonotonicMoment(codecId(9), 9_007_199_254_740_993)))
        val bytes = ProtectedLocalSecurityCodec.encodeThrottle(original)
        val decoded = ProtectedLocalSecurityCodec.decodeThrottle(original.binding(), bytes)
        assertEquals(original, decoded)
        assertTrue(bytes.decodeToString().contains("\"elapsedMillis\":\"9223372036854775807\""))
        assertTrue(bytes.decodeToString().contains("\"elapsedMillis\":\"9007199254740993\""))
        assertTrue(bytes.decodeToString().contains("\"remainingDelayMillis\":\"86400000\""))
    }

    @Test fun throttleBindingRequiresTheExactAccountOrDeviceDimension() {
        val account = codecThrottle()
        val device = codecThrottle(dimension = ThrottleDimension.Device(CODEC_DEVICE))
        val accountBytes = ProtectedLocalSecurityCodec.encodeThrottle(account)
        val deviceBytes = ProtectedLocalSecurityCodec.encodeThrottle(device)
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeThrottle(device.binding(), accountBytes) }
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeThrottle(account.binding(), deviceBytes) }
        val other = device.copy(key = device.key.copy(dimension = ThrottleDimension.Device(codecId(90))))
        assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeThrottle(other.binding(), deviceBytes) }
    }

    @Test fun malformedDimensionCombinationsNeverBecomeAnAccountBucket() {
        val account = codecThrottle()
        val bytes = ProtectedLocalSecurityCodec.encodeThrottle(account)
        val variants = listOf(bytes.change(JsonPrimitive("unknown"), "dimension", "kind"),
            bytes.change(JsonPrimitive("device"), "dimension", "kind"),
            bytes.change(JsonPrimitive(CODEC_DEVICE.value), "dimension", "deviceId"))
        for (variant in variants) assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeThrottle(account.binding(), variant) }
    }

    @Test fun throttleCountersDurationsAndPendingMomentTypesRemainBoundedAndExact() {
        val original = codecThrottle().copy(activeAttempt = ThrottleAttempt(codecId(20), MonotonicMoment(codecId(8), 0)))
        val bytes = ProtectedLocalSecurityCodec.encodeThrottle(original)
        val variants = listOf(bytes.change(JsonPrimitive(-1), "failures"), bytes.change(JsonPrimitive(2147483648L), "failures"),
            bytes.change(JsonPrimitive("-1"), "remainingDelayMillis"), bytes.change(JsonPrimitive("86400001"), "remainingDelayMillis"),
            bytes.change(JsonPrimitive(0), "remainingDelayMillis"), bytes.change(JsonPrimitive("-1"), "lastObservation", "elapsedMillis"),
            bytes.change(JsonPrimitive(0), "lastObservation", "elapsedMillis"), bytes.change(JsonPrimitive(0), "activeAttempt", "startedAt", "elapsedMillis"),
            bytes.change(JsonPrimitive("PRIVATE attempt"), "activeAttempt", "attemptId"))
        for (variant in variants) assertCodecUnavailable { ProtectedLocalSecurityCodec.decodeThrottle(original.binding(), variant) }
    }
}

private const val CODEC_ACTOR_TEXT = "00000000-0000-4000-8000-000000000004"
private const val CODEC_DEVICE_TEXT = "00000000-0000-4000-8000-000000000005"
private const val CODEC_SCOPE_JSON = """{"organizationId":"00000000-0000-4000-8000-000000000001","companyId":"00000000-0000-4000-8000-000000000002","branchId":"00000000-0000-4000-8000-000000000003"}"""
private val CODEC_SCOPE = BranchScope(codecId(1), codecId(2), codecId(3))
private val CODEC_ACTOR = DomainId(CODEC_ACTOR_TEXT)
private val CODEC_DEVICE = DomainId(CODEC_DEVICE_TEXT)

private fun codecId(number: Int): DomainId = DomainId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

private fun codecCredential(revision: Revision = Revision(0), salt: ByteArray = ByteArray(16) { it.toByte() }, iterations: Int = 600_000): PasswordHashRecord =
    PasswordHashRecord(CredentialBinding(CODEC_SCOPE, CODEC_ACTOR, revision), PasswordHashParameters(iterations),
        ProtectedPepperReference(codecId(6)), salt, ByteArray(32) { (0xa0 + it).toByte() })

private fun codecAccount(
    revision: Revision = Revision(0),
    generation: SessionGeneration = SessionGeneration(0),
    sessions: List<LocalSessionRecord> = emptyList(),
    credentialRevision: Revision = Revision(0),
    credential: PasswordHashRecord? = null,
): ProtectedLocalAccount = ProtectedLocalAccount(
    LocalAccountSessionState(CODEC_SCOPE, CODEC_ACTOR, true, revision, generation, sessions), credentialRevision, credential)

private fun codecSession(number: Int): LocalSessionRecord = LocalSessionRecord(codecId(1000 + number), CODEC_SCOPE, CODEC_ACTOR,
    codecId(2000 + number), SessionGeneration(number.toLong()), SessionStatus.REVOKED,
    AuthenticationPurpose.entries[number % AuthenticationPurpose.entries.size])

private fun codecPolicy(
    grants: List<PermissionGrant> = emptyList(),
    revision: Revision = Revision(0),
    sessionPolicy: LocalSessionPolicy = LocalSessionPolicy(),
    throttlePolicy: ThrottlePolicy = ThrottlePolicy(),
): ActivatedLocalPolicy = ActivatedLocalPolicy(CODEC_SCOPE, CODEC_ACTOR, revision, grants, sessionPolicy, throttlePolicy)

private fun codecGrant(
    scope: TenantScope = CODEC_SCOPE,
    action: SecurityAction = SecurityAction.DISCOUNT,
    verb: PermissionVerb = PermissionVerb.PERFORM,
    effect: GrantEffect = GrantEffect.ALLOW,
    limits: PermissionLimits = PermissionLimits(),
    owner: OwnerAccess = OwnerAccess.OWN_ONLY,
    named: DomainId? = null,
    targets: Set<DomainId>? = null,
    ceiling: PermissionLimits? = null,
): PermissionGrant = PermissionGrant(Permission(action, verb), scope, effect, limits, owner, named, targets, ceiling)

private fun codecAdmission(
    admitted: Boolean = false,
    identifier: String? = null,
    fingerprint: String? = null,
    capabilities: Set<String> = emptySet(),
): LocalDeviceAdmission = LocalDeviceAdmission(CODEC_SCOPE, CODEC_DEVICE, Revision(0), admitted, identifier, fingerprint, capabilities)

private fun codecThrottle(dimension: ThrottleDimension = ThrottleDimension.Account): PersistentThrottleState =
    PersistentThrottleState(ThrottleKey(CODEC_SCOPE, CODEC_ACTOR, dimension), Revision(0), 0,
        MonotonicMoment(codecId(8), 0), 0, null)

private fun ProtectedLocalAccount.binding(): ProtectedLocalBinding = state.let {
    ProtectedLocalBinding(ProtectedLocalRecordKey.Account(it.scope, it.actorId), it.revision)
}
private fun ActivatedLocalPolicy.binding(): ProtectedLocalBinding = ProtectedLocalBinding(ProtectedLocalRecordKey.EffectivePolicy(scope, actorId), revision)
private fun LocalDeviceAdmission.binding(): ProtectedLocalBinding = ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(scope, deviceId), revision)
private fun PersistentThrottleState.binding(): ProtectedLocalBinding = ProtectedLocalBinding(ProtectedLocalRecordKey.Throttle(key), revision)

private class CodecCase(
    val table: ProtectedLocalTable,
    val binding: ProtectedLocalBinding,
    val bytes: ByteArray,
    val decode: (ProtectedLocalBinding, ByteArray) -> Any,
)

private fun codecCases(): List<CodecCase> = listOf(
    codecAccount().let { CodecCase(ProtectedLocalTable.ACCOUNT, it.binding(), ProtectedLocalSecurityCodec.encodeAccount(it), ProtectedLocalSecurityCodec::decodeAccount) },
    codecPolicy().let { CodecCase(ProtectedLocalTable.EFFECTIVE_POLICY, it.binding(), ProtectedLocalSecurityCodec.encodePolicy(it), ProtectedLocalSecurityCodec::decodePolicy) },
    codecAdmission().let { CodecCase(ProtectedLocalTable.ADMISSION, it.binding(), ProtectedLocalSecurityCodec.encodeAdmission(it), ProtectedLocalSecurityCodec::decodeAdmission) },
    codecThrottle().let { CodecCase(ProtectedLocalTable.THROTTLE, it.binding(), ProtectedLocalSecurityCodec.encodeThrottle(it), ProtectedLocalSecurityCodec::decodeThrottle) },
)

private fun ProtectedLocalRecordKey.rekey(scope: BranchScope = this.scope, id: DomainId = recordId): ProtectedLocalRecordKey = when (this) {
    is ProtectedLocalRecordKey.Account -> copy(scope = scope, actorId = id)
    is ProtectedLocalRecordKey.EffectivePolicy -> copy(scope = scope, actorId = id)
    is ProtectedLocalRecordKey.Admission -> copy(scope = scope, deviceId = id)
    is ProtectedLocalRecordKey.Throttle -> copy(key = key.copy(scope = scope, actorId = id))
    is ProtectedLocalRecordKey.SessionIssuance -> copy(scope = scope, sessionId = id)
    is ProtectedLocalRecordKey.ActionApproval,
    is ProtectedLocalRecordKey.AuthConfiguration,
    is ProtectedLocalRecordKey.AuthEvent,
    is ProtectedLocalRecordKey.RefreshIssuance,
    is ProtectedLocalRecordKey.SessionArchive -> error("Unsupported protected record key in legacy fixture")
}

private fun assertCodecUnavailable(block: () -> Any?): IllegalStateException {
    val failure = assertFailsWith<IllegalStateException> { block() }
    assertEquals("Protected local record unavailable", failure.message)
    assertNull(failure.cause)
    assertTrue(failure.suppressedExceptions.isEmpty())
    return failure
}

private fun assertGrantEquals(expected: PermissionGrant, actual: PermissionGrant) {
    assertEquals(expected.permission, actual.permission)
    assertEquals(expected.scope, actual.scope)
    assertEquals(expected.effect, actual.effect)
    assertEquals(expected.limits, actual.limits)
    assertEquals(expected.ownerAccess, actual.ownerAccess)
    assertEquals(expected.namedSubjectId, actual.namedSubjectId)
    assertEquals(expected.targetIds, actual.targetIds)
    assertEquals(expected.delegationCeiling, actual.delegationCeiling)
}

private fun ByteArray.root(): JsonObject {
    val text = decodeToString(throwOnInvalidSequence = true)
    val result = Json.parseToJsonElement(text).jsonObject
    // Negative fixtures change only their named field, not incidental pretty-printing or order.
    assertEquals(text, result.toString())
    return result
}

private fun JsonElement.bytes(): ByteArray = toString().encodeToByteArray()

private fun ByteArray.change(value: JsonElement, vararg path: String): ByteArray {
    require(path.isNotEmpty())
    fun replace(element: JsonElement, depth: Int): JsonElement {
        if (depth == path.size) return value
        return when (element) {
            is JsonObject -> {
                val name = path[depth]
                val fields = element.toMutableMap()
                fields[name] = replace(element.getValue(name), depth + 1)
                JsonObject(fields)
            }
            is JsonArray -> {
                val index = path[depth].toInt()
                val values = element.toMutableList()
                values[index] = replace(element[index], depth + 1)
                JsonArray(values)
            }
            else -> error("Synthetic fixture path is not a container")
        }
    }
    return replace(root(), 0).bytes()
}
