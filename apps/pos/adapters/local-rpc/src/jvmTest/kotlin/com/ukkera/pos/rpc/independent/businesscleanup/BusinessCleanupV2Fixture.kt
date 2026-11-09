@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.rpc.independent.businesscleanup

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalMasterRpcExecuteRequestSerializerV1
import com.ukkera.pos.database.LocalMasterRpcExecuteRequestV1
import com.ukkera.pos.database.LocalMasterRpcLookupRequestSerializerV1
import com.ukkera.pos.database.LocalMasterRpcLookupRequestV1
import com.ukkera.pos.database.LocalMasterRpcOwnedRequestV1
import com.ukkera.pos.database.LocalMasterRpcReadRequestSerializerV1
import com.ukkera.pos.database.LocalMasterRpcReadRequestV1
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadKey
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.rpc.LocalRpcBusinessRequestCustody
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** Exact frozen v2 vectors. Logical coordinates below bind syntax only, never authority. */
internal object BusinessCleanupV2Frozen {
    private val location = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/business-rpc-request-cleanup-v2") }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen business request cleanup v2 inputs are unavailable")

    val fixtures: JsonObject = run {
        val hashes = mapOf(
            "README.md" to "c4f019d81cb66b1c000816f4a12b3af67f92cae8df076826e4a0e38643d4e2e6",
            "cases.md" to "b35d341465b0371f478c5b9e59d1c3ab9a3b7f128c87e9ed2c80fd25e6b1cf05",
            "fixtures.json" to "7c60a06e7c6c465512718b479cab18d148c1cad0e73f308aa6d596c1e75078dc",
        )
        hashes.forEach { (name, expected) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(location.resolve(name)))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Frozen business cleanup v2 artifact changed: $name")
        }
        Json.parseToJsonElement(Files.readString(location.resolve("fixtures.json"))).jsonObject.also {
            assertEquals("2.0", it.getValue("freeze_version").jsonPrimitive.content)
            assertEquals(15, it.getValue("template_count").jsonPrimitive.int)
            assertEquals(99, it.getValue("expanded_case_count").jsonPrimitive.int)
        }
    }

    fun bytes(name: String): ByteArray {
        val row = fixtures.getValue("bearers").jsonObject.getValue(name).jsonObject
        val bytes = row.getValue("hex").jsonPrimitive.content.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(52, row.getValue("length_bytes").jsonPrimitive.int)
        assertEquals(52, bytes.size)
        return bytes
    }

    fun clockMs(name: String): Long = fixtures.getValue("clock").jsonObject.getValue(name).jsonPrimitive.content.toLong()
    val capacity: Int get() = fixtures.getValue("decoder").jsonObject.getValue("retained_capacity").jsonPrimitive.int
    val borrowFault: String get() = fixtures.getValue("faults").jsonObject.getValue("borrow_body").jsonPrimitive.content

    fun inventoryKind(start: BusinessCleanupKind, index: Int): BusinessCleanupKind {
        val row = fixtures.getValue("inventory").jsonArray[index].jsonObject
        val name = row.getValue("type_by_rotation").jsonObject.getValue(start.code).jsonPrimitive.content
        return BusinessCleanupKind.entries.single { it.name == name }
    }

    fun inventoryCoordinate(index: Int): Int = fixtures.getValue("inventory").jsonArray[index].jsonObject
        .getValue("coordinates").jsonPrimitive.content.removePrefix("C").toInt()

    fun isClaimed(pattern: String, index: Int): Boolean {
        val row = fixtures.getValue("shutdown_claim_patterns").jsonObject.getValue(pattern).jsonObject
        val identity = fixtures.getValue("inventory").jsonArray[index].jsonObject.getValue("identity").jsonPrimitive.content
        return row.getValue("claimed_identities").jsonArray.any { it.jsonPrimitive.content == identity }
    }
}

internal enum class BusinessCleanupKind(val code: String) { Read("R"), Execute("E"), Lookup("L") }

/** Compares every byte without printing credential contents in failure messages. */
internal fun assertFull52(expected: ByteArray, actual: ByteArray, label: String) {
    assertEquals(52, expected.size, "$label: frozen carrier length")
    assertEquals(52, actual.size, "$label: witnessed carrier length")
    assertTrue(expected.contentEquals(actual), "$label: full 52-byte contents differ")
}

/** Read-only witness; this alias is never modified or used to drive production cleanup. */
internal fun retainedBearer(request: LocalMasterRpcOwnedRequestV1): ByteArray =
    LocalMasterRpcOwnedRequestV1::class.java.getDeclaredField("bearer").let {
        it.isAccessible = true
        it.get(request) as ByteArray
    }

internal fun captureBusinessRequest(kind: BusinessCleanupKind, caller: ByteArray, coordinate: Int = 0): LocalMasterRpcOwnedRequestV1 {
    val uuid = "00000000-0000-4000-8000-${coordinate.toString().padStart(12, '0')}"
    val credentials = LocalGatewayCredentials(DomainId(uuid), OpaqueCredential(caller))
    val payload = StoredBytes("C${coordinate.toString().padStart(3, '0')}".encodeToByteArray())
    return try {
        when (kind) {
            BusinessCleanupKind.Read -> LocalMasterRpcReadRequestV1.capture(credentials,
                ReadRequest(ReadKey("support.cleanup.read", 1), payload))
            BusinessCleanupKind.Execute -> LocalMasterRpcExecuteRequestV1.capture(credentials,
                MutationRequest(OperationId(uuid), CommandKey("support.cleanup.execute", 1), payload))
            BusinessCleanupKind.Lookup -> LocalMasterRpcLookupRequestV1.capture(credentials,
                OperationIdentity(DomainId("00000000-0000-4000-8000-000000000999"), OperationId(uuid),
                    DomainId("00000000-0000-4000-8000-000000000998"), 1, "0".repeat(64)))
        }
    } finally { credentials.bearer.destroy() }
}

internal class BusinessCleanupFixture {
    private val requests = mutableListOf<LocalMasterRpcOwnedRequestV1>()
    private val loans = mutableListOf<HeldBusinessBorrow>()
    private val waits = mutableListOf<CleanupWait>()

    fun own(request: LocalMasterRpcOwnedRequestV1): LocalMasterRpcOwnedRequestV1 = request.also { requests.add(it) }
    fun capture(kind: BusinessCleanupKind, caller: ByteArray, coordinate: Int = 0): LocalMasterRpcOwnedRequestV1 =
        own(captureBusinessRequest(kind, caller, coordinate))

    fun borrow(request: LocalMasterRpcOwnedRequestV1, expected: ByteArray, label: String,
        throwOnReturn: Boolean = false): HeldBusinessBorrow = HeldBusinessBorrow(request, expected, label, throwOnReturn)
        .also { loans.add(it); it.awaitEntered() }

    fun wait(scope: TestScope, label: String, block: suspend () -> Unit): CleanupWait =
        CleanupWait(scope, label, block).also { waits.add(it) }

    suspend fun close() {
        // Failure teardown releases synchronous bodies before any cleanup await.
        loans.forEach { it.release() }
        waits.forEach { it.cancel() }
        loans.forEach { it.awaitReturned() }
        requests.forEach { it.destroy() }
        withContext(NonCancellable) {
            withTimeout(2.seconds) { requests.forEach { it.awaitCleanup() } }
        }
    }
}

/** A real synchronous borrower on its own thread. Time bounds guard hangs, never prove pending. */
internal class HeldBusinessBorrow(request: LocalMasterRpcOwnedRequestV1, private val expected: ByteArray,
    private val label: String, private val throwOnReturn: Boolean) {
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val returned = CountDownLatch(1)
    private val alias = AtomicReference<ByteArray>()
    private val failure = AtomicReference<Throwable?>()
    private val thread = Thread({
        try {
            request.useBearer { bytes ->
                alias.set(bytes)
                assertFull52(expected, bytes, "$label: BORROW_ENTERED")
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS)) { "$label: BORROW_RELEASE was not delivered" }
                assertFull52(expected, bytes, "$label: held body before return")
                if (throwOnReturn) throw IllegalStateException(BusinessCleanupV2Frozen.borrowFault)
            }
        } catch (caught: Throwable) { failure.set(caught) }
        finally { entered.countDown(); returned.countDown() }
    }, "business-cleanup-borrow").apply { isDaemon = true; start() }

    val copy: ByteArray get() = checkNotNull(alias.get()) { "$label: borrow body did not enter" }

    fun awaitEntered() {
        assertTrue(entered.await(5, TimeUnit.SECONDS), "$label: missing BORROW_ENTERED")
        assertTrue(alias.get() != null, "$label: accepted borrow body unavailable")
        assertFalse(returned.count == 0L, "$label: borrow ended before BORROW_RELEASE")
    }

    fun release() { release.countDown() }

    fun awaitReturned() {
        assertTrue(returned.await(5, TimeUnit.SECONDS), "$label: missing BORROW_RETURNED/BORROW_THREW")
        thread.join(1_000)
        assertFalse(thread.isAlive, "$label: borrower failed to stop")
    }

    fun assertOutcome() {
        awaitReturned()
        if (throwOnReturn) {
            assertTrue(failure.get() is IllegalStateException, "$label: borrow fixture fault was lost")
            assertEquals(BusinessCleanupV2Frozen.borrowFault, failure.get()?.message, "$label: exact borrow fault")
        } else failure.get()?.let { throw it }
        assertFull52(BusinessCleanupV2Frozen.bytes("cleared_witnessed_owned_copy"), copy, "$label: returned copy")
    }
}

/** UNDISPATCHED entry returns only after the operation completes or reaches its suspension. */
internal class CleanupWait(private val scope: TestScope, private val label: String, block: suspend () -> Unit) {
    private var entered = false
    var succeeded: Boolean = false
        private set
    var cancelled: Boolean = false
        private set
    private val task = scope.async(start = CoroutineStart.UNDISPATCHED) {
        entered = true
        try { block(); succeeded = true }
        catch (caught: CancellationException) { cancelled = true; throw caught }
    }

    fun assertPending() {
        // Run the actual waiter's ready continuations to their next suspension.
        // A completed cleanup whose continuation was queued cannot count as pending.
        scope.testScheduler.runCurrent()
        assertTrue(entered, "$label: WAIT_ENTERED absent")
        assertTrue(task.isActive, "$label: wait did not suspend")
        assertFalse(succeeded, "$label: cleanup was reported complete while held")
    }

    fun cancel() { task.cancel() }
    suspend fun awaitSuccess() { task.await(); assertTrue(succeeded, "$label: WAIT_SUCCEEDED absent") }
    suspend fun awaitCancellation() {
        task.join()
        assertTrue(task.isCancelled && cancelled, "$label: WAIT_CANCELLED absent")
        assertFalse(succeeded, "$label: cancelled wait became successful")
    }
}

internal class BusinessCustodyFixture(val owned: BusinessCleanupFixture) {
    private var retired = false
    private val clock = TestTimeSource()
    private var clockMs = 0L
    val custody = LocalRpcBusinessRequestCustody({ retired }, clock)

    fun advanceTo(milliseconds: Long) {
        require(milliseconds >= clockMs)
        clock += (milliseconds - clockMs).milliseconds
        clockMs = milliseconds
    }

    fun retire() { retired = true; custody.deny() }

    private fun <T : LocalMasterRpcOwnedRequestV1> retained(delegate: KSerializer<T>): KSerializer<T> = custody.serializer(
        delegate, { it.isCleanupComplete }, { it.destroyIfUnclaimed() }, { it.destroy() }, { it.awaitCleanup() })

    fun decode(kind: BusinessCleanupKind, caller: ByteArray, coordinate: Int = 0): LocalMasterRpcOwnedRequestV1 {
        val captured = captureBusinessRequest(kind, caller, coordinate)
        val decoded = try {
            when (kind) {
                BusinessCleanupKind.Read -> Json.decodeFromString(retained(LocalMasterRpcReadRequestSerializerV1),
                    Json.encodeToString(LocalMasterRpcReadRequestSerializerV1, captured as LocalMasterRpcReadRequestV1))
                BusinessCleanupKind.Execute -> Json.decodeFromString(retained(LocalMasterRpcExecuteRequestSerializerV1),
                    Json.encodeToString(LocalMasterRpcExecuteRequestSerializerV1, captured as LocalMasterRpcExecuteRequestV1))
                BusinessCleanupKind.Lookup -> Json.decodeFromString(retained(LocalMasterRpcLookupRequestSerializerV1),
                    Json.encodeToString(LocalMasterRpcLookupRequestSerializerV1, captured as LocalMasterRpcLookupRequestV1))
            }
        } finally {
            captured.destroy()
            assertTrue(captured.isCleanupComplete, "Temporary serialized request cleanup did not complete")
        }
        return owned.own(decoded)
    }

    /** Raw physical snapshot, including completed entries. No function/closure is invoked. */
    fun census(): List<LocalMasterRpcOwnedRequestV1> {
        val atomField = custody.javaClass.getDeclaredField("atom").apply { isAccessible = true }
        val snapshot = (atomField.get(custody) as StateFlow<*>).value!!
        val requestsField = snapshot.javaClass.getDeclaredField("requests").apply { isAccessible = true }
        val entries = requestsField.get(snapshot) as List<*>
        return entries.map { entry ->
            checkNotNull(entry)
            val getter = entry.javaClass.getDeclaredField("isCleanupComplete").apply { isAccessible = true }.get(entry)
            getter.javaClass.declaredFields.map { field ->
                field.isAccessible = true
                field.get(getter)
            }.filterIsInstance<LocalMasterRpcOwnedRequestV1>().single()
        }
    }

    fun assertCensus(expected: List<LocalMasterRpcOwnedRequestV1>, label: String) {
        val actual = census()
        assertEquals(expected.size, actual.size, "$label: raw retained count")
        expected.forEach { exact -> assertEquals(1, actual.count { it === exact }, "$label: exact retained identity") }
    }
}
