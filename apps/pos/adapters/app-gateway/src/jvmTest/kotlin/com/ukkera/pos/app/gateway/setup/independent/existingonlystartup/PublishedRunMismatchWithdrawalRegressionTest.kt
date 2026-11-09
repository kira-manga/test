package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import com.ukkera.pos.app.setup.LocalSetupProblem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Focused review regression for the finding fixed by `a7d4e46c`: a connected, published
 * original must not stay presented or usable when the retained RUN launch record no longer
 * matches the exact opened native target coordinates.
 *
 * Expectations are frozen in
 * `qa/pos/designs/existing-only-startup-published-run-mismatch-regression-v1/expectation.md`
 * before this wiring. The real [com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator] and
 * [com.ukkera.pos.app.gateway.setup.LocalMasterLaunchRecord] run through the existing
 * controlled startup ports and frozen D5-01 positive scene. This is shared policy/lifecycle
 * coverage only: not native, Room/SQLite, RPC/LAN, installed-workflow or device evidence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PublishedRunMismatchWithdrawalRegressionTest {
    @Test
    fun connectedPublishedOriginalWithForeignRetainedRunWithdrawsExactHandles() = runTest {
        val variant = ExistingOnlyStartupFixtures.variants.getValue("E3V3-D5-01.V01-POSITIVE")
        val harness = ExistingOnlyStartupHarness(this, variant)

        // Frozen positive precondition: the canonical original is connected and published.
        harness.entry()
        harness.runUntilIdle()
        harness.settleStartup()
        harness.runUntilIdle()
        assertEquals(1, harness.publishes.size, "positive startup must publish exactly once")
        assertEquals(1, harness.clientConnects.size, "positive startup must connect exactly one client")
        assertTrue(harness.view.localClientConnected, "positive startup must present the connected original")
        assertTrue(harness.view.locked, "automatic RUN presentation remains private/locked until explicit resume")
        val publishedOwner = assertNotNull(harness.publishedOwner, "published lifecycle owner missing")
        val publishedClient = assertNotNull(harness.publishedClient, "published client missing")
        val master = assertNotNull(harness.masterObject, "opened master missing")
        val target = assertNotNull(harness.targetContext, "opened target missing")

        harness.coordinator.resumePresentation()
        harness.runUntilIdle()
        assertFalse(harness.view.locked, "explicit resume must unlock the presentation")

        // TEST mutation only: the retained RUN becomes a well-formed foreign runtime vector.
        harness.setHint("EV-SET-HINT-FOREIGN-RUN")

        val targetOpensBefore = harness.targetOpens.size
        val masterOpensBefore = harness.masterOpens.size
        val clientConnectsBefore = harness.clientConnects.size
        val publishesBefore = harness.publishes.size
        val withdrawsBefore = harness.withdraws.size
        val denialsBefore = harness.denialCalls.size
        val closesBefore = harness.closeEntryCount()
        val writesBefore = harness.launchStorage.writes
        val forbiddenBefore = harness.forbiddenCalls.toList()
        val jobsBefore = harness.jobs().toSet()

        harness.coordinator.openOriginalFirstAccount()
        harness.runUntilIdle()
        val actionJobs = harness.jobs().filter { it !in jobsBefore }
        assertEquals(1, actionJobs.size, "explicit setup re-entry must dispatch exactly one finite worker")
        val action = actionJobs.single()
        action.join()
        harness.runUntilIdle()
        assertTrue(action.isCompleted, "public action worker did not settle")
        assertFalse(action.isCancelled, "public action worker was cancelled")

        // Frozen expectation 1: definitive observable refusal, not success or a replacement path.
        assertEquals(LocalSetupProblem.STATE_UNCERTAIN, harness.view.problem,
            "mismatched retained RUN must expose the actual uncertainty refusal")
        assertNull(harness.view.work, "refused action must not remain in a work phase")

        // Frozen expectation 2: no positive connected claim survives.
        assertFalse(harness.view.localClientConnected,
            "mismatched retained RUN must withdraw the connected presentation claim")
        assertFalse(harness.view.localClientConnectionAvailable,
            "mismatched retained RUN must withdraw connection availability")

        // Frozen expectation 3: exact published owner withdrawn and exact child handles denied.
        assertEquals(withdrawsBefore + 1, harness.withdraws.size,
            "the exact published lifecycle owner must be withdrawn once")
        val withdrawal = harness.withdraws.last()
        assertSame(publishedOwner, withdrawal.owner,
            "withdrawal did not use the exact published lifecycle owner")
        assertEquals("OWNER-PUBLISHED", withdrawal.alias,
            "withdrawal did not correlate with the recorded publication owner")
        val newDenials = harness.denialCalls.drop(denialsBefore)
        assertTrue(newDenials.any { it.method == "client.denyNewWork" && it.alias == harness.clientAlias },
            "the exact connected client handle was not denied")
        assertTrue(newDenials.any { it.method == "master.denyNewWork" && it.alias == harness.masterAlias },
            "the exact opened master handle was not denied")

        // Frozen expectation 4: no replacement acquisition; exact owners remain the same objects.
        assertEquals(targetOpensBefore, harness.targetOpens.size, "mismatch must not acquire another target")
        assertEquals(masterOpensBefore, harness.masterOpens.size, "mismatch must not open another master")
        assertEquals(clientConnectsBefore, harness.clientConnects.size, "mismatch must not connect another client")
        assertSame(target, harness.targetContext, "target handle was replaced")
        assertSame(master, harness.masterObject, "master handle was replaced")
        assertSame(publishedClient, harness.clientObject, "client handle was replaced")

        // Frozen expectation 5: no close, no second publication and no retained-record rewrite.
        assertEquals(closesBefore, harness.closeEntryCount(),
            "mismatch must not fabricate a close of the working original")
        assertEquals(publishesBefore, harness.publishes.size,
            "mismatch must not publish a replacement client")
        assertEquals(writesBefore, harness.launchStorage.writes,
            "mismatch must not rewrite or repair the retained RUN record")

        // Frozen expectation 6: no unrelated/forbidden effect was introduced.
        assertEquals(forbiddenBefore, harness.forbiddenCalls,
            "mismatch introduced a forbidden lifecycle effect")
    }
}
