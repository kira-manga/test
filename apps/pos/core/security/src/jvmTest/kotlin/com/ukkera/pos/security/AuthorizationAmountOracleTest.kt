package com.ukkera.pos.security

import java.math.BigInteger
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

class AuthorizationAmountOracleTest {
    @Test
    fun exactPercentageBoundaryMatchesIndependentBigIntegerAcrossLargeAmounts(): Unit = runTest {
        val actor = local()
        val random = Random(0x51c0ffeeL)
        val bases = listOf(1L, 9_999L, 10_000L, Long.MAX_VALUE) +
            List(512) { (random.nextLong() and Long.MAX_VALUE).coerceAtLeast(1) }
        for (base in bases) {
            val bp = random.nextInt(10_001)
            val oracle = BigInteger.valueOf(base).multiply(BigInteger.valueOf(bp.toLong()))
                .divide(BigInteger.valueOf(10_000)).longValueExact()
            val request = requirement(SecurityAction.DISCOUNT, limitUse = LimitUse.Proportional(money(oracle), money(base)))
            val state = snapshot(actor, listOf(grant(request, limits = PermissionLimits(money(Long.MAX_VALUE), bp))))
            assertIs<AuthorizationDecision.Allowed>(AuthorizationEvaluator.evaluate(actor, state, request))
            if (oracle < Long.MAX_VALUE) {
                assertEquals(
                    AuthorizationDecision.Denied(AuthorizationDenial.LIMIT_EXCEEDED),
                    AuthorizationEvaluator.evaluate(
                        actor, state, request.copy(limitUse = LimitUse.Proportional(money(oracle + 1), money(base))),
                    ),
                )
            }
        }
    }
}
