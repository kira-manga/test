@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.rpc.independent.businesscleanup

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Real typed serializer/custody SUPPORT only; the reduced BRC-011 schedule is explicitly partial. */
class BusinessRequestCustodyV2SupportingTest {
    @Test
    fun brc007ControlledExpiryThresholdAndSuccessfulClaimOwnership() = runTest(timeout = 15.seconds) {
        for (kind in BusinessCleanupKind.entries) for (variant in listOf("pre_threshold", "expiry_first", "claim_first")) {
            val id = "BRC-007-${kind.code}-$variant"
            val fixture = BusinessCleanupFixture()
            try {
                val caller = BusinessCleanupV2Frozen.bytes("caller_A")
                val decoder = BusinessCustodyFixture(fixture)
                val request = decoder.decode(kind, caller)
                decoder.assertCensus(listOf(request), "$id: captured at 0 ms")
                when (variant) {
                    "pre_threshold" -> {
                        decoder.advanceTo(BusinessCleanupV2Frozen.clockMs("before_expiry_ms"))
                        decoder.custody.reap()
                        decoder.assertCensus(listOf(request), "$id: 29,999 ms")
                        assertFalse(request.isDestroyed, id)
                        assertFalse(request.isCleanupComplete, id)
                        var copy: ByteArray? = null
                        request.useBearer {
                            copy = it
                            assertNotSame(caller, it, id)
                            assertFull52(caller, it, "$id: pre-threshold borrow")
                        }
                        assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), checkNotNull(copy), "$id: returned")
                        decoder.advanceTo(BusinessCleanupV2Frozen.clockMs("at_expiry_ms"))
                        decoder.custody.reap()
                        assertTrue(request.isDestroyed && request.isCleanupComplete, id)
                        request.awaitCleanup()
                        decoder.assertCensus(emptyList(), "$id: completed expiry")
                        assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), retainedBearer(request), "$id: retained")
                    }
                    "expiry_first" -> {
                        decoder.advanceTo(BusinessCleanupV2Frozen.clockMs("at_expiry_ms"))
                        decoder.custody.reap()
                        assertTrue(request.isDestroyed && request.isCleanupComplete, id)
                        assertFalse(request.claimForCallback(), id)
                        var entries = 0
                        assertFailsWith<IllegalStateException>(id) { request.useBearer { entries++ } }
                        assertEquals(0, entries, id)
                        request.awaitCleanup()
                        decoder.assertCensus(emptyList(), id)
                        assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), retainedBearer(request), "$id: retained")
                    }
                    "claim_first" -> {
                        assertTrue(request.claimForCallback(), id)
                        val loan = fixture.borrow(request, caller, id)
                        for (checkpoint in listOf("at_expiry_ms", "held_age_ms")) {
                            decoder.advanceTo(BusinessCleanupV2Frozen.clockMs(checkpoint))
                            decoder.custody.reap()
                            decoder.assertCensus(listOf(request), "$id: $checkpoint")
                            assertFalse(request.isDestroyed, id)
                            assertFalse(request.isCleanupComplete, id)
                            assertFull52(caller, loan.copy, "$id: claimed held loan")
                            assertFull52(caller, retainedBearer(request), "$id: claimed retained bearer")
                        }
                        var laterCopy: ByteArray? = null
                        request.useBearer { laterCopy = it; assertFull52(caller, it, "$id: owner use remains available") }
                        assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), checkNotNull(laterCopy), "$id: later owner return")
                        request.destroy()
                        val wait = fixture.wait(this, id) { request.awaitCleanup() }
                        wait.assertPending()
                        assertTrue(request.isDestroyed, id)
                        assertFalse(request.isCleanupComplete, id)
                        assertFull52(caller, loan.copy, "$id: owner retirement while held")
                        loan.release()
                        loan.assertOutcome()
                        wait.awaitSuccess()
                        assertTrue(request.isCleanupComplete, id)
                        assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), retainedBearer(request), "$id: claimant-owned cleanup")
                    }
                }
                assertFull52(BusinessCleanupV2Frozen.bytes("caller_A"), caller, "$id: caller")
            } finally { fixture.close() }
        }
    }

    @Test
    fun brc010TerminalIncompleteInputsOccupyCapacityUntilExplicitCompletedRecheck() = runTest(timeout = 30.seconds) {
        for (start in BusinessCleanupKind.entries) for (young in listOf(false, true)) {
            val id = "BRC-010-${start.code}-base: ${if (young) "young" else "old"} phase"
            val fixture = BusinessCleanupFixture()
            try {
                val decoder = BusinessCustodyFixture(fixture)
                val callers = List(BusinessCleanupV2Frozen.capacity) { BusinessCleanupV2Frozen.bytes("caller_A") }
                val inputs = callers.mapIndexed { index, caller ->
                    decoder.decode(BusinessCleanupV2Frozen.inventoryKind(start, index), caller,
                        BusinessCleanupV2Frozen.inventoryCoordinate(index))
                }
                inputs.forEachIndexed { index, input ->
                    if (young || index != 0) assertTrue(input.claimForCallback(), "$id: I$index")
                }
                val loan = fixture.borrow(inputs[0], callers[0], "$id: I000")
                decoder.assertCensus(inputs, "$id: initial 256")
                if (young) {
                    inputs[0].destroy()
                } else {
                    decoder.advanceTo(BusinessCleanupV2Frozen.clockMs("before_expiry_ms"))
                    decoder.custody.reap()
                    assertFalse(inputs[0].isDestroyed, id)
                    decoder.assertCensus(inputs, "$id: before expiry")
                    decoder.advanceTo(BusinessCleanupV2Frozen.clockMs("at_expiry_ms"))
                    decoder.custody.reap()
                    assertTrue(inputs[0].isDestroyed, id)
                    assertFalse(inputs[0].isCleanupComplete, id)
                    decoder.assertCensus(inputs, "$id: expiry while borrowed")
                    decoder.advanceTo(BusinessCleanupV2Frozen.clockMs("held_age_ms"))
                    decoder.custody.reap()
                }
                assertTrue(inputs[0].isDestroyed, id)
                assertFalse(inputs[0].isCleanupComplete, id)
                val wait = fixture.wait(this, "$id: I000 cleanup") { inputs[0].awaitCleanup() }
                wait.assertPending()
                val candidateCaller = BusinessCleanupV2Frozen.bytes("caller_A")
                assertFailsWith<SerializationException>(id) { decoder.decode(start, candidateCaller, 257) }
                decoder.assertCensus(inputs, "$id: input 257 refused")
                assertFull52(callers[0], loan.copy, "$id: no forced active-copy clearing")
                assertFull52(callers[0], retainedBearer(inputs[0]), "$id: incomplete retained bearer")
                inputs.drop(1).forEachIndexed { index, input ->
                    assertFalse(input.isDestroyed, "$id: claimed I${index + 1}")
                    assertFalse(input.isCleanupComplete, "$id: claimed I${index + 1}")
                    assertFull52(callers[index + 1], retainedBearer(input), "$id: unaffected claimed input")
                }

                if (young) decoder.advanceTo(BusinessCleanupV2Frozen.clockMs("young_completed_recheck_ms"))
                loan.release()
                loan.assertOutcome()
                wait.awaitSuccess()
                assertTrue(inputs[0].isCleanupComplete, id)
                assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), retainedBearer(inputs[0]), "$id: completed I000")
                // This explicit maintenance is part of the frozen BRC-010 schedule.
                decoder.custody.reap()
                decoder.assertCensus(inputs.drop(1), "$id: completed-input recheck gives 255")
                val replacement = decoder.decode(start, candidateCaller, 257)
                decoder.assertCensus(inputs.drop(1) + replacement, "$id: one replacement restores 256")
                assertFalse(replacement.isDestroyed, id)
                assertFull52(candidateCaller, retainedBearer(replacement), "$id: replacement full carrier")
                callers.forEach { assertFull52(BusinessCleanupV2Frozen.bytes("caller_A"), it, "$id: caller unchanged") }
                assertFull52(BusinessCleanupV2Frozen.bytes("caller_A"), candidateCaller, "$id: candidate caller unchanged")
            } finally { fixture.close() }
        }
    }

    @Test
    fun brc011SupportInitialExactCensusAndFinalReleaseWithThreeHeldLoans() = runTest(timeout = 30.seconds) {
        // SUPPORT portion: the full frozen schedule holds all 256 loans and requires
        // progressive identity removal. Neither assertion is represented by this reduction.
        for (start in BusinessCleanupKind.entries) for (pattern in listOf("all_unclaimed", "all_claimed", "alternating")) {
            val id = "BRC-011-${start.code}-$pattern: initial/final SUPPORT"
            val fixture = BusinessCleanupFixture()
            try {
                val decoder = BusinessCustodyFixture(fixture)
                val callers = List(BusinessCleanupV2Frozen.capacity) { BusinessCleanupV2Frozen.bytes("caller_A") }
                val inputs = callers.mapIndexed { index, caller ->
                    decoder.decode(BusinessCleanupV2Frozen.inventoryKind(start, index), caller,
                        BusinessCleanupV2Frozen.inventoryCoordinate(index))
                }
                inputs.forEachIndexed { index, input ->
                    if (BusinessCleanupV2Frozen.isClaimed(pattern, index)) assertTrue(input.claimForCallback(), id)
                }
                // Three consecutive frozen identities cover R/E/L and keep the
                // selected census/owner waits outstanding.
                val held = listOf(0, 1, 2).associateWith { fixture.borrow(inputs[it], callers[it], "$id: I$it") }
                decoder.retire()
                val drain = fixture.wait(this, "$id: final shutdown") { decoder.custody.destroyAfterDrain() }
                drain.assertPending()
                decoder.assertCensus(inputs, "$id: exact initial retained census")
                val rejectedCaller = BusinessCleanupV2Frozen.bytes("caller_A")
                assertFailsWith<SerializationException>(id) { decoder.decode(start, rejectedCaller, 257) }
                decoder.assertCensus(inputs, "$id: retired admission refuses input 257")
                inputs.forEachIndexed { index, input ->
                    if (BusinessCleanupV2Frozen.isClaimed(pattern, index)) {
                        assertFalse(input.isDestroyed, "$id: claimed input kept live")
                        assertFalse(input.isCleanupComplete, "$id: claimed input still owns cleanup")
                    } else assertTrue(input.isDestroyed, "$id: every unclaimed input retired before waiting")
                }
                held.forEach { (index, loan) ->
                    assertFalse(inputs[index].isCleanupComplete, "$id: selected held input incomplete")
                    assertFull52(callers[index], loan.copy, "$id: selected held copy")
                    assertFull52(callers[index], retainedBearer(inputs[index]), "$id: selected held retained bearer")
                }

                val remaining = held.keys.toMutableSet()
                for ((index, loan) in held) if (!BusinessCleanupV2Frozen.isClaimed(pattern, index)) {
                    loan.release()
                    loan.assertOutcome()
                    remaining.remove(index)
                    runCurrent()
                    if (remaining.isNotEmpty()) drain.assertPending()
                }
                inputs.forEachIndexed { index, input ->
                    if (BusinessCleanupV2Frozen.isClaimed(pattern, index)) input.destroy()
                }
                remaining.forEach { index ->
                    assertTrue(inputs[index].isDestroyed, "$id: claimed owner requested retirement")
                    assertFalse(inputs[index].isCleanupComplete, "$id: retirement cannot complete held cleanup")
                    assertFull52(callers[index], held.getValue(index).copy, "$id: owner-retired copy remains intact")
                }
                if (remaining.isNotEmpty()) drain.assertPending()
                for ((index, loan) in held) if (BusinessCleanupV2Frozen.isClaimed(pattern, index)) {
                    loan.release()
                    loan.assertOutcome()
                    remaining.remove(index)
                    runCurrent()
                    if (remaining.isNotEmpty()) drain.assertPending()
                }
                drain.awaitSuccess()
                decoder.assertCensus(emptyList(), "$id: successful final release")
                inputs.forEach { input ->
                    assertTrue(input.isDestroyed && input.isCleanupComplete, "$id: every exact cleanup completed")
                    assertFalse(input.claimForCallback(), "$id: terminal claim refusal")
                    assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), retainedBearer(input), "$id: every retained bearer cleared")
                }
                callers.forEach { assertFull52(BusinessCleanupV2Frozen.bytes("caller_A"), it, "$id: caller unchanged") }
                assertFull52(BusinessCleanupV2Frozen.bytes("caller_A"), rejectedCaller, "$id: rejected caller unchanged")
            } finally { fixture.close() }
        }
    }
}
