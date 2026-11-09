package com.ukkera.pos.app.gateway.independent.foodquotedaddv2

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalFoodPreviewInput
import com.ukkera.pos.app.LocalInputLine
import com.ukkera.pos.app.LocalScalarInput
import com.ukkera.pos.app.LocalScalarValue
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Frozen FQDB-03/-04 public input-constructor matrix only. Supplied containers
 * are not an evaluated preview, catalog loan, installation or permission grant,
 * staged operation, durable Add, native service or physical-support fixture.
 */
public class FoodQuotationDiscoveryFrozenPreviewInputTest {
    @Test
    public fun fqdb03CreateFormOnePreservesTheSuppliedInput() {
        assertAccepted(LocalAction.FOOD_CREATE, 1)
    }

    @Test
    public fun fqdb03AddFormOnePreservesTheSuppliedInput() {
        assertAccepted(LocalAction.FOOD_ADD, 1)
    }

    @Test
    public fun fqdb03AddFormTwoPreservesTheSuppliedInput() {
        assertAccepted(LocalAction.FOOD_ADD, 2)
    }

    @Test
    public fun fqdb03AddFormThreePreservesTheSuppliedInputWithoutDowngrade() {
        assertAccepted(LocalAction.FOOD_ADD, 3)
    }

    @Test
    public fun fqdb04CreateFormMinusOneRefusesWithoutChangingTheSuppliedInput() {
        assertRefused(LocalAction.FOOD_CREATE, -1)
    }

    @Test
    public fun fqdb04CreateFormZeroRefusesWithoutChangingTheSuppliedInput() {
        assertRefused(LocalAction.FOOD_CREATE, 0)
    }

    @Test
    public fun fqdb04CreateFormTwoRefusesWithoutChangingTheSuppliedInput() {
        assertRefused(LocalAction.FOOD_CREATE, 2)
    }

    @Test
    public fun fqdb04CreateFormThreeRefusesWithoutChangingTheSuppliedInput() {
        assertRefused(LocalAction.FOOD_CREATE, 3)
    }

    @Test
    public fun fqdb04CreateFormFourRefusesWithoutChangingTheSuppliedInput() {
        assertRefused(LocalAction.FOOD_CREATE, 4)
    }

    @Test
    public fun fqdb04AddFormMinusOneRefusesWithoutChangingTheSuppliedInput() {
        assertRefused(LocalAction.FOOD_ADD, -1)
    }

    @Test
    public fun fqdb04AddFormZeroRefusesWithoutChangingTheSuppliedInput() {
        assertRefused(LocalAction.FOOD_ADD, 0)
    }

    @Test
    public fun fqdb04AddFormFourRefusesWithoutChangingTheSuppliedInput() {
        assertRefused(LocalAction.FOOD_ADD, 4)
    }

    @Test
    public fun fqdb04EveryOtherPublicActionRefusesFormsOneTwoAndThree() {
        val otherActions = LocalAction.entries.filterNot { it == LocalAction.FOOD_CREATE || it == LocalAction.FOOD_ADD }
        assertTrue(otherActions.isNotEmpty())
        for (action in otherActions) for (version in 1..3) assertRefused(action, version)
    }

    private fun assertAccepted(action: LocalAction, version: Int) {
        val supplied = containers(action)
        // These independent snapshots precede construction. Structural equality
        // is required, not reuse of a particular list/LocalInputLine instance.
        val valuesBefore = supplied.values.toList()
        val linesBefore = snapshot(supplied.lines)
        val input = LocalFoodPreviewInput(action, version, supplied.values, supplied.lines)
        assertEquals(action, input.action)
        assertEquals(version, input.formVersion)
        assertEquals(valuesBefore, input.values)
        assertEquals(linesBefore, snapshot(input.lines))
        assertEquals(valuesBefore, supplied.values)
        assertEquals(linesBefore, snapshot(supplied.lines))
    }

    private fun assertRefused(action: LocalAction, version: Int) {
        val supplied = containers(action)
        val valuesBefore = supplied.values.toList()
        val linesBefore = snapshot(supplied.lines)
        assertFailsWith<IllegalArgumentException>("$action form$version is outside the frozen accepted set") {
            LocalFoodPreviewInput(action, version, supplied.values, supplied.lines)
        }
        assertEquals(valuesBefore, supplied.values)
        assertEquals(linesBefore, snapshot(supplied.lines))
    }

    private data class SuppliedContainers(val values: MutableList<LocalScalarInput>, val lines: MutableList<LocalInputLine>)
    private data class LineSnapshot(val lineId: DomainId, val values: List<LocalScalarInput>)

    private fun snapshot(lines: List<LocalInputLine>): List<LineSnapshot> = lines.map { LineSnapshot(it.lineId, it.values.toList()) }

    private fun containers(action: LocalAction): SuppliedContainers {
        val values = mutableListOf(
            LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(DomainId("71000000-0000-4000-8000-000000000901"))),
            LocalScalarInput(LocalField.EXPECTED_CATALOG_REVISION, LocalScalarValue.Integer(17)),
        )
        if (action == LocalAction.FOOD_CREATE) values += listOf(
            LocalScalarInput(LocalField.ACTIVITY, LocalScalarValue.Choice("RESTAURANT")),
            LocalScalarInput(LocalField.CHANNEL, LocalScalarValue.Choice("DINE_IN")),
            LocalScalarInput(LocalField.SERVICE_REFERENCE, LocalScalarValue.Text("Supplied preview table")),
            LocalScalarInput(LocalField.SEAT_NOTE, LocalScalarValue.Text("Supplied check note")),
        ) else values += LocalScalarInput(LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION, LocalScalarValue.Integer(29))
        return SuppliedContainers(values, mutableListOf(
            line(DomainId("71000000-0000-4000-8000-000000000102"), "Second supplied selection", "Seat 2"),
            line(DomainId("71000000-0000-4000-8000-000000000101"), "First supplied selection", "Seat 1"),
        ))
    }

    private fun line(id: DomainId, itemNote: String, seatNote: String): LocalInputLine = LocalInputLine(id, listOf(
        LocalScalarInput(LocalField.RECIPE_ID, LocalScalarValue.Identifier(DomainId("71000000-0000-4000-8000-000000000301"))),
        LocalScalarInput(LocalField.EXPECTED_FOOD_RECIPE_REVISION, LocalScalarValue.Integer(11)),
        LocalScalarInput(LocalField.SKU_ID, LocalScalarValue.Identifier(DomainId("71000000-0000-4000-8000-000000000201"))),
        LocalScalarInput(LocalField.CHOICE_ID, LocalScalarValue.Identifier(DomainId("71000000-0000-4000-8000-000000000303"))),
        LocalScalarInput(LocalField.UNIT, LocalScalarValue.Identifier(DomainId("71000000-0000-4000-8000-000000000501"))),
        LocalScalarInput(LocalField.QUANTITY_SCALE, LocalScalarValue.Integer(0)),
        LocalScalarInput(LocalField.QUANTITY, LocalScalarValue.Decimal("1")),
        LocalScalarInput(LocalField.MODIFIER_IDS, LocalScalarValue.Text("")),
        LocalScalarInput(LocalField.NAME_ENGLISH, LocalScalarValue.Text("Supplied choice")),
        LocalScalarInput(LocalField.NAME_ARABIC, LocalScalarValue.Text("")),
        LocalScalarInput(LocalField.ITEM_NOTE, LocalScalarValue.Text(itemNote)),
        LocalScalarInput(LocalField.LINE_SEAT_NOTE, LocalScalarValue.Text(seatNote)),
        LocalScalarInput(LocalField.FOOD_SERVING_COUNT, LocalScalarValue.Integer(1)),
    ))
}
