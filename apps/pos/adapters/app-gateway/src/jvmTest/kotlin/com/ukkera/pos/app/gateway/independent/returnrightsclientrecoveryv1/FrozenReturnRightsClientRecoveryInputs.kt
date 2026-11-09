package com.ukkera.pos.app.gateway.independent.returnrightsclientrecoveryv1

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalRecordValue
import com.ukkera.pos.app.LocalScalarInput
import com.ukkera.pos.app.LocalScalarValue
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.app.gateway.encodeHex
import com.ukkera.pos.catalog.store.LocalReturnRightsActivationCodecV1
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Paths

/** Exact frozen synthetic bytes and observations; production code supplies no expected value. */
internal object FrozenReturnRightsClientRecoveryInputs {
    private val relative = Paths.get("qa/pos/designs/return-rights-client-recovery-v1/fixtures.json")
    private val file = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .map { it.resolve(relative) }.firstOrNull { Files.isRegularFile(it) }
        ?: error("The frozen return-rights client recovery fixtures are required")
    val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
    val original = root.getValue("journalOriginalValues").jsonObject
    val scope = scope(original.getValue("scope").jsonObject)
    val owner = LocalClientIdentity(scope, DomainId(original.rrcString("actorId")), DomainId(original.rrcString("deviceId")))
    val operationId = OperationId(original.rrcString("operationId"))

    fun id(name: String) = DomainId(root.getValue("ids").jsonObject.rrcString(name))
    fun objectValue(name: String) = root.getValue(name).jsonObject
    fun matrix(name: String) = root.getValue(name).jsonArray.map { it.jsonObject }
    fun fixture(group: String, name: String) = objectValue(group).getValue(name).jsonObject
    fun fixtures(group: String) = objectValue(group).entries.map { it.key to it.value.jsonObject }
    fun requestText(name: String): String {
        val fixture = fixture("requestFixtures", name)
        return (fixture["canonicalJson"] ?: fixture.getValue("wireJson")).jsonPrimitive.content
    }
    fun request(name: String) = StoredBytes(requestText(name).encodeToByteArray())
    fun scope(value: JsonObject) = BranchScope(DomainId(value.rrcString("organizationId")),
        DomainId(value.rrcString("companyId")), DomainId(value.rrcString("branchId")))

    fun intent(raw: String? = null, action: LocalAction = LocalAction.RETURN_RIGHTS_ACTIVATE) = LocalCommandIntent(
        operationId, action, 1, null,
        if (raw == null) emptyList() else listOf(LocalScalarInput(LocalField.NOTES, LocalScalarValue.Text(raw))))

    /** Typed completed-command construction only; no generic form, signing or durable stage. */
    fun entry(requestFixture: String = "F-REQUEST-0", entryOwner: LocalClientIdentity = owner): StoredClientIntentV1 {
        val decoded = LocalReturnRightsActivationCodecV1.decodeActivate(request(requestFixture))
        val request = LocalReturnRightsActivationCodecV1.activate(operationId, decoded)
        val revision = fixture("requestFixtures", requestFixture).rrcString("expectedActiveRevision")
        return StoredClientIntentV1.staged(entryOwner, intent(), request,
            LocalText("Original documentary activation", "التفعيل الوثائقي الأصلي"), listOf(LocalRecordValue(
                LocalText("Saved expected revision", "الإصدار المتوقع المحفوظ"), LocalText(revision, revision))))
    }

    /** Negative saved metadata; a fresh digest isolates validation from unrelated digest corruption. */
    fun invalidEntry(base: StoredClientIntentV1, overrides: JsonObject): StoredClientIntentV1 {
        val entryOwner = overrides["scope"]?.jsonObject?.let { base.owner.copy(scope = scope(it)) } ?: base.owner
        val commandName = overrides["command"]?.jsonPrimitive?.content ?: base.commandName
        val commandVersion = overrides["commandVersion"]?.jsonPrimitive?.content?.toInt() ?: base.commandVersion
        val payload = overrides["canonicalPayloadFixture"]?.jsonPrimitive?.content?.let(::request) ?: base.request().payload
        val command = CommandKey(commandName, commandVersion)
        return base.copy(owner = entryOwner, action = overrides["action"]?.jsonPrimitive?.content ?: base.action,
            commandName = commandName, commandVersion = commandVersion, payloadHex = encodeHex(payload.copyBytes()),
            semanticSha256 = semanticDigest(entryOwner.scope, entryOwner.actorId, command, payload))
    }

    /** Controlled recorded reply metadata; it is not a durable or authenticated Master outcome. */
    fun recorded(name: String): MutationReply.Recorded {
        val result = fixture("resultFixtures", name)
        return MutationReply.Recorded(operationId, owner.actorId, result.rrcFlag("outerCommitted"), result.rrcString("outerCode"),
            StoredBytes(result.rrcString("minimalJson").encodeToByteArray()), Revision(1), JournalSequence(1),
            UtcInstant.parseCanonical("2026-10-31T12:00:00Z"), replayed = true)
    }
}

internal fun JsonObject.rrcString(name: String): String = getValue(name).jsonPrimitive.content
internal fun JsonObject.rrcFlag(name: String): Boolean = getValue(name).jsonPrimitive.boolean
internal fun StoredBytes.rrcText(): String = copyBytes().let { bytes ->
    try { bytes.decodeToString(throwOnInvalidSequence = true) } finally { bytes.fill(0) }
}
