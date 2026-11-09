@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.supplierrpcworkflow

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Source-informed wiring of unchanged supplier-rpc-workflow v1 expectations over the shared
 * application/session/workflow implementation. Every external service and custody cut below is
 * a controlled SUPPORT port. There is no native original, admitted peer, real password check,
 * SDK exchange, database transaction, durable reopen or independently observed supplier effect.
 * These methods cannot pass any of the 52 whole APP/RPC/STORE/DEVICE/OWNER cases.
 *
 * Bound portions: SWF-002/004/005/006/008/009/010/014/018/020/023/024/025/027/028/029/031/032/
 * 033/034/035/039/044. Numeric DTO carriers are not storage-revision, no-op, sort/page-fill,
 * name-normalization, wire-budget or lifetime oracles; Q01–Q05 remain unresolved. Actual
 * physicalStart/native-output qualification and wire/framing variants are not constructed here.
 */
class SupplierRpcGatewaySupportingTest {
    @Test
    fun swf005CredentialRetentionAndTwoFreshCurrentsPrecedeJournalPublication() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val retain = SupplierGate().also { f.credentials.gates[2] = it }
            val acquire = SupplierGate().also { f.source.gate = it }
            val confirm = SupplierGate().also { f.master.currentGates[2] = it }
            val login = async { f.signIn() }
            runCurrent()
            assertTrue(retain.entered.isCompleted)
            assertEquals(0, f.master.currentCount)
            assertTrue(f.source.acquiredOwners.isEmpty())
            assertFalse(login.isCompleted)
            retain.release()
            runCurrent()
            assertTrue(acquire.entered.isCompleted)
            assertEquals(1, f.master.currentCount)
            assertEquals(listOf(SupplierFrozen.owner), f.source.acquiredOwners)
            assertEquals(listOf(SupplierFrozen.connection), f.source.borrowed.map { it.connectionId })
            assertFalse(login.isCompleted)
            acquire.release()
            runCurrent()
            assertTrue(confirm.entered.isCompleted)
            assertSame(f.journal, f.source.lastSelected)
            assertFalse(login.isCompleted)
            confirm.release()
            assertTrue(assertIs<SignInResult.Authenticated<BranchUiSession>>(login.await()).session.isCurrent())
            assertTrue(f.events.indexOf("credentials.2.retained") < f.events.indexOf("current.1.enter"))
            assertTrue(f.events.indexOf("current.1.return") < f.events.indexOf("intents.acquire.enter"))
            assertTrue(f.events.indexOf("intents.current") < f.events.indexOf("current.2.enter"))
            f.assertBorrowedCredentialsDestroyed()
            f.credentials.inspect { assertEquals(SupplierFrozen.owner, it.sessions.single().identity) }
        }
    }

    @Test
    fun swf005FailedCredentialRetentionCannotAcquireOrPublishSupplierWork() = runTest(timeout = 5.seconds) {
        for (cut in listOf(SupplierWriteCut.KNOWN_FAILURE, SupplierWriteCut.COMMITTED_BUT_UNKNOWN)) {
            withSupplier { f ->
                f.credentials.faults[2] = cut
                assertIs<SignInResult.Rejected>(f.signIn())
                assertEquals(1, f.master.loginCount)
                assertEquals(0, f.master.currentCount)
                assertTrue(f.source.acquiredOwners.isEmpty())
                assertTrue(f.master.mutations.isEmpty())
                f.gateway.closeAndAwait()
                val successor = f.newGateway()
                runCurrent()
                assertEquals(1, f.master.loginCount)
                assertEquals(0, f.master.refreshCount)
                assertTrue(f.master.mutations.isEmpty())
                successor.closeAndAwait()
            }
        }
        // Reusing controlled snapshots is not protected storage/process reopen.
    }

    @Test
    fun swf004WrongCredentialDeviceOrBranchStopsBeforeLogin() = runTest(timeout = 5.seconds) {
        for (owner in listOf(SupplierFrozen.deviceOwner.copy(deviceId = SupplierFrozen.otherDevice),
            SupplierFrozen.deviceOwner.copy(scope = SupplierFrozen.otherScope))) {
            withSupplier(credentialOwner = owner) { f ->
                assertIs<SignInResult.Rejected>(f.signIn())
                assertEquals(0, f.master.loginCount)
                assertEquals(0, f.credentials.writeCount)
                assertTrue(f.source.acquiredOwners.isEmpty())
            }
        }
    }

    @Test
    fun swf005FreshCurrentRefusalOrIssuedIdentityMismatchPreventsAcquisition() = runTest(timeout = 5.seconds) {
        val replies = listOf(LocalSessionViewResult.Denied, LocalSessionViewResult.Unavailable,
            LocalSessionViewResult.Current(SupplierFrozen.view(actor = SupplierFrozen.actorB)),
            LocalSessionViewResult.Current(SupplierFrozen.view(device = SupplierFrozen.otherDevice)),
            LocalSessionViewResult.Current(SupplierFrozen.view(scope = SupplierFrozen.otherScope,
                grants = SupplierFrozen.grants().map { PermissionGrant(it.permission, SupplierFrozen.otherScope,
                    it.effect, it.limits, it.ownerAccess, it.namedSubjectId, it.targetIds, it.delegationCeiling) })),
            LocalSessionViewResult.Current(SupplierFrozen.view(session = SupplierFrozen.successorSession)))
        for (reply in replies) withSupplier { f ->
            f.master.currentReply = { reply }
            assertIs<SignInResult.Rejected>(f.signIn())
            assertTrue(f.source.acquiredOwners.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            f.assertBorrowedCredentialsDestroyed()
        }
    }

    @Test
    fun swf004AuthenticatedAcquisitionCannotBorrowAnotherJournalOwner() = runTest(timeout = 5.seconds) {
        for (owner in listOf(SupplierFrozen.owner.copy(actorId = SupplierFrozen.actorB),
            SupplierFrozen.owner.copy(deviceId = SupplierFrozen.otherDevice),
            SupplierFrozen.owner.copy(scope = SupplierFrozen.otherScope))) {
            withSupplier { f ->
                val foreign = f.ownJournal(owner)
                f.source.acquireReply = { foreign }
                assertIs<SignInResult.Rejected>(f.signIn())
                assertTrue(f.source.selectedOwners.isEmpty())
                assertEquals(0, foreign.stageCount)
                assertTrue(f.master.mutations.isEmpty())
                f.assertBorrowedCredentialsDestroyed()
            }
        }
    }

    @Test
    fun swf004SameOwnerJournalReplacementCannotSubstituteForAuthenticatedAcquisition() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val substitute = f.ownJournal(SupplierFrozen.owner)
            f.source.currentReply = { substitute }
            assertIs<SignInResult.Rejected>(f.signIn())
            assertEquals(listOf(SupplierFrozen.owner), f.source.selectedOwners)
            assertEquals(1, f.master.currentCount)
            assertEquals(0, substitute.stageCount)
            f.assertBorrowedCredentialsDestroyed()
        }
    }

    @Test
    fun swf005And006RetirementOrSameCoordinateSuccessorDuringAcquisitionCannotPublish() = runTest(timeout = 5.seconds) {
        for (replacement in 0..2) withSupplier { f ->
            val gate = SupplierGate().also { f.source.gate = it }
            val login = async { f.signIn() }
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            when (replacement) {
                0 -> f.originalCurrent = false
                1 -> f.connections.value = null
                else -> f.connections.value = f.sameCoordinateSuccessor()
            }
            gate.release()
            assertIs<SignInResult.Rejected>(login.await())
            assertTrue(f.master.mutations.isEmpty())
            f.assertBorrowedCredentialsDestroyed()
        }
        // Coordinate/reference comparison is not an actual SDK physical-lifetime check.
    }

    @Test
    fun swf005PostAcquisitionCurrentMustStillMatchTheExactIssuedSession() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val gate = SupplierGate().also { f.source.gate = it }
            val login = async { f.signIn() }
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            f.master.currentReply = { LocalSessionViewResult.Current(SupplierFrozen.view(session = SupplierFrozen.successorSession)) }
            gate.release()
            assertIs<SignInResult.Rejected>(login.await())
            assertEquals(2, f.master.currentCount)
            assertSame(f.journal, f.source.lastSelected)
            assertTrue(f.master.mutations.isEmpty())
            f.assertBorrowedCredentialsDestroyed()
        }
    }

    @Test
    fun swf002SupplierAttachmentRefusesUnsupportedReadsActionsAndApprovalRoutes() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val workflow = f.session().workflows
            val queries = listOf(LocalWorkflowQuery(LocalWorkflow.CATALOG), LocalWorkflowQuery(LocalWorkflow.CASH),
                LocalWorkflowQuery(LocalWorkflow.PURCHASING, purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.INVOICES)),
                LocalWorkflowQuery(LocalWorkflow.PURCHASING, purchasing = LocalPurchasingUiQuery(includeCosts = true)),
                SupplierFrozen.query(reference = LocalRecordReference(LocalRecordKind.WAREHOUSE, SupplierFrozen.s1)),
                SupplierFrozen.query(reference = LocalRecordReference(LocalRecordKind.SUPPLIER, SupplierFrozen.s1,
                    parentId = SupplierFrozen.s2)))
            for (query in queries) assertEquals(LocalServiceProblem.NOT_IMPLEMENTED,
                assertIs<LocalReadResult.Unavailable>(workflow.read(query)).problem)
            for (action in listOf(LocalAction.DEVICE_PAIR, LocalAction.PURCHASE_CREATE, LocalAction.SUPPLIER_INVOICE_RECORD,
                LocalAction.SUPPLIER_CASH_START, LocalAction.WAREHOUSE_CONFIGURE)) {
                val intent = LocalCommandIntent(SupplierFrozen.o, action, 1, null, emptyList())
                assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, assertIs<LocalStageResult.NotStaged>(workflow.stage(intent)).problem)
            }
            assertIs<LocalActionApprovalResult.Unavailable>(workflow.previewActionApproval(SupplierFrozen.o))
            assertTrue(f.master.reads.isEmpty())
            assertTrue(f.master.lookups.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
            assertEquals(0, f.journal.stageCount)
        }
    }

    @Test
    fun swf008SharedBrowseAndDetailPreserveBothNamesAndActiveWithoutMutation() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val workflow = f.session().workflows
            val browse = assertIs<LocalReadResult.Available>(workflow.read(SupplierFrozen.query())).page
            assertEquals(SupplierFrozen.suppliers.map { it.id }, browse.records.map { it.reference.id })
            assertEquals(SupplierFrozen.suppliers.map { LocalText(it.names.english, it.names.arabic) }, browse.records.map { it.title })
            assertEquals(listOf(LocalAction.SUPPLIER_CREATE), browse.forms.map { it.action })
            val detail = assertIs<LocalReadResult.Available>(workflow.read(SupplierFrozen.query(
                reference = LocalRecordReference(LocalRecordKind.SUPPLIER, SupplierFrozen.s1)))).page
            assertEquals(SupplierFrozen.suppliers.first().names.english, detail.records.single().title.english)
            assertEquals(SupplierFrozen.suppliers.first().names.arabic, detail.records.single().title.arabic)
            assertEquals(listOf(LocalAction.SUPPLIER_UPDATE), detail.forms.map { it.action })
            val active = detail.forms.single().fields.filterIsInstance<LocalFormField.Scalar>().single { it.field == LocalField.ACTIVE }
            assertEquals(LocalScalarValue.Flag(true), active.initialValue)
            assertTrue(f.master.reads.all { it.query == LocalConfigurationProfile.QUERY })
            assertTrue(f.master.lookups.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
        }
        // The workflow's bounded browser is not the frozen limit-50/limit-1 walk (SWF-012).
    }

    @Test
    fun swf008MasterReadRefusalIsNotAnEmptySuccessDespiteOtherClientHints() = runTest(timeout = 5.seconds) {
        for (reason in listOf(ReadFailure.AUTHORIZATION_DENIED, ReadFailure.WRONG_SCOPE,
            ReadFailure.STORAGE_UNCERTAIN, ReadFailure.WRITER_NOT_QUALIFIED)) withSupplier { f ->
            val workflow = f.session().workflows
            f.master.readReply = { ReadReply.Unavailable(reason) }
            assertIs<LocalReadResult.Unavailable>(workflow.read(SupplierFrozen.query()))
            assertIs<LocalReadResult.Unavailable>(workflow.read(SupplierFrozen.query(
                reference = LocalRecordReference(LocalRecordKind.SUPPLIER, SupplierFrozen.s1))))
            assertTrue(f.master.mutations.isEmpty())
        }
        // Scripted port refusals do not prove production grant enforcement or output fencing.
    }

    @Test
    fun swf009ReadRecoveryAndExplicitlyDeniedAdminHintsCannotStageNewSupplierWork() = runTest(timeout = 5.seconds) {
        val readRecovery = SupplierFrozen.grants(read = true, admin = false, recovery = true)
        val deniedAdmin = SupplierFrozen.grants() + SupplierFrozen.grant(SecurityAction.SUPPLIER_ADMINISTRATION,
            PermissionVerb.ADMINISTER, GrantEffect.DENY)
        for (grants in listOf(readRecovery, deniedAdmin)) withSupplier { f ->
            f.master.activeView = SupplierFrozen.view(grants = grants)
            val workflow = f.session().workflows
            assertEquals(LocalServiceProblem.DENIED, assertIs<LocalStageResult.NotStaged>(workflow.stage(SupplierFrozen.update())).problem)
            assertEquals(0, f.journal.stageCount)
            assertTrue(f.master.mutations.isEmpty())
            assertTrue(f.master.lookups.isEmpty())
        }
    }

    @Test
    fun swf010ReadAdminHintsCannotTurnDeniedOriginalRecoveryIntoDisclosureOrDispatch() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            f.master.activeView = SupplierFrozen.view(grants = SupplierFrozen.grants(recovery = false))
            val workflow = f.session().workflows
            assertIs<LocalStageResult.Staged>(workflow.stage(SupplierFrozen.update()))
            f.master.lookupReply = { LookupReply.Deferred(it.operationId, DeferredReason.AUTHORIZATION_DENIED) }
            assertEquals(LocalServiceProblem.DENIED, assertIs<LocalOperationResult.Unresolved>(workflow.lookup(SupplierFrozen.o)).problem)
            assertEquals(LocalServiceProblem.DENIED, assertIs<LocalOperationResult.Unresolved>(workflow.submit(SupplierFrozen.o)).problem)
            assertEquals(LocalServiceProblem.DENIED, assertIs<LocalPendingResult.Unavailable>(workflow.pending()).problem)
            assertEquals(0, f.journal.dispatchCount)
            assertEquals(0, f.journal.rememberCount)
            assertTrue(f.master.mutations.isEmpty())
        }
        // The complete direct grant matrix, including admin-only mutation, remains RPC/STORE work.
    }

    @Test
    fun swf023CreateAndUpdateStageCutsNeverInvokeTheRemoteMutator() = runTest(timeout = 5.seconds) {
        for (intent in listOf(SupplierFrozen.create(), SupplierFrozen.update())) {
            for (cut in listOf(SupplierWriteCut.DURABLE, SupplierWriteCut.KNOWN_FAILURE, SupplierWriteCut.COMMITTED_BUT_UNKNOWN)) {
                withSupplier { f ->
                    val workflow = f.session().workflows
                    f.journal.stageCut = cut
                    val gate = SupplierGate().also { f.journal.stageGate = it }
                    val stage = async { workflow.stage(intent) }
                    runCurrent()
                    assertTrue(gate.entered.isCompleted)
                    assertFalse(stage.isCompleted)
                    assertTrue(f.master.mutations.isEmpty())
                    gate.release()
                    val result = stage.await()
                    if (cut == SupplierWriteCut.DURABLE) assertIs<LocalStageResult.Staged>(result)
                    else assertIs<LocalStageResult.Uncertain>(result)
                    assertTrue(f.master.mutations.isEmpty())
                    if (cut == SupplierWriteCut.KNOWN_FAILURE) assertNull(f.journal.observe(intent.operationId))
                    else SupplierFrozen.assertSaved(intent, assertNotNull(f.journal.observe(intent.operationId)))
                }
            }
        }
    }

    @Test
    fun swf024DispatchMustSettleBeforeTheSameSavedCreateOrUpdateIsInvoked() = runTest(timeout = 5.seconds) {
        for (intent in listOf(SupplierFrozen.create(), SupplierFrozen.update())) withSupplier { f ->
            val workflow = f.session().workflows
            assertIs<LocalStageResult.Staged>(workflow.stage(intent))
            val original = assertNotNull(f.journal.observe(intent.operationId))
            f.journal.dispatchCut = SupplierWriteCut.KNOWN_FAILURE
            val gate = SupplierGate().also { f.journal.dispatchGate = it }
            val submit = async { workflow.submit(intent.operationId) }
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            assertTrue(f.master.mutations.isEmpty())
            assertEquals(original.identity(), f.master.lookups.single())
            gate.release()
            assertIs<LocalOperationResult.Unresolved>(submit.await())
            assertEquals(original, f.journal.observe(intent.operationId))
            f.journal.dispatchCut = SupplierWriteCut.DURABLE
            assertIs<LocalOperationResult.Unresolved>(workflow.submit(intent.operationId))
            val request = f.master.mutations.single()
            assertEquals(original.operationId, request.operationId)
            assertEquals(original.request().command, request.command)
            assertEquals(original.request().payload, request.payload)
            assertTrue(f.events.indexOf("dispatch.retained") < f.events.indexOf("execute.enter"))
            runCurrent()
            assertEquals(1, f.master.mutations.size)
            SupplierFrozen.assertSaved(intent, assertNotNull(f.journal.observe(intent.operationId)))
        }
    }

    @Test
    fun swf018And020RepeatedOrEditedUiInputCannotRewriteOrMintOverTheSavedOriginal() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val workflow = f.session().workflows
            val input = SupplierFrozen.update()
            assertIs<LocalStageResult.Staged>(workflow.stage(input))
            val saved = assertNotNull(f.journal.observe(input.operationId))
            assertIs<LocalStageResult.Staged>(workflow.stage(input))
            for (changed in listOf(SupplierFrozen.update(names = SupplierFrozen.created.names),
                SupplierFrozen.update(active = true), SupplierFrozen.update(target = SupplierFrozen.s1),
                SupplierFrozen.update(expected = Revision(SupplierFrozen.r0.value + 1)), SupplierFrozen.create(),
                SupplierFrozen.update(version = 2))) {
                assertIs<LocalStageResult.Uncertain>(workflow.stage(changed))
                assertEquals(saved, f.journal.observe(input.operationId))
            }
            assertEquals(LocalServiceProblem.RETRY_LATER, assertIs<LocalStageResult.NotStaged>(
                workflow.stage(SupplierFrozen.update(operation = SupplierFrozen.o2))).problem)
            assertEquals(1, f.journal.stageCount)
            assertTrue(f.master.mutations.isEmpty())
            assertEquals(setOf(SupplierFrozen.o), f.journal.originalIds())
        }
    }

    @Test
    fun swf029LookupAbsenceOrFailureNeverDispatchesOrPermanentlyCachesAnAbsence() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val workflow = f.session().workflows
            assertIs<LocalStageResult.Staged>(workflow.stage(SupplierFrozen.update()))
            assertIs<LocalOperationResult.Pending>(workflow.lookup(SupplierFrozen.o))
            assertEquals(0, f.journal.dispatchCount)
            f.master.lookupReply = { LookupReply.Deferred(it.operationId, DeferredReason.STORAGE_UNCERTAIN) }
            assertIs<LocalOperationResult.Unresolved>(workflow.lookup(SupplierFrozen.o))
            assertIs<LocalOperationResult.Unresolved>(workflow.submit(SupplierFrozen.o))
            f.master.lookupReply = { LookupReply.AbsentAtCurrentWriter(SupplierFrozen.o2) }
            assertIs<LocalOperationResult.Unresolved>(workflow.lookup(SupplierFrozen.o))
            assertIs<LocalOperationResult.Unresolved>(workflow.submit(SupplierFrozen.o))
            assertEquals(0, f.journal.dispatchCount)
            assertTrue(f.master.mutations.isEmpty())
            assertEquals(setOf(SupplierFrozen.o), f.journal.originalIds())
        }
        // These DATA constructors do not supply authoritative Master absence.
    }

    @Test
    fun swf027UnknownDeliveryRecoversTheExactOriginalWithoutAnotherInvocation() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val workflow = f.session().workflows
            assertIs<LocalStageResult.Staged>(workflow.stage(SupplierFrozen.update()))
            val original = assertNotNull(f.journal.observe(SupplierFrozen.o))
            assertIs<LocalOperationResult.Unresolved>(workflow.submit(SupplierFrozen.o))
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, f.journal.observe(SupplierFrozen.o)?.localPhase)
            val result = SupplierFrozen.recorded()
            f.master.lookupReply = { LookupReply.Found(result) }
            val recovered = assertIs<LocalOperationResult.Recorded>(workflow.lookup(SupplierFrozen.o))
            assertEquals(SupplierFrozen.o, recovered.operationId)
            assertEquals(LocalRecordedDecision.APPLIED, recovered.decision)
            assertTrue(recovered.replayed)
            assertEquals(StoredKernelResultV1.from(result), f.journal.observe(SupplierFrozen.o)?.recorded)
            assertIs<LocalOperationResult.Recorded>(workflow.submit(SupplierFrozen.o))
            assertEquals(1, f.master.mutations.size)
            assertTrue(f.master.lookups.all { it == original.identity() })
            SupplierFrozen.assertSaved(SupplierFrozen.update(), assertNotNull(f.journal.observe(SupplierFrozen.o)))
        }
        // Invocation count and scripted original-result identity are not committed effect counts.
    }

    @Test
    fun swf028FailedClientResultRetentionPreservesOriginalAndPreventsAcknowledgement() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val workflow = f.session().workflows
            assertIs<LocalStageResult.Staged>(workflow.stage(SupplierFrozen.update()))
            f.journal.rememberCut = SupplierWriteCut.KNOWN_FAILURE
            f.master.executeReply = { SupplierFrozen.recorded(replayed = false) }
            workflow.submit(SupplierFrozen.o)
            val retained = assertNotNull(f.journal.observe(SupplierFrozen.o))
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, retained.localPhase)
            assertNull(retained.recorded)
            assertIs<LocalAcknowledgementResult.Unresolved>(workflow.acknowledgeRecorded(SupplierFrozen.o))
            assertTrue(f.journal.cleared.isEmpty())
            f.journal.rememberCut = SupplierWriteCut.DURABLE
            f.master.lookupReply = { LookupReply.Found(SupplierFrozen.recorded()) }
            assertIs<LocalOperationResult.Recorded>(workflow.lookup(SupplierFrozen.o))
            assertEquals(1, f.master.mutations.size)
            SupplierFrozen.assertSaved(SupplierFrozen.update(), assertNotNull(f.journal.observe(SupplierFrozen.o)))
        }
        // Controlled journal refusal only, not a genuine persistence-failure observation.
        // The SAOL-06 slices below separately require qualified result delivery to wait for retention.
    }

    /**
     * Frozen acknowledged-original-lookup v1 SAOL-06, supporting boundary only.
     * Existing SWF input carriers and scripted terminal replies are reused; this
     * does not execute F-COMMITTED or prove a native write failure/Master commit.
     */
    @Test
    fun saol06FailedSupplierResultRetentionSuppressesSubmitAndLookupUntilExplicitRecovery() = runTest(timeout = 5.seconds) {
        for (supplierOnly in listOf(false, true)) {
            for (intent in listOf(SupplierFrozen.create(), SupplierFrozen.update())) {
                for (cut in listOf(SupplierWriteCut.KNOWN_FAILURE, SupplierWriteCut.COMMITTED_BUT_UNKNOWN)) withSupplier { f ->
                    val label = "${intent.action}, supplierOnly=$supplierOnly, $cut"
                    val workflow = f.workflowForRetention(supplierOnly)
                    assertIs<LocalStageResult.Staged>(workflow.stage(intent), label)
                    val original = assertNotNull(f.journal.observe(intent.operationId))
                    val create = intent.action == LocalAction.SUPPLIER_CREATE
                    val target = if (create) SupplierFrozen.created.id else SupplierFrozen.updated.id
                    val revision = if (create) Revision(0) else SupplierFrozen.r0
                    val terminal = SupplierFrozen.recorded(operation = intent.operationId, target = target,
                        revision = revision, replayed = false)
                    f.master.executeReply = {
                        f.master.lookupReply = { LookupReply.Found(terminal) }
                        terminal
                    }
                    f.journal.rememberCut = cut

                    val submitted = assertIs<LocalOperationResult.Unresolved>(workflow.submit(intent.operationId), label)
                    assertEquals(intent.operationId, submitted.operationId, label)
                    assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, submitted.problem, label)
                    assertTrue(f.journal.rememberCount > 0, label)
                    val failedLookup = assertIs<LocalOperationResult.Unresolved>(workflow.lookup(intent.operationId), label)
                    assertEquals(intent.operationId, failedLookup.operationId, label)
                    assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, failedLookup.problem, label)
                    val retained = assertNotNull(f.journal.observe(intent.operationId))
                    SupplierFrozen.assertSaved(intent, retained)
                    assertFalse(retained.acknowledged, label)
                    if (cut.retain) assertEquals(StoredKernelResultV1.from(terminal), retained.recorded, label)
                    else assertNull(retained.recorded, label)
                    assertEquals(0, f.journal.ackCount, label)
                    assertTrue(f.journal.cleared.isEmpty(), label)
                    assertEquals(1, f.journal.stageCount, label)
                    assertEquals(1, f.journal.dispatchCount, label)
                    val sent = f.master.mutations.single()
                    val expectedRequest = original.request()
                    assertEquals(expectedRequest.operationId, sent.operationId, label)
                    assertEquals(expectedRequest.command, sent.command, label)
                    assertEquals(expectedRequest.payload, sent.payload, label)
                    assertNull(sent.actionApprovalId, label)
                    assertEquals(setOf(intent.operationId), f.journal.originalIds(), label)

                    val lookupCount = f.master.lookups.size
                    val rememberCount = f.journal.rememberCount
                    f.journal.rememberCut = SupplierWriteCut.DURABLE
                    runCurrent()
                    assertEquals(lookupCount, f.master.lookups.size, label)
                    assertEquals(rememberCount, f.journal.rememberCount, label)
                    assertEquals(0, f.journal.ackCount, label)
                    val recovered = assertIs<LocalOperationResult.Recorded>(workflow.lookup(intent.operationId), label)
                    assertEquals(intent.operationId, recovered.operationId, label)
                    assertEquals(intent.action, recovered.originalAction, label)
                    assertEquals(LocalRecordedDecision.APPLIED, recovered.decision, label)
                    assertEquals(LocalRecordReference(LocalRecordKind.SUPPLIER, target, revision),
                        assertNotNull(recovered.result).reference, label)
                    assertEquals(StoredKernelResultV1.from(terminal), f.journal.observe(intent.operationId)?.recorded, label)
                    assertEquals(0, f.journal.ackCount, label)
                    assertIs<LocalAcknowledgementResult.Acknowledged>(workflow.acknowledgeRecorded(intent.operationId), label)
                    assertEquals(listOf(intent.operationId), f.journal.cleared, label)
                    assertSame(sent, f.master.mutations.single(), label)
                    assertEquals(1, f.journal.stageCount, label)
                    assertEquals(1, f.journal.dispatchCount, label)
                    assertEquals(setOf(intent.operationId), f.journal.originalIds(), label)
                    assertTrue(f.master.lookups.all { it == original.identity() }, label)
                    SupplierFrozen.assertSaved(intent, assertNotNull(f.journal.observe(intent.operationId)))
                    f.assertBorrowedCredentialsDestroyed()
                }
            }
        }
        // Exact supplied result recovery and call counts are not durable supplier/effect evidence.
    }

    @Test
    fun saol06FailedAcknowledgedOriginalLookupDoesNotEraseHistoryRestageExecuteOrAck() = runTest(timeout = 5.seconds) {
        for (supplierOnly in listOf(false, true)) {
            for (intent in listOf(SupplierFrozen.create(), SupplierFrozen.update())) {
                for (cut in listOf(SupplierWriteCut.KNOWN_FAILURE, SupplierWriteCut.COMMITTED_BUT_UNKNOWN)) withSupplier { f ->
                    val label = "${intent.action}, supplierOnly=$supplierOnly, $cut"
                    val workflow = f.workflowForRetention(supplierOnly)
                    assertIs<LocalStageResult.Staged>(workflow.stage(intent), label)
                    val create = intent.action == LocalAction.SUPPLIER_CREATE
                    val terminal = SupplierFrozen.recorded(operation = intent.operationId,
                        target = if (create) SupplierFrozen.created.id else SupplierFrozen.updated.id,
                        revision = if (create) Revision(0) else SupplierFrozen.r0)
                    f.master.lookupReply = { LookupReply.Found(terminal) }
                    val first = assertIs<LocalOperationResult.Recorded>(workflow.lookup(intent.operationId), label)
                    assertIs<LocalAcknowledgementResult.Acknowledged>(workflow.acknowledgeRecorded(intent.operationId), label)
                    val acknowledged = assertNotNull(f.journal.observe(intent.operationId))
                    assertTrue(acknowledged.acknowledged, label)
                    assertEquals(StoredKernelResultV1.from(terminal), acknowledged.recorded, label)
                    val ackCount = f.journal.ackCount
                    f.journal.rememberCut = cut

                    val failed = assertIs<LocalOperationResult.Unresolved>(workflow.lookup(intent.operationId), label)
                    assertEquals(intent.operationId, failed.operationId, label)
                    assertEquals(LocalServiceProblem.STORAGE_UNCERTAIN, failed.problem, label)
                    assertEquals(acknowledged, f.journal.observe(intent.operationId), label)
                    assertEquals(ackCount, f.journal.ackCount, label)
                    assertEquals(listOf(intent.operationId), f.journal.cleared, label)
                    val lookupCount = f.master.lookups.size
                    f.journal.rememberCut = SupplierWriteCut.DURABLE
                    runCurrent()
                    assertEquals(lookupCount, f.master.lookups.size, label)
                    assertEquals(ackCount, f.journal.ackCount, label)
                    assertEquals(first, assertIs<LocalOperationResult.Recorded>(workflow.lookup(intent.operationId), label), label)
                    assertEquals(acknowledged, f.journal.observe(intent.operationId), label)
                    assertEquals(ackCount, f.journal.ackCount, label)
                    assertEquals(1, f.journal.stageCount, label)
                    assertEquals(0, f.journal.dispatchCount, label)
                    assertTrue(f.master.mutations.isEmpty(), label)
                    assertEquals(setOf(intent.operationId), f.journal.originalIds(), label)
                    assertTrue(f.master.lookups.all { it == acknowledged.identity() }, label)
                    SupplierFrozen.assertSaved(intent, assertNotNull(f.journal.observe(intent.operationId)))
                    f.assertBorrowedCredentialsDestroyed()
                }
            }
        }
        // The earlier terminal/ACK prefix is controlled, not rendered presentation or native history.
    }

    @Test
    fun swf044InvalidCompleteSupplierPagesCannotBecomePartialOrFilteredEmptySuccess() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val workflow = f.session().workflows
            val rows = SupplierFrozen.suppliers.map(SupplierFrozen::row)
            val base = LocalConfigurationPageV1(LocalConfigurationProfile.SUPPLIER, rows, null)
            val badPages = listOf(base.copy(kind = LocalConfigurationProfile.WAREHOUSE),
                base.copy(items = listOf(rows[0], rows[1].copy(names = null))),
                base.copy(items = listOf(rows[0], rows[1].copy(active = null))),
                base.copy(items = listOf(rows[0], rows[1].copy(code = "undeclared"))),
                base.copy(items = listOf(rows[0], rows[0])), base.copy(items = rows.reversed()),
                base.copy(nextAfter = SupplierFrozen.s2),
                base.copy(detail = LocalConfigurationDetailV1.Supplier(SupplierFrozen.suppliers.first())),
                base.copy(items = List(51) { index -> rows[0].copy(id = SupplierFrozen.slot("oversized-row-$index")) }))
            for (page in badPages) {
                f.master.readReply = { SupplierFrozen.data(page) }
                assertIs<LocalReadResult.Unavailable>(workflow.read(SupplierFrozen.query(search = "not-a-visible-name")))
                assertTrue(f.master.mutations.isEmpty())
            }
            val selected = SupplierFrozen.suppliers.first()
            for (detail in listOf<LocalConfigurationDetailV1>(LocalConfigurationDetailV1.Supplier(selected.copy(id = SupplierFrozen.s2)),
                LocalConfigurationDetailV1.Supplier(selected.copy(active = false)),
                LocalConfigurationDetailV1.Warehouse(LocalWarehouseV1(selected.id, selected.names, selected.active)))) {
                f.master.readReply = { SupplierFrozen.data(base.copy(items = listOf(rows.first()), detail = detail)) }
                assertIs<LocalReadResult.Unavailable>(workflow.read(SupplierFrozen.query(
                    reference = LocalRecordReference(LocalRecordKind.SUPPLIER, SupplierFrozen.s1))))
            }
            f.master.readReply = { ReadReply.Data(StoredBytes("{\"unsupported\":true}".encodeToByteArray()),
                SupplierFrozen.readRevision, SupplierFrozen.at) }
            assertIs<LocalReadResult.Unavailable>(workflow.read(SupplierFrozen.query()))
        }
    }

    @Test
    fun swf014UnsupportedBrowserAndPendingCursorsDoNotProduceEmptySuccess() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val workflow = f.session().workflows
            for (cursor in listOf("unsupported", "cfg|${LocalConfigurationProfile.WAREHOUSE}|-",
                "cfg|${LocalConfigurationProfile.SUPPLIER}|not-a-uuid")) {
                assertIs<LocalReadResult.Unavailable>(workflow.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING, cursor = cursor)))
            }
            for (cursor in listOf("unsupported", "p1:", "p1:not-a-uuid")) {
                assertIs<LocalPendingResult.Unavailable>(workflow.pending(cursor))
            }
            assertTrue(f.master.reads.isEmpty())
            assertTrue(f.master.lookups.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
        }
        // Exact cursor taxonomy, shape/lifetime qualification and page-fill remain Q03.
    }

    @Test
    fun swf044UnsupportedOrCorruptOriginalsAreValidatedBeforePendingCursorFiltering() = runTest(timeout = 5.seconds) {
        val warehouse = LocalConfigurationCodec.encode(LocalConfigurationRequestV1.ConfigureWarehouse(null,
            LocalWarehouseV1(SupplierFrozen.s2, SupplierFrozen.created.names, true)))
        val malformed = SupplierFrozen.encodeHex("not a supported original".encodeToByteArray())
        for (variant in 0..5) withSupplier { f ->
            val workflow = f.session().workflows
            assertIs<LocalStageResult.Staged>(workflow.stage(SupplierFrozen.update()))
            val saved = assertNotNull(f.journal.observe(SupplierFrozen.o))
            val hidden = when (variant) {
                0 -> saved.copy(operationId = SupplierFrozen.o2, payloadHex = SupplierFrozen.hex(warehouse))
                1 -> saved.copy(operationId = SupplierFrozen.o2, formVersion = 2)
                2 -> saved.copy(operationId = SupplierFrozen.o2, owner = SupplierFrozen.owner.copy(actorId = SupplierFrozen.actorB))
                3 -> saved.copy(operationId = SupplierFrozen.o2, payloadHex = malformed)
                4 -> saved.copy(operationId = SupplierFrozen.o2, semanticSha256 = PayloadDigest("0".repeat(64)))
                else -> saved.copy(operationId = SupplierFrozen.o2, phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name,
                    recorded = StoredKernelResultV1.from(SupplierFrozen.recorded(operation = SupplierFrozen.o2,
                        outerCode = "forged-success")))
            }
            f.journal.seed(hidden)
            val lookups = f.master.lookups.size
            assertIs<LocalPendingResult.Unavailable>(workflow.pending("p1:${SupplierFrozen.competing.value}"))
            assertEquals(lookups, f.master.lookups.size)
            assertIs<LocalStageResult.NotStaged>(workflow.stage(SupplierFrozen.update(operation = SupplierFrozen.competing)))
            assertIs<LocalOperationResult.Unresolved>(workflow.lookup(SupplierFrozen.o2))
            assertIs<LocalAcknowledgementResult.Unresolved>(workflow.acknowledgeRecorded(SupplierFrozen.o2))
            assertEquals(lookups, f.master.lookups.size)
            assertEquals(1, f.journal.stageCount)
            assertTrue(f.journal.cleared.isEmpty())
            assertEquals(saved, f.journal.observe(SupplierFrozen.o))
        }
    }

    @Test
    fun swf044MalformedRecordedOutcomesCannotBeRememberedRenderedOrAcknowledged() = runTest(timeout = 5.seconds) {
        for ((label, bad) in SupplierFrozen.badRecorded()) {
            for (route in 0..3) withSupplier { f ->
                val workflow = f.session().workflows
                assertIs<LocalStageResult.Staged>(workflow.stage(SupplierFrozen.update()))
                val saved = assertNotNull(f.journal.observe(SupplierFrozen.o))
                when (route) {
                    0 -> {
                        f.master.executeReply = { bad }
                        assertIs<LocalOperationResult.Unresolved>(workflow.submit(SupplierFrozen.o), label)
                    }
                    1 -> {
                        f.master.lookupReply = { LookupReply.Found(bad) }
                        assertIs<LocalOperationResult.Unresolved>(workflow.lookup(SupplierFrozen.o), label)
                    }
                    2 -> {
                        f.master.lookupReply = { LookupReply.Found(bad) }
                        assertIs<LocalPendingResult.Unavailable>(workflow.pending(), label)
                    }
                    else -> {
                        val actual = StoredKernelResultV1.from(SupplierFrozen.recorded())
                        f.journal.seed(saved.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = actual))
                        f.master.lookupReply = { LookupReply.Found(bad) }
                        assertIs<LocalAcknowledgementResult.Unresolved>(workflow.acknowledgeRecorded(SupplierFrozen.o), label)
                        assertEquals(actual, f.journal.observe(SupplierFrozen.o)?.recorded, label)
                    }
                }
                assertEquals(0, f.journal.rememberCount, label)
                assertTrue(f.journal.cleared.isEmpty(), label)
                assertFalse(assertNotNull(f.journal.observe(SupplierFrozen.o)).acknowledged, label)
                if (route != 3) assertNull(f.journal.observe(SupplierFrozen.o)?.recorded, label)
                SupplierFrozen.assertSaved(SupplierFrozen.update(), assertNotNull(f.journal.observe(SupplierFrozen.o)))
            }
        }
    }

    @Test
    fun swf033AcknowledgementClearsOnlyTheDisplayedOriginalAndKeepsOtherUncertainty() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val workflow = f.session().workflows
            assertIs<LocalStageResult.Staged>(workflow.stage(SupplierFrozen.update()))
            val result = SupplierFrozen.recorded()
            f.master.lookupReply = { identity -> if (identity.operationId == SupplierFrozen.o) LookupReply.Found(result)
                else LookupReply.AbsentAtCurrentWriter(identity.operationId) }
            assertIs<LocalOperationResult.Recorded>(workflow.lookup(SupplierFrozen.o))
            val original = assertNotNull(f.journal.observe(SupplierFrozen.o))
            val other = SupplierFrozen.saved(SupplierFrozen.update(operation = SupplierFrozen.o2))
            f.journal.seed(other)
            assertIs<LocalAcknowledgementResult.Unresolved>(workflow.acknowledgeRecorded(SupplierFrozen.o2))
            assertIs<LocalAcknowledgementResult.Unresolved>(workflow.acknowledgeRecorded(SupplierFrozen.competing))
            assertEquals(SupplierFrozen.o, assertIs<LocalAcknowledgementResult.Acknowledged>(
                workflow.acknowledgeRecorded(SupplierFrozen.o)).operationId)
            assertIs<LocalAcknowledgementResult.Acknowledged>(workflow.acknowledgeRecorded(SupplierFrozen.o))
            assertEquals(listOf(SupplierFrozen.o), f.journal.cleared)
            assertEquals(other, f.journal.observe(SupplierFrozen.o2))
            assertEquals(original.recorded, f.journal.observe(SupplierFrozen.o)?.recorded)
            assertEquals(listOf(SupplierFrozen.o2), assertIs<LocalPendingResult.Available>(workflow.pending()).operations.map { it.operationId })
            assertTrue(f.master.mutations.isEmpty())
        }
    }

    @Test
    fun swf034FailedAckDurabilityOrChangedActualResultCannotClearTheOriginal() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val workflow = f.session().workflows
            assertIs<LocalStageResult.Staged>(workflow.stage(SupplierFrozen.update()))
            f.master.lookupReply = { LookupReply.Found(SupplierFrozen.recorded()) }
            assertIs<LocalOperationResult.Recorded>(workflow.lookup(SupplierFrozen.o))
            val original = assertNotNull(f.journal.observe(SupplierFrozen.o))
            f.journal.ackCut = SupplierWriteCut.KNOWN_FAILURE
            assertIs<LocalAcknowledgementResult.Unresolved>(workflow.acknowledgeRecorded(SupplierFrozen.o))
            assertEquals(original, f.journal.observe(SupplierFrozen.o))
            f.journal.ackCut = SupplierWriteCut.DURABLE
            f.master.lookupReply = { LookupReply.Found(SupplierFrozen.recorded(sequence = JournalSequence(2))) }
            assertIs<LocalAcknowledgementResult.Unresolved>(workflow.acknowledgeRecorded(SupplierFrozen.o))
            assertIs<LocalOperationResult.Unresolved>(workflow.lookup(SupplierFrozen.o))
            assertEquals(original, f.journal.observe(SupplierFrozen.o))
            assertTrue(f.journal.cleared.isEmpty())
            f.master.lookupReply = { LookupReply.Found(SupplierFrozen.recorded()) }
            assertIs<LocalAcknowledgementResult.Acknowledged>(workflow.acknowledgeRecorded(SupplierFrozen.o))
            assertEquals(listOf(SupplierFrozen.o), f.journal.cleared)
            assertTrue(f.master.mutations.isEmpty())
        }
    }

    @Test
    fun swf039RetirementOrLateRecoveryPortDenyPreventsAcknowledgement() = runTest(timeout = 5.seconds) {
        for (retire in listOf(false, true)) withSupplier { f ->
            val session = f.session()
            val workflow = session.workflows
            assertIs<LocalStageResult.Staged>(workflow.stage(SupplierFrozen.update()))
            f.master.lookupReply = { LookupReply.Found(SupplierFrozen.recorded()) }
            assertIs<LocalOperationResult.Recorded>(workflow.lookup(SupplierFrozen.o))
            val original = assertNotNull(f.journal.observe(SupplierFrozen.o))
            val gate = SupplierGate().also { f.master.lookupGate = it }
            val ack = async { workflow.acknowledgeRecorded(SupplierFrozen.o) }
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            if (retire) session.close()
            else f.master.lookupReply = { LookupReply.Deferred(it.operationId, DeferredReason.AUTHORIZATION_DENIED) }
            gate.release()
            assertIs<LocalAcknowledgementResult.Unresolved>(ack.await())
            assertEquals(original, f.journal.observe(SupplierFrozen.o))
            assertTrue(f.journal.cleared.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
        }
        // The deny is a scripted return cut, not serialized production resource activation.
    }

    @Test
    fun swf035LateReadOnLockedOrReplacedPublicationHasNoPrivateApplicationAdoption() = runTest(timeout = 5.seconds) {
        for (retirement in 0..2) withSupplier { f ->
            val session = f.session()
            val gate = SupplierGate().also { f.master.readGate = it }
            val read = async { session.workflows.read(SupplierFrozen.query()) }
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            when (retirement) {
                0 -> session.close()
                1 -> f.originalCurrent = false
                else -> f.connections.value = f.sameCoordinateSuccessor()
            }
            gate.release()
            assertIs<LocalReadResult.Unavailable>(read.await())
            assertFalse(session.isCurrent())
            assertTrue(f.master.mutations.isEmpty())
        }
    }

    @Test
    fun swf025And031GatewayReplacementDoesNotRestoreLoginOrRedispatchSavedOriginal() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val workflow = f.session().workflows
            assertIs<LocalStageResult.Staged>(workflow.stage(SupplierFrozen.update()))
            assertIs<LocalOperationResult.Unresolved>(workflow.submit(SupplierFrozen.o))
            val original = assertNotNull(f.journal.observe(SupplierFrozen.o))
            f.gateway.closeAndAwait()
            assertEquals(0, f.journal.closeCount)
            val successor = f.newGateway()
            runCurrent()
            assertEquals(1, f.master.loginCount)
            assertEquals(0, f.master.refreshCount)
            assertEquals(1, f.master.mutations.size)
            assertEquals(original, f.journal.observe(SupplierFrozen.o))
            f.master.activeView = SupplierFrozen.view(session = SupplierFrozen.successorSession)
            val fresh = f.session(successor)
            f.master.lookupReply = { LookupReply.Found(SupplierFrozen.recorded()) }
            assertIs<LocalOperationResult.Recorded>(fresh.workflows.lookup(SupplierFrozen.o))
            assertEquals(2, f.master.loginCount)
            assertEquals(1, f.master.mutations.size)
            assertEquals(original.identity(), f.master.lookups.last())
            assertEquals(original.semanticSha256, f.journal.observe(SupplierFrozen.o)?.semanticSha256)
        }
    }

    @Test
    fun swf032NewActorCannotAdoptResubmitOrClearAnotherActorsSavedOriginal() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val a = f.session()
            assertIs<LocalStageResult.Staged>(a.workflows.stage(SupplierFrozen.update()))
            val original = assertNotNull(f.journal.observe(SupplierFrozen.o))
            val gate = SupplierGate().also { f.master.readGate = it }
            val late = async { a.workflows.read(SupplierFrozen.query()) }
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            a.close()
            val ownerB = SupplierFrozen.owner.copy(actorId = SupplierFrozen.actorB)
            val journalB = f.ownJournal(ownerB).also { f.source.journals[ownerB] = it }
            f.master.activeView = SupplierFrozen.view(actor = SupplierFrozen.actorB, session = SupplierFrozen.successorSession)
            val b = f.session()
            gate.release()
            assertIs<LocalReadResult.Unavailable>(late.await())
            val lookupCount = f.master.lookups.size
            assertIs<LocalOperationResult.Unresolved>(b.workflows.lookup(SupplierFrozen.o))
            assertIs<LocalOperationResult.Unresolved>(b.workflows.submit(SupplierFrozen.o))
            assertIs<LocalAcknowledgementResult.Unresolved>(b.workflows.acknowledgeRecorded(SupplierFrozen.o))
            journalB.seed(original)
            assertIs<LocalPendingResult.Unavailable>(b.workflows.pending())
            assertEquals(lookupCount, f.master.lookups.size)
            assertEquals(original, f.journal.observe(SupplierFrozen.o))
            assertTrue(f.journal.cleared.isEmpty())
            assertTrue(journalB.cleared.isEmpty())
            assertTrue(f.master.mutations.isEmpty())
        }
    }

    @Test
    fun swf035CancelledWaitKeepsDispatchedOriginalForExplicitRecovery() = runTest(timeout = 5.seconds) {
        withSupplier { f ->
            val workflow = f.session().workflows
            assertIs<LocalStageResult.Staged>(workflow.stage(SupplierFrozen.update()))
            val result = SupplierFrozen.recorded(replayed = false)
            f.master.executeReply = { result }
            val gate = SupplierGate().also { f.master.executeGate = it }
            val submit = async { workflow.submit(SupplierFrozen.o) }
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            assertEquals(1, f.master.mutations.size)
            submit.cancelAndJoin()
            val original = assertNotNull(f.journal.observe(SupplierFrozen.o))
            assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, original.localPhase)
            assertNull(original.recorded)
            assertFalse(original.acknowledged)
            gate.release()
            f.master.lookupReply = { LookupReply.Found(SupplierFrozen.recorded()) }
            assertIs<LocalOperationResult.Recorded>(workflow.lookup(SupplierFrozen.o))
            assertEquals(1, f.master.mutations.size)
            assertEquals(original.identity(), f.master.lookups.last())
        }
        // The scripted result is not proof that a physical Master commit survived cancellation.
    }
}

/** Frozen values plus explicitly labelled syntax-only DTO carriers; no golden digest algorithm. */
private object SupplierFrozen {
    private val hashes = mapOf("README.md" to "5636ecfe210c299ff95b26573d47160d8c9522c7e0de9733a68251d063988730",
        "cases.md" to "a218d90df30e676a023448daefb3431d1718e641687a4a44b09cc1d11b2cd77a",
        "fixtures.json" to "52ec68d1ee38757e27b58a47e75ff0136fc50ca6440c5544200c50287fabd0d5")
    private val directory = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/supplier-rpc-workflow") }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen supplier workflow inputs are unavailable")
    private val frozen: JsonObject = run {
        for ((name, expected) in hashes) {
            val bytes = Files.readAllBytes(directory.resolve(name))
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Frozen supplier workflow input changed: $name")
        }
        Json.parseToJsonElement(Files.readString(directory.resolve("fixtures.json"))).jsonObject.also {
            assertEquals("supplier-rpc-workflow", it.text("design"))
            assertEquals("1", it.text("version"))
            assertEquals("FROZEN", it.text("specification_state"))
        }
    }
    private val identifiers = frozen.getValue("identifiers").jsonObject
    private val base = frozen.getValue("fixtures").jsonObject.getValue("F-BASE").jsonObject
    private fun id(key: String) = DomainId(identifiers.text(key))
    val scope = BranchScope(id("organization"), id("company"), id("branch"))
    val otherScope = scope.copy(branchId = id("other_branch"))
    val actor = id("actor_a")
    val actorB = id("actor_b")
    val device = id("device_d")
    val otherDevice = id("other_device")
    val deviceOwner = LocalClientDeviceIdentity(scope, device)
    val owner = LocalClientIdentity(scope, actor, device)
    val s1 = id("supplier_s1")
    val s2 = id("supplier_s2")
    val o = OperationId(identifiers.text("operation_o"))
    val o2 = OperationId(identifiers.text("operation_o2"))
    val competing = OperationId(identifiers.text("operation_competing"))
    val suppliers = base.getValue("supplier_rows").jsonArray.map { supplier(it.jsonObject, "id") }
    val created = supplier(base.getValue("create").jsonObject, "supplier_id")
    val updated = supplier(base.getValue("update").jsonObject, "supplier_id")
    private fun supplier(value: JsonObject, idField: String): LocalSupplierV1 {
        val names = value.getValue("names").jsonObject
        return LocalSupplierV1(DomainId(value.text(idField)), LocalNamesV1(names.text("en"), names.text("ar")),
            value.getValue("active").jsonPrimitive.boolean)
    }

    // Named DTO slots only: neither these coordinates nor policy/deadline numbers qualify authority.
    fun slot(name: String) = DomainId(UUID.nameUUIDFromBytes(("supplier-workflow-support-v1:" + name).encodeToByteArray()).toString())
    val connection = slot("original-connection")
    private val firstSession = slot("issued-session")
    val successorSession = slot("fresh-explicit-session")
    private val boot = slot("display-clock")
    val r0 = Revision(7)
    // Coherent normalized DTO carriers, not a numeric revision oracle for Q02.
    private val resultDomainRevision = r0
    val readRevision = Revision(23)
    val at = UtcInstant.parseCanonical("2026-11-04T00:00:00Z")
    private val wire = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    fun grant(action: SecurityAction, verb: PermissionVerb, effect: GrantEffect = GrantEffect.ALLOW) =
        PermissionGrant(Permission(action, verb), scope, effect)
    fun grants(read: Boolean = true, admin: Boolean = true, recovery: Boolean = true): List<PermissionGrant> = buildList {
        if (read) add(grant(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM))
        if (admin) add(grant(SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER))
        if (recovery) add(grant(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM))
    }
    fun view(actor: DomainId = this.actor, device: DomainId = this.device, scope: BranchScope = this.scope,
        session: DomainId = firstSession, grants: List<PermissionGrant> = this.grants()): LocalSessionView {
        val policy = ActivatedLocalPolicy(scope, actor, Revision(1), grants, LocalSessionPolicy(1),
            ThrottlePolicy(0, 1_000, 1_000, 1_000))
        return LocalSessionView(actor, scope, device, session, SessionGeneration(1), LocalLoginIdentifier("supplier-support"),
            policy, MonotonicMoment(boot, 100_000), MonotonicMoment(boot, 100_000))
    }
    fun tokens(view: LocalSessionView): LocalSessionTokens {
        fun bytes(prefix: String): ByteArray = ByteArray(52) { 0x51 }.also { result ->
            prefix.encodeToByteArray().copyInto(result)
            val locator = view.sessionId.value.replace("-", "")
            repeat(16) { index -> result[index + 4] = locator.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        }
        val access = bytes("ULA1")
        val refresh = bytes("ULR1")
        try { return LocalSessionTokens(view, OpaqueCredential(access), OpaqueCredential(refresh)) }
        finally { access.fill(0); refresh.fill(0) }
    }
    fun query(reference: LocalRecordReference? = null, search: String = "") =
        LocalWorkflowQuery(LocalWorkflow.PURCHASING, search = search, reference = reference)
    fun create(operation: OperationId = o) = intent(operation, LocalAction.SUPPLIER_CREATE, created, null, 1)
    // F-OPS explicitly permits C/U under saved O. R0/R1 here are controlled carriers, not live rules.
    fun update(operation: OperationId = o, target: DomainId = updated.id, names: LocalNamesV1 = updated.names,
        active: Boolean = updated.active, expected: Revision = r0, version: Int = 1) =
        intent(operation, LocalAction.SUPPLIER_UPDATE, LocalSupplierV1(target, names, active), expected, version)
    private fun intent(operation: OperationId, action: LocalAction, supplier: LocalSupplierV1, expected: Revision?, version: Int) =
        LocalCommandIntent(operation, action, version, null, buildList {
            add(LocalScalarInput(LocalField.SUPPLIER_ID, LocalScalarValue.Identifier(supplier.id)))
            add(LocalScalarInput(LocalField.NEW_ITEM, LocalScalarValue.Flag(expected == null)))
            if (expected != null) add(LocalScalarInput(LocalField.EXPECTED_STORAGE_REVISION, LocalScalarValue.Integer(expected.value)))
            add(LocalScalarInput(LocalField.NAME_ENGLISH, LocalScalarValue.Text(supplier.names.english)))
            add(LocalScalarInput(LocalField.NAME_ARABIC, LocalScalarValue.Text(supplier.names.arabic)))
            add(LocalScalarInput(LocalField.ACTIVE, LocalScalarValue.Flag(supplier.active)))
        })
    fun saved(intent: LocalCommandIntent): StoredClientIntentV1 {
        fun value(field: LocalField) = intent.values.single { it.field == field }.value
        val supplier = LocalSupplierV1((value(LocalField.SUPPLIER_ID) as LocalScalarValue.Identifier).value,
            LocalNamesV1((value(LocalField.NAME_ENGLISH) as LocalScalarValue.Text).value,
                (value(LocalField.NAME_ARABIC) as LocalScalarValue.Text).value), (value(LocalField.ACTIVE) as LocalScalarValue.Flag).value)
        val expected = intent.values.singleOrNull { it.field == LocalField.EXPECTED_STORAGE_REVISION }?.value
            ?.let { Revision((it as LocalScalarValue.Integer).value) }
        val request = MutationRequest(intent.operationId, LocalConfigurationProfile.COMMAND,
            LocalConfigurationCodec.encode(LocalConfigurationRequestV1.ConfigureSupplier(expected, supplier)))
        return StoredClientIntentV1.staged(owner, intent, request, LocalText("Supplier original", "عملية المورد الأصلية"),
            listOf(LocalRecordValue(LocalText("Supplier", "المورد"), LocalText(supplier.names.english, supplier.names.arabic))))
    }
    fun assertSaved(intent: LocalCommandIntent, saved: StoredClientIntentV1) {
        val expected = this.saved(intent)
        assertEquals(intent.operationId, saved.operationId)
        assertEquals(owner, saved.owner)
        assertEquals(expected.request().command, saved.request().command)
        assertEquals(expected.request().payload, saved.request().payload)
        assertEquals(expected.inputDigest, saved.inputDigest)
        assertEquals(expected.semanticSha256, saved.semanticSha256)
        assertNull(saved.request().actionApprovalId)
    }
    fun row(value: LocalSupplierV1) = LocalConfigurationListItemV1(value.id, Revision(r0.value - 1), r0, value.names, value.active, null)
    fun data(page: LocalConfigurationPageV1) = ReadReply.Data(
        StoredBytes(wire.encodeToString(LocalConfigurationPageV1.serializer(), page).encodeToByteArray()), readRevision, at)
    fun read(request: ReadRequest): ReadReply {
        val query = LocalConfigurationCodec.decodeQuery(request.payload)
        val selected = suppliers.filter { query.id == null || it.id == query.id }
        return data(LocalConfigurationPageV1(LocalConfigurationProfile.SUPPLIER, selected.map(::row), null,
            detail = if (query.id == null) null else selected.singleOrNull()?.let { LocalConfigurationDetailV1.Supplier(it) }))
    }
    fun recorded(operation: OperationId = o, actor: DomainId = this.actor, target: DomainId? = updated.id,
        revision: Revision? = resultDomainRevision, committed: Boolean = true, outerCode: String = "committed",
        innerCode: String = "configuration.applied", replayed: Boolean = true,
        sequence: JournalSequence = JournalSequence(1), payload: StoredBytes? = null): MutationReply.Recorded =
        MutationReply.Recorded(operation, actor, committed, outerCode, payload ?: StoredBytes(wire.encodeToString(
            LocalConfigurationResultV1.serializer(), LocalConfigurationResultV1(innerCode, target, revision)).encodeToByteArray()),
            Revision(1), sequence, at, replayed)
    fun badRecorded(): List<Pair<String, MutationReply.Recorded>> = listOf(
        "wrong operation" to recorded(operation = o2), "wrong original actor" to recorded(actor = actorB),
        "wrong supplier" to recorded(target = s1), "missing supplier" to recorded(target = null),
        "missing result revision" to recorded(revision = null), "unsupported outer success" to recorded(outerCode = "forged-success"),
        "unsupported typed success" to recorded(innerCode = "configuration.unsupported"),
        "rejection claiming an applied result" to recorded(committed = false),
        "rejection retaining success coordinates" to recorded(committed = false, outerCode = "configuration.stale_revision",
            innerCode = "configuration.stale_revision"),
        // SWF-044/F-WIRE freezes duplicate-field refusal, including otherwise valid values.
        "duplicate result field" to recorded(payload = recorded().result.copyBytes().let { bytes ->
            try { StoredBytes(("{\"code\":\"configuration.applied\"," + bytes.decodeToString().drop(1)).encodeToByteArray()) }
            finally { bytes.fill(0) }
        }),
        "malformed result schema" to recorded(payload = StoredBytes("{\"unsupported\":true}".encodeToByteArray())))
    fun hex(bytes: StoredBytes): String = bytes.copyBytes().let { source -> try { encodeHex(source) } finally { source.fill(0) } }
    fun encodeHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}

private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

private class SupplierGate {
    val entered = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    suspend fun await() { entered.complete(Unit); released.await() }
    fun release() { released.complete(Unit) }
}

/** Independent chosen return cuts; no native flush, durable proof or protected close is modelled. */
private enum class SupplierWriteCut(val retain: Boolean, val answer: JournalWrite) {
    DURABLE(true, JournalWrite.DURABLE), KNOWN_FAILURE(false, JournalWrite.UNAVAILABLE),
    COMMITTED_BUT_UNKNOWN(true, JournalWrite.UNAVAILABLE),
}

private class SupplierIntents(owner: LocalClientIdentity, private val events: MutableList<String>) : LocalClientIntentJournal(owner) {
    private val entries = linkedMapOf<OperationId, StoredClientIntentV1>()
    var stageCut = SupplierWriteCut.DURABLE
    var dispatchCut = SupplierWriteCut.DURABLE
    var rememberCut = SupplierWriteCut.DURABLE
    var ackCut = SupplierWriteCut.DURABLE
    var stageGate: SupplierGate? = null
    var dispatchGate: SupplierGate? = null
    var stageCount = 0
        private set
    var dispatchCount = 0
        private set
    var rememberCount = 0
        private set
    var ackCount = 0
        private set
    var closeCount = 0
        private set
    val cleared = mutableListOf<OperationId>()
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> = JournalRead.Value(entries[operationId])
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> = JournalRead.Value(entries.values.filterNot { it.acknowledged })
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCount++
        events += "stage.enter"
        intent.validate(owner)
        stageGate?.await()
        val previous = entries[intent.operationId]
        if (previous != null && !previous.sameIntent(intent)) return JournalWrite.IDENTITY_CONFLICT
        if (stageCut.retain) { entries[intent.operationId] = previous ?: intent; events += "stage.retained" }
        return stageCut.answer
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        dispatchCount++
        events += "dispatch.enter"
        dispatchGate?.await()
        val previous = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (previous.recorded != null || previous.acknowledged) return JournalWrite.IDENTITY_CONFLICT
        if (dispatchCut.retain) {
            entries[operationId] = previous.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name)
            events += "dispatch.retained"
        }
        return dispatchCut.answer
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        rememberCount++
        val previous = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (result.operationId != operationId || result.originalActorId != owner.actorId ||
            previous.recorded != null && previous.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        if (rememberCut.retain) entries[operationId] = previous.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = result)
        return rememberCut.answer
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        ackCount++
        val previous = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (previous.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        if (previous.acknowledged) return JournalWrite.DURABLE
        if (ackCut.retain) { entries[operationId] = previous.copy(acknowledged = true); cleared += operationId }
        return ackCut.answer
    }
    fun seed(entry: StoredClientIntentV1) { entries[entry.operationId] = entry }
    fun observe(operationId: OperationId): StoredClientIntentV1? = entries[operationId]
    fun originalIds(): Set<OperationId> = entries.keys.toSet()
    fun release() { stageGate?.release(); dispatchGate?.release() }
    override suspend fun close() { closeCount++ }
}

private class SupplierCredentials(owner: LocalClientDeviceIdentity, private val events: MutableList<String>) : LocalClientCredentialJournal(owner) {
    private var state = StoredClientCredentials()
    var writeCount = 0
        private set
    val faults = mutableMapOf<Int, SupplierWriteCut>()
    val gates = mutableMapOf<Int, SupplierGate>()
    override suspend fun read(): JournalRead<StoredClientCredentials> = JournalRead.Value(copySupplierCredentials(state))
    override suspend fun replace(expectedRevision: Long, next: StoredClientCredentials): JournalWrite {
        val ordinal = ++writeCount
        events += "credentials.$ordinal.enter"
        gates[ordinal]?.await()
        require(state.revision == expectedRevision)
        next.validate(owner)
        val cut = faults[ordinal] ?: SupplierWriteCut.DURABLE
        if (cut.retain) {
            val copied = copySupplierCredentials(next)
            state.destroy()
            state = copied
            events += "credentials.$ordinal.retained"
        }
        return cut.answer
    }
    fun inspect(block: (StoredClientCredentials) -> Unit) {
        val copy = copySupplierCredentials(state)
        try { block(copy) } finally { copy.destroy() }
    }
    fun release() { gates.values.forEach { it.release() } }
    override suspend fun close() { state.destroy() }
}

private fun copySupplierCredentials(source: StoredClientCredentials): StoredClientCredentials {
    val sessions = mutableListOf<StoredClientSessionV1>()
    try {
        for (row in source.sessions) {
            val access = row.access.useBytes(::OpaqueCredential)
            try {
                val refresh = row.refresh.useBytes(::OpaqueCredential)
                sessions += StoredClientSessionV1(row.localId, row.identity, row.login, row.binding, row.sessionId, row.generation,
                    row.accessExpiresAt, row.workExpiresAt, row.phase, row.originalRevoked, access, refresh)
            } catch (failure: Throwable) { access.destroy(); throw failure }
        }
        return StoredClientCredentials(source.revision, source.attempts.toList(), sessions, source.enrollments.toList(),
            source.administrations.toList(), source.formatVersion)
    } catch (failure: Throwable) { sessions.forEach { it.destroy() }; throw failure }
}

private class SupplierJournalSource(original: SupplierIntents, private val events: MutableList<String>) : LocalAuthenticatedClientIntentJournalSource {
    val journals = mutableMapOf<LocalClientIdentity, LocalClientIntentJournal>(original.owner to original)
    val acquiredOwners = mutableListOf<LocalClientIdentity>()
    val selectedOwners = mutableListOf<LocalClientIdentity>()
    val borrowed = mutableListOf<LocalGatewayCredentials>()
    var gate: SupplierGate? = null
    var acquireReply: (LocalClientIdentity) -> LocalClientIntentJournal? = { journals[it] }
    var currentReply: (LocalClientIdentity) -> LocalClientIntentJournal? = { journals[it] }
    var lastSelected: LocalClientIntentJournal? = null
        private set
    override suspend fun acquireAuthenticated(owner: LocalClientIdentity, credentials: LocalGatewayCredentials): LocalClientIntentJournal? {
        events += "intents.acquire.enter"
        credentials.bearer.useBytes { require(it.size == 52) }
        acquiredOwners += owner
        borrowed += credentials
        gate?.await()
        return acquireReply(owner).also { events += "intents.acquire.return" }
    }
    override suspend fun current(owner: LocalClientIdentity): LocalClientIntentJournal? {
        events += "intents.current"
        selectedOwners += owner
        return currentReply(owner).also { lastSelected = it }
    }
}

/** Scripted service outcomes check application comparisons only; no bearer or grant is verified. */
private class SupplierMaster(private val events: MutableList<String>) : LocalMasterClient {
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    var activeView = SupplierFrozen.view()
    var loginCount = 0
        private set
    var currentCount = 0
        private set
    var refreshCount = 0
        private set
    val currentGates = mutableMapOf<Int, SupplierGate>()
    val credentials = mutableListOf<LocalGatewayCredentials>()
    val tokens = mutableListOf<LocalSessionTokens>()
    val reads = mutableListOf<ReadRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    val mutations = mutableListOf<MutationRequest>()
    var readGate: SupplierGate? = null
    var lookupGate: SupplierGate? = null
    var executeGate: SupplierGate? = null
    var currentReply: () -> LocalSessionViewResult = { LocalSessionViewResult.Current(activeView) }
    var readReply: (ReadRequest) -> ReadReply = SupplierFrozen::read
    var lookupReply: (OperationIdentity) -> LookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
    var executeReply: (MutationRequest) -> MutationReply = { MutationReply.Deferred(it.operationId, DeferredReason.STORAGE_UNCERTAIN) }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult {
        loginCount++
        events += "login.enter"
        return LocalAuthenticationResult.Authenticated(SupplierFrozen.tokens(activeView).also(tokens::add))
    }
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        val ordinal = ++currentCount
        events += "current.$ordinal.enter"
        this.credentials += credentials
        credentials.bearer.useBytes { require(it.size == 52) }
        currentGates[ordinal]?.await()
        return currentReply().also { events += "current.$ordinal.return" }
    }
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult {
        refreshCount++
        this.credentials += credentials
        return LocalAuthenticationResult.Unavailable
    }
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult {
        this.credentials += credentials
        return LocalSignOutResult.Revoked
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        this.credentials += credentials
        reads += request
        readGate?.await()
        return readReply(request)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        this.credentials += credentials
        lookups += identity
        lookupGate?.await()
        return lookupReply(identity)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        events += "execute.enter"
        this.credentials += credentials
        mutations += request
        val chosen = executeReply(request)
        executeGate?.await()
        return chosen
    }
    fun release() { currentGates.values.forEach { it.release() }; readGate?.release(); lookupGate?.release(); executeGate?.release() }
}

private class SupplierFixture(test: TestScope, credentialOwner: LocalClientDeviceIdentity) {
    val events = mutableListOf<String>()
    val master = SupplierMaster(events)
    val credentials = SupplierCredentials(credentialOwner, events)
    private val ownedJournals = mutableListOf<SupplierIntents>()
    val journal = ownJournal(SupplierFrozen.owner)
    val source = SupplierJournalSource(journal, events)
    var originalCurrent = true
    private val original = LocalSupplierClientConnection(master, SupplierFrozen.connection, SupplierFrozen.deviceOwner,
        LocalBranchDisplayLabels(SupplierFrozen.scope, "Synthetic supplier company", "Synthetic supplier branch")) { originalCurrent }
    val connections = MutableStateFlow<LocalSupplierClientConnection?>(original)
    private val dispatcher = StandardTestDispatcher(test.testScheduler)
    private val gateways = mutableListOf<LocalEndpointBranchGateway>()
    val gateway = newGateway()
    fun ownJournal(owner: LocalClientIdentity) = SupplierIntents(owner, events).also(ownedJournals::add)
    fun newGateway() = LocalEndpointBranchGateway.supplierOnly(connections, credentials, source, dispatcher).also(gateways::add)
    /** Direct shared gateway variation; all credential/service/journal inputs remain controlled. */
    fun workflowForRetention(supplierOnly: Boolean): LocalWorkflowGateway = LocalEndpointWorkflowGateway(master, journal,
        LocalWorkflowCredentialSource {
            val tokens = SupplierFrozen.tokens(master.activeView)
            val bearer = try { tokens.access.useBytes(::OpaqueCredential) } finally { tokens.destroy() }
            LocalWorkflowCredentialLease(LocalGatewayCredentials(SupplierFrozen.connection, bearer)) { originalCurrent }
        }, if (supplierOnly) LocalWorkflowInstallation.supplierOnly()
        else LocalWorkflowInstallation(setOf(LocalConfigurationProfile.COMMAND), setOf(LocalConfigurationProfile.QUERY)))
    fun sameCoordinateSuccessor() = LocalSupplierClientConnection(master, original.connectionId, original.identity, original.labels) { true }
    suspend fun signIn(gateway: LocalEndpointBranchGateway = this.gateway): SignInResult<BranchUiSession> {
        val password = CharArray(16) { 'x' }
        try { return gateway.signIn(master.activeView.login.value, password) }
        finally { password.fill('\u0000') }
    }
    suspend fun session(gateway: LocalEndpointBranchGateway = this.gateway): BranchUiSession =
        assertIs<SignInResult.Authenticated<BranchUiSession>>(signIn(gateway)).session
    fun assertBorrowedCredentialsDestroyed() {
        for (value in source.borrowed + master.credentials) assertFailsWith<IllegalStateException> { value.bearer.useBytes { Unit } }
        for (value in master.tokens) {
            assertFailsWith<IllegalStateException> { value.access.useBytes { Unit } }
            assertFailsWith<IllegalStateException> { value.refresh.useBytes { Unit } }
        }
    }
    suspend fun close() = withContext(NonCancellable) {
        credentials.release(); source.gate?.release(); master.release(); ownedJournals.forEach { it.release() }
        try { gateways.forEach { it.closeAndAwait() } }
        finally {
            credentials.close()
            ownedJournals.forEach { it.close() }
            master.tokens.forEach { it.destroy() }
        }
    }
}

private suspend fun TestScope.withSupplier(credentialOwner: LocalClientDeviceIdentity = SupplierFrozen.deviceOwner,
    block: suspend (SupplierFixture) -> Unit) {
    val fixture = SupplierFixture(this, credentialOwner)
    try { block(fixture) } finally { fixture.close() }
}
