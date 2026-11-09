package com.ukkera.pos.security.independent.installerattempttransferv1

import com.ukkera.pos.security.AndroidLocalFoundingVerifier
import com.ukkera.pos.security.SelfOwnedFailureV1
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen installer-attempt-transfer v1 IAT-P001/P002/P003 supporting helper wiring.
 * Synthetic scratch is opaque data, never a native reservation, derived credential,
 * Approval or grant. IAT-N001..005 remain NOT RUN. Invalid-admission transient-copy
 * counts have no observer at this unchanged boundary and remain unverified.
 * Each gate, future and worker-termination wait has a five-second harness watchdog;
 * it is unrelated to the native operations' frozen 30,000 ms budgets.
 */
class AndroidLocalInstallerAttemptTransferV1HostTest {
    @Test fun iatP001ExactScratchAndZeroMetadata() {
        for (id in listOf("IAT-F-SCRATCH", "IAT-F-ZERO-METADATA")) {
            val input = fixture(id)
            val prepared = input.prepare()
            var execution: AndroidLocalFoundingVerifier? = null
            try {
                execution = prepared.copyForExecution()
                observeExact(execution, input)
                assertInputUnchanged(input)
            } finally {
                execution?.clear()
                prepared.clear()
            }
        }
    }

    @Test fun iatP001InvalidScratchRefusesWithoutConsumerOrTransfer() {
        // IAT-P001's zero-transient-copy count cannot be observed at this unchanged
        // helper. This method wires the remaining frozen refusal assertions only.
        for ((id, input) in invalidFixtures()) {
            var transfer: AndroidLocalFoundingVerifier? = null
            var calls = 0
            try {
                assertFailsWith<SelfOwnedFailureV1>(id) {
                    transfer = input.prepare()
                    transfer.copyIntoAccount { _, _ -> calls++ }
                }
                assertNull(transfer, id)
                assertEquals(expectedInvalidCalls, calls, id)
                assertInputUnchanged(input)
            } finally {
                transfer?.clear()
            }
        }
    }

    @Test fun iatP002IndependentOwnershipAndTerminalCleanup() {
        val input = fixture("IAT-F-SCRATCH")
        val prepared = input.prepare()
        val preparedOwned = borrow(prepared)
        var execution: AndroidLocalFoundingVerifier? = null
        var executionOwned: Owned? = null
        try {
            execution = prepared.copyForExecution()
            pattern.salt.copyInto(input.salt)
            pattern.verifier.copyInto(input.verifier)

            prepared.clear()
            assertCleared(preparedOwned)
            assertCallerPattern(input)
            executionOwned = observeExact(execution, input)
            assertNotSame(preparedOwned.salt, executionOwned.salt)
            assertNotSame(preparedOwned.verifier, executionOwned.verifier)

            execution.clear()
            assertCleared(executionOwned)
            assertCallerPattern(input)
            prepared.clear()
            execution.clear()
            assertCleared(preparedOwned)
            assertCleared(executionOwned)
            assertTerminal(prepared)
            assertTerminal(execution)
            assertCallerPattern(input)
        } finally {
            execution?.clear()
            prepared.clear()
        }
        assertCleared(preparedOwned)
        executionOwned?.let(::assertCleared)
        assertCallerPattern(input)
    }

    @Test fun iatP003CopyCompleteBeforeClear() = withWorkers { workers ->
        requireSchedule("copy_complete_before_clear")
        val input = fixture("IAT-F-SCRATCH")
        val prepared = input.prepare()
        val preparedOwned = borrow(prepared)
        var execution: AndroidLocalFoundingVerifier? = null
        var executionOwned: Owned? = null
        var joined = false
        try {
            ordered(workers, { execution = prepared.copyForExecution() }, { prepared.clear() })
            joined = true
            val copy = assertNotNull(execution)
            executionOwned = observeExact(copy, input)
            assertNotSame(preparedOwned.salt, executionOwned.salt)
            assertNotSame(preparedOwned.verifier, executionOwned.verifier)
            assertCleared(preparedOwned)
            assertTerminal(prepared)
            assertInputUnchanged(input)
        } finally {
            // A failed watchdog may leave a worker holding the helper monitor.
            // Do not reacquire it in teardown or make an erasure claim for that run.
            if (joined) {
                execution?.clear()
                prepared.clear()
            }
        }
        executionOwned?.let(::assertCleared)
    }

    @Test fun iatP003ClearCompleteBeforeCopy() = withWorkers { workers ->
        requireSchedule("clear_complete_before_copy")
        val input = fixture("IAT-F-SCRATCH")
        val prepared = input.prepare()
        val preparedOwned = borrow(prepared)
        var joined = false
        try {
            ordered(workers, { prepared.clear() }, { assertCopyRefused(prepared) })
            joined = true
            assertCleared(preparedOwned)
            assertTerminal(prepared)
            assertInputUnchanged(input)
        } finally {
            if (joined) prepared.clear()
        }
    }

    @Test fun iatP003UseCompleteBeforeClear() = withWorkers { workers ->
        requireSchedule("use_complete_before_clear")
        val input = fixture("IAT-F-SCRATCH")
        val prepared = input.prepare()
        val preparedOwned = borrow(prepared)
        var joined = false
        try {
            ordered(workers, { observeExact(prepared, input) }, { prepared.clear() })
            joined = true
            assertCleared(preparedOwned)
            assertTerminal(prepared)
            assertInputUnchanged(input)
        } finally {
            if (joined) prepared.clear()
        }
    }

    @Test fun iatP003ClearCompleteBeforeUse() = withWorkers { workers ->
        requireSchedule("clear_complete_before_use")
        val input = fixture("IAT-F-SCRATCH")
        val prepared = input.prepare()
        val preparedOwned = borrow(prepared)
        var joined = false
        try {
            ordered(workers, { prepared.clear() }, { assertUseRefused(prepared) })
            joined = true
            assertCleared(preparedOwned)
            assertTerminal(prepared)
            assertInputUnchanged(input)
        } finally {
            if (joined) prepared.clear()
        }
    }

    @Test fun iatP003ConsumerEnteredThenClearRequested() = withWorkers { workers ->
        requireSchedule("consumer_entered_then_clear_requested")
        val input = fixture("IAT-F-SCRATCH")
        val prepared = input.prepare()
        val preparedOwned = borrow(prepared)
        val entered = CountDownLatch(1)
        val clearRequested = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        var joined = false
        try {
            val use = workers.submit(Callable {
                prepared.copyIntoAccount { salt, verifier ->
                    calls.incrementAndGet()
                    assertBytes(salt, verifier, input)
                    assertMetadata(prepared, input)
                    entered.countDown()
                    await(release)
                    assertBytes(salt, verifier, input)
                    assertMetadata(prepared, input)
                }
            })
            val clear = workers.submit(Callable {
                await(entered)
                clearRequested.countDown()
                prepared.clear()
            })
            await(clearRequested)
            release.countDown()
            use.get(WATCHDOG_SECONDS, TimeUnit.SECONDS)
            clear.get(WATCHDOG_SECONDS, TimeUnit.SECONDS)
            joined = true
            assertEquals(expectedValidCalls, calls.get())
            assertCleared(preparedOwned)
            assertTerminal(prepared)
            assertInputUnchanged(input)
        } finally {
            release.countDown()
            if (joined) prepared.clear()
        }
    }

    private data class Input(
        val salt: ByteArray,
        val verifier: ByteArray,
        val boot: Int,
        val elapsed: Long,
        val expectedSalt: ByteArray,
        val expectedVerifier: ByteArray
    ) {
        fun prepare() = AndroidLocalFoundingVerifier(salt, verifier, boot, elapsed)
    }

    private data class Owned(val salt: ByteArray, val verifier: ByteArray)

    private fun borrow(value: AndroidLocalFoundingVerifier): Owned =
        value.copyIntoAccount { salt, verifier -> Owned(salt, verifier) }

    private fun observeExact(value: AndroidLocalFoundingVerifier, input: Input): Owned {
        var calls = 0
        val owned = value.copyIntoAccount { salt, verifier ->
            calls++
            assertBytes(salt, verifier, input)
            assertMetadata(value, input)
            Owned(salt, verifier)
        }
        assertEquals(expectedValidCalls, calls)
        return owned
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
        assertContentEquals(zero.salt, owned.salt)
        assertContentEquals(zero.verifier, owned.verifier)
    }

    private fun assertInputUnchanged(input: Input) {
        assertContentEquals(input.expectedSalt, input.salt)
        assertContentEquals(input.expectedVerifier, input.verifier)
    }

    private fun assertCallerPattern(input: Input) {
        assertContentEquals(pattern.salt, input.salt)
        assertContentEquals(pattern.verifier, input.verifier)
    }

    private fun assertTerminal(value: AndroidLocalFoundingVerifier) {
        assertCopyRefused(value)
        assertUseRefused(value)
    }

    private fun assertCopyRefused(value: AndroidLocalFoundingVerifier) {
        var copy: AndroidLocalFoundingVerifier? = null
        try {
            assertFailsWith<SelfOwnedFailureV1> { copy = value.copyForExecution() }
            assertNull(copy)
        } finally {
            copy?.clear()
        }
    }

    private fun assertUseRefused(value: AndroidLocalFoundingVerifier) {
        var calls = 0
        assertFailsWith<SelfOwnedFailureV1> {
            value.copyIntoAccount { _, _ -> calls++ }
        }
        assertEquals(expectedInvalidCalls, calls)
    }

    private fun await(gate: CountDownLatch) =
        assertTrue(gate.await(WATCHDOG_SECONDS, TimeUnit.SECONDS), "Harness gate timeout")

    private fun ordered(workers: ExecutorService, first: () -> Unit, second: () -> Unit) {
        val done = CountDownLatch(1)
        val a = workers.submit(Callable { try { first() } finally { done.countDown() } })
        val b = workers.submit(Callable { await(done); second() })
        a.get(WATCHDOG_SECONDS, TimeUnit.SECONDS)
        b.get(WATCHDOG_SECONDS, TimeUnit.SECONDS)
    }

    private fun withWorkers(body: (ExecutorService) -> Unit) {
        val workers = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "installer-attempt-transfer-test").apply { isDaemon = true }
        }
        var primary: Throwable? = null
        try {
            body(workers)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                workers.shutdownNow()
                assertTrue(workers.awaitTermination(WATCHDOG_SECONDS, TimeUnit.SECONDS), "Harness worker timeout")
            } catch (failure: Throwable) {
                val original = primary
                if (original == null) throw failure
                original.addSuppressed(failure)
            }
        }
    }

    private fun fixture(id: String): Input {
        val row = inputs.getValue(id).jsonObject
        val base = row["base"]?.let { inputs.getValue(it.jsonPrimitive.content).jsonObject }
        return inputFrom(if (base == null) row else JsonObject(base + row))
    }

    private fun invalidFixtures(): List<Pair<String, Input>> {
        val base = inputs.getValue("IAT-F-SCRATCH").jsonObject
        return inputs.getValue("IAT-F-INVALID").jsonArray.map { variant ->
            val row = variant.jsonObject
            val field = row.getValue("field").jsonPrimitive.content
            row.getValue("id").jsonPrimitive.content to inputFrom(
                JsonObject(base + (field to row.getValue("value")))
            )
        }
    }

    private fun inputFrom(row: JsonObject): Input {
        val salt = hex(row.getValue("salt_hex").jsonPrimitive.content)
        val verifier = hex(row.getValue("verifier_hex").jsonPrimitive.content)
        return Input(
            salt, verifier,
            row.getValue("observed_boot").jsonPrimitive.content.toInt(),
            row.getValue("created_elapsed_millis").jsonPrimitive.content.toLong(),
            salt.copyOf(), verifier.copyOf()
        )
    }

    private fun buffers(id: String): Owned {
        val row = inputs.getValue(id).jsonObject
        return Owned(
            hex(row.getValue("salt_hex").jsonPrimitive.content),
            hex(row.getValue("verifier_hex").jsonPrimitive.content)
        )
    }

    private fun hex(value: String): ByteArray =
        value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun requireSchedule(id: String) {
        val schedules = document.getValue("variants").jsonObject
            .getValue("IAT-S-PORTABLE").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(id in schedules, "Missing frozen schedule: $id")
    }

    private val document by lazy {
        val relative = "qa/pos/designs/installer-attempt-transfer/fixtures.json"
        val file = generateSequence(File(checkNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .map { File(it, relative) }.first { it.isFile }
        Json.parseToJsonElement(file.readText()).jsonObject
    }
    private val inputs by lazy { document.getValue("inputs").jsonObject }
    private val pattern by lazy { buffers("IAT-M-PATTERN") }
    private val zero by lazy { buffers("IAT-M-ZERO") }
    private val expectedValidCalls by lazy {
        document.getValue("portable_expected").jsonObject
            .getValue("valid_consumer_invocations").jsonPrimitive.content.toInt()
    }
    private val expectedInvalidCalls by lazy {
        document.getValue("portable_expected").jsonObject
            .getValue("invalid_consumer_invocations").jsonPrimitive.content.toInt()
    }
    private companion object { const val WATCHDOG_SECONDS = 5L }
}
