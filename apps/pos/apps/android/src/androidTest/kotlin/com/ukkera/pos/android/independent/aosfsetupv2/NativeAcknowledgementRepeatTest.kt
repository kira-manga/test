package com.ukkera.pos.android.independent.aosfsetupv2

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureClient
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureOwner
import com.ukkera.pos.android.independent.sasacksetupv1.SasActor
import com.ukkera.pos.android.independent.sasacksetupv1.SasEvidence
import com.ukkera.pos.android.independent.sasacksetupv1.SasFixturePlan
import com.ukkera.pos.android.independent.sasacksetupv1.SasSecrets
import com.ukkera.pos.database.LocalMasterInstallationOutcome
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Supplemental execution of frozen NAC-001 action 4 / confirmed NAC-002: a repeated native
 * completion returns the saved original without another ACK, history admission or owner.
 * This does not execute the pending-cleanup portion or qualify a whole NAC/AOSF case.
 */
@RunWith(AndroidJUnit4::class)
class NativeAcknowledgementRepeatTest {
    @Test
    fun confirmedOriginalCanBeObservedRepeatedlyWithoutAnotherAcknowledgement(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("nativeAckCase") == "REPEAT_CONFIRMED") {
            "Select nativeAckCase=REPEAT_CONFIRMED on a fresh isolated installation"
        }
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")))
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing" &&
            application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val evidence = SasEvidence.claim(application, "NAC-SUPPLEMENTAL-REPEAT-CONFIRMED", protection.name)
        val plan = SasFixturePlan(protection)
        val secrets = SasSecrets()
        val owner = NativeLocalFixtureOwner(application, protection, evidence, plan.setup,
            clientFactory = { original, journals, _ ->
                object : NativeLocalFixtureClient {
                    override val original = original
                    override val signIns: Int get() = 0
                    private var closeAttempted = false
                    override suspend fun close() {
                        if (closeAttempted) return
                        closeAttempted = true
                        journals.close()
                    }
                }
            })
        var primary: Throwable? = null
        try {
            owner.setup(secrets.installer, secrets.head, secrets[SasActor.US])
            val before = readAosfNativeCut(application)
            val client = owner.openClient()
            val master = owner.currentMaster
            val incarnation = client.original.serviceIncarnationId
            check(master.mode.value == LocalMasterLifecycleMode.OPERATIONAL && client.signIns == 0)
            val setup = master.readOriginalSetup()
            val saved = checkNotNull(setup.receipt)
            repeat(2) {
                val result = master.resumeOriginalAcknowledgement() as? LocalMasterInstallationOutcome.Installed
                    ?: error("Confirmed native original was not returned by repeated completion")
                check(result.receipt.digest == saved.digest && result.receipt.genesis == saved.genesis &&
                    result.receipt.copyWireBytes().contentEquals(saved.copyWireBytes()))
                check(owner.currentMaster === master && owner.currentClient === client &&
                    client.original.serviceIncarnationId == incarnation && client.original.isCurrent() &&
                    client.signIns == 0 && master.mode.value == LocalMasterLifecycleMode.OPERATIONAL)
                check(master.readOriginalSetup().definition?.definitionDigest == setup.definition?.definitionDigest)
            }
            owner.closeOwned()
            val after = readAosfNativeCut(application)
            before.requireSameReadCut(after)
            after.requireInitialSetupOnly()
            evidence.status("NATIVE_REPEAT_SEGMENT_EXECUTED_NOT_WHOLE_NAC_CASE")
        } catch (failure: Throwable) {
            primary = failure
            evidence.fail(failure)
            throw failure
        } finally {
            try { cleanupAosf({ owner.closeOwned() }, { secrets.clear() }) }
            catch (cleanup: Throwable) {
                primary?.let { cleanup.addSuppressed(it) }
                evidence.fail(cleanup)
                throw cleanup
            }
        }
    }
}
