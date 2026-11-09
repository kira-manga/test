package com.ukkera.pos.app.gateway.independent.arrivalapprovalclient

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.LocalActionApprovalDenial
import com.ukkera.pos.database.LocalActionApprovalIssueResult
import com.ukkera.pos.inventory.store.LocalArrivalEvidenceSelectionV2
import com.ukkera.pos.inventory.store.LocalUnpricedArrivalCommandsV2
import com.ukkera.pos.security.PayloadDigest
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
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen AAW-01/02/03/04/05/06/07 and OH-04/06 bounded client slices.
 * Actual shared gateway/form mapping and real encrypted JVM client storage are
 * bound. Current-source/view/absence observations and retained client metadata
 * are supplied DATA, never genuine native authority or an executed Master result.
 * No restricted Master approval preview/receipt, grant or accepting writer is made.
 * Whole OH/QAM/UAD2/AAW cases, positive Master preview/issue/consume/history,
 * C1-C4/money/custody, original bytes, installed platforms and RPC remain UNBOUND.
 */
class ArrivalApprovalClientFrozenJournalSlicesTest {
    @Test
    fun aaw01And02CompleteNonDirectRecordFormStagesOriginalBytesWithoutIssuanceOrDispatch() = runTest {
        for (journey in AawClientJourney.entries) {
            val inputs = AawClientJournalInputs(journey)
            withArrivalApprovalJournal(inputs.owner) { host ->
                val endpoint = ControlledAawClientEndpoint(inputs)
                val credentials = AawClientCredentialInputs()
                val gateway = aawClientGateway(host, endpoint, credentials)
                val query = LocalWorkflowQuery(LocalWorkflow.RECEIVING,
                    search = "arrival2-documents:${inputs.documents.joinToString(",") { it.documentId.value }}",
                    reference = inputs.intent.reference)
                val page = assertIs<LocalReadResult.Available>(gateway.read(query)).page
                val form = page.forms.single { it.action == LocalAction.ARRIVAL_RECORD }
                assertEquals(2, form.version)
                val displayed = assertNotNull(form.arrivalEvidence)
                assertEquals(inputs.route, displayed.rules.single().approvalRoute)
                assertEquals(inputs.ruleDetails, displayed.rules.single().ruleDetails)
                assertEquals(inputs.documents, displayed.documents)
                // Bind the actual displayed form's explicit selection, not a private stage shortcut.
                val selection = assertNotNull(localArrivalEvidenceInputV2(form, inputs.intent.values))
                val intent = inputs.originalIntent(selection)
                val staged = assertIs<LocalStageResult.Staged>(gateway.stage(intent)).operation
                assertEquals(inputs.operationId, staged.operationId)
                assertFalse(staged.submitAllowed)
                assertEquals(LocalActionApprovalPhase.PREVIEW_REQUIRED, assertNotNull(staged.actionApproval).phase)
                val original = host.find(inputs.operationId)
                assertFrozenBody(inputs, original.request())
                assertNull(original.actionApprovalId)
                assertNull(original.actionApproval?.approvalId)
                assertEquals(inputs.route, original.actionApproval?.rule?.approvalRoute)
                assertEquals(inputs.ruleDetails, original.actionApproval?.rule?.ruleDetails)
                assertTrue(endpoint.previews.isEmpty())
                assertTrue(endpoint.issues.isEmpty())
                assertTrue(endpoint.executions.isEmpty())

                // The transport's supplied absence is not enough to run an unapproved original.
                val held = assertIs<LocalOperationResult.Pending>(gateway.submit(inputs.operationId)).operation
                assertFalse(held.submitAllowed)
                assertEquals(LocalActionApprovalPhase.PREVIEW_REQUIRED, held.actionApproval?.phase)
                assertEquals(original, host.find(inputs.operationId))
                assertTrue(endpoint.executions.isEmpty())
                assertIs<LocalStageResult.Staged>(gateway.stage(intent))
                assertEquals(original, host.find(inputs.operationId))
                host.reopenOriginal()
                val recovered = assertIs<LocalOperationResult.Pending>(
                    aawClientGateway(host, endpoint, credentials).lookup(inputs.operationId)).operation
                assertEquals(staged.summary, recovered.summary)
                assertEquals(inputs.operationId, recovered.operationId)
                assertFalse(recovered.submitAllowed)
                assertEquals(original, host.find(inputs.operationId))
                assertRequesterNotReplaced(inputs, endpoint)
                assertLeaseInputsRetired(credentials)
            }
        }
    }

    @Test
    fun aaw04And07DeniedPreviewCannotSaveAnApprovalOrDispatchTheOriginal() = runTest {
        val inputs = AawClientJournalInputs()
        withArrivalApprovalJournal(inputs.owner) { host ->
            val original = inputs.stagedData()
            assertEquals(JournalWrite.DURABLE, host.journal.stage(original))
            val endpoint = ControlledAawClientEndpoint(inputs)
            val credentials = AawClientCredentialInputs()
            val gateway = aawClientGateway(host, endpoint, credentials)
            val denied = assertIs<LocalActionApprovalResult.Unavailable>(gateway.previewActionApproval(inputs.operationId))
            assertEquals(inputs.operationId, denied.operationId)
            assertEquals(LocalServiceProblem.DENIED, denied.problem)
            assertFrozenBody(inputs, endpoint.previews.single())
            assertEquals(original, host.find(inputs.operationId))
            val password = "synthetic-unconfirmed-input".toCharArray()
            val retainedCallerInput = password.copyOf()
            try {
                assertIs<LocalActionApprovalResult.Unavailable>(gateway.issueActionApproval(
                    inputs.operationId, inputs.confirmationData, "APPROVER-A", password))
                assertContentEquals(retainedCallerInput, password)
                assertTrue(endpoint.issues.isEmpty())
                assertFalse(assertIs<LocalOperationResult.Pending>(gateway.submit(inputs.operationId)).operation.submitAllowed)
                assertTrue(endpoint.executions.isEmpty())
                assertNull(host.find(inputs.operationId).actionApprovalId)
                assertNull(host.find(inputs.operationId).actionApproval?.approvalId)
                assertRequesterNotReplaced(inputs, endpoint)
                assertLeaseInputsRetired(credentials)
            } finally { password.fill('\u0000'); retainedCallerInput.fill('\u0000') }
            host.reopenOriginal()
            assertEquals(original, host.find(inputs.operationId))
        }
    }

    @Test
    fun aaw05And07ConfirmedOriginalAndOneAPIdAreDurableBeforeEndpointDenial() = runTest {
        val inputs = AawClientJournalInputs()
        withArrivalApprovalJournal(inputs.owner) { host ->
            val original = saveClientReadyData(host, inputs)
            val endpoint = ControlledAawClientEndpoint(inputs)
            val credentials = AawClientCredentialInputs()
            endpoint.issueReply = LocalActionApprovalIssueResult.Denied(LocalActionApprovalDenial.AUTHORIZATION_REFUSED)
            val password = "synthetic-private-approval-password".toCharArray()
            val callerSnapshot = password.copyOf()
            endpoint.onIssue = { call ->
                val beforeDelivery = host.find(inputs.operationId)
                val ceremony = assertNotNull(beforeDelivery.actionApproval)
                assertEquals(LocalActionApprovalPhase.ISSUE_UNRESOLVED, ceremony.localPhase)
                assertEquals(call.approvalId, ceremony.approvalId)
                assertEquals(inputs.confirmationData, assertNotNull(ceremony.preview).confirmationDigest)
                assertEquals(inputs.requirement, ceremony.preview?.requirement())
                assertRetainedOriginal(original, beforeDelivery)
                assertFrozenBody(inputs, call.request)
                assertNull(call.request.actionApprovalId)
                assertNotSame(password, call.borrowedPassword)
                assertContentEquals(callerSnapshot, call.borrowedPassword)
            }
            try {
                val refused = assertIs<LocalActionApprovalResult.Available>(aawClientGateway(host, endpoint, credentials)
                    .issueActionApproval(inputs.operationId, inputs.confirmationData, "APPROVER-A", password)).operation
                val saved = host.find(inputs.operationId)
                assertRetainedOriginal(original, saved)
                assertEquals(LocalActionApprovalPhase.REFUSED, refused.actionApproval?.phase)
                assertEquals(LocalActionApprovalRefusal.AUTHORIZATION_REFUSED, refused.actionApproval?.refusal)
                assertEquals(endpoint.issues.single().approvalId, saved.actionApproval?.approvalId)
                assertNull(saved.actionApprovalId)
                assertContentEquals(callerSnapshot, password)
                assertTrue(endpoint.issues.single().borrowedPassword.all { it == '\u0000' })
                assertLeaseInputsRetired(credentials)
                assertNoPrivateAuthenticationInStorage(host, credentials.marker, "APPROVER-A", callerSnapshot.concatToString())
                host.reopenOriginal()
                val lookup = assertIs<LocalActionApprovalResult.Available>(
                    aawClientGateway(host, endpoint, credentials).lookupActionApproval(inputs.operationId)).operation
                assertEquals(LocalActionApprovalPhase.REFUSED, lookup.actionApproval?.phase)
                assertEquals(saved.actionApproval?.approvalId, lookup.actionApproval?.approvalId)
                assertEquals(saved, host.find(inputs.operationId))
                assertEquals(1, endpoint.issues.size)
                assertTrue(endpoint.executions.isEmpty())
                assertRequesterNotReplaced(inputs, endpoint)
            } finally { password.fill('\u0000'); callerSnapshot.fill('\u0000') }
        }
    }

    @Test
    fun aaw05And06UnknownIssuanceAndNotRecordedLookupNeverReissueOrReplaceTheOriginal() = runTest {
        val inputs = AawClientJournalInputs()
        withArrivalApprovalJournal(inputs.owner) { host ->
            val original = saveClientReadyData(host, inputs)
            val endpoint = ControlledAawClientEndpoint(inputs)
            val credentials = AawClientCredentialInputs()
            var gateway = aawClientGateway(host, endpoint, credentials)
            val unknown = assertIs<LocalActionApprovalResult.Unavailable>(issueWithTransientCallerInput(gateway, inputs))
            assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, unknown.problem)
            val saved = host.find(inputs.operationId)
            val approvalId = assertNotNull(saved.actionApproval?.approvalId)
            assertEquals(LocalActionApprovalPhase.ISSUE_UNRESOLVED, saved.actionApproval?.localPhase)
            assertNull(saved.actionApprovalId)
            assertRetainedOriginal(original, saved)

            // Two concurrent identical UI deliveries plus one after encrypted reopen are lookup-only.
            coroutineScope {
                List(2) { async { issueWithTransientCallerInput(gateway, inputs) } }.awaitAll().forEach {
                    val pending = assertIs<LocalActionApprovalResult.Available>(it).operation
                    assertEquals(LocalActionApprovalPhase.ISSUE_UNRESOLVED, pending.actionApproval?.phase)
                    assertFalse(pending.submitAllowed)
                }
            }
            host.reopenOriginal()
            gateway = aawClientGateway(host, endpoint, credentials)
            assertIs<LocalActionApprovalResult.Available>(issueWithTransientCallerInput(gateway, inputs))
            assertEquals(1, endpoint.issues.size)
            assertEquals(saved, host.find(inputs.operationId))
            val held = assertIs<LocalOperationResult.Pending>(gateway.submit(inputs.operationId)).operation
            assertFalse(held.submitAllowed)
            assertEquals(inputs.operationId, held.operationId)
            assertEquals(inputs.summary, held.summary)
            assertTrue(endpoint.approvalLookups.isNotEmpty())
            endpoint.approvalLookups.forEach { assertEquals(approvalId to inputs.confirmationData, it) }
            assertEquals(saved, host.find(inputs.operationId))
            assertTrue(endpoint.executions.isEmpty())
            assertTrue(endpoint.issues.single().borrowedPassword.all { it == '\u0000' })
            assertRequesterNotReplaced(inputs, endpoint)
            assertLeaseInputsRetired(credentials)
        }
    }

    @Test
    fun aaw04And07KnownRequesterRetirementAndDifferentActorCannotAdoptTheSavedOriginal() = runTest {
        val inputs = AawClientJournalInputs()
        withArrivalApprovalJournal(inputs.owner) { host ->
            val original = saveClientReadyData(host, inputs)
            val endpoint = ControlledAawClientEndpoint(inputs)
            val credentials = AawClientCredentialInputs()
            val gateway = aawClientGateway(host, endpoint, credentials)
            endpoint.onIssue = { credentials.active = false }
            assertIs<LocalActionApprovalResult.Unavailable>(issueWithTransientCallerInput(gateway, inputs))
            val retained = host.find(inputs.operationId)
            assertRetainedOriginal(original, retained)
            assertNotNull(retained.actionApproval?.approvalId)
            assertEquals(LocalActionApprovalPhase.ISSUE_UNRESOLVED, retained.actionApproval?.localPhase)
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertIs<LocalActionApprovalResult.Unavailable>(
                gateway.lookupActionApproval(inputs.operationId)).problem)
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertIs<LocalOperationResult.Unresolved>(
                gateway.submit(inputs.operationId)).problem)
            assertEquals(1, endpoint.issues.size)
            assertTrue(endpoint.approvalLookups.isEmpty())
            assertTrue(endpoint.executions.isEmpty())
            assertTrue(endpoint.issues.single().borrowedPassword.all { it == '\u0000' })

            assertEquals(JvmClientJournalOpenResult.Unavailable,
                host.openExistingAs(inputs.owner.copy(actorId = inputs.otherRequester)))
            host.reopenOriginal()
            assertEquals(retained, host.find(inputs.operationId))
            credentials.active = true
            endpoint.view = inputs.sessionView(inputs.otherRequester)
            val otherActorGateway = aawClientGateway(host, endpoint, credentials)
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertIs<LocalActionApprovalResult.Unavailable>(
                otherActorGateway.previewActionApproval(inputs.operationId)).problem)
            assertEquals(LocalServiceProblem.SESSION_ENDED, assertIs<LocalPendingResult.Unavailable>(otherActorGateway.pending()).problem)
            assertEquals(retained, host.find(inputs.operationId))
            assertTrue(endpoint.previews.isEmpty())
            assertTrue(endpoint.executions.isEmpty())
            assertLeaseInputsRetired(credentials)
        }
    }

    @Test
    fun aaw03And05SameIdChangedQuantityOriginalOrderRuleOrTargetCannotReplaceSavedInput() = runTest {
        val inputs = AawClientJournalInputs()
        withArrivalApprovalJournal(inputs.owner) { host ->
            val original = saveClientReadyData(host, inputs)
            val endpoint = ControlledAawClientEndpoint(inputs)
            val gateway = aawClientGateway(host, endpoint)
            val firstLine = inputs.intent.lines.first()
            val changedQuantity = LocalInputLine(firstLine.lineId, firstLine.values.map {
                if (it.field == LocalField.QUANTITY) LocalScalarInput(it.field, LocalScalarValue.Decimal("25")) else it
            })
            val reordered = LocalArrivalEvidenceInputV2(inputs.policy, inputs.catalog, inputs.selection.requesterPolicyRevision,
                inputs.rule, inputs.retention, inputs.documents.reversed(), inputs.selection.formSourceDigest)
            val namedRule = AawClientJournalInputs(AawClientJourney.NAMED_SELF_R_R).rule
            val changedRule = LocalArrivalEvidenceInputV2(inputs.policy, inputs.catalog, inputs.selection.requesterPolicyRevision,
                namedRule, inputs.retention, inputs.documents, inputs.selection.formSourceDigest)
            val mutations = listOf(
                cloneIntent(inputs.intent, lines = listOf(changedQuantity, inputs.intent.lines[1])),
                inputs.originalIntent(reordered), inputs.originalIntent(changedRule),
                cloneIntent(inputs.intent, values = inputs.intent.values.map {
                    if (it.field == LocalField.ARRIVAL_ID) LocalScalarInput(it.field,
                        LocalScalarValue.Identifier(DomainId("a1800000-0000-4000-8000-000000000063"))) else it
                }))
            for (changed in mutations) {
                assertIs<LocalStageResult.Uncertain>(gateway.stage(changed))
                assertEquals(original, host.find(inputs.operationId))
                assertFrozenBody(inputs, host.find(inputs.operationId).request())
            }
            assertTrue(endpoint.reads.isEmpty())
            assertTrue(endpoint.issues.isEmpty())
            assertTrue(endpoint.executions.isEmpty())
            host.reopenOriginal()
            assertEquals(original, host.find(inputs.operationId))
        }
    }

    @Test
    fun aaw01And02ClientReceiptMetadataRetainsAllRoutesWithoutSubstitutingMissingMasterHistory() = runTest {
        for (journey in AawClientJourney.entries) for (step in AawClientStep.entries) {
            val inputs = AawClientJournalInputs(journey, step)
            withArrivalApprovalJournal(inputs.owner) { host ->
                val original = saveClientReadyData(host, inputs)
                val unresolved = inputs.unresolvedData(original)
                assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(original, unresolved))
                val attached = inputs.attachedData(unresolved)
                assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(unresolved, attached))
                val saved = host.find(inputs.operationId)
                assertEquals(attached, saved)
                assertRetainedOriginal(original, saved)
                assertEquals(saved.actionApproval?.approvalId, saved.request().actionApprovalId)
                assertEquals(inputs.approver, saved.actionApproval?.receipt?.approverId)
                assertEquals(inputs.route, saved.actionApproval?.rule?.approvalRoute)
                assertEquals(inputs.ruleDetails, saved.actionApproval?.rule?.ruleDetails)
                assertFrozenBody(inputs, saved.request())
                host.reopenOriginal()
                assertEquals(saved, host.find(inputs.operationId))

                // Client DATA is not sufficient: supplied NotRecorded cannot fill a receipt/history gap.
                val endpoint = ControlledAawClientEndpoint(inputs)
                val gateway = aawClientGateway(host, endpoint)
                val gap = assertIs<LocalActionApprovalResult.Unavailable>(gateway.lookupActionApproval(inputs.operationId))
                assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, gap.problem)
                assertIs<LocalOperationResult.Unresolved>(gateway.submit(inputs.operationId))
                assertEquals(saved, host.find(inputs.operationId))
                assertTrue(endpoint.issues.isEmpty())
                assertTrue(endpoint.executions.isEmpty())
                assertRequesterNotReplaced(inputs, endpoint)
            }
        }
    }

    @Test
    fun aaw05And06ClientTerminalRetentionAndArchiveReopenPreserveExactOriginalData() = runTest {
        for (step in AawClientStep.entries) {
            val inputs = AawClientJournalInputs(step = step)
            withArrivalApprovalJournal(inputs.owner) { host ->
                val original = saveClientReadyData(host, inputs)
                val unresolved = inputs.unresolvedData(original)
                assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(original, unresolved))
                val attached = inputs.attachedData(unresolved)
                assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(unresolved, attached))
                val consumed = inputs.consumedData(attached)
                assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(attached, consumed))
                val terminal = inputs.terminalData()
                // This tests only client storage of supplied result DATA, not a genuine business commit.
                assertEquals(JournalWrite.DURABLE, host.journal.remember(inputs.operationId, terminal))
                val retained = host.pending().single()
                assertEquals(terminal, retained.recorded)
                assertFalse(retained.acknowledged)
                assertRetainedOriginal(original, retained)
                assertFrozenBody(inputs, retained.request())
                host.reopenOriginal()
                assertEquals(retained, host.pending().single())
                val endpoint = ControlledAawClientEndpoint(inputs).also { it.currentDenied = true }
                val gateway = aawClientGateway(host, endpoint)
                assertIs<LocalOperationResult.Unresolved>(gateway.lookup(inputs.operationId))
                assertIs<LocalAcknowledgementResult.Unresolved>(gateway.acknowledgeRecorded(inputs.operationId))
                assertEquals(retained, host.pending().single())
                assertTrue(endpoint.executions.isEmpty())

                // Explicit client-only custody binding, deliberately not the gateway's proof-qualified ACK.
                assertEquals(JournalWrite.DURABLE, host.journal.acknowledge(inputs.operationId, terminal))
                assertTrue(host.pending().isEmpty())
                val archived = host.find(inputs.operationId)
                assertEquals(retained.copy(acknowledged = true), archived)
                host.reopenOriginal()
                assertTrue(host.pending().isEmpty())
                assertEquals(archived, host.find(inputs.operationId))
                assertEquals(terminal, archived.recorded)
                assertEquals(inputs.requester, archived.owner.actorId)
                assertEquals(inputs.operationId, archived.actionApproval?.receipt?.consumedByOperationId)
                assertEquals(inputs.approver, archived.actionApproval?.receipt?.approverId)
                assertFrozenBody(inputs, archived.request())
            }
        }
    }
}

private suspend fun saveClientReadyData(host: SyntheticArrivalApprovalEncryptedJournal,
    inputs: AawClientJournalInputs): StoredClientIntentV1 {
    val original = inputs.stagedData()
    assertEquals(JournalWrite.DURABLE, host.journal.stage(original))
    val ready = inputs.readyData(original)
    assertEquals(JournalWrite.DURABLE, host.journal.updateActionApproval(original, ready))
    return ready
}

private suspend fun issueWithTransientCallerInput(gateway: LocalWorkflowGateway,
    inputs: AawClientJournalInputs): LocalActionApprovalResult {
    val password = "synthetic-transient-input".toCharArray()
    try { return gateway.issueActionApproval(inputs.operationId, inputs.confirmationData,
        if (inputs.journey.selfApprover) "REQUESTER-R" else "APPROVER-A", password) }
    finally { password.fill('\u0000') }
}

private fun assertRetainedOriginal(original: StoredClientIntentV1, current: StoredClientIntentV1) {
    assertEquals(original.owner, current.owner)
    assertEquals(original.operationId, current.operationId)
    assertEquals(original.action, current.action)
    assertEquals(original.formVersion, current.formVersion)
    assertEquals(original.inputDigest, current.inputDigest)
    assertEquals(original.commandName, current.commandName)
    assertEquals(original.commandVersion, current.commandVersion)
    assertEquals(original.payloadHex, current.payloadHex)
    assertEquals(original.semanticSha256, current.semanticSha256)
    assertEquals(original.label, current.label)
    assertEquals(original.summary, current.summary)
    assertEquals(original.actionApproval?.rule, current.actionApproval?.rule)
    assertEquals(original.actionApproval?.preview, current.actionApproval?.preview)
}

private fun assertFrozenBody(inputs: AawClientJournalInputs, request: com.ukkera.pos.database.MutationRequest) {
    assertEquals(inputs.operationId, request.operationId)
    assertEquals(inputs.request.command, request.command)
    assertEquals(inputs.request.payload, request.payload,
        "The exact supplied canonical body is unchanged; this is not a frozen production wire golden.")
    val evidence: LocalArrivalEvidenceSelectionV2 = when (inputs.step) {
        AawClientStep.RECORD -> LocalUnpricedArrivalCommandsV2.decodeRecordRequest(request.payload).let {
            assertEquals(inputs.arrivalId, it.arrivalId)
            assertEquals(inputs.supplier, it.supplierId)
            assertEquals(listOf(inputs.originalLineA, inputs.originalLineB), it.lines.map { line -> line.lineId })
            assertEquals(listOf(24L, 6L), it.lines.map { line -> line.baseQuantity.scaled })
            it.evidence
        }
        AawClientStep.QUALIFY -> LocalUnpricedArrivalCommandsV2.decodeQualifyRequest(request.payload).let {
            assertEquals(inputs.arrivalId, it.arrivalId); assertEquals(10L, it.quantity.scaled)
            assertTrue(it.inspectionPassed); it.evidence
        }
        AawClientStep.REJECT -> LocalUnpricedArrivalCommandsV2.decodeRejectRequest(request.payload).let {
            assertEquals(inputs.arrivalId, it.arrivalId); assertEquals(4L, it.quantity.scaled); it.evidence
        }
        AawClientStep.DISPATCH -> LocalUnpricedArrivalCommandsV2.decodeDispatchRequest(request.payload).let {
            assertEquals(inputs.arrivalId, it.arrivalId); assertEquals(3L, it.quantity.scaled)
            assertEquals(inputs.supplier, it.recipientSupplierId); it.evidence
        }
    }
    assertEquals(inputs.ruleId, evidence.ruleId)
    assertEquals(inputs.documents.map { it.documentId }, evidence.documents.map { it.documentId })
    assertEquals(listOf(614, 613), evidence.documents.map { it.byteLength })
    assertEquals(inputs.documents.map { it.exactBytesDigest }, evidence.documents.map { it.exactBytesDigest })
    assertEquals(inputs.retention.classId, evidence.retention.classId)
    assertEquals(inputs.retention.revision, evidence.retention.revision)
}

private fun assertRequesterNotReplaced(inputs: AawClientJournalInputs, endpoint: ControlledAawClientEndpoint) {
    assertEquals(inputs.requester, endpoint.view.actorId)
    assertEquals(0, endpoint.signIns)
    assertEquals(0, endpoint.refreshes)
    assertEquals(0, endpoint.signOuts)
}

private fun assertLeaseInputsRetired(credentials: AawClientCredentialInputs) {
    assertTrue(credentials.issued.isNotEmpty())
    credentials.issued.forEach { lease -> assertFailsWith<IllegalStateException> { lease.credentials.bearer.useBytes { } } }
}

private suspend fun assertNoPrivateAuthenticationInStorage(host: SyntheticArrivalApprovalEncryptedJournal,
    requesterMarker: String, login: String, passwordMarker: String) {
    host.storedPlaintexts().forEach { persisted ->
        assertFalse(persisted.contains(requesterMarker))
        assertFalse(persisted.contains(login))
        assertFalse(persisted.contains(passwordMarker))
        assertFalse(persisted.contains("\"approverPassword\""))
        assertFalse(persisted.contains("\"bearer\""))
    }
}

private fun cloneIntent(original: LocalCommandIntent, values: List<LocalScalarInput> = original.values,
    lines: List<LocalInputLine> = original.lines): LocalCommandIntent = LocalCommandIntent(original.operationId,
    original.action, original.formVersion, original.reference, values, original.lineField, lines, original.arrivalEvidence)
