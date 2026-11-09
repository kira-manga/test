package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Executes the complete frozen late-refresh-v3 inventory (4 cases / 9 variants) against the
 * real LocalSetupCoordinator/LocalMasterLaunchRecord and controlled public ports. A failed
 * assertion fails its JUnit method; an unexercised optional diagnostic is reported as
 * UNVERIFIED and never counted as PASS coverage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class LateRefreshV3QualificationTest {
    private val fixtures = LateRefreshV3Fixtures

    @Test
    public fun e3lrL1LateRecordInvalidationIsRefused() = runCase("E3LR-L1")

    @Test
    public fun e3lrL2LateIndependentLifecycleIsRefused() = runCase("E3LR-L2")

    @Test
    public fun e3lrL3OptionalLateModeDiagnostic() = runCase("E3LR-L3")

    @Test
    public fun e3lrL4UnchangedSetupPendingControl() = runCase("E3LR-L4")

    private fun runCase(caseId: String) = runTest {
        val case = fixtures.case(caseId)
        assertTrue(case.variants.isNotEmpty(), "$caseId has no frozen variants")
        val outcomes = mutableListOf<LateOutcome>()
        for (variant in case.variants) {
            val outcome = LateRefreshV3Driver(this, variant).execute()
            outcomes += outcome
            println(
                "E3-002-LATE-V3 $caseId ${outcome.variantId} ${outcome.status} " +
                    "actual=${outcome.actualClass} selection=${outcome.selectedOutcome} " +
                    "observed=${outcome.observations.size} " +
                    "missing=${outcome.missing.ifEmpty { listOf("NONE") }} " +
                    "forbidden=${outcome.forbiddenPresent.ifEmpty { listOf("NONE") }} " +
                    "gaps=${outcome.gaps.ifEmpty { listOf("NONE") }} " +
                    "teardown=${outcome.teardown} " +
                    "notes=${outcome.notes.joinToString(" | ")} ledger=${outcome.ledger}",
            )
        }
        val failed = outcomes.filter { it.status == VariantStatus.FAIL }
        val unverified = outcomes.filter { it.status == VariantStatus.UNVERIFIED }
        val counts = outcomes.groupingBy { it.status }.eachCount()
        println("E3-002-LATE-V3 $caseId SUMMARY variants=${outcomes.size} statuses=$counts " +
            "unverified-gap-variants=${unverified.size} " +
            "selected=${outcomes.groupingBy { it.selectedOutcome }.eachCount()}")
        assertEquals(case.variants.size, outcomes.size, "$caseId variant execution count")
        if (failed.isNotEmpty()) {
            throw AssertionError(
                "$caseId has ${failed.size} failing required variant(s): " +
                    failed.joinToString("; ") {
                        "${it.variantId}: actual=${it.actualClass} selection=${it.selectedOutcome} " +
                            "missing=${it.missing} forbidden=${it.forbiddenPresent} " +
                            "notes=${it.notes.lastOrNull()}"
                    },
            )
        }
    }
}
