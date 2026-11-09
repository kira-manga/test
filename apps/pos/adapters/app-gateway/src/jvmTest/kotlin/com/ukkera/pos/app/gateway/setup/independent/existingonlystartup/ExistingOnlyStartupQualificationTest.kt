package com.ukkera.pos.app.gateway.setup.independent.existingonlystartup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Executes the complete effective E3-002 v3 + errata-v1 + reviewed errata-v2 inventory
 * against the real LocalSetupCoordinator and LocalMasterLaunchRecord through controlled
 * public ports.
 *
 * Each JUnit method loops the frozen variants of one case; the exact variant ID is in every
 * assertion, printed result line and failure message. Required core UNVERIFIED/PARTIAL results
 * fail the method; optional conditional gaps remain separately recorded and do not change the
 * core expected class. This is controlled shared policy/lifecycle qualification (L1/L5), not
 * L2 Room/SQLite, L3 RPC/LAN, L4 installed workflow or L6 device/owner acceptance. A PASS here
 * does not imply product readiness.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class ExistingOnlyStartupQualificationTest {
    private val fixtures = ExistingOnlyStartupFixtures

    @Test
    public fun e3v3D101LaunchRecordDispositionAndInitialPresentation() = runCase("E3V3-D1-01")

    @Test
    public fun e3v3D102RepeatedAutomaticEntryAfterAnOutcome() = runCase("E3V3-D1-02")

    @Test
    public fun e3v3D103AdmittedWorkRetainedOwnerAndLifecycleRefusal() = runCase("E3V3-D1-03")

    @Test
    public fun e3v3D201ValidRunPermitsExistingOnlyAcquisition() = runCase("E3V3-D2-01")

    @Test
    public fun e3v3D202NativeTargetFailureHasNoCreatingFallback() = runCase("E3V3-D2-02")

    @Test
    public fun e3v3D203RunAloneEstablishesNoAuthority() = runCase("E3V3-D2-03")

    @Test
    public fun e3v3D204MasterOpenFailureHasNoFallback() = runCase("E3V3-D2-04")

    @Test
    public fun e3v3D301AllTwelveCoordinatesMatchAndBindingAgrees() = runCase("E3V3-D3-01")

    @Test
    public fun e3v3D302TwelveTargetMismatchesAndStoredForeignRun() = runCase("E3V3-D3-02")

    @Test
    public fun e3v3D303BindingDisagreementOrRefusalStopsOpening() = runCase("E3V3-D3-03")

    @Test
    public fun e3v3D401OperationalMasterReceivesExactContext() = runCase("E3V3-D4-01")

    @Test
    public fun e3v3D402AllNonOperationalModesRefuseReconnection() = runCase("E3V3-D4-02")

    @Test
    public fun e3v3D403AllNullableDefinitionReceiptCombinationsReconnect() = runCase("E3V3-D4-03")

    @Test
    public fun e3v3D404ThrowingSetupReadRemainsHeld() = runCase("E3V3-D4-04")

    @Test
    public fun e3v3D406NativeRoleAndBindingRefusal() = runCase("E3V3-D4-06")

    @Test
    public fun e3v3D501ExactMasterActionAndClientPublication() = runCase("E3V3-D5-01")

    @Test
    public fun e3v3D502NullClientDoesNotPublish() = runCase("E3V3-D5-02")

    @Test
    public fun e3v3D503ThrowingClientDoesNotPublish() = runCase("E3V3-D5-03")

    @Test
    public fun e3v3D504StaleClientDoesNotPublish() = runCase("E3V3-D5-04")

    @Test
    public fun e3v3D505OpaquePublicationOwnerCorrelation() = runCase("E3V3-D5-05")

    @Test
    public fun e3v3D601ChangedLaunchHintPreventsPublication() = runCase("E3V3-D6-01")

    @Test
    public fun e3v3D602MasterLeavingOperationalPreventsPublication() = runCase("E3V3-D6-02")

    @Test
    public fun e3v3D603ClientInvalidatedPreventsPublication() = runCase("E3V3-D6-03")

    @Test
    public fun e3v3D604RetirementDuringConnectCapturesLateClient() = runCase("E3V3-D6-04")

    @Test
    public fun e3v3D701FailedStartupRetainsCustodyAccounting() = runCase("E3V3-D7-01")

    @Test
    public fun e3v3D702RetirementAfterUnpublishedFailureClosesOwners() = runCase("E3V3-D7-02")

    @Test
    public fun e3v3D703RetirementAfterPublicationClosesAllOwners() = runCase("E3V3-D7-03")

    @Test
    public fun e3v3D704LateMasterIsCapturedWithoutPublication() = runCase("E3V3-D7-04")

    @Test
    public fun e3v3D705LateClientIsCapturedWithoutPublication() = runCase("E3V3-D7-05")

    @Test
    public fun e3v3D706RetiringAndClosedLifecycleRefusesIngress() = runCase("E3V3-D7-06")

    @Test
    public fun e3v3D707LateTargetIsCapturedWithoutProgression() = runCase("E3V3-D7-07")

    @Test
    public fun e3v3D801WaiterCancellationDoesNotCancelCleanup() = runCase("E3V3-D8-01")

    @Test
    public fun e3v3D802ThrowingUnpublicationRetainsUnresolvedCustody() = runCase("E3V3-D8-02")

    @Test
    public fun e3v3D803PendingThrowingAndFalseCloseOutcomes() = runCase("E3V3-D8-03")

    @Test
    public fun e3v3D804RepeatedRetirementDoesNotDuplicateCleanup() = runCase("E3V3-D8-04")

    @Test
    public fun e3v3D805CloseOrderIsProductionChosen() = runCase("E3V3-D8-05")

    @Test
    public fun e3v3C701NonRunRecordsPreserveInitialPresentation() = runCase("E3V3-C7-01")

    @Test
    public fun e3v3C702AutomaticRunAttemptMaintainsProtectedPresentation() = runCase("E3V3-C7-02")

    @Test
    public fun e3v3C703ClearedUiIsNotNativeConfirmation() = runCase("E3V3-C7-03")

    @Test
    public fun e3v3D708DefensiveWithdrawalThrow() = runCase("E3V3-D7-08")

    @Test
    public fun e3v3D806TargetCustodyIndeterminateAfterUnresolvedMaster() = runCase("E3V3-D8-06")

    private fun runCase(caseId: String) = runTest {
        val case = fixtures.case(caseId)
        assertTrue(case.variants.isNotEmpty(), "$caseId has no effective variants")
        val outcomes = mutableListOf<VariantOutcome>()
        for (variant in case.variants) {
            val outcome = ExistingOnlyStartupDriver(this, variant).execute()
            outcomes += outcome
            println(
                "E3-002 $caseId ${outcome.variantId} ${outcome.status} core=${outcome.coreStatus} " +
                    "conditional=${outcome.conditional.ifEmpty { listOf("EXERCISED/NONE") }} " +
                    "watch=${outcome.watch} teardown=${outcome.teardown} " +
                    "notes=${outcome.notes.joinToString(" | ")} ledger=${outcome.ledger}",
            )
        }
        val nonPassing = outcomes.filter { it.status != VariantStatus.PASS }
        val counts = outcomes.groupingBy { it.status }.eachCount()
        val conditionalGaps = outcomes.count { it.conditional.isNotEmpty() }
        println("E3-002 $caseId SUMMARY variants=${outcomes.size} statuses=$counts " +
            "conditional-gap-variants=$conditionalGaps")
        if (nonPassing.isNotEmpty()) {
            throw AssertionError(
                "$caseId has ${nonPassing.size} non-passing required variant(s): " +
                    nonPassing.joinToString("; ") {
                        "${it.variantId}: status=${it.status} core=${it.coreStatus} " +
                            "notes=${it.notes.lastOrNull()}"
                    },
            )
        }
    }
}
