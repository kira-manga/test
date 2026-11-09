package com.ukkera.pos.security.independent.windowsdpapi

import com.ukkera.pos.security.WindowsDpapiSecureIdentityStore
import dev.p2pkit.core.security.JvmSecureIdentityStore
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
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
 * Independent qualification for `WindowsDpapiSecureIdentityStore` against the
 * frozen `windows-dpapi-secure-identity-store-v1` cases.
 *
 * Every case is guarded by a real-Windows assumption. On Linux/macOS the suite
 * reports JUnit assumption skips; it must never be reported as a pass. The
 * harness uses only synthetic namespaces, deletes only the exact names it has
 * created in `finally`, and never enumerates registry keys, values or
 * application data. WDPAPI-010 reads only the exact value derived for its own
 * synthetic namespace.
 */
class WindowsDpapiSecureIdentityStoreNativeTest {

    @Test
    fun wdpapi001NonWindowsSkipsAndWindowsOpensGenuineStore() {
        fixture().use { fixture ->
            assertTrue(fixture.store is WindowsDpapiSecureIdentityStore)
            assertTrue(fixture.store is JvmSecureIdentityStore)
            assertEquals(
                "com.ukkera.pos.security.WindowsDpapiSecureIdentityStore",
                fixture.store.javaClass.name,
            )
        }
    }

    @Test
    fun wdpapi002AbsentNamespaceReadsNullAndDeletesFalse() {
        fixture().use { fixture ->
            val namespace = fixture.namespace("WDPAPI-002", "absent")
            assertNull(fixture.store.read(namespace))
            assertFalse(fixture.store.delete(namespace))
            assertNull(fixture.store.read(namespace))
            assertFalse(fixture.store.delete(namespace))
        }
    }

    @Test
    fun wdpapi003EmptyCycleAndLargeBinaryValuesRoundTripExactly() {
        fixture().use { fixture ->
            val payloads = listOf(
                "empty" to ByteArray(0),
                "byte-cycle" to ByteArray(256) { it.toByte() },
                "large-binary" to ByteArray(65_536) { ((it * 31 + 7) and 0xff).toByte() },
            )
            for ((label, payload) in payloads) {
                val namespace = fixture.namespace("WDPAPI-003", label)
                assertContentEquals(payload, fixture.store.putIfAbsent(namespace, payload))
                assertContentEquals(payload, assertNotNull(fixture.store.read(namespace)))
                assertContentEquals(payload, assertNotNull(fixture.store.read(namespace)))
            }
        }
    }

    @Test
    fun wdpapi004ExistingWinnerIsReturnedAndNeverReplaced() {
        fixture().use { fixture ->
            val namespace = fixture.namespace("WDPAPI-004", "winner")
            val first = synthetic("WDPAPI-004-first")
            val second = synthetic("WDPAPI-004-second")
            val third = synthetic("WDPAPI-004-third")

            assertContentEquals(first, fixture.store.putIfAbsent(namespace, first))
            assertContentEquals(first, fixture.store.putIfAbsent(namespace, second))
            assertContentEquals(first, fixture.store.putIfAbsent(namespace, third))
            assertContentEquals(first, assertNotNull(fixture.store.read(namespace)))
            assertFalse(assertNotNull(fixture.store.read(namespace)).contentEquals(second))
            assertFalse(assertNotNull(fixture.store.read(namespace)).contentEquals(third))
        }
    }

    @Test
    fun wdpapi005InputsAndOutputsAreDefensiveCopies() {
        fixture().use { fixture ->
            val namespace = fixture.namespace("WDPAPI-005", "defensive")
            val expected = synthetic("WDPAPI-005-durable")
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

            val existingWinner = fixture.store.putIfAbsent(namespace, synthetic("WDPAPI-005-loser"))
            assertContentEquals(expected, existingWinner)
            existingWinner.fill(0x7d.toByte())
            assertContentEquals(expected, assertNotNull(fixture.store.read(namespace)))
        }
    }

    @Test
    fun wdpapi006DeleteRemovesExactRecordAndIsIdempotent() {
        fixture().use { fixture ->
            val namespace = fixture.namespace("WDPAPI-006", "delete")
            val first = synthetic("WDPAPI-006-first")
            val second = synthetic("WDPAPI-006-second")

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
    fun wdpapi007SameProcessConcurrentPutIfAbsentHasExactlyOneWinner() {
        fixture().use { fixture ->
            val namespace = fixture.namespace("WDPAPI-007", "concurrent")
            val workerCount = 8
            val payloads = (0 until workerCount).map {
                "WDPAPI-007-worker-$it".toByteArray(StandardCharsets.UTF_8)
            }
            val ready = CountDownLatch(workerCount)
            val start = CountDownLatch(1)
            val executor = Executors.newFixedThreadPool(workerCount)
            val futures = payloads.map { payload ->
                executor.submit<ByteArray> {
                    ready.countDown()
                    check(start.await(30, TimeUnit.SECONDS)) {
                        "concurrent WDPAPI-007 workers did not receive the start signal"
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
                        fail("WDPAPI-007 worker failed", failure.cause ?: failure)
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
    fun wdpapi008IndependentOpenInstancesShareDurableStore() {
        fixture().use { fixture ->
            val namespace = fixture.namespace("WDPAPI-008", "shared")
            val first = synthetic("WDPAPI-008-first")
            val second = synthetic("WDPAPI-008-second")
            val third = synthetic("WDPAPI-008-third")

            assertContentEquals(first, fixture.store.putIfAbsent(namespace, first))
            val secondStore = WindowsDpapiSecureIdentityStore.open()
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
    fun wdpapi009DistinctNamespacesDoNotCollideOrLeak() {
        fixture().use { fixture ->
            val shapes = listOf("/", "/a", "/a/b", ":a", "::a", ".a")
            val namespaces = shapes.mapIndexed { index, shape ->
                fixture.namespace("WDPAPI-009", "shape-$index$shape")
            }
            val payloads = namespaces.mapIndexed { index, _ ->
                synthetic("WDPAPI-009-payload-$index")
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
                    fixture.store.putIfAbsent(namespace, synthetic("WDPAPI-009-loser-$index")),
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

    @Test
    fun wdpapi010ExactSyntheticRecordIsHkcuDpapiCiphertext() {
        fixture().use { fixture ->
            val namespace = fixture.namespace("WDPAPI-010", "at-rest")
            val payload = synthetic("WDPAPI-010-known-payload")
            val digest = sha256(namespace.toByteArray(StandardCharsets.UTF_8))
            val valueName = hex(digest)
            val entropy = sha256(ENTROPY_LABEL, digest)

            assertContentEquals(payload, fixture.store.putIfAbsent(namespace, payload))
            assertContentEquals(payload, assertNotNull(fixture.store.read(namespace)))

            val probe = readExactRegistryRecord(valueName, entropy)
            assertTrue(probe.hkcuPresent, "exact synthetic HKCU record is absent after write")
            assertFalse(probe.machinePresent, "machine-wide hive contains the exact synthetic record")
            val stored = assertNotNull(probe.stored)
            val plain = assertNotNull(probe.plain)
            assertFalse(stored.contentEquals(payload), "registry value is plaintext")
            assertFalse(containsSubsequence(stored, payload), "registry value contains plaintext")

            val outerCipher = parseOuterEnvelope(stored, digest)
            assertFalse(containsSubsequence(outerCipher, payload), "DPAPI input contains plaintext")
            val innerPayload = parseInnerEnvelope(plain, digest)
            assertContentEquals(payload, innerPayload)

            assertContentEquals(payload, assertNotNull(fixture.store.read(namespace)))
            assertTrue(fixture.store.delete(namespace))
            assertNull(fixture.store.read(namespace))

            val afterDelete = readExactRegistryRecord(valueName, entropy)
            assertFalse(afterDelete.hkcuPresent, "exact synthetic HKCU record remains after delete")
            assertFalse(afterDelete.machinePresent, "machine-wide exact synthetic record remains after delete")
        }
    }

    private fun fixture(): Fixture {
        assumeTrue(
            "WDPAPI native cases require a genuine Windows host; current os.name=" +
                System.getProperty("os.name"),
            isWindows(),
        )
        return Fixture(WindowsDpapiSecureIdentityStore.open())
    }

    private class Fixture(
        val store: WindowsDpapiSecureIdentityStore,
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

    private data class ExactRegistryProbe(
        val hkcuPresent: Boolean,
        val stored: ByteArray?,
        val plain: ByteArray?,
        val machinePresent: Boolean,
    )

    private companion object {
        const val NAMESPACE_PREFIX = "ukkera-pos-wdpapi-"
        const val REGISTRY_PATH = "Software\\Ukkera\\POS\\SecureIdentityV1"
        const val FORMAT_VERSION = 1
        const val MAX_PROCESS_OUTPUT = 2_000

        val RUN_ID: String = ByteArray(16).also(SecureRandom()::nextBytes).joinToString("") {
            (it.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        val ENTROPY_LABEL =
            "ukkera.pos.windows-dpapi-secure-identity.v1\u0000".toByteArray(StandardCharsets.UTF_8)
        val OUTER_MAGIC = "UKPID001".toByteArray(StandardCharsets.US_ASCII)
        val INNER_MAGIC = "UKPIV001".toByteArray(StandardCharsets.US_ASCII)

        fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

        fun synthetic(label: String): ByteArray =
            "synthetic:$label".toByteArray(StandardCharsets.UTF_8)

        fun sha256(vararg values: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            for (value in values) digest.update(value)
            return digest.digest()
        }

        fun hex(value: ByteArray): String = buildString(value.size * 2) {
            for (byte in value) {
                val unsigned = byte.toInt() and 0xff
                append("0123456789abcdef"[unsigned ushr 4])
                append("0123456789abcdef"[unsigned and 0x0f])
            }
        }

        fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
            if (needle.isEmpty()) return true
            if (needle.size > haystack.size) return false
            outer@ for (start in 0..haystack.size - needle.size) {
                for (index in needle.indices) {
                    if (haystack[start + index] != needle[index]) continue@outer
                }
                return true
            }
            return false
        }

        fun parseOuterEnvelope(stored: ByteArray, expectedDigest: ByteArray): ByteArray {
            val buffer = ByteBuffer.wrap(stored)
            val magic = ByteArray(OUTER_MAGIC.size).also { buffer.get(it) }
            assertContentEquals(OUTER_MAGIC, magic)
            assertEquals(FORMAT_VERSION, buffer.int)
            val digest = ByteArray(32).also { buffer.get(it) }
            assertContentEquals(expectedDigest, digest)
            val cipherLength = buffer.int
            assertTrue(cipherLength > 0)
            assertEquals(buffer.remaining(), cipherLength)
            return ByteArray(cipherLength).also { buffer.get(it) }
        }

        fun parseInnerEnvelope(plain: ByteArray, expectedDigest: ByteArray): ByteArray {
            val buffer = ByteBuffer.wrap(plain)
            val magic = ByteArray(INNER_MAGIC.size).also { buffer.get(it) }
            assertContentEquals(INNER_MAGIC, magic)
            val digest = ByteArray(32).also { buffer.get(it) }
            assertContentEquals(expectedDigest, digest)
            val valueLength = buffer.int
            assertTrue(valueLength >= 0)
            assertEquals(buffer.remaining(), valueLength)
            return ByteArray(valueLength).also { buffer.get(it) }
        }

        fun readExactRegistryRecord(valueName: String, entropy: ByteArray): ExactRegistryProbe {
            val script = """
                ${'$'}ErrorActionPreference = 'Stop'
                Add-Type -AssemblyName System.Security
                ${'$'}path = '$REGISTRY_PATH'
                ${'$'}name = '$valueName'
                ${'$'}machineValue = ${'$'}null
                ${'$'}machine = [Microsoft.Win32.Registry]::LocalMachine.OpenSubKey(${'$'}path, ${'$'}false)
                if (${'$'}null -ne ${'$'}machine) {
                    try {
                        ${'$'}machineValue = ${'$'}machine.GetValue(${'$'}name)
                    } finally {
                        ${'$'}machine.Close()
                    }
                }
                if (${'$'}null -ne ${'$'}machineValue) { Write-Output 'MACHINE_PRESENT' } else { Write-Output 'MACHINE_ABSENT' }
                ${'$'}value = ${'$'}null
                ${'$'}key = [Microsoft.Win32.Registry]::CurrentUser.OpenSubKey(${'$'}path, ${'$'}false)
                if (${'$'}null -ne ${'$'}key) {
                    try {
                        ${'$'}value = ${'$'}key.GetValue(${'$'}name)
                    } finally {
                        ${'$'}key.Close()
                    }
                }
                if (${'$'}null -eq ${'$'}value) { Write-Output 'HKCU_ABSENT'; exit 0 }
                if (-not (${'$'}value -is [byte[]])) { throw 'The exact HKCU value is not REG_BINARY' }
                ${'$'}entropy = [Convert]::FromBase64String('${Base64.getEncoder().encodeToString(entropy)}')
                # The frozen outer envelope is asserted independently below in Kotlin.
                # DPAPI receives its ciphertext field, not the application header.
                ${'$'}headerLength = ${OUTER_MAGIC.size + 4 + 32 + 4}
                if (${'$'}value.Length -le ${'$'}headerLength) { throw 'The exact HKCU outer envelope is truncated' }
                ${'$'}cipher = New-Object byte[] (${'$'}value.Length - ${'$'}headerLength)
                [Array]::Copy(${'$'}value, ${'$'}headerLength, ${'$'}cipher, 0, ${'$'}cipher.Length)
                ${'$'}plain = [System.Security.Cryptography.ProtectedData]::Unprotect(${'$'}cipher, ${'$'}entropy, [System.Security.Cryptography.DataProtectionScope]::CurrentUser)
                Write-Output 'HKCU_PRESENT'
                Write-Output ('VALUE=' + [Convert]::ToBase64String(${'$'}value))
                Write-Output ('PLAIN=' + [Convert]::ToBase64String(${'$'}plain))
            """.trimIndent()
            val output = runPowerShell(script)
            val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val machinePresent = when {
                "MACHINE_PRESENT" in lines -> true
                "MACHINE_ABSENT" in lines -> false
                else -> fail("WDPAPI-010 machine-hive probe produced no result: $output")
            }
            val hkcuPresent = when {
                "HKCU_PRESENT" in lines -> true
                "HKCU_ABSENT" in lines -> false
                else -> fail("WDPAPI-010 HKCU probe produced no result: $output")
            }
            if (!hkcuPresent) {
                return ExactRegistryProbe(false, null, null, machinePresent)
            }
            val encodedValue = lines.singleOrNull { it.startsWith("VALUE=") }
                ?: fail("WDPAPI-010 did not return the exact registry value")
            val encodedPlain = lines.singleOrNull { it.startsWith("PLAIN=") }
                ?: fail("WDPAPI-010 did not return the DPAPI plaintext")
            return ExactRegistryProbe(
                hkcuPresent = true,
                stored = Base64.getDecoder().decode(encodedValue.substringAfter("VALUE=")),
                plain = Base64.getDecoder().decode(encodedPlain.substringAfter("PLAIN=")),
                machinePresent = machinePresent,
            )
        }

        fun runPowerShell(script: String): String {
            val encoded = Base64.getEncoder().encodeToString(
                script.toByteArray(StandardCharsets.UTF_16LE),
            )
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-EncodedCommand",
                encoded,
            ).redirectErrorStream(true).start()
            val finished = process.waitFor(30, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                fail("WDPAPI-010 Windows registry/DPAPI probe timed out")
            }
            val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
            if (process.exitValue() != 0) {
                fail(
                    "WDPAPI-010 Windows registry/DPAPI probe failed with exit " +
                        "${process.exitValue()}: ${output.take(MAX_PROCESS_OUTPUT)}",
                )
            }
            return output
        }
    }
}
