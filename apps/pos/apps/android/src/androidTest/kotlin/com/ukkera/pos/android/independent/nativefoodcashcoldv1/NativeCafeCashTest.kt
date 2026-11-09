package com.ukkera.pos.android.independent.nativefoodcashcoldv1

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureOwner
import com.ukkera.pos.app.gateway.AndroidOriginalRetailPhysicalConnection
import com.ukkera.pos.sales.store.LocalFoodPaymentCodec
import com.ukkera.pos.sales.store.LocalFoodPaymentRequest
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.UtcInstant
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Selected genuine same-device native backend leg of frozen FCC-C-01. Four SQL originals
 * use actual shared native-journal staging; the separate requests/results file is comparison
 * DATA, never native authority. Full Branch authentication, physical I/A staging, installed
 * UI, admitted RPC, partition, process crash, physical human events and positive kitchen
 * Send remain unbound. A bounded success still leaves the whole case NOT_QUALIFIED. */
@RunWith(AndroidJUnit4::class)
class NativeCafeCashTest {
    @Test fun fccC01NativeCashPaidUnsentAcrossOrderlyReopen(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("syntheticNativeFoodCashFccC01") == "true") {
            "Explicit syntheticNativeFoodCashFccC01=true requires a fresh isolated synthetic localTesting installation"
        }
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")) {
            "Select keyProtection=OS_ISOLATED, SECURE_HARDWARE or STRONGBOX explicitly; no fallback is used"
        })
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing")
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 && Build.VERSION.SDK_INT >= 24)
        val frozen = instrumentation.context.assets.open("food-cash-cold-recovery-v1/fixtures.json").use {
            JSONObject(it.bufferedReader().readText())
        }
        val plan = NativeCafeCashPlan(frozen, protection)
        val evidence = NativeCafeCashEvidence(application) // Durable no-overwrite DATA precede all native setup.
        val assetDigests = JSONObject()
        listOf("README.md", "cases.md", "fixtures.json", "source-manifest.json", "TIMESTAMP-BINDING-v1.0.1.md").forEach { name ->
            val bytes = instrumentation.context.assets.open("food-cash-cold-recovery-v1/$name").use { it.readBytes() }
            try { assetDigests.put(name, sha256(bytes).value) } finally { bytes.fill(0) }
        }
        evidence.bind("UNCHANGED_FROZEN_ASSET_DIGESTS", assetDigests)
        val owner = NativeLocalFixtureOwner(application, protection, evidence, plan.setup,
            beforeClientJournals = { original, action ->
                checkNotNull(AndroidOriginalRetailPhysicalConnection.acquire(application, original, action)) {
                    "The actual Master could not acquire its original physical owner before protected-journal enrollment/reopen"
                }
            }) { original, journals, publications -> NativeCafeCashClient(original, journals, publications, plan, evidence) }
        val secrets = mutableListOf<CharArray>()
        fun secret(): CharArray {
            val generated = cafeCashSecret()
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
            val employee = secret()
            evidence.event("BOUNDED_FCC_C01_NATIVE_SCOPE", JSONObject().put("activity", "CAFE").put("keyProtection", protection.name)
                .put("sdkInt", Build.VERSION.SDK_INT).put("osRelease", Build.VERSION.RELEASE)
                .put("expectations", "base cases v1.0.0 + timestamp binding v1.0.1")
                .put("inputOrigin", "GENUINE_NATIVE_BACKEND_WITH_FOUR_SHARED_SQL_NATIVE_JOURNAL_STAGES_NOT_INSTALLED_UI")
                .put("closeKind", "CONFIRMED_ORDERLY_NATIVE_OWNER_CLOSE_NOT_HUMAN_OWNER_UI_OR_CRASH")
                .put("dateMapping", "SUPPLIED_AFRICA_CAIRO_04_00_NOT_RETAINED_BRANCH_OR_TAX_DATE_PROOF")
                .put("wholeCase", "NOT_QUALIFIED").put("unbound", JSONArray(NativeCafeCashEvidence.unbound)))
            owner.setup(installer, head, employee)
            installer.fill('\u0000')
            val initialNative = owner.readClosedNativeHistory { custody ->
                NativeCafeCashCensus.read(custody, owner.coordinates, plan, emptyList(), null, null, null,
                    "INITIAL_CLOSED_SETUP_WITHOUT_BUSINESS_OR_EMPLOYEE_SESSION", evidence)
            }
            val initial = owner.openClient()
            check(initial.signIns == 0)
            val priorSession = initial.signInFresh(employee)
            check(initial.signIns == 1)
            val seed = NativeCafeCashPreparation.prepare(initial, plan, evidence, head)
            head.fill('\u0000')
            requireCafeSameBusinessDay(plan, seed, listOf(cafeNow()))
            val quoted = NativeCafeCashProjections.read(initial, plan, seed, CafeCutPhase.QUOTED_UNPAID,
                "QUOTED_UNPAID_BEFORE_COLLECTION", evidence)
            NativeCafeCashPreparation.begin(initial, plan, quoted)
            val reserved = NativeCafeCashProjections.read(initial, plan, seed, CafeCutPhase.RESERVED_BEFORE_START,
                "BEGIN_RESERVED_ALL_UNSENT_BEFORE_I", evidence)
            requireCafeSameBusinessDay(plan, seed, listOf(cafeNow()))
            val collection = checkNotNull(reserved.collection)
            val session = checkNotNull(reserved.cash.session)
            val start = LocalFoodPaymentCodec.command(plan.startOperationId, LocalFoodPaymentRequest.StartCash(
                collectionId = plan.collectionId, expectedOrderStorageRevision = checkNotNull(reserved.food.food.detail).summary.revision,
                expectedCollectionRevision = collection.collectionDomainRevision, sessionId = plan.cashSessionId,
                expectedSessionRevision = session.revision, attemptId = plan.attemptId, amount = plan.payable,
                recordingOperationId = plan.recordingOperationId, resolutionOperationId = plan.resolutionOperationId,
                masterAllocationId = plan.masterAllocationId, clientAllocationId = plan.clientAllocationId))
            val requirement = initial.startOriginal(start)
            NativeCafeCashProjections.read(initial, plan, seed, CafeCutPhase.STARTED_UNAPPLIED,
                "DURABLE_I_ORIGINAL_WITHOUT_O_OR_A", evidence, requirement)
            val outcome = initial.captureOnce(requirement) // One actual native capture, never execute RecordOriginal.
            val durable = initial.awaitDurableOriginal(outcome)
            NativeCafeCashProjections.read(initial, plan, seed, CafeCutPhase.DURABLE_UNAPPLIED,
                "DURABLE_ORIGINAL_O_AWAITING_FINANCIAL_APPLICATION", evidence, requirement, outcome)
            requireCafeSameBusinessDay(plan, seed, listOf(outcome.evidence.occurredAt, cafeNow()))
            initial.applyOnce(durable)
            val finalized = NativeCafeCashPreparation.finalize(initial, plan)
            evidence.bind("FINALIZE_ORIGINAL_NATIVE_RECORDED_AT", finalized.recordedAt.canonicalString())
            val beforeCloseOriginals = evidence.originals()
            initial.requireFourSqlOriginals(beforeCloseOriginals, recovered = false)
            requireCafeSameBusinessDay(plan, seed, beforeCloseOriginals.map { it.result.recordedAt } + outcome.evidence.occurredAt + cafeNow())
            val preClose = NativeCafeCashProjections.read(initial, plan, seed, CafeCutPhase.PAID_UNSENT,
                "PAID_UNSENT_NATIVE_CUT_BEFORE_ACTUAL_CLOSE", evidence, requirement, outcome, beforeCloseOriginals)
            seed.published.requireCurrent(checkNotNull(initial.publications))
            evidence.bind("ACTUAL_COMPLETE_NATIVE_PUBLICATION_OUTPUT_DATA_BEFORE_CLOSE", seed.published.data())
            evidence.event("ORIGINAL_REQUEST_RESULT_TIME_DATA_RETAINED_BEFORE_ACTUAL_CLOSE", JSONObject()
                .put("originals", JSONArray(beforeCloseOriginals.map { saved -> JSONObject().put("symbol", saved.symbol)
                    .put("operationId", saved.request.operationId.value).put("originalActorId", saved.result.actorId.value)
                    .put("semanticDigest", saved.identity.semanticSha256).put("sequence", saved.result.sequence.value)
                    .put("originalNativeRecordedAt", saved.result.recordedAt.canonicalString()) }))
                .put("completeRequestsAndResults", "DURABLE_COMPARISON_DATA_FOUR_SQL_ORIGINALS_SEPARATELY_NATIVE_STAGED_NOT_AUTHORITY")
                .put("sharedSqlOriginals", 4).put("fullBranchAuthenticationAndPhysicalStaging", "UNBOUND")
                .put("requiredPreCloseFoodProducerSourceProof", "UNBOUND"))
            evidence.bind("FIRST_ORDERLY_CLOSE_REQUESTED_WITNESS_AT", cafeNow().canonicalString())
            owner.closeOwned()
            check(!initial.original.isCurrent() && !initial.original.peer.isCurrent())
            evidence.bind("FIRST_ORDERLY_CLOSE_CONFIRMED_WITNESS_AT", cafeNow().canonicalString())
            val saved = evidence.originals()
            requireSameCafeSavedOriginals(beforeCloseOriginals, saved)
            val firstClosed = owner.readClosedNativeHistory { custody ->
                NativeCafeCashCensus.read(custody, owner.coordinates, plan, saved, seed, preClose, initialNative,
                    "FIRST_CLOSED_PAID_UNSENT_BUSINESS_AND_ORIGINAL_DATA_CENSUS", evidence)
            }
            requireCafeSameBusinessDay(plan, seed, listOf(cafeNow()))
            evidence.bind("ORDERLY_REOPEN_REQUESTED_WITNESS_AT", cafeNow().canonicalString())
            val reopened = owner.openClient() // Real same material, existing-only journals and complete production admission.
            check(reopened.original !== initial.original && reopened.original.serviceIncarnationId != initial.original.serviceIncarnationId &&
                !initial.original.isCurrent() && reopened.signIns == 0)
            evidence.bind("ORDERLY_REOPEN_CONFIRMED_WITNESS_AT", cafeNow().canonicalString())
            val fresh = reopened.signInFresh(employee)
            check(reopened.signIns == 1 && fresh.sessionId != priorSession.sessionId && fresh.actorId == priorSession.actorId &&
                fresh.scope == priorSession.scope && fresh.deviceId == priorSession.deviceId && fresh.login == priorSession.login &&
                fresh.policy.revision == priorSession.policy.revision)
            requireCafeSameBusinessDay(plan, seed, listOf(cafeNow()))
            // No dispatch, setup, recapture, Start publication, Apply, Finalize or Send after reopen.
            val beforeLookup = NativeCafeCashProjections.read(reopened, plan, seed, CafeCutPhase.PAID_UNSENT,
                "FRESH_REOPEN_CURRENT_PAID_UNSENT_BEFORE_ORIGINAL_LOOKUP", evidence, requirement, outcome, saved)
            preClose.requireSame(beforeLookup)
            repeat(2) { saved.forEach { reopened.recover(it) } } // Fresh-authorized exact LOOKUP only, never retry execute.
            reopened.requireFourSqlOriginals(saved, recovered = true)
            val afterLookup = NativeCafeCashProjections.read(reopened, plan, seed, CafeCutPhase.PAID_UNSENT,
                "FRESH_REOPEN_CURRENT_PAID_UNSENT_AFTER_ORIGINAL_LOOKUPS", evidence, requirement, outcome, saved)
            preClose.requireSame(afterLookup)
            beforeLookup.requireSame(afterLookup)
            requireSameCafeSavedOriginals(saved, evidence.originals())
            requireCafeSameBusinessDay(plan, seed, listOf(cafeNow()))
            evidence.bind("SECOND_ORDERLY_CLOSE_REQUESTED_WITNESS_AT", cafeNow().canonicalString())
            owner.closeOwned()
            check(!reopened.original.isCurrent() && !reopened.original.peer.isCurrent())
            evidence.bind("SECOND_ORDERLY_CLOSE_CONFIRMED_WITNESS_AT", cafeNow().canonicalString())
            val secondClosed = owner.readClosedNativeHistory { custody ->
                NativeCafeCashCensus.read(custody, owner.coordinates, plan, saved, seed, preClose, initialNative,
                    "SECOND_CLOSED_PAID_UNSENT_AFTER_EXACT_ORIGINAL_LOOKUPS", evidence)
            }
            firstClosed.requireSameBusiness(secondClosed)
            requireSameCafeSavedOriginals(saved, evidence.originals())
            evidence.event("BOUNDED_CAFE_PAID_UNSENT_ORIGINAL_PRESERVATION_OBSERVED", JSONObject()
                .put("genuineOrderlyNativeCloseAndReopen", true).put("freshSingleCurrentSession", true).put("originalLookupOnly", true)
                .put("historicalQuoteMinor", plan.payable.minor).put("currentUnpaidPayable", JSONObject.NULL)
                .put("paidCoverageMinor", plan.payable.minor).put("remainingObligationMinor", 0)
                .put("receivedMinor", plan.received.minor).put("changeMinor", plan.change.minor)
                .put("appliedDrawerLedgerMinor", (plan.openingCash + plan.payable).minor)
                .put("stockReservationsSurvive", true).put("ingredientIssues", 0).put("kitchenTasks", 0)
                .put("sharedSqlNativeJournalOriginals", 4).put("existingOnlyPublicSqlJournalRecovery", true)
                .put("fullBranchAuthenticationAndPhysicalStaging", "UNBOUND")
                .put("positiveKitchenSend", "UNBOUND").put("wholeCase", "NOT_QUALIFIED"))
            assertionsFinished = true
        } catch (caught: Throwable) { remember(caught) }
        finally {
            secrets.forEach { it.fill('\u0000') }
            try { owner.closeOwned(); cleanupConfirmed = true } catch (caught: Throwable) { remember(caught) }
            try {
                evidence.event("FINAL_CLEANUP", JSONObject().put("nativeCloseConfirmed", cleanupConfirmed)
                    .put("runtimePasswordBuffersWiped", true).put("failureClass", failure?.javaClass?.name ?: JSONObject.NULL))
                evidence.status(if (assertionsFinished && cleanupConfirmed && failure == null)
                    "BOUNDED_NATIVE_ASSERTIONS_OBSERVED_WHOLE_CASE_NOT_QUALIFIED" else "HELD_NOT_VERIFIED")
            } catch (caught: Throwable) { remember(caught) }
        }
        failure?.let { throw it }
    }
}

private fun requireCafeSameBusinessDay(plan: NativeCafeCashPlan, seed: CafeCashSeed, times: List<UtcInstant>) {
    check(times.isNotEmpty() && times.all { plan.calendar.businessDateAt(it) == seed.cashBusinessDate }) {
        "The supplied Cairo 04:00 ordinary same-business-day rehearsal precondition crossed its cutoff; hold, never invent a date policy"
    }
}
private fun requireSameCafeSavedOriginals(before: List<CafeSavedOriginal>, after: List<CafeSavedOriginal>) {
    check(before.size == after.size)
    before.zip(after).forEach { (left, right) ->
        check(left.symbol == right.symbol && left.identity == right.identity && left.scope == right.scope && left.deviceId == right.deviceId &&
            left.sessionId == right.sessionId && left.sessionGeneration == right.sessionGeneration && left.policyRevision == right.policyRevision &&
            left.installationId == right.installationId && left.assignmentId == right.assignmentId && left.storageInstanceId == right.storageInstanceId &&
            left.authorityEpoch == right.authorityEpoch && left.request.operationId == right.request.operationId && left.request.command == right.request.command &&
            left.request.payload == right.request.payload && left.request.actionApprovalId == right.request.actionApprovalId && left.result == right.result) {
            "The durably retained whole original request/result/actor/device/time DATA changed across the close boundary"
        }
    }
}
/** Runtime-generated secrets never become a String, fixture, file, log or evidence field. */
private fun cafeCashSecret(): CharArray {
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
