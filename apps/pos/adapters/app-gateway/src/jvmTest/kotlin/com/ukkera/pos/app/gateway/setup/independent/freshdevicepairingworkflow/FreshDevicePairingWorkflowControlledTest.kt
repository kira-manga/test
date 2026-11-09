package com.ukkera.pos.app.gateway.setup.independent.freshdevicepairingworkflow

import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentExportResult
import com.ukkera.pos.app.gateway.setup.LocalSetupPairingDocumentImportResult
import com.ukkera.pos.app.setup.*
import com.ukkera.pos.security.LocalDevicePairingChallenge
import com.ukkera.pos.security.LocalDevicePairingPhase
import com.ukkera.pos.security.LocalDevicePairingPossession
import com.ukkera.pos.security.LocalDevicePairingSdkIdentityV2
import com.ukkera.pos.types.DomainId
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Frozen FDPW v1 application subsets, not whole-case/native PASS claims. Requirements:
 * qa/pos/designs/fresh-device-pairing-workflow/{cases.md,fixtures.json} and the
 * first-transfer contract. See README.md for each method's bounded case mapping.
 */
class FreshDevicePairingWorkflowControlledTest {
    @Test
    fun twoCoordinatorsTransferExactTypedOriginalsAndStopAtRetainedReview() = runBlocking {
        val pair = FreshWorkflowPair()
        try {
            pair.target.reviewTarget()
            assertEquals(0, pair.world.entries().size, "review alone has no native effect")
            pair.target.coordinator.exportFreshDeviceDocument(pair.target.view().reviewId, LocalSetupPairingDocumentKind.SDK_IDENTITY)
            pair.target.idle()
            assertTrue(pair.target.documents.exports().isEmpty(), "I is unavailable before source completion")
            pair.target.prepareTarget(); pair.target.acquireIdentity()
            val i = pair.target.export(LocalSetupPairingDocumentKind.SDK_IDENTITY)
            assertBytes(pair.world.data.identityT.copyWireBytes(), i)
            assertBytes(i, LocalDevicePairingSdkIdentityV2.decode(i).copyWireBytes())
            assertPrivateNameAbsent(pair.world.data.privateName, i)
            pair.master.openMaster(); pair.master.importIdentity(i)
            val requestId = assertNotNull(pair.master.view().originalRequestId)
            assertEquals(0, pair.world.entries(FreshWorkflowEffect.MASTER_PREPARE).size)
            pair.master.prepareChallenge()
            val original = assertNotNull(pair.world.original)
            assertEquals(requestId, original.prepared.requested.request.requestId)
            val c = pair.master.export(LocalSetupPairingDocumentKind.CHALLENGE)
            assertBytes(original.challenge.copyWireBytes(), c)
            assertBytes(c, LocalDevicePairingChallenge.decode(c).copyWireBytes())
            assertEquals(300_000L, LocalDevicePairingChallenge.decode(c).reviewContext.deadlineElapsedMillis)
            assertPrivateNameAbsent(pair.world.data.privateName, c)
            pair.target.importChallenge(c)
            assertEquals(0, pair.world.entries(FreshWorkflowEffect.TARGET_RESPOND).size, "challenge import is DATA")
            pair.target.respond()
            val p = pair.target.export(LocalSetupPairingDocumentKind.POSSESSION)
            assertBytes(original.possession.copyWireBytes(), p)
            assertBytes(p, LocalDevicePairingPossession.decode(p).copyWireBytes())
            assertPrivateNameAbsent(pair.world.data.privateName, p)
            pair.master.importPossession(p)
            assertEquals(0, pair.world.entries(FreshWorkflowEffect.MASTER_PROVE).size, "P import cannot confirm native proof")
            assertFalse(pair.master.view().stage == LocalSetupFreshDevicePairingStage.TARGET_PROVED)
            pair.master.prove()
            assertEquals(LocalSetupFreshDevicePairingStage.TARGET_PROVED, pair.master.view().stage)
            assertEquals(LocalDevicePairingPhase.TARGET_PROVED, pair.master.view().nativePhase)
            assertSame(original.prepared, pair.world.entries(FreshWorkflowEffect.MASTER_PROVE).single().actualAction,
                "prove receives the source-owned original review handle")
            pair.world.entries().forEach { assertAcknowledgedOriginal(pair, it) }
            assertEquals(FreshWorkflowEffect.entries.toList(), pair.world.entries().map { it.kind })
            pair.master.coordinator.handoffFreshDeviceAdmission(pair.master.view().reviewId); pair.master.idle()
            val review = assertNotNull(pair.master.coordinator.state.value.deviceAdmission)
            assertEquals(original.prepared.originalActionId, review.originalActionId)
            assertEquals(requestId, review.originalRequestId)
            assertBytes(original.prepared.requested.copyWireBytes(), assertNotNull(review.requested).copyWireBytes())
            assertNoUnrelatedCalls(pair)
        } finally { pair.cleanup() }
    }

    @Test
    fun eachPendingIntentBlocksItsEffectAndDuplicateTapsQueueNothing() = runBlocking {
        for (phase in FreshWorkflowEffect.entries) {
            val pair = FreshWorkflowPair()
            try {
                pair.before(phase)
                val harness = pair.owner(phase)
                val reviewId = harness.view().reviewId
                val storageGate = FreshWorkflowStorageGate(pair.intentKind(phase))
                pair.storage(phase).gate = storageGate
                val nativeGate = FreshWorkflowPortGate()
                pair.gate(phase, nativeGate)
                pair.invoke(phase, reviewId); storageGate.awaitEntry()
                repeat(3) { pair.invoke(phase, reviewId) }
                assertEquals(0, pair.world.entries(phase).size, "pending exact acknowledgement must block $phase")
                storageGate.release(); nativeGate.awaitEntry()
                assertAcknowledgedOriginal(pair, pair.world.entries(phase).single())
                repeat(3) { pair.invoke(phase, harness.view().reviewId) }
                nativeGate.release(); harness.idle()
                pair.invoke(phase, reviewId); harness.idle()
                assertEquals(1, pair.world.entries(phase).size, "duplicates cannot queue another original $phase")
                assertNoUnrelatedCalls(pair)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun refusedIntentAcknowledgementNeverEntersAnyCorrespondingNativePhase() = runBlocking {
        for (phase in FreshWorkflowEffect.entries) {
            val pair = FreshWorkflowPair()
            try {
                pair.before(phase)
                val gate = FreshWorkflowStorageGate(pair.intentKind(phase), fail = true)
                pair.storage(phase).gate = gate
                pair.invoke(phase, pair.owner(phase).view().reviewId); gate.awaitEntry()
                assertEquals(0, pair.world.entries(phase).size)
                gate.release(); pair.owner(phase).idle()
                assertEquals(0, pair.world.entries(phase).size, "failed acknowledgement cannot enter $phase")
                assertNoSuccessfulResult(pair, phase)
                assertNoUnrelatedCalls(pair)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun targetReviewEditsAndMasterAsTargetDoNotCreateNativeWork() = runBlocking {
        val pair = FreshWorkflowPair()
        try {
            pair.target.reviewTarget()
            val changed = LocalSetupFreshDeviceTargetInput(pair.world.data.scope, setOf("stock.read"),
                pair.world.data.appId, "other local name")
            pair.target.coordinator.reviewFreshDeviceTarget(changed); pair.target.idle()
            assertTrue(pair.world.entries().isEmpty(), "reviewing edited input is not preparation")
            pair.target.coordinator.reviewFreshDeviceTarget(pair.world.data.targetInput); pair.target.idle()
            pair.target.prepareTarget()
            val saved = pair.target.targetStorage.snapshot()
            pair.target.coordinator.reviewFreshDeviceTarget(changed); pair.target.idle()
            assertRetentionUnchanged(saved, pair.target.targetStorage.snapshot())
            assertEquals(pair.world.data.privateName, assertNotNull(pair.target.intents.target()).identity.deviceName)
            pair.master.openMaster()
            pair.master.coordinator.chooseFreshDeviceTarget(); pair.master.idle()
            pair.master.coordinator.prepareFreshDeviceTarget(pair.master.view().reviewId); pair.master.idle()
            assertEquals(1, pair.world.entries(FreshWorkflowEffect.TARGET_PREPARE).size,
                "the operational original Master cannot become another target")
            assertEquals(0, pair.world.entries(FreshWorkflowEffect.TARGET_SDK_ACQUIRE).size)
            assertNoUnrelatedCalls(pair)
        } finally { pair.cleanup() }
    }

    @Test
    fun incompleteAndForeignSdkDataCannotBeExportedOrReacquired() = runBlocking {
        for (reply in listOf(FreshWorkflowReply.UNAVAILABLE, FreshWorkflowReply.FOREIGN)) {
            val pair = FreshWorkflowPair()
            try {
                pair.before(FreshWorkflowEffect.TARGET_SDK_ACQUIRE)
                pair.world.acquisitionReply = reply
                pair.world.sdkRecoveryUnavailable = reply == FreshWorkflowReply.UNAVAILABLE
                pair.world.recoveredIdentity = pair.world.data.identityU
                pair.target.acquireIdentity()
                assertNull(pair.target.view().identity)
                pair.target.coordinator.exportFreshDeviceDocument(pair.target.view().reviewId, LocalSetupPairingDocumentKind.SDK_IDENTITY)
                pair.target.idle()
                pair.target.coordinator.acquireFreshDeviceSdkIdentity(pair.target.view().reviewId); pair.target.idle()
                assertTrue(pair.target.documents.exports().isEmpty(), "foreign/incomplete DATA supplies no completed I")
                assertEquals(1, pair.world.entries(FreshWorkflowEffect.TARGET_SDK_ACQUIRE).size)
                assertEquals(pair.world.data.identityT.originalPreparationId, pair.target.view().targetReference?.originalPreparationId)
                assertNoUnrelatedCalls(pair)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun repeatedExactArtifactsRemainReadOnlyAndCallerMutationCannotChangeI() = runBlocking {
        val pair = FreshWorkflowPair()
        try {
            pair.before(FreshWorkflowEffect.MASTER_PREPARE)
            pair.target.documents.mutateExportBuffer = true
            val i = pair.target.export(LocalSetupPairingDocumentKind.SDK_IDENTITY)
            i.fill(0)
            assertBytes(pair.world.data.identityT.copyWireBytes(), pair.target.export(LocalSetupPairingDocumentKind.SDK_IDENTITY))
            pair.master.importIdentity(pair.world.data.identityT.copyWireBytes())
            pair.master.prepareChallenge()
            val original = assertNotNull(pair.world.original)
            pair.target.importChallenge(original.challenge.copyWireBytes()); pair.target.respond()
            pair.target.importChallenge(original.challenge.copyWireBytes())
            pair.target.coordinator.respondFreshDeviceChallenge(pair.target.view().reviewId); pair.target.idle()
            assertBytes(original.possession.copyWireBytes(), pair.target.export(LocalSetupPairingDocumentKind.POSSESSION))
            pair.master.importPossession(original.possession.copyWireBytes()); pair.master.prove()
            pair.master.importIdentity(pair.world.data.identityT.copyWireBytes())
            pair.master.importPossession(original.possession.copyWireBytes())
            pair.master.coordinator.prepareFreshDeviceChallenge(pair.master.view().reviewId); pair.master.idle()
            pair.master.coordinator.proveFreshDeviceTarget(pair.master.view().reviewId); pair.master.idle()
            assertEquals(1, pair.world.entries(FreshWorkflowEffect.MASTER_PREPARE).size)
            assertEquals(1, pair.world.entries(FreshWorkflowEffect.TARGET_RESPOND).size)
            assertEquals(1, pair.world.entries(FreshWorkflowEffect.MASTER_PROVE).size)
            assertEquals(original.prepared.requested.request.requestId, pair.master.view().originalRequestId)
            assertNoUnrelatedCalls(pair)
        } finally { pair.cleanup() }
    }

    @Test
    fun corruptWrongKindAndOversizeChallengeDocumentsDoNotSign() = runBlocking {
        for (variant in 0..2) {
            val pair = FreshWorkflowPair()
            try {
                pair.before(FreshWorkflowEffect.MASTER_PREPARE); pair.master.prepareChallenge()
                val original = assertNotNull(pair.world.original)
                assertNull(pair.target.view().challenge, "no prior valid C may hide an invalid import")
                val retained = pair.target.targetStorage.snapshot()
                if (variant == 2) {
                    // This deliberately invalid DATA proves the carrier constructor's bound
                    // only. No native envelope is padded and no overbound result is offered.
                    assertFailsWith<IllegalArgumentException> {
                        LocalSetupPairingDocumentImportResult.Completed(LocalSetupPairingDocumentKind.CHALLENGE,
                            ByteArray(2_113_537))
                    }
                    assertTrue(pair.target.documents.imports().isEmpty())
                } else {
                    val result = if (variant == 0) LocalSetupPairingDocumentImportResult.Completed(
                        LocalSetupPairingDocumentKind.CHALLENGE, original.challenge.copyWireBytes().dropLast(1).toByteArray())
                    else LocalSetupPairingDocumentImportResult.Completed(LocalSetupPairingDocumentKind.SDK_IDENTITY,
                        pair.world.data.identityT.copyWireBytes())
                    pair.target.documents.offer(result)
                    pair.target.coordinator.importFreshDeviceChallenge(pair.target.view().reviewId); pair.target.idle()
                    assertEquals(LocalSetupProblem.INVALID_INPUT, pair.target.coordinator.state.value.problem)
                }
                assertNull(pair.target.view().challenge)
                assertFalse(pair.target.view().document?.outcome == LocalSetupPairingDocumentOutcome.IMPORTED)
                pair.target.coordinator.respondFreshDeviceChallenge(pair.target.view().reviewId); pair.target.idle()
                assertEquals(0, pair.world.entries(FreshWorkflowEffect.TARGET_RESPOND).size,
                    "direct response without a complete matching C must also refuse")
                assertNull(pair.target.view().possession)
                assertRetentionUnchanged(retained, pair.target.targetStorage.snapshot())
                assertNoUnrelatedCalls(pair)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun foreignChallengeAndPossessionDoNotReplacePinnedOriginals() = runBlocking {
        val pair = FreshWorkflowPair()
        try {
            pair.before(FreshWorkflowEffect.TARGET_RESPOND)
            val original = assertNotNull(pair.world.original)
            val foreign = pair.world.data.foreignOriginal()
            pair.target.importChallenge(foreign.challenge.copyWireBytes())
            assertBytes(original.challenge.copyWireBytes(), assertNotNull(pair.target.view().challenge).copyWireBytes())
            assertEquals(0, pair.world.entries(FreshWorkflowEffect.TARGET_RESPOND).size)
            pair.master.importPossession(foreign.possession.copyWireBytes())
            pair.master.coordinator.proveFreshDeviceTarget(pair.master.view().reviewId); pair.master.idle()
            assertEquals(0, pair.world.entries(FreshWorkflowEffect.MASTER_PROVE).size)
            assertNull(pair.master.view().possession)
            assertEquals(original.prepared.requested.request.requestId, pair.master.view().originalRequestId)
            assertBytes(original.challenge.copyWireBytes(), assertNotNull(pair.master.view().challenge).copyWireBytes())
            assertNoUnrelatedCalls(pair)
        } finally { pair.cleanup() }
    }

    @Test
    fun unavailablePreparedOnlyOrForeignMasterResultsCannotConfirmHandoff() = runBlocking {
        for (reply in listOf(FreshWorkflowReply.UNAVAILABLE, FreshWorkflowReply.PREPARED_ONLY, FreshWorkflowReply.FOREIGN)) {
            val pair = FreshWorkflowPair()
            try {
                pair.before(FreshWorkflowEffect.MASTER_PROVE)
                val requestId = pair.master.view().originalRequestId
                pair.world.proofReply = reply
                pair.master.prove()
                assertFalse(pair.master.view().stage == LocalSetupFreshDevicePairingStage.TARGET_PROVED)
                assertFalse(pair.master.view().nativePhase == LocalDevicePairingPhase.TARGET_PROVED)
                pair.master.coordinator.handoffFreshDeviceAdmission(pair.master.view().reviewId); pair.master.idle()
                assertNull(pair.master.coordinator.state.value.deviceAdmission)
                assertEquals(requestId, pair.master.view().originalRequestId)
                assertEquals(1, pair.world.entries(FreshWorkflowEffect.MASTER_PROVE).size)
                assertNoUnrelatedCalls(pair)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun actualMatchingSourceConfirmationOverridesPendingPresentationOnlyThroughRecovery() = runBlocking {
        val pair = FreshWorkflowPair()
        try {
            pair.before(FreshWorkflowEffect.MASTER_PROVE)
            pair.world.proofReply = FreshWorkflowReply.UNAVAILABLE
            pair.master.prove()
            val request = pair.master.view().originalRequestId
            pair.world.originalReviewUnavailable = true
            pair.master.recover()
            assertFalse(pair.master.view().nativePhase == LocalDevicePairingPhase.TARGET_PROVED)
            pair.world.originalReviewUnavailable = false
            pair.world.proofCompleted = true // A controlled source outcome, never a journal label.
            pair.master.recover()
            assertEquals(request, pair.master.view().originalRequestId)
            assertEquals(LocalSetupFreshDevicePairingStage.TARGET_PROVED, pair.master.view().stage)
            assertEquals(LocalDevicePairingPhase.TARGET_PROVED, pair.master.view().nativePhase)
            assertEquals(1, pair.world.entries(FreshWorkflowEffect.MASTER_PROVE).size)
            assertNoUnrelatedCalls(pair)
        } finally { pair.cleanup() }
    }

    @Test
    fun lostPreparationRepliesKeepUnobservedOwnerQuarantined() = runBlocking {
        for (reply in listOf(FreshWorkflowReply.LOST_REPLY, FreshWorkflowReply.THROW_AFTER_ENTRY, FreshWorkflowReply.CANCEL_AFTER_ENTRY)) {
            val pair = FreshWorkflowPair()
            try {
                pair.before(FreshWorkflowEffect.TARGET_PREPARE)
                val review = pair.target.view().reviewId
                val gate = FreshWorkflowPortGate(); pair.world.preparationGate = gate
                pair.world.preparationReply = reply
                pair.target.coordinator.prepareFreshDeviceTarget(review); gate.awaitEntry()
                assertAcknowledgedOriginal(pair, pair.world.entries(FreshWorkflowEffect.TARGET_PREPARE).single())
                val retained = pair.target.targetStorage.snapshot()
                gate.release(); pair.target.awaitUnconfirmedRetirement()
                val original = assertNotNull(pair.target.intents.target())
                assertEquals(pair.world.data.scope, original.identity.scope)
                assertEquals(pair.world.data.capabilities, original.identity.capabilities)
                assertEquals(pair.world.data.appId, original.identity.appId)
                assertEquals(pair.world.data.privateName, original.identity.deviceName)
                assertTrue(original.preparationAttempted)
                assertFalse(original.sdkAttempted)
                assertNull(original.sdkDigest)
                assertNull(pair.target.coordinator.state.value.freshDevicePairing)
                pair.target.coordinator.chooseFreshDeviceTarget()
                pair.target.coordinator.inspectOriginalFreshDeviceTarget()
                pair.target.coordinator.prepareFreshDeviceTarget(review)
                pair.target.coordinator.acquireFreshDeviceSdkIdentity(review)
                pair.target.coordinator.recoverFreshDevicePairing(review)
                pair.target.coordinator.exportFreshDeviceDocument(review, LocalSetupPairingDocumentKind.SDK_IDENTITY)
                assertEquals(1, pair.world.entries(FreshWorkflowEffect.TARGET_PREPARE).size)
                assertEquals(0, pair.world.entries(FreshWorkflowEffect.TARGET_SDK_ACQUIRE).size)
                assertFalse(pair.world.reads().contains("target-open-existing"),
                    "same-service quarantine cannot be bypassed by an existing-only open")
                assertFalse(pair.world.reads().contains("target-owned-close-confirmed"),
                    "an unreturned factory handle supplies no confirmed close")
                assertTrue(pair.target.documents.exports().isEmpty())
                assertRetentionUnchanged(retained, pair.target.targetStorage.snapshot())
                assertNoUnrelatedCalls(pair)
            } finally { pair.cleanup() }
        }
    }

    @Test fun lostSdkAcquisitionRepliesRecoverIWithoutAnotherAcquisition() = lostReplySubset(FreshWorkflowEffect.TARGET_SDK_ACQUIRE)
    @Test fun lostMasterPreparationRepliesRecoverCWithoutAnotherRequest() = lostReplySubset(FreshWorkflowEffect.MASTER_PREPARE)
    @Test fun lostPossessionRepliesRecoverPWithoutAnotherResponse() = lostReplySubset(FreshWorkflowEffect.TARGET_RESPOND)
    @Test fun lostMasterProofRepliesRecoverExactConfirmationWithoutAnotherProof() = lostReplySubset(FreshWorkflowEffect.MASTER_PROVE)

    @Test
    fun lostMasterPreparationNeverAdoptsTheTemptingLatestAlternateOriginal() = runBlocking {
        val pair = FreshWorkflowPair()
        try {
            pair.before(FreshWorkflowEffect.MASTER_PREPARE)
            val request = pair.master.view().originalRequestId
            pair.world.masterPreparationReply = FreshWorkflowReply.LOST_REPLY
            pair.master.prepareChallenge()
            val original = assertNotNull(pair.world.original)
            pair.world.referencesOverride = listOf(pair.world.data.foreignOriginal().provedReference)
            pair.master.recover()
            assertEquals(request, pair.master.view().originalRequestId)
            assertNull(pair.master.view().challenge)
            assertFalse(pair.master.view().stage == LocalSetupFreshDevicePairingStage.TARGET_PROVED)
            pair.world.referencesOverride = null
            pair.master.recover()
            assertBytes(original.challenge.copyWireBytes(), assertNotNull(pair.master.view().challenge).copyWireBytes())
            assertEquals(300_000L, pair.master.view().challenge?.reviewContext?.deadlineElapsedMillis)
            assertEquals(1, pair.world.entries(FreshWorkflowEffect.MASTER_PREPARE).size)
            assertNoUnrelatedCalls(pair)
        } finally { pair.cleanup() }
    }

    @Test
    fun restorationReexportsExactCompletedOriginalsWithoutAnyNewEffect() = runBlocking {
        val pair = FreshWorkflowPair()
        var restoredTarget: FreshDevicePairingWorkflowHarness? = null
        var restoredMaster: FreshDevicePairingWorkflowHarness? = null
        try {
            pair.before(FreshWorkflowEffect.MASTER_PROVE); pair.master.prove()
            val original = assertNotNull(pair.world.original)
            restoredTarget = pair.target.recreate()
            restoredMaster = pair.master.recreate()
            assertBytes(pair.world.data.identityT.copyWireBytes(), restoredTarget.export(LocalSetupPairingDocumentKind.SDK_IDENTITY))
            assertBytes(original.possession.copyWireBytes(), restoredTarget.export(LocalSetupPairingDocumentKind.POSSESSION))
            assertBytes(original.challenge.copyWireBytes(), restoredMaster.export(LocalSetupPairingDocumentKind.CHALLENGE))
            assertEquals(LocalSetupFreshDevicePairingStage.TARGET_PROVED, restoredMaster.view().stage)
            assertEquals(FreshWorkflowEffect.entries.toList(), pair.world.entries().map { it.kind })
            restoredMaster.coordinator.handoffFreshDeviceAdmission(restoredMaster.view().reviewId); restoredMaster.idle()
            assertEquals(original.prepared.originalActionId, restoredMaster.coordinator.state.value.deviceAdmission?.originalActionId)
            assertNoUnrelatedCalls(pair)
        } finally { restoredTarget?.cleanup(); restoredMaster?.cleanup(); pair.cleanup() }
    }

    @Test
    fun lockedPendingResponseClearsPrivateProjectionAndRecoversWithoutResigning() = runBlocking {
        val pair = FreshWorkflowPair()
        try {
            pair.before(FreshWorkflowEffect.TARGET_RESPOND)
            val gate = FreshWorkflowPortGate(); pair.world.responseGate = gate
            val before = assertNotNull(pair.target.intents.target())
            pair.target.coordinator.respondFreshDeviceChallenge(pair.target.view().reviewId); gate.awaitEntry()
            pair.target.coordinator.lockPresentation()
            assertTrue(pair.target.coordinator.state.value.locked)
            assertNull(pair.target.coordinator.state.value.freshDevicePairing)
            gate.release(); pair.target.idle()
            assertTrue(pair.target.coordinator.state.value.locked)
            assertNull(pair.target.coordinator.state.value.freshDevicePairing)
            assertEquals(before.identity, assertNotNull(pair.target.intents.target()).identity)
            pair.target.coordinator.resumePresentation(); pair.target.idle(); pair.target.recover()
            assertBytes(assertNotNull(pair.world.original).possession.copyWireBytes(), assertNotNull(pair.target.view().possession).copyWireBytes())
            assertEquals(1, pair.world.entries(FreshWorkflowEffect.TARGET_RESPOND).size)
            assertNoUnrelatedCalls(pair)
        } finally { pair.cleanup() }
    }

    @Test
    fun staleDocumentCallbackWhileLockedCannotPrepareTheMaster() = runBlocking {
        val pair = FreshWorkflowPair()
        try {
            pair.target.readyTarget(); pair.master.openMaster()
            val gate = FreshWorkflowPortGate(); pair.master.documents.importGate = gate
            pair.master.documents.offer(LocalSetupPairingDocumentKind.SDK_IDENTITY, pair.world.data.identityT.copyWireBytes())
            val staleReview = pair.master.view().reviewId
            pair.master.coordinator.importFreshDeviceIdentity(staleReview); gate.awaitEntry()
            pair.master.coordinator.lockPresentation()
            pair.master.coordinator.prepareFreshDeviceChallenge(staleReview)
            gate.release(); pair.master.idle()
            pair.master.coordinator.prepareFreshDeviceChallenge(staleReview); pair.master.idle()
            assertTrue(pair.master.coordinator.state.value.locked)
            assertNull(pair.master.coordinator.state.value.freshDevicePairing)
            assertEquals(0, pair.world.entries(FreshWorkflowEffect.MASTER_PREPARE).size)
            assertTrue(pair.master.masterStorage.snapshot().isEmpty())
            pair.master.coordinator.resumePresentation(); pair.master.idle()
            pair.master.coordinator.prepareFreshDeviceChallenge(staleReview); pair.master.idle()
            assertEquals(0, pair.world.entries(FreshWorkflowEffect.MASTER_PREPARE).size,
                "a detached old review cannot dispatch after presentation resumes")
            assertTrue(pair.master.masterStorage.snapshot().isEmpty())
            assertNoUnrelatedCalls(pair)
        } finally { pair.cleanup() }
    }

    @Test
    fun targetClockDoesNotAuthorizeMasterAndWatchCannotRenewOriginalChallenge() = runBlocking {
        val pair = FreshWorkflowPair()
        var restoredTarget: FreshDevicePairingWorkflowHarness? = null
        try {
            pair.before(FreshWorkflowEffect.TARGET_RESPOND)
            val c = assertNotNull(pair.target.view().challenge).copyWireBytes()
            val original = assertNotNull(pair.world.original)
            pair.target.execution.elapsed = 86_400_000L // Target elapsed is far beyond M's unrelated elapsed deadline.
            val gate = FreshWorkflowPortGate(); pair.world.responseGate = gate
            pair.target.coordinator.respondFreshDeviceChallenge(pair.target.view().reviewId); gate.awaitEntry()
            val retained = assertNotNull(pair.target.intents.target())
            assertBytes(c, assertNotNull(pair.world.entries(FreshWorkflowEffect.TARGET_RESPOND).single().challengeBytes))
            pair.target.execution.observeAt(86_430_000L)
            assertEquals(1, pair.world.entries(FreshWorkflowEffect.TARGET_RESPOND).size,
                "T does not compare M's elapsed deadline to its own clock")
            assertEquals(LocalSetupPhase.CLOSING, pair.target.coordinator.state.value.phase)
            assertTrue(pair.target.coordinator.state.value.locked)
            assertNull(pair.target.coordinator.state.value.freshDevicePairing)
            assertFalse(pair.world.responseCompleted, "watch expiry cannot manufacture a completed source outcome")
            assertNull(assertNotNull(pair.target.intents.target()).possessionDigest)
            assertEquals(retained.responseActionId, pair.target.intents.target()?.responseActionId)
            assertEquals(retained.challengeDigest, pair.target.intents.target()?.challengeDigest)
            assertBytes(c, original.challenge.copyWireBytes())
            assertEquals(300_000L, original.challenge.reviewContext.deadlineElapsedMillis)
            assertFalse(pair.world.reads().contains("target-owned-close"), "watch expiry is not native close")
            gate.release()
            // Only the later actual controlled close return allows a new service. Recovery
            // there still reads the same retained native DATA, not the expired old UI.
            restoredTarget = pair.target.recreate()
            assertBytes(original.possession.copyWireBytes(), assertNotNull(restoredTarget.view().possession).copyWireBytes())
            assertBytes(c, original.challenge.copyWireBytes())
            assertEquals(300_000L, original.challenge.reviewContext.deadlineElapsedMillis)
            pair.master.importPossession(original.possession.copyWireBytes())
            pair.world.proofReply = FreshWorkflowReply.UNAVAILABLE // Controlled native M refuses advancement.
            pair.master.prove()
            assertFalse(pair.master.view().nativePhase == LocalDevicePairingPhase.TARGET_PROVED)
            assertEquals(300_000L, pair.master.view().challenge?.reviewContext?.deadlineElapsedMillis)
            assertEquals(1, pair.world.entries(FreshWorkflowEffect.TARGET_RESPOND).size)
            assertNoUnrelatedCalls(pair)
        } finally { restoredTarget?.cleanup(); pair.cleanup() }
    }

    @Test
    fun controlledNativeCapacityRefusalIsNotPossessionOrReplacementPermission() = runBlocking {
        val pair = FreshWorkflowPair()
        try {
            pair.before(FreshWorkflowEffect.TARGET_RESPOND)
            val original = assertNotNull(pair.world.original)
            pair.world.responseReply = FreshWorkflowReply.CAPACITY
            pair.target.respond()
            assertEquals(LocalSetupProblem.CAPACITY_EXHAUSTED, pair.target.coordinator.state.value.problem)
            val retained = assertNotNull(pair.target.intents.target())
            assertEquals(original.prepared.originalActionId, retained.responseActionId)
            assertEquals(original.challenge.challengeDigest.value, retained.challengeDigest)
            assertNull(retained.possessionDigest)
            assertNull(pair.target.view().possession)
            pair.target.coordinator.respondFreshDeviceChallenge(pair.target.view().reviewId); pair.target.idle()
            pair.target.coordinator.exportFreshDeviceDocument(pair.target.view().reviewId, LocalSetupPairingDocumentKind.POSSESSION); pair.target.idle()
            pair.target.recover()
            assertTrue(pair.target.documents.exports().isEmpty())
            assertNull(pair.target.view().possession)
            assertEquals(1, pair.world.entries(FreshWorkflowEffect.TARGET_RESPOND).size)
            assertEquals(1, pair.world.entries(FreshWorkflowEffect.TARGET_PREPARE).size)
            assertEquals(1, pair.world.entries(FreshWorkflowEffect.TARGET_SDK_ACQUIRE).size)
            assertEquals(0, pair.world.entries(FreshWorkflowEffect.MASTER_PROVE).size)
            assertEquals(300_000L, pair.target.view().challenge?.reviewContext?.deadlineElapsedMillis)
            assertNoUnrelatedCalls(pair)
        } finally { pair.cleanup() }
    }

    @Test
    fun unknownOwnedCloseKeepsOriginalBindingAndBlocksFreshAcquisition() = runBlocking {
        for (reply in listOf(FreshWorkflowReply.LOST_REPLY, FreshWorkflowReply.THROW_AFTER_ENTRY, FreshWorkflowReply.CANCEL_AFTER_ENTRY)) {
            val pair = FreshWorkflowPair()
            try {
                pair.target.readyTarget()
                val retained = pair.target.targetStorage.snapshot()
                val review = pair.target.view().reviewId
                val gate = FreshWorkflowPortGate(); pair.world.closeGate = gate
                pair.world.closeReply = reply
                pair.target.coordinator.closeFreshDevicePairing(); gate.awaitEntry()
                gate.release(); pair.target.awaitUnconfirmedRetirement()
                assertTrue(pair.world.ownerCloseUnresolved)
                assertFalse(pair.target.coordinator.state.value.phase == LocalSetupPhase.CHOOSE_ROUTE,
                    "unknown native close cannot release the owner slot")
                assertNull(pair.target.coordinator.state.value.freshDevicePairing)
                pair.target.coordinator.chooseFreshDeviceTarget()
                pair.target.coordinator.inspectOriginalFreshDeviceTarget()
                pair.target.coordinator.prepareFreshDeviceTarget(review)
                pair.target.coordinator.acquireFreshDeviceSdkIdentity(review)
                assertRetentionUnchanged(retained, pair.target.targetStorage.snapshot())
                assertEquals(1, pair.world.reads().count { it == "target-owned-close" }, "failed owned close is not retried")
                assertFalse(pair.world.reads().contains("target-owned-close-confirmed"))
                assertFalse(pair.world.reads().contains("target-open-existing"))
                assertEquals(1, pair.world.entries(FreshWorkflowEffect.TARGET_PREPARE).size)
                assertEquals(1, pair.world.entries(FreshWorkflowEffect.TARGET_SDK_ACQUIRE).size)
                assertNoUnrelatedCalls(pair)
            } finally { pair.cleanup() }
        }
    }

    @Test
    fun cancelledOrUnavailableDocumentsNeverReportCompletedTransfer() = runBlocking {
        val pair = FreshWorkflowPair()
        try {
            pair.target.readyTarget(); pair.master.openMaster()
            pair.master.documents.offer(LocalSetupPairingDocumentImportResult.Cancelled)
            pair.master.coordinator.importFreshDeviceIdentity(pair.master.view().reviewId); pair.master.idle()
            assertEquals(LocalSetupPairingDocumentOutcome.CANCELLED, pair.master.view().document?.outcome)
            assertNull(pair.master.view().identity)
            pair.master.documents.offer(LocalSetupPairingDocumentImportResult.Unavailable())
            pair.master.coordinator.importFreshDeviceIdentity(pair.master.view().reviewId); pair.master.idle()
            assertNull(pair.master.view().identity)
            assertEquals(LocalSetupProblem.NATIVE_UNAVAILABLE, pair.master.coordinator.state.value.problem)
            pair.target.documents.exportReply = LocalSetupPairingDocumentExportResult.Cancelled
            pair.target.coordinator.exportFreshDeviceDocument(pair.target.view().reviewId, LocalSetupPairingDocumentKind.SDK_IDENTITY); pair.target.idle()
            assertEquals(LocalSetupPairingDocumentOutcome.CANCELLED, pair.target.view().document?.outcome)
            pair.target.documents.exportReply = LocalSetupPairingDocumentExportResult.Unavailable()
            pair.target.coordinator.exportFreshDeviceDocument(pair.target.view().reviewId, LocalSetupPairingDocumentKind.SDK_IDENTITY); pair.target.idle()
            assertEquals(LocalSetupPairingDocumentOutcome.UNAVAILABLE, pair.target.view().document?.outcome)
            assertEquals(LocalSetupProblem.NATIVE_UNAVAILABLE, pair.target.coordinator.state.value.problem,
                "unavailable export must leave its actual refusal problem visible")
            assertEquals(0, pair.world.entries(FreshWorkflowEffect.MASTER_PREPARE).size,
                "cancelled/unavailable transfer alone prepares no native challenge")
            pair.master.importIdentity(pair.world.data.identityT.copyWireBytes()); pair.master.prepareChallenge()
            pair.master.documents.exportReply = LocalSetupPairingDocumentExportResult.Unavailable(LocalSetupProblem.STATE_UNCERTAIN)
            pair.master.coordinator.exportFreshDeviceDocument(pair.master.view().reviewId, LocalSetupPairingDocumentKind.CHALLENGE); pair.master.idle()
            assertEquals(LocalSetupPairingDocumentOutcome.UNAVAILABLE, pair.master.view().document?.outcome)
            assertEquals(LocalSetupProblem.STATE_UNCERTAIN, pair.master.coordinator.state.value.problem,
                "Master export preserves the supplied unavailable problem too")
            assertBytes(assertNotNull(pair.world.original).challenge.copyWireBytes(), assertNotNull(pair.master.view().challenge).copyWireBytes())
            assertEquals(1, pair.world.entries(FreshWorkflowEffect.MASTER_PREPARE).size,
                "only the separate explicit preparation enters native work")
            assertEquals(0, pair.world.entries(FreshWorkflowEffect.TARGET_RESPOND).size)
            assertEquals(0, pair.world.entries(FreshWorkflowEffect.MASTER_PROVE).size)
            assertNoUnrelatedCalls(pair)
        } finally { pair.cleanup() }
    }

    private fun lostReplySubset(phase: FreshWorkflowEffect) = runBlocking {
        for (reply in listOf(FreshWorkflowReply.LOST_REPLY, FreshWorkflowReply.THROW_AFTER_ENTRY, FreshWorkflowReply.CANCEL_AFTER_ENTRY)) {
            val pair = FreshWorkflowPair()
            try {
                pair.before(phase); pair.reply(phase, reply)
                val harness = pair.owner(phase)
                pair.invoke(phase, harness.view().reviewId); harness.idle()
                assertEquals(1, pair.world.entries(phase).size, "a lost/thrown/cancelled entered call is still an attempt")
                assertAcknowledgedOriginal(pair, pair.world.entries(phase).single())
                pair.invoke(phase, harness.view().reviewId); harness.idle()
                assertEquals(1, pair.world.entries(phase).size)
                harness.recover()
                when (phase) {
                    FreshWorkflowEffect.TARGET_PREPARE -> error("unreturned factory ownership uses the separate quarantine subset")
                    FreshWorkflowEffect.TARGET_SDK_ACQUIRE -> assertBytes(pair.world.data.identityT.copyWireBytes(), assertNotNull(harness.view().identity).copyWireBytes())
                    FreshWorkflowEffect.MASTER_PREPARE -> assertBytes(assertNotNull(pair.world.original).challenge.copyWireBytes(), assertNotNull(harness.view().challenge).copyWireBytes())
                    FreshWorkflowEffect.TARGET_RESPOND -> assertBytes(assertNotNull(pair.world.original).possession.copyWireBytes(), assertNotNull(harness.view().possession).copyWireBytes())
                    FreshWorkflowEffect.MASTER_PROVE -> {
                        assertEquals(LocalSetupFreshDevicePairingStage.TARGET_PROVED, harness.view().stage)
                        assertEquals(LocalDevicePairingPhase.TARGET_PROVED, harness.view().nativePhase)
                    }
                }
                assertEquals(1, pair.world.entries(phase).size, "recovery is existing-only")
                assertNoUnrelatedCalls(pair)
            } finally { pair.cleanup() }
        }
    }
}

private class FreshWorkflowPair {
    val world = FreshDevicePairingWorkflowWorld()
    val target = FreshDevicePairingWorkflowHarness(world)
    val master = FreshDevicePairingWorkflowHarness(world, LocalSetupFreshDevicePairingRole.ORIGINAL_MASTER)
    suspend fun before(phase: FreshWorkflowEffect) {
        target.reviewTarget()
        if (phase == FreshWorkflowEffect.TARGET_PREPARE) return
        target.prepareTarget()
        if (phase == FreshWorkflowEffect.TARGET_SDK_ACQUIRE) return
        target.acquireIdentity(); master.openMaster(); master.importIdentity(world.data.identityT.copyWireBytes())
        if (phase == FreshWorkflowEffect.MASTER_PREPARE) return
        master.prepareChallenge(); target.importChallenge(checkNotNull(world.original).challenge.copyWireBytes())
        if (phase == FreshWorkflowEffect.TARGET_RESPOND) return
        target.respond(); master.importPossession(checkNotNull(world.original).possession.copyWireBytes())
    }
    fun owner(phase: FreshWorkflowEffect) = if (phase in setOf(FreshWorkflowEffect.MASTER_PREPARE, FreshWorkflowEffect.MASTER_PROVE)) master else target
    fun storage(phase: FreshWorkflowEffect) = if (owner(phase) === master) master.masterStorage else target.targetStorage
    fun intentKind(phase: FreshWorkflowEffect) = when (phase) {
        FreshWorkflowEffect.TARGET_PREPARE, FreshWorkflowEffect.MASTER_PREPARE -> "intent"
        FreshWorkflowEffect.TARGET_SDK_ACQUIRE -> "sdk"
        FreshWorkflowEffect.TARGET_RESPOND -> "respond"
        FreshWorkflowEffect.MASTER_PROVE -> "prove"
    }
    fun invoke(phase: FreshWorkflowEffect, reviewId: DomainId) {
        when (phase) {
            FreshWorkflowEffect.TARGET_PREPARE -> target.coordinator.prepareFreshDeviceTarget(reviewId)
            FreshWorkflowEffect.TARGET_SDK_ACQUIRE -> target.coordinator.acquireFreshDeviceSdkIdentity(reviewId)
            FreshWorkflowEffect.MASTER_PREPARE -> master.coordinator.prepareFreshDeviceChallenge(reviewId)
            FreshWorkflowEffect.TARGET_RESPOND -> target.coordinator.respondFreshDeviceChallenge(reviewId)
            FreshWorkflowEffect.MASTER_PROVE -> master.coordinator.proveFreshDeviceTarget(reviewId)
        }
    }
    fun gate(phase: FreshWorkflowEffect, gate: FreshWorkflowPortGate) {
        when (phase) {
            FreshWorkflowEffect.TARGET_PREPARE -> world.preparationGate = gate
            FreshWorkflowEffect.TARGET_SDK_ACQUIRE -> world.acquisitionGate = gate
            FreshWorkflowEffect.MASTER_PREPARE -> world.masterPreparationGate = gate
            FreshWorkflowEffect.TARGET_RESPOND -> world.responseGate = gate
            FreshWorkflowEffect.MASTER_PROVE -> world.proofGate = gate
        }
    }
    fun reply(phase: FreshWorkflowEffect, reply: FreshWorkflowReply) {
        when (phase) {
            FreshWorkflowEffect.TARGET_PREPARE -> world.preparationReply = reply
            FreshWorkflowEffect.TARGET_SDK_ACQUIRE -> world.acquisitionReply = reply
            FreshWorkflowEffect.MASTER_PREPARE -> world.masterPreparationReply = reply
            FreshWorkflowEffect.TARGET_RESPOND -> world.responseReply = reply
            FreshWorkflowEffect.MASTER_PROVE -> world.proofReply = reply
        }
    }
    suspend fun cleanup() { try { target.cleanup() } finally { master.cleanup() } }
}

private fun assertAcknowledgedOriginal(pair: FreshWorkflowPair, entry: FreshWorkflowEffectEntry) {
    val data = pair.world.data
    when (entry.kind) {
        FreshWorkflowEffect.TARGET_PREPARE, FreshWorkflowEffect.TARGET_SDK_ACQUIRE, FreshWorkflowEffect.TARGET_RESPOND -> {
            val retained = assertNotNull(entry.targetRecord, "entered target call must have acknowledged original intent DATA")
            assertEquals(data.scope, retained.identity.scope)
            assertEquals(data.capabilities, retained.identity.capabilities)
            assertEquals(data.appId, retained.identity.appId)
            assertEquals(data.privateName, retained.identity.deviceName)
            assertTrue(retained.preparationAttempted)
            if (entry.kind == FreshWorkflowEffect.TARGET_PREPARE) {
                assertEquals(retained.identity.scope, entry.requestedScope)
                assertEquals(retained.identity.capabilities, entry.requestedCapabilities)
            } else {
                assertEquals(data.identityT.originalPreparationId, retained.originalPreparationId)
                assertEquals(data.identityT.target.admission.deviceId, retained.deviceId)
                assertEquals(data.identityT.target.keyId, retained.keyId)
                assertTrue(retained.sdkAttempted)
                if (entry.kind == FreshWorkflowEffect.TARGET_SDK_ACQUIRE) {
                    assertEquals(retained.identity.appId, entry.appId)
                    assertEquals(retained.identity.deviceName, entry.privateName)
                } else {
                    val original = assertNotNull(pair.world.original)
                    assertEquals(original.prepared.originalActionId, retained.responseActionId)
                    assertEquals(original.challenge.challengeDigest.value, retained.challengeDigest)
                    assertEquals(data.identityT.descriptorDigest.value, retained.sdkDigest)
                    assertBytes(original.challenge.copyWireBytes(), assertNotNull(entry.challengeBytes))
                }
            }
        }
        FreshWorkflowEffect.MASTER_PREPARE, FreshWorkflowEffect.MASTER_PROVE -> {
            val retained = assertNotNull(entry.masterRecord, "entered Master call must have acknowledged original intent DATA")
            assertEquals(pair.master.context, retained.identity.context)
            assertEquals(data.identityT.originalAcquisitionId, retained.identity.acquisitionId)
            assertEquals(data.identityT.originalPreparationId, retained.identity.originalPreparationId)
            assertEquals(data.identityT.target.admission.deviceId, retained.identity.deviceId)
            assertEquals(data.identityT.target.keyId, retained.identity.keyId)
            assertEquals(data.identityT.descriptorDigest.value, retained.identity.sdkDigest)
            assertTrue(retained.preparationAttempted)
            if (entry.kind == FreshWorkflowEffect.MASTER_PREPARE) {
                assertEquals(retained.identity.requestId, entry.requestId)
                assertBytes(data.identityT.copyWireBytes(), assertNotNull(entry.identityBytes))
            } else {
                val original = assertNotNull(pair.world.original)
                assertTrue(retained.proofAttempted)
                assertEquals(original.prepared.originalActionId, retained.actionId)
                assertEquals(original.challenge.challengeDigest.value, retained.challengeDigest)
                assertBytes(original.challenge.copyWireBytes(), assertNotNull(entry.challengeBytes))
                val p = assertNotNull(entry.possessionBytes)
                assertBytes(original.possession.copyWireBytes(), p)
                assertEquals(independentDigest(p), retained.possessionDigest)
            }
        }
    }
}

private fun assertNoSuccessfulResult(pair: FreshWorkflowPair, phase: FreshWorkflowEffect) {
    when (phase) {
        FreshWorkflowEffect.TARGET_PREPARE -> assertNull(pair.target.view().targetReference)
        FreshWorkflowEffect.TARGET_SDK_ACQUIRE -> assertNull(pair.target.view().identity)
        FreshWorkflowEffect.MASTER_PREPARE -> assertNull(pair.master.view().challenge)
        FreshWorkflowEffect.TARGET_RESPOND -> assertNull(pair.target.view().possession)
        FreshWorkflowEffect.MASTER_PROVE -> assertFalse(pair.master.view().nativePhase == LocalDevicePairingPhase.TARGET_PROVED)
    }
}

private fun assertNoUnrelatedCalls(pair: FreshWorkflowPair) {
    assertEquals(emptyList(), pair.world.forbiddenCalls(), "no head/admission/employee/second-Master/continuation boundary")
    listOf(pair.target, pair.master).forEach { harness ->
        val view = harness.coordinator.state.value
        assertNull(view.employeeEnrollment); assertNull(view.employeePolicy); assertNull(view.definitionReview)
    }
}
private fun assertBytes(expected: ByteArray, actual: ByteArray) {
    assertTrue(expected.contentEquals(actual), "complete canonical original bytes remain identical")
}
private fun assertRetentionUnchanged(expected: Map<String, ByteArray>, actual: Map<String, ByteArray>) {
    assertEquals(expected.keys, actual.keys)
    expected.forEach { (name, bytes) -> assertBytes(bytes, assertNotNull(actual[name])) }
}
private fun assertPrivateNameAbsent(name: String, bytes: ByteArray) {
    val canary = name.encodeToByteArray()
    assertFalse(bytes.asList().windowed(canary.size).any { it == canary.asList() },
        "private acquisition name is absent from public I/C/P")
}
private fun independentDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
