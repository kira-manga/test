package com.ukkera.pos.database.independent.approvalretirementnoadmission

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalActionApprovalIssueResult
import com.ukkera.pos.database.LocalActionApprovalNoAdmissionReason
import com.ukkera.pos.database.LocalActionApprovalPreviewResult
import com.ukkera.pos.database.LocalActionApprovalRetireResult
import com.ukkera.pos.database.LocalActionApprovalRetirementAttempt
import com.ukkera.pos.database.LocalActionApprovalStatus
import com.ukkera.pos.database.LocalMasterApprovalClient
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** ARNA-01/04/06 representation/API slices only; no durable or authentic endpoint evidence. */
class ApprovalRetirementNoAdmissionRepresentationTest {
    @Test fun capturedInvocationRetainsTheExactFrozenDiscountBodyAndConfirmationContext() {
        val binding = ArnaV1Inputs.frozen.getValue("binding_A").jsonObject
        val input = binding.getValue("unapproved_command").jsonObject
        val preview = binding.getValue("confirmed_preview").jsonObject
        assertEquals(7L, input.getValue("expected_order_revision").jsonPrimitive.long)
        assertEquals(10_000L, input.getValue("gross_minor").jsonPrimitive.long)
        assertEquals(1_000L, input.getValue("requested_discount_minor").jsonPrimitive.long)
        assertEquals(9_000L, preview.getValue("prospective_net_minor").jsonPrimitive.long)
        assertEquals("absent", input.getValue("approval_proof_in_unapproved_command").jsonPrimitive.content)
        val request = ArnaV1Inputs.request()
        val originalBytes = request.payload.copyBytes()
        val attempt = ArnaV1Inputs.attempt(request = request)
        assertEquals(ArnaV1Inputs.id("I1"), attempt.attemptId)
        assertEquals(2L, attempt.sequence)
        assertEquals(ArnaV1Inputs.id("approval-A"), attempt.approvalId)
        assertEquals(request.operationId, attempt.operationId)
        assertEquals(request.command, attempt.command)
        assertEquals(sha256(originalBytes), attempt.originalPayloadDigest)
        assertEquals(ArnaV1Inputs.confirmation(), attempt.confirmationDigest)
        assertTrue(attempt.matches(request))
        assertContentEquals(originalBytes, request.payload.copyBytes())
    }

    @Test fun exactBusinessMatchRejectsChangedOperationCommandPayloadOrAttachedApproval() {
        val request = ArnaV1Inputs.request()
        val attempt = ArnaV1Inputs.attempt(request = request)
        val mismatches = listOf(
            MutationRequest(ArnaV1Inputs.request(otherBinding = true).operationId, request.command, request.payload),
            MutationRequest(request.operationId, CommandKey("qualification.arna.other", 1), request.payload),
            MutationRequest(request.operationId, request.command.copy(version = 2), request.payload),
            MutationRequest(request.operationId, request.command, ArnaV1Inputs.commandBody(discountMinor = 1_001)),
            MutationRequest(request.operationId, request.command, ArnaV1Inputs.commandBody(otherTarget = true)),
            MutationRequest(request.operationId, request.command, request.payload, ArnaV1Inputs.id("approval-A")),
        )
        mismatches.forEach { assertFalse(attempt.matches(it)) }
        assertTrue(attempt.matches(MutationRequest(request.operationId, request.command,
            StoredBytes(request.payload.copyBytes()))))
    }

    @Test fun equalBusinessCoordinatesDoNotSupplyInvocationApprovalConfirmationOrResponseOrigin() {
        val request = ArnaV1Inputs.request()
        val waiting = ArnaV1Inputs.attempt(request = request)
        val prior = waiting.copy(attemptId = ArnaV1Inputs.id("I0"), sequence = 1)
        val next = waiting.copy(attemptId = ArnaV1Inputs.id("I2"), sequence = 3)
        val changed = listOf(prior, next, waiting.copy(approvalId = ArnaV1Inputs.id("approval-B")),
            waiting.copy(confirmationDigest = ArnaV1Inputs.confirmation(orderRevision = 8)),
            waiting.copy(confirmationDigest = ArnaV1Inputs.confirmation(netMinor = 8_999)))
        changed.forEach {
            assertTrue(it.matches(request)) // This method checks business bytes only, not response ownership.
            assertNotEquals(waiting, it)
        }
        val copiedCoordinates = waiting.copy()
        assertEquals(waiting, copiedCoordinates)
        assertNotSame(waiting, copiedCoordinates)
        for (reason in LocalActionApprovalNoAdmissionReason.entries) {
            val liveCarrier = LocalActionApprovalRetireResult.NotAdmitted(waiting, reason)
            val otherOriginCarrier = LocalActionApprovalRetireResult.NotAdmitted(copiedCoordinates, reason)
            assertSame(waiting, liveCarrier.attempt)
            assertSame(copiedCoordinates, otherOriginCarrier.attempt)
            assertNotSame(liveCarrier.attempt, otherOriginCarrier.attempt)
            assertEquals(reason, liveCarrier.reason)
        }
        // No gateway is invoked here; no claim this comparison fences a physical dispatch.
    }

    @Test fun invalidSequenceOrApprovedOriginalHasNoAcceptingAttemptDefault() {
        val request = ArnaV1Inputs.request()
        for (sequence in listOf(0L, -1L)) assertFailsWith<IllegalArgumentException> {
            ArnaV1Inputs.attempt(sequence = sequence, request = request)
        }
        assertFailsWith<IllegalArgumentException> {
            LocalActionApprovalRetirementAttempt.capture(ArnaV1Inputs.id("I1"), 2,
                ArnaV1Inputs.id("approval-A"), MutationRequest(request.operationId, request.command,
                    request.payload, ArnaV1Inputs.id("approval-A")), ArnaV1Inputs.confirmation())
        }
    }

    @Test fun legacyCompatibilityPreservesEveryGenericRetirementResultWithoutUpgrade() = runTest {
        val request = ArnaV1Inputs.request()
        val attempt = ArnaV1Inputs.attempt(request = request)
        val legacy = ArnaLegacyOnlyClient()
        val credentials = ArnaV1Inputs.credentials()
        try {
            for (reply in listOf(LocalActionApprovalRetireResult.Denied, LocalActionApprovalRetireResult.Busy,
                LocalActionApprovalRetireResult.Unavailable, LocalActionApprovalRetireResult.StorageUncertain)) {
                legacy.reply = reply
                assertSame(reply, legacy.retireActionApprovalAttempt(attempt, credentials, request))
                val call = legacy.calls.last()
                assertEquals(attempt.approvalId, call.approvalId)
                assertSame(request, call.request)
                assertEquals(attempt.confirmationDigest, call.confirmation)
                assertSame(credentials, call.requestor)
            }
            assertEquals(4, legacy.calls.size)
        } finally { credentials.bearer.destroy() }
    }

    @Test fun legacyCompatibilitySanitizesNegativeWithoutLiveBindingAndRefusesMismatchedInput() = runTest {
        val request = ArnaV1Inputs.request()
        val attempt = ArnaV1Inputs.attempt(request = request)
        val legacy = ArnaLegacyOnlyClient()
        val credentials = ArnaV1Inputs.credentials()
        try {
            for (reason in LocalActionApprovalNoAdmissionReason.entries) {
                legacy.reply = LocalActionApprovalRetireResult.NotAdmitted(attempt, reason)
                assertSame(LocalActionApprovalRetireResult.Unavailable,
                    legacy.retireActionApprovalAttempt(attempt, credentials, request))
            }
            val calls = legacy.calls.size
            assertSame(LocalActionApprovalRetireResult.Denied, legacy.retireActionApprovalAttempt(attempt,
                credentials, MutationRequest(request.operationId, request.command,
                    ArnaV1Inputs.commandBody(discountMinor = 1_001))))
            assertEquals(calls, legacy.calls.size)
        } finally { credentials.bearer.destroy() }
    }
}

private data class ArnaLegacyCall(val approvalId: DomainId, val requestor: LocalGatewayCredentials,
    val request: MutationRequest, val confirmation: PayloadDigest)

/** Legacy API control only; supplied negatives deliberately have no endpoint origin evidence. */
private class ArnaLegacyOnlyClient : LocalMasterApprovalClient {
    var reply: LocalActionApprovalRetireResult = LocalActionApprovalRetireResult.Unavailable
    val calls = mutableListOf<ArnaLegacyCall>()
    override suspend fun retireActionApproval(approvalId: DomainId, requestor: LocalGatewayCredentials,
        request: MutationRequest, confirmationDigest: PayloadDigest): LocalActionApprovalRetireResult {
        calls += ArnaLegacyCall(approvalId, requestor, request, confirmationDigest)
        return reply
    }
    override suspend fun previewActionApproval(requestor: LocalGatewayCredentials, request: MutationRequest,
        retiredApprovalId: DomainId?): LocalActionApprovalPreviewResult = error("No preview in legacy safety slice")
    override suspend fun issueActionApproval(approvalId: DomainId, requestor: LocalGatewayCredentials,
        request: MutationRequest, confirmationDigest: PayloadDigest, approverLogin: LocalLoginIdentifier,
        approverPassword: CharArray, retiredApprovalId: DomainId?): LocalActionApprovalIssueResult =
        error("No issuance in legacy safety slice")
    override suspend fun lookupActionApproval(approvalId: DomainId, requestor: LocalGatewayCredentials,
        confirmationDigest: PayloadDigest): LocalActionApprovalStatus = error("No lookup in legacy safety slice")
}
