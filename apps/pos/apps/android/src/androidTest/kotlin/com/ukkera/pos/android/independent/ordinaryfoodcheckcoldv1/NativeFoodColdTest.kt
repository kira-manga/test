package com.ukkera.pos.android.independent.ordinaryfoodcheckcoldv1

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureOwner
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureSetup
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import java.security.SecureRandom
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/** Genuine bounded OFC-COLD-101 backend leg, selected independently for Restaurant
 * or Café. No reset, automatic login, replacement seed, accepting history decoder,
 * second live reader or whole-case/adjunct qualification is provided by this test.
 * Completed publication DATA and original reply/time precede the orderly close;
 * the stronger required pre-close FOOD producer/source proof remains UNBOUND. */
@RunWith(AndroidJUnit4::class)
class NativeFoodColdTest {
    @Test fun ofcCold101NativeOriginalAcrossOrderlyReopen(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("syntheticOrdinaryFoodCold101") == "true") {
            "Explicit syntheticOrdinaryFoodCold101=true requires a fresh isolated synthetic localTesting installation"
        }
        val profile = checkNotNull(arguments.getString("ordinaryFoodProfile")) {
            "Select ordinaryFoodProfile=restaurant or cafe explicitly; no profile is implicitly skipped"
        }
        check(profile == "restaurant" || profile == "cafe")
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")) {
            "Select keyProtection=OS_ISOLATED, SECURE_HARDWARE or STRONGBOX explicitly; no fallback is used"
        })
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing")
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 && Build.VERSION.SDK_INT >= 24)
        fun frozen(name: String) = instrumentation.context.assets.open("ordinary-food-check-cold-v1/$name").use {
            JSONObject(it.bufferedReader().readText())
        }
        val plan = NativeFoodColdInputPlan(frozen("base-v1.1.0.fixtures.json"),
            frozen("identity-only-v1.0.0.fixtures.json"), frozen("catalog-coordinate-v1.0.0.fixtures.json"), profile, protection)
        val evidence = NativeFoodColdEvidence(application, plan) // Must exist before native setup; never overwrite a prior invocation.
        val inputs = plan.setup
        val retainedSetup = NativeLocalFixtureSetup(inputs.companyName, inputs.branchName, inputs.installerLogin,
            inputs.headLogin, inputs.initialAuthorityEpoch, inputs.founding, account = { coordinates ->
                inputs.account(coordinates).also { evidence.bind("OWNER_FULL_ORIGINAL_SETUP_ACCOUNT_INPUT", foodColdAccount(it)) }
            })
        val owner = NativeLocalFixtureOwner(application, protection, evidence, retainedSetup) { original, journals, publications ->
            NativeFoodColdClient(original, journals, publications, plan, evidence)
        }
        val secrets = mutableListOf<CharArray>()
        fun secret(): CharArray {
            val generated = nativeFoodColdSecret()
            return try { secrets.add(generated); generated }
            catch (failure: Throwable) { generated.fill('\u0000'); throw failure }
        }
        suspend fun sessions(client: NativeFoodColdClient, passwords: Map<FoodColdActor, CharArray>): Map<FoodColdActor, LocalSessionView> {
            check(client.signIns == 0)
            val current = FoodColdActor.entries.associateWith { actor -> client.signIn(actor, passwords.getValue(actor)) }
            check(client.signIns == FoodColdActor.entries.size && current.values.map { it.actorId }.distinct().size == current.size &&
                current.values.map { it.sessionId }.distinct().size == current.size)
            current.forEach { (actor, view) ->
                val reobserved = client.current(actor)
                check(reobserved.sessionId == view.sessionId && reobserved.generation == view.generation &&
                    reobserved.accessExpiresAt == view.accessExpiresAt && reobserved.workExpiresAt == view.workExpiresAt)
            }
            return current // Current observations only; no old access/work token is retained here.
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
            val passwords = FoodColdActor.entries.associateWith { secret() }
            evidence.event("BOUNDED_NATIVE_SCOPE", JSONObject().put("profile", profile).put("keyProtection", protection.name)
                .put("sdkInt", Build.VERSION.SDK_INT).put("osRelease", Build.VERSION.RELEASE)
                .put("wholeCase", "NOT_QUALIFIED").put("inputOrigin", "ACTUAL_SAME_DEVICE_PROTECTED_BACKEND")
                .put("closeKind", "CONFIRMED_ORDERLY_NATIVE_OWNER_CLOSE_NOT_PROCESS_CRASH")
                .put("requiredPreCloseFoodProducerSourceProof", "UNBOUND")
                .put("unbound", JSONArray(NativeFoodColdEvidence.unbound)))
            owner.setup(installer, head, passwords.getValue(FoodColdActor.OWNER)) // Actual setup ends with actual owned close.
            installer.fill('\u0000')
            val initialNative = owner.readClosedNativeHistory { custody ->
                NativeFoodColdCensus.read(custody, owner.coordinates, plan, emptyList(), null, null, null,
                    "INITIAL_CLOSED_SETUP_BEFORE_SESSIONS_OR_BUSINESS", evidence)
            }
            val initial = owner.openClient() // Acquire original endpoint and native journals before either staff enrollment.
            check(initial.signIns == 0)
            initial.enroll(FoodColdActor.ADMINISTRATOR, passwords.getValue(FoodColdActor.ADMINISTRATOR), head)
            initial.enroll(FoodColdActor.KDS, passwords.getValue(FoodColdActor.KDS), head)
            check(initial.signIns == 0)
            val priorSessions = sessions(initial, passwords)
            val seed = NativeFoodColdPreparation.prepare(initial, plan, evidence, head)
            head.fill('\u0000')
            val retainedBeforeClose = evidence.originals() // Every exact request and original reply/time was durably retained before close.
            val preClose = NativeFoodColdProjections.readLiteralSeed(initial, plan, seed, retainedBeforeClose,
                "PRE_CLOSE_LITERAL_SEED", evidence)
            seed.published.requireCurrent(checkNotNull(initial.publications))
            evidence.bind("ACTUAL_COMPLETED_NATIVE_PUBLICATION_CUT_BEFORE_CLOSE", seed.published.cutData())
            evidence.event("ORIGINAL_INPUT_RESULT_DATA_RETAINED_BEFORE_ACTUAL_CLOSE", JSONObject()
                .put("originals", JSONArray(retainedBeforeClose.map { saved -> JSONObject().put("symbol", saved.symbol)
                    .put("operationId", saved.request.operationId.value).put("originalActorId", saved.result.actorId.value)
                    .put("originalRecordedAt", saved.result.recordedAt.canonicalString()).put("sequence", saved.result.sequence.value)
                    .put("semanticDigest", saved.identity.semanticSha256) }))
                .put("completeRequestsAndResults", "DURABLE_TEST_INPUT_DATA_NOT_AUTHORITY")
                .put("requiredPreCloseFoodProducerSourceProof", "UNBOUND"))
            owner.closeOwned()
            check(!initial.original.isCurrent() && !initial.original.peer.isCurrent())
            val saved = evidence.originals() // Reload only after genuine close; no old bearer supplies recovery.
            requireSameRetainedOriginals(retainedBeforeClose, saved)
            val firstClosed = owner.readClosedNativeHistory { custody ->
                NativeFoodColdCensus.read(custody, owner.coordinates, plan, saved, seed, preClose, initialNative,
                    "FIRST_CLOSED_SEED_ORIGINAL_EVENT_DATA_CUT", evidence)
            }

            val reopened = owner.openClient() // Fresh production original admission, same material/coordinates and existing native journals.
            check(reopened.original !== initial.original && reopened.original.serviceIncarnationId != initial.original.serviceIncarnationId &&
                !initial.original.isCurrent() && reopened.signIns == 0)
            val freshSessions = sessions(reopened, passwords)
            val priorIds = priorSessions.values.map { it.sessionId }.toSet()
            freshSessions.forEach { (actor, current) ->
                val prior = priorSessions.getValue(actor)
                check(current.sessionId !in priorIds && current.actorId == prior.actorId && current.scope == prior.scope &&
                    current.deviceId == prior.deviceId && current.login == prior.login && current.policy.revision == prior.policy.revision)
            }
            // No activation/reseed/publication/configuration command is performed after reopen.
            val beforeLookup = NativeFoodColdProjections.readLiteralSeed(reopened, plan, seed, saved,
                "FRESH_REOPEN_BEFORE_ORIGINAL_LOOKUP_LITERAL_SEED", evidence)
            preClose.requireSame(beforeLookup)
            repeat(2) { saved.forEach { reopened.recover(it) } } // Protected original lookup only; never execute/retry a saved mutation.
            val afterLookup = NativeFoodColdProjections.readLiteralSeed(reopened, plan, seed, saved,
                "FRESH_REOPEN_AFTER_ORIGINAL_LOOKUPS_LITERAL_SEED", evidence)
            preClose.requireSame(afterLookup)
            beforeLookup.requireSame(afterLookup)
            requireSameRetainedOriginals(saved, evidence.originals())
            owner.closeOwned()
            check(!reopened.original.isCurrent() && !reopened.original.peer.isCurrent())
            val afterClosed = owner.readClosedNativeHistory { custody ->
                NativeFoodColdCensus.read(custody, owner.coordinates, plan, saved, seed, preClose, initialNative,
                    "SECOND_CLOSED_SEED_AFTER_ORIGINAL_LOOKUPS", evidence)
            }
            firstClosed.requireSameBusiness(afterClosed)
            evidence.event("BOUNDED_ORDERLY_COLD_ORIGINAL_PRESERVATION_OBSERVED", JSONObject()
                .put("profile", profile).put("actualOwnedCloseConfirmed", true).put("freshOriginalLifetime", true)
                .put("separateFreshCurrentSessions", freshSessions.size).put("originalLookupOnly", true)
                .put("replacementSeed", false).put("documentaryReleaseRevision", "5")
                .put("quotationCursor", seed.quoteCursor.value.toString()).put("authorityEpoch", plan.authorityEpoch.value.toString())
                .put("payableMinor", plan.payable.minor.toString()).put("outstandingMinor", plan.outstanding.minor.toString())
                .put("quantity", plan.totalQuantity.toString()).put("commercialGroups", plan.groupCount)
                .put("originalIssues", plan.issuedPortions).put("historicalRequiredTasks", plan.taskCount)
                .put("financialCashRefundFinalizationIncidentRestoration", "ZERO_BY_COMPLETE_CLOSED_NATIVE_DATA_ASSERTIONS")
                .put("requiredPreCloseFoodProducerSourceProof", "UNBOUND").put("wholeCase", "NOT_QUALIFIED"))
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

private fun requireSameRetainedOriginals(before: List<FoodColdSavedOriginal>, after: List<FoodColdSavedOriginal>) {
    check(before.size == after.size)
    before.zip(after).forEach { (left, right) ->
        check(left.symbol == right.symbol && left.actor == right.actor && left.identity == right.identity && left.scope == right.scope &&
            left.deviceId == right.deviceId && left.sessionId == right.sessionId && left.policyRevision == right.policyRevision &&
            left.installationId == right.installationId && left.assignmentId == right.assignmentId && left.storageInstanceId == right.storageInstanceId &&
            left.request.operationId == right.request.operationId && left.request.command == right.request.command &&
            left.request.payload == right.request.payload && left.request.actionApprovalId == right.request.actionApprovalId && left.result == right.result) {
            "Durably retained original request/result/actor/time/coordinates changed"
        }
    }
}

/** Generated passwords never become a String, file, fixture, log or evidence field. */
private fun nativeFoodColdSecret(): CharArray {
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
