package com.ukkera.pos.android.independent.aosfsetupv2

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.ukkera.pos.android.AndroidLocalBranchService
import com.ukkera.pos.app.gateway.setup.LocalSetupCoordinator
import com.ukkera.pos.app.setup.LocalSetupBranchReview
import com.ukkera.pos.app.setup.LocalSetupCompanyReview
import com.ukkera.pos.app.setup.LocalSetupController
import com.ukkera.pos.app.setup.LocalSetupDefinitionReview
import com.ukkera.pos.app.setup.LocalSetupFirstAccountInput
import com.ukkera.pos.app.setup.LocalSetupNativeView
import com.ukkera.pos.app.setup.LocalSetupNewCompanyInput
import com.ukkera.pos.app.setup.LocalSetupPhase
import com.ukkera.pos.app.setup.LocalSetupReceiptView
import com.ukkera.pos.app.setup.LocalSetupView
import com.ukkera.pos.security.LocalAccountProvisioning
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

internal sealed interface AosfSetupOutcome {
    class Installed(
        val company: LocalSetupCompanyReview,
        val branch: LocalSetupBranchReview,
        val native: LocalSetupNativeView,
        val definition: LocalSetupDefinitionReview,
        val receipt: LocalSetupReceiptView,
        val account: LocalAccountProvisioning,
    ) : AosfSetupOutcome

    /** E3-004 owns the explicit continuation; this fixture must retain, never auto-resume. */
    class NativeAcknowledgementPending(val state: LocalSetupView) : AosfSetupOutcome
}

/**
 * Drives the actual AndroidLocalBranchService / AndroidLocalSetupOwner / AndroidLocalSetupExecution
 * / AndroidLocalSetupNativeServices composition. The test supplies requested setup DATA and
 * observes public controller state; it supplies no native owner, storage, verifier or authority.
 */
internal class AosfSetupServiceDriver(context: Context) {
    private val applicationContext = context.applicationContext
    private val intent = Intent(applicationContext, AndroidLocalBranchService::class.java)
        .setAction(AndroidLocalBranchService.ACTION_BIND_SETUP)
    private val connected = CompletableDeferred<AndroidLocalBranchService.SetupBinder>()
    private var connection: ServiceConnection? = null
    private var bound = false
    var controller: LocalSetupController? = null
        private set

    suspend fun attach(): LocalSetupController {
        check(connection == null)
        val current = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                val binder = service as? AndroidLocalBranchService.SetupBinder
                if (binder == null) connected.completeExceptionally(
                    IllegalStateException("Local setup binder identity changed"))
                else connected.complete(binder)
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
        bound = applicationContext.bindService(intent, current, Context.BIND_AUTO_CREATE)
        check(bound) { "Actual local setup service is unavailable" }
        val binder = withTimeout(30_000L) { connected.await() }
        val controller = withTimeout(30_000L) { binder.controllers.first { it != null } }
            ?: error("Actual local setup controller was not published")
        this.controller = controller
        controller.resumePresentation()
        return controller
    }

    private var retirementAttempted = false
    private var retirementConfirmed = false

    suspend fun retire(evidence: AosfEvidence) {
        val actual = controller ?: return
        if (retirementConfirmed) return
        check(!retirementAttempted) { "Setup-owner close was already attempted and remains unconfirmed" }
        retirementAttempted = true
        retireAosfSetup(actual, evidence)
        retirementConfirmed = true
    }

    fun close() {
        val current = connection
        val wasBound = bound
        connection = null
        bound = false
        try {
            if (current != null && wasBound) applicationContext.unbindService(current)
        } finally {
            applicationContext.stopService(intent)
        }
    }
}

internal suspend fun driveAosfSetup(
    driver: AosfSetupServiceDriver,
    plan: AosfFixturePlan,
    installerSecret: CharArray,
    headSecret: CharArray,
    responsibleSecret: CharArray,
    evidence: AosfEvidence,
): AosfSetupOutcome {
    val controller = driver.attach()
    val coordinator = controller as? LocalSetupCoordinator
        ?: error("Production setup owner composition changed; LocalSetupCoordinator is required")

    awaitPhase(controller, LocalSetupPhase.CHOOSE_ROUTE, "original fresh setup route")
    coordinator.chooseNewLocalCompany()
    awaitPhase(controller, LocalSetupPhase.NEW_COMPANY_DETAILS, "company details")
    coordinator.prepareNewLocalCompany(LocalSetupNewCompanyInput(plan.companyName, plan.installerName,
        plan.installerLogin, plan.headName, plan.headLogin, plan.protection))
    awaitPhase(controller, LocalSetupPhase.COMPANY_REVIEW, "native founding review")
    val company = controller.state.value.companyReview ?: error("The actual founding review is unavailable")
    evidence.bind("ORGANIZATION", company.organizationId.value)
    evidence.bind("COMPANY", company.companyId.value)
    evidence.bind("ROOT_FINGERPRINT", company.rootFingerprint)
    evidence.bind("ACTUAL_PROTECTION", company.protection.name)
    coordinator.completeNewLocalCompany(company.reviewId, installerSecret.copyOf(), headSecret.copyOf())
    awaitPhase(controller, LocalSetupPhase.COMPANY_RETAINED, "company retained")

    coordinator.prepareOriginalBranch(plan.branchName)
    awaitPhase(controller, LocalSetupPhase.BRANCH_REVIEW, "native branch review")
    val branch = controller.state.value.branchReview ?: error("The actual branch review is unavailable")
    evidence.bind("BRANCH", branch.branchId.value)
    evidence.bind("ALLOCATION", branch.allocationId.value)
    evidence.bind("INSTALLATION", branch.installationId.value)
    evidence.bind("DEVICE", branch.deviceId.value)
    evidence.bind("FIRST_ACTOR", branch.firstActorId.value)
    coordinator.issueOriginalBranch(branch.reviewId, installerSecret.copyOf())
    awaitPhase(controller, LocalSetupPhase.ORIGINAL_PREPARATION_RETAINED, "original preparation retained")

    coordinator.prepareOriginalNativeTarget()
    awaitPhase(controller, LocalSetupPhase.ORIGINAL_TARGET_RETAINED, "original native target")
    val native = controller.state.value.native ?: error("The actual native target review is unavailable")
    evidence.bind("NATIVE_TARGET_FINGERPRINT", native.targetFingerprint)
    evidence.bind("NATIVE_BINDING_DIGEST", native.nativeBindingDigest)
    evidence.bind("NATIVE_TARGET_PROTECTION", native.protection.name)

    coordinator.openOriginalFirstAccount()
    awaitPhase(controller, LocalSetupPhase.FIRST_ACCOUNT_DETAILS, "first account details")
    val account = plan.firstAccount(branch)
    coordinator.prepareFirstAccount(LocalSetupFirstAccountInput(account, plan.authorityEpoch))
    awaitPhase(controller, LocalSetupPhase.FIRST_ACCOUNT_REVIEW, "first account review")
    val definition = controller.state.value.definitionReview
        ?: error("The actual complete definition review is unavailable")
    evidence.bind("DEFINITION", definition.snapshot.definitionDigest.value)
    evidence.bind("SETUP_REQUEST", definition.snapshot.definition.originalRequestId.value)
    evidence.bind("AUTHORITY_EPOCH", definition.snapshot.definition.authorityEpoch.value.toString())

    coordinator.installFirstAccount(definition.reviewId, headSecret.copyOf(), responsibleSecret.copyOf())
    val installed = awaitAnyPhase(controller,
        setOf(LocalSetupPhase.INSTALLATION_RETAINED, LocalSetupPhase.INSTALLATION_ACK_PENDING,
            LocalSetupPhase.ORIGINAL_UNRESOLVED), "original installation settlement")
    return when (installed.phase) {
        LocalSetupPhase.INSTALLATION_RETAINED -> {
            val receipt = installed.receipt ?: error("Actual installation settled without a receipt projection")
            evidence.event("ORIGINAL_INSTALLATION_SETTLED", org.json.JSONObject()
                .put("receiptDigest", receipt.receiptDigest)
                .put("definitionDigest", receipt.definitionDigest)
                .put("originalRequestId", receipt.originalRequestId.value)
                .put("issuedAttemptId", receipt.issuedAttemptId.value)
                .put("selectedCaseSession", "POST_SETTLEMENT_ONLY"))
            AosfSetupOutcome.Installed(company, branch, native, definition, receipt, account)
        }
        LocalSetupPhase.INSTALLATION_ACK_PENDING -> {
            evidence.event("NATIVE_INSTALLATION_ACK_PENDING_RETAINED")
            evidence.unbound("E3-004-NATIVE-ACK-CONTINUATION",
                "AndroidLocalSetupMasterService.resumeOriginalAcknowledgement is the E3-004 explicit native continuation and is not auto-invoked")
            AosfSetupOutcome.NativeAcknowledgementPending(installed)
        }
        else -> error("Original installation did not settle: ${installed.problem}")
    }
}

internal suspend fun retireAosfSetup(controller: LocalSetupController, evidence: AosfEvidence) {
    val coordinator = controller as? LocalSetupCoordinator
        ?: error("Production setup owner composition changed; LocalSetupCoordinator is required")
    // intentional=false: AOSF-004 distinguishes owner close from an administrator STOP intent.
    withTimeout(180_000L) { coordinator.requestRetirement(intentional = false).await() }
    check(controller.state.value.phase == LocalSetupPhase.CLOSED) {
        "The production owner did not publish its confirmed close"
    }
    evidence.event("RUNTIME_OWNER_CONFIRMED_CLOSE", org.json.JSONObject()
        .put("closeConfirmedByProductionOwner", true)
        .put("intentionalStopRequested", false))
}

private suspend fun awaitPhase(controller: LocalSetupController, phase: LocalSetupPhase,
    step: String): LocalSetupView = withTimeout(180_000L) {
    controller.state.first { it.phase == phase || (it.phase == LocalSetupPhase.ORIGINAL_UNRESOLVED && it.work == null) }
}.also { view ->
    check(view.phase == phase) { "Actual setup stopped before $step: ${view.problem}" }
}

private suspend fun awaitAnyPhase(controller: LocalSetupController, phases: Set<LocalSetupPhase>,
    step: String): LocalSetupView = withTimeout(180_000L) {
    controller.state.first {
        it.phase in phases && (it.phase != LocalSetupPhase.ORIGINAL_UNRESOLVED || it.work == null)
    }
}.also { view ->
    check(view.phase in phases) { "Actual setup stopped before $step: ${view.problem}" }
}
