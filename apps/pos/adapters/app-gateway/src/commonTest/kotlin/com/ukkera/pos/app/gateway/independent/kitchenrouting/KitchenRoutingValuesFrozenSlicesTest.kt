package com.ukkera.pos.app.gateway.independent.kitchenrouting

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.queryDigest
import com.ukkera.pos.sales.FoodIngredientReturnDisposition
import com.ukkera.pos.sales.store.LocalFoodCodecV1
import com.ukkera.pos.sales.store.LocalFoodRequestV1
import com.ukkera.pos.sales.store.LocalFoodStationActionV1
import com.ukkera.pos.sales.store.LocalFoodStockCodecV2
import com.ukkera.pos.sales.store.LocalFoodStockRequestV2
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frozen KRT-P03/P04/P05 public value slices; no staging, permission decision or Master execution. */
public class KitchenRoutingValuesFrozenSlicesTest {
    @Test
    public fun krtP03FoodProfilesPreserveWholePortionCoordinates() {
        val commandRows = frozenRoutingValueInputs().getValue("commandCases").jsonArray
        val actualKeys = mutableMapOf<String, CommandKey>()
        for (element in commandRows) {
            val fixtures = frozenRoutingValueInputs()
            val row = element.jsonObject
            val id = row.valueText("id")
            val profileName = row.valueText("profile")
            val profile = fixtures.getValue("profiles").jsonObject.getValue(profileName).jsonObject
            val stationFields = row.getValue("stationRequest").jsonObject
            val station = stationFromFrozenFields(stationFields)
            val operationId = OperationId(row.valueText("operationId"))
            val command = when (profileName) {
                "legacy" -> LocalFoodCodecV1.command(operationId, station)
                "normalized" -> LocalFoodStockCodecV2.command(
                    operationId, LocalFoodStockRequestV2.Preparation(station),
                )
                else -> error("Unknown frozen FOOD profile")
            }

            assertEquals(row.valueText("operationId"), command.operationId.value, "$id exact operation ID")
            assertEquals(
                CommandKey(profile.valueText("commandName"), profile.getValue("version").jsonPrimitive.int),
                command.command, "$id exact command profile",
            )
            val decoded = when (profileName) {
                "legacy" -> LocalFoodCodecV1.decode(command.payload)
                "normalized" -> assertIs<LocalFoodStockRequestV2.Preparation>(
                    LocalFoodStockCodecV2.decode(command.payload), "$id exact Preparation wrapper",
                ).request
                else -> error("Unknown frozen FOOD profile")
            }
            val actualStation = assertIs<LocalFoodRequestV1.Station>(decoded, "$id exact Station type")
            assertStationFields(stationFields, actualStation, id)
            assertFrozenTaskTuple(fixtures.getValue("taskCoordinates").jsonObject, actualStation, id)
            actualKeys[profileName] = command.command
        }
        assertNotEquals(actualKeys.getValue("legacy"), actualKeys.getValue("normalized"), "KRT-P03 distinct profiles")
    }

    @Test
    public fun krtP04AssignmentTargetBoundsAndOmittedDefaults() {
        for (name in listOf("omitted", "null")) {
            val fixtures = frozenRoutingValueInputs()
            val defaults = fixtures.getValue("grantDefaults").jsonObject
            val grant = if (name == "omitted") {
                PermissionGrant(
                    fixtures.frozenPermission(), fixtures.valueScope(defaults.valueText("scope")),
                    GrantEffect.valueOf(defaults.valueText("effect")),
                )
            } else {
                PermissionGrant(
                    fixtures.frozenPermission(), fixtures.valueScope(defaults.valueText("scope")),
                    GrantEffect.valueOf(defaults.valueText("effect")), targetIds = null,
                )
            }
            assertGrantFields(fixtures, defaults, grant, "KRT-P04/targets-$name")
        }
        for ((name, size) in listOf("one" to 1, "maximum" to 256)) {
            val fixtures = frozenRoutingValueInputs()
            val defaults = fixtures.getValue("grantDefaults").jsonObject
            val targets = fixtures.getValue("targets").jsonObject.getValue(name)
            val grant = PermissionGrant(
                fixtures.frozenPermission(), fixtures.valueScope(defaults.valueText("scope")),
                GrantEffect.valueOf(defaults.valueText("effect")), targetIds = targets.frozenTargetIds(),
            )
            val expected = JsonObject(defaults + ("targetIds" to targets))
            assertGrantFields(fixtures, expected, grant, "KRT-P04/targets-$name")
            assertEquals(size, grant.targetIds?.size, "KRT-P04/targets-$name exact input bound")
        }
        for (name in listOf("empty", "oversize")) {
            val fixtures = frozenRoutingValueInputs()
            val defaults = fixtures.getValue("grantDefaults").jsonObject
            val targets = fixtures.getValue("targets").jsonObject.getValue(name).frozenTargetIds()
            val result = runCatching {
                PermissionGrant(
                    fixtures.frozenPermission(), fixtures.valueScope(defaults.valueText("scope")),
                    GrantEffect.valueOf(defaults.valueText("effect")), targetIds = targets,
                )
            }
            assertTrue(result.isFailure, "KRT-P04/targets-$name must refuse construction")
            assertNull(result.getOrNull(), "KRT-P04/targets-$name has no grant")
        }
    }

    @Test
    public fun krtP04AssignmentVariantsRemainOnlyConstructorInputs() {
        val variantRows = frozenRoutingValueInputs().getValue("permissionVariants").jsonArray
        for (element in variantRows) {
            val fixtures = frozenRoutingValueInputs()
            val row = element.jsonObject
            val id = "KRT-P04/${row.valueText("id")}"
            val grant = PermissionGrant(
                permission = fixtures.frozenPermission(),
                scope = fixtures.valueScope(row.valueText("scope")),
                effect = GrantEffect.valueOf(row.valueText("effect")),
                namedSubjectId = row.valueNullableText("namedSubjectId")?.let(::DomainId),
                targetIds = row.getValue("targetIds").frozenTargetIds(),
            )
            assertGrantFields(fixtures, row, grant, id)
        }
    }

    @Test
    public fun krtP05QueryDigestSyntaxAndFrozenRelationsOnly() {
        val inputs = frozenRoutingValueInputs().getValue("queryDigestInputs").jsonArray
        val oracle = frozenRoutingValueInputs().getValue("queryDigestOracle").jsonObject
        val syntax = Regex(oracle.valueText("syntax"))
        val digests = inputs.associate { element ->
            val fixtures = frozenRoutingValueInputs()
            val row = element.jsonObject
            val keyInput = row.getValue("key")
            val keyFields = if (keyInput is JsonObject) keyInput else fixtures.getValue(keyInput.jsonPrimitive.content).jsonObject
            val key = ReadKey(keyFields.valueText("name"), keyFields.getValue("version").jsonPrimitive.int)
            val payloadAlias = row.valueText("normalizedJson").removePrefix("stationRequests.")
            val normalizedJson = fixtures.getValue("stationRequests").jsonObject.valueText(payloadAlias)
            val digest = queryDigest(
                fixtures.valueScope(row.valueText("scope")), DomainId(row.valueText("actorId")),
                key, StoredBytes(normalizedJson.encodeToByteArray()),
            )
            assertTrue(syntax.matches(digest.value), "KRT-P05/${row.valueText("id")} exact digest syntax")
            row.valueText("id") to digest
        }
        for (element in oracle.getValue("equals").jsonArray) {
            val pair = element.jsonArray
            val left = pair[0].jsonPrimitive.content
            val right = pair[1].jsonPrimitive.content
            assertEquals(digests.getValue(left), digests.getValue(right), "KRT-P05/$left equals $right")
        }
        for (element in oracle.getValue("differsFromA1").jsonArray) {
            val other = element.jsonPrimitive.content
            assertNotEquals(digests.getValue("A1"), digests.getValue(other), "KRT-P05/$other differs from A1")
        }
    }
}

private fun JsonObject.valueText(name: String): String = getValue(name).jsonPrimitive.content

private fun JsonObject.valueNullableText(name: String): String? =
    getValue(name).let { if (it == JsonNull) null else it.jsonPrimitive.content }

private fun JsonElement.frozenTargetIds(): Set<DomainId>? =
    if (this == JsonNull) null else jsonArray.map { DomainId(it.jsonPrimitive.content) }.toSet()

private fun JsonObject.valueScope(alias: String): BranchScope = getValue(alias).jsonObject.let {
    BranchScope(DomainId(it.valueText("organizationId")), DomainId(it.valueText("companyId")), DomainId(it.valueText("branchId")))
}

private fun JsonObject.frozenPermission(): Permission = getValue("grantDefaults").jsonObject
    .getValue("permission").jsonObject.let {
        Permission(SecurityAction.valueOf(it.valueText("action")), PermissionVerb.valueOf(it.valueText("verb")))
    }

private fun stationFromFrozenFields(fields: JsonObject): LocalFoodRequestV1.Station = LocalFoodRequestV1.Station(
    orderId = DomainId(fields.valueText("orderId")),
    expectedOrderRevision = Revision(fields.valueText("expectedOrderRevision").toLong()),
    portionId = DomainId(fields.valueText("portionId")),
    expectedPortionRevision = Revision(fields.valueText("expectedPortionRevision").toLong()),
    stationId = DomainId(fields.valueText("stationId")),
    action = LocalFoodStationActionV1.valueOf(fields.valueText("action")),
    confirmationId = fields.valueNullableText("confirmationId")?.let(::DomainId),
    returnDisposition = fields.valueNullableText("returnDisposition")?.let(FoodIngredientReturnDisposition::valueOf),
)

private fun assertStationFields(expected: JsonObject, actual: LocalFoodRequestV1.Station, id: String) {
    assertEquals(expected.valueText("orderId"), actual.orderId.value, "$id orderId")
    assertEquals(expected.valueText("expectedOrderRevision"), actual.expectedOrderRevision.value.toString(), "$id order storage revision")
    assertEquals(expected.valueText("portionId"), actual.portionId.value, "$id portionId")
    assertEquals(expected.valueText("expectedPortionRevision"), actual.expectedPortionRevision.value.toString(), "$id preparation domain revision")
    assertEquals(expected.valueText("stationId"), actual.stationId.value, "$id stationId")
    assertEquals(expected.valueText("action"), actual.action.name, "$id action")
    assertEquals(expected.valueNullableText("confirmationId"), actual.confirmationId?.value, "$id confirmationId")
    assertEquals(expected.valueNullableText("returnDisposition"), actual.returnDisposition?.name, "$id returnDisposition")
}

/** Retained taskId/quantity/decoys are not Station arguments; this is tuple input alignment only. */
private fun assertFrozenTaskTuple(task: JsonObject, actual: LocalFoodRequestV1.Station, id: String) {
    assertEquals(task.valueText("orderId"), actual.orderId.value, "$id frozen task tuple order")
    assertEquals(task.valueText("portionId"), actual.portionId.value, "$id frozen task tuple portion")
    assertEquals(task.valueText("stationId"), actual.stationId.value, "$id frozen task tuple station")
    assertEquals(task.valueText("orderStorageRevision"), actual.expectedOrderRevision.value.toString(), "$id frozen task order storage revision")
    assertEquals(task.valueText("preparationDomainRevision"), actual.expectedPortionRevision.value.toString(), "$id frozen task preparation domain revision")
}

private fun assertGrantFields(fixtures: JsonObject, expected: JsonObject, actual: PermissionGrant, id: String) {
    val defaults = fixtures.getValue("grantDefaults").jsonObject
    assertEquals(fixtures.frozenPermission(), actual.permission, "$id permission")
    assertEquals(fixtures.valueScope(expected.valueText("scope")), actual.scope, "$id scope")
    assertEquals(GrantEffect.valueOf(expected.valueText("effect")), actual.effect, "$id effect")
    val limits = when (defaults.valueText("limits")) {
        "PermissionLimits()" -> PermissionLimits()
        else -> error("Unknown frozen permission limits input")
    }
    assertEquals(limits, actual.limits, "$id omitted limits")
    assertEquals(OwnerAccess.valueOf(defaults.valueText("ownerAccess")), actual.ownerAccess, "$id omitted ownerAccess")
    assertEquals(expected.valueNullableText("namedSubjectId")?.let(::DomainId), actual.namedSubjectId, "$id namedSubjectId")
    assertEquals(expected.getValue("targetIds").frozenTargetIds(), actual.targetIds, "$id exact targetIds")
    assertNull(actual.delegationCeiling, "$id omitted null delegationCeiling")
}
