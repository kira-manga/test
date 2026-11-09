package com.ukkera.pos.app.independent.credentialentry

import com.ukkera.pos.app.LocalEnrollmentResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Actual shared local entry slices. Accepted-session preservation and attempted identity
 * allocations have no usable fixture/counter here and remain unbound in the case mapping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CredentialEntryFrozenRuntimeSlicesTest {
    @Test fun cenV1004LocalSignInRejectsFrozenInvalidRowsSlice() = runTest {
        rejectRows(CredentialEntryOperation.SIGN_IN, supportsName = true)
    }

    @Test fun cenV1004SupportedNameEnrollmentRejectsFrozenInvalidRowsSlice() = runTest {
        rejectRows(CredentialEntryOperation.ENROLLMENT, supportsName = true)
    }

    @Test fun cenV1004UnsupportedNameEnrollmentRejectsFrozenInvalidRowsSlice() = runTest {
        rejectRows(CredentialEntryOperation.ENROLLMENT, supportsName = false)
    }

    @Test fun cenV1004LocalSignInOversizeCorrectionRestoresForwardingSlice() = runTest {
        correctRows(CredentialEntryOperation.SIGN_IN, supportsName = true)
    }

    @Test fun cenV1004SupportedNameEnrollmentOversizeCorrectionRestoresForwardingSlice() = runTest {
        correctRows(CredentialEntryOperation.ENROLLMENT, supportsName = true)
    }

    @Test fun cenV1004UnsupportedNameEnrollmentOversizeCorrectionRestoresForwardingSlice() = runTest {
        correctRows(CredentialEntryOperation.ENROLLMENT, supportsName = false)
    }

    @Test fun cenV1005LocalSignInForwardsExactFrozenValuesToRefusingGatewaySlice() = runTest {
        forwardRows(CredentialEntryOperation.SIGN_IN, supportsName = true)
    }

    @Test fun cenV1005SupportedNameEnrollmentForwardsExactFrozenValuesToRefusingGatewaySlice() = runTest {
        forwardRows(CredentialEntryOperation.ENROLLMENT, supportsName = true)
    }

    @Test fun cenV1005UnsupportedNameEnrollmentForwardsExactFrozenValuesToRefusingGatewaySlice() = runTest {
        forwardRows(CredentialEntryOperation.ENROLLMENT, supportsName = false)
    }

    @Test fun cenV1006F01LocalSignInSafeGatewayFailureClearsSubmittedBufferSlice() = runTest {
        failGateway(CredentialEntryOperation.SIGN_IN, supportsName = true)
    }

    @Test fun cenV1006F01SupportedNameEnrollmentSafeGatewayFailureClearsSubmittedBufferSlice() = runTest {
        failGateway(CredentialEntryOperation.ENROLLMENT, supportsName = true)
    }

    @Test fun cenV1006F01UnsupportedNameEnrollmentSafeGatewayFailureClearsSubmittedBufferSlice() = runTest {
        failGateway(CredentialEntryOperation.ENROLLMENT, supportsName = false)
    }

    @Test fun cenV1006F02LocalSignInCancellationClearsSubmittedBufferSlice() = runTest {
        cancelGateway(CredentialEntryOperation.SIGN_IN, supportsName = true)
    }

    @Test fun cenV1006F02SupportedNameEnrollmentCancellationClearsSubmittedBufferSlice() = runTest {
        cancelGateway(CredentialEntryOperation.ENROLLMENT, supportsName = true)
    }

    @Test fun cenV1006F02UnsupportedNameEnrollmentCancellationClearsSubmittedBufferSlice() = runTest {
        cancelGateway(CredentialEntryOperation.ENROLLMENT, supportsName = false)
    }

    @Test fun cenV1C1RefreshedIdentifierOnlyStatusEnforcesEmptyNameAtRuntimeSlice() = runTest {
        val nonempty = FrozenRuntimeInput("CEN-V1-C1/nonempty-name", "a", "p", "n")
        val fixture = CredentialEntryRuntimeFixture(CredentialEntryOperation.ENROLLMENT,
            supportsName = true, expectedInput = nonempty)
        try {
            fixture.prepare()
            fixture.statusSupportsName = false
            fixture.runtime.loadEnrollmentStatus(fixture.runtime.navigator.token())
            assertFalse(assertNotNull(fixture.runtime.presentation.value.enrollment.status).supportsDisplayName,
                "CEN-V1-C1: identifier-only capability delivered through the actual status flow")
            val rejectedBuffer = nonempty.password.toCharArray()
            fixture.invoke(this, rejectedBuffer).join()
            assertRejected(fixture, nonempty.id)
            fixture.assertCalls(0, nonempty.id)
            fixture.assertCleared(rejectedBuffer, nonempty.id)
            fixture.assertEntryUnchanged(nonempty.id)

            val empty = FrozenRuntimeInput("CEN-V1-C1/explicit-empty-retry", "a", "p", "")
            fixture.expectedInput = empty
            val correctedBuffer = empty.password.toCharArray()
            fixture.invoke(this, correctedBuffer).join()
            assertRefused(fixture, empty.id)
            fixture.assertCalls(1, empty.id)
            fixture.assertExactForwarding(empty.id)
            fixture.assertCleared(correctedBuffer, empty.id)
            fixture.assertEntryUnchanged(empty.id)
        } finally {
            fixture.close()
        }
    }

    private suspend fun CoroutineScope.rejectRows(operation: CredentialEntryOperation, supportsName: Boolean) {
        frozenRejectedInputs(operation, supportsName).forEach { input ->
            val fixture = CredentialEntryRuntimeFixture(operation, supportsName, input)
            try {
                fixture.prepare()
                val submitted = input.password.toCharArray()
                fixture.invoke(this, submitted).join()
                assertRejected(fixture, input.id)
                fixture.assertCalls(0, input.id)
                fixture.assertCleared(submitted, input.id)
                fixture.assertEntryUnchanged(input.id)
            } finally {
                fixture.close()
            }
        }
    }

    private suspend fun CoroutineScope.correctRows(operation: CredentialEntryOperation, supportsName: Boolean) {
        frozenOversizeCorrections(operation, supportsName).forEach { row ->
            val fixture = CredentialEntryRuntimeFixture(operation, supportsName, row.rejected)
            try {
                fixture.prepare()
                val rejectedBuffer = row.rejected.password.toCharArray()
                fixture.invoke(this, rejectedBuffer).join()
                assertRejected(fixture, row.rejected.id)
                fixture.assertCalls(0, row.rejected.id)
                fixture.assertCleared(rejectedBuffer, row.rejected.id)
                fixture.assertEntryUnchanged(row.rejected.id)

                fixture.expectedInput = row.corrected
                val correctedBuffer = row.corrected.password.toCharArray()
                fixture.invoke(this, correctedBuffer).join()
                assertRefused(fixture, row.corrected.id)
                fixture.assertCalls(1, row.corrected.id)
                fixture.assertExactForwarding(row.corrected.id)
                fixture.assertCleared(correctedBuffer, row.corrected.id)
                fixture.assertEntryUnchanged(row.corrected.id)
            } finally {
                fixture.close()
            }
        }
    }

    private suspend fun CoroutineScope.forwardRows(operation: CredentialEntryOperation, supportsName: Boolean) {
        frozenForwardingRows.forEach { row ->
            val input = row.runtimeInput("CEN-V1-005", operation, supportsName)
            val fixture = CredentialEntryRuntimeFixture(operation, supportsName, input)
            try {
                fixture.prepare()
                val submitted = input.password.toCharArray()
                fixture.invoke(this, submitted).join()
                assertRefused(fixture, input.id)
                fixture.assertCalls(1, input.id)
                fixture.assertExactForwarding(input.id)
                fixture.assertCleared(submitted, input.id)
                fixture.assertEntryUnchanged(input.id)
            } finally {
                fixture.close()
            }
        }
    }

    private suspend fun CoroutineScope.failGateway(operation: CredentialEntryOperation, supportsName: Boolean) {
        val input = frozenFailureInput(operation, supportsName)
        val label = "${input.id}/F01/$operation/name-supported=$supportsName"
        val fixture = CredentialEntryRuntimeFixture(operation, supportsName, input, CredentialGatewayAction.FAIL)
        try {
            fixture.prepare()
            val submitted = input.password.toCharArray()
            var completionFailure: Throwable? = null
            val invocation = fixture.invoke(this, submitted)
            invocation.invokeOnCompletion { completionFailure = it }
            invocation.join()
            assertFailureTermination(fixture, label)
            fixture.assertCalls(1, label)
            fixture.assertExactForwarding(label)
            fixture.assertCleared(submitted, label)
            fixture.assertEntryUnchanged(label)
            fixture.assertSentinelAbsent(completionFailure, label)
        } finally {
            fixture.close()
        }
    }

    private suspend fun TestScope.cancelGateway(operation: CredentialEntryOperation, supportsName: Boolean) {
        val input = frozenFailureInput(operation, supportsName)
        val label = "${input.id}/F02/$operation/name-supported=$supportsName"
        val fixture = CredentialEntryRuntimeFixture(operation, supportsName, input, CredentialGatewayAction.SUSPEND)
        var invocation: Job? = null
        try {
            fixture.prepare()
            val submitted = input.password.toCharArray()
            var completionFailure: Throwable? = null
            val running = fixture.invoke(this, submitted)
            invocation = running
            running.invokeOnCompletion { completionFailure = it }
            runCurrent()
            assertTrue(fixture.entered.isCompleted, "$label: gateway entered before cancellation")
            assertTrue(running.isActive, "$label: invocation suspended in the held gateway")
            fixture.assertCalls(1, label)
            fixture.assertExactForwarding(label)

            running.cancel(CancellationException("Synthetic credential-entry invocation cancellation"))
            running.join()
            assertTrue(running.isCancelled, "$label: invocation canceled")
            assertIs<CancellationException>(completionFailure, "$label: canceled termination observed")
            fixture.assertCleared(submitted, label)
            fixture.assertEntryUnchanged(label)
            fixture.assertSentinelAbsent(completionFailure, label)

            // Complete the held refusal only after cancellation has fully terminated. This
            // fixture has no timers or repeating work; runCurrent drains its bounded scheduler.
            fixture.releaseWithRefusal()
            runCurrent()
            fixture.assertCalls(1, "$label/after-release")
            fixture.assertEntryUnchanged("$label/after-release")
            fixture.assertCleared(submitted, "$label/after-release")
        } finally {
            invocation?.cancelAndJoin()
            fixture.releaseWithRefusal()
            fixture.close()
        }
    }

    private fun assertRejected(fixture: CredentialEntryRuntimeFixture, label: String) {
        when (fixture.operation) {
            CredentialEntryOperation.SIGN_IN -> assertNotNull(fixture.runtime.presentation.value.signInFailure,
                "$label: observable runtime rejection")
            CredentialEntryOperation.ENROLLMENT -> {
                assertIs<LocalEnrollmentResult.Rejected>(fixture.runtime.presentation.value.enrollment.result,
                    "$label: observable runtime rejection")
                // Absence on this public surface is not an attempted-allocation counter.
                assertNull(fixture.runtime.presentation.value.enrollment.requestId,
                    "$label: no request identity exposed after invalid input")
            }
        }
    }

    private fun assertRefused(fixture: CredentialEntryRuntimeFixture, label: String) {
        when (fixture.operation) {
            CredentialEntryOperation.SIGN_IN -> assertNotNull(fixture.runtime.presentation.value.signInFailure,
                "$label: refusal rather than authenticated result")
            CredentialEntryOperation.ENROLLMENT -> assertIs<LocalEnrollmentResult.Rejected>(
                fixture.runtime.presentation.value.enrollment.result, "$label: refusal rather than created employee")
        }
    }

    private fun assertFailureTermination(fixture: CredentialEntryRuntimeFixture, label: String) {
        when (fixture.operation) {
            CredentialEntryOperation.SIGN_IN -> assertNotNull(fixture.runtime.presentation.value.signInFailure,
                "$label: error termination rather than authenticated result")
            CredentialEntryOperation.ENROLLMENT -> {
                val result = assertNotNull(fixture.runtime.presentation.value.enrollment.result,
                    "$label: error termination observable")
                assertTrue(result is LocalEnrollmentResult.Rejected || result == LocalEnrollmentResult.Unresolved,
                    "$label: error termination rather than created employee")
            }
        }
    }
}
