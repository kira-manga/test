package com.ukkera.pos.android.independent.androidpersistedstopv1

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureOwner
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.sales.store.LocalCashQueryV1
import com.ukkera.pos.sales.store.LocalCashReadResultV1
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual native Master/client custody segments for E3-006 v2. A1 logout, a real pending CASH
 * closing original (actor/device/drawer) and one consumed native-owner close are exercised on
 * the production Android owner. The remote admitted C11 client, ALCR-MCART arithmetic and OS
 * lifecycle observations are not manufactured here.
 */
@RunWith(AndroidJUnit4::class)
class NativePersistedStopCustodyTest {
    @Test
    fun selectedCustodySegment(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val case = checkNotNull(arguments.getString("apslCase")) {
            "Select apslCase=A1_LOGOUT_PENDING|OWNER_CLOSE_ACCOUNTING"
        }
        check(case in setOf("A1_LOGOUT_PENDING", "OWNER_CLOSE_ACCOUNTING")) { "Unknown APSL custody case: $case" }
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")))
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing" &&
            application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val evidence = ApslEvidence.claim(application, case, protection.name)
        val plan = ApslFixturePlan(protection)
        val secrets = ApslSecrets()
        val owner = NativeLocalFixtureOwner(application, protection, evidence, plan.setup,
            clientFactory = { original, journals, _ ->
                ApslNativeClient(original, journals, plan, evidence)
            })
        var primary: Throwable? = null
        var closedByCase = false
        try {
            owner.setup(secrets.installer, secrets.head, secrets.employee)
            when (case) {
                "A1_LOGOUT_PENDING" -> {
                    a1LogoutPending(owner, secrets, evidence)
                    closedByCase = true
                }
                "OWNER_CLOSE_ACCOUNTING" -> {
                    ownerCloseAccounting(owner, evidence)
                    closedByCase = true
                }
            }
            evidence.status("SELECTED_APSL_SEGMENTS_EXECUTED_NOT_WHOLE_CASE")
        } catch (failure: Throwable) {
            primary = failure
            evidence.fail(failure)
            throw failure
        } finally {
            try {
                if (!closedByCase) owner.closeOwned()
            } catch (cleanup: Throwable) {
                primary?.let { cleanup.addSuppressed(it) }
                evidence.fail(cleanup)
                throw cleanup
            } finally {
                secrets.clear()
            }
        }
    }

    private suspend fun a1LogoutPending(owner: NativeLocalFixtureOwner<ApslNativeClient>,
        secrets: ApslSecrets, evidence: ApslEvidence) {
        val client = owner.openClient()
        val session = client.signIn(secrets.employee)
        val opened = client.configureAndOpenDrawer()
        val pending = client.beginPendingClosing()
        val deviceId = client.original.material.coordinates.deviceId
        check(pending.custodianId == session.actorId && pending.drawerId == opened.drawerId &&
            deviceId == session.deviceId && pending.status == "CLOSING")
        evidence.bind("PENDING_ACTOR", pending.custodianId.value)
        evidence.bind("PENDING_DEVICE", deviceId.value)
        evidence.bind("PENDING_DRAWER", pending.drawerId.value)
        evidence.bind("PENDING_SESSION", pending.id.value)
        evidence.bind("PENDING_STATUS", pending.status)
        val master = owner.currentMaster
        val incarnation = client.original.serviceIncarnationId
        client.logout()
        check(master.mode.value == LocalMasterLifecycleMode.OPERATIONAL) {
            "A1 logout retired the original Master"
        }
        check(client.original.isCurrent()) { "A1 logout retired the original native client owner" }
        val afterLogoutRead = runCatching {
            client.readCash(LocalCashQueryV1.Session(pending.id))
        }
        check(afterLogoutRead.isFailure) { "A logged-out A1 still disclosed protected cash custody" }
        evidence.event("A1_LOGOUT_DID_NOT_STOP_MASTER_OR_CHANGE_CUSTODY",
            JSONObject().put("serviceIncarnationId", incarnation.value)
                .put("masterMode", master.mode.value.name).put("protectedRead", "DENIED_NO_SESSION"))
        owner.closeOwned()

        val reopened = owner.openClient()
        reopened.signIn(secrets.employee)
        val read = reopened.readCash(LocalCashQueryV1.Session(pending.id)) as? LocalCashReadResultV1.Session
            ?: error("The original pending CASH session was not readable after existing-only reopen")
        check(read.session.id == pending.id && read.session.custodianId == pending.custodianId &&
            read.session.drawerId == pending.drawerId && read.session.status == "CLOSING" &&
            read.session.expectedCash == pending.expectedCash) {
            "STOP/resume path lost the attributed pending actor/client/drawer original"
        }
        check(reopened.original.material.coordinates.deviceId == deviceId)
        evidence.event("PENDING_ORIGINAL_RETAINED_ACTOR_DEVICE_DRAWER_AFTER_REOPEN",
            JSONObject().put("actorId", read.session.custodianId.value)
                .put("deviceId", reopened.original.material.coordinates.deviceId.value)
                .put("drawerId", read.session.drawerId.value)
                .put("sessionId", read.session.id.value)
                .put("status", read.session.status))
        evidence.unbound("APS-BQ-11",
            "No admitted C11 second client exists in this fresh installation; A1 logout and D1 custody are observed, but the remote C11 permitted-work request is unbound")
        evidence.unbound("ALCR-UNKNOWN-ORIGINAL",
            "The frozen ALCR-UNKNOWN amount 342.00, reserved A2/B1 and M-CART arithmetic require a FOOD/retail business original; the actual pending original here is an OPEN CASH closing custody session")
        evidence.unbound("APS-BQ-04",
            "Actual OS lock/background/force-stop/process-death/device-shutdown behavior was not performed")
        evidence.unbound("APS-BQ-06",
            "No public current local-administrator stop/resume authority surface is exposed by the direct native setup API")
        owner.closeOwned()
    }

    private suspend fun ownerCloseAccounting(owner: NativeLocalFixtureOwner<ApslNativeClient>,
        evidence: ApslEvidence) {
        owner.openClient()
        val actual = owner.currentMaster
        check(actual.mode.value == LocalMasterLifecycleMode.OPERATIONAL)
        val detached = owner.detachMasterForExplicitClose()
        check(detached === actual)
        val closeResult = kotlinx.coroutines.CompletableDeferred<Boolean>()
        kotlinx.coroutines.coroutineScope {
            launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                closeResult.complete(detached.quiesceAndClose())
            }
            withTimeout(30_000L) {
                detached.mode.first { it == LocalMasterLifecycleMode.CLOSING }
            }
            evidence.bind("CLOSE_ENTERED_OBSERVED", true)
            check(closeResult.await()) { "The actual native owner close was not confirmed" }
        }
        check(detached.mode.value == LocalMasterLifecycleMode.CLOSED)
        evidence.bind("CLOSE_API_OBSERVATIONS", 1)
        evidence.bind("CLOSE_API_RESULT_FIRST", true)
        evidence.event("SINGLE_NATIVE_OWNER_CLOSE_CONFIRMED")
        // A second API observer joins the same cached completion; it is not another physical
        // native close and must not be reported as a retry or a false re-close.
        val second = detached.quiesceAndClose()
        check(second) { "The cached native close completion was not returned to a second API observer" }
        check(detached.mode.value == LocalMasterLifecycleMode.CLOSED)
        evidence.bind("CLOSE_API_OBSERVATIONS", 2)
        evidence.bind("CLOSE_API_RESULT_SECOND", second)
        evidence.event("SECOND_API_OBSERVER_JOINED_CACHED_COMPLETION_WITHOUT_NATIVE_RETRY",
            JSONObject().put("sameCachedResult", second))
        evidence.unbound("APS-STOP-PRECLOSE",
            "This direct native-owner segment does not create the frozen launch STOP record; durable STOP/refusal of recreation is bound in STOP_RESUME/STOP_CLOSE_ACCOUNTING")
        evidence.unbound("NATIVE_CLOSE_ATTEMPT_COUNT",
            "No instrumentation counts physical native close attempts; two public quiesceAndClose observers both completed true, so the lifecycle-wide native attempt count is unbound")
        evidence.unbound("NATIVE_CLOSE_CONFIRMATION_COUNT",
            "No instrumentation counts physical native close confirmations; the second public observer is cached completion, not a second native confirmation, so the native confirmation count is unbound")
        evidence.unbound("AOSF-UB1",
            "The exact public native owner confirmed-close enum/taxonomy is not exposed; the actual Boolean result and CLOSED mode are observed")
        evidence.unbound("APS-BQ-08",
            "Duplicate-process/second-writer exclusion under an actual duplicate launch was not performed")
        owner.closeOwned()
    }
}
