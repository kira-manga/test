package com.ukkera.pos.security

/**
 * Verified ordinal view of a desktop platform's retained boot-ordinal ledger chain (genesis through
 * the current boot). Desktop boot sessions are unordered UUIDs, so key-intent ordering and rollback
 * refusal are answered ONLY from this chain: a session absent from it is never "earlier".
 *
 * The core verifies the chain's internal integrity (genesis, exact digest-linked +1 steps, distinct
 * sessions, ending at the current boot). It cannot detect a chain store that was wholesale restored
 * together with older original state; preventing that rollback is the platform's contract (see
 * `SelfOwnedOriginalEnvironment.bootLedgerChain`).
 */
internal class SelfOwnedBootChainV2 private constructor(
    private val ordinals: Map<String, Int>,
    val current: SelfOwnedBootOrdinalLedgerV2,
) {
    fun ordinalOf(session: SelfOwnedNativeBootSessionV2): Int? =
        if (session.platform != current.bootSession.platform) null else ordinals[session.bootSessionId]

    /** True only for a session in the verified chain at or before the current boot. */
    fun startedNoLaterThanCurrent(session: SelfOwnedNativeBootSessionV2): Boolean =
        ordinalOf(session)?.let { it <= current.ordinal } == true

    /** True only if both sessions are in the chain and [after] has a strictly greater ordinal. */
    fun later(before: SelfOwnedNativeBootSessionV2, after: SelfOwnedNativeBootSessionV2): Boolean {
        val b = ordinalOf(before) ?: return false
        val a = ordinalOf(after) ?: return false
        return a > b
    }

    override fun toString(): String = "SelfOwnedBootChainV2([REDACTED])"

    companion object {
        fun verify(chain: List<SelfOwnedBootOrdinalLedgerV2>, current: SelfOwnedBootOrdinalLedgerV2): SelfOwnedBootChainV2? {
            if (chain.isEmpty() || chain.size.toLong() != current.ordinal.toLong() + 1L) return null
            val genesis = chain.first()
            if (genesis.ordinal != 0 || genesis.previousDigest != null) return null
            val ordinals = HashMap<String, Int>(chain.size * 2)
            ordinals[genesis.bootSessionId] = 0
            for (index in 1 until chain.size) {
                val step = chain[index - 1].verifySuccessor(chain[index])
                if (step !is SelfOwnedBootOrdinalStepV2.Advanced) return null
                if (ordinals.put(chain[index].bootSessionId, chain[index].ordinal) != null) return null
            }
            if (chain.last() != current) return null
            return SelfOwnedBootChainV2(ordinals, current)
        }
    }
}
