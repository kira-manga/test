package com.ukkera.pos.app.gateway.independent.foodblockedunpaidstatev1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRelationalQueriesV2
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Frozen FBU client-boundary slices. Scripted public replies and a client-input journal
 * exercise the production shared gateway, not protected Master/native/RPC admission.
 * No business command is submitted and no physical-effect or historical proof is supplied.
 * All 24 whole cases, including FBU-23 production journeys, remain NOT_RUN.
 */
class FoodBlockedUnpaidFrozenGatewaySlicesTest {
    @Test
    fun fbu02And11CurrentBlockedCheckUsesOneReplyAndRetainsExactSavedDetails() = runTest {
        for (base in listOf(FbuGatewayInputs.r2, FbuGatewayInputs.c2)) {
            for (channel in listOf(LocalSalesChannel.DINE_IN, LocalSalesChannel.TAKEAWAY)) {
                val order = base.copy(channel = channel)
                val fixture = FbuGatewayFixture(order)
                val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
                val body = assertIs<LocalFoodUiBody.Check>(page.food?.body)
                assertEquals(LocalFoodCheckKitchenCodecV1.QUERY, fixture.endpoint.reads.single().query)
                assertEquals(LocalFoodCheckKitchenCodecV1.encode(LocalFoodQueryV1.Order(order.orderId)),
                    fixture.endpoint.reads.single().payload)
                assertExactUnpaidCheck(order, body)
                assertEquals(LocalFoodUiKitchenObservation(order.orderId, Revision(7), 2,
                    LocalFoodUiKitchenStatus.UNAVAILABLE), body.kitchen)
                assertTrue(body.kitchenUnavailableForUnsentWork)
                assertTrue(page.forms.none { it.action in setOf(LocalAction.FOOD_SEND, LocalAction.FOOD_BEGIN_COLLECTION) })
                assertDraftAndAddReachable(fixture, body)
                fixture.assertNothingStaged()
            }
        }
    }

    @Test
    fun fbu11AdvertisedDiagnosticFailuresDoNotFallBackOrPublishAPrivateCheck() = runTest {
        val failures: List<Pair<ReadFailure, LocalServiceProblem>> = listOf(
            ReadFailure.AUTHORIZATION_DENIED to LocalServiceProblem.DENIED,
            ReadFailure.WRONG_SCOPE to LocalServiceProblem.DENIED,
            ReadFailure.WRITER_NOT_QUALIFIED to LocalServiceProblem.UNAVAILABLE,
            ReadFailure.STORAGE_UNCERTAIN to LocalServiceProblem.STORAGE_UNCERTAIN,
        )
        for ((failure, expected) in failures) {
            val fixture = FbuGatewayFixture()
            fixture.endpoint.onRead = { ReadReply.Unavailable(failure) }
            assertEquals(expected, assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(fixture.query())).problem)
            assertEquals(LocalFoodCheckKitchenCodecV1.QUERY, fixture.endpoint.reads.single().query)
            fixture.assertNothingStaged()
        }
        for (failure in listOf("exception", "malformed", "legacy_outer_schema")) {
            val fixture = FbuGatewayFixture()
            fixture.endpoint.onRead = {
                when (failure) {
                    "exception" -> error("Controlled unavailable public response")
                    "malformed" -> fixture.endpoint.data(FbuGatewayInputs.bytes("{"))
                    else -> fixture.endpoint.data(FbuGatewayInputs.legacyBytes(FbuGatewayInputs.food(fixture.order)))
                }
            }
            assertEquals(LocalServiceProblem.UNAVAILABLE,
                assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(fixture.query())).problem)
            assertEquals(LocalFoodCheckKitchenCodecV1.QUERY, fixture.endpoint.reads.single().query)
            fixture.assertNothingStaged()
        }
    }

    @Test
    fun fbu11LegacyAbsenceAndExplicitUnknownNeverBecomeConfirmedKitchenAbsence() = runTest {
        for (mode in listOf("legacy", "omitted", "unknown")) {
            val fixture = FbuGatewayFixture(diagnosticInstalled = mode != "legacy")
            fixture.endpoint.onRead = {
                fixture.endpoint.data(if (mode == "legacy") FbuGatewayInputs.legacyBytes(FbuGatewayInputs.food(fixture.order))
                else FbuGatewayInputs.wrapperBytes(FbuGatewayInputs.wrapper(fixture.order,
                    if (mode == "omitted") null else LocalFoodCheckKitchenStatusV1.UNKNOWN)))
            }
            val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
            val body = assertIs<LocalFoodUiBody.Check>(page.food?.body)
            assertExactUnpaidCheck(fixture.order, body)
            assertFalse(body.kitchenUnavailableForUnsentWork)
            if (mode == "unknown") assertEquals(LocalFoodUiKitchenObservation(fixture.order.orderId, Revision(7), 2,
                LocalFoodUiKitchenStatus.UNKNOWN), body.kitchen) else assertNull(body.kitchen)
            assertEquals(if (mode == "legacy") LocalFoodProfileV1.QUERY else LocalFoodCheckKitchenCodecV1.QUERY,
                fixture.endpoint.reads.single().query)
            assertDraftAndAddReachable(fixture, body)
            fixture.assertNothingStaged()
        }
    }

    @Test
    fun fbu01EmptyZeroWorkAndBlockedObservationsKeepDraftAndAddReachableOnly() = runTest {
        for (order in listOf(FbuGatewayInputs.r0, FbuGatewayInputs.c0)) {
            for (status in listOf(LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK, LocalFoodCheckKitchenStatusV1.UNAVAILABLE)) {
                val fixture = FbuGatewayFixture(order)
                fixture.endpoint.onRead = { fixture.endpoint.data(FbuGatewayInputs.wrapperBytes(FbuGatewayInputs.wrapper(order, status))) }
                val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
                val body = assertIs<LocalFoodUiBody.Check>(page.food?.body)
                assertExactUnpaidCheck(order, body)
                assertEquals(LocalFoodUiKitchenObservation(order.orderId, Revision(7), 0,
                    LocalFoodUiKitchenStatus.valueOf(status.name)), body.kitchen)
                assertTrue(body.portions.isEmpty())
                assertEquals(Money(Currency.EGP, 0), body.originalQuotedTotal)
                assertTrue(page.forms.none { it.action in setOf(LocalAction.FOOD_SEND, LocalAction.FOOD_BEGIN_COLLECTION) })
                assertDraftAndAddReachable(fixture, body)
                fixture.assertNothingStaged()
            }
        }
    }

    @Test
    fun fbu13ForeignStaleMissingAndInconsistentDiagnosticCannotPublishAPositivePage() = runTest {
        val root = FbuGatewayInputs.json.parseToJsonElement(
            FbuGatewayInputs.wrapperBytes(FbuGatewayInputs.wrapper(FbuGatewayInputs.r2)).copyBytes().decodeToString()).jsonObject
        val kitchen = root.getValue("kitchen").jsonObject
        val variants = listOf(
            kitchen + ("orderId" to JsonPrimitive(FbuGatewayInputs.c2.orderId.value)),
            kitchen + ("orderStorageRevision" to JsonPrimitive("6")),
            kitchen - "orderStorageRevision",
            kitchen + ("unsentPortionCount" to JsonPrimitive(-1)),
            kitchen + ("unsentPortionCount" to JsonPrimitive(1)),
            kitchen + ("unsentPortionCount" to JsonPrimitive(3)),
            kitchen + ("status" to JsonPrimitive("NO_UNSENT_WORK")),
        )
        for (fields in variants) {
            val fixture = FbuGatewayFixture()
            fixture.endpoint.onRead = { fixture.endpoint.data(FbuGatewayInputs.bytes(
                JsonObject(root + ("kitchen" to JsonObject(fields))).toString())) }
            assertEquals(LocalServiceProblem.UNAVAILABLE,
                assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(fixture.query())).problem)
            assertEquals(LocalFoodCheckKitchenCodecV1.QUERY, fixture.endpoint.reads.single().query)
            fixture.assertNothingStaged()
        }
    }

    @Test
    fun fbu06WholeUnsentCountBlocksCollectionEvenWhenOnlyIssuedWorkIsVisible() = runTest {
        val order = FbuGatewayInputs.mixed
        val fixture = FbuGatewayFixture(order)
        val issuedId = FbuGatewayInputs.id("321")
        val response = FbuGatewayInputs.wrapper(order, visible = listOf(issuedId), next = issuedId)
        fixture.endpoint.onRead = { fixture.endpoint.data(FbuGatewayInputs.wrapperBytes(response)) }
        val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
        val body = assertIs<LocalFoodUiBody.Check>(page.food?.body)
        assertEquals(order.orderId, body.check.orderId)
        assertEquals(Revision(7), body.check.orderStorageRevision)
        assertEquals(3, body.check.portionCount)
        assertEquals(listOf(issuedId), body.portions.map { it.portionId })
        assertEquals(LocalFoodPreparation.QUEUED, body.portions.single().preparation)
        assertEquals(2, body.kitchen?.unsentPortionCount)
        assertTrue(body.kitchenUnavailableForUnsentWork)
        assertEquals(Money(Currency.EGP, 30_000), body.originalQuotedTotal)
        assertTrue(page.forms.none { it.action == LocalAction.FOOD_BEGIN_COLLECTION })
        assertEquals(LocalServiceProblem.UNAVAILABLE,
            assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(FbuGatewayInputs.beginIntent(order))).problem)
        assertTrue(fixture.endpoint.reads.none { it.query == LocalStockRelationalQueriesV2.PROFILE })
        fixture.assertNothingStaged()

        // Three unsent members contradict this page's already-issued member, even though
        // the whole count fits the summary's three portions. Never use a visible subset as zero.
        val inconsistent = response.copy(kitchen = assertNotNull(response.kitchen).copy(unsentPortionCount = 3))
        val invalidFixture = FbuGatewayFixture(order)
        invalidFixture.endpoint.onRead = { invalidFixture.endpoint.data(FbuGatewayInputs.wrapperBytes(inconsistent)) }
        assertEquals(LocalServiceProblem.UNAVAILABLE,
            assertIs<LocalReadResult.Unavailable>(invalidFixture.gateway.read(invalidFixture.query())).problem)
        invalidFixture.assertNothingStaged()
    }

    @Test
    fun fbu04StaleEnabledSendCannotStageAfterFreshUnavailableObservation() = runTest {
        for (order in listOf(FbuGatewayInputs.r2, FbuGatewayInputs.c2)) {
            val fixture = FbuGatewayFixture(order)
            fixture.endpoint.onRead = { fixture.endpoint.data(FbuGatewayInputs.wrapperBytes(
                FbuGatewayInputs.wrapper(order, LocalFoodCheckKitchenStatusV1.UNKNOWN))) }
            val old = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
            assertTrue(old.forms.any { it.action == LocalAction.FOOD_SEND })
            val oldReadCount = fixture.endpoint.reads.size
            fixture.endpoint.onRead = { fixture.endpoint.data(FbuGatewayInputs.wrapperBytes(FbuGatewayInputs.wrapper(order))) }
            assertEquals(LocalServiceProblem.UNAVAILABLE,
                assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(FbuGatewayInputs.sendIntent(order))).problem)
            val freshReads = fixture.endpoint.reads.drop(oldReadCount)
            assertTrue(freshReads.isNotEmpty())
            assertTrue(freshReads.all { it.query == LocalFoodCheckKitchenCodecV1.QUERY })
            fixture.assertNothingStaged()
        }
    }

    @Test
    fun fbu04And06FreshBeginCollectionRefusesBeforeRoutingOrClientStaging() = runTest {
        for (order in listOf(FbuGatewayInputs.r2, FbuGatewayInputs.c2, FbuGatewayInputs.mixed)) {
            val fixture = FbuGatewayFixture(order)
            fixture.endpoint.onRead = { fixture.endpoint.data(FbuGatewayInputs.wrapperBytes(
                FbuGatewayInputs.wrapper(order, LocalFoodCheckKitchenStatusV1.UNKNOWN))) }
            val old = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
            assertTrue(old.forms.any { it.action == LocalAction.FOOD_BEGIN_COLLECTION })
            val oldReadCount = fixture.endpoint.reads.size
            fixture.endpoint.onRead = { fixture.endpoint.data(FbuGatewayInputs.wrapperBytes(FbuGatewayInputs.wrapper(order))) }
            assertEquals(LocalServiceProblem.UNAVAILABLE,
                assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(FbuGatewayInputs.beginIntent(order))).problem)
            assertTrue(fixture.endpoint.reads.drop(oldReadCount).isNotEmpty())
            assertTrue(fixture.endpoint.reads.none { it.query == LocalStockRelationalQueriesV2.PROFILE })
            fixture.assertNothingStaged()
        }
    }

    @Test
    fun fbu11And24IssuedOnlyZeroWorkDoesNotBlanketBlockCollectionInput() = runTest {
        val order = FbuGatewayInputs.issued
        val fixture = FbuGatewayFixture(order)
        fixture.endpoint.onRead = { request -> fixture.endpoint.data(
            if (request.query == LocalStockRelationalQueriesV2.PROFILE) FbuGatewayInputs.bytes("{\"version\":2,\"normalized\":false}")
            else FbuGatewayInputs.wrapperBytes(FbuGatewayInputs.wrapper(order, LocalFoodCheckKitchenStatusV1.NO_UNSENT_WORK))) }
        val page = assertIs<LocalReadResult.Available>(fixture.gateway.read(fixture.query())).page
        val body = assertIs<LocalFoodUiBody.Check>(page.food?.body)
        assertEquals(LocalFoodUiKitchenObservation(order.orderId, Revision(7), 0,
            LocalFoodUiKitchenStatus.NO_UNSENT_WORK), body.kitchen)
        assertFalse(body.kitchenUnavailableForUnsentWork)
        assertTrue(body.portions.all { it.preparation == LocalFoodPreparation.QUEUED })
        assertTrue(page.forms.any { it.action == LocalAction.FOOD_BEGIN_COLLECTION })
        val intent = FbuGatewayInputs.beginIntent(order)
        assertEquals(intent.operationId, assertIs<LocalStageResult.Staged>(fixture.gateway.stage(intent)).operation.operationId)
        val entry = assertNotNull(fixture.journal.entry)
        assertEquals(FbuGatewayInputs.owner, entry.owner.actorId)
        assertEquals(intent.operationId, entry.operationId)
        assertEquals(intent.formVersion, entry.formVersion)
        assertEquals(LocalFoodFinancialProfileV1.COMMAND, entry.request().command)
        val input = assertIs<LocalFoodFinancialRequestV1.BeginCollection>(LocalFoodFinancialCodecV1.decode(entry.request().payload))
        assertEquals(order.orderId, input.orderId)
        assertEquals(Revision(7), input.expectedOrderRevision)
        assertEquals(FbuGatewayInputs.id("541"), input.collectionId)
        assertEquals(FbuGatewayInputs.id("092"), input.sessionId)
        assertEquals(1, fixture.journal.stageCalls)
        fixture.assertNoDispatch() // Client input only: not financial admission, reservation or success.
    }

    @Test
    fun fbu11And22RetiredCredentialLifetimeRejectsAnOtherwiseValidLateCheckReply() = runTest {
        val fixture = FbuGatewayFixture()
        fixture.endpoint.onRead = {
            val reply = fixture.endpoint.data(FbuGatewayInputs.wrapperBytes(FbuGatewayInputs.wrapper(fixture.order)))
            fixture.lifetimeCurrent = false
            reply
        }
        assertEquals(LocalServiceProblem.SESSION_ENDED,
            assertIs<LocalReadResult.Unavailable>(fixture.gateway.read(fixture.query())).problem)
        assertEquals(LocalFoodCheckKitchenCodecV1.QUERY, fixture.endpoint.reads.single().query)
        fixture.assertNothingStaged()
    }
}

private fun assertExactUnpaidCheck(order: FbuGatewayOrder, body: LocalFoodUiBody.Check) {
    assertEquals(order.orderId, body.check.orderId)
    assertEquals(FbuGatewayInputs.owner, body.check.ownerId)
    assertEquals(Revision(7), body.check.orderStorageRevision)
    assertEquals(order.activity.name, body.check.activity)
    assertEquals(order.channel.name, body.check.service.channel)
    assertEquals(if (order.channel == LocalSalesChannel.DINE_IN) FbuGatewayInputs.id("090").value else "FBU-P001",
        body.check.service.reference)
    assertEquals(if (order.channel == LocalSalesChannel.DINE_IN) FbuGatewayInputs.seatNote else "", body.check.service.seatNote)
    assertEquals(order.portionIds.size, body.check.portionCount)
    assertEquals(order.portionIds, body.portions.map { it.portionId })
    assertEquals(Money(Currency.EGP, order.quoteMinor), body.originalQuotedTotal)
    assertNull(body.check.collectionId)
    assertNull(body.financial)
    assertFalse(body.chargeResolutionRequired)
    assertTrue(body.portions.all { it.quantity == FbuGatewayInputs.one && it.itemNote == FbuGatewayInputs.itemNote &&
        it.seatNote == FbuGatewayInputs.seatNote && it.preparation == LocalFoodPreparation.NOT_SENT &&
        it.commercialLineId == order.portionIds.first() })
}

/** Public current-form mapping only; these policy hints do not authenticate the frozen owner. */
private suspend fun assertDraftAndAddReachable(fixture: FbuGatewayFixture, body: LocalFoodUiBody.Check) {
    fixture.withAccess { access ->
        assertEquals(2, assertNotNull(foodCreateDraftForm(access)).version)
        val add = assertNotNull(foodMenuForm(access, LocalFoodUiQuery.Menu(orderId = body.check.orderId,
            expectedOrderStorageRevision = body.check.orderStorageRevision), FbuGatewayInputs.menu(fixture.order)))
        assertEquals(LocalAction.FOOD_ADD, add.action)
        assertEquals(2, add.version)
        val fields = add.fields.filterIsInstance<LocalFormField.Scalar>().associate { it.field to it.initialValue }
        assertEquals(LocalScalarValue.Identifier(body.check.orderId), fields[LocalField.ORDER_ID])
        assertEquals(LocalScalarValue.Integer(7), fields[LocalField.EXPECTED_FOOD_ORDER_STORAGE_REVISION])
        assertEquals(LocalScalarValue.Integer(5), fields[LocalField.EXPECTED_CATALOG_REVISION])
    }
}
