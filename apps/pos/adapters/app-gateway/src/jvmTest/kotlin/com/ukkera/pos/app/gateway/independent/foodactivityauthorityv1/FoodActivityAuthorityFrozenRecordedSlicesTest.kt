package com.ukkera.pos.app.gateway.independent.foodactivityauthorityv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Controlled L1 renderer inputs from food-activity-authority-v1 FAA-061.
 * Constructed Recorded values go only to renderRecorded, never to a Master,
 * gateway lookup/dispatch or journal outcome writer. They prove no committed
 * original, native authority, current source, persistence or RPC result.
 */
public class FoodActivityAuthorityFrozenRecordedSlicesTest {
    @Test
    public fun faa061ValidSuccessKeepsDistinctCodesSavedOrderAndFixedRevisionSeven(): Unit = runTest {
        val fixture = FoodActivityGatewayFixture()
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(FoodActivityFrozenInputs.intent(2)))
        val original = assertNotNull(fixture.journal.entry)
        val rendered = renderRecorded(original, rendererInput(SUCCESS))
        val details = assertNotNull(rendered.food)
        assertEquals("food.order.draft_created", details.code)
        assertEquals(FoodActivityFrozenInputs.orderId, details.orderId)
        assertEquals(Revision(7), details.orderStorageRevision)
        assertTrue(details.portions.isEmpty())
        assertEquals(LocalFoodUiQuery.Check(FoodActivityFrozenInputs.orderId), details.followUp)
        assertEquals(FoodActivityFrozenInputs.operationId, rendered.operationId)
        assertEquals(original, fixture.journal.entry)
        fixture.assertNoBusinessDispatch()
        assertTrue(fixture.endpoint.lookups.isEmpty())
    }

    @Test
    public fun faa061EnvelopeConfusedWithBusinessOffersNoFollowUp(): Unit = runTest {
        assertNoFollowUp(SUCCESS, "food.order.draft_created")
    }

    @Test
    public fun faa061BusinessConfusedWithEnvelopeOffersNoFollowUp(): Unit = runTest {
        assertNoFollowUp(SUCCESS.replace("\"code\":\"food.order.draft_created\"", "\"code\":\"committed\""))
    }

    @Test
    public fun faa061WrongOrderOffersNoFollowUp(): Unit = runTest {
        assertNoFollowUp(SUCCESS.replace("f0000000-0000-4000-8000-000000000001", "f0000000-0000-4000-8000-000000000002"))
    }

    @Test
    public fun faa061MissingOrderOffersNoFollowUp(): Unit = runTest {
        assertNoFollowUp(SUCCESS.replace("\"orderId\":\"${FoodActivityFrozenInputs.orderId.value}\",", ""))
        assertNoFollowUp(SUCCESS.replace("\"orderId\":\"${FoodActivityFrozenInputs.orderId.value}\"", "\"orderId\":null"))
    }

    @Test
    public fun faa061MissingRevisionOffersNoFollowUp(): Unit = runTest {
        assertNoFollowUp(SUCCESS.replace("\"orderRevision\":\"7\",", ""))
        assertNoFollowUp(SUCCESS.replace("\"orderRevision\":\"7\"", "\"orderRevision\":null"))
    }

    @Test
    public fun faa061NonemptyPortionsOfferNoFollowUp(): Unit = runTest {
        val portion = "{\"portionId\":\"f1000000-0000-4000-8000-000000000001\",\"revision\":\"7\",\"status\":\"NOT_SENT\",\"issueId\":null}"
        assertNoFollowUp(SUCCESS.replace("\"portions\":[]", "\"portions\":[$portion]"))
    }

    @Test
    public fun faa061MissingTypedEmptyPortionsOffersNoFollowUp(): Unit = runTest {
        assertNoFollowUp(SUCCESS.replace(",\"portions\":[]", ""))
    }

    @Test
    public fun faa061MalformedDetailsOfferNoFollowUp(): Unit = runTest {
        assertNoFollowUp("null")
        assertNoFollowUp("[]")
    }

    private suspend fun assertNoFollowUp(body: String, envelopeCode: String = "committed") {
        val fixture = FoodActivityGatewayFixture()
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(FoodActivityFrozenInputs.intent(2)))
        val original = assertNotNull(fixture.journal.entry)
        val rendered = renderRecorded(original, rendererInput(body, envelopeCode))
        assertNull(rendered.food)
        assertEquals(FoodActivityFrozenInputs.operationId, rendered.operationId)
        assertEquals(original, fixture.journal.entry)
        assertEquals(2, original.formVersion)
        assertEquals(FoodActivityFrozenInputs.payloadLiteral(2, LocalSalesActivity.RESTAURANT),
            original.request().payload.copyBytes().decodeToString())
        assertEquals(1, fixture.journal.stageCalls)
        fixture.assertNoBusinessDispatch()
        assertTrue(fixture.endpoint.lookups.isEmpty())
    }

    private fun rendererInput(body: String, envelopeCode: String = "committed"): MutationReply.Recorded =
        MutationReply.Recorded(FoodActivityFrozenInputs.operationId, FoodActivityFrozenInputs.actorId, true,
            envelopeCode, StoredBytes(body.encodeToByteArray()), Revision(1), JournalSequence(23),
            UtcInstant.parseCanonical("2026-10-30T10:00:00Z"), replayed = false)

    private companion object {
        const val SUCCESS: String = "{\"code\":\"food.order.draft_created\",\"orderId\":\"f0000000-0000-4000-8000-000000000001\",\"orderRevision\":\"7\",\"portions\":[]}"
    }
}
