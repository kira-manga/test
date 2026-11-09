package com.ukkera.pos.app.independent.returnrightsauthoringv1

import com.ukkera.pos.app.LocalReturnRightsPublicationDraft
import com.ukkera.pos.app.LocalReturnRightsPublicationException
import com.ukkera.pos.app.LocalReturnRightsPublicationReason
import com.ukkera.pos.app.LocalReturnRightsPublicationRule
import com.ukkera.pos.app.LocalReturnRightsPublicationTreatment
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.ui.ReturnRightsEditor
import com.ukkera.pos.app.ui.ReturnRightsRuleEditor
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertEquals

/** Frozen unsigned documentary inputs only. No native action, key, review or signed output is manufactured. */
internal object FrozenReturnRightsAuthoringUiInputs {
    private val relative = Paths.get("qa/pos/designs/return-rights-authoring-v1/fixtures.json")
    private val file = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .map { it.resolve(relative) }.firstOrNull { Files.isRegularFile(it) }
        ?: error("The frozen return-rights authoring fixtures are required")
    val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
    val identities = root.getValue("identities").jsonObject
    val operationId = OperationId(identities.raaString("activationOperationO0"))
    fun id(name: String) = DomainId(identities.raaString(name))
    fun definition(name: String = "D0") = root.getValue("definitions").jsonObject.getValue(name).jsonObject.getValue("value").jsonObject
    fun invalidEdit(name: String) = root.getValue("invalidDraftEdits").jsonArray.map { it.jsonObject }
        .single { it.raaString("name") == name }
    fun rules(name: String = "D0") = definition(name).getValue("rules").jsonArray.map { element ->
        val rule = element.jsonObject
        LocalReturnRightsPublicationRule(rule.raaString("categoryCode"),
            LocalReturnRightsPublicationReason.valueOf(rule.raaString("reason")),
            LocalReturnRightsPublicationTreatment.valueOf(rule.raaString("treatment")),
            rule.raaOptionalInt("receiptWindowDays"), rule.raaOptionalInt("coveredResolutionDays"),
            rule["exceptionBasis"]?.takeUnless { it == JsonNull }?.jsonPrimitive?.content
                ?.let { LocalReturnRightsPublicationException.valueOf(it) })
    }
    fun draft(name: String = "D0", ruleValues: List<LocalReturnRightsPublicationRule> = rules(name)): LocalReturnRightsPublicationDraft {
        val value = definition(name)
        return LocalReturnRightsPublicationDraft(DomainId(value.raaString("publicationId")), DomainId(value.raaString("policyId")),
            Revision(value.raaString("revision").toLong()), value.raaString("jurisdictionCode"), value.raaString("legalSourceUri"),
            value.raaString("legalSourceAccessDate"), LocalText(value.raaString("continuingRightsEnglish"),
                value.raaString("continuingRightsArabic")), ruleValues)
    }
    fun editor(name: String = "D0"): ReturnRightsEditor {
        val value = definition(name)
        return ReturnRightsEditor(publicationId = DomainId(value.raaString("publicationId")), policy = value.raaString("policyId"),
            revision = value.raaString("revision"), source = value.raaString("legalSourceUri"), accessDate = value.raaString("legalSourceAccessDate"),
            english = value.raaString("continuingRightsEnglish"), arabic = value.raaString("continuingRightsArabic"),
            rules = rules(name).map { ReturnRightsRuleEditor(it.categoryCode, it.reason, it.treatment,
                it.receiptWindowDays?.toString().orEmpty(), it.coveredResolutionDays?.toString().orEmpty(), it.exceptionBasis) })
    }
    fun assertLiteralDraft(actual: LocalReturnRightsPublicationDraft, name: String = "D0", context: String = name) {
        val value = definition(name)
        assertEquals(value.raaString("publicationId"), actual.publicationId.value, context)
        assertEquals(value.raaString("policyId"), actual.policyId.value, context)
        assertEquals(value.raaString("revision"), actual.companyRevision.value.toString(), context)
        assertEquals(value.raaString("jurisdictionCode"), actual.jurisdictionCode, context)
        assertEquals(value.raaString("legalSourceUri"), actual.legalSourceUri, context)
        assertEquals(value.raaString("legalSourceAccessDate"), actual.legalSourceAccessDate, context)
        assertEquals(value.raaString("continuingRightsEnglish"), actual.continuingRights.english, context)
        assertEquals(value.raaString("continuingRightsArabic"), actual.continuingRights.arabic, context)
        assertEquals(rules(name), actual.rules, context)
        assertEquals(9, actual.rules.size, context)
    }
}

internal fun JsonObject.raaString(name: String) = getValue(name).jsonPrimitive.content
private fun JsonObject.raaOptionalInt(name: String) = getValue(name).takeUnless { it == JsonNull }?.jsonPrimitive?.int
