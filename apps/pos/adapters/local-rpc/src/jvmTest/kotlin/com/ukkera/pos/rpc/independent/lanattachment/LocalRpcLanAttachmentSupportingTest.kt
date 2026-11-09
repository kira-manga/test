package com.ukkera.pos.rpc.independent.lanattachment

import com.ukkera.pos.database.LocalMasterRpcPeerRegistration
import com.ukkera.pos.rpc.LocalRpcFactoryOwner
import com.ukkera.pos.rpc.LocalRpcFactoryPhase
import com.ukkera.pos.rpc.LocalRpcOriginalClientOwner
import com.ukkera.pos.rpc.LocalRpcOriginalClientSource
import com.ukkera.pos.rpc.LocalRpcOriginalHostOwner
import com.ukkera.pos.rpc.LocalRpcOriginalHostSource
import com.ukkera.pos.rpc.LocalRpcSelectedMasterClientReadback
import com.ukkera.pos.rpc.LocalRpcSelectedMasterClientSource
import com.ukkera.pos.rpc.independent.factorylifecycle.FactoryReadbackInputs
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import com.ukkera.pos.security.LocalMasterSdkOriginalV1
import dev.p2pkit.core.AppId
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.core.security.JvmSecureIdentityStore
import dev.p2pkit.rpc.RpcEndpoint
import dev.p2pkit.rpc.RpcLocalIdentity
import dev.p2pkit.rpc.RpcPlatform
import dev.p2pkit.rpc.RpcTrustPurpose
import dev.p2pkit.rpc.RpcTrustStore
import dev.p2pkit.rpc.jvm
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Early-refusal and missing-original ownership slices of frozen MRHA-v1. No source below
 * returns an original or approved Master, and no native registration or SDK role is fabricated.
 * Captures use the official JVM platform with deny-only identity/trust SPIs and never acquire.
 * The loopback endpoint is a frozen ENDPOINT_BAD input, never an actual Host or LAN0 endpoint.
 * Observations cover the supplied sources, platform entry, publication flows and controlled
 * source-close/holder callbacks only. They do not establish successful startup/connection,
 * SDK Running/Ready state, native cleanup, current admission, network absence or business effects.
 * Whole MRHA-001..024 and MRHA-Q01..Q07 remain separately NOT RUN.
 */
class LocalRpcLanAttachmentSupportingTest {
    @Test
    fun mrha004_captureHostAndClientCannotServeOrExposeEndpointBeforeFactoryAcquisition() = runBlocking {
        val lifetimes = NegativeLifetimes()
        val identity = DenyingIdentityStore()
        val trust = DenyingTrustStore()
        val platform = RpcPlatform.jvm(identity)
        val owners = listOf(
            LocalRpcFactoryOwner.captureHost(platform, lifetimes.applicationScope, branchAppId,
                "Front Counter", FactoryReadbackInputs.neverStartedLan(), trust),
            LocalRpcFactoryOwner.captureClient(platform, lifetimes.applicationScope, branchAppId,
                "Front Counter", FactoryReadbackInputs.neverStartedLan(), trust),
        )
        try {
            for (owner in owners) {
                repeat(2) {
                    assertUnavailable { owner.startServingHost() }
                    assertUnavailable { owner.servingEndpoint() }
                    assertEquals(LocalRpcFactoryPhase.NEW, owner.phase.value)
                    identity.assertNotEntered()
                    trust.assertNotEntered()
                }
                observe { lifetimes.callers.async { owner.close() }.await() }
                assertEquals(LocalRpcFactoryPhase.CLOSED, owner.phase.value)
                assertUnavailable { owner.startServingHost() }
                assertUnavailable { owner.servingEndpoint() }
                identity.assertNotEntered()
                trust.assertNotEntered()
            }
        } finally {
            for (owner in owners) observe { lifetimes.callers.async { owner.close() }.await() }
            lifetimes.finish()
        }
    }

    @Test
    fun mrha003_005_hostEndpointBeforeStartAndNoncurrentStartRefuseWithoutOriginalRead() = runBlocking {
        val fixture = MissingHostFixture()
        try {
            assertUnavailable { fixture.owner.endpoint() }
            assertEquals(LocalRpcFactoryPhase.NEW, fixture.owner.phase.value)
            assertEquals(0, fixture.source.read.calls.get())
            fixture.assertNoFactoryOrPublication()

            fixture.source.current = false
            assertUnavailable { fixture.owner.start() }
            assertUnavailable { fixture.owner.endpoint() }
            assertUnavailable { fixture.owner.acquire() }
            assertEquals(LocalRpcFactoryPhase.NEW, fixture.owner.phase.value)
            assertEquals(0, fixture.source.read.calls.get())
            fixture.assertNoFactoryOrPublication()
            observe { fixture.closeCaller().await() }
            assertEquals(LocalRpcFactoryPhase.CLOSED, fixture.owner.phase.value)
            fixture.assertNoFactoryOrPublication()
        } finally {
            fixture.finish()
        }
    }

    @Test
    fun mrha002_missingHostOriginalFailurePublishesNoEndpointAndIsNotReacquired() = runBlocking {
        val fixture = MissingHostFixture()
        try {
            assertOriginalUnavailable(fixture.startCaller())
            fixture.awaitClosed()
            fixture.source.read.assertFinishedOnce()
            fixture.assertNoFactoryOrPublication()
            repeat(2) {
                assertUnavailable { fixture.owner.start() }
                assertUnavailable { fixture.owner.endpoint() }
                assertUnavailable { fixture.owner.acquire() }
                observe { fixture.closeCaller().await() }
                fixture.source.read.assertFinishedOnce()
                fixture.assertNoFactoryOrPublication()
            }
        } finally {
            fixture.finish()
        }
    }

    @Test
    fun mrha006_008_duplicateHostStartWaitersKeepMissingOriginalReadOwnedAfterCancellation() = runBlocking {
        val fixture = MissingHostFixture(pauseRead = true)
        try {
            val temporary = fixture.startCaller()
            observe { fixture.source.read.entered.await() }
            val remaining = fixture.startCaller(CoroutineStart.UNDISPATCHED)
            fixture.source.read.assertPendingOnce()
            assertFalse(remaining.isCompleted)
            observe { temporary.cancelAndJoin() }
            assertTrue(temporary.isCancelled)
            assertTrue(fixture.lifetimes.applicationJob.isActive)
            val later = fixture.startCaller(CoroutineStart.UNDISPATCHED)
            assertFalse(remaining.isCompleted)
            assertFalse(later.isCompleted)
            fixture.source.read.assertPendingOnce()
            fixture.assertNoFactoryOrPublication()

            fixture.source.read.release()
            assertOriginalUnavailable(remaining)
            assertOriginalUnavailable(later)
            fixture.awaitClosed()
            fixture.source.read.assertFinishedOnce()
            assertUnavailable { fixture.owner.start() }
            fixture.assertNoFactoryOrPublication()
        } finally {
            fixture.finish()
        }
    }

    @Test
    fun mrha009_011_hostCloseDrainsHeldStartAndEndpointDespiteCloseWaiterCancellation() = runBlocking {
        val fixture = MissingHostFixture(pauseRead = true)
        try {
            val starting = fixture.startCaller()
            observe { fixture.source.read.entered.await() }
            val endpoint = fixture.endpointCaller()
            val temporaryClose = fixture.closeCaller()
            val remainingClose = fixture.closeCaller()
            observe { fixture.owner.phase.first { it == LocalRpcFactoryPhase.CLOSING } }
            temporaryClose.cancel()
            assertTrue(temporaryClose.isCancelled)
            assertFalse(remainingClose.isCompleted)
            assertFalse(starting.isCompleted)
            assertFalse(endpoint.isCompleted)
            assertUnavailable { fixture.owner.start() }
            assertUnavailable { fixture.owner.endpoint() }
            assertUnavailable { fixture.owner.acquire() }
            fixture.source.read.assertPendingOnce()
            fixture.assertNoFactoryOrPublication()

            fixture.source.read.release()
            assertOriginalUnavailable(starting)
            assertOriginalUnavailable(endpoint)
            observe { remainingClose.await() }
            observe { temporaryClose.join() }
            fixture.awaitClosed()
            fixture.source.read.assertFinishedOnce()
            fixture.assertNoFactoryOrPublication()
            assertTrue(fixture.source.current, "controlled source currentness was not rewritten by close")
        } finally {
            fixture.finish()
        }
    }

    @Test
    fun mrha013_identityOnlyClientConnectRefusesBeforeOriginalReadTrustOrFactory() = runBlocking {
        val fixture = MissingClientFixture(selected = false)
        try {
            repeat(2) {
                assertUnavailable { fixture.owner.connect(badLoopbackEndpoint()) }
                assertEquals(LocalRpcFactoryPhase.NEW, fixture.owner.phase.value)
                assertEquals(0, fixture.source.read.calls.get())
                assertEquals(0, fixture.source.closeCalls.get())
                assertEquals(0, fixture.holderReleases.get())
                fixture.assertNoFactoryOrPublication()
            }
            observe { fixture.closeCaller().await() }
            fixture.assertConfirmedSourceCleanup(expectedReads = 0)
            assertUnavailable { fixture.owner.connect(badLoopbackEndpoint()) }
            observe { fixture.closeCaller().await() }
            fixture.assertConfirmedSourceCleanup(expectedReads = 0)
        } finally {
            fixture.finish()
        }
    }

    @Test
    fun mrha015_017_020_duplicateSelectedClientConnectsKeepMissingOriginalReadOwnedAfterCancellation() = runBlocking {
        val fixture = MissingClientFixture(selected = true, pauseRead = true, pauseClose = true)
        val endpoint = badLoopbackEndpoint()
        try {
            val temporary = fixture.connectCaller(endpoint)
            observe { fixture.source.read.entered.await() }
            val remaining = fixture.connectCaller(endpoint, CoroutineStart.UNDISPATCHED)
            fixture.assertPendingRead()
            observe { temporary.cancelAndJoin() }
            assertTrue(temporary.isCancelled)
            assertTrue(fixture.lifetimes.applicationJob.isActive)
            val later = fixture.connectCaller(endpoint, CoroutineStart.UNDISPATCHED)
            assertFalse(remaining.isCompleted)
            assertFalse(later.isCompleted)
            fixture.assertPendingRead()

            fixture.source.read.release()
            assertOriginalUnavailable(remaining)
            assertOriginalUnavailable(later)
            observe { fixture.source.closeEntered.await() }
            fixture.assertHeldSourceCleanup()
            assertUnavailable { fixture.owner.connect(endpoint) }
            val closing = fixture.closeCaller()
            assertFalse(closing.isCompleted)
            fixture.source.releaseClose()
            observe { closing.await() }
            fixture.assertConfirmedSourceCleanup(expectedReads = 1)
            repeat(2) {
                assertUnavailable { fixture.owner.connect(endpoint) }
                observe { fixture.closeCaller().await() }
                fixture.assertConfirmedSourceCleanup(expectedReads = 1)
            }
        } finally {
            fixture.finish()
        }
    }

    @Test
    fun mrha021_clientCloseDrainsHeldConnectBeforeExactSourceCloseAndHolderRelease() = runBlocking {
        val fixture = MissingClientFixture(selected = true, pauseRead = true, pauseClose = true)
        val endpoint = badLoopbackEndpoint()
        try {
            val connecting = fixture.connectCaller(endpoint)
            observe { fixture.source.read.entered.await() }
            val temporaryClose = fixture.closeCaller()
            val remainingClose = fixture.closeCaller()
            observe { fixture.owner.phase.first { it == LocalRpcFactoryPhase.CLOSING } }
            temporaryClose.cancel()
            assertTrue(temporaryClose.isCancelled)
            assertFalse(remainingClose.isCompleted)
            assertFalse(connecting.isCompleted)
            assertUnavailable { fixture.owner.connect(endpoint) }
            fixture.assertPendingRead()

            fixture.source.read.release()
            assertOriginalUnavailable(connecting)
            observe { fixture.source.closeEntered.await() }
            assertFalse(remainingClose.isCompleted)
            fixture.assertHeldSourceCleanup()
            assertUnavailable { fixture.owner.connect(endpoint) }
            fixture.source.releaseClose()
            observe { remainingClose.await() }
            observe { temporaryClose.join() }
            fixture.assertConfirmedSourceCleanup(expectedReads = 1)
            observe { fixture.closeCaller().await() }
            fixture.assertConfirmedSourceCleanup(expectedReads = 1)
        } finally {
            fixture.finish()
        }
    }
}

private val branchAppId = AppId(
    "ukkera-pos.rpc.branch.v1:11111111-1111-4111-8111-111111111111:" +
        "22222222-2222-4222-8222-222222222222:33333333-3333-4333-8333-333333333333",
)

/** The actual official endpoint type; this frozen loopback variant is never connected. */
private fun badLoopbackEndpoint(): RpcEndpoint = RpcEndpoint("127.0.0.1", 1)

private suspend fun <T> observe(block: suspend () -> T): T = withTimeout(20_000L) { block() }

private suspend fun assertUnavailable(block: suspend () -> Any?) {
    assertFailsWith<IllegalStateException> { observe { block() } }
}

private suspend fun assertOriginalUnavailable(pending: Deferred<*>) {
    assertFailsWith<ControlledOriginalUnavailable> { observe { pending.await() } }
}

private class ControlledOriginalUnavailable : IllegalStateException("Controlled original is unavailable")

private class NegativeLifetimes {
    val applicationJob = SupervisorJob()
    val applicationScope = CoroutineScope(Dispatchers.Default + applicationJob)
    private val callersJob = SupervisorJob()
    val callers = CoroutineScope(Dispatchers.Default + callersJob)

    suspend fun finish() {
        observe { applicationJob.cancelAndJoin() }
        observe { callersJob.cancelAndJoin() }
    }
}

/** Never returns DATA; barriers expose only the owned missing-original read. */
private class RefusingOriginalRead(pause: Boolean) {
    val calls = AtomicInteger()
    val cancellations = AtomicInteger()
    val entered = CompletableDeferred<Unit>()
    val finished = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>().also { if (!pause) it.complete(Unit) }

    suspend fun refuse(): Nothing {
        calls.incrementAndGet()
        entered.complete(Unit)
        try {
            released.await()
            throw ControlledOriginalUnavailable()
        } catch (failure: CancellationException) {
            cancellations.incrementAndGet()
            throw failure
        } finally {
            finished.complete(Unit)
        }
    }

    fun release() { released.complete(Unit) }

    fun assertPendingOnce() {
        assertEquals(1, calls.get())
        assertEquals(0, cancellations.get())
        assertTrue(entered.isCompleted)
        assertFalse(finished.isCompleted)
    }

    fun assertFinishedOnce() {
        assertEquals(1, calls.get())
        assertEquals(0, cancellations.get())
        assertTrue(entered.isCompleted)
        assertTrue(finished.isCompleted)
    }
}

private class MissingHostSource(pauseRead: Boolean) : LocalRpcOriginalHostSource {
    @Volatile var current = true
    val read = RefusingOriginalRead(pauseRead)
    val trustEntries = AtomicInteger()
    val registrationEntries = AtomicInteger()

    override fun isCurrent(): Boolean = current
    override suspend fun yieldOriginal(): LocalMasterSdkOriginalV1 = read.refuse()
    override suspend fun trustStore(): RpcTrustStore {
        trustEntries.incrementAndGet()
        error("Missing original cannot supply trust")
    }
    override suspend fun peerRegistration(): LocalMasterRpcPeerRegistration? {
        registrationEntries.incrementAndGet()
        return null
    }
}

private class MissingHostFixture(pauseRead: Boolean = false) {
    val lifetimes = NegativeLifetimes()
    val source = MissingHostSource(pauseRead)
    private val platformEntries = AtomicInteger()
    val owner = LocalRpcOriginalHostOwner(lifetimes.applicationScope,
        LocalRpcOriginalHostOwner.retentionLifetime(lifetimes.applicationScope), source,
        { platformEntries.incrementAndGet(); error("Missing original cannot construct a Host") },
        FactoryReadbackInputs.neverStartedLan())

    fun startCaller(start: CoroutineStart = CoroutineStart.DEFAULT): Deferred<Unit> =
        lifetimes.callers.async(start = start) { owner.start() }
    fun endpointCaller(): Deferred<RpcEndpoint> =
        lifetimes.callers.async(start = CoroutineStart.UNDISPATCHED) { owner.endpoint() }
    fun closeCaller(): Deferred<Unit> =
        lifetimes.callers.async(start = CoroutineStart.UNDISPATCHED) { owner.closeOwned() }
    suspend fun awaitClosed() { observe { owner.phase.first { it == LocalRpcFactoryPhase.CLOSED } } }

    fun assertNoFactoryOrPublication() {
        assertEquals(0, platformEntries.get(), "the supplied Host factory platform is not entered")
        assertEquals(0, source.trustEntries.get())
        assertEquals(0, source.registrationEntries.get())
        assertNull(owner.original.value)
    }

    suspend fun finish() {
        source.read.release()
        observe { closeCaller().await() }
        lifetimes.finish()
    }
}

private enum class SourceCleanupEvent { CLOSE_ENTERED, CLOSE_RETURNED, HOLDER_RELEASED }

/** The token correlates this controlled source with its callbacks; it is not a native receipt. */
private abstract class MissingClientSource(pauseRead: Boolean, pauseClose: Boolean) {
    val target = Any()
    val read = RefusingOriginalRead(pauseRead)
    val closeCalls = AtomicInteger()
    val closeReturns = AtomicInteger()
    val closeCancellations = AtomicInteger()
    val closeEntered = CompletableDeferred<Unit>()
    val closeFinished = CompletableDeferred<Unit>()
    val closeTargets = CopyOnWriteArrayList<Any>()
    val cleanupEvents = CopyOnWriteArrayList<SourceCleanupEvent>()
    private val closeRelease = CompletableDeferred<Unit>().also { if (!pauseClose) it.complete(Unit) }

    protected suspend fun closeExactSource() {
        closeCalls.incrementAndGet()
        closeTargets.add(target)
        cleanupEvents.add(SourceCleanupEvent.CLOSE_ENTERED)
        closeEntered.complete(Unit)
        try {
            closeRelease.await()
            closeReturns.incrementAndGet()
            cleanupEvents.add(SourceCleanupEvent.CLOSE_RETURNED)
        } catch (failure: CancellationException) {
            closeCancellations.incrementAndGet()
            throw failure
        } finally {
            closeFinished.complete(Unit)
        }
    }

    fun releaseClose() { closeRelease.complete(Unit) }
}

private class MissingIdentityClientSource(pauseRead: Boolean, pauseClose: Boolean) :
    MissingClientSource(pauseRead, pauseClose), LocalRpcOriginalClientSource {
    override suspend fun recoverOriginal(): LocalDevicePairingSdkOriginal = read.refuse()
    override suspend fun close() { closeExactSource() }
}

private class MissingSelectedClientSource(pauseRead: Boolean, pauseClose: Boolean) :
    MissingClientSource(pauseRead, pauseClose), LocalRpcSelectedMasterClientSource {
    override suspend fun recover(): LocalRpcSelectedMasterClientReadback = read.refuse()
    override suspend fun close() { closeExactSource() }
}

private class MissingClientFixture(selected: Boolean, pauseRead: Boolean = false, pauseClose: Boolean = false) {
    val lifetimes = NegativeLifetimes()
    val source: MissingClientSource = if (selected) MissingSelectedClientSource(pauseRead, pauseClose)
        else MissingIdentityClientSource(pauseRead, pauseClose)
    private val platformEntries = AtomicInteger()
    private val providedTrust = DenyingTrustStore()
    val holderReleases = AtomicInteger()
    private val releasedTargets = CopyOnWriteArrayList<Any>()
    private val confirmedRelease = CompletableDeferred<Unit>()
    private val onConfirmedClose: () -> Unit = {
        holderReleases.incrementAndGet()
        releasedTargets.add(source.target)
        source.cleanupEvents.add(SourceCleanupEvent.HOLDER_RELEASED)
        confirmedRelease.complete(Unit)
    }
    val owner = when (val exactSource = source) {
        is MissingSelectedClientSource -> LocalRpcOriginalClientOwner(lifetimes.applicationScope,
            LocalRpcOriginalClientOwner.retentionLifetime(lifetimes.applicationScope), exactSource,
            { platformEntries.incrementAndGet(); error("Missing original cannot construct a Client") },
            FactoryReadbackInputs.neverStartedLan(), onConfirmedClose)
        is MissingIdentityClientSource -> LocalRpcOriginalClientOwner(lifetimes.applicationScope,
            LocalRpcOriginalClientOwner.retentionLifetime(lifetimes.applicationScope, providedTrust), exactSource,
            { platformEntries.incrementAndGet(); error("Identity-only refusal cannot construct a Client") },
            FactoryReadbackInputs.neverStartedLan(), providedTrust, onConfirmedClose)
        else -> error("Unsupported controlled source")
    }

    fun connectCaller(endpoint: RpcEndpoint, start: CoroutineStart = CoroutineStart.DEFAULT): Deferred<Unit> =
        lifetimes.callers.async(start = start) { owner.connect(endpoint) }
    fun closeCaller(): Deferred<Unit> =
        lifetimes.callers.async(start = CoroutineStart.UNDISPATCHED) { owner.close() }

    fun assertNoFactoryOrPublication() {
        assertEquals(0, platformEntries.get(), "the supplied Client factory platform is not entered")
        providedTrust.assertNotEntered()
        assertNull(owner.original.value)
        assertNull(owner.approvedMaster.value)
    }

    fun assertPendingRead() {
        source.read.assertPendingOnce()
        assertEquals(0, source.closeCalls.get())
        assertEquals(0, holderReleases.get())
        assertFalse(confirmedRelease.isCompleted)
        assertNoFactoryOrPublication()
    }

    fun assertHeldSourceCleanup() {
        assertEquals(LocalRpcFactoryPhase.CLOSING, owner.phase.value)
        source.read.assertFinishedOnce()
        assertEquals(1, source.closeCalls.get())
        assertEquals(0, source.closeReturns.get())
        assertEquals(0, source.closeCancellations.get())
        assertFalse(source.closeFinished.isCompleted)
        assertSame(source.target, source.closeTargets.single())
        assertEquals(0, holderReleases.get())
        assertFalse(confirmedRelease.isCompleted)
        assertTrue(releasedTargets.isEmpty())
        assertEquals(listOf(SourceCleanupEvent.CLOSE_ENTERED), source.cleanupEvents.toList())
        assertNoFactoryOrPublication()
    }

    fun assertConfirmedSourceCleanup(expectedReads: Int) {
        assertEquals(LocalRpcFactoryPhase.CLOSED, owner.phase.value)
        assertEquals(expectedReads, source.read.calls.get())
        assertEquals(0, source.read.cancellations.get())
        if (expectedReads == 1) source.read.assertFinishedOnce()
        assertEquals(1, source.closeCalls.get())
        assertEquals(1, source.closeReturns.get())
        assertEquals(0, source.closeCancellations.get())
        assertTrue(source.closeFinished.isCompleted)
        assertSame(source.target, source.closeTargets.single())
        assertEquals(1, holderReleases.get())
        assertTrue(confirmedRelease.isCompleted)
        assertSame(source.target, releasedTargets.single())
        assertEquals(listOf(SourceCleanupEvent.CLOSE_ENTERED, SourceCleanupEvent.CLOSE_RETURNED,
            SourceCleanupEvent.HOLDER_RELEASED), source.cleanupEvents.toList())
        assertNoFactoryOrPublication()
    }

    suspend fun finish() {
        // These barriers return only a missing-original failure and a resource-free test callback.
        // They cannot supply a successful original, actual native close or cleanup qualification.
        source.read.release()
        source.releaseClose()
        observe { closeCaller().await() }
        lifetimes.finish()
    }
}

/** Public official JVM SPI that fails on every identity operation; no keys or files are supplied. */
private class DenyingIdentityStore : JvmSecureIdentityStore {
    private val entries = AtomicInteger()
    override fun read(namespace: String): ByteArray? { entries.incrementAndGet(); error("Identity SPI is unavailable") }
    override fun putIfAbsent(namespace: String, value: ByteArray): ByteArray {
        entries.incrementAndGet(); error("Identity SPI is unavailable")
    }
    override fun delete(namespace: String): Boolean { entries.incrementAndGet(); error("Identity SPI is unavailable") }
    fun assertNotEntered() { assertEquals(0, entries.get()) }
}

/** No accepting set or default: any use fails and is counted independently of its caller. */
private class DenyingTrustStore : RpcTrustStore {
    private val entries = AtomicInteger()
    override suspend fun load(appId: AppId, purpose: RpcTrustPurpose): Set<PeerFingerprint> {
        entries.incrementAndGet(); error("Trust SPI is unavailable")
    }
    override suspend fun replace(appId: AppId, purpose: RpcTrustPurpose, fingerprints: Set<PeerFingerprint>) {
        entries.incrementAndGet(); error("Trust SPI is unavailable")
    }
    fun assertNotEntered() { assertEquals(0, entries.get()) }
}
