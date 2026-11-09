package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.app.setup.LocalSetupProblem
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal data class SupplementalOutcome(
    val variantId: String,
    val status: VariantStatus,
    val actualClass: String,
    val selectedOutcome: String,
    val notes: List<String>,
    val conditional: List<String>,
    val ledger: String,
    val teardown: String,
)

@OptIn(ExperimentalCoroutinesApi::class)
internal class SupplementalV8Driver(
    private val test: TestScope,
    private val variant: SupplementalVariant,
) {
    private val harness = SupplementalV8Harness(test, variant)
    private val script = harness.script
    private val notes = mutableListOf<String>()
    private val conditional = mutableListOf<String>()
    private var teardownState = "not-run"
    private var actualClass: String = "NOT_OBSERVED"
    private var selectedOutcome: String = "NONE"
    private var refreshIssuedReads: Int = 0
    private var refreshIssuedSetupReads: Int = 0
    private var refreshIssuedTrueCurrentness: Int = 0
    private var refreshJob: Job? = null
    private var refreshRefusedWithoutJob: Boolean = false

    suspend fun execute(): SupplementalOutcome {
        var failure: Throwable? = null
        try {
            when (script.family) {
                "R" -> runRefreshVariant()
                "B" -> runThrownOpenVariant()
                "P" -> runProvisionerVariant()
                else -> error("${variant.variantId}: unknown family ${script.family}")
            }
            assertCommon()
        } catch (error: Throwable) {
            failure = error
        }
        if (failure != null) notes += "FAILURE: ${failure.message}"
        harness.coreSnapshot()
        val coreStatus = if (failure == null) VariantStatus.PASS else VariantStatus.FAIL
        val coreNotes = notes.toList()
        val coreConditional = conditional.toList()
        val coreLedger = harness.ledger.summary()
        var teardownFailure: Throwable? = null
        try {
            teardown()
        } catch (error: Throwable) {
            teardownFailure = error
        }
        if (teardownFailure != null) notes += "TEARDOWN-FAILURE: ${teardownFailure.message}"
        return SupplementalOutcome(
            variantId = variant.variantId,
            status = if (teardownFailure != null) VariantStatus.FAIL else coreStatus,
            actualClass = actualClass,
            selectedOutcome = selectedOutcome,
            notes = coreNotes + notes.drop(coreNotes.size),
            conditional = coreConditional,
            ledger = coreLedger,
            teardown = teardownState,
        )
    }

    // ---------------------------------------------------------------- refresh family

    private suspend fun runRefreshVariant() {
        startPublishedReconnect()
        assertAutomaticPresentationLocked()
        if (variant.caseId == "E3RA-R5" && variant.variantId.startsWith("E3RA-R5.V02")) {
            runRefreshDuringRetirement()
            return
        }
        if (variant.caseId == "E3RA-R5" && variant.variantId.startsWith("E3RA-R5.V03")) {
            runRefreshAfterClosed()
            return
        }
        resumePresentation("explicit-refresh-admission")
        harness.usePhase(SupplementalV8Harness.Phase.REFRESH)
        if (script.currentness != "true") harness.setCurrentness(script.currentness)
        if (script.refreshRecordThrow) harness.armReadThrow()
        refreshIssuedReads = harness.launchStorage.reads
        refreshIssuedSetupReads = harness.setupReads.size
        refreshIssuedTrueCurrentness = harness.currentnessObservationCount("true")
        val beforeRefreshJobs = harness.jobs().toSet()
        val created = harness.invokeRefresh()
        assertEquals(1, created.size,
            "${variant.variantId}: admitted refresh must dispatch exactly one finite job")
        refreshJob = created.single()
        if (variant.gate("G-REFRESH-BINDING") != null) {
            harness.runUntilIdle()
            val gate = assertNotNull(script.bindingGate)
            assertTrue(harness.gateWasEntered(gate), "${variant.variantId}: $gate was not entered")
            performRefreshGateEvents(gate)
            harness.releaseGate(gate)
            harness.runUntilIdle()
        }
        harness.runUntilIdle()
        refreshJob!!.join()
        harness.runUntilIdle()
        assertTrue(refreshJob!!.isCompleted, "${variant.variantId}: refresh job did not settle")
        notes += "REFRESH_SETTLEMENT_OBSERVED job=${refreshJob!!.alias()} state=${harness.jobState(refreshJob!!)}"
        harness.captureRetirementJobs()
        when {
            variant.variantId.startsWith("E4") -> error("unreachable")
            variant.caseId == "E3RA-R5" && variant.variantId.startsWith("E3RA-R5.V01") -> {
                assertSecondRefreshRefused(beforeRefreshJobs)
                assertRefreshPositive()
            }
            variant.caseId == "E3RA-R4" && variant.variantId.startsWith("E3RA-R4.V04") -> {
                val deferred = assertNotNull(harness.retirementDeferred,
                    "${variant.variantId}: retirement was not requested")
                assertClosedRetirement(deferred)
                assertPostRetirementRefusal()
                actualClass = "RETIRED_CLOSED"
                selectedOutcome = "PRIMARY"
                assertClosedEvidence(requirePublicationWithdrawal = true)
            }
            else -> assertRefreshExpectedClass()
        }
        notes += "preRefreshReads=$refreshIssuedReads afterAction; total=${harness.launchStorage.reads}"
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
        notes += "initial reconnect published owner=${owner.alias()} client=${client.alias()} live=1"
    }

    private fun assertAutomaticPresentationLocked() {
        assertTrue(harness.view.locked,
            "${variant.variantId}: automatic reconnect must leave presentation private/locked before explicit resume")
    }

    private fun resumePresentation(reason: String) {
        harness.coordinator.resumePresentation()
        harness.runUntilIdle()
        notes += "explicit resumePresentation ($reason) locked=${harness.view.locked}"
        assertFalse(harness.view.locked, "${variant.variantId}: explicit presentation resume did not unlock")
    }

    private fun performRefreshGateEvents(gate: String) {
        when {
            variant.variantId.startsWith("E3RA-R4.V01") -> {
                val bytes = ExistingOnlyStartupFixtures.record("REC-STOP").bytes
                harness.mutateHint(bytes, "EV-SET-HINT-STOP")
                notes += "TEST mutation EV-SET-HINT-STOP while $gate held"
            }
            variant.variantId.startsWith("E3RA-R4.V02") -> {
                val bytes = ExistingOnlyStartupFixtures.record("REC-RUN-FOREIGN-01").bytes
                harness.mutateHint(bytes, "EV-SET-HINT-FOREIGN-RUN")
                notes += "TEST mutation EV-SET-HINT-FOREIGN-RUN while $gate held"
            }
            variant.variantId.startsWith("E3RA-R4.V04") -> {
                assertFalse(harness.retirementDeferred != null && harness.retirementDeferred!!.isCompleted,
                    "${variant.variantId}: retirement completed while binding held")
                harness.requestRetirement()
                harness.runUntilIdle()
                harness.captureRetirementJobs()
                notes += "retirement requested while $gate held; deferred pending=" +
                    (harness.retirementDeferred?.isCompleted == false)
            }
            variant.variantId.startsWith("E3RA-R5.V01") -> {
                // The second refresh is issued only at the real in-flight checkpoint.
                val before = harness.jobs().toSet()
                val second = harness.invokeRefresh()
                harness.runUntilIdle()
                assertEquals(emptyList(), second, "${variant.variantId}: second refresh dispatched a job")
                assertEquals(1, harness.targetOpens.size, "${variant.variantId}: second refresh acquired a target")
                assertEquals(1, harness.masterOpens.size, "${variant.variantId}: second refresh opened a Master")
                assertEquals(1, harness.publishes.size, "${variant.variantId}: second refresh published")
                assertTrue(harness.jobs().toSet() == before, "${variant.variantId}: second refresh created a worker")
                notes += "second refresh invoked at $gate; refused/coalesced with zero acquisition/publication"
            }
            else -> Unit
        }
    }

    private fun assertSecondRefreshRefused(before: Set<Job>) {
        assertEquals(1, harness.targetOpens.size, "${variant.variantId}: repeat refresh target count")
        assertEquals(1, harness.masterOpens.size, "${variant.variantId}: repeat refresh Master count")
        assertEquals(1, harness.publishes.size, "${variant.variantId}: repeat refresh publication count")
        assertEquals(1, harness.livePublications, "${variant.variantId}: repeat refresh live publication count")
        assertTrue(harness.jobs().toSet().containsAll(before),
            "${variant.variantId}: admitted workers disappeared")
        notes += "SECOND_REFRESH_ACTION_OBSERVED; SECOND_REFRESH_REFUSED_OR_COALESCED; " +
            "NO_SECOND_ACQUISITION; NO_SECOND_PUBLICATION"
    }

    private fun assertRefreshPositive() {
        assertEquals(1, harness.targetOpens.size, "${variant.variantId}: mode A must not acquire a target")
        assertEquals(1, harness.masterOpens.size, "${variant.variantId}: mode A must not open a Master")
        assertEquals(1, harness.clientConnects.size, "${variant.variantId}: mode A must not connect a Client")
        assertEquals(1, harness.publishes.size, "${variant.variantId}: mode A must not republish")
        assertEquals(1, harness.livePublications, "${variant.variantId}: exactly one live publication")
        assertEquals(0, harness.withdraws.size, "${variant.variantId}: mode A must not withdraw")
        assertEquals(0, harness.targetCloseEntries.size + harness.masterCloseEntries.size +
            harness.clientCloseEntries.size, "${variant.variantId}: mode A must not close an owner")
        assertEquals(harness.publishedOwner, harness.publishes.single().owner,
            "${variant.variantId}: exact publication token changed")
        assertEquals(harness.publishedClient, harness.publishes.single().client,
            "${variant.variantId}: exact published Client changed")
        assertTrue(harness.bindingReads.size >= 2, "${variant.variantId}: fresh binding read missing")
        assertTrue(harness.launchStorage.reads > refreshIssuedReads,
            "${variant.variantId}: no fresh launch-record read after refresh")
        assertTrue(harness.currentnessObservationCount("true") > refreshIssuedTrueCurrentness,
            "${variant.variantId}: no fresh true currentness observation attributable to the refresh")
        assertTrue(harness.view.localClientConnected, "${variant.variantId}: positive refresh not connected")
        assertEquals(LocalSetupPhase.INSTALLATION_RETAINED, harness.view.phase,
            "${variant.variantId}: positive refresh final phase")
        actualClass = "REFRESH_SAME_ORIGINAL"
        selectedOutcome = "PRIMARY_OR_ALTERNATE"
        assertPrivateRefreshProjection()
        notes += "ACTUAL_REFRESH_MODE_RECORDED=REUSE_CURRENT_PUBLICATION; " +
            "RECORD_READ; BINDING_READ; FRESH-CURRENTNESS-TRUE>preaction; PUBLICATION_SINGLE_LIVE; " +
            "OLD_OWNER_LEDGER_RECORDED=PRESERVED_EXACT_OWNER/PRESERVED_EXACT_PUBLICATION; " +
            "REFRESH_SETTLEMENT_OBSERVED job=${refreshJob?.alias() ?: "none"} " +
            "state=${refreshJob?.let { harness.jobState(it) } ?: "missing"}"
    }

    private fun assertRefreshExpectedClass() {
        val primary = variant.expectedClass
        val refused = !refreshSucceeded()
        when {
            variant.caseId == "E3RA-R1" -> {
                assertEquals("REFRESH_SAME_ORIGINAL", primary, "${variant.variantId}: R1 class changed")
                assertTrue(refused.not(), "${variant.variantId}: positive refresh refused")
                assertRefreshPositive()
                assertR1SetupCoverage()
            }
            variant.caseId == "E3RA-R2" -> {
                assertTrue(refused, "${variant.variantId}: binding refusal was not observed")
                assertRefreshRefusedPreservingOwner()
                actualClass = "REFRESH_REFUSED_ACCOUNTED"
                selectedOutcome = "PRIMARY"
                assertBindingRefusalEvidence()
            }
            variant.caseId == "E3RA-R4" -> {
                assertTrue(refused, "${variant.variantId}: record/hold refusal was not observed")
                actualClass = "REFRESH_REFUSED_ACCOUNTED"
                selectedOutcome = "PRIMARY"
                assertRefreshRefusedPreservingOwner()
                if (variant.variantId.startsWith("E3RA-R4.V03")) {
                    assertTrue(harness.log.all().any {
                        it.actor == "PRODUCTION" && it.method == "launchStorage.read" && it.phase == "throw"
                    }, "${variant.variantId}: record-read throw was not entered")
                    notes += "RECORD_READ; throw recorded; no retry; no publication"
                } else {
                    assertTrue(harness.bindingReads.last().outcome == "retained",
                        "${variant.variantId}: binding read not entered before refusal")
                }
            }
            variant.caseId == "E3RA-R3" -> {
                if (refused) {
                    actualClass = "REFRESH_REFUSED_ACCOUNTED"
                    selectedOutcome = "PRIMARY"
                    assertRefreshRefusedPreservingOwner()
                    if (variant.variantId.startsWith("E3RA-R3.V09")) {
                        assertTrue(harness.setupReads.lastOrNull()?.throwRead == true,
                            "${variant.variantId}: setup throw was not entered")
                        notes += "setup-throw primary refusal observed; no fallback/publication"
                    } else if (variant.variantId.startsWith("E3RA-R3.V06")) {
                        assertTrue(harness.currentnessObservationCount("false") > 0,
                            "${variant.variantId}: false currentness not observed")
                        assertEquals(1, harness.publishes.size, "${variant.variantId}: currentness refusal published")
                        assertFalse(harness.view.localClientConnected,
                            "${variant.variantId}: false currentness retained a positive connected claim")
                        assertFalse(harness.view.localClientConnectionAvailable,
                            "${variant.variantId}: false currentness retained connection availability")
                        notes += "CLIENT-CURRENTNESS-FALSE-OBSERVED; NO-POSITIVE-CONNECTED-CLAIM; " +
                            "NO-NEW-PUBLICATION; CLIENT-ACCOUNTED"
                    } else if (variant.variantId.startsWith("E3RA-R3.V10")) {
                        assertTrue(harness.log.all().any {
                            it.method == "client.isCurrent" && it.detail == "throw"
                        }, "${variant.variantId}: currentness throw not observed")
                        assertTrue(harness.clientCustodyUnknown.not(),
                            "${variant.variantId}: a returned currentness-unknown Client was misclassified as unreturned")
                        assertEquals(1, harness.publishes.size, "${variant.variantId}: currentness throw published")
                        assertFalse(harness.view.localClientConnected,
                            "${variant.variantId}: thrown currentness retained a positive connected claim")
                        assertFalse(harness.view.localClientConnectionAvailable,
                            "${variant.variantId}: thrown currentness retained connection availability")
                        notes += "CLIENT-CURRENTNESS-UNKNOWN; NO-TRUE-FROM-THROW; NO-POSITIVE-CONNECTED-CLAIM; " +
                            "CLIENT-HANDLE-ACCOUNTED; NO-NEW-PUBLICATION"
                    }
                } else {
                    assertRefreshPositive()
                    val boundary = variant.boundaryUnderTest
                    if (boundary != null && boundary != "SETUP_READ") {
                        conditionallyNotEntered(boundary)
                        actualClass = "REFRESH_SAME_ORIGINAL"
                        selectedOutcome = "ALTERNATE"
                        conditional += "BOUNDARY-NOT-EXERCISED"
                        assertTrue(variant.alternateRequiredObservations.isNotEmpty(),
                            "${variant.variantId}: no frozen alternate evidence")
                        assertTrue("BOUNDARY_NOT_ENTERED_OBSERVED" in variant.alternateRequiredObservations,
                            "${variant.variantId}: alternate non-entry evidence missing")
                    } else if (boundary == "SETUP_READ") {
                        val enteredFresh = harness.setupReads.size > refreshIssuedSetupReads
                        if (enteredFresh) {
                            throw AssertionError("${variant.variantId}: setup alternate selected after the " +
                                "optional read was actually entered; a non-throw entry is a script/result mismatch")
                        }
                        actualClass = "REFRESH_SAME_ORIGINAL"
                        selectedOutcome = "ALTERNATE"
                        conditional += "SETUP-READ-NOT-EXERCISED"
                        assertTrue("SETUP_BOUNDARY_NOT_ENTERED_OBSERVED" in variant.alternateRequiredObservations,
                            "${variant.variantId}: setup alternate non-entry evidence missing")
                        notes += "SETUP_BOUNDARY_NOT_ENTERED_OBSERVED; fresh setup reads before=" +
                            "$refreshIssuedSetupReads after=${harness.setupReads.size}; " +
                            "alternate selected without a fresh read and without a positive setup claim"
                    }
                }
            }
            else -> error("${variant.variantId}: unhandled refresh case ${variant.caseId}")
        }
        assertExpectedOrAlternate()
    }

    private fun conditionallyNotEntered(boundary: String) {
        when (boundary) {
            "TARGET_OPEN" -> assertEquals(1, harness.targetOpens.size,
                "${variant.variantId}: named target boundary was entered")
            "MASTER_OPEN" -> assertEquals(1, harness.masterOpens.size,
                "${variant.variantId}: named Master boundary was entered")
            "CLIENT_CONNECT" -> assertEquals(1, harness.clientConnects.size,
                "${variant.variantId}: named Client boundary was entered")
            else -> error("${variant.variantId}: unknown boundary $boundary")
        }
        notes += "BOUNDARY_NOT_ENTERED_OBSERVED=$boundary; coverage not claimed"
    }

    private fun assertR1SetupCoverage() {
        val freshReads = harness.setupReads.size - refreshIssuedSetupReads
        assertTrue(freshReads in 0..1,
            "${variant.variantId}: more than one fresh setup read attributable to the refresh")
        if (freshReads == 0) {
            conditional += "FRESH-NULLABLE-READ-NOT-EXERCISED"
            notes += "FRESH-NULLABLE-READ-NOT-EXERCISED; startup setup read is not fresh evidence; " +
                (if (variant.variantId.startsWith("E3RA-R1.V05"))
                    "transition not claimed" else "positive core stands")
            conditional += "LOCK-NOT-EXERCISED"
            return
        }
        val setup = harness.setupReads.last()
        assertTrue(setup.throwRead.not(), "${variant.variantId}: positive setup read threw")
        assertEquals(script.refreshSetup.definition, setup.definition == "present",
            "${variant.variantId}: fresh setup definition combination differs")
        assertEquals(script.refreshSetup.receipt, setup.receipt == "present",
            "${variant.variantId}: fresh setup receipt combination differs")
        if (variant.variantId.startsWith("E3RA-R1.V05")) {
            assertEquals("null", setup.definition, "${variant.variantId}: transition definition not null")
            assertEquals("null", setup.receipt, "${variant.variantId}: transition receipt not null")
            notes += "FRESH-SETUP-READ-OBSERVED=both-null; no cached nullable gate"
        } else {
            notes += "FRESH-SETUP-READ-OBSERVED=${setup.definition}/${setup.receipt}"
        }
        conditional += "LOCK-NOT-EXERCISED"
        notes += "LOCK-NOT-EXERCISED; refresh did not enter a presentation lock"
    }

    private fun refreshSucceeded(): Boolean =
        harness.view.phase == LocalSetupPhase.INSTALLATION_RETAINED &&
            harness.view.localClientConnected &&
            harness.livePublications == 1 &&
            harness.publishes.size == 1 &&
            harness.withdraws.isEmpty()

    private fun assertRefreshRefusedPreservingOwner() {
        assertEquals(1, harness.targetOpens.size, "${variant.variantId}: refusal acquired a second target")
        assertEquals(1, harness.masterOpens.size, "${variant.variantId}: refusal opened a second Master")
        assertEquals(1, harness.clientConnects.size, "${variant.variantId}: refusal connected a second Client")
        assertEquals("opened", harness.targetOpens.single().outcome,
            "${variant.variantId}: original target was not opened")
        assertEquals("opened", harness.masterOpens.single().outcome,
            "${variant.variantId}: original Master was not opened")
        assertEquals("returned", harness.clientConnects.single().outcome,
            "${variant.variantId}: original Client was not returned")
        assertNotNull(harness.targetContext, "${variant.variantId}: original target context disappeared")
        val master = assertNotNull(harness.masterObject, "${variant.variantId}: original Master disappeared")
        val client = assertNotNull(harness.clientObject, "${variant.variantId}: original Client disappeared")
        assertTrue(harness.targetTransferred, "${variant.variantId}: original target transfer disappeared")
        assertNotNull(harness.positiveTransfer(), "${variant.variantId}: exact transfer observation missing")
        assertEquals(1, harness.publishes.size, "${variant.variantId}: refusal created a publication")
        assertEquals(0, harness.withdraws.size, "${variant.variantId}: refusal withdrew the live publication")
        assertEquals(0, harness.closeCount(), "${variant.variantId}: refusal closed an owner")
        assertEquals(1, harness.livePublications, "${variant.variantId}: refusal lost the live publication")
        assertEquals(harness.publishedOwner, harness.publishes.single().owner,
            "${variant.variantId}: refusal changed the publication token")
        assertEquals(harness.publishedClient, harness.publishes.single().client,
            "${variant.variantId}: refusal changed the published Client")
        assertEquals(client, harness.publishedClient, "${variant.variantId}: exact Client identity changed")
        assertEquals(ReferenceDisposition.TRANSFERRED,
            harness.ledger.entry(harness.targetAlias())?.disposition,
            "${variant.variantId}: target disposition is not TRANSFERRED")
        assertEquals(ReferenceDisposition.NATIVE_OWNER,
            harness.ledger.entry(harness.masterAlias())?.disposition,
            "${variant.variantId}: Master disposition is not NATIVE_OWNER")
        assertEquals(ReferenceDisposition.CLIENT_RESOURCE,
            harness.ledger.entry(harness.clientAlias())?.disposition,
            "${variant.variantId}: Client disposition is not CLIENT_RESOURCE")
        assertFalse(harness.view.localClientConnected,
            "${variant.variantId}: refusal retained a positive connected claim")
        assertFalse(harness.view.localClientConnectionAvailable,
            "${variant.variantId}: refusal retained connection availability")
        assertNotNull(harness.view.problem, "${variant.variantId}: refusal left no observable problem")
        notes += "OLD_OWNER_LEDGER_RECORDED=target:TRANSFERRED exact; master:" +
            "${harness.masterAlias()} exact; client:${harness.clientAlias()} exact; " +
            "publication:PRESERVED_EXACT_PUBLICATION; NO_POSITIVE_CONNECTED_CLAIM"
    }

    private fun assertBindingRefusalEvidence() {
        assertTrue(harness.bindingReads.size >= 2, "${variant.variantId}: refresh binding read missing")
        when (script.refreshBinding.kind) {
            "changed" -> {
                assertEquals(LocalSetupProblem.ORIGINAL_ACTION_REQUIRED, harness.view.problem,
                    "${variant.variantId}: changed-binding refusal did not preserve the observed mismatch outcome")
                notes += "BINDING_REFUSAL_REASON_RECORDED=actual-mismatch-refusal" +
                    " problem=${harness.view.problem} nativeProblem=${harness.view.nativeProblem}; " +
                    "binding read entered; scripted index=${script.refreshBinding.index + 1} is input only"
            }
            "unresolved" -> {
                val expected = when (script.refreshBinding.reason) {
                    SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED -> LocalSetupProblem.AUTHENTICATION_REFUSED
                    else -> LocalSetupProblem.NATIVE_UNAVAILABLE
                }
                assertEquals(expected, harness.view.problem,
                    "${variant.variantId}: actual mapped binding-refusal reason was not preserved")
                notes += "BINDING_REFUSAL_REASON_RECORDED=actual-mapped problem=${harness.view.problem} " +
                    "nativeProblem=${harness.view.nativeProblem}; opaque/no guessed taxonomy"
            }
            "throw" -> {
                assertTrue(harness.bindingReads.last().outcome == "throw",
                    "${variant.variantId}: binding throw not recorded")
                assertEquals(LocalSetupProblem.STATE_UNCERTAIN, harness.view.problem,
                    "${variant.variantId}: thrown binding read did not resolve to an observed refusal state")
                notes += "BINDING_READ_THROW_RECORDED; actual problem=${harness.view.problem}; no retry"
            }
            else -> error("${variant.variantId}: binding refusal expected")
        }
        assertEquals(1, harness.targetOpens.size, "${variant.variantId}: binding refusal acquired a target")
        assertEquals(1, harness.masterOpens.size, "${variant.variantId}: binding refusal opened a Master")
        assertEquals(1, harness.clientConnects.size, "${variant.variantId}: binding refusal connected a Client")
    }

    private suspend fun runRefreshDuringRetirement() {
        harness.usePhase(SupplementalV8Harness.Phase.REFRESH)
        val deferred = harness.requestRetirement()
        harness.runUntilIdle()
        harness.captureRetirementJobs()
        val gate = assertNotNull(script.closeGate, "${variant.variantId}: G-CLOSE-OWNER not selected")
        assertTrue(harness.gateWasEntered(gate), "${variant.variantId}: $gate was not entered")
        assertFalse(deferred.isCompleted, "${variant.variantId}: retirement completed before the held close")
        notes += "ROMAN_G-CLOSE-OWNER entered; retirement Deferred pending"
        // The genuine retiring refusal: presentation resume, existing-company inspection and the
        // refresh action must all be refused while the current owner close is held.
        assertRetiringRefusals()
        harness.releaseGate(gate)
        harness.runUntilIdle()
        val cleanup = harness.retirementJobs.lastOrNull()
        cleanup?.join()
        harness.runUntilIdle()
        assertClosedRetirement(deferred)
        actualClass = "RETIRED_CLOSED"
        selectedOutcome = "PRIMARY"
        assertClosedEvidence(requirePublicationWithdrawal = true)
    }

    private fun assertRetiringRefusals() {
        val phaseBefore = harness.view.phase
        assertTrue(harness.view.locked,
            "${variant.variantId}: retiring presentation was not locked before resume")
        assertTrue(phaseBefore == LocalSetupPhase.CLOSING || phaseBefore == LocalSetupPhase.CLOSED,
            "${variant.variantId}: resume refusal was not observed during retirement/closed")
        val beforeResume = harness.jobs().toSet()
        harness.coordinator.resumePresentation()
        harness.runUntilIdle()
        assertTrue(harness.view.locked,
            "${variant.variantId}: retiring presentation resume unlocked the view")
        assertEquals(phaseBefore, harness.view.phase,
            "${variant.variantId}: retiring presentation resume changed the phase")
        assertEquals(emptyList(), harness.newJobsSince(beforeResume),
            "${variant.variantId}: retiring presentation resume dispatched work")
        val beforeInspect = harness.jobs().toSet()
        harness.coordinator.inspectOriginalCompany()
        harness.runUntilIdle()
        assertEquals(emptyList(), harness.newJobsSince(beforeInspect),
            "${variant.variantId}: retiring inspectOriginalCompany dispatched work")
        // Retirement may already have withdrawn its own publication or entered the held close.
        // The refresh itself must add no acquisition/publication/withdrawal/close.
        val withdrawsBefore = harness.withdraws.size
        val closesBefore = harness.closeCount()
        val targetsBefore = harness.targetOpens.size
        val mastersBefore = harness.masterOpens.size
        val connectsBefore = harness.clientConnects.size
        val publishesBefore = harness.publishes.size
        val beforeRefresh = harness.jobs().toSet()
        val created = harness.invokeRefresh()
        harness.runUntilIdle()
        assertEquals(emptyList(), created, "${variant.variantId}: retiring refresh dispatched work")
        assertEquals(emptyList(), harness.newJobsSince(beforeRefresh),
            "${variant.variantId}: retiring refresh created a worker")
        assertEquals(targetsBefore, harness.targetOpens.size,
            "${variant.variantId}: retiring refresh acquired a target")
        assertEquals(mastersBefore, harness.masterOpens.size,
            "${variant.variantId}: retiring refresh opened a Master")
        assertEquals(connectsBefore, harness.clientConnects.size,
            "${variant.variantId}: retiring refresh connected a Client")
        assertEquals(publishesBefore, harness.publishes.size,
            "${variant.variantId}: retiring refresh published")
        assertEquals(withdrawsBefore, harness.withdraws.size,
            "${variant.variantId}: retiring refresh withdrew a publication")
        assertEquals(closesBefore, harness.closeCount(),
            "${variant.variantId}: retiring refresh closed an owner")
        notes += "RESUME_REFUSED_OR_LOCKED; INSPECT_ORIGINAL_COMPANY_REFUSED; " +
            "REFRESH_ACTION_OBSERVED; REFRESH_REFUSED_OBSERVED; NO_REFRESH_ACQUISITION; " +
            "NO_REFRESH_PUBLICATION; NO_REFRESH_CLOSE; retirement-attributable " +
            "withdrawals=${harness.withdraws.size} closes=${harness.closeCount()}"
        refreshRefusedWithoutJob = true
    }

    private suspend fun runRefreshAfterClosed() {
        harness.usePhase(SupplementalV8Harness.Phase.REFRESH)
        val deferred = harness.requestRetirement()
        harness.runUntilIdle()
        harness.captureRetirementJobs()
        harness.retirementJobs.lastOrNull()?.join()
        harness.runUntilIdle()
        assertClosedRetirement(deferred)
        assertEquals(LocalSetupPhase.CLOSED, harness.view.phase,
            "${variant.variantId}: retirement did not reach CLOSED")
        assertClosedEvidence(requirePublicationWithdrawal = true)
        val preTargetOpens = harness.targetOpens.size
        val preMasterOpens = harness.masterOpens.size
        val preClientConnects = harness.clientConnects.size
        val prePublishes = harness.publishes.size
        val preWithdraws = harness.withdraws.size
        val preCloses = harness.closeCount()
        val resumeLocked = harness.view.locked
        harness.coordinator.resumePresentation()
        harness.runUntilIdle()
        assertTrue(harness.view.locked == resumeLocked, "${variant.variantId}: closed resume changed the lock")
        val beforeInspect = harness.jobs().toSet()
        harness.coordinator.inspectOriginalCompany()
        harness.runUntilIdle()
        assertEquals(emptyList(), harness.newJobsSince(beforeInspect),
            "${variant.variantId}: closed inspectOriginalCompany dispatched work")
        val before = harness.jobs().toSet()
        val created = harness.invokeRefresh()
        harness.runUntilIdle()
        assertEquals(emptyList(), created, "${variant.variantId}: closed refresh dispatched work")
        assertEquals(emptyList(), harness.newJobsSince(before),
            "${variant.variantId}: closed refresh created a worker")
        assertEquals(preTargetOpens, harness.targetOpens.size,
            "${variant.variantId}: closed refresh acquired a target")
        assertEquals(preMasterOpens, harness.masterOpens.size,
            "${variant.variantId}: closed refresh opened a Master")
        assertEquals(preClientConnects, harness.clientConnects.size,
            "${variant.variantId}: closed refresh connected a Client")
        assertEquals(prePublishes, harness.publishes.size,
            "${variant.variantId}: closed refresh published")
        assertEquals(preWithdraws, harness.withdraws.size,
            "${variant.variantId}: closed refresh withdrew a publication")
        assertEquals(preCloses, harness.closeCount(),
            "${variant.variantId}: closed refresh closed an owner")
        assertEquals(LocalSetupPhase.CLOSED, harness.view.phase,
            "${variant.variantId}: closed lifecycle was resurrected")
        refreshRefusedWithoutJob = true
        actualClass = "REFRESH_REFUSED_RETIRED"
        selectedOutcome = "PRIMARY"
        notes += "REFRESH_ACTION_OBSERVED; REFRESH_REFUSED_OBSERVED; NO_REFRESH_ACQUISITION; " +
            "NO_REFRESH_PUBLICATION; NO_REFRESH_CLOSE; LIFECYCLE_STAYS_CLOSED"
    }

    private suspend fun assertClosedRetirement(deferred: Deferred<Unit>) {
        harness.runUntilIdle()
        assertTrue(deferred.isCompleted, "${variant.variantId}: retirement Deferred did not settle")
        val failure = runCatching { deferred.await() }.exceptionOrNull()
        assertNull(failure, "${variant.variantId}: CLOSED requires normal Deferred, actual=$failure")
        assertEquals(LocalSetupPhase.CLOSED, harness.view.phase, "${variant.variantId}: retirement not CLOSED")
        assertFalse(harness.view.localClientConnected, "${variant.variantId}: CLOSED remained connected")
        assertTrue(harness.closedDeferredNormal, "${variant.variantId}: normal Deferred not observed")
        notes += "RETIREMENT_DEFERRED_NORMAL; ALL_CURRENT_OWNER_CONFIRMATIONS"
    }

    private fun assertClosedEvidence(requirePublicationWithdrawal: Boolean) {
        assertEquals(1, harness.clientCloseEntries.size, "${variant.variantId}: Client owner close count")
        assertEquals(1, harness.masterCloseEntries.size, "${variant.variantId}: Master owner close count")
        assertEquals("unit", harness.clientCloseEntries.single().outcome,
            "${variant.variantId}: old Client close was not Unit")
        assertEquals("true", harness.masterCloseEntries.single().outcome,
            "${variant.variantId}: old Master close was not true")
        assertEquals(0, harness.livePublications, "${variant.variantId}: publication still live")
        val withdrawal = assertNotNull(harness.withdraws.singleOrNull { it.alias == "OWNER-PUBLISHED" },
            "${variant.variantId}: old publication withdrawal not entered")
        assertSame(harness.publishedOwner, withdrawal.owner,
            "${variant.variantId}: old publication withdrawal used the wrong owner")
        assertEquals(OwnerState.CLOSE_CONFIRMED, harness.ledger.entry(harness.masterAlias())?.state,
            "${variant.variantId}: old Master owner is not confirmed")
        assertEquals(OwnerState.CLOSE_CONFIRMED, harness.ledger.entry(harness.clientAlias())?.state,
            "${variant.variantId}: old Client owner is not confirmed")
        assertNotNull(harness.positiveTransfer(),
            "${variant.variantId}: prior startup transfer missing from cumulative accounting")
        notes += "TRANSFER_ACCOUNTING_RECORDED=ACTUAL_TRANSFER_ACCOUNTING_COMPLETE " +
            "(prior startup target->Master); PUBLICATION_WITHDRAWAL_CONFIRMED_OR_ABSENT"
        if (requirePublicationWithdrawal) {
            assertTrue(harness.withdraws.isNotEmpty(), "${variant.variantId}: no withdrawal entry")
        }
    }

    private fun assertPostRetirementRefusal() {
        val resumeLocked = harness.view.locked
        harness.coordinator.resumePresentation()
        harness.runUntilIdle()
        assertTrue(harness.view.locked == resumeLocked, "${variant.variantId}: post-retirement resume changed lock")
        val before = harness.jobs().toSet()
        harness.coordinator.inspectOriginalCompany()
        harness.runUntilIdle()
        assertEquals(emptyList(), harness.newJobsSince(before),
            "${variant.variantId}: post-retirement inspectOriginalCompany created work")
        notes += "RESUME_REFUSED_OR_LOCKED; INSPECT_ORIGINAL_COMPANY_REFUSED"
    }

    // ---------------------------------------------------------------- thrown-open family

    private suspend fun runThrownOpenVariant() {
        harness.usePhase(SupplementalV8Harness.Phase.STARTUP)
        val job = harness.captureStartup { harness.coordinator.reopenRetainedMaster() }
        val gate = when {
            variant.variantId.startsWith("E3RA-B1.V02") -> script.masterGate
            variant.variantId.startsWith("E3RA-B2.V02") -> script.clientGate
            else -> null
        }
        if (gate != null) {
            harness.runUntilIdle()
            assertTrue(harness.gateWasEntered(gate), "${variant.variantId}: $gate was not entered")
            assertFalse(job.isCompleted, "${variant.variantId}: startup completed before $gate")
            harness.requestRetirement()
            harness.runUntilIdle()
            harness.captureRetirementJobs()
            notes += "retirement requested while $gate held; startup job active"
            harness.releaseGate(gate)
            harness.runUntilIdle()
        }
        harness.settle(job)
        if (gate == null) {
            harness.requestRetirement()
            harness.runUntilIdle()
            harness.captureRetirementJobs()
        }
        harness.runUntilIdle()
        harness.retirementJobs.lastOrNull()?.join()
        harness.runUntilIdle()
        val deferred = assertNotNull(harness.retirementDeferred,
            "${variant.variantId}: retirement was not requested")
        assertTrue(deferred.isCompleted, "${variant.variantId}: retirement Deferred did not settle")
        assertNotNull(runCatching { deferred.await() }.exceptionOrNull(),
            "${variant.variantId}: unresolved retirement must have an exceptional Deferred")
        assertFalse(harness.view.phase == LocalSetupPhase.CLOSED, "${variant.variantId}: false CLOSED")
        assertPostRetirementRefusal()
        actualClass = "RETIRED_UNRESOLVED"
        selectedOutcome = "PRIMARY"
        when (variant.caseId) {
            "E3RA-B1" -> assertB1Evidence()
            "E3RA-B2" -> assertB2Evidence()
            else -> error("${variant.variantId}: unknown thrown-open case")
        }
        assertTrue(harness.closedDeferredExceptional != null,
            "${variant.variantId}: exceptional Deferred observation missing")
    }

    private fun assertB1Evidence() {
        assertEquals(1, harness.targetOpens.size, "${variant.variantId}: target attempt count")
        assertEquals("opened", harness.targetOpens.single().outcome,
            "${variant.variantId}: target was not returned before openMaster")
        assertNotNull(harness.targetContext, "${variant.variantId}: target context is missing")
        assertEquals(1, harness.masterOpens.size, "${variant.variantId}: openMaster attempt count")
        assertTrue(harness.masterOpens.single().outcome in setOf("unresolved", "throw"),
            "${variant.variantId}: openMaster was not a thrown/unresolved call")
        assertNull(harness.masterObject, "${variant.variantId}: failed openMaster returned an owner")
        assertEquals(0, harness.clientConnects.size, "${variant.variantId}: connectClient after failed Master")
        assertEquals(0, harness.publishes.size, "${variant.variantId}: failed Master published")
        assertEquals(0, harness.masterCloseEntries.size, "${variant.variantId}: fabricated Master close")
        assertEquals(0, harness.targetCloseEntries.size, "${variant.variantId}: fabricated target close")
        assertEquals(0, harness.livePublications, "${variant.variantId}: failed Master live publication")
        assertEquals(0, harness.clientCloseEntries.size, "${variant.variantId}: fabricated Client close")
        notes += "MASTER_OPEN_THROW_OBSERVED; NO_MASTER_OR_CLIENT_HANDLE; " +
            "TARGET_DISPOSITION_RECORDED=HELD_TRANSFER_UNKNOWN " +
            "(inferred from returned-target/no-close/no-master-handle); " +
            "MASTER_DISPOSITION_RECORDED=TRANSFER_UNKNOWN_NO_HANDLE; " +
            "CLIENT_HANDLE_ACCOUNTED=NOT_OPENED; CUSTODY_INDETERMINATE_OBSERVED; " +
            "UNRESOLVED_REASON_RECORDED=TARGET_TRANSFER_UNKNOWN; " +
            "NATIVE_CUSTODY_LEDGER_RECORDED=observed-call-sequence"
        if (variant.variantId.startsWith("E3RA-B1.V03")) {
            conditional += "TARGET-CLOSE-NOT-EXERCISED; optional handle-only diagnostic unentered"
            assertNotNull(variant.actualTargetClose, "${variant.variantId}: actual target close domain missing")
        } else {
            notes += "optional target close diagnostic not entered; no target-only CLOSED"
        }
    }

    private fun assertB2Evidence() {
        assertEquals(1, harness.masterOpens.size, "${variant.variantId}: openMaster count")
        assertEquals("opened", harness.masterOpens.single().outcome,
            "${variant.variantId}: openMaster did not return the known Master")
        assertEquals(1, harness.clientConnects.size, "${variant.variantId}: connectClient count")
        assertEquals("throw", harness.clientConnects.single().outcome,
            "${variant.variantId}: connectClient did not throw")
        val master = assertNotNull(harness.masterObject, "${variant.variantId}: known Master missing")
        assertNotNull(harness.targetContext, "${variant.variantId}: target context disappeared")
        val transfer = assertNotNull(harness.positiveTransfer(),
            "${variant.variantId}: exact positive target transfer observation missing")
        assertSame(harness.targetContext, transfer.context, "${variant.variantId}: transfer context changed")
        assertSame(master, transfer.master, "${variant.variantId}: transfer Master changed")
        assertNull(harness.clientObject, "${variant.variantId}: unreturned Client became a handle")
        assertEquals(0, harness.clientCloseEntries.size, "${variant.variantId}: fabricated Client close")
        assertEquals(1, harness.masterCloseEntries.size, "${variant.variantId}: required Master close count")
        val expected = if (script.closeMaster == "throw") "throw" else "true"
        assertEquals(expected, harness.masterCloseEntries.single().outcome,
            "${variant.variantId}: exact Master close outcome")
        assertEquals(0, harness.publishes.size, "${variant.variantId}: thrown Client published")
        assertEquals(0, harness.livePublications, "${variant.variantId}: thrown Client live publication")
        assertEquals(ReferenceDisposition.TRANSFERRED,
            harness.ledger.entry(harness.targetAlias())?.disposition,
            "${variant.variantId}: target ledger is not TRANSFERRED")
        assertEquals(ReferenceDisposition.NATIVE_OWNER,
            harness.ledger.entry(harness.masterAlias())?.disposition,
            "${variant.variantId}: Master ledger is not NATIVE_OWNER")
        notes += "CLIENT_OPEN_THROW_OBSERVED; MASTER_CLOSE_ENTERED; " +
            "CLIENT_CUSTODY_UNKNOWN_RECORDED (inferred from thrown-connect/no-returned-handle/no-client-close); " +
            "NO_NEW_PUBLICATION; MASTER_CLOSE_${if (expected == "throw") "THROW" else "TRUE"}; " +
            "TARGET_DISPOSITION_RECORDED=TRANSFERRED(exact transfer); MASTER_DISPOSITION_RECORDED=" +
            "${if (expected == "throw") "CLOSE_THROW" else "CLOSED_TRUE"}; " +
            "CLIENT_HANDLE_ACCOUNTED=CLIENT_CUSTODY_UNKNOWN; UNRESOLVED_REASON_RECORDED=CLIENT_CUSTODY_UNKNOWN"
    }

    // ---------------------------------------------------------------- provisioner family

    private suspend fun runProvisionerVariant() {
        harness.usePhase(SupplementalV8Harness.Phase.PROVISIONER)
        val job = harness.invokeProvisionerInspection()
        val gate = script.provisionerOpenGate
        if (gate != null) {
            harness.runUntilIdle()
            assertTrue(harness.gateWasEntered(gate), "${variant.variantId}: $gate was not entered")
            assertFalse(job.isCompleted, "${variant.variantId}: inspection completed before $gate")
            harness.requestRetirement()
            harness.runUntilIdle()
            harness.captureRetirementJobs()
            assertProvisionerRetiringRefusals()
            notes += "retirement requested while $gate held; late Opened owner not yet captured"
            harness.releaseGate(gate)
            harness.runUntilIdle()
        }
        harness.settle(job)
        if (gate == null) {
            // P1-P3: capture both declared company-authority inspections on the exact holder.
            val provisioner = assertNotNull(harness.provisionerObject,
                "${variant.variantId}: Opened provisioner was not captured")
            assertEquals("opened", harness.provisionerOpens.single().outcome,
                "${variant.variantId}: holder binding was not Opened")
            assertEquals("enrolled", harness.provisionerInspects.singleOrNull()?.outcome,
                "${variant.variantId}: inspect() was not Enrolled")
            assertSame(provisioner.companyAuthority, harness.companyAuthorityObject,
                "${variant.variantId}: companyAuthority identity changed")
            val before = harness.authorityInspects.size
            val first = provisioner.companyAuthority.inspectCompanyAuthority()
            harness.authorityInspects += ProvisionerAuthorityObservation("inspectCompanyAuthority",
                harness.provisionerAlias(), if (first is com.ukkera.pos.security.LocalCompanyAuthorityResult.Value<*>)
                    "value" else "unavailable", harness.log.size)
            val second = provisioner.companyAuthority.readOriginalCompanyAndHeadAction()
            harness.authorityInspects += ProvisionerAuthorityObservation("readOriginalCompanyAndHeadAction",
                harness.provisionerAlias(), if (second is com.ukkera.pos.security.LocalCompanyAuthorityResult.Value<*>)
                    "value" else "unavailable", harness.log.size)
            assertTrue(harness.authorityInspects.size >= before + 2,
                "${variant.variantId}: both company-authority inspections were not recorded")
            notes += "PROVISIONER_INSPECTION_ENROLLED; COMPANY_AUTHORITY_INSPECTION_RECORDED; " +
                "ORIGINAL_COMPANY_HEAD_ACTION_RECORDED; exact holder=${harness.provisionerAlias()}"
            harness.requestRetirement()
            harness.runUntilIdle()
            harness.captureRetirementJobs()
            assertProvisionerRetiringRefusals()
            if (script.provisionerCloseGate != null) {
                val closeGate = script.provisionerCloseGate!!
                assertTrue(harness.gateWasEntered(closeGate),
                    "${variant.variantId}: $closeGate was not entered")
                assertFalse(harness.retirementDeferred?.isCompleted == true,
                    "${variant.variantId}: retirement completed while the provisioner close was held")
                notes += "$closeGate entered; pending close is a checkpoint, not a terminal outcome"
                harness.releaseGate(closeGate)
                harness.runUntilIdle()
            }
        } else {
            notes += "late Opened capture path; no mandatory post-retirement inspection; " +
                "PROVISIONER-LATE-REFUSED=NOT_APPLICABLE (late acquisition returned Opened; " +
                "late-owner denial/close exercised)"
        }
        harness.runUntilIdle()
        harness.retirementJobs.lastOrNull()?.join()
        harness.runUntilIdle()
        val deferred = assertNotNull(harness.retirementDeferred,
            "${variant.variantId}: retirement was not requested")
        assertTrue(deferred.isCompleted, "${variant.variantId}: retirement Deferred did not settle")
        val failure = runCatching { deferred.await() }.exceptionOrNull()
        when (variant.expectedClass) {
            "RETIRED_CLOSED" -> {
                assertNull(failure, "${variant.variantId}: CLOSED requires normal Deferred, actual=$failure")
                assertEquals(LocalSetupPhase.CLOSED, harness.view.phase,
                    "${variant.variantId}: CLOSED presentation missing")
                actualClass = "RETIRED_CLOSED"
                assertProvisionerClosed()
            }
            "RETIRED_UNRESOLVED" -> {
                assertNotNull(failure, "${variant.variantId}: unresolved retirement had a normal Deferred")
                assertFalse(harness.view.phase == LocalSetupPhase.CLOSED,
                    "${variant.variantId}: false CLOSED with unconfirmed owner")
                actualClass = "RETIRED_UNRESOLVED"
                assertProvisionerUnresolved()
            }
            else -> error("${variant.variantId}: unknown provisioner expected class")
        }
        selectedOutcome = "PRIMARY"
        assertProvisionerEvidence()
    }

    private fun assertProvisionerRetiringRefusals() {
        val phaseBefore = harness.view.phase
        assertTrue(harness.view.locked,
            "${variant.variantId}: retiring presentation was not locked before resume")
        assertTrue(phaseBefore == LocalSetupPhase.CLOSING || phaseBefore == LocalSetupPhase.CLOSED,
            "${variant.variantId}: resume refusal was not observed during retirement/closed")
        val beforeResume = harness.jobs().toSet()
        harness.coordinator.resumePresentation()
        harness.runUntilIdle()
        assertTrue(harness.view.locked,
            "${variant.variantId}: retiring presentation resume unlocked the view")
        assertEquals(phaseBefore, harness.view.phase,
            "${variant.variantId}: retiring presentation resume changed the phase")
        assertEquals(emptyList(), harness.newJobsSince(beforeResume),
            "${variant.variantId}: retiring presentation resume dispatched work")
        val before = harness.jobs().toSet()
        harness.coordinator.inspectOriginalCompany()
        harness.runUntilIdle()
        assertEquals(emptyList(), harness.newJobsSince(before),
            "${variant.variantId}: retiring inspectOriginalCompany dispatched work")
        notes += "AUTOMATIC_PRESENTATION_LOCKED; RESUME_REFUSED_OR_LOCKED; " +
            "INSPECT_ORIGINAL_COMPANY_REFUSED; NO_AUTHORITY_FROM_PRESENTATION"
    }

    private fun assertProvisionerClosed() {
        assertEquals(1, harness.provisionerCloses.size, "${variant.variantId}: provisioner close count")
        assertEquals("unit", harness.provisionerCloses.single().outcome,
            "${variant.variantId}: provisioner close was not Unit")
        assertEquals(1, harness.ledger.entry(harness.provisionerAlias())?.confirmations,
            "${variant.variantId}: exact provisioner owner not confirmed")
        assertTrue(harness.closedDeferredNormal, "${variant.variantId}: normal Deferred not observed")
        assertEquals(0, harness.publishes.size, "${variant.variantId}: provisioner published")
        assertEquals(0, harness.livePublications, "${variant.variantId}: provisioner live publication")
        notes += "ALL_CURRENT_OWNER_CONFIRMATIONS; PROVISIONER_CLOSE_UNIT; RETIREMENT_DEFERRED_NORMAL; " +
            "PUBLICATION_NOT_APPLICABLE_RECORDED; TRANSFER_ACCOUNTING_RECORDED=NOT_APPLICABLE_NO_TRANSFER"
    }

    private fun assertProvisionerUnresolved() {
        assertTrue(harness.closedDeferredExceptional != null,
            "${variant.variantId}: exceptional Deferred not recorded")
        assertEquals(1, harness.provisionerCloses.size, "${variant.variantId}: provisioner close count")
        notes += "UNRESOLVED_REASON_RECORDED=${variant.unresolvedReason}; " +
            "PUBLICATION_NOT_APPLICABLE_RECORDED; TRANSFER_ACCOUNTING_RECORDED=NOT_APPLICABLE_NO_TRANSFER"
        when {
            variant.unresolvedReason == "INGRESS_DENIAL_UNVERIFIED" -> {
                assertTrue(harness.provisionerDenials.size >= 2,
                    "${variant.variantId}: both denial domains were not attempted")
                assertTrue(harness.provisionerDenials.any { it.scripted == "throw" },
                    "${variant.variantId}: denial throw not recorded")
                assertEquals(1, harness.provisionerDenials.count { it.scripted == "throw" },
                    "${variant.variantId}: thrown denial was retried")
                assertTrue(harness.provisionerCloses.single().outcome == "unit",
                    "${variant.variantId}: exact Provisioner close was not separately recorded")
                notes += "INGRESS-DENIAL-UNVERIFIED; NO-FALSE-DENIAL-CLAIM; PROVISIONER-CLOSE-UNIT-SEPARATE"
            }
            variant.unresolvedReason == "PROVISIONER_CLOSE_UNCONFIRMED" -> {
                assertEquals("throw", harness.provisionerCloses.single().outcome,
                    "${variant.variantId}: close throw was not the unresolved cause")
                assertTrue(harness.provisionerDenials.all { it.scripted == "unit" },
                    "${variant.variantId}: both denials should confirm in this scene")
                notes += "PROVISIONER-CLOSE-UNCONFIRMED; NO-RETRY; NO-FALSE-CLOSED"
            }
            else -> error("${variant.variantId}: unknown provisioner unresolved reason")
        }
    }

    private fun assertProvisionerEvidence() {
        assertEquals(1, harness.provisionerOpens.size, "${variant.variantId}: provisioner acquisition count")
        assertTrue(harness.provisionerOpens.single().originalOnly,
            "${variant.variantId}: creating provisioner acquisition was attempted")
        assertEquals(0, harness.publishes.size, "${variant.variantId}: provisioner publication")
        assertEquals(0, harness.livePublications, "${variant.variantId}: provisioner live publication")
        assertEquals(0, harness.withdraws.size, "${variant.variantId}: provisioner withdrawal")
        val methods = harness.provisionerDenials.map { it.method }.toSet()
        assertTrue(methods.containsAll(setOf("denyNewIssuance", "denyHeadIssuance")),
            "${variant.variantId}: both denial domains not entered: $methods")
        assertEquals(variant.provisionerDenialOutcomes?.let { outcomes ->
            listOf(outcomes.requiredString("denyNewIssuance"), outcomes.requiredString("denyHeadIssuance"))
        }, harness.provisionerDenials.groupBy { it.method }
            .let { groups -> listOf(
                if (groups["denyNewIssuance"]?.single()?.scripted == "throw") "THROW" else "UNIT",
                if (groups["denyHeadIssuance"]?.single()?.scripted == "throw") "THROW" else "UNIT",
            ) }, "${variant.variantId}: denial outcomes differ from the frozen row")
        assertEquals(variant.provisionerCloseOutcome,
            if (harness.provisionerCloses.singleOrNull()?.outcome == "throw") "THROW" else "UNIT",
            "${variant.variantId}: provisioner close outcome differs from the frozen row")
        if (variant.caseId == "E3RA-P4") {
            val openSequence = harness.provisionerOpens.single().sequence
            val firstDenial = harness.provisionerDenials.minOf { it.sequence }
            val closeSequence = harness.provisionerCloses.single().sequence
            assertTrue(openSequence < firstDenial && firstDenial < closeSequence,
                "${variant.variantId}: late capture/denial/close order not observed")
            assertTrue(harness.startupJob?.isCompleted == true,
                "${variant.variantId}: admitted acquisition did not drain before close")
            notes += "PROVISIONER_OPENED_CAPTURED; PROVISIONER_DRAIN_OBSERVED; " +
                "PROVISIONER_EXACT_OWNER_ACCOUNTED; both denial attempts before drain completion/close"
        }
        if (variant.caseId == "E3RA-P1") {
            notes += "ISSUANCE_PROBE_STATUS_RECORDED; UNVERIFIED-ISSUANCE-PROBE " +
                "(no positive public issuance operation is exposed)"
            conditional += "UNVERIFIED-ISSUANCE-PROBE"
        }
    }

    // ---------------------------------------------------------------- shared assertions

    private fun assertCommon() {
        assertNoHintWrite()
        assertNoForbiddenEffects()
        assertPrivacyProjection()
        assertRecordBytes()
        if (actualClass == "NOT_OBSERVED") {
            throw AssertionError("${variant.variantId}: no actual outcome class was observed")
        }
        assertExpectedOrAlternate()
    }

    private fun assertExpectedOrAlternate() {
        if (actualClass == variant.expectedClass) {
            selectedOutcome = if (selectedOutcome == "PRIMARY_OR_ALTERNATE") "PRIMARY" else selectedOutcome
            return
        }
        val alternate = variant.alternateOutcomes.singleOrNull()
        if (alternate != null && actualClass == alternate.requiredString("expected_class")) {
            val condition = alternate.requiredString("condition")
            val diagnostic = alternate.requiredString("diagnostic")
            require(condition in setOf("BOUNDARY_NOT_ENTERED_AND_REUSE_CORE_POSITIVE",
                "OPTIONAL_SETUP_BOUNDARY_NOT_ENTERED")) {
                "${variant.variantId}: unknown alternate condition $condition"
            }
            require(selectedOutcome == "ALTERNATE") {
                "${variant.variantId}: alternate selected without observed non-entry"
            }
            notes += "SELECTED_ALTERNATE condition=$condition diagnostic=$diagnostic; " +
                "named boundary coverage not earned"
            return
        }
        throw AssertionError("${variant.variantId}: expected ${variant.expectedClass}, observed $actualClass" +
            (alternate?.let { ", alternate ${it.requiredString("expected_class")}" } ?: ""))
    }

    private fun assertNoHintWrite() {
        assertEquals(0, harness.launchStorage.writes, "${variant.variantId}: production launch-hint write")
    }

    private fun assertNoForbiddenEffects() {
        assertTrue(harness.forbiddenCalls.isEmpty(),
            "${variant.variantId}: forbidden effects ${harness.forbiddenCalls}")
        assertTrue(harness.denialCalls.all { it.method.isNotEmpty() }, "${variant.variantId}: denial log corrupt")
    }

    private fun assertPrivacyProjection() {
        val view = harness.view
        assertNull(view.companyReview, "${variant.variantId}: private company review exposed")
        assertNull(view.company, "${variant.variantId}: private company exposed")
        assertNull(view.branchReview, "${variant.variantId}: private branch review exposed")
        assertNull(view.definitionReview, "${variant.variantId}: private definition exposed")
        assertNull(view.firstAccountHeadLogin, "${variant.variantId}: private head login exposed")
        assertNull(view.native, "${variant.variantId}: private native projection exposed")
        assertNull(view.receipt, "${variant.variantId}: private receipt projection exposed")
        assertNull(view.employeeEnrollment, "${variant.variantId}: employee enrollment exposed")
        assertNull(view.employeeEnrollments, "${variant.variantId}: employee enrollments exposed")
        assertNull(view.employeePolicy, "${variant.variantId}: employee policy exposed")
        assertNull(view.employeePolicies, "${variant.variantId}: employee policies exposed")
        assertNull(view.deviceAdmission, "${variant.variantId}: device admission exposed")
        assertNull(view.deviceAdmissions, "${variant.variantId}: device admissions exposed")
        assertNull(view.freshDevicePairing, "${variant.variantId}: fresh pairing exposed")
        assertTrue(harness.forbiddenCalls.none { it.startsWith("employee-effect") },
            "${variant.variantId}: employee/session effect observed")
    }

    private fun assertPrivateRefreshProjection() {
        assertNull(harness.view.receipt, "${variant.variantId}: refresh retained a private receipt projection")
        assertNull(harness.view.native, "${variant.variantId}: refresh retained a private native projection")
    }

    private fun assertRecordBytes() {
        val initial = ExistingOnlyStartupFixtures.record("REC-RUN-CANONICAL").bytes
        when {
            variant.variantId.startsWith("E3RA-R4.V01") -> {
                val expected = ExistingOnlyStartupFixtures.record("REC-STOP").bytes
                assertNotNull(expected, "${variant.variantId}: STOP vector missing")
                assertNotNull(harness.launchStorage.bytes, "${variant.variantId}: mutated record disappeared")
                assertTrue(harness.launchStorage.bytes!!.contentEquals(expected),
                    "${variant.variantId}: mutated STOP bytes not preserved")
                assertMutatedReadObserved(expected, "EV-SET-HINT-STOP")
            }
            variant.variantId.startsWith("E3RA-R4.V02") -> {
                val expected = ExistingOnlyStartupFixtures.record("REC-RUN-FOREIGN-01").bytes
                assertNotNull(expected, "${variant.variantId}: foreign RUN vector missing")
                assertNotNull(harness.launchStorage.bytes, "${variant.variantId}: mutated record disappeared")
                assertTrue(harness.launchStorage.bytes!!.contentEquals(expected),
                    "${variant.variantId}: mutated foreign RUN bytes not preserved")
                assertMutatedReadObserved(expected, "EV-SET-HINT-FOREIGN-RUN")
            }
            else -> {
                assertNotNull(initial, "${variant.variantId}: canonical record missing")
                assertNotNull(harness.launchStorage.bytes, "${variant.variantId}: canonical record disappeared")
                assertTrue(harness.launchStorage.bytes!!.contentEquals(initial),
                    "${variant.variantId}: stored record bytes changed")
            }
        }
    }

    private fun assertMutatedReadObserved(bytes: ByteArray, event: String) {
        val mutation = harness.log.all().lastOrNull { it.actor == "TEST" && it.method == "launchStorage.mutate" }
        assertNotNull(mutation, "${variant.variantId}: TEST mutation was not logged")
        assertTrue(mutation!!.detail.contains(event), "${variant.variantId}: TEST mutation event differs")
        val digest = sha256(bytes)
        val fresh = harness.log.all().filter {
            it.actor == "PRODUCTION" && it.method == "launchStorage.read" &&
                it.phase == "returned" && it.sequence > mutation.sequence &&
                it.detail.contains("digest=$digest")
        }
        assertTrue(fresh.isNotEmpty(),
            "${variant.variantId}: fresh production read did not observe the mutated bytes $digest")
        notes += "TEST mutation $event at seq=${mutation.sequence}; fresh mutated-content reads=" +
            fresh.map { it.sequence } + "; production writes=${harness.launchStorage.writes}"
    }

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
            throw AssertionError("${variant.variantId}: teardown left unjoined workers " +
                remaining.joinToString(",") { it.alias() })
        }
    }

    private fun SupplementalVariant.gate(id: String): String? = if (id in gates) id else null
}

private fun SupplementalV8Harness.closeCount(): Int =
    targetCloseEntries.size + masterCloseEntries.size + clientCloseEntries.size
