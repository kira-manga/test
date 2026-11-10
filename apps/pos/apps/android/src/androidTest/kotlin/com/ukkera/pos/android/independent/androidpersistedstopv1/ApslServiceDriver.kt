package com.ukkera.pos.android.independent.androidpersistedstopv1

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.platform.app.InstrumentationRegistry
import com.ukkera.pos.android.AndroidLocalBranchService
import com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator
import com.ukkera.pos.app.setup.LocalSetupBranchReview
import com.ukkera.pos.app.setup.LocalSetupController
import com.ukkera.pos.app.setup.LocalSetupDefinitionReview
import com.ukkera.pos.app.setup.LocalSetupFirstAccountInput
import com.ukkera.pos.app.setup.LocalSetupNewCompanyInput
import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.app.setup.LocalSetupProblem
import com.ukkera.pos.app.setup.LocalSetupView
import com.ukkera.pos.app.setup.LocalSetupWork
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Drives the actual AndroidLocalBranchService / AndroidLocalSetupOwner / LocalSetupCoordinator.
 * It supplies requested synthetic setup DATA and observes public controller/service state; it
 * supplies no native owner, storage, verifier, STOP record or authority.
 */
internal class ApslServiceDriver(context: Context) {
    private val applicationContext = context.applicationContext
    private val bindIntent = Intent(applicationContext, AndroidLocalBranchService::class.java)
        .setAction(AndroidLocalBranchService.ACTION_BIND_SETUP)
    private val connected = CompletableDeferred<AndroidLocalBranchService.SetupBinder>()
    private var connection: ServiceConnection? = null
    private var bound = false
    var controller: LocalSetupController? = null
        private set
    var service: AndroidLocalBranchService? = null
        private set

    suspend fun attach(): LocalSetupController {
        check(connection == null && controller == null)
        val current = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val actual = binder as? AndroidLocalBranchService.SetupBinder
                if (actual == null) connected.completeExceptionally(
                    IllegalStateException("Local setup binder identity changed"))
                else connected.complete(actual)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                connected.completeExceptionally(IllegalStateException("Local setup service disconnected"))
            }

            override fun onBindingDied(name: ComponentName) {
                connected.completeExceptionally(IllegalStateException("Local setup binding died"))
            }

            override fun onNullBinding(name: ComponentName) {
                connected.completeExceptionally(IllegalStateException("Local setup service returned no binder"))
            }
        }
        connection = current
        bound = applicationContext.bindService(bindIntent, current, Context.BIND_AUTO_CREATE)
        check(bound) { "Actual local setup service is unavailable" }
        val binder = withTimeout(30_000L) { connected.await() }
        val published = withTimeout(30_000L) { binder.controllers.first { it != null } }
            ?: error("Actual local setup controller was not published")
        check(published.state.value.phase != LocalSetupPhase.CLOSED) {
            "Bound to a retired service owner; a new setup generation was not created"
        }
        controller = published
        service = serviceFrom(binder)
        // The visible application's startup trigger is explicit; binding alone never infers
        // authority or attempts an existing-only reopen. This avoids a background startService
        // restriction becoming a lifecycle result.
        (published as? LocalSetupCoordinator)?.reopenRetainedMaster()
        return published
    }

    /** The setup binder is a same-process inner binder; this recovers the exact service instance
     * for observation-only direct invocation of its public OS lifecycle callbacks. */
    private fun serviceFrom(binder: AndroidLocalBranchService.SetupBinder): AndroidLocalBranchService? =
        runCatching {
            binder.javaClass.getDeclaredField("this\$0").apply { isAccessible = true }
                .get(binder) as? AndroidLocalBranchService
        }.getOrNull()

    suspend fun setUp(plan: ApslFixturePlan, secrets: ApslSecrets, evidence: ApslEvidence): ApslSetupSnapshot {
        val controller = attach()
        val coordinator = controller as? LocalSetupCoordinator
            ?: error("Production setup owner composition changed; LocalSetupCoordinator is required")
        awaitIdle(controller)
        check(controller.state.value.phase == LocalSetupPhase.CHOOSE_ROUTE &&
            controller.state.value.receipt == null && !controller.state.value.localClientConnected &&
            controller.state.value.problem == null) {
            "APSL requires a fresh isolated installation; a retained setup/launch state is visible: " +
                "${controller.state.value.phase}/${controller.state.value.problem}"
        }
        coordinator.chooseNewLocalCompany()
        awaitPhase(controller, LocalSetupPhase.NEW_COMPANY_DETAILS, "company details")
        coordinator.prepareNewLocalCompany(LocalSetupNewCompanyInput(plan.companyName, plan.installerName,
            plan.installerLogin, plan.headName, plan.headLogin, plan.protection))
        awaitPhase(controller, LocalSetupPhase.COMPANY_REVIEW, "native founding review")
        val company = controller.state.value.companyReview ?: error("Actual founding review is unavailable")
        evidence.bind("ORGANIZATION", company.organizationId.value)
        evidence.bind("COMPANY", company.companyId.value)
        evidence.bind("ROOT_FINGERPRINT", company.rootFingerprint)
        coordinator.completeNewLocalCompany(company.reviewId, secrets.installer.copyOf(), secrets.head.copyOf())
        awaitPhase(controller, LocalSetupPhase.COMPANY_RETAINED, "company retained")
        coordinator.prepareOriginalBranch(plan.branchName)
        awaitPhase(controller, LocalSetupPhase.BRANCH_REVIEW, "native branch review")
        val branch = controller.state.value.branchReview ?: error("Actual branch review is unavailable")
        coordinator.issueOriginalBranch(branch.reviewId, secrets.installer.copyOf())
        awaitPhase(controller, LocalSetupPhase.ORIGINAL_PREPARATION_RETAINED, "original preparation retained")
        coordinator.prepareOriginalNativeTarget()
        awaitPhase(controller, LocalSetupPhase.ORIGINAL_TARGET_RETAINED, "original native target")
        coordinator.openOriginalFirstAccount()
        awaitPhase(controller, LocalSetupPhase.FIRST_ACCOUNT_DETAILS, "first account details")
        val account: LocalAccountProvisioning = plan.firstAccount(branch)
        coordinator.prepareFirstAccount(LocalSetupFirstAccountInput(account, plan.authorityEpoch))
        awaitPhase(controller, LocalSetupPhase.FIRST_ACCOUNT_REVIEW, "first account review")
        val definition: LocalSetupDefinitionReview = controller.state.value.definitionReview
            ?: error("Actual complete definition review is unavailable")
        coordinator.installFirstAccount(definition.reviewId, secrets.head.copyOf(), secrets.employee.copyOf())
        val settled = awaitAnyPhase(controller,
            setOf(LocalSetupPhase.INSTALLATION_RETAINED, LocalSetupPhase.INSTALLATION_ACK_PENDING,
                LocalSetupPhase.ORIGINAL_UNRESOLVED),
            "original installation settlement")
        if (settled.phase == LocalSetupPhase.ORIGINAL_UNRESOLVED) {
            error("Original installation did not settle: ${settled.problem}")
        }
        if (settled.phase == LocalSetupPhase.INSTALLATION_ACK_PENDING) {
            coordinator.resumeOriginalInstallationAcknowledgement()
            awaitPhase(controller, LocalSetupPhase.INSTALLATION_RETAINED, "native installation ACK")
        }
        val receipt = controller.state.value.receipt ?: error("Actual installation receipt is unavailable")
        evidence.bind("BRANCH", branch.branchId.value)
        evidence.bind("INSTALLATION", receipt.installationId.value)
        evidence.bind("DEVICE", branch.deviceId.value)
        evidence.bind("FIRST_ACTOR", branch.firstActorId.value)
        evidence.bind("SETUP_REQUEST", receipt.originalRequestId.value)
        evidence.bind("DEFINITION", receipt.definitionDigest)
        evidence.bind("RECEIPT", receipt.receiptDigest)
        evidence.bind("GENESIS", receipt.genesisDigest)
        coordinator.enrollOriginalLocalClient()
        awaitLocalClientConnected(controller)
        return snapshot(controller, branch)
    }

    suspend fun awaitPhase(controller: LocalSetupController, phase: LocalSetupPhase, step: String): LocalSetupView =
        withTimeout(180_000L) {
            controller.state.first {
                it.phase == phase && it.work == null ||
                    (it.phase == LocalSetupPhase.ORIGINAL_UNRESOLVED && it.work == null)
            }
        }.also { view ->
            check(view.phase == phase) { "Actual setup stopped before $step: ${view.problem}" }
        }

    suspend fun awaitAnyPhase(controller: LocalSetupController, phases: Set<LocalSetupPhase>,
        step: String): LocalSetupView = withTimeout(180_000L) {
        controller.state.first { it.phase in phases && it.work == null }
    }.also { view ->
        check(view.phase in phases) { "Actual setup stopped before $step: ${view.problem}" }
    }

    suspend fun awaitIdle(controller: LocalSetupController) {
        withTimeout(60_000L) { controller.state.first { it.work == null } }
    }

    suspend fun awaitLocalClientConnected(controller: LocalSetupController) {
        withTimeout(180_000L) {
            // Publication precedes the enrollment operation's finally block. Wait for that
            // operation to settle before the next explicit action can be admitted.
            controller.state.first { (it.localClientConnected && it.work == null) ||
                it.phase == LocalSetupPhase.CLOSED || (it.problem != null && it.work == null) }
        }.also { view ->
            check(view.localClientConnected) { "Actual original client was not published: ${view.problem}" }
        }
    }

    suspend fun awaitProblem(controller: LocalSetupController, problem: LocalSetupProblem): LocalSetupView =
        withTimeout(60_000L) {
            controller.state.first { it.problem == problem && it.work == null }
        }

    suspend fun awaitRunReopen(controller: LocalSetupController): LocalSetupView {
        val view = withTimeout(180_000L) {
            controller.state.first { it.localClientConnected || it.phase == LocalSetupPhase.CLOSED ||
                (it.problem != null && it.work == null) }
        }
        check(view.localClientConnected) { "Existing-only RUN reopen did not publish its original client: ${view.problem}" }
        awaitIdle(controller)
        controller.resumePresentation()
        return withTimeout(30_000L) { controller.state.first { !it.locked } }
    }

    /**
     * Explicit current-authority STOP through the production ingress. The old direct
     * requestRetirement(intentional = true) call is deliberately not used to create STOP.
     * Returns whether the transient CLOSING projection was observed; native close counts stay
     * unbound because this test does not instrument physical close attempts/confirmations.
     */
    suspend fun authenticatedStop(controller: LocalSetupController, login: String, password: CharArray): Boolean {
        var closeEntered = false
        val watcher = CoroutineScope(Dispatchers.Default).launch {
            controller.state.collect { view ->
                if (view.phase == LocalSetupPhase.CLOSING ||
                    view.work == LocalSetupWork.CLOSING_NATIVE_OWNER) closeEntered = true
            }
        }
        try {
            controller.authenticateAndStopLocalMaster(login, password.copyOf())
            val settled = withTimeout(180_000L) {
                controller.state.first { it.phase == LocalSetupPhase.CLOSED || (it.work == null && it.problem != null) }
            }
            check(settled.phase == LocalSetupPhase.CLOSED) {
                "Authenticated STOP did not reach CLOSED: ${settled.phase}/${settled.problem}"
            }
        } finally { watcher.cancelAndJoin() }
        return closeEntered
    }

    /** One authenticated STOP attempt; a denial returns the still-operational view. */
    suspend fun authenticatedStopAttempt(controller: LocalSetupController, login: String,
        password: CharArray): LocalSetupView {
        controller.authenticateAndStopLocalMaster(login, password.copyOf())
        awaitIdle(controller)
        return controller.state.value
    }

    /** Explicit authenticated same-original RESUME; only a published original client succeeds. */
    suspend fun authenticatedResume(controller: LocalSetupController, login: String,
        password: CharArray): LocalSetupView {
        controller.authenticateAndResumeLocalMaster(login, password.copyOf())
        val settled = withTimeout(180_000L) {
            controller.state.first { it.localClientConnected || (it.work == null && it.problem != null) }
        }
        check(settled.localClientConnected) { "Authenticated RESUME did not reopen the original: " +
            "${settled.phase}/${settled.problem}" }
        awaitIdle(controller)
        return controller.state.value
    }

    /** One authenticated RESUME attempt; a denial leaves the durable STOP posture visible. */
    suspend fun authenticatedResumeAttempt(controller: LocalSetupController, login: String,
        password: CharArray): LocalSetupView {
        controller.authenticateAndResumeLocalMaster(login, password.copyOf())
        awaitIdle(controller)
        return controller.state.value
    }

    /** Join the one cached coordinator retirement without creating a new STOP intent. */
    fun retirementCompletion(): Deferred<Unit> {
        val coordinator = controller as? LocalSetupCoordinator
            ?: error("Production setup owner composition changed; LocalSetupCoordinator is required")
        return coordinator.requestRetirement(intentional = false)
    }

    /** Stops the visible service owner without a STOP intent; its actual close is non-intentional. */
    suspend fun stopWithoutStopIntent() {
        val current = controller ?: error("No actual controller is attached")
        val active = connection
        val wasBound = bound
        connection = null
        bound = false
        if (active != null && wasBound) {
            try { applicationContext.unbindService(active) } catch (_: Exception) { /* still stop */ }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        withTimeout(180_000L) { current.state.first { it.phase == LocalSetupPhase.CLOSED } }
        service = null
        controller = null
    }

    fun closeBinding() {
        val active = connection
        val wasBound = bound
        connection = null
        bound = false
        if (active != null && wasBound) {
            try { applicationContext.unbindService(active) } catch (_: Exception) { /* observation only */ }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        controller = null
        service = null
    }

    fun snapshot(controller: LocalSetupController, branch: LocalSetupBranchReview): ApslSetupSnapshot {
        val receipt = requireNotNull(controller.state.value.receipt)
        return ApslSetupSnapshot(branch.organizationId, branch.companyId, branch.branchId,
            receipt.installationId, branch.deviceId, branch.firstActorId, receipt.originalRequestId,
            receipt.definitionDigest, receipt.receiptDigest, receipt.genesisDigest)
    }

    fun event(evidence: ApslEvidence, step: String, facts: JSONObject = JSONObject()) =
        evidence.event(step, facts)
}

internal data class ApslSetupSnapshot(
    val organizationId: com.ukkera.pos.types.DomainId,
    val companyId: com.ukkera.pos.types.DomainId,
    val branchId: com.ukkera.pos.types.DomainId,
    val installationId: com.ukkera.pos.types.DomainId,
    val deviceId: com.ukkera.pos.types.DomainId,
    val firstActorId: com.ukkera.pos.types.DomainId,
    val originalRequestId: com.ukkera.pos.types.DomainId,
    val definitionDigest: String,
    val receiptDigest: String,
    val genesisDigest: String,
) {
    fun requireSame(other: ApslSetupSnapshot) {
        check(organizationId == other.organizationId && companyId == other.companyId &&
            branchId == other.branchId && installationId == other.installationId &&
            deviceId == other.deviceId && firstActorId == other.firstActorId &&
            originalRequestId == other.originalRequestId && definitionDigest == other.definitionDigest &&
            receiptDigest == other.receiptDigest && genesisDigest == other.genesisDigest) {
            "Explicit resume did not reopen the same original company/branch/device/storage settlement"
        }
    }
}
