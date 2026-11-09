package com.ukkera.pos.app.gateway.independent.foodguardedaddv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.LocalFoodStockCodecV2
import com.ukkera.pos.types.*
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Controlled L1 renderer-input bindings of frozen food-guarded-add-v1 GFA-009–011.
 * Recorded constructors feed renderRecorded only. No lookup, dispatch, journal
 * outcome write, Master commit, durable original, native read or RPC is supplied.
 * The synthetic revision 19 is solely the frozen renderer projection; it is not
 * independently observed authoritative commit evidence.
 */
public class FoodGuardedAddFrozenRecordedSlicesTest {
    @Test
    public fun gfa009DistinctCommittedAndAddedCodesOfferOnlyTheSavedOrderAtRevisionNineteen(): Unit {
        val original = FoodGuardedAddFrozenSavedProfile.GUARDED.stored()
        val fixture = FoodGuardedAddFrozenGatewayFixture(original)
        val rendered = renderRecorded(original, rendererInput(SUCCESS))
        val details = assertNotNull(rendered.food)
        assertEquals("food.order.added", details.code)
        assertEquals(FoodGuardedAddFrozenInputs.restaurantOrder, details.orderId)
        assertEquals(Revision(19), details.orderStorageRevision)
        assertNotEquals(Revision(7), details.orderStorageRevision)
        assertNotEquals(Revision(8), details.orderStorageRevision)
        assertEquals(listOf(FoodGuardedAddFrozenInputs.portionIds[0], FoodGuardedAddFrozenInputs.portionIds[1]),
            details.portions.map { it.portionId })
        assertTrue(details.portions.all { it.preparation == LocalFoodPreparation.NOT_SENT && it.originalIssueId == null })
        assertEquals(LocalFoodUiQuery.Check(FoodGuardedAddFrozenInputs.restaurantOrder), details.followUp)
        assertEquals(original.operationId, rendered.operationId)
        assertOriginalUnchanged(fixture, original)
    }

    @Test
    public fun gfa010MatchingRecordedRevisionConflictOffersZeroFollowUps(): Unit {
        val original = FoodGuardedAddFrozenSavedProfile.GUARDED.stored()
        val fixture = FoodGuardedAddFrozenGatewayFixture(original)
        val rendered = renderRecorded(original, rendererInput(REJECTION, "REVISION_CONFLICT", committed = false))
        assertNull(rendered.food?.followUp)
        assertEquals(original.operationId, rendered.operationId)
        assertOriginalUnchanged(fixture, original)
        // This sample does not prescribe which actual revision conflicts are recorded.
    }

    @Test
    public fun gfa011SwappedEnvelopeAndBusinessCodesOfferZeroFollowUps(): Unit {
        assertNoFollowUp(SUCCESS, "food.order.added")
        assertNoFollowUp(foodGuardedAddFrozenEdit(SUCCESS, "/code", JsonPrimitive("committed")))
    }

    @Test
    public fun gfa011ForeignOrderOffersZeroFollowUps(): Unit {
        assertNoFollowUp(foodGuardedAddFrozenEdit(SUCCESS, "/orderId", JsonPrimitive(FoodGuardedAddFrozenInputs.otherOrder.value)))
    }

    @Test
    public fun gfa011MissingAndNegativeRecordedRevisionsOfferZeroFollowUps(): Unit {
        assertNoFollowUp(foodGuardedAddFrozenEdit(SUCCESS, "/orderRevision", JsonNull))
        assertNoFollowUp(foodGuardedAddFrozenEdit(SUCCESS, "/orderRevision", JsonPrimitive("-1")))
    }

    @Test
    public fun gfa011ReorderedMissingAndForeignSelectedPortionsOfferZeroFollowUps(): Unit {
        val portions = Json.parseToJsonElement(SUCCESS).jsonObject.getValue("portions").jsonArray
        val reversed = listOf(FoodGuardedAddFrozenInputs.portionIds[1], FoodGuardedAddFrozenInputs.portionIds[0])
        val changed = JsonArray(portions.mapIndexed { index, portion -> JsonObject(portion.jsonObject +
            ("portionId" to JsonPrimitive(reversed[index].value))) })
        assertNoFollowUp(foodGuardedAddFrozenEdit(SUCCESS, "/portions", changed))
        assertNoFollowUp(foodGuardedAddFrozenEdit(SUCCESS, "/portions", JsonArray(listOf(portions[0]))))
        assertNoFollowUp(foodGuardedAddFrozenEdit(SUCCESS, "/portions/1/portionId",
            JsonPrimitive(FoodGuardedAddFrozenInputs.existingPortion.value)))
    }

    @Test
    public fun gfa011SentOrIssuedPortionOffersZeroFollowUps(): Unit {
        assertNoFollowUp(foodGuardedAddFrozenEdit(SUCCESS, "/portions/0/status", JsonPrimitive("SENT")))
        assertNoFollowUp(foodGuardedAddFrozenEdit(SUCCESS, "/portions/0/issueId",
            JsonPrimitive("98000000-0000-4000-8000-000000000001")))
    }

    @Test
    public fun gfa011MismatchedRecordedRejectionCodesOfferZeroFollowUps(): Unit {
        assertNoFollowUp(foodGuardedAddFrozenEdit(REJECTION, "/code", JsonPrimitive("OWNER_REQUIRED")),
            "REVISION_CONFLICT", committed = false)
    }

    @Test
    public fun gfa011MissingResultDetailsOfferZeroFollowUps(): Unit {
        assertNoFollowUp("null")
    }

    private fun assertNoFollowUp(body: String, envelopeCode: String = "committed", committed: Boolean = true) {
        val original = FoodGuardedAddFrozenSavedProfile.GUARDED.stored()
        val fixture = FoodGuardedAddFrozenGatewayFixture(original)
        val rendered = renderRecorded(original, rendererInput(body, envelopeCode, committed))
        assertNull(rendered.food?.followUp)
        assertEquals(original.operationId, rendered.operationId)
        assertOriginalUnchanged(fixture, original)
    }

    private fun assertOriginalUnchanged(fixture: FoodGuardedAddFrozenGatewayFixture, original: StoredClientIntentV1) {
        assertEquals(original, fixture.journal.entry)
        val retained = assertNotNull(fixture.journal.entry)
        assertEquals(FoodGuardedAddFrozenInputs.actor, retained.owner.actorId)
        assertEquals(FoodGuardedAddFrozenInputs.operation, retained.operationId)
        assertEquals(2, retained.formVersion)
        assertEquals(LocalFoodStockCodecV2.COMMAND, retained.request().command)
        assertEquals(FoodGuardedAddFrozenInputs.GUARDED, retained.request().payload.copyBytes().decodeToString())
        assertEquals(original.semanticSha256, retained.semanticSha256)
        assertEquals(original.identity(), retained.identity())
        assertEquals(0, fixture.journal.stageCalls)
        assertTrue(fixture.journal.reads.isEmpty())
        assertTrue(fixture.endpoint.reads.isEmpty())
        assertTrue(fixture.endpoint.lookups.isEmpty())
        fixture.assertNoMutationDispatch()
    }

    private fun rendererInput(body: String, envelopeCode: String = "committed", committed: Boolean = true): MutationReply.Recorded =
        MutationReply.Recorded(FoodGuardedAddFrozenInputs.operation, FoodGuardedAddFrozenInputs.actor, committed,
            envelopeCode, StoredBytes(body.encodeToByteArray()), Revision(1), JournalSequence(1),
            UtcInstant.parseCanonical("2026-10-30T22:25:37Z"), replayed = false)

    private companion object {
        // orderRevision/code/IDs/state/issue fields bind the frozen resultProjection.
        // Required per-portion revision fields are renderer DTO metadata only, not a Master oracle.
        const val SUCCESS: String = """{"code":"food.order.added","orderId":"87000000-0000-4000-8000-000000000001","orderRevision":"19","portions":[{"portionId":"8e000000-0000-4000-8000-000000000001","revision":"19","status":"NOT_SENT","issueId":null},{"portionId":"8e000000-0000-4000-8000-000000000002","revision":"19","status":"NOT_SENT","issueId":null}]}"""
        const val REJECTION: String = """{"code":"REVISION_CONFLICT","orderId":null,"orderRevision":null,"portions":[]}"""
    }
}
