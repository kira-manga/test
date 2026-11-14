package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Actual Windows/macOS original provisioner acquisition for the shared setup coordinator, the desktop
 * counterpart of `AndroidSelfOwnedEnrollmentSource`. The OS custody is observed from the running
 * Master service process; no path, key, provider or clock is supplied. Unsupported hosts refuse.
 */
public object DesktopSelfOwnedEnrollmentSource {
    /** Explicit preparation acquisition; may create the original empty namespace. */
    public suspend fun open(service: SelfOwnedInstalledIdentityTargetV2): LocalProvisionerOpenResult =
        observe(service) { open(it, SelfOwnedNativeProfileV1.LIVE, existingOriginal = false) }
    /** Existing original only; a missing namespace is unresolved, never permission to recreate. */
    public suspend fun openOriginal(service: SelfOwnedInstalledIdentityTargetV2): LocalProvisionerOpenResult =
        observe(service) { open(it, SelfOwnedNativeProfileV1.LIVE, existingOriginal = true) }
    public suspend fun openTraining(service: SelfOwnedInstalledIdentityTargetV2, slot: LocalTrainingCompositionSlot): LocalProvisionerOpenResult =
        observe(service) { open(it, SelfOwnedNativeProfileV1.training(slot), existingOriginal = false) }
    public suspend fun openTrainingOriginal(service: SelfOwnedInstalledIdentityTargetV2, slot: LocalTrainingCompositionSlot): LocalProvisionerOpenResult =
        observe(service) { open(it, SelfOwnedNativeProfileV1.training(slot), existingOriginal = true) }

    private suspend fun observe(service: SelfOwnedInstalledIdentityTargetV2,
        open: suspend (DesktopOriginalOs) -> LocalProvisionerOpenResult): LocalProvisionerOpenResult {
        val os = try { DesktopNativeOriginalOs.current(service) } catch (failure: SelfOwnedFailureV1) {
            return LocalProvisionerOpenResult.Unavailable(failure.reason)
        }
        return open(os)
    }

    internal suspend fun open(os: DesktopOriginalOs, profile: SelfOwnedNativeProfileV1,
        existingOriginal: Boolean): LocalProvisionerOpenResult {
        val lease = AndroidProvisionerProcessLease.acquire(profile)
            ?: return LocalProvisionerOpenResult.Unavailable(SelfOwnedInstallationFailureV1.BUSY)
        val platform = DesktopSelfOwnedOriginalPlatform(os)
        val work = try { AndroidOwnedInstallationWork(lease, platform.process) } catch (_: Throwable) {
            lease.releaseUnusedBeforeNativeOpen()
            return LocalProvisionerOpenResult.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
        }
        val retainedRegistry = AtomicReference<SelfOwnedOriginalRegistryStore?>(null)
        var delivered = false
        try {
            return when (val result = work.run {
                object : AndroidOwnedTask<DesktopLocalProvisionerV2> {
                    override fun execute(): DesktopLocalProvisionerV2 {
                        val environment = platform.captureEnvironment(work, profile, preparation = !existingOriginal)
                        val registry = platform.provisionerRegistry(environment, work)
                        retainedRegistry.set(registry) // Ownership precedes any partial descriptor open.
                        if (existingOriginal) registry.openExistingNative() else registry.openNative()
                        val store = registry as? SelfOwnedProvisionerRegistryStoreV2
                            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
                        return DesktopLocalProvisionerV2(environment, store, platform.provisionerKeys(environment, registry, work), work)
                    }
                    override fun discard(value: DesktopLocalProvisionerV2) { value.closeNative() }
                    override fun clearInputs() = Unit
                }
            }) {
                is AndroidOwnedResult.Value -> LocalProvisionerOpenResult.Opened(result.value).also { delivered = true }
                is AndroidOwnedResult.Unavailable -> LocalProvisionerOpenResult.Unavailable(result.reason)
            }
        } finally {
            if (!delivered) work.requestClose { retainedRegistry.get()?.closeNative() }
        }
    }
}

/**
 * UI-facing V2 provisioner over [SelfOwnedDesktopProvisionerV2] on ONE actual native lifetime.
 * Founding is the seven-key company-and-head profile only; the original grant is the V2
 * preparation ([issuesNativePreparationV2]). Every V1-only entry is a bounded refusal. Pending
 * actions are bound to this exact owner and the native action it retained; nothing is rebuilt
 * from caller data. Password buffers transfer to each call and are cleared on every outcome.
 */
internal class DesktopLocalProvisionerV2(
    private val environment: SelfOwnedOriginalEnvironment,
    private val registry: SelfOwnedProvisionerRegistryStoreV2,
    private val keys: SelfOwnedOriginalProvisionerKeys,
    private val work: AndroidOwnedInstallationWork,
) : LocalProvisioner, LocalCompanyAuthority {
    private val core = SelfOwnedDesktopProvisionerV2(environment, registry, keys, work)
    private val issuance = AndroidLocalCompanyIssuanceGate()
    private val actions = ConcurrentHashMap<DomainId, SelfOwnedDesktopActionV2>()
    private val closed = AtomicBoolean(false)

    override val companyAuthority: LocalCompanyAuthority get() = this
    override val issuesNativePreparationV2: Boolean get() = true

    // ---- Provisioner ----

    override suspend fun inspect(): LocalProvisionerInspection = when (val result = run { inspectNative() }) {
        is AndroidOwnedResult.Value -> result.value
        is AndroidOwnedResult.Unavailable -> LocalProvisionerInspection.Unavailable(result.reason)
    }

    override suspend fun prepareNewCompany(facts: SelfOwnedCompanyFactsV1): LocalProvisionerPreparation =
        LocalProvisionerPreparation.Unavailable(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)

    override suspend fun completeNewCompany(action: LocalProvisionerPendingAction, secret: CharArray): LocalProvisionerEnrollment {
        secret.fill('\u0000')
        return LocalProvisionerEnrollment.Unavailable(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    }

    override suspend fun prepareBranchWithEnrolledCompany(facts: SelfOwnedBranchFactsV1): LocalProvisionerPreparation =
        when (val result = run {
            val action = core.prepareOriginalBranch(facts.branchName, facts.enrolledRootFingerprint)
            pendingBranch(action, core.readSetup())
        }) {
            is AndroidOwnedResult.Value -> LocalProvisionerPreparation.Prepared(result.value)
            is AndroidOwnedResult.Unavailable -> LocalProvisionerPreparation.Unavailable(result.reason)
        }

    override suspend fun issueOriginalPreparation(action: LocalProvisionerPendingAction, installerLogin: String,
        secret: CharArray): LocalProvisionerGrant {
        secret.fill('\u0000')
        return LocalProvisionerGrant.Unresolved(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    }

    override suspend fun recoverOriginalPreparation(): LocalProvisionerGrant =
        LocalProvisionerGrant.Unresolved(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)

    override suspend fun issueOriginalNativePreparationV2(action: LocalProvisionerPendingAction, installerLogin: String,
        secret: CharArray): SelfOwnedNativePreparationResultV2 {
        val owned = secret.copyOf()
        try {
            secret.fill('\u0000')
            val native = nativeAction(action, LocalProvisionerActionKind.PREPARE_ORIGINAL_BRANCH)
                ?: return SelfOwnedNativePreparationResultV2.Unavailable(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            return when (val result = run { core.issueOriginalPreparation(native, installerLogin, owned) }) {
                is AndroidOwnedResult.Value -> SelfOwnedNativePreparationResultV2.Retained(result.value)
                is AndroidOwnedResult.Unavailable -> SelfOwnedNativePreparationResultV2.Unavailable(result.reason)
            }
        } finally { owned.fill('\u0000') }
    }

    override suspend fun recoverOriginalNativePreparationV2(): SelfOwnedNativePreparationResultV2 =
        when (val result = run { core.recoverOriginalPreparation() }) {
            is AndroidOwnedResult.Value -> SelfOwnedNativePreparationResultV2.Retained(result.value)
            is AndroidOwnedResult.Unavailable -> SelfOwnedNativePreparationResultV2.Unavailable(result.reason)
        }

    override suspend fun withdrawNewIssuance(): LocalProvisionerWithdrawal {
        core.denyNewIssuance()
        return when (val result = run { core.withdrawNewIssuance() }) {
            is AndroidOwnedResult.Value -> LocalProvisionerWithdrawal.Retained
            is AndroidOwnedResult.Unavailable -> LocalProvisionerWithdrawal.Unavailable(result.reason)
        }
    }

    override fun denyNewIssuance() { core.denyNewIssuance() }

    override suspend fun close() {
        core.denyNewIssuance(); issuance.deny(null)
        work.requestClose { closeNative() }.await()
    }

    fun closeNative() {
        core.close(); issuance.deny(null)
        if (closed.compareAndSet(false, true)) registry.closeNative()
    }

    // ---- Company authority (founding and read-only views) ----

    override suspend fun inspectCompanyAuthority(): LocalCompanyAuthorityResult<LocalCompanyAuthorityView> =
        companyRun { installationView().inspectCompanyAuthority() }

    override suspend fun readOriginalCompanyAndHeadAction(): LocalCompanyAuthorityResult<LocalCompanyFoundingAction> =
        companyRun { originalFounding(resume = false) }

    override suspend fun resumeOriginalCompanyAndHead(): LocalCompanyAuthorityResult<LocalCompanyFoundingAction> =
        companyRun { originalFounding(resume = true) }

    override suspend fun readOriginalInstallationHumanReference(): LocalCompanyAuthorityResult<LocalInstallationHumanReferenceV2> =
        companyRun { installationView().readOriginalInstallationHumanReference() }

    override suspend fun prepareNewCompanyAndHead(facts: LocalCompanyFoundingFactsV1): LocalCompanyAuthorityResult<LocalCompanyFoundingAction> =
        companyRun {
            val action = core.prepareNewCompanyAndHead(facts)
            val read = core.readSetup()
            founding(read.company ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN), action, retained = false,
                action.rootFingerprint ?: read.rootFingerprint)
        }

    override suspend fun completeNewCompanyAndHead(action: LocalCompanyFoundingAction, installerSecret: CharArray,
        headSecret: CharArray): LocalCompanyAuthorityResult<LocalCompanyAuthorityView> {
        val installer = installerSecret.copyOf(); val head = headSecret.copyOf()
        try {
            installerSecret.fill('\u0000'); headSecret.fill('\u0000')
            val native = actions[action.originalId]?.takeIf {
                action.owner === this && it.kind == SelfOwnedDesktopActionKindV2.FOUND_NEW_COMPANY_AND_HEAD && !action.enrollmentRetained
            } ?: return LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.ORIGINAL_RESULT_REQUIRED)
            return companyRun {
                core.completeNewCompanyAndHead(native, installer, head)
                installationView().inspectCompanyAuthority()
            }
        } finally { installer.fill('\u0000'); head.fill('\u0000') }
    }

    override suspend fun preparePolicyPublication(proposal: LocalCompanyPolicyProposal): LocalCompanyAuthorityResult<LocalCompanyPolicyAction> =
        unsupported()
    override suspend fun approvePolicyPublication(action: LocalCompanyPolicyAction, headLogin: String,
        secret: CharArray): LocalCompanyAuthorityResult<LocalCompanyPolicyPublication> { secret.fill('\u0000'); return unsupported() }
    override suspend fun recoverPolicyPublication(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalCompanyPolicyPublication> =
        unsupported()
    // Original installation approval belongs to the installation owner's company issuer, not this provisioner.
    override suspend fun prepareOriginalInstallationDefinition(proposal: OriginalInstallationProposal):
        LocalCompanyAuthorityResult<LocalCompanyInstallationAction> = unsupported()
    override suspend fun approveOriginalInstallationDefinition(action: LocalCompanyInstallationAction, headLogin: String,
        secret: CharArray): LocalCompanyAuthorityResult<LocalCompanyInstallationApproval> { secret.fill('\u0000'); return unsupported() }
    override suspend fun readOriginalInstallationAttempt(): LocalCompanyAuthorityResult<LocalCompanyInstallationApproval> = unsupported()
    override suspend fun acknowledgeOriginalInstallationReceipt(completion: OriginalInstallationCompletion):
        LocalCompanyAuthorityResult<LocalInstallationSignedAcknowledgementV2> = unsupported()
    override suspend fun retireOriginalInstallationAttempt(retirement: OriginalAttemptRetirement): LocalCompanyAuthorityResult<Unit> =
        unsupported()
    override suspend fun withdrawHeadIssuance(purpose: LocalCompanyHeadPurposeV1?): LocalCompanyAuthorityResult<Unit> {
        issuance.deny(purpose)
        return unsupported()
    }
    override fun denyHeadIssuance() { issuance.deny(null) }

    // ---- Mapping ----

    private fun inspectNative(): LocalProvisionerInspection {
        val read = core.readSetup()
        val company = read.company ?: return if (read.original == null) LocalProvisionerInspection.Unenrolled
            // A four-key original-only registry has no head; the desktop setup route never founds one.
            else LocalProvisionerInspection.Unavailable(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        val original = company.original
        val state = read.state
        if (state == null) {
            if (original.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            return LocalProvisionerInspection.FoundingPending(LocalProvisionerPendingAction(this, original.actionId,
                LocalProvisionerActionKind.FOUND_NEW_COMPANY, original.facts.companyName, original.facts.responsiblePersonName,
                original.facts.installerLogin, null, read.rootFingerprint, original.facts.protection, original.organizationId,
                original.companyId, null, original.installedTarget, 0, 0L))
        }
        val fingerprint = read.rootFingerprint ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val registryState = state.original
        val allocation = registryState.allocations.singleOrNull()
        val branch = allocation?.let { pendingBranch(core.prepareOriginalBranch(it.body.branchName, fingerprint), read) }
        val view = LocalProvisionerCompanyView(original.rootId, original.organizationId, original.companyId,
            original.facts.companyName, original.facts.responsiblePersonName, original.facts.installerLogin, fingerprint,
            original.facts.protection, registryState.issuanceEnabled && registryState.installers.single().enabled)
        return LocalProvisionerInspection.Enrolled(view, branch, allocation?.phase == LocalProvisionerAllocationPhaseV1.GRANT_RETAINED)
    }

    private fun pendingBranch(action: SelfOwnedDesktopActionV2, read: SelfOwnedDesktopProvisionerV2.SetupReadV2): LocalProvisionerPendingAction {
        val original = read.original ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val allocation = read.state?.original?.allocations?.singleOrNull()
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (allocation.body.coordinates.allocationId != action.allocationId) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        actions[action.originalId] = action
        return LocalProvisionerPendingAction(this, action.originalId, LocalProvisionerActionKind.PREPARE_ORIGINAL_BRANCH,
            original.facts.companyName, original.facts.responsiblePersonName, original.facts.installerLogin,
            action.branchName, action.rootFingerprint, original.facts.protection, original.organizationId, original.companyId,
            allocation.body.coordinates, original.installedTarget, action.boot.ordinal, action.expiresElapsedMillis)
    }

    private fun originalFounding(resume: Boolean): LocalCompanyFoundingAction {
        val read = core.readSetup()
        val company = read.company ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        if (read.state != null) return founding(company, null, retained = true, read.rootFingerprint)
        if (!resume && !company.complete()) localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_KEY_PREPARATION_REQUIRED)
        // The exact retained facts resume the same original key intents; no new IDs or facts.
        val action = core.prepareNewCompanyAndHead(LocalCompanyFoundingFactsV1(company.original.facts, company.headName,
            company.headLogin, company.lifetime, LocalCompanyHeadPurposeV1.entries.toSet()))
        return founding(core.readSetup().company ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN), action,
            retained = false, action.rootFingerprint)
    }

    private fun founding(company: LocalCompanyAuthorityBootstrapV2, action: SelfOwnedDesktopActionV2?, retained: Boolean,
        fingerprint: String?): LocalCompanyFoundingAction {
        val original = company.original
        if (action != null) {
            if (action.originalId != original.actionId) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            actions[action.originalId] = action
        }
        return LocalCompanyFoundingAction(this, original.actionId, original.facts.companyName, original.facts.responsiblePersonName,
            original.facts.installerLogin, company.headName, company.headLogin, company.lifetime,
            fingerprint ?: localCompanyRefuse(LocalCompanyAuthorityFailure.ORIGINAL_KEY_PREPARATION_REQUIRED),
            original.organizationId, original.companyId, company.headAuthorityId, company.headEnrollmentId,
            original.facts.protection, retained, action?.boot?.ordinal ?: 0, action?.expiresElapsedMillis ?: 0L)
    }

    private fun nativeAction(action: LocalProvisionerPendingAction, kind: LocalProvisionerActionKind): SelfOwnedDesktopActionV2? =
        actions[action.originalId]?.takeIf { action.owner === this && action.kind == kind }

    private fun installationView() = SelfOwnedDesktopCompanyInstallationV2(environment, registry, keys, work, null, issuance)

    private suspend fun <T> run(body: () -> T): AndroidOwnedResult<T> = work.run {
        object : AndroidOwnedTask<T> {
            override fun execute(): T { requireOpen(); return body() }
            override fun discard(value: T) = Unit // Immutable data; authority stays in native custody.
            override fun clearInputs() = Unit
        }
    }

    private suspend fun <T> companyRun(body: () -> T): LocalCompanyAuthorityResult<T> = when (val result = run {
        try { LocalCompanyAuthorityResult.Value(body()) }
        catch (refusal: LocalCompanyAuthorityRefusal) { LocalCompanyAuthorityResult.Unavailable(refusal.reason) }
    }) {
        is AndroidOwnedResult.Value -> result.value
        is AndroidOwnedResult.Unavailable -> LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE, result.reason)
    }

    private fun <T> unsupported(): LocalCompanyAuthorityResult<T> = LocalCompanyAuthorityResult.Unavailable(
        LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE, SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)

    private fun requireOpen() { if (closed.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED) }

    override fun toString(): String = "DesktopLocalProvisionerV2([REDACTED])"
}
