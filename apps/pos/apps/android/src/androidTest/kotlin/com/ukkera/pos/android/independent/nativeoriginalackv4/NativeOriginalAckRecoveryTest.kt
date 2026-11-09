package com.ukkera.pos.android.independent.nativeoriginalackv4

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.independent.aosfsetupv2.readAosfNativeCut
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureClient
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureOwner
import com.ukkera.pos.android.independent.nativefixture.requireSameNativeFixtureCoordinates
import com.ukkera.pos.android.independent.sasacksetupv1.SasActor
import com.ukkera.pos.android.independent.sasacksetupv1.SasFixturePlan
import com.ukkera.pos.android.independent.sasacksetupv1.SasSecrets
import com.ukkera.pos.database.AndroidSelfOwnedMasterService
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.LocalOriginalAcknowledgementRead
import com.ukkera.pos.database.LocalOriginalAcknowledgementStatus
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.security.LocalBranchInstallationReceiptV2
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Supplemental execution of frozen E3-004 v4-c1 segments that are genuinely unblocked through
 * the public Android original owner:
 *
 * - `INSTALLATION_ONLY_CONFIRMED_READ_REPEAT` observes the already-confirmed original through an
 *   installation-only owner, repeats the setup read, the public `readOriginalAcknowledgement()`
 *   confirmed observation, and the public completion observation, then proves zero
 *   read/resume-attributable native/SQL/key/enrollment/ACK change plus one owner.
 * - `CONFIRMED_CLOSE_REOPEN_REPEAT` confirms one native close, takes an independent closed cut,
 *   reopens existing-only, repeats those reads/completion observations, and proves the
 *   ACK/original identity and all counts survive without a second ACK, owner or replacement.
 *
 * Neither segment executes the pending checkpoint, public-read PENDING branch, admission-failure
 * cut, cancellation, lost reply/unknown, contention or conflict branches. Those remain
 * conditional/unbound; a successful run is not a whole NAC/SAS case, an OSR pass or owner
 * acceptance.
 */
@RunWith(AndroidJUnit4::class)
class NativeOriginalAckRecoveryTest {
    @Test
    fun selectedFrozenSegment(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val segment = checkNotNull(arguments.getString("nativeAckV4Segment")) {
            "Select nativeAckV4Segment=INSTALLATION_ONLY_CONFIRMED_READ_REPEAT or " +
                "CONFIRMED_CLOSE_REOPEN_REPEAT on a fresh isolated installation"
        }
        check(segment == INSTALLATION_ONLY_CONFIRMED_READ_REPEAT || segment == CONFIRMED_CLOSE_REOPEN_REPEAT) {
            "Unknown nativeAckV4Segment: $segment"
        }
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")))
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing" &&
            application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            "E3-004 native ACK requires the isolated nondebuggable com.ukkera.pos.local.testing application"
        }
        val evidence = NativeAckEvidence.claim(application, segment, protection.name)
        val plan = SasFixturePlan(protection)
        val secrets = SasSecrets()
        val setupOwner = NativeLocalFixtureOwner<NativeLocalFixtureClient>(
            application, protection, evidence, plan.setup,
            clientFactory = { _, _, _ ->
                error("The frozen ACK segments never enroll or open a client journal")
            })
        var handle: NativeAckOwnerHandle? = null
        var primary: Throwable? = null
        try {
            setupOwner.setup(secrets.installer, secrets.head, secrets[SasActor.US])
            handle = NativeAckOwnerHandle(application, evidence)
            when (segment) {
                INSTALLATION_ONLY_CONFIRMED_READ_REPEAT ->
                    runInstallationOnlySegment(application, handle, setupOwner.coordinates, evidence)
                CONFIRMED_CLOSE_REOPEN_REPEAT ->
                    runConfirmedCloseReopenSegment(application, handle, setupOwner.coordinates, evidence)
                else -> error("Unknown ACK segment")
            }
            evidence.status("${segment}_SEGMENT_EXECUTED_NOT_WHOLE_NAC_CASE")
        } catch (failure: Throwable) {
            primary = failure
            evidence.fail(failure)
            throw failure
        } finally {
            try {
                handle?.closeForCleanup()
                setupOwner.closeOwned()
            } catch (cleanup: Throwable) {
                primary?.let { cleanup.addSuppressed(it) }
                evidence.fail(cleanup)
                throw cleanup
            } finally {
                secrets.clear()
            }
        }
    }

    private suspend fun runInstallationOnlySegment(application: Application, handle: NativeAckOwnerHandle,
        setupCoordinates: com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1,
        evidence: NativeAckEvidence) {
        val beforeCut = readAosfNativeCut(application)
        val beforeAck = readNativeAckState(application)
        beforeAck.requireConfirmed()
        beforeCut.requireInitialSetupOnly()

        handle.openInstallationOnly()
        val service = handle.currentService
        check(service.mode.value == LocalMasterLifecycleMode.INSTALLED_RUNTIME_HELD) {
            "Installation-only open published ${service.mode.value}"
        }
        requireSameNativeFixtureCoordinates(setupCoordinates, handle.coordinates)
        val first = service.readOriginalSetup()
        val savedReceipt = checkNotNull(first.receipt)
        val anchor = bindNativeAckAnchor(first, handle.coordinates, handle.currentMaterial)
        check(beforeAck.receiptDigest == savedReceipt.digest.value &&
            beforeAck.definitionDigest == first.definition?.definitionDigest?.value) {
            "The independent native ACK state does not identify the saved original"
        }
        evidence.bind("ORIGINAL_SETTLEMENT_ANCHOR", anchor.retained())
        evidence.bind("ACK_BEFORE_SEGMENT", beforeAck.retained())
        requireConfirmedAcknowledgement(service, savedReceipt, "installation-initial", evidence)
        repeat(2) { iteration ->
            val read = service.readOriginalSetup()
            requireSameReadback(first, read)
            bindNativeAckAnchor(read, handle.coordinates, handle.currentMaterial).requireSame(anchor)
            requireConfirmedAcknowledgement(service, savedReceipt, "installation-repeat-$iteration", evidence)
            val outcome = service.resumeOriginalAcknowledgement()
            val installed = outcome as? LocalMasterInstallationOutcome.Installed
                ?: error("Confirmed installation-only completion was not observed: $outcome")
            requireSameReceipt(savedReceipt, installed.receipt)
            check(service.mode.value == LocalMasterLifecycleMode.INSTALLED_RUNTIME_HELD) {
                "Repeated completion changed installation-only mode: ${service.mode.value}"
            }
            evidence.event("NATIVE_ACK_INSTALLATION_ONLY_REPEAT", JSONObject()
                .put("iteration", iteration)
                .put("receiptDigest", installed.receipt.digest.value)
                .put("originalRequestId", installed.receipt.core.originalRequestId.value)
                .put("issuedAttemptId", installed.receipt.core.issuedAttemptId.value))
        }
        requireSameReadback(first, service.readOriginalSetup())
        requireConfirmedAcknowledgement(service, savedReceipt, "installation-final", evidence)
        handle.requireNoSecondWriter()
        handle.closeConfirmed()

        val afterCut = readAosfNativeCut(application)
        val afterAck = readNativeAckState(application)
        beforeCut.requireSameReadCut(afterCut)
        beforeAck.requireSame(afterAck)
        check(afterCut.receiptDigest == savedReceipt.digest.value) {
            "The saved original receipt changed after the read/repeat segment"
        }
        evidence.bind("ACK_AFTER_SEGMENT", afterAck.retained())
        evidence.event("NATIVE_ACK_INSTALLATION_ONLY_READ_REPEAT_ZERO_DELTA", JSONObject()
            .put("setupReads", 4).put("publicAckReads", 4).put("completionObservations", 2)
            .put("newAcknowledgements", 0).put("newOwners", 0)
            .put("newReceipts", 0).put("newStorageInstances", 0))
        unboundSegment(evidence, "INSTALLATION_ONLY_CONFIRMED_READ_REPEAT")
    }

    private suspend fun runConfirmedCloseReopenSegment(application: Application, handle: NativeAckOwnerHandle,
        setupCoordinates: com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1,
        evidence: NativeAckEvidence) {
        val beforeCut = readAosfNativeCut(application)
        val beforeAck = readNativeAckState(application)
        beforeAck.requireConfirmed()
        beforeCut.requireInitialSetupOnly()

        handle.openOperational()
        var service = handle.currentService
        check(service.mode.value == LocalMasterLifecycleMode.OPERATIONAL) {
            "Operational open published ${service.mode.value}"
        }
        requireSameNativeFixtureCoordinates(setupCoordinates, handle.coordinates)
        val first = service.readOriginalSetup()
        val savedReceipt = checkNotNull(first.receipt)
        val anchor = bindNativeAckAnchor(first, handle.coordinates, handle.currentMaterial)
        check(beforeAck.receiptDigest == savedReceipt.digest.value &&
            beforeAck.definitionDigest == first.definition?.definitionDigest?.value) {
            "The independent native ACK state does not identify the saved original"
        }
        val firstOriginal = service.originalLocalClient()
        check(firstOriginal.isCurrent())
        evidence.bind("ORIGINAL_SETTLEMENT_ANCHOR", anchor.retained())
        evidence.bind("FIRST_SERVICE_INCARNATION", firstOriginal.serviceIncarnationId.value)
        requireConfirmedAcknowledgement(service, savedReceipt, "pre-close-initial", evidence)
        repeat(2) { iteration ->
            val read = service.readOriginalSetup()
            requireSameReadback(first, read)
            bindNativeAckAnchor(read, handle.coordinates, handle.currentMaterial).requireSame(anchor)
            requireConfirmedAcknowledgement(service, savedReceipt, "pre-close-repeat-$iteration", evidence)
            val outcome = service.resumeOriginalAcknowledgement()
            val installed = outcome as? LocalMasterInstallationOutcome.Installed
                ?: error("Confirmed operational completion was not observed: $outcome")
            requireSameReceipt(savedReceipt, installed.receipt)
            check(service.mode.value == LocalMasterLifecycleMode.OPERATIONAL)
            evidence.event("NATIVE_ACK_PRE_CLOSE_REPEAT", JSONObject()
                .put("iteration", iteration)
                .put("receiptDigest", installed.receipt.digest.value)
                .put("serviceIncarnationId", firstOriginal.serviceIncarnationId.value))
        }
        handle.requireNoSecondWriter()
        handle.closeConfirmed()

        val closedCut = readAosfNativeCut(application)
        val closedAck = readNativeAckState(application)
        beforeCut.requireSameReadCut(closedCut)
        beforeAck.requireSame(closedAck)

        handle.openOperational()
        service = handle.currentService
        check(service.mode.value == LocalMasterLifecycleMode.OPERATIONAL) {
            "Existing-only reopen published ${service.mode.value}"
        }
        requireSameNativeFixtureCoordinates(setupCoordinates, handle.coordinates)
        val reopened = service.readOriginalSetup()
        requireSameReadback(first, reopened)
        bindNativeAckAnchor(reopened, handle.coordinates, handle.currentMaterial).requireSame(anchor)
        val reopenedOriginal = service.originalLocalClient()
        check(reopenedOriginal.isCurrent() && reopenedOriginal.serviceIncarnationId != firstOriginal.serviceIncarnationId) {
            "Existing-only reopen did not acquire a fresh same-original service incarnation"
        }
        evidence.bind("REOPENED_SERVICE_INCARNATION", reopenedOriginal.serviceIncarnationId.value)
        requireConfirmedAcknowledgement(service, savedReceipt, "post-reopen-initial", evidence)
        repeat(2) { iteration ->
            val read = service.readOriginalSetup()
            requireSameReadback(first, read)
            bindNativeAckAnchor(read, handle.coordinates, handle.currentMaterial).requireSame(anchor)
            requireConfirmedAcknowledgement(service, savedReceipt, "post-reopen-repeat-$iteration", evidence)
            val outcome = service.resumeOriginalAcknowledgement()
            val installed = outcome as? LocalMasterInstallationOutcome.Installed
                ?: error("Confirmed reopened completion was not observed: $outcome")
            requireSameReceipt(savedReceipt, installed.receipt)
            check(service.mode.value == LocalMasterLifecycleMode.OPERATIONAL)
            evidence.event("NATIVE_ACK_POST_REOPEN_REPEAT", JSONObject()
                .put("iteration", iteration)
                .put("receiptDigest", installed.receipt.digest.value)
                .put("serviceIncarnationId", reopenedOriginal.serviceIncarnationId.value))
        }
        handle.requireNoSecondWriter()
        handle.closeConfirmed()

        val afterCut = readAosfNativeCut(application)
        val afterAck = readNativeAckState(application)
        beforeCut.requireSameReadCut(afterCut)
        closedCut.requireSameReadCut(afterCut)
        beforeAck.requireSame(afterAck)
        closedAck.requireSame(afterAck)
        evidence.bind("ACK_AFTER_REOPEN", afterAck.retained())
        evidence.event("NATIVE_ACK_CONFIRMED_CLOSE_REOPEN_ZERO_ACK_DELTA", JSONObject()
            .put("confirmedCloses", 2).put("reopens", 1)
            .put("setupReads", 6).put("publicAckReads", 6).put("completionObservations", 4)
            .put("newAcknowledgements", 0).put("newOwners", 0)
            .put("newInstallations", 0).put("newReceipts", 0)
            .put("newCredentials", 0).put("newKeys", 0)
            .put("newSqlEnrollments", 0).put("newDatabases", 0)
            .put("newRoomPools", 0).put("newStorageInstances", 0))
        unboundSegment(evidence, "CONFIRMED_CLOSE_REOPEN_REPEAT")
    }

    private fun requireSameReadback(expected: LocalOriginalInstallationSetup, actual: LocalOriginalInstallationSetup) {
        check(expected.company.organizationId == actual.company.organizationId &&
            expected.company.companyId == actual.company.companyId &&
            expected.company.companyName == actual.company.companyName &&
            expected.company.rootId == actual.company.rootId &&
            expected.company.rootFingerprint == actual.company.rootFingerprint &&
            expected.company.headLogin == actual.company.headLogin)
        check(expected.definition?.definitionDigest == actual.definition?.definitionDigest)
        expected.receipt?.let { expectedReceipt ->
            requireSameReceipt(expectedReceipt, checkNotNull(actual.receipt))
        }
    }

    private fun requireSameReceipt(expected: LocalBranchInstallationReceiptV2,
        actual: LocalBranchInstallationReceiptV2) {
        val expectedBytes = expected.copyWireBytes()
        val actualBytes = actual.copyWireBytes()
        try {
            check(expectedBytes.contentEquals(actualBytes)) {
                "The saved original receipt bytes changed"
            }
        } finally {
            expectedBytes.fill(0)
            actualBytes.fill(0)
        }
    }

    /**
     * Public read-only ACK observation. Only the CONFIRMED branch is bound by these fresh
     * confirmed installations; PENDING/UNKNOWN are not simulated because no genuine pending
     * production checkpoint or public fault boundary is available to this harness.
     */
    private suspend fun requireConfirmedAcknowledgement(service: AndroidSelfOwnedMasterService,
        expected: LocalBranchInstallationReceiptV2, phase: String, evidence: NativeAckEvidence) {
        val modeBefore = service.mode.value
        when (val read = service.readOriginalAcknowledgement()) {
            is LocalOriginalAcknowledgementRead.Observed -> {
                check(read.status == LocalOriginalAcknowledgementStatus.CONFIRMED) {
                    "The public ACK read returned ${read.status} for a confirmed original"
                }
                requireSameReceipt(expected, read.receipt)
                evidence.event("NATIVE_ACK_PUBLIC_READ_CONFIRMED", JSONObject()
                    .put("phase", phase)
                    .put("status", read.status.name)
                    .put("receiptDigest", read.receipt.digest.value)
                    .put("originalRequestId", read.receipt.core.originalRequestId.value)
                    .put("issuedAttemptId", read.receipt.core.issuedAttemptId.value))
            }
            else -> error("The public ACK read did not observe the confirmed original at $phase: $read")
        }
        check(service.mode.value == modeBefore) {
            "The public ACK read changed lifecycle mode from $modeBefore to ${service.mode.value}"
        }
    }

    private fun unboundSegment(evidence: NativeAckEvidence, segment: String) {
        evidence.unbound("NA-U1", "Exact product ACK/attempt/owner/envelope representation is unbound")
        evidence.unbound("NA-U2", "Saved delivery-envelope lifetime and re-derivation are unbound")
        evidence.unbound("NA-U3", "Caller cancellation surface and attempt representation are unbound")
        evidence.unbound("NA-U4", "Contention wait/decline ordering and busy status are unbound")
        evidence.unbound("NA-U5", "Closed status, non-hosting read and reopen support are unbound")
        evidence.unbound("NA-U6", "Independent native cleanup/owner-release signal is unbound; confirmed close is not relabelled as cleanup proof")
        evidence.unbound("NA-U7", "Post-ACK history re-admission representation/trigger is unbound")
        evidence.unbound("NA-U8", "Android service/process lifecycle mapping is unbound")
        evidence.unbound("NA-U9", "Operational-admission relation remains unbound and unexercised")
        evidence.unbound("NA-U10", "Interruption before durable settlement remains outside this ACK segment")
        evidence.unbound("NATIVE-ACK-PENDING-CHECKPOINT",
            "No deterministic public interruption between native settlement and ACK exists; pending/resume is conditional and NOT RUN")
        evidence.unbound("NATIVE-ACK-CANCELLATION",
            "No pending caller-wait exists to cancel without a deterministic checkpoint; NOT RUN")
        evidence.unbound("NATIVE-ACK-LOST-DELIVERY",
            "No public fault boundary withholds/terminates the ACK reply; unknown/lost-delivery is NOT RUN")
        evidence.unbound("NATIVE-ACK-CONFLICTS",
            "The public completion action accepts no alternate receipt/identity; conflict variants B-O are NOT RUN")
        evidence.unbound("NATIVE-ACK-PUBLIC-READ-PENDING",
            "The public ACK read PENDING branch requires a genuine production pending original; the confirmed segments neither create nor simulate one; NOT RUN")
        evidence.unbound("NATIVE-ACK-ADMISSION-FAILURE-CUT",
            "No genuine public fault hook forces post-ACK history/Room admission or activation failure; the saved-completion/UNRESOLVED branch is source-reviewed only; NOT RUN")
        evidence.unbound("NATIVE-ACK-CONCURRENT-INSTALLING-BUSY",
            "No deterministic overlap forces resume while INSTALLING; the Busy branch is source-reviewed only; NOT RUN")
        evidence.unbound("NAC-006-B-COMPLETE",
            "$segment is a bounded segment and does not execute the whole frozen NAC-006-B case")
        evidence.unbound("NAC-002-C-COMPLETE",
            "$segment binds repeated setup reads, public ACK CONFIRMED reads and completion observations; it does not execute the reply-withheld observer or the whole frozen NAC-002-C case")
    }

    private companion object {
        const val INSTALLATION_ONLY_CONFIRMED_READ_REPEAT = "INSTALLATION_ONLY_CONFIRMED_READ_REPEAT"
        const val CONFIRMED_CLOSE_REOPEN_REPEAT = "CONFIRMED_CLOSE_REOPEN_REPEAT"
    }
}
