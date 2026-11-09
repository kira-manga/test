package com.ukkera.pos.app.gateway.independent.foodquotedaddv2

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalActionForm
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalFoodQuotationProfileReadCodecV1
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * FQADD2-002 bounded gateway menu selection with controlled session/read DTOs.
 * No native installation, current Master authority, actual preview success,
 * durable Add, admitted RPC or UI lifecycle acceptance is established.
 */
public class FoodQuotedAddFrozenMenuTest {
    @Test
    public fun fqadd2002ExplicitInstalledNormalizedAndAvailableUiActionExposeFormThree(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        assertEquals(3, fixture.addForm().version)
        assertEquals(1, fixture.profileReads())
        fixture.assertNoStageOrDispatch()
    }

    @Test
    public fun fqadd2002ExplicitFalseKeepsTheStrictRoutedFormTwo(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        fixture.endpoint.profileBytes = StoredBytes("{\"installed\":false}".encodeToByteArray())
        assertEquals(2, fixture.addForm().version)
        assertEquals(1, fixture.profileReads())
        fixture.assertNoStageOrDispatch()
    }

    @Test
    public fun fqadd2002AbsentQuotationReadKeepsTheStrictRoutedFormTwo(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture(
            FoodQuotedAddBoundedUiInputs.installation(quotationRead = false))
        assertEquals(2, fixture.addForm().version)
        assertEquals(0, fixture.profileReads())
        fixture.assertNoStageOrDispatch()
    }

    @Test
    public fun fqadd2002PreviewReadRegistrationAloneCannotSelectFormThree(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture(
            FoodQuotedAddBoundedUiInputs.installation(quotationRead = false, previewRead = true))
        assertEquals(2, fixture.addForm().version)
        assertEquals(0, fixture.profileReads())
        fixture.assertNoStageOrDispatch()
    }

    @Test
    public fun fqadd2002OldFoodRegistrationAloneCannotExposeQuotedAdd(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture(
            FoodQuotedAddBoundedUiInputs.installation(normalizedFood = false, quotationRead = false))
        fixture.assertNoAddForm()
        assertEquals(0, fixture.profileReads())
        fixture.assertNoStageOrDispatch()
    }

    @Test
    public fun fqadd2002MissingNormalizedSupportCannotBorrowTheQuotationRead(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture(
            FoodQuotedAddBoundedUiInputs.installation(normalizedFood = false))
        fixture.assertNoAddForm()
        assertEquals(0, fixture.profileReads())
        fixture.assertNoStageOrDispatch()
    }

    @Test
    public fun fqadd2002UnavailableUiAddActionDoesNotBorrowInstallationSupport(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        fixture.endpoint.addHintAvailable = false
        fixture.assertNoAddForm()
        assertEquals(0, fixture.profileReads())
        fixture.assertNoStageOrDispatch()
    }

    @Test
    public fun fqadd2002FailedDiscoveryDoesNotExposeQuotedAdd(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        fixture.endpoint.profileFailure = ReadFailure.STORAGE_UNCERTAIN
        assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(FoodQuotedAddBoundedUiInputs.menuQuery()))
        assertEquals(1, fixture.profileReads())
        fixture.assertNoStageOrDispatch()
    }

    @Test
    public fun fqadd2002CorruptDiscoveryDoesNotExposeQuotedAdd(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        fixture.endpoint.profileBytes = StoredBytes("{}".encodeToByteArray())
        assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(FoodQuotedAddBoundedUiInputs.menuQuery()))
        assertEquals(1, fixture.profileReads())
        fixture.assertNoStageOrDispatch()
    }

    @Test
    public fun fqadd2002IoFailureDuringDiscoveryDoesNotExposeQuotedAdd(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        fixture.endpoint.profileException = IOException("Synthetic unavailable read")
        assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(FoodQuotedAddBoundedUiInputs.menuQuery()))
        assertEquals(1, fixture.profileReads())
        fixture.assertNoStageOrDispatch()
    }

    private suspend fun FoodQuotedAddFrozenGatewayFixture.addForm(): LocalActionForm {
        val result = assertIs<LocalReadResult.Available>(gateway.read(FoodQuotedAddBoundedUiInputs.menuQuery()))
        val form = result.page.forms.single()
        assertEquals(LocalAction.FOOD_ADD, form.action)
        return form
    }

    private suspend fun FoodQuotedAddFrozenGatewayFixture.assertNoAddForm() {
        val result = assertIs<LocalReadResult.Available>(gateway.read(FoodQuotedAddBoundedUiInputs.menuQuery()))
        assertTrue(result.page.forms.none { it.action == LocalAction.FOOD_ADD })
    }

    private fun FoodQuotedAddFrozenGatewayFixture.profileReads(): Int =
        endpoint.reads.count { it.query == LocalFoodQuotationProfileReadCodecV1.QUERY }
}
