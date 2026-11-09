package com.ukkera.pos.android.independent.nativesupplierworkflowv1

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.app.AppDependencies
import com.ukkera.pos.app.BranchUiGateway
import com.ukkera.pos.app.BranchUiSession
import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalOperationResult
import com.ukkera.pos.app.LocalPendingOperation
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalPendingResult
import com.ukkera.pos.app.LocalRecordedDecision
import com.ukkera.pos.app.LocalRecordKind
import com.ukkera.pos.app.LocalSessionAttachmentKey
import com.ukkera.pos.app.PlatformCapabilities
import com.ukkera.pos.app.PlatformFamily
import com.ukkera.pos.app.SignInResult
import com.ukkera.pos.app.UkkeraApp
import com.ukkera.pos.app.UkkeraRuntime
import com.ukkera.pos.app.navigation.AppArea
import com.ukkera.pos.app.navigation.NavigationAction
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.catalog.store.LocalNamesV1
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Actual shared UI over the case's native client, not a result renderer or an accepting service.
 * The test rule must be createEmptyComposeRule(): this owner launches and closes its own target
 * Activity. The real launcher/setup service is deliberately not started by this bounded host.
 *
 * A comparison projection is NEVER supplied to UkkeraApp. Original selection and explicit ACK
 * use the production controls, controller, protected gateway and native intent journal. The
 * same exact authenticated BranchUiSession is returned unchanged to UkkeraRuntime and the case.
 * This supports P's displayed-original boundary, not complete installed-UI or RPC qualification.
 */
internal class NativeSupplierDisplay(
    private val ui: ComposeTestRule,
    private val evidence: NativeSupplierEvidence,
    private val timeoutMillis: Long = 60_000,
) : AutoCloseable {
    private var activity: ActivityScenario<ComponentActivity>? = null
    private var runtime: UkkeraRuntime? = null
    private var client: NativeSupplierClient? = null
    private var observedGateway: NativeSupplierSessionObservation? = null
    private var selected: NativeSupplierDisplayedOriginal? = null
    private var uiStagedOperation: OperationId? = null
    private var displaySequence = 0L

    /** Captured from an actual successful runtime sign-in, never manufactured from display IDs. */
    val session: BranchUiSession
        get() = ui.runOnUiThread { requireCurrentSession() }

    fun open(client: NativeSupplierClient) {
        check(runtime == null && activity == null && this.client == null)
        check(client.original.isCurrent())
        this.client = client
        val gateway = NativeSupplierSessionObservation(client.gateway, client.observer::retire)
        observedGateway = gateway
        val actual = ui.runOnUiThread {
            UkkeraRuntime(AppDependencies(branchGateway = gateway,
                platform = PlatformCapabilities(PlatformFamily.ANDROID)))
        }
        runtime = actual // Retain before Activity acquisition can fail.
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val host = ActivityScenario.launch<ComponentActivity>(Intent(target, ComponentActivity::class.java))
        activity = host
        host.onActivity { it.setContent { UkkeraApp(actual) } }
        ui.waitForIdle()
        ui.runOnUiThread {
            check(actual.navigator.state.value.root == SecurityRoot.BOOTSTRAP &&
                actual.presentation.value.display == null && !actual.presentation.value.training &&
                gateway.authenticated == null && gateway.signIns == 0)
        }
        evidence.event("SHARED_UI_HOST_OPENED_WITHOUT_AUTOMATIC_LOGIN", JSONObject()
            .put("entry", "UkkeraApp")
            .put("host", "localTesting-only ComponentActivity")
            .put("installed_launcher_qualification", false))
    }

    /** Consumes/wipes this password buffer. No password String or UI text is constructed. */
    suspend fun signIn(account: String, secret: CharArray): BranchUiSession {
        val actual = checkNotNull(runtime)
        check(checkNotNull(observedGateway).authenticated == null)
        val issued = try {
            withContext(Dispatchers.Main.immediate) {
                actual.openSignIn(company = false, actual.navigator.token())
                actual.signIn(account, secret, actual.navigator.token())
                val authenticated = requireCurrentSession()
                // signIn awaits the genuine gateway and publishes this session synchronously
                // before returning. Select the ordinary authorized area in that same Main
                // continuation, before composition can start an unrelated default workspace.
                // No display, grants, workspace mode or refused read is replaced or ignored.
                actual.navigate(NavigationAction.SelectArea(AppArea.PURCHASING), actual.navigator.token())
                check(actual.navigator.state.value.selectedArea == AppArea.PURCHASING &&
                    requireCurrentSession() === authenticated)
                authenticated
            }
        } finally { secret.fill('\u0000') }
        ui.waitForIdle()
        check(session === issued)
        val owner = checkNotNull(client)
        val current = owner.observer.currentView
        val coordinates = owner.original.material.coordinates
        check(current.actorId == coordinates.firstActorId && current.scope == coordinates.scope &&
            current.deviceId == coordinates.deviceId && issued.display.actorId == current.actorId &&
            issued.display.companyId == current.scope.companyId && issued.display.branchId == current.scope.branchId)
        ui.runOnUiThread {
            check(actual.navigator.state.value.selectedArea == AppArea.PURCHASING)
            check(requireCurrentSession() === issued)
        }
        evidence.event("GENUINE_SESSION_PUBLISHED_TO_SHARED_UI", JSONObject()
            .put("sessionId", current.sessionId.value)
            .put("gateway_session_forwarded_unchanged", true))
        return issued
    }

    /** Ordinary CREATE form and Stage action only; no submission or acknowledgement is made. */
    suspend fun stageCreate(names: LocalNamesV1, active: Boolean): NativeSupplierUiStaged {
        val actualSession = session
        requireNoPending(actualSession)
        awaitReadReady()
        selected = null
        button(CREATE).assertIsEnabled().performClick()
        return stageCurrentForm(actualSession, LocalAction.SUPPLIER_CREATE, null, names, active)
    }

    /**
     * Select the actual supplier through its protected directory, then its ordinary UPDATE form.
     * Its expected revision is READ from the current read-only field, never supplied or replaced.
     * In particular this is NOT a seam for authoring the separately frozen saved-R1 stale input.
     */
    suspend fun stageUpdate(supplierId: DomainId, names: LocalNamesV1, active: Boolean): NativeSupplierUiStaged {
        val actualSession = session
        requireNoPending(actualSession)
        awaitReadReady()
        selected = null
        if (ui.onAllNodes(hasText(ALL_RECORDS) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) {
            button(ALL_RECORDS).assertIsEnabled().performClick()
            awaitReadReady()
        }
        field(SEARCH).assertIsEnabled().performTextReplacement(supplierId.value)
        button(REFRESH).assertIsEnabled().performClick()
        awaitReadReady()
        // The exact-ID search is interpreted by the real protected directory. Its sole record
        // card carries this actual revision label; no caller-created supplier row is installed.
        visible(hasClickAction() and hasText(STORAGE_REVISION)).assertIsEnabled().performClick()
        awaitReadReady()
        button(UPDATE).assertIsEnabled().performClick()
        return stageCurrentForm(actualSession, LocalAction.SUPPLIER_UPDATE, supplierId, names, active)
    }

    private suspend fun stageCurrentForm(actualSession: BranchUiSession, action: LocalAction,
        selectedSupplier: DomainId?, names: LocalNamesV1, active: Boolean): NativeSupplierUiStaged {
        check(uiStagedOperation == null && session === actualSession)
        val supplierId = DomainId(readTextField("Supplier *"))
        check(selectedSupplier == null || supplierId == selectedSupplier)
        val creating = action == LocalAction.SUPPLIER_CREATE
        check(toggleValue(NEW_RECORD) == creating)
        val revision = if (creating) null else Revision(readTextField("$STORAGE_REVISION *").toLong())
        field(ENGLISH_NAME).assertIsEnabled().performTextReplacement(names.english)
        check(readTextField(ENGLISH_NAME) == names.english)
        field(ARABIC_NAME).assertIsEnabled().performTextReplacement(names.arabic)
        check(readTextField(ARABIC_NAME) == names.arabic)
        setToggle(ACTIVE, active)
        setToggle(CONFIRM_INPUT, true)
        button(STAGE).assertIsEnabled().performClick()
        // Move to the ordinary form/pending header, where the actual async Stage response is
        // rendered. This observes completion, not a timed sleep or repeated staging attempt.
        scrollTo(hasText(if (creating) CREATE else UPDATE))
        ui.waitUntil(timeoutMillis) { ui.onAllNodes(hasText(SAVED)).fetchSemanticsNodes().isNotEmpty() }
        // This is a fresh protected pending read of the genuine durable journal AFTER the UI
        // has reported Staged. It cannot invent K, stage an intent, or dispatch a mutation.
        val pending = actualSession.workflows.pending() as? LocalPendingResult.Available
            ?: error("The UI-staged original is unavailable in the genuine pending journal")
        check(pending.nextCursor == null && pending.operations.size == 1)
        val original = pending.operations.single()
        check(original.action == action && original.phase == LocalPendingPhase.STAGED && original.submitAllowed)
        fun savedValue(label: String): String = original.summary.single { it.label.english == label }.value.english
        check(savedValue("Supplier") == supplierId.value && savedValue(NEW_RECORD) == creating.toString() &&
            savedValue(ENGLISH_NAME) == names.english && savedValue(ARABIC_NAME) == names.arabic &&
            savedValue(ACTIVE) == active.toString())
        if (revision == null) check(original.summary.none { it.label.english == STORAGE_REVISION })
        else check(savedValue(STORAGE_REVISION) == revision.value.toString())
        check(session === actualSession)
        textVisible("\u2066${original.operationId.value}\u2069")
        uiStagedOperation = original.operationId
        evidence.event("ACTUAL_SHARED_UI_DURABLE_STAGE_BEFORE_SUBMIT", JSONObject()
            .put("operationId", original.operationId.value).put("action", action.name)
            .put("intendedSupplierId", supplierId.value)
            .put("expectedStorageRevision", revision?.value ?: JSONObject.NULL)
            .put("entry", "UkkeraApp supplier form and Save operation for review")
            .put("submission_performed", false))
        return NativeSupplierUiStaged(original, supplierId, revision)
    }

    private suspend fun requireNoPending(actualSession: BranchUiSession) {
        check(uiStagedOperation == null)
        val pending = actualSession.workflows.pending() as? LocalPendingResult.Available
            ?: error("Current saved-operation access is unavailable")
        check(pending.operations.isEmpty() && pending.nextCursor == null && session === actualSession) {
            "Outstanding originals must be genuinely displayed and explicitly acknowledged first"
        }
    }

    /**
     * Select the saved ID through the real exact-original UI action, including archived originals.
     * The endpoint completion barrier prevents an old identical screen from satisfying a new
     * lookup. Current lifetime checks and ordinary UI busy completion also have to settle.
     * The independently bound semantic result is compared; transient replay-envelope flags are
     * not original business content. No automatic ACK, staging, submission or journal edit occurs.
     */
    fun displayOriginal(expected: LocalOperationResult.Recorded): NativeSupplierDisplayedOriginal {
        check(expected.food == null && expected.returnStock == null &&
            expected.result?.reference?.kind?.let { it == LocalRecordKind.SUPPLIER } != false)
        val actualSession = session
        val owner = checkNotNull(client)
        val lookup: SemanticsNodeInteraction
        if (uiStagedOperation == expected.operationId) {
            // The controller still owns its frozen UI-staged form after the case's separate
            // submission. Its existing saved-original action is the legitimate continuation.
            lookup = button(CHECK_OUTCOME)
            awaitEnabled(CHECK_OUTCOME)
        } else {
            check(uiStagedOperation == null)
            scrollTo(hasText(ORIGINAL_ID))
            ui.waitUntil(timeoutMillis) {
                ui.onAllNodes(hasText(ORIGINAL_ID) and hasSetTextAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            field(ORIGINAL_ID).assertIsEnabled().performTextReplacement(expected.operationId.value)
            lookup = button(LOOKUP_ORIGINAL)
            awaitEnabled(LOOKUP_ORIGINAL)
        }
        val previousLookups = owner.observer.completedOriginalLookups(expected.operationId)
        selected = null
        lookup.assertIsEnabled().performClick()
        ui.waitUntil(timeoutMillis) {
            owner.observer.completedOriginalLookups(expected.operationId) > previousLookups
        }
        // The endpoint can finish before gateway validation and UI publication. The original
        // ID/ACK item may not exist yet, or may be outside the composed lazy-list viewport.
        // Scroll to the always-present ordinary read control and await controller completion
        // first, then discover the actual result and ACK with the lazy-list scroll action.
        // An empty original-ID editor is not a completion signal after saved-card lookup.
        awaitReadReady()
        check(session === actualSession)
        assertRecordedVisible(expected)
        button(ACKNOWLEDGE).assertIsEnabled()
        uiStagedOperation = null
        val actual = checkNotNull(runtime)
        val shown = ui.runOnUiThread {
            check(requireCurrentSession() === actualSession)
            NativeSupplierDisplayedOriginal(this, ++displaySequence, expected,
                actualSession, actual.presentation.value.localAttachment,
                actual.navigator.state.value.rootGeneration,
                owner.observer.completedOriginalLookups(expected.operationId))
        }
        selected = shown
        evidence.event("AUTHORIZED_ORIGINAL_ACTUALLY_DISPLAYED", JSONObject()
            .put("operationId", expected.operationId.value)
            .put("displaySequence", shown.sequence)
            .put("successfulProtectedLookupCount", shown.completedLookups)
            .put("entry", "UkkeraApp original-result lookup")
            .put("decision", expected.decision.name))
        return shown
    }

    /** Explicit separate UI action, valid only for the same result actually displayed above. */
    fun acknowledgeDisplayed(displayed: NativeSupplierDisplayedOriginal) {
        requireSameDisplay(displayed)
        // Reobserve the actual display immediately before its explicit action; the handle itself
        // is only an observation selector and cannot grant authority or assert journal durability.
        assertRecordedVisible(displayed.result)
        val owner = checkNotNull(client)
        val previousLookups = owner.observer.completedOriginalLookups(displayed.operationId)
        button(ACKNOWLEDGE).assertIsEnabled().performClick()
        ui.waitUntil(timeoutMillis) {
            owner.observer.completedOriginalLookups(displayed.operationId) > previousLookups
        }
        // The receipt is published before the controller's independent follow-up reads finish.
        // Their pending-list changes can move it offscreen. Await the ordinary read control,
        // then scroll to the genuine acknowledgement rather than polling uncomposed content
        // or assuming the follow-up reads are no-ops.
        awaitReadReady()
        requireSameDisplay(displayed)
        textVisible(ACKNOWLEDGED)
        check(ui.onAllNodes(hasText(ACKNOWLEDGE) and hasClickAction()).fetchSemanticsNodes().isEmpty()) {
            "The displayed original acknowledgement has not settled"
        }
        selected = null
        evidence.event("EXPLICIT_DISPLAYED_ORIGINAL_UI_ACKNOWLEDGED", JSONObject()
            .put("operationId", displayed.operationId.value)
            .put("displaySequence", displayed.sequence)
            .put("entry", "UkkeraApp displayed-result acknowledgement"))
    }

    /** Real UI logout; local clearance is immediate, authoritative cleanup is reported truthfully. */
    suspend fun signOut() {
        val actual = checkNotNull(runtime)
        val outgoing = session
        selected = null
        uiStagedOperation = null
        checkNotNull(client).observer.retire() // Destroy the extra runtime-only named-read custody now.
        withContext(Dispatchers.Main.immediate) {
            actual.signOut()
            check(actual.navigator.state.value.root == SecurityRoot.BOOTSTRAP &&
                actual.presentation.value.display == null && !outgoing.isCurrent())
        }
        ui.waitUntil(timeoutMillis) { !actual.presentation.value.revocationPending }
        val state = ui.runOnUiThread { actual.presentation.value }
        check(state.display == null && !state.training)
        evidence.event("SHARED_UI_LOGOUT_CLEARED_PRIVATE_ACCESS", JSONObject()
            .put("revocationPending", state.revocationPending)
            .put("cleanupNeedsAttention", state.cleanupNeedsAttention)
            .put("native_master_stop_requested", false))
    }

    /** UI-only lifecycle. The case must separately await its genuine native owner's close. */
    override fun close() {
        selected = null
        uiStagedOperation = null
        client?.observer?.retire()
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try { action() } catch (caught: Throwable) {
                if (failure == null) failure = caught else checkNotNull(failure).addSuppressed(caught)
            }
        }
        runtime?.let { actual -> attempt {
            ui.runOnUiThread { actual.close(); check(actual.closed) }
            runtime = null
        } }
        activity?.let { actual -> attempt { actual.close(); activity = null } }
        failure?.let { throw it }
        client = null
        observedGateway = null
        evidence.event("SHARED_UI_HOST_CLOSE_CONFIRMED_NOT_NATIVE_MASTER_CLOSE")
    }

    private fun requireSameDisplay(displayed: NativeSupplierDisplayedOriginal) {
        check(displayed.owner === this && selected === displayed)
        ui.runOnUiThread {
            val actual = checkNotNull(runtime)
            check(requireCurrentSession() === displayed.session &&
                actual.presentation.value.localAttachment == displayed.attachment &&
                actual.navigator.state.value.rootGeneration == displayed.rootGeneration)
        }
    }

    /** Must be sampled on the UI thread; it is a lifetime check, not protected authorization. */
    private fun requireCurrentSession(): BranchUiSession {
        val actual = checkNotNull(runtime)
        val captured = checkNotNull(checkNotNull(observedGateway).authenticated)
        check(!actual.closed && actual.navigator.state.value.root == SecurityRoot.BRANCH_WORKSPACE &&
            !actual.presentation.value.training && actual.presentation.value.display == captured.display &&
            actual.presentation.value.localAttachment == captured.attachmentKey && captured.isCurrent())
        return captured
    }

    private fun assertRecordedVisible(result: LocalOperationResult.Recorded) {
        // IdentifierText displays these exact direction isolates. A typed lookup field or a
        // caller's saved ID alone cannot satisfy this original-result display assertion.
        textVisible("\u2066${result.operationId.value}\u2069")
        textVisible(when (result.decision) {
            LocalRecordedDecision.APPLIED -> "Durable result recorded"
            LocalRecordedDecision.REJECTED -> "Rejection durably recorded"
            LocalRecordedDecision.RECORDED_ONLY -> error("Supplier directory result is not a documentary-only result")
        })
        textVisible(result.message.english)
        result.result?.let { record ->
            textVisible(record.title.english)
            record.status?.let { textVisible(it.english) }
            record.values.forEach { value ->
                textVisible(value.label.english)
                textVisible(if (value.value.english == value.value.arabic) "\u2066${value.value.english}\u2069"
                    else value.value.english)
            }
        }
    }

    private fun field(label: String): SemanticsNodeInteraction = visible(hasText(label) and hasSetTextAction())
    private fun button(label: String): SemanticsNodeInteraction = visible(hasText(label) and hasClickAction())

    private fun readTextField(label: String): String = visible(hasText(label) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).fetchSemanticsNode().config
        .getOrNull(SemanticsProperties.EditableText)?.text ?: error("The real supplier form field is unavailable")

    private fun toggle(label: String): SemanticsNodeInteraction = visible(hasText(label) and
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState))

    private fun toggleValue(label: String): Boolean =
        toggle(label).fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On

    private fun setToggle(label: String, checked: Boolean) {
        val target = toggle(label).assertIsEnabled()
        if ((target.fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.On) != checked)
            target.performClick()
        check(toggleValue(label) == checked)
    }

    private fun awaitReadReady() {
        button(REFRESH)
        awaitEnabled(REFRESH)
    }

    private fun visible(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        scrollTo(matcher)
        return ui.onNode(matcher).performScrollTo().assertIsDisplayed()
    }

    private fun textVisible(value: String) {
        val matcher = hasText(value)
        scrollTo(matcher)
        val matches = ui.onAllNodes(matcher, useUnmergedTree = true)
        check(matches.fetchSemanticsNodes().isNotEmpty()) { "The selected original has missing displayed content" }
        matches[0].performScrollTo().assertIsDisplayed()
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        ui.onNode(hasScrollToNodeAction() and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(matcher)
    }

    private fun awaitEnabled(label: String) = ui.waitUntil(timeoutMillis) { enabled(label) }
    private fun enabled(label: String): Boolean =
        ui.onAllNodes(hasText(label) and hasClickAction() and isEnabled()).fetchSemanticsNodes().isNotEmpty()

    private companion object {
        const val ORIGINAL_ID = "Original operation ID"
        const val LOOKUP_ORIGINAL = "Look up original result"
        const val CHECK_OUTCOME = "Check original outcome"
        const val ACKNOWLEDGE = "Acknowledge result and continue"
        const val ACKNOWLEDGED = "Result acknowledgement saved. Master and financial records are retained."
        const val SEARCH = "Search this workflow"
        const val REFRESH = "Refresh current data"
        const val ALL_RECORDS = "Back to all records"
        const val CREATE = "Create local supplier"
        const val UPDATE = "Update local supplier"
        const val NEW_RECORD = "Explicitly create a new record"
        const val STORAGE_REVISION = "Expected storage revision"
        const val ENGLISH_NAME = "English name"
        const val ARABIC_NAME = "Arabic name"
        const val ACTIVE = "Active"
        const val CONFIRM_INPUT = "I reviewed the exact identifiers, amounts and evidence. Save this immutable intent for separate submission."
        const val STAGE = "Save operation for review"
        const val SAVED = "The operation intent is durably saved. This is not confirmation of business completion."
    }
}

/** Real UI-stage/pending observations. CREATE's intended ID is not yet a committed S1 binding. */
internal data class NativeSupplierUiStaged(
    val operation: LocalPendingOperation,
    val supplierId: DomainId,
    val expectedStorageRevision: Revision?,
)

/** An observed UI selection, not an authorization capability or a fabricated acknowledgement. */
internal class NativeSupplierDisplayedOriginal internal constructor(
    internal val owner: NativeSupplierDisplay,
    internal val sequence: Long,
    internal val result: LocalOperationResult.Recorded,
    internal val session: BranchUiSession,
    internal val attachment: LocalSessionAttachmentKey?,
    internal val rootGeneration: Long,
    internal val completedLookups: Long,
) {
    val operationId: OperationId get() = result.operationId
}

/** Observe only. In particular, no BranchUiSession/workflows/current result is wrapped or replaced. */
private class NativeSupplierSessionObservation(
    private val actual: BranchUiGateway,
    private val retireNamedReads: () -> Unit,
) : BranchUiGateway by actual {
    @Volatile var authenticated: BranchUiSession? = null
        private set
    @Volatile var signIns = 0
        private set

    override suspend fun signIn(account: String, secret: CharArray): SignInResult<BranchUiSession> =
        observe { actual.signIn(account, secret) }

    override suspend fun signIn(account: String, secret: CharArray, explicitDeviceMove: Boolean): SignInResult<BranchUiSession> =
        observe { actual.signIn(account, secret, explicitDeviceMove) }

    private suspend fun observe(call: suspend () -> SignInResult<BranchUiSession>): SignInResult<BranchUiSession> {
        check(authenticated == null)
        signIns++
        val result = try { call() } catch (failure: Throwable) {
            // Endpoint authentication can succeed before native credential persistence or
            // session publication fails. Retire the observation's extra custody immediately;
            // do not wait for UI error rendering or the enclosing case's finally block.
            try { retireNamedReads() } catch (cleanupFailure: Throwable) {
                if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
            }
            throw failure
        }
        if (result is SignInResult.Authenticated) authenticated = result.session else retireNamedReads()
        return result
    }
}
