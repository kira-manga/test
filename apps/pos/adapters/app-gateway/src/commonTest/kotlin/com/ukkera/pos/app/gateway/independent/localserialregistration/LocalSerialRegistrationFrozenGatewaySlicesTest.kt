@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.localserialregistration

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/**
 * Bounded frozen LSR client-identity/refusal slices through the production shared
 * workflow gateway and journal format. Controlled ports supply no qualified
 * native authority, actual durable storage, business results or ledger facts.
 * Whole LSR cases, direct native publication preparation/review/staging and
 * genuine positive non-device/platform integration remain UNBOUND / NOT_RUN.
 */
class LocalSerialRegistrationFrozenGatewaySlicesTest {
    private val f = LsrGatewayInputs

    @Test fun lsr06And13_savedFormatKeepsExactOriginalActorRequestAndRawCode() {
        val original = f.entry()
        val restored = journalJson.decodeFromString(StoredClientIntentV1.serializer(),
            journalJson.encodeToString(StoredClientIntentV1.serializer(), original))
        restored.validate(f.owner)
        assertEquals(original, restored)
        assertEquals(f.actor, restored.owner.actorId)
        assertEquals(f.operation, restored.operationId)
        assertEquals(f.request().payload, restored.request().payload)
        assertEquals(original.identity(), restored.identity())
        assertTrue(restored.summary.first().value.english.contains("000AbC-07"))
        assertNull(restored.actionApprovalId)
        assertNull(restored.actionApproval)
    }

    @Test fun lsr05And06_caseAndLeadingZeroCandidatesHaveDistinctSemanticBytes() {
        val originals = listOf(f.entry(), f.entry("000abc-07"), f.entry("0AbC-07"))
        assertEquals(3, originals.map { it.request().payload }.distinct().size)
        assertEquals(3, originals.map { it.semanticSha256 }.distinct().size)
        assertEquals(listOf(f.code, "000abc-07", "0AbC-07"), originals.map {
            com.ukkera.pos.catalog.store.LocalSerialRegistrationCodecV1.decode(it.request().payload).publication.definition.serialCode
        })
        // Distinct request identity only; no native uniqueness reservation/acceptance.
    }

    @Test fun lsr01And10_genericStageCannotCreateAnUnsignedSerialActivation() = runTest {
        val fixture = LsrGatewayFixture(null)
        val result = assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(f.intent()))
        assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, result.problem)
        assertNull(fixture.journal.entry)
        assertEquals(0, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.executions.isEmpty())
        assertEquals(0, fixture.endpoint.readCalls)
    }

    @Test fun lsr13And14_changedInputCannotReplaceAnExistingOriginal() = runTest {
        val fixture = LsrGatewayFixture()
        val original = assertNotNull(fixture.journal.entry)
        val input = f.intent()
        val changed = LocalCommandIntent(input.operationId, input.action, input.formVersion, input.reference,
            input.values + LocalScalarInput(LocalField.SERIAL_CODE, LocalScalarValue.Text("000AbC-08")))
        assertEquals(f.operation, assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(changed)).operationId)
        assertEquals(original, fixture.journal.entry)
        assertTrue(fixture.endpoint.lookups.isEmpty())
        assertTrue(fixture.endpoint.executions.isEmpty())
        assertEquals(0, fixture.journal.stageCalls)
    }

    @Test fun lsr13And16_unknownOriginalBlocksAFreshOperationBypass() = runTest {
        val fixture = LsrGatewayFixture()
        val original = fixture.journal.entry
        val refusal = assertIs<LocalStageResult.NotStaged>(fixture.gateway.stage(f.intent(f.otherOperation)))
        assertEquals(LocalServiceProblem.RETRY_LATER, refusal.problem)
        assertEquals(original, fixture.journal.entry)
        assertEquals(0, fixture.journal.stageCalls)
        assertTrue(fixture.endpoint.executions.isEmpty())
    }

    @Test fun lsr13And16_sameOriginalRepeatedStageOnlyLooksUpThatOriginalWhenUnqualified() = runTest {
        val fixture = LsrGatewayFixture()
        val original = assertNotNull(fixture.journal.entry)
        repeat(2) {
            assertEquals(f.operation, assertIs<LocalStageResult.Uncertain>(fixture.gateway.stage(f.intent())).operationId)
        }
        assertEquals(listOf(original.identity(), original.identity()), fixture.endpoint.lookups)
        assertEquals(original, fixture.journal.entry)
        assertEquals(0, fixture.journal.stageCalls)
        assertEquals(0, fixture.journal.dispatchCalls)
        assertTrue(fixture.endpoint.executions.isEmpty())
    }

    @Test fun lsr16_controlledNotRecordedAllowsOnlyTheSavedSameOriginalResubmission() = runTest {
        val fixture = LsrGatewayFixture()
        val original = assertNotNull(fixture.journal.entry)
        fixture.endpoint.lookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
        repeat(2) {
            val result = assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(f.operation))
            assertEquals(f.operation, result.operationId)
            assertEquals(LocalServiceProblem.UNAVAILABLE, result.problem)
        }
        assertEquals(listOf(original.identity(), original.identity()), fixture.endpoint.lookups)
        assertEquals(2, fixture.endpoint.executions.size)
        fixture.endpoint.executions.forEach {
            assertEquals(original.operationId, it.operationId)
            assertEquals(original.request().command, it.command)
            assertEquals(original.request().payload, it.payload)
            assertNull(it.actionApprovalId)
        }
        val retained = assertNotNull(fixture.journal.entry)
        assertTrue(original.sameIntent(retained))
        assertEquals(LocalPendingPhase.DISPATCH_UNRESOLVED, retained.localPhase)
        assertEquals(0, fixture.journal.stageCalls)
        assertEquals(0, fixture.journal.resultWrites)
        assertEquals(0, fixture.journal.acknowledgements)
        // No genuine NOT_RECORDED race, Master deduplication or one-effect proof.
    }

    @Test fun lsr13_dispatchCheckpointFailurePreventsTheStateChangingSend() = runTest {
        for (failure in listOf(JournalWrite.UNAVAILABLE, JournalWrite.NO_CAPACITY, JournalWrite.IDENTITY_CONFLICT)) {
            val fixture = LsrGatewayFixture()
            val original = fixture.journal.entry
            fixture.endpoint.lookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
            fixture.journal.dispatchWrite = failure
            assertEquals(f.operation, assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(f.operation)).operationId)
            assertEquals(original, fixture.journal.entry)
            assertEquals(1, fixture.journal.dispatchCalls)
            assertTrue(fixture.endpoint.executions.isEmpty())
            assertEquals(0, fixture.journal.resultWrites)
        }
    }

    @Test fun lsr10And11_latestActivationPermissionRefusesSubmitAndPreservesOriginal() = runTest {
        val fixture = LsrGatewayFixture()
        val original = fixture.journal.entry
        fixture.endpoint.lookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
        assertTrue(assertIs<LocalOperationResult.Pending>(fixture.gateway.lookup(f.operation)).operation.submitAllowed)
        fixture.endpoint.viewResult = LocalSessionViewResult.Current(f.currentView(granted = false))
        val refusal = assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(f.operation))
        assertEquals(LocalServiceProblem.DENIED, refusal.problem)
        assertEquals(original, fixture.journal.entry)
        assertEquals(0, fixture.journal.dispatchCalls)
        assertTrue(fixture.endpoint.executions.isEmpty())
    }

    @Test fun lsr11And17_wrongCurrentActorDeviceOrScopeReleasesNoPrivatePendingData() = runTest {
        val wrongViews = listOf(
            f.currentView(actorId = f.otherActor),
            f.currentView(deviceId = f.id("73000000-0000-4000-8000-000000000007")),
            f.currentView(branchScope = f.scope.copy(branchId = f.id("41000000-0000-4000-8000-000000000002"))),
            f.currentView(branchScope = f.scope.copy(companyId = f.id("31000000-0000-4000-8000-000000000002"))),
            f.currentView(branchScope = f.scope.copy(organizationId = f.id("11000000-0000-4000-8000-000000000002"))))
        for (view in wrongViews) {
            val fixture = LsrGatewayFixture()
            val original = fixture.journal.entry
            fixture.endpoint.viewResult = LocalSessionViewResult.Current(view)
            assertEquals(LocalServiceProblem.SESSION_ENDED,
                assertIs<LocalPendingResult.Unavailable>(fixture.gateway.pending()).problem)
            assertEquals(original, fixture.journal.entry)
            assertTrue(fixture.endpoint.lookups.isEmpty())
            assertTrue(fixture.endpoint.executions.isEmpty())
        }
    }

    @Test fun lsr11And19_currentSessionRefusalNeverDefaultsToAnEmptyHistory() = runTest {
        val cases = listOf(
            LocalSessionViewResult.Denied to LocalServiceProblem.SESSION_ENDED,
            LocalSessionViewResult.Busy to LocalServiceProblem.RETRY_LATER,
            LocalSessionViewResult.Unavailable to LocalServiceProblem.UNAVAILABLE,
            LocalSessionViewResult.StorageUncertain to LocalServiceProblem.STORAGE_UNCERTAIN)
        for ((current, problem) in cases) {
            val fixture = LsrGatewayFixture()
            val original = fixture.journal.entry
            fixture.endpoint.viewResult = current
            assertEquals(problem, assertIs<LocalPendingResult.Unavailable>(fixture.gateway.pending()).problem)
            assertEquals(original, fixture.journal.entry)
            assertTrue(fixture.endpoint.lookups.isEmpty())
            assertTrue(fixture.endpoint.executions.isEmpty())
        }
    }

    @Test fun lsr11And17_missingCurrentCredentialsCannotRecoverOrAdoptTheOriginal() = runTest {
        val fixture = LsrGatewayFixture()
        val original = fixture.journal.entry
        fixture.credentialAvailable = false
        assertEquals(LocalServiceProblem.SESSION_ENDED,
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(f.operation)).problem)
        assertEquals(LocalServiceProblem.SESSION_ENDED,
            assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(f.operation)).problem)
        assertEquals(original, fixture.journal.entry)
        assertTrue(fixture.endpoint.lookups.isEmpty())
        assertTrue(fixture.endpoint.executions.isEmpty())
    }

    @Test fun lsr13And16_cancelledLookupKeepsTheOriginalAndDoesNotDispatch() = runTest {
        val fixture = LsrGatewayFixture()
        val original = fixture.journal.entry
        fixture.endpoint.heldLookup = CompletableDeferred()
        val work = launch { fixture.gateway.lookup(f.operation) }
        runCurrent()
        assertEquals(1, fixture.endpoint.lookups.size)
        work.cancel()
        runCurrent()
        assertTrue(work.isCancelled)
        assertEquals(original, fixture.journal.entry)
        assertTrue(fixture.endpoint.executions.isEmpty())
        assertEquals(0, fixture.journal.resultWrites)
        assertEquals(0, fixture.journal.acknowledgements)
    }

    @Test fun lsr11And17_retiredCredentialLifetimeDiscardsADelayedPendingProjection() = runTest {
        val fixture = LsrGatewayFixture()
        val original = fixture.journal.entry
        val held = CompletableDeferred<Unit>()
        fixture.endpoint.heldLookup = held
        fixture.endpoint.lookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
        var result: LocalPendingResult? = null
        val work = launch { result = fixture.gateway.pending() }
        runCurrent()
        fixture.lifetimeCurrent = false
        held.complete(Unit)
        runCurrent()
        work.join()
        assertEquals(LocalServiceProblem.SESSION_ENDED, assertIs<LocalPendingResult.Unavailable>(result).problem)
        assertEquals(original, fixture.journal.entry)
        assertTrue(fixture.endpoint.executions.isEmpty())
    }

    @Test fun lsr10And13_readingPendingNeverAutomaticallySubmitsAnActivation() = runTest {
        val fixture = LsrGatewayFixture()
        fixture.endpoint.lookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
        val pending = assertIs<LocalPendingResult.Available>(fixture.gateway.pending()).operations.single()
        assertEquals(f.operation, pending.operationId)
        assertEquals(LocalAction.SERIAL_REGISTRATION_ACTIVATE, pending.action)
        assertEquals(assertNotNull(fixture.journal.entry).summary.map { it.ui() }, pending.summary)
        assertTrue(pending.submitAllowed)
        assertTrue(fixture.endpoint.executions.isEmpty())
        assertEquals(0, fixture.journal.dispatchCalls)
    }

    @Test fun lsr16And19_unknownOriginalCannotBeAcknowledgedAsARecordedOutcome() = runTest {
        val fixture = LsrGatewayFixture()
        val original = fixture.journal.entry
        assertEquals(f.operation,
            assertIs<LocalAcknowledgementResult.Unresolved>(fixture.gateway.acknowledgeRecorded(f.operation)).operationId)
        assertEquals(original, fixture.journal.entry)
        assertEquals(0, fixture.journal.acknowledgements)
        assertEquals(0, fixture.journal.resultWrites)
        assertTrue(fixture.endpoint.executions.isEmpty())
    }

    @Test fun lsr21_missingInstalledActivationContractCannotEnableSubmit() = runTest {
        val fixture = LsrGatewayFixture(installed = false)
        fixture.endpoint.lookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
        val pending = assertIs<LocalOperationResult.Pending>(fixture.gateway.lookup(f.operation)).operation
        assertFalse(pending.submitAllowed)
        assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(f.operation))
        assertEquals(0, fixture.journal.dispatchCalls)
        assertTrue(fixture.endpoint.executions.isEmpty())
    }

    @Test fun lsr14And16_mismatchedLookupIdentityCannotReplaceTheRequestedOriginal() = runTest {
        val fixture = LsrGatewayFixture()
        val original = fixture.journal.entry
        fixture.endpoint.lookupReply = { LookupReply.AbsentAtCurrentWriter(f.otherOperation) }
        assertEquals(f.operation, assertIs<LocalOperationResult.Unresolved>(fixture.gateway.lookup(f.operation)).operationId)
        assertEquals(f.operation, assertIs<LocalOperationResult.Unresolved>(fixture.gateway.submit(f.operation)).operationId)
        assertEquals(original, fixture.journal.entry)
        assertEquals(0, fixture.journal.dispatchCalls)
        assertTrue(fixture.endpoint.executions.isEmpty())
    }

    @Test fun lsr14And17_savedOwnerCannotBeValidatedOrRetaggedAsAnotherActor() {
        val original = f.entry()
        assertFailsWith<IllegalArgumentException> { original.validate(f.owner.copy(actorId = f.otherActor)) }
        val retagged = original.copy(owner = f.owner.copy(actorId = f.otherActor))
        assertFailsWith<IllegalArgumentException> { retagged.validate(retagged.owner) }
        assertEquals(f.actor, original.owner.actorId)
        assertEquals(f.operation, original.operationId)
        assertEquals(f.request().payload, original.request().payload)
    }
}
