package com.ukkera.pos.android.openingobligation

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real delegated S1 setup, cold native source-history admission, one frozen positive A,
 * protected exact readback and same-original lookup after confirmed close/fresh auth.
 * This does not qualify a whole ODI case, independent DB/full zero-effect inspection, UI,
 * admitted LAN RPC, process-kill/power/fault schedules or another platform.
 * No production service, clock, peer, authority, verifier or history producer is replaced.
 */
@RunWith(AndroidJUnit4::class)
class NativePositiveOpeningSupplierObligationTest {
    @Test
    fun nativePositiveOpeningASurvivesSupplierHistoryCloseAndFreshRecovery(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("syntheticOpeningObligation") == "true") {
            "Explicit syntheticOpeningObligation=true is required on a disposable testing installation"
        }
        check(arguments.getString("syntheticPositiveOpeningObligation") == "true") {
            "Explicit syntheticPositiveOpeningObligation=true is required for this separate positive fixture"
        }
        val application = instrumentation.targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing")
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")) {
            "Select keyProtection=OS_ISOLATED, SECURE_HARDWARE or STRONGBOX explicitly; there is no fallback"
        })
        val original = NativeOpeningSupplierOriginal.read(instrumentation.context.assets)
        val namespace = DomainId(UUID.randomUUID().toString())
        val positiveSetup = NativePositiveOpeningSupplierSetup(namespace, original)
        val plan = NativeOpeningSupplierPlan(namespace, protection, original, positiveSupplierSetup = positiveSetup)
        val intentFile = File(application.noBackupFilesDir, "opening-supplier-obligation-positive-a-intent-v1.json")
        check(listOf(intentFile, File("${intentFile.path}.bak"), File("${intentFile.path}.new")).none { it.exists() }) {
            "Retained opening intent exists; do not reset or replace an unknown original fixture"
        }
        check(intentFile.createNewFile()) { "Opening intent is already retained; no fixture retry is permitted" }
        val record = JSONObject().put("status", "ORIGINAL_INTENT_RETENTION_PENDING")
            .put("method", "nativePositiveOpeningASurvivesSupplierHistoryCloseAndFreshRecovery")
            .put("plan", plan.retainedPlan()).put("boundedVerification", "NOT_VERIFIED")
            .put("boundFrozenParts", JSONArray(listOf("ODI-001 native A readback", "ODI-003 native read/result parts",
                "ODI-006 original lookup only", "ODI-014 logout/confirmed service close/fresh auth parts")))
            .put("wholeODICases", "NOT_RUN").put("ODAObligations", "NOT_RUN")
        val durableIntent = AtomicFile(intentFile)
        fun retainIntent() {
            val stream = durableIntent.startWrite()
            try {
                stream.write(record.toString().encodeToByteArray())
                durableIntent.finishWrite(stream)
            } catch (failure: Throwable) {
                try { durableIntent.failWrite(stream) }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        val owner = NativeOpeningSupplierObligationOwner(application, plan, record, ::retainIntent)
        val secrets = mutableListOf<CharArray>()
        val random = SecureRandom()
        fun secret(): CharArray {
            val bytes = ByteArray(24)
            val characters = try {
                random.nextBytes(bytes)
                val hex = "0123456789abcdef"
                CharArray(bytes.size * 2) { index ->
                    val value = bytes[index / 2].toInt() and 0xff
                    hex[if (index % 2 == 0) value ushr 4 else value and 15]
                }
            } finally { bytes.fill(0) }
            secrets += characters
            return characters
        }
        var primaryFailure: Throwable? = null
        var assertionsFinished = false
        var finalNativeCloseConfirmed = false
        fun rememberFailure(next: Throwable) {
            val first = primaryFailure
            if (first == null) primaryFailure = next else if (first !== next) first.addSuppressed(next)
        }
        try {
            // All frozen documentary bytes/fields and canonical supplier setup precede native
            // provisioning. Final A is bound/retained only from the actual supplier storage read.
            retainIntent()
            val installer = secret()
            val head = secret()
            val founderPassword = secret()
            val operatorPassword = secret()
            owner.prepareAndInstall(installer, head, founderPassword)
            record.put("nativeScopeAliasBindings", JSONObject()
                .put("organizationO1", owner.coordinates.scope.organizationId.value)
                .put("companyC1", owner.coordinates.scope.companyId.value)
                .put("branchB1", owner.coordinates.scope.branchId.value))
                .put("aliasBindingMeaning", "FIXTURE_NOTATION_NOT_AUTHORITY_OR_REWRITTEN_DOCUMENT")
            retainIntent()

            owner.openOperational() // 1: ordinary original runtime, founder never signed in.
            owner.enrollOperator(head, operatorPassword) // Real approval/Applied/lookup/close.
            owner.openOperational() // 2: ordinary full-history cold admission.
            owner.requireColdConsumedOperator()
            owner.signInOperator(operatorPassword) // Independent actual employee authentication.
            owner.requireAbsentSupplier()
            owner.requireEmptyOpeningRead("BEFORE_CONFIGURATION")
            owner.configureSupplier() // One real current S1 ADMINISTER command, expected storage null.
            owner.bindOriginalFromProtectedSupplier() // Actual active source read/storageRevision 1.
            owner.logout()
            check(owner.close()) { "Supplier configuration owner closure remains unconfirmed" }

            owner.openOperational() // 3: ordinary native history must join real employee supplier setup.
            owner.requireColdConsumedOperator()
            owner.signInOperator(operatorPassword)
            owner.requireActiveSupplier("AFTER_SUPPLIER_HISTORY_COLD_OPEN")
            owner.requireEmptyOpeningRead("BEFORE_ORIGINAL")
            owner.postPositiveOriginal() // Exactly ONE original A posting, immutable frozen documentary input.
            owner.requirePositiveOpeningReads("AFTER_ORIGINAL")
            owner.logout()
            check(owner.close()) { "Original positive A owner closure remains unconfirmed" }

            owner.openOperational() // 4: full ordinary native opening/source history; no execute retry.
            owner.requireColdConsumedOperator()
            owner.signInOperator(operatorPassword)
            owner.requireActiveSupplier("AFTER_OPENING_HISTORY_COLD_OPEN")
            owner.requirePositiveOpeningReads("AFTER_FRESH_AUTHENTICATION")
            owner.recoverPositiveOriginal() // Only the originally retained identity is looked up.
            owner.requirePositiveOpeningReads("AFTER_ORIGINAL_RECOVERY")
            owner.logout()
            check(owner.close()) { "Final native owner closure remains unconfirmed" }
            finalNativeCloseConfirmed = true
            assertionsFinished = true
        } catch (failure: Throwable) {
            owner.hold()
            rememberFailure(failure)
        } finally {
            secrets.forEach { it.fill('\u0000') }
            original.documentBytes.fill(0)
            var cleanupReportedClosed: Boolean? = null
            try { cleanupReportedClosed = owner.close() }
            catch (failure: Throwable) { owner.hold(); rememberFailure(failure) }
            owner.takeCleanupFailures().forEach(::rememberFailure)
            if (cleanupReportedClosed != true) {
                owner.hold()
                rememberFailure(AssertionError("Actual cleanup or ownership settlement remains unconfirmed"))
            }
            val verified = assertionsFinished && finalNativeCloseConfirmed && cleanupReportedClosed == true &&
                primaryFailure == null && !owner.held
            record.put("cleanupReportedClosed", cleanupReportedClosed ?: JSONObject.NULL)
                .put("nativeCloseConfirmed", if (verified) true else JSONObject.NULL)
                .put("lastOwnedStep", owner.step)
                .put("boundedVerification", if (verified) "VERIFIED" else "NOT_VERIFIED")
                .put("status", if (verified) "BOUNDED_NATIVE_POSITIVE_A_RECOVERY_VERIFIED_WHOLE_CASES_NOT_RUN" else "HELD")
                .put("wholeODICases", "NOT_RUN").put("ODAObligations", "NOT_RUN")
                .put("failureClass", primaryFailure?.javaClass?.name ?: JSONObject.NULL)
                .put("suppressedFailureClasses", JSONArray(primaryFailure?.suppressed?.map { it.javaClass.name } ?: emptyList<String>()))
            // This updates only the original retained intent. It never resets state,
            // creates another operation or retries an uncertain native dispatch/close.
            try { retainIntent() } catch (failure: Throwable) {
                owner.hold()
                record.put("status", "HELD").put("boundedVerification", "NOT_VERIFIED")
                rememberFailure(failure) // Do not retry a rejected/unknown final retention.
            }
        }
        primaryFailure?.let { throw it }
    }
}
