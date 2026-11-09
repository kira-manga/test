package com.ukkera.pos.app.gateway.independent.pay10classificationcorrection

import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.gateway.GatewayFailure
import com.ukkera.pos.app.gateway.savedPaymentCorrectionRequest
import com.ukkera.pos.app.gateway.validatePaymentCorrectionRecorded
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalTenderSelectionV1
import com.ukkera.pos.sales.store.LocalTenderViewV1
import com.ukkera.pos.types.Revision
import kotlin.test.*

/**
 * Frozen PAY-10 CARD -> CASH typed-result SUPPORT only. A policy revision
 * change on the original CARD cannot substitute for the requested CASH result.
 * This does not freeze or qualify an alternate external-to-external request's
 * classification-equivalence policy, native current authority or durable commit.
 */
class Pay10RecordedClassificationParitySupportingTest {
    @Test
    fun supportOriginalCardAtAnotherPolicyRevisionCannotReplaceTheFrozenCashCorrectionResult() {
        val input = Pay10GatewayInputs
        val original = input.staged()
        assertEquals(LocalTenderSelectionV1.Cash, savedPaymentCorrectionRequest(original).correctedTender)
        val sameCardAtAnotherRevision = LocalTenderViewV1("CARD", input.card.tenderId, Revision(2))
        assertEquals(input.card.kind, sameCardAtAnotherRevision.kind)
        assertEquals(input.card.tenderId, sameCardAtAnotherRevision.tenderId)
        assertNotEquals(input.card, sameCardAtAnotherRevision)
        val recorded = input.recorded()
        val payload = recorded.result.copyBytes().decodeToString()
        val authoredField = "\"correctedTender\":" + input.wire.encodeToString(
            LocalTenderViewV1.serializer(), input.correction.correctedTender)
        val malformedField = "\"correctedTender\":" + input.wire.encodeToString(
            LocalTenderViewV1.serializer(), sameCardAtAnotherRevision)
        assertEquals(1, payload.split(authoredField).size - 1)
        // Alter only the serialized result: the DTO initializer itself now
        // rejects this contradiction, so a DTO copy would skip gateway validation.
        val malformed = MutationReply.Recorded(recorded.operationId, recorded.originalActorId,
            recorded.committed, recorded.code, StoredBytes(payload.replace(authoredField, malformedField).encodeToByteArray()),
            recorded.authorityEpoch, recorded.sequence, recorded.recordedAt, recorded.replayed)
        assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, assertFailsWith<GatewayFailure> {
            validatePaymentCorrectionRecorded(original, malformed)
        }.problem)
        assertEquals(input.request, savedPaymentCorrectionRequest(original))
        assertEquals(input.owner, original.owner)
        assertEquals(input.operation, original.operationId)
        assertNull(original.recorded)
        // Retained semantic target/result mismatch only; no accepting source,
        // native writer, real transport or whole frozen case is exercised.
    }
}
