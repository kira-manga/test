package com.ukkera.pos.app.independent.sessionpresentationv1

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalActionForm
import com.ukkera.pos.app.LocalDraftValue
import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalFoodOperationDetails
import com.ukkera.pos.app.LocalFormDraft
import com.ukkera.pos.app.LocalFormField
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingOperation
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalRecord
import com.ukkera.pos.app.LocalRecordedDecision
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalRecordValue
import com.ukkera.pos.app.LocalScalarValue
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSessionDraftEpochs
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.LocalWorkspaceState
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.independent.sessionnoaccessv1.FrozenNoAccessUiInputs
import com.ukkera.pos.app.independent.sessionpresentationv1.FrozenSensitivePresentationUiInputs as Lsp
import com.ukkera.pos.app.independent.sessionpresentationv1.FrozenSensitiveSupplementUiInputs as Lsna
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.AppRoute
import com.ukkera.pos.app.navigation.LeaveGuard
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.NavigationResult
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Shared-presentation portions of the unchanged LSP and LSNA freezes. Real common runtime,
 * entry ViewModels, controller actions and navigation consume explicitly controlled replies.
 * Private labels/read-only values are typed UI metadata carriers, not a business form schema.
 * Only the fixed supported reason/search has an unsent-input continuity assertion (AMB-005).
 *
 * Recorded/result/food inputs below are inert controlled presentation payloads. They establish
 * neither master completion nor any payment/food decision, full body, recipe or portion effect.
 * No original business submission, durable checkpoint, protected journal, policy activation,
 * admitted RPC, rendered frame/semantics or native lifecycle is executed by this harness.
 * All complete assertions/cases/variants and those required layers remain NOT_RUN.
 *
 * LSNA P11 is an already-valid-session Current observation, not positive zero-grant login or
 * renewal admission. OM-01 and OM-02/AMB-006 remain unresolved. LRR referenced-route validation
 * schedules and complete restoration cases are not exercised by these retirement slices.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionSensitivePresentationFrozenSlicesTest {
    @Test
    fun lsp001A01A03V01_supportedReasonSearchRebindOnlyAfterExplicitCurrentRead() = runTest {
        exerciseFormRebind(reportNarrowing = false)
    }

    @Test
    fun lsp002A01Lsp029V02_supportedCashierInputDoesNotCarryRetiredReadMetadata() = runTest {
        exerciseFormRebind(reportNarrowing = true)
    }

    @Test
    fun lsp001A02A03_recoveryIdSurvivesWithoutTheOldPrivatePendingProjection() = runTest {
        val saved = controlledPending(Lsp.PAYMENT_ID, LocalAction.PAYMENT_ATTEST, Lsp.PRIVATE_MARKER)
        val oldBoundary = SensitiveControlledWorkflowBoundary(saved = listOf(saved),
            lookupReply = { LocalOperationResult.Pending(saved) })
        val session = SensitiveControlledSession(initialBoundary = oldBoundary)
        withFixture(session) { fixture ->
            val runtime = fixture.runtime
            val before = assertNotNull(runtime.presentation.value.display)
            val original = fixture.controller()
            original.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            original.lookup(Lsp.PAYMENT_ID, runtime.navigator.token())
            runCurrent()
            val selected = assertIs<LocalOperationResult.Pending>(original.state.value.operation)
            check(selected.operation.summary.any { it.value.english == Lsp.PRIVATE_MARKER }) {
                "The controlled private pending projection must reach the real controller before retirement."
            }
            val oldToken = runtime.navigator.token()
            val oldCalls = oldBoundary.calls.toList()
            val nextBoundary = SensitiveControlledWorkflowBoundary()
            session.planRenewal(before, nextBoundary)
            runtime.refreshLocalSession(runtime.navigator.token())
            runCurrent()

            val continued = fixture.controller()
            continued.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            assertSameOwner(before, assertNotNull(runtime.presentation.value.display), Lsp.RENEWAL_IDENTITY)
            assertEquals(Lsp.PAYMENT_ID, continued.state.value.operationId,
                Lsp.RENEWAL_IDENTITY.message("the selected original presentation ID is retained"))
            assertRetiredReadProjection(continued.state.value, Lsp.PRIVACY)
            assertFalse(continued.state.value.canStart,
                Lsp.RECOVERY.message("removing private details does not authorize a new request"))
            assertTrue(nextBoundary.calls.isEmpty(),
                Lsp.RENEWAL_BOUNDARY.message("renewal/activation alone starts no successor boundary work"))
            assertNoMutationCalls(fixture, Lsp.NO_AUTO)

            original.lookup(Lsp.PAYMENT_ID, oldToken)
            original.lookup(Lsp.PAYMENT_ID, runtime.navigator.token())
            original.submit(Lsp.PAYMENT_ID, runtime.navigator.token())
            original.acknowledgeRecorded(Lsp.PAYMENT_ID, runtime.navigator.token())
            runCurrent()
            assertEquals(oldCalls, oldBoundary.calls,
                Lsp.RENEWAL_BOUNDARY.message("retired registrations cannot call their departed boundary"))

            continued.reviewAttachment(runtime.navigator.token())
            runCurrent()
            assertFalse(continued.state.value.attachmentReviewRequired,
                Lsp.RENEWAL_BOUNDARY.message("one explicit controlled current preflight is accepted"))
            assertEquals(Lsp.PAYMENT_ID, continued.state.value.operationId,
                Lsp.RECOVERY.message("an empty current pending page does not erase the selected original ID"))
            assertTrue(nextBoundary.calls.none { it.method == "LOOKUP" },
                Lsp.RENEWAL_BOUNDARY.message("preflight is not automatic outcome recovery"))
            continued.lookup(Lsp.PAYMENT_ID, runtime.navigator.token())
            runCurrent()
            val lookup = nextBoundary.calls.filter { it.method == "LOOKUP" }.single()
            assertEquals(Lsp.PAYMENT_ID, lookup.operationId,
                Lsp.RENEWAL_BOUNDARY.message("the explicit lookup uses the original ID"))
            assertEquals(runtime.presentation.value.localAttachment, lookup.attachment,
                Lsp.RENEWAL_BOUNDARY.message("the explicit lookup uses the adopted successor boundary"))
            val unknown = assertIs<LocalOperationResult.Unresolved>(continued.state.value.operation,
                Lsp.RECOVERY.message("the unavailable controlled reread does not invent completion or rejection"))
            assertEquals(Lsp.PAYMENT_ID, unknown.operationId,
                Lsp.RENEWAL_IDENTITY.message("the visible unknown result remains the original presentation ID"))
            assertNoMutationCalls(fixture, Lsp.NO_AUTO)
        }
    }

    @Test
    fun recoveryOnly_controlledKnownStatusOutlivesRetiredOpaqueResultAndFoodDetails() = runTest {
        val saved = controlledPending(Lsp.PAYMENT_ID, LocalAction.PAYMENT_ATTEST, Lsp.PRIVATE_MARKER)
        val knownInput = controlledPrivateResult(Lsp.PAYMENT_ID, Lsp.PRIVATE_MARKER,
            LocalRecordReference(LocalRecordKind.PAYMENT, DomainId.random()), includeOpaqueFood = true)
        val oldBoundary = SensitiveControlledWorkflowBoundary(saved = listOf(saved), lookupReply = { knownInput })
        val session = SensitiveControlledSession(initialBoundary = oldBoundary)
        withFixture(session) { fixture ->
            val runtime = fixture.runtime
            val original = fixture.controller()
            original.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            original.lookup(Lsp.PAYMENT_ID, runtime.navigator.token())
            runCurrent()
            val delivered = assertIs<LocalOperationResult.Recorded>(original.state.value.operation)
            check(delivered.result?.title?.english == Lsp.PRIVATE_MARKER &&
                delivered.food?.code == Lsp.PRIVATE_MARKER) {
                "Both opaque controlled private projections must be delivered before their cleanup is observed."
            }
            val nextBoundary = SensitiveControlledWorkflowBoundary()
            session.planRenewal(session.display, nextBoundary)
            runtime.refreshLocalSession(runtime.navigator.token())
            runCurrent()
            val continued = fixture.controller()
            continued.activate(LocalWorkflow.CHECKOUT)
            runCurrent()

            assertKnownIdentityWithoutDetails(continued.state.value)
            assertTrue(nextBoundary.calls.isEmpty(),
                Lsp.NO_AUTO.message("known-status continuation performs no automatic reread, replay or acknowledgement"))
            continued.reviewAttachment(runtime.navigator.token())
            runCurrent()
            assertFalse(continued.state.value.attachmentReviewRequired,
                Lsp.RECOVERY.message("explicit current preflight supplies only controlled fresh read controls"))
            assertKnownIdentityWithoutDetails(continued.state.value)
            continued.lookup(Lsp.PAYMENT_ID, runtime.navigator.token())
            runCurrent()
            assertKnownIdentityWithoutDetails(continued.state.value)
            assertEquals(LocalServiceProblem.UNAVAILABLE, continued.state.value.operationRead?.problem,
                Lsp.RECOVERY.message("unavailable current detail reread is separate from previously known status"))
            assertFalse(continued.state.value.canStart,
                Lsp.RECOVERY.message("the original result has not been silently finalized or forgotten"))
            continued.acknowledgeRecorded(Lsp.PAYMENT_ID, runtime.navigator.token())
            continued.submit(Lsp.PAYMENT_ID, runtime.navigator.token())
            runCurrent()
            assertEquals(listOf(Lsp.PAYMENT_ID), nextBoundary.calls.filter { it.method == "LOOKUP" }.map { it.operationId },
                Lsp.RECOVERY.message("there is exactly the separately scheduled original-ID controlled reread"))
            assertNoMutationCalls(fixture, Lsp.RECOVERY)
            assertKnownIdentityWithoutDetails(continued.state.value)
        }
    }

    @Test
    fun lsp018V02Lsp020V02Lsp027_lateOpaqueKitchenResultCannotChangeSameActorRouteEntityReplacement() = runTest {
        // An opaque route coordinate, not an asserted food order/portion/business fixture.
        val orderId = DomainId.random()
        val route = AppRoute.Order(orderId)
        val reference = LocalRecordReference(LocalRecordKind.ORDER, orderId)
        val saved = controlledPending(Lsp.KITCHEN_ID, LocalAction.KITCHEN_SEND, Lsp.PRIVATE_MARKER)
        val oldBoundary = SensitiveControlledWorkflowBoundary(saved = listOf(saved))
        val held = oldBoundary.holdNextLookup(controlledPrivateResult(Lsp.KITCHEN_ID, Lsp.PRIVATE_MARKER,
            reference, includeOpaqueFood = true))
        val originalSession = SensitiveControlledSession(initialBoundary = oldBoundary)
        withFixture(originalSession) { fixture ->
            val runtime = fixture.runtime
            runtime.navigate(NavigationAction.SelectArea(AppArea.ORDERS), runtime.navigator.token())
            runtime.navigate(NavigationAction.Push(route), runtime.navigator.token())
            check(runtime.navigator.state.value.activeEntry.route == route)
            val original = fixture.controller()
            original.activate(LocalWorkflow.ORDERS, reference)
            runCurrent()
            original.changeSearch(Lsp.SAFE_SEARCH, runtime.navigator.token())
            runCurrent()
            original.lookup(Lsp.KITCHEN_ID, runtime.navigator.token())
            runCurrent()
            held.captured.await()
            val oldToken = runtime.navigator.token()
            val oldEntry = runtime.navigator.state.value.activeEntry.id
            val oldCalls = oldBoundary.calls.toList()
            val oldDisplay = assertNotNull(runtime.presentation.value.display)

            // The registered public Logout action closes the UI before unavailable revocation.
            runtime.navigate(NavigationAction.ExitWorkspace, runtime.navigator.token())
            assertEquals(SecurityRoot.BOOTSTRAP, runtime.navigator.state.value.root,
                Lsp.DEPARTED_UI.message("the explicit public departure immediately removes the private workspace"))
            assertNull(runtime.presentation.value.display,
                Lsp.PRIVACY.message("the departed employee display is absent before reentry"))
            runCurrent()
            val formControl = SensitiveFormControl()
            val nextBoundary = SensitiveControlledWorkflowBoundary(readReply = {
                formControl.page(old = false, workflow = LocalWorkflow.ORDERS)
            })
            val currentSession = SensitiveControlledSession(oldDisplay, nextBoundary)
            fixture.gateway.useNextSession(currentSession)
            fixture.signIn()
            runtime.navigate(NavigationAction.SelectArea(AppArea.ORDERS), runtime.navigator.token())
            runtime.navigate(NavigationAction.Push(route), runtime.navigator.token())
            val fresh = fixture.controller()
            fresh.activate(LocalWorkflow.ORDERS, reference)
            runCurrent()
            assertEquals("", fresh.state.value.search,
                Lsp.DEPARTED_UI.message("fresh same-route reentry does not restore the departed search"))
            assertNull(fresh.state.value.operationId,
                Lsp.RECOVERY.message("fresh ordinary UI does not silently adopt the old kitchen operation"))
            assertFalse(oldEntry == runtime.navigator.state.value.activeEntry.id,
                Lsp.LATE_UI.message("matching actor/route/entity does not reuse the departed entry owner"))
            fresh.openForm(LocalAction.ORDER_CREATE, runtime.navigator.token())
            fresh.edit(LocalField.REASON, LocalDraftValue(Lsp.CURRENT_MARKER), runtime.navigator.token())
            fresh.confirmForm(true, runtime.navigator.token())
            runtime.navigate(NavigationAction.Back, runtime.navigator.token())
            check(runtime.navigator.state.value.confirmation != null && runtime.navigator.state.value.guard == LeaveGuard.DIRTY)
            val freshBefore = fresh.state.value
            val navigationBefore = runtime.navigator.state.value
            val displayBefore = runtime.presentation.value.display
            val currentCallsBefore = nextBoundary.calls.toList()

            held.release()
            runCurrent()
            original.lookup(Lsp.KITCHEN_ID, oldToken)
            original.lookup(Lsp.KITCHEN_ID, runtime.navigator.token())
            original.submit(Lsp.KITCHEN_ID, runtime.navigator.token())
            original.acknowledgeRecorded(Lsp.KITCHEN_ID, runtime.navigator.token())
            runtime.navigate(NavigationAction.Back, oldToken)
            runCurrent()
            assertEquals(freshBefore, fresh.state.value,
                Lsp.LATE_UI.message("the replacement reason/confirmation and all current controller state are unchanged"))
            assertEquals(navigationBefore, runtime.navigator.state.value,
                Lsp.LATE_UI.message("late old delivery neither navigates nor clears the replacement dialog/guard"))
            assertEquals(displayBefore, runtime.presentation.value.display,
                Lsp.FOOD_UI.message("the same valid replacement actor is not changed by the opaque food result"))
            assertTrue(currentSession.isCurrent(), Lsp.LATE_UI.message("the replacement controlled session remains current"))
            assertEquals(oldCalls, oldBoundary.calls,
                Lsp.LATE_BOUNDARY.message("late completion and old registrations dispatch no departed calls"))
            assertEquals(currentCallsBefore, nextBoundary.calls,
                Lsp.FOOD_BOUNDARY.message("late completion starts no new current boundary call"))
            assertEquals(2, fixture.gateway.signIns,
                Lsp.NO_AUTO.message("only the two explicit controlled login selections occurred"))
            assertNoMutationCalls(fixture, Lsp.FOOD_BOUNDARY)
        }
    }

    @Test
    fun lsna01_02_05_06_currentNoAreasAndRegrantDoNotAdoptOriginalUnknownRecoveryUi() = runTest {
        val operationId = Lsna.ORIGINAL_PARTIAL_PAYMENT_ID
        val saved = controlledPending(operationId, LocalAction.PAYMENT_ATTEST, FrozenNoAccessUiInputs.PRIVATE_SEARCH,
            listOf(
                LocalRecordValue(sensitiveControlText("EGP minor"), sensitiveControlText(Lsna.ORIGINAL_PARTIAL_PAYMENT_MINOR)),
                LocalRecordValue(sensitiveControlText("Payable revision"), sensitiveControlText(Lsna.ORIGINAL_PAYABLE_REVISION)),
                LocalRecordValue(sensitiveControlText("Terminal reference"), sensitiveControlText(Lsna.ORIGINAL_TERMINAL_REFERENCE)),
            ))
        val oldBoundary = SensitiveControlledWorkflowBoundary(saved = listOf(saved))
        val session = SensitiveControlledSession(FrozenNoAccessUiInputs.p10Display(), oldBoundary)
        withFixture(session) { fixture ->
            val runtime = fixture.runtime
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { runtime.monitorAuthenticatedSession() }
            val original = fixture.controller()
            original.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            original.changeSearch(FrozenNoAccessUiInputs.PRIVATE_SEARCH, runtime.navigator.token())
            runCurrent()
            original.lookup(operationId, runtime.navigator.token())
            runCurrent()
            check((original.state.value.operation as? LocalOperationResult.Unresolved)?.operationId == operationId)
            val held = oldBoundary.holdNextLookup(controlledPrivateResult(operationId,
                FrozenNoAccessUiInputs.PRIVATE_SEARCH,
                LocalRecordReference(LocalRecordKind.SKU, FrozenNoAccessUiInputs.sku),
                cost = "6100", marginOrValuation = "61000", includeOpaqueFood = false))
            original.lookup(operationId, runtime.navigator.token())
            runCurrent()
            held.captured.await()
            // A controlled draft flag binds the existing product confirmation. The full frozen
            // quantity-3 cart and its business form remain unbound, as in the earlier LSNA slices.
            runtime.navigator.setRootDraft(true)
            check(runtime.navigator.registerGuard(runtime.navigator.state.value.activeEntry.id, LeaveGuard.DIRTY) != null)
            runtime.navigate(NavigationAction.Back, runtime.navigator.token())
            val oldConfirmation = assertNotNull(runtime.navigator.state.value.confirmation).id
            val oldToken = runtime.navigator.token()
            val oldEntries = runtime.navigator.state.value.stacks.values.flatten().map { it.id }.toSet()
            val oldCalls = oldBoundary.calls.toList()
            val noAreasBoundary = SensitiveControlledWorkflowBoundary()

            // Current observed policy only; never a fabricated successful P11 login/renewal.
            session.publishCurrent(FrozenNoAccessUiInputs.p11Display(), noAreasBoundary)
            runCurrent()
            assertNoAreas(fixture, session)
            assertNull(original.state.value.operationId,
                Lsna.UNKNOWN_CLEANUP.message("retired presentation does not expose the original private recovery identity"))
            assertNull(original.state.value.operation,
                Lsna.NO_AREAS_RETIRED.message("retired presentation has no original private result"))
            assertTrue(original.state.value.pending.isEmpty(),
                Lsna.NO_AREAS_RETIRED.message("old private pending summaries are retired, not copied to status"))
            assertEquals("", original.state.value.search,
                Lsna.NO_AREAS_RETIRED.message("the original transient private search is cleared"))
            assertTrue(runtime.navigator.state.value.stacks.values.flatten().none { it.id in oldEntries },
                Lsna.NO_AREAS_RETIRED.message("all former private entry owners are retired"))
            val acquisitions = session.workflowAcquisitions
            assertNull(runtime.localSessionFor(runtime.navigator.token()),
                Lsna.NO_AREAS_UI.message("no-area status has no operational or recovery session binding"))
            assertNull(fixture.activeEntry().localWorkflows(runtime, runtime.navigator.token()),
                Lsna.NO_AUTH_RECOVERY.message("status cannot acquire a workflow/recovery controller"))
            assertNull(fixture.activeEntry().localAdministration(runtime, runtime.navigator.token()),
                Lsna.NO_AUTH_RECOVERY.message("status cannot acquire an administration controller"))
            assertEquals(acquisitions, session.workflowAcquisitions,
                Lsna.NO_AREAS_UI.message("the safe surface acquires no operational gateway"))
            original.lookup(operationId, oldToken)
            original.lookup(operationId, runtime.navigator.token())
            original.submit(operationId, runtime.navigator.token())
            original.acknowledgeRecorded(operationId, runtime.navigator.token())
            runtime.confirmLeave(oldConfirmation)
            runtime.navigator.cancelLeave(oldConfirmation)
            runtime.navigate(NavigationAction.Back, oldToken)
            for (area in FrozenNoAccessUiInputs.p10Display().allowedAreas) {
                runtime.navigate(NavigationAction.SelectArea(area), runtime.navigator.token())
                assertNoAreas(fixture, session)
            }
            runCurrent()
            assertEquals(oldCalls, oldBoundary.calls,
                Lsna.NO_ACCESS_LATE.message("old recovery/confirmation/navigation registrations start no old calls"))
            assertTrue(noAreasBoundary.calls.isEmpty(),
                Lsna.NO_AREAS_BOUNDARY.message("P11 observation and attempted UI reentry start no protected read or recovery"))

            val regrantBoundary = SensitiveControlledWorkflowBoundary()
            session.publishCurrent(FrozenNoAccessUiInputs.p12Display(), regrantBoundary)
            runCurrent()
            assertEquals(AppRoute.Cashier, runtime.navigator.state.value.activeEntry.route,
                Lsna.REGRANT_UI.message("the narrow controlled regrant starts at a fresh permitted entry"))
            assertTrue(runtime.navigator.state.value.stacks.values.flatten().none { it.id in oldEntries },
                Lsna.REGRANT_UI.message("regrant does not recover the withdrawn private entry history"))
            assertTrue(regrantBoundary.calls.isEmpty(),
                Lsna.REGRANT_BOUNDARY.message("policy observation alone is not automatic original recovery"))
            val fresh = fixture.controller()
            fresh.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            assertEquals("", fresh.state.value.search,
                Lsna.REGRANT_UI.message("fresh permitted UI has no restored original search"))
            assertNull(fresh.state.value.form, Lsna.REGRANT_UI.message("no old unsent form is restored"))
            assertNull(fresh.state.value.operationId, Lsna.NO_AUTH_RECOVERY.message("the old unknown operation is not silently adopted"))
            assertNull(fresh.state.value.operation, Lsna.REGRANT_UI.message("no old result is copied to the fresh UI"))
            assertNull(fresh.state.value.operationRead, Lsna.NO_AUTH_RECOVERY.message("no retired result-knowledge projection is copied"))
            assertTrue(fresh.state.value.pending.isEmpty(), Lsna.REGRANT_UI.message("fresh controlled pending input contains no old summary"))
            fresh.lookup(operationId, runtime.navigator.token())
            runCurrent()
            assertTrue(regrantBoundary.calls.none { it.method == "LOOKUP" },
                Lsna.NO_AUTH_RECOVERY.message("an unbound original ID cannot acquire recovery from the new UI"))
            val freshBefore = fresh.state.value
            val navigationBefore = runtime.navigator.state.value
            val callsBeforeLate = fixture.calls.toList()
            held.release()
            runCurrent()
            runtime.confirmLeave(oldConfirmation)
            runtime.navigate(NavigationAction.Back, oldToken)
            assertEquals(freshBefore, fresh.state.value,
                Lsna.NO_ACCESS_LATE.message("a held old controlled private result cannot alter the fresh permitted controller"))
            assertEquals(navigationBefore, runtime.navigator.state.value,
                Lsna.NO_ACCESS_LATE.message("old completion/confirmation cannot restore private history or change the fresh surface"))
            assertEquals(callsBeforeLate, fixture.calls,
                Lsna.REGRANT_BOUNDARY.message("late original delivery causes no new controlled read/recovery/dispatch"))
            assertEquals(1, fixture.gateway.signIns,
                Lsna.NO_AREAS_BOUNDARY.message("only attachment of the original controlled P10 session occurred"))
            assertNoMutationCalls(fixture, Lsna.UNKNOWN_CLEANUP)
        }
    }

    private suspend fun TestScope.exerciseFormRebind(reportNarrowing: Boolean) {
        val formControl = SensitiveFormControl()
        val oldBoundary = SensitiveControlledWorkflowBoundary(readReply = { formControl.page(old = true) })
        val session = SensitiveControlledSession(initialBoundary = oldBoundary)
        withFixture(session) { fixture ->
            val runtime = fixture.runtime
            val before = assertNotNull(runtime.presentation.value.display)
            val original = fixture.controller()
            original.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            original.changeSearch(Lsp.SAFE_SEARCH, runtime.navigator.token())
            runCurrent()
            original.openForm(LocalAction.ORDER_CREATE, runtime.navigator.token())
            original.edit(LocalField.REASON, LocalDraftValue(Lsp.PRIVATE_MARKER), runtime.navigator.token())
            original.edit(LocalField.SKU_ID, LocalDraftValue(selected = LocalScalarValue.Reference(formControl.reference),
                referenceLabel = sensitiveControlText(Lsp.PRIVATE_MARKER)), runtime.navigator.token())
            original.confirmForm(true, runtime.navigator.token())
            val populated = assertNotNull(original.state.value.form)
            check(populated.values[LocalField.REASON]?.text == Lsp.PRIVATE_MARKER && populated.confirmed)
            check(metadataText(populated.descriptor).any { it == Lsp.PRIVATE_MARKER } &&
                readOnlyText(populated).any { it == Lsp.COST_MINOR }) {
                "The private read-derived metadata and read-only values must be populated before renewal."
            }
            val entry = runtime.navigator.state.value.activeEntry.id
            val nextBoundary = SensitiveControlledWorkflowBoundary(readReply = { formControl.page(old = false) })
            val next = if (reportNarrowing) before.copy(allowedAreas = setOf(AppArea.CASHIER, AppArea.ORDERS),
                localDraftEpochs = LocalSessionDraftEpochs(2, 1)) else before
            val ui = if (reportNarrowing) Lsp.NARROWED_UI else Lsp.RENEWAL_UI
            val boundary = if (reportNarrowing) Lsp.COST_ONLY_BOUNDARY else Lsp.RENEWAL_BOUNDARY
            session.planRenewal(next, nextBoundary)
            runtime.refreshLocalSession(runtime.navigator.token())
            runCurrent()
            val continued = fixture.controller()
            continued.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            assertSameOwner(before, assertNotNull(runtime.presentation.value.display), ui)
            assertEquals(entry, runtime.navigator.state.value.activeEntry.id,
                ui.message("the same still-permitted cashier entry owns the supported input"))
            assertFalse(runtime.presentation.value.locallyLocked, ui.message("continuity/narrowing is not employee logout"))
            assertEquals(Lsp.SAFE_SEARCH, continued.state.value.search, ui.message("supported safe search remains recoverable"))
            assertNull(continued.state.value.page,
                Lsp.PRIVACY.message("old protected read metadata is absent before explicit current preflight"))
            assertNull(continued.state.value.form,
                Lsp.PRIVACY.message("the old descriptor/labels/confirmation are not exposed as a retained full form"))
            val checkpoint = assertNotNull(continued.state.value.formInput,
                ui.message("supported safe reason remains recoverable without the old form descriptor"))
            assertEquals(Lsp.PRIVATE_MARKER, checkpoint.values[LocalField.REASON]?.text,
                ui.message("only the explicitly supported entered reason is asserted to survive"))
            assertTrue(checkpoint.values.values.all { it.referenceLabel == null } &&
                checkpoint.lines.all { line -> line.values.values.all { it.referenceLabel == null } },
                Lsp.PRIVACY.message("read-derived reference labels are not carried in the input checkpoint"))
            assertNull(runtime.navigator.state.value.confirmation, ui.message("renewal supplies no implicit leave or business confirmation"))
            assertTrue(nextBoundary.calls.isEmpty(), boundary.message("attachment adoption/activation starts no implicit boundary call"))

            continued.reviewAttachment(runtime.navigator.token())
            runCurrent()
            val rebound = assertNotNull(continued.state.value.form,
                ui.message("the supported reason is recoverable after a separately explicit controlled current preflight"))
            assertEquals(Lsp.PRIVATE_MARKER, rebound.values[LocalField.REASON]?.text,
                ui.message("the supported entered reason survives the current-metadata rebind"))
            assertEquals(Lsp.SAFE_SEARCH, continued.state.value.search, ui.message("the supported search still belongs to this cashier workflow"))
            assertFalse(rebound.confirmed, ui.message("retained input never carries implicit confirmation"))
            assertFalse(continued.state.value.attachmentReviewRequired, boundary.message("explicit current read supplies current controls"))
            assertNoRetiredFormProjection(rebound)
            val read = nextBoundary.calls.filter { it.method == "READ" }.single()
            assertEquals(Lsp.SAFE_SEARCH, read.query?.search, boundary.message("explicit current preflight uses the supported current search"))
            assertEquals(runtime.presentation.value.localAttachment, read.attachment,
                boundary.message("fresh metadata comes from the adopted successor boundary"))
            assertTrue(nextBoundary.calls.none { it.method == "LOOKUP" }, boundary.message("input restoration is not automatic result recovery"))
            if (reportNarrowing) {
                assertFalse(AppArea.REPORTS in assertNotNull(runtime.presentation.value.display).allowedAreas,
                    Lsp.COST_ONLY_UI.message("current control metadata does not reinstate the removed report area"))
                assertEquals(NavigationResult.IGNORED,
                    runtime.navigator.navigate(NavigationAction.SelectArea(AppArea.REPORTS), runtime.navigator.token()),
                    Lsp.COST_ONLY_BOUNDARY.message("retired report-area navigation remains unavailable"))
            }
            assertEquals(1, fixture.gateway.signIns, ui.message("only the explicit original controlled login occurred"))
            assertNoMutationCalls(fixture, Lsp.NO_AUTO)
        }
    }

    private fun assertNoRetiredFormProjection(form: LocalFormDraft) {
        val metadata = metadataText(form.descriptor)
        assertTrue(metadata.none { it.contains(Lsp.PRIVATE_MARKER) || it == Lsp.COST_MINOR || it == Lsp.MARGIN_MINOR },
            Lsp.PRIVACY.message("fresh descriptor/title/help/choice/reference metadata contains no retired private or cost/margin value"))
        assertTrue(readOnlyText(form).none { it.contains(Lsp.PRIVATE_MARKER) || it == Lsp.COST_MINOR || it == Lsp.MARGIN_MINOR },
            Lsp.PRIVACY.message("read-only scalar and line projections do not copy the old private/cost values"))
        val labels = form.values.values.mapNotNull { it.referenceLabel } +
            form.lines.flatMap { it.values.values.mapNotNull { value -> value.referenceLabel } }
        assertTrue(labels.none { it.english.contains(Lsp.PRIVATE_MARKER) || it.arabic.contains(Lsp.PRIVATE_MARKER) },
            Lsp.PRIVACY.message("current reference labels are not the retired private projection"))
    }

    private fun assertKnownIdentityWithoutDetails(state: LocalWorkspaceState) {
        assertEquals(Lsp.PAYMENT_ID, state.operationId, Lsp.RECOVERY.message("original controlled known-result identity remains selected"))
        assertTrue(state.operationRead?.recorded == true,
            Lsp.RECOVERY.message("previously known controlled recorded status is not relabelled unknown"))
        // The visible recovery card uses this envelope and the separate previously recorded status.
        val currentRead = assertIs<LocalOperationResult.Unresolved>(state.operation,
            Lsp.PRIVACY.message("the visible original-result lookup retains no retired Pending/Recorded/result/food details"))
        assertEquals(Lsp.PAYMENT_ID, currentRead.operationId,
            Lsp.RECOVERY.message("the visible detail-reread control keeps the original known-result identity"))
        assertFalse(state.recordedAcknowledged, Lsp.RECOVERY.message("cleanup or a lost current read is not acknowledgement"))
    }

    private fun assertRetiredReadProjection(state: LocalWorkspaceState, oracle: FrozenSensitiveUiAssertion) {
        assertNull(state.page, oracle.message("old protected page is retired"))
        assertNull(state.form, oracle.message("old full form metadata is retired"))
        assertNull(state.referencePicker, oracle.message("old private reference directory is retired"))
        assertTrue(state.pending.isEmpty(), oracle.message("old private pending labels/summaries are retired"))
        assertTrue(state.operation == null || state.operation is LocalOperationResult.Unresolved,
            oracle.message("old Pending/Recorded private details are absent; no replacement result is fabricated"))
    }

    private fun assertNoAreas(fixture: SensitivePresentationRuntimeFixture, session: SensitiveControlledSession) {
        val runtime = fixture.runtime
        val navigation = runtime.navigator.state.value
        val display = assertNotNull(runtime.presentation.value.display, Lsna.NO_AREAS_UI.message("the valid employee identity remains distinct"))
        assertEquals(SecurityRoot.BRANCH_WORKSPACE, navigation.root, Lsna.NO_AREAS_UI.message("permission-only removal is not logout"))
        assertEquals(setOf(AppArea.SESSION), navigation.stacks.keys, Lsna.NO_AREAS_UI.message("no operational/recovery stack remains"))
        assertEquals(AppRoute.SessionAccess, navigation.activeEntry.route, Lsna.NO_AREAS_UI.message("safe existing session status is selected"))
        assertEquals(FrozenNoAccessUiInputs.employeeA, display.actorId, Lsna.NO_AREAS_UI.message("the original enabled employee remains A"))
        assertEquals(FrozenNoAccessUiInputs.company, display.companyId, Lsna.NO_AREAS_UI.message("same controlled company scope"))
        assertEquals(FrozenNoAccessUiInputs.branch, display.branchId, Lsna.NO_AREAS_UI.message("same controlled branch scope"))
        assertTrue(display.allowedAreas.isEmpty(), Lsna.NO_AREAS_UI.message("zero operational hints, not inferred recovery grants"))
        assertTrue(session.isCurrent(), Lsna.NO_AREAS_UI.message("the already-valid controlled session remains current"))
        assertFalse(runtime.presentation.value.locallyLocked, Lsna.NO_AREAS_UI.message("permission loss does not fabricate authentication lock"))
        assertNull(runtime.presentation.value.sessionProblem, Lsna.NO_AREAS_UI.message("no fabricated expired/disabled/logout classification"))
        assertNull(runtime.presentation.value.signInFailure, Lsna.NO_AREAS_UI.message("no fabricated failed login"))
        assertNull(navigation.confirmation, Lsna.NO_AREAS_RETIRED.message("original private confirmation is absent"))
        assertFalse(navigation.rootHasDraft, Lsna.NO_AREAS_RETIRED.message("original transient draft flag is cleared"))
    }

    private fun assertSameOwner(before: SessionDisplayContext, after: SessionDisplayContext, oracle: FrozenSensitiveUiAssertion) {
        assertEquals(before.actorId, after.actorId, oracle.message("controlled employee identity unchanged"))
        assertEquals(before.companyId, after.companyId, oracle.message("controlled company scope unchanged"))
        assertEquals(before.branchId, after.branchId, oracle.message("controlled branch scope unchanged"))
    }

    private fun assertNoMutationCalls(fixture: SensitivePresentationRuntimeFixture, oracle: FrozenSensitiveUiAssertion) {
        assertTrue(fixture.calls.none { it.method in setOf("STAGE", "SUBMIT", "ACKNOWLEDGE") },
            oracle.message("controlled initiating common UI stage/submit/acknowledgement calls equal zero"))
    }

    private fun metadataText(form: LocalActionForm): List<String> = buildList {
        fun text(value: LocalText?) { if (value != null) { add(value.english); add(value.arabic) } }
        fun scalar(field: LocalFormField.Scalar) {
            text(field.label); text(field.help)
            field.choices.forEach { text(it.label) }
            field.references.forEach { text(it.label) }
            when (val value = field.initialValue) {
                is LocalScalarValue.Text -> add(value.value)
                is LocalScalarValue.Integer -> add(value.value.toString())
                else -> Unit
            }
        }
        text(form.title); text(form.explanation); text(form.confirmation)
        form.fields.forEach { field -> when (field) {
            is LocalFormField.Scalar -> scalar(field)
            is LocalFormField.Lines -> { text(field.label); field.columns.forEach(::scalar) }
        } }
    }

    private fun readOnlyText(form: LocalFormDraft): List<String> = buildList {
        form.descriptor.fields.filterIsInstance<LocalFormField.Scalar>().filter { it.readOnly }.forEach { field ->
            form.values[field.field]?.let { add(it.text) }
        }
        form.descriptor.fields.filterIsInstance<LocalFormField.Lines>().forEach { group ->
            group.columns.filter { it.readOnly }.forEach { column ->
                form.lines.forEach { line -> line.values[column.field]?.let { add(it.text) } }
            }
        }
    }

    private fun controlledPending(id: OperationId, action: LocalAction, marker: String,
        extra: List<LocalRecordValue> = emptyList()): LocalPendingOperation = LocalPendingOperation(
        id, action, sensitiveControlText(marker),
        listOf(LocalRecordValue(sensitiveControlText("Private original display"), sensitiveControlText(marker))) + extra,
        submitAllowed = false, phase = LocalPendingPhase.DISPATCH_UNRESOLVED,
    )

    /** Opaque DTO carriers only. RECORDED_ONLY does not fabricate an applied monetary result. */
    private fun controlledPrivateResult(id: OperationId, marker: String, reference: LocalRecordReference,
        cost: String = Lsp.COST_MINOR, marginOrValuation: String = Lsp.MARGIN_MINOR,
        includeOpaqueFood: Boolean): LocalOperationResult.Recorded = LocalOperationResult.Recorded(
        id, LocalRecordedDecision.RECORDED_ONLY, sensitiveControlText(marker),
        result = LocalRecord(reference, sensitiveControlText(marker), sensitiveControlText(marker), listOf(
            LocalRecordValue(sensitiveControlText("Private read display"), sensitiveControlText(marker)),
            LocalRecordValue(sensitiveControlText("EGP minor read value"), sensitiveControlText(cost)),
            LocalRecordValue(sensitiveControlText("EGP minor read value"), sensitiveControlText(marginOrValuation)),
        )),
        food = if (includeOpaqueFood) LocalFoodOperationDetails(marker, null, null, emptyList(), null) else null,
    )

    private suspend fun TestScope.withFixture(session: SensitiveControlledSession,
        block: suspend TestScope.(SensitivePresentationRuntimeFixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fixtureToClose: SensitivePresentationRuntimeFixture? = null
        try {
            val fixture = SensitivePresentationRuntimeFixture(session).also { fixtureToClose = it }
            fixture.signIn()
            runCurrent()
            block(fixture)
        } finally {
            try { fixtureToClose?.close(); runCurrent() } finally { Dispatchers.resetMain() }
        }
    }
}
