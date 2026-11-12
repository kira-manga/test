package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import java.util.concurrent.atomic.AtomicBoolean

/** Actual native owner behind the UI-facing interface; no injected accepting trust boundary. */
internal class AndroidLocalProvisioner(
    private val environment: SelfOwnedOriginalEnvironment,
    private val registry: LocalProvisionerRegistryStore,
    private val keys: LocalProvisionerNativeKeys,
    private val work: AndroidOwnedInstallationWork,
) : LocalProvisioner, LocalCompanyAuthority {
    private val actionOwner = Any()
    private val denied = AtomicBoolean(false)
    private val actuallyClosed = AtomicBoolean(false)
    private val authentication = AndroidLocalInstallerAuthentication(environment, registry, keys, work)
    private val company = AndroidLocalCompanyAuthority(environment, registry, keys, work)
    override val companyAuthority: LocalCompanyAuthority get() = this

    override suspend fun inspectCompanyAuthority(): LocalCompanyAuthorityResult<LocalCompanyAuthorityView> =
        companyRun { company.inspectCompanyAuthority() }

    override suspend fun readOriginalCompanyAndHeadAction(): LocalCompanyAuthorityResult<LocalCompanyFoundingAction> =
        companyRun { company.readOriginalCompanyAndHeadAction() }

    override suspend fun resumeOriginalCompanyAndHead(): LocalCompanyAuthorityResult<LocalCompanyFoundingAction> =
        companyRun { company.resumeOriginalCompanyAndHead() }

    override suspend fun readOriginalInstallationHumanReference(): LocalCompanyAuthorityResult<LocalInstallationHumanReferenceV2> =
        companyRun { company.readOriginalInstallationHumanReference() }

    override suspend fun prepareNewCompanyAndHead(facts: LocalCompanyFoundingFactsV1): LocalCompanyAuthorityResult<LocalCompanyFoundingAction> =
        companyRun { company.prepareNewCompanyAndHead(facts) }

    override suspend fun completeNewCompanyAndHead(action: LocalCompanyFoundingAction, installerSecret: CharArray,
        headSecret: CharArray): LocalCompanyAuthorityResult<LocalCompanyAuthorityView> {
        var prepared: AndroidLocalCompanyAuthority.PreparedFoundingCredentials? = null
        try {
            // KDF/HMAC finishes and its owned password inputs clear before ordinary admission
            // of final signing/persistence/readback. Each operation keeps its original budget.
            val preparation = companyOutcome(work.run { companyFoundingPasswordTask(action, installerSecret, headSecret) })
            val credentials = when (preparation) {
                is LocalCompanyAuthorityResult.Value -> preparation.value
                is LocalCompanyAuthorityResult.Unavailable -> return preparation
            }
            prepared = credentials
            return companyOutcome(work.run { companyFoundingCompletionTask(credentials) })
        } finally {
            installerSecret.fill('\u0000'); headSecret.fill('\u0000')
            prepared?.let { company.discardFoundingCredentials(it) }
        }
    }

    override suspend fun preparePolicyPublication(proposal: LocalCompanyPolicyProposal): LocalCompanyAuthorityResult<LocalCompanyPolicyAction> =
        companyRun { company.preparePolicyPublication(proposal) }

    override suspend fun approvePolicyPublication(action: LocalCompanyPolicyAction, headLogin: String,
        secret: CharArray): LocalCompanyAuthorityResult<LocalCompanyPolicyPublication> {
        try {
            return companyOutcome(work.run { passwordTask(secret) { owned ->
                companyValue { company.approvePolicyPublication(action, headLogin, owned) }
            } })
        } finally { secret.fill('\u0000') }
    }

    override suspend fun recoverPolicyPublication(originalActionId: DomainId): LocalCompanyAuthorityResult<LocalCompanyPolicyPublication> =
        companyRun { company.recoverPolicyPublication(originalActionId) }

    override suspend fun prepareOriginalInstallationDefinition(proposal: OriginalInstallationProposal):
        LocalCompanyAuthorityResult<LocalCompanyInstallationAction> = companyRun { company.prepareOriginalInstallationDefinition(proposal) }

    override suspend fun approveOriginalInstallationDefinition(action: LocalCompanyInstallationAction, headLogin: String,
        secret: CharArray): LocalCompanyAuthorityResult<LocalCompanyInstallationApproval> {
        try {
            return companyOutcome(work.run { passwordTask(secret) { owned ->
                companyValue { company.approveOriginalInstallationDefinition(action, headLogin, owned) }
            } })
        } finally { secret.fill('\u0000') }
    }

    override suspend fun readOriginalInstallationAttempt(): LocalCompanyAuthorityResult<LocalCompanyInstallationApproval> =
        companyRun { company.readOriginalInstallationAttempt() }

    override suspend fun acknowledgeOriginalInstallationReceipt(completion: OriginalInstallationCompletion):
        LocalCompanyAuthorityResult<LocalInstallationSignedAcknowledgementV2> =
        companyRun { company.acknowledgeOriginalInstallationReceipt(completion) }

    override suspend fun retireOriginalInstallationAttempt(retirement: OriginalAttemptRetirement): LocalCompanyAuthorityResult<Unit> =
        companyRun { company.retireOriginalInstallationAttempt(retirement) }

    override suspend fun withdrawHeadIssuance(purpose: LocalCompanyHeadPurposeV1?): LocalCompanyAuthorityResult<Unit> {
        company.denyPurpose(purpose) // Immediate denial first; not yet a durable acknowledgement.
        return companyRun { company.withdrawHeadIssuance(purpose) }
    }

    override fun denyHeadIssuance() { company.denyHeadIssuance() }

    private suspend fun <T> companyRun(body: () -> T): LocalCompanyAuthorityResult<T> =
        companyOutcome(work.run { task { companyValue(body) } })

    private fun <T> companyValue(body: () -> T): LocalCompanyAuthorityResult<T> = try {
        LocalCompanyAuthorityResult.Value(body())
    } catch (refusal: LocalCompanyAuthorityRefusal) { LocalCompanyAuthorityResult.Unavailable(refusal.reason) }

    private fun <T> companyOutcome(result: AndroidOwnedResult<LocalCompanyAuthorityResult<T>>): LocalCompanyAuthorityResult<T> = when (result) {
        is AndroidOwnedResult.Value -> result.value
        is AndroidOwnedResult.Unavailable -> LocalCompanyAuthorityResult.Unavailable(LocalCompanyAuthorityFailure.NATIVE_UNAVAILABLE, result.reason)
    }

    /** Admission precedes BOTH copies. Owned buffers clear; JCA-internal copies cannot be promised wiped. */
    private fun companyFoundingPasswordTask(action: LocalCompanyFoundingAction,
        installerSecret: CharArray, headSecret: CharArray):
        AndroidOwnedTask<LocalCompanyAuthorityResult<AndroidLocalCompanyAuthority.PreparedFoundingCredentials>> {
        if (installerSecret === headSecret ||
            installerSecret.size !in SelfOwnedInstallationProfileV1.MINIMUM_PASSWORD_CHARS..SelfOwnedInstallationProfileV1.MAXIMUM_PASSWORD_CHARS ||
            headSecret.size !in SelfOwnedInstallationProfileV1.MINIMUM_PASSWORD_CHARS..SelfOwnedInstallationProfileV1.MAXIMUM_PASSWORD_CHARS) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        }
        val installer = installerSecret.copyOf()
        val head = try { headSecret.copyOf() } catch (failure: Throwable) { installer.fill('\u0000'); throw failure }
        installerSecret.fill('\u0000'); headSecret.fill('\u0000')
        if (!selfOwnedPasswordInput(installer) || !selfOwnedPasswordInput(head)) {
            installer.fill('\u0000'); head.fill('\u0000'); selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        }
        return object : AndroidOwnedTask<LocalCompanyAuthorityResult<AndroidLocalCompanyAuthority.PreparedFoundingCredentials>> {
            override fun execute(): LocalCompanyAuthorityResult<AndroidLocalCompanyAuthority.PreparedFoundingCredentials> {
                requireOwned()
                val result = companyValue { company.prepareFoundingCredentials(action, installer, head) }
                return try { requireOwned(); result }
                catch (failure: Throwable) { discard(result); throw failure }
            }
            override fun discard(value: LocalCompanyAuthorityResult<AndroidLocalCompanyAuthority.PreparedFoundingCredentials>) {
                if (value is LocalCompanyAuthorityResult.Value) company.discardFoundingCredentials(value.value)
            }
            override fun clearInputs() { installer.fill('\u0000'); head.fill('\u0000') }
        }
    }

    private fun companyFoundingCompletionTask(prepared: AndroidLocalCompanyAuthority.PreparedFoundingCredentials):
        AndroidOwnedTask<LocalCompanyAuthorityResult<LocalCompanyAuthorityView>> {
        val adopted = company.adoptFoundingCredentials(prepared)
        return try { object : AndroidOwnedTask<LocalCompanyAuthorityResult<LocalCompanyAuthorityView>> {
            override fun execute(): LocalCompanyAuthorityResult<LocalCompanyAuthorityView> {
                requireOwned()
                return companyValue { company.completeNewCompanyAndHead(adopted) }.also { requireOwned() }
            }
            override fun discard(value: LocalCompanyAuthorityResult<LocalCompanyAuthorityView>) = Unit
            override fun clearInputs() { company.discardFoundingCredentials(adopted) }
        } } catch (failure: Throwable) { company.discardFoundingCredentials(adopted); throw failure }
    }

    /** Factory read only. Does not create keys, choose IDs or turn an imported root into authority. */
    fun openExisting() {
        requireOwned()
        keys.assertCompletedOriginalKeys()
        val state = registry.readState(keys)
        if (state == null && registry.bootstrapRecord()?.enrollmentStarted == true) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        requireOwned()
    }

    override suspend fun inspect(): LocalProvisionerInspection = when (val result = work.run { task { inspectNative() } }) {
        is AndroidOwnedResult.Value -> result.value
        is AndroidOwnedResult.Unavailable -> LocalProvisionerInspection.Unavailable(result.reason)
    }

    override suspend fun prepareNewCompany(facts: SelfOwnedCompanyFactsV1): LocalProvisionerPreparation =
        when (val result = work.run { task { prepareNewCompanyNative(facts) } }) {
            is AndroidOwnedResult.Value -> LocalProvisionerPreparation.Prepared(result.value)
            is AndroidOwnedResult.Unavailable -> LocalProvisionerPreparation.Unavailable(result.reason)
        }

    override suspend fun completeNewCompany(action: LocalProvisionerPendingAction, secret: CharArray): LocalProvisionerEnrollment {
        try {
            return when (val result = work.run { passwordTask(secret) { completeNewCompanyNative(action, it) } }) {
                is AndroidOwnedResult.Value -> LocalProvisionerEnrollment.Enrolled(result.value)
                is AndroidOwnedResult.Unavailable -> LocalProvisionerEnrollment.Unavailable(result.reason)
            }
        } finally { secret.fill('\u0000') }
    }

    override suspend fun prepareBranchWithEnrolledCompany(facts: SelfOwnedBranchFactsV1): LocalProvisionerPreparation =
        when (val result = work.run { task { prepareBranchNative(facts) } }) {
            is AndroidOwnedResult.Value -> LocalProvisionerPreparation.Prepared(result.value)
            is AndroidOwnedResult.Unavailable -> LocalProvisionerPreparation.Unavailable(result.reason)
        }

    override suspend fun issueOriginalPreparation(action: LocalProvisionerPendingAction,
        installerLogin: String, secret: CharArray): LocalProvisionerGrant {
        var reserved: OriginalIssuanceReservation.Reserved? = null
        var prepared: PreparedOriginalIssuance? = null
        try {
            val reservation = when (val result = work.run { originalIssuanceReservationTask(action, installerLogin, secret) }) {
                is AndroidOwnedResult.Value -> when (val value = result.value) {
                    is OriginalIssuanceReservation.Retained -> return LocalProvisionerGrant.Retained(value.packet)
                    is OriginalIssuanceReservation.Reserved -> value
                }
                is AndroidOwnedResult.Unavailable -> return LocalProvisionerGrant.Unresolved(result.reason)
            }
            reserved = reservation
            // Durable reservation/readback precedes PBKDF2/native HMAC. No running segment
            // is rearmed, and neither an approval nor a password is delivered between stages.
            val credentials = when (val result = work.run { originalIssuancePasswordTask(reservation, secret) }) {
                is AndroidOwnedResult.Value -> result.value
                is AndroidOwnedResult.Unavailable -> return LocalProvisionerGrant.Unresolved(result.reason)
            }
            prepared = credentials
            return when (val result = work.run { originalIssuanceCompletionTask(credentials) }) {
                is AndroidOwnedResult.Value -> LocalProvisionerGrant.Retained(result.value)
                is AndroidOwnedResult.Unavailable -> LocalProvisionerGrant.Unresolved(result.reason)
            }
        } finally {
            secret.fill('\u0000')
            prepared?.let { authentication.discardOriginalAllocationPreparation(it.credentials) }
            reserved?.let { authentication.discardOriginalAllocationPreparation(it.credentials) }
        }
    }

    override suspend fun recoverOriginalPreparation(): LocalProvisionerGrant =
        when (val result = work.run { task { recoverNative() } }) {
            is AndroidOwnedResult.Value -> LocalProvisionerGrant.Retained(result.value)
            is AndroidOwnedResult.Unavailable -> LocalProvisionerGrant.Unresolved(result.reason)
        }

    override suspend fun withdrawNewIssuance(): LocalProvisionerWithdrawal {
        denied.set(true) // This immediate denial is NOT yet a durable withdrawal acknowledgement.
        return when (val result = work.run { task {
            val state = registry.readState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
            if (state.issuanceEnabled) registry.retainState(state.next(issuanceEnabled = false), keys)
            Unit
        } }) {
            is AndroidOwnedResult.Value -> LocalProvisionerWithdrawal.Retained
            is AndroidOwnedResult.Unavailable -> LocalProvisionerWithdrawal.Unavailable(result.reason)
        }
    }

    override fun denyNewIssuance() { denied.set(true) }

    override suspend fun close() {
        denied.set(true)
        company.denyHeadIssuance()
        work.requestClose { closeNative() }.await()
    }

    /** Only factory discard or the owned close task calls this, under an independent watchdog. */
    fun closeNative() {
        denied.set(true)
        company.denyHeadIssuance()
        registry.closeNative()
        actuallyClosed.set(true)
    }

    private fun inspectNative(): LocalProvisionerInspection {
        keys.assertCompletedOriginalKeys()
        val original = registry.bootstrapRecord() ?: return LocalProvisionerInspection.Unenrolled
        val state = registry.readState(keys)
        if (state == null && original.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (state == null) return LocalProvisionerInspection.FoundingPending(foundingAction(original))
        val allocation = state.allocations.singleOrNull()
        return LocalProvisionerInspection.Enrolled(company(state, original), allocation?.let { branchAction(original, it) },
            allocation?.phase == LocalProvisionerAllocationPhaseV1.GRANT_RETAINED)
    }

    private fun prepareNewCompanyNative(facts: SelfOwnedCompanyFactsV1): LocalProvisionerPendingAction {
        requireNewIssuance()
        environment.requireSupportedProtection(facts.protection)
        // A fresh seven-key/head original must complete its explicit TWO-secret ceremony.
        if (registry.companyBootstrapRecord() != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        keys.assertCompletedOriginalKeys()
        if (registry.readState(keys) != null) selfOwnedFail(SelfOwnedInstallationFailureV1.ALREADY_ENROLLED)
        var original = registry.bootstrapRecord()
        if (original == null) {
            // Actual explicit founding: these names establish a NEW local authority, never
            // prove membership/ownership of a pre-existing company with the same display name.
            val ids = newIds(10, emptySet())
            val nonce = AndroidP256Primitives.randomBytes(32)
            val created = try {
                SelfOwnedBootstrapV1(0L, ids[0], ids[1], ids[2], ids[3], ids[4], ids[5], facts, environment.target, registry.namespaceIdentity(),
                    SelfOwnedOctetsV1(nonce), SelfOwnedNativeKeyRoleV1.entries.mapIndexed { index, role ->
                        SelfOwnedNativeKeyIntentV1(role, ids[6 + index], SelfOwnedKeyIntentPhaseV1.NOT_STARTED,
                            null, null, SelfOwnedOctetsV1(ByteArray(0)))
                    }, false)
            } finally { nonce.fill(0) }
            registry.retainBootstrap(created)
            original = registry.bootstrapRecord()
        }
        val retained = original ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (retained.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!sameProvisionerCompanyFacts(retained.facts, facts)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        requireNewIssuance()
        keys.prepareOriginalKeys()
        requireNewIssuance()
        return foundingAction(requireNotNull(registry.bootstrapRecord()))
    }

    private fun completeNewCompanyNative(action: LocalProvisionerPendingAction, secret: CharArray): LocalProvisionerCompanyView {
        requireNewIssuance()
        if (registry.companyBootstrapRecord() != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        val original = registry.bootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        // Lost enrollment replies cannot replace the already retained installer credential.
        if (registry.readState(keys) != null) selfOwnedFail(SelfOwnedInstallationFailureV1.ALREADY_ENROLLED)
        if (original.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!original.complete()) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        keys.assertAllOriginalKeys()
        requireAction(action, original, null)
        val account = authentication.createFoundingInstaller(secret)
        requireNewIssuance()
        val root = keys.rootAnnouncement()
        val delegation = keys.issuerDelegation(root)
        val state = LocalProvisionerRegistryStateV1(0L, root, delegation, true, 1L, listOf(account), emptyList())
        requireNewIssuance()
        // An absent registry after this prior marker is unresolved enrollment, never permission
        // to replace a possibly committed original installer password/account.
        registry.retainEnrollmentStart()
        val retained = registry.retainState(state, keys)
        requireNewIssuance()
        return company(retained, original)
    }

    private fun prepareBranchNative(facts: SelfOwnedBranchFactsV1): LocalProvisionerPendingAction {
        requireNewIssuance()
        environment.requireSupportedProtection(facts.protection)
        val original = registry.bootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.EXISTING_AUTHORITY_REQUIRED)
        val state = registry.readState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.EXISTING_AUTHORITY_REQUIRED)
        if (!state.issuanceEnabled) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        if (!state.installers.single().enabled) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        if (facts.enrolledRootFingerprint != AndroidP256Primitives.rootFingerprint(keys.rootBody())) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.EXISTING_AUTHORITY_REQUIRED)
        }
        if (facts.protection != original.facts.protection) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        state.allocations.singleOrNull()?.let { existing ->
            if (existing.body.branchName != facts.branchName || existing.body.protection != facts.protection ||
                !existing.body.target.same(environment.target)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            return branchAction(original, existing)
        }
        // This profile issues only to this actual installed app. External target/import/delegation
        // acquisition remains separate unimplemented work, not a caller-supplied target selector.
        val reserved = (listOf(original.actionId, original.rootId, original.organizationId, original.companyId,
            original.issuerId, original.installerId) + original.keys.map { it.keyId } +
            (registry.companyBootstrapRecord()?.allIds() ?: emptyList())).toSet()
        val ids = newIds(10, reserved)
        val coordinates = SelfOwnedAllocationCoordinatesV1(ids[0], BranchScope(original.organizationId, original.companyId, ids[1]),
            ids[2], ids[3], ids[4], ids[5], ids[6], ids[7], ids[8], ids[9])
        val nonce = AndroidP256Primitives.randomBytes(32)
        val account = state.installers.single()
        val body = try {
            SelfOwnedPreparationBodyV1(original.rootId, AndroidP256Primitives.documentDigest(state.root), original.issuerId,
                AndroidP256Primitives.documentDigest(state.delegation), account.installerId, account.credentialRevision,
                account.delegationRevision, coordinates, state.nextSerial, SelfOwnedOctetsV1(nonce), environment.target,
                facts.branchName, facts.protection)
        } finally { nonce.fill(0) }
        val allocation = LocalProvisionerAllocationV1(body, LocalProvisionerAllocationPhaseV1.ALLOCATED, null)
        requireNewIssuance()
        val retained = registry.retainState(state.next(nextSerial = state.nextSerial + 1L, allocations = listOf(allocation)), keys)
        requireNewIssuance()
        return branchAction(original, retained.allocations.single())
    }

    private fun reserveOriginalIssuanceNative(action: LocalProvisionerPendingAction, login: String): OriginalIssuanceReservation {
        val original = registry.bootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        val state = registry.readState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        val allocation = state.allocations.singleOrNull() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireAction(action, original, allocation)
        if (allocation.phase == LocalProvisionerAllocationPhaseV1.GRANT_RETAINED) {
            return OriginalIssuanceReservation.Retained(packet(state, allocation))
        }
        requireNewIssuance()
        val reserved = authentication.reserveOriginalAllocation(action, login)
        return try { OriginalIssuanceReservation.Reserved(action, reserved) }
        catch (failure: Throwable) { authentication.discardOriginalAllocationPreparation(reserved); throw failure }
    }

    private fun deriveOriginalIssuanceNative(action: LocalProvisionerPendingAction,
        reserved: AndroidLocalInstallerAuthentication.ReservedOriginalAllocation, secret: CharArray): PreparedOriginalIssuance {
        requireNewIssuance()
        if (reserved.action !== action) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val original = registry.bootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        val state = registry.readState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        val allocation = state.allocations.singleOrNull() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireAction(action, original, allocation)
        val derived = authentication.deriveOriginalAllocation(reserved, secret)
        return try { PreparedOriginalIssuance(action, derived) }
        catch (failure: Throwable) { authentication.discardOriginalAllocationPreparation(derived); throw failure }
    }

    private fun issuePreparedNative(action: LocalProvisionerPendingAction,
        prepared: AndroidLocalInstallerAuthentication.DerivedOriginalAllocation): SelfOwnedPreparationPacketV1 {
        if (prepared.reservation.action !== action) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val original = registry.bootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        var state = registry.readState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        var allocation = state.allocations.singleOrNull() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireAction(action, original, allocation)
        if (allocation.phase == LocalProvisionerAllocationPhaseV1.GRANT_RETAINED) return packet(state, allocation)
        requireNewIssuance()
        val approval = authentication.completeOriginalAllocation(prepared)
        state = requireNotNull(registry.readState(keys))
        allocation = state.allocations.single()
        authentication.requireStillCurrent(approval, state, allocation.body.coordinates.allocationId)
        requireNewIssuance()
        if (allocation.phase == LocalProvisionerAllocationPhaseV1.ALLOCATED) {
            allocation = allocation.copy(phase = LocalProvisionerAllocationPhaseV1.SIGNING_INTENT)
            state = registry.retainState(state.replaceAllocation(allocation), keys)
            allocation = state.allocations.single()
        }
        authentication.requireStillCurrent(approval, state, allocation.body.coordinates.allocationId)
        requireNewIssuance()
        val grant = keys.signOriginalPreparation(allocation.body)
        // Current concrete account/delegation and exact original record are re-read after signing.
        state = requireNotNull(registry.readState(keys))
        allocation = state.allocations.single()
        authentication.requireStillCurrent(approval, state, allocation.body.coordinates.allocationId)
        requireNewIssuance()
        val retained = registry.retainState(state.replaceAllocation(allocation.copy(
            phase = LocalProvisionerAllocationPhaseV1.GRANT_RETAINED, grant = grant)), keys)
        requireNewIssuance()
        return packet(retained, retained.allocations.single())
    }

    private fun recoverNative(): SelfOwnedPreparationPacketV1 {
        keys.assertCompletedOriginalKeys()
        val state = registry.readState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val allocation = state.allocations.singleOrNull() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        // Deliberately ignores new-issuance withdrawal and asks for NO caller scope/IDs/password.
        // Failure is unresolved, never permission to make another allocation, key or branch.
        return packet(state, allocation)
    }

    private fun packet(state: LocalProvisionerRegistryStateV1, allocation: LocalProvisionerAllocationV1): SelfOwnedPreparationPacketV1 {
        if (allocation.phase != LocalProvisionerAllocationPhaseV1.GRANT_RETAINED || allocation.grant == null) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        }
        return SelfOwnedPreparationPacketV1(allocation.body.coordinates, allocation.body.target, state.root, state.delegation, allocation.grant)
    }

    private fun company(state: LocalProvisionerRegistryStateV1, original: SelfOwnedBootstrapV1): LocalProvisionerCompanyView =
        LocalProvisionerCompanyView(original.rootId, original.organizationId, original.companyId, original.facts.companyName,
            original.facts.responsiblePersonName, original.facts.installerLogin, AndroidP256Primitives.rootFingerprint(keys.rootBody()),
            original.facts.protection, state.issuanceEnabled && state.installers.single().enabled && !denied.get())

    private fun foundingAction(original: SelfOwnedBootstrapV1): LocalProvisionerPendingAction =
        action(original, null, if (original.complete()) AndroidP256Primitives.rootFingerprint(keys.rootBody()) else null)
    private fun branchAction(original: SelfOwnedBootstrapV1, allocation: LocalProvisionerAllocationV1): LocalProvisionerPendingAction =
        action(original, allocation, AndroidP256Primitives.rootFingerprint(keys.rootBody()))

    private fun action(original: SelfOwnedBootstrapV1, allocation: LocalProvisionerAllocationV1?, fingerprint: String?): LocalProvisionerPendingAction {
        val now = work.clock()
        if (now > Long.MAX_VALUE - SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        return LocalProvisionerPendingAction(actionOwner, allocation?.body?.coordinates?.allocationId ?: original.actionId,
            if (allocation == null) LocalProvisionerActionKind.FOUND_NEW_COMPANY else LocalProvisionerActionKind.PREPARE_ORIGINAL_BRANCH,
            original.facts.companyName, original.facts.responsiblePersonName, original.facts.installerLogin,
            allocation?.body?.branchName, fingerprint, original.facts.protection, original.organizationId, original.companyId,
            allocation?.body?.coordinates, original.target, environment.bootCount, now + SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS)
    }

    private fun requireAction(action: LocalProvisionerPendingAction, original: SelfOwnedBootstrapV1, allocation: LocalProvisionerAllocationV1?) {
        val coordinates = allocation?.body?.coordinates
        if (action.owner !== actionOwner || action.originalId != (coordinates?.allocationId ?: original.actionId) ||
            action.kind != (if (allocation == null) LocalProvisionerActionKind.FOUND_NEW_COMPANY else LocalProvisionerActionKind.PREPARE_ORIGINAL_BRANCH) ||
            action.boot != environment.bootCount || work.clock() >= action.expiresElapsedMillis ||
            action.rootFingerprint == null || action.rootFingerprint != AndroidP256Primitives.rootFingerprint(keys.rootBody()) ||
            action.organizationId != original.organizationId || action.companyId != original.companyId ||
            action.allocation?.allocationId != coordinates?.allocationId || !action.target.same(original.target) ||
            action.companyName != original.facts.companyName || action.responsiblePersonName != original.facts.responsiblePersonName ||
            action.installerLogin != original.facts.installerLogin || action.protection != original.facts.protection ||
            action.branchName != allocation?.body?.branchName) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    }

    private fun requireNewIssuance() {
        requireOwned()
        if (denied.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
    }
    private fun requireOwned() {
        if (actuallyClosed.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        work.requireActive(); environment.requireCurrent(); registry.assertContinuity()
    }

    private sealed interface OriginalIssuanceReservation {
        class Retained(val packet: SelfOwnedPreparationPacketV1): OriginalIssuanceReservation
        class Reserved(val action: LocalProvisionerPendingAction,
            val credentials: AndroidLocalInstallerAuthentication.ReservedOriginalAllocation): OriginalIssuanceReservation
    }

    private class PreparedOriginalIssuance(val action: LocalProvisionerPendingAction,
        val credentials: AndroidLocalInstallerAuthentication.DerivedOriginalAllocation)

    /** Bounded validation after admission, without retaining/copying a password into the
     * reservation task. Invalid original inputs still cannot consume an attempt. */
    private fun originalIssuanceReservationTask(action: LocalProvisionerPendingAction,
        login: String, secret: CharArray): AndroidOwnedTask<OriginalIssuanceReservation> {
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        return object : AndroidOwnedTask<OriginalIssuanceReservation> {
            override fun execute(): OriginalIssuanceReservation {
                requireOwned()
                val value = try { reserveOriginalIssuanceNative(action, login) }
                catch (failure: Throwable) { requireOwned(); throw failure }
                return try { requireOwned(); value }
                catch (failure: Throwable) { discard(value); throw failure }
            }
            override fun discard(value: OriginalIssuanceReservation) {
                if (value is OriginalIssuanceReservation.Reserved) authentication.discardOriginalAllocationPreparation(value.credentials)
            }
            override fun clearInputs() = Unit
        }
    }

    /** Only the admitted derivation owns a password copy; caller cleanup cannot erase its
     * independent reservation scratch. Both owned inputs clear before result delivery. */
    private fun originalIssuancePasswordTask(prepared: OriginalIssuanceReservation.Reserved,
        secret: CharArray): AndroidOwnedTask<PreparedOriginalIssuance> {
        if (secret.size !in SelfOwnedInstallationProfileV1.MINIMUM_PASSWORD_CHARS..SelfOwnedInstallationProfileV1.MAXIMUM_PASSWORD_CHARS) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        }
        val owned = secret.copyOf()
        secret.fill('\u0000')
        if (!selfOwnedPasswordInput(owned)) { owned.fill('\u0000'); selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        val action = prepared.action
        val adopted = try { authentication.adoptOriginalAllocationReservation(prepared.credentials) }
        catch (failure: Throwable) { owned.fill('\u0000'); throw failure }
        return try { object : AndroidOwnedTask<PreparedOriginalIssuance> {
            override fun execute(): PreparedOriginalIssuance {
                requireOwned()
                val value = try { deriveOriginalIssuanceNative(action, adopted, owned) }
                catch (failure: Throwable) { requireOwned(); throw failure }
                return try { requireOwned(); value }
                catch (failure: Throwable) { discard(value); throw failure }
            }
            override fun discard(value: PreparedOriginalIssuance) { authentication.discardOriginalAllocationPreparation(value.credentials) }
            override fun clearInputs() { owned.fill('\u0000'); authentication.discardOriginalAllocationPreparation(adopted) }
        } } catch (failure: Throwable) {
            owned.fill('\u0000'); authentication.discardOriginalAllocationPreparation(adopted); throw failure
        }
    }

    /** Final admission copies disposable buffers only; all native ownership/action/account
     * checks, completion mutation, signing and authenticated readback remain in execute. */
    private fun originalIssuanceCompletionTask(prepared: PreparedOriginalIssuance): AndroidOwnedTask<SelfOwnedPreparationPacketV1> {
        val action = prepared.action
        val adopted = authentication.adoptOriginalAllocationVerification(prepared.credentials)
        return try { object : AndroidOwnedTask<SelfOwnedPreparationPacketV1> {
            override fun execute(): SelfOwnedPreparationPacketV1 {
                requireOwned()
                return issuePreparedNative(action, adopted).also { requireOwned() }
            }
            override fun discard(value: SelfOwnedPreparationPacketV1) = Unit // Same durable original grant; never replacement authority.
            override fun clearInputs() { authentication.discardOriginalAllocationPreparation(adopted) }
        } } catch (failure: Throwable) { authentication.discardOriginalAllocationPreparation(adopted); throw failure }
    }

    /** All callbacks here are private authored operations, not caller-provided trust providers. */
    private fun <T> task(body: () -> T): AndroidOwnedTask<T> = object : AndroidOwnedTask<T> {
        override fun execute(): T { requireOwned(); return body().also { requireOwned() } }
        override fun discard(value: T) = Unit // Pure immutable view/bytes; actual authority remains on disk in this owner.
        override fun clearInputs() = Unit
    }

    /** Called only AFTER admission, and does no IO. No copied secret sits in a waiter queue. */
    private fun <T> passwordTask(secret: CharArray, body: (CharArray) -> T): AndroidOwnedTask<T> {
        if (secret.size !in SelfOwnedInstallationProfileV1.MINIMUM_PASSWORD_CHARS..SelfOwnedInstallationProfileV1.MAXIMUM_PASSWORD_CHARS) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        }
        val owned = secret.copyOf()
        secret.fill('\u0000')
        if (!selfOwnedPasswordInput(owned)) { owned.fill('\u0000'); selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        return object : AndroidOwnedTask<T> {
            override fun execute(): T { requireOwned(); return body(owned).also { requireOwned() } }
            override fun discard(value: T) = Unit
            override fun clearInputs() { owned.fill('\u0000') }
        }
    }

    private fun newIds(count: Int, reserved: Set<DomainId>): List<DomainId> {
        val ids = List(count) { AndroidP256Primitives.newId() }
        if (ids.distinct().size != count || ids.any { it in reserved }) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return ids
    }
}
