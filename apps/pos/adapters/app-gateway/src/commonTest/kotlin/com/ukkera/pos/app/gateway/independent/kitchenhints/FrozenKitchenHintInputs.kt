package com.ukkera.pos.app.gateway.independent.kitchenhints

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.OwnerAccess
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionLimits
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.DomainId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Literal readers only. No station-assignment, context-retirement, or state predicate lives here. */
internal object FrozenKitchenHintInputs {
    private val fixtures: JsonObject = Json.parseToJsonElement(FROZEN_KITCHEN_HINT_FIXTURES).jsonObject
    private val matrix: JsonObject = Json.parseToJsonElement(FROZEN_KITCHEN_HINT_MATRIX).jsonObject

    internal val permissionRows: List<JsonObject> = matrix.array("permissionAndContextRows").map { it.jsonObject }
    internal val priorStateRows: List<JsonObject> = matrix.array("priorStateRows").map { it.jsonObject }

    // Explicit binding selections, not rewritten cases or a substitute context/permission algorithm.
    // Other rows retain all frozen expectations and are recorded as blocked for unsupported slices.
    internal val currentStationRowIds: List<String> = listOf(
        "KW3-MATRIX-001", "KW3-MATRIX-002", "KW3-MATRIX-004", "KW3-MATRIX-005",
        "KW3-MATRIX-006", "KW3-MATRIX-007", "KW3-MATRIX-008", "KW3-MATRIX-009",
        "KW3-MATRIX-014", "KW3-MATRIX-017", "KW3-MATRIX-018", "KW3-MATRIX-019",
        "KW3-MATRIX-020", "KW3-MATRIX-023", "KW3-MATRIX-024",
    )
    internal val currentDirectoryRowIds: List<String> = listOf(
        "KW3-MATRIX-001", "KW3-MATRIX-002", "KW3-MATRIX-004", "KW3-MATRIX-005",
        "KW3-MATRIX-006", "KW3-MATRIX-007", "KW3-MATRIX-008", "KW3-MATRIX-009",
        "KW3-MATRIX-013", "KW3-MATRIX-014", "KW3-MATRIX-015", "KW3-MATRIX-016",
        "KW3-MATRIX-017", "KW3-MATRIX-018", "KW3-MATRIX-019", "KW3-MATRIX-020",
        "KW3-MATRIX-022", "KW3-MATRIX-023", "KW3-MATRIX-024",
    )
    internal val rejectedCandidateRowIds: List<String> = listOf("KW3-MATRIX-003", "KW3-MATRIX-021")

    internal val stationPermission: Permission = Permission(SecurityAction.KITCHEN_TASK, PermissionVerb.PERFORM)
    internal val directoryPermission: Permission = Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM)

    internal fun permissionRow(id: String): JsonObject = permissionRows.single { it.text("id") == id }

    internal fun expected(row: JsonObject): JsonObject = row.obj("expected")

    internal fun session(row: JsonObject): JsonObject =
        fixtures.obj("displaySessionInputs").obj(row.obj("input").text("sessionViewRef"))

    internal fun observation(row: JsonObject): JsonObject =
        fixtures.obj("taskObservationInputs").obj(row.obj("input").text("taskObservationRef"))

    internal fun actionDeclaration(alias: String): JsonObject = fixtures.obj("actionForms").obj(alias)

    internal fun action(alias: String): LocalAction = LocalAction.valueOf(actionDeclaration(alias).text("actionName"))

    internal fun id(ref: String): DomainId {
        val value = fixtures.obj("reusedSyntheticIDs")[ref]
            ?: fixtures.obj("newSyntheticIDs").getValue(ref)
        return DomainId(value.jsonPrimitive.content)
    }

    internal fun scope(ref: String): BranchScope {
        val input = fixtures.obj("businessScopes").obj(ref)
        return BranchScope(
            id(input.text("organizationIdRef")),
            id(input.text("companyIdRef")),
            id(input.text("branchIdRef")),
        )
    }

    internal fun grantArguments(row: JsonObject): List<FrozenKitchenGrantArguments> =
        row.obj("input").array("permissionGrantRefs").map { grantArguments(it.jsonPrimitive.content) }

    internal fun grantArguments(ref: String): FrozenKitchenGrantArguments {
        val input = fixtures.obj("permissionGrantInputs").obj(ref)
        val coordinate = input.obj("permission")
        require(input.text("limits") == "PermissionLimits() declared default") {
            "Unsupported frozen limits representation: $ref"
        }
        require(input.getValue("delegationCeiling") == JsonNull) {
            "Unsupported frozen delegation-ceiling representation: $ref"
        }
        val targetMembers = input.optionalText("targetIdsSetRef")?.let { setRef ->
            fixtures.obj("targetSets").array(setRef).map { DomainId(it.jsonPrimitive.content) }
        }
        // Conversion to the declared Set type must never silently remove a literal member.
        require(targetMembers == null || targetMembers.size == targetMembers.toSet().size) {
            "Frozen target members cannot be represented unchanged: $ref"
        }
        return FrozenKitchenGrantArguments(
            ref = ref,
            originalInput = input,
            permission = Permission(
                SecurityAction.valueOf(coordinate.text("action")),
                PermissionVerb.valueOf(coordinate.text("verb")),
            ),
            scope = scope(input.text("scopeRef")),
            effect = GrantEffect.valueOf(input.text("effect")),
            limits = PermissionLimits(),
            ownerAccess = OwnerAccess.valueOf(input.text("ownerAccess")),
            namedSubjectId = input.optionalText("namedSubjectIdRef")?.let(::id),
            targetIds = targetMembers?.toSet(),
            delegationCeiling = null,
        )
    }
}

/** Every constructor argument is resolved before a rejection assertion invokes production code. */
internal data class FrozenKitchenGrantArguments(
    internal val ref: String,
    internal val originalInput: JsonObject,
    internal val permission: Permission,
    internal val scope: BranchScope,
    internal val effect: GrantEffect,
    internal val limits: PermissionLimits,
    internal val ownerAccess: OwnerAccess,
    internal val namedSubjectId: DomainId?,
    internal val targetIds: Set<DomainId>?,
    internal val delegationCeiling: PermissionLimits?,
) {
    internal fun construct(): PermissionGrant = PermissionGrant(
        permission = permission,
        scope = scope,
        effect = effect,
        limits = limits,
        ownerAccess = ownerAccess,
        namedSubjectId = namedSubjectId,
        targetIds = targetIds,
        delegationCeiling = delegationCeiling,
    )
}

internal fun JsonObject.obj(key: String): JsonObject = getValue(key).jsonObject
internal fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray
internal fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.long(key: String): Long = text(key).toLong()
internal fun JsonObject.optionalText(key: String): String? =
    getValue(key).let { if (it == JsonNull) null else it.jsonPrimitive.content }

/** A strict projection of copied frozen outcome tokens, never an implementation-derived oracle. */
internal fun frozenHintBoolean(token: String): Boolean = when (token) {
    "ALLOW_CURRENT_STATION_READ_HINT",
    "ALLOW_CURRENT_SCOPE_DIRECTORY_HINT",
    "ALLOW_CURRENT_SCOPE_DIRECTORY_HINT_FROM_SEPARATE_VALID_CAT_GRANT",
    "ALLOW_FOOD_STATION_START_INTENT",
    "ALLOW_FOOD_STATION_READY_INTENT" -> true
    "BLOCK_STATION_READ_HINT",
    "BLOCK_DIRECTORY_HINT_EXPLICIT_CAT_DENY",
    "BLOCK_DIRECTORY_HINT_NO_CAT_PERFORM_GRANT",
    "BLOCK_FOOD_STATION_START_INTENT",
    "BLOCK_FOOD_STATION_READY_INTENT" -> false
    else -> error("No boolean binding for frozen outcome token: $token")
}
