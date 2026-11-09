package com.ukkera.pos.android.independent.nativefoodpaymentalreadyappliedv1

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureOwner
import com.ukkera.pos.app.gateway.AndroidOriginalRetailPhysicalConnection
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.UtcInstant
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Genuine FAA-F02 Android same-device native assertion slice, NOT whole FAA acceptance.
 * Physical Start/capture/import use real native owners. R is separately pre-pinned and
 * actually staged while O is pending; earlier A accounts once; R then records ordinary
 * AlreadyApplied. This race setup is binding DATA, not independent FAA-Q02 resolution.
 * Real orderly close/reopen and fresh authentication permit only exact existing financial
 * lookup. Q01–Q04 qualification, remote transport/faults and other variants stay unbound. */
@RunWith(AndroidJUnit4::class)
class NativeFoodAlreadyAppliedTest {
    @Test fun faa002NativeOrdinaryAlreadyAppliedCashPaidUnsentAcrossOrderlyReopen(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("syntheticNativeFoodAlreadyAppliedFaaF02") == "true") {
            "Explicit syntheticNativeFoodAlreadyAppliedFaaF02=true requires a fresh isolated synthetic localTesting installation"
        }
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")) {
            "Select keyProtection=OS_ISOLATED, SECURE_HARDWARE or STRONGBOX explicitly; no fallback is used"
        })
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing")
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 && Build.VERSION.SDK_INT >= 24)
        val digests = JSONObject()
        var frozen: JSONObject? = null
        frozenFoodAlreadyAppliedAssetDigests.forEach { (name, expected) ->
            val bytes = instrumentation.context.assets.open("food-payment-already-applied-v1/$name").use { it.readBytes() }
            try {
                val observed = sha256(bytes).value
                check(observed == expected) { "The frozen FAA asset differs; do not replace an expectation" }
                digests.put(name, observed)
                if (name == "fixtures.json") frozen = JSONObject(bytes.decodeToString(throwOnInvalidSequence = true))
            } finally { bytes.fill(0) }
        }
        val plan = NativeFoodAlreadyAppliedPlan(checkNotNull(frozen), protection)
        val evidence = NativeFoodAlreadyAppliedEvidence(application) // Durable no-overwrite comparison DATA precede native setup.
        evidence.bind("EXACT_UNCHANGED_FROZEN_ASSET_DIGESTS", digests)
        evidence.bind("FROZEN_ILLUSTRATIVE_ID_LABELS_NOT_FORCED_NATIVE_ISSUANCE", checkNotNull(frozen).getJSONObject("ids"))
        val owner = NativeLocalFixtureOwner(application, protection, evidence, plan.setup,
            beforeClientJournals = { original, action ->
                checkNotNull(AndroidOriginalRetailPhysicalConnection.acquire(application, original, action)) {
                    "The actual Master must acquire its exact original physical owner before native-journal enrollment/reopen"
                }
            }) { original, journals, publications -> NativeFoodAlreadyAppliedClient(original, journals, publications, plan, evidence) }
        val secrets = mutableListOf<CharArray>()
        fun secret(): CharArray {
            val generated = foodAlreadyAppliedSecret()
            return try { secrets.add(generated); generated }
            catch (failure: Throwable) { generated.fill('\u0000'); throw failure }
        }
        var failure: Throwable? = null
        var assertionsFinished = false
        var cleanupConfirmed = false
        fun remember(caught: Throwable) {
            val prior = failure
            if (prior == null) failure = caught else if (prior !== caught) prior.addSuppressed(caught)
        }
        try {
            val installer = secret()
            val head = secret()
            val employeeA = secret()
            val employeeB = secret()
            evidence.event("BOUNDED_FAA_F02_NATIVE_ASSERTION_SCOPE", JSONObject().put("activity", plan.activity.name)
                .put("keyProtection", protection.name).put("sdkInt", Build.VERSION.SDK_INT).put("osRelease", Build.VERSION.RELEASE)
                .put("expectationVersion", "1.0.0").put("inputOrigin", "GENUINE_NATIVE_BACKEND_AND_PUBLIC_EMPLOYEE_FINANCIAL_WORKFLOW_NOT_INSTALLED_UI")
                .put("setupQualification", "FAA-Q02_PRESTAGED_SECOND_APPLY_BINDING_DATA_RACE_SETUP_NOT_INDEPENDENT_ORACLE")
                .put("closeKind", "CONFIRMED_ORDERLY_NATIVE_OWNER_CLOSE_NOT_PROCESS_CRASH_OR_HUMAN_OWNER_UI")
                .put("businessDateMapping", "SUPPLIED_AFRICA_CAIRO_04_00_NOT_RETAINED_BRANCH_OR_TAX_DATE_QUALIFICATION")
                .put("wholeCases", "NOT_QUALIFIED").put("unbound", JSONArray(NativeFoodAlreadyAppliedEvidence.unbound)))
            owner.setup(installer, head, employeeA)
            installer.fill('\u0000')
            val initialNative = owner.readClosedNativeHistory { custody ->
                NativeFoodAlreadyAppliedCensus.read(custody, owner.coordinates, plan, emptyList(), null, null, null,
                    "INITIAL_ACTUAL_CLOSED_NATIVE_SETUP_WITHOUT_BUSINESS_OR_SIGN_IN", evidence)
            }
            val initial = owner.openClient()
            check(initial.signIns == 0)
            initial.enrollB(employeeB, head)
            initial.activateFixturePolicyA(head) // Real seven next-revision activations, before either sign-in.
            val firstA = initial.signInFresh(FoodAlreadyAppliedActor.A, employeeA)
            val firstB = initial.signInFresh(FoodAlreadyAppliedActor.B, employeeB)
            check(initial.signIns == 2 && firstA.actorId != firstB.actorId && firstA.sessionId != firstB.sessionId)
            evidence.bind("OBSERVED_ACTUAL_CURRENT_ID_MAPPINGS_DATA", JSONObject().put("organizationId", firstA.scope.organizationId.value)
                .put("companyId", firstA.scope.companyId.value).put("branchId", firstA.scope.branchId.value).put("actorA", firstA.actorId.value)
                .put("actorB", firstB.actorId.value).put("deviceId", firstA.deviceId.value).put("authSessionA", firstA.sessionId.value)
                .put("authSessionB", firstB.sessionId.value).put("nativeIssuedUuidVariantQualification", "UNBOUND"))
            val seed = NativeFoodAlreadyAppliedPreparation.prepare(initial, plan, evidence, head)
            head.fill('\u0000')
            requireFoodAlreadyAppliedSameBusinessDay(plan, seed, listOf(foodAlreadyAppliedNow()))
            val reserved = NativeFoodAlreadyAppliedProjections.read(initial, plan, seed, paid = false,
                label = "FROZEN_F02_BOTH_UNSENT_RESERVATIONS_BEFORE_NATIVE_START", evidence = evidence)
            val food = LocalFoodCheckKitchenCodecV3.result(reserved.payload("ORIGINAL_FOOD_COMPLETE_TWO_PORTIONS"))
            val collection = (LocalFoodFinancialReadCodecV1.result(reserved.payload("ORIGINAL_COLLECTION"))
                as LocalFoodFinancialReadResultV1.Collection).value
            val cash = checkNotNull((LocalCashReadCodec.decode(reserved.payload("OWN_CASH/A")) as LocalCashReadResultV1.OwnCash).session)
            val start = LocalFoodPaymentCodec.command(plan.startOperationId, LocalFoodPaymentRequest.StartCash(
                collectionId = plan.collectionId, expectedOrderStorageRevision = checkNotNull(food.food.detail).summary.revision,
                expectedCollectionRevision = collection.collectionDomainRevision, sessionId = plan.cashSessionA,
                expectedSessionRevision = cash.revision, attemptId = plan.attemptId, amount = plan.payable,
                recordingOperationId = plan.recordingOperationId, resolutionOperationId = plan.resolutionOperationId,
                masterAllocationId = plan.masterAllocationId, clientAllocationId = plan.clientAllocationId))
            val requirement = initial.startOriginal(start)
            val outcome = initial.captureOnce(requirement) // Exactly one genuine native capture, never execute RecordOriginal.
            val pending = initial.awaitDurableOriginal(outcome) // Actual independent native worker/import plus RawRetained lookup.
            requireFoodAlreadyAppliedSameBusinessDay(plan, seed, listOf(outcome.evidence.occurredAt, foodAlreadyAppliedNow()))
            initial.stageSeparateOrdinary(pending) // Pending-only gate preserved; R is staged binding DATA before A, not a recovery mutation.
            val earlier = initial.applyEarlierOnce(pending)
            val paidBaseline = NativeFoodAlreadyAppliedProjections.read(initial, plan, seed, paid = true,
                label = "EARLIER_GENUINE_A_ACCOUNTS_CASH_ONCE_FROZEN_F02_BASELINE", evidence = evidence, outcome = outcome)
            val rejected = initial.submitSeparateOrdinary() // One actual dispatch, genuine ordinary AlreadyApplied, remembrance and ACK.
            check(earlier.sequence.value < rejected.sequence.value)
            requireOrdinaryAlreadyApplied(rejected, plan)
            val savedBeforeW = evidence.originals()
            val r = savedBeforeW.single { it.symbol == NativeFoodAlreadyAppliedStaging.REJECTION }
            check(r.nativeStaged && r.request.operationId == plan.rejectionOperationId && !r.result.committed)
            r.result.requireSame(rejected)
            val afterR = NativeFoodAlreadyAppliedProjections.read(initial, plan, seed, paid = true,
                label = "ORDINARY_R_AND_ACTUAL_ACK_ADD_ZERO_FROZEN_BUSINESS_EFFECTS", evidence = evidence, outcome = outcome)
            paidBaseline.requireSameNoEffects(afterR)
            repeat(2) { initial.lookupOrdinary(r) } // Exact current-authorized financial lookup, not replay of earlier Applied A.
            val afterWarmLookups = NativeFoodAlreadyAppliedProjections.read(initial, plan, seed, paid = true,
                label = "REPEATED_EXACT_ORDINARY_R_LOOKUPS_ADD_ZERO_BUSINESS_EFFECTS", evidence = evidence, outcome = outcome)
            paidBaseline.requireSameNoEffects(afterWarmLookups)
            afterR.requireSameNoEffects(afterWarmLookups)
            requireSameFoodAlreadyAppliedSavedOriginals(savedBeforeW, evidence.originals())
            val later = NativeFoodAlreadyAppliedPreparation.laterWriter(initial, plan)
            check(rejected.sequence.value < later.sequence.value) { "Genuine ordinary R must precede unrelated successful FOOD W" }
            val preClose = NativeFoodAlreadyAppliedProjections.read(initial, plan, seed, paid = true,
                label = "LATER_UNRELATED_EMPTY_FOOD_W_PRESERVES_ORIGINAL_F02_MONEY_STOCK_AND_NATIVE_O", evidence = evidence, outcome = outcome)
            paidBaseline.requireSameNoEffects(preClose) // Deliberate W's unrelated empty order/work index is not a second original money effect.
            val beforeCloseOriginals = evidence.originals()
            check(beforeCloseOriginals.size == savedBeforeW.size + 1)
            requireSameFoodAlreadyAppliedSavedOriginals(savedBeforeW, beforeCloseOriginals.filter { it.symbol != "W_LATER_EMPTY_FOOD_DRAFT" })
            requireFoodAlreadyAppliedSameBusinessDay(plan, seed, beforeCloseOriginals.map { it.result.recordedAt } + outcome.evidence.occurredAt + foodAlreadyAppliedNow())
            evidence.event("ORDINARY_R_PINNED_REMEMBERED_ACKNOWLEDGED_BEFORE_LATER_W_AND_ACTUAL_CLOSE", JSONObject()
                .put("originalRecordingOperationId", plan.recordingOperationId.value).put("earlierApplicationOperationId", plan.earlierApplyOperationId.value)
                .put("ordinaryROperationId", r.request.operationId.value).put("ordinaryRSequence", r.result.sequence.value).put("ordinaryRCommitted", false)
                .put("laterWOperationId", later.operationId.value).put("laterWSequence", later.sequence.value)
                .put("nativeEmployeeJournalStageSubmitAndAck", true).put("replacementOrRecoveryOperation", false)
                .put("setupQualification", "FAA-Q02_BINDING_DATA_NOT_INDEPENDENT_RESOLUTION"))
            evidence.bind("FIRST_ORDERLY_CLOSE_REQUESTED_WITNESS_AT", foodAlreadyAppliedNow().canonicalString())
            owner.closeOwned()
            check(!initial.original.isCurrent() && !initial.original.peer.isCurrent())
            evidence.bind("FIRST_ORDERLY_CLOSE_CONFIRMED_WITNESS_AT", foodAlreadyAppliedNow().canonicalString())
            val saved = evidence.originals() // Real durable DATA reread after actual close, no stage/request reconstruction.
            requireSameFoodAlreadyAppliedSavedOriginals(beforeCloseOriginals, saved)
            val firstClosed = owner.readClosedNativeHistory { custody ->
                NativeFoodAlreadyAppliedCensus.read(custody, owner.coordinates, plan, saved, seed, preClose, initialNative,
                    "FIRST_ACTUAL_CLOSED_F02_ALREADY_APPLIED_ORIGINAL_AND_BUSINESS_DATA_CENSUS", evidence)
            }
            requireFoodAlreadyAppliedSameBusinessDay(plan, seed, listOf(foodAlreadyAppliedNow()))
            evidence.bind("ORDERLY_REOPEN_REQUESTED_WITNESS_AT", foodAlreadyAppliedNow().canonicalString())
            val reopened = owner.openClient() // Same native material, existing-only journals; actual full production history admission.
            check(reopened.original !== initial.original && reopened.original.serviceIncarnationId != initial.original.serviceIncarnationId &&
                !initial.original.isCurrent() && reopened.signIns == 0)
            evidence.bind("ORDERLY_REOPEN_CONFIRMED_WITNESS_AT", foodAlreadyAppliedNow().canonicalString())
            val freshA = reopened.signInFresh(FoodAlreadyAppliedActor.A, employeeA)
            val freshB = reopened.signInFresh(FoodAlreadyAppliedActor.B, employeeB)
            check(reopened.signIns == 2)
            requireFoodAlreadyAppliedFreshSession(firstA, freshA)
            requireFoodAlreadyAppliedFreshSession(firstB, freshB)
            check(freshA.sessionId != freshB.sessionId)
            // No setup/publication mutation, Start, capture, import, Stage, Submit, Apply or W after reopen.
            val beforeColdLookup = NativeFoodAlreadyAppliedProjections.read(reopened, plan, seed, paid = true,
                label = "FRESH_AUTHENTICATED_REOPEN_BEFORE_EXACT_EXISTING_FINANCIAL_R_LOOKUP", evidence = evidence, outcome = outcome)
            preClose.requireSameNoEffects(beforeColdLookup)
            val coldR = saved.single { it.symbol == NativeFoodAlreadyAppliedStaging.REJECTION }
            repeat(2) { reopened.lookupOrdinary(coldR) }
            val afterColdLookup = NativeFoodAlreadyAppliedProjections.read(reopened, plan, seed, paid = true,
                label = "FRESH_REOPEN_REPEATED_EXACT_R_LOOKUP_PRESERVES_ALL_FROZEN_ORIGINAL_EFFECTS", evidence = evidence, outcome = outcome)
            preClose.requireSameNoEffects(afterColdLookup)
            beforeColdLookup.requireSameNoEffects(afterColdLookup)
            requireSameFoodAlreadyAppliedSavedOriginals(saved, evidence.originals())
            requireFoodAlreadyAppliedSameBusinessDay(plan, seed, listOf(foodAlreadyAppliedNow()))
            evidence.bind("SECOND_ORDERLY_CLOSE_REQUESTED_WITNESS_AT", foodAlreadyAppliedNow().canonicalString())
            owner.closeOwned()
            check(!reopened.original.isCurrent() && !reopened.original.peer.isCurrent())
            evidence.bind("SECOND_ORDERLY_CLOSE_CONFIRMED_WITNESS_AT", foodAlreadyAppliedNow().canonicalString())
            val secondClosed = owner.readClosedNativeHistory { custody ->
                NativeFoodAlreadyAppliedCensus.read(custody, owner.coordinates, plan, saved, seed, preClose, initialNative,
                    "SECOND_ACTUAL_CLOSED_F02_AFTER_FRESH_EXACT_R_LOOKUPS_DATA_CENSUS", evidence)
            }
            firstClosed.requireSameBusiness(secondClosed)
            requireSameFoodAlreadyAppliedSavedOriginals(saved, evidence.originals())
            evidence.event("BOUNDED_NATIVE_F02_ORDINARY_ALREADY_APPLIED_ASSERTIONS_OBSERVED", JSONObject()
                .put("genuineOrderlyNativeCloseAndReopen", true).put("freshCurrentEmployeeSessions", 2).put("exactExistingFinancialLookupOnly", true)
                .put("nativePhysicalCaptureCalls", 1).put("nativeOriginals", 1).put("originalCashAccountings", 1).put("netCashMovements", 1)
                .put("receivedMinor", plan.received.minor).put("changeMinor", plan.change.minor).put("netCashMinor", plan.payable.minor)
                .put("drawerAExpectedMinor", plan.expectedA.minor).put("drawerBExpectedMinor", plan.expectedB.minor)
                .put("coverageMinor", plan.payable.minor).put("remainingMinor", 0).put("breadOnHand", 10).put("meatOnHandG", 1500)
                .put("breadReserved", 2).put("meatReservedG", 300).put("breadAvailable", 8).put("meatAvailableG", 1200)
                .put("ingredientIssues", 0).put("kitchenTasks", 0).put("saleFinalizations", 0).put("refundMinor", 0)
                .put("originalNativeMoneyStockActorCustodyBodyOperationAndAllocationsPreserved", true)
                .put("ordinaryRBeforeUnrelatedSuccessfulW", true).put("minimumRightsQualification", "FAA-Q03_UNBOUND")
                .put("setupQualification", "FAA-Q02_BINDING_DATA_NOT_INDEPENDENT_RESOLUTION")
                .put("wholeCases", "NOT_QUALIFIED").put("unbound", JSONArray(NativeFoodAlreadyAppliedEvidence.unbound)))
            assertionsFinished = true
        } catch (caught: Throwable) { remember(caught) }
        finally {
            secrets.forEach { it.fill('\u0000') }
            try { owner.closeOwned(); cleanupConfirmed = true } catch (caught: Throwable) { remember(caught) }
            try {
                evidence.event("FINAL_CLEANUP", JSONObject().put("actualNativeCloseConfirmed", cleanupConfirmed)
                    .put("runtimePasswordBuffersWiped", true).put("failureClass", failure?.javaClass?.name ?: JSONObject.NULL))
                evidence.status(if (assertionsFinished && cleanupConfirmed && failure == null)
                    "BOUNDED_NATIVE_ASSERTIONS_OBSERVED_WHOLE_CASES_NOT_QUALIFIED" else "HELD_NOT_VERIFIED")
            } catch (caught: Throwable) { remember(caught) }
        }
        failure?.let { throw it }
    }
}

private val frozenFoodAlreadyAppliedAssetDigests = linkedMapOf(
    "README.md" to "fdcc1addb1d5ddba59585ce2ce6fb1e7455d430ba5549441fc2a3024b9e9b316",
    "cases.md" to "d87995f4c91ac841c704d7453d9e958220abd01d5b4aa92e08046d83904d1626",
    "fixtures.json" to "91ef569172f09e39906996c893bdc19a7e4eb178e07826e4443fbc87b92c9785",
    "source-manifest.json" to "487efd3f0f0347f9d70abcde5500442936b9e4936947ccfdc9ed05186c1a096b",
)
private fun requireFoodAlreadyAppliedSameBusinessDay(plan: NativeFoodAlreadyAppliedPlan, seed: FoodAlreadyAppliedSeed,
    times: List<UtcInstant>) {
    check(seed.cashBusinessDateA == seed.cashBusinessDateB && times.isNotEmpty() &&
        times.all { plan.calendar.businessDateAt(it) == seed.cashBusinessDateA }) {
        "The selected ordinary same-business-day synthetic precondition crossed Cairo 04:00; hold, never invent a calendar policy"
    }
}
private fun requireFoodAlreadyAppliedFreshSession(before: LocalSessionView, after: LocalSessionView) {
    check(before.sessionId != after.sessionId && before.actorId == after.actorId && before.scope == after.scope &&
        before.deviceId == after.deviceId && before.login == after.login && before.policy.revision == after.policy.revision)
}
private fun requireSameFoodAlreadyAppliedSavedOriginals(before: List<FoodAlreadyAppliedSavedOriginal>, after: List<FoodAlreadyAppliedSavedOriginal>) {
    check(before.size == after.size)
    before.zip(after).forEach { (left, right) ->
        check(left.symbol == right.symbol && sameFoodAlreadyAppliedRequest(left.request, right.request) && left.identity == right.identity &&
            left.scope == right.scope && left.deviceId == right.deviceId && left.authSessionId == right.authSessionId &&
            left.sessionGeneration == right.sessionGeneration && left.policyRevision == right.policyRevision &&
            left.installationId == right.installationId && left.assignmentId == right.assignmentId && left.storageInstanceId == right.storageInstanceId &&
            left.result == right.result && left.nativeStaged == right.nativeStaged) {
            "Whole durable original request/result/actor/device/session/time/stage DATA changed across a close or exact lookup"
        }
    }
}
/** Runtime-generated secrets never become a String, fixture, evidence field or log. */
private fun foodAlreadyAppliedSecret(): CharArray {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@%_-".toCharArray()
    val generated = CharArray(32)
    var delivered = false
    return try {
        val random = SecureRandom()
        for (index in generated.indices) generated[index] = alphabet[random.nextInt(alphabet.size)]
        delivered = true
        generated
    } finally {
        alphabet.fill('\u0000')
        if (!delivered) generated.fill('\u0000')
    }
}
