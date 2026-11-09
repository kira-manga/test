package com.ukkera.pos.app.gateway.independent.sessionzeroaccessv2

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
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Exact LZA v2.0.0 inputs and oracles. Loading never changes an execution status. */
internal class FrozenLocalSessionZeroAccessV2 private constructor(
    private val cases: JsonObject,
    private val fixtures: JsonObject,
) {
    internal fun case(caseId: String): FrozenZeroAccessOracle {
        val case = cases.getValue("cases").jsonArray.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == caseId
        }.jsonObject
        return FrozenZeroAccessOracle(caseId, case.getValue("expected").jsonObject)
    }

    internal fun failure(rowId: String): FrozenZeroAccessOracle {
        val row = fixtures.getValue("failure_rows").jsonArray.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == rowId
        }.jsonObject
        return FrozenZeroAccessOracle("LZA-C04/$rowId", row.getValue("expected").jsonObject)
    }

    internal fun logout(variantId: String): FrozenZeroAccessOracle {
        val variant = fixtures.getValue("logout_variants").jsonArray.single {
            it.jsonObject.getValue("id").jsonPrimitive.content == variantId
        }.jsonObject
        return FrozenZeroAccessOracle("LZA-C05/$variantId", variant)
    }

    internal fun businessEffectOracle(): FrozenZeroAccessOracle =
        FrozenZeroAccessOracle("F-NO-BUSINESS-EFFECT", fixture("F-NO-BUSINESS-EFFECT"))

    internal fun id(key: String): DomainId = DomainId(fixtures.getValue("ids").jsonObject
        .getValue(key).jsonPrimitive.content)

    internal fun employeeIdentity(): LocalClientIdentity = LocalClientIdentity(
        BranchScope(id("organization"), id("company"), id("branch")), id("employee"), id("device"),
    )

    internal fun zeroPolicy(): ControlledPolicyInput {
        val zero = fixture("F-ZERO")
        requireCurrentIdentity(zero)
        check(listOf("operationalAreas", "operationalGrants", "recoveryGrants").all {
            zero.getValue(it).jsonArray.isEmpty()
        })
        return ControlledPolicyInput(Revision(zero.getValue("policyRevision").jsonPrimitive.content.toLong()), emptyList())
    }

    /** Fixed controlled reply values, never a genuine Master/credential/lineage observation. */
    internal fun renewalInput(): FrozenZeroAccessRenewalInput {
        val value = fixture("F-RENEWAL-LINEAGE")
        val policy = zeroPolicy()
        check(value.getValue("policy_revision").jsonPrimitive.content.toLong() == policy.revision.value)
        check(value.getValue("operational_grants").jsonArray.isEmpty())
        for ((field, key) in listOf("employee_id" to "employee", "origin_device_id" to "device",
            "branch_id" to "branch", "renewal_request_id" to "renewalRequest")) {
            check(value.getValue(field).jsonPrimitive.content == id(key).value)
        }
        val labels = value.getValue("credential_observation_labels").jsonObject
        fun credentials(key: String): FrozenZeroAccessCredentialLabels {
            val item = labels.getValue(key).jsonObject
            return FrozenZeroAccessCredentialLabels(item.getValue("access").jsonPrimitive.content,
                item.getValue("refresh").jsonPrimitive.content)
        }
        check(labels.getValue("labels_are_not_tokens_or_native_credentials").jsonPrimitive.content == "true")
        val predecessor = DomainId(value.getValue("predecessor_issued_session_id").jsonPrimitive.content)
        check(predecessor == id("controlledSession"))
        val successor = DomainId(value.getValue("successor_issued_session_id").jsonPrimitive.content)
        check(successor.value == case("LZA-C02").text("issuedSessionId"))
        return FrozenZeroAccessRenewalInput(policy, predecessor, successor, credentials("predecessor"),
            credentials("successor"), Instant.parse(value.getValue("controlled_renewal_time_utc").jsonPrimitive.content),
            Instant.parse(value.getValue("original_work_deadline_utc").jsonPrimitive.content),
            Instant.parse(value.getValue("successor_work_deadline_utc").jsonPrimitive.content))
    }

    /** The sole frozen grant binds the existing quantity permission, without recovery authority. */
    internal fun quantityOnlyPolicy(): ControlledPolicyInput {
        val grant = fixture("F-GRANT").getValue("activatedMasterFact").jsonObject
        requireCurrentIdentity(grant)
        check(grant.getValue("operationalAreas").jsonArray.map { it.jsonPrimitive.content } ==
            listOf("quantity-only stock view"))
        check(grant.getValue("operationalGrants").jsonArray.map { it.jsonPrimitive.content } ==
            listOf("the existing quantity-only stock read projection"))
        check(grant.getValue("recoveryGrants").jsonArray.isEmpty())
        return ControlledPolicyInput(Revision(grant.getValue("policyRevision").jsonPrimitive.content.toLong()),
            listOf(Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM)))
    }

    private fun fixture(id: String): JsonObject = fixtures.getValue("fixtures").jsonObject.getValue(id).jsonObject

    private fun requireCurrentIdentity(value: JsonObject) {
        check(value.getValue("authentication").jsonPrimitive.content == "explicitly confirmed by Master")
        check(value.getValue("sessionCurrent").jsonPrimitive.content == "explicitly confirmed by Master")
        check(value.getValue("accountEnabled").jsonPrimitive.content == "true")
        check(value.getValue("deviceAdmitted").jsonPrimitive.content == "true")
        for ((field, key) in listOf("organizationId" to "organization", "companyId" to "company",
            "branchId" to "branch", "employeeId" to "employee", "originDeviceId" to "device",
            "sessionId" to "controlledSession")) {
            check(value.getValue(field).jsonPrimitive.content == id(key).value)
        }
    }

    internal companion object {
        private const val DIRECTORY = "qa/pos/designs/local-session-zero-access-v2"
        private const val FREEZE_SHA256 = "f064533484b6f690fd262bfba44b34ac510c5cb831d4bb7e4ad893b420080e4a"
        private const val ARTIFACT_SET_SHA256 = "5a5c55a3d2cf83dfde629ce4bc4d99268bcf5bc1cdaa9735981788faff40921d"
        private const val RENEWAL_CONTRACT = "qa/pos/contracts/LOCAL-SESSION-RENEWAL-IDENTITY-v1.md"
        private const val RENEWAL_CONTRACT_SHA256 = "1e1b8e9f47f26a96d96fd2c563f064f3fcb20cea03df0d973fc2b7b3585a2a32"
        private val artifacts = mapOf(
            "README.md" to ("eedc73c635a7ccd57882b877cc8c473e0e413b0ad4ffae6e35b7be4de5690907" to 2424),
            "cases.json" to ("856601e88cc146754a1d3311658007b45e85ba10dbc806991600c8be6b7212c4" to 32759),
            "fixtures.json" to ("cb97dcac0aff3e7aa69efcfa189c59721d14e5928d31ed8351c7c9bd27a6df4f" to 23331),
            "source-manifest.json" to ("041c5670a5af33a25406b038357b36e098cbead00f292a2700465e561d146df7" to 19844),
        )
        private val caseIds = listOf("LZA-C01", "LZA-C02", "LZA-C03", "LZA-C04", "LZA-C05",
            "LZA-M01", "LZA-M02", "LZA-A01")

        internal fun load(): FrozenLocalSessionZeroAccessV2 {
            val root = repositoryRoot()
            val directory = root.resolve(DIRECTORY)
            val freezeBytes = Files.readAllBytes(directory.resolve("FREEZE.json"))
            check(sha256(freezeBytes) == FREEZE_SHA256) { "LZA v2.0.0 freeze hash mismatch." }
            check(sha256(Files.readAllBytes(root.resolve(RENEWAL_CONTRACT))) == RENEWAL_CONTRACT_SHA256) {
                "The frozen LZA v2 renewal identity contract hash does not match."
            }
            val json = Json { ignoreUnknownKeys = false }
            val freeze = json.parseToJsonElement(freezeBytes.decodeToString()).jsonObject
            check(freeze.getValue("version").jsonPrimitive.content == "2.0.0")
            check(freeze.getValue("status").jsonPrimitive.content == "FROZEN")
            check(freeze.getValue("executionStatus").jsonPrimitive.content == "NOT_RUN")
            val frozenArtifacts = freeze.getValue("artifacts").jsonObject
            check(frozenArtifacts.keys == artifacts.keys)
            check(sha256(sortedJson(frozenArtifacts).toString().encodeToByteArray()) == ARTIFACT_SET_SHA256)
            check(freeze.getValue("artifactSetSha256").jsonPrimitive.content == ARTIFACT_SET_SHA256)
            val bytes = artifacts.mapValues { (name, expected) ->
                val metadata = frozenArtifacts.getValue(name).jsonObject
                check(metadata.getValue("sha256").jsonPrimitive.content == expected.first)
                check(metadata.getValue("bytes").jsonPrimitive.content.toInt() == expected.second)
                Files.readAllBytes(directory.resolve(name)).also {
                    check(it.size == expected.second && sha256(it) == expected.first) {
                        "Frozen LZA artifact hash or byte count mismatch: $name"
                    }
                }
            }
            val cases = json.parseToJsonElement(bytes.getValue("cases.json").decodeToString()).jsonObject
            val fixtures = json.parseToJsonElement(bytes.getValue("fixtures.json").decodeToString()).jsonObject
            check(cases.getValue("design_id").jsonPrimitive.content == "local-session-zero-access-v2")
            check(cases.getValue("version").jsonPrimitive.content == "2.0.0")
            check(cases.getValue("freeze_status").jsonPrimitive.content == "FROZEN")
            check(fixtures.getValue("freeze_status").jsonPrimitive.content == "FROZEN")
            check(fixtures.getValue("design_id").jsonPrimitive.content == "local-session-zero-access-v2")
            check(fixtures.getValue("version").jsonPrimitive.content == "2.0.0")
            check(cases.getValue("case_count").jsonPrimitive.content.toInt() == 8)
            check(cases.getValue("cases").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content } == caseIds)
            check(freeze.getValue("caseIds").jsonArray.map { it.jsonPrimitive.content } == caseIds)
            check(cases.getValue("cases").jsonArray.all {
                it.jsonObject.getValue("execution_status").jsonPrimitive.content == "NOT_RUN"
            })
            return FrozenLocalSessionZeroAccessV2(cases, fixtures)
        }

        private fun sortedJson(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.toSortedMap().mapValues { sortedJson(it.value) })
            is JsonArray -> JsonArray(value.map(::sortedJson))
            else -> value
        }

        private fun repositoryRoot(): Path {
            val configured = System.getProperty("ukkera.pos.repositoryRoot")
            if (configured != null) return Paths.get(configured).toAbsolutePath().normalize().also {
                check(Files.isDirectory(it.resolve(DIRECTORY))) { "Configured LZA freeze is unavailable." }
            }
            return generateSequence(Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()) { it.parent }
                .firstOrNull { Files.isDirectory(it.resolve(DIRECTORY)) }
                ?: error("LZA freeze is unavailable; supply the repository root for this harness.")
        }

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

internal data class FrozenZeroAccessCredentialLabels(internal val access: String, internal val refresh: String)

internal data class FrozenZeroAccessRenewalInput(
    internal val policy: ControlledPolicyInput,
    internal val predecessorSessionId: DomainId,
    internal val successorSessionId: DomainId,
    internal val predecessorCredentials: FrozenZeroAccessCredentialLabels,
    internal val successorCredentials: FrozenZeroAccessCredentialLabels,
    internal val controlledTimeUtc: Instant,
    internal val originalWorkDeadlineUtc: Instant,
    internal val successorWorkDeadlineUtc: Instant,
) {
    /** Synthetic coordinate translation for the public MonotonicMoment DATA, not a clock port. */
    internal fun deadlineElapsedMillis(deadline: Instant): Long =
        Duration.between(controlledTimeUtc, deadline).toMillis().also { check(it >= 0) }
}

internal data class FrozenZeroAccessOracle(internal val reference: String, internal val expected: JsonObject) {
    internal fun int(key: String): Int = expected.getValue(key).jsonPrimitive.content.toInt()
    internal fun text(key: String): String = expected.getValue(key).jsonPrimitive.content
    internal fun boolean(key: String): Boolean = expected.getValue(key).jsonPrimitive.content.toBooleanStrict()
    internal fun message(key: String, slice: String): String =
        "$reference expected.$key: $slice. Frozen oracle: ${expected.getValue(key)}"
}
