package com.ukkera.pos.sales.store.independent.returnsourceomittedexpectation

import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.store.LocalReturnCaseCommandCodecV1
import com.ukkera.pos.sales.store.LocalReturnCaseProfileV1
import com.ukkera.pos.sales.store.LocalReturnCaseRequestV1
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull

/** RSE-v1-01 only. Codec results do not establish a source or protected execution. */
class ReturnSourceOmittedExpectationFrozenCodecSlicesTest {
    private val frozen = ReturnSourceOmittedExpectationFrozenInputs

    @Test
    fun rse_v1_01_a_exactCanonicalOmissionAndOperationBody() {
        val before = frozen.bytes
        val decoded = assertIs<LocalReturnCaseRequestV1.Qualify>(LocalReturnCaseCommandCodecV1.decode(before))
        assertEquals(frozen.request, decoded)
        assertNull(decoded.expectedEntitlementRevision)
        assertEquals(frozen.caseRevision, decoded.expectedCaseRevision)
        assertContentEquals(frozen.body.encodeToByteArray(), LocalReturnCaseCommandCodecV1.encode(decoded).copyBytes())
        assertContentEquals(before.copyBytes(), LocalReturnCaseCommandCodecV1.normalize(before).copyBytes())
        val command = LocalReturnCaseCommandCodecV1.command(frozen.operationId, decoded)
        assertEquals(frozen.operationId, command.operationId)
        assertEquals(LocalReturnCaseProfileV1.COMMAND, command.command)
        assertContentEquals(before.copyBytes(), command.payload.copyBytes())
        assertContentEquals(frozen.body.encodeToByteArray(), before.copyBytes())
    }

    @Test
    fun rse_v1_01_b_explicitNullIsNotCanonicalOmission() {
        assertNoncanonical(frozen.body.dropLast(1) + ",\"expectedEntitlementRevision\":null}", "RSE-v1-01-b")
    }

    @Test
    fun rse_v1_01_c_numericCaseRevisionIsRefusedIndependently() {
        assertNoncanonical(frozen.body.replace("\"expectedCaseRevision\":\"11\"", "\"expectedCaseRevision\":11"),
            "RSE-v1-01-c case")
    }

    @Test
    fun rse_v1_01_c_numericEntitlementRevisionIsRefusedIndependently() {
        assertNoncanonical(frozen.body.dropLast(1) + ",\"expectedEntitlementRevision\":7}", "RSE-v1-01-c entitlement")
    }

    private fun assertNoncanonical(body: String, label: String) {
        val original = frozen.bytes
        val input = StoredBytes(body.encodeToByteArray())
        assertFails(label + " actual decoder") { LocalReturnCaseCommandCodecV1.decode(input) }
        assertFails(label + " actual normalizer") { LocalReturnCaseCommandCodecV1.normalize(input) }
        assertContentEquals(body.encodeToByteArray(), input.copyBytes(), label + " input unchanged")
        assertContentEquals(frozen.body.encodeToByteArray(), original.copyBytes(), label + " canonical original unchanged")
        assertNull(assertIs<LocalReturnCaseRequestV1.Qualify>(LocalReturnCaseCommandCodecV1.decode(original))
            .expectedEntitlementRevision)
        // No public input-error code or native business-state observation is invented.
    }
}
