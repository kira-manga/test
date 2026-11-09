package com.ukkera.pos.security.independent.mackeychain

import com.ukkera.pos.security.MacKeychainSecureIdentityStore
import dev.p2pkit.core.security.JvmSecureIdentityStore
import java.io.Closeable
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.Assume.assumeTrue

/**
 * Public-API harness for frozen macos-sdk-keychain-store-native-v1 (2e6646f2).
 * Uses genuine macOS APIs and exact synthetic namespaces only. Missing Keychain
 * entitlement/access fails on macOS; other hosts explicitly skip every method.
 * Reuses the bounded Windows API harness structure, without its at-rest probe.
 */
class MacKeychainSecureIdentityStoreNativeTest {
    @Test
    fun mackc001NonMacSkipsAndMacOpensGenuineStore() {
        fixture().use { fixture ->
            assertTrue(fixture.store is MacKeychainSecureIdentityStore)
            assertTrue(fixture.store is JvmSecureIdentityStore)
            assertEquals(
                "com.ukkera.pos.security.MacKeychainSecureIdentityStore",
                fixture.store.javaClass.name,
            )
        }
    }

    @Test
    fun mackc002AbsentNamespaceReadsNullAndDeletesFalse() {
        fixture().use { fixture ->
            val namespace = fixture.namespace("MACKC-002", "absent")
            assertNull(fixture.store.read(namespace))
            assertFalse(fixture.store.delete(namespace))
            assertNull(fixture.store.read(namespace))
            assertFalse(fixture.store.delete(namespace))
        }
    }

    @Test
    fun mackc003EmptyCycleAndLargeBinaryValuesRoundTripExactly() {
        fixture().use { fixture ->
            val payloads = listOf(
                "empty" to ByteArray(0),
                "byte-cycle" to ByteArray(256) { it.toByte() },
                "large-binary" to ByteArray(65_536) { ((it * 31 + 7) and 0xff).toByte() },
            )
            for ((label, payload) in payloads) {
                val namespace = fixture.namespace("MACKC-003", label)
                assertContentEquals(payload, fixture.store.putIfAbsent(namespace, payload))
                assertContentEquals(payload, assertNotNull(fixture.store.read(namespace)))
                assertContentEquals(payload, assertNotNull(fixture.store.read(namespace)))
            }
        }
    }

    @Test
    fun mackc004ExistingWinnerIsReturnedAndNeverReplaced() {
        fixture().use { fixture ->
            val namespace = fixture.namespace("MACKC-004", "winner")
            val first = synthetic("MACKC-004-first")
            val second = synthetic("MACKC-004-second")
            val third = synthetic("MACKC-004-third")

            assertContentEquals(first, fixture.store.putIfAbsent(namespace, first))
            assertContentEquals(first, fixture.store.putIfAbsent(namespace, second))
            assertContentEquals(first, fixture.store.putIfAbsent(namespace, third))
            assertContentEquals(first, assertNotNull(fixture.store.read(namespace)))
            assertFalse(assertNotNull(fixture.store.read(namespace)).contentEquals(second))
            assertFalse(assertNotNull(fixture.store.read(namespace)).contentEquals(third))
        }
    }

    @Test
    fun mackc005InputsAndOutputsAreDefensiveCopies() {
        fixture().use { fixture ->
            val namespace = fixture.namespace("MACKC-005", "defensive")
            val expected = synthetic("MACKC-005-durable")
            val caller = expected.copyOf()

            val firstWinner = fixture.store.putIfAbsent(namespace, caller)
            assertContentEquals(expected, firstWinner)

            caller.fill(0x5a.toByte())
            assertContentEquals(expected, assertNotNull(fixture.store.read(namespace)))

            firstWinner.fill(0x6b.toByte())
            assertContentEquals(expected, assertNotNull(fixture.store.read(namespace)))

            val firstRead = assertNotNull(fixture.store.read(namespace))
            assertContentEquals(expected, firstRead)
            firstRead.fill(0x7c.toByte())
            val secondRead = assertNotNull(fixture.store.read(namespace))
            assertContentEquals(expected, secondRead)
            assertNotSame(firstRead, secondRead)

            val existingWinner = fixture.store.putIfAbsent(namespace, synthetic("MACKC-005-loser"))
            assertContentEquals(expected, existingWinner)
            existingWinner.fill(0x7d.toByte())
            assertContentEquals(expected, assertNotNull(fixture.store.read(namespace)))
        }
    }

    @Test
    fun mackc006DeleteRemovesExactRecordAndIsIdempotent() {
        fixture().use { fixture ->
            val namespace = fixture.namespace("MACKC-006", "delete")
            val first = synthetic("MACKC-006-first")
            val second = synthetic("MACKC-006-second")

            assertContentEquals(first, fixture.store.putIfAbsent(namespace, first))
            assertContentEquals(first, assertNotNull(fixture.store.read(namespace)))
            assertTrue(fixture.store.delete(namespace))
            assertNull(fixture.store.read(namespace))
            assertFalse(fixture.store.delete(namespace))
            assertNull(fixture.store.read(namespace))

            assertContentEquals(second, fixture.store.putIfAbsent(namespace, second))
            assertContentEquals(second, assertNotNull(fixture.store.read(namespace)))
            assertTrue(fixture.store.delete(namespace))
            assertNull(fixture.store.read(namespace))
        }
    }

    @Test
    fun mackc007SameProcessConcurrentPutIfAbsentHasExactlyOneWinner() {
        fixture().use { fixture ->
            val namespace = fixture.namespace("MACKC-007", "concurrent")
            val workerCount = 8
            val payloads = (0 until workerCount).map {
                "MACKC-007-worker-$it".toByteArray(StandardCharsets.UTF_8)
            }
            val ready = CountDownLatch(workerCount)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(workerCount)
            val futures = payloads.map { payload ->
                executor.submit<ByteArray> {
                    ready.countDown()
                    check(start.await(30, TimeUnit.SECONDS)) {
                        "concurrent MACKC-007 workers did not receive the start signal"
                    }
                    fixture.store.putIfAbsent(namespace, payload)
                }
            }
            var startReleased = false
            try {
                assertTrue(ready.await(30, TimeUnit.SECONDS), "workers did not reach the barrier")
                start.countDown()
                startReleased = true

                val results = futures.map { future ->
                    try {
                        future.get(30, TimeUnit.SECONDS)
                    } catch (failure: ExecutionException) {
                        fail("MACKC-007 worker failed", failure.cause ?: failure)
                    }
                }
                val winner = results.first()
                for (result in results) {
                    assertContentEquals(winner, result)
                }
                assertEquals(
                    1,
                    payloads.count { it.contentEquals(winner) },
                    "exactly one worker payload must be the durable winner",
                )

                val returnedInstances = Collections.newSetFromMap(
                    IdentityHashMap<ByteArray, Boolean>(),
                )
                returnedInstances.addAll(results)
                assertEquals(
                    workerCount,
                    returnedInstances.size,
                    "each concurrent caller must receive its own defensive copy",
                )

                assertContentEquals(winner, assertNotNull(fixture.store.read(namespace)))
                assertTrue(fixture.store.delete(namespace))
                assertNull(fixture.store.read(namespace))
            } finally {
                if (!startReleased) start.countDown()
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
            }
        }
    }

    @Test
    fun mackc008IndependentOpenInstancesShareDurableStore() {
        fixture().use { fixture ->
            val namespace = fixture.namespace("MACKC-008", "shared")
            val first = synthetic("MACKC-008-first")
            val second = synthetic("MACKC-008-second")
            val third = synthetic("MACKC-008-third")

            assertContentEquals(first, fixture.store.putIfAbsent(namespace, first))
            val secondStore = MacKeychainSecureIdentityStore.open()
            assertContentEquals(first, assertNotNull(secondStore.read(namespace)))
            assertContentEquals(first, secondStore.putIfAbsent(namespace, second))
            assertContentEquals(first, assertNotNull(secondStore.read(namespace)))
            assertTrue(secondStore.delete(namespace))
            assertNull(fixture.store.read(namespace))
            assertContentEquals(third, fixture.store.putIfAbsent(namespace, third))
            assertContentEquals(third, assertNotNull(secondStore.read(namespace)))
        }
    }

    @Test
    fun mackc009DistinctNamespacesDoNotCollideOrLeak() {
        fixture().use { fixture ->
            val shapes = listOf("/", "/a", "/a/b", ":a", "::a", ".a")
            val namespaces = shapes.mapIndexed { index, shape ->
                fixture.namespace("MACKC-009", "shape-$index$shape")
            }
            val payloads = namespaces.mapIndexed { index, _ ->
                synthetic("MACKC-009-payload-$index")
            }

            namespaces.forEachIndexed { index, namespace ->
                assertContentEquals(payloads[index], fixture.store.putIfAbsent(namespace, payloads[index]))
            }
            namespaces.forEachIndexed { index, namespace ->
                assertContentEquals(payloads[index], assertNotNull(fixture.store.read(namespace)))
                assertContentEquals(payloads[index], assertNotNull(fixture.store.read(namespace)))
            }
            namespaces.forEachIndexed { index, namespace ->
                assertContentEquals(
                    payloads[index],
                    fixture.store.putIfAbsent(namespace, synthetic("MACKC-009-loser-$index")),
                )
            }

            val deletedIndex = 1
            assertTrue(fixture.store.delete(namespaces[deletedIndex]))
            assertNull(fixture.store.read(namespaces[deletedIndex]))
            namespaces.forEachIndexed { index, namespace ->
                if (index != deletedIndex) {
                    assertContentEquals(payloads[index], assertNotNull(fixture.store.read(namespace)))
                }
            }
        }
    }

    private fun fixture(): Fixture {
        assumeTrue(
            "MACKC native cases require a genuine macOS host; current os.name=" +
                System.getProperty("os.name"),
            isMac(),
        )
        return Fixture(MacKeychainSecureIdentityStore.open())
    }

    private class Fixture(
        val store: MacKeychainSecureIdentityStore,
    ) : Closeable {
        private val namespaces = linkedSetOf<String>()

        fun namespace(caseId: String, label: String): String {
            val value = "$NAMESPACE_PREFIX$RUN_ID-$caseId-$label"
            namespaces.add(value)
            return value
        }

        override fun close() {
            var failure: Throwable? = null
            for (namespace in namespaces) {
                try {
                    store.delete(namespace)
                } catch (caught: Throwable) {
                    if (failure == null) failure = caught else failure.addSuppressed(caught)
                }
            }
            namespaces.clear()
            failure?.let { throw it }
        }
    }

    private companion object {
        const val NAMESPACE_PREFIX = "ukkera-pos-mackc-"
        val RUN_ID: String = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        fun isMac(): Boolean = System.getProperty("os.name").orEmpty().equals("Mac OS X", ignoreCase = true)
        fun synthetic(label: String): ByteArray = "synthetic:$label".toByteArray(StandardCharsets.UTF_8)
    }
}
