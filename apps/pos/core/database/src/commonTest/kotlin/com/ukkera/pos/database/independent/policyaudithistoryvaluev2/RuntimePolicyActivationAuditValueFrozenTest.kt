package com.ukkera.pos.database.independent.policyaudithistoryvaluev2

import com.ukkera.pos.database.LocalAuthenticationCodec
import com.ukkera.pos.database.requireRuntimePolicyActivationAuditValue
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAuthEvent
import com.ukkera.pos.security.LocalAuthEventKind
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Frozen policy-audit-history-value-v2 DATA cases only. The codec prepares C0;
 * fixed fields and match/refusal expectations come from the frozen fixtures.
 * Synthetic bindings are not protected rows, native history or authority.
 */
class RuntimePolicyActivationAuditValueFrozenTest {
    @Test
    fun separatelyRestoredCompleteValuesMatchOriginalCanonicalLiteral() {
        val original = originalEvent()
        val c0 = LocalAuthenticationCodec.encodeEvent(original)
        val originalBytes = c0.copyOf()
        assertOriginalLiteral(c0, "PAHV-V2-DATA-01")

        val restored = LocalAuthenticationCodec.decodeEvent(bindingFor(fixtureFields()), c0)
        val independentlyMaterialized = originalEvent()
        assertNotSame(original, restored, "PAHV-V2-DATA-01 restored allocation")
        assertNotSame(original, independentlyMaterialized, "PAHV-V2-DATA-01 independent allocation")
        assertNotSame(restored, independentlyMaterialized, "PAHV-V2-DATA-01 separate inputs")

        for (value in listOf(restored, independentlyMaterialized)) {
            assertEquals(fixtureFields(), observedFields(value), "PAHV-V2-DATA-01 complete fields")
            requireRuntimePolicyActivationAuditValue(original, c0, value)
            assertContentEquals(c0, LocalAuthenticationCodec.encodeEvent(value), "PAHV-V2-DATA-01 readback")
        }
        assertOriginalsUnchanged("PAHV-V2-DATA-01", c0, originalBytes,
            original, restored, independentlyMaterialized)
    }

    @Test
    fun allFrozenFieldLiteralAndNonEventMismatchesRefuseWithoutChangingPriorValues() {
        val original = originalEvent()
        val c0 = LocalAuthenticationCodec.encodeEvent(original)
        val originalBytes = c0.copyOf()
        val priorRestored = LocalAuthenticationCodec.decodeEvent(bindingFor(fixtureFields()), c0)
        val priorIndependent = originalEvent()
        assertOriginalLiteral(c0, "PAHV-V2-DATA-02 baseline")
        requireRuntimePolicyActivationAuditValue(original, c0, priorRestored)
        requireRuntimePolicyActivationAuditValue(original, c0, priorIndependent)

        val fieldVariants = fieldVariants()
        assertEquals(13, fieldVariants.size, "frozen field-variant count")
        for (variant in fieldVariants) {
            val freshOriginal = originalEvent()
            val freshC0 = LocalAuthenticationCodec.encodeEvent(freshOriginal)
            assertContentEquals(originalBytes, freshC0, "${variant.id} fresh C0")
            val changedFields = fixtureFields() + (variant.path to variant.value)
            val candidateBytes = replaceExactlyOnce(freshC0,
                "\"${variant.path.substringAfterLast('.')}\":${fixtureFields().getValue(variant.path)}",
                "\"${variant.path.substringAfterLast('.')}\":${variant.value}", variant.id)
            val candidateSnapshot = candidateBytes.copyOf()

            // Controls are outside the refusal boundary; no altered bytes are reencoded.
            assertEquals(replaceField(literalObject(freshC0), variant.path, variant.value),
                literalObject(candidateBytes), "${variant.id} exactly one field")
            assertEquals(changedFields, literalFields(literalObject(candidateBytes)), "${variant.id} input fields")
            val candidate = decodeOrRefuse(changedFields, candidateBytes)
            if (candidate != null) {
                assertNotSame(freshOriginal, candidate, "${variant.id} restored allocation")
                assertEquals(changedFields, observedFields(candidate), "${variant.id} restored fields")
                assertFails(variant.id) {
                    requireRuntimePolicyActivationAuditValue(freshOriginal, freshC0, candidate)
                }
            }
            assertContentEquals(candidateSnapshot, candidateBytes, "${variant.id} candidate literal unchanged")
            assertOriginalsUnchanged(variant.id, freshC0, originalBytes, freshOriginal)
            assertOriginalsUnchanged(variant.id, c0, originalBytes, original, priorRestored, priorIndependent)
        }

        assertEquals(7, LiteralVariant.entries.size, "frozen literal-variant count")
        for (variant in LiteralVariant.entries) {
            val freshOriginal = originalEvent()
            val freshC0 = LocalAuthenticationCodec.encodeEvent(freshOriginal)
            assertContentEquals(originalBytes, freshC0, "${variant.id} fresh C0")
            val unchangedRestored = LocalAuthenticationCodec.decodeEvent(bindingFor(fixtureFields()), freshC0)
            assertNotSame(freshOriginal, unchangedRestored, "${variant.id} restored allocation")
            assertEquals(fixtureFields(), observedFields(unchangedRestored), "${variant.id} unchanged input fields")
            val changedLiteral = mutateLiteral(variant, freshC0)
            val changedSnapshot = changedLiteral.copyOf()
            assertLiteralMutation(variant, freshC0, changedLiteral)
            assertFails(variant.id) {
                // The real literal/value boundary receives the exact changed bytes.
                requireRuntimePolicyActivationAuditValue(freshOriginal, changedLiteral, unchangedRestored)
            }
            assertContentEquals(changedSnapshot, changedLiteral, "${variant.id} changed literal unchanged")
            assertOriginalsUnchanged(variant.id, freshC0, originalBytes, freshOriginal, unchangedRestored)
            assertOriginalsUnchanged(variant.id, c0, originalBytes, original, priorRestored, priorIndependent)
        }

        val nonEventOriginal = originalEvent()
        val nonEventC0 = LocalAuthenticationCodec.encodeEvent(nonEventOriginal)
        assertContentEquals(originalBytes, nonEventC0, "PAHV-V2-DATA-02-NON-EVENT fresh C0")
        assertFails("PAHV-V2-DATA-02-NON-EVENT") {
            requireRuntimePolicyActivationAuditValue(nonEventOriginal, nonEventC0, "synthetic non-event")
        }
        assertOriginalsUnchanged("PAHV-V2-DATA-02-NON-EVENT", nonEventC0, originalBytes, nonEventOriginal)
        assertOriginalsUnchanged("PAHV-V2-DATA-02 final readback", c0, originalBytes,
            original, priorRestored, priorIndependent)
    }
}

private const val PROFILE = "ukkera.local-auth-event/v1"
private const val ORGANIZATION_ID = "a1000000-0000-4000-8000-000000000001"
private const val COMPANY_ID = "a2000000-0000-4000-8000-000000000001"
private const val BRANCH_ID = "a3000000-0000-4000-8000-000000000001"
private const val EVENT_ID = "c1000000-0000-4000-8000-000000000001"
private const val CHANGED_EVENT_ID = "c1000000-0000-4000-8000-000000000002"
private const val TARGET_ID = "a4000000-0000-4000-8000-000000000001"
private const val ACCOUNT_REVISION = "9007199254740993"
private const val BOOT_ID = "f1000000-0000-4000-8000-000000000001"
private const val ELAPSED_MILLIS = "123456789"
private const val CONTENT_DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

private fun originalEvent(eventId: String = EVENT_ID): LocalAuthEvent = LocalAuthEvent(
    scope = BranchScope(DomainId(ORGANIZATION_ID), DomainId(COMPANY_ID), DomainId(BRANCH_ID)),
    eventId = DomainId(eventId),
    kind = LocalAuthEventKind.POLICY_ACTIVATED,
    targetId = DomainId(TARGET_ID),
    operatorId = null,
    sessionId = null,
    accountRevision = Revision(ACCOUNT_REVISION.toLong()),
    at = MonotonicMoment(DomainId(BOOT_ID), ELAPSED_MILLIS.toLong()),
    contentDigest = PayloadDigest(CONTENT_DIGEST),
)

private fun fixtureFields(): Map<String, JsonElement> = mapOf(
    "scope.organizationId" to JsonPrimitive(ORGANIZATION_ID),
    "scope.companyId" to JsonPrimitive(COMPANY_ID),
    "scope.branchId" to JsonPrimitive(BRANCH_ID),
    "eventId" to JsonPrimitive(EVENT_ID),
    "kind" to JsonPrimitive("POLICY_ACTIVATED"),
    "targetId" to JsonPrimitive(TARGET_ID),
    "operatorId" to JsonNull,
    "sessionId" to JsonNull,
    "accountRevision" to JsonPrimitive(ACCOUNT_REVISION),
    "at.bootId" to JsonPrimitive(BOOT_ID),
    "at.elapsedMillis" to JsonPrimitive(ELAPSED_MILLIS),
    "contentDigest" to JsonPrimitive(CONTENT_DIGEST),
    "revision" to JsonPrimitive("0"),
)

private fun observedFields(value: LocalAuthEvent): Map<String, JsonElement> = mapOf(
    "scope.organizationId" to JsonPrimitive(value.scope.organizationId.value),
    "scope.companyId" to JsonPrimitive(value.scope.companyId.value),
    "scope.branchId" to JsonPrimitive(value.scope.branchId.value),
    "eventId" to JsonPrimitive(value.eventId.value),
    "kind" to JsonPrimitive(value.kind.name),
    "targetId" to JsonPrimitive(value.targetId.value),
    "operatorId" to nullableString(value.operatorId?.value),
    "sessionId" to nullableString(value.sessionId?.value),
    "accountRevision" to nullableString(value.accountRevision?.value?.toString()),
    "at.bootId" to JsonPrimitive(value.at.bootId.value),
    "at.elapsedMillis" to JsonPrimitive(value.at.elapsedMillis.toString()),
    "contentDigest" to nullableString(value.contentDigest?.value),
    "revision" to JsonPrimitive(value.revision.value.toString()),
)

private fun nullableString(value: String?): JsonElement = value?.let { JsonPrimitive(it) } ?: JsonNull

private fun literalObject(bytes: ByteArray): JsonObject =
    Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject

private fun literalFields(literal: JsonObject): Map<String, JsonElement> = fixtureFields().keys.associateWith { path ->
    if ('.' in path) literal.getValue(path.substringBefore('.')).jsonObject.getValue(path.substringAfter('.'))
    else literal.getValue(path)
}

private fun assertOriginalLiteral(bytes: ByteArray, id: String) {
    val literal = literalObject(bytes)
    assertEquals(JsonPrimitive(PROFILE), literal.getValue("schema"), "$id profile")
    // JsonPrimitive(String) equality distinguishes decimal strings from numbers;
    // getValue also requires both explicit null slots to be present.
    assertEquals(fixtureFields(), literalFields(literal), "$id complete literal fields")
}

private fun assertOriginalsUnchanged(id: String, bytes: ByteArray, snapshot: ByteArray, vararg values: LocalAuthEvent) {
    assertContentEquals(snapshot, bytes, "$id original literal unchanged")
    assertOriginalLiteral(bytes, id)
    for (value in values) {
        assertEquals(fixtureFields(), observedFields(value), "$id original/restored fields unchanged")
        assertContentEquals(snapshot, LocalAuthenticationCodec.encodeEvent(value), "$id canonical readback unchanged")
    }
}

private fun bindingFor(fields: Map<String, JsonElement>): ProtectedLocalBinding = ProtectedLocalBinding(
    ProtectedLocalRecordKey.AuthEvent(
        BranchScope(DomainId(fields.getValue("scope.organizationId").jsonPrimitive.content),
            DomainId(fields.getValue("scope.companyId").jsonPrimitive.content),
            DomainId(fields.getValue("scope.branchId").jsonPrimitive.content)),
        DomainId(fields.getValue("eventId").jsonPrimitive.content),
    ),
    Revision(0),
)

private fun decodeOrRefuse(fields: Map<String, JsonElement>, bytes: ByteArray): LocalAuthEvent? = try {
    LocalAuthenticationCodec.decodeEvent(bindingFor(fields), bytes)
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    // Frozen negatives permit public decode/validation refusal, with no assigned code.
    null
}

private data class FieldVariant(val id: String, val path: String, val value: JsonElement)

private fun fieldVariants(): List<FieldVariant> = listOf(
    FieldVariant("PAHV-V2-DATA-02-ORGANIZATION", "scope.organizationId", JsonPrimitive("a1000000-0000-4000-8000-000000000002")),
    FieldVariant("PAHV-V2-DATA-02-COMPANY", "scope.companyId", JsonPrimitive("a2000000-0000-4000-8000-000000000002")),
    FieldVariant("PAHV-V2-DATA-02-BRANCH", "scope.branchId", JsonPrimitive("a3000000-0000-4000-8000-000000000002")),
    FieldVariant("PAHV-V2-DATA-02-REQUEST", "eventId", JsonPrimitive(CHANGED_EVENT_ID)),
    FieldVariant("PAHV-V2-DATA-02-KIND", "kind", JsonNull),
    FieldVariant("PAHV-V2-DATA-02-TARGET", "targetId", JsonPrimitive("a4000000-0000-4000-8000-000000000002")),
    FieldVariant("PAHV-V2-DATA-02-OPERATOR", "operatorId", JsonPrimitive("a4000000-0000-4000-8000-000000000003")),
    FieldVariant("PAHV-V2-DATA-02-SESSION", "sessionId", JsonPrimitive("a6000000-0000-4000-8000-000000000001")),
    FieldVariant("PAHV-V2-DATA-02-ACCOUNT-REVISION", "accountRevision", JsonPrimitive("9007199254740994")),
    FieldVariant("PAHV-V2-DATA-02-BOOT", "at.bootId", JsonPrimitive("f1000000-0000-4000-8000-000000000002")),
    FieldVariant("PAHV-V2-DATA-02-ELAPSED", "at.elapsedMillis", JsonPrimitive("123456790")),
    FieldVariant("PAHV-V2-DATA-02-DIGEST", "contentDigest", JsonPrimitive("1123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")),
    FieldVariant("PAHV-V2-DATA-02-EVENT-REVISION", "revision", JsonPrimitive("1")),
)

private fun replaceField(original: JsonObject, path: String, value: JsonElement): JsonObject = if ('.' in path) {
    val parent = path.substringBefore('.')
    JsonObject(original + (parent to JsonObject(original.getValue(parent).jsonObject + (path.substringAfter('.') to value))))
} else JsonObject(original + (path to value))

private fun replaceExactlyOnce(original: ByteArray, from: String, to: String, id: String): ByteArray {
    val text = original.decodeToString(throwOnInvalidSequence = true)
    val offset = text.indexOf(from)
    assertTrue(offset >= 0, "$id qualified framing token exists")
    assertEquals(offset, text.lastIndexOf(from), "$id qualified framing token is unique")
    val changed = (text.substring(0, offset) + to + text.substring(offset + from.length))
        .encodeToByteArray(throwOnInvalidSequence = true)
    assertFalse(changed.contentEquals(original), "$id changed literal control")
    return changed
}

private enum class LiteralVariant(val id: String) {
    CHANGED_ORIGINAL("PAHV-V2-DATA-02-CHANGED-ORIGINAL"),
    PROFILE("PAHV-V2-DATA-02-PROFILE"),
    OMITTED_OPERATOR_NULL("PAHV-V2-DATA-02-OMITTED-OPERATOR-NULL"),
    OMITTED_SESSION_NULL("PAHV-V2-DATA-02-OMITTED-SESSION-NULL"),
    NONCANONICAL_ZERO("PAHV-V2-DATA-02-NONCANONICAL-ZERO"),
    TRAILING_BYTE("PAHV-V2-DATA-02-TRAILING-BYTE"),
    TRUNCATED("PAHV-V2-DATA-02-TRUNCATED"),
}

private fun mutateLiteral(variant: LiteralVariant, c0: ByteArray): ByteArray = when (variant) {
    LiteralVariant.CHANGED_ORIGINAL -> LocalAuthenticationCodec.encodeEvent(originalEvent(CHANGED_EVENT_ID))
    LiteralVariant.PROFILE -> replaceExactlyOnce(c0, "\"schema\":\"$PROFILE\"", "\"schema\":\"$PROFILE \"", variant.id)
    LiteralVariant.OMITTED_OPERATOR_NULL -> replaceExactlyOnce(c0, "\"operatorId\":null,", "", variant.id)
    LiteralVariant.OMITTED_SESSION_NULL -> replaceExactlyOnce(c0, "\"sessionId\":null,", "", variant.id)
    LiteralVariant.NONCANONICAL_ZERO -> replaceExactlyOnce(c0, "\"revision\":\"0\"", "\"revision\":\"00\"", variant.id)
    LiteralVariant.TRAILING_BYTE -> c0 + byteArrayOf(0x20)
    LiteralVariant.TRUNCATED -> c0.copyOf(c0.size - 1)
}

private fun assertLiteralMutation(variant: LiteralVariant, c0: ByteArray, changed: ByteArray) {
    assertFalse(changed.contentEquals(c0), "${variant.id} changed literal control")
    val original = literalObject(c0)
    when (variant) {
        LiteralVariant.CHANGED_ORIGINAL -> assertEquals(replaceField(original, "eventId", JsonPrimitive(CHANGED_EVENT_ID)),
            literalObject(changed), "${variant.id} only request changes")
        LiteralVariant.PROFILE -> assertEquals(replaceField(original, "schema", JsonPrimitive("$PROFILE ")),
            literalObject(changed), "${variant.id} exact trailing-space profile")
        LiteralVariant.OMITTED_OPERATOR_NULL -> assertEquals(JsonObject(original - "operatorId"),
            literalObject(changed), "${variant.id} only operator null slot omitted")
        LiteralVariant.OMITTED_SESSION_NULL -> assertEquals(JsonObject(original - "sessionId"),
            literalObject(changed), "${variant.id} only session null slot omitted")
        LiteralVariant.NONCANONICAL_ZERO -> assertEquals(replaceField(original, "revision", JsonPrimitive("00")),
            literalObject(changed), "${variant.id} exact decimal-string token")
        LiteralVariant.TRAILING_BYTE -> {
            assertEquals(c0.size + 1, changed.size, "${variant.id} exact length")
            assertContentEquals(c0, changed.copyOfRange(0, c0.size), "${variant.id} original prefix")
            assertEquals(0x20.toByte(), changed.last(), "${variant.id} exactly byte 0x20")
        }
        LiteralVariant.TRUNCATED -> {
            assertEquals(c0.size - 1, changed.size, "${variant.id} exact length")
            assertContentEquals(c0.copyOfRange(0, c0.size - 1), changed, "${variant.id} remove only final byte")
        }
    }
}
