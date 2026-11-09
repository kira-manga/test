package com.ukkera.pos.app.gateway.independent.sessionterminalv1

import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.LocalRetailPhysicalDenial
import com.ukkera.pos.app.LocalRetailPhysicalLifetime
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.SessionGatewayFixture
import com.ukkera.pos.security.BranchScope
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/** Frozen identity labels are controlled samples; the reused encrypted journals are real IO. */
internal suspend fun terminalGatewayFixture(freeze: FrozenTerminalCleanupV1,
    dispatcher: CoroutineDispatcher): SessionGatewayFixture = SessionGatewayFixture.create(dispatcher,
    identitySample = LocalClientIdentity(
        BranchScope(freeze.id("organization_1"), freeze.id("retail_company"), freeze.id("retail_branch")),
        freeze.id("account_a"), freeze.id("shared_client_device")),
    initialSessionSample = freeze.id("session_a_original"), skuInputSample = freeze.retailProduct())

internal data class TerminalNegativeLifetimes(
    val session: LocalRetailPhysicalLifetime,
    val epoch: LocalRetailPhysicalLifetime,
)

/**
 * Read-only white-box access to the two real negative lifetime owners. No field is assigned,
 * lifetime replaced, native holder synthesized or positive capability supplied by this helper.
 * All probe attachment and denial still use the production negative-only API.
 */
internal fun terminalNegativeLifetimes(session: BranchUiSession): TerminalNegativeLifetimes {
    val type = session.javaClass
    check(type.simpleName == "EndpointSession") { "The real endpoint session is required for this binding." }
    fun read(name: String): Any? = type.getDeclaredField(name).apply { isAccessible = true }.get(session)
    val original = read("physicalSession") as? LocalRetailPhysicalLifetime
        ?: error("The real session negative lifetime is unavailable.")
    val epochs = read("physicalEpoch") as? StateFlow<*>
        ?: error("The real epoch lifetime publication is unavailable.")
    val epoch = epochs.value as? LocalRetailPhysicalLifetime
        ?: error("The real current epoch negative lifetime is unavailable.")
    return TerminalNegativeLifetimes(original, epoch)
}

/** The external synthetic fault may resolve; no production latch, grant or field is reset. */
internal class TerminalGatewayDenialProbe(private val failure: Throwable? = null) : LocalRetailPhysicalDenial {
    private val failing = AtomicBoolean(failure != null)
    private val count = AtomicInteger()
    private val successful = AtomicBoolean()
    val attempts: Int get() = count.get()
    val released: Boolean get() = successful.get()

    override fun deny() {
        count.incrementAndGet()
        if (failing.get()) throw checkNotNull(failure)
        successful.set(true)
    }

    fun resolveFault() { failing.set(false) }
}

/** Two callers reach the same actual callback; only its first completion is held. */
internal class TerminalHeldDenial(private val firstFailure: Throwable? = null) : LocalRetailPhysicalDenial {
    val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val count = AtomicInteger()
    private val successful = AtomicInteger()
    val attempts: Int get() = count.get()
    val successfulAttempts: Int get() = successful.get()

    override fun deny() {
        if (count.incrementAndGet() == 1) {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) {
                "The synthetic denial barrier exceeded its harness observation safeguard."
            }
            firstFailure?.let { throw it }
        }
        successful.incrementAndGet()
    }

    fun releaseFirst() { release.countDown() }
}

/** Owned synchronous denial callers only; UI mutation is never moved to these threads. */
internal class TerminalDenialThreads : AutoCloseable {
    private val executor = Executors.newFixedThreadPool(2)
    fun closeSession(session: BranchUiSession): Future<Unit> = executor.submit(Callable { session.close(); Unit })

    override fun close() {
        executor.shutdown()
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            executor.shutdownNow()
            check(executor.awaitTermination(10, TimeUnit.SECONDS)) {
                "The owned synthetic denial callers did not terminate after barrier release."
            }
        }
    }
}
