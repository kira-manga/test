package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.app.setup.LocalSetupLanInput
import com.ukkera.pos.app.setup.LocalSetupLanEndpoint
import com.ukkera.pos.app.setup.LocalSetupLanWorkflow
import com.ukkera.pos.security.LocalPairedDeviceCredentialJournalAction
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.json.*

/** Separate non-secret startup intent. Never changes the compatible Master RUN/STOP
 * record, supplies native identity, enrolls a journal or restores an employee login. */
public class LocalLanLaunchRecord(private val storage: LocalMasterLaunchStorage) {
    private val monitor = LocalSetupMonitor()

    public sealed interface Read {
        public data object Absent : Read
        public data object Stopped : Read
        public data object Unresolved : Read
        public class Host internal constructor(public val settings: LocalSetupLanSettings,
            internal val identities: List<String>, public val cohost: Boolean) : Read {
            public fun matches(actual: SelfOwnedAllocationCoordinatesV1): Boolean = identities == fields(actual)
            override fun toString(): String = "LocalLanLaunchRecord.Host([REDACTED])"
        }
        public class Client internal constructor(public val settings: LocalSetupLanSettings,
            public val originalPairingActionId: DomainId, public val endpoint: LocalSetupLanEndpoint?) : Read {
            override fun toString(): String = "LocalLanLaunchRecord.Client([REDACTED])"
        }
    }

    public fun read(): Read = withSetupLock(monitor) {
        try {
            val bytes = storage.read() ?: return@withSetupLock Read.Absent
            try {
                require(bytes.size in 1..MAXIMUM_BYTES)
                val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
                require(root.keys == setOf("format", "role", "binding", "settings", "endpoint", "cohost") && root.string("format") == FORMAT)
                val cohost = root.getValue("cohost").jsonPrimitive.boolean
                val binding = root.getValue("binding").jsonArray.map { value ->
                    val id = value.jsonPrimitive
                    require(id.isString)
                    id.content.also { require(DomainId(it).value == it) }
                }
                val result = when (root.string("role")) {
                    "STOP" -> { require(!cohost && binding.isEmpty() && root["settings"] == JsonNull && root["endpoint"] == JsonNull); Read.Stopped }
                    "HOST" -> {
                        require(binding.size == 12 && binding.distinct().size == 12)
                        require(root["endpoint"] == JsonNull)
                        Read.Host(settings(root.getValue("settings").jsonObject), binding, cohost)
                    }
                    "CLIENT" -> {
                        require(!cohost && binding.size == 1)
                        val selected = settings(root.getValue("settings").jsonObject)
                        val endpoint = root.getValue("endpoint").let { value ->
                            if (value == JsonNull) null else value.jsonObject.let {
                                require(it.keys == setOf("address", "port"))
                                LocalSetupLanEndpoint(it.string("address"), it.getValue("port").jsonPrimitive.int)
                                    .also { endpoint -> selected.validateEndpoint(endpoint) }
                            }
                        }
                        Read.Client(selected, DomainId(binding.single()), endpoint)
                    }
                    else -> error("Unknown LAN startup intent")
                }
                // Strict canonical bytes reject duplicate keys, alternate types and unknown
                // fields. A partial/corrupt intent never falls back to another role or ENROLL.
                val canonical = encode(result)
                try { require(bytes.contentEquals(canonical)) } finally { canonical.fill(0) }
                result
            } finally { bytes.fill(0) }
        } catch (_: Exception) { Read.Unresolved }
    }

    public fun retainHost(actual: SelfOwnedAllocationCoordinatesV1, settings: LocalSetupLanSettings,
        cohost: Boolean): Unit = withSetupLock(monitor) { write(Read.Host(settings, fields(actual), cohost)) }

    /** Called only for an explicitly selected original Master, before connection dispatch. */
    public fun retainClient(originalPairingActionId: DomainId, settings: LocalSetupLanSettings,
        endpoint: LocalSetupLanEndpoint? = null): Unit = withSetupLock(monitor) {
        endpoint?.let(settings::validateEndpoint)
        write(Read.Client(settings, originalPairingActionId, endpoint))
    }

    public fun retainStop(): Unit = withSetupLock(monitor) { write(Read.Stopped) }

    private fun write(value: Read) {
        val bytes = encode(value)
        try {
            storage.replaceAndForce(bytes)
            val actual = checkNotNull(storage.read())
            try { check(actual.contentEquals(bytes)) } finally { actual.fill(0) }
            check(read() != Read.Unresolved)
        } finally { bytes.fill(0) }
    }

    private fun encode(value: Read): ByteArray {
        val selected = when (value) { is Read.Host -> value.settings; is Read.Client -> value.settings; else -> null }
        val ids = when (value) { is Read.Host -> value.identities; is Read.Client -> listOf(value.originalPairingActionId.value); else -> emptyList() }
        val role = when (value) { is Read.Host -> "HOST"; is Read.Client -> "CLIENT"; Read.Stopped -> "STOP"; else -> error("No LAN intent") }
        val encoded = buildJsonObject {
            put("format", FORMAT); put("role", role)
            put("cohost", (value as? Read.Host)?.cohost == true)
            put("binding", JsonArray(ids.map(::JsonPrimitive)))
            put("endpoint", (value as? Read.Client)?.endpoint?.let { endpoint -> buildJsonObject {
                put("address", endpoint.address); put("port", endpoint.port)
            } } ?: JsonNull)
            put("settings", selected?.let { settings -> buildJsonObject {
                put("subnets", JsonArray(settings.lan.allowedSubnets.map(::JsonPrimitive)))
                put("interface", settings.lan.interfaceName); put("address", settings.lan.localAddress)
                put("port", settings.lan.listenPort); put("workflow", settings.workflow.name)
                put("credential", settings.credentialAction != null); put("actor", settings.actorIntentAction != null)
                put("advertise", settings.advertiseHost)
            } } ?: JsonNull)
        }.toString().encodeToByteArray()
        if (encoded.size !in 1..MAXIMUM_BYTES) { encoded.fill(0); error("LAN startup intent exceeds storage bound") }
        return encoded
    }

    private fun settings(value: JsonObject): LocalSetupLanSettings {
        require(value.keys == setOf("subnets", "interface", "address", "port", "workflow", "credential", "actor", "advertise"))
        val credential = value.getValue("credential").jsonPrimitive.boolean
        val actor = value.getValue("actor").jsonPrimitive.boolean
        return LocalSetupLanSettings.from(LocalSetupLanInput(
            value.getValue("subnets").jsonArray.map { it.jsonPrimitive.also { text -> require(text.isString) }.content },
            value.string("interface"), value.string("address"), value.getValue("port").jsonPrimitive.int,
            if (credential) LocalPairedDeviceCredentialJournalAction.REOPEN else null,
            LocalSetupLanWorkflow.valueOf(value.string("workflow")),
            if (actor) LocalPairedDeviceCredentialJournalAction.REOPEN else null,
            value.getValue("advertise").jsonPrimitive.boolean,
        ))
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.also { require(it.isString) }.content

    public companion object {
        public const val MAXIMUM_BYTES: Int = 8192
        private const val FORMAT = "ukkera.local-lan-launch/v1"
        private fun fields(value: SelfOwnedAllocationCoordinatesV1): List<String> = listOf(value.allocationId,
            value.scope.organizationId, value.scope.companyId, value.scope.branchId, value.installationId,
            value.assignmentId, value.deviceId, value.storageInstanceId, value.storageKeyId,
            value.pepperKeyId, value.targetSigningKeyId, value.firstActorId).map { it.value }
    }
}
