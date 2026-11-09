package com.ukkera.pos.android.independent.androidpersistedstopv1

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.AndroidLocalBranchService
import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.app.setup.LocalSetupProblem
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual AndroidBranchService / LocalSetupCoordinator lifecycle segments for E3-006 v2.
 * A fresh isolated nondebuggable localTesting installation and one selected `apslCase` are
 * required. No case is reported as a whole APSL PASS; unbound native/OS/authority facts remain
 * explicit. OS process death, force-stop and device shutdown are never inferred from this test.
 */
@RunWith(AndroidJUnit4::class)
class NativePersistedStopServiceTest {
    @Test
    fun selectedLifecycleSegment(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val case = checkNotNull(arguments.getString("apslCase")) {
            "Select apslCase=STOP_RESUME|LOCK_BACKGROUND_REOPEN|STOP_CLOSE_ACCOUNTING"
        }
        check(case in setOf("STOP_RESUME", "LOCK_BACKGROUND_REOPEN", "STOP_CLOSE_ACCOUNTING")) {
            "Unknown APSL service case: $case"
        }
        val protection = SelfOwnedKeyProtectionV1.valueOf(checkNotNull(arguments.getString("keyProtection")))
        val application = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        check(application.packageName == "com.ukkera.pos.local.testing" &&
            application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0)
        val evidence = ApslEvidence.claim(application, case, protection.name)
        val plan = ApslFixturePlan(protection)
        val secrets = ApslSecrets()
        val driver = ApslServiceDriver(application)
        var primary: Throwable? = null
        try {
            when (case) {
                "STOP_RESUME" -> stopResume(driver, plan, secrets, evidence)
                "LOCK_BACKGROUND_REOPEN" -> lockBackgroundReopen(driver, plan, secrets, evidence)
                "STOP_CLOSE_ACCOUNTING" -> stopCloseAccounting(driver, plan, secrets, evidence)
            }
            evidence.status("SELECTED_APSL_SEGMENTS_EXECUTED_NOT_WHOLE_CASE")
        } catch (failure: Throwable) {
            primary = failure
            evidence.fail(failure)
            throw failure
        } finally {
            try {
                if (driver.controller != null) driver.stopWithoutStopIntent()
                else driver.closeBinding()
                application.stopService(Intent(application, AndroidLocalBranchService::class.java))
            } catch (cleanup: Throwable) {
                primary?.let { cleanup.addSuppressed(it) }
                evidence.fail(cleanup)
                throw cleanup
            } finally {
                secrets.clear()
            }
        }
    }

    private suspend fun stopResume(driver: ApslServiceDriver, plan: ApslFixturePlan,
        secrets: ApslSecrets, evidence: ApslEvidence) {
        val snapshot = driver.setUp(plan, secrets, evidence)
        val firstDriver = driver
        val controller = firstDriver.controller!!
        // A wrong password is not current lifecycle authority and must not write STOP.
        val deniedStop = firstDriver.authenticatedStopAttempt(controller, plan.employeeLogin.value,
            CharArray(24) { 'z' })
        check(deniedStop.phase == LocalSetupPhase.INSTALLATION_RETAINED &&
            deniedStop.problem == LocalSetupProblem.AUTHENTICATION_REFUSED &&
            deniedStop.localClientConnected && !deniedStop.lifecycleStopped) {
            "Unauthorized STOP changed the active Master: ${deniedStop.phase}/${deniedStop.problem}"
        }
        evidence.event("UNAUTHORIZED_STOP_REFUSED_BEFORE_DURABLE_STOP")
        val closeEnteredObserved = firstDriver.authenticatedStop(controller, plan.employeeLogin.value,
            secrets.employee.copyOf())
        check(controller.state.value.phase == LocalSetupPhase.CLOSED)
        val first: Deferred<Unit> = firstDriver.retirementCompletion()
        withTimeout(180_000L) { first.await() }
        evidence.bind("STOP_ACKNOWLEDGED", true)
        evidence.bind("CLOSE_ENTERED_OBSERVED", closeEnteredObserved)
        evidence.bind("AUTHENTICATED_STOP_INGRESS", true)
        evidence.event("AUTHENTICATED_STOP_ACKNOWLEDGED_AND_OWNER_CLOSED")
        // A repeated stop request on the closed controller creates no new retirement or close.
        val repeatStop = firstDriver.authenticatedStopAttempt(controller, plan.employeeLogin.value,
            secrets.employee.copyOf())
        check(repeatStop.phase == LocalSetupPhase.CLOSED)
        check(firstDriver.retirementCompletion() === first) {
            "A repeated stop created a new retirement/close attempt"
        }
        evidence.event("REPEAT_STOP_REFUSED_BY_CLOSED_STATE_SAME_CACHED_RETIREMENT")
        firstDriver.stopWithoutStopIntent()

        val stoppedDriver = ApslServiceDriver(applicationOf(firstDriver))
        val stoppedController = stoppedDriver.attach()
        val stopped = stoppedDriver.awaitProblem(stoppedController, LocalSetupProblem.AUTOMATIC_REOPEN_STOPPED)
        check(stopped.phase == LocalSetupPhase.CHOOSE_ROUTE && !stopped.localClientConnected && stopped.receipt == null) {
            "A durable STOP was not respected by automatic launch: ${stopped.phase}/${stopped.problem}"
        }
        evidence.event("DURABLE_STOP_REFUSED_AUTOMATIC_RECREATION",
            JSONObject().put("phase", stopped.phase.name).put("problem", stopped.problem?.name))
        // A UI lock/task/background event while stopped cannot clear STOP or start a writer.
        runMainCallbacks(stoppedDriver, stoppedDriver.controller!!, evidence)
        val afterLifecycle = stoppedController.state.value
        check(afterLifecycle.problem == LocalSetupProblem.AUTOMATIC_REOPEN_STOPPED &&
            !afterLifecycle.localClientConnected && afterLifecycle.receipt == null)
        check(afterLifecycle.locked)
        stoppedController.resumePresentation()
        stoppedDriver.awaitIdle(stoppedController)
        check(stoppedController.state.value.problem == LocalSetupProblem.AUTOMATIC_REOPEN_STOPPED)
        // A plain setup open is not current lifecycle authority either.
        stoppedController.openOriginalFirstAccount()
        stoppedDriver.awaitIdle(stoppedController)
        val refused = stoppedController.state.value
        check(refused.phase == LocalSetupPhase.CHOOSE_ROUTE &&
            refused.problem == LocalSetupProblem.AUTOMATIC_REOPEN_STOPPED &&
            !refused.localClientConnected && refused.receipt == null && refused.native == null) {
            "Explicit setup open bypassed durable STOP: ${refused.phase}/${refused.problem}"
        }
        evidence.event("EXPLICIT_SETUP_OPEN_REFUSED_WITHOUT_CURRENT_ADMIN_AUTHORITY",
            JSONObject().put("phase", refused.phase.name).put("problem", refused.problem?.name)
                .put("localClientConnected", refused.localClientConnected))
        // A wrong resume password leaves the durable STOP and starts no writer.
        val deniedResume = stoppedDriver.authenticatedResumeAttempt(stoppedController, plan.employeeLogin.value,
            CharArray(24) { 'z' })
        check(deniedResume.lifecycleStopped && !deniedResume.localClientConnected &&
            deniedResume.problem == LocalSetupProblem.AUTHENTICATION_REFUSED) {
            "Unauthorized RESUME cleared STOP or opened a writer: ${deniedResume.phase}/${deniedResume.problem}"
        }
        evidence.event("UNAUTHORIZED_RESUME_REFUSED_STOP_REMAINED")
        // A separate service restart proves the record is still durably STOP before resume.
        stoppedDriver.stopWithoutStopIntent()
        val stillStoppedDriver = ApslServiceDriver(applicationOf(stoppedDriver))
        val stillStoppedController = stillStoppedDriver.attach()
        stillStoppedDriver.awaitProblem(stillStoppedController, LocalSetupProblem.AUTOMATIC_REOPEN_STOPPED)
        check(stillStoppedController.state.value.phase == LocalSetupPhase.CHOOSE_ROUTE &&
            !stillStoppedController.state.value.localClientConnected &&
            stillStoppedController.state.value.receipt == null) {
            "Durable STOP did not survive explicit-open/unauthorized-resume refusal and service restart"
        }
        evidence.event("DURABLE_STOP_REMAINED_AFTER_REFUSALS_AND_RESTART")
        // Explicit current-authority RESUME through the new pre-start installation owner.
        val resumed = stillStoppedDriver.authenticatedResume(stillStoppedController, plan.employeeLogin.value,
            secrets.employee.copyOf())
        check(resumed.phase == LocalSetupPhase.INSTALLATION_RETAINED && resumed.localClientConnected &&
            !resumed.lifecycleStopped && resumed.problem == null) {
            "Authenticated RESUME did not reopen the same original: ${resumed.phase}/${resumed.problem}"
        }
        stillStoppedDriver.requireSameReceipt(stillStoppedController, snapshot, "authenticated resume")
        evidence.bind("AUTHENTICATED_RESUME_INGRESS", true)
        evidence.event("AUTHENTICATED_RESUME_REOPENED_SAME_ORIGINAL")
        // A repeat resume after RUN exists is refused and adds no client/writer.
        val repeatResume = stillStoppedDriver.authenticatedResumeAttempt(stillStoppedController,
            plan.employeeLogin.value, secrets.employee.copyOf())
        check(repeatResume.phase == LocalSetupPhase.INSTALLATION_RETAINED &&
            repeatResume.localClientConnected && repeatResume.problem == LocalSetupProblem.ORIGINAL_ACTION_REQUIRED) {
            "Repeat RESUME changed the running original: ${repeatResume.phase}/${repeatResume.problem}"
        }
        evidence.event("REPEAT_RESUME_REFUSED_WHILE_RUNNING")
        stillStoppedDriver.stopWithoutStopIntent()
        evidence.unbound("APS-BQ-06",
            "A new MASTER_LIFECYCLE/ADMINISTER pre-start authority surface now exists and is exercised by this segment, but its APSL decision/qualification remains open because no native execution is claimed")
        evidence.unbound("APSL-003.V01-AUTHORIZED-RESUME",
            "The authenticated same-original resume segment is wired and compiled only; it is NOT RUN natively and no whole APSL-003 case is claimed")
        evidence.unbound("APSL-003.V02-UNAUTHORIZED-RESUME",
            "Only a wrong-password refusal is wired; cashier/client/generic-manager/stale/foreign/revoked-delegation variants are not run")
        evidence.unbound("APSL-003.V05-RESUME-AFTER-FAILED-CLOSE",
            "An unconfirmed/consumed close and its authorized resolution path were not exercised")
        evidence.unbound("NATIVE_CLOSE_BEFORE_RUN_READBACK",
            "The production ordering closes the pre-start lifecycle owner before retainRunning; this segment does not instrument the internal close/RUN-write sequence or physical native close counts")
        evidence.unbound("LCR-004:POST",
            "The frozen LCR R1/R2 sale arithmetic and fresh A login plus sale B are not established by this setup-only lifecycle segment")
        evidence.unbound("APS-BQ-04",
            "Actual OS force-stop, process death and device shutdown were not performed; only service/controller lifecycle was observed")
        evidence.unbound("APS-BQ-01",
            "No torn/full/read-only/I/O/permission fault cut was applied to the real launch record in this segment")
    }

    private suspend fun lockBackgroundReopen(driver: ApslServiceDriver, plan: ApslFixturePlan,
        secrets: ApslSecrets, evidence: ApslEvidence) {
        val snapshot = driver.setUp(plan, secrets, evidence)
        val controller = driver.controller!!
        val before = controller.state.value
        check(before.phase == LocalSetupPhase.INSTALLATION_RETAINED && before.localClientConnected && !before.locked)
        runMainCallbacks(driver, controller, evidence)
        driver.awaitIdle(controller)
        val after = controller.state.value
        check(after.locked && after.phase == LocalSetupPhase.INSTALLATION_RETAINED &&
            after.localClientConnected && after.problem == null) {
            "UI lock/task/background changed the original Master lifecycle or custody"
        }
        evidence.event("LOCK_BACKGROUND_TASK_CALLBACKS_DID_NOT_RETIRE_ORIGINAL_MASTER")
        controller.resumePresentation()
        driver.awaitIdle(controller)
        check(!controller.state.value.locked && controller.state.value.localClientConnected)
        driver.requireSameReceipt(controller, snapshot, "lock/background")
        controller.refreshOriginalStatus()
        driver.awaitIdle(controller)
        check(controller.state.value.localClientConnected && controller.state.value.problem == null) {
            "The still-active original Master did not pass the explicit current-state refresh"
        }
        evidence.event("STILL_ACTIVE_MASTER_REFRESHED_AFTER_UI_LOCK_BACKGROUND")
        driver.stopWithoutStopIntent()
        val reopenedDriver = ApslServiceDriver(applicationOf(driver))
        reopenedDriver.attach()
        reopenedDriver.awaitRunReopen(reopenedDriver.controller!!)
        reopenedDriver.requireSameReceipt(reopenedDriver.controller!!, snapshot, "non-intentional restart")
        evidence.event("EXISTING_ONLY_RUN_REOPEN_AFTER_NON_INTENTIONAL_SERVICE_CLOSE")
        // UI close/relaunch while an acknowledged STOP exists cannot clear STOP.
        reopenedDriver.authenticatedStop(reopenedDriver.controller!!, plan.employeeLogin.value,
            secrets.employee.copyOf())
        check(reopenedDriver.controller!!.state.value.phase == LocalSetupPhase.CLOSED)
        withTimeout(180_000L) { reopenedDriver.retirementCompletion().await() }
        evidence.bind("AUTHENTICATED_STOP_INGRESS", true)
        reopenedDriver.stopWithoutStopIntent()
        val stoppedDriver = ApslServiceDriver(applicationOf(reopenedDriver))
        val stoppedController = stoppedDriver.attach()
        stoppedDriver.awaitProblem(stoppedController, LocalSetupProblem.AUTOMATIC_REOPEN_STOPPED)
        runMainCallbacks(stoppedDriver, stoppedController, evidence)
        check(stoppedController.state.value.problem == LocalSetupProblem.AUTOMATIC_REOPEN_STOPPED &&
            stoppedController.state.value.phase == LocalSetupPhase.CHOOSE_ROUTE)
        evidence.event("UI_CLOSE_WITH_ACKNOWLEDGED_STOP_DID_NOT_CLEAR_OR_AUTO_START")
        stoppedDriver.stopWithoutStopIntent()
        evidence.unbound("APS-BQ-04",
            "Actual OS task removal/swipe-away, process death and device shutdown were not performed; the service callbacks were invoked on the actual instance only")
        evidence.unbound("APS-BQ-05",
            "Actual foreground-service notification/OEM battery/permission behavior and P1 ready-to-serve timing remain unmeasured")
        evidence.unbound("APS-BQ-11",
            "No pre-existing admitted C11 client or public ordinary-client observation surface exists in this fresh installation")
    }

    private suspend fun stopCloseAccounting(driver: ApslServiceDriver, plan: ApslFixturePlan,
        secrets: ApslSecrets, evidence: ApslEvidence) {
        driver.setUp(plan, secrets, evidence)
        val controller = driver.controller!!
        val closeEntered = driver.authenticatedStop(controller, plan.employeeLogin.value,
            secrets.employee.copyOf())
        val first = driver.retirementCompletion()
        withTimeout(180_000L) { first.await() }
        check(controller.state.value.phase == LocalSetupPhase.CLOSED)
        evidence.bind("PRE_CLOSE_STOP_ACKNOWLEDGED", true)
        evidence.bind("CLOSE_ENTERED_OBSERVED", closeEntered)
        evidence.bind("AUTHENTICATED_STOP_INGRESS", true)
        evidence.bind("SERVICE_RETIREMENT_CALLS_OBSERVED", 1)
        evidence.bind("COORDINATOR_CLOSED_OBSERVED", true)
        evidence.event("ONE_CONFIRMED_SERVICE_RETIREMENT_FROM_AUTHENTICATED_STOP")
        val second = driver.retirementCompletion()
        check(second === first) { "The already-confirmed owner accepted a second retirement/close" }
        check(controller.state.value.phase == LocalSetupPhase.CLOSED)
        evidence.bind("SERVICE_RETIREMENT_CALLS_OBSERVED", 2)
        evidence.bind("SAME_RETIREMENT_DEFERRED", second === first)
        evidence.event("SECOND_RETIREMENT_OBSERVER_JOINED_CACHED_COMPLETION")
        driver.stopWithoutStopIntent()
        val stoppedDriver = ApslServiceDriver(applicationOf(driver))
        val stoppedController = stoppedDriver.attach()
        stoppedDriver.awaitProblem(stoppedController, LocalSetupProblem.AUTOMATIC_REOPEN_STOPPED)
        check(!stoppedController.state.value.localClientConnected)
        evidence.event("STOP_REMAINED_DURABLE_NO_REPLACEMENT_WRITER")
        stoppedDriver.stopWithoutStopIntent()
        evidence.unbound("AOSF-UB1",
            "The exact public native owner acquisition/confirmed-close enum is not exposed by the setup controller; the service retirement Deferred and CLOSED state are the available observation")
        evidence.unbound("NATIVE_CLOSE_ATTEMPT_COUNT",
            "No instrumentation counts physical native close attempts; only the coordinator retirement/CLOSED observation is recorded, so the lifecycle-wide native attempt count is unbound")
        evidence.unbound("NATIVE_CLOSE_CONFIRMATION_COUNT",
            "No instrumentation counts physical native close confirmations; this segment records only the public coordinator completion and service state, so the native confirmation count is unbound")
        evidence.unbound("APS-BQ-10",
            "Direct repeated close on the same already-confirmed native owner is bound separately in OWNER_CLOSE_ACCOUNTING; this service segment observes the consumed coordinator retirement")
        evidence.unbound("APS-BQ-06",
            "Current local-administrator authority for the intentional stop is not exposed as a public setup action authorization surface")
    }

    /** Invokes the actual service's public lifecycle callbacks and the production binder lock. */
    private fun runMainCallbacks(driver: ApslServiceDriver, controller: com.ukkera.pos.app.setup.LocalSetupController,
        evidence: ApslEvidence) {
        val actualService = driver.service
        if (actualService == null) {
            evidence.unbound("SERVICE_INSTANCE_CALLBACK_DISPATCH",
                "The exact service instance could not be recovered from the same-process binder; only the binder lock was exercised")
            onMain { controller.lockPresentation() }
            return
        }
        val intent = Intent(applicationOf(driver), AndroidLocalBranchService::class.java)
            .setAction(AndroidLocalBranchService.ACTION_BIND_SETUP)
        onMain {
            controller.lockPresentation()
            actualService.onTaskRemoved(null)
            actualService.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            actualService.onUnbind(intent)
        }
        evidence.event("ACTUAL_SERVICE_ON_TASK_REMOVED_ON_TRIM_MEMORY_ON_UNBIND_DISPATCHED")
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun applicationOf(driver: ApslServiceDriver): Application =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application

    private fun ApslServiceDriver.requireSameReceipt(controller: com.ukkera.pos.app.setup.LocalSetupController,
        expected: ApslSetupSnapshot, step: String) {
        val receipt = checkNotNull(controller.state.value.receipt) { "No actual receipt after $step" }
        check(receipt.installationId == expected.installationId && receipt.branchId == expected.branchId &&
            receipt.originalRequestId == expected.originalRequestId &&
            receipt.definitionDigest == expected.definitionDigest &&
            receipt.receiptDigest == expected.receiptDigest && receipt.genesisDigest == expected.genesisDigest) {
            "The reopened original settlement changed after $step"
        }
    }
}
