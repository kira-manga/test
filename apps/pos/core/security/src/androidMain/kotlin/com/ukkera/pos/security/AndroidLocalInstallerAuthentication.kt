package com.ukkera.pos.security

import android.util.Log
import com.ukkera.pos.types.DomainId
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.SecretKey
import javax.crypto.spec.PBEKeySpec
import javax.security.auth.Destroyable

/** Actual separate installer credentials; never a branch CredentialBinding or employee login. */
internal class AndroidLocalInstallerAuthentication(
    private val environment: AndroidSelfOwnedEnvironment,
    private val registry: AndroidLocalProvisionerRegistry,
    private val keys: AndroidLocalProvisionerKeys,
    private val work: AndroidOwnedInstallationWork,
    private val headIssuance: AndroidLocalCompanyIssuanceGate? = null,
) {
    private val originalAllocationPreparation = AtomicReference<OriginalAllocationPreparation?>(null)

    fun createFoundingInstaller(secret: CharArray): LocalProvisionerInstallerV1 =
        withFoundingInstaller(secret) { original, salt, verifier ->
            LocalProvisionerInstallerV1(original.installerId, original.facts.responsiblePersonName,
                original.facts.installerLogin, 0L, 0L, true, SelfOwnedOctetsV1(salt), SelfOwnedOctetsV1(verifier),
                0, null, environment.bootCount, work.clock(), 0L)
        }

    fun prepareFoundingInstaller(secret: CharArray): AndroidLocalFoundingVerifier =
        withFoundingInstaller(secret) { _, salt, verifier ->
            AndroidLocalFoundingVerifier(salt, verifier, environment.bootCount, work.clock())
        }

    private fun <T> withFoundingInstaller(secret: CharArray,
        use: (SelfOwnedBootstrapV1, ByteArray, ByteArray) -> T): T {
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        environment.requireCurrent()
        val original = registry.bootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (!original.complete() || original.enrollmentStarted || registry.readState(keys) != null) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ALREADY_ENROLLED)
        }
        val salt = AndroidP256Primitives.randomBytes(32)
        val verifier = try { derive(secret, original, original.installerId, 0L, salt) }
        catch (failure: Throwable) { salt.fill(0); throw failure }
        try {
            environment.requireCurrent()
            return use(original, salt, verifier)
        } finally { salt.fill(0); verifier.fill(0) }
    }

    /** Durable reservation/readback completes before derivation is admitted. No password is
     * retained by this result, and cancellation never resets the already retained penalty. */
    fun reserveOriginalAllocation(action: LocalProvisionerPendingAction, login: String): ReservedOriginalAllocation {
        try { selfOwnedLogin(login) } catch (_: IllegalArgumentException) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        }
        environment.requireCurrent()
        if (originalAllocationPreparation.get() != null) selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
        val allocationId = action.originalId
        val original = registry.bootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        var state = registry.readState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        requireIssuance(state, allocationId)
        var account = state.installers.single()
        if (!account.enabled) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        val now = work.clock()
        if (account.throttleBoot > environment.bootCount) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (account.throttleBoot < environment.bootCount) {
            // elapsedRealtime cannot compare across boots. Re-arm the ENTIRE retained cooldown;
            // a prior unfinished reservation already counts as failure and is not forgiven.
            val normalized = account.copy(throttleBoot = environment.bootCount, reservationId = null,
                nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(now, account.cooldownMillis))
            state = registry.retainState(state.replaceInstaller(normalized), keys)
            account = state.installers.single()
            if (account.cooldownMillis > 0L) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        }
        if (work.clock() < account.nextAllowedElapsedMillis) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        val failures = localNativeCredentialNextFailureCount(account.failedAttempts)
        val cooldown = localNativeCredentialFailureDelayMillis(failures)
        val reservation = AndroidP256Primitives.newId()
        val reserved = account.copy(failedAttempts = failures, reservationId = reservation,
            throttleBoot = environment.bootCount, cooldownMillis = cooldown,
            nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), cooldown))
        state = registry.retainState(state.replaceInstaller(reserved), keys)
        account = state.installers.single()
        if (account.reservationId != reservation) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val salt = account.salt.copyBytes()
        val expected = try { account.verifier.copyBytes() } catch (failure: Throwable) { salt.fill(0); throw failure }
        var scratch: AndroidLocalFoundingVerifier? = null
        var prepared: ReservedOriginalAllocation? = null
        try {
            scratch = AndroidLocalFoundingVerifier(salt, expected, environment.bootCount, work.clock())
            prepared = ReservedOriginalAllocation(original, action, account.installerId, account.credentialRevision,
                account.delegationRevision, reservation, login, state.revision, scratch)
            if (!originalAllocationPreparation.compareAndSet(null, prepared)) selfOwnedFail(SelfOwnedInstallationFailureV1.BUSY)
            return prepared
        } catch (failure: Throwable) {
            prepared?.let { originalAllocationPreparation.compareAndSet(it, null) }
            scratch?.clear(); throw failure
        } finally { salt.fill(0); expected.fill(0) }
    }

    /** Read/derive only, under its own ordinary admission. This disposable verifier is NOT
     * an approval; final native work must consume it once and authenticate fresh state. */
    fun deriveOriginalAllocation(prepared: ReservedOriginalAllocation, secret: CharArray): DerivedOriginalAllocation {
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        if (originalAllocationPreparation.get() !== prepared) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        environment.requireCurrent()
        val current = registry.readState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireReservedOriginalAllocation(prepared, current)
        val salt = prepared.expected.copyIntoAccount { salt, _ -> salt.copyOf() }
        var actual: ByteArray? = null
        var reservedCopy: ReservedOriginalAllocation? = null
        var verifier: AndroidLocalFoundingVerifier? = null
        var derived: DerivedOriginalAllocation? = null
        try {
            actual = derive(secret, prepared.original, prepared.installerId, prepared.credentialRevision, salt)
            environment.requireCurrent()
            if (registry.bootstrapRecord() !== prepared.original) selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
            reservedCopy = copyReservedOriginalAllocation(prepared)
            verifier = AndroidLocalFoundingVerifier(salt, actual, environment.bootCount, work.clock())
            derived = DerivedOriginalAllocation(reservedCopy, verifier)
            if (!originalAllocationPreparation.compareAndSet(prepared, derived)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            }
            return derived
        } catch (failure: Throwable) {
            derived?.let { originalAllocationPreparation.compareAndSet(it, null) }
            originalAllocationPreparation.compareAndSet(prepared, null)
            reservedCopy?.clear(); verifier?.clear(); throw failure
        } finally { prepared.clear(); salt.fill(0); actual?.fill(0) }
    }

    /** No approval crosses native operations. Completion, signing and final readback use the
     * final worker's deadline AND the unchanged original action expiry. */
    fun completeOriginalAllocation(prepared: DerivedOriginalAllocation): Approval {
        if (!originalAllocationPreparation.compareAndSet(prepared, null)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        }
        try {
            environment.requireCurrent()
            val reserved = prepared.reservation
            val actionDeadline = reserved.action.expiresElapsedMillis
            val current = registry.readState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            val checked = requireReservedOriginalAllocation(reserved, current)
            if (prepared.actual.boot != environment.bootCount || work.clock() >= actionDeadline) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            }
            val matched = prepared.actual.copyIntoAccount { salt, actual ->
                val expectedSalt = checked.salt.copyBytes()
                val expected = try { checked.verifier.copyBytes() }
                catch (failure: Throwable) { expectedSalt.fill(0); throw failure }
                try { AndroidP256Primitives.same(expectedSalt, salt) && AndroidP256Primitives.same(expected, actual) &&
                    reserved.login == checked.login }
                finally { expectedSalt.fill(0); expected.fill(0) }
            }
            val completed = if (matched) checked.copy(failedAttempts = 0, reservationId = null, cooldownMillis = 0L,
                nextAllowedElapsedMillis = work.clock()) else checked.copy(reservationId = null,
                nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), checked.cooldownMillis))
            val retained = registry.retainState(current.replaceInstaller(completed), keys)
            if (!matched) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
            work.requireActive()
            val expires = minOf(actionDeadline, work.currentDeadlineMillis())
            if (work.clock() >= expires) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            return Approval(this, reserved.allocationId, reserved.original.rootId, reserved.installerId, reserved.credentialRevision,
                reserved.delegationRevision, environment.bootCount, expires, retained.revision)
        } finally { prepared.clear() }
    }

    private fun requireReservedOriginalAllocation(prepared: ReservedOriginalAllocation,
        state: LocalProvisionerRegistryStateV1): LocalProvisionerInstallerV1 {
        environment.requireCurrent()
        if (registry.bootstrapRecord() !== prepared.original || prepared.expected.boot != environment.bootCount ||
            prepared.action.boot != environment.bootCount || work.clock() >= prepared.action.expiresElapsedMillis) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        }
        requireIssuance(state, prepared.allocationId)
        val checked = state.installers.single()
        if (state.revision < prepared.retainedRevision || !checked.enabled || checked.installerId != prepared.installerId ||
            checked.credentialRevision != prepared.credentialRevision || checked.delegationRevision != prepared.delegationRevision ||
            checked.reservationId != prepared.reservationId || checked.throttleBoot != environment.bootCount) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        }
        val sameCredential = prepared.expected.copyIntoAccount { salt, verifier ->
            val currentSalt = checked.salt.copyBytes()
            val currentVerifier = try { checked.verifier.copyBytes() }
            catch (failure: Throwable) { currentSalt.fill(0); throw failure }
            try { AndroidP256Primitives.same(currentSalt, salt) && AndroidP256Primitives.same(currentVerifier, verifier) }
            finally { currentSalt.fill(0); currentVerifier.fill(0) }
        }
        if (!sameCredential) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        work.requireActive()
        return checked
    }

    /** Bounded scratch-only transfers, called by task factories AFTER ordinary admission. */
    fun adoptOriginalAllocationReservation(prepared: ReservedOriginalAllocation): ReservedOriginalAllocation {
        val adopted = copyReservedOriginalAllocation(prepared)
        try {
            if (!originalAllocationPreparation.compareAndSet(prepared, adopted)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            }
            prepared.clear(); return adopted
        } catch (failure: Throwable) {
            originalAllocationPreparation.compareAndSet(adopted, null)
            adopted.clear(); throw failure
        }
    }

    fun adoptOriginalAllocationVerification(prepared: DerivedOriginalAllocation): DerivedOriginalAllocation {
        val reserved = copyReservedOriginalAllocation(prepared.reservation)
        var actual: AndroidLocalFoundingVerifier? = null
        var adopted: DerivedOriginalAllocation? = null
        try {
            actual = prepared.actual.copyForExecution()
            adopted = DerivedOriginalAllocation(reserved, actual)
            if (!originalAllocationPreparation.compareAndSet(prepared, adopted)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
            }
            prepared.clear(); return adopted
        } catch (failure: Throwable) {
            adopted?.let { originalAllocationPreparation.compareAndSet(it, null) }
            reserved.clear(); actual?.clear(); throw failure
        }
    }

    private fun copyReservedOriginalAllocation(prepared: ReservedOriginalAllocation): ReservedOriginalAllocation {
        val expected = prepared.expected.copyForExecution()
        return try { ReservedOriginalAllocation(prepared.original, prepared.action, prepared.installerId,
            prepared.credentialRevision, prepared.delegationRevision, prepared.reservationId, prepared.login,
            prepared.retainedRevision, expected) }
        catch (failure: Throwable) { expected.clear(); throw failure }
    }

    /** Pure disposable-buffer cleanup. Never resets a reservation, penalty or native custody. */
    fun discardOriginalAllocationPreparation(prepared: OriginalAllocationPreparation) {
        originalAllocationPreparation.compareAndSet(prepared, null)
        prepared.clear()
    }

    internal sealed interface OriginalAllocationPreparation { fun clear() }

    internal class ReservedOriginalAllocation(val original: SelfOwnedBootstrapV1, val action: LocalProvisionerPendingAction,
        val installerId: DomainId, val credentialRevision: Long, val delegationRevision: Long, val reservationId: DomainId,
        val login: String, val retainedRevision: Long, val expected: AndroidLocalFoundingVerifier): OriginalAllocationPreparation {
        val allocationId: DomainId get() = action.originalId
        override fun clear() { expected.clear() }
        override fun toString(): String = "ReservedOriginalInstallerAllocation([REDACTED])"
    }

    internal class DerivedOriginalAllocation(val reservation: ReservedOriginalAllocation,
        val actual: AndroidLocalFoundingVerifier): OriginalAllocationPreparation {
        override fun clear() { reservation.clear(); actual.clear() }
        override fun toString(): String = "DerivedOriginalInstallerAllocation([REDACTED])"
    }

    /** Invocation-local result of actual verification; not a reusable public installer session. */
    internal class Approval internal constructor(
        internal val owner: AndroidLocalInstallerAuthentication,
        internal val allocationId: DomainId,
        internal val rootId: DomainId,
        internal val installerId: DomainId,
        internal val credentialRevision: Long,
        internal val delegationRevision: Long,
        internal val boot: Int,
        internal val expires: Long,
        internal val retainedRevision: Long,
    ) {
        override fun toString(): String = "LocalInstallerApproval([REDACTED])"
    }

    fun requireStillCurrent(approval: Approval, state: LocalProvisionerRegistryStateV1, allocationId: DomainId) {
        environment.requireCurrent()
        val original = registry.bootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val account = state.installers.single()
        if (approval.owner !== this || approval.rootId != original.rootId || approval.allocationId != allocationId ||
            approval.boot != environment.bootCount || work.clock() >= approval.expires || state.revision < approval.retainedRevision ||
            account.installerId != approval.installerId || account.credentialRevision != approval.credentialRevision ||
            account.delegationRevision != approval.delegationRevision || !account.enabled || account.reservationId != null ||
            account.failedAttempts != 0) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        requireIssuance(state, allocationId)
        work.requireActive()
    }

    private fun requireIssuance(state: LocalProvisionerRegistryStateV1, allocationId: DomainId) {
        if (!state.issuanceEnabled) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        val allocation = state.allocations.singleOrNull { it.body.coordinates.allocationId == allocationId }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (allocation.phase == LocalProvisionerAllocationPhaseV1.GRANT_RETAINED) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        }
    }

    fun prepareFoundingHead(secret: CharArray): AndroidLocalFoundingVerifier {
        requireHeadIssuance(null)
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        environment.requireCurrent()
        val original = registry.companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        if (!original.complete() || original.original.enrollmentStarted || registry.readState(keys) != null) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.ALREADY_ENROLLED)
        }
        val salt = AndroidP256Primitives.randomBytes(32)
        val verifier = try { deriveHead(secret, original, 0L, salt) }
        catch (failure: Throwable) { salt.fill(0); throw failure }
        return try {
            environment.requireCurrent(); work.requireActive(); requireHeadIssuance(null)
            AndroidLocalFoundingVerifier(salt, verifier, environment.bootCount, work.clock())
        } finally { salt.fill(0); verifier.fill(0) }
    }

    /** Same concrete derivation owner; independent pepper, binding, bucket and purpose grant. */
    fun authenticateHeadAction(purpose: LocalCompanyHeadPurposeV1, originalActionId: DomainId,
        actionDigest: PayloadDigest, login: String, secret: CharArray, actionDeadline: Long): HeadApproval {
        requireHeadIssuance(purpose)
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        try { selfOwnedLogin(login) } catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        environment.requireCurrent()
        if (work.clock() >= actionDeadline) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val original = registry.companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        var state = registry.readCompanyState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        var account = state.head
        requireHeadPurpose(account, purpose)
        val now = work.clock()
        if (account.throttleBoot > environment.bootCount) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (account.throttleBoot < environment.bootCount) {
            val normalized = account.copy(throttleBoot = environment.bootCount, reservationId = null,
                nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(now, account.cooldownMillis))
            state = registry.retainCompanyState(state.next(head = normalized), keys)
            account = state.head
            if (account.cooldownMillis > 0L) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        }
        if (work.clock() < account.nextAllowedElapsedMillis) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        val failures = localNativeCredentialNextFailureCount(account.failedAttempts)
        val delay = localNativeCredentialFailureDelayMillis(failures)
        val reservation = AndroidP256Primitives.newId()
        val reserved = account.copy(failedAttempts = failures, reservationId = reservation,
            throttleBoot = environment.bootCount, cooldownMillis = delay, nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), delay))
        state = registry.retainCompanyState(state.next(head = reserved), keys)
        account = state.head
        if (account.reservationId != reservation) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val salt = account.salt.copyBytes(); val expected = account.verifier.copyBytes()
        val actual = try { deriveHead(secret, original, account.credentialRevision, salt) }
        catch (failure: Throwable) { salt.fill(0); expected.fill(0); throw failure }
        val matched = try { AndroidP256Primitives.same(expected, actual) && login == account.login }
        finally { salt.fill(0); expected.fill(0); actual.fill(0) }
        environment.requireCurrent(); requireHeadIssuance(purpose)
        val current = registry.readCompanyState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val checked = current.head
        requireHeadPurpose(checked, purpose)
        if (checked.headAuthorityId != account.headAuthorityId || checked.headEnrollmentId != account.headEnrollmentId ||
            checked.accountRevision != account.accountRevision || checked.credentialRevision != account.credentialRevision ||
            checked.purposeRevision(purpose) != account.purposeRevision(purpose) || checked.salt != account.salt ||
            checked.verifier != account.verifier || checked.reservationId != reservation || checked.throttleBoot != environment.bootCount) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        }
        val completed = if (matched) checked.copy(failedAttempts = 0, reservationId = null, cooldownMillis = 0L,
            nextAllowedElapsedMillis = work.clock()) else checked.copy(reservationId = null,
            nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), checked.cooldownMillis))
        val retained = registry.retainCompanyState(current.next(head = completed), keys)
        if (!matched) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        work.requireActive(); requireHeadIssuance(purpose)
        val expires = minOf(actionDeadline, work.currentDeadlineMillis())
        if (work.clock() >= expires) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return HeadApproval(this, purpose, originalActionId, actionDigest, original.original.rootId, original.headAuthorityId,
            original.headEnrollmentId, account.accountRevision, account.credentialRevision, account.purposeRevision(purpose),
            original.key(headKeyRole(purpose)).keyRevision, environment.bootCount, expires, retained.original.revision)
    }

    /** Explicit new business ceremony: authenticate the named head, without borrowing either V1 purpose. */
    fun authenticateBusinessPublicationAction(originalActionId: DomainId, actionDigest: PayloadDigest,
        login: String, secret: CharArray, actionDeadline: Long): BusinessPublicationHeadApproval {
        requireHeadIssuance(null)
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        try { selfOwnedLogin(login) } catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        environment.requireCurrent()
        if (work.clock() >= actionDeadline) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val original = registry.companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        var state = registry.readCompanyState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        var account = state.head
        if (!account.enabled) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        val now = work.clock()
        if (account.throttleBoot > environment.bootCount) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (account.throttleBoot < environment.bootCount) {
            state = registry.retainCompanyState(state.next(head = account.copy(throttleBoot = environment.bootCount,
                reservationId = null, nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(now, account.cooldownMillis))), keys)
            account = state.head
            if (account.cooldownMillis > 0L) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        }
        if (work.clock() < account.nextAllowedElapsedMillis) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        val failures = localNativeCredentialNextFailureCount(account.failedAttempts)
        val delay = localNativeCredentialFailureDelayMillis(failures)
        val reservation = AndroidP256Primitives.newId()
        state = registry.retainCompanyState(state.next(head = account.copy(failedAttempts = failures,
            reservationId = reservation, throttleBoot = environment.bootCount, cooldownMillis = delay,
            nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), delay))), keys)
        account = state.head
        if (account.reservationId != reservation) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val salt = account.salt.copyBytes(); val expected = account.verifier.copyBytes()
        val actual = try { deriveHead(secret, original, account.credentialRevision, salt) }
        catch (failure: Throwable) { salt.fill(0); expected.fill(0); throw failure }
        val matched = try { AndroidP256Primitives.same(expected, actual) && login == account.login }
        finally { salt.fill(0); expected.fill(0); actual.fill(0) }
        environment.requireCurrent(); requireHeadIssuance(null)
        val current = registry.readCompanyState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val checked = current.head
        if (!checked.enabled || checked.headAuthorityId != account.headAuthorityId || checked.headEnrollmentId != account.headEnrollmentId ||
            checked.accountRevision != account.accountRevision || checked.credentialRevision != account.credentialRevision ||
            checked.salt != account.salt || checked.verifier != account.verifier || checked.reservationId != reservation ||
            checked.throttleBoot != environment.bootCount) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        val completed = if (matched) checked.copy(failedAttempts = 0, reservationId = null, cooldownMillis = 0L,
            nextAllowedElapsedMillis = work.clock()) else checked.copy(reservationId = null,
            nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), checked.cooldownMillis))
        val retained = registry.retainCompanyState(current.next(head = completed), keys)
        if (!matched) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        work.requireActive(); requireHeadIssuance(null)
        val expires = minOf(actionDeadline, work.currentDeadlineMillis())
        if (work.clock() >= expires) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return BusinessPublicationHeadApproval(this, originalActionId, actionDigest, original.original.rootId,
            original.headAuthorityId, original.headEnrollmentId, account.accountRevision, account.credentialRevision,
            environment.bootCount, expires, retained.original.revision)
    }

    internal class BusinessPublicationHeadApproval internal constructor(
        internal val owner: AndroidLocalInstallerAuthentication,
        internal val originalActionId: DomainId,
        internal val actionDigest: PayloadDigest,
        internal val rootId: DomainId,
        internal val headAuthorityId: DomainId,
        internal val headEnrollmentId: DomainId,
        internal val accountRevision: Long,
        internal val credentialRevision: Long,
        internal val boot: Int,
        internal val expires: Long,
        internal val retainedRevision: Long,
    ) {
        override fun toString(): String = "BusinessPublicationHeadApproval([REDACTED])"
    }

    fun requireBusinessPublicationStillCurrent(approval: BusinessPublicationHeadApproval, state: LocalCompanyAuthorityStateV1,
        originalActionId: DomainId, actionDigest: PayloadDigest) {
        environment.requireCurrent(); requireHeadIssuance(null)
        val original = registry.companyBootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val account = state.head
        if (approval.owner !== this || approval.originalActionId != originalActionId || approval.actionDigest != actionDigest ||
            approval.rootId != original.original.rootId || approval.headAuthorityId != original.headAuthorityId ||
            approval.headEnrollmentId != original.headEnrollmentId || approval.accountRevision != account.accountRevision ||
            approval.credentialRevision != account.credentialRevision || approval.boot != environment.bootCount ||
            work.clock() >= approval.expires || state.original.revision < approval.retainedRevision || !account.enabled ||
            account.reservationId != null || account.failedAttempts != 0) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        work.requireActive(); requireHeadIssuance(null)
    }

    /** Explicit new employee ceremony: authenticate the named head, without borrowing business or either V1 purpose. */
    fun authenticateEmployeeEnrollmentAction(originalActionId: DomainId, actionDigest: PayloadDigest,
        login: String, secret: CharArray, actionDeadline: Long): EmployeeEnrollmentHeadApproval {
        requireHeadIssuance(null)
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        try { selfOwnedLogin(login) } catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        environment.requireCurrent()
        if (work.clock() >= actionDeadline) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val original = registry.companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        var state = registry.readCompanyState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        var account = state.head
        if (!account.enabled) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        val now = work.clock()
        if (account.throttleBoot > environment.bootCount) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (account.throttleBoot < environment.bootCount) {
            state = registry.retainCompanyState(state.next(head = account.copy(throttleBoot = environment.bootCount,
                reservationId = null, nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(now, account.cooldownMillis))), keys)
            account = state.head
            if (account.cooldownMillis > 0L) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        }
        if (work.clock() < account.nextAllowedElapsedMillis) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        val failures = localNativeCredentialNextFailureCount(account.failedAttempts)
        val delay = localNativeCredentialFailureDelayMillis(failures)
        val reservation = AndroidP256Primitives.newId()
        state = registry.retainCompanyState(state.next(head = account.copy(failedAttempts = failures,
            reservationId = reservation, throttleBoot = environment.bootCount, cooldownMillis = delay,
            nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), delay))), keys)
        account = state.head
        if (account.reservationId != reservation) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val salt = account.salt.copyBytes(); val expected = account.verifier.copyBytes()
        val actual = try { deriveHead(secret, original, account.credentialRevision, salt) }
        catch (failure: Throwable) { salt.fill(0); expected.fill(0); throw failure }
        val matched = try { AndroidP256Primitives.same(expected, actual) && login == account.login }
        finally { salt.fill(0); expected.fill(0); actual.fill(0) }
        environment.requireCurrent(); requireHeadIssuance(null)
        val current = registry.readCompanyState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val checked = current.head
        if (!checked.enabled || checked.headAuthorityId != account.headAuthorityId || checked.headEnrollmentId != account.headEnrollmentId ||
            checked.accountRevision != account.accountRevision || checked.credentialRevision != account.credentialRevision ||
            checked.salt != account.salt || checked.verifier != account.verifier || checked.reservationId != reservation ||
            checked.throttleBoot != environment.bootCount) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        val completed = if (matched) checked.copy(failedAttempts = 0, reservationId = null, cooldownMillis = 0L,
            nextAllowedElapsedMillis = work.clock()) else checked.copy(reservationId = null,
            nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), checked.cooldownMillis))
        val retained = registry.retainCompanyState(current.next(head = completed), keys)
        if (!matched) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        work.requireActive(); requireHeadIssuance(null)
        val expires = minOf(actionDeadline, work.currentDeadlineMillis())
        if (work.clock() >= expires) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return EmployeeEnrollmentHeadApproval(this, originalActionId, actionDigest, original.original.rootId,
            original.headAuthorityId, original.headEnrollmentId, account.accountRevision, account.credentialRevision,
            environment.bootCount, expires, retained.original.revision)
    }

    internal class EmployeeEnrollmentHeadApproval internal constructor(
        internal val owner: AndroidLocalInstallerAuthentication,
        internal val originalActionId: DomainId,
        internal val actionDigest: PayloadDigest,
        internal val rootId: DomainId,
        internal val headAuthorityId: DomainId,
        internal val headEnrollmentId: DomainId,
        internal val accountRevision: Long,
        internal val credentialRevision: Long,
        internal val boot: Int,
        internal val expires: Long,
        internal val retainedRevision: Long,
    ) {
        override fun toString(): String = "EmployeeEnrollmentHeadApproval([REDACTED])"
    }

    fun requireEmployeeEnrollmentStillCurrent(approval: EmployeeEnrollmentHeadApproval, state: LocalCompanyAuthorityStateV1,
        originalActionId: DomainId, actionDigest: PayloadDigest) {
        environment.requireCurrent(); requireHeadIssuance(null)
        val original = registry.companyBootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val account = state.head
        if (approval.owner !== this || approval.originalActionId != originalActionId || approval.actionDigest != actionDigest ||
            approval.rootId != original.original.rootId || approval.headAuthorityId != original.headAuthorityId ||
            approval.headEnrollmentId != original.headEnrollmentId || approval.accountRevision != account.accountRevision ||
            approval.credentialRevision != account.credentialRevision || approval.boot != environment.bootCount ||
            work.clock() >= approval.expires || state.original.revision < approval.retainedRevision || !account.enabled ||
            account.reservationId != null || account.failedAttempts != 0) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        work.requireActive(); requireHeadIssuance(null)
    }

    /** Explicit new employee-policy ceremony: authenticate the named head, without borrowing business, enrollment or either V1 purpose. */
    fun authenticateEmployeePolicyActivationAction(originalActionId: DomainId, actionDigest: PayloadDigest,
        login: String, secret: CharArray, actionDeadline: Long): EmployeePolicyActivationHeadApproval {
        requireHeadIssuance(null)
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        try { selfOwnedLogin(login) } catch (_: IllegalArgumentException) { selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT) }
        environment.requireCurrent()
        if (work.clock() >= actionDeadline) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val original = registry.companyBootstrapRecord() ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        var state = registry.readCompanyState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        var account = state.head
        if (!account.enabled) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
        val now = work.clock()
        if (account.throttleBoot > environment.bootCount) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (account.throttleBoot < environment.bootCount) {
            state = registry.retainCompanyState(state.next(head = account.copy(throttleBoot = environment.bootCount,
                reservationId = null, nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(now, account.cooldownMillis))), keys)
            account = state.head
            if (account.cooldownMillis > 0L) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        }
        if (work.clock() < account.nextAllowedElapsedMillis) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        val failures = localNativeCredentialNextFailureCount(account.failedAttempts)
        val delay = localNativeCredentialFailureDelayMillis(failures)
        val reservation = AndroidP256Primitives.newId()
        state = registry.retainCompanyState(state.next(head = account.copy(failedAttempts = failures,
            reservationId = reservation, throttleBoot = environment.bootCount, cooldownMillis = delay,
            nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), delay))), keys)
        account = state.head
        if (account.reservationId != reservation) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val salt = account.salt.copyBytes(); val expected = account.verifier.copyBytes()
        val actual = try { deriveHead(secret, original, account.credentialRevision, salt) }
        catch (failure: Throwable) { salt.fill(0); expected.fill(0); throw failure }
        val matched = try { AndroidP256Primitives.same(expected, actual) && login == account.login }
        finally { salt.fill(0); expected.fill(0); actual.fill(0) }
        environment.requireCurrent(); requireHeadIssuance(null)
        val current = registry.readCompanyState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val checked = current.head
        if (!checked.enabled || checked.headAuthorityId != account.headAuthorityId || checked.headEnrollmentId != account.headEnrollmentId ||
            checked.accountRevision != account.accountRevision || checked.credentialRevision != account.credentialRevision ||
            checked.salt != account.salt || checked.verifier != account.verifier || checked.reservationId != reservation ||
            checked.throttleBoot != environment.bootCount) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        val completed = if (matched) checked.copy(failedAttempts = 0, reservationId = null, cooldownMillis = 0L,
            nextAllowedElapsedMillis = work.clock()) else checked.copy(reservationId = null,
            nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), checked.cooldownMillis))
        val retained = registry.retainCompanyState(current.next(head = completed), keys)
        if (!matched) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        work.requireActive(); requireHeadIssuance(null)
        val expires = minOf(actionDeadline, work.currentDeadlineMillis())
        if (work.clock() >= expires) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return EmployeePolicyActivationHeadApproval(this, originalActionId, actionDigest, original.original.rootId,
            original.headAuthorityId, original.headEnrollmentId, account.accountRevision, account.credentialRevision,
            environment.bootCount, expires, retained.original.revision)
    }

    internal class EmployeePolicyActivationHeadApproval internal constructor(
        internal val owner: AndroidLocalInstallerAuthentication,
        internal val originalActionId: DomainId,
        internal val actionDigest: PayloadDigest,
        internal val rootId: DomainId,
        internal val headAuthorityId: DomainId,
        internal val headEnrollmentId: DomainId,
        internal val accountRevision: Long,
        internal val credentialRevision: Long,
        internal val boot: Int,
        internal val expires: Long,
        internal val retainedRevision: Long,
    ) {
        override fun toString(): String = "EmployeePolicyActivationHeadApproval([REDACTED])"
    }

    fun requireEmployeePolicyActivationStillCurrent(approval: EmployeePolicyActivationHeadApproval, state: LocalCompanyAuthorityStateV1,
        originalActionId: DomainId, actionDigest: PayloadDigest) {
        environment.requireCurrent(); requireHeadIssuance(null)
        val original = registry.companyBootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val account = state.head
        if (approval.owner !== this || approval.originalActionId != originalActionId || approval.actionDigest != actionDigest ||
            approval.rootId != original.original.rootId || approval.headAuthorityId != original.headAuthorityId ||
            approval.headEnrollmentId != original.headEnrollmentId || approval.accountRevision != account.accountRevision ||
            approval.credentialRevision != account.credentialRevision || approval.boot != environment.bootCount ||
            work.clock() >= approval.expires || state.original.revision < approval.retainedRevision || !account.enabled ||
            account.reservationId != null || account.failedAttempts != 0) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        work.requireActive(); requireHeadIssuance(null)
    }

    // BEGIN isolated device-pairing head authority V1 (old ceremonies stay unchanged).
    /** NEW explicit named-head consent, not an enrollment/business/D7 purpose grant.
     * The actual native participant has already forced THIS full one-action consent intent.
     * It is authenticated here using the genuine named head's credential and account-wide
     * retained throttle. A consent intent alone, or a matching old purpose, is not approval. */
    fun authenticateDevicePairingAction(record: NativeDevicePairingRecordV1, login: String,
        secret: CharArray): DevicePairingHeadApproval {
        requireHeadIssuance(null)
        if (!selfOwnedPasswordInput(secret)) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        try { selfOwnedLogin(login) } catch (_: IllegalArgumentException) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_INPUT)
        }
        environment.requireCurrent()
        if (record.phase != LocalDevicePairingPhase.HEAD_CONSENT_INTENT)
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val original = registry.companyBootstrapRecord()
            ?: localCompanyRefuse(LocalCompanyAuthorityFailure.HEAD_ENROLLMENT_REQUIRED)
        var state = registry.readCompanyState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        requireDevicePairingConsent(record, state)
        var account = state.head
        val now = work.clock()
        if (account.throttleBoot > environment.bootCount) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        if (account.throttleBoot < environment.bootCount) {
            state = registry.retainCompanyState(state.next(head = account.copy(throttleBoot = environment.bootCount,
                reservationId = null, nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(now, account.cooldownMillis))), keys)
            account = state.head
            requireDevicePairingConsent(record, state)
            if (account.cooldownMillis > 0L) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        }
        if (work.clock() < account.nextAllowedElapsedMillis) selfOwnedFail(SelfOwnedInstallationFailureV1.THROTTLED)
        val failures = localNativeCredentialNextFailureCount(account.failedAttempts)
        val delay = localNativeCredentialFailureDelayMillis(failures)
        val reservation = AndroidP256Primitives.newId()
        state = registry.retainCompanyState(state.next(head = account.copy(failedAttempts = failures,
            reservationId = reservation, throttleBoot = environment.bootCount, cooldownMillis = delay,
            nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), delay))), keys)
        account = state.head
        requireDevicePairingConsent(record, state)
        if (account.reservationId != reservation) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val salt = account.salt.copyBytes(); val expected = account.verifier.copyBytes()
        val actual = try { deriveHead(secret, original, account.credentialRevision, salt) }
        catch (failure: Throwable) { salt.fill(0); expected.fill(0); throw failure }
        val matched = try { AndroidP256Primitives.same(expected, actual) && login == account.login }
        finally { salt.fill(0); expected.fill(0); actual.fill(0) }
        environment.requireCurrent(); requireHeadIssuance(null)
        val current = registry.readCompanyState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        requireDevicePairingConsent(record, current)
        val checked = current.head
        if (!checked.enabled || checked.headAuthorityId != account.headAuthorityId || checked.headEnrollmentId != account.headEnrollmentId ||
            checked.accountRevision != account.accountRevision || checked.credentialRevision != account.credentialRevision ||
            checked.salt != account.salt || checked.verifier != account.verifier || checked.reservationId != reservation ||
            checked.throttleBoot != environment.bootCount) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        val completed = if (matched) checked.copy(failedAttempts = 0, reservationId = null, cooldownMillis = 0L,
            nextAllowedElapsedMillis = work.clock()) else checked.copy(reservationId = null,
            nextAllowedElapsedMillis = localNativeCredentialDeadlineMillis(work.clock(), checked.cooldownMillis))
        val retained = registry.retainCompanyState(current.next(head = completed), keys)
        if (!matched) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        work.requireActive(); requireHeadIssuance(null); requireDevicePairingConsent(record, retained)
        val consent = requireNotNull(record.consent)
        val expires = minOf(consent.deadlineElapsedMillis, work.currentDeadlineMillis())
        if (work.clock() >= expires) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        return DevicePairingHeadApproval(this, record.body, requireNotNull(record.possession), consent,
            consent.actionDigest, original.original.rootId, original.headAuthorityId, original.headEnrollmentId,
            account.accountRevision, account.credentialRevision, environment.bootCount, expires, retained.original.revision)
    }

    /** Invocation-local, fully action-specific result of REAL credential verification.
     * Full immutable body includes original root/head/native cut, requested device/key/point,
     * exact definition/request/census, nonce and challenge deadline. No generic head session. */
    internal class DevicePairingHeadApproval internal constructor(
        internal val owner: AndroidLocalInstallerAuthentication,
        internal val body: NativeDevicePairingBodyV1,
        internal val possession: SelfOwnedOctetsV1,
        internal val consent: NativeDevicePairingConsentV1,
        internal val actionDigest: PayloadDigest,
        internal val rootId: DomainId,
        internal val headAuthorityId: DomainId,
        internal val headEnrollmentId: DomainId,
        internal val accountRevision: Long,
        internal val credentialRevision: Long,
        internal val boot: Int,
        internal val expires: Long,
        internal val retainedRevision: Long,
    ) {
        override fun toString(): String = "DevicePairingHeadApproval([REDACTED])"
    }

    fun requireDevicePairingStillCurrent(approval: DevicePairingHeadApproval, state: LocalCompanyAuthorityStateV1,
        record: NativeDevicePairingRecordV1) {
        environment.requireCurrent(); requireHeadIssuance(null)
        requireDevicePairingConsent(record, state)
        val original = registry.companyBootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val account = state.head
        if (approval.owner !== this || approval.body != record.body || approval.possession != record.possession ||
            approval.consent != record.consent || approval.actionDigest != record.consent?.actionDigest ||
            approval.rootId != original.original.rootId || approval.headAuthorityId != original.headAuthorityId ||
            approval.headEnrollmentId != original.headEnrollmentId || approval.accountRevision != account.accountRevision ||
            approval.credentialRevision != account.credentialRevision || approval.boot != environment.bootCount ||
            work.clock() >= approval.expires || approval.expires > requireNotNull(record.consent).deadlineElapsedMillis ||
            state.original.revision < approval.retainedRevision || !account.enabled || account.reservationId != null ||
            account.failedAttempts != 0) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        record.approvedHead?.let {
            if (it.accountRevision != approval.accountRevision || it.credentialRevision != approval.credentialRevision ||
                it.retainedRevision != approval.retainedRevision || it.osBootCount != approval.boot ||
                it.deadlineElapsedMillis != approval.expires) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        }
        work.requireActive(); requireHeadIssuance(null)
    }

    /** Independent registry/root/head/proof join, not a supplied consent/current callback. */
    private fun requireDevicePairingConsent(record: NativeDevicePairingRecordV1, state: LocalCompanyAuthorityStateV1) {
        environment.requireCurrent(); work.requireActive(); requireHeadIssuance(null)
        if (record.phase !in setOf(LocalDevicePairingPhase.HEAD_CONSENT_INTENT,
                LocalDevicePairingPhase.SIGNING_INTENT, LocalDevicePairingPhase.APPROVED))
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val retained = registry.readDevicePairings(keys)?.records?.singleOrNull {
            it.body.originalActionId == record.body.originalActionId
        } ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (retained != record) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val original = registry.companyBootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val origin = record.body.origin; val prepared = record.body.preparedHead; val consent = requireNotNull(record.consent)
        val account = state.head
        val rootBytes = state.original.root.copyWireBytes()
        val rootDigest = try { sha256(rootBytes) } finally { rootBytes.fill(0) }
        val action = NativeDevicePairingWireV1.actionMessage(record)
        val digest = try { sha256(action) } finally { action.fill(0) }
        if (origin.rootId != original.original.rootId || origin.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            origin.rootDocumentDigest != rootDigest || origin.foundingActionId != original.original.actionId ||
            origin.headAuthorityId != original.headAuthorityId || origin.headEnrollmentId != original.headEnrollmentId ||
            origin.rootFingerprint != AndroidP256Primitives.rootFingerprint(keys.rootBody()) || consent.actionDigest != digest ||
            !account.enabled || account.headAuthorityId != origin.headAuthorityId || account.headEnrollmentId != origin.headEnrollmentId ||
            account.accountRevision != prepared.accountRevision || account.credentialRevision != prepared.credentialRevision ||
            account.name != prepared.headName || account.login != prepared.headLogin ||
            account.accountRevision != consent.accountRevision || account.credentialRevision != consent.credentialRevision ||
            state.original.revision < consent.retainedRevision || environment.bootCount != prepared.osBootCount ||
            environment.bootCount != consent.osBootCount || work.clock() < consent.observation.moment.elapsedMillis ||
            work.clock() >= consent.deadlineElapsedMillis)
            selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        val proofBytes = requireNotNull(record.possession).copyBytes()
        val proof = try { LocalDevicePairingPossession.decode(proofBytes) } finally { proofBytes.fill(0) }
        val challenge = LocalDevicePairingChallenge.fromNative(record.body)
        val message = NativeDevicePairingWireV1.possessionMessage(challenge)
        val point = record.body.requested().target.copyPublicPoint(); val signature = proof.copySignature()
        try { if (!AndroidP256Primitives.verify(point, message, signature)) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED) }
        finally { message.fill(0); point.fill(0); signature.fill(0) }
    }
    /** Independent original/head/ledger join for explicit unsigned DATA disposition only.
     * No password/cancellation approval is inferred; no expired consent or old cut is used.
     * An outstanding genuine credential reservation is UNKNOWN and is never cleared here. */
    fun requireDevicePairingKnownUnsigned(record: NativeDevicePairingRecordV1): LocalCompanyAuthorityStateV1 {
        environment.requireCurrent(); work.requireActive(); requireHeadIssuance(null)
        if (record.phase !in setOf(LocalDevicePairingPhase.PREPARED, LocalDevicePairingPhase.TARGET_PROVED,
                LocalDevicePairingPhase.HEAD_CONSENT_INTENT, LocalDevicePairingPhase.UNSIGNED_RETIREMENT_SIGNING_INTENT) ||
            record.approvedHead != null || record.rootEndorsement.size != 0 || record.retirementEndorsement.size != 0)
            selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        val retained = registry.readDevicePairings(keys)?.records?.singleOrNull { it.body.originalActionId == record.body.originalActionId }
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        if (retained != record) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val original = registry.companyBootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val state = registry.readCompanyState(keys) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val account = state.head; val origin = record.body.origin
        if (account.reservationId != null || state.original.installers.any { it.reservationId != null })
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val root = state.original.root.copyWireBytes()
        val digest = try { sha256(root) } finally { root.fill(0) }
        if (origin.rootId != original.original.rootId || origin.rootKeyId != original.original.key(SelfOwnedNativeKeyRoleV1.ROOT).keyId ||
            origin.rootDocumentDigest != digest || origin.foundingActionId != original.original.actionId ||
            origin.headAuthorityId != original.headAuthorityId || origin.headEnrollmentId != original.headEnrollmentId ||
            account.headAuthorityId != origin.headAuthorityId || account.headEnrollmentId != origin.headEnrollmentId ||
            origin.rootFingerprint != AndroidP256Primitives.rootFingerprint(keys.rootBody()))
            selfOwnedFail(SelfOwnedInstallationFailureV1.CONFLICT)
        record.unsignedDisposition?.let {
            if (it.retainedRevision > state.original.revision || it.accountRevision != account.accountRevision ||
                it.credentialRevision != account.credentialRevision || it.osBootCount != environment.bootCount)
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        return state
    }
    // END isolated device-pairing head authority V1.

    internal class HeadApproval internal constructor(
        internal val owner: AndroidLocalInstallerAuthentication,
        internal val purpose: LocalCompanyHeadPurposeV1,
        internal val originalActionId: DomainId,
        internal val actionDigest: PayloadDigest,
        internal val rootId: DomainId,
        internal val headAuthorityId: DomainId,
        internal val headEnrollmentId: DomainId,
        internal val accountRevision: Long,
        internal val credentialRevision: Long,
        internal val purposeRevision: Long,
        internal val keyRevision: Long,
        internal val boot: Int,
        internal val expires: Long,
        internal val retainedRevision: Long,
    ) {
        private var installationAttemptId: DomainId? = null
        internal fun bindInstallationAttempt(id: DomainId) {
            if (purpose != LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE ||
                installationAttemptId != null && installationAttemptId != id) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
            installationAttemptId = id
        }
        override fun toString(): String = "LocalCompanyHeadApproval([REDACTED])"
    }

    fun requireHeadStillCurrent(approval: HeadApproval, state: LocalCompanyAuthorityStateV1,
        purpose: LocalCompanyHeadPurposeV1, originalActionId: DomainId, actionDigest: PayloadDigest) {
        environment.requireCurrent(); requireHeadIssuance(purpose)
        val original = registry.companyBootstrapRecord() ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        val account = state.head
        if (approval.owner !== this || approval.purpose != purpose || approval.originalActionId != originalActionId ||
            approval.actionDigest != actionDigest || approval.rootId != original.original.rootId ||
            approval.headAuthorityId != original.headAuthorityId || approval.headEnrollmentId != original.headEnrollmentId ||
            approval.accountRevision != account.accountRevision || approval.credentialRevision != account.credentialRevision ||
            approval.purposeRevision != account.purposeRevision(purpose) || approval.keyRevision != original.key(headKeyRole(purpose)).keyRevision ||
            approval.boot != environment.bootCount || work.clock() >= approval.expires || state.original.revision < approval.retainedRevision ||
            account.reservationId != null || account.failedAttempts != 0) selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHENTICATION_REFUSED)
        requireHeadPurpose(account, purpose); work.requireActive(); requireHeadIssuance(purpose)
    }

    private fun requireHeadIssuance(purpose: LocalCompanyHeadPurposeV1?) {
        val original = headIssuance ?: selfOwnedFail(SelfOwnedInstallationFailureV1.AUTHORITY_UNAVAILABLE)
        original.requireIssuance(purpose)
    }

    private fun requireHeadPurpose(account: LocalCompanyHeadAccountV1, purpose: LocalCompanyHeadPurposeV1) {
        if (!account.purposeEnabled(purpose)) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
    }

    private fun headKeyRole(purpose: LocalCompanyHeadPurposeV1): LocalCompanyHeadKeyRoleV1 = when (purpose) {
        LocalCompanyHeadPurposeV1.POLICY_PUBLISH -> LocalCompanyHeadKeyRoleV1.POLICY
        LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE -> LocalCompanyHeadKeyRoleV1.INSTALLATION
    }

    private fun derive(secret: CharArray, original: SelfOwnedBootstrapV1, installer: DomainId,
        credentialRevision: Long, salt: ByteArray): ByteArray = withDerivedMaterial(secret, salt) { bytes ->
        // Legacy installer framing is byte-for-byte unchanged.
        val message = selfOwnedEncode("separate-local-installer-verifier/v1") {
            id(original.rootId); id(original.organizationId); id(original.companyId); id(installer)
            id(original.key(SelfOwnedNativeKeyRoleV1.INSTALLER_PEPPER).keyId); long(credentialRevision)
            int(SelfOwnedInstallationProfileV1.PASSWORD_ITERATIONS); string("PBKDF2-HMAC-SHA256/256")
            field(salt); field(bytes)
        }
        try { keys.installerTag(message) } finally { message.fill(0) }
    }

    private fun deriveHead(secret: CharArray, original: LocalCompanyAuthorityBootstrapV1,
        credentialRevision: Long, salt: ByteArray): ByteArray = withDerivedMaterial(secret, salt) { bytes ->
        val message = selfOwnedEncode("separate-local-company-head-verifier/v1") {
            string("EXPLICIT_FRESH_COMPANY_AND_HEAD_V1")
            id(original.original.rootId); id(original.original.organizationId); id(original.original.companyId)
            id(original.headAuthorityId); id(original.headEnrollmentId)
            id(original.key(LocalCompanyHeadKeyRoleV1.PEPPER).keyId); long(credentialRevision)
            int(SelfOwnedInstallationProfileV1.PASSWORD_ITERATIONS); string("PBKDF2-HMAC-SHA256/256")
            field(salt); field(bytes)
        }
        try { keys.headTag(message) } finally { message.fill(0) }
    }

    /** Private mechanical KDF reuse, never a caller-provided authenticator or serialization port. */
    private fun withDerivedMaterial(secret: CharArray, salt: ByteArray, use: (ByteArray) -> ByteArray): ByteArray {
        val specification = PBEKeySpec(secret, salt, SelfOwnedInstallationProfileV1.PASSWORD_ITERATIONS, 256)
        var material: SecretKey? = null
        var derived: ByteArray? = null
        try {
            val factory = AndroidNativePasswordKeyDerivation.secretKeyFactory()
            try { Log.i("UkkeraNativeOwner", "Credential derivation phase: factory-returned") }
            catch (_: Throwable) { /* diagnostics do not change the original result */ }
            val created = factory.generateSecret(specification)
            material = created
            val bytes = requireNotNull(created.encoded)
            derived = bytes
            if (bytes.size != 32) selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            try { Log.i("UkkeraNativeOwner", "Credential derivation phase: derivation-returned") }
            catch (_: Throwable) { /* diagnostics do not change the original result */ }
            val result = use(bytes)
            try { Log.i("UkkeraNativeOwner", "Credential derivation phase: pepper-tag-returned") }
            catch (_: Throwable) { /* diagnostics do not change the original result */ }
            return result
        } finally {
            specification.clearPassword(); derived?.fill(0)
            // JCA/provider immutable/internal copies cannot be promised wiped. No String is
            // constructed from the password; caller/owned arrays and exposed derivation copies clear.
            // SecretKey inherits Destroyable only from API 26; the interface itself exists
            // on older Android and the private legacy provider explicitly implements it.
            try { (material as? Destroyable)?.destroy() } catch (_: Exception) { /* best effort provider cleanup */ }
        }
    }


}

/** Same-helper monotone denial shared with its actual authenticator, never a verifier callback. */
internal class AndroidLocalCompanyIssuanceGate {
    private val all = AtomicBoolean(false)
    private val policy = AtomicBoolean(false)
    private val installation = AtomicBoolean(false)
    fun deny(purpose: LocalCompanyHeadPurposeV1?) {
        when (purpose) {
            null -> all.set(true)
            LocalCompanyHeadPurposeV1.POLICY_PUBLISH -> policy.set(true)
            LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE -> installation.set(true)
        }
    }
    fun denied(purpose: LocalCompanyHeadPurposeV1? = null): Boolean = all.get() || when (purpose) {
        null -> false
        LocalCompanyHeadPurposeV1.POLICY_PUBLISH -> policy.get()
        LocalCompanyHeadPurposeV1.FULL_D7_INSTALLATION_APPROVE -> installation.get()
    }
    fun requireIssuance(purpose: LocalCompanyHeadPurposeV1?) {
        if (denied(purpose)) selfOwnedFail(SelfOwnedInstallationFailureV1.ISSUANCE_WITHDRAWN)
    }
}
