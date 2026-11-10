package com.ukkera.pos.database

import androidx.room3.Transactor
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.DeviceProtectedLocalStorage
import com.ukkera.pos.security.KeyProtectionStatus
import com.ukkera.pos.security.LocalAccessIssuance
import com.ukkera.pos.security.LocalAccountSessionState
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LocalOpenResult
import com.ukkera.pos.security.LocalSealResult
import com.ukkera.pos.security.LocalSessionTransaction
import com.ukkera.pos.security.LocalAuthConfiguration
import com.ukkera.pos.security.LocalAuthEvent
import com.ukkera.pos.security.LocalBranchInstallationRecord
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalRefreshIssuance
import com.ukkera.pos.security.LocalSessionArchive
import com.ukkera.pos.security.LocalSessionMaintenance
import com.ukkera.pos.security.ProtectedLocalActionApproval
import com.ukkera.pos.security.LocalSessions
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.PasswordHashRecord
import com.ukkera.pos.security.SessionTransition
import com.ukkera.pos.security.SessionTransitionDenial
import com.ukkera.pos.security.localLoginKey
import com.ukkera.pos.security.ProtectedLocalTable
import com.ukkera.pos.security.ThrottleDimension
import com.ukkera.pos.security.PersistentThrottleState
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.ProtectedLocalAccount
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.security.ProtectedLocalStorageFormat
import com.ukkera.pos.security.ProtectedStorageKeyReference
import com.ukkera.pos.security.SealedLocalRecord
import com.ukkera.pos.security.SessionStatus
import com.ukkera.pos.security.ThrottleKey
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Inert protected persistence only. There is no initializer, login/session issuance ceremony,
 * credential reset, policy activation, admission ceremony or accepting gateway in this adapter.
 * The injected provider/key must be separately qualified; a status flag is not that evidence.
 */
internal class RoomLocalSecurityStore(
    internal val database: MasterRoomDatabase,
    private val protection: DeviceProtectedLocalStorage,
    private val keyReference: ProtectedStorageKeyReference,
    private val physicalRegistry: PhysicalInstructionPublicationRegistry? = null,
    private val rpcSessionFence: LocalMasterRpcSessionFence? = null,
) {
    /** Key selector equality only; actual protected operations and qualified custody remain mandatory. */
    internal fun usesInstallationKey(reference: ProtectedStorageKeyReference): Boolean = keyReference == reference

    internal fun ownsPhysicalRegistry(registry: PhysicalInstructionPublicationRegistry): Boolean = physicalRegistry === registry

    /**
     * The caller supplies its actual writer, never a new/nested transaction. Only the narrow
     * reader escapes; neither the mutable transaction, DAOs nor credential record is exposed.
     * The concrete TransactionView owns closure, including caught failures and rollback.
     */
    internal fun borrowAuthorizationReader(
        owner: MasterRoomDatabase,
        connection: Transactor,
    ): RoomLocalAuthorizationReader {
        if (owner !== database) protectedLocalUnavailable()
        return RoomLocalAuthorizationReader(RoomLocalSecurityTransaction(
            database, connection, database.localSecurity(), protection, keyReference, physicalRegistry, rpcSessionFence,
        ))
    }

    /** Only the kernel-owned approval binding may borrow this actual writer transaction. */
    internal fun borrowApprovalTransaction(owner: MasterRoomDatabase, connection: Transactor): RoomLocalSecurityTransaction {
        if (owner !== database) protectedLocalUnavailable()
        return RoomLocalSecurityTransaction(database, connection, database.localSecurity(), protection, keyReference,
            physicalRegistry, rpcSessionFence)
    }

    suspend fun <T> transaction(block: suspend (RoomLocalSecurityTransaction) -> T): T {
        currentCoroutineContext().ensureActive()
        val value = localSecurityWriter(database) { connection ->
            transactionAtWriter(connection, block)
        }
        // Includes outer COMMIT/driver/invalidation errors; never return a provisional success.
        currentCoroutineContext().ensureActive()
        return value.valueOrThrow()
    }

    /** Uses only the original owner's already acquired writer. Failure outcomes must cross
     * the full outer native settlement before they are unwrapped by the owning service. */
    internal suspend fun <T> transactionAtWriter(connection: Transactor,
        block: suspend (RoomLocalSecurityTransaction) -> T): RoomLocalSecurityOutcome<T> {
        // Reject nested callers: the returned value must follow this transaction's COMMIT,
        // not escape while a caller can still roll back an enclosing writer transaction.
        requireDurableWriter(connection)
        currentCoroutineContext().ensureActive()
        return try { connection.withTransaction<RoomLocalSecurityOutcome<T>>(Transactor.SQLiteTransactionType.IMMEDIATE) {
            val history = database.runtimeHistoryOwner?.frameFor(connection)
            history?.enterTransaction(currentCoroutineContext())
            val transaction = RoomLocalSecurityTransaction(
                database, connection, database.localSecurity(), protection, keyReference, physicalRegistry, rpcSessionFence,
            )
            history?.bindSecurity(transaction)
            try {
                val result = localSecurityBody {
                    try {
                        transaction.ensureCurrent()
                        val captured = block(transaction)
                        transaction.finishNormalReturn()
                        captured
                    } catch (failure: Throwable) {
                        // Observe only an original installation's static failure location,
                        // before the existing detail-free outcome conversion below.
                        try {
                            val owner = database.runtimeHistoryOwner
                            if (owner != null && owner.originalRecord.record.parent == null) {
                                reportOriginalInstallationBodyFailure(failure)
                            }
                        } catch (_: Throwable) { /* Diagnostics cannot change the outcome. */ }
                        throw failure
                    }
                }
                // A normal rollback(result) return proves cleanup; a thrown inner exception
                // alone does not (Room may attach a failed unwind to it).
                if (result !is RoomLocalSecurityOutcome.Value) rollback(result)
                result
            } finally {
                transaction.close()
            }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { localStorageUncertain() }
    }

}

/** Borrowed only for one live Room writer transaction. Never retain it or treat it as authority. */
internal class RoomLocalSecurityTransaction internal constructor(
    private val database: MasterRoomDatabase,
    private val connection: Transactor,
    private val dao: LocalSecurityDao,
    private val protection: DeviceProtectedLocalStorage,
    private val keyReference: ProtectedStorageKeyReference,
    private val physicalRegistry: PhysicalInstructionPublicationRegistry? = null,
    private val rpcSessionFence: LocalMasterRpcSessionFence? = null,
) : LocalSessionTransaction {
    private var active = true
    private var failed = false
    private var storageUncertain = false
    private var conflicted = false
    private var writes = 0
    private var actionApprovalFrame: RoomLocalActionApprovalFrame? = null
    private var deviceRevocationFrame: RoomLocalDeviceRevocationFrame? = null
    private var accountAdministrationFrame: RoomLocalAccountAdministrationFrame? = null
    private var administrationObservationFrame: RoomLocalAdministrationObservationFrame? = null
    private var provisioningFrame: RoomLocalProvisioningFrame? = null
    private var authenticationFrame: RoomLocalAuthenticationFrame? = null
    private var branchInstallationFrame: RoomLocalBranchInstallationFrame? = null
    private var originalInstallationFrame: RoomSelfOwnedBranchInstallationFrame? = null
    private var serialPublicationFrame: RoomSerialPublicationRequestFrame? = null

    internal fun belongsTo(owner: MasterRoomDatabase): Boolean = database === owner

    internal suspend fun ensureCurrent() {
        try {
            currentCoroutineContext().ensureActive()
            if (storageUncertain) localStorageUncertain()
            check(active && !failed)
            val inTransaction = try { connection.inTransaction() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { uncertain() }
            if (!inTransaction) uncertain()
            currentCoroutineContext().ensureActive()
            check(active && !failed)
        } catch (cancelled: CancellationException) {
            failed = true
            throw cancelled
        } catch (_: RoomLocalStorageUncertain) {
            uncertain()
        } catch (_: Exception) {
            refuse()
        }
    }

    internal suspend fun ensureCommittable() {
        ensureCurrent()
        // An ignored/caught later CAS failure must not commit an earlier member of a pair.
        if (conflicted && writes != 0) refuse()
    }

    /** A dedicated approval frame, not a general-purpose caller-supplied commit callback. */
    internal suspend fun openActionApprovalFrame(
        principal: LocalEmployeePrincipal,
        source: RoomCurrentLocalAuthorizationSource,
        peer: RoomLocalAccessPeerBinding,
    ): RoomLocalActionApprovalFrame {
        ensureCurrent()
        if (hasFinalizationFrame()) refuse()
        val context = currentCoroutineContext()
        val frame = RoomLocalActionApprovalFrame(TransactionView.forWriter(principal.scope, database.facts(),
            database, connection, context, principal), principal, source, peer, context)
        actionApprovalFrame = frame
        frame.initialize(this)
        return frame
    }

    /** Current requester authorization on the original writer, not a detached cached grant. */
    internal suspend fun openSerialPublicationFrame(
        principal: LocalEmployeePrincipal,
        source: RoomCurrentLocalAuthorizationSource,
    ): RoomSerialPublicationRequestFrame {
        ensureCurrent()
        if (hasFinalizationFrame()) refuse()
        val history = database.runtimeHistoryOwner?.frameFor(connection) ?: refuse()
        val context = currentCoroutineContext()
        val frame = RoomSerialPublicationRequestFrame(TransactionView.forWriter(principal.scope, database.facts(),
            database, connection, context, principal), history, principal, source, context)
        serialPublicationFrame = frame
        frame.initialize(this)
        return frame
    }

    /** Specific captured device-revocation proof, never an arbitrary commit callback. */
    internal suspend fun attachDeviceRevocationFrame(frame: RoomLocalDeviceRevocationFrame) {
        ensureCurrent()
        if (hasFinalizationFrame()) refuse()
        deviceRevocationFrame = frame
        frame.initialize(this)
    }

    /** Exact reset/disable/session-revoke proof, attached before the first owned effect. */
    internal suspend fun attachAccountAdministrationFrame(frame: RoomLocalAccountAdministrationFrame) {
        ensureCurrent()
        if (hasFinalizationFrame()) refuse()
        accountAdministrationFrame = frame
        frame.initialize(this)
    }

    internal suspend fun attachAdministrationObservationFrame(frame: RoomLocalAdministrationObservationFrame) {
        requireAdministrationWriteCount(0)
        if (hasFinalizationFrame()) refuse()
        administrationObservationFrame = frame
        frame.initialize(this)
    }

    internal suspend fun requireAdministrationWriteCount(expected: Int) {
        ensureCurrent()
        check(expected in 0..1)
        if (writes != expected) refuse()
    }

    internal suspend fun requireSerialPublicationWriteCount(expected: Int) {
        ensureCurrent()
        check(expected in 0..1)
        if (serialPublicationFrame == null || writes != expected) refuse()
    }

    /** Only this distinct variable-image device effect can consume this write census. */
    internal suspend fun requireDeviceRevocationWriteCount(expected: Int) {
        ensureCurrent()
        check(expected in 0..RuntimeDeviceRevocationEvidenceV1.MAX_IMAGES)
        if (writes != expected) refuse()
    }

    /** Exact current ceremony and a closed provisioning/lookup effect, not a commit callback. */
    internal suspend fun attachProvisioningFrame(frame: RoomLocalProvisioningFrame) {
        ensureCurrent()
        if (hasFinalizationFrame()) refuse()
        provisioningFrame = frame
        frame.initialize(this)
    }

    /** Specific verified first-install/receipt proof, attached before any initial BranchRow effect. */
    internal suspend fun attachBranchInstallationFrame(frame: RoomLocalBranchInstallationFrame) {
        ensureCurrent()
        if (hasFinalizationFrame()) refuse()
        branchInstallationFrame = frame
        frame.initialize(this)
    }

    internal suspend fun attachOriginalInstallationFrame(frame: RoomSelfOwnedBranchInstallationFrame) {
        ensureCurrent(); if(hasFinalizationFrame()) refuse(); originalInstallationFrame=frame; frame.initialize(this)
    }
    internal suspend fun originalInstallationFootprint(initial: Boolean): Boolean = operation {
        val owner=checkNotNull(database.runtimeHistoryOwner)
        check(owner.originalRecord.record.parent==null)
        RoomBranchInstallationStorage.originalFootprint(connection,initial)
    }
    internal fun captureOriginalInstallation(snapshot: com.ukkera.pos.security.LocalBranchInstallationSnapshotV2,
        receipt: com.ukkera.pos.security.LocalBranchInstallationReceiptV2) {
        checkNotNull(database.runtimeHistoryOwner).frameFor(connection).captureInstallation(snapshot,receipt)
    }
    internal suspend fun insertOriginalInstallationBranch(frame: RoomSelfOwnedBranchInstallationFrame,
        receipt: com.ukkera.pos.security.LocalBranchInstallationReceiptV2) {
        ensureCurrent(); if(originalInstallationFrame!==frame || !frame.permits(this,receipt)) refuse()
        physicalRegistry?.denyScope(receipt.core.allocation.coordinates.scope)
        fenceRpcSessionsBeforeWrite(LocalMasterRpcSessionInvalidation.All)
        operation { database.facts().insertBranch(RoomBranchInstallationStorage.originalBranch(receipt)) }; writes++
    }
    internal suspend fun insertOriginalInstallationReceipt(frame: RoomSelfOwnedBranchInstallationFrame,
        receipt: com.ukkera.pos.security.LocalBranchInstallationReceiptV2) {
        ensureCurrent(); if(originalInstallationFrame!==frame || !frame.permits(this,receipt)) refuse()
        val c=receipt.core.allocation.coordinates
        val binding=ProtectedLocalBinding(ProtectedLocalRecordKey.AuthEvent(c.scope,c.installationId),Revision(0))
        val sealed=sealOwned(binding,validated { LocalBranchInstallationCodecV2.encodeEvent(receipt) }, invalidation = LocalMasterRpcSessionInvalidation.None)
        operation { database.localAuthentication().insertEvent(ProtectedLocalAuthEventRow(c.scope.storageKey(),c.installationId.value,
            binding.schemaVersion,sealed.formatVersion,sealed.algorithm,0,keyReference.keyId.value,sealed.copyNonce(),sealed.copyCiphertextAndTag())) }; writes++
    }
    internal suspend fun readOriginalInstallationReceipt(scope: BranchScope,installationId: DomainId): com.ukkera.pos.security.LocalBranchInstallationReceiptV2? =
        read(ProtectedLocalRecordKey.AuthEvent(scope,installationId),LocalBranchInstallationCodecV2::decodeEvent)

    internal suspend fun attachAuthenticationFrame(frame: RoomLocalAuthenticationFrame) {
        ensureCurrent(); if (hasFinalizationFrame()) refuse(); authenticationFrame=frame
    }
    internal fun historyPurpose(value: RuntimeWritePurposeV2,actor: DomainId? = null,request: DomainId? = null,
        contentDigest: PayloadDigest? = null) {
        database.runtimeHistoryOwner?.frameFor(connection)?.captureSecurityPurpose(value,actor,request,contentDigest)
    }
    internal suspend fun historyMasterLifecycle(evidence: RuntimeMasterLifecycleEvidenceV1,
        principal: LocalEmployeePrincipal, authorization: AuthorizedAction,
        facts: RoomLocalAuthorizationFacts, observed: RoomLocalAuthorizationObservation) {
        ensureCurrent()
        database.runtimeHistoryOwner?.frameFor(connection)?.captureMasterLifecycleEvidence(
            this, evidence, principal, authorization, facts, observed)
    }
    internal fun historyThrottle(evidence: RuntimeThrottleEvidenceV1) {
        database.runtimeHistoryOwner?.frameFor(connection)?.captureThrottle(evidence)
    }
    internal fun historyApprovalAttempt() {
        database.runtimeHistoryOwner?.frameFor(connection)?.captureApprovalAttempt()
    }
    internal fun beginHistoryApprovalIssuance(capture: RuntimeActionApprovalIssuanceCapture) {
        database.runtimeHistoryOwner?.frameFor(connection)?.beginApprovalIssuance(capture)
    }
    internal fun completeHistoryApprovalIssuance(capture: RuntimeActionApprovalIssuanceCapture) {
        database.runtimeHistoryOwner?.frameFor(connection)?.completeApprovalIssuance(capture)
    }
    internal fun beginHistoryApprovalUse(capture: RuntimeActionApprovalUseCapture) {
        database.runtimeHistoryOwner?.frameFor(connection)?.beginApprovalUse(capture)
    }
    internal fun requireActionApprovalRetirementHistory() {
        val frame = database.runtimeHistoryOwner?.frameFor(connection) ?: refuse()
        frame.requireApprovalRetirementHistory()
    }
    internal suspend fun actionApprovalIssuanceOrigins(): List<RuntimeApprovalIssuanceOrigin> =
        (database.runtimeHistoryOwner?.frameFor(connection) ?: refuse()).approvalIssuanceOrigins()
    internal suspend fun verifyActionApprovalRetirementHistory(value: ProtectedLocalActionApproval, event: LocalAuthEvent?): Long? {
        val frame = database.runtimeHistoryOwner?.frameFor(connection) ?: refuse()
        return frame.verifyApprovalRetirementHistory(value, event)
    }
    internal fun beginHistoryApprovalRetirement(capture: RuntimeActionApprovalRetirementCapture) {
        val frame = database.runtimeHistoryOwner?.frameFor(connection) ?: refuse()
        frame.beginApprovalRetirement(capture)
    }
    internal fun completeHistoryApprovalRetirement(capture: RuntimeActionApprovalRetirementCapture) {
        val frame = database.runtimeHistoryOwner?.frameFor(connection) ?: refuse()
        frame.completeApprovalRetirement(capture)
    }
    internal fun beginHistorySerialPublication(capture: RuntimeSerialPublicationRequestCapture) {
        (database.runtimeHistoryOwner?.frameFor(connection) ?: refuse()).beginSerialPublication(capture)
    }
    internal fun completeHistorySerialPublication(capture: RuntimeSerialPublicationRequestCapture) {
        (database.runtimeHistoryOwner?.frameFor(connection) ?: refuse()).completeSerialPublication(capture)
    }
    internal suspend fun readHistorySerialPublicationRequest(requestId: DomainId): RuntimeSerialPublicationRequestEvidenceV1? =
        (database.runtimeHistoryOwner?.frameFor(connection) ?: refuse()).readSerialPublicationRequest(requestId)
    internal suspend fun readHistorySerialPublicationOrigins(actorId: DomainId): List<RuntimeSerialPublicationRequestEvidenceV1> =
        (database.runtimeHistoryOwner?.frameFor(connection) ?: refuse()).serialPublicationOrigins(actorId)
    internal suspend fun beginHistoryAuthentication(scope: BranchScope, actorId: DomainId, deviceId: DomainId,
        eventId: DomainId, action: RuntimeAuthenticationActionV1, at: RoomLocalAuthorizationObservation,
        newSessionId: DomainId?, previousSessionId: DomainId?, explicitDeviceMove: Boolean): RuntimeAuthenticationCapture? {
        val frame = database.runtimeHistoryOwner?.frameFor(connection) ?: return null
        // Re-read actual authenticated dependencies on this loan before any session maintenance.
        // In sign-in this must also match the captured password attempt's unchanged incarnation.
        val account = readProtectedAccount(scope, actorId) ?: refuse()
        val policy = readPolicy(scope, actorId) ?: refuse()
        val admission = readAdmission(scope, deviceId) ?: refuse()
        val configuration = readAuthConfiguration(scope, actorId) ?: refuse()
        fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
        return RuntimeAuthenticationCapture(scope, actorId, deviceId, eventId, action, at.publicValue(),
            newSessionId, previousSessionId, explicitDeviceMove, RuntimeThrottleDependenciesV1(
                digest(ProtectedLocalSecurityCodec.encodeAccount(account)), digest(LocalEffectivePolicyCodec.encodePolicy(policy)),
                digest(ProtectedLocalSecurityCodec.encodeAdmission(admission)), digest(LocalAuthenticationCodec.encodeConfiguration(configuration))))
            .also(frame::beginAuthentication)
    }
    internal fun completeHistoryAuthentication(capture: RuntimeAuthenticationCapture?, denial: SessionTransitionDenial? = null,
        access: LocalAccessIssuance? = null, refresh: LocalRefreshIssuance? = null) {
        if (capture != null) checkNotNull(database.runtimeHistoryOwner).frameFor(connection)
            .completeAuthentication(capture, denial, access, refresh)
    }
    internal suspend fun bindOriginalEmployeeFrame(permit: com.ukkera.pos.security.NativeLocalEmployeeProvisioningPermit):
        com.ukkera.pos.security.NativeEmployeeProvisioningFrame {
        ensureCurrent(); if (writes != 0) refuse()
        val historyOwner = checkNotNull(database.runtimeHistoryOwner)
        return historyOwner.bindOriginalEmployeeFrame(historyOwner.frameFor(connection), permit)
    }
    internal fun beginHistoryEmployeeProvisioning(capture: RuntimeEmployeeProvisioningCapture) {
        checkNotNull(database.runtimeHistoryOwner).frameFor(connection).beginEmployeeProvisioning(capture)
    }
    internal fun completeHistoryEmployeeProvisioning(capture: RuntimeEmployeeProvisioningCapture) {
        checkNotNull(database.runtimeHistoryOwner).frameFor(connection).completeEmployeeProvisioning(capture)
    }
    internal suspend fun bindOriginalEmployeePolicyFrame(permit: com.ukkera.pos.security.NativeLocalEmployeePolicyActivationPermit):
        com.ukkera.pos.security.NativeEmployeePolicyActivationFrame {
        ensureCurrent(); if (writes != 0) refuse()
        val historyOwner = checkNotNull(database.runtimeHistoryOwner)
        return historyOwner.bindOriginalEmployeePolicyFrame(historyOwner.frameFor(connection), permit)
    }
    internal fun beginHistoryEmployeePolicyActivation(capture: RuntimeEmployeePolicyActivationCapture) {
        checkNotNull(database.runtimeHistoryOwner).frameFor(connection).beginEmployeePolicyActivation(capture)
    }
    internal fun completeHistoryEmployeePolicyActivation(capture: RuntimeEmployeePolicyActivationCapture) {
        checkNotNull(database.runtimeHistoryOwner).frameFor(connection).completeEmployeePolicyActivation(capture)
    }
    internal suspend fun bindOriginalDeviceAdmissionFrame(permit: com.ukkera.pos.security.NativeLocalDeviceAdmissionPermit):
        com.ukkera.pos.security.NativeDeviceAdmissionFrame {
        ensureCurrent(); if (writes != 0) refuse()
        val historyOwner = checkNotNull(database.runtimeHistoryOwner)
        return historyOwner.bindOriginalDeviceAdmissionFrame(historyOwner.frameFor(connection), permit)
    }
    internal fun beginHistoryDeviceAdmission(capture: RuntimeDeviceAdmissionCapture) {
        checkNotNull(database.runtimeHistoryOwner).frameFor(connection).beginDeviceAdmission(capture)
    }
    internal fun completeHistoryDeviceAdmission(capture: RuntimeDeviceAdmissionCapture) {
        checkNotNull(database.runtimeHistoryOwner).frameFor(connection).completeDeviceAdmission(capture)
    }
    internal fun beginHistorySessionRevocation(capture: RuntimeSessionRevocationHistoryCapture) {
        database.runtimeHistoryOwner?.frameFor(connection)?.beginSessionRevocation(capture)
    }
    internal fun completeHistorySessionRevocation(capture: RuntimeSessionRevocationHistoryCapture) {
        database.runtimeHistoryOwner?.frameFor(connection)?.completeSessionRevocation(capture)
    }
    internal fun beginHistoryAdministrationCancellation(capture: RuntimeAdministrationCancellationCapture) {
        database.runtimeHistoryOwner?.frameFor(connection)?.beginAdministrationCancellation(capture)
    }
    internal fun completeHistoryAdministrationCancellation(capture: RuntimeAdministrationCancellationCapture) {
        database.runtimeHistoryOwner?.frameFor(connection)?.completeAdministrationCancellation(capture)
    }
    /** Immutable native origin selects the capture slice only; it grants no authority. */
    internal fun originalCohostDeviceRevocationOrigin(principal: LocalEmployeePrincipal,
        before: LocalDeviceAdmission): LocalDeviceAdmission? = database.runtimeHistoryOwner?.frameFor(connection)
        ?.originalCohostDeviceRevocationOrigin(principal, before)
    internal fun beginHistoryDeviceRevocation(capture: RuntimeDeviceRevocationCapture) {
        database.runtimeHistoryOwner?.frameFor(connection)?.beginDeviceRevocation(capture)
    }
    internal fun completeHistoryDeviceRevocation(capture: RuntimeDeviceRevocationCapture) {
        database.runtimeHistoryOwner?.frameFor(connection)?.completeDeviceRevocation(capture)
    }
    /** Selects only a declaration grammar; the live target row is never a native origin. */
    internal fun isAdditionalDeviceRevocationCaptureCandidate(principal: LocalEmployeePrincipal,
        operatorBefore: LocalDeviceAdmission, targetBefore: LocalDeviceAdmission): Boolean =
        database.runtimeHistoryOwner?.frameFor(connection)
            ?.isAdditionalDeviceRevocationCaptureCandidate(principal, operatorBefore, targetBefore) == true
    internal fun beginHistoryAdditionalDeviceRevocation(capture: RuntimeDeviceRevocationCaptureV2) {
        checkNotNull(database.runtimeHistoryOwner).frameFor(connection).beginAdditionalDeviceRevocation(capture)
    }
    internal fun completeHistoryAdditionalDeviceRevocation(capture: RuntimeDeviceRevocationCaptureV2) {
        checkNotNull(database.runtimeHistoryOwner).frameFor(connection).completeAdditionalDeviceRevocation(capture)
    }
    private fun hasFinalizationFrame(): Boolean = originalInstallationFrame != null || authenticationFrame != null || actionApprovalFrame != null || deviceRevocationFrame != null ||
        accountAdministrationFrame != null || administrationObservationFrame != null || provisioningFrame != null || branchInstallationFrame != null || serialPublicationFrame != null

    internal suspend fun finishNormalReturn() {
        ensureCommittable()
        actionApprovalFrame?.prepareNormalReturn(this)
        deviceRevocationFrame?.prepareNormalReturn(this)
        accountAdministrationFrame?.prepareNormalReturn(this)
        administrationObservationFrame?.prepareNormalReturn(this)
        provisioningFrame?.prepareNormalReturn(this)
        branchInstallationFrame?.prepareNormalReturn(this)
        originalInstallationFrame?.prepareNormalReturn(this)
        serialPublicationFrame?.prepareNormalReturn(this)
        // All connection/key/dependency work precedes the dedicated frame's last clock sample.
        ensureCommittable()
        authenticationFrame?.prepareNormalReturn(this)
        database.runtimeHistoryOwner?.frameFor(connection)?.prepareFromSecurity(this)
        actionApprovalFrame?.finishNow()
        deviceRevocationFrame?.finishNow()
        accountAdministrationFrame?.finishNow()
        administrationObservationFrame?.finishNow()
        provisioningFrame?.finishNow()
        branchInstallationFrame?.finishNow()
        originalInstallationFrame?.finishNow()
        serialPublicationFrame?.finishNow()
        authenticationFrame?.finishNow()
        database.runtimeHistoryOwner?.frameFor(connection)?.finishFromSecurity(this)
    }

    internal suspend fun close() {
        active = false
        // An approval view may own a stock scratch loan. Its synchronous owner
        // must await that loan's cleanup before releasing the Room transaction.
        try {
            actionApprovalFrame?.close()
        } finally {
            deviceRevocationFrame?.close()
            accountAdministrationFrame?.close()
            administrationObservationFrame?.close()
            provisioningFrame?.close()
            branchInstallationFrame?.close()
            originalInstallationFrame?.close()
            serialPublicationFrame?.close()
            authenticationFrame?.close()
        }
    }

    internal suspend fun installationFootprint(): RoomBranchInstallationFootprint =
        operation { RoomBranchInstallationStorage.footprint(connection) } ?: refuse()

    internal suspend fun matchesInstallationBranch(expected: BranchRow): Boolean =
        operation { RoomBranchInstallationStorage.matchesBranch(connection, expected) }

    /** Not a generic branch seed: only this transaction's attached installation frame may insert. */
    internal suspend fun insertInstallationBranch(frame: RoomLocalBranchInstallationFrame, record: LocalBranchInstallationRecord) {
        ensureCurrent()
        if (branchInstallationFrame !== frame || !frame.permitsBranchInsertion(this, record)) refuse()
        val row = validated { RoomBranchInstallationStorage.branch(record) }
        // The only protected-store business-table write not passing through sealOwned.
        physicalRegistry?.denyScope(record.request.identity.scope)
        fenceRpcSessionsBeforeWrite(LocalMasterRpcSessionInvalidation.All)
        operation { database.facts().insertBranch(row) }
        writes++
    }

    internal fun refuse(): Nothing {
        failed = true
        if (storageUncertain) localStorageUncertain()
        protectedLocalUnavailable()
    }

    private fun uncertain(): Nothing {
        failed = true
        storageUncertain = true
        localStorageUncertain()
    }

    override suspend fun readAccount(scope: BranchScope, actorId: DomainId): LocalAccountSessionState? =
        readProtectedAccount(scope, actorId)?.state

    suspend fun readProtectedAccount(scope: BranchScope, actorId: DomainId): ProtectedLocalAccount? =
        read(ProtectedLocalRecordKey.Account(scope, actorId), ProtectedLocalSecurityCodec::decodeAccount)

    suspend fun readPolicy(scope: BranchScope, actorId: DomainId): ActivatedLocalPolicy? =
        read(ProtectedLocalRecordKey.EffectivePolicy(scope, actorId), LocalEffectivePolicyCodec::decodePolicy)

    suspend fun readAdmission(scope: BranchScope, deviceId: DomainId): LocalDeviceAdmission? =
        read(ProtectedLocalRecordKey.Admission(scope, deviceId), ProtectedLocalSecurityCodec::decodeAdmission)

    suspend fun readThrottle(key: ThrottleKey): PersistentThrottleState? =
        read(ProtectedLocalRecordKey.Throttle(key), ProtectedLocalSecurityCodec::decodeThrottle)

    suspend fun readIssuance(scope: BranchScope, sessionId: DomainId): LocalAccessIssuance? =
        read(ProtectedLocalRecordKey.SessionIssuance(scope, sessionId), ProtectedLocalSecurityCodec::decodeIssuance)

    suspend fun readAuthConfiguration(scope: BranchScope, actorId: DomainId): LocalAuthConfiguration? =
        read(ProtectedLocalRecordKey.AuthConfiguration(scope, actorId), LocalAuthenticationCodec::decodeConfiguration)

    /** Narrow account projection; no session is fabricated for a prospective recipient. */
    internal suspend fun readHandoffRecipient(scope: BranchScope, orderId: DomainId, actorId: DomainId,
        requirePresent: Boolean = false): LocalHandoffRecipient? {
        val account = readProtectedAccount(scope, actorId)
        if (account == null) { if (requirePresent) refuse(); return null }
        if (!account.state.enabled || account.state.generation.value == Long.MAX_VALUE || account.credential == null) return null
        val policy = readPolicy(scope, actorId) ?: refuse()
        val configuration = readAuthConfiguration(scope, actorId) ?: refuse()
        return validated {
            check(account.state.scope == scope && account.state.actorId == actorId && policy.scope == scope && policy.actorId == actorId &&
                configuration.scope == scope && configuration.actorId == actorId)
            if (!com.ukkera.pos.security.AuthorizationEvaluator.permitsLocalHandoffRecipient(policy, scope, orderId)) null
            else LocalHandoffRecipient(actorId, configuration.login.value, account.state.revision, policy.revision)
        }
    }

    internal suspend fun handoffRecipientPage(scope: BranchScope, requesterId: DomainId, orderId: DomainId,
        after: DomainId?, limit: Int): LocalHandoffRecipientPage {
        require(limit in 1..20)
        var cursor = after?.value
        var scanned = 0
        val selected = mutableListOf<LocalHandoffRecipient>()
        while (true) {
            val page = operation { database.localAuthentication().accountPage(scope.storageKey(), cursor) }
            if (page.isEmpty()) return LocalHandoffRecipientPage(selected, null)
            for (row in page) {
                if (++scanned > 4096) refuse() // Existing protected account-census profile, never a successful prefix.
                val id = validated { check(row.storageValid && (cursor == null || row.actorId > checkNotNull(cursor))); DomainId(row.actorId) }
                cursor = id.value
                if (id == requesterId) continue
                val recipient = readHandoffRecipient(scope, orderId, id, requirePresent = true) ?: continue
                selected += recipient
                if (selected.size > limit) return LocalHandoffRecipientPage(selected.take(limit), selected[limit - 1].actorId)
            }
        }
    }

    internal suspend fun handoffInboxTarget(scope: BranchScope, actorId: DomainId): DomainId? {
        val account = readProtectedAccount(scope, actorId) ?: return null
        if (!account.state.enabled || account.credential == null) return null
        val policy = readPolicy(scope, actorId) ?: refuse()
        return validated { check(policy.scope == scope && policy.actorId == actorId)
            com.ukkera.pos.security.AuthorizationEvaluator.localHandoffInboxAuthorizationTarget(policy, scope) }
    }

    suspend fun readRefresh(scope: BranchScope, sessionId: DomainId): LocalRefreshIssuance? =
        read(ProtectedLocalRecordKey.RefreshIssuance(scope, sessionId), LocalAuthenticationCodec::decodeRefresh)

    suspend fun readAuthEvent(scope: BranchScope, eventId: DomainId): LocalAuthEvent? =
        read(ProtectedLocalRecordKey.AuthEvent(scope, eventId), LocalAuthenticationCodec::decodeEvent)

    suspend fun readSessionArchive(scope: BranchScope, sessionId: DomainId): LocalSessionArchive? =
        read(ProtectedLocalRecordKey.SessionArchive(scope, sessionId), LocalAuthenticationCodec::decodeArchive)

    suspend fun readActionApproval(scope: BranchScope, approvalId: DomainId): ProtectedLocalActionApproval? =
        read(ProtectedLocalRecordKey.ActionApproval(scope, approvalId), LocalActionApprovalCodec::decode)

    suspend fun resolveLogin(scope: BranchScope, login: LocalLoginIdentifier): LocalAuthConfiguration? {
        val key = validated { localLoginKey(scope, login) }
        val locator = operation { database.localAuthentication().resolveLogin(scope.storageKey(), key) } ?: return null
        val actor = validated { check(locator.storageValid); DomainId(locator.actorId) }
        val configuration = readAuthConfiguration(scope, actor) ?: refuse()
        if (configuration.login != login || configuration.loginKey != key) refuse()
        return configuration
    }

    /** Bounded paged enumeration, never an untrusted caller-selected SQL/table name. */
    suspend fun forEachAccount(scope: BranchScope, block: suspend (ProtectedLocalAccount) -> Unit) {
        var after: String? = null
        var count = 0
        while (true) {
            val page = operation { database.localAuthentication().accountPage(scope.storageKey(), after) }
            if (page.isEmpty()) return
            for (row in page) {
                if (++count > 4096) refuse()
                val id = validated {
                    check(row.storageValid && (after == null || row.actorId > checkNotNull(after)))
                    DomainId(row.actorId)
                }
                block(readProtectedAccount(scope, id) ?: refuse())
                after = id.value
            }
        }
    }

    /** Authenticates the entire bounded retained set, including revoked admissions. */
    suspend fun readAdmissions(scope: BranchScope): List<LocalDeviceAdmission> {
        val rows = operation { database.localAuthentication().deviceIds(scope.storageKey()) }
        if (rows.size > 256) refuse()
        val admissions = ArrayList<LocalDeviceAdmission>(rows.size)
        for (row in rows) {
            val id = validated { check(row.storageValid); DomainId(row.actorId) }
            val value = readAdmission(scope, id) ?: refuse()
            admissions.add(value)
        }
        return admissions.toList()
    }

    suspend fun admittedDeviceIds(scope: BranchScope): Set<DomainId> =
        readAdmissions(scope).filter { it.admitted }.map { it.deviceId }.toSet()

    /**
     * Maintenance-only full authentication, including inactive/history rows. Fixed metadata-first
     * rowid pages include negative IDs, all scopes and malformed keys rather than filtering them
     * into absence. A foreign scope or unsupported shape refuses the whole staged operation.
     */
    suspend fun authenticateCensus(scope: BranchScope, maximumRecords: Long): Map<String, Long> {
        if (maximumRecords <= 0) refuse()
        var total = 0L
        val counts = linkedMapOf<String, Long>()
        for (table in ProtectedLocalTable.entries) {
            val physical = protectedPhysicalTable(table)
            val idColumn = when (table) {
                ProtectedLocalTable.ACCOUNT, ProtectedLocalTable.EFFECTIVE_POLICY, ProtectedLocalTable.THROTTLE,
                ProtectedLocalTable.AUTH_CONFIGURATION -> "actorId"
                ProtectedLocalTable.ADMISSION -> "deviceId"
                ProtectedLocalTable.SESSION_ISSUANCE, ProtectedLocalTable.REFRESH_ISSUANCE, ProtectedLocalTable.SESSION_ARCHIVE -> "sessionId"
                ProtectedLocalTable.AUTH_EVENT -> "eventId"
                ProtectedLocalTable.ACTION_APPROVAL -> "approvalId"
            }
            val dimension = if (table == ProtectedLocalTable.THROTTLE)
                "CASE WHEN typeof(dimensionKey)='text' AND length(CAST(dimensionKey AS BLOB)) IN (7,43) THEN dimensionKey ELSE '' END" else "''"
            var after: Long? = null
            var count = 0L
            while (true) {
                val page = operation { connection.usePrepared(
                    "SELECT rowid, CASE WHEN typeof(scope)='text' AND length(CAST(scope AS BLOB))=110 THEN scope ELSE '' END, " +
                        "CASE WHEN typeof($idColumn)='text' AND length(CAST($idColumn AS BLOB))=36 THEN $idColumn ELSE '' END, $dimension " +
                        "FROM $physical WHERE (? IS NULL OR rowid>?) ORDER BY rowid LIMIT 256",
                ) { statement ->
                    val cursor = after
                    if (cursor == null) { statement.bindNull(1); statement.bindNull(2) }
                    else { statement.bindLong(1, cursor); statement.bindLong(2, cursor) }
                    buildList {
                        while (statement.step()) add(ProtectedCensusLocator(statement.getLong(0), statement.getText(1),
                            statement.getText(2), statement.getText(3)))
                    }
                } }
                if (page.isEmpty()) break
                for (row in page) {
                    if (total == maximumRecords) refuse()
                    val key = validated {
                        check(row.scope == scope.storageKey() && (after == null || row.rowid > checkNotNull(after)))
                        val id = DomainId(row.id)
                        when (table) {
                            ProtectedLocalTable.ACCOUNT -> ProtectedLocalRecordKey.Account(scope, id)
                            ProtectedLocalTable.EFFECTIVE_POLICY -> ProtectedLocalRecordKey.EffectivePolicy(scope, id)
                            ProtectedLocalTable.ADMISSION -> ProtectedLocalRecordKey.Admission(scope, id)
                            ProtectedLocalTable.SESSION_ISSUANCE -> ProtectedLocalRecordKey.SessionIssuance(scope, id)
                            ProtectedLocalTable.AUTH_CONFIGURATION -> ProtectedLocalRecordKey.AuthConfiguration(scope, id)
                            ProtectedLocalTable.REFRESH_ISSUANCE -> ProtectedLocalRecordKey.RefreshIssuance(scope, id)
                            ProtectedLocalTable.AUTH_EVENT -> ProtectedLocalRecordKey.AuthEvent(scope, id)
                            ProtectedLocalTable.SESSION_ARCHIVE -> ProtectedLocalRecordKey.SessionArchive(scope, id)
                            ProtectedLocalTable.ACTION_APPROVAL -> ProtectedLocalRecordKey.ActionApproval(scope, id)
                            ProtectedLocalTable.THROTTLE -> ProtectedLocalRecordKey.Throttle(ThrottleKey(scope, id,
                                if (row.dimension == "account") ThrottleDimension.Account else {
                                    check(row.dimension.startsWith("device:") && row.dimension.length == 43)
                                    ThrottleDimension.Device(DomainId(row.dimension.substring(7)))
                                }))
                        }
                    }
                    val value: Any? = when (key) {
                        is ProtectedLocalRecordKey.Account -> readProtectedAccount(scope, key.actorId)
                        is ProtectedLocalRecordKey.EffectivePolicy -> readPolicy(scope, key.actorId)
                        is ProtectedLocalRecordKey.Admission -> readAdmission(scope, key.deviceId)
                        is ProtectedLocalRecordKey.Throttle -> readThrottle(key.key)
                        is ProtectedLocalRecordKey.SessionIssuance -> readIssuance(scope, key.sessionId)
                        is ProtectedLocalRecordKey.AuthConfiguration -> readAuthConfiguration(scope, key.actorId)
                        is ProtectedLocalRecordKey.RefreshIssuance -> readRefresh(scope, key.sessionId)
                        is ProtectedLocalRecordKey.AuthEvent -> readAuthEvent(scope, key.eventId)
                        is ProtectedLocalRecordKey.SessionArchive -> readSessionArchive(scope, key.sessionId)
                        is ProtectedLocalRecordKey.ActionApproval -> readActionApproval(scope, key.approvalId)
                    }
                    if (value == null) refuse()
                    count++; total++; after = row.rowid
                }
            }
            counts[physical] = count
        }
        return counts.toMap()
    }

    /** Internal provisioning primitives. Only the controlled service may authorize their use. */
    suspend fun insertAccount(value: ProtectedLocalAccount) {
        val state = value.state
        if (state.revision.value != 0L || state.generation.value != 0L || state.sessions.isNotEmpty()) refuse()
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.Account(state.scope, state.actorId), state.revision)
        val sealed = sealOwned(binding, validated { ProtectedLocalSecurityCodec.encodeAccount(value) }, invalidation = LocalMasterRpcSessionInvalidation.Account(value, null))
        operation { dao.insertAccount(ProtectedLocalAccountRow(state.scope.storageKey(), state.actorId.value,
            binding.schemaVersion, sealed.formatVersion, sealed.algorithm, 0, keyReference.keyId.value,
            sealed.copyNonce(), sealed.copyCiphertextAndTag())) }
        writes++
    }

    suspend fun insertPolicy(value: ActivatedLocalPolicy) {
        if (value.revision.value != 0L) refuse()
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.EffectivePolicy(value.scope, value.actorId), value.revision)
        val sealed = sealOwned(binding, validated { LocalEffectivePolicyCodec.encodePolicy(value) }, invalidation = LocalMasterRpcSessionInvalidation.Policy(value.scope, value.actorId))
        operation { dao.insertPolicy(ProtectedEffectivePolicyRow(value.scope.storageKey(), value.actorId.value,
            binding.schemaVersion, sealed.formatVersion, sealed.algorithm, 0, keyReference.keyId.value,
            sealed.copyNonce(), sealed.copyCiphertextAndTag())) }
        writes++
    }

    /** No merge or default grants; the trusted activation ceremony supplies the complete policy. */
    suspend fun replacePolicy(expectedRevision: Revision, next: ActivatedLocalPolicy): Boolean {
        val previous = readPolicy(next.scope, next.actorId) ?: return conflict()
        if (previous.revision != expectedRevision || expectedRevision.value == Long.MAX_VALUE ||
            next.revision.value != expectedRevision.value + 1) return conflict()
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.EffectivePolicy(next.scope, next.actorId), next.revision)
        val sealed = sealOwned(binding, validated { LocalEffectivePolicyCodec.encodePolicy(next) }, invalidation = LocalMasterRpcSessionInvalidation.Policy(next.scope, next.actorId))
        return wrote(operation { dao.replacePolicy(next.scope.storageKey(), next.actorId.value,
            expectedRevision.value, next.revision.value, binding.schemaVersion, sealed.formatVersion,
            sealed.algorithm, keyReference.keyId.value, sealed.copyNonce(), sealed.copyCiphertextAndTag()) })
    }

    suspend fun insertAdmission(value: LocalDeviceAdmission) {
        if (value.revision.value != 0L) refuse()
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(value.scope, value.deviceId), value.revision)
        val sealed = sealOwned(binding, validated { ProtectedLocalSecurityCodec.encodeAdmission(value) }, invalidation = LocalMasterRpcSessionInvalidation.Admission(value))
        operation { dao.insertAdmission(ProtectedDeviceAdmissionRow(value.scope.storageKey(), value.deviceId.value,
            binding.schemaVersion, sealed.formatVersion, sealed.algorithm, 0, keyReference.keyId.value,
            sealed.copyNonce(), sealed.copyCiphertextAndTag())) }
        writes++
    }

    suspend fun insertThrottle(value: PersistentThrottleState) {
        if (value.revision.value != 0L || value.activeAttempt != null || value.failures != 0 || value.remainingDelayMillis != 0L) refuse()
        val key = ProtectedLocalRecordKey.Throttle(value.key)
        val binding = ProtectedLocalBinding(key, value.revision)
        val sealed = sealOwned(binding, validated { ProtectedLocalSecurityCodec.encodeThrottle(value) }, invalidation = LocalMasterRpcSessionInvalidation.None)
        operation { dao.insertThrottle(ProtectedLoginThrottleRow(value.key.scope.storageKey(), value.key.actorId.value,
            key.dimensionKey, binding.schemaVersion, sealed.formatVersion, sealed.algorithm, 0, keyReference.keyId.value,
            sealed.copyNonce(), sealed.copyCiphertextAndTag())) }
        writes++
    }

    suspend fun insertAuthConfiguration(value: LocalAuthConfiguration) {
        if (value.revision.value != 0L) refuse()
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.AuthConfiguration(value.scope, value.actorId), value.revision)
        val sealed = sealOwned(binding, validated { LocalAuthenticationCodec.encodeConfiguration(value) }, invalidation = LocalMasterRpcSessionInvalidation.None)
        operation { database.localAuthentication().insertConfiguration(ProtectedLocalAuthConfigurationRow(
            value.scope.storageKey(), value.actorId.value, value.loginKey, binding.schemaVersion, sealed.formatVersion,
            sealed.algorithm, 0, keyReference.keyId.value, sealed.copyNonce(), sealed.copyCiphertextAndTag())) }
        writes++
    }

    suspend fun insertRefresh(value: LocalRefreshIssuance) {
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.RefreshIssuance(value.scope, value.sessionId), value.revision)
        val sealed = sealOwned(binding, validated { LocalAuthenticationCodec.encodeRefresh(value) }, invalidation = LocalMasterRpcSessionInvalidation.None)
        operation { database.localAuthentication().insertRefresh(ProtectedLocalRefreshIssuanceRow(value.scope.storageKey(),
            value.sessionId.value, binding.schemaVersion, sealed.formatVersion, sealed.algorithm, 0, keyReference.keyId.value,
            sealed.copyNonce(), sealed.copyCiphertextAndTag())) }
        writes++
    }

    suspend fun insertAuthEvent(value: LocalAuthEvent) {
        if (value.kind == com.ukkera.pos.security.LocalAuthEventKind.ACTION_APPROVAL_RETIRED) {
            val frame = database.runtimeHistoryOwner?.frameFor(connection) ?: refuse()
            frame.requireApprovalRetirementEvent(value)
        }
        if (value.kind == com.ukkera.pos.security.LocalAuthEventKind.SERIAL_PUBLICATION_REQUEST) {
            val frame = database.runtimeHistoryOwner?.frameFor(connection) ?: refuse()
            frame.requireSerialPublicationEvent(value)
        }
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.AuthEvent(value.scope, value.eventId), value.revision)
        val sealed = sealOwned(binding, validated { LocalAuthenticationCodec.encodeEvent(value) }, invalidation = LocalMasterRpcSessionInvalidation.None, event = value)
        operation { database.localAuthentication().insertEvent(ProtectedLocalAuthEventRow(value.scope.storageKey(),
            value.eventId.value, binding.schemaVersion, sealed.formatVersion, sealed.algorithm, 0, keyReference.keyId.value,
            sealed.copyNonce(), sealed.copyCiphertextAndTag())) }
        writes++
    }

    suspend fun insertActionApproval(value: ProtectedLocalActionApproval) {
        val record = value.record
        if (!value.hasApproverIncarnation || record.revision != Revision(0) || record.consumedByOperationId != null) refuse()
        val scope = record.requirement.scope as? BranchScope ?: refuse()
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.ActionApproval(scope, record.approvalId), record.revision)
        val sealed = sealOwned(binding, validated { LocalActionApprovalCodec.encode(value) }, invalidation = LocalMasterRpcSessionInvalidation.None, approval = value)
        operation { database.localActionApprovals().insert(ProtectedLocalActionApprovalRow(scope.storageKey(), record.approvalId.value,
            binding.schemaVersion, sealed.formatVersion, sealed.algorithm, 0, keyReference.keyId.value,
            sealed.copyNonce(), sealed.copyCiphertextAndTag())) }
        writes++
    }

    suspend fun consumeActionApproval(expected: ProtectedLocalActionApproval, next: ProtectedLocalActionApproval): Boolean {
        val scope = expected.record.requirement.scope as? BranchScope ?: refuse()
        val current = readActionApproval(scope, expected.record.approvalId) ?: return conflict()
        if (readVerifiedActionApprovalRetirement(this, current) != null) return conflict()
        val boundOperation = expected.record.requirement.binding?.operationId ?: refuse()
        if (!expected.hasApproverIncarnation || !next.hasApproverIncarnation ||
            expected.record.revision != Revision(0) || expected.record.consumedByOperationId != null ||
            next.record != expected.record.copy(revision = Revision(1), consumedByOperationId = boundOperation) ||
            !sameApproval(current, expected) || !sameApproval(expected.consumed(next.record), next)) return conflict()
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.ActionApproval(scope, next.record.approvalId), Revision(1))
        val sealed = sealOwned(binding, validated { LocalActionApprovalCodec.encode(next) }, invalidation = LocalMasterRpcSessionInvalidation.None, approval = next)
        return wrote(operation { database.localActionApprovals().consume(scope.storageKey(), next.record.approvalId.value,
            binding.schemaVersion, sealed.formatVersion, sealed.algorithm, keyReference.keyId.value,
            sealed.copyNonce(), sealed.copyCiphertextAndTag()) })
    }

    private fun sameApproval(first: ProtectedLocalActionApproval, second: ProtectedLocalActionApproval): Boolean = validated {
        val a = LocalActionApprovalCodec.encode(first)
        try {
            val b = LocalActionApprovalCodec.encode(second)
            try { a.contentEquals(b) } finally { b.fill(0) }
        } finally { a.fill(0) }
    }

    private suspend fun insertSessionArchive(value: LocalSessionArchive) {
        val session = value.session
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.SessionArchive(session.scope, session.sessionId), value.revision)
        val sealed = sealOwned(binding, validated { LocalAuthenticationCodec.encodeArchive(value) }, invalidation = LocalMasterRpcSessionInvalidation.None)
        operation { database.localAuthentication().insertArchive(ProtectedLocalSessionArchiveRow(session.scope.storageKey(),
            session.sessionId.value, binding.schemaVersion, sealed.formatVersion, sealed.algorithm, 0, keyReference.keyId.value,
            sealed.copyNonce(), sealed.copyCiphertextAndTag())) }
        writes++
    }

    /**
     * The ONLY history-removal path. Inserts exact immutable revoked records and CASes the
     * account in this transaction; any insert/CAS/COMMIT failure rolls back the whole archive.
     * Live sessions, credentials, original session identity/generation and issuances are unchanged.
     */
    suspend fun archiveRevokedSessions(scope: BranchScope, actorId: DomainId, now: MonotonicMoment): LocalAccountSessionState {
        val current = readProtectedAccount(scope, actorId) ?: refuse()
        val before = current.state
        val archival = validated { LocalSessionMaintenance.archiveWhenFull(before) } ?: return before
        for (session in archival.archived) insertSessionArchive(LocalSessionArchive(session, now))
        val next = archival.retained
        if (!writeAccount(before.revision, ProtectedLocalAccount(next, current.credentialRevision, current.credential), current.credentialRevision)) refuse()
        return next
    }

    /**
     * A freshly password-authenticated login need not explicitly move a work session which is
     * already cryptographically unusable after expiry/restart/revocation. Access expiry alone
     * does NOT retire a still-renewable work session. No timestamp/issuance is ever backfilled.
     */
    suspend fun retireUnusableInteractiveSessions(scope: BranchScope, actorId: DomainId, current: RoomLocalAuthorizationObservation) {
        val account = readProtectedAccount(scope, actorId) ?: refuse()
        val expired = mutableSetOf<DomainId>()
        for (session in account.state.sessions) {
            if (session.status != SessionStatus.ACTIVE || session.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) continue
            val access = readIssuance(scope, session.sessionId) ?: continue
            val refresh = readRefresh(scope, session.sessionId)
            val admission = readAdmission(scope, session.deviceId) ?: refuse()
            if (validated { LocalSessionMaintenance.retirementRequired(account, session, access, refresh, admission,
                    current.publicValue()) }) expired.add(session.sessionId)
        }
        if (expired.isNotEmpty()) {
            val next = LocalSessions.revokeSelected(account.state, expired) as? SessionTransition.Applied ?: refuse()
            if (!replaceAccount(account.state.revision, next.state)) refuse()
        }
    }

    /** Credential replacement advances its independent binding and revokes ALL active sessions. */
    suspend fun resetCredential(scope: BranchScope, actorId: DomainId, expectedAccountRevision: Revision,
        expectedCredentialRevision: Revision, credential: PasswordHashRecord): LocalAccountSessionState? {
        val current = readProtectedAccount(scope, actorId) ?: return null
        if (current.state.revision != expectedAccountRevision || current.credentialRevision != expectedCredentialRevision ||
            expectedCredentialRevision.value == Long.MAX_VALUE || !current.state.enabled) return null
        val transition = LocalSessions.revokeAll(current.state) as? SessionTransition.Applied ?: refuse()
        if (!transition.state.enabled) return null // Counter exhaustion is not a usable credential reset.
        val next = validated { ProtectedLocalAccount(transition.state, expectedCredentialRevision.next(), credential) }
        if (!writeAccount(expectedAccountRevision, next, current.credentialRevision)) refuse()
        return next.state
    }

    /** The sole enable path; the caller must hold current explicit digest-bound ceremony authority. */
    suspend fun reactivateAccount(target: com.ukkera.pos.security.LocalAccountReactivation): LocalAccountSessionState? {
        val account = readProtectedAccount(target.scope, target.actorId) ?: return null
        val before = account.state
        val policy = readPolicy(target.scope, target.actorId) ?: return null
        val configuration = readAuthConfiguration(target.scope, target.actorId) ?: return null
        if (before.enabled || before.revision != target.expectedAccountRevision ||
            account.credentialRevision != target.expectedCredentialRevision || policy.revision != target.expectedPolicyRevision ||
            configuration.revision != target.expectedConfigurationRevision || before.revision.value >= Long.MAX_VALUE - 1 ||
            before.generation.value >= Long.MAX_VALUE - 1 || before.sessions.any { it.status == SessionStatus.ACTIVE }) return null
        if (readThrottle(ThrottleKey(target.scope, target.actorId, ThrottleDimension.Account)) == null) refuse()
        for (device in readAdmissions(target.scope)) {
            if (readThrottle(ThrottleKey(target.scope, target.actorId, ThrottleDimension.Device(device.deviceId))) == null) refuse()
        }
        val next = LocalAccountSessionState(before.scope, before.actorId, true, before.revision.next(),
            before.generation.next(), before.sessions)
        if (!writeAccount(before.revision, ProtectedLocalAccount(next, account.credentialRevision, account.credential), account.credentialRevision)) refuse()
        return next
    }

    /** Maintenance/local revocation only. Never re-admits or replaces the public device key. */
    suspend fun revokeAdmission(scope: BranchScope, deviceId: DomainId): Boolean {
        val old = readAdmission(scope, deviceId) ?: return false
        if (!old.admitted) return true
        if (old.revision.value == Long.MAX_VALUE) refuse()
        val next = LocalDeviceAdmission(scope, deviceId, old.revision.next(), false,
            old.publicKeyIdentifier, old.publicFingerprint, old.capabilities)
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(scope, deviceId), next.revision)
        val sealed = sealOwned(binding, validated { ProtectedLocalSecurityCodec.encodeAdmission(next) }, invalidation = LocalMasterRpcSessionInvalidation.Admission(next))
        return wrote(operation { dao.replaceAdmission(scope.storageKey(), deviceId.value, old.revision.value, next.revision.value,
            binding.schemaVersion, sealed.formatVersion, sealed.algorithm, keyReference.keyId.value,
            sealed.copyNonce(), sealed.copyCiphertextAndTag()) })
    }

    /** Called only after the explicit trusted re-pair ceremony; never a generic admission upsert. */
    suspend fun readmitAdmission(expectedRevision: Revision, next: LocalDeviceAdmission): Boolean {
        val previous = readAdmission(next.scope, next.deviceId) ?: return conflict()
        if (previous.admitted || !next.admitted || previous.revision != expectedRevision ||
            expectedRevision.value == Long.MAX_VALUE || next.revision.value != expectedRevision.value + 1) return conflict()
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.Admission(next.scope, next.deviceId), next.revision)
        val sealed = sealOwned(binding, validated { ProtectedLocalSecurityCodec.encodeAdmission(next) }, invalidation = LocalMasterRpcSessionInvalidation.Admission(next))
        return wrote(operation { dao.replaceAdmission(next.scope.storageKey(), next.deviceId.value, expectedRevision.value, next.revision.value,
            binding.schemaVersion, sealed.formatVersion, sealed.algorithm, keyReference.keyId.value,
            sealed.copyNonce(), sealed.copyCiphertextAndTag()) })
    }

    /**
     * Inert, immutable persistence primitive, not login or a principal factory. No account,
     * policy, admission or bearer is manufactured/validated here. Duplicate/failed inserts
     * poison the owning transaction even if a caller catches the sanitized exception.
     */
    suspend fun insertIssuance(value: LocalAccessIssuance) {
        val binding = validated {
            ProtectedLocalBinding(ProtectedLocalRecordKey.SessionIssuance(value.scope, value.sessionId), value.revision)
        }
        val plaintext = validated { ProtectedLocalSecurityCodec.encodeIssuance(value) }
        val sealed = sealOwned(binding, plaintext, invalidation = LocalMasterRpcSessionInvalidation.None)
        operation {
            database.localAccessIssuance().insert(ProtectedLocalAccessIssuanceRow(
                value.scope.storageKey(), value.sessionId.value, binding.schemaVersion, sealed.formatVersion,
                sealed.algorithm, binding.revision.value, keyReference.keyId.value,
                sealed.copyNonce(), sealed.copyCiphertextAndTag(),
            ))
        }
        writes++
    }

    /** Four authenticated snapshots, with no credential/verifier object in the projection. */
    internal suspend fun readAuthorizationFacts(principal: LocalEmployeePrincipal): RoomLocalAuthorizationFacts? {
        val account = observedRead(ProtectedLocalRecordKey.Account(principal.scope, principal.actorId),
            ProtectedLocalSecurityCodec::decodeAccount) ?: return null
        val policy = observedRead(ProtectedLocalRecordKey.EffectivePolicy(principal.scope, principal.actorId),
            LocalEffectivePolicyCodec::decodePolicy) ?: return null
        val admission = observedRead(ProtectedLocalRecordKey.Admission(principal.scope, principal.deviceId),
            ProtectedLocalSecurityCodec::decodeAdmission) ?: return null
        val issuance = observedRead(ProtectedLocalRecordKey.SessionIssuance(principal.scope, principal.sessionId),
            ProtectedLocalSecurityCodec::decodeIssuance) ?: return null
        return validated {
            RoomLocalAuthorizationFacts(account.value.state, account.value.credentialRevision,
                account.value.credential != null, policy.value, admission.value, issuance.value,
                listOf(account.fingerprint, policy.fingerprint, admission.fingerprint, issuance.fingerprint))
        }
    }

    internal suspend fun readApprovalApprover(scope: BranchScope, actorId: DomainId, deviceId: DomainId): RoomApprovalApproverFacts? {
        val account = observedRead(ProtectedLocalRecordKey.Account(scope, actorId), ProtectedLocalSecurityCodec::decodeAccount) ?: return null
        val policy = observedRead(ProtectedLocalRecordKey.EffectivePolicy(scope, actorId), LocalEffectivePolicyCodec::decodePolicy) ?: return null
        val admission = observedRead(ProtectedLocalRecordKey.Admission(scope, deviceId), ProtectedLocalSecurityCodec::decodeAdmission) ?: return null
        return RoomApprovalApproverFacts(account.value.state, account.value.credentialRevision, account.value.credential != null,
            policy.value, admission.value, listOf(account.fingerprint, policy.fingerprint, admission.fingerprint))
    }

    /**
     * Locator-first authentication read. Actor/device originate only in authenticated issuance,
     * not an unverified principal or bearer claims. The projection never exposes a password
     * record. As with authorization reads, absence denies and decoder/AEAD errors poison the
     * transaction. Fingerprint order stays account/policy/admission/issuance.
     */
    internal suspend fun readLocalAccessFacts(scope: BranchScope, sessionId: DomainId): RoomLocalAuthorizationFacts? {
        val issuance = observedRead(ProtectedLocalRecordKey.SessionIssuance(scope, sessionId),
            ProtectedLocalSecurityCodec::decodeIssuance) ?: return null
        val actorId = issuance.value.actorId
        val account = observedRead(ProtectedLocalRecordKey.Account(scope, actorId),
            ProtectedLocalSecurityCodec::decodeAccount) ?: return null
        val policy = observedRead(ProtectedLocalRecordKey.EffectivePolicy(scope, actorId),
            LocalEffectivePolicyCodec::decodePolicy) ?: return null
        val admission = observedRead(ProtectedLocalRecordKey.Admission(scope, issuance.value.deviceId),
            ProtectedLocalSecurityCodec::decodeAdmission) ?: return null
        return validated {
            RoomLocalAuthorizationFacts(account.value.state, account.value.credentialRevision,
                account.value.credential != null, policy.value, admission.value, issuance.value,
                listOf(account.fingerprint, policy.fingerprint, admission.fingerprint, issuance.fingerprint))
        }
    }

    internal suspend fun ensureAuthorizationHealthy() {
        // Both qualified-status reads and live-connection checks precede the final clock phase.
        // This is not evidence that the injected provider has actually been qualified.
        protectedOperation { Unit }
        ensureCommittable()
    }

    override suspend fun replaceAccount(expectedRevision: Revision, state: LocalAccountSessionState): Boolean {
        // Snapshot before the first suspension. LocalAccountSessionState exposes List, which
        // is not by itself proof against a caller retaining/casting a mutable backing list.
        val next = validated {
            val sessions = state.sessions.toList()
            LocalAccountSessionState(state.scope, state.actorId, state.enabled, state.revision, state.generation, sessions)
        }
        val current = readProtectedAccount(next.scope, next.actorId) ?: return conflict()
        val previous = current.state
        if (previous.revision != expectedRevision || !validSessionReplacement(previous, next)) return conflict()
        if (next.sessions.size > previous.sessions.size &&
            readSessionArchive(next.scope, next.sessions.last().sessionId) != null) return conflict()
        if (previous.revision.value == Long.MAX_VALUE) {
            // Only exact current terminal state is an idempotent no-op. The schema correctly
            // prohibits UPDATE at MAX; stale or active/history-reset states never get here.
            return true
        }
        return writeAccount(expectedRevision, ProtectedLocalAccount(next, current.credentialRevision, current.credential), current.credentialRevision)
    }

    private suspend fun writeAccount(expectedRevision: Revision, value: ProtectedLocalAccount, previousCredentialRevision: Revision): Boolean {
        val next = value.state
        val binding = ProtectedLocalBinding(ProtectedLocalRecordKey.Account(next.scope, next.actorId), next.revision)
        val plaintext = validated { ProtectedLocalSecurityCodec.encodeAccount(value) }
        val sealed = sealOwned(binding, plaintext, invalidation = LocalMasterRpcSessionInvalidation.Account(value, previousCredentialRevision))
        return wrote(operation {
            dao.replaceAccount(
                next.scope.storageKey(), next.actorId.value, expectedRevision.value, next.revision.value,
                binding.schemaVersion, sealed.formatVersion, sealed.algorithm, keyReference.keyId.value,
                sealed.copyNonce(), sealed.copyCiphertextAndTag(),
            )
        })
    }

    suspend fun replaceThrottle(expectedRevision: Revision, state: PersistentThrottleState): Boolean {
        // The existing throttle payload consists solely of immutable scalar/value objects.
        val current = readThrottle(state.key) ?: return conflict()
        if (current.revision != expectedRevision || expectedRevision.value == Long.MAX_VALUE ||
            state.revision.value != expectedRevision.value + 1
        ) return conflict()
        val key = ProtectedLocalRecordKey.Throttle(state.key)
        val binding = ProtectedLocalBinding(key, state.revision)
        val plaintext = validated { ProtectedLocalSecurityCodec.encodeThrottle(state) }
        val sealed = sealOwned(binding, plaintext, invalidation = LocalMasterRpcSessionInvalidation.None)
        return wrote(operation {
            dao.replaceThrottle(
                state.key.scope.storageKey(), state.key.actorId.value, key.dimensionKey,
                expectedRevision.value, state.revision.value, binding.schemaVersion,
                sealed.formatVersion, sealed.algorithm, keyReference.keyId.value,
                sealed.copyNonce(), sealed.copyCiphertextAndTag(),
            )
        })
    }

    private suspend fun <T> read(
        key: ProtectedLocalRecordKey,
        decode: (ProtectedLocalBinding, ByteArray) -> T,
    ): T? {
        val metadata = operation { metadata(key) } ?: return null
        val maximumCiphertext = key.table.maximumPlaintextBytes.toLong() + ProtectedLocalStorageFormat.TAG_BYTES
        if (!metadata.storageValid || metadata.revision < 0 ||
            metadata.ciphertextByteCount !in (ProtectedLocalStorageFormat.TAG_BYTES + 1L)..maximumCiphertext
        ) refuse()
        // The expected key comes from the requested lookup, revision from independently checked
        // integer metadata. No ciphertext or unbounded clear string has been fetched yet.
        val expected = validated { ProtectedLocalBinding(key, Revision(metadata.revision)) }
        val envelope = operation { envelope(key) } ?: refuse()
        val sealed = validated { envelope.checked(expected, metadata, keyReference) }
        val opened = when (val result = protectedOperation { protection.open(keyReference, expected, sealed) }) {
            is LocalOpenResult.Opened -> result
            is LocalOpenResult.Unavailable -> refuse()
        }
        val plaintext = validated {
            expected.requirePlaintextSize(opened.size)
            check(opened.size.toLong() + ProtectedLocalStorageFormat.TAG_BYTES == metadata.ciphertextByteCount)
            opened.copyPlaintext()
        }
        return try {
            validated {
                decode(expected, plaintext).also { value ->
                    if (value is LocalAuthConfiguration && value.loginKey != envelope.loginKey) refuse()
                }
            }
        } finally {
            // Best effort for this owned buffer; no assertion of complete runtime erasure.
            plaintext.fill(0)
        }
    }

    private suspend fun <T> observedRead(
        key: ProtectedLocalRecordKey,
        decode: (ProtectedLocalBinding, ByteArray) -> T,
    ): ObservedLocalRecord<T>? = read(key) { binding, plaintext ->
        // read() already authenticates and strictly decodes before its owned bytes are cleared.
        // Hash the complete canonical payload, not a lossy selection of public status fields.
        ObservedLocalRecord(decode(binding, plaintext), sha256(plaintext))
    }

    private suspend fun sealOwned(binding: ProtectedLocalBinding, plaintext: ByteArray,
        invalidation: LocalMasterRpcSessionInvalidation,
        event: LocalAuthEvent? = null, approval: ProtectedLocalActionApproval? = null): SealedLocalRecord {
        // Keep the canonical snapshot independent of a provider retaining/modifying its input.
        val providerInput = plaintext.copyOf()
        return try {
            validated { binding.requirePlaintextSize(plaintext.size) }
            // Irreversible same-atom denial BEFORE any actual protected provider/SQL mutation.
            // Event/approval keys alone lack their targets: pass the actual typed record from
            // their sole insert/consume sites; missing/mismatched coverage denies the scope.
            physicalRegistry?.denyProtectedWrite(binding.key, event, approval)
            when (invalidation) {
                is LocalMasterRpcSessionInvalidation.Account -> {
                    val key = binding.key as? ProtectedLocalRecordKey.Account ?: refuse()
                    if (key.scope != invalidation.scope || key.actorId != invalidation.actorId) refuse()
                }
                is LocalMasterRpcSessionInvalidation.Admission -> {
                    val key = binding.key as? ProtectedLocalRecordKey.Admission ?: refuse()
                    if (key.scope != invalidation.scope || key.deviceId != invalidation.deviceId) refuse()
                }
                is LocalMasterRpcSessionInvalidation.Policy -> {
                    val key = binding.key as? ProtectedLocalRecordKey.EffectivePolicy ?: refuse()
                    if (key.scope != invalidation.scope || key.actorId != invalidation.actorId) refuse()
                }
                LocalMasterRpcSessionInvalidation.None -> when (binding.key) {
                    is ProtectedLocalRecordKey.Account, is ProtectedLocalRecordKey.Admission,
                    is ProtectedLocalRecordKey.EffectivePolicy -> refuse()
                    is ProtectedLocalRecordKey.Throttle,
                    is ProtectedLocalRecordKey.SessionIssuance, is ProtectedLocalRecordKey.AuthConfiguration,
                    is ProtectedLocalRecordKey.RefreshIssuance, is ProtectedLocalRecordKey.AuthEvent,
                    is ProtectedLocalRecordKey.SessionArchive, is ProtectedLocalRecordKey.ActionApproval -> Unit
                }
                LocalMasterRpcSessionInvalidation.All -> refuse()
            }
            fenceRpcSessionsBeforeWrite(invalidation)
            val sealed = when (val result = protectedOperation { protection.seal(keyReference, binding, providerInput) }) {
                is LocalSealResult.Sealed -> result.record
                is LocalSealResult.Unavailable -> refuse()
            }
            validated {
                check(sealed.binding == binding && sealed.keyReference == keyReference)
                check(sealed.formatVersion == ProtectedLocalStorageFormat.FORMAT_VERSION)
                check(sealed.algorithm == ProtectedLocalStorageFormat.ALGORITHM)
                check(sealed.copyNonce().size == ProtectedLocalStorageFormat.NONCE_BYTES)
                check(sealed.ciphertextAndTagSize == plaintext.size + ProtectedLocalStorageFormat.TAG_BYTES)
                database.runtimeHistoryOwner?.frameFor(connection)?.captureProtectedImage(binding, plaintext)
                sealed
            }
        } finally {
            plaintext.fill(0)
            providerInput.fill(0)
        }
    }

    private suspend fun fenceRpcSessionsBeforeWrite(invalidation: LocalMasterRpcSessionInvalidation) {
        ensureCurrent()
        try {
            val fence = rpcSessionFence
            // The separate original pairing census is read-only. A transaction lacking this
            // owner's concrete fence cannot silently become another protected mutation lane.
            if (fence == null) {
                if (database.runtimeHistoryOwner != null) refuse()
            } else fence.beforeProtectedWrite(invalidation)
            ensureCurrent()
            // Principal-verification reads share this same outer writer/native loan through
            // settlement; no fresh session can publish pre-write facts between paired writes.
        } catch (cancelled: CancellationException) {
            failed = true
            throw cancelled
        } catch (_: Exception) {
            refuse()
        } catch (failure: Throwable) {
            failed = true
            throw failure
        }
    }

    private suspend fun <T> operation(block: suspend () -> T): T {
        ensureCurrent()
        val result = try {
            block()
        } catch (cancelled: CancellationException) {
            failed = true
            throw cancelled
        } catch (failure: Exception) {
            // Retain the original static SQL failure location before uncertainty normalization.
            // No diagnostic failure, message, bound value or SQL text may escape this boundary.
            try {
                val owner = database.runtimeHistoryOwner
                if (owner != null && owner.originalRecord.record.parent == null) {
                    reportOriginalInstallationBodyFailure(failure)
                }
            } catch (_: Throwable) { /* Observational only. */ }
            uncertain()
        }
        ensureCurrent()
        return result
    }

    private suspend fun <T> protectedOperation(block: suspend () -> T): T {
        ensureCurrent()
        val result = try {
            check(protection.protectionStatus == KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT)
            val value = block()
            check(protection.protectionStatus == KeyProtectionStatus.QUALIFIED_SUPERVISED_SERVICE_CONTEXT)
            value
        } catch (cancelled: CancellationException) {
            failed = true
            throw cancelled
        } catch (_: Exception) {
            // A missing/unavailable key provider is NOT a SQL writer/cleanup failure. The
            // enclosing transaction still must return a proven rollback before this is benign.
            refuse()
        }
        ensureCurrent()
        return result
    }

    private fun <T> validated(block: () -> T): T = try {
        check(active && !failed)
        block()
    } catch (cancelled: CancellationException) {
        failed = true
        throw cancelled
    } catch (_: Exception) {
        refuse()
    }

    private fun conflict(): Boolean {
        conflicted = true
        return false
    }

    private fun wrote(count: Int): Boolean {
        if (count == 0) return conflict()
        if (count != 1) refuse()
        writes++
        return true
    }

    private suspend fun metadata(key: ProtectedLocalRecordKey): LocalSecurityMetadata? {
        val scope = key.scope.storageKey()
        val id = key.recordId.value
        return when (key) {
            is ProtectedLocalRecordKey.Account -> dao.accountMetadata(scope, id)
            is ProtectedLocalRecordKey.EffectivePolicy -> dao.policyMetadata(scope, id)
            is ProtectedLocalRecordKey.Admission -> dao.admissionMetadata(scope, id)
            is ProtectedLocalRecordKey.Throttle -> dao.throttleMetadata(scope, id, key.dimensionKey)
            is ProtectedLocalRecordKey.SessionIssuance -> database.localAccessIssuance().metadata(scope, id)
            is ProtectedLocalRecordKey.AuthConfiguration -> database.localAuthentication().configurationMetadata(scope, id)
            is ProtectedLocalRecordKey.RefreshIssuance -> database.localAuthentication().refreshMetadata(scope, id)
            is ProtectedLocalRecordKey.AuthEvent -> database.localAuthentication().eventMetadata(scope, id)
            is ProtectedLocalRecordKey.SessionArchive -> database.localAuthentication().archiveMetadata(scope, id)
            is ProtectedLocalRecordKey.ActionApproval -> database.localActionApprovals().metadata(scope, id)
        }
    }

    private suspend fun envelope(key: ProtectedLocalRecordKey): LocalEnvelope? {
        val scope = key.scope.storageKey()
        val id = key.recordId.value
        return when (key) {
            is ProtectedLocalRecordKey.Account -> dao.account(scope, id)?.let {
                LocalEnvelope(it.scope, it.actorId, null, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
            }
            is ProtectedLocalRecordKey.EffectivePolicy -> dao.policy(scope, id)?.let {
                LocalEnvelope(it.scope, it.actorId, null, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
            }
            is ProtectedLocalRecordKey.Admission -> dao.admission(scope, id)?.let {
                LocalEnvelope(it.scope, it.deviceId, null, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
            }
            is ProtectedLocalRecordKey.Throttle -> dao.throttle(scope, id, key.dimensionKey)?.let {
                LocalEnvelope(it.scope, it.actorId, it.dimensionKey, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
            }
            is ProtectedLocalRecordKey.SessionIssuance -> database.localAccessIssuance().read(scope, id)?.let {
                LocalEnvelope(it.scope, it.sessionId, null, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
            }
            is ProtectedLocalRecordKey.AuthConfiguration -> database.localAuthentication().configuration(scope, id)?.let {
                LocalEnvelope(it.scope, it.actorId, null, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag, it.loginKey)
            }
            is ProtectedLocalRecordKey.RefreshIssuance -> database.localAuthentication().refresh(scope, id)?.let {
                LocalEnvelope(it.scope, it.sessionId, null, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
            }
            is ProtectedLocalRecordKey.AuthEvent -> database.localAuthentication().event(scope, id)?.let {
                LocalEnvelope(it.scope, it.eventId, null, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
            }
            is ProtectedLocalRecordKey.SessionArchive -> database.localAuthentication().archive(scope, id)?.let {
                LocalEnvelope(it.scope, it.sessionId, null, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
            }
            is ProtectedLocalRecordKey.ActionApproval -> database.localActionApprovals().approval(scope, id)?.let {
                LocalEnvelope(it.scope, it.approvalId, null, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
            }
        }
    }
}

private class ObservedLocalRecord<T>(val value: T, val fingerprint: PayloadDigest)

private class LocalEnvelope(
    val scope: String,
    val recordId: String,
    val dimensionKey: String?,
    val schemaVersion: Int,
    val formatVersion: Int,
    val algorithm: String,
    val revision: Long,
    val keyId: String,
    val nonce: ByteArray,
    val ciphertextAndTag: ByteArray,
    val loginKey: String? = null,
) {
    fun checked(
        expected: ProtectedLocalBinding,
        metadata: LocalSecurityMetadata,
        permittedKey: ProtectedStorageKeyReference,
    ): SealedLocalRecord {
        check(scope == expected.key.scope.storageKey() && recordId == expected.key.recordId.value)
        check(dimensionKey == (expected.key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey)
        check(schemaVersion == expected.schemaVersion && formatVersion == ProtectedLocalStorageFormat.FORMAT_VERSION)
        check(algorithm == ProtectedLocalStorageFormat.ALGORITHM && revision == expected.revision.value)
        check(keyId == permittedKey.keyId.value)
        check(nonce.size == ProtectedLocalStorageFormat.NONCE_BYTES)
        check(ciphertextAndTag.size.toLong() == metadata.ciphertextByteCount)
        return SealedLocalRecord(expected, permittedKey, nonce, ciphertextAndTag)
    }
}

private fun validSessionReplacement(previous: LocalAccountSessionState, next: LocalAccountSessionState): Boolean {
    if (previous.scope != next.scope || previous.actorId != next.actorId) return false
    val terminal = previous.revision.value >= Long.MAX_VALUE - 1 || previous.generation.value >= Long.MAX_VALUE - 1
    if (terminal) {
        return next.revision.value == Long.MAX_VALUE && next.generation.value == Long.MAX_VALUE && !next.enabled &&
            next.sessions.size == previous.sessions.size && previous.sessions.zip(next.sessions).all { (before, after) ->
                after == before.copy(status = SessionStatus.REVOKED)
            }
    }
    if (next.revision.value != previous.revision.value + 1 || next.generation.value != previous.generation.value + 1 ||
        (!previous.enabled && next.enabled) || next.sessions.size !in previous.sessions.size..(previous.sessions.size + 1)
    ) return false
    for (index in previous.sessions.indices) {
        val before = previous.sessions[index]
        val after = next.sessions[index]
        // Preserve full ordered history and all original identity/purpose/generation fields.
        if (after != before.copy(status = after.status) ||
            (before.status == SessionStatus.REVOKED && after.status != SessionStatus.REVOKED)
        ) return false
    }
    if (next.sessions.size != previous.sessions.size) {
        val added = next.sessions.last()
        if (!next.enabled || added.status != SessionStatus.ACTIVE || added.generation != next.generation ||
            added.authenticationPurpose == AuthenticationPurpose.SERVICE
        ) return false
    }
    return true
}

/** No provider/parser/SQL/callback details or private data is attached, even as a cause. */
internal fun protectedLocalUnavailable(): Nothing = throw IllegalStateException("Protected local record unavailable")

private class ProtectedCensusLocator(val rowid: Long, val scope: String, val id: String, val dimension: String)
internal fun protectedPhysicalTable(table: ProtectedLocalTable): String = when (table) {
    ProtectedLocalTable.ACCOUNT -> "protected_local_account"
    ProtectedLocalTable.EFFECTIVE_POLICY -> "protected_effective_policy"
    ProtectedLocalTable.ADMISSION -> "protected_device_admission"
    ProtectedLocalTable.THROTTLE -> "protected_login_throttle"
    ProtectedLocalTable.SESSION_ISSUANCE -> "protected_local_access_issuance"
    ProtectedLocalTable.AUTH_CONFIGURATION -> "protected_local_auth_configuration"
    ProtectedLocalTable.REFRESH_ISSUANCE -> "protected_local_refresh_issuance"
    ProtectedLocalTable.AUTH_EVENT -> "protected_local_auth_event"
    ProtectedLocalTable.SESSION_ARCHIVE -> "protected_local_session_archive"
    ProtectedLocalTable.ACTION_APPROVAL -> "protected_local_action_approval"
}
