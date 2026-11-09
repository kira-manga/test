package com.ukkera.pos.app.gateway.independent.foodquotedaddv2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.LocalFoodPortionRevisionV1
import com.ukkera.pos.sales.store.LocalFoodResultV1
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FQADD2-004 typed-result/input-order and FQADD2-018 preparation-default slices,
 * using BINDING-001. A constructed Recorded input is not Master publication,
 * original-result recovery or settlement. The supplied order revision below is
 * renderer metadata, never an independent numerical draft/Add/native oracle.
 */
public class FoodQuotedAddFrozenRecordedResultTest {
    private val suppliedRendererRevision: Revision = Revision(38)

    @Test
    public fun fqadd2004SuppliedTypedSuccessRetainsBoundOrderAndPortionInputOrder() {
        val saved = FoodQuotedAddFrozenSelectionInputs.savedFirstEntry()
        val rendered = renderRecorded(saved, reply(success()))
        assertEquals(saved.operationId, rendered.operationId)
        assertEquals(LocalRecordedDecision.APPLIED, rendered.decision)
        val detail = assertNotNull(rendered.food)
        assertEquals("food.order.added", detail.code)
        assertEquals(FoodQuotedAddBoundedUiInputs.selectorOrder, detail.orderId)
        assertEquals(suppliedRendererRevision, detail.orderStorageRevision)
        assertEquals(listOf(FoodQuotedAddFrozenSelectionInputs.firstPortion,
            FoodQuotedAddFrozenSelectionInputs.secondPortion), detail.portions.map { it.portionId })
        assertTrue(detail.portions.all { it.preparationDomainRevision == Revision(0) &&
            it.preparation == LocalFoodPreparation.NOT_SENT && it.originalIssueId == null })
        assertEquals(LocalFoodUiQuery.Check(FoodQuotedAddBoundedUiInputs.selectorOrder), detail.followUp)
    }

    @Test
    public fun fqadd2004ReorderedTypedPortionsOfferNoFoodFollowUp() {
        val result = success()
        assertNoFoodDetails(result.copy(portions = result.portions.reversed()))
    }

    @Test
    public fun fqadd2004MissingTypedPortionOffersNoFoodFollowUp() {
        val result = success()
        assertNoFoodDetails(result.copy(portions = result.portions.dropLast(1)))
    }

    @Test
    public fun fqadd2004ForeignTypedPortionOffersNoFoodFollowUp() {
        val result = success()
        assertNoFoodDetails(result.copy(portions = listOf(result.portions.first(),
            result.portions.last().copy(portionId = FoodQuotedAddFrozenSelectionInputs.laterPortion))))
    }

    @Test
    public fun fqadd2004DuplicatedTypedPortionOffersNoFoodFollowUp() {
        val result = success()
        assertNoFoodDetails(result.copy(portions = listOf(result.portions.first(), result.portions.first())))
    }

    @Test
    public fun fqadd2004ForeignTypedOrderOffersNoFoodFollowUp() {
        assertNoFoodDetails(success().copy(orderId = FoodQuotedAddFrozenSelectionInputs.laterPortion))
    }

    @Test
    public fun fqadd2004MissingTypedOrderOffersNoFoodFollowUp() {
        assertNoFoodDetails(success().copy(orderId = null))
    }

    @Test
    public fun fqadd2004MissingRecordedOrderRevisionOffersNoFoodFollowUp() {
        assertNoFoodDetails(success().copy(orderRevision = null))
    }

    @Test
    public fun fqadd2004CommittedEnvelopeCodeIsNotTheTypedAddBusinessCode() {
        assertNoFoodDetails(success().copy(code = "committed"))
    }

    @Test
    public fun fqadd2004TypedAddCodeCannotReplaceTheCommittedEnvelopeCode() {
        assertNoFoodDetails(success(), envelopeCode = "food.order.added")
    }

    @Test
    public fun fqadd2004NonNotSentTypedPreparationOffersNoFoodFollowUp() {
        val result = success()
        assertNoFoodDetails(result.copy(portions = result.portions.map { it.copy(status = FoodPreparationStatus.QUEUED) }))
    }

    @Test
    public fun fqadd2004NonnullTypedIssueOffersNoFoodFollowUp() {
        val result = success()
        assertNoFoodDetails(result.copy(portions = result.portions.map {
            it.copy(issueId = FoodQuotedAddFrozenSelectionInputs.laterPortion)
        }))
    }

    @Test
    public fun fqadd2018NonzeroInitialPreparationRevisionOffersNoFoodFollowUp() {
        val result = success()
        assertNoFoodDetails(result.copy(portions = result.portions.map { it.copy(revision = Revision(1)) }))
    }

    @Test
    public fun fqadd2004MatchingControlledRejectionCodesOfferNoFoodFollowUp() {
        // A supplied renderer token, not the spelling or existence of a genuine
        // business refusal. The public contract covers matching rejected codes.
        val token = "bounded.renderer.rejected"
        val saved = FoodQuotedAddFrozenSelectionInputs.savedFirstEntry()
        val rendered = renderRecorded(saved, reply(LocalFoodResultV1(token, null, null), committed = false,
            envelopeCode = token))
        assertEquals(LocalRecordedDecision.REJECTED, rendered.decision)
        val detail = assertNotNull(rendered.food)
        assertEquals(token, detail.code)
        assertNull(detail.orderId)
        assertNull(detail.orderStorageRevision)
        assertTrue(detail.portions.isEmpty())
        assertNull(detail.followUp)
    }

    @Test
    public fun fqadd2004MismatchedControlledRejectionCodesOfferNoFoodFollowUp() {
        assertNoFoodDetails(LocalFoodResultV1("food.order.added", null, null),
            committed = false, envelopeCode = "bounded.renderer.rejected")
    }

    private fun success(): LocalFoodResultV1 = LocalFoodResultV1("food.order.added",
        FoodQuotedAddBoundedUiInputs.selectorOrder, suppliedRendererRevision,
        FoodQuotedAddFrozenSelectionInputs.firstSelections.map {
            LocalFoodPortionRevisionV1(it.portionId, Revision(0), FoodPreparationStatus.NOT_SENT, null)
        })

    private fun reply(result: LocalFoodResultV1, committed: Boolean = true,
        envelopeCode: String = "committed"): MutationReply.Recorded = MutationReply.Recorded(
        FoodQuotedAddBoundedUiInputs.firstOperation, FoodQuotedAddBoundedUiInputs.owner,
        committed, envelopeCode, StoredBytes(foodQuotedAddUiJson.encodeToString(
            LocalFoodResultV1.serializer(), result).encodeToByteArray()),
        Revision(1), JournalSequence(1), FoodQuotedAddBoundedUiInputs.observedAt, false)

    private fun assertNoFoodDetails(result: LocalFoodResultV1, committed: Boolean = true,
        envelopeCode: String = "committed") {
        val saved = FoodQuotedAddFrozenSelectionInputs.savedFirstEntry()
        val rendered = renderRecorded(saved, reply(result, committed, envelopeCode))
        assertEquals(saved.operationId, rendered.operationId)
        assertEquals(if (committed) LocalRecordedDecision.APPLIED else LocalRecordedDecision.REJECTED, rendered.decision)
        assertNull(rendered.food)
        assertNull(rendered.result)
    }
}
