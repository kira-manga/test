package com.ukkera.pos.security.independent.foundingtransferv1

import com.ukkera.pos.security.AndroidLocalFoundingVerifier
import com.ukkera.pos.security.SelfOwnedFailureV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Frozen NCT-001..008 wiring against the actual Android helper; no native authority fixture. */
class AndroidLocalFoundingCredentialTransferV1HostTest {
    @Test fun nct001ExactProfileAndMetadata() {
        for (id in listOf("NCT-F-VALID", "NCT-F-ZERO-METADATA")) {
            val input = fixture(id)
            val original = input.prepare()
            val execution = original.copyForExecution()
            try {
                assertExact(execution, input)
                assertInputUnchanged(input)
                assertNotSame(borrow(original).salt, borrow(execution).salt)
                assertNotSame(borrow(original).verifier, borrow(execution).verifier)
            } finally { execution.clear(); original.clear() }
        }
    }

    @Test fun nct002MalformedAdmissionRefusesWithoutConsumer() {
        // The frozen zero-transient-copy assertion cannot be observed at this unchanged
        // host boundary. This method verifies the other assertions, not that whole case.
        for (id in listOf("NCT-F-SALT-31", "NCT-F-SALT-33", "NCT-F-VERIFIER-31",
            "NCT-F-VERIFIER-33", "NCT-F-NEGATIVE-BOOT", "NCT-F-NEGATIVE-ELAPSED")) {
            val input = fixture(id)
            var transfer: AndroidLocalFoundingVerifier? = null
            var calls = 0
            assertFailsWith<SelfOwnedFailureV1> {
                transfer = input.prepare()
                transfer.copyIntoAccount { _, _ -> calls++ }
            }
            assertEquals(null, transfer)
            assertEquals(0, calls)
            assertInputUnchanged(input)
        }
    }

    @Test fun nct003CallerAndOriginalClearingPreservesExecutionCopy() {
        for (variant in 0..2) {
            val input = fixture("NCT-F-VALID")
            val original = input.prepare()
            val owned = borrow(original)
            val mutation = mutations.getValue(if (variant == 2) "NCT-M-PATTERN" else "NCT-M-ZERO")
            if (variant != 1) {
                mutation.first.copyInto(input.salt)
                mutation.second.copyInto(input.verifier)
            }
            val execution = original.copyForExecution()
            val executionOwned = borrow(execution)
            try {
                if (variant != 0) {
                    if (variant == 2) {
                        mutations.getValue("NCT-M-ZERO").first.copyInto(input.salt)
                        mutations.getValue("NCT-M-ZERO").second.copyInto(input.verifier)
                    }
                    original.clear()
                    assertCleared(owned)
                }
                assertExact(execution, input)
            } finally { execution.clear(); original.clear() }
            assertCleared(owned)
            assertCleared(executionOwned)
        }
    }

    @Test fun nct004AbandonmentAndRetirementEraseOnlyOwnedScratch() {
        val abandonedInput = fixture("NCT-F-VALID")
        val abandoned = abandonedInput.prepare()
        val abandonedOwned = borrow(abandoned)
        abandoned.clear()
        assertCleared(abandonedOwned)
        assertInputUnchanged(abandonedInput)

        val input = fixture("NCT-F-VALID")
        val original = input.prepare()
        val originalOwned = borrow(original)
        val execution = original.copyForExecution()
        val executionOwned = borrow(execution)
        try {
            assertExact(execution, input)
            execution.clear()
            assertCleared(executionOwned)
            assertExact(original, input)
            assertInputUnchanged(input)
        } finally { execution.clear(); original.clear() }
        assertCleared(originalOwned)
    }

    @Test fun nct005ClearIsIdempotentAndTerminal() {
        val input = fixture("NCT-F-VALID")
        val original = input.prepare()
        val owned = borrow(original)
        repeat(2) { original.clear(); assertCleared(owned) }
        assertTerminal(original)
        original.clear()
        assertCleared(owned)
        assertInputUnchanged(input)
    }

    @Test fun nct006OrderedConcurrentCopyUseAndClear() = withWorkers { workers ->
        for (variant in 0..3) {
            val input = fixture("NCT-F-VALID")
            val original = input.prepare()
            val owned = borrow(original)
            var execution: AndroidLocalFoundingVerifier? = null
            var drained = false
            try {
                val operation: () -> Unit = when (variant) {
                    0 -> { { execution = original.copyForExecution() } }
                    1 -> { { assertFailsWith<SelfOwnedFailureV1> { original.copyForExecution() } } }
                    2 -> { { assertExact(original, input) } }
                    else -> { { assertUseRefused(original) } }
                }
                val clearFirst = variant == 1 || variant == 3
                ordered(workers, if (clearFirst) ({ original.clear() }) else operation,
                    if (clearFirst) operation else ({ original.clear() }))
                drained = true
                execution?.let { assertExact(it, input) }
                assertCleared(owned)
                assertTerminal(original)
            } finally {
                // A timed-out worker may hold the helper monitor: never block teardown
                // by reacquiring it. Such a failed schedule makes no erasure claim.
                if (drained) { execution?.clear(); original.clear() }
            }
        }
    }

    @Test fun nct007ActiveConsumerCannotObserveConcurrentErasure() = withWorkers { workers ->
        val input = fixture("NCT-F-VALID")
        val original = input.prepare()
        val owned = borrow(original)
        val entered = CountDownLatch(1)
        val clearRequested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        var drained = false
        try {
            val use = workers.submit(Callable {
                original.copyIntoAccount { salt, verifier ->
                    calls.incrementAndGet()
                    assertBytes(salt, verifier, input)
                    assertMetadata(original, input)
                    entered.countDown()
                    await(release)
                    assertBytes(salt, verifier, input)
                    assertMetadata(original, input)
                }
            })
            val clear = workers.submit(Callable {
                await(entered)
                clearRequested.countDown()
                original.clear()
            })
            await(clearRequested)
            release.countDown()
            use.get(WATCHDOG_SECONDS, TimeUnit.SECONDS)
            clear.get(WATCHDOG_SECONDS, TimeUnit.SECONDS)
            drained = true
            assertEquals(1, calls.get())
            assertCleared(owned)
            assertTerminal(original)
        } finally { release.countDown(); if (drained) original.clear() }
    }

    @Test fun nct008ContestedCopyUseAndClearRemainAtomic() = withWorkers { workers ->
        for (copy in listOf(true, false)) {
          val outcomes = mutableListOf<Boolean>()
          repeat(128) {
            val input = fixture("NCT-F-VALID")
            val original = input.prepare()
            val owned = borrow(original)
            val ready = CountDownLatch(2)
            val start = CountDownLatch(1)
            val calls = AtomicInteger()
            var execution: AndroidLocalFoundingVerifier? = null
            var drained = false
            try {
                val operation = workers.submit(Callable {
                    ready.countDown(); await(start)
                    try {
                        if (copy) {
                            execution = original.copyForExecution()
                        } else {
                            original.copyIntoAccount { salt, verifier ->
                                calls.incrementAndGet()
                                assertBytes(salt, verifier, input)
                                assertMetadata(original, input)
                            }
                        }
                        true
                    } catch (_: SelfOwnedFailureV1) { false }
                })
                val clear = workers.submit(Callable {
                    ready.countDown(); await(start); original.clear()
                })
                await(ready); start.countDown()
                val succeeded = operation.get(WATCHDOG_SECONDS, TimeUnit.SECONDS)
                clear.get(WATCHDOG_SECONDS, TimeUnit.SECONDS)
                drained = true
                outcomes += succeeded
                execution?.let { assertExact(it, input) }
                if (!copy) assertEquals(if (succeeded) 1 else 0, calls.get())
                assertCleared(owned)
                assertTerminal(original)
            } finally {
                start.countDown()
                if (drained) { execution?.clear(); original.clear() }
            }
          }
          println("NCT-008 ${if (copy) "copy" else "use"}: ${outcomes.joinToString(",") { if (it) "EXACT" else "REFUSED" }}")
        }
    }

    private data class Input(val salt: ByteArray, val verifier: ByteArray, val boot: Int, val elapsed: Long,
        val expectedSalt: ByteArray, val expectedVerifier: ByteArray) {
        fun prepare() = AndroidLocalFoundingVerifier(salt, verifier, boot, elapsed)
    }
    private data class Owned(val salt: ByteArray, val verifier: ByteArray)
    private fun borrow(value: AndroidLocalFoundingVerifier): Owned =
        value.copyIntoAccount { salt, verifier -> Owned(salt, verifier) }
    private fun assertExact(value: AndroidLocalFoundingVerifier, input: Input) {
        var calls = 0
        value.copyIntoAccount { salt, verifier -> calls++; assertBytes(salt, verifier, input) }
        assertEquals(1, calls)
        assertMetadata(value, input)
    }
    private fun assertMetadata(value: AndroidLocalFoundingVerifier, input: Input) {
        assertEquals(input.boot, value.boot)
        assertEquals(input.elapsed, value.createdElapsedMillis)
    }
    private fun assertBytes(salt: ByteArray, verifier: ByteArray, input: Input) {
        assertContentEquals(input.expectedSalt, salt)
        assertContentEquals(input.expectedVerifier, verifier)
    }
    private fun assertCleared(owned: Owned) {
        assertContentEquals(cleanupSalt, owned.salt)
        assertContentEquals(cleanupVerifier, owned.verifier)
    }
    private fun assertInputUnchanged(input: Input) {
        assertContentEquals(input.expectedSalt, input.salt)
        assertContentEquals(input.expectedVerifier, input.verifier)
    }
    private fun assertTerminal(value: AndroidLocalFoundingVerifier) {
        assertFailsWith<SelfOwnedFailureV1> { value.copyForExecution() }
        assertUseRefused(value)
    }
    private fun assertUseRefused(value: AndroidLocalFoundingVerifier) {
        var calls = 0
        assertFailsWith<SelfOwnedFailureV1> { value.copyIntoAccount { _, _ -> calls++ } }
        assertEquals(0, calls)
    }
    private fun await(gate: CountDownLatch) = assertTrue(gate.await(WATCHDOG_SECONDS, TimeUnit.SECONDS), "Harness gate timeout")
    private fun ordered(workers: ExecutorService, first: () -> Unit, second: () -> Unit) {
        val done = CountDownLatch(1)
        val a = workers.submit(Callable { try { first() } finally { done.countDown() } })
        val b = workers.submit(Callable { await(done); second() })
        a.get(WATCHDOG_SECONDS, TimeUnit.SECONDS)
        b.get(WATCHDOG_SECONDS, TimeUnit.SECONDS)
    }
    private fun withWorkers(body: (ExecutorService) -> Unit) {
        val workers = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "founding-transfer-test").apply { isDaemon = true }
        }
        try { body(workers) } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(WATCHDOG_SECONDS, TimeUnit.SECONDS), "Harness worker timeout")
        }
    }
    private fun fixture(id: String): Input {
        val row = document.getValue("inputs").jsonArray.single { it.jsonObject.getValue("id").jsonPrimitive.content == id }.jsonObject
        val salt = hex(row.getValue("salt_hex").jsonPrimitive.content)
        val verifier = hex(row.getValue("verifier_hex").jsonPrimitive.content)
        assertEquals(row.getValue("salt_bytes").jsonPrimitive.content.toInt(), salt.size)
        assertEquals(row.getValue("verifier_bytes").jsonPrimitive.content.toInt(), verifier.size)
        return Input(salt, verifier, row.getValue("observed_boot").jsonPrimitive.content.toInt(),
            row.getValue("created_elapsed_millis").jsonPrimitive.content.toLong(), salt.copyOf(), verifier.copyOf())
    }
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val document by lazy {
        val relative = "qa/pos/freezes/native-founding-credential-transfer-v1/fixtures.json"
        val file = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, relative) }.first { it.isFile }
        Json.parseToJsonElement(file.readText()).jsonObject
    }
    private val mutations by lazy {
        document.getValue("caller_mutations").jsonArray.associate { row ->
            val obj = row.jsonObject
            obj.getValue("id").jsonPrimitive.content to
                (hex(obj.getValue("salt_hex").jsonPrimitive.content) to hex(obj.getValue("verifier_hex").jsonPrimitive.content))
        }
    }
    private val cleanupSalt by lazy { hex(document.getValue("expected_cleanup").jsonObject.getValue("salt_hex").jsonPrimitive.content) }
    private val cleanupVerifier by lazy { hex(document.getValue("expected_cleanup").jsonObject.getValue("verifier_hex").jsonPrimitive.content) }
    private companion object { const val WATCHDOG_SECONDS = 5L }
}
