package com.ukkera.pos.app.gateway.independent.fooddeliverydraftsv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.text.CharacterCodingException

/**
 * Exact frozen delivery assertions at controlled public-read/client-input seams.
 * No real Master authority, durable saved order, original result, money history,
 * native storage or admitted RPC exists here. Whole D/R cases remain NOT_RUN.
 */
class FoodDeliveryDraftsFrozenGatewaySlicesTest {
    private val f = FdpFrozenData

    @Test
    fun d01D02D19BaseAndArabicDraftStagingPreservesExactPrivateFieldsAndCanonicalRequest() = runTest {
        val arabic = f.objectAt("serviceIntents").getValue("deliveryArabic").jsonObject
        val arabicFields = listOf("recipient", "contact", "address").associateWith { arabic.getValue(it).jsonPrimitive.content }
        for (activity in listOf("RESTAURANT", "CAFE")) for (fields in listOf(f.baseFields(), arabicFields)) {
            val fixture = FdpGatewayFixture()
            val intent = FdpGatewayInputs.intent(activity, fields)
            val staged = assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
            val original = assertNotNull(fixture.journal.entry)
            assertEquals(intent.operationId, staged.operation.operationId)
            assertEquals(LocalAction.FOOD_CREATE_DRAFT, staged.operation.action)
            assertEquals(LocalPendingPhase.STAGED, staged.operation.phase)
            assertEquals(2, original.formVersion)
            assertEquals(fixture.journal.owner, original.owner)
            assertEquals(intent.operationId, original.operationId)
            assertEquals("sales.food.stock", original.request().command.name)
            assertEquals(2, original.request().command.version)
            assertEquals(FdpGatewayInputs.expectedDeliveryPayload(activity, fields), original.request().payload.copyBytes().decodeToString())
            val saved = assertNotNull(savedFoodDraft(original.request()))
            assertEquals(LocalSalesActivity.valueOf(activity), saved.activity)
            assertEquals(LocalSalesChannel.DELIVERY, saved.service.channel)
            assertEquals("DEL-SYN-030", saved.service.reference)
            assertEquals("", saved.service.seatNote)
            assertExactIntake(fields, assertNotNull(saved.deliveryDetails))
            assertPrivateSummaryKeepsIntake(fields, staged.operation.summary)
            original.validate(fixture.journal.owner)
            assertEquals(1, fixture.journal.stageCalls)
            assertTrue(fixture.endpoint.reads.isEmpty())
            assertTrue(fixture.endpoint.lookups.isEmpty())
            fixture.assertNoBusinessDispatch()
        }
    }

    @Test
    fun d04D19MissingReferenceStagesTheExactAllocatedNeutralLabelWithoutCopyingIntake() = runTest {
        val fixture = FdpGatewayFixture()
        val intent = FdpGatewayInputs.intent(reference = null)
        val staged = assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val original = assertNotNull(fixture.journal.entry)
        val saved = assertNotNull(savedFoodDraft(original.request()))
        assertEquals(f.neutralDraft, original.request().payload.copyBytes().decodeToString())
        assertEquals(FdpGatewayInputs.id("orderIds", "deliveryRestaurant").value, saved.service.reference)
        assertEquals(intent.operationId, original.operationId)
        assertTrue(staged.operation.summary.any { it.value.english == saved.orderId.value &&
            it.value.arabic == saved.orderId.value }, "Private review retains the exact neutral reference, without a label-text oracle")
        assertExactIntake(f.baseFields(), assertNotNull(saved.deliveryDetails))
        assertPrivateSummaryKeepsIntake(f.baseFields(), staged.operation.summary)
        assertEquals("", saved.service.seatNote)
        assertEquals(1, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.reads.isEmpty())
        assertTrue(fixture.endpoint.lookups.isEmpty())
        fixture.assertNoBusinessDispatch()
    }

    @Test
    fun d03All34FrozenMetadataFormAttemptsGateClientStagingAndPreserveValidText() = runTest {
        assertEquals(34, f.metadataVariants.size)
        var preIntentRefusals = 0
        for (variant in f.metadataVariants) {
            val fixture = FdpGatewayFixture()
            val caseId = f.caseId(variant)
            val fields = f.attemptedFields(variant)
            val rawToken = variant["exactRawJsonStringToken"]?.jsonPrimitive?.content
            if (rawToken != null) {
                // Exact V29–V34 input is refused by the real immutable intent
                // constructor before stage; no constructible replacement is supplied.
                assertFalse(f.validates(variant), caseId)
                val expectedCodeUnit = when (rawToken) {
                    "\"\\ud800\"" -> '\uD800'
                    "\"\\udc00\"" -> '\uDC00'
                    else -> error("Unspecified frozen raw Unicode token")
                }
                val attempted = fields.getValue(variant.getValue("replaceField").jsonPrimitive.content)
                assertEquals(1, attempted.length, caseId)
                assertEquals(expectedCodeUnit, attempted.single(), caseId)
                assertFailsWith<CharacterCodingException>(caseId) { FdpGatewayInputs.intent(fields = fields) }
                fixture.assertNothingStaged()
                preIntentRefusals++
            } else {
                val intent = FdpGatewayInputs.intent(fields = fields)
                val staged = fixture.gateway.stage(intent)
                if (f.validates(variant)) {
                    assertIs<LocalStageResult.Staged>(staged, caseId)
                    val original = assertNotNull(fixture.journal.entry, caseId)
                    assertEquals(FdpGatewayInputs.expectedDeliveryPayload("RESTAURANT", fields),
                        original.request().payload.copyBytes().decodeToString(), caseId)
                    assertExactIntake(fields, assertNotNull(assertNotNull(savedFoodDraft(original.request())).deliveryDetails), caseId)
                    assertEquals(1, fixture.journal.stageCalls, caseId)
                } else {
                    assertIs<LocalStageResult.NotStaged>(staged, caseId)
                    fixture.assertNothingStaged()
                }
            }
            assertTrue(fixture.endpoint.reads.isEmpty(), caseId)
            assertTrue(fixture.endpoint.lookups.isEmpty(), caseId)
            fixture.assertNoBusinessDispatch()
        }
        assertEquals(6, preIntentRefusals)
        // Form fields have no separate null-object representation. V01/V02 both
        // supply no fields here; the store codec separately attempts absent/null JSON.
        // V29–V34 qualify pre-intent refusal only, not a NotStaged reply or
        // actual controller/native/Master invalid-input qualification.
    }

    @Test
    fun d03All99FrozenAsciiControlFieldInputsRefuseBeforeAnyJournalStage() = runTest {
        val fields = f.objectAt("deliveryMetadataTechnicalRules").getValue("controlCrossProduct").jsonObject
            .getValue("fields").jsonArray.map { it.jsonPrimitive.content }
        var attempts = 0
        for (field in fields) for (codepoint in f.prohibitedCodepoints()) {
            val caseId = "FDP-D03-C-${field.uppercase()}-${codepoint.toString(16).uppercase().padStart(4, '0')}"
            val fixture = FdpGatewayFixture()
            val values = f.baseFields() + (field to "A${codepoint.toChar()}B")
            assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(FdpGatewayInputs.intent(fields = values)), caseId)
            fixture.assertNothingStaged()
            assertTrue(fixture.endpoint.reads.isEmpty(), caseId)
            assertTrue(fixture.endpoint.lookups.isEmpty(), caseId)
            attempts++
        }
        assertEquals(99, attempts)
    }

    @Test
    fun d19D21NonDeliveryAbsenceStagesExactCompatibleBytesAndInapplicableFieldsRefuse() = runTest {
        val compatible = f.objectAt("compatibility").getValue("canonicalNonDeliveryDraftPayloads").jsonObject
        for ((key, channel, reference) in listOf(Triple("takeaway", "TAKEAWAY", "PU-SYN-030"),
                Triple("dineIn", "DINE_IN", "TABLE-SYN-030"))) {
            val fixture = FdpGatewayFixture()
            val intent = FdpGatewayInputs.intent(fields = emptyMap(), channel = channel, reference = reference)
            assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
            val original = assertNotNull(fixture.journal.entry)
            val canonical = compatible.getValue(key).jsonObject.getValue("canonicalPayloadJson").jsonPrimitive.content
            assertEquals(canonical, original.request().payload.copyBytes().decodeToString())
            assertNull(assertNotNull(savedFoodDraft(original.request())).deliveryDetails)
            assertFalse("deliveryDetails" in Json.parseToJsonElement(canonical).jsonObject.getValue("request").jsonObject)
            assertEquals(1, fixture.journal.stageCalls)
            fixture.assertNoBusinessDispatch()

            val inapplicable = FdpGatewayFixture()
            assertIs<LocalStageResult.NotStaged>(inapplicable.gateway.stage(
                FdpGatewayInputs.intent(fields = f.baseFields(), channel = channel, reference = reference)))
            inapplicable.assertNothingStaged()
            assertTrue(inapplicable.endpoint.reads.isEmpty())
        }
        val old = FdpGatewayFixture()
        assertIs<LocalStageResult.NotStaged>(old.gateway.stage(FdpGatewayInputs.intent(formVersion = 1)))
        old.assertNothingStaged()
        assertTrue(old.endpoint.reads.isEmpty())
        // Channel editor switching and actual older-client/native compatibility are separate.
    }

    @Test
    fun d20MatchingRequiredObservationMapsOnePrivateCurrentCutWithOnlyItemQuotation() = runTest {
        val fixture = FdpGatewayFixture()
        val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
        val body = assertIs<LocalFoodUiBody.Check>(page.food?.body)
        assertEquals("sales.food.check-kitchen.read", fixture.endpoint.reads.single().query.name)
        assertEquals(1, fixture.endpoint.reads.single().query.version)
        assertEquals(f.canonicalSelector, fixture.endpoint.reads.single().payload.copyBytes().decodeToString())
        assertEquals(FdpGatewayInputs.id("orderIds", "deliveryRestaurant"), body.check.orderId)
        assertEquals(FdpGatewayInputs.owner, body.check.ownerId)
        assertEquals(Revision(30), body.check.orderStorageRevision)
        assertEquals("RESTAURANT", body.check.activity)
        assertEquals("DELIVERY", body.check.service.channel)
        assertEquals("DEL-SYN-030", body.check.service.reference)
        assertEquals("", body.check.service.seatNote)
        val delivery = assertNotNull(body.delivery)
        assertEquals(body.check.orderId, delivery.orderId)
        assertEquals(Revision(30), delivery.orderStorageRevision)
        assertEquals(f.baseFields().getValue("recipient"), delivery.details.recipient)
        assertEquals(f.baseFields().getValue("contact"), delivery.details.contact)
        assertEquals(f.baseFields().getValue("address"), delivery.details.address)
        assertEquals(listOf(FdpGatewayInputs.id("portionIds", "selectedFirst"), FdpGatewayInputs.id("portionIds", "selectedSecond")),
            body.portions.map { it.portionId })
        assertTrue(body.portions.all { it.quantity == FdpGatewayInputs.one && it.itemNote == "" && it.seatNote == "" &&
            it.preparation == LocalFoodPreparation.NOT_SENT })
        assertEquals(Money(Currency.EGP, 22_800), body.originalQuotedTotal)
        assertTrue(body.chargeResolutionRequired)
        assertNull(body.check.collectionId)
        assertNull(body.financial)
        assertEquals(LocalFoodUiKitchenObservation(body.check.orderId, Revision(30), 2, LocalFoodUiKitchenStatus.UNAVAILABLE), body.kitchen)
        assertTrue(page.forms.none { it.action == LocalAction.FOOD_SEND || it.action == LocalAction.FOOD_BEGIN_COLLECTION })
        fixture.withAccess { access -> assertNotNull(foodCreateDraftForm(access)) }
        fixture.assertNothingStaged()
        assertTrue(fixture.endpoint.lookups.isEmpty())
        // Public response mapping is not an independent stock/money/fulfillment observation.
    }

    @Test
    fun d20FrozenMissingMalformedForeignStaleAndNonDeliveryMetadataPublishNoUsableCheckOrFallback() = runTest {
        for ((caseId, raw) in FdpGatewayInputs.invalidWrappers()) {
            val fixture = FdpGatewayFixture()
            val query = if (caseId == f.caseId(f.ownCheckVariants[5])) {
                fixture.endpoint.onRead = { fixture.endpoint.data(FdpGatewayInputs.wrapperBytes(
                    LocalFoodCheckKitchenResultV1(FdpGatewayInputs.food(LocalSalesChannel.TAKEAWAY)))) }
                LocalWorkflowQuery(LocalWorkflow.FOOD,
                    food = LocalFoodUiQuery.Check(FdpGatewayInputs.id("orderIds", "takeaway")))
            } else fixture.query()
            assertIs<LocalReadResult.Available>(fixture.gateway.read(query), caseId)
            fixture.endpoint.onRead = { fixture.endpoint.data(FdpGatewayInputs.bytes(raw)) }
            assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(query), caseId)
            assertEquals(2, fixture.endpoint.reads.size, caseId)
            assertTrue(fixture.endpoint.reads.all { it.query == LocalFoodCheckKitchenCodecV1.QUERY }, caseId)
            fixture.assertNothingStaged()
            assertTrue(fixture.endpoint.lookups.isEmpty(), caseId)
        }
    }

    @Test
    fun d20FailedAdvertisedReadDoesNotRetrieveAnOldWrapperOrLegacyPrivateMetadata() = runTest {
        for (failure in listOf(ReadFailure.AUTHORIZATION_DENIED, ReadFailure.WRONG_SCOPE,
                ReadFailure.WRITER_NOT_QUALIFIED, ReadFailure.STORAGE_UNCERTAIN)) {
            val fixture = FdpGatewayFixture()
            assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query()))
            fixture.endpoint.onRead = { ReadReply.Unavailable(failure) }
            assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(fixture.query()))
            assertEquals(2, fixture.endpoint.reads.size)
            assertTrue(fixture.endpoint.reads.all { it.query == LocalFoodCheckKitchenCodecV1.QUERY })
            fixture.assertNothingStaged()
            assertTrue(fixture.endpoint.lookups.isEmpty())
        }
        for (failedBody in listOf("exception", "malformed", "legacy_only_body")) {
            val fixture = FdpGatewayFixture()
            assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query()))
            fixture.endpoint.onRead = {
                when (failedBody) {
                    "exception" -> error("Controlled lost/unusable public response")
                    "malformed" -> fixture.endpoint.data(FdpGatewayInputs.bytes("{"))
                    else -> fixture.endpoint.data(FdpGatewayInputs.legacyBytes(FdpGatewayInputs.food()))
                }
            }
            assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(fixture.query()))
            assertEquals(2, fixture.endpoint.reads.size)
            assertTrue(fixture.endpoint.reads.all { it.query == LocalFoodCheckKitchenCodecV1.QUERY })
            fixture.assertNothingStaged()
        }
        // Refusal encodings at the Master/common envelope remain Q03; none are invented.
    }

    @Test
    fun d20LateResponseAfterCredentialLifetimeLossCannotPublishPrivateMetadata() = runTest {
        val fixture = FdpGatewayFixture()
        val requested = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ReadReply>()
        fixture.endpoint.onRead = { requested.complete(Unit); release.await() }
        val response = async { fixture.gateway.read(fixture.query()) }
        requested.await()
        fixture.lifetimeCurrent = false
        release.complete(fixture.endpoint.data(FdpGatewayInputs.wrapperBytes(FdpGatewayInputs.wrapper())))
        assertIs<LocalReadResult.Unavailable>(response.await())
        assertEquals(LocalFoodCheckKitchenCodecV1.QUERY, fixture.endpoint.reads.single().query)
        fixture.assertNothingStaged()
        assertTrue(fixture.endpoint.lookups.isEmpty())
        // This is a credential-lease seam, not native lifecycle/session admission qualification.
    }

    @Test
    fun d20D22LegacyOnlyDeliveryReadRefusesWithoutDiscardingTheSavedClientRequest() = runTest {
        val fixture = FdpGatewayFixture(diagnosticInstalled = false)
        val intent = FdpGatewayInputs.intent()
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val original = assertNotNull(fixture.journal.entry)
        assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(fixture.query()))
        assertEquals(LocalFoodProfileV1.QUERY, fixture.endpoint.reads.single().query)
        assertEquals(original, fixture.journal.entry)
        assertEquals(f.canonicalDraft, assertNotNull(fixture.journal.entry).request().payload.copyBytes().decodeToString())
        assertExactIntake(f.baseFields(), assertNotNull(assertNotNull(savedFoodDraft(original.request())).deliveryDetails))
        assertEquals(1, fixture.journal.stageCalls)
        fixture.assertNoBusinessDispatch()
        assertTrue(fixture.endpoint.lookups.isEmpty())
    }

    @Test
    fun d22RequestDecoderAndGatewayReconstructionKeepExactStagedIdentityInsteadOfReplacementEditorText() = runTest {
        val fixture = FdpGatewayFixture()
        val intent = FdpGatewayInputs.intent()
        assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent))
        val original = assertNotNull(fixture.journal.entry)
        val request = original.request()
        val identity = original.identity()
        val serialized = journalJson.encodeToString(StoredClientIntentV1.serializer(), original)
        val restored = journalJson.decodeFromString(StoredClientIntentV1.serializer(), serialized)
        restored.validate(fixture.journal.owner)
        assertEquals(original, restored)
        fixture.journal.entry = restored
        fixture.endpoint.onRead = { error("Reconstruction cannot read current source/editor data") }
        val reconstructed = fixture.newGateway()
        val replacement = replaceIntake(intent, mapOf("recipient" to "Synthetic Replacement Recipient",
            "contact" to "replacement-30@example.invalid", "address" to "Replacement Synthetic Address"))
        val saved = assertNotNull(savedFoodDraft(restored.request()))
        assertExactIntake(f.baseFields(), assertNotNull(saved.deliveryDetails))
        assertIs<LocalStageResult.Uncertain>(reconstructed.stage(replacement))
        assertTrue(fixture.endpoint.lookups.isEmpty(), "Changed semantic input is not a lookup for another body")
        assertIs<LocalStageResult.Uncertain>(reconstructed.stage(intent))
        assertEquals(listOf(identity), fixture.endpoint.lookups)
        assertIs<LocalOperationResult.Unresolved>(reconstructed.submit(intent.operationId))
        assertIs<LocalOperationResult.Unresolved>(reconstructed.lookup(intent.operationId))
        assertEquals(listOf(identity, identity, identity), fixture.endpoint.lookups)
        assertEquals(original, fixture.journal.entry)
        assertEquals(request.payload, assertNotNull(fixture.journal.entry).request().payload)
        assertEquals(identity, assertNotNull(fixture.journal.entry).identity())
        assertEquals(original.inputDigest, assertNotNull(fixture.journal.entry).inputDigest)
        assertEquals(original.semanticSha256, assertNotNull(fixture.journal.entry).semanticSha256)
        assertPrivateSummaryKeepsIntake(f.baseFields(), restored.summary.map { it.ui() })
        assertEquals(1, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.reads.isEmpty())
        fixture.assertNoBusinessDispatch()
        // This is a staged client input, NOT ORIGINAL, R_DRAFT/R_ADD, durable order
        // reconstruction, Add persistence, process restart, or a successful recovered result.
    }
}

private fun assertExactIntake(expected: Map<String, String>, actual: LocalFoodDeliveryDetailsV1, message: String? = null) {
    assertEquals(expected.getValue("recipient"), actual.recipient, message)
    assertEquals(expected.getValue("contact"), actual.contact, message)
    assertEquals(expected.getValue("address"), actual.address, message)
}

/** Dedicated private review rows retain original values; generic-card projection is tested separately. */
private fun assertPrivateSummaryKeepsIntake(expected: Map<String, String>, summary: List<LocalRecordValue>) {
    for (value in expected.values) {
        assertTrue(summary.any { it.value.english == value && it.value.arabic == value })
    }
}

private fun replaceIntake(intent: LocalCommandIntent, fields: Map<String, String>): LocalCommandIntent =
    LocalCommandIntent(intent.operationId, intent.action, intent.formVersion, intent.reference,
        intent.values.filterNot { it.field in FdpGatewayInputs.deliveryFields.values } + fields.map { (key, value) ->
            LocalScalarInput(FdpGatewayInputs.deliveryFields.getValue(key), LocalScalarValue.Text(value))
        }, intent.lineField, intent.lines)
