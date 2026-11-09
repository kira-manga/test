@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.returnstockdisposition

import com.ukkera.pos.sales.AcceptedReturn
import com.ukkera.pos.sales.CashierEvidence
import com.ukkera.pos.sales.ReturnClaimEvidence
import com.ukkera.pos.sales.ReturnDisposition
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Exact semantic inputs from the unchanged freeze. UUIDs and times below are
 * inert bindings, not original history, employee observations or authority. */
@OptIn(ExperimentalSerializationApi::class)
internal object FrozenReturnStockDispositionInputs {
    val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    private val file = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/return-stock-disposition/fixtures.json") }
        .firstOrNull { Files.isRegularFile(it) } ?: error("Frozen return-stock-disposition v1 fixtures required")
    val frozen: JsonObject = json.parseToJsonElement(Files.readString(file)).jsonObject.also { root ->
        check(root.literal("specification_id") == "RETURN-STOCK-DISPOSITION")
        check(root.getValue("frozen_version").jsonPrimitive.int == 1 && root.literal("design_state") == "FROZEN")
        check(root.literal("execution_state") == "NOT RUN")
        check(root.getValue("fixtures").jsonArray.all { it.jsonObject.literal("state") == "NOT RUN" })
        check(root.getValue("cases").jsonArray.all { it.jsonObject.literal("state") == "NOT RUN" })
    }

    fun fixture(name: String): JsonObject = frozen.getValue("fixtures").jsonArray.map { it.jsonObject }
        .single { it.literal("id") == name }
    fun id(number: Int) = DomainId("73000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    fun operation(number: Int) = OperationId(id(number).value)
    fun instant(literal: String) = UtcInstant(Instant.parse(literal))
    val scope = BranchScope(id(1), id(2), id(3))
    val eachUnit = id(4)
    val kgUnit = id(5)

    fun quantity(fixture: JsonObject, literal: String): Quantity {
        val name = fixture.literal("id")
        val scale = when (name) {
            "CORE" -> fixture.getValue("quantity_precision").jsonPrimitive.int
            "TAX", "PACK" -> 0 // Whole EACH literals; no catalog conversion is consulted.
            "MEASURE" -> {
                check(fixture.literal("quantity_step") == "0.01")
                2
            }
            else -> error("No quantity carrier is bound for $name")
        }
        val unit = when (fixture.literal("base_unit")) {
            "EACH" -> eachUnit
            "KG" -> kgUnit
            else -> error("No unit carrier is bound")
        }
        return Quantity.parse(unit, literal, scale)
    }

    fun money(literal: String) = Money(Currency.EGP, ExactDecimal.parse(literal, 2).toScaledLongExact(2))
}

internal fun JsonObject.objectAt(name: String): JsonObject = getValue(name).jsonObject
internal fun JsonObject.literal(name: String): String = getValue(name).jsonPrimitive.content

/** DATA ONLY. This directly constructed carrier can exercise the pure scalar
 * frontier. It cannot establish an accepted return in a production source read.
 * Only CORE has a frozen guarded prior frontier; the other three carriers are
 * used for quantity-codec checks, never an assumed initial-zero stock post.
 * Price components, tender allocations and retained history are intentionally
 * absent; no storage, command authority, inspection or inventory port is supplied. */
internal class FrozenReturnStockDataCarrier(
    fixtureName: String,
    disposition: ReturnDisposition = ReturnDisposition.INSPECTED_SELLABLE,
) {
    val inputs = FrozenReturnStockDispositionInputs
    val fixture = inputs.fixture(fixtureName)
    private val coordinateBase = when (fixtureName) {
        "CORE" -> 100
        "TAX" -> 1_000
        "PACK" -> 2_000
        "MEASURE" -> 3_000
        else -> error("This supporting harness does not bind $fixtureName")
    }
    private fun id(offset: Int) = inputs.id(coordinateBase + offset)
    private fun operation(offset: Int) = inputs.operation(coordinateBase + offset)
    val actorId = id(1)
    val saleId = id(2)
    val caseId = id(3)
    val returnId = id(4)
    val portionId = id(5)
    val issueId = id(6)
    val allocationId = id(7)
    val acceptanceOperationId = operation(8)
    val acceptanceDigest = sha256("Inert stock DATA binding $fixtureName".encodeToByteArray())
    val inspection = LocalCashEvidenceV1(id(9), inputs.instant("2026-11-03T10:00:00Z"))
    val recordedAt = inputs.instant("2026-11-03T10:00:01Z")
    val acceptedLiteral: String = when (fixtureName) {
        "CORE" -> fixture.objectAt("before_stock_post").literal("current_accepted_quantity")
        "PACK" -> fixture.objectAt("current_accepted_return").literal("base_quantity")
        else -> fixture.objectAt("current_accepted_return").literal("quantity")
    }
    val acceptedQuantity = quantity(acceptedLiteral)
    val originalPayable: Money = inputs.money(when (fixtureName) {
        "CORE" -> fixture.objectAt("before_stock_post").literal("current_refund_due")
        "TAX" -> fixture.objectAt("current_accepted_return").literal("original_refund_due")
        else -> fixture.objectAt("current_accepted_return").literal("refund_due")
    })
    // CORE's earlier accepted quantity is frozen. Zero for the other carriers is
    // an unused shape coordinate, not a claim that their history was reconstructed.
    val consumedBefore = quantity(if (fixtureName == "CORE") fixture.getValue("independent_business_sequence")
        .jsonArray.map { it.jsonObject }.single { it.literal("fact") == "earlier accepted return" }.literal("quantity") else "0")
    val claimEvidence = ReturnClaimEvidence(id(10), Revision(1), Instant.parse("2026-11-01T10:00:00Z"),
        Instant.parse("2026-11-02T10:00:00Z"), null, "Synthetic pure-state binding only",
        CashierEvidence(id(11), actorId, Instant.parse("2026-11-02T10:00:00Z")))
    val accepted = AcceptedReturn(returnId, inputs.scope, saleId, caseId, portionId, acceptedQuantity, consumedBefore,
        actorId, claimEvidence, disposition, CashierEvidence(inspection.evidenceId, actorId, inspection.occurredAt.value),
        originalPayable, emptyList(), emptyList(), Revision(0))

    fun quantity(literal: String) = inputs.quantity(fixture, literal)

    private fun frozenPostedAssertion(before: LocalReturnStockStateV1?): Quantity {
        check(fixture.literal("id") == "CORE") { "UNBOUND: exact guarded prior-posted frontier is not frozen for this carrier" }
        val remainder = when {
            before == null -> fixture.objectAt("before_stock_post").literal("current_remaining_disposition_quantity")
            before.postedQuantity == quantity("1") -> fixture.objectAt("after_one_unit_sellable_post")
                .literal("remaining_disposition_quantity")
            before.postedQuantity == acceptedQuantity -> fixture.objectAt("after_all_two_units_sellable_post")
                .literal("remaining_disposition_quantity")
            else -> error("UNBOUND: no frozen CORE predecessor frontier")
        }
        // Derive only from the unchanged disposition conservation oracle, never
        // from inventory pool quantity or original unaccepted return entitlement.
        return acceptedQuantity - quantity(remainder)
    }

    fun request(
        quantity: Quantity = acceptedQuantity,
        before: LocalReturnStockStateV1? = null,
        postingNumber: Int = 1,
        custody: LocalReturnStockCustodyV1 = LocalReturnStockCustodyV1.RELEASED,
    ) = LocalReturnStockRequestV1(saleId = saleId, caseId = caseId, returnId = returnId,
        expectedCaseRevision = Revision(1), expectedDispositionRevision = before?.revision ?: Revision(0),
        acceptedQuantity = acceptedQuantity, expectedPostedQuantity = frozenPostedAssertion(before),
        issueId = issueId, expectedIssueRevision = Revision(1), restorationId = id(100 + postingNumber),
        selections = listOf(LocalReturnStockSelectionV1(allocationId, quantity, custody)), inspectionEvidence = inspection)

    fun dataDigest(state: LocalReturnStockStateV1?): PayloadDigest? = state?.let {
        sha256(inputs.json.encodeToString(LocalReturnStockStateV1.serializer(), it).encodeToByteArray())
    }

    fun post(
        request: LocalReturnStockRequestV1,
        before: LocalReturnStockStateV1? = null,
        operationNumber: Int = 1,
        beforeDigest: PayloadDigest? = dataDigest(before),
    ): LocalReturnStockPostingV1 = returnStockPostingValue(inputs.scope, operation(200 + operationNumber),
        sha256(inputs.json.encodeToString(LocalReturnStockRequestV1.serializer(), request).encodeToByteArray()),
        actorId, recordedAt, request, accepted, acceptanceOperationId, acceptanceDigest, before, beforeDigest)
}
