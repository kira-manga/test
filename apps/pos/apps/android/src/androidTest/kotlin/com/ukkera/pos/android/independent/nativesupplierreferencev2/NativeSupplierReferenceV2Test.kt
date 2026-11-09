package com.ukkera.pos.android.independent.nativesupplierreferencev2

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureOwner
import com.ukkera.pos.app.*
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/** Genuine native backend/shared-gateway binding of two bounded frozen selections.
 * No native execution has been established by adding this source. Whole frozen cases,
 * admitted physical RPC, installed UI and fault/authority variants are not qualified here. */
@RunWith(AndroidJUnit4::class)
class NativeSupplierReferenceV2Test {
    @Test fun protectedNativeSupplierReferenceV2SelectedLeg(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("syntheticNativeSupplierReferenceV2") == "true") {
            "Explicit syntheticNativeSupplierReferenceV2=true is required on an isolated synthetic installation"
        }
        val leg = SupplierReferenceV2Leg.select(checkNotNull(arguments.getString("supplierReferenceV2Case")) {
            "Select supplierReferenceV2Case=SREF2-002 or SREF2-003; one independently fresh leg per invocation"
        })
        val declaredRevision = checkNotNull(arguments.getString("testedRevision")) {
            "Record the actual tested source revision separately from package/installation proof"
        }
        check(Regex("[0-9a-f]{40}").matches(declaredRevision))
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing" && Build.VERSION.SDK_INT >= 24)
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")) {
            "Select keyProtection=OS_ISOLATED, SECURE_HARDWARE or STRONGBOX explicitly; no fallback is used"
        })
        val inputs = SupplierReferenceV2Inputs.load(instrumentation.context.assets, leg)
        val steps = supplierReferenceV2Steps(inputs, leg)
        check(steps.map { inputs.operation(it.symbol) }.distinct().size == steps.size)
        // Reserve durable observation before the first native setup acquisition. Existing state
        // or an uncertain earlier invocation is refused, never deleted/reset/repaired by the test.
        val evidence = SupplierReferenceV2Evidence(application, leg, declaredRevision)
        val owner = NativeLocalFixtureOwner<SupplierReferenceV2Client>(application, protection, evidence,
            SupplierReferenceV2NativeSetup.setup()) { original, journals, _ ->
            SupplierReferenceV2Client(original, journals, evidence)
        }
        val secrets = mutableListOf<CharArray>()
        fun secret(): CharArray = SupplierReferenceV2NativeSetup.secret().also { secrets += it }
        var failure: Throwable? = null
        var assertionsFinished = false
        var cleanupConfirmed = false
        fun remember(caught: Throwable) {
            val previous = failure
            if (previous == null) failure = caught else if (previous !== caught) previous.addSuppressed(caught)
        }
        suspend fun census(label: String): SupplierReferenceV2Census = owner.readClosedNativeHistory { custody ->
            SupplierReferenceV2Census.read(custody, owner.coordinates, label, evidence)
        }
        try {
            evidence.event("BOUNDED_SELECTION_STARTED", JSONObject().put("caseId", leg.caseId)
                .put("keyProtection", protection.name).put("androidApi", Build.VERSION.SDK_INT)
                .put("wholeCase", "NOT_QUALIFIED").put("rpc", "UNBOUND").put("installedUI", "UNBOUND")
                .put("historicV1PretransitionOriginals", "UNBOUND")
                .put("authorityVariantsAndFaults", "UNBOUND"))
            val installer = secret()
            val head = secret()
            val password = secret()
            owner.setup(installer, head, password)
            installer.fill('\u0000'); head.fill('\u0000')
            val initial = census("FRESH_NATIVE_INITIAL_CENSUS")
            initial.requireInitialNoBusiness()
            evidence.bind("INITIAL_AUTHORITY_EPOCH", initial.epoch)
            val originals = mutableListOf<SupplierReferenceV2Original>()
            var supplierId: DomainId? = null
            var previousSession: BranchUiSession? = null
            var lastClosedCut: SupplierReferenceV2Census? = null

            for ((index, step) in steps.withIndex()) {
                val client = owner.openClient() // Actual full production admission, not the census above.
                check(client.signIns == 0 && previousSession?.isCurrent() != true)
                val session = client.signIn(password) // Client retains delivered session before assertions.
                requireSupplierReferenceV2Current(client, session, "CURRENT_${index + 1}", evidence)
                requireSupplierReferenceV2Pending(session, emptySet())
                for (original in originals) {
                    requireSameSupplierReferenceV2Result(original.uiResult, session.workflows.lookup(original.request.operationId))
                }
                requireSupplierReferenceV2Pending(session, emptySet())

                val form = supplierReferenceV2Form(session, supplierId)
                val id = supplierId ?: form.supplierId.also {
                    evidence.bind("S_${leg.supplierRole}", JSONObject().put("actualGeneratedId", it.value)
                        .put("frozenRoleId", inputs.fixtureSupplierId(leg))
                        .put("source", "genuine protected creation form owns ID generation; bound before stage"))
                    supplierId = it
                }
                check(form.supplierId == id)
                val previous = originals.lastOrNull()
                if (previous == null) check(form.expectedStorageRevision == null)
                else {
                    form.requireState(previous.step.after)
                    check(form.expectedStorageRevision == previous.afterStorageRevision &&
                        form.reference?.revision == checkNotNull(previous.uiResult.result).reference.revision)
                }
                val operation = inputs.operation(step.symbol)
                val request = form.expectedRequest(operation, step)
                val intent = form.intent(operation, step)
                val actualOwner = client.intentOwner()
                evidence.bind("INPUT_${step.symbol}", JSONObject().put("operationId", operation.value)
                    .put("supplierId", id.value).put("formVersion", step.version)
                    .put("command", request.command.name).put("commandVersion", request.command.version)
                    .put("expectedStorageRevision", form.expectedStorageRevision?.value ?: JSONObject.NULL)
                    .put("originalActorId", actualOwner.actorId.value).put("originalDeviceId", actualOwner.deviceId.value)
                    .put("canonicalExpectedRequest", supplierReferenceV2Base64(request.payload.copyBytes()))
                    .put("origin", "public gateway input over a real protected V2 form; V1 omission explicitly selected"))
                val staged = session.workflows.stage(intent) as? LocalStageResult.Staged
                    ?: error("The original supplier input did not reach genuine durable native stage")
                check(staged.operation.operationId == operation && staged.operation.action == form.descriptor.action &&
                    staged.operation.phase == LocalPendingPhase.STAGED && staged.operation.submitAllowed && staged.operation.recordedDecision == null)
                check(requireSupplierReferenceV2Pending(session, setOf(operation)).single() == staged.operation)
                evidence.event("GENUINE_NATIVE_STAGE_CONFIRMED", JSONObject().put("operationId", operation.value)
                    .put("stageContract", "DURABLE_INPUT_ONLY")
                    .put("stageOnlyNativeNoEffects", "NOT_SEPARATELY_OBSERVED"))

                val result = requireSupplierReferenceV2Applied(operation, step, id, session.workflows.submit(operation))
                val current = supplierReferenceV2Form(session, id)
                current.requireState(step.after)
                check(current.reference == checkNotNull(result.result).reference)
                val afterStorage = checkNotNull(current.expectedStorageRevision)
                check(afterStorage != form.expectedStorageRevision)
                val original = SupplierReferenceV2Original(step, request, actualOwner, afterStorage, result)
                originals += original
                val pending = requireSupplierReferenceV2Pending(session, setOf(operation)).single()
                check(pending.phase == LocalPendingPhase.RECORDED_UNACKNOWLEDGED && !pending.submitAllowed &&
                    pending.recordedDecision == LocalRecordedDecision.APPLIED)
                // The harness consumes the actual protected terminal DATA before this API ACK.
                // This is neither a fake display callback nor an installed/Compose screen claim.
                requireSameSupplierReferenceV2Result(result, session.workflows.lookup(operation))
                val ack = session.workflows.acknowledgeRecorded(operation) as? LocalAcknowledgementResult.Acknowledged
                    ?: error("Actual recorded-original acknowledgement is unresolved")
                check(ack.operationId == operation)
                requireSupplierReferenceV2Pending(session, emptySet())
                evidence.event("PROTECTED_CURRENT_AND_ORIGINAL_DATA_OBSERVED_THEN_ACK", JSONObject()
                    .put("operationId", operation.value).put("actualStorageRevision", afterStorage.value)
                    .put("actualDomainRevision", current.reference?.revision?.value)
                    .put("referenceVector", step.after.reference?.id ?: JSONObject.NULL)
                    .put("installedDisplayQualified", false))

                client.logout(session)
                requireLoggedOut(session)
                owner.requireMasterStillOperational()
                owner.reobserveSetup()
                owner.closeOwned()
                check(!client.original.isCurrent() && !session.isCurrent())
                previousSession = session
                val cut = census("NATIVE_ORIGINAL_CUT_AFTER_${step.symbol}")
                cut.requireSequence(originals, id, initial, evidence)
                lastClosedCut = cut
                // The next mutation cannot begin until all previous actual original request,
                // result, supplier source and documentary bytes have been separately retained.
                check(originals.all { it.source != null })
            }

            val id = checkNotNull(supplierId)
            val last = originals.last()
            val reopened = owner.openClient() // Same native installation; new real lifetime, no automatic login.
            check(reopened.signIns == 0 && previousSession?.isCurrent() != true)
            val recoveredSession = reopened.signIn(password)
            requireSupplierReferenceV2Current(reopened, recoveredSession, "CURRENT_FINAL_REAUTHENTICATION", evidence)
            val recoveredOwner = reopened.intentOwner()
            check(originals.all { it.owner == recoveredOwner })
            requireSupplierReferenceV2Pending(recoveredSession, emptySet())
            val finalCurrent = supplierReferenceV2Form(recoveredSession, id)
            finalCurrent.requireState(last.step.after)
            check(finalCurrent.expectedStorageRevision == last.afterStorageRevision &&
                finalCurrent.reference == checkNotNull(last.uiResult.result).reference)
            for (original in originals) {
                requireSameSupplierReferenceV2Result(original.uiResult,
                    recoveredSession.workflows.lookup(original.request.operationId))
                evidence.event("FRESH_AUTHORIZED_ORIGINAL_LOOKUP_AFTER_CLEAR", JSONObject()
                    .put("operationId", original.request.operationId.value).put("originalVersion", original.step.version)
                    .put("originalReferenceVector", if (original.step.version == 2) original.step.after.reference?.id ?: JSONObject.NULL
                        else "V1_NO_REFERENCE_RESULT_MEMBER"))
            }
            requireSupplierReferenceV2Pending(recoveredSession, emptySet())
            val afterLookup = supplierReferenceV2Form(recoveredSession, id)
            afterLookup.requireState(last.step.after)
            check(afterLookup.expectedStorageRevision == last.afterStorageRevision && afterLookup.reference == finalCurrent.reference)
            reopened.logout(recoveredSession)
            requireLoggedOut(recoveredSession)
            owner.requireMasterStillOperational()
            owner.reobserveSetup()
            owner.closeOwned()
            check(!reopened.original.isCurrent() && !recoveredSession.isCurrent())
            val final = census("FINAL_NATIVE_CENSUS_AFTER_PROTECTED_ORIGINAL_RECOVERY")
            final.requireSequence(originals, id, initial, evidence)
            checkNotNull(lastClosedCut).requireSameOriginalBusiness(final)
            evidence.event("BOUNDED_NATIVE_ASSERTIONS_FINISHED", JSONObject().put("caseId", leg.caseId)
                .put("suppliers", 1).put("acceptedSupplierMutations", steps.size).put("originals", originals.size)
                .put("unexpectedSupplierOrStockPayableCashBusinessState", 0).put("wholeCase", "NOT_QUALIFIED"))
            assertionsFinished = true
        } catch (caught: Throwable) { remember(caught) }
        finally {
            secrets.forEach { it.fill('\u0000') }
            try { owner.closeOwned(); cleanupConfirmed = true } catch (caught: Throwable) { remember(caught) }
            try {
                evidence.event("FINAL_CLEANUP", JSONObject().put("actualNativeCloseConfirmed", cleanupConfirmed)
                    .put("failureClass", failure?.javaClass?.name ?: JSONObject.NULL))
                evidence.status(if (assertionsFinished && cleanupConfirmed && failure == null)
                    "BOUNDED_NATIVE_ASSERTIONS_OBSERVED_WHOLE_CASE_NOT_QUALIFIED" else "HELD_NOT_VERIFIED")
            } catch (caught: Throwable) { remember(caught) }
        }
        failure?.let { throw it }
    }

    private suspend fun requireLoggedOut(session: BranchUiSession) {
        check(!session.isCurrent() && session.current() is LocalSessionCheckResult.Unavailable)
        check(session.workflows.read(LocalWorkflowQuery(LocalWorkflow.PURCHASING,
            purchasing = LocalPurchasingUiQuery(LocalPurchasingSection.SUPPLIERS))) is LocalReadResult.Unavailable)
    }
}
