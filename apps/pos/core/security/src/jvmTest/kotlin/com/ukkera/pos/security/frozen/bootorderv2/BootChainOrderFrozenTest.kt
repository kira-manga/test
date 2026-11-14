package com.ukkera.pos.security.frozen.bootorderv2

import com.ukkera.pos.security.SelfOwnedBootChainV2
import com.ukkera.pos.security.SelfOwnedBootOrdinalLedgerV2
import com.ukkera.pos.security.SelfOwnedBootOrdinalStepV2
import com.ukkera.pos.security.SelfOwnedNativeBootSessionV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen desktop boot-order rules, authored on 13 November 2026 BEFORE [SelfOwnedBootChainV2]
 * existed. NOT independent (same author, owner instruction).
 *
 * The platform supplies its retained boot-ordinal ledger chain (genesis ordinal 0 through the
 * current boot). The core accepts the chain only if it starts at genesis, every step is an exact
 * [SelfOwnedBootOrdinalLedgerV2.verifySuccessor] advance, sessions are distinct, and it ends at the
 * current boot ledger. Ordering questions are answered only from that verified chain:
 * - a key STARTED on a session absent from the chain, on another platform, or later than the
 *   current boot is refused (rollback / foreign session);
 * - "later boot" requires a strictly greater ordinal; a different session alone is not later.
 */
class BootChainOrderFrozenTest {
    private fun windows(n: Int) = SelfOwnedNativeBootSessionV2.Windows("11111111-2222-4333-8444-%012d".format(n))
    private fun mac(n: Int) = SelfOwnedNativeBootSessionV2.Mac("99999999-8888-4777-8666-%012d".format(n))

    private fun chain(vararg sessions: SelfOwnedNativeBootSessionV2): List<SelfOwnedBootOrdinalLedgerV2> {
        val out = mutableListOf(SelfOwnedBootOrdinalLedgerV2(0, sessions.first(), null))
        for (session in sessions.drop(1))
            out += assertIs<SelfOwnedBootOrdinalStepV2.Advanced>(out.last().advance(session)).ledger
        return out
    }

    @Test
    fun verifiedChainOrdersSessionsByOrdinal() {
        val ledgers = chain(windows(0), windows(1), windows(2))
        val verified = assertNotNull(SelfOwnedBootChainV2.verify(ledgers, ledgers.last()))
        assertEquals(0, verified.ordinalOf(windows(0)))
        assertEquals(2, verified.ordinalOf(windows(2)))
        assertNull(verified.ordinalOf(windows(3)))
        assertNull(verified.ordinalOf(mac(0)))
        assertTrue(verified.startedNoLaterThanCurrent(windows(0)))
        assertTrue(verified.startedNoLaterThanCurrent(windows(2)))
        assertFalse(verified.startedNoLaterThanCurrent(windows(3)))
        assertFalse(verified.startedNoLaterThanCurrent(mac(0)))
        assertTrue(verified.later(windows(0), windows(2)))
        assertTrue(verified.later(windows(1), windows(2)))
        assertFalse(verified.later(windows(2), windows(1)))
        assertFalse(verified.later(windows(2), windows(2)))
        assertFalse(verified.later(windows(1), windows(3)))
        assertFalse(verified.later(windows(3), windows(1)))
    }

    @Test
    fun rolledBackPrefixCannotOrderALaterSession() {
        val full = chain(windows(0), windows(1), windows(2))
        // A restored older state (current = ordinal 1) never knows session 2: a key started there is refused.
        val rolled = assertNotNull(SelfOwnedBootChainV2.verify(full.take(2), full[1]))
        assertFalse(rolled.startedNoLaterThanCurrent(windows(2)))
    }

    @Test
    fun malformedChainsAreRefused() {
        val ledgers = chain(windows(0), windows(1), windows(2))
        assertNull(SelfOwnedBootChainV2.verify(emptyList(), ledgers.last()))
        assertNull(SelfOwnedBootChainV2.verify(ledgers.drop(1), ledgers.last()))          // not from genesis
        assertNull(SelfOwnedBootChainV2.verify(listOf(ledgers[0], ledgers[2]), ledgers[2])) // gap
        assertNull(SelfOwnedBootChainV2.verify(ledgers, ledgers[1]))                         // not ending at current
        assertNull(SelfOwnedBootChainV2.verify(ledgers.take(2), ledgers[2]))                 // current not retained
        assertNull(SelfOwnedBootChainV2.verify(listOf(ledgers[0], ledgers[0]), ledgers[0])) // repeated (Same) step
        val forged = SelfOwnedBootOrdinalLedgerV2(2, windows(2), "c".repeat(64))
        assertNull(SelfOwnedBootChainV2.verify(ledgers.take(2) + forged, forged))           // digest mismatch
        val reused = assertIs<SelfOwnedBootOrdinalStepV2.Advanced>(ledgers[1].advance(windows(0))).ledger
        assertNull(SelfOwnedBootChainV2.verify(ledgers.take(2) + reused, reused))           // session reused
        val macGenesis = SelfOwnedBootOrdinalLedgerV2(0, mac(0), null)
        assertNull(SelfOwnedBootChainV2.verify(listOf(macGenesis), ledgers[0]))             // other platform
    }
}
