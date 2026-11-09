@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.managerorderpresentation

import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.sales.store.LocalFoodOrderSummaryV1
import com.ukkera.pos.sales.store.LocalFoodReadResultV1
import com.ukkera.pos.sales.store.LocalFoodServiceV1
import com.ukkera.pos.sales.store.LocalOrderReadResultV2
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.Revision
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Focused v2-frozen manager-order-presentation slices through the real gateway.
 * These are not whole-case passes: controlled endpoints do not prove current Master authority,
 * native source joins/state, canonical wire vectors, RPC, installed UI, or full OWN-06.
 * Unrepresented names/human selections/unit prices/tax and bilingual meanings remain unbound.
 */
class ManagerOrderPresentationGatewayTest {
    @Test
    fun mpv1LegacyBoundOwnerRevisionProfileQuantityPayableAndReferences() = runTest {
        val harness = ControlledManagerGateway()
        assertBoundManagerPage(harness.gateway.read(ManagerPresentationInputs.query()), ManagerPresentationInputs.legacyExpected)
        harness.assertTargetDispatch()
    }

    @Test
    fun mpv1NormalizedBoundDecimalQuantityAndSettledReferences() = runTest {
        val expected = ManagerPresentationInputs.normalizedExpected
        val harness = ControlledManagerGateway(target = expected.target, response = { managerPresent(expected) })
        assertBoundManagerPage(harness.gateway.read(ManagerPresentationInputs.query(expected.target)), expected)
        harness.assertTargetDispatch()
    }

    @Test
    fun mpv1MatchingSelectedTargetStillSendsOnlyTheTypedTarget() = runTest {
        val harness = ControlledManagerGateway()
        val reference = LocalRecordReference(LocalRecordKind.ORDER, ManagerPresentationInputs.legacy)
        assertBoundManagerPage(harness.gateway.read(ManagerPresentationInputs.query(reference = reference)),
            ManagerPresentationInputs.legacyExpected)
        harness.assertTargetDispatch()
    }

    @Test
    fun mpv2All31FrozenPortionIdentitiesQuantitiesAndPayablesRemainInOneTargetPage() = runTest {
        val expected = ManagerPresentationInputs.manyExpected
        val harness = ControlledManagerGateway(target = expected.target, response = { managerPresent(expected) })
        assertBoundManagerPage(harness.gateway.read(ManagerPresentationInputs.query(expected.target)), expected)
        harness.assertTargetDispatch()
        // Record-model availability is bound here; actual scrolling/touch/keyboard remains separate.
    }

    @Test
    fun mpv1EmptyTargetIsRefusedBeforeAnyProtectedRead() = runTest {
        val harness = ControlledManagerGateway()
        assertManagerNoSuccess(harness.gateway.read(LocalWorkflowQuery(LocalWorkflow.ORDERS, "manager:")), harness)
        harness.assertTargetDispatch(0)
    }

    @Test
    fun mpv1ConflictingSelectedTargetIsRefusedBeforeRead() = runTest {
        val harness = ControlledManagerGateway()
        assertManagerNoSuccess(harness.gateway.read(ManagerPresentationInputs.query(reference =
            LocalRecordReference(LocalRecordKind.ORDER, ManagerPresentationInputs.conflicting))), harness)
        harness.assertTargetDispatch(0)
    }

    @Test
    fun mpv1ManagerCursorIsRefusedBeforeRead() = runTest {
        val harness = ControlledManagerGateway()
        assertManagerNoSuccess(harness.gateway.read(ManagerPresentationInputs.query().copy(cursor = ManagerPresentationInputs.cursor)), harness)
        harness.assertTargetDispatch(0)
    }

    @Test
    fun mpv1EverySharedNonOrdersWorkspaceRefusesManagerMode() = runTest {
        for (workflow in LocalWorkflow.entries.filterNot { it == LocalWorkflow.ORDERS }) {
            val harness = ControlledManagerGateway()
            // FOOD/KITCHEN/FULFILLMENT/REPORTS already forbid raw search in their typed query.
            // Constructor-level refusal is observed without prescribing an error or guard order.
            val query = try { LocalWorkflowQuery(workflow, ManagerPresentationInputs.query().search) }
                catch (_: IllegalArgumentException) { null }
            if (query != null) assertManagerNoSuccess(harness.gateway.read(query), harness)
            harness.assertTargetDispatch(0)
        }
    }

    @Test
    fun mpv1MissingV1InstallationHasExactNotImplementedResultWithoutFallback() = runTest {
        val rosters = listOf(emptySet(), setOf(ReadKey("sales.order.manager-review", 2)),
            setOf(ReadKey("sales.order.read", 1), ReadKey("sales.order.read", 2)))
        for (roster in rosters) {
            val harness = ControlledManagerGateway(reads = roster)
            val refused = assertIs<LocalReadResult.Unavailable>(harness.gateway.read(ManagerPresentationInputs.query()))
            assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, refused.problem)
            harness.assertTargetDispatch(0)
        }
    }

    @Test
    fun mpv1AllSixFrozenWeakHintVariantsRefuseBeforeRead() = runTest {
        val weakHints = listOf(
            "missing" to emptyList(),
            "explicit target deny" to listOf(ManagerPresentationInputs.hint(effect = GrantEffect.DENY)),
            "manager title only" to emptyList(),
            "ORDER_READ only" to listOf(ManagerPresentationInputs.hint(action = SecurityAction.ORDER_READ)),
            "ORDER_HANDOFF only" to listOf(ManagerPresentationInputs.hint(action = SecurityAction.ORDER_HANDOFF)),
            "grant for another target" to listOf(ManagerPresentationInputs.hint(target = ManagerPresentationInputs.normalized)),
        )
        for ((variant, grants) in weakHints) {
            val harness = ControlledManagerGateway(grants = grants)
            assertIs<LocalReadResult.Unavailable>(harness.gateway.read(ManagerPresentationInputs.query()), variant)
            harness.assertTargetDispatch(0)
        }
    }

    @Test
    fun mpv1StaleAllowingHintDoesNotTurnControlledMasterDenialIntoAbsence() = runTest {
        val harness = ControlledManagerGateway(clientPolicyRevision = Revision(42),
            response = { ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED) })
        assertManagerNoSuccess(harness.gateway.read(ManagerPresentationInputs.query()), harness)
        harness.assertTargetDispatch()
        // Controlled denial propagation only; actual policy-43 evaluation is a genuine obligation.
    }

    @Test
    fun mpv1ControlledUnavailableDisconnectedTimeoutAndUncertainRepliesNeverBecomeEmpty() = runTest {
        fun failure(name: String, response: suspend () -> ReadReply) = name to response
        val failures = listOf(
            failure("Master unavailable") { ReadReply.Unavailable(ReadFailure.WRITER_NOT_QUALIFIED) },
            failure("disconnected local link") { throw IllegalStateException("Controlled disconnected read") },
            failure("uncertain timeout") { throw TimeoutException("Controlled read outcome uncertain") },
            failure("explicit uncertain read") { ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN) },
        )
        for ((variant, response) in failures) {
            val harness = ControlledManagerGateway(response = response)
            assertIs<LocalReadResult.Unavailable>(harness.gateway.read(ManagerPresentationInputs.query()), variant)
            harness.assertTargetDispatch()
        }
    }

    @Test
    fun mpv1AuthorizedEmptyShapeIsAvailableWithoutRecordsFormsOrContinuation() = runTest {
        val harness = ControlledManagerGateway(target = ManagerPresentationInputs.absent,
            response = { managerControlData(LocalOrderReadResultV2(emptyList(), null)) })
        val available = assertIs<LocalReadResult.Available>(harness.gateway.read(ManagerPresentationInputs.query(ManagerPresentationInputs.absent)))
        assertEquals(LocalWorkflow.ORDERS, available.page.workflow)
        assertTrue(available.page.records.isEmpty())
        assertTrue(available.page.forms.isEmpty())
        assertNull(available.page.nextCursor)
        val notice = assertNotNull(available.page.notice)
        assertTrue(notice.english.isNotBlank() && notice.arabic.isNotBlank())
        harness.assertTargetDispatch()
        // Revision-zero authorization and both notice meanings need separate binding/verification.
    }

    @Test
    fun mpv1WrongMultipleDuplicateCursorMissingProfileAndIncompleteRepliesFailClosed() = runTest {
        val legacy = ManagerPresentationInputs.projection(ManagerPresentationInputs.legacyExpected)
        val normalized = ManagerPresentationInputs.projection(ManagerPresentationInputs.normalizedExpected)
        fun root(orders: List<com.ukkera.pos.sales.store.LocalProfiledOrderV2>) = managerControlWire.encodeToJsonElement(
            LocalOrderReadResultV2.serializer(), LocalOrderReadResultV2(orders, null)).jsonObject
        val present = root(listOf(legacy))
        fun profile(transform: (JsonObject) -> JsonObject): JsonObject {
            val first = present.getValue("orders").jsonArray.single().jsonObject
            return JsonObject(present + ("orders" to JsonArray(listOf(transform(first)))))
        }
        fun order(transform: (JsonObject) -> JsonObject) = profile { item ->
            JsonObject(item + ("order" to transform(item.getValue("order").jsonObject)))
        }
        val missingPriceLinePayable = order { current ->
            val lines = current.getValue("lines").jsonArray
            JsonObject(current + ("lines" to JsonArray(listOf(JsonObject(lines.first().jsonObject - "payable")) + lines.drop(1))))
        }
        val bad = listOf(
            "wrong target" to root(listOf(normalized)),
            "two targets" to root(listOf(legacy, normalized)),
            "duplicate target" to root(listOf(legacy, legacy)),
            "present plus cursor" to JsonObject(present + ("nextAfter" to JsonPrimitive(ManagerPresentationInputs.cursor))),
            "empty plus cursor" to JsonObject(root(emptyList()) + ("nextAfter" to JsonPrimitive(ManagerPresentationInputs.cursor))),
            "missing profile" to profile { JsonObject(it - "stockProfile") },
            "unknown profile" to profile { JsonObject(it + ("stockProfile" to JsonPrimitive("UNKNOWN_PROFILE"))) },
            "missing quote payable" to order { JsonObject(it - "payable") },
            "missing required line payable" to missingPriceLinePayable,
            "incomplete envelope" to JsonObject(emptyMap()),
        )
        for ((variant, body) in bad) {
            val harness = ControlledManagerGateway(response = { managerControlData(body) })
            assertIs<LocalReadResult.Unavailable>(harness.gateway.read(ManagerPresentationInputs.query()), variant)
            harness.assertTargetDispatch()
        }
        // The source/discriminator mismatch has no source witness in this DTO; it stays UNBOUND.
        // Raw cursor spelling binds rejection only, not a valid native/canonical cursor fixture.
    }

    @Test
    fun mpv1AnIncompatibleTypedFoodReplyCannotPublishRetailManagerContent() = runTest {
        val food = LocalFoodReadResultV1(orders = listOf(LocalFoodOrderSummaryV1(ManagerPresentationInputs.foodOnly,
            ManagerPresentationInputs.owner, Revision(1), LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(LocalSalesChannel.COUNTER, "constructor-only FOOD service"), 1, null, null)))
        val harness = ControlledManagerGateway(target = ManagerPresentationInputs.foodOnly, response = {
            ReadReply.Data(managerControlBody(food, LocalFoodReadResultV1.serializer()), Revision(43),
                com.ukkera.pos.types.UtcInstant.parseCanonical("2026-10-30T09:00:00Z"))
        })
        assertManagerNoSuccess(harness.gateway.read(ManagerPresentationInputs.query(ManagerPresentationInputs.foodOnly)), harness)
        harness.assertTargetDispatch()
    }

    @Test
    fun mpv1RetirementBeforeDelayedPresentReplyPreventsGatewayPublication() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val harness = ControlledManagerGateway(response = {
            withContext(NonCancellable) { started.complete(Unit); release.await(); managerPresent() }
        })
        val result = async { harness.gateway.read(ManagerPresentationInputs.query()) }
        runCurrent()
        assertTrue(started.isCompleted)
        harness.leaseCurrent = false
        release.complete(Unit)
        runCurrent()
        assertManagerNoSuccess(result.await(), harness)
        harness.assertTargetDispatch()
    }

    @Test
    fun mpv1CallerCancellationPropagatesEvenWhenLatePresentDataIsDelivered() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var lateDataReturned = false
        val harness = ControlledManagerGateway(response = {
            withContext(NonCancellable) {
                started.complete(Unit); release.await()
                lateDataReturned = true
                managerPresent()
            }
        })
        val result = async { harness.gateway.read(ManagerPresentationInputs.query()) }
        runCurrent()
        assertTrue(started.isCompleted)
        result.cancel(CancellationException("Controlled caller cancellation"))
        release.complete(Unit)
        runCurrent()
        assertTrue(lateDataReturned, "The real gateway must encounter the deliberately late delivery")
        assertFailsWith<CancellationException> { result.await() }
        harness.assertTargetDispatch()
    }

    @Test
    fun mpv1EndpointCancellationIsPropagatedInsteadOfSuccessfulAbsence() = runTest {
        val harness = ControlledManagerGateway(response = { throw CancellationException("Controlled endpoint cancellation") })
        assertFailsWith<CancellationException> { harness.gateway.read(ManagerPresentationInputs.query()) }
        harness.assertTargetDispatch()
    }

    @Test
    fun mpv1AlreadyRetiredLeaseCannotDiscloseOrClaimEmptySuccess() = runTest {
        val harness = ControlledManagerGateway()
        harness.leaseCurrent = false
        harness.attachedLease = null
        assertManagerNoSuccess(harness.gateway.read(ManagerPresentationInputs.query()), harness)
        // Exact pre-read count/error is intentionally not an oracle for MP-v1-019.
    }
}
