package com.ukkera.pos.app.gateway.setup.independent.retaineddeviceadmissionworkflowv1

import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissionOutcome
import com.ukkera.pos.app.setup.LocalSetupDeviceAdmissionStage
import com.ukkera.pos.app.setup.LocalSetupProblem
import com.ukkera.pos.security.LocalDevicePairingReference
import com.ukkera.pos.security.LocalProvisioningStatus
import com.ukkera.pos.types.DomainId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Assertion slices wired from the frozen retained-device-admission-workflow-v1 cases.
 * Names identify covered application controls, not complete native or platform cases.
 * The rich completed-outcome mismatches and unavailable concrete profiles that this
 * application seam cannot carry remain NOT RUN; no substituted generic refusal counts.
 */
class RetainedDeviceAdmissionWorkflowV1ControlledTest {
    @Test fun rdaw001ExactOriginalSelectionRefreshAndCachedApprovalHaveNoEffects() = runBlocking {
        listOf(false, true).forEach { cachedApproval ->
            withCase(configure = { if (cachedApproval) it.ledger.approvedBoth() }) { case ->
                val h = case.h
                assertFixedOriginalValues(h.data)
                assertEquals(h.data.a.deviceDisplayLabel, h.data.b.deviceDisplayLabel)
                assertFalse(h.data.a.reference.originalActionId == h.data.b.reference.originalActionId)
                h.inspect()
                h.assertOriginalReview()
                h.refresh()
                h.assertOriginalReview()
                h.inspect(h.data.b)
                h.assertOriginalReview(h.data.b)
                h.inspect()
                h.assertOriginalReview()
                assertEquals(if (cachedApproval) LocalSetupDeviceAdmissionStage.APPROVED
                    else LocalSetupDeviceAdmissionStage.HEAD_REVIEW, h.selected().stage)
                assertEquals(0, h.ledger.approvals().size)
                assertEquals(0, h.ledger.admissions().size)
                assertEquals(0, h.storage.effectWrites().size, "review and cached DATA must not retain an effect intent")
                h.assertSecretAbsent(case.secret)
                h.assertNoOtherApplicationEffects()
            }
        }
    }

    @Test fun rdaw002EmptyAndMissingRetainedOriginalsCannotOfferOrAdmitFreshWork() = runBlocking {
        listOf("empty_retained_list", "selected_original_missing").forEach { variant ->
            withCase(configure = { h ->
                h.ledger.references = if (variant == "empty_retained_list") emptyList() else listOf(h.data.b.reference)
                h.ledger.actions = emptyMap()
                h.ledger.reviewUnavailable = setOf(h.data.a.reference.originalActionId, h.data.b.reference.originalActionId)
            }) { case ->
                val h = case.h
                h.coordinator.inspectDeviceAdmission(h.data.a.reference.originalActionId)
                h.idle()
                requestUnavailableActions(case)
                h.refresh()
                assertNoFreshWork(case)
                assertEquals(0, h.storage.effectWrites().size)
                assertFalse(h.coordinator.state.value.deviceAdmissions?.originals.orEmpty().any {
                    it.originalActionId == h.data.a.reference.originalActionId
                })
            }
        }
    }

    @Test fun rdaw002UnprovedAndLegacyV1RetainedDataDoNotBecomeEligibleSdkBoundOriginals() = runBlocking {
        listOf("target_not_proved", "legacy_v1_without_sdk_association").forEach { variant ->
            withCase(configure = { h ->
                val action = if (variant == "target_not_proved") h.data.a.preparedAction else h.data.a.legacyAction
                val reference = if (variant == "target_not_proved") h.data.a.preparedReference else h.data.a.legacyReference
                h.ledger.references = listOf(reference)
                h.ledger.actions = mapOf(reference.originalActionId to action)
            }) { case ->
                val h = case.h
                h.inspect()
                assertEquals(LocalSetupDeviceAdmissionStage.TARGET_NOT_READY, h.selected().stage)
                assertEquals(h.data.a.reference.originalActionId, h.selected().originalActionId)
                requestUnavailableActions(case)
                h.refresh()
                assertNoFreshWork(case)
                assertTrue(h.storage.effectWrites().isEmpty())
            }
        }
    }

    @Test fun rdaw002UnavailableRetainedProducerDoesNotRecoverEligibilityFromAvailablePublicData() = runBlocking {
        listOf("list_unavailable", "selected_review_unavailable").forEach { variant ->
            withCase(configure = { h ->
                // Exact typed A/B public DATA remain in the fixture, but no eligible producer answer is supplied.
                h.ledger.listUnavailable = variant == "list_unavailable"
                h.ledger.reviewUnavailable = setOf(h.data.a.reference.originalActionId)
            }) { case ->
                val h = case.h
                h.coordinator.inspectDeviceAdmission(h.data.a.reference.originalActionId)
                h.idle()
                requestUnavailableActions(case)
                h.refresh()
                assertNoFreshWork(case)
                assertTrue(h.storage.effectWrites().isEmpty())
                assertNotNull(h.coordinator.state.value.problem)
            }
        }
    }

    @Test fun rdaw002FourRepresentableOneFieldReferenceReplacementsRemainUnavailable() = runBlocking {
        listOf("targetDeviceId", "nativeKeyId", "pairingActionId", "admissionRequestId").forEach { field ->
            withCase(configure = { h ->
                val a = h.data.a.reference; val b = h.data.b.reference
                val mismatched = when (field) {
                    "targetDeviceId" -> a.copy(deviceId = b.deviceId)
                    "nativeKeyId" -> a.copy(keyId = b.keyId)
                    "pairingActionId" -> a.copy(originalActionId = b.originalActionId)
                    else -> a.copy(originalRequestId = b.originalRequestId)
                }
                assertEquals(1, referenceDifferenceCount(a, mismatched), "use only the frozen field replacement")
                h.ledger.references = listOf(mismatched)
                h.ledger.actions = mapOf(a.originalActionId to h.data.a.action)
                h.ledger.reviewUnavailable = setOf(mismatched.originalActionId)
            }) { case ->
                val h = case.h
                val mismatched = h.ledger.references.single()
                h.coordinator.inspectDeviceAdmission(mismatched.originalActionId)
                h.idle()
                requestUnavailableActions(case)
                h.refresh()
                assertNoFreshWork(case)
                assertTrue(h.storage.snapshot().isEmpty(), "unavailable mismatched reference cannot retain an effect-capable original")
            }
        }
    }

    @Test fun rdaw003ApprovalWaitsForAcknowledgedExactOriginalHeadIntent() = runBlocking {
        withCase { case ->
            var h = case.h
            h.inspect()
            h.assertOriginalReview()
            val gate = RetainedDeviceAdmissionWorkflowV1StorageGate("head")
            h.storage.gate = gate
            val caller = approveInput(case)
            gate.awaitEntry()
            assertTrue(caller.all { it == '\u0000' }, "caller buffer is cleared on ceremony input transfer")
            assertFalse(gate.acknowledged)
            assertEquals(0, h.ledger.approvals().size, "approval must not enter before retention acknowledgement")
            assertEquals(0, h.ledger.admissions().size)
            assertFalse(h.storage.snapshot().containsKey(file(h, "head")))
            assertFalse(case.secret.presentIn(h.coordinator.state.value))
            gate.release()
            h.idle()
            assertTrue(gate.acknowledged)
            h.assertAcknowledgedOriginalEntry(h.ledger.approvals().single(), "head")
            assertEquals(LocalSetupDeviceAdmissionStage.APPROVED, h.selected().stage)
            assertEquals(0, h.ledger.admissions().size)
            assertNull(h.selected().eventId)
            h.assertSecretAbsent(case.secret)
            val originalEvidence = h.storage.snapshot()
            h.refresh()
            case.restore()
            h = case.h
            h.assertOriginalReview()
            assertEquals(1, h.ledger.approvals().size)
            assertEquals(0, h.ledger.admissions().size)
            assertRetainedEvidenceUnchanged(originalEvidence, h.storage.snapshot())
            h.assertSecretAbsent(case.secret)
        }
    }

    @Test fun rdaw004EveryApprovalIntentFailureBeforeAcknowledgementPreventsProducerEntry() = runBlocking {
        RetainedDeviceAdmissionWorkflowV1StorageFailure.entries.forEach { failure ->
            withCase { case ->
                var h = case.h
                h.inspect()
                val originalEvidence = h.storage.snapshot()
                val oldReview = h.selected().reviewId
                val gate = RetainedDeviceAdmissionWorkflowV1StorageGate("head", failure)
                h.storage.gate = gate
                val caller = approveInput(case)
                gate.awaitEntry()
                assertEquals(0, h.ledger.approvals().size)
                assertFalse(gate.acknowledged)
                gate.release()
                h.idle()
                assertTrue(caller.all { it == '\u0000' })
                assertNoFreshWork(case)
                h.assertUnresolvedA()
                assertNotNull(h.coordinator.state.value.problem, "the failed acknowledgement must be visible safely")
                assertRetainedEvidenceUnchanged(originalEvidence, h.storage.snapshot())
                h.assertSecretAbsent(case.secret)
                h.backAndReenter()
                case.restore()
                h = case.h
                h.coordinator.approveDeviceAdmission(oldReview, case.secret.input())
                h.idle()
                assertNoFreshWork(case)
                h.assertOriginalReview()
                assertRetainedEvidenceUnchanged(originalEvidence, h.storage.snapshot())
                h.assertSecretAbsent(case.secret)
            }
        }
    }

    @Test fun rdaw005LostThrownAndCancelledApprovalRepliesDoNotResignOrAdmit() = runBlocking {
        listOf(RetainedDeviceAdmissionWorkflowV1Reply.LOST_REPLY,
            RetainedDeviceAdmissionWorkflowV1Reply.THROW_AFTER_ENTRY,
            RetainedDeviceAdmissionWorkflowV1Reply.CANCEL_AFTER_ENTRY).forEach { reply ->
            withCase(configure = { it.ledger.approvalReply = reply; it.ledger.recoveryUnavailable = true }) { case ->
                var h = case.h
                h.inspect()
                approveInput(case)
                h.idle()
                assertEquals(1, h.ledger.approvals().size)
                assertEquals(0, h.ledger.admissions().size)
                assertEquals(LocalSetupDeviceAdmissionStage.APPROVAL_UNRESOLVED, h.selected().stage)
                h.assertAcknowledgedOriginalEntry(h.ledger.approvals().single(), "head")
                h.assertSecretAbsent(case.secret)
                val evidence = h.storage.snapshot()
                requestUnavailableActions(case)
                h.refresh()
                h.backAndReenter()
                lockAndResume(case)
                case.restore()
                h = case.h
                h.assertUnresolvedA()
                assertEquals(LocalSetupDeviceAdmissionStage.APPROVAL_UNRESOLVED, h.selected().stage)
                assertEquals(1, h.ledger.approvals().size)
                assertEquals(0, h.ledger.admissions().size)
                assertRetainedEvidenceUnchanged(evidence, h.storage.snapshot())
                h.assertSecretAbsent(case.secret)
            }
        }
    }

    @Test fun rdaw006AdmissionIsSeparateAndItsAppliedReplyIsNotOriginalCompletion() = runBlocking {
        withCase(configure = { it.ledger.approvedA(); it.ledger.admissionReply = RetainedDeviceAdmissionWorkflowV1Reply.PUBLIC_APPLIED_DATA }) { case ->
            var h = case.h
            h.inspect()
            h.refresh()
            case.restore()
            h = case.h
            assertEquals(LocalSetupDeviceAdmissionStage.APPROVED, h.selected().stage)
            assertEquals(0, h.ledger.approvals().size)
            assertEquals(0, h.ledger.admissions().size, "cached approval, review and restoration do not admit")
            val storageGate = RetainedDeviceAdmissionWorkflowV1StorageGate("admit")
            val lookupGate = RetainedDeviceAdmissionWorkflowV1PortGate()
            h.storage.gate = storageGate
            h.ledger.lookupGate = lookupGate
            h.coordinator.admitApprovedDevice(h.selected().reviewId)
            storageGate.awaitEntry()
            assertFalse(storageGate.acknowledged)
            assertEquals(0, h.ledger.admissions().size, "admission must not enter before its own intent acknowledgement")
            assertFalse(h.storage.snapshot().containsKey(file(h, "admit")))
            storageGate.release()
            lookupGate.awaitEntry()
            assertTrue(storageGate.acknowledged)
            assertEquals(1, h.ledger.admissions().size)
            assertEquals(0, h.ledger.approvals().size)
            h.assertAcknowledgedOriginalEntry(h.ledger.admissions().single(), "admit")
            assertFalse(h.storage.snapshot().containsKey(file(h, "head")), "approval DATA is not a new head ceremony")
            h.assertUnresolvedA()
            assertEquals(LocalSetupDeviceAdmissionStage.ADMISSION_UNRESOLVED, h.selected().stage)
            assertFalse(h.storage.snapshot().containsKey(file(h, "applied")), "dispatch DATA cannot retain a success terminal")
            h.assertOnlyOriginalALookups()
            lookupGate.release()
            h.idle()
            h.assertUnresolvedA()
            h.ledger.admittedA()
            h.lookup()
            assertEquals(LocalSetupDeviceAdmissionStage.APPLIED, h.selected().stage)
            assertEquals(h.data.a.reference.originalRequestId, h.selected().eventId)
            assertEquals(1, h.ledger.admissions().size)
            assertEquals(0, h.ledger.approvals().size)
            h.assertOnlyOriginalALookups()
            h.assertNoOtherApplicationEffects()
        }
    }

    @Test fun rdaw007EveryAdmissionIntentFailureBeforeAcknowledgementPreventsEntry() = runBlocking {
        RetainedDeviceAdmissionWorkflowV1StorageFailure.entries.forEach { failure ->
            withCase(configure = { it.ledger.approvedA() }) { case ->
                var h = case.h
                h.inspect()
                val evidence = h.storage.snapshot()
                val oldReview = h.selected().reviewId
                val gate = RetainedDeviceAdmissionWorkflowV1StorageGate("admit", failure)
                h.storage.gate = gate
                h.coordinator.admitApprovedDevice(oldReview)
                gate.awaitEntry()
                assertFalse(gate.acknowledged)
                assertEquals(0, h.ledger.admissions().size)
                gate.release()
                h.idle()
                assertNoFreshWork(case)
                h.assertUnresolvedA()
                assertNotNull(h.coordinator.state.value.problem)
                assertRetainedEvidenceUnchanged(evidence, h.storage.snapshot())
                h.refresh()
                h.backAndReenter()
                case.restore()
                h = case.h
                h.coordinator.admitApprovedDevice(oldReview)
                h.idle()
                assertNoFreshWork(case)
                h.assertOriginalReview()
                assertRetainedEvidenceUnchanged(evidence, h.storage.snapshot())
                h.assertSecretAbsent(case.secret)
            }
        }
    }

    @Test fun rdaw008AllSevenAdmissionReplyVariantsRecoverOnlyTheAttemptedOriginal() = runBlocking {
        val replies = listOf(
            RetainedDeviceAdmissionWorkflowV1Reply.UNCERTAIN,
            RetainedDeviceAdmissionWorkflowV1Reply.UNAVAILABLE,
            RetainedDeviceAdmissionWorkflowV1Reply.DENIED,
            RetainedDeviceAdmissionWorkflowV1Reply.LOST_REPLY,
            RetainedDeviceAdmissionWorkflowV1Reply.THROW_AFTER_ENTRY,
            RetainedDeviceAdmissionWorkflowV1Reply.CANCEL_AFTER_ENTRY,
            RetainedDeviceAdmissionWorkflowV1Reply.PUBLIC_APPLIED_DATA,
        )
        replies.forEach { reply ->
            withCase(configure = { it.ledger.approvedA(); it.ledger.admissionReply = reply }) { case ->
                var h = case.h
                h.inspect()
                val oldReview = h.selected().reviewId
                h.coordinator.admitApprovedDevice(oldReview)
                h.idle()
                assertEquals(1, h.ledger.admissions().size)
                assertEquals(0, h.ledger.approvals().size)
                h.assertAcknowledgedOriginalEntry(h.ledger.admissions().single(), "admit")
                h.assertUnresolvedA()
                val evidence = h.storage.snapshot()
                h.ledger.outcome(RetainedDeviceAdmissionWorkflowV1Outcome.APPARENT_ABSENCE_ONLY)
                h.lookup()
                h.coordinator.admitApprovedDevice(oldReview)
                h.idle()
                h.refresh()
                h.backAndReenter()
                lockAndResume(case)
                case.restore()
                h = case.h
                h.lookup()
                requestUnavailableActions(case)
                h.assertUnresolvedA()
                h.assertOnlyOriginalALookups()
                assertEquals(1, h.ledger.admissions().size, "attempted admission must never redispatch after reply failure/reentry")
                assertEquals(0, h.ledger.approvals().size)
                assertRetainedEvidenceUnchanged(evidence, h.storage.snapshot())
                h.assertSecretAbsent(case.secret)
            }
        }
    }

    @Test fun rdaw009HeldApprovalRejectsConcurrentAndReentrantActionsWithoutQueueingEffects() = runBlocking {
        listOf(RetainedDeviceAdmissionWorkflowV1Reply.APPROVED, RetainedDeviceAdmissionWorkflowV1Reply.LOST_REPLY).forEach { reply ->
            withCase(configure = { it.ledger.approvalReply = reply }) { case ->
                val h = case.h
                h.inspect()
                val oldReview = h.selected().reviewId
                val pending = RetainedDeviceAdmissionWorkflowV1PortGate()
                val reentrantBuffers = mutableListOf<CharArray>()
                h.ledger.approvalGate = pending
                h.ledger.approvalOnEntry = { reentrantBuffers += busyActionBurst(case, oldReview) }
                approveInput(case)
                pending.awaitEntry()
                val concurrentBuffers = busyActionBurst(case, oldReview)
                assertEquals(LocalSetupProblem.BUSY, h.coordinator.state.value.problem)
                assertEquals(1, h.ledger.approvals().size)
                assertEquals(0, h.ledger.admissions().size)
                assertTrue((reentrantBuffers + concurrentBuffers).all { chars -> chars.all { it == '\u0000' } })
                assertFalse(case.secret.presentIn(h.coordinator.state.value))
                pending.release()
                h.idle()
                h.refresh()
                assertEquals(1, h.ledger.approvals().size, "busy inputs must not become deferred approval jobs")
                assertEquals(0, h.ledger.admissions().size, "busy or refresh inputs must not become deferred admission jobs")
                h.assertOriginalReview()
                h.assertSecretAbsent(case.secret)
            }
        }
    }

    @Test fun rdaw009HeldAdmissionRejectsConcurrentAndReentrantActionsWithoutRedispatch() = runBlocking {
        listOf(RetainedDeviceAdmissionWorkflowV1Reply.PUBLIC_APPLIED_DATA, RetainedDeviceAdmissionWorkflowV1Reply.LOST_REPLY).forEach { reply ->
            withCase(configure = { it.ledger.approvedA(); it.ledger.admissionReply = reply }) { case ->
                val h = case.h
                h.inspect()
                val oldReview = h.selected().reviewId
                val pending = RetainedDeviceAdmissionWorkflowV1PortGate()
                val reentrantBuffers = mutableListOf<CharArray>()
                h.ledger.admissionGate = pending
                h.ledger.admissionOnEntry = { reentrantBuffers += busyActionBurst(case, oldReview) }
                h.coordinator.admitApprovedDevice(oldReview)
                pending.awaitEntry()
                val concurrentBuffers = busyActionBurst(case, oldReview)
                assertEquals(1, h.ledger.admissions().size)
                assertEquals(0, h.ledger.approvals().size)
                assertTrue((reentrantBuffers + concurrentBuffers).all { chars -> chars.all { it == '\u0000' } })
                pending.release()
                h.idle()
                h.lookup()
                h.refresh()
                assertEquals(1, h.ledger.admissions().size)
                assertEquals(0, h.ledger.approvals().size)
                h.assertUnresolvedA()
                h.assertOnlyOriginalALookups()
                h.assertSecretAbsent(case.secret)
            }
        }
    }

    @Test fun rdaw010RestorationKeepsAttemptedAWhenOnlySameLabelBIsListedWithCachedApproval() = runBlocking {
        withCase(configure = { h -> h.seedAttempted(); h.ledger.latestB() }) { case ->
            var h = case.h
            val evidence = h.storage.snapshot()
            assertEquals(listOf(h.data.b.approvedReference), assertNotNull(h.coordinator.state.value.deviceAdmissions).originals)
            h.assertUnresolvedA()
            assertTrue(h.data.a.action.challenge.rootFingerprint == h.selected().rootFingerprint,
                "latest B DATA must not replace the captured A root fingerprint")
            assertTrue(h.data.a.action.challenge.challengeDigest.value == h.selected().challengeDigest,
                "latest B DATA must not replace the captured A challenge digest")
            h.coordinator.inspectDeviceAdmission(h.data.b.reference.originalActionId)
            h.idle()
            requestUnavailableActions(case)
            h.assertUnresolvedA()
            case.restore()
            h = case.h
            h.lookup()
            h.assertOnlyOriginalALookups()
            h.assertUnresolvedA()
            assertEquals(0, h.ledger.approvals().size)
            assertEquals(0, h.ledger.admissions().size)
            assertEquals(case.initialEffectWrites, h.storage.effectWrites().size)
            assertFalse(h.ledger.recoveries().contains(h.data.b.reference.originalActionId), "B approval DATA cannot resolve A")
            assertRetainedEvidenceUnchanged(evidence, h.storage.snapshot())
            h.assertSecretAbsent(case.secret)
        }
    }

    @Test fun rdaw011SupportedJournalLabelsWithMissingUnavailableOrConflictingLookupNeverSettleA() = runBlocking {
        val labels = listOf("APPARENT_SUCCESS_TERMINAL", "APPARENT_NO_EFFECT_TERMINAL", "PENDING")
        val unresolved = listOf(RetainedDeviceAdmissionWorkflowV1Outcome.UNAVAILABLE,
            RetainedDeviceAdmissionWorkflowV1Outcome.MISSING_WITNESS_OR_HISTORY,
            RetainedDeviceAdmissionWorkflowV1Outcome.CONFLICTING,
            RetainedDeviceAdmissionWorkflowV1Outcome.APPARENT_ABSENCE_ONLY)
        labels.forEach { label -> unresolved.forEach { lookup ->
            withCase(configure = { h ->
                val record = h.seedAttempted()
                when (label) {
                    "APPARENT_SUCCESS_TERMINAL" -> h.intents.retainApplied(record.identity, h.data.a.reference.originalRequestId)
                    "APPARENT_NO_EFFECT_TERMINAL" -> h.intents.retainKnownNoEffect(record.identity)
                }
                h.ledger.approvedA()
                h.ledger.outcome(lookup)
            }) { case ->
                var h = case.h
                val evidence = h.storage.snapshot()
                h.assertUnresolvedA()
                h.lookup()
                h.refresh()
                requestUnavailableActions(case)
                case.restore()
                h = case.h
                h.assertUnresolvedA()
                h.assertOnlyOriginalALookups()
                assertNoFreshWork(case)
                assertRetainedEvidenceUnchanged(evidence, h.storage.snapshot())
                h.assertSecretAbsent(case.secret)
            }
        } }
    }

    // RDAW-011 terminal-label uncertainty with the RDAW-010/016 no-fallback invariant:
    // offered same-label B cannot replace retained unresolved A or enable fresh work.
    @Test fun rdaw011TerminalAWithUnknownLookupCannotYieldToOfferedApprovedB() = runBlocking {
        listOf("APPARENT_SUCCESS_TERMINAL", "APPARENT_NO_EFFECT_TERMINAL").forEach { label ->
            withCase(configure = { h ->
                val original = h.seedAttempted()
                when (label) {
                    "APPARENT_SUCCESS_TERMINAL" -> h.intents.retainApplied(original.identity, h.data.a.reference.originalRequestId)
                    "APPARENT_NO_EFFECT_TERMINAL" -> h.intents.retainKnownNoEffect(original.identity)
                }
                h.ledger.latestB()
                h.ledger.outcome(RetainedDeviceAdmissionWorkflowV1Outcome.UNAVAILABLE)
            }) { case ->
                var h = case.h
                val evidence = h.storage.snapshot()
                assertTrue(assertNotNull(h.coordinator.state.value.deviceAdmissions).originals == listOf(h.data.b.approvedReference),
                    "only approved same-label B is offered as retained list DATA")
                h.assertUnresolvedA()
                h.lookup()
                h.assertUnresolvedA()
                h.coordinator.inspectDeviceAdmission(h.data.b.reference.originalActionId)
                h.idle()
                h.assertUnresolvedA()
                requestUnavailableActions(case)
                h.assertOnlyOriginalALookups()
                assertNoFreshWork(case)
                assertEquals(case.initialEffectWrites, h.storage.effectWrites().size,
                    "unknown saved terminal A must not enable a new effect intent for B")
                assertRetainedEvidenceUnchanged(evidence, h.storage.snapshot())
                h.refresh()
                h.backAndReenter()
                case.restore()
                h = case.h
                h.assertUnresolvedA()
                h.coordinator.inspectDeviceAdmission(h.data.b.reference.originalActionId)
                h.idle()
                h.assertUnresolvedA()
                requestUnavailableActions(case)
                h.lookup()
                h.assertUnresolvedA()
                h.assertOnlyOriginalALookups()
                assertNoFreshWork(case)
                assertEquals(case.initialEffectWrites, h.storage.effectWrites().size,
                    "restoring unknown saved terminal A must not enable a new effect intent for B")
                assertFalse(h.ledger.recoveries().contains(h.data.b.reference.originalActionId),
                    "offered B approval DATA must not resolve unknown saved terminal A")
                assertRetainedEvidenceUnchanged(evidence, h.storage.snapshot())
                h.assertSecretAbsent(case.secret)
            }
        }
    }

    @Test fun rdaw011PreviouslyDeliveredSuccessDataPlusSuccessJournalLabelStillNeedsOriginalLookup() = runBlocking {
        withCase(configure = { it.ledger.approvedA(); it.ledger.admissionReply = RetainedDeviceAdmissionWorkflowV1Reply.PUBLIC_APPLIED_DATA }) { case ->
            var h = case.h
            h.inspect()
            h.coordinator.admitApprovedDevice(h.selected().reviewId)
            h.idle()
            h.assertUnresolvedA()
            val original = h.intents.records(h.context).single()
            h.intents.retainApplied(original.identity, h.data.a.reference.originalRequestId)
            val evidence = h.storage.snapshot()
            val originalEntries = h.ledger.admissions().size
            case.restore()
            h = case.h
            h.assertUnresolvedA()
            h.lookup()
            h.assertUnresolvedA()
            assertEquals(originalEntries, h.ledger.admissions().size, "restoration cut must have no new admission entry")
            assertEquals(0, h.ledger.approvals().size)
            h.assertOnlyOriginalALookups()
            assertRetainedEvidenceUnchanged(evidence, h.storage.snapshot())
        }
    }

    @Test fun rdaw012MatchingOriginalLookupResolvesAdmittedOrEitherSettledNoEffectFactWithoutNewEffects() = runBlocking {
        listOf("confirmedAdmittedA", "actual_original_unused_no_child_settlement_confirmed",
            "actual_original_rollback_settlement_confirmed").forEach { fact ->
            withCase(configure = { h ->
                h.seedAttempted()
                h.ledger.approvedA()
                when (fact) {
                    "confirmedAdmittedA" -> h.ledger.admittedA()
                    "actual_original_unused_no_child_settlement_confirmed" ->
                        h.ledger.outcome(RetainedDeviceAdmissionWorkflowV1Outcome.CONFIRMED_UNUSED_NO_CHILD_SETTLEMENT_A)
                    else -> h.ledger.outcome(RetainedDeviceAdmissionWorkflowV1Outcome.CONFIRMED_ROLLBACK_SETTLEMENT_A)
                }
            }) { case ->
                var h = case.h
                val expected = if (fact == "confirmedAdmittedA") LocalSetupDeviceAdmissionStage.APPLIED
                    else LocalSetupDeviceAdmissionStage.KNOWN_NO_EFFECT
                assertEquals(expected, h.selected().stage)
                assertEquals(if (fact == "confirmedAdmittedA") h.data.a.reference.originalRequestId else null, h.selected().eventId)
                val oldReview = h.selected().reviewId
                h.inspect()
                h.refresh()
                h.coordinator.admitApprovedDevice(oldReview)
                h.idle()
                h.backAndReenter()
                h.inspect()
                case.restore()
                h = case.h
                h.coordinator.admitApprovedDevice(h.selected().reviewId)
                h.idle()
                h.lookup()
                assertEquals(expected, h.selected().stage, "known no effect must not reopen an admission button")
                assertNoFreshWork(case)
                h.assertOnlyOriginalALookups()
                assertEquals(case.initialEffectWrites, h.storage.effectWrites().size)
                h.assertNoOtherApplicationEffects()
            }
        }
    }

    @Test fun rdaw013ForeignAppliedEventCannotReplaceAttemptedOriginalA() = runBlocking {
        withCase(configure = { h ->
            h.seedAttempted()
            h.ledger.approvedA()
            h.ledger.lookupFact = "apparently_completed_data_for_b_request"
            h.ledger.lookupStatus = LocalProvisioningStatus.Applied(h.data.b.reference.originalRequestId)
        }) { case ->
            var h = case.h
            val evidence = h.storage.snapshot()
            h.assertUnresolvedA()
            h.lookup()
            h.refresh()
            case.restore()
            h = case.h
            h.assertUnresolvedA()
            h.assertOnlyOriginalALookups()
            assertNoFreshWork(case)
            assertRetainedEvidenceUnchanged(evidence, h.storage.snapshot())
        }
    }

    @Test fun rdaw013MissingAAndSameLabelCompletedBDoNotSwitchRecoveryIdentity() = runBlocking {
        withCase(configure = { h ->
            h.seedAttempted()
            h.ledger.latestB(missingA = true)
            h.ledger.lookupFact = "apparently_completed_data_for_b_request"
            h.ledger.lookupStatus = LocalProvisioningStatus.Applied(h.data.b.reference.originalRequestId)
        }) { case ->
            val h = case.h
            val evidence = h.storage.snapshot()
            h.assertUnresolvedA()
            h.coordinator.inspectDeviceAdmission(h.data.b.reference.originalActionId)
            h.idle()
            h.lookup()
            h.assertUnresolvedA()
            h.assertOnlyOriginalALookups()
            assertNoFreshWork(case)
            assertRetainedEvidenceUnchanged(evidence, h.storage.snapshot())
        }
    }

    @Test fun rdaw014EachControlledCurrentProducerRefusalRemainsUnresolvedUntilOriginalSettledLookup() = runBlocking {
        listOf("current_head_revoked", "original_expired", "wrong_master_or_owner", "original_definition_changed",
            "protected_census_changed", "original_already_used").forEach { refusal ->
            withCase(configure = { h ->
                h.ledger.approvedA()
                h.ledger.admissionRefusalFact = refusal
                h.ledger.admissionReply = RetainedDeviceAdmissionWorkflowV1Reply.DENIED
            }) { case ->
                val h = case.h
                h.inspect()
                assertEquals(LocalSetupDeviceAdmissionStage.APPROVED, h.selected().stage)
                h.coordinator.admitApprovedDevice(h.selected().reviewId)
                h.idle()
                assertEquals(1, h.ledger.admissions().size)
                assertEquals(0, h.ledger.approvals().size)
                h.assertAcknowledgedOriginalEntry(h.ledger.admissions().single(), "admit")
                h.assertUnresolvedA()
                assertEquals(LocalSetupDeviceAdmissionOutcome.DENIED, h.selected().outcome)
                assertTrue(h.ledger.safeDiagnostics().contains("controlled-refusal:$refusal"))
                h.lookup()
                h.assertUnresolvedA()
                h.ledger.outcome(RetainedDeviceAdmissionWorkflowV1Outcome.CONFIRMED_ROLLBACK_SETTLEMENT_A)
                h.lookup()
                assertEquals(LocalSetupDeviceAdmissionStage.KNOWN_NO_EFFECT, h.selected().stage)
                requestUnavailableActions(case, expectUnresolved = false)
                assertEquals(1, h.ledger.admissions().size)
                assertEquals(0, h.ledger.approvals().size)
                assertEquals(LocalSetupDeviceAdmissionStage.KNOWN_NO_EFFECT, h.selected().stage)
                h.assertOnlyOriginalALookups()
                h.assertNoOtherApplicationEffects()
            }
        }
    }

    @Test fun rdaw015EveryCeremonyExitClearsSuppliedBuffersAndRetainsOriginalAcrossLockBackAndRestoration() = runBlocking {
        listOf(RetainedDeviceAdmissionWorkflowV1Reply.APPROVED,
            RetainedDeviceAdmissionWorkflowV1Reply.UNAVAILABLE,
            RetainedDeviceAdmissionWorkflowV1Reply.THROW_AFTER_ENTRY,
            RetainedDeviceAdmissionWorkflowV1Reply.CANCEL_AFTER_ENTRY).forEach { reply ->
            withCase(configure = { it.ledger.approvalReply = reply; it.ledger.recoveryUnavailable = reply != RetainedDeviceAdmissionWorkflowV1Reply.APPROVED }) { case ->
                var h = case.h
                h.inspect()
                val caller = approveInput(case)
                h.idle()
                assertTrue(caller.all { it == '\u0000' })
                assertEquals(1, h.ledger.approvals().size)
                assertEquals(0, h.ledger.admissions().size)
                h.assertSecretAbsent(case.secret)
                val evidence = h.storage.snapshot()
                lockAndResume(case)
                h.backAndReenter()
                case.restore()
                h = case.h
                h.assertOriginalReview()
                assertEquals(1, h.ledger.approvals().size)
                assertEquals(0, h.ledger.admissions().size)
                h.assertSecretAbsent(case.secret)
                assertRetainedEvidenceUnchanged(evidence, h.storage.snapshot())
                h.assertNoOtherApplicationEffects()
            }
        }
    }

    @Test fun rdaw016ConflictingUnsupportedAndFailedReadEvidenceIsNeverRepairedToEnableB() = runBlocking {
        listOf("original_a_conflicts_with_saved_intent_context", "recognized_original_a_with_unsupported_retained_data",
            "recognized_original_a_with_failed_intent_read").forEach { variant ->
            withCase(configure = { h ->
                h.seedAttempted()
                h.ledger.latestB()
                val name = file(h, "intent")
                if (variant == "recognized_original_a_with_failed_intent_read") h.storage.failedReadName = name
                else {
                    val bytes = assertNotNull(h.storage.snapshot()[name])
                    val fields = bytes.decodeToString().split('\n').dropLast(2).toMutableList()
                    bytes.fill(0)
                    if (variant == "original_a_conflicts_with_saved_intent_context")
                        fields[2] = "00000000-0000-4000-8000-000000000206"
                    else fields[0] = "ukkera.original-device-admission-ui-intent/v999"
                    val changed = controlledJournalBytes(fields)
                    try { h.storage.replaceFixtureBytes(name, changed) } finally { changed.fill(0) }
                }
            }) { case ->
                var h = case.h
                val evidence = h.storage.snapshot()
                assertNotNull(h.coordinator.state.value.problem)
                assertFalse(h.coordinator.state.value.deviceAdmission?.stage in RetainedDeviceAdmissionWorkflowV1Harness.TERMINAL_STAGES)
                h.coordinator.inspectDeviceAdmission(h.data.b.reference.originalActionId)
                h.idle()
                requestUnavailableActions(case)
                h.refresh()
                h.backAndReenter()
                lockAndResume(case)
                case.restore(selectA = false)
                h = case.h
                requestUnavailableActions(case)
                assertNoFreshWork(case)
                assertEquals(case.initialEffectWrites, h.storage.effectWrites().size)
                assertFalse(h.coordinator.state.value.deviceAdmission?.stage in RetainedDeviceAdmissionWorkflowV1Harness.TERMINAL_STAGES)
                assertRetainedEvidenceUnchanged(evidence, h.storage.snapshot())
                h.assertSecretAbsent(case.secret)
                assertTrue(evidence.keys.any { it.startsWith(h.data.a.reference.originalActionId.value + ".") },
                    "recognizable original evidence must survive even when its content cannot be used")
            }
        }
    }

    private class Case(var h: RetainedDeviceAdmissionWorkflowV1Harness) {
        val secret = RetainedDeviceAdmissionWorkflowV1Secret.create()
        val initialEffectWrites = h.storage.effectWrites().size
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val observations = mutableListOf<kotlinx.coroutines.Job>()
        private val secretPublished = AtomicBoolean(false)
        init { observe(h) }
        private fun observe(harness: RetainedDeviceAdmissionWorkflowV1Harness) {
            observations += scope.launch(start = CoroutineStart.UNDISPATCHED) {
                harness.coordinator.state.collect { if (secret.presentIn(it)) secretPublished.set(true) }
            }
        }
        suspend fun restore(selectA: Boolean = true) {
            h = h.recreate()
            observe(h)
            if (selectA) h.inspect()
        }
        fun assertNoSecretPublished() {
            assertFalse(secretPublished.get(), "secret must never appear in observed public workflow DATA")
        }
        suspend fun close() {
            try { h.close() }
            finally {
                observations.forEach { it.cancelAndJoin() }
                scope.cancel()
                secret.clear()
            }
        }
    }

    private suspend fun withCase(configure: (RetainedDeviceAdmissionWorkflowV1Harness) -> Unit = {},
        body: suspend (Case) -> Unit) {
        val harness = RetainedDeviceAdmissionWorkflowV1Harness()
        var case: Case? = null
        try {
            configure(harness)
            val active = Case(harness).also { case = it }
            harness.open()
            body(active)
            active.assertNoSecretPublished()
            active.h.assertNoOtherApplicationEffects()
        } finally {
            val active = case
            if (active == null) harness.close() else active.close()
        }
    }

    private fun approveInput(case: Case): CharArray {
        val input = case.secret.input()
        case.h.coordinator.approveDeviceAdmission(case.h.selected().reviewId, input)
        assertTrue(input.all { it == '\u0000' }, "transferred caller input must be cleared without secret diagnostics")
        return input
    }

    private suspend fun requestUnavailableActions(case: Case, expectUnresolved: Boolean = true) {
        val h = case.h
        val id = h.coordinator.state.value.deviceAdmission?.reviewId ?: DomainId.random()
        val input = case.secret.input()
        h.coordinator.approveDeviceAdmission(id, input)
        h.idle()
        assertTrue(input.all { it == '\u0000' })
        h.coordinator.admitApprovedDevice(id)
        h.idle()
        if (expectUnresolved)
            assertFalse(h.coordinator.state.value.deviceAdmission?.stage in RetainedDeviceAdmissionWorkflowV1Harness.TERMINAL_STAGES,
                "unsupported/direct action cannot infer completion")
    }

    private fun busyActionBurst(case: Case, originalReview: DomainId): List<CharArray> {
        val h = case.h
        val buffers = (1..3).map {
            case.secret.input().also { input -> h.coordinator.approveDeviceAdmission(originalReview, input) }
        }
        repeat(3) { h.coordinator.admitApprovedDevice(originalReview) }
        h.coordinator.openDeviceAdmissions()
        h.coordinator.closeDeviceAdmissions()
        h.coordinator.openDeviceAdmissions()
        return buffers
    }

    private suspend fun lockAndResume(case: Case) {
        val h = case.h
        val oldId = h.coordinator.state.value.deviceAdmission?.reviewId ?: DomainId.random()
        h.coordinator.lockPresentation()
        assertTrue(h.coordinator.state.value.locked)
        assertTrue(h.coordinator.state.value.deviceAdmission == null, "locking must remove the public device-admission review DATA")
        val input = case.secret.input()
        h.coordinator.approveDeviceAdmission(oldId, input)
        h.coordinator.admitApprovedDevice(oldId)
        h.coordinator.openDeviceAdmissions()
        assertTrue(input.all { it == '\u0000' })
        h.coordinator.resumePresentation()
        h.idle()
        assertFalse(h.coordinator.state.value.locked)
        h.assertSecretAbsent(case.secret)
    }

    private fun assertNoFreshWork(case: Case) {
        assertEquals(0, case.h.ledger.approvals().size)
        assertEquals(0, case.h.ledger.admissions().size)
        case.h.assertNoOtherApplicationEffects()
    }

    private fun file(h: RetainedDeviceAdmissionWorkflowV1Harness, kind: String): String =
        RetainedDeviceAdmissionWorkflowV1Harness.filename(h.data.a.reference.originalActionId, kind)

    private fun referenceDifferenceCount(a: LocalDevicePairingReference, b: LocalDevicePairingReference): Int = listOf(
        a.originalActionId != b.originalActionId, a.originalRequestId != b.originalRequestId,
        a.deviceId != b.deviceId, a.keyId != b.keyId, a.phase != b.phase).count { it }

    private fun assertFixedOriginalValues(data: RetainedDeviceAdmissionWorkflowV1Data) {
        assertEquals("00000000-0000-4000-8000-000000000001", data.scope.organizationId.value)
        assertEquals("00000000-0000-4000-8000-000000000002", data.scope.companyId.value)
        assertEquals("00000000-0000-4000-8000-000000000003", data.scope.branchId.value)
        assertEquals("00000000-0000-4000-8000-000000000004", data.rootId.value)
        assertEquals("00000000-0000-4000-8000-000000000005", data.cohostDeviceId.value)
        assertEquals("00000000-0000-4000-8000-000000000006", data.installationId.value)
        assertEquals("00000000-0000-4000-8000-00000000000d", data.headAccountId.value)
        listOf(data.a, data.b).forEach { original ->
            val requested = original.action.requested
            val sdk = assertNotNull(requested.sdkIdentity)
            val a = original === data.a
            val suffix = if (a) "00" else "01"
            assertEquals("00000000-0000-4000-8000-00000000${suffix}07", sdk.originalAcquisitionId.value)
            assertEquals("00000000-0000-4000-8000-00000000${suffix}08", sdk.originalPreparationId.value)
            assertEquals("00000000-0000-4000-8000-00000000${suffix}09", requested.admission.deviceId.value)
            assertEquals("00000000-0000-4000-8000-00000000${suffix}0a", requested.target.keyId.value)
            assertEquals("00000000-0000-4000-8000-00000000${suffix}0b", original.action.originalActionId.value)
            assertEquals("00000000-0000-4000-8000-00000000${suffix}0c", requested.request.requestId.value)
            assertEquals("Synthetic new device", original.deviceDisplayLabel)
            assertEquals(0L, requested.admission.revision.value)
            assertTrue(requested.admission.admitted)
            assertEquals("EXPLICIT_NAMED_COMPANY_HEAD_ONE_NEW_DEVICE_NATIVE_P256_SDK_X25519_PAIRING_V2", requested.purpose)
            assertEquals("synthetic-pos-retained-workflow-v1", sdk.appId.value)
            assertEquals(if (a) "p2id2-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                else "p2id2-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbba", sdk.peerId.value)
            assertEquals(if (a) "p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                else "p2f1-ccccccccccccccccccccccccccccccccccccccccccccccccccca", sdk.fingerprint.value)
            assertEquals(if (a) "p2pkit:v2:p2a1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:p2f1-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                else "p2pkit:v2:p2a1-ddddddddddddddddddddddddddddddddddddddddddddddddddda:p2f1-ccccccccccccccccccccccccccccccccccccccccccccccccccca", sdk.pairingQr)
            assertEquals(data.scope, requested.request.scope)
            assertEquals(data.scope, requested.admission.scope)
            val body = original.action.record.body
            assertEquals(data.rootId, body.origin.rootId)
            assertEquals(data.installationId, body.origin.coordinates.installationId)
            assertEquals(data.cohostDeviceId, body.origin.coordinates.deviceId)
            assertEquals(0L, body.preparedHead.observation.moment.elapsedMillis)
            assertEquals(600_000L, body.preparedHead.deadlineElapsedMillis,
                "fixed synthetic 12:00Z-to-12:10Z deadline; not a production clock/lifetime claim")
            assertEquals(600_000L, assertNotNull(original.approval.record.approvedHead).deadlineElapsedMillis)
            assertDefinitionBytes(original.action.requested, original.approval.requested)
        }
    }
}
