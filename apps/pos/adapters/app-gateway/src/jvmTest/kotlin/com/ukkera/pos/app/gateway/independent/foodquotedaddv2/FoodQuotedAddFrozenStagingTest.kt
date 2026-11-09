package com.ukkera.pos.app.gateway.independent.foodquotedaddv2

import com.ukkera.pos.app.*
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalFoodQuotationProfileReadCodecV1
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FQADD2-002 with the separately frozen BINDING-004 valid singleton. Controlled
 * reads and the client journal do not establish native draft/quotation admission,
 * business effects, protected Master authority, RPC or installed UI acceptance.
 */
public class FoodQuotedAddFrozenStagingTest {
    @Test
    public fun fqadd2002BoundSingleDefaultSelectionStagesOnlyTheQuotedFormThreeRequest(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        val intent = fixture.selectSingletonFormThree()
        val staged = assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val saved = assertNotNull(fixture.journal.entry)
        assertEquals(FoodQuotedAddBoundedUiInputs.firstOperation, staged.operation.operationId)
        assertEquals(LocalAction.FOOD_ADD.name, saved.action)
        assertEquals(3, saved.formVersion)
        assertNull(saved.recorded)
        assertFoodQuotedAddBoundedRequest(saved.request(), FoodQuotedAddFrozenSelectionInputs.singleDefaultSelections,
            FoodQuotedAddFrozenSelectionInputs.SINGLE_DEFAULT_CANONICAL_ARRAY)
        assertEquals(1, fixture.journal.stageCalls)
        assertEquals(2, fixture.profileReads())
        fixture.assertNoMutationDispatch()
    }

    @Test
    public fun fqadd2002QuotationReadRemovedAfterSelectionDoesNotStageAFallback(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        val intent = fixture.selectSingletonFormThree()
        fixture.assertNoFallback(fixture.gatewayWith(
            FoodQuotedAddBoundedUiInputs.installation(quotationRead = false)).stage(intent))
    }

    @Test
    public fun fqadd2002NormalizedSupportRemovedAfterSelectionDoesNotStageAFallback(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        val intent = fixture.selectSingletonFormThree()
        fixture.assertNoFallback(fixture.gatewayWith(
            FoodQuotedAddBoundedUiInputs.installation(normalizedFood = false)).stage(intent))
    }

    @Test
    public fun fqadd2002InstalledFalseAfterSelectionDoesNotStageAFallback(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        val intent = fixture.selectSingletonFormThree()
        fixture.endpoint.profileBytes = StoredBytes("{\"installed\":false}".encodeToByteArray())
        fixture.assertNoFallback(fixture.gateway.stage(intent))
    }

    @Test
    public fun fqadd2002UiAddHintRemovedAfterSelectionDoesNotStageAFallback(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        val intent = fixture.selectSingletonFormThree()
        fixture.endpoint.addHintAvailable = false
        fixture.assertNoFallback(fixture.gateway.stage(intent))
    }

    @Test
    public fun fqadd2002DiscoveryFailsAfterSelectionWithoutStagingAFallback(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        val intent = fixture.selectSingletonFormThree()
        fixture.endpoint.profileFailure = ReadFailure.STORAGE_UNCERTAIN
        fixture.assertNoFallback(fixture.gateway.stage(intent))
    }

    @Test
    public fun fqadd2002DiscoveryCorruptsAfterSelectionWithoutStagingAFallback(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        val intent = fixture.selectSingletonFormThree()
        fixture.endpoint.profileBytes = StoredBytes("{}".encodeToByteArray())
        fixture.assertNoFallback(fixture.gateway.stage(intent))
    }

    @Test
    public fun fqadd2002DiscoveryIoFailsAfterSelectionWithoutStagingAFallback(): Unit = runTest {
        val fixture = FoodQuotedAddFrozenGatewayFixture()
        val intent = fixture.selectSingletonFormThree()
        fixture.endpoint.profileException = IOException("Supplied discovery unavailable")
        fixture.assertNoFallback(fixture.gateway.stage(intent))
    }

    private suspend fun FoodQuotedAddFrozenGatewayFixture.selectSingletonFormThree(): LocalCommandIntent {
        val page = assertIs<LocalReadResult.Available>(gateway.read(FoodQuotedAddBoundedUiInputs.menuQuery())).page
        val form = page.forms.single { it.action == LocalAction.FOOD_ADD }
        assertEquals(3, form.version)
        assertNoStageOrDispatch()
        val intent = FoodQuotedAddFrozenSelectionInputs.singletonIntent(form.version)
        assertEquals(1, intent.lines.size)
        return intent
    }

    private fun FoodQuotedAddFrozenGatewayFixture.assertNoFallback(result: LocalStageResult) {
        // Transport uncertainty remains uncertainty; the freeze supplies no exact
        // refusal code. Neither result may be relabelled a new saved operation.
        assertTrue(result !is LocalStageResult.Staged)
        assertNoStageOrDispatch()
    }

    private fun FoodQuotedAddFrozenGatewayFixture.profileReads(): Int =
        endpoint.reads.count { it.query == LocalFoodQuotationProfileReadCodecV1.QUERY }
}
