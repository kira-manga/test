package com.ukkera.pos.android.independent.coldoriginalstartv1

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureOwner
import com.ukkera.pos.app.gateway.AndroidOriginalRetailPhysicalConnection
import com.ukkera.pos.sales.store.LocalFinancialReadResultV3
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.BusinessDate
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.UtcInstant
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual same-device backend leg of frozen COS-001, not whole-case qualification.
 * Genuine setup, fresh employee sessions, physical Start/publication/native capture,
 * separate financial Apply and normalized Finalize precede two actual owner reopens.
 * Original sale cost is read from its separately authorized original issue history.
 * Private saved inputs and prior read values are comparison DATA, never authority.
 * The unchanged frozen UI/payment-receipt/network assertions remain unbound.
 */
@RunWith(AndroidJUnit4::class)
class NativeColdOriginalTest {
    @Test fun cos001NativeCashOriginalAcrossTwoColdReopens(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("syntheticNativeColdOriginalCos001") == "true") {
            "Explicit syntheticNativeColdOriginalCos001=true requires an isolated synthetic localTesting installation"
        }
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing")
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 && Build.VERSION.SDK_INT >= 24)
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")) {
            "Select keyProtection=OS_ISOLATED, SECURE_HARDWARE or STRONGBOX explicitly; no fallback is used"
        })
        val frozen = instrumentation.context.assets.open("cold-original-start-v1-fixtures.json").use {
            JSONObject(it.bufferedReader().readText())
        }
        val now = Instant.now()
        val plan = NativeColdOriginalFixturePlan(frozen, DomainId(UUID.randomUUID().toString()),
            UtcInstant.parseCanonical(now.toString()),
            BusinessDate.parseCanonical(now.atOffset(ZoneOffset.UTC).toLocalDate().toString()), protection)
        val evidence = NativeColdOriginalEvidence(application, plan)
        val owner = NativeLocalFixtureOwner(application, protection, evidence, plan.setup,
            beforeClientJournals = { original, action ->
                checkNotNull(AndroidOriginalRetailPhysicalConnection.acquire(application, original, action)) {
                    "The actual Master could not acquire its original physical owner before client journal enrollment"
                }
            }) { original, journals, publications ->
                NativeColdOriginalClient(original, journals, publications, plan, evidence)
            }
        val secrets = mutableListOf<CharArray>()
        val staleAccess = mutableListOf<OpaqueCredential>()
        fun secret() = coldOriginalSecret().also(secrets::add)
        fun originals() = listOf("ORIGINAL_START", "ORIGINAL_OUTCOME", "ORIGINAL_APPLICATION", "ORIGINAL_FINALIZATION")
            .map(evidence::savedOriginal)
        suspend fun census(label: String) = owner.readClosedNativeHistory { custody ->
            NativeColdOriginalCensus.read(custody, owner.coordinates, plan, originals(), "C3", label, evidence)
        }
        var failure: Throwable? = null
        var assertionsFinished = false
        var cleanupConfirmed = false
        fun remember(caught: Throwable) {
            val prior = failure
            if (prior == null) failure = caught else if (prior !== caught) prior.addSuppressed(caught)
            // Retain the originating failure before required native cleanup can itself
            // lose closure and exclude this process. No messages, inputs or native data.
            try {
                val locations = caught.stackTrace.filter {
                    it.className.startsWith("com.ukkera.pos.")
                }.take(6).joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
                android.util.Log.w("UkkeraNativeOwner", "Cold executor failure before cleanup: " +
                    "type=${caught.javaClass.simpleName}; $locations")
            } catch (_: Throwable) { /* Diagnostics cannot replace the original failure. */ }
        }
        try {
            val installer = secret()
            val head = secret()
            val employee = secret()
            evidence.event("BOUNDED_NATIVE_SCOPE", JSONObject()
                .put("keyProtection", protection.name).put("sdkInt", Build.VERSION.SDK_INT)
                .put("osRelease", Build.VERSION.RELEASE).put("wholeCase", "NOT_QUALIFIED")
                .put("inputOrigin", "ACTUAL_SAME_DEVICE_PROTECTED_BACKEND")
                .put("businessDateMeaning", "EXPLICIT_SYNTHETIC_UTC_DATE_NOT_FISCAL_POLICY")
                .put("closeKind", "CONFIRMED_ORDERLY_NATIVE_OWNER_CLOSE_NOT_PROCESS_CRASH")
                .put("unbound", JSONArray(NativeColdOriginalEvidence.unboundFrozenAssertions)))
            owner.setup(installer, head, employee)
            installer.fill('\u0000')
            val initial = owner.openClient()
            check(initial.signIns == 0)
            val firstSession = initial.signInFresh(employee)
            val preparation = NativeColdOriginalPreparation(initial, plan, evidence)
            val c0 = preparation.prepareC0(head)
            head.fill('\u0000')
            val request = preparation.beginAndPrepareStart(c0)
            val requirement = initial.startOriginal(request)
            preparation.reads.checkpoint("C1")
            val beforeCapture = initial.recording()
            check(beforeCapture.original == null && beforeCapture.resolution == null &&
                beforeCapture.requirementDigest == coldDigest(requirement.canonicalBytes).value)
            initial.captureSuccessfulOriginal(requirement)
            val c2Fact = initial.awaitDurableSuccessfulC2()
            initial.observeDurableOriginalResult(c2Fact)
            preparation.reads.checkpoint("C2-SUCCESS")
            val c2Recording = initial.recording()
            check(c2Recording.original == c2Fact.summary && c2Recording.resolution == null &&
                c2Recording.copy(original = null) == beforeCapture)
            val c3 = preparation.applyAndFinalize()
            val c3Fact = initial.financialFact() as? LocalFinancialReadResultV3.Fact
                ?: error("The applied original is unavailable")
            initial.requireOriginalFact(c3Fact)
            check(c3Fact.copy(summary = c3Fact.summary.copy(
                applicationState = c2Fact.summary.applicationState,
                pendingReason = c2Fact.summary.pendingReason,
                lastApplicationOperationId = c2Fact.summary.lastApplicationOperationId,
                appliedAction = c2Fact.summary.appliedAction)) == c2Fact) {
                "Financial application rewrote an immutable captured original"
            }
            val c3Recording = initial.recording()
            check(c3Recording.original == c3Fact.summary && c3Recording.resolution == null &&
                c3Recording.copy(original = null) == beforeCapture)
            val closedSessionAccess = initial.copyAccessForNegativeChallenge().also(staleAccess::add)
            owner.closeOwned()
            check(!initial.original.isCurrent())
            val originalCensus = census("COS001_C3_CLOSED_NATIVE_CUT")

            var previousSessionId = firstSession.sessionId
            var deniedAccess = closedSessionAccess
            repeat(2) { index ->
                val reopened = owner.openClient() // Actual history admission, not a reconstructed fixture Master.
                check(reopened.signIns == 0)
                val saved = originals() // Re-read durable test inputs; no old bearer supplies recovery.
                reopened.requireStaleAccessDenied(deniedAccess, saved.first())
                deniedAccess.destroy()
                val fresh = reopened.signInFresh(employee)
                check(fresh.sessionId != previousSessionId && fresh.actorId == firstSession.actorId &&
                    fresh.scope == firstSession.scope && fresh.deviceId == firstSession.deviceId)
                previousSessionId = fresh.sessionId
                repeat(2) { saved.forEach { reopened.recoverExact(it) } }
                check(reopened.financialFact() == c3Fact && reopened.recording() == c3Recording)
                check(ColdOriginalProjectionReader(reopened, plan, evidence).checkpoint("C3") == c3) {
                    "Original recovery changed the acknowledged business cut"
                }
                deniedAccess = reopened.copyAccessForNegativeChallenge().also(staleAccess::add)
                owner.closeOwned()
                check(!reopened.original.isCurrent())
                val after = census("COS001_REOPEN_${index + 1}_CLOSED_NATIVE_CUT")
                originalCensus.requireSameBusiness(after)
                evidence.event("COLD_REOPEN_ORIGINAL_RECOVERY_OBSERVED", JSONObject()
                    .put("reopen", index + 1).put("sessionId", fresh.sessionId.value)
                    .put("originalLookupOnly", true).put("replacementStart", false)
                    .put("wholeCase", "NOT_QUALIFIED"))
            }
            assertionsFinished = true
        } catch (caught: Throwable) { remember(caught) }
        finally {
            secrets.forEach { it.fill('\u0000') }
            staleAccess.forEach { it.destroy() }
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
}

private fun coldOriginalSecret(): CharArray {
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
