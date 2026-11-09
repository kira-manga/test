package com.ukkera.pos.app.independent.sessionterminalv1

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ukkera.pos.app.EntryPresentation
import com.ukkera.pos.app.EntryViewModel
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingOperation
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalReadResult
import com.ukkera.pos.app.LocalRetailPhysicalDenial
import com.ukkera.pos.app.LocalSessionDraftEpochs
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.app.LocalWorkflowPage
import com.ukkera.pos.app.LocalWorkspaceState
import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.UiRootScope
import com.ukkera.pos.app.independent.sessionpresentationv1.SensitiveControlledSession
import com.ukkera.pos.app.independent.sessionpresentationv1.SensitiveControlledWorkflowBoundary
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.ui.CompanySkuEditor
import com.ukkera.pos.app.ui.FoodRecipeEditor
import com.ukkera.pos.app.ui.PricePolicyEditor
import com.ukkera.pos.app.ui.ReturnRightsEditor
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.koin.core.parameter.parametersOf

/** Controlled private DTOs and negative teardown probes, never authentication/native authority. */
internal fun terminalUiSession(freeze: FrozenTerminalCleanupV1, actorB: Boolean = false,
    boundary: SensitiveControlledWorkflowBoundary = terminalUiBoundary(freeze),
): SensitiveControlledSession = SensitiveControlledSession(SessionDisplayContext(
    actorId = freeze.id(if (actorB) "account_b" else "account_a"),
    companyId = freeze.id("retail_company"), branchId = freeze.id("retail_branch"),
    actorName = if (actorB) "ACTOR-B" else "ACTOR-A", companyName = "COMPANY-1", branchName = "BRANCH-A",
    allowedAreas = setOf(AppArea.CASHIER, AppArea.ORDERS, AppArea.REPORTS),
    localDraftEpochs = LocalSessionDraftEpochs(1, 1),
), boundary)

internal fun terminalUiBoundary(freeze: FrozenTerminalCleanupV1): SensitiveControlledWorkflowBoundary {
    val saved = LocalPendingOperation(freeze.paymentOperation(), LocalAction.PAYMENT_ATTEST,
        terminalText(freeze.privateCanary(0)),
        listOf(com.ukkera.pos.app.LocalRecordValue(terminalText("Original private display"), terminalText(freeze.privateCanary(0)))),
        submitAllowed = false, phase = LocalPendingPhase.DISPATCH_UNRESOLVED)
    return SensitiveControlledWorkflowBoundary(readReply = { query ->
        LocalReadResult.Available(LocalWorkflowPage(query.workflow, emptyList(), emptyList(),
            notice = terminalText(freeze.privateCanary(2))))
    }, saved = listOf(saved), lookupReply = { LocalOperationResult.Pending(saved) })
}

/** The real root's Koin factory/ViewModelStore creates the second fixture entry registration. */
internal fun terminalEntry(root: UiRootScope, id: Long): EntryViewModel {
    val provider = ViewModelProvider.create(root.viewModelStore,
        factory = viewModelFactory { initializer { root.koinScope.get<EntryViewModel> { parametersOf(id) } } })
    return provider["entry-$id", EntryViewModel::class].also(root::register)
}

/** External entry-store ownership drops before disposal, including when disposal throws. */
internal class TerminalDisposableEntryOwner {
    private var store: ViewModelStore? = ViewModelStore()
    val ownsStore: Boolean get() = store != null

    fun entry(root: UiRootScope, id: Long): EntryViewModel {
        val provider = ViewModelProvider.create(checkNotNull(store),
            factory = viewModelFactory { initializer { root.koinScope.get<EntryViewModel> { parametersOf(id) } } })
        return provider["entry-$id", EntryViewModel::class].also(root::register)
    }

    fun removeAndClear() {
        val original = checkNotNull(store)
        store = null
        original.clear()
    }
}

/** Incomplete private text only; no draft is converted, signed, staged or executed. */
internal fun seedTerminalPrivateInput(entry: EntryViewModel, freeze: FrozenTerminalCleanupV1) {
    entry.change { it.copy(query = freeze.privateCanary(0), countedCash = freeze.privateCanary(1)) }
    entry.companyCatalogDrafts.editor.value = CompanySkuEditor(englishName = freeze.privateCanary(1))
    entry.foodConfigurationDrafts.recipeEditor.value = FoodRecipeEditor(english = freeze.privateCanary(1))
    entry.returnRightsDrafts.editor.value = ReturnRightsEditor(english = freeze.privateCanary(1))
    entry.pricePolicyDrafts.editor.value = PricePolicyEditor(entryId = freeze.privateCanary(1))
}

internal fun assertTerminalPrivateInputCleared(entry: EntryViewModel, message: String) {
    assertEquals(EntryPresentation(), entry.state.value, message)
    assertNull(entry.companyCatalogDrafts.editor.value, message)
    assertNull(entry.foodConfigurationDrafts.recipeEditor.value, message)
    assertNull(entry.returnRightsDrafts.editor.value, message)
    assertNull(entry.pricePolicyDrafts.editor.value, message)
}

internal fun assertTerminalReadCleared(state: LocalWorkspaceState, message: String) {
    assertNull(state.page, message)
    assertNull(state.form, message)
    assertNull(state.formInput, message)
    assertNull(state.reference, message)
    assertNull(state.operation, message)
    assertEquals(emptyList(), state.pending, message)
}

/** A fixture-owned negative callback. Failure/late success cannot supply a positive grant. */
internal class TerminalDenialProbe(private val failure: Throwable? = null) : LocalRetailPhysicalDenial {
    var fail = failure != null
    var attempts = 0
        private set
    var released = false
        private set

    override fun deny() {
        attempts++
        if (fail) throw checkNotNull(failure)
        released = true
    }
}

internal fun terminalText(value: String): LocalText = LocalText(value, value)
