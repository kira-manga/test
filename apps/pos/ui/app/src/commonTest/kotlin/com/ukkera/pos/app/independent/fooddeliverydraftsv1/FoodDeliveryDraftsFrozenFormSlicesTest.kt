package com.ukkera.pos.app.independent.fooddeliverydraftsv1

import com.ukkera.pos.app.*
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Bounded common representation/validation slices of food-delivery-drafts-v1.
 * Oracles: frozen cases FDP-D03/D19/D20 and exact fixtures.json inputs; DD Metadata
 * and public input / Durable and current-read contract. These are not wire,
 * production Master, persistence, RPC, installed UI or whole-case acceptance.
 */
class FoodDeliveryDraftsFrozenFormSlicesTest {
    @Test
    fun fdpD03MissingEmptyAndSpaceOnlyScalarValuesRefuseValidation() {
        FdpUiFrozenInputs.metadataVariants.filter { it.caseId.takeLast(2).toInt() in 3..11 }.forEach { input ->
            assertNotNull(fdpUiDraft(input).foodDeliveryInputIssue(), input.caseId)
        }
    }

    @Test
    fun fdpD03ExactUtf16BoundariesAndLiteralUnicodeKeepFrozenOutcomes() {
        FdpUiFrozenInputs.metadataVariants.filter { it.caseId.takeLast(2).toInt() in 12..28 }.forEach { input ->
            input.boundedField?.let { field ->
                assertEquals(input.utf16CodeUnits, input.texts.getValue(field).length, input.caseId)
            }
            val draft = fdpUiDraft(input)
            if (input.accepts) {
                assertNull(draft.foodDeliveryInputIssue(), input.caseId)
                val typed = input.typedDetails()
                assertEquals(input.texts, typed.texts(), input.caseId)
                draft.foodDraftFields().filter { it.field in FdpUiFrozenInputs.deliveryFields }.forEach { field ->
                    val parsed = assertNotNull(parseLocalInput(field, draft.values.getValue(field.field))
                        as? LocalParsedValue.Value, input.caseId)
                    assertEquals(LocalScalarValue.Text(input.texts.getValue(field.field)), parsed.value, input.caseId)
                }
            } else {
                assertNotNull(draft.foodDeliveryInputIssue(), input.caseId)
                assertFails(input.caseId) { input.typedDetails() }
            }
        }
    }

    @Test
    fun fdpD03ExactLoneSurrogateAttemptsRefuseEveryField() {
        FdpUiFrozenInputs.metadataVariants.filter { it.exactRawJsonStringToken != null }.forEach { input ->
            // The frozen raw token denotes an attempted code unit, never a valid stored value.
            val field = assertNotNull(input.rawTokenField)
            val expectedUnit = when (input.exactRawJsonStringToken) {
                "\"\\ud800\"" -> 0xD800
                "\"\\udc00\"" -> 0xDC00
                else -> error("No frozen lone-surrogate token")
            }
            assertEquals(listOf(expectedUnit), input.texts.getValue(field).map { it.code }, input.caseId)
            assertNotNull(fdpUiDraft(input).foodDeliveryInputIssue(), input.caseId)
            assertFails(input.caseId) { input.typedDetails() }
        }
    }

    @Test
    fun fdpD03AllThirtyThreeAsciiControlsRefuseAllThreeFields() {
        FdpUiFrozenInputs.asciiControlVariants().forEach { input ->
            assertNotNull(fdpUiDraft(input).foodDeliveryInputIssue(), input.caseId)
            assertFails(input.caseId) { input.typedDetails() }
        }
    }

    @Test
    fun fdpD19DeliveryEffectiveFieldsAreRequiredWithNoMandatoryExtraReference() {
        val draft = localFormDraft(fdpUiDraftForm(reference = null))
        val effective = draft.foodDraftFields()
        val metadata = effective.filter { it.field in FdpUiFrozenInputs.deliveryFields }
        assertEquals(FdpUiFrozenInputs.deliveryFields, metadata.map { it.field })
        assertTrue(metadata.all { it.required })
        assertTrue(effective.single { it.field == LocalField.CHANNEL }.choices.any { it.code == "DELIVERY" })
        assertEquals(false, effective.single { it.field == LocalField.SERVICE_REFERENCE }.required)
        assertNull(draft.foodDeliveryInputIssue())
        assertEquals(FdpUiFrozenInputs.baseTexts, draft.values.filterKeys {
            it in FdpUiFrozenInputs.deliveryFields
        }.mapValues { it.value.text })
        assertEquals("", draft.values.getValue(LocalField.SEAT_NOTE).text)
    }

    @Test
    fun fdpD19NonDeliveryFieldsOmitAbsentMetadataAndRefuseInapplicableText() {
        listOf("TAKEAWAY" to FdpUiFrozenInputs.pickupReference, "DINE_IN" to FdpUiFrozenInputs.tableReference)
            .forEachIndexed { index, (channel, reference) ->
                val absent = localFormDraft(fdpUiDraftForm(emptyMap(), channel, reference))
                val effective = absent.foodDraftFields()
                assertTrue(effective.none { it.field in FdpUiFrozenInputs.deliveryFields }, "FDP-D19-V0${index + 1}")
                assertTrue(effective.single { it.field == LocalField.SERVICE_REFERENCE }.required)
                assertNull(absent.foodDeliveryInputIssue())
                assertEquals(reference, absent.values.getValue(LocalField.SERVICE_REFERENCE).text)
                val present = localFormDraft(fdpUiDraftForm(FdpUiFrozenInputs.baseTexts, channel, reference))
                assertNotNull(present.foodDeliveryInputIssue(), "FDP-D19-V0${index + 3}")
            }
    }

    @Test
    fun fdpD19ExplicitChannelCleanupRetiresExactlyTheThreeNewFormValues() {
        listOf("TAKEAWAY", "DINE_IN").forEachIndexed { index, channel ->
            val original = localFormDraft(fdpUiDraftForm())
            val changed = original.copy(values = original.values +
                (LocalField.CHANNEL to LocalDraftValue(selected = LocalScalarValue.Choice(channel))))
                .withFoodDeliveryChannelCleanup()
            FdpUiFrozenInputs.deliveryFields.forEach { field ->
                assertEquals(LocalDraftValue(), changed.values.getValue(field), "FDP-D19-V0${index + 5}")
                assertEquals(FdpUiFrozenInputs.baseTexts.getValue(field), original.values.getValue(field).text)
            }
            assertTrue(changed.foodDraftFields().none { it.field in FdpUiFrozenInputs.deliveryFields })
            assertNull(changed.foodDeliveryInputIssue())
            assertEquals(FdpUiFrozenInputs.reference, changed.values.getValue(LocalField.SERVICE_REFERENCE).text)
            assertEquals("", changed.values.getValue(LocalField.SEAT_NOTE).text)
        }
    }

    @Test
    fun fdpD20GenericPendingCardsExcludeIntakeAndPreserveExplicitOriginalReview() {
        val summary = fdpUiSummary(FdpUiFrozenInputs.baseTexts)
        val original = LocalPendingOperation(FdpUiFrozenInputs.createOperation, LocalAction.FOOD_CREATE_DRAFT,
            fdpUiText("Stored draft request"), summary, submitAllowed = true)
        repeat(2) {
            assertTrue(original.listSummary().isEmpty())
            assertEquals(summary, original.summary)
            assertEquals(FdpUiFrozenInputs.createOperation, original.operationId)
            assertEquals(LocalPendingPhase.STAGED, original.phase)
            assertTrue(original.submitAllowed)
        }
        assertEquals(FdpUiFrozenInputs.baseTexts.values.toList(), original.summary.map { it.value.english })
        assertEquals(FdpUiFrozenInputs.baseTexts.values.toList(), original.summary.map { it.value.arabic })
    }

    @Test
    fun fdpD20ExactCurrentCheckCoordinatesHaveNoMissingOrMismatchedTypedDefault() {
        val observation = fdpUiDeliveryObservation()
        val current = fdpUiBlockedCheck(observation)
        assertEquals(FdpUiFrozenInputs.restaurantOrder, current.check.orderId)
        assertEquals(Revision(30), current.check.orderStorageRevision)
        assertEquals(current.check.orderId, assertNotNull(current.delivery).orderId)
        assertEquals(current.check.orderStorageRevision, current.delivery?.orderStorageRevision)
        assertEquals(FdpUiFrozenInputs.baseTexts, current.delivery?.details?.texts())
        // These constructor gates cannot qualify decoding or an actual protected read failure.
        assertFails("FDP-D20-V02") { fdpUiBlockedCheck(null) }
        assertFails("FDP-D20-V04") {
            fdpUiBlockedCheck(observation.copy(orderId = FdpUiFrozenInputs.foreignOrder))
        }
        assertFails("FDP-D20-V05") {
            // 20 is the frozen F_EMPTY_EXISTING revision, not an inferred revision increment.
            fdpUiBlockedCheck(observation.copy(orderStorageRevision = Revision(20)))
        }
        assertFails("FDP-D20-V06") { fdpUiBlockedCheck(observation, "TAKEAWAY") }
        assertFails("FDP-D20-V06") { fdpUiBlockedCheck(observation, "DINE_IN") }
        assertNull(fdpUiBlockedCheck(null, "TAKEAWAY").delivery)
        assertNull(fdpUiBlockedCheck(null, "DINE_IN").delivery)
    }
}

private fun FdpUiMetadataVariant.typedDetails() = LocalFoodUiDeliveryDetails(
    texts.getValue(LocalField.FOOD_DELIVERY_RECIPIENT), texts.getValue(LocalField.FOOD_DELIVERY_CONTACT),
    texts.getValue(LocalField.FOOD_DELIVERY_ADDRESS))

internal fun LocalFoodUiDeliveryDetails.texts(): Map<LocalField, String> = mapOf(
    LocalField.FOOD_DELIVERY_RECIPIENT to recipient,
    LocalField.FOOD_DELIVERY_CONTACT to contact,
    LocalField.FOOD_DELIVERY_ADDRESS to address,
)
