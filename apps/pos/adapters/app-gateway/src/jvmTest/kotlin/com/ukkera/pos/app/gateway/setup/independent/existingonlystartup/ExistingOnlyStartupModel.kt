package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
import com.ukkera.pos.app.gateway.setup.LocalSetupClient
import com.ukkera.pos.app.gateway.setup.LocalSetupMaster
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeMaterialView
import com.ukkera.pos.security.SelfOwnedNativeReadResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class Scene(
    val recordId: String,
    val target: String?,
    val binding: String?,
    val master: String?,
    val setup: String?,
    val client: String?,
    val retirement: String,
    val withdraw: String,
    val closeTarget: String,
    val closeMaster: String,
    val closeClient: String,
    val closeExpectation: String,
    val race: Race?,
    val presentation: String,
    val settlement: String,
    val drain: String,
    val denials: String,
    val firstClose: String,
    val expected: String,
    val extra: String,
) {
    companion object {
        private val requiredKeys = listOf(
            "record", "target", "binding", "master", "setup", "client", "retirement", "withdraw",
            "close_target", "close_master", "close_client", "close_expectation", "race", "presentation",
            "settlement", "drain", "denials", "first_close", "expected",
        )
        private val raceKeys = setOf(
            "hold", "on_enter", "release", "after_settle", "after_outcome", "after_closed", "stages",
        )
        private val stageKeys = setOf(
            "hold", "on_enter", "release", "after_settle", "after_outcome", "after_closed",
        )

        fun from(variant: FrozenVariant, fixtures: ExistingOnlyStartupFixtures): Scene {
            fun value(key: String): String = variant.scene.requiredString(key)
            fun optional(key: String): String? = variant.scene.optionalString(key)?.takeIf { it != "NONE" }
            require(variant.scene.keys == requiredKeys.toSet()) {
                "${variant.variantId} declares unknown or missing scene keys"
            }
            val race = variant.scene["race"]?.takeIf { it !is JsonNull }?.jsonObject?.let { element ->
                require(element.keys.all { it in raceKeys }) {
                    "${variant.variantId} has unknown race keys ${element.keys - raceKeys}"
                }
                val explicitStages = element["stages"]?.jsonArray?.map { stageElement ->
                    val stage = stageElement.jsonObject
                    require(stage.keys.all { it in stageKeys }) {
                        "${variant.variantId} has unknown race stage keys ${stage.keys - stageKeys}"
                    }
                    RaceStage.from(stage).also { parsed ->
                        require(parsed.afterSettle.isEmpty() && parsed.afterOutcome.isEmpty() &&
                            parsed.afterClosed.isEmpty()) {
                            "${variant.variantId} has an unimplemented staged after_* script"
                        }
                    }
                }
                if (explicitStages != null) {
                    require(element.keys.none { it in stageKeys }) {
                        "${variant.variantId} mixes staged and flat race fields"
                    }
                    require(explicitStages.isNotEmpty()) { "${variant.variantId} has an empty race" }
                    Race(
                        hold = null,
                        onEnter = emptyList(),
                        release = null,
                        afterSettle = emptyList(),
                        afterOutcome = emptyList(),
                        afterClosed = emptyList(),
                        stages = explicitStages,
                    )
                } else {
                    val hold = element.optionalString("hold")
                    if (hold == null) {
                        Race(
                            hold = null,
                            onEnter = element.strings("on_enter"),
                            release = null,
                            afterSettle = element.strings("after_settle"),
                            afterOutcome = element.strings("after_outcome"),
                            afterClosed = element.strings("after_closed"),
                            stages = emptyList(),
                        )
                    } else {
                        val stage = RaceStage.from(element)
                        Race(
                            hold = stage.hold,
                            onEnter = stage.onEnter,
                            release = stage.release,
                            afterSettle = stage.afterSettle,
                            afterOutcome = stage.afterOutcome,
                            afterClosed = stage.afterClosed,
                            stages = listOf(stage),
                        )
                    }
                }
            }
            val scene = Scene(
                recordId = value("record"),
                target = optional("target"),
                binding = optional("binding"),
                master = optional("master"),
                setup = optional("setup"),
                client = optional("client"),
                retirement = value("retirement"),
                withdraw = value("withdraw"),
                closeTarget = value("close_target"),
                closeMaster = value("close_master"),
                closeClient = value("close_client"),
                closeExpectation = value("close_expectation"),
                race = race,
                presentation = value("presentation"),
                settlement = value("settlement"),
                drain = value("drain"),
                denials = value("denials"),
                firstClose = value("first_close"),
                expected = value("expected"),
                extra = variant.extra,
            )
            for (key in requiredKeys) {
                require(variant.scene.containsKey(key)) { "${variant.variantId} is missing scene key $key" }
            }
            return scene
        }
    }
}

internal data class Race(
    val hold: String?,
    val onEnter: List<String>,
    val release: String?,
    val afterSettle: List<String>,
    val afterOutcome: List<String>,
    val afterClosed: List<String>,
    val stages: List<RaceStage>,
)

internal data class RaceStage(
    val hold: String?,
    val onEnter: List<String>,
    val release: String?,
    val afterSettle: List<String>,
    val afterOutcome: List<String>,
    val afterClosed: List<String>,
) {
    companion object {
        fun from(element: JsonObject): RaceStage {
            val hold = element.optionalString("hold")
            val release = element.optionalString("release")
            require(hold != null) { "Race stage has no hold gate" }
            require(release == null || release == hold) {
                "Race stage releases $release while holding $hold"
            }
            return RaceStage(
                hold = hold,
                onEnter = element.strings("on_enter"),
                release = release,
                afterSettle = element.strings("after_settle"),
                afterOutcome = element.strings("after_outcome"),
                afterClosed = element.strings("after_closed"),
            )
        }
    }
}

private fun JsonObject.strings(key: String): List<String> =
    get(key)?.let { element ->
        if (element is JsonNull) emptyList()
        else element.jsonArray.map { it.jsonPrimitive.content }
    } ?: emptyList()

internal data class TargetProfile(
    val id: String,
    val result: String,
    val contextAlias: String,
    val coordinateSource: String,
    val foreignAt: Int?,
    val reason: SelfOwnedInstallationFailureV1,
    val reasonAlias: String?,
    val exceptionAlias: String?,
    val holdGate: String?,
) {
    companion object {
        fun from(fixtures: ExistingOnlyStartupFixtures, id: String): TargetProfile {
            val value = fixtures.profile("targets", id)
            val coordinates = value["coordinates"]?.jsonObject
            return TargetProfile(
                id = id,
                result = value.requiredString("result"),
                contextAlias = value.optionalString("context_alias") ?: "CTX-$id",
                coordinateSource = coordinates?.optionalString("source") ?: "canonical",
                foreignAt = coordinates?.optionalInt("replace_index"),
                reason = reason(value.optionalString("reason_alias")),
                reasonAlias = value.optionalString("reason_alias"),
                exceptionAlias = value.optionalString("exception_alias"),
                holdGate = value.optionalString("hold_gate"),
            )
        }
    }
}

internal data class BindingProfile(
    val id: String,
    val result: String,
    val materialSource: String,
    val bindingSource: String,
    val foreignIndex: Int?,
    val reason: SelfOwnedInstallationFailureV1,
    val reasonAlias: String?,
    val exceptionAlias: String?,
    val holdGate: String?,
) {
    companion object {
        fun from(fixtures: ExistingOnlyStartupFixtures, id: String): BindingProfile {
            val value = fixtures.profile("bindings", id)
            return BindingProfile(
                id = id,
                result = value.requiredString("result"),
                materialSource = value.optionalString("material_source") ?: "canonical",
                bindingSource = value.optionalString("binding_source") ?: "canonical",
                foreignIndex = value.optionalInt("foreign_index"),
                reason = reason(value.optionalString("reason_alias")),
                reasonAlias = value.optionalString("reason_alias"),
                exceptionAlias = value.optionalString("exception_alias"),
                holdGate = value.optionalString("hold_gate"),
            )
        }
    }
}

internal data class MasterProfile(
    val id: String,
    val result: String,
    val initialMode: LocalMasterLifecycleMode,
    val alias: String,
    val reason: SelfOwnedInstallationFailureV1,
    val reasonAlias: String?,
    val exceptionAlias: String?,
    val holdGate: String?,
) {
    companion object {
        fun from(fixtures: ExistingOnlyStartupFixtures, id: String): MasterProfile {
            val value = fixtures.profile("masters", id)
            return MasterProfile(
                id = id,
                result = value.requiredString("result"),
                initialMode = LocalMasterLifecycleMode.valueOf(value.optionalString("initial_mode") ?: "OPERATIONAL"),
                alias = "MST-$id",
                reason = reason(value.optionalString("reason_alias")),
                reasonAlias = value.optionalString("reason_alias"),
                exceptionAlias = value.optionalString("exception_alias"),
                holdGate = value.optionalString("hold_gate"),
            )
        }
    }
}

internal data class SetupProfile(
    val id: String,
    val definition: String,
    val receipt: String,
    val throwRead: Boolean,
) {
    val definitionPresent: Boolean get() = definition == "present"
    val receiptPresent: Boolean get() = receipt == "present"
    val alias: String get() = "SETUP-$id"

    companion object {
        fun from(fixtures: ExistingOnlyStartupFixtures, id: String): SetupProfile {
            val value = fixtures.profile("setups", id)
            return SetupProfile(
                id = id,
                definition = value.requiredString("definition"),
                receipt = value.requiredString("receipt"),
                throwRead = value.requiredBoolean("throw"),
            )
        }
    }
}

internal data class ClientProfile(
    val id: String,
    val result: String,
    val isCurrent: Boolean,
    val alias: String,
    val exceptionAlias: String?,
    val holdGate: String?,
) {
    companion object {
        fun from(fixtures: ExistingOnlyStartupFixtures, id: String): ClientProfile {
            val value = fixtures.profile("clients", id)
            return ClientProfile(
                id = id,
                result = value.requiredString("result"),
                isCurrent = value["is_current"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.boolean ?: true,
                alias = value.optionalString("client_alias") ?: "CLIENT-$id",
                exceptionAlias = value.optionalString("exception_alias"),
                holdGate = value.optionalString("hold_gate"),
            )
        }
    }
}

internal data class CloseProfile(
    val id: String,
    val outcome: String,
    val gate: String?,
    val owner: String?,
    val optional: Boolean,
) {
    companion object {
        fun from(fixtures: ExistingOnlyStartupFixtures, id: String): CloseProfile {
            val value = fixtures.profile("closes", id)
            return CloseProfile(
                id = id,
                outcome = value.requiredString("outcome"),
                gate = value.optionalString("gate"),
                owner = value.optionalString("owner"),
                optional = value["optional"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.boolean ?: false,
            )
        }
    }
}

internal data class WithdrawProfile(
    val id: String,
    val outcome: String,
    val identityRule: String,
    val countRule: String,
) {
    companion object {
        fun from(fixtures: ExistingOnlyStartupFixtures, id: String): WithdrawProfile {
            val value = fixtures.profile("withdraws", id)
            return WithdrawProfile(
                id = id,
                outcome = when (id) {
                    "WD-FORBIDDEN" -> "FORBIDDEN"
                    "WD-CONDITIONAL" -> "CONDITIONAL"
                    "WD-THROW" -> "THROW"
                    "WD-DEFENSIVE-THROW" -> "THROW_IF_ENTERED"
                    else -> error("Unknown effective withdraw profile $id")
                },
                identityRule = value.optionalString("identity_rule") ?: "",
                countRule = value.optionalString("count_rule") ?: "",
            )
        }
    }
}

private fun reason(alias: String?): SelfOwnedInstallationFailureV1 = when (alias) {
    "REASON-TARGET-UNAVAILABLE", "REASON-BINDING-UNAVAILABLE", "REASON-MASTER-UNAVAILABLE" ->
        SelfOwnedInstallationFailureV1.UNAVAILABLE
    "REASON-TARGET-ROLE-REFUSED", "REASON-BINDING-DENIED" ->
        SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED
    else -> SelfOwnedInstallationFailureV1.UNAVAILABLE
}

internal data class StartupObservationLogEntry(
    val sequence: Int,
    val actor: String,
    val method: String,
    val phase: String,
    val alias: String?,
    val detail: String,
)

internal class StartupObservationLog {
    private val entries = mutableListOf<StartupObservationLogEntry>()
    val size: Int get() = entries.size

    fun record(actor: String, method: String, phase: String, alias: String? = null, detail: String = "") {
        entries += StartupObservationLogEntry(entries.size + 1, actor, method, phase, alias, detail)
    }

    fun all(): List<StartupObservationLogEntry> = entries.toList()

    fun count(method: String, phase: String? = null): Int =
        entries.count { it.method == method && (phase == null || it.phase == phase) }

    fun trace(): List<String> = entries.map { entry ->
        buildString {
            append(entry.sequence).append(' ').append(entry.actor).append(' ').append(entry.method)
            append(' ').append(entry.phase)
            entry.alias?.let { append(' ').append(it) }
            if (entry.detail.isNotEmpty()) append(" [").append(entry.detail).append(']')
        }
    }
}

internal enum class OwnerState {
    NO_OWNER, CALL_IN_FLIGHT, HELD, CLOSE_ENTERED, CLOSE_CONFIRMED, CLOSE_UNCONFIRMED, OPTIONAL_CLOSE_OBSERVED,
}

internal enum class ReferenceDisposition {
    NO_HANDLE, NATIVE_OWNER, TRANSFERRED, HELD_TRANSFER_UNKNOWN, CLIENT_RESOURCE,
}

internal class OwnerEntry(val alias: String, val kind: String) {
    var state: OwnerState = OwnerState.NO_OWNER
    var disposition: ReferenceDisposition = ReferenceDisposition.NO_HANDLE
    var closeAttempts: Int = 0
    var confirmations: Int = 0
    var optionalCloseAttempts: Int = 0
    var transferred: Boolean = false
    val notes = mutableListOf<String>()
    override fun toString(): String =
        "$alias($kind, state=$state, disposition=$disposition, attempts=$closeAttempts, " +
            "optional=$optionalCloseAttempts, confirmations=$confirmations, transferred=$transferred)"
}

internal class OwnershipLedger {
    private val entries = linkedMapOf<String, OwnerEntry>()

    fun register(alias: String, kind: String): OwnerEntry =
        entries.getOrPut(alias) { OwnerEntry(alias, kind) }

    fun entry(alias: String): OwnerEntry? = entries[alias]

    fun callInFlight(alias: String, kind: String) {
        val entry = register(alias, kind)
        if (entry.state == OwnerState.NO_OWNER) entry.state = OwnerState.CALL_IN_FLIGHT
    }

    fun failedAcquisition(alias: String) {
        entries[alias]?.let {
            if (it.state == OwnerState.CALL_IN_FLIGHT) it.state = OwnerState.NO_OWNER
            it.disposition = ReferenceDisposition.NO_HANDLE
        }
    }

    fun held(alias: String, kind: String) {
        val entry = register(alias, kind)
        if (entry.state == OwnerState.NO_OWNER || entry.state == OwnerState.CALL_IN_FLIGHT) entry.state = OwnerState.HELD
        entry.disposition = if (kind == "client") ReferenceDisposition.CLIENT_RESOURCE
        else ReferenceDisposition.NATIVE_OWNER
    }

    fun closeEntered(alias: String) {
        val entry = entries.getValue(alias)
        entry.closeAttempts++
        entry.state = OwnerState.CLOSE_ENTERED
    }

    fun confirm(alias: String) {
        val entry = entries.getValue(alias)
        entry.confirmations++
        entry.state = OwnerState.CLOSE_CONFIRMED
    }

    fun unconfirm(alias: String) {
        val entry = entries.getValue(alias)
        entry.state = OwnerState.CLOSE_UNCONFIRMED
    }

    fun optionalCloseObserved(alias: String) {
        val entry = entries.getValue(alias)
        entry.optionalCloseAttempts++
        entry.state = OwnerState.OPTIONAL_CLOSE_OBSERVED
        entry.notes += "optional target-close diagnostic; handle-only, not native confirmation"
    }

    fun transfer(alias: String, to: String) {
        val entry = entries.getValue(alias)
        entry.transferred = true
        entry.disposition = ReferenceDisposition.TRANSFERRED
        entry.notes += "custody transferred to $to; stale context close is not the genuine owner close"
    }

    fun heldTransferUnknown(alias: String) {
        val entry = entries.getValue(alias)
        entry.disposition = ReferenceDisposition.HELD_TRANSFER_UNKNOWN
        entry.notes += "openMaster outcome does not expose transfer status; native custody is indeterminate"
    }

    fun all(): List<OwnerEntry> = entries.values.toList()

    fun summary(): String = entries.values.joinToString("; ")
}

internal data class TargetOpenObservation(
    val preparation: Boolean,
    val sequence: Int,
) {
    var outcome: String = ""
    var contextAlias: String = ""
    var coordinates: SelfOwnedAllocationCoordinatesV1? = null
    var reason: SelfOwnedInstallationFailureV1? = null
}

internal data class MasterOpenObservation(
    val context: SelfOwnedLocalSecurityContext,
    val sequence: Int,
) {
    var outcome: String = ""
    var masterAlias: String = ""
}

internal data class BindingObservation(
    val sequence: Int,
    val scripted: String,
) {
    var outcome: String = ""
    var material: SelfOwnedNativeMaterialView? = null
}

internal data class SetupObservation(
    val definition: String,
    val receipt: String,
    val throwRead: Boolean,
    val sequence: Int,
)

internal data class ClientConnectObservation(
    val master: LocalSetupMaster,
    val action: LocalOriginalClientJournalAction,
    val sequence: Int,
) {
    var outcome: String = ""
    var clientAlias: String = ""
}

internal data class PublishObservation(
    val owner: Any,
    val client: LocalSetupClient,
    val sequence: Int,
    val actor: String = "PRODUCTION",
)

/**
 * Positive public-contract transfer observation: the controlled openMaster port only records
 * this when it returns Opened. The Android original-master factory performs the sole custody
 * handoff inside claimOriginalHistoryCustody; after that point the stale target context is
 * inert and only the returned Master may close the native lifetime.
 */
internal data class TransferObservation(
    val context: SelfOwnedLocalSecurityContext,
    val contextAlias: String,
    val master: LocalSetupMaster,
    val masterAlias: String,
    val sequence: Int,
)

internal data class WithdrawObservation(
    val owner: Any,
    val alias: String,
    val scripted: String,
    val sequence: Int,
    val actor: String = "PRODUCTION",
) {
    var outcome: String = ""
}

internal data class CloseObservation(
    val ownerKind: String,
    val sequence: Int,
) {
    var profile: CloseProfile? = null
    var owner: String = ownerKind
    var held: Boolean = false
    var outcome: String = ""
}

internal data class DenialObservation(
    val method: String,
    val alias: String,
    val sequence: Int,
)
