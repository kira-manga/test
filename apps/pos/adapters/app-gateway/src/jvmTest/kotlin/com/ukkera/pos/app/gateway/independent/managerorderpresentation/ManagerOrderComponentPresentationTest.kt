@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.managerorderpresentation

import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalManagerQuoteComponent
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkflowPage
import com.ukkera.pos.app.gateway.fieldLabel
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.sales.store.LocalOrderComponentV1
import com.ukkera.pos.sales.store.LocalOrderReadResultV2
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
 * Partial v1 slices from qa/pos/freezes/manager-order-components; no whole-case pass.
 * Method map:
 * - legacy: MPC-v1-001/005/008 item associations, payable summary and read-only ports.
 * - normalized: MPC-v1-002/005/008 item associations and original decimal-line payable.
 * - many portions: MPC-v1-003/004/005/008 all supplied item occurrences and 31 portion identities.
 * - absent portion bindings: MPC-v1-003/005 nullable public-field preservation only.
 * - incomplete replies: MPC-v1-006 missing required DTO payable fields only.
 * - failed replies: MPC-v1-006 controlled denial/unavailable/uncertain propagation only.
 * - empty: MPC-v1-006 empty shape only; authorization and notice meanings remain unverified.
 * - lifetime: MPC-v1-007 controlled retirement/replacement/already-retired result suppression.
 * - cancellation: MPC-v1-007 caller and endpoint cancellation, including late supplied data.
 *
 * Supplied keys/categories and legacy/normalized portion coordinates are synthetic typed inputs,
 * not original native identities. Two item entries per portion describe these inputs only.
 * Charge entries/charge-ID-present variants, category absence, exact response limits, complete
 * names/selections/unit prices/merchandise-net discounts/allocations, Arabic label meanings,
 * runtime locale switches, installed input affordances, physical/controller recovery callbacks,
 * native originals/state/current Master authority/admitted RPC/restart and full OWN-06 remain
 * unbound or unverified. Literal associations and numeric money are checked in both language
 * values; nonblank Arabic wording is not a semantic translation or installed RTL assertion.
 */
class ManagerOrderComponentPresentationTest {
    @Test
    fun mpcv1LegacyItemAssociationsPreserveTheOriginalPayableSummary() = runTest {
        val expected = ManagerPresentationInputs.legacyExpected
        val supplied = legacyComponents()
        val harness = ControlledManagerGateway(response = { componentReply(expected, supplied) })
        assertComponentPage(harness.gateway.read(ManagerPresentationInputs.query()), expected, supplied)
        harness.assertTargetDispatch()
    }

    @Test
    fun mpcv1NormalizedItemAssociationsPreserveRecordedDecimalLinePayables() = runTest {
        val expected = ManagerPresentationInputs.normalizedExpected
        val supplied = suppliedItems(expected, listOf(10000L to 1400L, 9000L to 1260L))
        val harness = ControlledManagerGateway(target = expected.target,
            response = { componentReply(expected, supplied) })
        assertComponentPage(harness.gateway.read(ManagerPresentationInputs.query(expected.target)), expected, supplied)
        harness.assertTargetDispatch()
        // The public order reply supplies no remaining-due value; settlement itself is unbound.
    }

    @Test
    fun mpcv1All31PortionsKeepEverySuppliedEqualAmountItemOccurrence() = runTest {
        val expected = ManagerPresentationInputs.manyExpected
        val supplied = suppliedItems(expected, List(31) { 1000L to 140L }).reversed()
        val harness = ControlledManagerGateway(target = expected.target,
            response = { componentReply(expected, supplied) })
        assertComponentPage(harness.gateway.read(ManagerPresentationInputs.query(expected.target)), expected, supplied)
        harness.assertTargetDispatch()
        // Full occurrence comparison includes first/middle/last without imposing list order.
    }

    @Test
    fun mpcv1AbsentOptionalPortionBindingsAreNotInventedForSuppliedItems() = runTest {
        val expected = ManagerPresentationInputs.legacyExpected
        val supplied = legacyComponents().map { it.copy(portionId = null) }
        val harness = ControlledManagerGateway(response = { componentReply(expected, supplied) })
        assertComponentPage(harness.gateway.read(ManagerPresentationInputs.query()), expected, supplied)
        harness.assertTargetDispatch()
        // Nullable DTO construction is bound here; native source qualification remains separate.
    }

    @Test
    fun mpcv1ComponentsCannotReplaceMissingRequiredQuoteOrLinePayable() = runTest {
        val source = managerControlWire.encodeToJsonElement(LocalOrderReadResultV2.serializer(),
            componentResult(ManagerPresentationInputs.legacyExpected, legacyComponents())).jsonObject
        fun alteredOrder(transform: (JsonObject) -> JsonObject): JsonObject {
            val profiled = source.getValue("orders").jsonArray.single().jsonObject
            val order = profiled.getValue("order").jsonObject
            return JsonObject(source + ("orders" to JsonArray(listOf(
                JsonObject(profiled + ("order" to transform(order)))))))
        }
        val malformed = listOf(
            "missing quote payable" to alteredOrder { JsonObject(it - "payable") },
            "missing required line payable" to alteredOrder { order ->
                val lines = order.getValue("lines").jsonArray
                JsonObject(order + ("lines" to JsonArray(
                    listOf(JsonObject(lines.first().jsonObject - "payable")) + lines.drop(1))))
            },
        )
        for ((variant, body) in malformed) {
            val harness = ControlledManagerGateway(response = { managerControlData(body) })
            assertIs<LocalReadResult.Unavailable>(harness.gateway.read(ManagerPresentationInputs.query()), variant)
            harness.assertTargetDispatch()
        }
        // Missing full merchandise-net discount/allocation data has no DTO recipe here.
    }

    @Test
    fun mpcv1DeniedUnavailableAndUncertainRepliesPublishNoComponentPage() = runTest {
        for (failure in listOf(ReadFailure.AUTHORIZATION_DENIED, ReadFailure.WRITER_NOT_QUALIFIED,
            ReadFailure.STORAGE_UNCERTAIN)) {
            val harness = ControlledManagerGateway(response = { ReadReply.Unavailable(failure) })
            assertManagerNoSuccess(harness.gateway.read(ManagerPresentationInputs.query()), harness)
            harness.assertTargetDispatch()
        }
    }

    @Test
    fun mpcv1AuthorizedEmptyShapeContainsNoComponentDetailsOrMutationForms() = runTest {
        val harness = ControlledManagerGateway(target = ManagerPresentationInputs.absent,
            response = { managerControlData(LocalOrderReadResultV2(emptyList(), null)) })
        val page = assertIs<LocalReadResult.Available>(
            harness.gateway.read(ManagerPresentationInputs.query(ManagerPresentationInputs.absent))).page
        assertEquals(LocalWorkflow.ORDERS, page.workflow)
        assertTrue(page.records.isEmpty())
        assertTrue(page.forms.isEmpty())
        assertNull(page.nextCursor)
        assertNull(page.priceControls)
        assertNull(page.managerQuoteDetails)
        val notice = assertNotNull(page.notice)
        assertTrue(notice.english.isNotBlank() && notice.arabic.isNotBlank())
        harness.assertTargetDispatch()
    }

    @Test
    fun mpcv1RetiredAndReplacementLeasesSuppressDelayedComponentReplies() = runTest {
        val expected = ManagerPresentationInputs.legacyExpected
        val supplied = legacyComponents()
        for (replacement in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val harness = ControlledManagerGateway(response = {
                withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    componentReply(expected, supplied)
                }
            })
            val result = async { harness.gateway.read(ManagerPresentationInputs.query()) }
            try {
                runCurrent()
                assertTrue(entered.isCompleted)
                harness.leaseCurrent = false
                harness.attachedLease = if (replacement) ManagerPresentationInputs.cashierLease else null
                release.complete(Unit)
                runCurrent()
                assertManagerNoSuccess(result.await(), harness)
                harness.assertTargetDispatch()
            } finally {
                release.complete(Unit)
                runCurrent()
            }
        }
        val retired = ControlledManagerGateway(response = { componentReply(expected, supplied) })
        retired.leaseCurrent = false
        retired.attachedLease = null
        assertManagerNoSuccess(retired.gateway.read(ManagerPresentationInputs.query()), retired)
        // The frozen already-retired guard does not prescribe a pre-dispatch call count.
    }

    @Test
    fun mpcv1CallerAndEndpointCancellationRemainCancellationWithSuppliedComponents() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var lateDataReturned = false
        val harness = ControlledManagerGateway(response = {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                lateDataReturned = true
                componentReply(ManagerPresentationInputs.legacyExpected, legacyComponents())
            }
        })
        val result = async { harness.gateway.read(ManagerPresentationInputs.query()) }
        try {
            runCurrent()
            assertTrue(entered.isCompleted)
            result.cancel(CancellationException("Controlled component review cancellation"))
            release.complete(Unit)
            runCurrent()
            assertTrue(lateDataReturned, "The gateway encounters the deliberately late supplied data")
            assertFailsWith<CancellationException> { result.await() }
            harness.assertTargetDispatch()
        } finally {
            release.complete(Unit)
            runCurrent()
        }
        val endpointCancelled = ControlledManagerGateway(response = {
            throw CancellationException("Controlled component endpoint cancellation")
        })
        assertFailsWith<CancellationException> { endpointCancelled.gateway.read(ManagerPresentationInputs.query()) }
        endpointCancelled.assertTargetDispatch()
    }
}

private fun legacyComponents(): List<LocalOrderComponentV1> = suppliedItems(
    ManagerPresentationInputs.legacyExpected, listOf(22500L to 3150L, 7500L to 1050L))

/** Typed input construction only; literal posted amounts are never obtained by repricing. */
private fun suppliedItems(expected: BoundManagerOrder, posted: List<Pair<Long, Long>>): List<LocalOrderComponentV1> {
    val portions = ManagerPresentationInputs.projection(expected).order.selection.lines.map { it.portionId }
    require(portions.size == posted.size)
    return portions.flatMapIndexed { index, portion ->
        val prefix = "p/${portion.value}/item"
        val category = "HYPOTHETICAL-${8000 + index % 2}"
        listOf(
            LocalOrderComponentV1("$prefix/net", portion, null, "ITEM_NET", Money(Currency.EGP, posted[index].first), category),
            LocalOrderComponentV1("$prefix/tax", portion, null, "ITEM_TAX", Money(Currency.EGP, posted[index].second), category),
        )
    }
}

private fun componentResult(expected: BoundManagerOrder, supplied: List<LocalOrderComponentV1>): LocalOrderReadResultV2 {
    val projection = ManagerPresentationInputs.projection(expected)
    return LocalOrderReadResultV2(listOf(projection.copy(order = projection.order.copy(components = supplied))), null)
}

private fun componentReply(expected: BoundManagerOrder, supplied: List<LocalOrderComponentV1>): ReadReply.Data =
    managerControlData(componentResult(expected, supplied))

private data class ComponentAssociation(val key: String, val portion: DomainId?, val charge: DomainId?,
    val kind: String, val minor: Long, val category: String)

private fun assertComponentPage(result: LocalReadResult, expected: BoundManagerOrder,
    supplied: List<LocalOrderComponentV1>): LocalWorkflowPage {
    val page = assertBoundManagerPage(result, expected)
    val details = assertNotNull(page.managerQuoteDetails)
    assertEquals(expected.target, details.orderId)
    assertEquals(Revision(expected.revision), details.revision)
    val expectedAssociations = supplied.map {
        ComponentAssociation(it.key, it.portionId, it.chargeId, it.kind, it.amount.minor, it.taxCategory)
    }
    val actualAssociations = details.components.map { component ->
        val key = assertNotNull(componentLiteral(component, "Component key"))
        assertEquals(component.key, key, "The roster key and the accessible supplied key stay associated")
        val kindValue = componentValue(component, "Recorded component")
        val kind = assertNotNull(mapOf("Item net" to "ITEM_NET", "Item tax" to "ITEM_TAX",
            "Charge net" to "CHARGE_NET", "Charge tax" to "CHARGE_TAX")[kindValue.english],
            "Current English display binding must identify the supplied semantic kind")
        assertTrue(kindValue.arabic.isNotBlank())
        val amount = componentValue(component, "Recorded posted amount")
        val minor = componentMinor(amount.english)
        assertEquals(minor, componentMinor(amount.arabic), "Both language values preserve exact posted money")
        ComponentAssociation(key,
            componentLiteral(component, fieldLabel(LocalField.PORTION_ID).english)?.let(::DomainId),
            componentLiteral(component, "Charge ID")?.let(::DomainId), kind, minor,
            assertNotNull(componentLiteral(component, "Recorded tax category")))
    }
    assertEquals(expectedAssociations.groupingBy { it }.eachCount(), actualAssociations.groupingBy { it }.eachCount(),
        "Every supplied supported occurrence keeps its exact key/optional IDs/kind/amount/category; no collapse or truncation")
    return page
}

/** Current field labels select observations; they are not frozen mandatory translations. */
private fun componentLiteral(component: LocalManagerQuoteComponent, label: String): String? {
    val fields = component.values.filter { it.label.english == label }
    assertTrue(fields.size <= 1, "A supplied component field must not be duplicated")
    val value = fields.singleOrNull()?.value ?: return null
    assertEquals(value.english, value.arabic, "Literal component facts stay unchanged in both language values")
    return value.english
}

private fun componentValue(component: LocalManagerQuoteComponent, label: String): LocalText =
    component.values.single { it.label.english == label }.value

private fun componentMinor(value: String): Long = BigDecimal(value.substringBefore(' ')).movePointRight(2).longValueExact()
