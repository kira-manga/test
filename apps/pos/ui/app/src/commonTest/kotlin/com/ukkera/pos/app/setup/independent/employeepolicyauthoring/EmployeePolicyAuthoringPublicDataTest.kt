package com.ukkera.pos.app.setup.independent.employeepolicyauthoring

import com.ukkera.pos.app.setup.actionCodes
import com.ukkera.pos.security.SecurityAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

/** Frozen v2 public-DATA slices only; none establishes native preparation or approval. */
class EmployeePolicyAuthoringPublicDataTest {
    private val fixtures = EmployeePolicyAuthoringFixtures

    @Test fun epaC01CompleteFrozenReplacementSurvivesPublicReviewData() {
        val definition = fixtures.definition()
        assertEquals(fixtures.originalRequest, definition.request.requestId)
        assertEquals(fixtures.scope, definition.request.scope)
        assertEquals(fixtures.employee, definition.request.targetId)
        assertEquals(7L, definition.previousPolicyRevision.value)
        fixtures.assertPolicy(actual = definition.nextPolicy)
        // Opaque controlled DTO bytes, not the production effective-policy codec.
        val actual = definition.copyCanonicalPolicy()
        val expected = fixtures.input().toString().encodeToByteArray()
        try { assertContentEquals(expected, actual) }
        finally { actual.fill(0); expected.fill(0) }
    }

    @Test fun epaC03MinimumValuesStayExactInPublicData() = assertExactProfile("minimum_values")
    @Test fun epaC03MaximumValuesStayExactInPublicData() = assertExactProfile("maximum_values")
    @Test fun epaC03CapZeroIsRejectedByPublicValues() = rejectNumeric("cap_zero")
    @Test fun epaC03CapSeventeenIsRejectedByPublicValues() = rejectNumeric("cap_seventeen")
    @Test fun epaC03FreeMinusOneIsRejectedByPublicValues() = rejectNumeric("free_minus_one")
    @Test fun epaC03FreeSixIsRejectedByPublicValues() = rejectNumeric("free_six")
    @Test fun epaC03Initial999IsRejectedByPublicValues() = rejectNumeric("initial_999")
    @Test fun epaC03Initial60001IsRejectedByPublicValues() = rejectNumeric("initial_60001")
    @Test fun epaC03MaximumBelowInitialIsRejectedByPublicValues() = rejectNumeric("maximum_below_initial")
    @Test fun epaC03Maximum86400001IsRejectedByPublicValues() = rejectNumeric("maximum_86400001")
    @Test fun epaC03Timeout999IsRejectedByPublicValues() = rejectNumeric("timeout_999")
    @Test fun epaC03Timeout300001IsRejectedByPublicValues() = rejectNumeric("timeout_300001")
    @Test fun epaC03ApprovalZeroIsRejectedByPublicValues() = rejectNumeric("approval_zero")
    @Test fun epaC03Approval600001IsRejectedByPublicValues() = rejectNumeric("approval_600001")

    @Test fun epaC05UnknownActionIsRejectedByPublicPermissionInput() {
        rejectGrant {
            JsonObject(it + ("permission" to fixtures.invalidGrant("unknown_permission")))
        }
        // Public raw-name-to-typed input only; no native policy rejection is bound.
    }

    @Test fun epaC05UnsupportedReadVerbIsRejectedByPublicPermissionInput() {
        rejectGrant(index = 2) {
            JsonObject(it + ("permission" to fixtures.invalidGrant("unknown_verb_read")))
        }
        // Preserve ORDER_READ/READ exactly as the frozen rejection subrun, never coerce it.
        // Public enum input cannot represent it; native/UI/RPC outcomes remain unbound.
    }

    @Test fun epaC05WrongTenantIsRejectedByPublicValues() {
        rejectGrant {
            val scope = it.getValue("scope").jsonObject
            val wrongBranch = fixtures.invalidGrant("wrong_tenant").jsonObject.getValue("replace_grant_scope_branch")
            JsonObject(it + ("scope" to JsonObject(scope + ("branchId" to wrongBranch))))
        }
    }

    @Test fun epaC05WrongSubjectIsRejectedByPublicValues() {
        rejectGrant {
            JsonObject(it + ("namedSubjectId" to fixtures.invalidGrant("wrong_subject").jsonObject.getValue("replace_namedSubjectId")))
        }
    }

    @Test fun epaC05NegativeAmountIsRejectedByPublicValues() {
        rejectGrant { withLimits(it, "maximumAmount", fixtures.invalidGrant("negative_amount")) }
    }

    @Test fun epaC05PercentageMinusOneIsRejectedByPublicValues() {
        rejectGrant { withLimits(it, "maximumPercentageBasisPoints", fixtures.invalidGrant("percentage_minus_one")) }
    }

    @Test fun epaC05Percentage10001IsRejectedByPublicValues() {
        rejectGrant { withLimits(it, "maximumPercentageBasisPoints", fixtures.invalidGrant("percentage_10001")) }
    }

    @Test fun epaC05FiniteAndNoCeilingIsRejectedByPublicValues() {
        rejectGrant {
            JsonObject(it + ("limits" to JsonObject(it.getValue("limits").jsonObject + fixtures.invalidGrant("finite_and_no_ceiling").jsonObject)))
        }
    }

    @Test fun epaC05EmptyTargetSetIsRejectedByPublicValues() {
        rejectGrant { JsonObject(it + ("targetIds" to fixtures.invalidGrant("empty_target_set"))) }
    }

    @Test fun epaC05TargetSet257IsRejectedByPublicValues() {
        val count = fixtures.invalidGrant("target_set_257").jsonObject.getValue("count").jsonPrimitive.int
        // Exact UUID construction specified by frozen grant_invalid.target_set_257.
        val targets = JsonArray((1..count).map { JsonPrimitive("aaaaaaaa-aaaa-4aaa-8aaa-" + it.toString(16).padStart(12, '0')) })
        rejectGrant { JsonObject(it + ("targetIds" to targets)) }
    }

    @Test fun epaC05Grants1025IsRejectedByPublicValues() {
        val original = fixtures.input()
        fixtures.assertPolicy(actual = fixtures.bindPolicy(original))
        val count = fixtures.invalidGrant("grants_1025").jsonObject.getValue("count").jsonPrimitive.int
        val firstGrant = original.getValue("grants").jsonArray[0]
        val value = JsonObject(original + ("grants" to JsonArray(List(count) { firstGrant })))
        assertFails { fixtures.bindPolicy(value) }
    }

    @Test fun epaC05UnknownSelfActionIsRejectedBySharedActionInput() {
        val text = fixtures.invalidGrant("unknown_self_action").jsonArray.single().jsonPrimitive.content
        assertFails { actionCodes(text) }
    }

    @Test fun epaC06EmptyCompletePolicyStaysEmpty() = assertExactProfile("empty_complete")
    @Test fun epaC06NullApprovalStaysAbsent() = assertExactProfile("null_approval")
    @Test fun epaC06ZeroCeilingsStayZero() = assertExactProfile("zero_ceilings")
    @Test fun epaC06ExplicitNoCeilingRetainsExactCurrency() = assertExactProfile("explicit_no_ceiling")
    @Test fun epaC06MissingMonetaryBoundStaysMissingInPublicData() = assertExactProfile("missing_monetary_limit")

    @Test fun epaC06EmptyAndNamedSelfApprovalSelectionsAreExact() {
        assertEquals(emptySet(), actionCodes(""))
        assertEquals(setOf(SecurityAction.DISCOUNT), actionCodes("DISCOUNT"))
    }

    private fun assertExactProfile(name: String) {
        fixtures.assertPolicy(name, fixtures.definition(name).nextPolicy)
    }

    private fun rejectNumeric(name: String) {
        // A wrong baseline must fail the test, not satisfy the intended bad-field assertion.
        fixtures.assertPolicy(actual = fixtures.policy())
        assertFails { fixtures.patchedNumeric(name) }
    }

    private fun rejectGrant(index: Int = 1, change: (JsonObject) -> JsonObject) {
        val original = fixtures.input()
        fixtures.assertPolicy(actual = fixtures.bindPolicy(original))
        val grants = original.getValue("grants").jsonArray.mapIndexed { grantIndex, item ->
            if (grantIndex == index) change(item.jsonObject) else item
        }
        val value = JsonObject(original + ("grants" to JsonArray(grants)))
        assertFails { fixtures.bindPolicy(value) }
    }

    private fun withLimits(grant: JsonObject, name: String, value: JsonElement): JsonObject =
        JsonObject(grant + ("limits" to JsonObject(grant.getValue("limits").jsonObject + (name to value))))
}
