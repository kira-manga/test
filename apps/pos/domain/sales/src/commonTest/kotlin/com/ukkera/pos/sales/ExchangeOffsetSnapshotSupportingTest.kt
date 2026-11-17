package com.ukkera.pos.sales

import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** SELF-AUTHORED, NOT independent. exchange-offset-snapshot-supporting-v1.0.1.
 * Serialization/link controls only; these do not qualify native history or authority. */
class ExchangeOffsetSnapshotSupportingTest {
    private class Cut(val fixture: SalesFixture, val accepted: ReturnAccepted, val source: RetailFixture,
        val replacement: RetailFixture, val after: ExchangeOffsetApplied)

    private suspend fun offset(mixedCash: Boolean = false): Cut {
        val f = SalesFixture()
        val source = f.retail(f.quote("100"))
        if (mixedCash) { source.cash(egp(60)); source.cash(egp(40)) } else source.cash(egp(100))
        val accepted = f.accept(source.finalize().value.entitlement, f.quantity(1))
        val replacement = f.retail(f.quote("100"))
        val value = accepted.acceptedReturn
        val slices = value.tenderObligations.map { ExchangeOffsetSlice(f.nextId(), it.originalPaymentId, it.amount) }
        val after = Exchanges.applyOffset(f.command(SecurityAction.REFUND_RECORD, value.id, value.revision, egp(100)), value,
            value.revision, replacement.collection, replacement.collection.revision, f.nextId(), slices, f.evidence()).value
        return Cut(f, accepted, source, replacement, after)
    }

    private fun acceptedBytes(cut: Cut): String = SalesSnapshotCodec.encodeAcceptedReturn(cut.after.acceptedReturn,
        cut.accepted.entitlement, cut.accepted.claim)
    private fun collectionBytes(cut: Cut): String = SalesSnapshotCodec.encodeCollection(cut.after.collection)
    private fun decodeAccepted(cut: Cut, text: String): AcceptedReturn = SalesSnapshotCodec.decodeAcceptedReturn(text,
        SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, cut.after.acceptedReturn.scope, cut.after.acceptedReturn.id,
            cut.after.acceptedReturn.revision), cut.accepted.entitlement, cut.accepted.claim)
    private fun decodeCollection(cut: Cut, text: String): CollectionLock = SalesSnapshotCodec.decodeCollection(text,
        SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, cut.after.collection.scope, cut.after.collection.id, cut.after.collection.revision),
        cut.replacement.order, cut.replacement.session)

    private fun node(text: String, vararg path: String): JsonElement = path.fold(Json.parseToJsonElement(text)) { at, key ->
        if (at is JsonArray) at[key.toInt()] else at.jsonObject.getValue(key)
    }
    private fun mutate(text: String, value: JsonElement, vararg path: String): String {
        fun change(at: JsonElement, index: Int): JsonElement {
            if (index == path.size) return value
            val key = path[index]
            return if (at is JsonArray) JsonArray(at.mapIndexed { i, item -> if (i == key.toInt()) change(item, index + 1) else item })
            else {
                check(key in at.jsonObject)
                JsonObject(at.jsonObject.mapValues { (name, item) -> if (name == key) change(item, index + 1) else item })
            }
        }
        return change(Json.parseToJsonElement(text), 0).toString()
    }

    @Test fun offsetReturnRoundTripsWithoutPhysicalRefundObservation() = runTest {
        val cut = offset(); val encoded = acceptedBytes(cut); val after = decodeAccepted(cut, encoded)
        assertEquals(egp(0), after.due); assertEquals(egp(100), after.refunded)
        assertNull(after.attempts.single().latestObservation)
        assertEquals(cut.after.acceptedReturn.attempts.single().refund, after.attempts.single().refund)
        assertEquals(encoded, SalesSnapshotCodec.encodeAcceptedReturn(after, cut.accepted.entitlement, cut.accepted.claim))
        SalesSnapshotLinks.requireAcceptedReturn(after, cut.accepted.entitlement, cut.accepted.claim)
    }

    @Test fun offsetCollectionRoundTripsWithoutCashOrExternalObservation() = runTest {
        val cut = offset(); val encoded = collectionBytes(cut); val after = decodeCollection(cut, encoded)
        assertEquals(egp(100), after.collected); assertEquals(egp(0), after.remainingPayable)
        assertNull(after.attempts.single().latestObservation)
        assertEquals(cut.after.collection.attempts.single().payment, after.attempts.single().payment)
        assertEquals(encoded, SalesSnapshotCodec.encodeCollection(after))
        SalesSnapshotLinks.requireCollection(after, cut.replacement.order, cut.replacement.session)
    }

    @Test fun offsetRefundCannotChangeItsAttestationActor() = runTest {
        val cut = offset(); val encoded = acceptedBytes(cut)
        assertFailsWith<SalesSnapshotRejectedException> { decodeAccepted(cut, mutate(encoded, JsonPrimitive(cut.fixture.other.value),
            "state", "attempts", "0", "refund", "attestation", "actorId")) }
    }

    @Test fun offsetPaymentCannotChangeItsAttestationActor() = runTest {
        val cut = offset(); val encoded = collectionBytes(cut)
        assertFailsWith<SalesSnapshotRejectedException> { decodeCollection(cut, mutate(encoded, JsonPrimitive(cut.fixture.other.value),
            "state", "attempts", "0", "payment", "attestation", "actorId")) }
    }

    @Test fun offsetRecordsCannotAcquirePhysicalObservations() = runTest {
        val cut = offset(); val returnedBytes = acceptedBytes(cut); val collectedBytes = collectionBytes(cut)
        val value = cut.accepted.acceptedReturn
        val physical = cut.fixture.payRefund(value, cut.fixture.openCash(egp(100)),
            value.tenderObligations.single().originalPaymentId, egp(100)).acceptedReturn
        val physicalBytes = SalesSnapshotCodec.encodeAcceptedReturn(physical, cut.accepted.entitlement, cut.accepted.claim)
        val refundObservation = JsonObject(node(physicalBytes, "state", "attempts", "0", "latestObservation").jsonObject +
            ("evidence" to node(returnedBytes, "state", "attempts", "0", "refund", "attestation")))
        assertFailsWith<SalesSnapshotRejectedException> { decodeAccepted(cut, mutate(returnedBytes, refundObservation,
            "state", "attempts", "0", "latestObservation")) }
        val paymentObservation = JsonObject(node(SalesSnapshotCodec.encodeCollection(cut.source.collection),
            "state", "attempts", "0", "latestObservation").jsonObject +
            ("evidence" to node(collectedBytes, "state", "attempts", "0", "payment", "attestation")))
        assertFailsWith<SalesSnapshotRejectedException> { decodeCollection(cut, mutate(collectedBytes, paymentObservation,
            "state", "attempts", "0", "latestObservation")) }
    }

    @Test fun offsetPaymentCannotInventCashHandled() = runTest {
        val cut = offset(); val encoded = collectionBytes(cut)
        val amount = node(SalesSnapshotCodec.encodeCollection(cut.source.collection), "state", "attempts", "0", "payment", "cashReceived")
        assertNotEquals(JsonNull, amount)
        assertFailsWith<SalesSnapshotRejectedException> { decodeCollection(cut, mutate(encoded, amount,
            "state", "attempts", "0", "payment", "cashReceived")) }
    }

    @Test fun ordinaryCashStillRequiresItsActualPhysicalObservation() = runTest {
        val cut = offset()
        val source = cut.source
        val collection = SalesSnapshotCodec.encodeCollection(source.collection)
        assertFailsWith<SalesSnapshotRejectedException> { SalesSnapshotCodec.decodeCollection(mutate(collection, JsonNull,
            "state", "attempts", "0", "latestObservation"), SalesSnapshotBinding(SalesSnapshotKind.COLLECTION,
            source.collection.scope, source.collection.id, source.collection.revision), source.order, source.session) }
        val value = cut.accepted.acceptedReturn
        val refunded = cut.fixture.payRefund(value, cut.fixture.openCash(egp(100)), value.tenderObligations.single().originalPaymentId, egp(100)).acceptedReturn
        val bytes = SalesSnapshotCodec.encodeAcceptedReturn(refunded, cut.accepted.entitlement, cut.accepted.claim)
        assertFailsWith<SalesSnapshotRejectedException> { SalesSnapshotCodec.decodeAcceptedReturn(mutate(bytes, JsonNull,
            "state", "attempts", "0", "latestObservation"), SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN,
            refunded.scope, refunded.id, refunded.revision), cut.accepted.entitlement, cut.accepted.claim) }
    }

    @Test fun offsetRecordingOperationMustRemainTheOriginalOffsetOperation() = runTest {
        val cut = offset(); val wrong = OperationId(cut.fixture.nextId().value)
        val returnedBytes = acceptedBytes(cut); val collectedBytes = collectionBytes(cut)
        assertFailsWith<SalesSnapshotRejectedException> { decodeAccepted(cut, mutate(returnedBytes, JsonPrimitive(wrong.value),
            "state", "attempts", "0", "refund", "recordingOperationId")) }
        assertFailsWith<SalesSnapshotRejectedException> { decodeCollection(cut, mutate(collectedBytes, JsonPrimitive(wrong.value),
            "state", "attempts", "0", "payment", "recordingOperationId")) }
    }

    @Test fun multipleOriginalObligationsShareOneExactOffsetCommandAndRevision() = runTest {
        val cut = offset(mixedCash = true)
        assertEquals(2, cut.after.acceptedReturn.attempts.size)
        assertEquals(1, cut.after.acceptedReturn.attempts.map { it.originalOperationId }.distinct().size)
        assertEquals(2, decodeAccepted(cut, acceptedBytes(cut)).attempts.size)
        assertEquals(2, decodeCollection(cut, collectionBytes(cut)).attempts.size)
        assertEquals(egp(0), cut.after.acceptedReturn.due)
    }

    @Test fun replacementReturnKeepsTheOriginalPhysicalSourceTender() = runTest {
        val cut = offset()
        cut.replacement.collection = cut.after.collection
        val sale = cut.replacement.finalize().value
        val returned = cut.fixture.accept(sale.entitlement, cut.fixture.quantity(1))
        assertEquals(Tender.Cash, returned.acceptedReturn.tenderObligations.single().tender)
        val encoded = SalesSnapshotCodec.encodeAcceptedReturn(returned.acceptedReturn, returned.entitlement, returned.claim)
        val after = SalesSnapshotCodec.decodeAcceptedReturn(encoded, SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN,
            returned.acceptedReturn.scope, returned.acceptedReturn.id, returned.acceptedReturn.revision), returned.entitlement, returned.claim)
        assertEquals(egp(100), after.due)
        assertEquals(encoded, SalesSnapshotCodec.encodeAcceptedReturn(after, returned.entitlement, returned.claim))
    }

    @Test fun sharedOffsetCommandCannotMixDifferentExchangeIdentities() = runTest {
        val cut = offset(mixedCash = true)
        val encoded = collectionBytes(cut); val wrong = JsonPrimitive(cut.fixture.nextId().value)
        val attempt = mutate(encoded, wrong, "state", "attempts", "1", "tender", "exchangeId")
        val both = mutate(attempt, wrong, "state", "attempts", "1", "payment", "tender", "exchangeId")
        assertFailsWith<SalesSnapshotRejectedException> { decodeCollection(cut, both) }
    }

    @Test fun ordinaryPhysicalAttemptsStillCannotReuseAnOriginalOperation() = runTest {
        val cut = offset(mixedCash = true); val source = cut.source
        val encoded = SalesSnapshotCodec.encodeCollection(source.collection)
        val first = node(encoded, "state", "attempts", "0", "originalOperationId")
        val attempt = mutate(encoded, first, "state", "attempts", "1", "originalOperationId")
        val both = mutate(attempt, first, "state", "attempts", "1", "payment", "originalOperationId")
        assertFailsWith<SalesSnapshotRejectedException> { SalesSnapshotCodec.decodeCollection(both,
            SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, source.collection.scope, source.collection.id,
                source.collection.revision), source.order, source.session) }
    }
}
