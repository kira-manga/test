package com.ukkera.pos.security

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Self-authored, NOT independent. Pure mathematical compatibility/negative controls.
 * Generated software keys prove no native possession, current permission or OS custody. */
class FreshP256ArithmeticSupportingTest {
    private fun key(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }
    private fun point(pair: KeyPair) = JvmP256Primitives.point(pair.public as ECPublicKey)
    private fun sign(pair: KeyPair, message: ByteArray) =
        JvmP256Primitives.canonicalize(JvmP256Primitives.sign(pair.private, message))

    @Test fun everyNewChallengeStillRequiresItsOwnValidSignature() {
        val pair = key(); val point = point(pair)
        repeat(64) { index ->
            val challenge = "new-challenge-$index".encodeToByteArray()
            val signature = sign(pair, challenge)
            assertTrue(JvmP256Primitives.verifyFresh(point, challenge, signature))
            assertFalse(JvmP256Primitives.verifyFresh(point, "different-challenge-$index".encodeToByteArray(), signature))
            val altered = signature.copyOf().also { it[31] = (it[31].toInt() xor 1).toByte() }
            assertFalse(JvmP256Primitives.verifyFresh(point, challenge, altered))
            assertTrue(JvmP256Primitives.verifyFresh(point, challenge, signature))
        }
    }

    @Test fun otherAndMalformedPublicPointsCannotReuseVerification() {
        val pair = key(); val message = "private-key-proof-message".encodeToByteArray(); val signature = sign(pair, message)
        assertTrue(JvmP256Primitives.verifyFresh(point(pair), message, signature))
        assertFalse(JvmP256Primitives.verifyFresh(point(key()), message, signature))
        assertFalse(JvmP256Primitives.verifyFresh(ByteArray(65), message, signature))
        assertFalse(JvmP256Primitives.verifyFresh(point(pair), message, ByteArray(64)))
    }

    @Test fun manyPublicIdentitiesDoNotChangeEarlierMathematicalResults() {
        val first = key(); val message = "retained-public-point-only".encodeToByteArray(); val signature = sign(first, message)
        assertTrue(JvmP256Primitives.verifyFresh(point(first), message, signature))
        repeat(80) {
            val pair = key(); val own = sign(pair, message)
            assertTrue(JvmP256Primitives.verifyFresh(point(pair), message, own))
            assertFalse(JvmP256Primitives.verifyFresh(point(pair), message, signature))
        }
        assertTrue(JvmP256Primitives.verifyFresh(point(first), message, signature))
    }

    @Test fun concurrentFreshChallengesNeverShareMutableVerifierOrNonceState() {
        val pair = key(); val point = point(pair); val executor = Executors.newFixedThreadPool(4)
        try {
            val calls = (0 until 32).map { index -> Callable {
                val message = "concurrent-challenge-$index".encodeToByteArray(); val signature = sign(pair, message)
                JvmP256Primitives.verifyFresh(point, message, signature) &&
                    !JvmP256Primitives.verifyFresh(point, "other-$index".encodeToByteArray(), signature)
            } }
            assertTrue(executor.invokeAll(calls).all { it.get() })
        } finally { executor.shutdownNow() }
    }
}
