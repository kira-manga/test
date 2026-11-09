package com.ukkera.pos.app.gateway.independent.foodquotedaddv2

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.sales.store.LocalFoodCodecV1
import com.ukkera.pos.sales.store.LocalFoodStockCodecV2
import com.ukkera.pos.sales.store.LocalFoodStockRequestV2
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Public form/profile pairing and exact supplied-original preservation only.
 * BINDING-001 is supplied directly to the saved-request constructor; its explicit
 * anchors are never changed to UI singleton defaults. These are not authentic
 * form1/form2 originals, native journal restart or qualified resend acceptance.
 */
public class FoodQuotedAddFrozenSavedIntentTest {
    @Test
    public fun fqadd2002SuppliedFormThreeQuotedSavedRequestKeepsTheExactBoundSelections() {
        val saved = FoodQuotedAddFrozenSelectionInputs.savedFirstEntry()
        saved.validate(saved.owner)
        assertEquals(3, saved.formVersion)
        assertFoodQuotedAddBoundedRequest(saved.request(), FoodQuotedAddFrozenSelectionInputs.firstSelections,
            FoodQuotedAddFrozenSelectionInputs.FIRST_CANONICAL_ARRAY)
    }

    @Test
    public fun fqadd2002QuotedSavedPayloadCannotBePairedWithFormOneMetadata() {
        val saved = FoodQuotedAddFrozenSelectionInputs.savedFirstEntry()
        val corrupted = saved.copy(formVersion = 1)
        assertFailsWith<IllegalArgumentException> { corrupted.validate(saved.owner) }
    }

    @Test
    public fun fqadd2002QuotedSavedPayloadCannotBePairedWithFormTwoMetadata() {
        val saved = FoodQuotedAddFrozenSelectionInputs.savedFirstEntry()
        val corrupted = saved.copy(formVersion = 2)
        assertFailsWith<IllegalArgumentException> { corrupted.validate(saved.owner) }
    }

    @Test
    public fun fqadd2002GuardedSavedPayloadCannotBeRelabelledFormThree() {
        val guarded = LocalFoodStockCodecV2.command(FoodQuotedAddBoundedUiInputs.firstOperation,
            LocalFoodStockRequestV2.ActivityProtectedAdd(FoodQuotedAddFrozenSelectionInputs.firstAdd()))
        val saved = FoodQuotedAddFrozenSelectionInputs.savedFirstEntry(formVersion = 2, request = guarded)
        val corrupted = saved.copy(formVersion = 3)
        assertFailsWith<IllegalArgumentException> { corrupted.validate(saved.owner) }
    }

    @Test
    public fun fqadd2002PreparationSavedPayloadCannotBeRelabelledFormThree() {
        val preparation = LocalFoodStockCodecV2.command(FoodQuotedAddBoundedUiInputs.firstOperation,
            LocalFoodStockRequestV2.Preparation(FoodQuotedAddFrozenSelectionInputs.firstAdd()))
        val saved = FoodQuotedAddFrozenSelectionInputs.savedFirstEntry(formVersion = 1, request = preparation)
        val corrupted = saved.copy(formVersion = 3)
        assertFailsWith<IllegalArgumentException> { corrupted.validate(saved.owner) }
    }

    @Test
    public fun fqadd2002LegacyFoodSavedPayloadCannotBeRelabelledFormThree() {
        val legacy = LocalFoodCodecV1.command(FoodQuotedAddBoundedUiInputs.firstOperation,
            FoodQuotedAddFrozenSelectionInputs.firstAdd())
        val saved = FoodQuotedAddFrozenSelectionInputs.savedFirstEntry(formVersion = 1, request = legacy)
        val corrupted = saved.copy(formVersion = 3)
        assertFailsWith<IllegalArgumentException> { corrupted.validate(saved.owner) }
    }

    @Test
    public fun fqadd2003SuppliedQuotedOriginalSurvivesTheJournalDtoRoundTripExactly() {
        val saved = FoodQuotedAddFrozenSelectionInputs.savedFirstEntry()
        val restored = journalRoundTrip(saved)
        assertRetained(saved, restored)
    }

    @Test
    public fun fqadd2003DeferredSavedContinuationDoesNotRediscoverAnAdvertisedProfile(): Unit = runTest {
        val saved = FoodQuotedAddFrozenSelectionInputs.savedFirstEntry()
        val fixture = FoodQuotedAddFrozenGatewayFixture(restored = journalRoundTrip(saved))
        fixture.endpoint.readsForbidden = true
        fixture.endpoint.addHintAvailable = false
        assertDeferredReconstruction(fixture, saved)
    }

    @Test
    public fun fqadd2003DeferredSavedContinuationKeepsOriginalBytesWhenSupportIsRemoved(): Unit = runTest {
        val saved = FoodQuotedAddFrozenSelectionInputs.savedFirstEntry()
        val fixture = FoodQuotedAddFrozenGatewayFixture(
            FoodQuotedAddBoundedUiInputs.installation(normalizedFood = false, quotationRead = false),
            restored = journalRoundTrip(saved))
        fixture.endpoint.readsForbidden = true
        fixture.endpoint.addHintAvailable = false
        assertDeferredReconstruction(fixture, saved)
    }

    @Test
    public fun fqadd2003ExistingQuotedOperationIsNotRestagedAsFormTwo(): Unit = runTest {
        val saved = FoodQuotedAddFrozenSelectionInputs.savedFirstEntry()
        val fixture = FoodQuotedAddFrozenGatewayFixture(restored = journalRoundTrip(saved))
        fixture.endpoint.readsForbidden = true
        val result = assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(
            FoodQuotedAddFrozenSelectionInputs.savedFirstInputIntent(formVersion = 2)))
        assertEquals(saved.operationId, result.operationId)
        assertRetained(saved, assertNotNull(fixture.journal.entry))
        assertTrue(fixture.endpoint.lookups.isEmpty())
        assertTrue(fixture.endpoint.reads.isEmpty())
        assertEquals(0, fixture.journal.stageCalls)
        fixture.assertNoMutationDispatch()
    }

    private suspend fun assertDeferredReconstruction(fixture: FoodQuotedAddFrozenGatewayFixture,
        saved: StoredClientIntentV1) {
        // The supplied endpoint never certifies original absence or a recorded
        // result. Unresolved reconstruction is not a resend or settlement pass.
        val restage = assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(
            FoodQuotedAddFrozenSelectionInputs.savedFirstInputIntent()))
        assertEquals(saved.operationId, restage.operationId)
        val lookup = assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(saved.operationId))
        val submit = assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(saved.operationId))
        assertEquals(saved.operationId, lookup.operationId)
        assertEquals(saved.operationId, submit.operationId)
        assertEquals(listOf(saved.identity(), saved.identity(), saved.identity()), fixture.endpoint.lookups)
        assertTrue(fixture.endpoint.reads.isEmpty())
        assertRetained(saved, assertNotNull(fixture.journal.entry))
        assertEquals(0, fixture.journal.stageCalls)
        fixture.assertNoMutationDispatch()
    }

    private fun journalRoundTrip(saved: StoredClientIntentV1): StoredClientIntentV1 =
        journalJson.decodeFromString(StoredClientIntentV1.serializer(),
            journalJson.encodeToString(StoredClientIntentV1.serializer(), saved)).also { it.validate(saved.owner) }

    private fun assertRetained(original: StoredClientIntentV1, restored: StoredClientIntentV1) {
        assertEquals(original, restored)
        assertEquals(original.owner.actorId, restored.owner.actorId)
        assertEquals(original.operationId, restored.operationId)
        assertEquals(original.formVersion, restored.formVersion)
        assertEquals(original.inputDigest, restored.inputDigest)
        assertEquals(original.semanticSha256, restored.semanticSha256)
        assertEquals(original.request().command, restored.request().command)
        assertEquals(original.request().payload, restored.request().payload)
        assertEquals(original.identity(), restored.identity())
        assertFoodQuotedAddBoundedRequest(restored.request(), FoodQuotedAddFrozenSelectionInputs.firstSelections,
            FoodQuotedAddFrozenSelectionInputs.FIRST_CANONICAL_ARRAY)
    }
}
