package com.ukkera.pos.app.gateway.setup.independent.pairedactorintentdisposition

import com.ukkera.pos.app.gateway.setup.independent.applicationlanattachment.ApplicationLanAttachmentFrozenInputs
import com.ukkera.pos.app.setup.LocalSetupLanInput
import com.ukkera.pos.app.setup.LocalSetupLanWorkflow
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Unchanged paired-actor-intent-disposition v1 SUPPORT inputs and expected outcomes. */
internal object PairedActorIntentDispositionFrozenInputs {
    private const val FIXTURES = "qa/pos/designs/paired-actor-intent-disposition/fixtures.json"
    private const val FIXTURE_SHA256 = "51d2bada6cc4a264b46e05875680696c628f131b0abb43f159bc429ee0e3e074"
    private val root: JsonObject by lazy {
        val fixture = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, FIXTURES) }.firstOrNull { it.isFile }
            ?: error("The frozen paired actor-intent fixture must be available from the repository")
        val bytes = fixture.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        check(digest == FIXTURE_SHA256) { "The paired actor-intent fixture differs from frozen v1" }
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject.also {
            check(it.text("design") == "paired-actor-intent-disposition" && it.number("version") == 1)
            check(it.text("specification_state") == "FROZEN" && it.text("execution_status") == "NOT RUN")
        }
    }

    val classifications: List<Classification> by lazy {
        root.rows("classification_variants").map { row ->
            Classification(row.text("id"), row.action("selected_actor_action"),
                row.flag("actor_allocation_present"), row.number("allocation_count"),
                row.number("capacity"), row.text("expected_classification"))
        }.also { check(it.size == 60) }
    }

    val pairs: List<Pair> by lazy {
        root.getValue("fixtures").jsonObject.getValue("F-SELECT").jsonObject.rows("pairs").map { row ->
            Pair(row.text("id"), row.action("device_action"), row.action("actor_action"))
        }.also { check(it.size == 9) }
    }

    val comparisons: List<Comparison> by lazy {
        root.rows("selection_comparison_variants").map { row ->
            Comparison(row.text("id"), pair(row.text("left")), pair(row.text("right")),
                row.flag("expected_same_selection"))
        }.also { check(it.size == 81) }
    }

    val legacy: List<Legacy> by lazy {
        root.rows("legacy_variants").map { row ->
            Legacy(row.text("id"), row.action("device_action"), row.action("expected_actor_action"),
                pair(row.text("expected_pair")))
        }.also { check(it.size == 3) }
    }

    val reviews: List<Review> by lazy {
        root.rows("interactive_review_variants").map { row ->
            Review(row.text("id"), row.actionOrNull("device_action"), row.actionOrNull("actor_action"),
                row.flag("expected_complete_explicit_review"), row["expected_captured_pair"]?.let {
                    pair(it.jsonPrimitive.content)
                })
        }.also { check(it.size == 16) }
    }

    fun input(deviceAction: LocalPairedDeviceCredentialJournalAction?,
        actorAction: LocalPairedDeviceCredentialJournalAction?): LocalSetupLanInput {
        // F-SELECT fixes all other fields. The transport carrier is the earlier unchanged LAN
        // fixture, not an invented network default or a native target/authority observation.
        val transport = ApplicationLanAttachmentFrozenInputs.clientPrimary
        return LocalSetupLanInput(transport.allowedSubnets, transport.interfaceName,
            transport.localAddress, transport.listenPort, credentialAction = deviceAction,
            workflow = LocalSetupLanWorkflow.SUPPLIERS, actorIntentAction = actorAction)
    }

    fun input(pair: Pair): LocalSetupLanInput = input(pair.deviceAction, pair.actorAction)

    fun legacyInput(deviceAction: LocalPairedDeviceCredentialJournalAction): LocalSetupLanInput {
        val transport = ApplicationLanAttachmentFrozenInputs.clientPrimary
        // Deliberately omit the additive argument: this is the frozen existing-source-call variant.
        return LocalSetupLanInput(transport.allowedSubnets, transport.interfaceName,
            transport.localAddress, transport.listenPort, credentialAction = deviceAction,
            workflow = LocalSetupLanWorkflow.SUPPLIERS)
    }

    data class Classification(val id: String, val action: LocalPairedDeviceCredentialJournalAction,
        val allocated: Boolean, val allocationCount: Int, val maximumAllocations: Int,
        val expected: String)
    data class Pair(val id: String, val deviceAction: LocalPairedDeviceCredentialJournalAction,
        val actorAction: LocalPairedDeviceCredentialJournalAction)
    data class Comparison(val id: String, val left: Pair, val right: Pair, val expectedSame: Boolean)
    data class Legacy(val id: String, val deviceAction: LocalPairedDeviceCredentialJournalAction,
        val expectedActorAction: LocalPairedDeviceCredentialJournalAction, val expectedPair: Pair)
    data class Review(val id: String, val deviceAction: LocalPairedDeviceCredentialJournalAction?,
        val actorAction: LocalPairedDeviceCredentialJournalAction?, val expectedComplete: Boolean,
        val expectedPair: Pair?)

    private fun pair(id: String): Pair = pairs.single { it.id == id }
    private fun JsonObject.rows(key: String) = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.boolean
    private fun JsonObject.action(key: String) = LocalPairedDeviceCredentialJournalAction.valueOf(text(key))
    private fun JsonObject.actionOrNull(key: String) = getValue(key).let {
        if (it == JsonNull) null else LocalPairedDeviceCredentialJournalAction.valueOf(it.jsonPrimitive.content)
    }
}
