package com.ukkera.pos.app.gateway.setup.independent.unsignedpairingcontinuation

import com.ukkera.pos.app.gateway.setup.LocalDeviceAdmissionIntents
import com.ukkera.pos.app.gateway.setup.LocalFreshDevicePairingIntents
import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentExportResult
import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentImportResult
import com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow.FreshWorkflowPortGate
import com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow.FreshWorkflowStorageGate
import com.ukkera.pos.app.setup.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** UPRC-v1/v2 common subsets under UPRC-v3 original-setup binding; native layers remain unrun. */
class UnsignedPairingContinuationControlledTest {
    @Test
    fun explicitRetirementAllocationAndLinkedSuccessorProofStopAtRetainedHandoff() = runBlocking {
        val pair = ContinuationPair()
        try {
            pair.initial()
            val old = pair.world.original()
            val i0 = pair.world.data.identity.copyWireBytes()
            val c0 = old.challenge.copyWireBytes()
            val p0 = old.possession.copyWireBytes()
            val targetBefore = pair.world.targetStorage.snapshot()
            val masterBefore = pair.world.masterStorage.snapshot()
            assertCompleteReview(pair, old)
            pair.master.reviewRetirement()
            assertEquals(LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_REVIEW, pair.master.view().stage)
            assertEquals(0, pair.world.entries(ContinuationEffect.MASTER_RETIRE).size)
            pair.master.retire()
            val r0 = assertNotNull(old.retirement)
            assertEquals(LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIRED, pair.master.view().stage)
            assertBytes(r0.copyWireBytes(), pair.master.export(LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT))
            val mutatedExport = pair.master.export(LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT)
            mutatedExport.fill(0)
            assertBytes(r0.copyWireBytes(), pair.master.export(LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT))
            assertBytes(c0, r0.originalChallenge.copyWireBytes())
            assertEquals(420_000L, r0.originalChallenge.reviewContext.deadlineElapsedMillis)
            assertEquals(0, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
            assertEquals(1, pair.world.entries(ContinuationEffect.MASTER_PREPARE).size)

            pair.target.importRetirement(r0.copyWireBytes())
            pair.target.importRetirement(r0.copyWireBytes())
            assertEquals(LocalSetupFreshDevicePairingStage.TARGET_RETIREMENT_REVIEW, pair.target.view().stage)
            assertBytes(c0, assertNotNull(pair.target.view().retirement).originalChallenge.copyWireBytes())
            assertEquals(0, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
            assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_RESPOND).size)
            pair.target.continueAllocation()
            assertEquals(LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_READY, pair.target.view().stage)
            assertEquals(2, pair.world.allocationCount(), "controlled completed allocation is separate from an attempted call")
            assertEquals(1, pair.target.view().responseCycle)
            val cycle = pair.target.cyclesSnapshot().single()
            assertEquals(pair.world.data.responseId(0), cycle.previousResponseId)
            assertEquals(pair.world.data.responseId(1), cycle.allocationResponseId)
            assertEquals(r0.dispositionDigest.value, cycle.retirementDigest)
            assertNull(cycle.responseActionId, "allocation alone signs no successor response")
            assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_RESPOND).size)

            pair.master.beginNextMaster()
            pair.master.importIdentity(i0)
            assertEquals(1, pair.world.entries(ContinuationEffect.MASTER_PREPARE).size)
            pair.master.prepareChallenge()
            val successor = pair.world.original(1)
            assertFalse(old.requestId == successor.requestId)
            assertEquals(r0.dispositionDigest, successor.challenge.reviewContext.previousUnsignedRetirementDigest)
            assertEquals(810_000L, successor.challenge.reviewContext.deadlineElapsedMillis)
            assertBytes(i0, assertNotNull(successor.prepared.requested.sdkIdentity).copyWireBytes())
            val c1 = pair.master.export(LocalSetupPairingDocumentKind.CHALLENGE)
            pair.target.importChallenge(c1)
            assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_RESPOND).size)
            pair.target.respond()
            val p1 = pair.target.export(LocalSetupPairingDocumentKind.POSSESSION)
            assertBytes(successor.possession.copyWireBytes(), p1)
            pair.master.importPossession(p1)
            assertEquals(1, pair.world.entries(ContinuationEffect.MASTER_PROVE).size)
            pair.master.prove()
            assertEquals(LocalDevicePairingPhase.TARGET_PROVED, pair.master.view().nativePhase)
            val proof = pair.world.entries(ContinuationEffect.MASTER_PROVE).last()
            assertSame(successor.prepared, proof.actualAction, "proof must use the source-owned reviewed request handle")
            assertG0Ports(pair.world)
            pair.master.coordinator.handoffFreshDeviceAdmission(pair.master.view().reviewId); pair.master.idle()
            val handoff = assertNotNull(pair.master.coordinator.state.value.deviceAdmission)
            assertEquals(LocalSetupDeviceAdmissionStage.HEAD_REVIEW, handoff.stage)
            assertEquals(LocalDevicePairingPhase.TARGET_PROVED, handoff.nativePhase)
            assertEquals(successor.requestId, handoff.originalRequestId)
            assertEquals(successor.prepared.originalActionId, handoff.originalActionId)
            assertBytes(successor.prepared.requested.copyWireBytes(), assertNotNull(handoff.requested).copyWireBytes())
            assertFalse(handoff.headAttempted)
            assertFalse(handoff.admissionAttempted)
            assertNull(handoff.authorityDigest)
            assertNull(handoff.outcome)
            assertNull(handoff.eventId)

            assertBytes(i0, pair.world.data.identity.copyWireBytes())
            assertBytes(c0, old.challenge.copyWireBytes())
            assertBytes(p0, old.possession.copyWireBytes())
            assertEquals(420_000L, old.challenge.reviewContext.deadlineElapsedMillis)
            assertOldFiles(targetBefore, pair.world.targetStorage.snapshot())
            assertOldFiles(masterBefore, pair.world.masterStorage.snapshot())
            pair.world.entries().forEach { assertAcknowledgedOriginal(pair, it) }
            assertEquals(listOf(ContinuationEffect.TARGET_PREPARE, ContinuationEffect.TARGET_SDK_ACQUIRE,
                ContinuationEffect.MASTER_PREPARE, ContinuationEffect.TARGET_RESPOND, ContinuationEffect.MASTER_PROVE,
                ContinuationEffect.MASTER_RETIRE, ContinuationEffect.TARGET_CONTINUE, ContinuationEffect.MASTER_PREPARE,
                ContinuationEffect.TARGET_RESPOND, ContinuationEffect.MASTER_PROVE), pair.world.entries().map { it.kind })
            assertG0Ports(pair.world, expectedReview = successor)
        } finally { pair.cleanup() }
    }

    @Test
    fun preparedOriginalWithoutPossessionRetiresWithoutAllocationOrResponse() = runBlocking {
        val pair = ContinuationPair()
        try {
            pair.initial(withPossession = false)
            val old = pair.world.original()
            val c0 = old.challenge.copyWireBytes()
            pair.master.reviewRetirement(); pair.master.retire()
            val retirement = assertNotNull(old.retirement)
            assertEquals(LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIRED, pair.master.view().stage)
            assertEquals(LocalDevicePairingPhase.PREPARED, retirement.record.unsignedDisposition?.originalPhase)
            assertNull(retirement.record.possession)
            assertBytes(c0, retirement.originalChallenge.copyWireBytes())
            assertEquals(420_000L, retirement.originalChallenge.reviewContext.deadlineElapsedMillis)
            assertEquals(1, pair.world.entries(ContinuationEffect.MASTER_RETIRE).size)
            assertEquals(0, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
            assertEquals(0, pair.world.entries(ContinuationEffect.TARGET_RESPOND).size)
            assertEquals(0, pair.world.entries(ContinuationEffect.MASTER_PROVE).size)
            assertEquals(1, pair.world.entries(ContinuationEffect.MASTER_PREPARE).size)
            assertAcknowledgedOriginal(pair, pair.world.entries(ContinuationEffect.MASTER_RETIRE).single())
            assertG0Ports(pair.world)
        } finally { pair.cleanup() }
    }

    @Test
    fun pendingExactIntentsBlockRetirementAllocationAndSuccessorPreparationAndThreeClicks() = runBlocking {
        for (boundary in ContinuationBoundary.entries) {
            val pair = ContinuationPair()
            try {
                pair.before(boundary)
                val harness = pair.owner(boundary)
                val beforeCount = pair.world.entries(boundary.effect).size
                val staleReview = harness.view().reviewId
                val storageGate = FreshWorkflowStorageGate(boundary.intentKind)
                pair.storage(boundary).gate = storageGate
                val nativeGate = FreshWorkflowPortGate()
                pair.nativeGate(boundary, nativeGate)
                pair.invoke(boundary, staleReview); storageGate.awaitEntry()
                repeat(2) { pair.invoke(boundary, staleReview) }
                assertEquals(beforeCount, pair.world.entries(boundary.effect).size, "pending acknowledgement cannot enter the native port")
                storageGate.release(); nativeGate.awaitEntry()
                assertAcknowledgedOriginal(pair, pair.world.entries(boundary.effect).last())
                repeat(2) { pair.invoke(boundary, harness.view().reviewId) }
                nativeGate.release(); harness.idle()
                pair.invoke(boundary, staleReview); harness.idle()
                pair.invoke(boundary, harness.view().reviewId); harness.idle()
                assertEquals(beforeCount + 1, pair.world.entries(boundary.effect).size, "no losing tap queues an alternative original")
                assertG0Ports(pair.world)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun failedExactRetirementAndAllocationIntentsNeverEnterCorrespondingCalls() = runBlocking {
        for (boundary in listOf(ContinuationBoundary.RETIREMENT, ContinuationBoundary.ALLOCATION)) {
            val pair = ContinuationPair()
            try {
                pair.before(boundary)
                val harness = pair.owner(boundary)
                val storage = pair.storage(boundary)
                val retained = storage.snapshot()
                val gate = FreshWorkflowStorageGate(boundary.intentKind, fail = true)
                storage.gate = gate
                pair.invoke(boundary, harness.view().reviewId); gate.awaitEntry()
                assertEquals(0, pair.world.entries(boundary.effect).size)
                gate.release(); harness.idle()
                assertEquals(0, pair.world.entries(boundary.effect).size)
                assertSameFiles(retained, storage.snapshot())
                assertFalse(harness.view().stage in setOf(LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIRED,
                    LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_READY))
                assertG0Ports(pair.world)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun lostThrowingAndCancelledRetirementRepliesRecoverOnlyTheSameRetirement() = runBlocking {
        for (reply in LOST_REPLIES) {
            val pair = ContinuationPair()
            try {
                pair.before(ContinuationBoundary.RETIREMENT)
                pair.world.retirementReply = reply
                val old = pair.world.original()
                pair.master.retire()
                assertEquals(1, pair.world.entries(ContinuationEffect.MASTER_RETIRE).size)
                assertAcknowledgedOriginal(pair, pair.world.entries(ContinuationEffect.MASTER_RETIRE).single())
                assertEquals(LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_UNRESOLVED, pair.master.view().stage)
                val r0 = assertNotNull(old.retirement)
                pair.master.retire()
                pair.master.recover()
                assertEquals(LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIRED, pair.master.view().stage)
                assertEquals(old.requestId, pair.master.view().originalRequestId)
                assertBytes(r0.copyWireBytes(), pair.master.export(LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT))
                assertTrue(pair.world.reads().contains("retirement-recover:${old.prepared.originalActionId.value}"))
                assertEquals(1, pair.world.entries(ContinuationEffect.MASTER_RETIRE).size)
                assertEquals(0, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
                assertEquals(1, pair.world.entries(ContinuationEffect.MASTER_PREPARE).size)
                assertEquals(420_000L, old.challenge.reviewContext.deadlineElapsedMillis)
                assertG0Ports(pair.world)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun cachedTerminalJournalNeedsActualOriginalAndExactRetirementLookupAfterRecreation() = runBlocking {
        val pair = ContinuationPair()
        var restored: UnsignedPairingContinuationHarness? = null
        try {
            pair.before(ContinuationBoundary.RETIREMENT)
            pair.world.retirementReply = ContinuationReply.LOST_REPLY
            pair.master.retire()
            val old = pair.world.original()
            val r0 = assertNotNull(old.retirement)
            pair.master.intents.retainMasterRetirement(pair.master.context, old.requestId, r0)
            val saved = pair.world.masterStorage.snapshot()
            pair.world.originalReviewUnavailable = true
            restored = pair.master.recreate(old.requestId)
            assertEquals(LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIREMENT_UNRESOLVED, restored.view().stage)
            assertNull(restored.view().retirement, "cached terminal metadata does not reconstruct native R")
            restored.coordinator.exportFreshDeviceDocument(restored.view().reviewId, LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT); restored.idle()
            assertTrue(restored.documents.exports().isEmpty())
            restored.coordinator.beginFreshDevicePairing(); restored.idle()
            assertEquals(old.requestId, restored.view().originalRequestId)
            pair.world.originalReviewUnavailable = false
            pair.world.retirementRecoveryUnavailable = true
            restored.recover()
            assertNull(restored.view().retirement)
            assertFalse(restored.view().stage == LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIRED)
            pair.world.retirementRecoveryUnavailable = false
            pair.world.recoveredRetirement = pair.world.data.retirement(pair.world.data.original(index = 100, identity = pair.world.data.otherIdentity)).second
            restored.recover()
            assertNull(restored.view().retirement)
            assertFalse(restored.view().stage == LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIRED)
            pair.world.recoveredRetirement = null
            restored.recover()
            assertBytes(r0.copyWireBytes(), restored.export(LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT))
            assertSameFiles(saved, pair.world.masterStorage.snapshot())
            assertEquals(1, pair.world.entries(ContinuationEffect.MASTER_RETIRE).size)
            assertEquals(1, pair.world.entries(ContinuationEffect.MASTER_PREPARE).size)
            assertG0Ports(pair.world)
        } finally { restored?.cleanup(); pair.cleanup() }
    }

    @Test
    fun lostThrowingAndCancelledAllocationRepliesRecoverWithoutAnotherAllocation() = runBlocking {
        for (reply in LOST_REPLIES) {
            val pair = ContinuationPair()
            try {
                pair.before(ContinuationBoundary.ALLOCATION)
                val r0 = assertNotNull(pair.world.original().retirement)
                val originalFiles = pair.world.targetStorage.snapshot()
                pair.world.allocationReply = reply
                pair.target.continueAllocation()
                assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
                assertAcknowledgedOriginal(pair, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).single())
                assertEquals(LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED, pair.target.view().stage)
                assertNull(pair.target.cyclesSnapshot().single().allocationResponseId)
                assertEquals(2, pair.world.allocationCount())
                pair.target.continueAllocation()
                pair.target.recover()
                assertEquals(LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_READY, pair.target.view().stage)
                assertEquals(pair.world.data.responseId(1), pair.target.view().targetReference?.originalResponseId)
                assertTrue(pair.world.reads().contains("allocation-recover:${r0.dispositionDigest.value}"))
                assertBytes(pair.world.data.identity.copyWireBytes(), pair.target.export(LocalSetupPairingDocumentKind.SDK_IDENTITY))
                assertOldFiles(originalFiles, pair.world.targetStorage.snapshot())
                assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
                assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_PREPARE).size)
                assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_SDK_ACQUIRE).size)
                assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_RESPOND).size)
                assertG0Ports(pair.world)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun recreationPreservesOriginalAndSuccessorCycleAndRequiresItsExactRetirementForRecovery() = runBlocking {
        val pair = ContinuationPair()
        var restored: UnsignedPairingContinuationHarness? = null
        var restoredAgain: UnsignedPairingContinuationHarness? = null
        try {
            pair.before(ContinuationBoundary.ALLOCATION)
            val r0 = assertNotNull(pair.world.original().retirement)
            val originalFiles = pair.world.targetStorage.snapshot()
            pair.world.allocationReply = ContinuationReply.LOST_REPLY
            pair.target.continueAllocation()
            pair.world.allocationRecoveryUnavailable = true
            restored = pair.target.recreate()
            assertEquals(LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED, restored.view().stage)
            assertNull(restored.view().retirement)
            assertNull(restored.cyclesSnapshot().single().allocationResponseId)
            restored.continueAllocation()
            restored.respond()
            val otherR = pair.world.data.retirement(pair.world.data.original(index = 100, identity = pair.world.data.otherIdentity)).second
            restored.importRetirement(otherR.copyWireBytes())
            assertNull(restored.view().retirement)
            restored.importRetirement(r0.copyWireBytes())
            pair.world.allocationRecoveryUnavailable = false
            restored.recover()
            assertEquals(LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_READY, restored.view().stage)
            assertEquals(pair.world.data.responseId(1), restored.view().targetReference?.originalResponseId)
            assertBytes(pair.world.data.identity.copyWireBytes(), assertNotNull(restored.view().identity).copyWireBytes())
            val successor = pair.prepareSuccessor()
            restored.importChallenge(successor.challenge.copyWireBytes()); restored.respond()
            assertBytes(successor.possession.copyWireBytes(), restored.export(LocalSetupPairingDocumentKind.POSSESSION))
            val cycle = restored.cyclesSnapshot().single()
            assertEquals(successor.prepared.originalActionId, cycle.responseActionId)
            assertEquals(successor.challenge.challengeDigest.value, cycle.challengeDigest)
            assertNotNull(cycle.possessionDigest)
            val completedFiles = pair.world.targetStorage.snapshot()
            restoredAgain = restored.recreate()
            assertEquals(LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED, restoredAgain.view().stage)
            restoredAgain.importRetirement(r0.copyWireBytes()); restoredAgain.recover()
            assertBytes(successor.possession.copyWireBytes(), restoredAgain.export(LocalSetupPairingDocumentKind.POSSESSION))
            assertEquals(cycle, restoredAgain.cyclesSnapshot().single())
            assertSameFiles(completedFiles, pair.world.targetStorage.snapshot())
            assertOldFiles(originalFiles, pair.world.targetStorage.snapshot())
            assertEquals(2, pair.world.allocationCount())
            assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
            assertEquals(2, pair.world.entries(ContinuationEffect.TARGET_RESPOND).size)
            assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_PREPARE).size)
            assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_SDK_ACQUIRE).size)
            assertG0Ports(pair.world)
        } finally { restoredAgain?.cleanup(); restored?.cleanup(); pair.cleanup() }
    }

    @Test
    fun malformedWrongKindAndOtherOriginalRetirementsDoNotReplaceSelectionOrAllocate() = runBlocking {
        for (variant in 0..5) {
            val pair = ContinuationPair()
            try {
                pair.initial(); pair.master.reviewRetirement(); pair.master.retire()
                val old = pair.world.original()
                val r0 = assertNotNull(old.retirement).copyWireBytes()
                val saved = pair.world.targetStorage.snapshot()
                val input = when (variant) {
                    0 -> LocalSetupPairingDocumentImportResult.Completed(LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT, r0.dropLast(1).toByteArray())
                    1 -> LocalSetupPairingDocumentImportResult.Completed(LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT, r0 + byteArrayOf(0))
                    2 -> LocalSetupPairingDocumentImportResult.Completed(LocalSetupPairingDocumentKind.SDK_IDENTITY, pair.world.data.identity.copyWireBytes())
                    3 -> LocalSetupPairingDocumentImportResult.Completed(LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT, "replacement-envelope".encodeToByteArray())
                    4 -> LocalSetupPairingDocumentImportResult.Completed(LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT,
                        pair.world.data.retirement(pair.world.data.original(index = 100, identity = pair.world.data.otherIdentity)).second.copyWireBytes())
                    else -> LocalSetupPairingDocumentImportResult.Completed(LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT,
                        pair.world.data.retirement(pair.world.data.original(index = 100, differentRoot = true)).second.copyWireBytes())
                }
                pair.target.documents.offer(input)
                pair.target.coordinator.importUnsignedDevicePairingRetirement(pair.target.view().reviewId); pair.target.idle()
                assertEquals(LocalSetupProblem.INVALID_INPUT, pair.target.coordinator.state.value.problem)
                assertNull(pair.target.view().retirement)
                assertBytes(old.challenge.copyWireBytes(), assertNotNull(pair.target.view().challenge).copyWireBytes())
                pair.target.continueAllocation()
                assertEquals(0, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
                assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_RESPOND).size)
                assertSameFiles(saved, pair.world.targetStorage.snapshot())
                assertG0Ports(pair.world)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun wrongPredecessorOriginOrIdentityChallengeAndOldPossessionCannotAdvanceSuccessor() = runBlocking {
        for (variant in 0..4) {
            val pair = ContinuationPair()
            try {
                pair.before(ContinuationBoundary.ALLOCATION); pair.target.continueAllocation()
                val old = pair.world.original()
                val r0 = assertNotNull(old.retirement)
                val successor = pair.prepareSuccessor()
                val saved = pair.world.targetStorage.snapshot()
                val challenge = when (variant) {
                    0 -> old.challenge
                    1 -> pair.world.data.original(index = 100).challenge
                    2 -> pair.world.data.original(index = 100, predecessorDigest = carrierDigest("other-retirement-link")).challenge
                    3 -> pair.world.data.original(index = 100, previous = r0, differentRoot = true).challenge
                    else -> pair.world.data.original(index = 100, previous = r0, identity = pair.world.data.otherIdentity).challenge
                }
                pair.target.importChallenge(challenge.copyWireBytes())
                assertEquals(LocalSetupProblem.INVALID_INPUT, pair.target.coordinator.state.value.problem)
                assertNull(pair.target.view().challenge)
                assertBytes(r0.copyWireBytes(), assertNotNull(pair.target.view().retirement).copyWireBytes())
                pair.target.respond()
                assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_RESPOND).size)
                assertSameFiles(saved, pair.world.targetStorage.snapshot())
                pair.master.importPossession(old.possession.copyWireBytes())
                assertNull(pair.master.view().possession)
                pair.master.prove()
                assertEquals(1, pair.world.entries(ContinuationEffect.MASTER_PROVE).size)
                assertFalse(pair.master.view().stage == LocalSetupFreshDevicePairingStage.TARGET_PROVED)
                assertEquals(successor.requestId, pair.master.view().originalRequestId)
                assertG0Ports(pair.world)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun unavailableCapacityAndForeignAllocationResultsRetainAttemptWithoutCreatingSuccessor() = runBlocking {
        for (reply in listOf(ContinuationReply.UNAVAILABLE, ContinuationReply.CAPACITY, ContinuationReply.FOREIGN)) {
            val pair = ContinuationPair()
            try {
                pair.before(ContinuationBoundary.ALLOCATION)
                val saved = pair.world.targetStorage.snapshot()
                pair.world.allocationReply = reply
                pair.target.continueAllocation()
                assertEquals(LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED, pair.target.view().stage)
                if (reply == ContinuationReply.CAPACITY) assertEquals(LocalSetupProblem.CAPACITY_EXHAUSTED, pair.target.coordinator.state.value.problem)
                assertNull(pair.target.cyclesSnapshot().single().allocationResponseId)
                assertEquals(1, pair.world.allocationCount())
                pair.target.continueAllocation(); pair.target.recover(); pair.target.respond()
                assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
                assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_RESPOND).size)
                assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_PREPARE).size)
                assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_SDK_ACQUIRE).size)
                assertOldFiles(saved, pair.world.targetStorage.snapshot())
                assertG0Ports(pair.world)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun lateNativePhasesUnavailableOriginalAndLostCurrentClientCannotInvokeRetirement() = runBlocking {
        for (variant in 0..4) {
            val pair = ContinuationPair()
            try {
                pair.initial()
                val old = pair.world.original()
                val c0 = old.challenge.copyWireBytes()
                when (variant) {
                    0 -> old.current = pair.world.data.latePhase(old, LocalDevicePairingPhase.SIGNING_INTENT)
                    1 -> old.current = pair.world.data.latePhase(old, LocalDevicePairingPhase.APPROVED)
                    2 -> old.current = pair.world.data.latePhase(old, LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT)
                    3 -> pair.world.originalReviewUnavailable = true
                    else -> { pair.master.reviewRetirement(); pair.world.clientCurrent = false }
                }
                if (variant != 4) pair.master.reviewRetirement()
                val reviewId = pair.master.coordinator.state.value.freshDevicePairing?.reviewId
                if (reviewId != null) { pair.master.coordinator.retireUnsignedDevicePairing(reviewId); pair.master.idle() }
                assertEquals(0, pair.world.entries(ContinuationEffect.MASTER_RETIRE).size)
                assertNull(old.retirement)
                assertEquals(0, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
                assertBytes(c0, old.challenge.copyWireBytes())
                assertEquals(420_000L, old.challenge.reviewContext.deadlineElapsedMillis)
                assertG0Ports(pair.world)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun lockingAnEnteredRetirementOrAllocationHidesReviewAndCannotRepeatTheAttempt() = runBlocking {
        for (boundary in listOf(ContinuationBoundary.RETIREMENT, ContinuationBoundary.ALLOCATION)) {
            val pair = ContinuationPair()
            try {
                pair.before(boundary)
                val harness = pair.owner(boundary)
                val nativeGate = FreshWorkflowPortGate(); pair.nativeGate(boundary, nativeGate)
                pair.invoke(boundary, harness.view().reviewId); nativeGate.awaitEntry()
                assertAcknowledgedOriginal(pair, pair.world.entries(boundary.effect).single())
                harness.coordinator.lockPresentation()
                assertTrue(harness.coordinator.state.value.locked)
                assertNull(harness.coordinator.state.value.freshDevicePairing)
                nativeGate.release(); harness.idle()
                assertTrue(harness.coordinator.state.value.locked)
                assertNull(harness.coordinator.state.value.freshDevicePairing)
                harness.coordinator.resumePresentation(); harness.idle(); harness.recover()
                assertEquals(if (boundary == ContinuationBoundary.RETIREMENT) LocalSetupFreshDevicePairingStage.MASTER_UNSIGNED_RETIRED
                    else LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_READY, harness.view().stage)
                pair.invoke(boundary, harness.view().reviewId); harness.idle()
                assertEquals(1, pair.world.entries(boundary.effect).size)
                assertG0Ports(pair.world)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun cancelledAndUnavailableRetirementTransfersDoNotCompleteOrAllocate() = runBlocking {
        val pair = ContinuationPair()
        try {
            pair.initial(); pair.master.reviewRetirement(); pair.master.retire()
            val r0 = assertNotNull(pair.world.original().retirement)
            val saved = pair.world.targetStorage.snapshot()
            pair.master.documents.exportReply = LocalSetupPairingDocumentExportResult.Cancelled
            pair.master.coordinator.exportFreshDeviceDocument(pair.master.view().reviewId, LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT); pair.master.idle()
            assertEquals(LocalSetupPairingDocumentOutcome.CANCELLED, pair.master.view().document?.outcome)
            pair.master.documents.exportReply = LocalSetupPairingDocumentExportResult.Unavailable()
            pair.master.coordinator.exportFreshDeviceDocument(pair.master.view().reviewId, LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT); pair.master.idle()
            assertFalse(pair.master.view().document?.outcome == LocalSetupPairingDocumentOutcome.EXPORTED)
            pair.master.documents.exportReply = LocalSetupPairingDocumentExportResult.Completed
            assertBytes(r0.copyWireBytes(), pair.master.export(LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT))
            pair.target.documents.offer(LocalSetupPairingDocumentImportResult.Cancelled)
            pair.target.coordinator.importUnsignedDevicePairingRetirement(pair.target.view().reviewId); pair.target.idle()
            assertEquals(LocalSetupPairingDocumentOutcome.CANCELLED, pair.target.view().document?.outcome)
            assertNull(pair.target.view().retirement)
            pair.target.documents.offer(LocalSetupPairingDocumentImportResult.Unavailable())
            pair.target.coordinator.importUnsignedDevicePairingRetirement(pair.target.view().reviewId); pair.target.idle()
            assertFalse(pair.target.view().document?.outcome == LocalSetupPairingDocumentOutcome.IMPORTED)
            assertNull(pair.target.view().retirement)
            assertEquals(0, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
            assertEquals(1, pair.world.entries(ContinuationEffect.MASTER_RETIRE).size)
            assertSameFiles(saved, pair.world.targetStorage.snapshot())
            assertG0Ports(pair.world)
        } finally { pair.cleanup() }
    }

    /** UPRC-v2 only; the preserved v1 no-P test above establishes Master retirement alone. */
    @Test
    fun knownEmptyInitialAllocationWaitsForIntentAndRecoversWithoutFabricatingOldPossession() = runBlocking {
        for (reply in listOf(ContinuationReply.COMPLETE, ContinuationReply.LOST_REPLY)) {
            val pair = ContinuationPair()
            try {
                pair.initial(withPossession = false); pair.master.reviewRetirement(); pair.master.retire()
                val old = pair.world.original()
                val r0 = assertNotNull(old.retirement)
                assertNull(r0.record.possession)
                val oldFiles = pair.world.targetStorage.snapshot()
                pair.target.importRetirement(r0.copyWireBytes())
                assertNull(pair.target.view().possession)
                assertFalse(pair.target.view().responseAttempted)
                assertEquals(pair.world.data.responseId(0), pair.target.view().targetReference?.originalResponseId)
                assertEquals(0, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
                val gate = FreshWorkflowStorageGate("continue1")
                pair.world.targetStorage.gate = gate
                pair.world.allocationReply = reply
                pair.target.coordinator.continueUnsignedDevicePairing(pair.target.view().reviewId); gate.awaitEntry()
                assertEquals(0, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
                gate.release(); pair.target.idle()
                assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
                assertAcknowledgedOriginal(pair, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).single())
                if (reply == ContinuationReply.LOST_REPLY) {
                    assertEquals(LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_UNRESOLVED, pair.target.view().stage)
                    pair.target.continueAllocation(); pair.target.recover()
                }
                assertEquals(LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_READY, pair.target.view().stage)
                assertEquals(2, pair.world.allocationCount())
                assertEquals(1, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
                assertEquals(0, pair.world.entries(ContinuationEffect.TARGET_RESPOND).size)
                assertEquals(0, pair.world.entries(ContinuationEffect.MASTER_PROVE).size)
                assertEquals(1, pair.world.entries(ContinuationEffect.MASTER_PREPARE).size)
                assertNull(pair.target.targetSnapshot()?.responseActionId)
                assertNull(pair.target.targetSnapshot()?.possessionDigest)
                assertOldFiles(oldFiles, pair.world.targetStorage.snapshot())
                assertBytes(r0.copyWireBytes(), assertNotNull(pair.target.view().retirement).copyWireBytes())
                assertG0Ports(pair.world)
            } finally { pair.cleanup() }
        }
    }

    /** UPRC-v2 missing/unfinished source subsets, with no absence-derived authority. */
    @Test
    fun missingInitialSlotAndUncertainNativeSigningCannotBeTreatedAsKnownEmpty() = runBlocking {
        for (variant in 0..3) {
            val pair = ContinuationPair()
            try {
                pair.initial(withPossession = false); pair.master.reviewRetirement(); pair.master.retire()
                val old = pair.world.original()
                pair.target.importRetirement(assertNotNull(old.retirement).copyWireBytes())
                when (variant) {
                    0 -> pair.world.targetOriginalUnavailable = true
                    1 -> pair.world.referenceOverride = pair.world.data.sdkReference().copy(originalResponseId = null)
                    2 -> pair.world.referenceOverride = pair.world.data.sdkReference().copy(
                        phase = LocalDevicePairingTargetPhase.RESPONSE_SIGNING_INTENT,
                        originalPairingActionId = old.prepared.originalActionId, challengeDigest = old.challenge.challengeDigest)
                    else -> pair.world.referenceOverride = pair.world.data.sdkReference().copy(
                        phase = LocalDevicePairingTargetPhase.RESPONSE_ALLOCATION_SIGNING_INTENT)
                }
                pair.target.continueAllocation()
                assertEquals(0, pair.world.entries(ContinuationEffect.TARGET_CONTINUE).size)
                assertEquals(0, pair.world.entries(ContinuationEffect.TARGET_RESPOND).size)
                assertTrue(pair.target.cyclesSnapshot().isEmpty())
                assertFalse(pair.target.view().stage == LocalSetupFreshDevicePairingStage.TARGET_CONTINUATION_READY)
                assertNull(pair.target.view().possession)
                assertEquals(1, pair.world.allocationCount())
                assertEquals(420_000L, old.challenge.reviewContext.deadlineElapsedMillis)
                assertG0Ports(pair.world)
            } finally { pair.cleanup() }
        }
    }

    private companion object {
        val LOST_REPLIES = listOf(ContinuationReply.LOST_REPLY, ContinuationReply.THROW_AFTER_ENTRY, ContinuationReply.CANCEL_AFTER_ENTRY)
    }
}

private enum class ContinuationBoundary(val effect: ContinuationEffect, val intentKind: String) {
    RETIREMENT(ContinuationEffect.MASTER_RETIRE, "retire"),
    ALLOCATION(ContinuationEffect.TARGET_CONTINUE, "continue1"),
    SUCCESSOR_PREPARATION(ContinuationEffect.MASTER_PREPARE, "prepare"),
}

private class ContinuationPair {
    val world = ContinuationWorld()
    val target = UnsignedPairingContinuationHarness(world, LocalSetupFreshDevicePairingRole.FRESH_TARGET)
    val master = UnsignedPairingContinuationHarness(world, LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER)
    suspend fun initial(withPossession: Boolean = true) {
        target.readyTarget(); master.openMaster(); master.importIdentity(target.export(LocalSetupPairingDocumentKind.SDK_IDENTITY))
        master.prepareChallenge(); target.importChallenge(master.export(LocalSetupPairingDocumentKind.CHALLENGE))
        if (withPossession) {
            target.respond(); master.importPossession(target.export(LocalSetupPairingDocumentKind.POSSESSION)); master.prove()
        }
    }
    suspend fun before(boundary: ContinuationBoundary) {
        initial(); master.reviewRetirement()
        if (boundary == ContinuationBoundary.RETIREMENT) return
        master.retire()
        if (boundary == ContinuationBoundary.ALLOCATION) target.importRetirement(master.export(LocalSetupPairingDocumentKind.UNSIGNED_RETIREMENT))
        else { master.beginNextMaster(); master.importIdentity(world.data.identity.copyWireBytes()) }
    }
    suspend fun prepareSuccessor(): ContinuationOriginal {
        master.beginNextMaster(); master.importIdentity(world.data.identity.copyWireBytes()); master.prepareChallenge()
        return world.original(1)
    }
    fun owner(boundary: ContinuationBoundary) = if (boundary == ContinuationBoundary.ALLOCATION) target else master
    fun storage(boundary: ContinuationBoundary) = if (boundary == ContinuationBoundary.ALLOCATION) world.targetStorage else world.masterStorage
    fun nativeGate(boundary: ContinuationBoundary, gate: FreshWorkflowPortGate) {
        when (boundary) {
            ContinuationBoundary.RETIREMENT -> world.retirementGate = gate
            ContinuationBoundary.ALLOCATION -> world.allocationGate = gate
            ContinuationBoundary.SUCCESSOR_PREPARATION -> world.masterPreparationGate = gate
        }
    }
    fun invoke(boundary: ContinuationBoundary, reviewId: DomainId) {
        when (boundary) {
            ContinuationBoundary.RETIREMENT -> master.coordinator.retireUnsignedDevicePairing(reviewId)
            ContinuationBoundary.ALLOCATION -> target.coordinator.continueUnsignedDevicePairing(reviewId)
            ContinuationBoundary.SUCCESSOR_PREPARATION -> master.coordinator.prepareFreshDeviceChallenge(reviewId)
        }
    }
    suspend fun cleanup() { try { target.cleanup() } finally { master.cleanup() } }
}

private fun assertCompleteReview(pair: ContinuationPair, original: ContinuationOriginal) {
    val view = pair.master.view()
    val challenge = assertNotNull(view.challenge)
    assertEquals(pair.world.data.scope, challenge.requested.request.scope)
    assertEquals(pair.world.data.rootId, challenge.reviewContext.rootId)
    assertEquals(pair.world.data.installationId, challenge.reviewContext.coordinates.installationId)
    assertEquals(pair.world.data.masterDeviceId, challenge.cohostDeviceId)
    assertEquals(pair.world.data.targetDeviceId, challenge.requested.admission.deviceId)
    assertEquals(pair.world.data.targetKeyId, challenge.requested.target.keyId)
    assertEquals(pair.world.data.targetPreparationId, challenge.requested.sdkIdentity?.originalPreparationId)
    assertEquals(pair.world.data.targetAcquisitionId, challenge.requested.sdkIdentity?.originalAcquisitionId)
    assertEquals(pair.world.data.capabilities, challenge.requested.admission.capabilities)
    assertEquals(original.requestId, view.originalRequestId)
    assertEquals(original.prepared.originalActionId, challenge.originalActionId)
    assertEquals(LocalDevicePairingPhase.TARGET_PROVED, view.nativePhase)
    assertBytes(pair.world.data.identity.copyWireBytes(), assertNotNull(view.identity).copyWireBytes())
}

private fun assertAcknowledgedOriginal(pair: ContinuationPair, entry: ContinuationEntry) {
    if (entry.target != null) {
        val target = entry.target
        assertEquals(pair.world.data.scope, target.identity.scope)
        assertEquals(pair.world.data.capabilities, target.identity.capabilities)
        assertEquals(pair.world.data.appId, target.identity.appId)
        assertEquals(pair.world.data.privateName, target.identity.deviceName)
        assertTrue(target.preparationAttempted)
        if (entry.kind != ContinuationEffect.TARGET_PREPARE) {
            assertEquals(pair.world.data.targetPreparationId, target.originalPreparationId)
            assertEquals(pair.world.data.targetDeviceId, target.deviceId)
            assertEquals(pair.world.data.targetKeyId, target.keyId)
        }
        if (entry.kind == ContinuationEffect.TARGET_SDK_ACQUIRE) assertTrue(target.sdkAttempted)
        if (entry.kind == ContinuationEffect.TARGET_CONTINUE) {
            val cycle = entry.cycles.last()
            val retirement = LocalDevicePairingUnsignedRetirement.decode(assertNotNull(entry.retirementBytes))
            assertEquals(retirement.originalActionId, cycle.retiredActionId)
            assertEquals(retirement.originalChallenge.challengeDigest.value, cycle.retiredChallengeDigest)
            assertEquals(retirement.dispositionDigest.value, cycle.retirementDigest)
            assertEquals(pair.world.data.responseId(cycle.index - 1), cycle.previousResponseId)
            assertNull(cycle.allocationResponseId, "completed allocation cannot precede its invocation")
        }
        if (entry.kind == ContinuationEffect.TARGET_RESPOND) {
            val challenge = LocalDevicePairingChallenge.decode(assertNotNull(entry.challengeBytes))
            val action = entry.cycles.lastOrNull()?.responseActionId ?: target.responseActionId
            val digest = entry.cycles.lastOrNull()?.challengeDigest ?: target.challengeDigest
            assertEquals(challenge.originalActionId, action)
            assertEquals(challenge.challengeDigest.value, digest)
            entry.cycles.lastOrNull()?.let { assertNotNull(it.allocationResponseId) }
        }
    }
    if (entry.master != null) {
        val master = entry.master
        assertEquals(pair.master.context, master.identity.context)
        assertEquals(pair.world.data.targetDeviceId, master.identity.deviceId)
        assertEquals(pair.world.data.targetKeyId, master.identity.keyId)
        assertEquals(pair.world.data.targetPreparationId, master.identity.originalPreparationId)
        assertEquals(pair.world.data.targetAcquisitionId, master.identity.acquisitionId)
        assertEquals(pair.world.data.identity.descriptorDigest.value, master.identity.sdkDigest)
        assertEquals(entry.requestId, master.identity.requestId)
        assertTrue(master.preparationAttempted)
        if (entry.kind == ContinuationEffect.MASTER_RETIRE) {
            assertTrue(master.retirementAttempted)
            assertEquals(entry.actionId, master.actionId)
            assertEquals(LocalDevicePairingChallenge.decode(assertNotNull(entry.challengeBytes)).challengeDigest.value, master.challengeDigest)
        }
        if (entry.kind == ContinuationEffect.MASTER_PROVE) {
            assertTrue(master.proofAttempted)
            assertEquals(entry.actionId, master.actionId)
            val bytes = assertNotNull(entry.possessionBytes)
            assertEquals(sha256(bytes).value, master.possessionDigest)
        }
    }
}

internal fun assertBytes(expected: ByteArray, actual: ByteArray) = assertTrue(expected.contentEquals(actual), "canonical DATA bytes changed")
private fun assertOldFiles(old: Map<String, ByteArray>, current: Map<String, ByteArray>) {
    assertTrue(current.keys.containsAll(old.keys), "previous original files must remain retained")
    old.forEach { (name, bytes) -> assertBytes(bytes, assertNotNull(current[name])) }
}
private fun assertSameFiles(old: Map<String, ByteArray>, current: Map<String, ByteArray>) {
    assertEquals(old.keys, current.keys); assertOldFiles(old, current)
}
private fun assertG0Ports(world: ContinuationWorld, expectedReview: ContinuationOriginal? = null) {
    assertTrue(world.forbiddenCalls().isEmpty(), "continuation must not call unrelated approval, admission, employee or new-installation ports")
    assertTrue(world.unusedMasterTargetStorage.names().isEmpty())
    assertTrue(world.unusedTargetMasterStorage.names().isEmpty())
    if (expectedReview == null) {
        assertTrue(world.masterAdmissionStorage.names().isEmpty(), "without explicit handoff, no admission review identity is retained")
    } else {
        // UPRC-08 permits exact retained review DATA, never implicit head approval or admission.
        val context = LocalDeviceAdmissionIntents.Context(world.data.installationId, world.data.scope, world.data.masterDeviceId)
        val records = LocalDeviceAdmissionIntents(world.masterAdmissionStorage).records(context)
        assertEquals(1, records.size, "handoff retains only the reviewed successor, not the retired original")
        val record = records.single()
        val requested = expectedReview.prepared.requested
        assertEquals(LocalDeviceAdmissionIntents.Identity(
            context = context,
            actionId = expectedReview.prepared.originalActionId,
            requestId = expectedReview.requestId,
            deviceId = world.data.targetDeviceId,
            keyId = world.data.targetKeyId,
            acquisitionId = world.data.targetAcquisitionId,
            preparationId = world.data.targetPreparationId,
            definitionDigest = requested.definitionDigest.value,
            requestDigest = requested.request.contentDigest.value,
            challengeDigest = expectedReview.challenge.challengeDigest.value,
            sdkDigest = expectedReview.identity.descriptorDigest.value,
            rootFingerprint = expectedReview.challenge.rootFingerprint,
        ), record.identity, "review identity must match the exact retained MREQ1/C1 original")
        assertFalse(record.headAttempted, "retained review alone performs no head approval")
        assertNull(record.authorityDigest)
        assertFalse(record.admissionAttempted, "retained review alone submits no admission")
        assertNull(record.terminal)
        assertNull(record.eventId)
        assertFalse(record.unresolvedAttempt)
    }
    assertTrue(world.targetAdmissionStorage.names().isEmpty())
}
