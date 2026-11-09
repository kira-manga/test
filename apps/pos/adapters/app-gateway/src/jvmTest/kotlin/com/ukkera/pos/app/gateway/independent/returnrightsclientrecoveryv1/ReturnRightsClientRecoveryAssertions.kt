package com.ukkera.pos.app.gateway.independent.returnrightsclientrecoveryv1

import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordedDecision
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.catalog.store.LocalReturnRightsActivationCodecV1
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyScope
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Immutable observable grant values, including restrictions; separately loaded grants need not be identical objects. */
internal data class ReturnRightsGrantScalars(
    val action: String,
    val verb: String,
    val effect: String,
    val scopeKind: String,
    val organizationId: String,
    val companyId: String,
    val branchId: String?,
    val targetIds: Set<String>?,
    val ownerAccess: String,
    val namedSubjectId: String?,
    val limits: ReturnRightsGrantLimitScalars,
    val delegationCeiling: ReturnRightsGrantLimitScalars?,
)

internal data class ReturnRightsGrantLimitScalars(
    val maximumAmountMinor: Long?,
    val maximumAmountCurrency: String?,
    val maximumPercentageBasisPoints: Int?,
    val noMonetaryCeilingCurrency: String?,
)

internal fun returnRightsGrantScalars(grants: List<PermissionGrant>): List<ReturnRightsGrantScalars> = grants.map { grant ->
    ReturnRightsGrantScalars(grant.permission.action.name, grant.permission.verb.name, grant.effect.name,
        when (grant.scope) { is BranchScope -> "BRANCH"; is CompanyScope -> "COMPANY" },
        grant.scope.organizationId.value, grant.scope.companyId.value, (grant.scope as? BranchScope)?.branchId?.value,
        grant.targetIds?.map { it.value }?.toSet(), grant.ownerAccess.name, grant.namedSubjectId?.value,
        returnRightsGrantLimitScalars(grant.limits), grant.delegationCeiling?.let(::returnRightsGrantLimitScalars))
}

private fun returnRightsGrantLimitScalars(limits: PermissionLimits) = ReturnRightsGrantLimitScalars(
    limits.maximumAmount?.minor, limits.maximumAmount?.currency?.name, limits.maximumPercentageBasisPoints,
    limits.noMonetaryCeiling?.currency?.name)

/** Original logical values are frozen; the original real semantic digest is captured, not invented. */
internal fun assertFrozenReturnRightsOriginal(entry: StoredClientIntentV1, context: String) {
    val inputs = FrozenReturnRightsClientRecoveryInputs
    val original = inputs.original
    assertEquals(inputs.operationId, entry.operationId, context)
    assertEquals(inputs.owner, entry.owner, context)
    assertEquals(original.rrcString("action"), entry.action, context)
    assertEquals(original.rrcString("command"), entry.commandName, context)
    assertEquals(original.rrcString("commandVersion"), entry.commandVersion.toString(), context)
    assertEquals(inputs.request(original.rrcString("canonicalPayloadFixture")), entry.request().payload, context)
    assertNull(entry.actionApprovalId, context)
    val decoded = LocalReturnRightsActivationCodecV1.decodeActivate(entry.request().payload)
    assertEquals(original.rrcString("expectedActiveRevision"), decoded.expectedActiveRevision.value.toString(), context)
    assertEquals(inputs.fixture("definitionFixtures", original.rrcString("signedEnvelopeFixture"))
        .rrcString("signedEnvelopeCanonicalJson"), decoded.signedPublication, context)
}

internal fun assertReturnRightsOriginalRetained(original: StoredClientIntentV1, actual: StoredClientIntentV1, context: String) {
    assertTrue(original.sameIntent(actual), context)
    assertEquals(original.owner, actual.owner, context)
    assertEquals(original.operationId, actual.operationId, context)
    assertEquals(original.action, actual.action, context)
    assertEquals(original.commandName, actual.commandName, context)
    assertEquals(original.commandVersion, actual.commandVersion, context)
    assertEquals(original.payloadHex, actual.payloadHex, context)
    assertEquals(original.semanticSha256, actual.semanticSha256, context)
    assertEquals(original.identity(), actual.identity(), context)
    assertEquals(original.formVersion, actual.formVersion, context)
    assertEquals(original.inputDigest, actual.inputDigest, context)
    assertEquals(original.actionApprovalId, actual.actionApprovalId, context)
    actual.validate(original.owner)
}

internal fun assertReturnRightsOriginalRequest(original: StoredClientIntentV1, actual: MutationRequest, context: String) {
    assertEquals(original.operationId, actual.operationId, context)
    assertEquals(original.request().command, actual.command, context)
    assertEquals(original.request().payload, actual.payload, context)
    assertNull(actual.actionApprovalId, context)
}

/** Semantic bilingual observations, not implementation-derived literal message oracles. */
internal fun assertFrozenReturnRightsReceipt(name: String, entry: StoredClientIntentV1,
    reply: MutationReply.Recorded, actual: LocalOperationResult.Recorded) {
    val inputs = FrozenReturnRightsClientRecoveryInputs
    val fixture = inputs.fixture("resultFixtures", name)
    assertEquals(entry.operationId, actual.operationId, name)
    assertEquals(if (reply.committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED, actual.decision, name)
    assertEquals(reply.replayed, actual.replayed, name)
    assertEquals(fixture.rrcString("expectedPolicyNavigationRecords").toInt(), listOfNotNull(actual.result).size, name)
    assertEquals(fixture.rrcString("expectedSignedTermsNoticesOrRuleRows").toInt(),
        actual.result?.values?.size ?: 0, "$name: receipt contains no policy-content rows")
    assertNull(actual.food, name)
    val english = actual.message.english.lowercase()
    val arabic = actual.message.arabic
    assertTrue(english.contains("documentary") && english.contains("stock") && english.contains("money"), name)
    assertTrue(arabic.contains("وثائق") && arabic.contains("مخزون") && arabic.contains("نقد"), name)
    assertTrue(english.contains("no ") && (arabic.contains("لا ") || arabic.contains("لم ")), name)

    when (fixture.rrcString("expectedPresentation")) {
        "validated_documentary_activation" -> {
            assertTrue(english.contains("original") && english.contains("activation") && !english.contains("cannot validate"), name)
            assertTrue(arabic.contains("الأصلي") && arabic.contains("تفعيل"), name)
            assertTrue(english.contains("does not qualify") && arabic.contains("لا يثبت أهلية"),
                "$name: activation is not return eligibility")
            assertTrue(english.contains("does not") && english.contains("or authorize a refund") &&
                arabic.contains("لا يخول رد الأموال"), "$name: activation grants no refund authority")
            val record = assertNotNull(actual.result, name)
            val query = inputs.fixture("queryFixtures", fixture.rrcString("expectedHistoricalQueryFixture"))
            val queryBody = kotlinx.serialization.json.Json.parseToJsonElement(query.rrcString("canonicalJson")).jsonObject
            assertEquals(LocalRecordKind.RETURN_RIGHTS_PUBLICATION, record.reference.kind, name)
            assertEquals(DomainId(queryBody.rrcString("id")), record.reference.id, name)
            assertNull(record.reference.parentId, name)
            assertNull(record.reference.revision, name)
            assertTrue(record.selectable, name)
            assertTrue(record.values.isEmpty(), name)
            val dispatched = LocalReturnRightsActivationCodecV1.readPublication(record.reference.id)
            assertEquals(query.rrcString("command"), dispatched.query.name, name)
            assertEquals(query.rrcString("version"), dispatched.query.version.toString(), name)
            assertEquals(query.rrcString("canonicalJson"), dispatched.payload.rrcText(), name)
        }
        "validated_not_applied" -> {
            assertNull(actual.result, name)
            assertTrue(english.contains("not applied") && arabic.contains("لم يُنفذ"), name)
            assertFalse(english.contains("cannot validate"), name)
            assertTrue(english.contains("rights remain separate") && arabic.contains("لا يعني ذلك رفض حقوق العميل"),
                "$name: refusal is not a customer-rights or eligibility decision")
            assertTrue(english.contains("no refund authority") && arabic.contains("لا يخول رد الأموال"),
                "$name: refusal grants no refund authority")
            assertNull(LocalReturnRightsActivationCodecV1.decodeResult(reply.result).activeRevision, name)
            when (fixture.rrcString("outerCode")) {
                "return.rights.revision_conflict" -> {
                    assertTrue(english.contains("revision") && english.contains("conflict"), name)
                    assertTrue(arabic.contains("إصدار") && arabic.contains("يتعارض"), name)
                }
                "return.rights.publication_already_used" -> {
                    assertTrue(english.contains("publication") && english.contains("already been used"), name)
                    assertTrue(arabic.contains("سبق استخدام") && arabic.contains("هوية"), name)
                }
                "return.rights.policy_sequence_required" -> {
                    assertTrue(english.contains("sequence") && english.contains("not met"), name)
                    assertTrue(arabic.contains("تسلسل") && arabic.contains("لم يتحقق"), name)
                }
                else -> error("The frozen ordinary-refusal matrix is exhaustive")
            }
        }
        "generic_unvalidated_receipt" -> {
            assertNull(actual.result, name)
            assertTrue(english.contains("cannot validate"), name)
            assertTrue(arabic.contains("لا يمكن") && arabic.contains("التحقق"), name)
            assertTrue(english.contains("no policy, return eligibility") && english.contains("authority is inferred") &&
                arabic.contains("لا تُستنتج") && arabic.contains("أهلية مرتجع"),
                "$name: generic receipt infers no return eligibility")
            assertTrue(english.contains("no policy,") && english.contains("refund,") && english.contains("authority is inferred") &&
                arabic.contains("لا تُستنتج") && arabic.contains("صلاحية رد أموال"),
                "$name: generic receipt infers no refund authority")
            if (name == "F-RESULT-RESERVED-SIGNATURE") {
                assertFalse(english.contains("signature"), name)
                assertFalse(arabic.contains("توقيع"), name)
            }
        }
        else -> error("Unknown frozen result observation")
    }

    val savedRequest = inputs.fixture("requestFixtures", fixture.rrcString("savedRequestFixture"))
    val definitionFixture = inputs.fixture("definitionFixtures", savedRequest.rrcString("definitionFixture"))
    val definition = definitionFixture.getValue("definition").jsonObject
    val displayed = buildString {
        append(actual.message.english); append(actual.message.arabic)
        actual.result?.let { record ->
            append(record.title.english); append(record.title.arabic)
            record.status?.let { append(it.english); append(it.arabic) }
            record.values.forEach { append(it.label.english); append(it.label.arabic); append(it.value.english); append(it.value.arabic) }
        }
    }
    for (field in listOf("continuingRightsEnglish", "continuingRightsArabic", "legalSourceUri"))
        assertFalse(displayed.contains(definition.getValue(field).jsonPrimitive.content), "$name: $field")
    assertFalse(displayed.contains(definitionFixture.rrcString("signedEnvelopeCanonicalJson")), name)
    assertFalse(displayed.contains("CHANGE_OF_MIND") || displayed.contains("RECEIPT_WINDOW") || displayed.contains("signatureHex"), name)
}
