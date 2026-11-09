package com.ukkera.pos.android.independent.nativesupplierworkflowv1

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalConfigurationCodec
import com.ukkera.pos.catalog.store.LocalSupplierV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bounded Android P observations against native-supplier-workflow-v1 plus its v2 clarification.
 * No fresh-database substitute, test principal, accepting native proof, fake display or RPC.
 * CREATE/UPDATE use real shared-UI Stage; the saved-R1 refusal is explicitly synthetic input at
 * the public application gateway because the UI does not offer that old-form generation path.
 * Missing setup inputs and that application-generation obligation prevent a whole NSUP-01 PASS.
 * All commands/results/ACKs, credential custody, native reads and owner closes remain genuine.
 */
@RunWith(AndroidJUnit4::class)
class NativeSupplierWorkflowTest {
    @get:Rule val ui = createEmptyComposeRule()

    @Test fun protectedNativeSupplierBoundedAssertions(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("syntheticNativeSupplierWorkflow") == "true") {
            "Explicit syntheticNativeSupplierWorkflow=true is required on an isolated synthetic localTesting installation"
        }
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing")
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")) {
            "Select keyProtection=OS_ISOLATED, SECURE_HARDWARE or STRONGBOX explicitly; no fallback is used"
        })
        val evidence = NativeSupplierEvidence(application)
        val originals = NativeSupplierOriginals(evidence)
        val owner = NativeSupplierOwner(application, protection, evidence, originals)
        val display = NativeSupplierDisplay(ui, evidence)
        val secrets = mutableListOf<CharArray>()
        fun secret(): CharArray = NativeSupplierFixture.secret().also { secrets += it }
        var failure: Throwable? = null
        var assertionsFinished = false
        var cleanupConfirmed = false
        fun remember(caught: Throwable) {
            val before = failure
            if (before == null) failure = caught else if (before !== caught) before.addSuppressed(caught)
        }
        try {
            val installer = secret()
            val head = secret()
            val password = secret()
            evidence.event("BOUNDED_RUN_SCOPE", JSONObject().put("keyProtection", protection.name)
                .put("nativeSetupDeliveryAck", "NOT_RUN_CONTRACT_UNSPECIFIED")
                .put("staleApplicationGeneration", "NOT_QUALIFIED_SAVED_R1_UI_PATH_MISSING")
                .put("wholeCase", "NOT_QUALIFIED")
                .put("unasserted", JSONArray(listOf("full setup fixture descriptive/activity/currency/timezone/locales inputs",
                    "K_STALE production-controller generation", "secret non-disclosure scan of every native record",
                    "additional Client/RPC", "complete installed/device/owner acceptance"))))
            owner.setup(installer, head, password)
            installer.fill('\u0000'); head.fill('\u0000')
            val initial = owner.census("NSUP01_INITIAL_FRESH_NATIVE_CENSUS")
            initial.requireInitialNoBusiness()

            val first = owner.openClient() // Normal production history admission follows the supplementary census.
            check(!first.observer.hasNamedReadAccess)
            display.open(first)
            val session1 = supplierSecret(password) { display.signIn(NativeSupplierFixture.login.value, it) }
            val issued1 = requireSupplierSession(first, session1, "SESSION_1", evidence)
            requireSupplierPending(session1, emptySet())
            requireSupplierRead(first, session1, null, false, "NSUP01_EMPTY_PAGE", evidence, "B0")

            // The controller generates the operation ID. Its separate Stage performs no execute.
            val createStage = display.stageCreate(NativeSupplierFixture.createNames, active = true)
            check(createStage.expectedStorageRevision == null)
            evidence.bind("R0", createStage.expectedStorageRevision?.value)
            val create = originals.staged("K_CREATE", createStage.operation,
                NativeSupplierCommandInput(createStage.supplierId, NativeSupplierFixture.createNames, true,
                    createStage.expectedStorageRevision), first.intentOwner(), NativeSupplierInputOrigin.SHARED_UI_STAGE)
            check(requireSupplierPending(session1, setOf(create.operationId)).single().submitAllowed)
            val createResult = requireSupplierResult(create, session1.workflows.submit(create.operationId), true, evidence)
            val supplierId = checkNotNull(LocalConfigurationCodec.decodeResult(checkNotNull(create.result).result).id)
            evidence.bind("S1", supplierId.value)
            val created = LocalSupplierV1(supplierId, NativeSupplierFixture.createNames, true)
            requireSupplierRead(first, session1, created, false, "NSUP01_CREATE_PAGE", evidence, "B1")
            val createdRow = checkNotNull(requireSupplierRead(first, session1, created, true, "NSUP01_CREATE_DETAIL", evidence))
            check(createdRow.revision == LocalConfigurationCodec.decodeResult(checkNotNull(create.result).result).revision)
            display.acknowledgeDisplayed(display.displayOriginal(createResult)) // Explicit C2-ACK after the required reads.
            requireSupplierPending(session1, emptySet())

            // Keep the actual public form for the later lower-boundary stale input. No current
            // revision is inferred from the create result or incremented by the harness.
            val originalUpdateForm = readSupplierForm(session1, supplierId)
            val updateStage = display.stageUpdate(supplierId, NativeSupplierFixture.updateNames, active = false)
            val r1 = checkNotNull(updateStage.expectedStorageRevision)
            check(updateStage.supplierId == supplierId && originalUpdateForm.expectedStorageRevision == r1)
            evidence.bind("R1", r1.value)
            val update = originals.staged("K_UPDATE", updateStage.operation,
                NativeSupplierCommandInput(supplierId, NativeSupplierFixture.updateNames, false, r1),
                first.intentOwner(), NativeSupplierInputOrigin.SHARED_UI_STAGE)
            check(requireSupplierPending(session1, setOf(update.operationId)).single().submitAllowed)
            val updateResult = requireSupplierResult(update, session1.workflows.submit(update.operationId), true, evidence)
            val updated = LocalSupplierV1(supplierId, NativeSupplierFixture.updateNames, false)
            val updatedRow = checkNotNull(requireSupplierRead(first, session1, updated, true, "NSUP01_UPDATE_DETAIL", evidence))
            val r2 = updatedRow.storageRevision
            check(r2 != r1 && updatedRow.revision == LocalConfigurationCodec.decodeResult(checkNotNull(update.result).result).revision)
            evidence.bind("R2", r2.value)
            display.acknowledgeDisplayed(display.displayOriginal(updateResult))
            requireSupplierPending(session1, emptySet())

            // Deliberate qualification boundary, not a passing substitute for the UI old-form
            // requirement. The frozen saved R1 is unchanged, and the real gateway/native intent
            // journal, current Master, durable optimistic refusal and later recovery all run.
            val staleIntent = originalUpdateForm.syntheticStaleIntent()
            evidence.event("SYNTHETIC_STALE_APPLICATION_INPUT_BEFORE_STAGE", JSONObject()
                .put("operationId", staleIntent.operationId.value).put("savedR1", r1.value)
                .put("controllerGenerated", false).put("wholeCase", "NOT_QUALIFIED"))
            val staleStage = session1.workflows.stage(staleIntent) as? LocalStageResult.Staged
                ?: error("The synthetic saved-R1 input did not reach genuine durable application stage")
            val stale = originals.staged("K_STALE", staleStage.operation,
                NativeSupplierCommandInput(supplierId, NativeSupplierFixture.staleNames, true, r1),
                first.intentOwner(), NativeSupplierInputOrigin.SYNTHETIC_APPLICATION_BOUNDARY)
            val staleResult = requireSupplierResult(stale, session1.workflows.submit(stale.operationId), false, evidence)
            check(requireSupplierRead(first, session1, updated, true, "NSUP01_STALE_LEFT_CURRENT_SUPPLIER_UNCHANGED", evidence)?.storageRevision == r2)
            // Keep STALE genuinely recorded and unacknowledged, but unselected in the UI.
            // Step 8 does not require its display. A selected unacknowledged result must not
            // be displaced merely to display the archived UPDATE required by step 11.
            val stalePending = requireSupplierPending(session1, setOf(stale.operationId)).single()
            check(stalePending.phase == LocalPendingPhase.RECORDED_UNACKNOWLEDGED && !stalePending.submitAllowed &&
                stalePending.recordedDecision == LocalRecordedDecision.REJECTED)

            val resolved = listOf(create to createResult, update to updateResult, stale to staleResult)
            for ((original, expected) in resolved) {
                requireSameSupplierUiResult(expected, session1.workflows.lookup(original.operationId))
                evidence.event("NSUP01_CURRENT_ORIGINAL_LOOKUP", JSONObject().put("operationId", original.operationId.value))
            }
            for ((original, expected) in resolved) {
                // submit consumes the exact journal-owned body; no restage, changed R1, new ID,
                // response cache, selected principal or fabricated absence authorizes this call.
                requireSameSupplierUiResult(expected, session1.workflows.submit(original.operationId))
                evidence.event("NSUP01_EXACT_SAVED_ORIGINAL_DUPLICATE", JSONObject().put("operationId", original.operationId.value))
            }
            check(requireSupplierRead(first, session1, updated, true, "NSUP01_CURRENT_AFTER_ORIGINAL_RECOVERY", evidence)?.storageRevision == r2)
            check(requireSupplierPending(session1, setOf(stale.operationId)).single() == stalePending)

            // Step 11 is retained even after the explicitly permitted earlier UPDATE ACK.
            // Repeat uses another fresh authorized original display, never a stale UI callback.
            display.acknowledgeDisplayed(display.displayOriginal(updateResult))
            check(requireSupplierPending(session1, setOf(stale.operationId)).single() == stalePending)
            display.acknowledgeDisplayed(display.displayOriginal(updateResult))
            check(requireSupplierPending(session1, setOf(stale.operationId)).single() == stalePending)
            evidence.event("NSUP01_UPDATE_ACK_AND_REPEAT_PRESERVED_OTHER_ORIGINAL")

            display.signOut()
            requireLoggedOut(first, session1, owner, evidence)
            display.close() // UI/runtime close is distinct from the actual native owner close.
            owner.closeOwned()
            check(!first.original.isCurrent() && !first.observer.hasNamedReadAccess)
            val beforeReopen = owner.census("NSUP01_FRESH_NATIVE_ORIGINALS_AFTER_RECOVERY_AND_ACK")
            beforeReopen.requireBusiness(originals.all, supplierId, initial)
            check(beforeReopen.epoch == initial.epoch)

            val second = owner.openClient() // Unmodified full production scanner admits this same original.
            check(!second.observer.hasNamedReadAccess && second.observer.signIns == 0 && !session1.isCurrent())
            display.open(second)
            val session2 = supplierSecret(password) { display.signIn(NativeSupplierFixture.login.value, it) }
            val issued2 = requireSupplierSession(second, session2, "SESSION_2", evidence)
            check(session2 !== session1 && issued2.sessionId != issued1.sessionId &&
                issued2.actorId == issued1.actorId && issued2.scope == issued1.scope && issued2.deviceId == issued1.deviceId)
            check(requireSupplierRead(second, session2, updated, true, "NSUP01_FRESH_REOPENED_DETAIL", evidence)?.storageRevision == r2)
            requireSupplierRead(second, session2, updated, false, "NSUP01_FRESH_REOPENED_PAGE", evidence)
            for ((original, expected) in resolved) {
                requireSameSupplierUiResult(expected, session2.workflows.lookup(original.operationId))
                evidence.event("NSUP01_FRESH_SESSION_ORIGINAL_LOOKUP", JSONObject()
                    .put("operationId", original.operationId.value).put("sessionId", issued2.sessionId.value))
            }
            check(requireSupplierPending(session2, setOf(stale.operationId)).single() == stalePending)
            owner.reobserveSetup()
            display.signOut()
            requireLoggedOut(second, session2, owner, evidence)
            display.close()
            owner.closeOwned()
            val final = owner.census("NSUP01_FINAL_NATIVE_CENSUS_AFTER_ACTUAL_CLOSE")
            final.requireBusiness(originals.all, supplierId, initial)
            beforeReopen.requireSameOriginalBusiness(final)
            check(!second.original.isCurrent() && !second.observer.hasNamedReadAccess)
            evidence.event("BOUNDED_DURABLE_SUPPLIER_COUNTS_OBSERVED", JSONObject()
                .put("suppliers", 1).put("creations", 1).put("updates", 1).put("resolvedOriginals", 3)
                .put("committedOriginals", 2).put("optimisticRefusedOriginals", 1)
                .put("additionalSupplierEffects", 0).put("unrelatedBusinessEffects", 0)
                .put("wholeCase", "NOT_QUALIFIED"))
            assertionsFinished = true
        } catch (caught: Throwable) { remember(caught) }
        finally {
            secrets.forEach { it.fill('\u0000') }
            try { display.close() } catch (caught: Throwable) { remember(caught) }
            try { owner.closeOwned(); cleanupConfirmed = true } catch (caught: Throwable) { remember(caught) }
            try {
                evidence.event("FINAL_CLEANUP", JSONObject().put("nativeCloseConfirmed", cleanupConfirmed)
                    .put("failureClass", failure?.javaClass?.name ?: JSONObject.NULL))
                evidence.status(if (assertionsFinished && cleanupConfirmed && failure == null)
                    "BOUNDED_NATIVE_ASSERTIONS_OBSERVED_WHOLE_CASE_NOT_QUALIFIED" else "HELD_NOT_VERIFIED")
            } catch (caught: Throwable) { remember(caught) }
        }
        failure?.let { throw it }
    }

    private suspend fun requireLoggedOut(client: NativeSupplierClient, session: BranchUiSession,
        owner: NativeSupplierOwner, evidence: NativeSupplierEvidence) {
        check(!session.isCurrent() && !client.observer.hasNamedReadAccess)
        check(session.current() is LocalSessionCheckResult.Unavailable)
        check(session.workflows.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
            purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.SUPPLIERS))) is LocalReadResult.Unavailable)
        val cleanup = client.gateway.cleanupStatus()
        evidence.event("ACTUAL_LOGOUT_CLEANUP_OBSERVED", JSONObject().put("available", cleanup.available)
            .put("pendingRevocations", cleanup.pendingRevocations).put("authenticationUnresolved", cleanup.authenticationUnresolved))
        check(cleanup.clear) { "Authoritative logout cleanup remains pending; do not reopen as if confirmed" }
        owner.requireMasterStillOperational()
        owner.reobserveSetup()
    }
}
