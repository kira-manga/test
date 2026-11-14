package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey
import javax.crypto.spec.PBEKeySpec
import javax.security.auth.Destroyable

internal enum class SelfOwnedDesktopActionKindV2 { FOUND_NEW_COMPANY, FOUND_NEW_COMPANY_AND_HEAD, PREPARE_ORIGINAL_BRANCH }

/**
 * Invocation-local pending desktop provisioner action. [owner] is compared by identity, [boot] with
 * the actual current boot ledger and [expiresElapsedMillis] with the owned clock. Never a token.
 */
internal class SelfOwnedDesktopActionV2 internal constructor(
    internal val owner: Any,
    val originalId: DomainId,
    val kind: SelfOwnedDesktopActionKindV2,
    val companyName: String,
    val installerLogin: String,
    val headLogin: String?,
    val branchName: String?,
    val rootFingerprint: String?,
    val organizationId: DomainId,
    val companyId: DomainId,
    val allocationId: DomainId?,
    internal val boot: SelfOwnedBootOrdinalLedgerV2,
    val expiresElapsedMillis: Long,
) { override fun toString(): String = "SelfOwnedDesktopActionV2([REDACTED])" }

internal class SelfOwnedDesktopCompanyViewV2 internal constructor(
    val rootId: DomainId,
    val organizationId: DomainId,
    val companyId: DomainId,
    val companyName: String,
    val rootFingerprint: String,
    val headAuthorityId: DomainId?,
    val issuanceEnabled: Boolean,
) { override fun toString(): String = "SelfOwnedDesktopCompanyViewV2([REDACTED])" }

/**
 * Windows/macOS original provisioner core: founding (four-key original-only or seven-key company
 * and head), the single original branch allocation, separate installer authentication and the V2
 * preparation grant. It mirrors the Android V1 native operations ([AndroidLocalProvisioner],
 * [AndroidLocalInstallerAuthentication], [AndroidLocalCompanyAuthority] founding) step for step,
 * over V2 formats only; Android never constructs it (UNSUPPORTED_PLATFORM for any V1 environment
 * or key custody) and nothing is re-tried as another version.
 *
 * Every method is a synchronous native operation for the platform's owned worker. Boot facts come
 * only from the verified boot-ordinal chain; credential throttles use its ordinal and re-arm the
 * full cooldown on a later boot. A reservation/penalty is durably retained and read back BEFORE the
 * PBKDF2 derivation, and the completion re-authenticates the fresh state.
 */
internal class SelfOwnedDesktopProvisionerV2(
    private val environment: SelfOwnedOriginalEnvironment,
    private val registry: SelfOwnedProvisionerRegistryStoreV2,
    keys: SelfOwnedOriginalProvisionerKeys,
    private val work: AndroidOwnedInstallationWork,
) {
    private val keys: SelfOwnedProvisionerNativeKeysV2 = SelfOwnedProvisionerRegistryV2.requireDesktopKeys(keys)
    private val actionOwner = Any()
    private val denied = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    init { requireDesktopEnvironment() }

    fun denyNewIssuance() { denied.set(true) }
    fun close() { denied.set(true); closed.set(true) }

    // ---- Four-key original-only founding (mirror of AndroidLocalProvisioner) ----

    fun prepareNewCompany(facts: SelfOwnedCompanyFactsV1): SelfOwnedDesktopActionV2 {
        requireNewIssuance()
        environment.requireSupportedProtection(facts.protection)
        if (registry.companyBootstrapRecordV2() != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        keys.assertCompletedOriginalKeys()
        if (readState() != null) selfOwnedFail(SelfOwnedInstallationFailureV1.ALREADY_ENROLLED)
        var original = registry.bootstrapRecordV2()
        if (original == null) {
            val ids = newIds(10, emptySet())
            retainBootstrap(null, newBootstrap(ids, facts))
            original = registry.bootstrapRecordV2()
        }
        val retained = original ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireOwnTarget(retained)
        if (retained.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!sameProvisionerCompanyFacts(retained.facts, facts)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        requireNewIssuance()
        keys.prepareOriginalKeys()
        requireNewIssuance()
        return foundingAction(requireBootstrap(), null)
    }

    fun completeNewCompany(action: SelfOwnedDesktopActionV2, secret: CharArray): SelfOwnedDesktopCompanyViewV2 {
        try {
            requireNewIssuance()
            if (registry.companyBootstrapRecordV2() != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            val original = registry.bootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            if (readState() != null) selfOwnedFail(SelfOwnedInstallationFailureV1.ALREADY_ENROLLED)
            if (original.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            if (!original.complete()) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            keys.assertAllOriginalKeys()
            requireFoundingAction(action, original, null)
            val account = foundingInstaller(original, secret)
            requireNewIssuance()
            val root = keys.rootAnnouncement()
            val delegation = keys.issuerDelegation(root)
            val state = LocalProvisionerRegistryStateV2(0L, root, delegation, true, 1L, listOf(account), emptyList())
            requireFoundingAction(action, original, null); requireNewIssuance()
            // An absent registry after this marker is unresolved enrollment, never permission to
            // replace a possibly committed installer credential.
            val started = original.copy(revision = original.revision + 1L, enrollmentStarted = true)
            retainBootstrap(original, started)
            val retained = retainState(null, state)
            requireNewIssuance()
            return view(retained, requireBootstrap(), null)
        } finally { secret.fill('\u0000') }
    }

    // ---- Seven-key company and head founding (mirror of AndroidLocalCompanyAuthority) ----

    fun prepareNewCompanyAndHead(facts: LocalCompanyFoundingFactsV1): SelfOwnedDesktopActionV2 {
        val companyKeys = SelfOwnedCompanyAuthorityV2.requireCompanyKeys(keys)
        requireNewIssuance()
        environment.requireSupportedProtection(facts.company.protection)
        keys.assertCompletedOriginalKeys()
        val legacy = registry.bootstrapRecordV2()
        var company = registry.companyBootstrapRecordV2()
        if (legacy != null && company == null) localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        if (readState() != null) selfOwnedFail(SelfOwnedInstallationFailureV1.ALREADY_ENROLLED)
        if (company == null) {
            val ids = newIds(15, emptySet())
            val created = LocalCompanyAuthorityBootstrapV2(0L, newBootstrap(ids, facts.company), ids[10], ids[11], facts.headName,
                facts.headLogin, facts.headLifetime, LocalCompanyHeadKeyRoleV1.entries.mapIndexed { index, role ->
                    LocalCompanyHeadKeyIntentV2(role, ids[12 + index], 0L, SelfOwnedKeyIntentPhaseV1.NOT_STARTED,
                        null, null, SelfOwnedOctetsV1(ByteArray(0)))
                })
            retainCompanyBootstrap(null, created)
            company = requireCompanyBootstrap()
        }
        requireOwnTarget(company.original)
        if (company.original.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!sameProvisionerCompanyFacts(company.original.facts, facts.company) || company.headName != facts.headName ||
            company.headLogin != facts.headLogin || company.lifetime != facts.headLifetime) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        }
        requireNewIssuance(); keys.prepareOriginalKeys(); companyKeys.prepareCompanyKeys(); requireNewIssuance()
        return foundingAction(requireBootstrap(), requireCompanyBootstrap())
    }

    fun completeNewCompanyAndHead(action: SelfOwnedDesktopActionV2, installerSecret: CharArray,
        headSecret: CharArray): SelfOwnedDesktopCompanyViewV2 {
        try {
            val companyKeys = SelfOwnedCompanyAuthorityV2.requireCompanyKeys(keys)
            if (installerSecret === headSecret) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
            requireNewIssuance()
            val company = requireCompanyBootstrap()
            if (readState() != null) selfOwnedFail(SelfOwnedInstallationFailureV1.ALREADY_ENROLLED)
            if (company.original.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            if (!company.complete()) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            companyKeys.assertAllCompanyKeys(); requireFoundingAction(action, company.original, company)
            val installer = foundingInstaller(company.original, installerSecret)
            val head = foundingHead(company, headSecret)
            requireNewIssuance()
            if (!sameCompany(requireCompanyBootstrap(), company)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            val root = keys.rootAnnouncement()
            val delegation = keys.issuerDelegation(root)
            val enrollment = companyKeys.signFreshHeadEnrollmentV2()
            val state = LocalCompanyAuthorityStateV2(LocalProvisionerRegistryStateV2(0L, root, delegation, true, 1L,
                listOf(installer), emptyList()), enrollment, head, emptyList(), emptyList())
            // Capacity preflight before the marker: a too-large state never strands enrollment.
            SelfOwnedNativeProvisionerWireV2.encodeCompanyState(state).fill(0)
            requireFoundingAction(action, company.original, company); requireNewIssuance()
            val started = company.nextOriginal(company.original.copy(revision = company.original.revision + 1L,
                enrollmentStarted = true))
            retainCompanyBootstrap(company, started)
            val retained = retainCompany(null, state)
            requireNewIssuance()
            return view(retained.original, requireBootstrap(), requireCompanyBootstrap())
        } finally { installerSecret.fill('\u0000'); headSecret.fill('\u0000') }
    }

    fun inspectCompany(): SelfOwnedDesktopCompanyViewV2? {
        requireOwned()
        val original = registry.bootstrapRecordV2() ?: return null
        val state = readState()
        if (state == null) {
            if (original.enrollmentStarted) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            return null
        }
        return view(state, original, registry.companyBootstrapRecordV2())
    }

    /** Read-only projection of the retained original records for the setup adapter; grants nothing. */
    internal class SetupReadV2(val original: SelfOwnedBootstrapV2?, val company: LocalCompanyAuthorityBootstrapV2?,
        val state: LocalCompanyAuthorityStateV2?, val rootFingerprint: String?)

    fun readSetup(): SetupReadV2 {
        requireOwned()
        val original = registry.bootstrapRecordV2()
        val company = registry.companyBootstrapRecordV2()
        val state = company?.let { readCompany(it) }
        val fingerprint = if (original?.complete() == true) JvmP256Primitives.rootFingerprint(keys.rootBody()) else null
        return SetupReadV2(original, company, state, fingerprint)
    }

    // ---- Original branch allocation and issuance ----

    fun prepareOriginalBranch(branchName: String, enrolledRootFingerprint: String): SelfOwnedDesktopActionV2 {
        requireNewIssuance()
        try { SelfOwnedInstalledIdentityGrammarV2.requireLabel(branchName) }
        catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        val original = registry.bootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.EXISTING_AUTHORITY_REQUIRED)
        val state = readState() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.EXISTING_AUTHORITY_REQUIRED)
        if (!state.issuanceEnabled) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        if (!state.installers.single().enabled) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        if (enrolledRootFingerprint != JvmP256Primitives.rootFingerprint(keys.rootBody())) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.EXISTING_AUTHORITY_REQUIRED)
        }
        requireOwnTarget(original)
        state.allocations.singleOrNull()?.let { existing ->
            if (existing.body.branchName != branchName) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            return branchAction(original, existing)
        }
        // This profile issues only to this actual installed app; no caller-supplied target selector.
        val reserved = (original.allIds() + (registry.companyBootstrapRecordV2()?.allIds() ?: emptyList())).toSet()
        val ids = newIds(10, reserved)
        val coordinates = SelfOwnedAllocationCoordinatesV1(ids[0], BranchScope(original.organizationId, original.companyId, ids[1]),
            ids[2], ids[3], ids[4], ids[5], ids[6], ids[7], ids[8], ids[9])
        val account = state.installers.single()
        val nonce = JvmP256Primitives.randomBytes(SelfOwnedInstalledIdentityProfileV2.NONCE_BYTES)
        val body = try {
            SelfOwnedInstalledIdentityPreparationV2(original.rootId, JvmP256Primitives.documentDigest(state.root), original.issuerId,
                JvmP256Primitives.documentDigest(state.delegation), account.installerId, account.credentialRevision,
                account.delegationRevision, coordinates, state.nextSerial, nonce, original.installedTarget.target, branchName)
        } finally { nonce.fill(0) }
        val allocation = LocalProvisionerAllocationV2(body, LocalProvisionerAllocationPhaseV1.ALLOCATED, null)
        requireNewIssuance()
        val retained = retainState(state, state.next(nextSerial = state.nextSerial + 1L, allocations = listOf(allocation)))
        requireNewIssuance()
        return branchAction(original, retained.allocations.single())
    }

    /**
     * Separate installer authentication, then SIGNING_INTENT, the issuer V2 grant over the exact
     * signed body (verified here against the actual issuer point) and GRANT_RETAINED with readback.
     * A retained grant is returned without authentication (exact-result recovery).
     */
    fun issueOriginalPreparation(action: SelfOwnedDesktopActionV2, installerLogin: String,
        secret: CharArray): SelfOwnedPreparationPacketV2 {
        try {
            val original = registry.bootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
            var state = readState() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
            var allocation = state.allocations.singleOrNull() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            requireBranchAction(action, original, allocation)
            if (allocation.phase == LocalProvisionerAllocationPhaseV1.GRANT_RETAINED) return packet(original, state, allocation)
            requireNewIssuance()
            val approval = authenticateInstaller(original, installerLogin, secret, allocation.body.coordinates.allocationId,
                action.expiresElapsedMillis)
            state = readState() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            allocation = state.allocations.single()
            requireStillCurrent(approval, state, allocation)
            requireNewIssuance()
            if (allocation.phase == LocalProvisionerAllocationPhaseV1.ALLOCATED) {
                state = retainState(state, state.replaceAllocation(allocation.copy(phase = LocalProvisionerAllocationPhaseV1.SIGNING_INTENT)))
                allocation = state.allocations.single()
            }
            requireStillCurrent(approval, state, allocation)
            requireNewIssuance()
            val grant = keys.signOriginalPreparationV2(allocation.body)
            requireIssuerGrant(allocation.body, grant)
            state = readState() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            allocation = state.allocations.single()
            requireStillCurrent(approval, state, allocation)
            requireNewIssuance()
            val retained = retainState(state, state.replaceAllocation(allocation.copy(
                phase = LocalProvisionerAllocationPhaseV1.GRANT_RETAINED, grant = grant)))
            requireNewIssuance()
            return packet(original, retained, retained.allocations.single())
        } finally { secret.fill('\u0000') }
    }

    /** Exact retained result only; asks for no scope, IDs or password and ignores withdrawal. */
    fun recoverOriginalPreparation(): SelfOwnedPreparationPacketV2 {
        requireOwned()
        keys.assertCompletedOriginalKeys()
        val original = registry.bootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val state = readState() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val allocation = state.allocations.singleOrNull() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return packet(original, state, allocation)
    }

    fun withdrawNewIssuance() {
        denied.set(true) // Immediate denial first; durable withdrawal follows.
        requireOwned()
        val state = readState() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        if (state.issuanceEnabled) retainState(state, state.next(issuanceEnabled = false))
    }

    // ---- Installer authentication (mirror of AndroidLocalInstallerAuthentication) ----

    private class InstallerApproval(val allocationId: DomainId, val installerId: DomainId, val credentialRevision: Long,
        val delegationRevision: Long, val boot: SelfOwnedBootOrdinalLedgerV2, val expiresElapsedMillis: Long)

    private fun authenticateInstaller(original: SelfOwnedBootstrapV2, login: String, secret: CharArray,
        allocationId: DomainId, actionDeadline: Long): InstallerApproval {
        try { selfOwnedLogin(login) } catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        val boot = currentBoot()
        var state = readState() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        requireIssuance(state, allocationId)
        var account = state.installers.single()
        if (!account.enabled) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        if (account.throttleBoot > boot.ordinal) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (account.throttleBoot < boot.ordinal) {
            // Elapsed time cannot compare across boots: re-arm the ENTIRE retained cooldown; an
            // unfinished reservation already counted as a failure and is not forgiven.
            val normalized = account.copy(throttleBoot = boot.ordinal, reservationId = null,
                nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), account.cooldownMillis))
            state = retainState(state, state.replaceInstaller(normalized))
            account = state.installers.single()
            if (account.cooldownMillis > 0L) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        }
        if (work.clock() < account.nextAllowedElapsedMillis) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        val failures = localNativeCredentialNextFailureCount(account.failedAttempts)
        val cooldown = localNativeCredentialFailureDelayMillis(failures)
        val reservation = JvmP256Primitives.newId()
        state = retainState(state, state.replaceInstaller(account.copy(failedAttempts = failures, reservationId = reservation,
            throttleBoot = boot.ordinal, cooldownMillis = cooldown,
            nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), cooldown))))
        account = state.installers.single()
        if (account.reservationId != reservation) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        // The penalty is durable before derivation; cancellation never resets it.
        val salt = account.salt.copyBytes()
        val actual = try { derive(secret, original, account.installerId, account.credentialRevision, salt) } finally { salt.fill(0) }
        try {
            if (!sameBootstrapNow(original) || !currentBoot().ordinal.let { it == boot.ordinal } ||
                work.clock() >= actionDeadline) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            val current = readState() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            requireIssuance(current, allocationId)
            val checked = current.installers.single()
            if (checked.reservationId != reservation || checked.credentialRevision != account.credentialRevision ||
                checked.salt != account.salt || checked.verifier != account.verifier || !checked.enabled) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            }
            val expected = checked.verifier.copyBytes()
            val matched = try { JvmP256Primitives.same(expected, actual) && login == checked.login } finally { expected.fill(0) }
            val completed = if (matched) checked.copy(failedAttempts = 0, reservationId = null, cooldownMillis = 0L,
                nextAllowedElapsedMillis = work.clock()) else checked.copy(reservationId = null,
                nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), checked.cooldownMillis))
            retainState(current, current.replaceInstaller(completed))
            if (!matched) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
            work.requireActive()
            if (work.clock() >= actionDeadline) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            return InstallerApproval(allocationId, checked.installerId, checked.credentialRevision, checked.delegationRevision,
                boot, actionDeadline)
        } finally { actual.fill(0) }
    }

    private fun requireStillCurrent(approval: InstallerApproval, state: LocalProvisionerRegistryStateV2,
        allocation: LocalProvisionerAllocationV2) {
        requireIssuance(state, approval.allocationId)
        val account = state.installers.single()
        if (allocation.body.coordinates.allocationId != approval.allocationId || account.installerId != approval.installerId ||
            !account.enabled || account.credentialRevision != approval.credentialRevision ||
            account.delegationRevision != approval.delegationRevision || allocation.body.installerRevision != approval.credentialRevision ||
            allocation.body.delegationRevision != approval.delegationRevision || currentBoot() != approval.boot ||
            work.clock() >= approval.expiresElapsedMillis) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    }

    private fun requireIssuance(state: LocalProvisionerRegistryStateV2, allocationId: DomainId) {
        if (!state.issuanceEnabled || denied.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        if (state.allocations.singleOrNull()?.body?.coordinates?.allocationId != allocationId) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        }
    }

    private fun foundingInstaller(original: SelfOwnedBootstrapV2, secret: CharArray): LocalProvisionerInstallerV1 {
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        val salt = JvmP256Primitives.randomBytes(32)
        val verifier = try { derive(secret, original, original.installerId, 0L, salt) }
        catch (failure: Throwable) { salt.fill(0); throw failure }
        try {
            requireOwned()
            return LocalProvisionerInstallerV1(original.installerId, original.facts.responsiblePersonName,
                original.facts.installerLogin, 0L, 0L, true, SelfOwnedOctetsV1(salt), SelfOwnedOctetsV1(verifier),
                0, null, currentBoot().ordinal, work.clock(), 0L)
        } finally { salt.fill(0); verifier.fill(0) }
    }

    private fun foundingHead(company: LocalCompanyAuthorityBootstrapV2, secret: CharArray): LocalCompanyHeadAccountV1 {
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        val companyKeys = SelfOwnedCompanyAuthorityV2.requireCompanyKeys(keys)
        val salt = JvmP256Primitives.randomBytes(32)
        val verifier = try {
            withDerivedMaterial(secret, salt) { derived ->
                val message = SelfOwnedNativeProvisionerWireV2.headVerifierMessage(company, 0L, salt, derived)
                try { companyKeys.headTag(message) } finally { message.fill(0) }
            }.also { if (it.size != 32) { it.fill(0); selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE) } }
        } catch (failure: Throwable) { salt.fill(0); throw failure }
        try {
            requireOwned()
            return LocalCompanyHeadAccountV1(company.headAuthorityId, company.headEnrollmentId, company.headName, company.headLogin,
                0L, 0L, true, 0L, true, 0L, true, SelfOwnedOctetsV1(salt), SelfOwnedOctetsV1(verifier),
                0, null, currentBoot().ordinal, work.clock(), 0L)
        } finally { salt.fill(0); verifier.fill(0) }
    }

    private fun derive(secret: CharArray, original: SelfOwnedBootstrapV2, installer: DomainId, credentialRevision: Long,
        salt: ByteArray): ByteArray = withDerivedMaterial(secret, salt) { derived ->
        val message = SelfOwnedNativeProvisionerWireV2.installerVerifierMessage(original, installer, credentialRevision, salt, derived)
        try { keys.installerTag(message) } finally { message.fill(0) }
    }.also { if (it.size != 32) { it.fill(0); selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE) } }

    /** Mechanical PBKDF2-HMAC-SHA256 (600k, 256-bit); owned copies clear, provider copies cannot be promised. */
    private fun withDerivedMaterial(secret: CharArray, salt: ByteArray, use: (ByteArray) -> ByteArray): ByteArray {
        val specification = PBEKeySpec(secret, salt, SelfOwnedInstallationProfileV1.PASSWORD_ITERATIONS, 256)
        var material: SecretKey? = null
        var derived: ByteArray? = null
        try {
            val created = work.platform.passwordKeyFactory().generateSecret(specification)
            material = created
            val bytes = requireNotNull(created.encoded)
            derived = bytes
            if (bytes.size != 32) selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            return use(bytes)
        } finally {
            specification.clearPassword(); derived?.fill(0)
            try { (material as? Destroyable)?.destroy() } catch (_: Exception) { /* best effort provider cleanup */ }
        }
    }

    // ---- Authenticated state custody ----

    private fun readState(): LocalProvisionerRegistryStateV2? {
        requireOwned()
        val company = registry.companyBootstrapRecordV2()
        if (company != null) return readCompany(company)?.original
        val bytes = registry.registryEnvelopeBytes() ?: return null
        val original = registry.bootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return try {
            SelfOwnedProvisionerRegistryV2.read(original, bytes, keys).also { requireThrottleBoots(it) }
        } finally { bytes.fill(0); registry.assertContinuity() }
    }

    private fun readCompany(company: LocalCompanyAuthorityBootstrapV2): LocalCompanyAuthorityStateV2? {
        val original = registry.bootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!SelfOwnedProvisionerRegistryV2.sameBootstrap(company.original, original)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val bytes = registry.registryEnvelopeBytes() ?: return null
        return try {
            SelfOwnedCompanyAuthorityV2.read(company, bytes, SelfOwnedCompanyAuthorityV2.requireCompanyKeys(keys), currentBoot().ordinal)
        } finally { bytes.fill(0); registry.assertContinuity() }
    }

    /** Original-registry write; inside a company container it is ONE whole-container revision. */
    private fun retainState(previous: LocalProvisionerRegistryStateV2?, next: LocalProvisionerRegistryStateV2):
        LocalProvisionerRegistryStateV2 {
        val company = registry.companyBootstrapRecordV2()
        if (company != null) {
            val container = readCompany(company) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            requireSameState(previous, container.original)
            return retainCompany(container, container.copy(original = next)).original
        }
        val original = registry.bootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val actual = readState()
        requireSameState(previous, actual)
        SelfOwnedProvisionerRegistryV2.requireTransition(actual, next)
        requireThrottleBoots(next)
        val bytes = SelfOwnedProvisionerRegistryV2.seal(original, next, keys)
        try { registry.replaceRegistryEnvelope(bytes) } finally { bytes.fill(0) }
        // Actual authenticated readback, not an in-memory acknowledgement.
        val readback = readState() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireSameState(next, readback)
        return readback
    }

    private fun retainCompany(previous: LocalCompanyAuthorityStateV2?, next: LocalCompanyAuthorityStateV2): LocalCompanyAuthorityStateV2 {
        val company = requireCompanyBootstrap()
        val companyKeys = SelfOwnedCompanyAuthorityV2.requireCompanyKeys(keys)
        val actual = readCompany(company)
        val expected = previous?.let { SelfOwnedNativeProvisionerWireV2.encodeCompanyState(it) }
        val observed = actual?.let { SelfOwnedNativeProvisionerWireV2.encodeCompanyState(it) }
        try {
            if ((expected == null) != (observed == null) || (expected != null && !expected.contentEquals(observed))) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
        } finally { expected?.fill(0); observed?.fill(0) }
        SelfOwnedCompanyAuthorityV2.requireTransition(actual, next)
        val bytes = SelfOwnedCompanyAuthorityV2.seal(company, next, companyKeys, currentBoot().ordinal)
        try { registry.replaceRegistryEnvelope(bytes) } finally { bytes.fill(0) }
        val readback = readCompany(company) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val a = SelfOwnedNativeProvisionerWireV2.encodeCompanyState(next); val b = SelfOwnedNativeProvisionerWireV2.encodeCompanyState(readback)
        try { if (!a.contentEquals(b)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) } finally { a.fill(0); b.fill(0) }
        return readback
    }

    private fun requireSameState(expected: LocalProvisionerRegistryStateV2?, actual: LocalProvisionerRegistryStateV2?) {
        val a = expected?.let { SelfOwnedNativeProvisionerWireV2.encodeRegistry(it) }
        val b = actual?.let { SelfOwnedNativeProvisionerWireV2.encodeRegistry(it) }
        try {
            if ((a == null) != (b == null) || (a != null && !a.contentEquals(b))) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        } finally { a?.fill(0); b?.fill(0) }
    }

    private fun requireThrottleBoots(state: LocalProvisionerRegistryStateV2) {
        val boot = currentBoot().ordinal
        if (state.installers.any { it.throttleBoot > boot }) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }

    private fun retainBootstrap(previous: SelfOwnedBootstrapV2?, next: SelfOwnedBootstrapV2) {
        requireOwned()
        val actual = registry.bootstrapRecordV2()
        if (registry.companyBootstrapRecordV2() != null) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        if (previous == null) {
            if (actual != null || next.revision != 0L || next.enrollmentStarted || next.keys.any { it.phase != SelfOwnedKeyIntentPhaseV1.NOT_STARTED }) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
        } else {
            if (actual == null || !SelfOwnedProvisionerRegistryV2.sameBootstrap(actual, previous) ||
                next.revision != previous.revision + 1L) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        }
        registry.retainBootstrapV2(next)
        val readback = registry.bootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!SelfOwnedProvisionerRegistryV2.sameBootstrap(readback, next)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    }

    private fun retainCompanyBootstrap(previous: LocalCompanyAuthorityBootstrapV2?, next: LocalCompanyAuthorityBootstrapV2) {
        requireOwned()
        val actual = registry.companyBootstrapRecordV2()
        if (previous == null) {
            if (actual != null || registry.bootstrapRecordV2() != null || next.revision != 0L || next.original.revision != 0L ||
                next.original.enrollmentStarted || !next.headKeys.all { it.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED } ||
                !next.original.keys.all { it.phase == SelfOwnedKeyIntentPhaseV1.NOT_STARTED }) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            }
        } else if (actual == null || !sameCompany(actual, previous) || next.revision != previous.revision + 1L) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        }
        registry.retainCompanyBootstrapV2(next)
        val readback = registry.companyBootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val original = registry.bootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!sameCompany(readback, next) || !SelfOwnedProvisionerRegistryV2.sameBootstrap(original, next.original)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private fun sameCompany(left: LocalCompanyAuthorityBootstrapV2, right: LocalCompanyAuthorityBootstrapV2): Boolean {
        val a = SelfOwnedNativeProvisionerWireV2.encodeCompanyBootstrap(left); val b = SelfOwnedNativeProvisionerWireV2.encodeCompanyBootstrap(right)
        return try { a.contentEquals(b) } finally { a.fill(0); b.fill(0) }
    }

    private fun sameBootstrapNow(original: SelfOwnedBootstrapV2): Boolean =
        registry.bootstrapRecordV2()?.let { SelfOwnedProvisionerRegistryV2.sameBootstrap(it, original) } == true

    private fun requireBootstrap(): SelfOwnedBootstrapV2 =
        registry.bootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
    private fun requireCompanyBootstrap(): LocalCompanyAuthorityBootstrapV2 =
        registry.companyBootstrapRecordV2() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)

    // ---- Environment, actions and results ----

    private fun requireDesktopEnvironment(): SelfOwnedBootOrdinalLedgerV2 {
        if (environment.originalFormat() != SelfOwnedOriginalFormat.V2) selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        if (environment.installedTarget !is SelfOwnedNativeInstalledTargetBindingV1) selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        val ledger = (environment.bootMark as? SelfOwnedBootMark.Session)?.ledger
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        SelfOwnedBootChainV2.verify(environment.bootLedgerChain(), ledger)
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return ledger
    }

    private fun currentBoot(): SelfOwnedBootOrdinalLedgerV2 = requireDesktopEnvironment()

    private fun requireOwnTarget(original: SelfOwnedBootstrapV2) {
        if (!original.installedTarget.matches(environment.installedTarget) ||
            original.platform != currentBoot().bootSession.platform) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val namespace = SelfOwnedNativeProvisionerWireV2.encodeNamespace(registry.namespaceIdentityV2())
        val retained = SelfOwnedNativeProvisionerWireV2.encodeNamespace(original.namespace)
        try { if (!namespace.contentEquals(retained)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
        finally { namespace.fill(0); retained.fill(0) }
    }

    private fun newBootstrap(ids: List<DomainId>, facts: SelfOwnedCompanyFactsV1): SelfOwnedBootstrapV2 {
        val target = environment.installedTarget as? SelfOwnedNativeInstalledTargetBindingV1
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        val nonce = JvmP256Primitives.randomBytes(SelfOwnedNativeOriginalProfileV2.NONCE_BYTES)
        return try {
            SelfOwnedBootstrapV2(0L, ids[0], ids[1], ids[2], ids[3], ids[4], ids[5], facts, target, registry.namespaceIdentityV2(),
                SelfOwnedOctetsV1(nonce), SelfOwnedNativeKeyRoleV1.entries.mapIndexed { index, role ->
                    SelfOwnedProvisionerKeyIntentV2(role, ids[6 + index], SelfOwnedKeyIntentPhaseV1.NOT_STARTED,
                        null, null, SelfOwnedOctetsV1(ByteArray(0)))
                }, false)
        } catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
        finally { nonce.fill(0) }
    }

    private fun packet(original: SelfOwnedBootstrapV2, state: LocalProvisionerRegistryStateV2,
        allocation: LocalProvisionerAllocationV2): SelfOwnedPreparationPacketV2 {
        val grant = allocation.grant
        if (allocation.phase != LocalProvisionerAllocationPhaseV1.GRANT_RETAINED || grant == null) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        }
        return try { SelfOwnedPreparationPacketV2(allocation.body.coordinates, original.installedTarget, state.root, state.delegation, grant) }
        catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) }
    }

    private fun requireIssuerGrant(body: SelfOwnedInstalledIdentityPreparationV2, grant: SelfOwnedInstalledIdentitySignedDocumentV2) {
        val expected = body.copyWireBytes(); val actual = grant.preparation.copyWireBytes()
        val signed = grant.signedBody.copyWireBytes(); val signature = grant.copySignature()
        val point = keys.originalPoint(SelfOwnedNativeKeyRoleV1.ISSUER)
        try {
            if (!expected.contentEquals(actual) || !JvmP256Primitives.verify(point, signed, signature)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
        } finally { expected.fill(0); actual.fill(0); signed.fill(0); signature.fill(0); point.fill(0) }
    }

    private fun view(state: LocalProvisionerRegistryStateV2, original: SelfOwnedBootstrapV2,
        company: LocalCompanyAuthorityBootstrapV2?): SelfOwnedDesktopCompanyViewV2 =
        SelfOwnedDesktopCompanyViewV2(original.rootId, original.organizationId, original.companyId, original.facts.companyName,
            JvmP256Primitives.rootFingerprint(keys.rootBody()), company?.headAuthorityId,
            state.issuanceEnabled && state.installers.single().enabled && !denied.get())

    private fun foundingAction(original: SelfOwnedBootstrapV2, company: LocalCompanyAuthorityBootstrapV2?): SelfOwnedDesktopActionV2 =
        SelfOwnedDesktopActionV2(actionOwner, original.actionId,
            if (company == null) SelfOwnedDesktopActionKindV2.FOUND_NEW_COMPANY else SelfOwnedDesktopActionKindV2.FOUND_NEW_COMPANY_AND_HEAD,
            original.facts.companyName, original.facts.installerLogin, company?.headLogin, null,
            if (original.complete()) JvmP256Primitives.rootFingerprint(keys.rootBody()) else null,
            original.organizationId, original.companyId, null, currentBoot(), pendingDeadline())

    private fun branchAction(original: SelfOwnedBootstrapV2, allocation: LocalProvisionerAllocationV2): SelfOwnedDesktopActionV2 =
        SelfOwnedDesktopActionV2(actionOwner, allocation.body.coordinates.allocationId, SelfOwnedDesktopActionKindV2.PREPARE_ORIGINAL_BRANCH,
            original.facts.companyName, original.facts.installerLogin, null, allocation.body.branchName,
            JvmP256Primitives.rootFingerprint(keys.rootBody()), original.organizationId, original.companyId,
            allocation.body.coordinates.allocationId, currentBoot(), pendingDeadline())

    private fun requireFoundingAction(action: SelfOwnedDesktopActionV2, original: SelfOwnedBootstrapV2,
        company: LocalCompanyAuthorityBootstrapV2?) {
        val kind = if (company == null) SelfOwnedDesktopActionKindV2.FOUND_NEW_COMPANY else SelfOwnedDesktopActionKindV2.FOUND_NEW_COMPANY_AND_HEAD
        requireCommonAction(action, original, kind)
        if (action.originalId != original.actionId || action.allocationId != null || action.branchName != null ||
            action.headLogin != company?.headLogin) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    }

    private fun requireBranchAction(action: SelfOwnedDesktopActionV2, original: SelfOwnedBootstrapV2, allocation: LocalProvisionerAllocationV2) {
        requireCommonAction(action, original, SelfOwnedDesktopActionKindV2.PREPARE_ORIGINAL_BRANCH)
        if (action.originalId != allocation.body.coordinates.allocationId || action.allocationId != allocation.body.coordinates.allocationId ||
            action.branchName != allocation.body.branchName) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
    }

    private fun requireCommonAction(action: SelfOwnedDesktopActionV2, original: SelfOwnedBootstrapV2, kind: SelfOwnedDesktopActionKindV2) {
        if (action.owner !== actionOwner || action.kind != kind || action.boot != currentBoot() ||
            work.clock() >= action.expiresElapsedMillis || action.rootFingerprint == null ||
            action.rootFingerprint != JvmP256Primitives.rootFingerprint(keys.rootBody()) ||
            action.organizationId != original.organizationId || action.companyId != original.companyId ||
            action.companyName != original.facts.companyName || action.installerLogin != original.facts.installerLogin) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        }
    }

    private fun pendingDeadline(): Long {
        val now = work.clock()
        if (now > Long.MAX_VALUE - SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return now + SelfOwnedInstallationProfileV1.PENDING_ACTION_MILLIS
    }

    private fun newIds(count: Int, reserved: Set<DomainId>): List<DomainId> {
        val ids = List(count) { JvmP256Primitives.newId() }
        if (ids.distinct().size != count || ids.any { it in reserved }) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return ids
    }

    private fun requireNewIssuance() {
        requireOwned()
        if (denied.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
    }

    private fun requireOwned() {
        if (closed.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        work.requireActive(); environment.requireCurrent(); registry.assertContinuity(); requireDesktopEnvironment()
    }

    override fun toString(): String = "SelfOwnedDesktopProvisionerV2([REDACTED])"
}

private fun SelfOwnedBootstrapV2.allIds(): List<DomainId> =
    listOf(actionId, rootId, organizationId, companyId, issuerId, installerId) + keys.map { it.keyId }
