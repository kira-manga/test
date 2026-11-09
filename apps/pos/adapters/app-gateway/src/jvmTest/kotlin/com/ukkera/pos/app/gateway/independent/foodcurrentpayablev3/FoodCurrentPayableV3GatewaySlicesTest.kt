package com.ukkera.pos.app.gateway.independent.foodcurrentpayablev3

import com.ukkera.pos.app.LocalFoodFulfillment
import com.ukkera.pos.app.LocalFoodPreparation
import com.ukkera.pos.app.LocalFoodUiBody
import com.ukkera.pos.app.LocalFoodUiCurrentUnpaidPayableProfile
import com.ukkera.pos.app.LocalFoodUiQuery
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowQuery
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.app.gateway.foodCheckWorkflow
import com.ukkera.pos.app.gateway.foodRead
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.sales.FoodPreparationStatus
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV1
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV2
import com.ukkera.pos.sales.store.LocalFoodCheckKitchenCodecV3
import com.ukkera.pos.sales.store.LocalFoodQueryV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Supporting frozen FCP3-001/007/008/009/010/012/015 bindings through the real shared
 * read and check-workflow functions. Replies/lease are controlled, not native Master,
 * current authority, admitted RPC, source closure, concurrency or durable no-effect evidence.
 */
class FoodCurrentPayableV3GatewaySlicesTest {
    private val v3 = LocalFoodCheckKitchenCodecV3.QUERY
    private val v2 = LocalFoodCheckKitchenCodecV2.QUERY
    private val v1 = LocalFoodCheckKitchenCodecV1.QUERY
    private val legacy = ReadKey("sales.food.read", 1)

    @Test
    fun fcp3008_009_oneHighestSupportedInstalledVersionIsChosenBeforeDispatch() = runTest {
        val variants = listOf(setOf(v3, v2, v1, legacy) to v3, setOf(v2, v1, legacy) to v2,
            setOf(v1) to v1, setOf(legacy) to legacy,
            setOf(ReadKey(v3.name, 4), v3, v2, v1, legacy) to v3)
        for ((keys, selected) in variants) {
            val h = FoodCurrentPayableV3GatewayInputs(keys)
            try {
                h.endpoint.respond = { request -> h.reply(h.frame(request.query, h.mix(visible = listOf(2), next = h.portion(2)),
                    current = if (request.query == v3) h.egp(19_403) else null)) }
                val result = foodRead(h.access, LocalFoodQueryV1.Order(h.order, h.portion(1), 1))
                assertEquals(if (selected == v3) h.egp(19_403) else null, result.currentUnpaidPayable)
                assertEquals(h.egp(30_803), result.result.detail?.originalQuotedTotal)
                assertEquals(if (selected == v3) LocalFoodUiCurrentUnpaidPayableProfile.ORDINARY_UNPAID_V3
                    else LocalFoodUiCurrentUnpaidPayableProfile.NEVER_SENT_ONLY, result.currentUnpaidPayableProfile)
                assertEquals(listOf(selected), h.endpoint.requests.map { it.query })
                assertEquals("{\"type\":\"ORDER\",\"orderId\":\"${h.order.value}\",\"afterPortionId\":\"${h.portion(1).value}\",\"limit\":1}",
                    h.endpoint.requests.single().payload.copyBytes().decodeToString())
                assertEquals(0, h.endpoint.mutationCalls)
                assertEquals(0, h.endpoint.lookupCalls)
            } finally { h.lease.destroy() }
        }
    }

    @Test
    fun fcp3001_009_010_015_realCheckProjectionKeepsWholeCurrentForEveryControlledMixPage() = runTest {
        val h = FoodCurrentPayableV3GatewayInputs(setOf(v3, v2, v1, legacy))
        try {
            val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.FOOD_CHECKS)
            assertTrue(installed.has(v3), "FCP3-009 actual restricted shared installation includes V3")
            val access = h.accessFor(installed)
            val pages = listOf(Triple(listOf(1, 2), null, h.portion(2)),
                Triple(listOf(3, 4, 5), h.portion(2), h.portion(5)), Triple(listOf(6), h.portion(5), null))
            for ((visible, after, next) in pages) {
                h.endpoint.respond = { request ->
                    if (request.query != v3) error("Unexpected lower-version request")
                    h.reply(h.frame(v3, h.mix(visible, next)))
                }
                val before = h.endpoint.requests.size
                val page = foodCheckWorkflow(access, LocalWorkflowQuery(LocalWorkflow.FOOD,
                    food = LocalFoodUiQuery.Check(h.order, after)))
                val body = assertIs<LocalFoodUiBody.Check>(assertNotNull(page.food).body)
                assertEquals(h.egp(19_403), body.currentUnpaidPayable)
                assertEquals(h.egp(30_803), body.originalQuotedTotal)
                assertEquals(LocalFoodUiCurrentUnpaidPayableProfile.ORDINARY_UNPAID_V3, body.currentUnpaidPayableProfile)
                assertEquals(h.order, body.check.orderId)
                assertEquals(h.owner, body.check.ownerId)
                assertEquals(Revision(41), body.check.orderStorageRevision)
                assertEquals(visible.map(h::portion), body.portions.map { it.portionId })
                assertEquals(listOf(v3), h.endpoint.requests.drop(before).map { it.query })
                assertTrue(page.forms.isEmpty(), "FCP3-015 restricted amount view creates no business action form")
                if (5 in visible) assertEquals(LocalFoodFulfillment.SERVED, body.portions.single { it.portionId == h.portion(5) }.fulfillment)
                if (6 in visible) assertEquals(LocalFoodPreparation.CANCELLED_BEFORE_START, body.portions.single().preparation)
            }
            assertEquals(0, h.endpoint.mutationCalls)
            assertEquals(0, h.endpoint.lookupCalls)
        } finally { h.lease.destroy() }
    }

    @Test
    fun fcp3001_009_allOrdinarySentControlledReplyStillHasKnownV3Current() = runTest {
        val h = FoodCurrentPayableV3GatewayInputs(setOf(v3, v2, v1, legacy))
        try {
            h.endpoint.respond = { h.reply(h.frame(v3, h.mix(visible = listOf(1, 2, 3, 4, 5, 6), next = null,
                allSent = true), unsentCount = 0)) }
            val body = assertIs<LocalFoodUiBody.Check>(assertNotNull(foodCheckWorkflow(h.access,
                LocalWorkflowQuery(LocalWorkflow.FOOD, food = LocalFoodUiQuery.Check(h.order))).food).body)
            assertEquals(h.egp(19_403), body.currentUnpaidPayable)
            assertEquals(h.egp(30_803), body.originalQuotedTotal)
            assertEquals(LocalFoodPreparation.QUEUED, body.portions.first().preparation)
            assertEquals(listOf(v3), h.endpoint.requests.map { it.query })
        } finally { h.lease.destroy() }
    }

    @Test
    fun fcp3009_chosenV3FailureDenialMalformedOrMissingSourceNeverRetriesOldOfferedVersions() = runTest {
        for (failure in listOf("transport", "denial", "missing-current", "truncated", "source-unavailable", "malformed-food")) {
            val h = FoodCurrentPayableV3GatewayInputs(setOf(v3, v2, v1, legacy))
            try {
                h.endpoint.respond = { request ->
                    if (request.query != v3) h.reply(h.frame(request.query, current = null)) else when (failure) {
                        "transport" -> error("Controlled interrupted read transport")
                        "denial" -> ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
                        "source-unavailable" -> ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN)
                        "truncated" -> h.reply(h.frame(v3).dropLast(1))
                        else -> {
                            val valid = Json.parseToJsonElement(h.frame(v3)).jsonObject
                            h.reply(if (failure == "missing-current") JsonObject(valid - "currentUnpaidPayable").toString()
                                else JsonObject(valid + ("food" to JsonPrimitive(true))).toString())
                        }
                    }
                }
                assertFails("FCP3-009 $failure cannot return a verified check projection") {
                    foodCheckWorkflow(h.access, LocalWorkflowQuery(LocalWorkflow.FOOD, food = LocalFoodUiQuery.Check(h.order)))
                }
                assertEquals(listOf(v3), h.endpoint.requests.map { it.query }, "FCP3-009 no 2/1/legacy fallback")
                assertEquals(0, h.endpoint.mutationCalls)
                assertEquals(0, h.endpoint.lookupCalls)
            } finally { h.lease.destroy() }
        }
    }

    @Test
    fun fcp3003_005_009_v3NullStaysUnavailableAndEmptyStaysKnownZero() = runTest {
        val h = FoodCurrentPayableV3GatewayInputs(setOf(v3, v2, v1, legacy))
        try {
            h.endpoint.respond = { h.reply(h.frame(v3, current = null)) }
            val unknown = assertIs<LocalFoodUiBody.Check>(assertNotNull(foodCheckWorkflow(h.access,
                LocalWorkflowQuery(LocalWorkflow.FOOD, food = LocalFoodUiQuery.Check(h.order))).food).body)
            assertNull(unknown.currentUnpaidPayable)
            assertEquals(h.egp(30_803), unknown.originalQuotedTotal)
            assertEquals(LocalFoodUiCurrentUnpaidPayableProfile.ORDINARY_UNPAID_V3, unknown.currentUnpaidPayableProfile)
            assertEquals(listOf(v3), h.endpoint.requests.map { it.query })
            h.endpoint.respond = { h.reply(h.frame(v3, h.empty(), current = h.egp(0), unsentCount = 0)) }
            val zero = assertIs<LocalFoodUiBody.Check>(assertNotNull(foodCheckWorkflow(h.access,
                LocalWorkflowQuery(LocalWorkflow.FOOD, food = LocalFoodUiQuery.Check(h.emptyOrder))).food).body)
            assertEquals(h.egp(0), zero.currentUnpaidPayable)
            assertEquals(h.egp(0), zero.originalQuotedTotal)
            assertEquals(listOf(v3, v3), h.endpoint.requests.map { it.query })
        } finally { h.lease.destroy() }
        // The null is a controlled valid DTO, not a genuine locked/partial-paid financial fixture.
    }

    @Test
    fun fcp3008_009_v2KnownPresendRemainsLegacyAndPreparedKnownCannotBeReinterpretedAsV3() = runTest {
        val h = FoodCurrentPayableV3GatewayInputs(setOf(v2, v1, legacy))
        try {
            h.endpoint.respond = { h.reply(h.frame(v2, h.mix(prepared = false), current = h.egp(19_403), unsentCount = 5)) }
            val pre = assertIs<LocalFoodUiBody.Check>(assertNotNull(foodCheckWorkflow(h.access,
                LocalWorkflowQuery(LocalWorkflow.FOOD, food = LocalFoodUiQuery.Check(h.order))).food).body)
            assertEquals(h.egp(19_403), pre.currentUnpaidPayable)
            assertEquals(LocalFoodUiCurrentUnpaidPayableProfile.NEVER_SENT_ONLY, pre.currentUnpaidPayableProfile)
            val before = h.endpoint.requests.size
            h.endpoint.respond = { h.reply(h.frame(v3, h.mix(visible = listOf(5), next = null))) }
            assertFails("FCP3-008 prepared known V2-shaped response fails V2 validation") {
                foodRead(h.access, LocalFoodQueryV1.Order(h.order, h.portion(4), 1))
            }
            assertEquals(listOf(v2), h.endpoint.requests.drop(before).map { it.query })
            h.endpoint.respond = { ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED) }
            val beforeDenial = h.endpoint.requests.size
            assertFails("FCP3-009 failure of selected V2 does not retry V1/legacy") {
                foodRead(h.access, LocalFoodQueryV1.Order(h.order, limit = 1))
            }
            assertEquals(listOf(v2), h.endpoint.requests.drop(beforeDenial).map { it.query })
        } finally { h.lease.destroy() }
    }

    @Test
    fun fcp3009_newReadAfterAdvertisementChangeMakesAFreshSelection() = runTest {
        val h = FoodCurrentPayableV3GatewayInputs(setOf(v3, v2, v1, legacy))
        try {
            h.endpoint.respond = { request -> h.reply(h.frame(request.query, current = if (request.query == v3) h.egp(19_403) else null)) }
            assertEquals(h.egp(19_403), foodRead(h.access, LocalFoodQueryV1.Order(h.order, limit = 1)).currentUnpaidPayable)
            val changed = h.accessFor(LocalWorkflowInstallation(emptySet(), setOf(v2, v1, legacy)))
            assertNull(foodRead(changed, LocalFoodQueryV1.Order(h.order, limit = 1)).currentUnpaidPayable)
            assertEquals(listOf(v3, v2), h.endpoint.requests.map { it.query })
        } finally { h.lease.destroy() }
        // Changing installed client inputs is supporting selection, not native reconnect/admission.
    }

    @Test
    fun fcp3007_eachControlledMaximumPageKeeps3648InsteadOfItsPageSubtotal() = runTest {
        val h = FoodCurrentPayableV3GatewayInputs(setOf(v3))
        try {
            for (limit in listOf(1, 8)) {
                var after: DomainId? = null
                for (members in (1..32).chunked(limit)) {
                    val next = if (members.last() == 32) null else h.fullPortion(members.last())
                    h.endpoint.respond = { h.reply(h.frame(v3, h.full(members, next), current = h.egp(3_648), unsentCount = 8)) }
                    val before = h.endpoint.requests.size
                    val result = foodRead(h.access, LocalFoodQueryV1.Order(h.fullOrder, after, limit))
                    assertEquals(h.egp(3_648), result.currentUnpaidPayable)
                    assertEquals(h.egp(3_648), result.result.detail?.originalQuotedTotal)
                    assertEquals(members.map(h::fullPortion), result.result.detail?.portions?.map { it.portionId })
                    assertEquals(32, result.result.detail?.summary?.portionCount)
                    assertEquals(listOf(v3), h.endpoint.requests.drop(before).map { it.query })
                    after = next
                }
            }
            assertEquals(0, h.endpoint.mutationCalls)
            assertEquals(0, h.endpoint.lookupCalls)
        } finally { h.lease.destroy() }
        // Complete native storage history and genuine server pagination are not established by these replies.
    }

    @Test
    fun fcp3009_010_wrongOwnerOrderOrObservationRevisionCannotProduceVerifiedCurrent() = runTest {
        for (mismatch in listOf("owner", "order", "kitchen-revision")) {
            val h = FoodCurrentPayableV3GatewayInputs(setOf(v3, v2, v1, legacy))
            try {
                h.endpoint.respond = {
                    val food = h.mix(returnedOwner = if (mismatch == "owner") h.otherOwner else h.owner,
                        returnedOrder = if (mismatch == "order") h.emptyOrder else h.order)
                    val valid = h.frame(v3, food)
                    h.reply(if (mismatch != "kitchen-revision") valid else {
                        val frame = Json.parseToJsonElement(valid).jsonObject
                        JsonObject(frame + ("kitchen" to JsonObject(frame.getValue("kitchen").jsonObject +
                            ("orderStorageRevision" to JsonPrimitive("42"))))).toString()
                    })
                }
                assertFails("FCP3-010 $mismatch cannot present verified current") {
                    foodCheckWorkflow(h.access, LocalWorkflowQuery(LocalWorkflow.FOOD, food = LocalFoodUiQuery.Check(h.order)))
                }
                assertEquals(listOf(v3), h.endpoint.requests.map { it.query })
            } finally { h.lease.destroy() }
        }
        // Client validation is not proof of Master-side owner/session/device authority.
    }

    @Test
    fun fcp3012_controlledPermittedCurrentTuplesRemainOneReplyProjection() = runTest {
        val h = FoodCurrentPayableV3GatewayInputs(setOf(v3))
        try {
            for ((revision, cancelled, amount) in listOf(Triple(Revision(41), false, 19_403L), Triple(Revision(42), true, 8_003L))) {
                h.endpoint.respond = { h.reply(h.frame(v3, h.mix(cancelA = cancelled, revision = revision),
                    current = h.egp(amount), unsentCount = if (cancelled) 0 else 1)) }
                val result = foodRead(h.access, LocalFoodQueryV1.Order(h.order, limit = 1))
                assertEquals(revision, result.result.detail?.summary?.revision)
                assertEquals(if (cancelled) FoodPreparationStatus.CANCELLED_BEFORE_START else FoodPreparationStatus.NOT_SENT,
                    result.result.detail?.portions?.single()?.preparation?.status)
                assertEquals(h.egp(amount), result.currentUnpaidPayable)
                assertEquals(h.egp(30_803), result.result.detail?.originalQuotedTotal)
                assertEquals(revision, result.kitchen?.orderStorageRevision)
            }
            assertEquals(listOf(v3, v3), h.endpoint.requests.map { it.query })
        } finally { h.lease.destroy() }
        // Frozen tuples are controlled inputs; this does not execute the production cancellation/read race.
    }

    @Test
    fun fcp3009_010_clientLeaseLossAfterDataRefusesTheProjectionWithoutFallback() = runTest {
        val h = FoodCurrentPayableV3GatewayInputs(setOf(v3, v2, v1, legacy))
        try {
            h.endpoint.respond = {
                h.clientLeaseCurrent = false
                h.reply(h.frame(v3))
            }
            assertFails("Controlled client lease loss cannot publish a new verified amount") {
                foodCheckWorkflow(h.access, LocalWorkflowQuery(LocalWorkflow.FOOD, food = LocalFoodUiQuery.Check(h.order)))
            }
            assertEquals(listOf(v3), h.endpoint.requests.map { it.query })
            assertEquals(0, h.endpoint.mutationCalls)
        } finally { h.lease.destroy() }
        // No genuine Master revocation, private-controller cleanup or native lifecycle result is claimed.
    }
}
