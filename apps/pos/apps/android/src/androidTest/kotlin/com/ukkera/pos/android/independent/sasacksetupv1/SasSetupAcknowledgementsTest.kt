package com.ukkera.pos.android.independent.sasacksetupv1

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.independent.aosfsetupv2.cleanupAosf
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureOwner
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Independent E3-005 supporting-capability executor for the frozen SAS-001..SAS-022 cases.
 *
 * One selected synthetic case runs against a fresh isolated nondebuggable installation. The
 * harness uses only the actual native owner, real signed head ceremonies, the current master,
 * durable Room and ordinary production commands/reads. It never supplies a fake readiness,
 * grant, session, actor, audit time or result. A whole frozen case is never reported PASS here:
 * every selected case ends with its explicit unbound remainder (other master platforms,
 * undecided native facts, missing prerequisite bindings or missing separate environments).
 */
@RunWith(AndroidJUnit4::class)
class SasSetupAcknowledgementsTest {
    @Test
    fun selectedFrozenCase(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val case = checkNotNull(arguments.getString("sasCase")) {
            "Select one frozen sasCase=SAS-001..SAS-022 explicitly"
        }
        check(case in SAS_CASES) { "Unknown frozen SAS case $case" }
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")) {
            "Select keyProtection=OS_ISOLATED, SECURE_HARDWARE or STRONGBOX explicitly"
        })
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing") {
            "SAS requires the isolated com.ukkera.pos.local.testing application"
        }
        check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            "The genuine native owner refuses a debuggable application"
        }
        val evidence = SasEvidence.claim(application, case, protection.name)
        val plan = SasFixturePlan(protection)
        val secrets = SasSecrets()
        val owner = NativeLocalFixtureOwner(
            application = application,
            protection = protection,
            evidence = evidence,
            setupInputs = plan.setup,
            clientFactory = { original, journals, publications -> SasClient(original, journals, publications, plan, evidence) },
        )
        var fixture: SasFixture? = null
        var primaryFailure: Throwable? = null
        try {
            owner.setup(secrets.installer, secrets.head, secrets[SasActor.US])
            val client = owner.openClient()
            val actual = SasFixture(application, owner, client, plan, secrets, evidence)
            fixture = actual
            evidence.bind("S1_ANCHOR", actual.anchor.retained())
            val baseCut = actual.settleBaseCut()
            actual.baseCut = baseCut
            evidence.bind("BASE_CUT", baseCut.facts())
            evidence.event("S1_NATIVE_ORIGINAL_READY", org.json.JSONObject()
                .put("installationId", actual.anchor.installationId.value)
                .put("originalRequestId", actual.anchor.originalRequestId.value)
                .put("receiptDigest", actual.anchor.receiptDigest)
                .put("authorityEpoch", actual.anchor.authorityEpoch)
                .put("serviceIncarnationId", actual.anchor.serviceIncarnationId))
            val status = runSelectedCase(case, actual, evidence)
            evidence.status(status)
            assumeTrue("Frozen SAS $case retains explicit unbound obligations; see evidence unbound entries", false)
        } catch (assumption: AssumptionViolatedException) {
            throw assumption
        } catch (failure: Throwable) {
            primaryFailure = failure
            evidence.fail(failure)
            throw failure
        } finally {
            try {
                cleanupAosf(
                    { fixture?.close() ?: owner.closeOwned() },
                    { secrets.clear() },
                    { evidence.persist() },
                )
            } catch (cleanupFailure: Throwable) {
                primaryFailure?.let { cleanupFailure.addSuppressed(it) }
                evidence.fail(cleanupFailure)
                throw cleanupFailure
            }
        }
    }

    private companion object {
        val SAS_CASES = (1..22).map { "SAS-" + it.toString().padStart(3, '0') }.toSet()
    }
}
