package com.ukkera.pos.app.gateway.independent.approvalsafereplacement

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.arrivalapprovalclient.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen ASR v1 CLIENT assertion slices, not complete cases. The shared gateway,
 * journal transition checks and AES-GCM client persistence are real. Approvals,
 * retirement rows, clocks, session observations and Found business replies are
 * supplied control DATA, not native proofs or a genuinely committed business cut.
 * No restricted Master approval preview/receipt/retirement receipt is constructed.
 * See README for the finite mappings and mandatory NOT_RUN/UNBOUND lanes.
 */
class ApprovalSafeReplacementClientJournalSlicesTest {
    @Test
    fun asr12EncryptedReopenRetainsOriginalAndBothCeremoniesAcrossTwelveActionRoutes() = runTest {
        for (journey in AawClientJourney.entries) for (step in AawClientStep.entries) {
            val inputs = AawClientJournalInputs(journey, step)
            withArrivalApprovalJournal(inputs.owner) { host ->
                val original = asrSaveReady(host, inputs)
                suspend fun checkpoint(expected: StoredClientIntentV1) {
                    assertEquals(expected, host.find(inputs.operationId))
                    host.reopenOriginal()
                    assertEquals(expected, host.find(inputs.operationId))
                    asrAssertOriginalUnchanged(original, host.find(inputs.operationId))
                }
                val initialUnknown = inputs.unresolvedData(original, asrApprovalCarrier(0))
                assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(original, initialUnknown))
                checkpoint(initialUnknown)
                val attached = inputs.attachedData(initialUnknown)
                assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(initialUnknown, attached))
                checkpoint(attached)
                val retirementUnknown = asrRetirementUnresolvedData(attached)
                assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(attached, retirementUnknown))
                checkpoint(retirementUnknown)
                val retired = asrSaveRetired(host, inputs, retirementUnknown)
                checkpoint(retired)
                assertTrue(assertNotNull(retired.actionApproval).retiredApprovals.isEmpty())
                val reviewOpen = asrArchiveClientData(host, retired)
                checkpoint(reviewOpen)
                val archivedOriginal = assertNotNull(reviewOpen.actionApproval).retiredApprovals.single()
                assertEquals(assertNotNull(retired.actionApproval).approvalId, archivedOriginal.approvalId)
                assertEquals(retired.actionApproval?.preview, archivedOriginal.preview)
                assertEquals(retired.actionApproval?.receipt, archivedOriginal.receipt)
                assertEquals(retired.actionApproval?.retirement, archivedOriginal.retirement)
                val ready = asrSaveReady(host, inputs, reviewOpen)
                val replacementUnknown = inputs.unresolvedData(ready, asrApprovalCarrier(1))
                assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(ready, replacementUnknown))
                checkpoint(replacementUnknown)
                assertNotEquals(archivedOriginal.approvalId, replacementUnknown.actionApproval?.approvalId)
                val replacement = inputs.attachedData(replacementUnknown)
                assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(replacementUnknown, replacement))
                checkpoint(replacement)
                assertEquals(JournalWrite.DURABLE, host.journal.markDispatched(inputs.operationId))
                val businessUnknown = host.find(inputs.operationId)
                assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, businessUnknown.localPhase)
                checkpoint(businessUnknown)
                val consumedData = inputs.consumedData(businessUnknown)
                assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(businessUnknown, consumedData))
                assertEquals(JournalWrite.DURABLE, host.journal.remember(inputs.operationId, inputs.terminalData()))
                val retainedResultData = host.find(inputs.operationId)
                checkpoint(retainedResultData)
                assertEquals(inputs.terminalData(), retainedResultData.recorded)
                assertEquals(listOf(archivedOriginal), retainedResultData.actionApproval?.retiredApprovals)

                // Actual encrypted openExisting rejects another owner; no accepting initialization.
                assertEquals(JvmClientJournalOpenResult.Unavailable,
                    host.openExistingAs(inputs.owner.copy(actorId = inputs.otherRequester)))
                host.reopenOriginal()
                assertEquals(retainedResultData, host.find(inputs.operationId))
            }
        }
    }

    @Test
    fun asr03And07RetirementIsDurableBeforeDeliveryAndEveryUnknownRepeatIsLookupOnly() = runTest {
        val replies = listOf(
            LocalActionApprovalRetireResult.StorageUncertain to LocalServiceProblem.STORAGE_UNCERTAIN,
            LocalActionApprovalRetireResult.Denied to LocalServiceProblem.DENIED,
            LocalActionApprovalRetireResult.Busy to LocalServiceProblem.RETRY_LATER,
            LocalActionApprovalRetireResult.Unavailable to LocalServiceProblem.UNAVAILABLE)
        for ((reply, problem) in replies) {
            val inputs = AawClientJournalInputs()
            withArrivalApprovalJournal(inputs.owner) { host ->
                val attached = asrSaveAttached(host, inputs)
                val endpoint = ControlledAsrClientEndpoint(ControlledAawClientEndpoint(inputs))
                val credentials = AawClientCredentialInputs()
                endpoint.retirementReply = reply
                endpoint.onRetirement = { call ->
                    val beforeDelivery = host.find(inputs.operationId)
                    assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED, beforeDelivery.actionApproval?.localPhase)
                    assertEquals(asrApprovalCarrier(0), call.approvalId)
                    assertEquals(inputs.confirmationData, call.confirmation)
                    assertOriginalRequest(inputs, call.request)
                    asrAssertOriginalUnchanged(attached, beforeDelivery)
                    assertEquals(attached.actionApproval?.preview, beforeDelivery.actionApproval?.preview)
                    assertEquals(attached.actionApproval?.receipt, beforeDelivery.actionApproval?.receipt)
                    assertNull(beforeDelivery.actionApproval?.retirement)
                }
                var gateway = asrClientGateway(host.journal, endpoint, credentials)
                assertEquals(problem, assertIs<LocalActionApprovalResult.Unavailable>(
                    gateway.retireActionApproval(inputs.operationId)).problem)
                val held = host.find(inputs.operationId)
                assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED, held.actionApproval?.localPhase)
                coroutineScope {
                    List(2) { async { gateway.retireActionApproval(inputs.operationId) } }.awaitAll().forEach {
                        assertIs<LocalActionApprovalResult.Unavailable>(it)
                    }
                }
                host.reopenOriginal()
                gateway = asrClientGateway(host.journal, endpoint, credentials)
                assertIs<LocalActionApprovalResult.Unavailable>(gateway.retireActionApproval(inputs.operationId))
                val observations = listOf(LocalActionApprovalStatus.NotRecorded, LocalActionApprovalStatus.Denied,
                    LocalActionApprovalStatus.Busy, LocalActionApprovalStatus.Unavailable,
                    LocalActionApprovalStatus.StorageUncertain)
                for (observation in observations) {
                    endpoint.base.approvalStatus = observation
                    assertIs<LocalActionApprovalResult.Unavailable>(gateway.beginActionApprovalReplacement(inputs.operationId))
                    assertIs<LocalActionApprovalResult.Unavailable>(gateway.previewActionApproval(inputs.operationId))
                    assertIs<LocalActionApprovalResult.Unavailable>(issueInput(gateway, inputs))
                    assertIs<LocalOperationResult.Unresolved>(gateway.submit(inputs.operationId))
                    assertEquals(held, host.find(inputs.operationId))
                }
                assertEquals(1, endpoint.retirements.size)
                assertTrue(endpoint.base.previews.isEmpty())
                assertTrue(endpoint.base.issues.isEmpty())
                endpoint.base.approvalLookups.forEach { assertEquals(asrApprovalCarrier(0) to inputs.confirmationData, it) }
                endpoint.assertNoBusinessMutationOrAuthentication()
                assertLeasesDestroyed(credentials)
            }
        }
    }

    @Test
    fun asr05And08UnknownInitialOrReplacementIssueKeepsOneCurrentIdAndNeverReissues() = runTest {
        for (replacement in listOf(false, true)) {
            val inputs = AawClientJournalInputs()
            withArrivalApprovalJournal(inputs.owner) { host ->
                val previous = if (replacement) asrArchiveClientData(host,
                    asrSaveRetired(host, inputs, asrSaveAttached(host, inputs))) else null
                val ready = asrSaveReady(host, inputs, previous)
                val archives = assertNotNull(ready.actionApproval).retiredApprovals
                val endpoint = ControlledAsrClientEndpoint(ControlledAawClientEndpoint(inputs))
                val credentials = AawClientCredentialInputs()
                endpoint.base.onIssue = { call ->
                    val beforeDelivery = host.find(inputs.operationId)
                    assertEquals(LocalActionApprovalPhase.ISSUE_UNRESOLVED, beforeDelivery.actionApproval?.localPhase)
                    assertEquals(call.approvalId, beforeDelivery.actionApproval?.approvalId)
                    assertEquals(inputs.confirmationData, call.confirmationDigest)
                    assertOriginalRequest(inputs, call.request)
                    assertEquals(archives, beforeDelivery.actionApproval?.retiredApprovals)
                    assertEquals(archives.lastOrNull()?.approvalId, endpoint.issuePredecessors.single())
                    asrAssertOriginalUnchanged(ready, beforeDelivery)
                }
                var gateway = asrClientGateway(host.journal, endpoint, credentials)
                assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
                    assertIs<LocalActionApprovalResult.Unavailable>(issueInput(gateway, inputs)).problem)
                val held = host.find(inputs.operationId)
                val currentId = assertNotNull(held.actionApproval?.approvalId)
                assertEquals(LocalActionApprovalPhase.ISSUE_UNRESOLVED, held.actionApproval?.localPhase)
                if (replacement) assertNotEquals(archives.single().approvalId, currentId)
                coroutineScope {
                    List(2) { async { issueInput(gateway, inputs) } }.awaitAll().forEach {
                        assertEquals(LocalActionApprovalPhase.ISSUE_UNRESOLVED,
                            assertIs<LocalActionApprovalResult.Available>(it).operation.actionApproval?.phase)
                    }
                }
                host.reopenOriginal()
                gateway = asrClientGateway(host.journal, endpoint, credentials)
                assertIs<LocalActionApprovalResult.Available>(issueInput(gateway, inputs))
                assertIs<LocalActionApprovalResult.Available>(gateway.retireActionApproval(inputs.operationId))
                assertIs<LocalActionApprovalResult.Available>(gateway.beginActionApprovalReplacement(inputs.operationId))
                assertIs<LocalActionApprovalResult.Available>(gateway.previewActionApproval(inputs.operationId))
                assertFalse(assertIs<LocalOperationResult.Pending>(gateway.submit(inputs.operationId)).operation.submitAllowed)
                assertEquals(held, host.find(inputs.operationId))
                assertEquals(1, endpoint.base.issues.size)
                assertTrue(endpoint.base.issues.single().borrowedPassword.all { it == '\u0000' })
                assertTrue(endpoint.retirements.isEmpty())
                assertTrue(endpoint.base.previews.isEmpty())
                endpoint.base.approvalLookups.forEach { assertEquals(currentId to inputs.confirmationData, it) }
                endpoint.assertNoBusinessMutationOrAuthentication()
                assertLeasesDestroyed(credentials)
                host.storedPlaintexts().forEach { plain ->
                    assertFalse(plain.contains(credentials.marker))
                    assertFalse(plain.contains("SYN-ASR-OWN-AUTH-INPUT"))
                }
            }
        }
    }

    @Test
    fun asr11PreviewAndIssueNegativeSeamsCarryOnlyTheLatestArchivedPredecessor() = runTest {
        val inputs = AawClientJournalInputs()
        withArrivalApprovalJournal(inputs.owner) { host ->
            val original = asrSaveAttached(host, inputs)
            val firstReview = asrArchiveClientData(host, asrSaveRetired(host, inputs, original))
            val secondAttached = asrSaveAttached(host, inputs, 1, firstReview)
            val secondReview = asrArchiveClientData(host, asrSaveRetired(host, inputs, secondAttached, 1))
            val archives = assertNotNull(secondReview.actionApproval).retiredApprovals
            assertEquals(listOf(asrApprovalCarrier(0), asrApprovalCarrier(1)), archives.map { it.approvalId })
            val endpoint = ControlledAsrClientEndpoint(ControlledAawClientEndpoint(inputs))
            val gateway = asrClientGateway(host.journal, endpoint)
            assertEquals(LocalServiceProblem.DENIED, assertIs<LocalActionApprovalResult.Unavailable>(
                gateway.previewActionApproval(inputs.operationId)).problem)
            assertEquals(listOf<DomainId?>(asrApprovalCarrier(1)), endpoint.previewPredecessors)
            assertOriginalRequest(inputs, endpoint.base.previews.single())
            assertEquals(secondReview, host.find(inputs.operationId))
            // A client READY carrier enables observing a negative issuance handoff, not approval authority.
            val ready = asrSaveReady(host, inputs, secondReview)
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN,
                assertIs<LocalActionApprovalResult.Unavailable>(issueInput(gateway, inputs)).problem)
            assertEquals(listOf<DomainId?>(asrApprovalCarrier(1)), endpoint.issuePredecessors)
            assertOriginalRequest(inputs, endpoint.base.issues.single().request)
            val held = host.find(inputs.operationId)
            assertEquals(archives, held.actionApproval?.retiredApprovals)
            asrAssertOriginalUnchanged(ready, held)
            assertTrue(endpoint.retirements.isEmpty())
            endpoint.assertNoBusinessMutationOrAuthentication()
            host.reopenOriginal()
            assertEquals(held, host.find(inputs.operationId))
        }
    }

    @Test
    fun asr04CurrentRequesterScopeDeviceAndRequestHintsRefuseBeforeRetirementHandoff() = runTest {
        val inputs = AawClientJournalInputs()
        val views = listOf(
            asrSessionView(inputs, actor = inputs.otherRequester),
            asrSessionView(inputs, scope = inputs.scope.copy(companyId = DomainId.random())),
            asrSessionView(inputs, scope = inputs.scope.copy(branchId = DomainId.random())),
            asrSessionView(inputs, device = DomainId.random()),
            asrSessionView(inputs, verbs = listOf(PermissionVerb.APPROVE)),
            asrSessionView(inputs, verbs = listOf(PermissionVerb.PERFORM)),
            asrSessionView(inputs, denyRequest = true))
        for (view in views) withArrivalApprovalJournal(inputs.owner) { host ->
            val original = asrSaveAttached(host, inputs)
            val endpoint = ControlledAsrClientEndpoint(ControlledAawClientEndpoint(inputs))
            endpoint.base.view = view
            assertIs<LocalActionApprovalResult.Unavailable>(
                asrClientGateway(host.journal, endpoint).retireActionApproval(inputs.operationId))
            assertTrue(endpoint.retirements.isEmpty())
            assertTrue(endpoint.base.issues.isEmpty())
            endpoint.assertNoBusinessMutationOrAuthentication()
            host.reopenOriginal()
            assertEquals(original, host.find(inputs.operationId))
        }
        for (unavailableCredentials in listOf(false, true)) withArrivalApprovalJournal(inputs.owner) { host ->
            val original = asrSaveAttached(host, inputs)
            val endpoint = ControlledAsrClientEndpoint(ControlledAawClientEndpoint(inputs))
            val credentials = AawClientCredentialInputs()
            if (unavailableCredentials) credentials.active = false else endpoint.base.currentDenied = true
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertIs<LocalActionApprovalResult.Unavailable>(
                asrClientGateway(host.journal, endpoint, credentials).retireActionApproval(inputs.operationId)).problem)
            assertTrue(endpoint.retirements.isEmpty())
            host.reopenOriginal()
            assertEquals(original, host.find(inputs.operationId))
        }
        // REQUEST-only permits the client handoff, not native retirement or PERFORM/APPROVE.
        withArrivalApprovalJournal(inputs.owner) { host ->
            asrSaveAttached(host, inputs)
            val endpoint = ControlledAsrClientEndpoint(ControlledAawClientEndpoint(inputs))
            endpoint.base.view = asrSessionView(inputs, verbs = listOf(PermissionVerb.REQUEST))
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, assertIs<LocalActionApprovalResult.Unavailable>(
                asrClientGateway(host.journal, endpoint).retireActionApproval(inputs.operationId)).problem)
            assertEquals(1, endpoint.retirements.size)
            endpoint.assertNoBusinessMutationOrAuthentication()
        }
    }

    @Test
    fun asr16SixteenPreviousClientRowsDoNotPreventCurrentCancellationOrPermitASeventeenthArchive() = runTest {
        val inputs = AawClientJournalInputs()
        withArrivalApprovalJournal(inputs.owner) { host ->
            val original = asrSaveAttached(host, inputs)
            var current = original
            for (index in 0 until 15) {
                current = asrSaveAttached(host, inputs, index + 1,
                    asrArchiveClientData(host, asrSaveRetired(host, inputs, current, index)))
            }
            val previousFifteen = assertNotNull(current.actionApproval).retiredApprovals
            assertEquals(15, previousFifteen.size)
            val retiredFifteen = asrSaveRetired(host, inputs, current, 15)
            assertEquals(previousFifteen, retiredFifteen.actionApproval?.retiredApprovals)
            assertEquals(asrApprovalCarrier(15), retiredFifteen.actionApproval?.approvalId)
            assertEquals(LocalActionApprovalPhase.RETIRED, retiredFifteen.actionApproval?.localPhase)
            val reviewOpen = asrArchiveClientData(host, retiredFifteen)
            val previousSixteen = assertNotNull(reviewOpen.actionApproval).retiredApprovals
            assertEquals(16, previousSixteen.size)
            assertEquals(previousFifteen, previousSixteen.take(15))
            assertEquals(retiredFifteen.actionApproval?.preview, previousSixteen.last().preview)
            assertEquals(retiredFifteen.actionApproval?.receipt, previousSixteen.last().receipt)
            assertEquals(retiredFifteen.actionApproval?.retirement, previousSixteen.last().retirement)
            assertEquals(LocalActionApprovalPhase.PREVIEW_REQUIRED, reviewOpen.actionApproval?.localPhase)
            val endpoint = ControlledAsrClientEndpoint(ControlledAawClientEndpoint(inputs))
            var gateway = asrClientGateway(host.journal, endpoint)
            repeat(2) {
                assertEquals(LocalActionApprovalPhase.PREVIEW_REQUIRED,
                    assertIs<LocalActionApprovalResult.Available>(gateway.beginActionApprovalReplacement(inputs.operationId))
                        .operation.actionApproval?.phase)
                assertEquals(reviewOpen, host.find(inputs.operationId))
            }
            assertTrue(endpoint.base.approvalLookups.isEmpty())
            val attachedSixteen = asrSaveAttached(host, inputs, 16, reviewOpen)
            endpoint.onRetirement = { call ->
                assertEquals(asrApprovalCarrier(16), call.approvalId)
                assertEquals(previousSixteen, host.find(inputs.operationId).actionApproval?.retiredApprovals)
                assertEquals(LocalActionApprovalPhase.RETIRE_UNRESOLVED,
                    host.find(inputs.operationId).actionApproval?.localPhase)
            }
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, assertIs<LocalActionApprovalResult.Unavailable>(
                gateway.retireActionApproval(inputs.operationId)).problem)
            val unknown = host.find(inputs.operationId)
            asrAssertOriginalUnchanged(original, unknown)
            assertEquals(attachedSixteen.actionApproval?.receipt, unknown.actionApproval?.receipt)
            host.reopenOriginal()
            gateway = asrClientGateway(host.journal, endpoint)
            assertIs<LocalActionApprovalResult.Unavailable>(gateway.beginActionApprovalReplacement(inputs.operationId))
            assertIs<LocalActionApprovalResult.Unavailable>(gateway.retireActionApproval(inputs.operationId))
            assertEquals(unknown, host.find(inputs.operationId))
            assertEquals(1, endpoint.retirements.size)

            // Inert terminal CLIENT data binds only the capacity gate, not a native retirement.
            val retiredSixteen = asrSaveRetired(host, inputs, unknown, 16)
            val eventsBeforeCapacityRefusal = endpoint.events.toList()
            repeat(2) {
                assertEquals(LocalServiceProblem.RETRY_LATER, assertIs<LocalActionApprovalResult.Unavailable>(
                    gateway.beginActionApprovalReplacement(inputs.operationId)).problem)
                assertEquals(retiredSixteen, host.find(inputs.operationId))
            }
            host.reopenOriginal()
            gateway = asrClientGateway(host.journal, endpoint)
            assertEquals(LocalServiceProblem.RETRY_LATER, assertIs<LocalActionApprovalResult.Unavailable>(
                gateway.beginActionApprovalReplacement(inputs.operationId)).problem)
            assertEquals(eventsBeforeCapacityRefusal, endpoint.events)
            assertEquals(previousSixteen, host.find(inputs.operationId).actionApproval?.retiredApprovals)
            assertEquals(asrApprovalCarrier(16), host.find(inputs.operationId).actionApproval?.approvalId)
            assertEquals(17, (previousSixteen.map { it.approvalId } +
                assertNotNull(retiredSixteen.actionApproval?.approvalId)).distinct().size)
            assertFailsWith<IllegalArgumentException> { assertNotNull(retiredSixteen.actionApproval).replacement() }
            assertTrue(endpoint.base.previews.isEmpty())
            assertTrue(endpoint.base.issues.isEmpty())
            endpoint.assertNoBusinessMutationOrAuthentication()
        }
    }

    @Test
    fun asr10And11And14TruncationRewritingOrSelectingOldClientHistoryCannotReplaceTheCurrentApproval() = runTest {
        val inputs = AawClientJournalInputs()
        withArrivalApprovalJournal(inputs.owner) { host ->
            val retiredOriginal = asrSaveRetired(host, inputs, asrSaveAttached(host, inputs))
            val current = asrSaveAttached(host, inputs, 1, asrArchiveClientData(host, retiredOriginal))
            val approval = assertNotNull(current.actionApproval)
            val old = approval.retiredApprovals.single()
            val mutations = listOf(
                current.copy(actionApproval = approval.copy(retiredApprovals = emptyList())),
                current.copy(actionApproval = approval.copy(retiredApprovals = listOf(old.copy(
                    retirement = old.retirement.copy(originalApprovalDigest = PayloadDigest("b".repeat(64))))))),
                current.copy(actionApproval = approval.copy(preview = assertNotNull(approval.preview).copy(
                    confirmationDigest = PayloadDigest("c".repeat(64))))),
                current.copy(actionApprovalId = old.approvalId, actionApproval = approval.copy(
                    approvalId = old.approvalId, retiredApprovals = emptyList())))
            for (mutation in mutations) {
                assertEquals(JournalWrite.IDENTITY_CONFLICT, host.journal.updateActionApproval(current, mutation))
                assertEquals(current, host.find(inputs.operationId))
            }
            val endpoint = ControlledAsrClientEndpoint(ControlledAawClientEndpoint(inputs))
            val gateway = asrClientGateway(host.journal, endpoint)
            for (observation in listOf(LocalActionApprovalStatus.NotRecorded,
                    LocalActionApprovalStatus.Unavailable, LocalActionApprovalStatus.StorageUncertain)) {
                endpoint.base.approvalStatus = observation
                assertIs<LocalActionApprovalResult.Unavailable>(gateway.beginActionApprovalReplacement(inputs.operationId))
                assertEquals(current, host.find(inputs.operationId))
            }
            endpoint.base.approvalLookups.forEach { assertEquals(asrApprovalCarrier(1) to inputs.confirmationData, it) }
            assertTrue(endpoint.retirements.isEmpty())
            assertTrue(endpoint.base.previews.isEmpty())
            assertTrue(endpoint.base.issues.isEmpty())
            endpoint.assertNoBusinessMutationOrAuthentication()
            host.reopenOriginal()
            assertEquals(current, host.find(inputs.operationId))
        }
    }

    @Test
    fun asr06And07And11And14FoundBusinessControlDataWinsBeforeBrokenApprovalHistory() = runTest {
        for (phase in listOf(LocalActionApprovalPhase.RETIRE_UNRESOLVED,
                LocalActionApprovalPhase.RETIRED, LocalActionApprovalPhase.CONSUMED)) {
            val inputs = AawClientJournalInputs()
            withArrivalApprovalJournal(inputs.owner) { host ->
                val retiredOriginal = asrSaveRetired(host, inputs, asrSaveAttached(host, inputs))
                val attached = asrSaveAttached(host, inputs, 1, asrArchiveClientData(host, retiredOriginal))
                val controlState = when (phase) {
                    LocalActionApprovalPhase.RETIRE_UNRESOLVED -> asrRetirementUnresolvedData(attached).also {
                        assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(attached, it))
                    }
                    LocalActionApprovalPhase.RETIRED -> asrSaveRetired(host, inputs, attached, 1)
                    else -> inputs.consumedData(attached).also {
                        assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(attached, it))
                    }
                }
                val endpoint = ControlledAsrClientEndpoint(ControlledAawClientEndpoint(inputs))
                endpoint.base.approvalStatus = LocalActionApprovalStatus.StorageUncertain
                endpoint.businessReply = asrFoundBusinessData(inputs)
                var gateway = asrClientGateway(host.journal, endpoint)
                assertEquals(inputs.operationId,
                    assertIs<LocalOperationResult.Recorded>(gateway.submit(inputs.operationId)).operationId)
                assertEquals(inputs.operationId,
                    assertIs<LocalOperationResult.Recorded>(gateway.lookup(inputs.operationId)).operationId)
                val pending = assertIs<LocalPendingResult.Available>(gateway.pending()).operations.single()
                assertEquals(inputs.operationId, pending.operationId)
                assertEquals(LocalPendingPhase.RECORDED_UNACKNOWLEDGED, pending.phase)
                assertFalse(pending.submitAllowed)
                assertTrue(endpoint.base.approvalLookups.isEmpty())
                val retained = host.find(inputs.operationId)
                assertEquals(inputs.terminalData(), retained.recorded)
                assertEquals(controlState.actionApproval, retained.actionApproval)
                asrAssertOriginalUnchanged(controlState, retained)
                assertIs<LocalActionApprovalResult.Unavailable>(gateway.beginActionApprovalReplacement(inputs.operationId))
                assertEquals(retained, host.find(inputs.operationId))
                host.reopenOriginal()
                gateway = asrClientGateway(host.journal, endpoint)
                assertIs<LocalOperationResult.Recorded>(gateway.lookup(inputs.operationId))
                assertEquals(retained, host.find(inputs.operationId))
                assertTrue(endpoint.retirements.isEmpty())
                assertTrue(endpoint.base.previews.isEmpty())
                assertTrue(endpoint.base.issues.isEmpty())
                endpoint.assertNoBusinessMutationOrAuthentication()
            }
        }
    }

    @Test
    fun asr06DispatchedUnknownClientOriginalRetainsThreeUnitObservationAndCannotHandOffRetirement() = runTest {
        for (journey in AawClientJourney.entries) {
            val inputs = AawClientJournalInputs(journey, AawClientStep.DISPATCH)
            withArrivalApprovalJournal(inputs.owner) { host ->
                asrSaveAttached(host, inputs)
                assertEquals(JournalWrite.DURABLE, host.journal.markDispatched(inputs.operationId))
                val original = host.find(inputs.operationId)
                val endpoint = ControlledAsrClientEndpoint(ControlledAawClientEndpoint(inputs))
                endpoint.businessReply = LookupReply.Deferred(inputs.operationId, DeferredReason.HISTORY_INCOMPLETE)
                var gateway = asrClientGateway(host.journal, endpoint)
                assertIs<LocalActionApprovalResult.Unavailable>(gateway.retireActionApproval(inputs.operationId))
                assertIs<LocalActionApprovalResult.Unavailable>(gateway.beginActionApprovalReplacement(inputs.operationId))
                assertIs<LocalOperationResult.Unresolved>(gateway.submit(inputs.operationId))
                assertIs<LocalOperationResult.Unresolved>(gateway.lookup(inputs.operationId))
                assertEquals(original, host.find(inputs.operationId))
                host.reopenOriginal()
                gateway = asrClientGateway(host.journal, endpoint)
                assertIs<LocalActionApprovalResult.Unavailable>(gateway.retireActionApproval(inputs.operationId))
                assertIs<LocalOperationResult.Unresolved>(gateway.submit(inputs.operationId))
                assertEquals(original, host.find(inputs.operationId))
                assertEquals("3", inputs.step.quantity)
                assertOriginalRequest(inputs, original.unapprovedRequest())
                assertTrue(endpoint.retirements.isEmpty())
                assertTrue(endpoint.base.issues.isEmpty())
                endpoint.assertNoBusinessMutationOrAuthentication()
            }
        }
    }

    @Test
    fun asr12FailedOrUnknownIdentityWritePreventsFirstIssueAndRetirementDelivery() = runTest {
        for (retirement in listOf(false, true)) for (writeActuallyDurable in listOf(false, true)) {
            val inputs = AawClientJournalInputs()
            withArrivalApprovalJournal(inputs.owner) { host ->
                val original = if (retirement) asrSaveAttached(host, inputs) else asrSaveReady(host, inputs)
                val endpoint = ControlledAsrClientEndpoint(ControlledAawClientEndpoint(inputs))
                val gateway = asrClientGateway(AsrApprovalWriteFailureJournal(host.journal, writeActuallyDurable), endpoint)
                val result = if (retirement) gateway.retireActionApproval(inputs.operationId) else issueInput(gateway, inputs)
                assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, assertIs<LocalActionApprovalResult.Unavailable>(result).problem)
                assertTrue(endpoint.retirements.isEmpty())
                assertTrue(endpoint.base.issues.isEmpty())
                val retained = host.find(inputs.operationId)
                asrAssertOriginalUnchanged(original, retained)
                if (!writeActuallyDurable) assertEquals(original, retained)
                else assertEquals(if (retirement) LocalActionApprovalPhase.RETIRE_UNRESOLVED
                    else LocalActionApprovalPhase.ISSUE_UNRESOLVED, retained.actionApproval?.localPhase)
                host.reopenOriginal()
                assertEquals(retained, host.find(inputs.operationId))
                if (writeActuallyDurable) {
                    val reopened = asrClientGateway(host.journal, endpoint)
                    if (retirement) assertIs<LocalActionApprovalResult.Unavailable>(reopened.retireActionApproval(inputs.operationId))
                    else assertIs<LocalActionApprovalResult.Available>(issueInput(reopened, inputs))
                    assertEquals(retained, host.find(inputs.operationId))
                    assertTrue(endpoint.retirements.isEmpty())
                    assertTrue(endpoint.base.issues.isEmpty())
                }
                endpoint.assertNoBusinessMutationOrAuthentication()
            }
        }
    }
}

private suspend fun issueInput(gateway: LocalEndpointWorkflowGateway,
    inputs: AawClientJournalInputs): LocalActionApprovalResult {
    val password = "SYN-ASR-OWN-AUTH-INPUT".toCharArray()
    val callerSnapshot = password.copyOf()
    try {
        return gateway.issueActionApproval(inputs.operationId, inputs.confirmationData,
            if (inputs.journey.selfApprover) "REQUESTER-R" else "APPROVER-A", password).also {
            assertContentEquals(callerSnapshot, password)
        }
    } finally { password.fill('\u0000'); callerSnapshot.fill('\u0000') }
}

private fun assertOriginalRequest(inputs: AawClientJournalInputs, request: MutationRequest) {
    assertEquals(inputs.operationId, request.operationId)
    assertEquals(inputs.request.command, request.command)
    assertEquals(inputs.request.payload, request.payload)
    assertNull(request.actionApprovalId)
}

private fun assertLeasesDestroyed(credentials: AawClientCredentialInputs) {
    credentials.issued.forEach {
        assertFailsWith<IllegalStateException> { it.credentials.bearer.useBytes { } }
    }
}
