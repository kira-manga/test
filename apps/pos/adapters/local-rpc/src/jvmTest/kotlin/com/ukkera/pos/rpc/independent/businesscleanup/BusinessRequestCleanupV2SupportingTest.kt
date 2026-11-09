package com.ukkera.pos.rpc.independent.businesscleanup

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Frozen v2 BRC-001..005 typed-carrier SUPPORT; no admitted RPC/native authority is exercised. */
class BusinessRequestCleanupV2SupportingTest {
    @Test
    fun brc001FullLengthCallerCopiesAndIndependentPeers() = runTest(timeout = 15.seconds) {
        for (kind in BusinessCleanupKind.entries) for (peer in BusinessCleanupKind.entries) {
            val id = "BRC-001-${kind.code}-peer-${peer.code}"
            val fixture = BusinessCleanupFixture()
            try {
                val callerA = BusinessCleanupV2Frozen.bytes("caller_A")
                val callerB = BusinessCleanupV2Frozen.bytes("caller_B")
                val a = fixture.capture(kind, callerA)
                val b = fixture.capture(peer, callerB)
                assertNotSame(a, b, id)
                assertNotSame(callerA, retainedBearer(a), id)
                assertNotSame(callerB, retainedBearer(b), id)
                assertNotSame(retainedBearer(a), retainedBearer(b), id)
                BusinessCleanupV2Frozen.bytes("caller_A_after_mutation").copyInto(callerA)

                var copyA: ByteArray? = null
                var copyB: ByteArray? = null
                a.useBearer { bytes ->
                    copyA = bytes
                    assertFull52(BusinessCleanupV2Frozen.bytes("caller_A"), bytes, "$id: A borrow")
                    assertNotSame(callerA, bytes, id)
                    assertNotSame(retainedBearer(a), bytes, id)
                }
                b.useBearer { bytes ->
                    copyB = bytes
                    assertFull52(callerB, bytes, "$id: B borrow")
                    assertNotSame(callerB, bytes, id)
                    assertNotSame(retainedBearer(b), bytes, id)
                }
                assertNotSame(copyA, copyB, id)
                assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), checkNotNull(copyA), "$id: A returned")
                assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), checkNotNull(copyB), "$id: B returned")

                a.destroy()
                a.awaitCleanup()
                assertTrue(a.isDestroyed && a.isCleanupComplete, id)
                assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), retainedBearer(a), "$id: A retained")
                assertFalse(b.isDestroyed, id)
                assertFalse(b.isCleanupComplete, id)
                b.useBearer { bytes ->
                    copyB = bytes
                    assertFull52(BusinessCleanupV2Frozen.bytes("caller_B"), bytes, "$id: independent later B borrow")
                }
                assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), checkNotNull(copyB), "$id: later B returned")
                b.destroy()
                b.awaitCleanup()
                assertTrue(b.isDestroyed && b.isCleanupComplete, id)
                assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), retainedBearer(b), "$id: B retained")
                assertFull52(BusinessCleanupV2Frozen.bytes("caller_A_after_mutation"), callerA, "$id: caller A")
                assertFull52(BusinessCleanupV2Frozen.bytes("caller_B"), callerB, "$id: caller B")
            } finally { fixture.close() }
        }
    }

    @Test
    fun brc002TerminalRetirementWaitsForAnAcceptedSynchronousLoan() = runTest(timeout = 15.seconds) {
        for (kind in BusinessCleanupKind.entries) {
            val id = "BRC-002-${kind.code}-base"
            val fixture = BusinessCleanupFixture()
            try {
                val caller = BusinessCleanupV2Frozen.bytes("caller_A")
                val request = fixture.capture(kind, caller)
                assertFalse(request.isDestroyed, id)
                assertFalse(request.isCleanupComplete, id)
                val loan = fixture.borrow(request, caller, id)
                request.destroy()
                val wait = fixture.wait(this, id) { request.awaitCleanup() }
                wait.assertPending()
                assertTrue(request.isDestroyed, id)
                assertFalse(request.isCleanupComplete, id)
                var refusedBodyEntries = 0
                assertFailsWith<IllegalStateException>(id) { request.useBearer { refusedBodyEntries++ } }
                assertEquals(0, refusedBodyEntries, id)
                assertFalse(request.claimForCallback(), id)
                assertFull52(caller, loan.copy, "$id: held loan")
                assertFull52(caller, retainedBearer(request), "$id: retained while held")

                loan.release()
                loan.assertOutcome()
                wait.awaitSuccess()
                assertTrue(request.isDestroyed && request.isCleanupComplete, id)
                assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), retainedBearer(request), "$id: retained after cleanup")
                assertFull52(BusinessCleanupV2Frozen.bytes("caller_A"), caller, "$id: caller")
            } finally { fixture.close() }
        }
    }

    @Test
    fun brc003LastAcceptedReturnControlsCleanupAndPreservesTheBorrowFault() = runTest(timeout = 15.seconds) {
        for (kind in BusinessCleanupKind.entries) for (throws in listOf(false, true)) {
            val id = "BRC-003-${kind.code}-${if (throws) "first_throws" else "normal"}"
            val fixture = BusinessCleanupFixture()
            try {
                val caller = BusinessCleanupV2Frozen.bytes("caller_A")
                val request = fixture.capture(kind, caller)
                val first = fixture.borrow(request, caller, "$id: 1", throwOnReturn = throws)
                val second = fixture.borrow(request, caller, "$id: 2")
                assertNotSame(first.copy, second.copy, id)
                assertNotSame(caller, first.copy, id)
                assertNotSame(caller, second.copy, id)
                request.destroy()
                val wait = fixture.wait(this, id) { request.awaitCleanup() }
                wait.assertPending()
                first.release()
                first.assertOutcome()
                assertFull52(caller, second.copy, "$id: second still held")
                assertFull52(caller, retainedBearer(request), "$id: retained before last return")
                assertTrue(request.isDestroyed, id)
                assertFalse(request.isCleanupComplete, id)
                wait.assertPending()

                second.release()
                second.assertOutcome()
                wait.awaitSuccess()
                assertTrue(request.isCleanupComplete, id)
                assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), retainedBearer(request), "$id: retained after last return")
                assertFull52(BusinessCleanupV2Frozen.bytes("caller_A"), caller, "$id: caller")
            } finally { fixture.close() }
        }
    }

    @Test
    fun brc004EmptyRetirementAndRepeatedCompletedWaitsRemainTerminal() = runTest(timeout = 15.seconds) {
        for (kind in BusinessCleanupKind.entries) {
            val id = "BRC-004-${kind.code}-base"
            val fixture = BusinessCleanupFixture()
            try {
                val caller = BusinessCleanupV2Frozen.bytes("caller_A")
                val request = fixture.capture(kind, caller)
                request.destroy()
                assertTrue(request.isDestroyed && request.isCleanupComplete, id)
                request.destroy()
                var bodyEntries = 0
                repeat(3) {
                    request.awaitCleanup()
                    assertFalse(request.claimForCallback(), id)
                    assertFailsWith<IllegalStateException>(id) { request.useBearer { bodyEntries++ } }
                    assertTrue(request.isDestroyed && request.isCleanupComplete, id)
                }
                assertEquals(0, bodyEntries, id)
                assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), retainedBearer(request), "$id: retained")
                assertFull52(BusinessCleanupV2Frozen.bytes("caller_A"), caller, "$id: caller")
            } finally { fixture.close() }
        }
    }

    @Test
    fun brc005CancellingOneWaiterDoesNotCancelExactOwnedCleanup() = runTest(timeout = 15.seconds) {
        for (kind in BusinessCleanupKind.entries) {
            val id = "BRC-005-${kind.code}-base"
            val fixture = BusinessCleanupFixture()
            try {
                val caller = BusinessCleanupV2Frozen.bytes("caller_A")
                val request = fixture.capture(kind, caller)
                val loan = fixture.borrow(request, caller, id)
                request.destroy()
                val first = fixture.wait(this, "$id: W1") { request.awaitCleanup() }
                val second = fixture.wait(this, "$id: W2") { request.awaitCleanup() }
                first.assertPending()
                second.assertPending()
                first.cancel()
                first.awaitCancellation()
                assertTrue(request.isDestroyed, id)
                assertFalse(request.isCleanupComplete, id)
                assertFalse(request.claimForCallback(), id)
                second.assertPending()
                assertFull52(caller, loan.copy, "$id: held after waiter cancellation")
                assertFull52(caller, retainedBearer(request), "$id: retained after waiter cancellation")

                loan.release()
                loan.assertOutcome()
                second.awaitSuccess()
                fixture.wait(this, "$id: W3") { request.awaitCleanup() }.awaitSuccess()
                assertTrue(request.isCleanupComplete, id)
                assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), retainedBearer(request), "$id: retained after cleanup")
                assertFull52(BusinessCleanupV2Frozen.bytes("caller_A"), caller, "$id: caller")
            } finally { fixture.close() }
        }
    }
}
