package com.ukkera.pos.database

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** Child performs real Room IO and halts without graceful connection/transaction cleanup. */
object StoreChildProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2)
        val f = StoreFixture(Path.of(args[0]))
        val cut = args[1]
        val store = f.store(StoreFaultProbe {
            if (it.name == cut) Runtime.getRuntime().halt(23)
        })
        val reply = store.execute(testPrincipal(), f.request())
        check(reply is MutationReply.Recorded && reply.committed)
        f.close()
    }
}

class StoreProcessRecoveryTest {
    private fun start(path: Path, point: String): Process {
        val classpath = requireNotNull(System.getProperty("ukkera.test.runtime.classpath"))
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        return ProcessBuilder(java, "-Xmx128m", "-cp", classpath, StoreChildProbe::class.java.name, path.toString(), point)
            .redirectErrorStream(true).start()
    }
    private fun finish(process: Process): Int {
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS)
            fail("Synthetic storage probe timed out")
        }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(output.length < 100_000, "Unexpected child output size")
        if (process.exitValue() !in listOf(0, 23)) fail("Storage child failed: ${output.take(4_000)}")
        return process.exitValue()
    }

    @Test fun killedBeforeCommitReopensWithoutPartialEffectsAndSameIdCanCommit() = runTest {
        val f = StoreFixture(); f.seed(); f.close()
        assertEquals(23, finish(start(f.path, StoreFaultPoint.AFTER_EFFECTS.name)))
        val reopened = StoreFixture(f.path)
        try {
            for (table in listOf("operation_result", "journal_envelope", "outbox_intent", "audit_event")) assertEquals(0L, reopened.count(table))
            val result = assertIs<MutationReply.Recorded>(reopened.store().execute(testPrincipal(), reopened.request()))
            assertTrue(result.committed); assertFalse(result.replayed)
        } finally { reopened.close() }
    }

    @Test fun killedAfterCommitBeforeReplyRecoversExactlyOnceInFreshProcess() = runTest {
        val f = StoreFixture(); f.seed(); f.close()
        assertEquals(23, finish(start(f.path, StoreFaultPoint.AFTER_COMMIT.name)))
        // A second independent JVM handles the same request; no parent process state.
        assertEquals(0, finish(start(f.path, "NONE")))
        val reopened = StoreFixture(f.path)
        try {
            assertEquals(1L, reopened.count("operation_result")); assertEquals(1L, reopened.count("journal_envelope"))
            assertEquals(1L, reopened.count("audit_event")); assertEquals(2L, reopened.count("outbox_intent"))
            assertTrue(assertIs<MutationReply.Recorded>(reopened.store().execute(testPrincipal(), reopened.request())).replayed)
        } finally { reopened.close() }
    }

    @Test fun independentProcessesRaceSameIdOnOneFileWithoutDoubleCommit() = runTest {
        val f = StoreFixture(); f.seed(); f.close()
        val first = start(f.path, "NONE"); val second = start(f.path, "NONE")
        assertEquals(0, finish(first)); assertEquals(0, finish(second))
        val reopened = StoreFixture(f.path)
        try { assertEquals(1L, reopened.count("operation_result")); assertEquals(1L, reopened.count("journal_envelope")) }
        finally { reopened.close() }
    }
}
