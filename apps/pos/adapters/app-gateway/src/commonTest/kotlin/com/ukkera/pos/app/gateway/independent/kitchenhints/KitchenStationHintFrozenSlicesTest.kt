package com.ukkera.pos.app.gateway.independent.kitchenhints

import com.ukkera.pos.app.LocalActionForm
import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalFoodPreparation
import com.ukkera.pos.app.LocalFoodTaskState
import com.ukkera.pos.app.LocalFormField
import com.ukkera.pos.app.LocalScalarValue
import com.ukkera.pos.app.gateway.allow
import com.ukkera.pos.app.gateway.foodTaskForms
import com.ukkera.pos.security.PermissionGrant
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Selected immutable advisory slices only. No test below authenticates, reads a protected page,
 * verifies retirement, stages a command, creates a journal, or establishes master authority.
 * Unsupported frozen assertions are not converted into passing tests.
 */
public class KitchenStationHintFrozenSlicesTest {
    @Test
    public fun permissionGrantConstructors_matchFrozenInputShapes(): Unit {
        FrozenKitchenHintInputs.permissionRows.forEach { row ->
            val arguments = FrozenKitchenHintInputs.grantArguments(row)
            val caseId = row.text("id")
            when (FrozenKitchenHintInputs.expected(row).text("shapeDisposition")) {
                "VALID" -> arguments.forEach { input ->
                    assertGrantFieldsPreserved(input, input.construct(), "$caseId / expected.shapeDisposition")
                }
                "INVALID_SUPPLIED_EMPTY_TARGET_SET", "INVALID_SUPPLIED_OVERSIZED_TARGET_SET" ->
                    assertRejectedCandidateShape(row, arguments)
                else -> error("No constructor-shape binding for frozen case $caseId")
            }
        }
    }

    @Test
    public fun currentStationHintsAndAllow_matchFrozenPermissionRows(): Unit {
        FrozenKitchenHintInputs.currentStationRowIds.forEach { caseId ->
            val row = FrozenKitchenHintInputs.permissionRow(caseId)
            withUnitAccess(row) { unit ->
                val expected = FrozenKitchenHintInputs.expected(row)
                val taskInput = FrozenKitchenHintInputs.observation(row).obj("task")
                val stationId = FrozenKitchenHintInputs.id(taskInput.text("stationIdRef"))
                assertEquals(
                    frozenHintBoolean(expected.text("stationLabelAndTasksIntent")),
                    unit.access.hasHint(FrozenKitchenHintInputs.stationPermission, stationId),
                    "$caseId / expected.stationLabelAndTasksIntent [hasHint unit slice]",
                )
                assertEquals(
                    frozenHintBoolean(expected.text("startIntentForQueuedTaskPreparingPortion")),
                    unit.access.allow(FrozenKitchenHintInputs.action("START"), stationId),
                    "$caseId / expected.startIntentForQueuedTaskPreparingPortion [allow permission-only slice]",
                )
                assertEquals(
                    frozenHintBoolean(expected.text("readyIntentForStartedTaskPreparingPortion")),
                    unit.access.allow(FrozenKitchenHintInputs.action("READY"), stationId),
                    "$caseId / expected.readyIntentForStartedTaskPreparingPortion [allow permission-only slice]",
                )
                assertZeroBusinessCommandAttempts(row, unit)
            }
        }
    }

    @Test
    public fun currentDirectoryHints_matchFrozenCatalogExpectations(): Unit {
        FrozenKitchenHintInputs.currentDirectoryRowIds.forEach { caseId ->
            val row = FrozenKitchenHintInputs.permissionRow(caseId)
            withUnitAccess(row) { unit ->
                assertEquals(
                    frozenHintBoolean(FrozenKitchenHintInputs.expected(row).text("configuredDirectoryIntent")),
                    unit.access.hasHint(FrozenKitchenHintInputs.directoryPermission),
                    "$caseId / expected.configuredDirectoryIntent [current view only; no retirement binding]",
                )
                assertZeroBusinessCommandAttempts(row, unit)
            }
        }
    }

    @Test
    public fun separateValidDirectoryBaseline_followsFrozenRejectedCandidates(): Unit {
        FrozenKitchenHintInputs.rejectedCandidateRowIds.forEach { caseId ->
            val row = FrozenKitchenHintInputs.permissionRow(caseId)
            // Reject the complete unchanged candidate before constructing the independently declared baseline.
            val rejectedCandidateArguments = FrozenKitchenHintInputs.grantArguments(row)
            assertRejectedCandidateShape(row, rejectedCandidateArguments)
            // This is a new literal CAT_ALLOW_MAIN sample, NOT a filtered/admitted candidate policy.
            val separateBaselineArguments = listOf(FrozenKitchenHintInputs.grantArguments("CAT_ALLOW_MAIN"))
            withUnitAccess(row, separateBaselineArguments) { unit ->
                assertEquals(
                    frozenHintBoolean(FrozenKitchenHintInputs.expected(row).text(
                        "separatelyValidBaselineDirectoryAfterRejectedCandidate",
                    )),
                    unit.access.hasHint(FrozenKitchenHintInputs.directoryPermission),
                    "$caseId / expected.separatelyValidBaselineDirectoryAfterRejectedCandidate [separate baseline only]",
                )
                assertZeroBusinessCommandAttempts(row, unit)
            }
        }
    }

    @Test
    public fun eligibleTaskFormHints_matchFrozenPermissionRows(): Unit {
        FrozenKitchenHintInputs.currentStationRowIds.forEach { caseId ->
            val row = FrozenKitchenHintInputs.permissionRow(caseId)
            withUnitAccess(row) { unit ->
                // These two independent prior-state inputs are stated in the frozen spec and field names.
                // There is no Start transition between the samples and no duplicated state predicate.
                val startTask = KitchenHintUnitScaffolding.task(
                    row, LocalFoodTaskState.QUEUED, LocalFoodPreparation.PREPARING,
                )
                val readyTask = KitchenHintUnitScaffolding.task(
                    row, LocalFoodTaskState.STARTED, LocalFoodPreparation.PREPARING,
                )
                assertDeclaredIntent(
                    row, "START", "startIntentForQueuedTaskPreparingPortion", foodTaskForms(unit.access, startTask),
                )
                assertDeclaredIntent(
                    row, "READY", "readyIntentForStartedTaskPreparingPortion", foodTaskForms(unit.access, readyTask),
                )
                assertZeroBusinessCommandAttempts(row, unit)
            }
        }
    }

    @Test
    public fun priorStateTaskFormHints_matchFrozenStateRows(): Unit {
        FrozenKitchenHintInputs.priorStateRows.forEach { row ->
            val input = row.obj("input")
            val taskState = LocalFoodTaskState.valueOf(input.text("taskState"))
            val preparationState = LocalFoodPreparation.valueOf(input.text("preparationState"))
            withUnitAccess(row) { unit ->
                val task = KitchenHintUnitScaffolding.task(row, taskState, preparationState)
                val forms = foodTaskForms(unit.access, task)
                // Only the two frozen intents are projected; unlisted cancellation/discard forms are not guessed.
                assertDeclaredIntent(row, "START", "startIntent", forms)
                assertDeclaredIntent(row, "READY", "readyIntent", forms)
                assertZeroBusinessCommandAttempts(row, unit)
            }
        }
    }

    private fun assertRejectedCandidateShape(
        row: JsonObject,
        arguments: List<FrozenKitchenGrantArguments>,
    ) {
        // Readers and all argument constructors ran before this block. Only actual PermissionGrant
        // constructors are allowed to produce the rejection; no exception type/message is prescribed.
        assertFails("${row.text("id")} / expected.shapeDisposition [PermissionGrant constructor]") {
            arguments.forEach { it.construct() }
        }
        // No rejected candidate reaches GatewayAccess or any intention evaluator.
    }

    private fun assertGrantFieldsPreserved(
        input: FrozenKitchenGrantArguments,
        grant: PermissionGrant,
        assertion: String,
    ) {
        val message = "$assertion / ${input.ref} [literal argument preservation]"
        assertEquals(input.permission, grant.permission, "$message.permission")
        assertEquals(input.scope, grant.scope, "$message.scope")
        assertEquals(input.effect, grant.effect, "$message.effect")
        assertEquals(input.limits, grant.limits, "$message.limits")
        assertEquals(input.ownerAccess, grant.ownerAccess, "$message.ownerAccess")
        assertEquals(input.namedSubjectId, grant.namedSubjectId, "$message.namedSubjectId")
        assertEquals(input.targetIds, grant.targetIds, "$message.targetIds")
        assertEquals(input.delegationCeiling, grant.delegationCeiling, "$message.delegationCeiling")
    }

    private fun assertDeclaredIntent(
        row: JsonObject,
        actionAlias: String,
        expectedField: String,
        forms: List<LocalActionForm>,
    ) {
        val caseId = row.text("id")
        val action = FrozenKitchenHintInputs.action(actionAlias)
        val expectedHint = frozenHintBoolean(FrozenKitchenHintInputs.expected(row).text(expectedField))
        assertEquals(
            expectedHint,
            forms.any { it.action == action },
            "$caseId / expected.$expectedField [foodTaskForms advisory presence only]",
        )
        if (expectedHint) {
            val form = assertNotNull(forms.firstOrNull { it.action == action }, "$caseId / $expectedField")
            assertDisplayedCoordinates(row, actionAlias, form)
        }
    }

    private fun assertDisplayedCoordinates(row: JsonObject, actionAlias: String, form: LocalActionForm) {
        val caseId = row.text("id")
        val task = FrozenKitchenHintInputs.observation(row).obj("task")
        val message = "$caseId / $actionAlias / A-PAYLOAD-01+A-REV-01 [displayed form fields only; not command body]"
        assertEquals(FrozenKitchenHintInputs.action(actionAlias), form.action, "$message.action")
        listOf(
            LocalField.ORDER_ID to "orderIdRef",
            LocalField.PORTION_ID to "portionIdRef",
            LocalField.TASK_ID to "taskIdRef",
            LocalField.STATION_ID to "stationIdRef",
        ).forEach { (field, inputKey) ->
            val scalar = assertIs<LocalFormField.Scalar>(
                form.fields.firstOrNull { it.field == field }, "$message.$field",
            )
            assertEquals(
                LocalScalarValue.Identifier(FrozenKitchenHintInputs.id(task.text(inputKey))),
                scalar.initialValue,
                "$message.$field.initialValue",
            )
        }
        listOf(
            LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION to "orderStorageRevision",
            LocalField.EXPECTED_FOOD_PORTION_DOMAIN_REVISION to "preparationDomainRevision",
        ).forEach { (field, inputKey) ->
            val scalar = assertIs<LocalFormField.Scalar>(
                form.fields.firstOrNull { it.field == field }, "$message.$field",
            )
            assertEquals(LocalScalarValue.Integer(task.long(inputKey)), scalar.initialValue, "$message.$field.initialValue")
        }
    }

    private fun assertZeroBusinessCommandAttempts(row: JsonObject, unit: KitchenHintUnitAccess) {
        assertEquals(
            FrozenKitchenHintInputs.expected(row).long("businessCommandSubmissions"),
            unit.endpoint.businessCommandAttempts.toLong(),
            "${row.text("id")} / expected.businessCommandSubmissions [fail-on-call unit endpoint only]",
        )
    }

    private fun withUnitAccess(
        row: JsonObject,
        arguments: List<FrozenKitchenGrantArguments> = FrozenKitchenHintInputs.grantArguments(row),
        block: (KitchenHintUnitAccess) -> Unit,
    ) {
        val unit = KitchenHintUnitScaffolding.access(row, arguments)
        try {
            block(unit)
        } finally {
            unit.destroy()
        }
    }
}
