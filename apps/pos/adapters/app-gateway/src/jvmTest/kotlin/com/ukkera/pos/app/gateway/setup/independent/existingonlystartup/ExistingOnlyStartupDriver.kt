package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import com.ukkera.pos.app.gateway.LocalOriginalClientJournalAction
import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.app.setup.LocalSetupProblem
import com.ukkera.pos.app.setup.LocalSetupView
import com.ukkera.pos.database.LocalMasterLifecycleMode
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal enum class VariantStatus { PASS, UNVERIFIED, FAIL }

internal data class VariantOutcome(
    val variantId: String,
    val status: VariantStatus,
    val coreStatus: VariantStatus,
    val notes: List<String>,
    val trace: List<String>,
    val ledger: String,
    val conditional: List<String>,
    val watch: String,
    val teardown: String,
)

private sealed interface RetirementObservation {
    data object Normal : RetirementObservation
    data class Exceptional(val failure: Throwable) : RetirementObservation
    data object PendingAtObservation : RetirementObservation
}

/**
 * Drives one effective frozen variant through the real coordinator and asserts the expected
 * class, presentation, ownership ledger, gates and forbidden-effect census. Unentered frozen
 * ports are recorded as NOT EXERCISED/UNVERIFIED, never as PASS.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ExistingOnlyStartupDriver(
    private val test: TestScope,
    private val variant: FrozenVariant,
) {
    private val harness = ExistingOnlyStartupHarness(test, variant)
    private val scene = harness.scene
    private val raceStages = scene.race?.stages ?: emptyList()
    private var nextRaceStage = 0
    private val notes = mutableListOf<String>()
    private val conditionalGaps = mutableListOf<String>()
    private val teardownNotes = mutableListOf<String>()
    private val waiters = mutableListOf<Job>()
    private var unverified = false
    private var teardownState = "not-run"

    suspend fun execute(): VariantOutcome {
        var failure: Throwable? = null
        try {
            runVariant()
        } catch (error: Throwable) {
            failure = error
        }
        val coreStatus = when {
            failure != null -> VariantStatus.FAIL
            unverified -> VariantStatus.UNVERIFIED
            else -> VariantStatus.PASS
        }
        if (failure != null) notes += "FAILURE: ${failure.message}"
        val coreNotes = notes.toList()
        val coreTrace = harness.log.trace()
        val coreLedger = harness.ledger.summary()
        val coreConditional = conditionalGaps.toList()
        val coreWatch = if (harness.observerWatching()) "WATCH-REGISTERED" else "WATCH-STOPPED"
        var teardownFailure: Throwable? = null
        try {
            teardown()
        } catch (error: Throwable) {
            teardownFailure = error
        }
        if (teardownFailure != null) {
            teardownNotes += "TEARDOWN-FAILURE: ${teardownFailure.message}"
        }
        val finalStatus = if (teardownFailure != null) VariantStatus.FAIL else coreStatus
        return VariantOutcome(
            variantId = variant.variantId,
            status = finalStatus,
            coreStatus = coreStatus,
            notes = coreNotes + teardownNotes,
            trace = coreTrace,
            ledger = coreLedger,
            conditional = coreConditional,
            watch = coreWatch,
            teardown = teardownState,
        )
    }

    private suspend fun runVariant() {
        val startup = harness.entry()
        harness.runUntilIdle()
        handleStartupHold(startup)
        harness.settleStartup()
        assertStartupBinding(startup)
        scene.race?.afterSettle?.forEach { event ->
            harness.performEvent(event)
            harness.runUntilIdle()
        }
        when (scene.retirement) {
            "RET-AFTER-PUBLISH", "RET-AFTER-FAILURE" -> {
                harness.requestRetirement()
                harness.runUntilIdle()
            }
            "RET-WAITER-CANCEL" -> {
                harness.requestRetirement()
                harness.runUntilIdle()
                driveWaiterCancellation()
            }
            "RET-RACE" -> Unit
            "RET-NONE" -> Unit
            else -> error("${variant.variantId}: unknown retirement timing ${scene.retirement}")
        }
        if (scene.retirement != "RET-WAITER-CANCEL") handleRetirementHold()
        scene.race?.afterOutcome?.forEach { event ->
            harness.awaitOutcomeOrCompletion()
            harness.performEvent(event)
            harness.runUntilIdle()
        }
        var retirementObserved: RetirementObservation? = null
        if (scene.race?.afterClosed?.isNotEmpty() == true) {
            val deferred = assertNotNull(harness.retirementDeferred, "${variant.variantId}: no retirement Deferred")
            retirementObserved = observeDeferred(deferred)
            assertTrue(retirementObserved is RetirementObservation.Normal,
                "${variant.variantId}: after_closed requires normal Deferred completion, was $retirementObserved")
            assertEquals(LocalSetupPhase.CLOSED, harness.view.phase,
                "${variant.variantId}: after_closed requires CLOSED presentation")
            scene.race.afterClosed.forEach { event ->
                harness.performEvent(event)
                harness.runUntilIdle()
            }
        }
        if (retirementObserved == null && harness.retirementDeferred != null) {
            retirementObserved = observeDeferred(assertNotNull(harness.retirementDeferred))
        }
        assertVariant(retirementObserved)
    }

    private suspend fun handleStartupHold(startup: Job) {
        val stage = raceStages.getOrNull(nextRaceStage) ?: return
        val hold = stage.hold ?: return
        if (hold !in STARTUP_GATES) return
        if (harness.gateWasEntered(hold)) {
            val enterSequence = assertNotNull(harness.gateEnterSequence(hold),
                "${variant.variantId}: $hold entry was not logged")
            notes += "$hold entered while startup job active=${!startup.isCompleted}"
            assertFalse(startup.isCompleted, "${variant.variantId}: startup job completed before $hold")
            stage.onEnter.forEach { event ->
                harness.performEvent(event)
                harness.runUntilIdle()
            }
            if (stage.onEnter.contains("EV-REQUEST-RETIREMENT")) {
                assertNotNull(harness.retirementDeferred,
                    "${variant.variantId}: $hold on_enter did not request retirement")
                assertEquals(hold, stage.release,
                    "${variant.variantId}: staged hold $hold must release the same gate")
                assertTrue(assertNotNull(harness.retirementSequence()) > enterSequence,
                    "${variant.variantId}: retirement request must follow $hold entry")
            }
            val release = assertNotNull(stage.release,
                "${variant.variantId}: held stage $hold has no release")
            assertEquals(hold, release, "${variant.variantId}: staged release differs from hold")
            harness.releaseGate(release)
            harness.runUntilIdle()
            val releaseSequence = assertNotNull(harness.gateReleaseSequence(release),
                "${variant.variantId}: $release release was not logged")
            if (stage.onEnter.contains("EV-REQUEST-RETIREMENT")) {
                assertTrue(releaseSequence > harness.retirementSequence(),
                    "${variant.variantId}: $release was released before retirement was requested")
            }
            nextRaceStage++
        } else {
            notes += "$hold NOT EXERCISED (production did not enter)"
            unverified = true
        }
    }

    private suspend fun handleRetirementHold() {
        while (nextRaceStage < raceStages.size) {
            val stage = raceStages[nextRaceStage]
            val hold = assertNotNull(stage.hold, "${variant.variantId}: race stage has no hold")
            if (hold in STARTUP_GATES) {
                error("${variant.variantId}: startup gate $hold appears after retirement")
            }
            harness.runUntilIdle()
            if (!harness.gateWasEntered(hold)) {
                notes += "$hold NOT EXERCISED (production did not enter this close path)"
                unverified = true
                return
            }
            val enterSequence = assertNotNull(harness.gateEnterSequence(hold),
                "${variant.variantId}: $hold entry was not logged")
            val deferred = harness.retirementDeferred
            if (deferred != null) {
                assertFalse(deferred.isCompleted, "${variant.variantId}: retirement Deferred completed before $hold")
                notes += "$hold entered; G-DEFERRED-PENDING"
                assertTrue(enterSequence > harness.retirementSequence(),
                    "${variant.variantId}: $hold was entered before retirement was requested")
            }
            if (nextRaceStage > 0) {
                val previousHold = assertNotNull(raceStages[nextRaceStage - 1].hold)
                val previousRelease = assertNotNull(harness.gateReleaseSequence(previousHold),
                    "${variant.variantId}: previous gate $previousHold was not released")
                assertTrue(enterSequence > previousRelease,
                    "${variant.variantId}: $hold entered before $previousHold was released")
            }
            stage.onEnter.forEach { event ->
                harness.performEvent(event)
                harness.runUntilIdle()
            }
            val release = assertNotNull(stage.release,
                "${variant.variantId}: retirement stage $hold has no release")
            assertEquals(hold, release, "${variant.variantId}: staged release differs from hold")
            harness.releaseGate(release)
            harness.runUntilIdle()
            nextRaceStage++
        }
    }

    private suspend fun driveWaiterCancellation() {
        val gate = "G-FIRST-CLOSE"
        if (!harness.gateWasEntered(gate)) {
            notes += "$gate NOT EXERCISED; waiter cancellation path unverified"
            unverified = true
            return
        }
        val deferred = assertNotNull(harness.retirementDeferred)
        assertFalse(deferred.isCompleted, "${variant.variantId}: G-DEFERRED-PENDING failed")
        assertTrue(harness.firstCloseGateEntered && harness.firstClosePending,
            "${variant.variantId}: first close is not positively entered/pending")
        notes += "G-FIRST-CLOSE entered owner=${harness.firstCloseOwner}; G-DEFERRED-PENDING; G-CLEANUP-ALIVE"
        val waiter = test.async { deferred.await() }
        waiters += waiter
        harness.runUntilIdle()
        assertFalse(waiter.isCompleted, "${variant.variantId}: G-WAITER-SUSPENDED failed")
        assertTrue(waiter.isActive, "${variant.variantId}: waiter is not active")
        waiter.cancel()
        harness.runUntilIdle()
        assertTrue(waiter.isCancelled, "${variant.variantId}: G-WAITER-CANCELLED failed")
        assertFalse(deferred.isCompleted, "${variant.variantId}: cleanup Deferred completed with waiter cancellation")
        assertFalse(deferred.isCancelled, "${variant.variantId}: cleanup Deferred was cancelled")
        assertTrue(harness.firstClosePending, "${variant.variantId}: G-CLEANUP-ALIVE failed")
        notes += "G-WAITER-CANCELLED; G-CLEANUP-ALIVE"
        harness.releaseGate(gate)
        harness.runUntilIdle()
    }

    private fun assertStartupBinding(startup: Job) {
        assertTrue(startup.isCompleted, "${variant.variantId}: startup job did not settle")
        assertFalse(startup.isCancelled, "${variant.variantId}: startup job was cancelled")
        notes += "startup settlement bound to real startup job ${startup.alias()}"
        if (harness.recordClassification() == "RUN" && harness.targetOpens.isNotEmpty()) {
            assertTrue(harness.startupCallJobs.isNotEmpty(),
                "${variant.variantId}: startup calls did not capture the admitted startup job")
            harness.startupCallJobs.forEach { job ->
                assertSame(startup, job, "${variant.variantId}: a startup call ran on another job")
            }
        }
    }

    private suspend fun observeDeferred(deferred: Deferred<Unit>): RetirementObservation {
        harness.runUntilIdle()
        if (!deferred.isCompleted) {
            notes += "G-DEFERRED-FINAL PENDING-AT-OBSERVATION (nonterminal)"
            return RetirementObservation.PendingAtObservation
        }
        val result = runCatching { deferred.await() }
        return if (result.isSuccess) {
            notes += "G-DEFERRED-FINAL normal completion"
            RetirementObservation.Normal
        } else {
            notes += "G-DEFERRED-FINAL exceptional completion: ${result.exceptionOrNull()?.message}"
            RetirementObservation.Exceptional(assertNotNull(result.exceptionOrNull()))
        }
    }

    private fun assertVariant(retirement: RetirementObservation?) {
        assertNoHintWrite()
        assertSingleAcquisition()
        assertGenericWithdrawalCorrelation()
        assertGenericDenialCorrelation()
        assertBindingAndSetupConsistency()
        assertNoForbiddenEffects()
        assertBindingChangedContract()
        assertCustodyDisposition()
        when (scene.expected) {
            "NO_ACQUISITION" -> assertNoAcquisition()
            "TARGET_FAILED" -> assertTargetFailed(retirement)
            "TARGET_HELD_MISMATCH" -> assertTargetHeldMismatch()
            "MASTER_HELD" -> assertMasterHeld()
            "CLIENT_HELD" -> assertClientHeld()
            "PUBLISHED" -> assertPublished()
            "STALE_REJECTED" -> assertStaleRejected()
            "RETIRED_CLOSED" -> assertRetiredClosed(retirement)
            "RETIRED_UNRESOLVED" -> assertRetiredUnresolved(retirement)
            "RETIRED_DEFENSE_CONDITIONAL" -> assertDefenseConditional(retirement)
            else -> error("${variant.variantId}: unknown expected class ${scene.expected}")
        }
        assertIngressRefusal()
        assertDrainCheckpoint()
        assertPublishedObserverCensus()
        recordConditionalCoverage()
    }

    private fun assertNoAcquisition() {
        assertEquals(0, harness.targetOpens.size, "${variant.variantId}: openTarget count")
        assertEquals(0, harness.masterOpens.size, "${variant.variantId}: openMaster count")
        assertEquals(0, harness.bindingReads.size, "${variant.variantId}: binding read count")
        assertEquals(0, harness.setupReads.size, "${variant.variantId}: setup read count")
        assertEquals(0, harness.clientConnects.size, "${variant.variantId}: connectClient count")
        assertEquals(0, harness.publishes.size, "${variant.variantId}: publishClient count")
        assertEquals(0, harness.denialCalls.size, "${variant.variantId}: denial call count")
        if (scene.withdraw == "WD-FORBIDDEN") {
            assertEquals(0, harness.withdraws.size, "${variant.variantId}: withdrawClient count")
        }
        assertEquals(0, harness.targetCloseEntries.size + harness.masterCloseEntries.size + harness.clientCloseEntries.size,
            "${variant.variantId}: close count")
        assertStoredRecordUnchanged()
        assertPresentation("VIEW-INITIAL-NO-OWNER")
        val record = harness.fixturesRecord(scene.recordId)
        when (record.classification) {
            "ABSENT" -> assertFalse(harness.view.problem == LocalSetupProblem.AUTOMATIC_REOPEN_STOPPED,
                "${variant.variantId}: absence must not be reported as STOPPED")
            "STOP" -> assertEquals(LocalSetupProblem.AUTOMATIC_REOPEN_STOPPED, harness.view.problem,
                "${variant.variantId}: STOP report")
            "READ_ERROR", "UNRESOLVED" -> assertEquals(LocalSetupProblem.AUTOMATIC_REOPEN_UNAVAILABLE,
                harness.view.problem, "${variant.variantId}: unavailable report")
            else -> error("${variant.variantId}: non-RUN disposition expected")
        }
        notes += "record disposition=${record.classification}; problem=${harness.view.problem}"
    }

    private fun assertTargetFailed(retirement: RetirementObservation?) {
        assertEquals(1, harness.targetOpens.size, "${variant.variantId}: openTarget count")
        assertFalse(harness.targetOpens.single().preparation, "${variant.variantId}: creating target requested")
        assertEquals(0, harness.masterOpens.size, "${variant.variantId}: openMaster count")
        assertEquals(0, harness.bindingReads.size, "${variant.variantId}: binding read count")
        assertEquals(0, harness.setupReads.size, "${variant.variantId}: setup read count")
        assertEquals(0, harness.clientConnects.size, "${variant.variantId}: connectClient count")
        assertEquals(0, harness.publishes.size, "${variant.variantId}: publishClient count")
        assertEquals(0, harness.targetCloseEntries.size, "${variant.variantId}: target close count")
        assertEquals(0, harness.masterCloseEntries.size, "${variant.variantId}: master close count")
        assertEquals(0, harness.clientCloseEntries.size, "${variant.variantId}: client close count")
        assertEquals(0, harness.launchStorage.writes, "${variant.variantId}: production hint write")
        assertPresentation("VIEW-RUN-PROTECTED")
        val target = harness.targetOpens.single()
        if (target.outcome == "opened") {
            assertEquals(OwnerState.HELD, harness.ledger.entry(harness.targetContextAlias)?.state,
                "${variant.variantId}: returned target must be accounted")
        } else {
            assertEquals(OwnerState.NO_OWNER,
                harness.ledger.entry(harness.targetContextAlias)?.state ?: OwnerState.NO_OWNER,
                "${variant.variantId}: failed target open must not fabricate an owner")
        }
        if (target.outcome == "throw") {
            notes += "target throw triggered production auto-retirement: ${retirement ?: RetirementObservation.PendingAtObservation}"
            assertFalse(harness.view.phase == LocalSetupPhase.CLOSED, "${variant.variantId}: target throw cannot report CLOSED")
        }
        notes += "target outcome=${target.outcome}; problem=${harness.view.problem}; nativeProblem=${harness.view.nativeProblem}"
    }

    private fun assertTargetHeldMismatch() {
        assertEquals(1, harness.targetOpens.size, "${variant.variantId}: openTarget count")
        assertEquals(0, harness.masterOpens.size, "${variant.variantId}: openMaster count")
        assertEquals(0, harness.clientConnects.size, "${variant.variantId}: connectClient count")
        assertEquals(0, harness.publishes.size, "${variant.variantId}: publishClient count")
        assertEquals(0, harness.targetCloseEntries.size, "${variant.variantId}: target close count")
        assertEquals(0, harness.masterCloseEntries.size, "${variant.variantId}: master close count")
        assertEquals(0, harness.clientCloseEntries.size, "${variant.variantId}: client close count")
        assertEquals(OwnerState.HELD, harness.ledger.entry(harness.targetContextAlias)?.state,
            "${variant.variantId}: mismatched target must be accounted")
        val target = harness.targetOpens.single()
        val targetValues = target.coordinates ?: error("${variant.variantId}: target coordinates missing")
        val canonical = harness.fixturesCanonicalCoordinates()
        val foreign = harness.fixturesForeignCoordinates()
        val stored = harness.fixturesRecordCoordinates(scene.recordId)
        val targetProfileId = scene.target
        val profileIndex = targetProfileId?.let { id ->
            Regex("TGT-FOREIGN-(\\d{2})$").find(id)?.groupValues?.get(1)?.toInt()?.minus(1)
        }?.also { require(it in 0..11) { "${variant.variantId}: target profile index is out of range" } }
        val namedIndex = if (Regex("MISMATCH-\\d{2}-[A-Z_]+$").containsMatchIn(variant.variantId)) {
            parseIndexedCoordinate(variant.variantId, "MISMATCH")
        } else null
        if (profileIndex != null && namedIndex != null) {
            assertEquals(profileIndex, namedIndex,
                "${variant.variantId}: target profile and named coordinate disagree")
        }
        val expectedIndex = when {
            profileIndex != null -> profileIndex
            namedIndex != null -> namedIndex
            scene.recordId == "REC-RUN-FOREIGN-01" -> 0
            else -> error("${variant.variantId}: unknown target mismatch construction")
        }
        val targetVsCanonical = canonical.indices.filter { targetValues.component(it) != canonical[it] }
        if (profileIndex != null) {
            assertEquals(listOf(expectedIndex), targetVsCanonical,
                "${variant.variantId}: target must differ from canonical at exactly the named coordinate")
            assertEquals(foreign[expectedIndex], targetValues.component(expectedIndex),
                "${variant.variantId}: target replacement is not the frozen foreign coordinate")
            assertTrue((canonical.indices - expectedIndex).all { targetValues.component(it) == canonical[it] },
                "${variant.variantId}: target changed an unnamed coordinate")
        } else {
            assertEquals(emptyList<Int>(), targetVsCanonical,
                "${variant.variantId}: stored-foreign mismatch target must retain canonical coordinates")
        }
        if (stored != null) {
            val changed = (0..11).filter { targetValues.component(it) != stored[it] }
            assertEquals(listOf(expectedIndex), changed,
                "${variant.variantId}: target differs from the stored RUN at an unexpected coordinate")
            notes += "target mismatch index=$expectedIndex named=${harness.fixtureCoordinateName(expectedIndex)} " +
                "storedDelta=$changed"
        } else {
            notes += "stored record is not RUN; mismatch is observable only from the retained target"
        }
        assertPresentation("VIEW-RUN-PROTECTED")
        notes += "problem=${harness.view.problem}"
    }

    private fun assertMasterHeld() {
        assertEquals(1, harness.targetOpens.size, "${variant.variantId}: openTarget count")
        assertEquals(0, harness.publishes.size, "${variant.variantId}: publishClient count")
        assertEquals(0, harness.targetCloseEntries.size, "${variant.variantId}: target close count")
        assertEquals(0, harness.masterCloseEntries.size, "${variant.variantId}: master close count")
        assertEquals(0, harness.clientCloseEntries.size, "${variant.variantId}: client close count")
        val target = harness.ledger.entry(harness.targetContextAlias)
        assertNotNull(target, "${variant.variantId}: target context is not accounted")
        assertTrue(target.state in setOf(OwnerState.HELD, OwnerState.CLOSE_CONFIRMED),
            "${variant.variantId}: target state ${target.state}")
        val masterOpen = harness.masterOpens.singleOrNull()
        if (masterOpen == null) {
            assertEquals(0, harness.masterOpens.size, "${variant.variantId}: unexpected openMaster count")
            assertEquals(0, harness.clientConnects.size, "${variant.variantId}: connectClient without Master")
            assertEquals(0, harness.setupReads.size, "${variant.variantId}: setup read without Master")
            notes += "Master open failure observed; no Client connection"
        } else {
            assertEquals(1, harness.masterOpens.size, "${variant.variantId}: openMaster count")
            assertSame(harness.targetContext, masterOpen.context,
                "${variant.variantId}: openMaster did not receive the exact context")
            when (masterOpen.outcome) {
                "opened" -> {
                    val master = harness.ledger.entry(harness.masterAlias)
                    assertNotNull(master, "${variant.variantId}: master is not accounted")
                    assertTrue(master.state in setOf(OwnerState.HELD, OwnerState.CLOSE_CONFIRMED),
                        "${variant.variantId}: master state ${master.state}")
                    val expectedConnect = harness.masterInitialMode == LocalMasterLifecycleMode.OPERATIONAL &&
                        harness.setupReads.none { it.throwRead }
                    if (!expectedConnect) {
                        assertEquals(0, harness.clientConnects.size,
                            "${variant.variantId}: connectClient must not occur in this non-operational/held path")
                        if (harness.masterInitialMode != LocalMasterLifecycleMode.OPERATIONAL) {
                            assertEquals(0, harness.setupReads.size,
                                "${variant.variantId}: non-operational mode must not read setup")
                        }
                    } else {
                        assertEquals(1, harness.clientConnects.size, "${variant.variantId}: connectClient count")
                        assertExactMasterConnect()
                        when (harness.clientProfileResult()) {
                            "null" -> {
                                assertEquals("null", harness.clientConnects.single().outcome,
                                    "${variant.variantId}: Client profile null")
                                assertNull(harness.clientObject, "${variant.variantId}: null Client became an owner")
                            }
                            "throw" -> {
                                assertEquals("throw", harness.clientConnects.single().outcome,
                                    "${variant.variantId}: Client profile throw")
                                assertNull(harness.clientObject, "${variant.variantId}: thrown Client became an owner")
                            }
                            "returned" -> {
                                assertEquals("returned", harness.clientConnects.single().outcome,
                                    "${variant.variantId}: Client profile returned")
                                val client = assertNotNull(harness.ledger.entry(harness.clientAlias),
                                    "${variant.variantId}: Client is not accounted")
                                assertTrue(client.state in setOf(OwnerState.HELD, OwnerState.CLOSE_CONFIRMED),
                                    "${variant.variantId}: Client state ${client.state}")
                            }
                            else -> error("${variant.variantId}: unknown client profile result")
                        }
                    }
                }
                "unresolved", "throw" -> {
                    assertNull(harness.masterObject, "${variant.variantId}: failed Master open returned an owner")
                    assertEquals(0, harness.clientConnects.size,
                        "${variant.variantId}: connectClient after failed Master open")
                    assertEquals(0, harness.setupReads.size,
                        "${variant.variantId}: setup read after failed Master open")
                }
                else -> error("${variant.variantId}: unknown Master open outcome ${masterOpen.outcome}")
            }
        }
        assertPresentation("VIEW-RUN-PROTECTED")
        notes += "target=${target.state}; master=${masterOpen?.outcome}; " +
            "client=${harness.clientConnects.singleOrNull()?.outcome}; problem=${harness.view.problem}"
    }

    private fun assertClientHeld() {
        assertEquals(1, harness.targetOpens.size, "${variant.variantId}: openTarget count")
        assertEquals(1, harness.masterOpens.size, "${variant.variantId}: openMaster count")
        assertEquals(1, harness.clientConnects.size, "${variant.variantId}: connectClient count")
        assertEquals(0, harness.publishes.size, "${variant.variantId}: publishClient count")
        assertEquals(0, harness.targetCloseEntries.size, "${variant.variantId}: target close count")
        assertEquals(0, harness.masterCloseEntries.size, "${variant.variantId}: master close count")
        assertEquals(0, harness.clientCloseEntries.size, "${variant.variantId}: client close count")
        assertExactMasterConnect()
        listOf(harness.targetContextAlias, harness.masterAlias, harness.clientAlias).forEach { alias ->
            val owner = harness.ledger.entry(alias)
            assertNotNull(owner, "${variant.variantId}: $alias is not accounted")
            assertTrue(owner.state in setOf(OwnerState.HELD, OwnerState.CLOSE_CONFIRMED),
                "${variant.variantId}: $alias state ${owner.state}")
        }
        assertTrue(harness.log.all().any { it.method == "client.isCurrent" && it.detail == "false" },
            "${variant.variantId}: stale Client currentness was not observed as false")
        assertPresentation("VIEW-RUN-PROTECTED")
        notes += "stale client retained; problem=${harness.view.problem}"
    }

    private fun assertPublished() {
        assertEquals(1, harness.targetOpens.size, "${variant.variantId}: openTarget count")
        assertEquals(1, harness.masterOpens.size, "${variant.variantId}: openMaster count")
        assertEquals(1, harness.bindingReads.size, "${variant.variantId}: binding read count")
        assertEquals(1, harness.setupReads.size, "${variant.variantId}: setup read count")
        assertEquals(1, harness.clientConnects.size, "${variant.variantId}: connectClient count")
        assertEquals(1, harness.publishes.size, "${variant.variantId}: publishClient count")
        assertSame(harness.targetContext, harness.masterOpens.single().context,
            "${variant.variantId}: openMaster did not receive the exact context")
        assertExactMasterConnect()
        assertSame(harness.clientObject, harness.publishes.single().client,
            "${variant.variantId}: publishClient did not receive the exact Client")
        assertSame(harness.publishedOwner, harness.publishes.single().owner,
            "${variant.variantId}: publication owner was not captured")
        val connectSequence = harness.clientConnects.single().sequence
        val publishSequence = harness.publishes.single().sequence
        val currentObservations = harness.log.all().filter { it.method == "client.isCurrent" }
        assertTrue(currentObservations.any { it.sequence in (connectSequence + 1) until publishSequence },
            "${variant.variantId}: no currentness observation between connect and publish")
        assertEquals(LocalMasterLifecycleMode.OPERATIONAL, harness.masterFlowValue,
            "${variant.variantId}: Master mode is not OPERATIONAL")
        assertEquals(0, harness.launchStorage.writes, "${variant.variantId}: production hint write")
        assertEquals(0, harness.targetCloseEntries.size + harness.masterCloseEntries.size + harness.clientCloseEntries.size,
            "${variant.variantId}: unexpected close call")
        val transfer = assertNotNull(harness.positiveTransfer(),
            "${variant.variantId}: no positive custody transfer observed at openMaster")
        assertSame(harness.targetContext, transfer.context, "${variant.variantId}: transfer context identity")
        assertSame(harness.masterObject, transfer.master, "${variant.variantId}: transfer Master identity")
        assertEquals(OwnerState.HELD, harness.ledger.entry(harness.masterAlias)?.state,
            "${variant.variantId}: published Master is not held")
        assertEquals(OwnerState.HELD, harness.ledger.entry(harness.clientAlias)?.state,
            "${variant.variantId}: published Client is not held")
        assertPresentation("VIEW-RUN-PUBLISHED")
        val setup = harness.setupReads.single()
        assertEquals(harness.setupProfileDefinition(), setup.definition, "${variant.variantId}: setup definition observation")
        assertEquals(harness.setupProfileReceipt(), setup.receipt, "${variant.variantId}: setup receipt observation")
        notes += "setup=${setup.definition}/${setup.receipt}; publish owner=${harness.publishedOwner?.alias()}; " +
            "withdrawals=${harness.withdraws.size}; transfer=${transfer.contextAlias}->${transfer.masterAlias}"
    }

    private fun assertStaleRejected() {
        assertEquals(1, harness.targetOpens.size, "${variant.variantId}: openTarget count")
        assertEquals(1, harness.masterOpens.size, "${variant.variantId}: openMaster count")
        assertEquals(1, harness.bindingReads.size, "${variant.variantId}: binding read count")
        assertEquals(1, harness.setupReads.size, "${variant.variantId}: setup read count")
        assertEquals(1, harness.clientConnects.size, "${variant.variantId}: connectClient count")
        assertEquals(0, harness.publishes.size, "${variant.variantId}: publishClient count")
        assertEquals(0, harness.targetCloseEntries.size, "${variant.variantId}: target close count")
        assertEquals(0, harness.masterCloseEntries.size, "${variant.variantId}: master close count")
        assertEquals(0, harness.clientCloseEntries.size, "${variant.variantId}: client close count")
        assertExactMasterConnect()
        val target = harness.ledger.entry(harness.targetContextAlias)
        assertNotNull(target, "${variant.variantId}: target is not accounted")
        assertTrue(target.state in setOf(OwnerState.HELD, OwnerState.CLOSE_CONFIRMED),
            "${variant.variantId}: target state ${target.state}")
        val master = harness.ledger.entry(harness.masterAlias)
        assertNotNull(master, "${variant.variantId}: master is not accounted")
        assertTrue(master.state in setOf(OwnerState.HELD, OwnerState.CLOSE_CONFIRMED),
            "${variant.variantId}: master state ${master.state}")
        if (harness.clientObject != null) {
            val client = harness.ledger.entry(harness.clientAlias)
            assertNotNull(client, "${variant.variantId}: client is not accounted")
            assertTrue(client.state in setOf(OwnerState.HELD, OwnerState.CLOSE_CONFIRMED),
                "${variant.variantId}: client state ${client.state}")
        }
        val hintMutation = raceEvents().firstOrNull { it.startsWith("EV-SET-HINT-") }
        if (hintMutation != null) {
            val expectedRecord = when (hintMutation) {
                "EV-SET-HINT-STOP" -> "REC-STOP"
                "EV-SET-HINT-UNRESOLVED-BOM" -> "REC-BOM"
                "EV-SET-HINT-FOREIGN-RUN" -> "REC-RUN-FOREIGN-ALL"
                else -> error("${variant.variantId}: unknown hint mutation $hintMutation")
            }
            val expectedBytes = harness.fixturesRecord(expectedRecord).bytes
            assertNotNull(expectedBytes, "${variant.variantId}: mutated hint has no bytes")
            assertNotNull(harness.launchStorage.bytes, "${variant.variantId}: mutated hint disappeared")
            assertTrue(harness.launchStorage.bytes!!.contentEquals(expectedBytes),
                "${variant.variantId}: test-side hint mutation was not preserved byte-for-byte")
            val mutation = harness.log.all().lastOrNull {
                it.actor == "TEST" && it.method == "launchStorage.mutate"
            }
            assertNotNull(mutation, "${variant.variantId}: TEST hint mutation was not logged")
            val freshReads = harness.log.all().filter {
                it.actor == "PRODUCTION" && it.method == "launchStorage.read" &&
                    it.phase == "returned" && it.sequence > mutation!!.sequence
            }
            assertTrue(freshReads.isNotEmpty(),
                "${variant.variantId}: no fresh launch-record read followed the held-call mutation")
            // L2: the fresh read must return the mutated bytes, not merely occur. No arbitrary
            // total read count is imposed; the actual returned digest is the evidence.
            val mutatedDigest = sha256(expectedBytes)
            val contentReads = freshReads.filter { it.detail.contains("digest=$mutatedDigest") }
            assertTrue(contentReads.isNotEmpty(),
                "${variant.variantId}: later production read did not observe the mutated bytes " +
                    "(expected digest=$mutatedDigest actual=${freshReads.map { it.detail }})")
            notes += "TEST mutation $hintMutation logged at seq=${mutation!!.sequence}; " +
                "fresh production reads=${freshReads.map { it.sequence }}; " +
                "mutated-content read=${contentReads.map { it.sequence }} digest=$mutatedDigest; " +
                "production writes=${harness.launchStorage.writes}"
        } else {
            assertStoredRecordUnchanged()
        }
        if (raceEvents().any { it.startsWith("EV-SET-MODE-") }) {
            assertTrue(harness.masterFlowValue != LocalMasterLifecycleMode.OPERATIONAL,
                "${variant.variantId}: mode mutation did not leave OPERATIONAL")
            val mutation = harness.log.all().lastOrNull {
                it.actor == "TEST" && it.method == "master.mode" && it.phase == "mutated"
            }
            assertNotNull(mutation, "${variant.variantId}: mode mutation was not logged")
            val freshModeReads = harness.log.all().filter {
                it.actor == "PRODUCTION" && it.method == "master.mode" &&
                    it.phase == "observed" && it.sequence > mutation!!.sequence
            }
            assertTrue(freshModeReads.isNotEmpty(),
                "${variant.variantId}: no fresh Master mode read followed the held-call mutation")
            notes += "mode mutation seq=${mutation!!.sequence}; fresh mode reads=" +
                freshModeReads.map { it.sequence }
        }
        if (raceEvents().contains("EV-INVALIDATE-CLIENT")) {
            assertTrue(harness.log.all().any { it.method == "client.isCurrent" && it.detail == "false" },
                "${variant.variantId}: invalidated Client was not observed false")
        }
        assertPresentation("VIEW-RUN-PROTECTED")
        notes += "stale rejection; problem=${harness.view.problem}"
    }

    private fun assertRetiredClosed(retirement: RetirementObservation?) {
        recordConstructionRestriction()
        assertEquals(RetirementObservation.Normal, retirement,
            "${variant.variantId}: CLOSED requires independent normal Deferred completion, was $retirement")
        assertEquals(LocalSetupPhase.CLOSED, harness.view.phase, "${variant.variantId}: CLOSED presentation")
        assertFalse(harness.view.localClientConnected, "${variant.variantId}: CLOSED must be disconnected")
        assertPrivateCleared(harness.view)
        assertEquals(0, harness.publishes.count { it.sequence > harness.retirementSequence() },
            "${variant.variantId}: publication after retirement")
        assertCloseAttemptsAtMostOnce()
        assertGenuineOwnersConfirmed()
        assertWithdrawalCorrelationForRetirement()
        assertTargetSpecificPathCoverage()
        notes += "CLOSED; locked=${harness.view.locked}; close order=${closeOrder()}"
    }

    private fun assertRetiredUnresolved(retirement: RetirementObservation?) {
        recordConstructionRestriction()
        assertTrue(retirement is RetirementObservation.Exceptional || retirement is RetirementObservation.PendingAtObservation,
            "${variant.variantId}: unresolved retirement requires exceptional/pending Deferred, was $retirement")
        assertFalse(harness.view.phase == LocalSetupPhase.CLOSED, "${variant.variantId}: unresolved must not report CLOSED")
        assertPresentation(scene.presentation)
        assertPrivateCleared(harness.view)
        assertCloseAttemptsAtMostOnce()
        assertEquals(0, harness.publishes.count { it.sequence > harness.retirementSequence() },
            "${variant.variantId}: publication after retirement")
        if (harness.fixturesCustodyClass(variant.variantId) == "TARGET_CUSTODY_INDETERMINATE") {
            assertTrue(harness.masterOpens.singleOrNull()?.outcome in setOf("unresolved", "throw"),
                "${variant.variantId}: indeterminate custody without an unresolved/thrown openMaster")
            assertNull(harness.masterObject, "${variant.variantId}: indeterminate custody returned a Master")
            assertEquals(0, harness.masterCloseEntries.size,
                "${variant.variantId}: indeterminate custody invented a Master close")
            val target = assertNotNull(harness.ledger.entry(harness.targetContextAlias),
                "${variant.variantId}: indeterminate target is not accounted")
            assertEquals(ReferenceDisposition.HELD_TRANSFER_UNKNOWN, target.disposition,
                "${variant.variantId}: indeterminate target disposition")
            if (harness.optionalTargetClose()) {
                assertEquals(0, target.confirmations,
                    "${variant.variantId}: optional target close was treated as native confirmation")
                assertTrue(target.optionalCloseAttempts <= 1,
                    "${variant.variantId}: optional target close was retried")
            }
            notes += "G-CUSTODY-INDETERMINATE; held-transfer-unknown; no Master close; no false CLOSED"
        }
        if (harness.withdrawOutcome == "THROW") {
            assertTrue(harness.withdraws.isNotEmpty(), "${variant.variantId}: WD-THROW was not entered")
            val thrown = harness.withdraws.filter { it.outcome == "throw" }
            assertTrue(thrown.isNotEmpty(), "${variant.variantId}: withdrawal did not throw")
            assertEquals(1, harness.withdraws.size,
                "${variant.variantId}: uncertain withdrawal token was retried")
            notes += "G-WITHDRAW-OUTCOME throw; no retry"
        }
        assertWithdrawalCorrelationForRetirement()
        val unconfirmed = harness.ledger.all().filter { it.state == OwnerState.CLOSE_UNCONFIRMED }
        val failingClose = harness.targetCloseEntries + harness.masterCloseEntries + harness.clientCloseEntries
        if (unconfirmed.isEmpty()) {
            val indeterminateCustody = harness.fixturesCustodyClass(variant.variantId) ==
                "TARGET_CUSTODY_INDETERMINATE"
            assertTrue(harness.withdraws.any { it.outcome == "throw" } ||
                retirement is RetirementObservation.PendingAtObservation || indeterminateCustody,
                "${variant.variantId}: unresolved custody has no unconfirmed owner, indeterminate custody or withdrawal")
        }
        notes += "UNRESOLVED; unconfirmed=${unconfirmed.joinToString()}; " +
            "closeOutcomes=${failingClose.joinToString { "${it.owner}:${it.outcome}" }}"
    }

    private fun assertDefenseConditional(retirement: RetirementObservation?) {
        assertEquals("THROW_IF_ENTERED", harness.withdrawOutcome,
            "${variant.variantId}: WD-DEFENSIVE-THROW profile not selected")
        assertPresentation("VIEW-DEFENSE-CONDITIONAL")
        if (harness.withdraws.isEmpty()) {
            notes += "WD-DEFENSIVE-THROW NOT EXERCISED/UNVERIFIED"
            unverified = true
        } else {
            assertTrue(harness.withdraws.any { it.outcome == "throw" },
                "${variant.variantId}: defensive withdrawal did not throw")
            assertEquals(1, harness.withdraws.size, "${variant.variantId}: defensive withdrawal was retried")
            notes += "defensive withdrawal entered and threw; no retry; no false confirmation"
        }
        assertCloseAttemptsAtMostOnce()
        assertEquals(0, harness.publishes.size, "${variant.variantId}: defensive scene must not publish")
        assertEquals(0, harness.publishes.count { it.sequence > harness.retirementSequence() },
            "${variant.variantId}: publication after retirement")
        val closed = retirement == RetirementObservation.Normal && harness.view.phase == LocalSetupPhase.CLOSED
        val unresolved = retirement is RetirementObservation.Exceptional ||
            retirement is RetirementObservation.PendingAtObservation ||
            harness.view.phase != LocalSetupPhase.CLOSED
        assertTrue(closed || unresolved, "${variant.variantId}: final class must be CLOSED or UNRESOLVED")
        if (closed) {
            assertGenuineOwnersConfirmed()
            notes += "RETIRED_CLOSED branch observed"
        } else {
            assertFalse(harness.view.phase == LocalSetupPhase.CLOSED, "${variant.variantId}: unresolved cannot be CLOSED")
            notes += "RETIRED_UNRESOLVED branch observed"
        }
        assertWithdrawalCorrelationForRetirement()
        notes += "final Deferred=$retirement"
    }

    private fun assertGenuineOwnersConfirmed() {
        val target = harness.ledger.entry(harness.targetContextAlias)
        if (harness.masterObject != null) {
            val master = harness.ledger.entry(harness.masterAlias)
            assertNotNull(master, "${variant.variantId}: master is not accounted")
            assertEquals(ReferenceDisposition.NATIVE_OWNER, master?.disposition,
                "${variant.variantId}: Master disposition is not native owner")
            assertEquals(OwnerState.CLOSE_CONFIRMED, master.state,
                "${variant.variantId}: genuine Master owner was not confirmed")
            if (harness.clientObject != null) {
                val client = harness.ledger.entry(harness.clientAlias)
                assertNotNull(client, "${variant.variantId}: client is not accounted")
                assertEquals(ReferenceDisposition.CLIENT_RESOURCE, client?.disposition,
                    "${variant.variantId}: Client disposition is not resource owner")
                assertEquals(OwnerState.CLOSE_CONFIRMED, client.state,
                    "${variant.variantId}: genuine Client owner was not confirmed")
            }
            assertNotNull(target, "${variant.variantId}: transferred target reference disappeared")
            assertEquals(ReferenceDisposition.TRANSFERRED, target?.disposition,
                "${variant.variantId}: former target reference is not accounted as TRANSFERRED")
            val transfer = harness.positiveTransfer()
            assertNotNull(transfer, "${variant.variantId}: target context is not positively transferred")
            assertSame(harness.targetContext, transfer?.context,
                "${variant.variantId}: transfer observation has another context")
            assertSame(harness.masterObject, transfer?.master,
                "${variant.variantId}: transfer observation has another Master")
            notes += "target context positively transferred at openMaster; stale context close not required"
        } else if (target != null) {
            assertEquals(ReferenceDisposition.NATIVE_OWNER, target.disposition,
                "${variant.variantId}: target-only owner disposition")
            assertEquals(OwnerState.CLOSE_CONFIRMED, target.state,
                "${variant.variantId}: target-only owner was not confirmed")
        }
    }

    private fun assertCloseAttemptsAtMostOnce() {
        listOf(
            harness.targetContextAlias to harness.targetCloseEntries,
            harness.masterAlias to harness.masterCloseEntries,
            harness.clientAlias to harness.clientCloseEntries,
        ).forEach { (alias, entries) ->
            assertTrue(entries.size <= 1, "${variant.variantId}: $alias has ${entries.size} close attempts")
            val owner = harness.ledger.entry(alias)
            if (owner != null) assertTrue(owner.closeAttempts <= 1,
                "${variant.variantId}: ledger $alias has ${owner.closeAttempts} close attempts")
        }
    }

    private fun assertExactMasterConnect() {
        val connect = harness.clientConnects.singleOrNull() ?: return
        assertSame(harness.masterObject, connect.master,
            "${variant.variantId}: connectClient did not receive the exact Master")
        assertEquals(LocalOriginalClientJournalAction.REOPEN_EXISTING, connect.action,
            "${variant.variantId}: connectClient action")
    }

    private fun assertGenericWithdrawalCorrelation() {
        if (scene.withdraw == "WD-FORBIDDEN") {
            assertEquals(0, harness.withdraws.size, "${variant.variantId}: WD-FORBIDDEN withdrawal")
            return
        }
        if (harness.withdraws.isEmpty()) {
            if (scene.withdraw == "WD-CONDITIONAL") {
                conditionalGaps += "DEFENSIVE-WITHDRAWAL-NOT-EXERCISED"
            }
            return
        }
        val published = harness.publishedOwner
        if (published != null) {
            harness.withdraws.forEach { withdrawal ->
                assertSame(published, withdrawal.owner,
                    "${variant.variantId}: withdrawal did not use the exact published owner")
                assertTrue(withdrawal.alias != "TOKEN-OTHER",
                    "${variant.variantId}: withdrawal logged an unrelated lifecycle owner")
            }
        } else {
            val first = harness.withdraws.first().owner
            harness.withdraws.forEach { withdrawal ->
                assertSame(first, withdrawal.owner,
                    "${variant.variantId}: defensive withdrawals used different lifecycle owners")
                assertTrue(withdrawal.alias != "TOKEN-OTHER",
                    "${variant.variantId}: withdrawal logged an unrelated lifecycle owner")
            }
        }
        assertTrue(harness.forbiddenCalls.none { it.contains("uncertain-token-retry") },
            "${variant.variantId}: uncertain withdrawal token was retried")
        notes += "withdrawals=${harness.withdraws.size} correlated=" +
            (if (published != null) "published-owner" else "acquired-token") +
            " aliases=${harness.withdraws.joinToString(",") { "${it.alias}:${it.outcome}" }}"
    }

    private fun assertGenericDenialCorrelation() {
        val validAliases = buildSet {
            if (harness.targetContext != null) add(harness.targetContextAlias)
            if (harness.masterObject != null) add(harness.masterAlias)
            if (harness.clientObject != null) add(harness.clientAlias)
        }
        harness.denialCalls.forEach { denial ->
            assertTrue(denial.method.isNotEmpty(), "${variant.variantId}: denial log corrupt")
            assertTrue(denial.alias in validAliases,
                "${variant.variantId}: denial ${denial.method} targeted unrelated alias ${denial.alias}")
        }
        assertTrue(harness.forbiddenCalls.none { it.contains("denial") },
            "${variant.variantId}: forbidden denial path observed")
        if (harness.denialCalls.isNotEmpty()) {
            notes += "denials=" + harness.denialCalls.joinToString(",") { "${it.method}@${it.alias}" }
        }
    }

    private fun assertBindingAndSetupConsistency() {
        harness.bindingReads.filter { it.outcome == "retained" }.forEach { read ->
            val material = assertNotNull(read.material, "${variant.variantId}: retained binding has no material")
            val target = assertNotNull(harness.targetContext?.materialAtOpen,
                "${variant.variantId}: retained binding without an opened target")
            assertTrue((0..11).all { material.coordinates.component(it) == target.coordinates.component(it) },
                "${variant.variantId}: retained binding material differs from the opened target")
        }
        val targetOpened = harness.targetOpens.singleOrNull()?.outcome == "opened"
        val retirementDuringTarget = raceStages.any { stage ->
            stage.hold == "G-TARGET" && stage.onEnter.contains("EV-REQUEST-RETIREMENT")
        }
        if (targetOpened && scene.binding != null && scene.expected != "TARGET_HELD_MISMATCH" &&
            !retirementDuringTarget
        ) {
            assertEquals(1, harness.bindingReads.size,
                "${variant.variantId}: binding reread count on an opened existing target")
        }
        val masterOpen = harness.masterOpens.singleOrNull()
        if (masterOpen?.outcome == "opened") {
            val expectedSetup = harness.masterInitialMode == LocalMasterLifecycleMode.OPERATIONAL
            val retirementDuringMaster = raceStages.any { stage ->
                stage.hold == "G-MASTER" && stage.onEnter.contains("EV-REQUEST-RETIREMENT")
            }
            if (expectedSetup && !retirementDuringMaster) {
                assertEquals(1, harness.setupReads.size,
                    "${variant.variantId}: operational Master setup read count")
                val setup = harness.setupReads.single()
                assertEquals(harness.setupProfileDefinition(), setup.definition,
                    "${variant.variantId}: setup definition observation")
                assertEquals(harness.setupProfileReceipt(), setup.receipt,
                    "${variant.variantId}: setup receipt observation")
                assertEquals(harness.setupProfileThrow(), setup.throwRead,
                    "${variant.variantId}: setup throw observation")
            } else if (expectedSetup) {
                // A held openMaster that returns after retirement is captured and closed
                // without reading setup or progressing to Client/publication.
                assertTrue(harness.setupReads.size <= 1,
                    "${variant.variantId}: late Master read setup more than once")
            } else {
                assertEquals(0, harness.setupReads.size,
                    "${variant.variantId}: non-operational held Master must not read setup")
            }
        } else {
            assertEquals(0, harness.setupReads.size,
                "${variant.variantId}: setup read without an opened Master")
        }
    }

    private fun assertCustodyDisposition() {
        val expectedCustody = harness.fixturesCustodyClass(variant.variantId)
        val target = harness.ledger.entry(harness.targetContextAlias)
        when (expectedCustody) {
            "NO_NATIVE_OWNER" -> {
                assertNull(target, "${variant.variantId}: no-target scene fabricated a target owner")
                assertTrue(harness.targetOpens.isEmpty(),
                    "${variant.variantId}: no-native-owner scene acquired a target")
            }
            "NO_TARGET" -> {
                assertTrue(target == null || target.disposition == ReferenceDisposition.NO_HANDLE,
                    "${variant.variantId}: failed target open fabricated ${target?.disposition}")
                assertNull(harness.targetContext, "${variant.variantId}: failed target open returned a context")
            }
            "TARGET_NATIVE_PRE_TRANSFER" -> {
                val owner = assertNotNull(target, "${variant.variantId}: pre-transfer target is not accounted")
                assertEquals(ReferenceDisposition.NATIVE_OWNER, owner.disposition,
                    "${variant.variantId}: pre-transfer target disposition")
                assertEquals(0, harness.masterOpens.size,
                    "${variant.variantId}: pre-transfer target scene invoked openMaster")
            }
            "TARGET_CUSTODY_INDETERMINATE" -> {
                val owner = assertNotNull(target, "${variant.variantId}: indeterminate target is not accounted")
                assertEquals(ReferenceDisposition.HELD_TRANSFER_UNKNOWN, owner.disposition,
                    "${variant.variantId}: indeterminate target disposition")
                assertNull(harness.masterObject, "${variant.variantId}: indeterminate custody returned an owner")
            }
            "MASTER_NATIVE_TARGET_STALE" -> {
                val master = assertNotNull(harness.masterObject,
                    "${variant.variantId}: master-native scene has no Master")
                val owner = assertNotNull(target, "${variant.variantId}: transferred target is not accounted")
                assertEquals(ReferenceDisposition.TRANSFERRED, owner.disposition,
                    "${variant.variantId}: post-transfer target disposition")
                assertEquals(ReferenceDisposition.NATIVE_OWNER,
                    harness.ledger.entry(harness.masterAlias)?.disposition,
                    "${variant.variantId}: Master disposition")
                assertSame(harness.targetContext, harness.positiveTransfer()?.context,
                    "${variant.variantId}: transfer context identity")
                assertSame(master, harness.positiveTransfer()?.master,
                    "${variant.variantId}: transfer Master identity")
            }
            else -> error("${variant.variantId}: unknown inventory custody class $expectedCustody")
        }
        notes += "custody=$expectedCustody targetDisposition=${target?.disposition ?: "NO_HANDLE"}"
    }

    private fun assertBindingChangedContract() {
        if (scene.binding?.startsWith("BND-CHANGED") != true) return
        notes += "D3-03 CONTROLLED-BINDING-INPUT: the frozen scene separates material_source=canonical " +
            "from binding_source=canonical_with_foreign_at. The native adapter normally validates the " +
            "original signed binding before returning Retained; this controlled disagreement exercises " +
            "coordinator consistency checks through the existing public originalNativeBindingDigest. " +
            "Actual calls below determine the result against unchanged zero-progression assertions. " +
            "No native signature-validation execution or authority is established by this fixture."
        assertEquals(1, harness.bindingReads.size, "${variant.variantId}: D3-03 changed binding read count")
        assertEquals("retained", harness.bindingReads.single().outcome,
            "${variant.variantId}: D3-03 changed binding observation")
        assertEquals(0, harness.masterOpens.size,
            "${variant.variantId}: D3-03 requires zero openMaster after binding disagreement")
        assertEquals(0, harness.clientConnects.size,
            "${variant.variantId}: D3-03 requires zero connectClient after binding disagreement")
        assertEquals(0, harness.publishes.size,
            "${variant.variantId}: D3-03 requires zero publishClient after binding disagreement")
        assertEquals(OwnerState.HELD, harness.ledger.entry(harness.targetContextAlias)?.state,
            "${variant.variantId}: D3-03 target must remain accounted")
        val expectedIndex = parseIndexedCoordinate(variant.variantId, "BINDING-CHANGED")
        val binding = assertNotNull(harness.bindingObjectForAssertion(),
            "${variant.variantId}: controlled binding object is missing")
        val actualDigest = harness.fingerprintBinding(binding)
        val referenceDigests = (0..11).associateWith { index ->
            harness.fingerprintCoordinates("canonical", index)
        }
        assertEquals(referenceDigests.getValue(expectedIndex), actualDigest,
            "${variant.variantId}: binding bytes do not match the named changed coordinate")
        assertTrue((0..11).filter { it != expectedIndex }.none { referenceDigests.getValue(it) == actualDigest },
            "${variant.variantId}: binding bytes also match an unnamed coordinate")
        val material = assertNotNull(harness.bindingReads.single().material,
            "${variant.variantId}: D3-03 retained binding has no material")
        val materialCoordinates = material.coordinates
        val canonical = harness.fixturesCanonicalCoordinates()
        assertEquals(emptyList<Int>(), canonical.indices.filter { materialCoordinates.component(it) != canonical[it] },
            "${variant.variantId}: D3-03 material coordinates are not canonical")
        notes += "D3-03 changed binding coordinate=${harness.fixtureCoordinateName(expectedIndex)} " +
            "digest=$actualDigest openMaster=${harness.masterOpens.size} " +
            "connect=${harness.clientConnects.size} publish=${harness.publishes.size}"
    }

    private fun assertWithdrawalCorrelationForRetirement() {
        if (harness.withdraws.isEmpty()) {
            if (harness.publishes.isNotEmpty()) {
                conditionalGaps += "UNVERIFIED-WITHDRAWAL-CORRELATION (no withdrawal call observed)"
            } else if (scene.withdraw == "WD-CONDITIONAL") {
                conditionalGaps += "DEFENSIVE-WITHDRAWAL-NOT-EXERCISED"
            }
        }
    }

    private fun assertTargetSpecificPathCoverage() {
        if (scene.closeTarget !in TARGET_CLOSE_SPECIFIC) return
        if (harness.positiveTransfer() != null) {
            if (!harness.targetContextCloseEntered()) {
                notes += "CONSTRUCTION-RESTRICTION: the target context was positively transferred at " +
                    "openMaster; the public original-context close is inert and is not an independent " +
                    "owner close. The frozen target-specific close checkpoint is not constructible."
                unverified = true
            }
            return
        }
        assertEquals(1, harness.targetCloseEntries.size,
            "${variant.variantId}: pre-transfer target close must be entered exactly once")
        val entry = harness.targetCloseEntries.single()
        val release = assertNotNull(harness.gateReleaseSequence("G-TARGET"),
            "${variant.variantId}: pre-transfer target had no released G-TARGET")
        assertTrue(entry.sequence > release,
            "${variant.variantId}: target close was entered before the target hold was released")
        notes += "pre-transfer target close entered once at seq=${entry.sequence} after G-TARGET release seq=$release; " +
            "outcome=${entry.outcome}"
    }

    private fun recordConstructionRestriction() {
        if (scene.expected == "RETIRED_CLOSED" && harness.masterObject == null &&
            harness.masterOpens.singleOrNull()?.outcome == "unresolved" &&
            !harness.targetContextCloseEntered()
        ) {
            notes += "CONTRACT-BLOCKER: openMaster returned Unresolved without exposing whether native " +
                "custody was consumed. Production retains attachmentPending and cannot positively " +
                "confirm a target-only close; the frozen target-only RETIRED_CLOSED path requires " +
                "independent adjudication or a transfer-status contract, not an inferred closure."
        }
        if (scene.closeTarget in TARGET_CLOSE_SPECIFIC && harness.positiveTransfer() != null &&
            !harness.targetContextCloseEntered()
        ) {
            notes += "CONTRACT-BLOCKER: the frozen target-close outcome is not an independent owner " +
                "after positive openMaster custody transfer; actual Master/Client closes are recorded."
        }
    }

    private fun assertIngressRefusal() {
        val raceStages = scene.race?.stages ?: emptyList()
        val events = buildList {
            raceStages.forEach { stage ->
                addAll(stage.onEnter)
                addAll(stage.afterSettle)
                addAll(stage.afterOutcome)
                addAll(stage.afterClosed)
            }
        }
        if (events.contains("EV-REPEAT-REOPEN")) {
            assertTrue(harness.repeatReopenRefusals > 0,
                "${variant.variantId}: repeat reopen was not positively refused")
            notes += "G-INGRESS-REFUSED: repeat reopen refused"
        }
        if (events.contains("EV-REPEAT-RETIRE")) {
            assertTrue(harness.repeatRetirementRefusals > 0,
                "${variant.variantId}: repeat retirement was not positively refused")
            notes += "G-INGRESS-REFUSED: repeat retirement returned the same Deferred"
        }
    }

    private fun assertDrainCheckpoint() {
        if (scene.drain != "STARTUP-WORK-DRAIN-REQUIRED") return
        val completedAt = assertNotNull(harness.startupJobCompletedAtSequence,
            "${variant.variantId}: no positive startup completion observation")
        val firstClose = assertNotNull(harness.firstRetirementCloseSequence(),
            "${variant.variantId}: no actual close entered after startup drain")
        assertTrue(firstClose > completedAt,
            "${variant.variantId}: close entered before admitted startup work drained")
        notes += "G-STARTUP-DRAIN observed: startup completed at seq=$completedAt before first close seq=$firstClose"
    }

    /**
     * L1: a published RET-NONE scene must have exactly one live client observer at the preshutdown
     * core snapshot. The watchdog registration is not a Job and never satisfies this count.
     */
    private fun assertPublishedObserverCensus() {
        if (scene.expected != "PUBLISHED" || scene.retirement != "RET-NONE") return
        val observers = harness.observerJobs().filter { it.isActive }
        assertEquals(1, observers.size,
            "${variant.variantId}: published RET-NONE scene must have exactly one active client observer, " +
                "actual=${harness.observerJobs().map { "${it.alias()}:${harness.jobState(it)}" }}")
        val observer = observers.single()
        assertTrue(observer.isActive, "${variant.variantId}: published client observer is not active")
        notes += "PUBLISHED-CLIENT-OBSERVER alias=${observer.alias()} state=${harness.jobState(observer)} " +
            "watchdog=${if (harness.observerWatching()) "registered-not-a-job" else "stopped"}"
    }

    /** Flat and staged race steps are both first-class; an unentered hook never counts. */
    private fun raceEvents(): List<String> {
        val race = scene.race ?: return emptyList()
        return buildList {
            addAll(race.onEnter)
            race.stages.forEach { stage -> addAll(stage.onEnter) }
        }
    }

    private fun recordConditionalCoverage() {
        if (scene.firstClose != "NONE" && !harness.firstCloseGateEntered) {
            conditionalGaps += "FIRST-CLOSE-NOT-EXERCISED"
        }
        if (scene.withdraw == "WD-DEFENSIVE-THROW" && harness.withdraws.isEmpty()) {
            conditionalGaps += "WD-DEFENSIVE-THROW-NOT-EXERCISED"
        }
        if (harness.optionalTargetClose()) {
            val profile = harness.closeTargetProfileForAssertion()
            if (harness.targetCloseEntries.isEmpty()) {
                conditionalGaps += "OPTIONAL-TARGET-CLOSE-NOT-EXERCISED (${profile.id})"
                notes += "optional target close ${profile.id} not entered; HELD_TRANSFER_UNKNOWN core only"
            } else {
                assertEquals(1, harness.targetCloseEntries.size,
                    "${variant.variantId}: optional target close entered more than once")
                conditionalGaps += "OPTIONAL-TARGET-CLOSE-EXERCISED-DIAGNOSTIC (${profile.id})"
                notes += "optional target close entered once as diagnostic; native confirmation not inferred"
            }
        }
        if (scene.presentation.startsWith("VIEW-UI-CLEARED")) {
            conditionalGaps += "NATIVE-CONFIRMATION-RECORDED-SEPARATELY"
        }
    }

    private fun closeOrder(): String = buildList {
        harness.targetCloseEntries.forEach { add("target:${it.outcome}") }
        harness.masterCloseEntries.forEach { add("master:${it.outcome}") }
        harness.clientCloseEntries.forEach { add("client:${it.outcome}") }
    }.joinToString(",")

    private fun assertPresentation(expected: String) {
        val view = harness.view
        when (expected) {
            "VIEW-INITIAL-NO-OWNER" -> {
                assertEquals(LocalSetupPhase.CHOOSE_ROUTE, view.phase, "${variant.variantId}: initial phase")
                assertFalse(view.locked, "${variant.variantId}: initial locked")
                assertFalse(view.localClientConnected, "${variant.variantId}: initial connection")
            }
            "VIEW-RUN-PROTECTED" -> {
                assertTrue(view.locked, "${variant.variantId}: RUN-protected locked")
                assertFalse(view.localClientConnected, "${variant.variantId}: RUN-protected connection")
            }
            "VIEW-RUN-PUBLISHED" -> {
                assertTrue(view.locked, "${variant.variantId}: RUN-published locked")
                assertTrue(view.localClientConnected, "${variant.variantId}: RUN-published connection")
                assertTrue(harness.publishes.isNotEmpty(), "${variant.variantId}: connection without publication")
            }
            "VIEW-RETIRED" -> {
                assertEquals(LocalSetupPhase.CLOSED, view.phase, "${variant.variantId}: retired phase")
                assertFalse(view.localClientConnected, "${variant.variantId}: retired connection")
            }
            "VIEW-UI-CLEARED-WITHDRAW-UNCONFIRMED", "VIEW-UI-CLEARED-CLOSE-UNCONFIRMED" -> {
                // routeSelection, locked and localClientConnected are RECORD_ACTUAL.
                // The UI-cleared profile only fixes privacy; native confirmation is separate.
            }
            "VIEW-DEFENSE-CONDITIONAL" -> Unit
            else -> error("${variant.variantId}: unknown presentation profile $expected")
        }
        assertPrivateCleared(view)
        notes += "presentation=$expected phase=${view.phase} locked=${view.locked} " +
            "connected=${view.localClientConnected} problem=${view.problem}"
    }

    private fun assertPrivateCleared(view: LocalSetupView) {
        assertNull(view.companyReview, "${variant.variantId}: private company review exposed")
        assertNull(view.company, "${variant.variantId}: private company exposed")
        assertNull(view.branchReview, "${variant.variantId}: private branch review exposed")
        assertNull(view.native, "${variant.variantId}: private native view exposed")
        assertNull(view.receipt, "${variant.variantId}: private receipt exposed")
        assertNull(view.firstAccountHeadLogin, "${variant.variantId}: private head login exposed")
        assertNull(view.definitionReview, "${variant.variantId}: private definition exposed")
        assertNull(view.employeeEnrollments, "${variant.variantId}: employee enrollments exposed")
        assertNull(view.employeeEnrollment, "${variant.variantId}: employee enrollment exposed")
        assertNull(view.employeePolicies, "${variant.variantId}: employee policies exposed")
        assertNull(view.employeePolicy, "${variant.variantId}: employee policy exposed")
        assertNull(view.deviceAdmissions, "${variant.variantId}: device admissions exposed")
        assertNull(view.deviceAdmission, "${variant.variantId}: device admission exposed")
        assertNull(view.freshDevicePairing, "${variant.variantId}: fresh pairing exposed")
    }

    private fun assertNoForbiddenEffects() {
        assertTrue(harness.forbiddenCalls.isEmpty(),
            "${variant.variantId}: forbidden effects observed ${harness.forbiddenCalls}")
        assertTrue(harness.denialCalls.all { it.method.isNotEmpty() }, "${variant.variantId}: denial log corrupt")
        assertTrue(harness.hostNotices.size <= 1, "${variant.variantId}: duplicate client-hosting notice")
    }

    private fun assertNoHintWrite() {
        assertEquals(0, harness.launchStorage.writes, "${variant.variantId}: production hint write")
    }

    private fun assertSingleAcquisition() {
        assertTrue(harness.targetOpens.size <= 1, "${variant.variantId}: second target acquisition")
        assertTrue(harness.masterOpens.size <= 1, "${variant.variantId}: second Master open")
        assertTrue(harness.clientConnects.size <= 1, "${variant.variantId}: second Client connect")
        assertTrue(harness.publishes.size <= 1, "${variant.variantId}: second publication")
    }

    private fun assertStoredRecordUnchanged() {
        val initial = harness.fixturesRecord(scene.recordId)
        val actual = harness.launchStorage.bytes
        if (initial.bytes == null) {
            assertNull(actual, "${variant.variantId}: absent record was materialized")
        } else {
            assertNotNull(actual, "${variant.variantId}: stored record disappeared")
            assertTrue(actual.contentEquals(initial.bytes), "${variant.variantId}: stored record bytes changed")
        }
    }

    private suspend fun teardown() {
        val coreCensus = harness.jobCensus()
        waiters.forEach { waiter -> runCatching { waiter.cancelAndJoin() } }
        harness.beginTeardown()
        var rounds = 0
        while (rounds < 32) {
            harness.releaseAllGates()
            harness.runUntilIdle()
            rounds++
            if (harness.unreleasedGates().isEmpty()) break
        }
        harness.runUntilIdle()
        val beforeCancel = harness.jobCensus()
        val active = harness.jobs().filter { it.isActive }
        active.forEach { it.cancel() }
        harness.runUntilIdle()
        active.forEach { job -> runCatching { job.join() } }
        harness.runUntilIdle()
        val finalCensus = harness.jobCensus()
        val remaining = harness.jobs().filter { !it.isCompleted }
        val startup = harness.startupJob
        val retirement = harness.retirementJobs
        val nonWorker = harness.jobs().filter { job ->
            job !== startup && retirement.none { it === job }
        }
        val observers = if (harness.publishes.isNotEmpty()) nonWorker.take(1) else emptyList()
        val unclassified = if (harness.publishes.isNotEmpty()) nonWorker.drop(1) else nonWorker
        val deferredState = harness.retirementDeferred?.let { deferred ->
            when {
                !deferred.isCompleted -> "pending-at-teardown"
                runCatching { deferred.await() }.isSuccess -> "normal"
                else -> "exceptional"
            }
        } ?: "absent"
        val startupState = startup?.let { job ->
            when {
                !job.isCompleted -> "unjoined"
                job.isCancelled -> "cancelled"
                else -> "completed"
            }
        } ?: "absent"
        teardownNotes += "teardown-core-census=${coreCensus.joinToString(" | ")}"
        teardownNotes += "teardown-before-cancel=${beforeCancel.joinToString(" | ")}"
        teardownNotes += "teardown-canceled=${active.joinToString(",") { it.alias() }.ifEmpty { "none" }}"
        teardownNotes += "teardown-final-census=${finalCensus.joinToString(" | ")}"
        teardownNotes += "teardown-observers=${observers.joinToString(",") { "${it.alias()}:${harness.jobState(it)}" }.ifEmpty { "none" }}" +
            " watchdog=${if (harness.observerWatching()) "registered-not-a-job" else "stopped"}"
        teardownState = "startup=$startupState deferred=$deferredState " +
            "watch=${if (harness.observerWatching()) "registered-not-a-job" else "stopped"} " +
            "remaining=${remaining.joinToString(",") { it.alias() }.ifEmpty { "none" }}"
        if (unclassified.isNotEmpty()) {
            throw AssertionError("${variant.variantId}: unclassified dispatched workers " +
                unclassified.joinToString(",") { "${it.alias()}:${harness.jobState(it)}" })
        }
        if (remaining.isNotEmpty()) {
            throw AssertionError("${variant.variantId}: teardown left unjoined workers " +
                remaining.joinToString(",") { "${it.alias()}:${harness.jobState(it)}" })
        }
    }

    private companion object {
        val STARTUP_GATES = setOf("G-TARGET", "G-MASTER", "G-BINDING", "G-CONNECT")
        val TARGET_CLOSE_SPECIFIC = setOf(
            "CLOSE-TARGET-HELD-CONFIRM", "CLOSE-TARGET-HELD-THROW", "CLOSE-TARGET-THROW",
        )
    }
}

internal fun com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1.component(index: Int): String = when (index) {
    0 -> allocationId.value
    1 -> scope.organizationId.value
    2 -> scope.companyId.value
    3 -> scope.branchId.value
    4 -> installationId.value
    5 -> assignmentId.value
    6 -> deviceId.value
    7 -> storageInstanceId.value
    8 -> storageKeyId.value
    9 -> pepperKeyId.value
    10 -> targetSigningKeyId.value
    11 -> firstActorId.value
    else -> error("Coordinate index $index is out of range")
}

private fun ExistingOnlyStartupHarness.fixturesRecord(recordId: String): FrozenLaunchRecord =
    ExistingOnlyStartupFixtures.record(recordId)

private fun ExistingOnlyStartupHarness.fixturesRecordCoordinates(recordId: String): List<String>? =
    ExistingOnlyStartupFixtures.recordCoordinates(recordId)

private fun ExistingOnlyStartupHarness.fixturesCustodyClass(variantId: String): String =
    ExistingOnlyStartupFixtures.custodyClass(variantId)

private fun ExistingOnlyStartupHarness.fixturesCanonicalCoordinates(): List<String> =
    ExistingOnlyStartupFixtures.canonical

private fun ExistingOnlyStartupHarness.fixturesForeignCoordinates(): List<String> =
    ExistingOnlyStartupFixtures.foreign

private fun ExistingOnlyStartupHarness.fixtureCoordinateName(index: Int): String =
    ExistingOnlyStartupFixtures.coordinateOrder[index]

private fun parseIndexedCoordinate(variantId: String, marker: String): Int {
    val match = Regex("$marker-(\\d{2})-([A-Z_]+)$").find(variantId)
        ?: error("$variantId does not name a $marker coordinate")
    val oneBased = match.groupValues[1].toInt()
    val index = oneBased - 1
    require(index in 0..11) { "$variantId coordinate index is out of range" }
    val name = match.groupValues[2]
    assertEquals(ExistingOnlyStartupFixtures.coordinateOrder[index].uppercase(), name,
        "$variantId coordinate name does not match index $index")
    return index
}

private fun ExistingOnlyStartupHarness.recordClassification(): String =
    ExistingOnlyStartupFixtures.record(scene.recordId).classification
