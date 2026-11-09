package com.ukkera.pos.app.gateway.independent.sessionnoaccessv1

import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.ControlledPolicyInput
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Hash-checked frozen inputs and expected paragraphs; no execution status is written here. */
internal class FrozenLocalSessionNoAccessV1 private constructor(
    private val cases: JsonObject,
    private val fixtures: JsonObject,
) {
    internal fun step(caseId: String, number: Int): FrozenNoAccessStep {
        val case = cases.getValue("cases").jsonArray.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == caseId
        }.jsonObject
        val step = case.getValue("steps").jsonArray.single {
            it.jsonObject.getValue("step").jsonPrimitive.content.toInt() == number
        }.jsonObject
        return FrozenNoAccessStep(caseId, number, step.getValue("expected").jsonPrimitive.content)
    }

    internal fun id(key: String): DomainId = DomainId(fixtures.getValue("fixed_ids").jsonObject
        .getValue(key).jsonPrimitive.content)

    internal fun employeeIdentity(): LocalClientIdentity = LocalClientIdentity(
        BranchScope(id("organization"), id("company"), id("branch")), id("employee_a"), id("client_device"),
    )

    /**
     * Bounded native projection samples from the permitted fixture behavior. These are controlled
     * reply data, not a provisioned/activated master policy, permission limits or recovery grant.
     * Financial commands, subscriptions, exports and successful protected projections are unbound.
     */
    internal fun projectionSample(policyId: String): ControlledPolicyInput {
        val policy = fixtures.getValue("policies").jsonObject.getValue(policyId).jsonObject
        val revision = Revision(policy.getValue("activated_revision").jsonPrimitive.content.toLong())
        fun perform(action: SecurityAction) = Permission(action, PermissionVerb.PERFORM)
        val permissions = when (policyId) {
            "P10" -> listOf(perform(SecurityAction.ORDER_CREATE), perform(SecurityAction.ORDER_EDIT),
                perform(SecurityAction.ORDER_READ), perform(SecurityAction.CASH_READ),
                perform(SecurityAction.INVENTORY_QUANTITY_READ), perform(SecurityAction.INVENTORY_COST_READ),
                perform(SecurityAction.BRANCH_REPORT), Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.EXPORT))
            "P11" -> {
                check(policy.getValue("effective_operational_permissions").jsonArray.isEmpty())
                check(policy.getValue("effective_operational_projections").jsonArray.isEmpty())
                check(policy.getValue("effective_export_and_alert_grants").jsonArray.isEmpty())
                check(policy.getValue("operation_recovery_grant").jsonPrimitive.content == "false")
                emptyList()
            }
            "P12" -> listOf(perform(SecurityAction.ORDER_CREATE), perform(SecurityAction.ORDER_EDIT),
                perform(SecurityAction.ORDER_READ), perform(SecurityAction.INVENTORY_QUANTITY_READ))
            else -> error("No frozen policy input is bound for $policyId.")
        }
        return ControlledPolicyInput(revision, permissions)
    }

    internal companion object {
        private const val DIRECTORY = "qa/pos/designs/local-session-no-access-v1"
        private val hashes = mapOf(
            "README.md" to "1f4023e8834072194dd995595ca761fc04e5636bad4a64b4fbcad1125d99f89a",
            "cases.json" to "2793bbde0a4c8821ff1da58e49779a5ed398a26db5f0eef21e519e32463cc2ba",
            "fixtures.json" to "cbab5b991a6fef70a71ca5fd820b97f9cdf9111cd783885d86a26c9ac28e2615",
        )

        internal fun load(): FrozenLocalSessionNoAccessV1 {
            val directory = repositoryRoot().resolve(DIRECTORY)
            val bytes = hashes.mapValues { (name, expected) ->
                Files.readAllBytes(directory.resolve(name)).also {
                    check(sha256(it) == expected) { "Frozen no-access file hash mismatch: $name" }
                }
            }
            val json = Json { ignoreUnknownKeys = false }
            val cases = json.parseToJsonElement(bytes.getValue("cases.json").decodeToString()).jsonObject
            val fixtures = json.parseToJsonElement(bytes.getValue("fixtures.json").decodeToString()).jsonObject
            check(cases.getValue("design_id").jsonPrimitive.content == "local-session-no-access-v1")
            check(cases.getValue("design_version").jsonPrimitive.content == "1.0.0")
            check(fixtures.getValue("fixture_version").jsonPrimitive.content == "1.0.0")
            check(cases.getValue("frozen_expectation_status").jsonPrimitive.content == "FROZEN_BEFORE_EXECUTION")
            check(cases.getValue("cases").jsonArray.size == 6)
            check(cases.getValue("cases").jsonArray.all {
                it.jsonObject.getValue("execution_status").jsonPrimitive.content == "NOT RUN"
            })
            check(cases.getValue("public_open_mappings").jsonArray.map {
                it.jsonObject.getValue("id").jsonPrimitive.content
            }.toSet() == setOf("OM-01", "OM-02"))
            return FrozenLocalSessionNoAccessV1(cases, fixtures)
        }

        private fun repositoryRoot(): Path {
            val configured = System.getProperty("ukkera.pos.repositoryRoot")
            if (configured != null) return Paths.get(configured).toAbsolutePath().normalize().also {
                check(Files.isDirectory(it.resolve(DIRECTORY))) { "Configured no-access freeze is unavailable." }
            }
            return generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()) { it.parent }
                .firstOrNull { Files.isDirectory(it.resolve(DIRECTORY)) }
                ?: error("No-access freeze is unavailable; supply the repository root for this harness.")
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

internal data class FrozenNoAccessStep(val caseId: String, val number: Int, val expected: String) {
    internal fun message(observedSlice: String): String =
        "$caseId step $number: $observedSlice. Frozen expectation: $expected"
}
