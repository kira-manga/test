package com.ukkera.pos.app.gateway.independent.manageroriginalquotev1

import com.ukkera.pos.app.gateway.independent.managerorderpresentation.managerControlBody
import com.ukkera.pos.app.gateway.independent.managerorderreviewv1.ControlledManagerReviewView
import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Frozen controlled slices only; no actual Master, persistence, admitted RPC or restart result. */
class ManagerOriginalQuoteReadSlicesTest {
    @Test
    fun u002StrictSelectorRejectsEveryBoundInvalidInputShape() {
        val id = OriginalQuoteFrozen.exclusive.text("target_order_id")
        val valid = "{\"orderId\":\"" + id + "\"}"
        val invalid = mutableListOf("", "{}", "{\"orderId\":null}", "{\"orderId\":\"not-a-uuid\"}",
            "[" + valid + "," + valid + "]", "{\"orderId\":\"" + id + "\",\"orderId\":\"" + id + "\"}",
            "{\"orderId\":\"" + id + "\",\"orderId\":\"" + OriginalQuoteFrozen.scale.text("target_order_id") + "\"}",
            valid.dropLast(1), "{\"orderId\":{\"nested\":{\"target\":\"" + id + "\"}}}",
            valid + " ".repeat(2049 - valid.length))
        val callerFields = mapOf("employeeId" to OriginalQuoteFrozen.manager.value,
            "ownerId" to OriginalQuoteFrozen.owner.value, "branchId" to OriginalQuoteFrozen.scope.branchId.value,
            "role" to "MANAGER", "permission" to "ORDER_REASSIGN", "recipient" to OriginalQuoteFrozen.creator.value,
            "revision" to OriginalQuoteFrozen.exclusive.text("stored_order_business_revision"))
        for ((field, value) in callerFields)
            invalid += valid.dropLast(1) + ",\"" + field + "\":\"" + value + "\"}"
        val handler = LocalManagerOriginalQuoteReadHandler()
        for (body in invalid) assertFailsWith<IllegalArgumentException>("Invalid selector must not normalize: " + body.take(32)) {
            handler.normalize(StoredBytes(body.encodeToByteArray()))
        }
        assertEquals(OriginalQuoteFrozen.quoteKey, handler.key)
    }

    @Test
    fun u001U005CompleteExclusiveQuoteCodecPreservesFrozenOriginalEconomicsForBothProfiles() {
        for (profile in LocalOrderStockProfileV2.entries) {
            val input = LocalManagerOriginalQuoteResult(listOf(OriginalQuoteFrozen.entry(profile = profile)))
            val actual = LocalManagerOriginalQuoteCodec.decode(managerControlBody(input, LocalManagerOriginalQuoteResult.serializer()), OriginalQuoteFrozen.query())
            assertEquals(1, actual.orders.size)
            assertEquals(profile, actual.orders.single().target.stockProfile)
            assertFrozenQuote(actual.orders.single())
        }
    }

    @Test
    fun u010WeightedQuoteCodecKeepsExactDimensionsAndRevisionsAboveTwoToTheFiftyThird() {
        for (profile in LocalOrderStockProfileV2.entries) {
            val input = LocalManagerOriginalQuoteResult(listOf(OriginalQuoteFrozen.entry(OriginalQuoteFrozen.scale, profile)))
            val result = LocalManagerOriginalQuoteCodec.decode(managerControlBody(input, LocalManagerOriginalQuoteResult.serializer()),
                OriginalQuoteFrozen.query(OriginalQuoteFrozen.scale))
            assertFrozenQuote(result.orders.single(), OriginalQuoteFrozen.scale)
            val roundTrip = StoredPricingSnapshotCodec.decode(result.orders.single().quoteSnapshot)
            assertEquals(OriginalQuoteFrozen.scale.text("stored_snapshot_revision"), roundTrip.revision.value.toString())
            assertEquals(OriginalQuoteFrozen.scale.text("stored_order_business_revision"), result.orders.single().target.order.revision.value.toString())
        }
    }

    @Test
    fun u006U007ChangedIdentityAndContradictoryIncompleteQuoteFieldsNeverDecodeAsPrivateOrEmptySuccess() {
        val original = controlElement(OriginalQuoteFrozen.entry(), LocalManagerOriginalQuote.serializer()).jsonObject
        for (corruption in OriginalQuoteFrozen.corruptions.filter { it.text("id") != "C-UNJOINED" }) {
            val invalid = corruptQuoteEntry(original, corruption)
            val body = managerControlBody(buildJsonObject { put("orders", JsonArray(listOf(invalid))) }, JsonObject.serializer())
            assertFailsWith<IllegalArgumentException>(corruption.text("id") + " must refuse the complete response") {
                LocalManagerOriginalQuoteCodec.decode(body, OriginalQuoteFrozen.query())
            }
        }
    }

    @Test
    fun u001U005ProductionHandlerRetainsExactTargetBusinessRevisionPurposeAndOriginalCreator() = runTest {
        val view = controlledQuoteSource().view()
        val read = prepareQuote(view)
        val requirement = read.prepared.requirements.single()
        assertEquals(Permission(SecurityAction.ORDER_REASSIGN, PermissionVerb.PERFORM), requirement.permission)
        assertEquals(OriginalQuoteFrozen.scope, requirement.scope)
        assertEquals(OwnerConstraint.Unrestricted, requirement.owner)
        val binding = assertNotNull(requirement.binding)
        assertEquals(OriginalQuoteFrozen.exclusive.id("target_order_id"), binding.documentId)
        assertEquals(Revision(OriginalQuoteFrozen.exclusive.long("stored_order_business_revision")), binding.revision)
        assertEquals(read.context.semanticDigest, binding.payloadDigest, "Computed wiring digest is forwarded; independent digest vector remains unbound")
        assertEquals("order.manager-review", binding.reasonCode)
        assertNull(binding.operationId)
        val principal = QuoteControlAuthority.principal()
        val payload = read.prepared.project(listOf(QuoteControlAuthority.proof(principal, requirement)), OriginalQuoteFrozen.observedAt)
        val actual = LocalManagerOriginalQuoteCodec.decode(payload, read.query)
        assertFrozenQuote(actual.orders.single())
        assertTrue(view.retainedCalls.isEmpty(), "No original-operation recovery or acknowledgement")
    }

    @Test
    fun u003CurrentControlledDenialsAndWrongProofBindingCannotProjectPrivateQuote() = runTest {
        val read = prepareQuote()
        val principal = QuoteControlAuthority.principal()
        val requirement = read.prepared.requirements.single()
        val variants = listOf(
            emptyList(),
            listOf(OriginalQuoteFrozen.hint(permission = Permission(SecurityAction.ORDER_READ, PermissionVerb.PERFORM))),
            listOf(OriginalQuoteFrozen.hint(permission = Permission(SecurityAction.ORDER_HANDOFF, PermissionVerb.PERFORM))),
            listOf(OriginalQuoteFrozen.hint(permission = Permission(SecurityAction.ORDER_REASSIGN, PermissionVerb.REQUEST))),
            listOf(OriginalQuoteFrozen.hint(target = OriginalQuoteFrozen.scale.id("target_order_id"))),
            listOf(OriginalQuoteFrozen.hint(grantScope = BranchScope(OriginalQuoteFrozen.scope.organizationId,
                OriginalQuoteFrozen.scope.companyId, OriginalQuoteFrozen.ids.id("other_branch")))),
        )
        for (grants in variants) {
            assertIs<AuthorizationDecision.Denied>(AuthorizationEvaluator.evaluate(principal, QuoteControlAuthority.snapshot(principal, grants), requirement))
            assertFailsWith<IllegalStateException> { read.prepared.project(emptyList(), OriginalQuoteFrozen.observedAt) }
        }
        val binding = assertNotNull(requirement.binding)
        val wrongRequirement = requirement.copy(binding = binding.copy(documentId = OriginalQuoteFrozen.scale.id("target_order_id")))
        val wrongProof = QuoteControlAuthority.proof(principal, wrongRequirement)
        assertFailsWith<IllegalStateException> { read.prepared.project(listOf(wrongProof), OriginalQuoteFrozen.observedAt) }
    }

    @Test
    fun u004AuthorizedControlledRetailAbsenceKeepsRevisionZeroWhilePresentBrokenSourcesRefuse() = runTest {
        val principal = QuoteControlAuthority.principal()
        for (fixture in listOf(OriginalQuoteFrozen.declaration("F-ABSENT"), OriginalQuoteFrozen.declaration("F-FOODONLY"))) {
            val read = prepareQuote(ControlledManagerReviewView(scope = OriginalQuoteFrozen.scope), fixture.id("order_id"))
            val requirement = read.prepared.requirements.single()
            assertEquals(Revision(fixture.long("authorization_revision".takeIf { it in fixture } ?: "retail_authorization_revision")),
                assertNotNull(requirement.binding).revision)
            assertFailsWith<IllegalStateException> { read.prepared.project(emptyList(), OriginalQuoteFrozen.observedAt) }
            val payload = read.prepared.project(listOf(QuoteControlAuthority.proof(principal, requirement)), OriginalQuoteFrozen.observedAt)
            assertTrue(LocalManagerOriginalQuoteCodec.decode(payload, read.query).orders.isEmpty())
        }
        val source = controlledQuoteSource()
        assertFailsWith<IllegalStateException> { prepareQuote(source.view(includeContext = false)) }
        val missingCreator = source.order.changed("state", source.order.obj("state").without("originalCreatorId"))
        assertFailsWith<IllegalArgumentException> { prepareQuote(source.view(orderOverride = missingCreator)) }
        val conflicting = OriginalQuoteFrozen.corruptions.single { it.text("id") == "C-SNAPSHOT-ID" }
        val changedSnapshot = OriginalQuoteFrozen.wire.parseToJsonElement(source.order.obj("state").text("quote")).jsonObject
            .changed("snapshotId", JsonPrimitive(conflicting.text("replacement")))
        val changedOrder = source.order.changed("state", source.order.obj("state").changed("quote", JsonPrimitive(changedSnapshot.toString())))
        assertFailsWith<IllegalStateException> { prepareQuote(source.view(orderOverride = changedOrder)) }
        // The FOOD-only declaration binds retail absence, not a genuine populated FOOD source.
    }
}

internal fun corruptQuoteEntry(entry: JsonObject, corruption: JsonObject): JsonObject {
    val id = corruption.text("id")
    if (id == "C-TARGET-ID") return entry.changed("target", entry.obj("target").changed("order",
        entry.obj("target").obj("order").changed("orderId", JsonPrimitive(corruption.text("replacement")))))
    if (id == "C-INCOMPLETE") return entry.changed("quoteSnapshot", JsonNull)
    if (id == "C-MISSING-CREATOR") return entry.without("originalCreatorId")
    if (id == "C-PROFILE") return entry.changed("target", entry.obj("target").changed("stockProfile", JsonPrimitive("UNSUPPORTED")))
    var quote = OriginalQuoteFrozen.wire.parseToJsonElement(entry.text("quoteSnapshot")).jsonObject
    fun money(minor: String) = buildJsonObject { put("currency", "EGP"); put("minor", minor) }
    quote = when (id) {
        "C-SNAPSHOT-ID" -> quote.changed("snapshotId", JsonPrimitive(corruption.text("replacement")))
        "C-QUOTE-REV" -> quote.changed("revision", JsonPrimitive(corruption.text("replacement")))
        "C-CURRENCY" -> quote.changed("currency", JsonPrimitive(corruption.text("replacement")))
        "C-FINAL" -> quote.changed("payable", money(corruption.text("replacement")))
        "C-BEFORE" -> quote.changed("beforeDiscountPayable", money(corruption.text("replacement")))
        "C-DISCOUNT" -> quote.changed("discounts", JsonArray(quote.array("discounts").mapIndexed { index, value ->
            if (index == 0) value.jsonObject.changed("advertisedPayableReduction", money(corruption.text("replacement"))) else value
        }))
        "C-QUANTITY" -> quote.changed("lines", JsonArray(quote.array("lines").mapIndexed { index, value ->
            if (index == 0) value.jsonObject.changed("quantity", controlElement(OriginalQuoteFrozen.quantity(
                OriginalQuoteFrozen.exclusive.array("portions").first().jsonObject.changed("quantity_exact_decimal",
                    JsonPrimitive(corruption.text("replacement")))), Quantity.serializer())) else value
        }))
        "C-DUPLICATE-PORTION" -> quote.changed("lines", JsonArray(listOf(quote.array("lines").first(), quote.array("lines").first())))
        "C-MISSING-PORTION" -> quote.changed("lines", JsonArray(emptyList()))
        "C-POSTED-TAX" -> quote.changed("components", JsonArray(quote.array("components").map { value ->
            if (value.jsonObject.text("kind") == "ITEM_TAX") value.jsonObject.changed("posted", money(corruption.text("replacement"))) else value
        }))
        else -> error("Unbound codec corruption: " + id)
    }
    return entry.changed("quoteSnapshot", JsonPrimitive(quote.toString()))
}
