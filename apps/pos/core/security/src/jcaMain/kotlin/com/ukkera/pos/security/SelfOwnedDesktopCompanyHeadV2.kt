package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.SecretKey
import javax.crypto.spec.PBEKeySpec
import javax.security.auth.Destroyable

/**
 * The named-head ceremonies a desktop company workflow may require. The two V1 purposes carry their
 * own purpose/key revisions; business publication, employee enrollment and employee policy
 * activation are separate ceremonies that never borrow either purpose (exactly as Android V1).
 */
internal enum class SelfOwnedDesktopHeadCeremonyV2(val purpose: LocalCompanyHeadPurposeV1?) {
    POLICY_PUBLISH(LocalCompanyHeadPurposeV1.POLICY_PUBLISH),
    FULL_D7_INSTALLATION_APPROVE(LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE),
    BUSINESS_PUBLICATION(null),
    EMPLOYEE_ENROLLMENT(null),
    EMPLOYEE_POLICY_ACTIVATION(null),
}

/**
 * Windows/macOS named-head authentication and head-issuance withdrawal over the seven-key V2
 * company container. It mirrors [AndroidLocalInstallerAuthentication] head ceremonies and
 * [AndroidLocalCompanyAuthority.withdrawHeadIssuance] step for step, over V2 custody only:
 *
 * - boot facts come only from the verified boot-ordinal chain; the head throttle re-arms the full
 *   retained cooldown on a later boot and a throttle boot beyond the chain is STATE_UNCERTAIN;
 * - the incremented failure count, cooldown and reservation are durably retained and read back
 *   BEFORE the PBKDF2 derivation; the completion re-reads and requires the unchanged account;
 * - the verifier is the V2 company head verifier ([SelfOwnedNativeProvisionerWireV2.headVerifierMessage]
 *   tagged by the company head pepper), never the Android V1 framing;
 * - an approval is bound to this owner, ceremony, action ID and digest, head identity and
 *   revisions, the exact boot ledger and an expiry no later than the admitted work deadline; any
 *   withdrawal (monotone in-memory gate and durable revision bump) invalidates it, and the caller
 *   re-checks [requireStillCurrent] against the state it commits with (revocation serialized with
 *   commit).
 *
 * Android never constructs it (UNSUPPORTED_PLATFORM for any V1 environment or key custody).
 * Every method is a synchronous native operation for the platform's owned worker inside an
 * admitted [AndroidOwnedInstallationWork.run] stage.
 */
internal class SelfOwnedDesktopCompanyHeadV2(
    private val environment: SelfOwnedOriginalEnvironment,
    private val registry: SelfOwnedProvisionerRegistryStoreV2,
    keys: SelfOwnedOriginalProvisionerKeys,
    private val work: AndroidOwnedInstallationWork,
    private val issuance: AndroidLocalCompanyIssuanceGate = AndroidLocalCompanyIssuanceGate(),
) {
    private val keys: SelfOwnedProvisionerNativeKeysV2 = SelfOwnedProvisionerRegistryV2.requireDesktopKeys(keys)
    private val companyKeys: SelfOwnedCompanyNativeKeysV2 = SelfOwnedCompanyAuthorityV2.requireCompanyKeys(keys)
    private val closed = AtomicBoolean(false)

    init { requireDesktopEnvironment() }

    fun close() { issuance.deny(null); closed.set(true) }

    internal class Approval internal constructor(
        internal val owner: SelfOwnedDesktopCompanyHeadV2,
        internal val ceremony: SelfOwnedDesktopHeadCeremonyV2,
        internal val originalActionId: DomainId,
        internal val actionDigest: PayloadDigest,
        internal val rootId: DomainId,
        internal val headAuthorityId: DomainId,
        internal val headEnrollmentId: DomainId,
        internal val accountRevision: Long,
        internal val credentialRevision: Long,
        internal val purposeRevision: Long?,
        internal val keyRevision: Long?,
        internal val boot: SelfOwnedBootOrdinalLedgerV2,
        internal val expires: Long,
        internal val retainedRevision: Long,
    ) {
        override fun toString(): String = "SelfOwnedDesktopHeadApprovalV2([REDACTED])"
    }

    /** Authenticates the named head for exactly one [ceremony] action. */
    fun authenticate(ceremony: SelfOwnedDesktopHeadCeremonyV2, originalActionId: DomainId, actionDigest: PayloadDigest,
        login: String, secret: CharArray, actionDeadline: Long): Approval {
        val purpose = ceremony.purpose
        issuance.requireIssuance(purpose)
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        try { selfOwnedLogin(login) } catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        requireOwned()
        if (work.clock() >= actionDeadline) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val verified = verifyCredential(purpose, login, secret, { requireCeremony(it.head, ceremony) }) { requireCeremony(it.head, ceremony) }
        val company = verified.company; val account = verified.account
        val expires = minOf(actionDeadline, work.currentDeadlineMillis())
        if (work.clock() >= expires) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return Approval(this, ceremony, originalActionId, actionDigest, company.original.rootId, company.headAuthorityId,
            company.headEnrollmentId, account.accountRevision, account.credentialRevision,
            purpose?.let { account.purposeRevision(it) }, purpose?.let { company.key(headKeyRole(it)).keyRevision },
            verified.boot, expires, verified.retained.original.revision)
    }

    /**
     * Separate one-device pairing consent ceremony (never one of [SelfOwnedDesktopHeadCeremonyV2]):
     * [consent] is the caller's independent registry/root/head/proof join of the exact forced
     * HEAD_CONSENT_INTENT record, re-run against every container state this ceremony observes.
     */
    fun authenticateDevicePairing(actionDigest: PayloadDigest, login: String, secret: CharArray, consentDeadline: Long,
        consent: (LocalCompanyAuthorityStateV2) -> Unit): PairingApproval {
        issuance.requireIssuance(null)
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        try { selfOwnedLogin(login) } catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        requireOwned()
        val verified = verifyCredential(null, login, secret, consent) { consent(it) }
        consent(verified.retained)
        val company = verified.company; val account = verified.account
        val expires = minOf(consentDeadline, work.currentDeadlineMillis())
        if (work.clock() >= expires) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return PairingApproval(this, actionDigest, company.original.rootId, company.headAuthorityId, company.headEnrollmentId,
            account.accountRevision, account.credentialRevision, verified.boot, expires, verified.retained.original.revision)
    }

    internal class PairingApproval internal constructor(
        internal val owner: SelfOwnedDesktopCompanyHeadV2,
        internal val actionDigest: PayloadDigest,
        internal val rootId: DomainId,
        internal val headAuthorityId: DomainId,
        internal val headEnrollmentId: DomainId,
        internal val accountRevision: Long,
        internal val credentialRevision: Long,
        internal val boot: SelfOwnedBootOrdinalLedgerV2,
        internal val expires: Long,
        internal val retainedRevision: Long,
    ) {
        override fun toString(): String = "SelfOwnedDesktopPairingApprovalV2([REDACTED])"
    }

    /** Pairing mirror of [requireStillCurrent]; the caller re-runs its consent join first. */
    fun requireDevicePairingStillCurrent(approval: PairingApproval, state: LocalCompanyAuthorityStateV2, actionDigest: PayloadDigest,
        consentDeadline: Long) {
        requireOwned(); issuance.requireIssuance(null)
        val company = registry.companyBootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val account = state.head
        if (approval.owner !== this || approval.actionDigest != actionDigest || approval.rootId != company.original.rootId ||
            approval.headAuthorityId != company.headAuthorityId || approval.headEnrollmentId != company.headEnrollmentId ||
            approval.accountRevision != account.accountRevision || approval.credentialRevision != account.credentialRevision ||
            approval.boot != currentBoot() || work.clock() >= approval.expires || approval.expires > consentDeadline ||
            state.original.revision < approval.retainedRevision || !account.enabled || account.reservationId != null ||
            account.failedAttempts != 0) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        work.requireActive(); issuance.requireIssuance(null)
    }

    /** Verified current boot ordinal of this environment (the only desktop boot fact). */
    fun currentBootOrdinal(): Int { requireOwned(); return currentBoot().ordinal }

    private class VerifiedCredential(val company: LocalCompanyAuthorityBootstrapV2, val account: LocalCompanyHeadAccountV1,
        val boot: SelfOwnedBootOrdinalLedgerV2, val retained: LocalCompanyAuthorityStateV2)

    /**
     * The shared durable-throttle credential core: [first] checks every observed state before the
     * attempt is charged, [checkCurrent] re-checks each later state (as on Android V1).
     */
    private fun verifyCredential(purpose: LocalCompanyHeadPurposeV1?, login: String, secret: CharArray,
        first: (LocalCompanyAuthorityStateV2) -> Unit, checkCurrent: (LocalCompanyAuthorityStateV2) -> Unit): VerifiedCredential {
        val company = registry.companyBootstrapRecordV2() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        val boot = currentBoot()
        var state = readCompany(company) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        var account = state.head
        first(state)
        val now = work.clock()
        if (account.throttleBoot > boot.ordinal) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (account.throttleBoot < boot.ordinal) {
            // Elapsed time cannot compare across boots: re-arm the ENTIRE retained cooldown; an
            // unfinished reservation already counted as a failure and is not forgiven.
            state = retainCompany(company, state, state.next(head = account.copy(throttleBoot = boot.ordinal, reservationId = null,
                nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(now, account.cooldownMillis))))
            account = state.head
            checkCurrent(state)
            if (account.cooldownMillis > 0L) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        }
        if (work.clock() < account.nextAllowedElapsedMillis) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        val failures = localNativeCredentialNextFailureCount(account.failedAttempts)
        val delay = localNativeCredentialFailureDelayMillis(failures)
        val reservation = JvmP256Primitives.newId()
        state = retainCompany(company, state, state.next(head = account.copy(failedAttempts = failures, reservationId = reservation,
            throttleBoot = boot.ordinal, cooldownMillis = delay,
            nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), delay))))
        account = state.head
        checkCurrent(state)
        if (account.reservationId != reservation) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        // The penalty is durable before derivation; cancellation never resets it.
        val salt = account.salt.copyBytes(); val expected = account.verifier.copyBytes()
        val actual = try { deriveHead(secret, company, account.credentialRevision, salt) }
        catch (failure: Throwable) { salt.fill(0); expected.fill(0); throw failure }
        val matched = try { JvmP256Primitives.same(expected, actual) && login == account.login }
        finally { salt.fill(0); expected.fill(0); actual.fill(0) }
        requireOwned(); issuance.requireIssuance(purpose)
        if (currentBoot() != boot) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        val current = readCompany(company) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val checked = current.head
        checkCurrent(current)
        if (checked.headAuthorityId != account.headAuthorityId || checked.headEnrollmentId != account.headEnrollmentId ||
            checked.accountRevision != account.accountRevision || checked.credentialRevision != account.credentialRevision ||
            purpose != null && checked.purposeRevision(purpose) != account.purposeRevision(purpose) ||
            checked.salt != account.salt || checked.verifier != account.verifier || checked.reservationId != reservation ||
            checked.throttleBoot != boot.ordinal) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        val completed = if (matched) checked.copy(failedAttempts = 0, reservationId = null, cooldownMillis = 0L,
            nextAllowedElapsedMillis = work.clock()) else checked.copy(reservationId = null,
            nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), checked.cooldownMillis))
        val retained = retainCompany(company, current, current.next(head = completed))
        if (!matched) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        work.requireActive(); issuance.requireIssuance(purpose)
        return VerifiedCredential(company, account, boot, retained)
    }

    /**
     * Re-checks [approval] against [state], the exact authenticated container state the caller is
     * about to commit with. Any withdrawal, revision change, pending reservation/penalty, boot change
     * or expiry refuses (AUTHENTICATION_REFUSED / ISSUANCE_WITHDRAWN).
     */
    fun requireStillCurrent(approval: Approval, state: LocalCompanyAuthorityStateV2, ceremony: SelfOwnedDesktopHeadCeremonyV2,
        originalActionId: DomainId, actionDigest: PayloadDigest) {
        requireOwned(); issuance.requireIssuance(ceremony.purpose)
        val company = registry.companyBootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val account = state.head
        val purpose = ceremony.purpose
        if (approval.owner !== this || approval.ceremony != ceremony || approval.originalActionId != originalActionId ||
            approval.actionDigest != actionDigest || approval.rootId != company.original.rootId ||
            approval.headAuthorityId != company.headAuthorityId || approval.headEnrollmentId != company.headEnrollmentId ||
            approval.accountRevision != account.accountRevision || approval.credentialRevision != account.credentialRevision ||
            approval.purposeRevision != purpose?.let { account.purposeRevision(it) } ||
            approval.keyRevision != purpose?.let { company.key(headKeyRole(it)).keyRevision } ||
            approval.boot != currentBoot() || work.clock() >= approval.expires || state.original.revision < approval.retainedRevision ||
            account.reservationId != null || account.failedAttempts != 0) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        requireCeremony(account, ceremony); work.requireActive(); issuance.requireIssuance(purpose)
    }

    /** Reads the authenticated current container (data only; holding it grants nothing). */
    fun readState(): LocalCompanyAuthorityStateV2? {
        requireOwned()
        val company = registry.companyBootstrapRecordV2() ?: return null
        return readCompany(company)
    }

    /**
     * Authenticated replacement of [previous] (which must be the exact current container) by [next]:
     * shared-core transition rules, seal, atomic replace and authenticated readback.
     */
    fun retainState(previous: LocalCompanyAuthorityStateV2, next: LocalCompanyAuthorityStateV2): LocalCompanyAuthorityStateV2 {
        requireOwned()
        val company = registry.companyBootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        return retainCompany(company, previous, next)
    }

    /**
     * Revocation (mirror of V1 withdrawHeadIssuance): the in-memory gate denies first, then the
     * container durably disables [purpose] (or the whole head for null) with exact revision bumps.
     */
    fun withdrawHeadIssuance(purpose: LocalCompanyHeadPurposeV1?) {
        issuance.deny(purpose); requireOwned()
        val company = registry.companyBootstrapRecordV2() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        val state = readCompany(company) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        val head = state.head
        val policyEnabled = head.policyEnabled && purpose != null && purpose != LocalCompanyHeadPurposeV1.POLICY_PUBLISH
        val installationEnabled = head.installationEnabled && purpose != null && purpose != LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE
        val enabled = head.enabled && purpose != null
        if (head.enabled == enabled && head.policyEnabled == policyEnabled && head.installationEnabled == installationEnabled) return
        if (enabled != head.enabled) capacityRevision(head.accountRevision)
        if (policyEnabled != head.policyEnabled) capacityRevision(head.policyRevision)
        if (installationEnabled != head.installationEnabled) capacityRevision(head.installationRevision)
        retainCompany(company, state, state.next(head = head.copy(enabled = enabled,
            accountRevision = head.accountRevision + (if (head.enabled != enabled) 1L else 0L),
            policyEnabled = policyEnabled, policyRevision = head.policyRevision + (if (head.policyEnabled != policyEnabled) 1L else 0L),
            installationEnabled = installationEnabled,
            installationRevision = head.installationRevision + (if (head.installationEnabled != installationEnabled) 1L else 0L))))
        requireOwned()
    }

    private fun requireCeremony(account: LocalCompanyHeadAccountV1, ceremony: SelfOwnedDesktopHeadCeremonyV2) {
        val enabled = ceremony.purpose?.let { account.purposeEnabled(it) } ?: account.enabled
        if (!enabled) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
    }

    private fun headKeyRole(purpose: LocalCompanyHeadPurposeV1): LocalCompanyHeadKeyRoleV1 = when (purpose) {
        LocalCompanyHeadPurposeV1.POLICY_PUBLISH -> LocalCompanyHeadKeyRoleV1.POLICY
        LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE -> LocalCompanyHeadKeyRoleV1.INSTALLATION
    }

    private fun deriveHead(secret: CharArray, company: LocalCompanyAuthorityBootstrapV2, credentialRevision: Long,
        salt: ByteArray): ByteArray = withDerivedMaterial(secret, salt) { derived ->
        val message = SelfOwnedNativeProvisionerWireV2.headVerifierMessage(company, credentialRevision, salt, derived)
        try { companyKeys.headTag(message) } finally { message.fill(0) }
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

    // ---- Authenticated container custody (same rules as SelfOwnedDesktopProvisionerV2) ----

    private fun readCompany(company: LocalCompanyAuthorityBootstrapV2): LocalCompanyAuthorityStateV2? {
        val original = registry.bootstrapRecordV2() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (!SelfOwnedProvisionerRegistryV2.sameBootstrap(company.original, original)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val bytes = registry.registryEnvelopeBytes() ?: return null
        return try {
            SelfOwnedCompanyAuthorityV2.read(company, bytes, companyKeys, currentBoot().ordinal)
        } finally { bytes.fill(0); registry.assertContinuity() }
    }

    private fun retainCompany(company: LocalCompanyAuthorityBootstrapV2, previous: LocalCompanyAuthorityStateV2,
        next: LocalCompanyAuthorityStateV2): LocalCompanyAuthorityStateV2 {
        requireOwned()
        val actual = readCompany(company)
        val expected = SelfOwnedNativeProvisionerWireV2.encodeCompanyState(previous)
        val observed = actual?.let { SelfOwnedNativeProvisionerWireV2.encodeCompanyState(it) }
        try { if (observed == null || !expected.contentEquals(observed)) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT) }
        finally { expected.fill(0); observed?.fill(0) }
        SelfOwnedCompanyAuthorityV2.requireTransition(actual, next)
        val bytes = SelfOwnedCompanyAuthorityV2.seal(company, next, companyKeys, currentBoot().ordinal)
        try { registry.replaceRegistryEnvelope(bytes) } finally { bytes.fill(0) }
        // Actual authenticated readback, not an in-memory acknowledgement.
        val readback = readCompany(company) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val a = SelfOwnedNativeProvisionerWireV2.encodeCompanyState(next); val b = SelfOwnedNativeProvisionerWireV2.encodeCompanyState(readback)
        try { if (!a.contentEquals(b)) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN) } finally { a.fill(0); b.fill(0) }
        return readback
    }

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

    private fun requireOwned() {
        if (closed.get()) selfOwnedFail(SelfOwnedInstallationFailureV1.NATIVE_RETIRED)
        work.requireActive(); environment.requireCurrent(); registry.assertContinuity(); requireDesktopEnvironment()
    }

    override fun toString(): String = "SelfOwnedDesktopCompanyHeadV2([REDACTED])"
}
