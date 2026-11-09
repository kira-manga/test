package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException

/**
 * Fixed original event's PURE candidate selection. No native acquisition, SQL, callback,
 * signature verification, AEAD authority, full-history assertion or Found result lives here.
 * The concrete P4 read owner supplies its original snapshot/attempt/proofs/C1 and bytes opened
 * by the actual original native key in the SAME scan snapshot, then owns close/reobservation.
 */
internal object OriginalInstallationReceiptSelection {
    fun selectCandidate(
        snapshot: LocalBranchInstallationSnapshotV2,
        originalAttempt: LocalInstallationAttemptFactsV2,
        originalApprovals: LocalInstallationOriginalApprovalsV2,
        firstChildFacts: LocalInstallationCompletedChildFactsV2,
        expectedAuthEventBinding: ProtectedLocalBinding,
        openedPlaintext: ByteArray,
    ): LocalBranchInstallationReceiptV2? {
        return try {
            val definition = snapshot.definition
            val c = definition.allocation.coordinates
            check(expectedAuthEventBinding == ProtectedLocalBinding(
                ProtectedLocalRecordKey.AuthEvent(c.scope, c.installationId), Revision(0)))
            val receipt = LocalBranchInstallationCodecV2.decodeEvent(expectedAuthEventBinding, openedPlaintext)
            val r = receipt.core
            check(r.allocation.digest == snapshot.allocationDigest && r.definitionDigest == snapshot.definitionDigest &&
                r.originalRequestId == definition.originalRequestId && r.issuedAttemptId == originalAttempt.issuedAttemptId &&
                r.attemptSequence == originalAttempt.attemptSequence && r.authorityEpoch == definition.authorityEpoch &&
                r.headAuthorityId == definition.human.headAuthorityId && r.headEnrollmentId == definition.human.headEnrollmentId &&
                r.foundingBodyDigest == definition.human.foundingBodyDigest)
            check(r.issuerContextId == originalAttempt.issuerContextId && r.monotonicBootId == originalAttempt.monotonicBootId &&
                r.installedAtElapsedMillis >= originalAttempt.observedElapsedMillis &&
                r.installedAtElapsedMillis < originalAttempt.deadlineElapsedMillis)

            // Structural original-proof equality only. Actual original signatures/keys are
            // independently verified by P4's concrete native owner, not by these pure frames.
            val humanFrame = LocalBranchInstallationWireV2.humanApprovalFrame(snapshot, originalAttempt)
            val targetFrame = LocalBranchInstallationWireV2.targetApprovalFrame(snapshot, originalAttempt, originalApprovals.human)
            sameAndClear(humanFrame.copyWireBytes(), originalApprovals.human.frame.copyWireBytes())
            sameAndClear(targetFrame.copyWireBytes(), originalApprovals.target.frame.copyWireBytes())
            check(r.humanApprovalDigest == originalApprovals.human.digest && r.targetProofDigest == originalApprovals.target.digest)

            check(firstChildFacts.originalAllocationDigest == snapshot.allocationDigest &&
                firstChildFacts.definitionDigest == snapshot.definitionDigest &&
                firstChildFacts.originalRequestId == definition.originalRequestId &&
                firstChildFacts.issuedAttemptId == originalAttempt.issuedAttemptId &&
                firstChildFacts.attemptSequence == originalAttempt.attemptSequence &&
                firstChildFacts.commandEpoch == definition.authorityEpoch &&
                firstChildFacts.installationGenesisDigest == receipt.genesis && firstChildFacts.receiptDigest == receipt.digest)

            // Complete ORIGINAL seed semantics, not mutable present-day account/policy values.
            val templates = LocalBranchInstallationCodecV2.preflight(snapshot)
            try {
                check(r.seeds.policy == templates.policyDigest &&
                    r.seeds.configuration == templates.configurationDigest &&
                    r.seeds.admission == templates.admissionDigest)
            } finally { templates.clear() }
            val moment = MonotonicMoment(r.monotonicBootId, r.installedAtElapsedMillis)
            val accountThrottle = PersistentThrottleState(
                ThrottleKey(c.scope, c.firstActorId, ThrottleDimension.Account), Revision(0), 0, moment, 0, null)
            val deviceThrottle = PersistentThrottleState(
                ThrottleKey(c.scope, c.firstActorId, ThrottleDimension.Device(c.deviceId)), Revision(0), 0, moment, 0, null)
            check(r.seeds.accountThrottle == installationDigest(ProtectedLocalSecurityCodec.encodeThrottle(accountThrottle)) &&
                r.seeds.deviceThrottle == installationDigest(ProtectedLocalSecurityCodec.encodeThrottle(deviceThrottle)) &&
                r.seeds.recoveryState == installationDigest(
                    RoomBranchInstallationStorage.recoveryJson(c.scope).encodeToByteArray(throwOnInvalidSequence = true)))

            // The generated original credential/account plaintext digest cannot be derived
            // from D7: native AEAD + the full original C1 census must independently verify it.
            // firstChildFacts.historyDigest is a reference, not proof of that full traversal.
            receipt
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    private fun sameAndClear(expected: ByteArray, actual: ByteArray) {
        try { check(expected.contentEquals(actual)) } finally { expected.fill(0); actual.fill(0) }
    }
}
