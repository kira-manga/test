package com.ukkera.pos.app.gateway.setup.independent.applicationlanattachment

import com.ukkera.pos.app.setup.LocalSetupLanEndpoint
import com.ukkera.pos.app.setup.LocalSetupLanInput
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Unchanged v1 inputs, used with the separately frozen application-lan-attachment-v1.1 correction. */
internal object ApplicationLanAttachmentFrozenInputs {
    private const val FIXTURES = "qa/pos/designs/application-lan-attachment/fixtures.json"
    private const val FIXTURE_SHA256 = "8ecb1ff1d1167c47dfc2da3dfcf884bf22a7937df9946d2e3d8f72b7d6ba9050"
    private val root: JsonObject by lazy {
        val fixture = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, FIXTURES) }.firstOrNull { it.isFile }
            ?: error("The frozen application LAN fixture must be available from the repository")
        val bytes = fixture.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        check(digest == FIXTURE_SHA256) { "The application LAN fixture differs from the frozen input" }
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject.also {
            check(it.text("version") == "application-lan-attachment-v1")
        }
    }

    private val selections get() = root.getValue("selections").jsonObject
    val hostZero get() = selections.getValue("host_zero").jsonObject.input()
    val hostFixed get() = selections.getValue("host_fixed").jsonObject.input()
    val clientPrimary get() = selections.getValue("client_primary").jsonObject.input()
    val clientEndpoint get() = selections.getValue("client_primary").jsonObject.getValue("endpoint").jsonObject.endpoint()
    val actualHostEndpoint get() = selections.getValue("actual_host_endpoint").jsonObject.endpoint()

    val policyCounts get() = root.rows("policy_counts").map { row ->
        PolicyCount(row.text("id"), row.getValue("cidrs").let { if (it == JsonNull) null else it.strings() },
            row.eligible())
    }
    val badCidrs get() = root.getValue("bad_cidrs").strings()
    val localAddresses get() = root.rows("local_address_variants").map { Address(it.text("value"), it.eligible()) }
    val endpointAddresses get() = root.rows("endpoint_address_variants").map { Address(it.text("value"), it.eligible()) }
    val listenerPorts get() = root.rows("listener_ports").map { Port(it.number("value"), it.eligible()) }
    val endpointPorts get() = root.rows("endpoint_ports").map { Port(it.number("value"), it.eligible()) }
    val interfaces get() = root.rows("interface_variants").map { row ->
        Interface(row.getValue("id").let { if (it == JsonNull) null else it.jsonPrimitive.content },
            row["kind"]?.let { if (it == JsonNull) null else it.jsonPrimitive.content },
            row["explicitly_eligible"]?.jsonPrimitive?.boolean, row.eligible())
    }
    val hostConflicts get() = root.rows("conflicting_selections").filter { "host" in it.getValue("roles").strings() }
        .map { row -> HostConflict(row.text("id"), hostZero.withSelection(row.text("change"), row.getValue("value"))) }
    val waiterStages get() = root.getValue("waiter_stages").strings()
    val masterRefusals get() = root.getValue("master_refusals").strings()
    val clientReadRefusals get() = root.getValue("client_read_refusals").strings()
    val failureScripts get() = root.getValue("failure_scripts").strings()
    val statusScripts get() = root.getValue("status_scripts").strings()

    data class PolicyCount(val id: String, val cidrs: List<String>?, val eligible: Boolean)
    data class Address(val value: String, val eligible: Boolean)
    data class Port(val value: Int, val eligible: Boolean)
    data class Interface(val id: String?, val kind: String?, val describedEligibility: Boolean?, val eligible: Boolean)
    data class HostConflict(val id: String, val input: LocalSetupLanInput)

    private fun JsonObject.input() = LocalSetupLanInput(getValue("approved_cidrs").strings(),
        text("interface"), text("local_address"), number("listener_port"))
    private fun JsonObject.endpoint() = LocalSetupLanEndpoint(text("address"), number("port"))
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.int
    private fun JsonObject.eligible() = text("expected").also { check(it in setOf("eligible", "refuse")) } == "eligible"
    private fun JsonObject.rows(key: String) = getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonElement.strings() = (this as JsonArray).map { it.jsonPrimitive.content }
    private fun LocalSetupLanInput.withSelection(change: String, value: JsonElement): LocalSetupLanInput = when (change) {
        "approved_cidrs" -> copyInput(allowedSubnets = value.strings())
        "interface" -> copyInput(interfaceName = value.jsonPrimitive.content)
        "local_address" -> copyInput(localAddress = value.jsonPrimitive.content)
        "listener_port" -> copyInput(listenPort = value.jsonPrimitive.int)
        else -> error("Unsupported frozen Host selection: $change")
    }
}

internal fun LocalSetupLanInput.copyInput(
    allowedSubnets: List<String> = this.allowedSubnets,
    interfaceName: String = this.interfaceName,
    localAddress: String = this.localAddress,
    listenPort: Int = this.listenPort,
): LocalSetupLanInput = LocalSetupLanInput(allowedSubnets, interfaceName, localAddress, listenPort)
