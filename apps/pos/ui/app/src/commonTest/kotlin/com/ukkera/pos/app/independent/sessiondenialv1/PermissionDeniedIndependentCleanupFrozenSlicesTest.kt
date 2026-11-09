package com.ukkera.pos.app.independent.sessiondenialv1

import com.ukkera.pos.app.EntryViewModel
import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalDraftValue
import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalFormField
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingOperation
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalReferenceResult
import com.ukkera.pos.app.LocalReferenceSource
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalSessionDraftEpochs
import com.ukkera.pos.app.LocalWorkflow
import com.ukkera.pos.app.independent.sessionpresentationv1.FrozenSensitivePresentationUiInputs as Lsp
import com.ukkera.pos.app.independent.sessionpresentationv1.SensitiveControlledSession
import com.ukkera.pos.app.independent.sessionpresentationv1.SensitiveControlledWorkflowBoundary
import com.ukkera.pos.app.independent.sessionpresentationv1.SensitiveFormControl
import com.ukkera.pos.app.independent.sessionpresentationv1.SensitivePresentationRuntimeFixture
import com.ukkera.pos.app.independent.sessionpresentationv1.sensitiveControlText
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.ui.CatalogReleaseEditor
import com.ukkera.pos.app.ui.CompanySkuEditor
import com.ukkera.pos.app.ui.FoodRecipeEditor
import com.ukkera.pos.app.ui.LocalConfigurationEntryLifetime
import com.ukkera.pos.app.ui.PricePolicyEditor
import com.ukkera.pos.app.ui.ReturnRightsEditor
import com.ukkera.pos.app.ui.SerialRegistrationEditor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * Bounded controller/entry slices of local-session-presentation-v1 LSP-002-A01/A04,
 * LSP-010-A01/A03 and terminal-cleanup-v1 STC-S10/S12/S08. Frozen outcomes are unchanged.
 * Current DENIED reaches the production controller through its reference read. The real
 * entry configuration holders receive a controlled failing presentation callback.
 *
 * Generic form/read carriers and controlled sessions do not bind report schema, policy
 * activation, genuine authentication, protected RPC, rendered frames, durable original
 * money/evidence or native closure. Clearing a full denied descriptor below adds no
 * assertion about unsent safe-input restoration. The full cases and unbound layers remain
 * NOT_RUN; the late-error slice separately observes an allowed current safe form.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PermissionDeniedIndependentCleanupFrozenSlicesTest {
    @Test
    fun stcS10S12_currentDeniedClearsPrivateFormAndLaterConfigurationAfterCallbackFailure() = runTest {
        val form = SensitiveFormControl()
        var deniedReplies = 0
        val boundary = SensitiveControlledWorkflowBoundary(readReply = { query ->
            val page = (form.page(old = true, workflow = query.workflow) as LocalReadResult.Available).page
            LocalReadResult.Available(page.copy(forms = page.forms.map { descriptor ->
                descriptor.copy(fields = descriptor.fields.map { field ->
                    if (field is LocalFormField.Scalar && field.field == LocalField.SKU_ID)
                        field.copy(referenceSource = LocalReferenceSource(LocalRecordKind.SKU)) else field
                })
            }))
        }, referenceReply = {
            deniedReplies++
            LocalReferenceResult.Unavailable(LocalServiceProblem.DENIED)
        })
        val session = SensitiveControlledSession(initialBoundary = boundary)
        withFixture(session) { fixture ->
            val controller = fixture.controller()
            controller.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            val token = fixture.runtime.navigator.token()
            controller.openForm(LocalAction.ORDER_CREATE, token)
            controller.edit(LocalField.REASON, LocalDraftValue(Lsp.PRIVATE_MARKER), fixture.runtime.navigator.token())
            check(controller.state.value.page != null && controller.state.value.form != null) {
                "Private controlled page and descriptor must be delivered before DENIED."
            }
            val entry = fixture.activeEntry()
            seedDeniedConfiguration(entry, Lsp.PRIVATE_MARKER)
            val fault = IllegalStateException("Synthetic required private-presentation cleanup failure")
            val cleanup = DeniedConfigurationProbe(entry, fault)
            val signIns = fixture.gateway.signIns
            val departedToken = fixture.runtime.navigator.token()
            try {
                // Ordinary page refresh deliberately refuses an open form. Its reference
                // picker is the supported current read while private form metadata is live.
                controller.openReferencePicker(LocalField.SKU_ID, fixture.runtime.navigator.token())
                runCurrent()
                assertTrue(deniedReplies > 0, "STC-S10: the current DENIED read actually reaches the controller")
                assertNull(controller.state.value.page, "LSP-002-A01 / STC-S12: old denied private page is absent")
                assertNull(controller.state.value.form, "LSP-002-A01 / STC-S12: old full private descriptor is absent")
                assertNull(controller.state.value.referencePicker, "STC-S12: old private reference presentation is absent")
                assertDeniedConfigurationCleared(entry, cleanup, "STC-S12")
                assertTrue(fixture.runtime.presentation.value.cleanupNeedsAttention,
                    "STC-S12: required cleanup failure remains observable")
                assertNull(fixture.runtime.localSessionFor(departedToken),
                    "STC-S12: failed terminal cleanup cannot restore old local access")
                assertFalse(fixture.runtime.navigator.isCurrent(departedToken),
                    "STC-S12: old callbacks cannot retain the failed local scope")
                assertNull(fixture.runtime.presentation.value.display,
                    "STC-S12: failed cleanup keeps ordinary private employee presentation hidden")
                fixture.runtime.openSignIn(company = false, fixture.runtime.navigator.token())
                fixture.runtime.signIn("ACTOR-A", charArrayOf('s', 'a', 'm', 'p', 'l', 'e'), fixture.runtime.navigator.token())
                assertEquals(signIns, fixture.gateway.signIns,
                    "STC-S12: required cleanup failure blocks even controlled successor authentication delivery")
                assertNoDeniedMutationCalls(fixture, "LSP-002-A03/A04")

                val failedAttempts = cleanup.attempts.count { it == "catalog" }
                assertFailsWith<IllegalStateException>("STC-S12: failed exact callback remains reachable") {
                    entry.clearLocalConfigurationDrafts()
                }
                assertTrue(cleanup.attempts.count { it == "catalog" } > failedAttempts,
                    "STC-S12: a UI disappearance has not discarded the failed cleanup")
                cleanup.fail = false
                val beforeResolution = cleanup.attempts.count { it == "catalog" }
                entry.clearLocalConfigurationDrafts()
                assertTrue(cleanup.attempts.count { it == "catalog" } > beforeResolution,
                    "STC-S12: resolution retries the original callback")
                assertNoDeniedMutationCalls(fixture, "LSP-002-A03/A04")
            } finally {
                cleanup.fail = false
            }
        }
    }

    @Test
    fun lsp010A01A03_stcS08_formerContextDeniedCannotClearCurrentSafeInput() = runTest {
        val saved = LocalPendingOperation(Lsp.PAYMENT_ID, LocalAction.PAYMENT_ATTEST,
            sensitiveControlText(Lsp.PRIVATE_MARKER), emptyList(), submitAllowed = false,
            phase = LocalPendingPhase.DISPATCH_UNRESOLVED)
        val oldBoundary = SensitiveControlledWorkflowBoundary(saved = listOf(saved))
        val held = oldBoundary.holdNextLookup(LocalOperationResult.Unresolved(Lsp.PAYMENT_ID, LocalServiceProblem.DENIED))
        val session = SensitiveControlledSession(initialBoundary = oldBoundary)
        withFixture(session) { fixture ->
            val runtime = fixture.runtime
            val original = fixture.controller()
            original.activate(LocalWorkflow.CHECKOUT)
            runCurrent()
            original.lookup(Lsp.PAYMENT_ID, runtime.navigator.token())
            runCurrent()
            check(held.captured.isCompleted) { "The old-context denial must be held before policy adoption." }
            val oldToken = runtime.navigator.token()
            val before = assertNotNull(runtime.presentation.value.display)
            val form = SensitiveFormControl()
            val currentBoundary = SensitiveControlledWorkflowBoundary(readReply = { query ->
                form.page(old = false, workflow = query.workflow)
            })
            session.planRenewal(before.copy(allowedAreas = setOf(AppArea.CASHIER, AppArea.ORDERS),
                localDraftEpochs = LocalSessionDraftEpochs(2, 1)), currentBoundary)
            runtime.refreshLocalSession(runtime.navigator.token())
            runCurrent()
            runtime.navigate(NavigationAction.SelectArea(AppArea.ORDERS), runtime.navigator.token())
            val current = fixture.controller()
            current.activate(LocalWorkflow.ORDERS)
            runCurrent()
            current.changeSearch(Lsp.SAFE_SEARCH, runtime.navigator.token())
            runCurrent()
            current.openForm(LocalAction.ORDER_CREATE, runtime.navigator.token())
            current.edit(LocalField.REASON, LocalDraftValue(Lsp.PRIVATE_MARKER), runtime.navigator.token())
            current.confirmForm(true, runtime.navigator.token())
            check(current.state.value.form?.values?.get(LocalField.REASON)?.text == Lsp.PRIVATE_MARKER) {
                "The current allowed safe reason must be populated before releasing the former-context denial."
            }
            val safeState = current.state.value
            val navigation = runtime.navigator.state.value
            val presentation = runtime.presentation.value
            val calls = currentBoundary.calls.toList()

            held.release()
            runCurrent()
            original.refresh(oldToken)
            original.refresh(runtime.navigator.token())
            original.lookup(Lsp.PAYMENT_ID, oldToken)
            original.lookup(Lsp.PAYMENT_ID, runtime.navigator.token())
            runCurrent()

            assertEquals(safeState, current.state.value,
                "LSP-010-A01 / STC-S08: old DENIED cannot erase the current safe form, search, confirmation or state")
            assertEquals(navigation, runtime.navigator.state.value,
                "LSP-010-A01 / STC-S08: old DENIED cannot navigate the current workflow")
            assertEquals(presentation, runtime.presentation.value,
                "LSP-010-A01/A03: current employee presentation and safe status remain unchanged")
            assertEquals(calls, currentBoundary.calls,
                "LSP-010-A03 / STC-S08: departed callback cannot issue current boundary work")
            assertTrue(session.isCurrent(), "LSP-010-A03: former-context denial is distinct from current revocation")
            assertFalse(runtime.presentation.value.locallyLocked, "LSP-010-A01: old DENIED cannot logout current A")
            assertNoDeniedMutationCalls(fixture, "LSP-010-A03")
        }
    }

    private suspend fun TestScope.withFixture(session: SensitiveControlledSession,
        block: suspend TestScope.(SensitivePresentationRuntimeFixture) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var fixture: SensitivePresentationRuntimeFixture? = null
        try {
            val current = SensitivePresentationRuntimeFixture(session).also { fixture = it }
            current.signIn()
            runCurrent()
            block(current)
        } finally {
            try { fixture?.close(); runCurrent() } finally { Dispatchers.resetMain() }
        }
    }
}

/** Incomplete private editor text only; never converted, signed, staged or submitted. */
internal fun seedDeniedConfiguration(entry: EntryViewModel, marker: String) {
    entry.companyCatalogDrafts.editor.value = CompanySkuEditor(englishName = marker)
    entry.foodConfigurationDrafts.recipeEditor.value = FoodRecipeEditor(english = marker)
    entry.returnRightsDrafts.editor.value = ReturnRightsEditor(english = marker)
    entry.pricePolicyDrafts.editor.value = PricePolicyEditor(entryId = marker)
    entry.catalogReleaseDrafts.editor.value = CatalogReleaseEditor(catalogId = marker)
    entry.serialRegistrationDrafts.editor.value = SerialRegistrationEditor(serialCode = marker)
}

/** Observes the real entry lifetime API. Fault resolution changes only a synthetic callback. */
internal class DeniedConfigurationProbe(entry: EntryViewModel, private val failure: Throwable? = null) {
    var fail = failure != null
    val attempts = mutableListOf<String>()
    val captured: List<Triple<String, LocalConfigurationEntryLifetime, Any>> = listOf(
        "catalog" to entry.companyCatalogDrafts.lifetime,
        "food" to entry.foodConfigurationDrafts.lifetime,
        "returns" to entry.returnRightsDrafts.lifetime,
        "pricing" to entry.pricePolicyDrafts.lifetime,
        "release" to entry.catalogReleaseDrafts.lifetime,
        "serial" to entry.serialRegistrationDrafts.lifetime,
    ).map { (name, lifetime) -> Triple(name, lifetime, lifetime.identity) }

    init {
        captured.forEach { (name, lifetime, identity) ->
            lifetime.attach(identity) {
                attempts += name
                if (name == "catalog" && fail) throw checkNotNull(failure)
            }
        }
    }
}

internal fun assertDeniedConfigurationCleared(entry: EntryViewModel, cleanup: DeniedConfigurationProbe, message: String) {
    assertNull(entry.companyCatalogDrafts.editor.value, "$message: private catalog editor is unavailable")
    assertNull(entry.foodConfigurationDrafts.recipeEditor.value, "$message: private food editor is unavailable")
    assertNull(entry.returnRightsDrafts.editor.value, "$message: private return editor is unavailable")
    assertNull(entry.pricePolicyDrafts.editor.value, "$message: private price editor is unavailable")
    assertNull(entry.catalogReleaseDrafts.editor.value, "$message: private release editor is unavailable")
    assertFalse(entry.serialRegistrationDrafts.editor.value.dirty, "$message: private serial editor is unavailable")
    cleanup.captured.forEach { (name, lifetime, identity) ->
        assertTrue(name in cleanup.attempts, "$message: independent $name cleanup was actually attempted")
        assertFalse(lifetime.isCurrent(identity), "$message: former $name callback identity is invalidated")
        assertTrue(lifetime.accessDenied || entry.scopeClosed,
            "$message: denied $name presentation cannot publish through an ended entry")
    }
}

private fun assertNoDeniedMutationCalls(fixture: SensitivePresentationRuntimeFixture, message: String) {
    assertEquals(0, fixture.calls.count { it.method in setOf("STAGE", "SUBMIT", "ACKNOWLEDGE") },
        "$message: no controlled initiating business mutation or acknowledgement; no durable-ledger claim")
}
