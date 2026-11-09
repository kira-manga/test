package com.ukkera.pos.android.independent.nativeoriginalackv4

import android.app.Application
import com.ukkera.pos.android.independent.aosfsetupv2.cleanupAosf
import com.ukkera.pos.android.independent.nativefixture.NativeLocalFixtureEvidence
import com.ukkera.pos.android.independent.nativefixture.requireSameNativeFixtureCoordinates
import com.ukkera.pos.app.gateway.AndroidLocalBranchRuntime
import com.ukkera.pos.database.AndroidSelfOwnedMasterOpenResult
import com.ukkera.pos.database.AndroidSelfOwnedMasterService
import com.ukkera.pos.database.LocalMasterLifecycleMode
import com.ukkera.pos.database.LocalOriginalInstallationSetup
import com.ukkera.pos.security.AndroidSelfOwnedInstallationOwner
import com.ukkera.pos.security.NativeHistoryDatabaseIdentityV2
import com.ukkera.pos.security.SelfOwnedAllocationCoordinatesV1
import com.ukkera.pos.security.SelfOwnedInstallationFailureV1
import com.ukkera.pos.security.SelfOwnedLocalSecurityContext
import com.ukkera.pos.security.SelfOwnedNativeHistoryClaimResult
import com.ukkera.pos.security.SelfOwnedNativeHistoryCustody
import com.ukkera.pos.security.SelfOwnedNativeMaterialView
import com.ukkera.pos.security.SelfOwnedNativeOpenResult
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Independent, non-transferable observation of the actual original installation identity. It is
 * assembled only from the saved production receipt/definition/company view and the native
 * material acquired for the same owner. It contains no capability, secret or authority.
 */
internal data class NativeAckAnchor(
    val rootId: String,
    val rootFingerprint: String,
    val organizationId: String,
    val companyId: String,
    val branchId: String,
    val allocationId: String,
    val installationId: String,
    val assignmentId: String,
    val deviceId: String,
    val firstActorId: String,
    val storageInstanceId: String,
    val storageKeyId: String,
    val pepperKeyId: String,
    val targetSigningKeyId: String,
    val originalRequestId: String,
    val issuedAttemptId: String,
    val attemptSequence: Long,
    val definitionDigest: String,
    val receiptDigest: String,
    val genesisDigest: String,
    val authorityEpoch: Long,
    val protection: String,
    val storageKeySecurity: String,
    val targetSigningFingerprint: String,
    val nativeBindingDigest: String,
) {
    fun requireSame(other: NativeAckAnchor) {
        check(this == other) { "The saved original installation identity changed" }
    }

    fun retained(): JSONObject = JSONObject()
        .put("rootId", rootId).put("rootFingerprint", rootFingerprint)
        .put("organizationId", organizationId).put("companyId", companyId)
        .put("branchId", branchId).put("allocationId", allocationId)
        .put("installationId", installationId).put("assignmentId", assignmentId)
        .put("deviceId", deviceId).put("firstActorId", firstActorId)
        .put("storageInstanceId", storageInstanceId).put("storageKeyId", storageKeyId)
        .put("pepperKeyId", pepperKeyId).put("targetSigningKeyId", targetSigningKeyId)
        .put("originalRequestId", originalRequestId).put("issuedAttemptId", issuedAttemptId)
        .put("attemptSequence", attemptSequence).put("definitionDigest", definitionDigest)
        .put("receiptDigest", receiptDigest).put("genesisDigest", genesisDigest)
        .put("authorityEpoch", authorityEpoch).put("protection", protection)
        .put("storageKeySecurity", storageKeySecurity)
        .put("targetSigningFingerprint", targetSigningFingerprint)
        .put("nativeBindingDigest", nativeBindingDigest)
}

/** The original saved receipt/definition and acquired native material must describe one original. */
internal fun bindNativeAckAnchor(setup: LocalOriginalInstallationSetup,
    coordinates: SelfOwnedAllocationCoordinatesV1,
    material: SelfOwnedNativeMaterialView): NativeAckAnchor {
    val receipt = checkNotNull(setup.receipt) { "The confirmed original has no saved receipt" }
    val definition = checkNotNull(setup.definition) { "The confirmed original has no saved definition" }
    val installed = receipt.core.allocation.coordinates
    requireSameNativeFixtureCoordinates(coordinates, installed)
    requireSameNativeFixtureCoordinates(coordinates, material.coordinates)
    requireSameNativeFixtureCoordinates(coordinates, definition.definition.allocation.coordinates)
    check(setup.company.organizationId == coordinates.scope.organizationId &&
        setup.company.companyId == coordinates.scope.companyId) {
        "The original company authority changed"
    }
    check(receipt.core.originalRequestId == definition.definition.originalRequestId &&
        receipt.core.definitionDigest == definition.definitionDigest &&
        receipt.core.authorityEpoch.value == definition.definition.authorityEpoch.value) {
        "The saved receipt is not bound to the original definition/operation/epoch"
    }
    check(definition.definition.firstAccount.actorId == coordinates.firstActorId &&
        material.originalNativeBindingDigest.isNotEmpty()) {
        "The saved original actor or native binding is unavailable"
    }
    return NativeAckAnchor(
        rootId = setup.company.rootId.value,
        rootFingerprint = setup.company.rootFingerprint,
        organizationId = coordinates.scope.organizationId.value,
        companyId = coordinates.scope.companyId.value,
        branchId = coordinates.scope.branchId.value,
        allocationId = coordinates.allocationId.value,
        installationId = coordinates.installationId.value,
        assignmentId = coordinates.assignmentId.value,
        deviceId = coordinates.deviceId.value,
        firstActorId = coordinates.firstActorId.value,
        storageInstanceId = coordinates.storageInstanceId.value,
        storageKeyId = coordinates.storageKeyId.value,
        pepperKeyId = coordinates.pepperKeyId.value,
        targetSigningKeyId = coordinates.targetSigningKeyId.value,
        originalRequestId = receipt.core.originalRequestId.value,
        issuedAttemptId = receipt.core.issuedAttemptId.value,
        attemptSequence = receipt.core.attemptSequence,
        definitionDigest = definition.definitionDigest.value,
        receiptDigest = receipt.digest.value,
        genesisDigest = receipt.genesis.value,
        authorityEpoch = receipt.core.authorityEpoch.value,
        protection = material.protection.name,
        storageKeySecurity = material.storageKeySecurity.name,
        targetSigningFingerprint = material.targetSigningFingerprint,
        nativeBindingDigest = material.originalNativeBindingDigest,
    )
}

/**
 * Durable native original state observed through the production closed-native custody. This is a
 * read-only observation of the actual record; it never writes, signs or fabricates an ACK.
 */
internal data class NativeAckState(
    val recordDigest: String,
    val generation: Long,
    val denialGeneration: Long,
    val pending: Boolean,
    val database: NativeHistoryDatabaseIdentityV2?,
    val allocationDigest: String,
    val organizationId: String,
    val companyId: String,
    val branchId: String,
    val allocationId: String,
    val installationId: String,
    val assignmentId: String,
    val deviceId: String,
    val firstActorId: String,
    val storageInstanceId: String,
    val storageKeyId: String,
    val pepperKeyId: String,
    val targetSigningKeyId: String,
    val role: String?,
    val attemptPhase: String?,
    val definitionDigest: String?,
    val receiptDigest: String?,
    val receiptGenesis: String?,
    val acknowledgementDigest: String?,
    val acknowledgementFrameDigest: String?,
    val parentHistorySequence: Long?,
    val firstChildHistoryDigest: String?,
    val originalRequestId: String?,
    val issuedAttemptId: String?,
    val attemptSequence: Long?,
    val authorityEpoch: Long?,
) {
    fun requireSame(other: NativeAckState) {
        check(this == other) { "A read-attributable durable original/ACK state changed" }
    }

    fun requireConfirmed() {
        check(!pending && acknowledgementDigest != null && acknowledgementFrameDigest != null &&
            definitionDigest != null && receiptDigest != null && receiptGenesis != null &&
            parentHistorySequence != null && firstChildHistoryDigest != null) {
            "The actual native original ACK is not confirmed"
        }
    }

    fun retained(): JSONObject = JSONObject()
        .put("recordDigest", recordDigest).put("generation", generation)
        .put("denialGeneration", denialGeneration).put("pending", pending)
        .put("database", database?.toString()).put("allocationDigest", allocationDigest)
        .put("role", role).put("attemptPhase", attemptPhase)
        .put("definitionDigest", definitionDigest).put("receiptDigest", receiptDigest)
        .put("receiptGenesis", receiptGenesis)
        .put("acknowledgementDigest", acknowledgementDigest)
        .put("acknowledgementFrameDigest", acknowledgementFrameDigest)
        .put("parentHistorySequence", parentHistorySequence)
        .put("firstChildHistoryDigest", firstChildHistoryDigest)
        .put("originalRequestId", originalRequestId).put("issuedAttemptId", issuedAttemptId)
        .put("attemptSequence", attemptSequence).put("authorityEpoch", authorityEpoch)
}

/** One independent closed-native observation, never a live database handle or writer. */
internal suspend fun readNativeAckState(application: Application): NativeAckState {
    var context: SelfOwnedLocalSecurityContext? = null
    var custody: SelfOwnedNativeHistoryCustody? = null
    try {
        val opened = AndroidSelfOwnedInstallationOwner.openOriginal(application)
        val actual = (opened as? SelfOwnedNativeOpenResult.Opened)?.context
            ?: error("Closed native original observation is unavailable: $opened")
        context = actual
        val claimed = withContext(NonCancellable) {
            context = null
            when (val result = AndroidSelfOwnedInstallationOwner.claimOriginalHistoryCustody(actual)) {
                is SelfOwnedNativeHistoryClaimResult.Claimed -> result.custody.also { custody = it }
                is SelfOwnedNativeHistoryClaimResult.Unresolved -> {
                    if (result.reason == SelfOwnedInstallationFailureV1.BUSY) context = actual
                    error("Original native history custody is unavailable: $result")
                }
            }
        }
        return claimed.runRoomWork(
            block = { nativeAckStateFrom(claimed.snapshotOriginal()) },
            discard = { claimed.denyDataUse() },
        )
    } finally {
        cleanupAosf(
            { custody?.let { actual -> custody = null; actual.close() } },
            { context?.let { actual -> context = null; actual.close() } },
        )
    }
}

private fun nativeAckStateFrom(
    snapshot: com.ukkera.pos.security.OriginalNativeHistorySnapshot,
): NativeAckState {
    val record = snapshot.record
    val coordinates = record.allocation.coordinates
    return NativeAckState(
        recordDigest = snapshot.recordDigest.value,
        generation = record.generation,
        denialGeneration = record.denialGeneration,
        pending = record.pending != null,
        database = record.database,
        allocationDigest = record.allocation.digest.value,
        organizationId = coordinates.scope.organizationId.value,
        companyId = coordinates.scope.companyId.value,
        branchId = coordinates.scope.branchId.value,
        allocationId = coordinates.allocationId.value,
        installationId = coordinates.installationId.value,
        assignmentId = coordinates.assignmentId.value,
        deviceId = coordinates.deviceId.value,
        firstActorId = coordinates.firstActorId.value,
        storageInstanceId = coordinates.storageInstanceId.value,
        storageKeyId = coordinates.storageKeyId.value,
        pepperKeyId = coordinates.pepperKeyId.value,
        targetSigningKeyId = coordinates.targetSigningKeyId.value,
        role = record.roleIntent?.role?.name,
        attemptPhase = record.attempt?.phase?.name,
        definitionDigest = record.definition?.definitionDigest?.value,
        receiptDigest = record.receipt?.digest?.value,
        receiptGenesis = record.receipt?.genesis?.value,
        acknowledgementDigest = record.issuerAcknowledgement?.digest?.value,
        acknowledgementFrameDigest = record.issuerAcknowledgement?.frame?.digest?.value,
        parentHistorySequence = record.parent?.historySequence,
        firstChildHistoryDigest = record.firstChild?.historyDigest?.value,
        originalRequestId = record.receipt?.core?.originalRequestId?.value,
        issuedAttemptId = record.receipt?.core?.issuedAttemptId?.value,
        attemptSequence = record.receipt?.core?.attemptSequence,
        authorityEpoch = record.receipt?.core?.authorityEpoch?.value,
    )
}

/**
 * Actual public owner lifetime for one selected segment. It only acquires/transfers/closes the
 * genuine production owner; it supplies no verifier, history or accepting result. A failed close
 * is retained and never retried against the same or a replacement handle.
 */
internal class NativeAckOwnerHandle(
    private val application: Application,
    private val evidence: NativeLocalFixtureEvidence,
) {
    private var context: SelfOwnedLocalSecurityContext? = null
    private var service: AndroidSelfOwnedMasterService? = null
    private var material: SelfOwnedNativeMaterialView? = null
    private var closeAttempted = false
    private var closeUncertain = false
    private var contextCloseAttempted = false

    val currentService: AndroidSelfOwnedMasterService get() = checkNotNull(service)
    val currentMaterial: SelfOwnedNativeMaterialView get() = checkNotNull(material)
    val coordinates: SelfOwnedAllocationCoordinatesV1 get() = currentMaterial.coordinates

    suspend fun openInstallationOnly(): AndroidSelfOwnedMasterService =
        open { actual -> AndroidSelfOwnedMasterService.forOriginalInstallation(application, actual) }

    suspend fun openOperational(): AndroidSelfOwnedMasterService =
        open { actual -> AndroidLocalBranchRuntime.openOriginalMaster(application, actual) }

    private suspend fun open(openMaster: suspend (SelfOwnedLocalSecurityContext) -> AndroidSelfOwnedMasterOpenResult):
        AndroidSelfOwnedMasterService {
        check(context == null && service == null && material == null && !closeAttempted && !closeUncertain) {
            "An original owner is already acquired or an earlier close is unconfirmed"
        }
        val opened = AndroidSelfOwnedInstallationOwner.openOriginal(application)
        val actual = (opened as? SelfOwnedNativeOpenResult.Opened)?.context
            ?: error("Original native context is unavailable: $opened")
        material = actual.materialAtOpen
        context = actual
        val result = try {
            withContext(NonCancellable) {
                context = null
                openMaster(actual)
            }
        } catch (failure: Throwable) {
            // The production factory owns an undelivered/uncertain claim. Never close or retry it
            // from the test with a second assumption about the actual owner.
            closeUncertain = true
            throw failure
        }
        return when (result) {
            is AndroidSelfOwnedMasterOpenResult.Opened -> result.service.also {
                service = it
                evidence.event("NATIVE_ACK_OWNER_OPENED", JSONObject().put("mode", it.mode.value.name))
            }
            is AndroidSelfOwnedMasterOpenResult.Unresolved -> {
                if (result.reason == SelfOwnedInstallationFailureV1.BUSY) {
                    // A rejected BUSY claim transfers nothing; the caller still owns the context.
                    context = actual
                } else {
                    // Any other failure does not establish whether the context was consumed.
                    // Retain it as unknown rather than issuing a second close/replacement.
                    context = null
                    closeUncertain = true
                }
                error("Original Master did not open: ${result.reason}")
            }
        }
    }

    suspend fun requireNoSecondWriter() {
        check(service != null && !closeAttempted)
        when (val second = AndroidSelfOwnedInstallationOwner.openOriginal(application)) {
            is SelfOwnedNativeOpenResult.Opened -> {
                try { second.context.close() } catch (_: Throwable) { /* Retain the violation below. */ }
                error("A second native writer was admitted while the original owner was active")
            }
            is SelfOwnedNativeOpenResult.Unresolved -> check(
                second.reason == SelfOwnedInstallationFailureV1.BUSY) {
                "A second writer was not distinguishably excluded: ${second.reason}"
            }
        }
    }

    suspend fun closeConfirmed() {
        check(!closeAttempted) { "A consumed close is never retried against a reused handle" }
        check(!closeUncertain)
        val actual = service ?: error("No actual original owner is open")
        closeAttempted = true
        val confirmed = try {
            withContext(NonCancellable) { actual.quiesceAndClose() }
        } catch (failure: Throwable) {
            closeUncertain = true
            throw failure
        }
        if (!confirmed) {
            closeUncertain = true
            error("Actual original owner close is unconfirmed")
        }
        check(actual.mode.value == LocalMasterLifecycleMode.CLOSED) {
            "Confirmed close published an unexpected lifecycle: ${actual.mode.value}"
        }
        service = null
        material = null
        closeAttempted = false
        evidence.event("NATIVE_ACK_OWNER_CONFIRMED_CLOSE", JSONObject()
            .put("closeConfirmedByProductionOwner", true)
            .put("intentionalStopRequested", false)
            .put("independentCleanupSignal", "UNBOUND_NA_U6"))
    }

    /** One cleanup attempt only; an already-unconfirmed close is retained, never retried. */
    suspend fun closeForCleanup() {
        if (service != null) {
            if (!closeAttempted && !closeUncertain) closeConfirmed()
            return
        }
        val actual = context ?: return
        if (contextCloseAttempted) return
        contextCloseAttempted = true
        context = null
        withContext(NonCancellable) { actual.close() }
    }
}
