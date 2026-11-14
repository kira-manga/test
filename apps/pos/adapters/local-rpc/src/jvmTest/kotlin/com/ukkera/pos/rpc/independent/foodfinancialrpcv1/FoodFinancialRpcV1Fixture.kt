@file:OptIn(kotlin.time.ExperimentalTime::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.rpc.independent.foodfinancialrpcv1

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.LocalMasterFoodCashFinancialRpcProtocolV1
import com.ukkera.pos.database.LocalMasterRpcExecuteRequestV1
import com.ukkera.pos.database.LocalMasterRpcLookupRequestV1
import com.ukkera.pos.database.LocalMasterRpcOwnedRequestV1
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
import java.util.Collections
import java.util.UUID
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

/** Frozen requirements vectors only. These bytes and coordinates provide no authentication. */
internal object FoodFinancialRpcV1Frozen {
    private val location = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/food-financial-rpc-v1") }
        .firstOrNull { Files.isRegularFile(it.resolve("freeze.json")) }
        ?: error("Frozen FOOD financial RPC version 1 inputs are unavailable")

    val fixtures: JsonObject = run {
        val hashes = mapOf(
            "README.md" to "549a8d87b41d8c4ad848bceaf8f9327cac3fb1b5d61acac3b607594831355c95",
            "cases.md" to "77ebc205ff504658350d891b0709a8d49d7a834b25121f249d8bc96a9a579352",
            "fixtures.json" to "4461dfc58fc8f5e9d4e1df0ebdfc431dd8c92b8610f450a5bd6ef911549fbd01",
        )
        hashes.forEach { (name, expected) ->
            val actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(location.resolve(name)))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Frozen FOOD financial RPC input changed: " + name)
        }
        val freeze = Json.parseToJsonElement(Files.readString(location.resolve("freeze.json"))).jsonObject
        assertEquals("food-financial-rpc", freeze.getValue("design").jsonPrimitive.content)
        assertEquals("1.0.0", freeze.getValue("version").jsonPrimitive.content)
        assertEquals("FROZEN", freeze.getValue("freeze_state").jsonPrimitive.content)
        Json.parseToJsonElement(Files.readString(location.resolve("fixtures.json"))).jsonObject.also {
            assertEquals("food-financial-rpc-v1.0.0", it.getValue("fixture_version").jsonPrimitive.content)
        }
    }

    private val bearerFixture: JsonObject get() = fixtures.getValue("BEARER52").jsonObject
    fun bearer(): ByteArray = bearerFixture.getValue("bytes").jsonArray
        .map { it.jsonPrimitive.int.toByte() }.toByteArray().also {
            assertEquals(52, bearerFixture.getValue("length").jsonPrimitive.int)
            assertEquals(52, it.size)
        }
    val unsupportedLengths: List<Int> get() = bearerFixture.getValue("unsupported_lengths").jsonArray
        .map { it.jsonPrimitive.int }
    val capacity: Int get() = bearerFixture.getValue("decoded_capacity").jsonPrimitive.int
    val beforeExpiryMs: Long get() = bearerFixture.getValue("reap_before_threshold_milliseconds").jsonPrimitive.content.toLong()
    val atExpiryMs: Long get() = bearerFixture.getValue("reap_at_threshold_milliseconds").jsonPrimitive.content.toLong()
    fun inventory(): List<FoodFinancialCarrierKind> = buildList {
        repeat(bearerFixture.getValue("read_count").jsonPrimitive.int) { add(FoodFinancialCarrierKind.Read) }
        repeat(bearerFixture.getValue("execute_count").jsonPrimitive.int) { add(FoodFinancialCarrierKind.Execute) }
        repeat(bearerFixture.getValue("lookup_count").jsonPrimitive.int) { add(FoodFinancialCarrierKind.Lookup) }
    }

    fun id(label: String): DomainId = DomainId(UUID.nameUUIDFromBytes(("FFRC-v1-" + label).encodeToByteArray()).toString())
    fun operation(coordinate: Int = 0): OperationId = OperationId(id("operation-" + coordinate).value)
    fun identity(coordinate: Int = 0): OperationIdentity = OperationIdentity(
        id("R1"), operation(coordinate), id("A"), 1,
        MessageDigest.getInstance("SHA-256").digest(("FFRC-v1-original-" + coordinate).encodeToByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) },
    )
}

internal enum class FoodFinancialCarrierKind { Read, Execute, Lookup }

/** Full-length comparisons deliberately avoid including bearer contents in diagnostics. */
internal fun assertFoodBearer(expected: ByteArray, actual: ByteArray, label: String) {
    assertEquals(52, expected.size, label + ": expected length")
    assertEquals(52, actual.size, label + ": actual length")
    assertTrue(expected.contentEquals(actual), label + ": full bearer contents differ")
}

internal fun assertFoodCleared(actual: ByteArray, label: String) {
    assertEquals(52, actual.size, label + ": witnessed length")
    assertTrue(actual.all { it == 0.toByte() }, label + ": owned bearer is not cleared")
}

/** Read-only alias used exclusively to witness production wiping, never to drive it. */
internal fun foodRetainedBearer(request: LocalMasterRpcOwnedRequestV1): ByteArray =
    LocalMasterRpcOwnedRequestV1::class.java.getDeclaredField("bearer").let {
        it.isAccessible = true
        it.get(request) as ByteArray
    }

/** Carrier-only data: these payloads are deliberately not a FOOD command or native original. */
internal fun captureFoodCarrier(kind: FoodFinancialCarrierKind, caller: ByteArray, coordinate: Int = 0,
    identity: OperationIdentity = FoodFinancialRpcV1Frozen.identity(coordinate)): LocalMasterRpcOwnedRequestV1 {
    val credentials = LocalGatewayCredentials(FoodFinancialRpcV1Frozen.id("L1"), OpaqueCredential(caller))
    val payload = StoredBytes(("FFRC-carrier-only-" + coordinate).encodeToByteArray())
    return try {
        when (kind) {
            FoodFinancialCarrierKind.Read -> LocalMasterRpcReadRequestV1.capture(credentials,
                ReadRequest(ReadKey("sales.food.payment.read", 1), payload))
            FoodFinancialCarrierKind.Execute -> LocalMasterRpcExecuteRequestV1.capture(credentials,
                MutationRequest(FoodFinancialRpcV1Frozen.operation(coordinate), CommandKey("sales.food.payment", 1), payload))
            FoodFinancialCarrierKind.Lookup -> LocalMasterRpcLookupRequestV1.capture(credentials, identity)
        }
    } finally { credentials.bearer.destroy() }
}

internal fun encodeFoodCarrier(request: LocalMasterRpcOwnedRequestV1): String = when (request) {
    is LocalMasterRpcReadRequestV1 -> Json.encodeToString(LocalMasterFoodCashFinancialRpcProtocolV1.READ.request, request)
    is LocalMasterRpcExecuteRequestV1 -> Json.encodeToString(LocalMasterFoodCashFinancialRpcProtocolV1.EXECUTE.request, request)
    is LocalMasterRpcLookupRequestV1 -> Json.encodeToString(LocalMasterFoodCashFinancialRpcProtocolV1.LOOKUP.request, request)
    else -> error("Not a food-financial business request")
}

internal fun decodeFoodCarrier(kind: FoodFinancialCarrierKind, wire: String): LocalMasterRpcOwnedRequestV1 = when (kind) {
    FoodFinancialCarrierKind.Read -> Json.decodeFromString(LocalMasterFoodCashFinancialRpcProtocolV1.READ.request, wire)
    FoodFinancialCarrierKind.Execute -> Json.decodeFromString(LocalMasterFoodCashFinancialRpcProtocolV1.EXECUTE.request, wire)
    FoodFinancialCarrierKind.Lookup -> Json.decodeFromString(LocalMasterFoodCashFinancialRpcProtocolV1.LOOKUP.request, wire)
}

internal class FoodFinancialCarrierFixture {
    private val requests = Collections.synchronizedList(mutableListOf<LocalMasterRpcOwnedRequestV1>())
    private val loans = mutableListOf<HeldFoodBorrows>()
    private val waits = mutableListOf<FoodCleanupWait>()

    fun own(request: LocalMasterRpcOwnedRequestV1): LocalMasterRpcOwnedRequestV1 = request.also { requests.add(it) }
    fun capture(kind: FoodFinancialCarrierKind, caller: ByteArray, coordinate: Int = 0): LocalMasterRpcOwnedRequestV1 =
        own(captureFoodCarrier(kind, caller, coordinate))
    fun borrow(requests: List<LocalMasterRpcOwnedRequestV1>, expected: List<ByteArray>, label: String): HeldFoodBorrows =
        HeldFoodBorrows(requests, expected, label).also { loans.add(it); it.awaitAllEntered() }
    fun wait(scope: TestScope, label: String, block: suspend () -> Unit): FoodCleanupWait =
        FoodCleanupWait(scope, label, block).also { waits.add(it) }

    suspend fun close() {
        loans.forEach { it.releaseAll() }
        waits.forEach { it.cancel() }
        loans.forEach { it.awaitAllReturned() }
        requests.toList().forEach { it.destroy() }
        withContext(NonCancellable) {
            withTimeout(5.seconds) { requests.toList().forEach { it.awaitCleanup() } }
        }
    }
}

/** One synchronous thread holds all exact loans; reverse releases expose individual returns. */
internal class HeldFoodBorrows(
    private val requests: List<LocalMasterRpcOwnedRequestV1>,
    private val expected: List<ByteArray>, private val label: String,
) {
    private val entered = List(requests.size) { CountDownLatch(1) }
    private val releases = List(requests.size) { CountDownLatch(1) }
    private val returned = List(requests.size) { CountDownLatch(1) }
    private val aliases = List(requests.size) { AtomicReference<ByteArray>() }
    private val failure = AtomicReference<Throwable?>()
    private val finished = CountDownLatch(1)
    private val thread: Thread

    init {
        require(requests.isNotEmpty() && requests.size == expected.size)
        thread = Thread({
            try { hold(0) }
            catch (caught: Throwable) { failure.set(caught) }
            finally { entered.forEach { it.countDown() }; finished.countDown() }
        }, "food-financial-carrier-borrows").apply { isDaemon = true; start() }
    }

    private fun hold(index: Int) {
        requests[index].useBearer { bytes ->
            aliases[index].set(bytes)
            assertFoodBearer(expected[index], bytes, label + ": entered " + index)
            entered[index].countDown()
            if (index + 1 < requests.size) hold(index + 1)
            check(releases[index].await(30, TimeUnit.SECONDS)) { "Held FOOD borrower was not released" }
            assertFoodBearer(expected[index], bytes, label + ": held before return " + index)
        }
        assertFoodCleared(copy(index), label + ": returned " + index)
        returned[index].countDown()
    }

    fun copy(index: Int = 0): ByteArray = checkNotNull(aliases[index].get()) { "FOOD borrower did not enter" }
    fun awaitAllEntered() {
        assertTrue(entered.last().await(5, TimeUnit.SECONDS), label + ": missing borrow entry")
        failure.get()?.let { throw it }
        assertTrue(aliases.all { it.get() != null }, label + ": exact borrow census incomplete")
        assertFalse(finished.count == 0L, label + ": held borrower already ended")
    }
    fun release(index: Int = 0) { releases[index].countDown() }
    fun releaseAll() { releases.forEach { it.countDown() } }
    fun awaitReturned(index: Int = 0) {
        assertTrue(returned[index].await(5, TimeUnit.SECONDS), label + ": missing exact borrow return")
        failure.get()?.let { throw it }
        assertFoodCleared(copy(index), label + ": witnessed returned copy")
    }
    fun awaitAllReturned() {
        assertTrue(finished.await(5, TimeUnit.SECONDS), label + ": borrower did not finish")
        thread.join(1_000)
        assertFalse(thread.isAlive, label + ": borrower failed to stop")
        failure.get()?.let { throw it }
        aliases.forEach { alias -> alias.get()?.let { assertFoodCleared(it, label + ": final returned copy") } }
    }
}

internal class FoodCleanupWait(private val scope: TestScope, private val label: String, block: suspend () -> Unit) {
    private var entered = false
    private var succeeded = false
    private var cancelled = false
    private val task = scope.async(start = CoroutineStart.UNDISPATCHED) {
        entered = true
        try { block(); succeeded = true }
        catch (caught: CancellationException) { cancelled = true; throw caught }
    }
    fun assertPending() {
        scope.testScheduler.runCurrent()
        assertTrue(entered && task.isActive, label + ": wait did not suspend")
        assertFalse(succeeded, label + ": cleanup reported success while held")
    }
    fun cancel() { task.cancel() }
    suspend fun awaitSuccess() { task.await(); assertTrue(succeeded, label + ": cleanup success absent") }
    suspend fun awaitCancellation() {
        task.join()
        assertTrue(task.isCancelled && cancelled, label + ": waiter cancellation absent")
        assertFalse(succeeded, label + ": cancelled waiter became successful")
    }
}

internal class FoodFinancialDecoderFixture(private val owned: FoodFinancialCarrierFixture) {
    private var retired = false
    private val clock = TestTimeSource()
    private var milliseconds = 0L
    val custody = LocalRpcBusinessRequestCustody({ retired }, clock)
    val refused = Collections.synchronizedList(mutableListOf<LocalMasterRpcOwnedRequestV1>())
    var cleanupFailure: Throwable? = null

    fun advanceTo(value: Long) {
        require(value >= milliseconds)
        clock += (value - milliseconds).milliseconds
        milliseconds = value
    }
    fun retire() { retired = true; custody.deny() }
    private fun <Q : LocalMasterRpcOwnedRequestV1> retained(delegate: KSerializer<Q>): KSerializer<Q> = custody.serializer(
        delegate, { it.isCleanupComplete }, { it.destroyIfUnclaimed() },
        { refused.add(it); it.destroy() }, { cleanupFailure?.let { failure -> throw failure }; it.awaitCleanup() },
    )
    fun decode(kind: FoodFinancialCarrierKind, caller: ByteArray, coordinate: Int = 0): LocalMasterRpcOwnedRequestV1 {
        val temporary = captureFoodCarrier(kind, caller, coordinate)
        val decoded = try {
            val wire = encodeFoodCarrier(temporary)
            when (kind) {
                FoodFinancialCarrierKind.Read -> Json.decodeFromString(retained(LocalMasterFoodCashFinancialRpcProtocolV1.READ.request), wire)
                FoodFinancialCarrierKind.Execute -> Json.decodeFromString(retained(LocalMasterFoodCashFinancialRpcProtocolV1.EXECUTE.request), wire)
                FoodFinancialCarrierKind.Lookup -> Json.decodeFromString(retained(LocalMasterFoodCashFinancialRpcProtocolV1.LOOKUP.request), wire)
            }
        } finally {
            temporary.destroy()
            assertTrue(temporary.isCleanupComplete, "Temporary FOOD serializer carrier remains owned")
        }
        return owned.own(decoded)
    }

    /** Raw retained snapshot, including completed entries. No cleanup callback is invoked. */
    fun census(): List<LocalMasterRpcOwnedRequestV1> {
        val atomField = custody.javaClass.getDeclaredField("atom").apply { isAccessible = true }
        val snapshot = (atomField.get(custody) as StateFlow<*>).value!!
        val entries = snapshot.javaClass.getDeclaredField("requests").apply { isAccessible = true }.get(snapshot) as List<*>
        return entries.map { entry ->
            checkNotNull(entry)
            val closure = entry.javaClass.getDeclaredField("isCleanupComplete").apply { isAccessible = true }.get(entry)
            closure.javaClass.declaredFields.map { field -> field.isAccessible = true; field.get(closure) }
                .filterIsInstance<LocalMasterRpcOwnedRequestV1>().single()
        }
    }
    fun assertCensus(expected: List<LocalMasterRpcOwnedRequestV1>, label: String) {
        val actual = census()
        assertEquals(expected.size, actual.size, label + ": retained count")
        expected.forEach { exact -> assertEquals(1, actual.count { it === exact }, label + ": exact retained identity") }
    }
}
