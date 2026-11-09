package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Executes the complete accepted supplemental-v8 inventory (11 cases / 52 variants) against the
 * real LocalSetupCoordinator/LocalMasterLaunchRecord and controlled public ports. Every required
 * variant must be PASS; a missing/failed/unverified/PARTIAL-equivalent result fails its JUnit
 * method. Optional/unentered diagnostics are recorded separately and never count as coverage.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class SupplementalV8QualificationTest {
    private val fixtures = SupplementalV8Fixtures

    @Test
    public fun e3raR1RefreshPreservesNullableOriginalDetails() = runCase("E3RA-R1")

    @Test
    public fun e3raR2RefreshRefusesChangedOrUnavailableBinding() = runCase("E3RA-R2")

    @Test
    public fun e3raR3RefreshRefusesTargetMasterClientSetupFailures() = runCase("E3RA-R3")

    @Test
    public fun e3raR4RefreshRechecksRecordAndJoinsRetirement() = runCase("E3RA-R4")

    @Test
    public fun e3raR5RefreshIsSingleAdmissionAndRefusesRetiringOrClosed() = runCase("E3RA-R5")

    @Test
    public fun e3raB1ThrownMasterOpenLeavesTargetCustodyIndeterminate() = runCase("E3RA-B1")

    @Test
    public fun e3raB2ThrownClientOpenKeepsMasterAndClientCustodySeparate() = runCase("E3RA-B2")

    @Test
    public fun e3raP1ProvisionerRetirementDeniesBothIngressDomains() = runCase("E3RA-P1")

    @Test
    public fun e3raP2ThrownProvisionerDenialIsNotAConfirmation() = runCase("E3RA-P2")

    @Test
    public fun e3raP3ProvisionerClosePendingThrowAndConfirmation() = runCase("E3RA-P3")

    @Test
    public fun e3raP4LateProvisionerCapturedDeniedAndClosed() = runCase("E3RA-P4")

    private fun runCase(caseId: String) = runTest {
        val case = fixtures.case(caseId)
        assertTrue(case.variants.isNotEmpty(), "$caseId has no frozen variants")
        val outcomes = mutableListOf<SupplementalOutcome>()
        for (variant in case.variants) {
            val outcome = SupplementalV8Driver(this, variant).execute()
            outcomes += outcome
            println(
                "E3-002-SUPPLEMENT-V8 $caseId ${outcome.variantId} ${outcome.status} " +
                    "actual=${outcome.actualClass} selection=${outcome.selectedOutcome} " +
                    "conditional=${outcome.conditional.ifEmpty { listOf("NONE") }} " +
                    "teardown=${outcome.teardown} " +
                    "notes=${outcome.notes.joinToString(" | ")} ledger=${outcome.ledger}",
            )
        }
        val nonPassing = outcomes.filter { it.status != VariantStatus.PASS }
        val counts = outcomes.groupingBy { it.status }.eachCount()
        println("E3-002-SUPPLEMENT-V8 $caseId SUMMARY variants=${outcomes.size} statuses=$counts " +
            "conditional-gap-variants=${outcomes.count { it.conditional.isNotEmpty() }}")
        assertEquals(case.variants.size, outcomes.size, "$caseId variant execution count")
        if (nonPassing.isNotEmpty()) {
            throw AssertionError(
                "$caseId has ${nonPassing.size} non-passing required variant(s): " +
                    nonPassing.joinToString("; ") {
                        "${it.variantId}: status=${it.status} actual=${it.actualClass} " +
                            "notes=${it.notes.lastOrNull()}"
                    },
            )
        }
    }
}
