package com.ukkera.pos.app.gateway.independent.returnrightspresentationv1

import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Reads the unchanged, independently frozen inputs; no production codec supplies an oracle. */
internal object FrozenReturnRightsPresentationInputs {
    private val relative = Paths.get("qa/pos/designs/return-rights-presentation-v1/fixtures.json")
    private val file: Path = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .map { it.resolve(relative) }.firstOrNull { Files.isRegularFile(it) }
        ?: error("The frozen return-rights presentation fixtures are required")
    val root: JsonObject = Json.parseToJsonElement(Files.readString(file)).jsonObject
    val workflows: List<LocalWorkflow> = listOf(LocalWorkflow.CATALOG, LocalWorkflow.SETUP)
    val scope: BranchScope = branchScope(root.getValue("sessionScope").jsonObject)
    val replacement: JsonObject = root.getValue("replacementSession").jsonObject
    val replacementScope: BranchScope = branchScope(replacement.getValue("scope").jsonObject)
    val effects: JsonObject = root.getValue("businessEffectsFixture").jsonObject

    fun array(name: String): List<JsonObject> = root.getValue(name).jsonArray.map { it.jsonObject }
    fun id(name: String): DomainId = DomainId(root.getValue("ids").jsonObject.string(name))
    fun fixture(name: String): JsonObject = (array("publishedFixtures") + array("statusFixtures"))
        .single { it.string("fixtureId") == name }
    fun wire(name: String): String = fixture(name).string("canonicalReadResultJson")
    fun visible(name: String): JsonObject = fixture(name).getValue("requiredVisibleValues").jsonObject

    fun selection(workflow: LocalWorkflow, historical: Boolean, publication: DomainId = id("publicationA"),
        selectedScope: BranchScope = scope): LocalWorkflowQuery = LocalWorkflowQuery(workflow,
        reference = LocalRecordReference(
            if (historical) LocalRecordKind.RETURN_RIGHTS_PUBLICATION else LocalRecordKind.RETURN_RIGHTS_CURRENT,
            if (historical) publication else selectedScope.companyId))

    fun query(historical: Boolean, publication: DomainId = id("publicationA")): String =
        if (!historical) root.string("currentQuery") else when (publication) {
            id("publicationA") -> root.string("historicalAQuery")
            id("publicationB") -> root.string("historicalBQuery")
            else -> "{\"kind\":\"PUBLICATION\",\"id\":${JsonPrimitive(publication.value)}}"
        }

    /** Applies only the stated frozen raw/ordered edit, retaining any intentional violation. */
    fun editedRead(variant: JsonObject, baseName: String = variant.string("base")): String {
        variant["exactRawReadResultJson"]?.let { return it.jsonPrimitive.content }
        val base = wire(baseName)
        variant["exactRawPrefix"]?.let { prefix ->
            return prefix.jsonPrimitive.content + base.substring(
                variant.getValue("appendBaseReadResultSuffixFromCharacter").jsonPrimitive.content.toInt())
        }
        val original = Json.parseToJsonElement(base).jsonObject
        check(original.toString() == base) { "Fixture construction must retain the frozen canonical base" }
        variant["outerFieldOrder"]?.let { order ->
            return JsonObject(order.jsonArray.associate { item ->
                val name = item.jsonPrimitive.content
                name to original.getValue(name)
            }).toString()
        }
        variant["appendOuterField"]?.let { fields ->
            return JsonObject(LinkedHashMap(original).also { it.putAll(fields.jsonObject) }).toString()
        }
        val path = variant["path"] ?: variant["replacePath"]
        if (path != null) {
            val parts = path.jsonArray.map { it.jsonPrimitive.content }
            val value = when {
                variant["valueOperation"] != null -> Json.parseToJsonElement(
                    original.getValue("activation").jsonObject.string("signedPublication"))
                variant["exactStringPrefix"] != null -> JsonPrimitive(
                    variant.string("exactStringPrefix") +
                        original.getValue("activation").jsonObject.string("signedPublication").substring(
                            variant.getValue("appendBaseStringSuffixFromCharacter").jsonPrimitive.content.toInt()))
                else -> variant.getValue("value")
            }
            return replace(original, parts, value).toString()
        }
        val activation = original.getValue("activation").jsonObject
        val envelope = Json.parseToJsonElement(activation.string("signedPublication")).jsonObject
        val definition = envelope.getValue("definition")
        val editedDefinition = replace(definition,
            variant.getValue("envelopeDefinitionPath").jsonArray.map { it.jsonPrimitive.content },
            variant["value"] ?: JsonNull, remove = variant.string("envelopeDefinitionOperation") == "REMOVE_FIELD")
        val editedEnvelope = JsonObject(LinkedHashMap(envelope).also { it["definition"] = editedDefinition })
        val withEnvelope = replace(original, listOf("activation", "signedPublication"), JsonPrimitive(editedEnvelope.toString()))
        return replace(withEnvelope, listOf("activation", "reference", "bodyDigest"),
            variant.getValue("referenceBodyDigestAfterMutation")).toString()
    }

    /** Null/malformed IDs and the incomplete mixed price context have no typed query binding. */
    fun boundInvalidSelection(variant: JsonObject, workflow: LocalWorkflow, historical: Boolean): LocalWorkflowQuery {
        val original = selection(workflow, historical)
        val reference = checkNotNull(original.reference)
        val value = variant.getValue("value")
        return when (variant.string("replaceSelectionField")) {
            "id" -> original.copy(reference = reference.copy(id = DomainId(value.jsonPrimitive.content)))
            "parent" -> original.copy(reference = reference.copy(parentId = DomainId(value.jsonPrimitive.content)))
            "revision" -> original.copy(reference = reference.copy(revision = Revision(value.jsonPrimitive.content.toLong())))
            "search" -> original.copy(search = value.jsonPrimitive.content)
            "pagination" -> original.copy(cursor = value.jsonObject.string("cursor"))
            "workflow" -> selection(LocalWorkflow.PEOPLE, historical)
            else -> error("This frozen selector needs a real input boundary before execution")
        }
    }

    private fun replace(element: JsonElement, path: List<String>, value: JsonElement, remove: Boolean = false): JsonElement {
        check(path.isNotEmpty())
        val head = path.first()
        val tail = path.drop(1)
        return when (element) {
            is JsonObject -> JsonObject(LinkedHashMap(element).also { entries ->
                check(entries.containsKey(head))
                if (tail.isEmpty()) {
                    if (remove) entries.remove(head) else entries[head] = value
                } else entries[head] = replace(entries.getValue(head), tail, value, remove)
            })
            is JsonArray -> JsonArray(element.toMutableList().also { entries ->
                val index = head.toInt()
                if (tail.isEmpty()) {
                    check(!remove)
                    entries[index] = value
                } else entries[index] = replace(entries[index], tail, value, remove)
            })
            else -> error("Frozen edit cannot traverse a scalar")
        }
    }

    private fun branchScope(value: JsonObject): BranchScope = BranchScope(
        DomainId(value.string("organizationId")), DomainId(value.string("companyId")), DomainId(value.string("branchId")))
}

internal fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.flag(name: String): Boolean = getValue(name).jsonPrimitive.boolean
