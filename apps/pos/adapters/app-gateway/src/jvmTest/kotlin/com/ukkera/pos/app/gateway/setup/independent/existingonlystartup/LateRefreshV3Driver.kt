package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.database.LocalMasterLifecycleMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.JsonObject
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal data class LateOutcome(
    val variantId: String,
    val status: VariantStatus,
    val actualClass: String,
    val selectedOutcome: String,
    val observations: List<String>,
    val missing: List<String>,
    val forbiddenPresent: List<String>,
    val gaps: List<String>,
    val notes: List<String>,
    val ledger: String,
    val teardown: String,
)

/**
 * Executor for the frozen late-refresh-v3 temporal contract. The driver only records an
 * observation token after asserting the actual controlled public-port evidence; it never
 * converts a script input, an idleness window or an immediate Unit return into settlement.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class LateRefreshV3Driver(
    private val test: TestScope,
    private val variant: LateRefreshVariant,
) {
    private val harness = SupplementalV8Harness(
        test,
        SupplementalVariant("E3RA-RL", variant.caseTitle, variant.raw),
    )
    private val script = harness.script
    private val observed = linkedSetOf<String>()
    private val notes = mutableListOf<String>()
    private val gaps = mutableListOf<String>()
    private var selectedOutcome: String = "NONE"
    private var actualClass: String = "NOT_OBSERVED"
    private var status: VariantStatus = VariantStatus.FAIL
    private var refreshJob: Job? = null
    private var refreshIssuedReads: Int = 0
    private var refreshIssuedWrites: Int = 0
    private var refreshIssuedSetupReads: Int = 0
    private var refreshIssuedBindingReads: Int = 0
    private var refreshIssuedTrueCurrentness: Int = 0
    private var refreshStartSequence: Int = 0
    private var gateSequence: Int = 0
    private var refreshPublishesBeforeLateAction: Int = 0
    private var publicationStateAtCheckpoint: String = "NONE"
    private var finalPublicationState: String = "NONE"
    private var lateActionApplied: Boolean = false
    private var teardownState: String = "not-run"

    init {
        script.lateSetupGate = GATE
        script.lateRefreshSetup = SetupScript(definition = false, receipt = false, throws = false)
        script.latePublicationSemantics = true
        if (variant.variantId == "E3LR-L2.V02-LATE-WATCHDOG-WITHDRAWAL-THROW") {
            script.lateWithdrawalThrow = true
        }
    }

    suspend fun execute(): LateOutcome {
        var failure: Throwable? = null
        try {
            runVariant()
            assertCommon()
            assertSelectedOutcome()
        } catch (error: Throwable) {
            failure = error
            notes += "FAILURE: ${error.message}"
        }
        val coreNotes = notes.toList()
        val coreObservations = observed.toList()
        val coreGaps = gaps.toList()
        val coreLedger = harness.ledger.summary()
        val missing = selectedOutcomeJson()?.stringList("required_observations")
            ?.filterNot { it in coreObservations } ?: emptyList()
        val forbidden = selectedOutcomeJson()?.stringList("forbidden_observations")
            ?.filter { it in coreObservations } ?: emptyList()
        var teardownFailure: Throwable? = null
        try {
            teardown()
        } catch (error: Throwable) {
            teardownFailure = error
        }
        if (teardownFailure != null) notes += "TEARDOWN-FAILURE: ${teardownFailure.message}"
        if (failure != null) status = VariantStatus.FAIL
        if (teardownFailure != null) status = VariantStatus.FAIL
        if (missing.isNotEmpty() || forbidden.isNotEmpty()) status = VariantStatus.FAIL
        return LateOutcome(
            variantId = variant.variantId,
            status = status,
            actualClass = actualClass,
            selectedOutcome = selectedOutcome,
            observations = coreObservations,
            missing = missing,
            forbiddenPresent = forbidden,
            gaps = coreGaps,
            notes = coreNotes + notes.drop(coreNotes.size),
            ledger = coreLedger,
            teardown = teardownState,
        )
    }

    // ---------------------------------------------------------------- variant flow

    private suspend fun runVariant() {
        startPublishedReconnect()
        assertAutomaticPresentationLocked()
        resumePresentation()
        harness.usePhase(SupplementalV8Harness.Phase.REFRESH)
        refreshIssuedReads = harness.launchStorage.reads
        refreshIssuedWrites = harness.launchStorage.writes
        refreshIssuedSetupReads = harness.setupReads.size
        refreshIssuedBindingReads = harness.bindingReads.size
        refreshIssuedTrueCurrentness = harness.currentnessObservationCount("true")
        refreshStartSequence = harness.log.size
        val created = harness.invokeRefresh()
        assertEquals(1, created.size,
            "${variant.variantId}: admitted refresh must dispatch exactly one finite job")
        refreshJob = created.single()
        harness.runUntilIdle()
        val entered = harness.gateWasEntered(GATE)
        when {
            entered -> runEntered()
            refreshJob!!.isCompleted -> runObservedNonEntry()
            else -> throw AssertionError(
                "${variant.variantId}: refresh is suspended without the late setup gate; " +
                    "entry/non-entry could not be observed",
            )
        }
        notes += "preRefreshReads=$refreshIssuedReads; total=${harness.launchStorage.reads}; " +
            "writes=$refreshIssuedWrites beforeAction; total=${harness.launchStorage.writes}"
    }

    private suspend fun startPublishedReconnect() {
        harness.usePhase(SupplementalV8Harness.Phase.STARTUP)
        val job = harness.captureStartup { harness.coordinator.reopenRetainedMaster() }
        harness.settle(job)
        assertEquals(1, harness.targetOpens.size, "${variant.variantId}: initial target open count")
        assertEquals(1, harness.masterOpens.size, "${variant.variantId}: initial Master open count")
        assertEquals(1, harness.clientConnects.size, "${variant.variantId}: initial Client connect count")
        assertEquals(1, harness.publishes.size, "${variant.variantId}: initial publication count")
        assertEquals(1, harness.livePublications, "${variant.variantId}: initial live publication count")
        assertTrue(harness.view.localClientConnected, "${variant.variantId}: initial reconnect not connected")
        val owner = assertNotNull(harness.publishedOwner,
            "${variant.variantId}: initial publication owner missing")
        val client = assertNotNull(harness.publishedClient,
            "${variant.variantId}: initial published Client missing")
        assertNotNull(harness.positiveTransfer(), "${variant.variantId}: initial target transfer missing")
        notes += "initial reconnect published owner=${owner.alias()} " +
            "client=${client.alias()} live=1"
    }

    private fun assertAutomaticPresentationLocked() {
        assertTrue(harness.view.locked,
            "${variant.variantId}: automatic reconnect must leave presentation private/locked")
    }

    private fun resumePresentation() {
        harness.coordinator.resumePresentation()
        harness.runUntilIdle()
        assertFalse(harness.view.locked,
            "${variant.variantId}: explicit presentation resume did not unlock")
        notes += "explicit resumePresentation; AUTOMATIC_PRESENTATION_LOCKED; NO_AUTHORITY_FROM_PRESENTATION"
    }

    private suspend fun runEntered() {
        gateSequence = assertNotNull(harness.gateEnterSequence(GATE),
            "${variant.variantId}: late setup gate has no observation sequence")
        assertFalse(refreshJob!!.isCompleted,
            "${variant.variantId}: refresh settled while the optional setup read was held")
        assertEarlierCore()
        observe("LATE_SETUP_READ_ENTERED", true)
        observe("LATE_SETUP_HELD", true)
        observe("LATE_CHECKPOINT_ORDER_OBSERVED", true)
        performLateAction()
        assertFalse(refreshJob!!.isCompleted,
            "${variant.variantId}: refresh settled while the late action was applied")
        releaseAndSettle()
        assertPostSettlement(entered = true)
    }

    private fun runObservedNonEntry() {
        assertEquals(refreshIssuedSetupReads, harness.setupReads.size,
            "${variant.variantId}: non-entry observed but a fresh setup read exists")
        observe("LATE_SETUP_BOUNDARY_NOT_ENTERED_OBSERVED", true)
        observe("SETUP_RELEASE_NOT_APPLICABLE_RECORDED", true)
        assertNoLateAction()
        assertEarlierCoreAfterSettlement()
        assertSettlementAndOwners()
        when (variant.caseId) {
            "E3LR-L1", "E3LR-L2" -> {
                selectedOutcome = "ALTERNATE"
                assertPositiveFinal()
                assertAlternateNoLateClaim()
                status = VariantStatus.PASS
                notes += "SELECTED_ALTERNATE condition=LATE_SETUP_BOUNDARY_NOT_ENTERED " +
                    "diagnostic=LATE-SETUP-BOUNDARY-NOT-EXERCISED; no late-boundary coverage"
            }
            "E3LR-L3" -> {
                selectedOutcome = "DIAGNOSTIC"
                actualClass = if (positiveState()) "REFRESH_SAME_ORIGINAL" else "REFRESH_REFUSED_ACCOUNTED"
                observe("NO_LATE_BOUNDARY_COVERAGE", true)
                observe("ACTUAL_REFRESH_OUTCOME_RECORDED", true)
                status = VariantStatus.UNVERIFIED
                gaps += "L3: LATE-SETUP-BOUNDARY-NOT-EXERCISED; optional mode diagnostic not exercised; " +
                    "no late coverage; actual refresh outcome=$actualClass"
                notes += "OPTIONAL-DIAGNOSTIC-NOT-EXERCISED; actual=$actualClass; no pass claim"
            }
            "E3LR-L4" -> {
                if (variant.variantId.contains("V01")) {
                    throw AssertionError(
                        "${variant.variantId}: the held control requires an actually entered setup read",
                    )
                }
                selectedOutcome = "BRANCH_SETUP_READ_NOT_ENTERED"
                assertPositiveFinal()
                observe("NO_LATE_BOUNDARY_COVERAGE", true)
                actualClass = "REFRESH_SAME_ORIGINAL"
                status = VariantStatus.PASS
                notes += "SELECTED_BRANCH SETUP_READ_NOT_ENTERED; positive original-owner control; " +
                    "no read/hold/release/nullable evidence claimed"
            }
            else -> error("${variant.variantId}: unknown case ${variant.caseId}")
        }
    }

    private fun assertEarlierCore() {
        val freshReads = harness.log.all().filter {
            it.actor == "PRODUCTION" && it.method == "launchStorage.read" &&
                it.phase == "returned" && it.sequence > refreshStartSequence
        }
        val canonicalDigest = sha256(ExistingOnlyStartupFixtures.record("REC-RUN-CANONICAL").bytes!!)
        assertTrue(freshReads.any { it.detail.contains("digest=$canonicalDigest") },
            "${variant.variantId}: no fresh canonical RUN production read before the late checkpoint")
        observe("EARLIER_RECORD_POSITIVE", true)
        observe("RECORD_READ", true)
        assertTrue(harness.bindingReads.size > refreshIssuedBindingReads,
            "${variant.variantId}: no fresh binding read before the late checkpoint")
        val binding = harness.bindingReads.last()
        assertEquals("retained", binding.outcome,
            "${variant.variantId}: fresh binding read was not retained")
        assertNotNull(binding.material, "${variant.variantId}: fresh binding material missing")
        assertTrue(coordinatesEqual(binding.material!!.coordinates,
            ExistingOnlyStartupData.canonicalCoordinates),
            "${variant.variantId}: fresh binding material is not the same original")
        observe("EARLIER_BINDING_POSITIVE", true)
        observe("BINDING_READ", true)
        assertTrue(harness.currentnessObservationCount("true") > refreshIssuedTrueCurrentness,
            "${variant.variantId}: no fresh true currentness before the late checkpoint")
        observe("EARLIER_CURRENTNESS_TRUE", true)
        observe("CURRENTNESS_OBSERVED", true)
        observe("CURRENTNESS_TRUE", true)
        assertEquals(LocalMasterLifecycleMode.OPERATIONAL, harness.masterMode(),
            "${variant.variantId}: Master is not operational at the checkpoint")
        val setupEnter = harness.log.all().firstOrNull {
            it.actor == "PRODUCTION" && it.method == "master.readOriginalSetup" &&
                it.phase == "enter" && it.sequence > refreshStartSequence
        } ?: throw AssertionError("${variant.variantId}: no observed setup-read entry")
        assertTrue(harness.log.all().any {
            it.actor == "PRODUCTION" && it.method == "master.mode" &&
                it.detail == "OPERATIONAL" && it.sequence > refreshStartSequence &&
                it.sequence < setupEnter.sequence
        }, "${variant.variantId}: no fresh operational Master observation before the checkpoint")
        observe("MASTER_OPERATIONAL", true)
        assertTrue(harness.view.localClientConnected,
            "${variant.variantId}: the earlier connected core is not positive")
        val lastEarlier = harness.log.all().filter {
            it.sequence < setupEnter.sequence && it.actor == "PRODUCTION" &&
                (it.method == "launchStorage.read" || it.method == "readOriginalNativeBinding" ||
                    it.method == "client.isCurrent" || it.method == "master.mode")
        }.maxOfOrNull { it.sequence } ?: 0
        assertTrue(lastEarlier < setupEnter.sequence,
            "${variant.variantId}: optional setup read was entered before the earlier core")
        notes += "checkpoint order: earlier-core max seq=$lastEarlier < setup-entry seq=" +
            "${setupEnter.sequence}; readOriginalSetup entered at gate=$GATE"
    }

    private fun assertEarlierCoreAfterSettlement() {
        val canonicalDigest = sha256(ExistingOnlyStartupFixtures.record("REC-RUN-CANONICAL").bytes!!)
        assertTrue(harness.log.all().any {
            it.actor == "PRODUCTION" && it.method == "launchStorage.read" &&
                it.phase == "returned" && it.sequence > refreshStartSequence &&
                it.detail.contains("digest=$canonicalDigest")
        }, "${variant.variantId}: no fresh canonical RUN production read")
        observe("RECORD_READ", true)
        assertTrue(harness.bindingReads.size > refreshIssuedBindingReads,
            "${variant.variantId}: no fresh binding read")
        observe("BINDING_READ", true)
        assertTrue(harness.currentnessObservationCount("true") > refreshIssuedTrueCurrentness,
            "${variant.variantId}: no fresh true currentness")
        observe("CURRENTNESS_OBSERVED", true)
        observe("CURRENTNESS_TRUE", true)
    }

    private fun performLateAction() {
        refreshPublishesBeforeLateAction = harness.productionPublishCount()
        when (variant.caseId) {
            "E3LR-L1" -> performRecordChange()
            "E3LR-L2" -> performLifecycleChange()
            "E3LR-L3" -> performModeChange()
            "E3LR-L4" -> performNoLateChange()
            else -> error("${variant.variantId}: unknown case ${variant.caseId}")
        }
    }

    private fun performRecordChange() {
        lateActionApplied = true
        when (variant.recordProfile) {
            "LR-REC-LATE-STOP" -> {
                val bytes = ExistingOnlyStartupFixtures.record("REC-STOP").bytes
                assertNotNull(bytes, "${variant.variantId}: STOP vector missing")
                harness.mutateHint(bytes, "EV-LATE-STOP")
                assertTrue(harness.launchStorage.bytes!!.contentEquals(bytes),
                    "${variant.variantId}: late STOP bytes were not applied")
                notes += "TEST_RECORD_MUTATION applied REC-STOP while $GATE held"
            }
            "LR-REC-LATE-FOREIGN-RUN" -> {
                val bytes = ExistingOnlyStartupFixtures.record("REC-RUN-FOREIGN-01").bytes
                assertNotNull(bytes, "${variant.variantId}: foreign RUN vector missing")
                harness.mutateHint(bytes, "EV-LATE-FOREIGN-RUN")
                assertTrue(harness.launchStorage.bytes!!.contentEquals(bytes),
                    "${variant.variantId}: late foreign RUN bytes were not applied")
                notes += "TEST_RECORD_MUTATION applied foreign RUN while $GATE held"
            }
            "LR-REC-LATE-READ-THROW" -> {
                harness.armReadThrow()
                notes += "CONTROLLED_RECORD_READ armed to throw at the next canonical read while $GATE held"
            }
            else -> error("${variant.variantId}: unknown late record profile ${variant.recordProfile}")
        }
        observe("LATE_CHANGE_APPLIED", true)
        val actor = if (variant.recordProfile == "LR-REC-LATE-READ-THROW") {
            "CONTROLLED_RECORD_READ"
        } else {
            "TEST_RECORD_MUTATION"
        }
        observe("TEST_MUTATION_ACTOR_RECORDED", true)
        notes += "LATE_CHANGE_APPLIED actor=$actor profile=${variant.recordProfile}"
    }

    private fun performLifecycleChange() {
        lateActionApplied = true
        assertEquals(1, harness.livePublications,
            "${variant.variantId}: checkpoint does not have exactly one live publication")
        assertEquals(1, harness.productionPublishCount(),
            "${variant.variantId}: checkpoint has an unexpected production publication count")
        val owner = assertNotNull(harness.publishedOwner,
            "${variant.variantId}: checkpoint must record the exact live owner")
        publicationStateAtCheckpoint = "OLD_ONLY"
        observe("CHECKPOINT_PUBLICATION_STATE_RECORDED", true)
        observe("CHECKPOINT_PUBLICATION_SINGLE_LIVE_OBSERVED", true)
        notes += "CHECKPOINT_PUBLICATION_STATE=OLD_ONLY exactOwner=${owner.alias()} live=1 " +
            "exactOwnerSource=OBSERVED_LIVE_TOKEN_AT_CHECKPOINT"
        val threw = try {
            val observation = harness.independentWithdraw(owner)
            assertSame(owner, observation.owner,
                "${variant.variantId}: independent withdrawal used a different owner")
            assertEquals("unit", observation.outcome,
                "${variant.variantId}: independent withdrawal did not return Unit")
            false
        } catch (failure: ControlledFailure) {
            notes += "independent withdrawClient(exactOwner) THROW observed: ${failure.message}"
            true
        }
        val observation = assertNotNull(harness.withdraws.singleOrNull(),
            "${variant.variantId}: independent withdrawal was not observed")
        assertEquals("INDEPENDENT_LIFECYCLE_WATCHDOG", observation.actor,
            "${variant.variantId}: test withdrawal was not attributed to the independent actor")
        assertSame(owner, observation.owner,
            "${variant.variantId}: withdrawal observation owner is not the exact checkpoint token")
        observe("WATCHDOG_WITHDRAWAL_EXACT_OWNER", true)
        observe("WATCHDOG_ATTRIBUTION_RECORDED", true)
        observe("LATE_CHANGE_APPLIED", true)
        if (threw) {
            assertEquals("throw", observation.outcome,
                "${variant.variantId}: withdrawal throw was not recorded")
            observe("WATCHDOG_WITHDRAWAL_OUTCOME_RECORDED", true)
            assertTrue(harness.publicationUncertain,
                "${variant.variantId}: withdrawal throw was treated as definite no-effect")
            finalPublicationState = "UNCERTAIN"
            gaps += "L2.V02: UNVERIFIED-LATE-PUBLICATION (withdrawal THROW is uncertainty; " +
                "no false absence, no retry, no replacement claimed)"
            notes += "WATCHDOG_WITHDRAW_THROW; publication disposition UNCERTAIN; " +
                "no false absence; no retry"
        } else {
            assertEquals("unit", observation.outcome,
                "${variant.variantId}: withdrawal outcome is not Unit")
            observe("WATCHDOG_WITHDRAWAL_OUTCOME_RECORDED", true)
            assertTrue(harness.publicationWithdrawnByExactOwner,
                "${variant.variantId}: exact-owner withdrawal did not invalidate the published Client")
            finalPublicationState = "ABSENT"
            notes += "WATCHDOG_WITHDRAWAL_OUTCOME=UNIT; live=0; old publication ABSENT"
            if (variant.caseId == "E3LR-L2" && variant.kind == "LATE_LIFECYCLE_FOREIGN") {
                performForeignPublication()
            }
        }
    }

    private fun performForeignPublication() {
        val foreign = ControlledLateForeignClient()
        val owner = Any()
        harness.independentForeignPublish(owner, foreign)
        assertSame(owner, harness.foreignOwner,
            "${variant.variantId}: foreign publication owner changed")
        assertSame(foreign, harness.foreignClient,
            "${variant.variantId}: foreign publication Client changed")
        assertTrue(harness.foreignLive, "${variant.variantId}: foreign publication is not live")
        assertEquals(1, harness.livePublications,
            "${variant.variantId}: final live set is not exactly the retained foreign publication")
        assertTrue(harness.clientAlias() != foreign.constructionIdentity,
            "${variant.variantId}: foreign construction identity is not distinct from the original")
        assertTrue(foreign.isCurrent(),
            "${variant.variantId}: test-owned foreign Client isCurrent observation is false")
        assertTrue(harness.productionPublishCount() == 1,
            "${variant.variantId}: refresh published into the foreign publication slot")
        assertTrue(harness.withdraws.none { it.owner === harness.foreignOwner },
            "${variant.variantId}: refresh withdrew the foreign publication")
        assertTrue(harness.publishes.none {
            it.actor == "PRODUCTION" && it.client === harness.foreignClient
        }, "${variant.variantId}: refresh replaced the foreign publication")
        assertTrue(foreign.isCurrent(),
            "${variant.variantId}: test-owned foreign Client is not current after retention")
        finalPublicationState = "FOREIGN_RETAINED"
        observe("FOREIGN_PUBLICATION_LEDGER_RECORDED", true)
        observe("FOREIGN_PUBLICATION_RETAINED", true)
        observe("NO_FOREIGN_WITHDRAWAL", true)
        notes += "FOREIGN_PUBLICATION_LEDGER_RECORDED exactOwner=FOREIGN-TEST-OWNER " +
            "token=FOREIGN-TEST-CLIENT isCurrent=true retained=true; " +
            "foreignCoordinatesSource=TEST_FIXTURE_CONSTRUCTION_NOT_PRODUCTION_GETTER; " +
            "knownConstructionIdentity differs from original; no Client.coordinates getter"
    }

    private fun performModeChange() {
        lateActionApplied = true
        assertEquals(1, harness.livePublications,
            "${variant.variantId}: checkpoint does not have exactly one live publication")
        publicationStateAtCheckpoint = "OLD_ONLY"
        harness.setMasterMode(LocalMasterLifecycleMode.POLICY_HELD)
        harness.runUntilIdle()
        assertEquals(LocalMasterLifecycleMode.POLICY_HELD, harness.masterMode(),
            "${variant.variantId}: controlled Master mode transition was not applied")
        observe("LATE_CHANGE_APPLIED", true)
        notes += "LATE_CHANGE_APPLIED actor=CONTROLLED_MASTER_MODE_WATCHDOG transition=POLICY_HELD " +
            "source=Master.mode StateFlow; no production setter"
    }

    private fun performNoLateChange() {
        assertFalse(lateActionApplied, "${variant.variantId}: unexpected late action")
        assertEquals(sha256(ExistingOnlyStartupFixtures.record("REC-RUN-CANONICAL").bytes!!),
            sha256(harness.launchStorage.bytes!!),
            "${variant.variantId}: control record bytes changed")
        assertTrue(harness.withdraws.isEmpty(),
            "${variant.variantId}: control performed an independent withdrawal")
        assertEquals(LocalMasterLifecycleMode.OPERATIONAL, harness.masterMode(),
            "${variant.variantId}: control changed the Master mode")
        observe("NO_LATE_CHANGE_APPLIED", true)
        observe("NO_LATE_RECORD_MUTATION_PERFORMED", true)
        observe("NO_WATCHDOG_WITHDRAWAL_PERFORMED", true)
        observe("NO_MODE_TRANSITION_PERFORMED", true)
        observe("NO_LATE_BOUNDARY_COVERAGE", true)
    }

    private fun assertNoLateAction() {
        assertFalse(lateActionApplied, "${variant.variantId}: late action applied without entry")
        assertEquals(sha256(ExistingOnlyStartupFixtures.record("REC-RUN-CANONICAL").bytes!!),
            sha256(harness.launchStorage.bytes!!),
            "${variant.variantId}: non-entry branch mutated the canonical record")
        assertTrue(harness.withdraws.isEmpty(),
            "${variant.variantId}: non-entry branch performed a withdrawal")
        assertEquals(LocalMasterLifecycleMode.OPERATIONAL, harness.masterMode(),
            "${variant.variantId}: non-entry branch changed the Master mode")
        observe("NO_LATE_CHANGE_APPLIED", true)
        observe("NO_LATE_RECORD_MUTATION_PERFORMED", true)
        observe("NO_WATCHDOG_WITHDRAWAL_PERFORMED", true)
        observe("NO_MODE_TRANSITION_PERFORMED", true)
        observe("NO_LATE_BOUNDARY_COVERAGE", true)
    }

    private suspend fun releaseAndSettle() {
        val readsAtGate = harness.launchStorage.reads
        harness.releaseGate(GATE)
        harness.runUntilIdle()
        refreshJob!!.join()
        harness.runUntilIdle()
        assertTrue(refreshJob!!.isCompleted, "${variant.variantId}: refresh job did not settle")
        observe("REFRESH_SETTLEMENT_OBSERVED", true)
        notes += "REFRESH_SETTLEMENT_OBSERVED job=${refreshJob!!.alias()} " +
            "state=${harness.jobState(refreshJob!!)}"
        val setup = harness.setupReads.last()
        assertEquals("null", setup.definition,
            "${variant.variantId}: late setup release was not both-null")
        assertEquals("null", setup.receipt,
            "${variant.variantId}: late setup release was not both-null")
        assertFalse(setup.throwRead, "${variant.variantId}: late setup release threw")
        observe("LATE_SETUP_RELEASE_RESULT_RECORDED", true)
        notes += "LATE_SETUP_RELEASE_RESULT_RECORDED company=non-null definition=null receipt=null"
        if (variant.caseId == "E3LR-L1") {
            val delta = harness.launchStorage.reads - readsAtGate
            when (variant.recordProfile) {
                "LR-REC-LATE-READ-THROW" -> {
                    assertTrue(delta >= 1, "${variant.variantId}: no fresh late record read entered")
                    assertTrue(harness.log.all().any {
                        it.actor == "PRODUCTION" && it.method == "launchStorage.read" &&
                            it.phase == "throw" && it.sequence > gateSequence
                    }, "${variant.variantId}: late record read throw was not observed")
                    observe("THROW_RECORDED", true)
                    observe("LATE_RECORD_READ_AFTER_CHANGE", true)
                    observe("NO_RETRY", delta == 1)
                    notes += "THROW_RECORDED at fresh late read; delta=$delta; no retry; " +
                        "no read-back bytes invented; MUTATION_READ_BACK_RECORDED absent"
                }
                else -> {
                    assertTrue(delta >= 1, "${variant.variantId}: no fresh late record read entered")
                    val expected = when (variant.recordProfile) {
                        "LR-REC-LATE-STOP" -> ExistingOnlyStartupFixtures.record("REC-STOP").bytes
                        "LR-REC-LATE-FOREIGN-RUN" ->
                            ExistingOnlyStartupFixtures.record("REC-RUN-FOREIGN-01").bytes
                        else -> null
                    }
                    assertNotNull(expected, "${variant.variantId}: frozen late record vector missing")
                    assertMutatedReadObserved(expected!!)
                    observe("MUTATION_READ_BACK_RECORDED", true)
                    observe("LATE_RECORD_READ_AFTER_CHANGE", true)
                }
            }
        }
        observe("NULLABLE_NOT_A_GATE", true)
        notes += "NULLABLE_NOT_A_GATE: the both-null release was consumed and the refresh proceeded " +
            "to the late boundary/final claim; the L4 positive control exercises the same both-null release"
    }

    private fun assertMutatedReadObserved(bytes: ByteArray) {
        val mutation = harness.log.all().lastOrNull {
            it.actor == "TEST" && it.method == "launchStorage.mutate"
        }
        assertNotNull(mutation, "${variant.variantId}: TEST mutation was not logged")
        val digest = sha256(bytes)
        val fresh = harness.log.all().filter {
            it.actor == "PRODUCTION" && it.method == "launchStorage.read" &&
                it.phase == "returned" && it.sequence > mutation!!.sequence &&
                it.detail.contains("digest=$digest")
        }
        assertTrue(fresh.isNotEmpty(),
            "${variant.variantId}: fresh production read did not observe the mutated bytes $digest")
        assertTrue(harness.launchStorage.bytes!!.contentEquals(bytes),
            "${variant.variantId}: mutated bytes were not retained")
        notes += "MUTATION_READ_BACK_RECORDED digest=$digest seq=${fresh.map { it.sequence }}"
    }

    private fun assertPostSettlement(entered: Boolean) {
        assertSettlementAndOwners()
        when (variant.caseId) {
            "E3LR-L1" -> assertL1Final()
            "E3LR-L2" -> assertL2Final()
            "E3LR-L3" -> assertL3Final()
            "E3LR-L4" -> assertL4Final()
            else -> error("${variant.variantId}: unknown case ${variant.caseId}")
        }
        if (variant.caseId == "E3LR-L4" && variant.variantId.contains("V02")) {
            val enteredBranch = variant.branchOutcomes.single {
                it.requiredString("branch_id") == "SETUP_READ_ENTERED"
            }
            selectedOutcome = "BRANCH_SETUP_READ_ENTERED"
            assertTrue(entered, "${variant.variantId}: entered branch selected without entry")
            notes += "SELECTED_BRANCH SETUP_READ_ENTERED diagnostic=" +
                enteredBranch.requiredString("diagnostic") + "; no late-boundary coverage"
        } else if (variant.caseId == "E3LR-L4") {
            selectedOutcome = "PRIMARY"
        }
    }

    private fun assertSettlementAndOwners() {
        assertTrue(refreshJob!!.isCompleted, "${variant.variantId}: refresh job did not settle")
        observe("REFRESH_SETTLEMENT_OBSERVED", true)
        assertEquals(0, harness.launchStorage.writes - refreshIssuedWrites,
            "${variant.variantId}: production hint/record write from refresh")
        observe("PRODUCTION_HINT_WRITES_ZERO", true)
        assertEquals(0, harness.retirementRequests,
            "${variant.variantId}: refresh/retirement was requested")
        observe("NO_RETIREMENT", true)
        assertTrue(harness.forbiddenCalls.isEmpty(),
            "${variant.variantId}: forbidden effects ${harness.forbiddenCalls}")
        assertTrue(harness.provisionerOpens.isEmpty(),
            "${variant.variantId}: new setup/provisioner acquisition")
        observe("NO_NEW_SETUP", true)
        assertEquals(1, harness.targetOpens.size,
            "${variant.variantId}: refresh acquired a new target")
        assertEquals(1, harness.masterOpens.size,
            "${variant.variantId}: refresh opened a new Master")
        assertEquals(1, harness.clientConnects.size,
            "${variant.variantId}: refresh connected a new Client")
        assertEquals(0, harness.targetCloseEntries.size + harness.masterCloseEntries.size +
            harness.clientCloseEntries.size,
            "${variant.variantId}: refresh closed an owner")
        observe("NEW_OWNER_LEDGER_RECORDED", true)
        notes += "NEW_OWNER_LEDGER_RECORDED newTarget=NOT_OPENED newMaster=NOT_OPENED " +
            "newClient=NO_HANDLE_RETURNED newPublication=NONE; no retry/second acquisition"
        assertOldOwnerLedger()
    }

    private fun assertOldOwnerLedger() {
        val target = assertNotNull(harness.targetContext,
            "${variant.variantId}: original target context disappeared")
        val master = assertNotNull(harness.masterObject,
            "${variant.variantId}: original Master disappeared")
        val client = assertNotNull(harness.clientObject,
            "${variant.variantId}: original Client disappeared")
        val transfer = assertNotNull(harness.positiveTransfer(),
            "${variant.variantId}: exact prior transfer observation missing")
        assertSame(target, transfer.context, "${variant.variantId}: transfer context changed")
        assertSame(master, transfer.master, "${variant.variantId}: transfer Master changed")
        assertTrue(harness.targetTransferred, "${variant.variantId}: original target not transferred")
        assertEquals(ReferenceDisposition.TRANSFERRED,
            harness.ledger.entry(harness.targetAlias())?.disposition,
            "${variant.variantId}: target ledger is not TRANSFERRED")
        assertEquals(ReferenceDisposition.NATIVE_OWNER,
            harness.ledger.entry(harness.masterAlias())?.disposition,
            "${variant.variantId}: Master ledger is not NATIVE_OWNER")
        assertEquals(ReferenceDisposition.CLIENT_RESOURCE,
            harness.ledger.entry(harness.clientAlias())?.disposition,
            "${variant.variantId}: Client ledger is not CLIENT_RESOURCE")
        assertNotNull(harness.publishedOwner, "${variant.variantId}: publication token missing")
        assertSame(client, harness.publishedClient,
            "${variant.variantId}: published Client identity changed")
        observe("OLD_OWNER_LEDGER_RECORDED", true)
        notes += "OLD_OWNER_LEDGER_RECORDED target=TRANSFERRED exact; master=${harness.masterAlias()} exact; " +
            "client=${harness.clientAlias()} exact; publication=" +
            (harness.publishedOwner?.alias() ?: "missing")
    }

    private fun assertL1Final() {
        assertRefusedFinal()
        assertNotNull(harness.publishedOwner,
            "${variant.variantId}: old publication token disappeared")
        assertEquals(1, harness.productionPublishCount(),
            "${variant.variantId}: production published during the record invalidation")
        assertEquals(1, harness.livePublications,
            "${variant.variantId}: old startup publication was lost")
        assertEquals(0, harness.productionWithdraws().size,
            "${variant.variantId}: record invalidation withdrew the old publication")
        assertNoPublicationAfterLate()
        finalPublicationState = "OLD_STARTUP_PUBLICATION_PRESERVED_NOT_REFRESH_ATTRIBUTABLE"
        notes += "FINAL_PUBLICATION_STATE=OLD_STARTUP_PUBLICATION_PRESERVED (cleanup attribution: " +
            "not refresh-attributable); NO_LIVE_REFRESH_PUBLICATION_AT_FINAL"
        assertEquals("REFRESH_REFUSED_ACCOUNTED", variant.expectedClass,
            "${variant.variantId}: frozen primary class changed")
        actualClass = "REFRESH_REFUSED_ACCOUNTED"
        selectedOutcome = "PRIMARY"
        status = VariantStatus.PASS
        notes += "SELECTED_PRIMARY REFRESH_REFUSED_ACCOUNTED; late record boundary exercised"
    }

    private fun assertL2Final() {
        assertRefusedFinal()
        assertEquals("OLD_ONLY", publicationStateAtCheckpoint,
            "${variant.variantId}: checkpoint publication state changed")
        assertEquals(1, harness.testWithdrawals().size,
            "${variant.variantId}: independent withdrawal count changed")
        assertEquals(0, harness.productionWithdraws().size,
            "${variant.variantId}: refresh performed the independent withdrawal")
        assertEquals(1, harness.productionPublishCount(),
            "${variant.variantId}: refresh published after the late withdrawal")
        assertNoPublicationAfterLate()
        when (finalPublicationState) {
            "ABSENT" -> assertEquals(0, harness.livePublications,
                "${variant.variantId}: withdrawn publication is still live")
            "UNCERTAIN" -> assertTrue(harness.publicationUncertain,
                "${variant.variantId}: withdrawal uncertainty was lost")
            "FOREIGN_RETAINED" -> {
                assertTrue(harness.foreignLive, "${variant.variantId}: foreign publication disappeared")
                assertTrue(harness.withdraws.none { it.owner === harness.foreignOwner },
                    "${variant.variantId}: refresh withdrew the foreign publication")
                assertTrue(harness.publishes.none {
                    it.actor == "PRODUCTION" && it.client === harness.foreignClient
                }, "${variant.variantId}: refresh replaced the foreign publication")
            }
            else -> error("${variant.variantId}: unexpected final publication state $finalPublicationState")
        }
        observe("FINAL_PUBLICATION_STATE_RECORDED", true)
        observe("NO_REFRESH_REPUBLICATION", true)
        notes += "FINAL_PUBLICATION_STATE=$finalPublicationState; " +
            "WATCHDOG_WITHDRAWAL_NOT_REFRESH_CLOSE; NO_REFRESH_REPUBLICATION"
        assertEquals("REFRESH_REFUSED_ACCOUNTED", variant.expectedClass,
            "${variant.variantId}: frozen primary class changed")
        actualClass = "REFRESH_REFUSED_ACCOUNTED"
        selectedOutcome = "PRIMARY"
        status = VariantStatus.PASS
        notes += "SELECTED_PRIMARY REFRESH_REFUSED_ACCOUNTED; late lifecycle boundary exercised"
    }

    private fun assertL3Final() {
        assertRefusedFinal()
        assertTrue(harness.log.all().any {
            it.actor == "PRODUCTION" && it.method == "master.mode" &&
                it.detail == "POLICY_HELD"
        }, "${variant.variantId}: actual POLICY_HELD mode was not observed by production")
        observe("MODE_TRANSITION_OBSERVED", true)
        assertNoPublicationAfterLate()
        val withdrawals = harness.productionWithdraws()
        if (withdrawals.isNotEmpty()) {
            assertEquals(1, withdrawals.size,
                "${variant.variantId}: unexpected production withdrawal count")
            assertSame(harness.publishedOwner, withdrawals.single().owner,
                "${variant.variantId}: mode watchdog withdrew a different owner")
            assertEquals("unit", withdrawals.single().outcome,
                "${variant.variantId}: mode watchdog withdrawal did not return Unit")
            assertEquals(0, harness.livePublications,
                "${variant.variantId}: mode-invalidated publication is still live")
            finalPublicationState = "ABSENT"
            notes += "mode watchdog withdrew the exact live publication; old publication WITHDRAWN_UNIT"
        } else {
            assertEquals(1, harness.livePublications,
                "${variant.variantId}: actual final publication set is neither withdrawn nor preserved")
            finalPublicationState = "OLD_STARTUP_PUBLICATION_PRESERVED_NOT_REFRESH_ATTRIBUTABLE"
            notes += "mode watchdog withdrawal not entered; actual old publication preserved; " +
                "no refresh publication"
        }
        assertEquals("REFRESH_REFUSED_ACCOUNTED", variant.expectedClass,
            "${variant.variantId}: frozen primary class changed")
        actualClass = "REFRESH_REFUSED_ACCOUNTED"
        selectedOutcome = "PRIMARY"
        status = VariantStatus.PASS
        notes += "SELECTED_PRIMARY REFRESH_REFUSED_ACCOUNTED; optional mode boundary exercised"
    }

    private fun assertL4Final() {
        assertPositiveFinal()
        assertEquals(1, harness.productionPublishCount(),
            "${variant.variantId}: control published a replacement")
        assertEquals(1, harness.livePublications,
            "${variant.variantId}: control does not have exactly one live publication")
        assertTrue(harness.productionWithdraws().isEmpty(),
            "${variant.variantId}: control withdrew the publication")
        assertEquals(0, harness.targetCloseEntries.size + harness.masterCloseEntries.size +
            harness.clientCloseEntries.size,
            "${variant.variantId}: control closed an owner")
        observe("PUBLICATION_SINGLE_LIVE", true)
        notes += "PUBLICATION_SINGLE_LIVE; SAME_ORIGINAL_SINGLE; control did not force a late action"
        actualClass = "REFRESH_SAME_ORIGINAL"
        if (selectedOutcome == "NONE") selectedOutcome = "PRIMARY"
        status = VariantStatus.PASS
        notes += "REFRESH_SAME_ORIGINAL; no late-invalidation coverage claimed"
    }

    private fun assertRefusedFinal() {
        assertFalse(harness.view.localClientConnected,
            "${variant.variantId}: refusal retained a positive connected claim")
        assertFalse(harness.view.localClientConnectionAvailable,
            "${variant.variantId}: refusal retained connection availability")
        assertEquals(LocalSetupPhase.ORIGINAL_UNRESOLVED, harness.view.phase,
            "${variant.variantId}: refusal did not reach ORIGINAL_UNRESOLVED")
        assertNotNull(harness.view.problem, "${variant.variantId}: refusal left no observable problem")
        assertNull(harness.view.receipt, "${variant.variantId}: refusal retained a receipt")
        assertNull(harness.view.native, "${variant.variantId}: refusal retained a native projection")
        observe("NO_POSITIVE_REFRESH_CLAIM", true)
        observe("FINAL_CONNECTED_STATE_RECORDED", true)
        observe("NO_RESURRECTION", true)
        notes += "FINAL_CONNECTED_STATE=FALSE; problem=${harness.view.problem}"
    }

    private fun assertPositiveFinal() {
        assertTrue(harness.view.localClientConnected,
            "${variant.variantId}: positive control is not connected")
        assertTrue(harness.view.localClientConnectionAvailable,
            "${variant.variantId}: positive control is not connection-available")
        assertEquals(LocalSetupPhase.INSTALLATION_RETAINED, harness.view.phase,
            "${variant.variantId}: positive control final phase")
        assertNull(harness.view.problem, "${variant.variantId}: positive control retained a problem")
        assertNull(harness.view.receipt, "${variant.variantId}: positive control retained a receipt")
        assertNull(harness.view.native, "${variant.variantId}: positive control retained a native projection")
        assertTrue(harness.currentnessObservationCount("true") > refreshIssuedTrueCurrentness,
            "${variant.variantId}: positive final has no fresh true currentness")
        val lastCurrentness = harness.log.all().lastOrNull {
            it.actor == "PRODUCTION" && it.method == "client.isCurrent" && it.phase == "observed"
        }
        val finalObservationCut = if (gateSequence > 0) gateSequence else refreshStartSequence
        assertTrue(lastCurrentness != null && lastCurrentness.sequence > finalObservationCut,
            "${variant.variantId}: final currentness was not observed after the setup boundary")
        assertTrue(lastCurrentness?.detail == "true",
            "${variant.variantId}: the final currentness observation is not true")
        val canonicalDigest = sha256(ExistingOnlyStartupFixtures.record("REC-RUN-CANONICAL").bytes!!)
        val lastRecord = harness.log.all().lastOrNull {
            it.actor == "PRODUCTION" && it.method == "launchStorage.read" && it.phase == "returned"
        }
        assertTrue(lastRecord != null && lastRecord.sequence > finalObservationCut,
            "${variant.variantId}: final record was not observed after the setup boundary")
        assertTrue(lastRecord?.detail?.contains("digest=$canonicalDigest") == true,
            "${variant.variantId}: the final canonical record observation is not the same original")
        assertEquals(LocalMasterLifecycleMode.OPERATIONAL, harness.masterMode(),
            "${variant.variantId}: positive final has a non-operational Master")
        assertTrue(harness.log.all().any {
            it.actor == "PRODUCTION" && it.method == "master.mode" &&
                it.detail == "OPERATIONAL" && it.sequence > finalObservationCut
        }, "${variant.variantId}: positive final has no operational Master observation after the setup boundary")
        assertEquals(1, harness.livePublications,
            "${variant.variantId}: positive final does not have exactly one live publication")
        observe("CURRENTNESS_OBSERVED", true)
        observe("CURRENTNESS_TRUE", true)
        observe("MASTER_OPERATIONAL", true)
        observe("PUBLICATION_SINGLE_LIVE", true)
        observe("FINAL_CONNECTED_STATE_RECORDED", true)
        notes += "FINAL_CONNECTED_STATE=TRUE_JUSTIFIED; fresh record/binding/currentness, " +
            "operational Master, exactly one live publication at finite settlement"
    }

    private fun assertAlternateNoLateClaim() {
        assertTrue(harness.publishes.none { it.actor == "PRODUCTION" && it.sequence > refreshStartSequence },
            "${variant.variantId}: alternate published")
        assertTrue(harness.withdraws.isEmpty(), "${variant.variantId}: alternate withdrew")
        assertTrue(harness.forbiddenCalls.isEmpty(),
            "${variant.variantId}: alternate performed a late/refused effect")
        notes += "alternate retained positive core and no late-boundary coverage"
    }

    private fun assertNoPublicationAfterLate() {
        assertEquals(refreshPublishesBeforeLateAction, harness.productionPublishCount(),
            "${variant.variantId}: production published after the late invalidation")
        observe("NO_PUBLICATION_AFTER_LATE_INVALIDATION", true)
        assertTrue(harness.publishes.none {
            it.actor == "PRODUCTION" && it.sequence > refreshStartSequence
        }, "${variant.variantId}: refresh-attributable publication exists at final")
        observe("NO_LIVE_REFRESH_PUBLICATION_AT_FINAL", true)
        notes += "REFRESH_PUBLICATION_ACCOUNTED_IF_PRESENT=NOT_APPLICABLE_NO_REFRESH_PUBLICATION"
        observe("REFRESH_PUBLICATION_ACCOUNTED_IF_PRESENT", true)
    }

    // ---------------------------------------------------------------- shared assertions

    private fun observe(token: String, evidence: Boolean, detail: String = "") {
        assertTrue(evidence, "${variant.variantId}: $token evidence missing" +
            (if (detail.isNotEmpty()) " ($detail)" else ""))
        observed += token
    }

    private fun assertCommon() {
        assertTrue(harness.forbiddenCalls.isEmpty(),
            "${variant.variantId}: forbidden effects ${harness.forbiddenCalls}")
        assertTrue(harness.retirementRequests == 0,
            "${variant.variantId}: retirement was requested")
        assertFalse(harness.view.phase == LocalSetupPhase.CLOSING ||
            harness.view.phase == LocalSetupPhase.CLOSED,
            "${variant.variantId}: branch was retired/closed")
        assertNull(harness.view.companyReview, "${variant.variantId}: private company review exposed")
        assertNull(harness.view.company, "${variant.variantId}: private company exposed")
        assertNull(harness.view.branchReview, "${variant.variantId}: private branch review exposed")
        assertNull(harness.view.definitionReview, "${variant.variantId}: private definition exposed")
        assertNull(harness.view.firstAccountHeadLogin, "${variant.variantId}: private head login exposed")
        assertNull(harness.view.native, "${variant.variantId}: private native projection exposed")
        assertNull(harness.view.receipt, "${variant.variantId}: private receipt projection exposed")
        assertNull(harness.view.employeeEnrollment, "${variant.variantId}: employee enrollment exposed")
        assertNull(harness.view.employeeEnrollments, "${variant.variantId}: employee enrollments exposed")
        assertNull(harness.view.employeePolicy, "${variant.variantId}: employee policy exposed")
        assertNull(harness.view.employeePolicies, "${variant.variantId}: employee policies exposed")
        assertNull(harness.view.deviceAdmission, "${variant.variantId}: device admission exposed")
        assertNull(harness.view.deviceAdmissions, "${variant.variantId}: device admissions exposed")
        assertNull(harness.view.freshDevicePairing, "${variant.variantId}: fresh pairing exposed")
        observe("FINAL_PRIVACY_RECORDED", true)
        notes += "FINAL_PRIVACY_RECORDED locked=${harness.view.locked}; no private content/session/" +
            "unlock/secret or employee authority"
        observe("ACTUAL_REFRESH_MODE_RECORDED", true)
        notes += "ACTUAL_REFRESH_MODE_RECORDED=REUSE_CURRENT_PUBLICATION"
        if (actualClass == "NOT_OBSERVED") {
            throw AssertionError("${variant.variantId}: no actual outcome class was observed")
        }
    }

    private fun assertSelectedOutcome() {
        val selected = selectedOutcomeJson() ?: return
        val required = selected.stringList("required_observations")
        val missing = required.filterNot { it in observed }
        assertTrue(missing.isEmpty(),
            "${variant.variantId}: missing required observations $missing; observed=$observed")
        val forbidden = selected.stringList("forbidden_observations")
        val present = forbidden.filter { it in observed }
        assertTrue(present.isEmpty(),
            "${variant.variantId}: forbidden observations present $present")
        val mustNotClaim = selected["must_not_claim"]?.let { element ->
            (element as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                (it as? kotlinx.serialization.json.JsonPrimitive)?.content
            } ?: emptyList()
        } ?: emptyList()
        val claims = mustNotClaim.filter { it in observed }
        assertTrue(claims.isEmpty(),
            "${variant.variantId}: must_not_claim observations present $claims")
        val expected = selected.optionalString("expected_class")
        if (expected != null) {
            assertEquals(expected, actualClass,
                "${variant.variantId}: selected outcome class differs")
        }
        notes += "selected=$selectedOutcome required=${required.size} " +
            "forbidden=${forbidden.size} mustNotClaim=${mustNotClaim.size}; all asserted"
    }

    private fun selectedOutcomeJson(): JsonObject? = when (selectedOutcome) {
        "PRIMARY" -> variant.raw
        "ALTERNATE" -> variant.alternateOutcomes.singleOrNull {
            it.requiredString("condition") == "LATE_SETUP_BOUNDARY_NOT_ENTERED"
        } ?: error("${variant.variantId}: alternate outcome missing")
        "BRANCH_SETUP_READ_ENTERED" -> variant.branchOutcomes.singleOrNull {
            it.requiredString("branch_id") == "SETUP_READ_ENTERED"
        } ?: error("${variant.variantId}: entered branch outcome missing")
        "BRANCH_SETUP_READ_NOT_ENTERED" -> variant.branchOutcomes.singleOrNull {
            it.requiredString("branch_id") == "SETUP_READ_NOT_ENTERED"
        } ?: error("${variant.variantId}: non-entry branch outcome missing")
        "DIAGNOSTIC" -> null
        else -> null
    }

    private fun positiveState(): Boolean = harness.view.localClientConnected &&
        harness.view.phase == LocalSetupPhase.INSTALLATION_RETAINED && harness.view.problem == null

    private fun coordinatesEqual(
        a: com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1,
        b: com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1,
    ): Boolean = a.allocationId == b.allocationId && a.scope == b.scope &&
        a.installationId == b.installationId && a.assignmentId == b.assignmentId &&
        a.deviceId == b.deviceId && a.storageInstanceId == b.storageInstanceId &&
        a.storageKeyId == b.storageKeyId && a.pepperKeyId == b.pepperKeyId &&
        a.targetSigningKeyId == b.targetSigningKeyId && a.firstActorId == b.firstActorId

    private suspend fun teardown() {
        val core = harness.jobs().map { "${it.alias()}:${harness.jobState(it)}" }
        harness.beginTeardown()
        var rounds = 0
        while (rounds < 32) {
            harness.releaseAllGates()
            harness.runUntilIdle()
            rounds++
            if (harness.unreleasedGates().isEmpty()) break
        }
        harness.runUntilIdle()
        val before = harness.jobs().map { "${it.alias()}:${harness.jobState(it)}" }
        val active = harness.jobs().filter { it.isActive }
        active.forEach { it.cancel() }
        harness.runUntilIdle()
        active.forEach { job -> runCatching { job.join() } }
        harness.runUntilIdle()
        val remaining = harness.jobs().filter { !it.isCompleted }
        teardownState = "core=${core.joinToString(",")} before=${before.joinToString(",")} " +
            "remaining=${remaining.joinToString(",") { it.alias() }.ifEmpty { "none" }}"
        if (remaining.isNotEmpty()) {
            throw AssertionError(
                "${variant.variantId}: teardown left unjoined workers " +
                    remaining.joinToString(",") { it.alias() },
            )
        }
    }

    private companion object {
        const val GATE = "G-LATE-SETUP"
    }
}

/** A controlled foreign Client owned only by the test; no production coordinates getter. */
private class ControlledLateForeignClient : com.ukkera.pos.app.gateway.setup.LocalSetupClient {
    val constructionIdentity: String = "FOREIGN-CLIENT-FIXTURE"
    override fun isCurrent(): Boolean = true
    override fun denyNewWork() = Unit
    override suspend fun close() = Unit
    override suspend fun prepareEmployeeEnrollment(
        requestId: com.ukkera.pos.types.DomainId,
        definition: com.ukkera.pos.security.LocalAccountProvisioning,
    ): com.ukkera.pos.security.LocalCompanyAuthorityResult<com.ukkera.pos.security.LocalEmployeeEnrollmentAction> =
        forbidden()
    override suspend fun approveEmployeeEnrollment(
        action: com.ukkera.pos.security.LocalEmployeeEnrollmentAction,
        headLogin: String,
        headSecret: CharArray,
    ): com.ukkera.pos.security.LocalCompanyAuthorityResult<com.ukkera.pos.app.gateway.setup.LocalSetupEmployeeConnection> =
        forbidden()
    override suspend fun recoverEmployeeEnrollmentApproval(
        originalActionId: com.ukkera.pos.types.DomainId,
    ): com.ukkera.pos.security.LocalCompanyAuthorityResult<com.ukkera.pos.app.gateway.setup.LocalSetupEmployeeConnection> =
        forbidden()
    override suspend fun recoverEmployeeEnrollmentConnection(
        originalActionId: com.ukkera.pos.types.DomainId,
    ): com.ukkera.pos.security.LocalCompanyAuthorityResult<com.ukkera.pos.app.gateway.setup.LocalSetupEmployeeConnection> =
        forbidden()
    override suspend fun reviewEmployeeEnrollment(
        originalActionId: com.ukkera.pos.types.DomainId,
    ): com.ukkera.pos.security.LocalCompanyAuthorityResult<com.ukkera.pos.security.LocalEmployeeEnrollmentAction> =
        forbidden()
    override suspend fun retainedEmployeeEnrollments():
        com.ukkera.pos.security.LocalCompanyAuthorityResult<List<com.ukkera.pos.security.LocalEmployeeEnrollmentReference>> =
        forbidden()
    override suspend fun enrollApprovedEmployee(
        originalRequestId: com.ukkera.pos.types.DomainId,
        password: CharArray,
    ): com.ukkera.pos.app.LocalEnrollmentResult {
        password.fill('\u0000')
        return forbidden()
    }
    override suspend fun lookupEmployeeEnrollment(
        originalRequestId: com.ukkera.pos.types.DomainId,
    ): com.ukkera.pos.app.LocalEnrollmentResult = forbidden()
    override suspend fun acquireEmployeeIntentJournal(
        originalRequestId: com.ukkera.pos.types.DomainId,
        action: com.ukkera.pos.security.NativeEmployeeClientJournalAction,
    ): Boolean = forbidden()

    private fun <T> forbidden(): T = throw AssertionError(
        "foreign fixture must not be used for employee/authority operations",
    )
}
