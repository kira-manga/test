package com.ukkera.pos.sales.store

import com.ukkera.pos.database.LocalMasterPhysicalRpcClientV1
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.PairedDeviceCredentialJournalCustody
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The selection is explicit. Existing-only reopening never creates a missing key or file. */
public enum class DistributedPhysicalNativeAction { ENROLL_ORIGINAL, REOPEN_EXISTING }

/**
 * Retained native acquisition, published before its first directory/key/file operation. The
 * actual platform factories alone construct it. Cancelling await only abandons delivery;
 * the independently owned worker and every returned/uncertain native resource remain here.
 */
public class DistributedPhysicalClientNativeAcquisition internal constructor(
    private val nativeOriginal: PairedDeviceCredentialJournalCustody,
    private val rpc: LocalMasterPhysicalRpcClientV1,
    public val originalActorId: DomainId,
    public val actorIntentJournalId: DomainId,
    public val binding: LocalPhysicalCatalogueBinding,
    private val action: DistributedPhysicalNativeAction,
    private val purpose: DistributedPhysicalClientPurpose,
    private val storageProfile: LocalPhysicalSpoolStorageProfileV1,
    private val qualification: PhysicalJournalExternalCustodyOwnership,
    private val namespace: OriginalPhysicalNativeNamespace,
    openCatalogue: () -> LocalPhysicalCatalogueOpen,
) {
    private val root = SupervisorJob()
    private val scope = CoroutineScope(root + Dispatchers.IO)
    private var catalogue: LocalPhysicalAllocationCatalogue? = null
    private val journals = mutableListOf<LocalPhysicalJournal>()
    private var unreturnedNativeOwner = false
    private var owner: DistributedPhysicalClientJournalOwner? = null
    private val closeGate = Any()
    private var closing: CompletableDeferred<LocalPhysicalSpoolClose>? = null
    private val work: Deferred<DistributedPhysicalClientJournalOwner?> = scope.async(start = CoroutineStart.LAZY) {
        acquireOwned(openCatalogue)
    }

    /** Data coherence only. The actual native owner is acquired and checked separately. */
    private fun originalMatches(): Boolean = rpc.scope == binding.scope && rpc.originalDeviceId == binding.deviceId &&
        rpc.hasOriginal(nativeOriginal.sdkOriginal, nativeOriginal.approvedOriginal)

    internal fun start() { work.start() }

    public suspend fun await(): DistributedPhysicalClientJournalOwner? {
        currentCoroutineContext().ensureActive()
        val result = work.await()
        currentCoroutineContext().ensureActive()
        return result.takeIf { originalMatches() && rpc.isCurrent() && !qualification.lifetimeDenied }
    }

    private fun acquireOwned(openCatalogue: () -> LocalPhysicalCatalogueOpen): DistributedPhysicalClientJournalOwner? {
        try {
            check(originalMatches() && rpc.isCurrent() && !qualification.denied &&
                (purpose == DistributedPhysicalClientPurpose.INTERACTIVE || action == DistributedPhysicalNativeAction.REOPEN_EXISTING))
            namespace.prepare()
            unreturnedNativeOwner = true
            val opened = openCatalogue()
            if (opened !is LocalPhysicalCatalogueOpen.Opened) {
                if (opened === LocalPhysicalCatalogueOpen.Busy) unreturnedNativeOwner = false
                return null
            }
            catalogue = opened.catalogue // Own before any further read, comparison or census.
            unreturnedNativeOwner = false
            val actual = opened.catalogue
            check(actual.binding == binding && actual.storageProfile == storageProfile)
            val ids = actual.retainedAllocationSelectors() ?: error("physical.remote.catalogue")
            check(ids.size <= LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES * LocalPhysicalJournalFormatV1.MAX_CATALOGUE_GENERATIONS &&
                ids.distinct().size == ids.size)
            val master = nativeOriginal.approvedOriginal.masterSdkOriginal.allocation.coordinates
            for (id in ids) {
                namespace.requireCurrent()
                val receipt = actual.readExpectedReceipt(id) ?: error("physical.remote.receipt")
                val requirement = receipt.binding.requirement
                val domain = localPhysicalDomainOwners(requirement)
                val foreign = LocalPhysicalForeignResolutionDomain.fromRequirement(requirement)
                val refs = foreign?.references(requirement)
                check(receipt.binding.owner == binding && domain.client == binding && requirement.scope == binding.scope &&
                    (if (refs != null) refs.resolverActorId == originalActorId && refs.resolverIntentJournalId == actorIntentJournalId
                        else requirement.originalActorId == originalActorId) && requirement.clientDeviceId == binding.deviceId &&
                    requirement.clientStorageProfile == storageProfile && requirement.masterDeviceId == master.deviceId &&
                    domain.authority.deviceId == master.deviceId && domain.authority.assignmentId == master.assignmentId)
                unreturnedNativeOwner = true
                val existing = actual.openExisting(id, domain.domain)
                if (existing !is LocalPhysicalJournalOpen.Opened) {
                    if (existing !== LocalPhysicalJournalOpen.Held) unreturnedNativeOwner = false
                    error("physical.remote.journal")
                }
                journals.add(existing.journal)
                unreturnedNativeOwner = false
                check(existing.journal.receipt.sourceCommitment == receipt.sourceCommitment)
                if (foreign != null) {
                    check(existing.journal.readForeignResolution() is LocalForeignResolutionRead.Read)
                } else {
                    val snapshot = (existing.journal.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot
                        ?: error("physical.remote.original")
                    check(snapshot.receipt.sourceCommitment == receipt.sourceCommitment)
                }
                check(existing.journal.close() == LocalPhysicalSpoolClose.CLOSED)
                check(journals.remove(existing.journal))
            }
            namespace.requireCensus(binding, actual.nativeFileCensus())
            if (action == DistributedPhysicalNativeAction.ENROLL_ORIGINAL) check(ids.isEmpty())
            check(originalMatches() && rpc.isCurrent() && !qualification.denied)
            // Transfer the actual namespace owner too. In particular Windows retains its
            // real protected-DACL directory handles; each actual cipher/native cut rechecks
            // those SAME handles/path identities. No prepared owner is silently discarded.
            val admitted = DistributedPhysicalClientJournalOwner(actual, rpc, originalActorId, actorIntentJournalId,
                namespace, purpose, Dispatchers.IO, Dispatchers.IO)
            owner = admitted
            // A raw recovery child has no separate successful actor intent owner. Keep
            // its actual allocation-journal loan until this native child certainly closes.
            if (purpose == DistributedPhysicalClientPurpose.INTERACTIVE)
                check(qualification.releaseAfterNativeCleanup())
            return admitted
        } catch (failure: Exception) {
            com.ukkera.pos.database.reportLocalMasterRpcReadFailure(
                com.ukkera.pos.database.LocalMasterRpcReadStage.PHYSICAL_CLIENT_NATIVE_ACQUIRE, failure)
            // The same acquisition retains its attempted namespace, real FDs and unknown IO.
            return null
        }
    }

    public suspend fun close(): LocalPhysicalSpoolClose = withContext(NonCancellable) {
        val selected = synchronized(closeGate) {
            closing?.let { return@synchronized it to false }
            CompletableDeferred<LocalPhysicalSpoolClose>().also { closing = it } to true
        }
        if (selected.second) {
            val outcome = try {
                work.start(); work.join()
                val completeOwner = owner
                if (completeOwner != null) {
                    val closed = completeOwner.close()
                    if (closed == LocalPhysicalSpoolClose.CLOSED && qualification.releaseAfterNativeCleanup())
                        LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD
                } else {
                    var complete = !unreturnedNativeOwner
                    for (journal in journals) if (journal.close() != LocalPhysicalSpoolClose.CLOSED) complete = false
                    if (complete && catalogue?.close() == LocalPhysicalSpoolClose.HELD) complete = false
                    if (complete && namespace.close() != LocalPhysicalSpoolClose.CLOSED) complete = false
                    if (complete && !qualification.releaseAfterNativeCleanup()) complete = false
                    if (complete) LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD
                }
            } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
            if (outcome == LocalPhysicalSpoolClose.CLOSED) {
                root.cancel()
                DistributedPhysicalClientNativeAcquisitions.release(binding, this@DistributedPhysicalClientNativeAcquisition)
            }
            selected.first.complete(outcome)
        }
        selected.first.await()
    }

    override fun toString(): String = "DistributedPhysicalClientNativeAcquisition([REDACTED])"
}

/** Existing-only immutable native evidence, with no method exposing an interactive owner.
 * The actual owner's closed purpose independently rejects begin even inside this module. */
public class DistributedPhysicalRawRecoveryAcquisition internal constructor(
    private val acquisition: DistributedPhysicalClientNativeAcquisition,
    private val rpc: LocalMasterPhysicalRpcClientV1,
) {
    public suspend fun redeliver(): Map<DomainId, com.ukkera.pos.database.LocalMasterPhysicalOriginalRetentionV1>? {
        val native = acquisition.await() ?: return null
        native.denyNewWork()
        return native.redeliverOriginals(rpc)
    }
    public suspend fun close(): LocalPhysicalSpoolClose = acquisition.close()
    override fun toString(): String = "DistributedPhysicalRawRecoveryAcquisition([REDACTED])"
}

/** One process owner per actual fixed original namespace, including partial/unknown acquisition. */
internal object DistributedPhysicalClientNativeAcquisitions {
    private val attempts = ConcurrentHashMap<LocalPhysicalCatalogueBinding, DistributedPhysicalClientNativeAcquisition>()
    private val admission = Any()

    fun release(binding: LocalPhysicalCatalogueBinding, owner: DistributedPhysicalClientNativeAcquisition) {
        attempts.remove(binding, owner)
    }

    fun retain(binding: LocalPhysicalCatalogueBinding,
        prepare: () -> DistributedPhysicalClientNativeAcquisition): DistributedPhysicalClientNativeAcquisition? = synchronized(admission) {
        // Do not adopt a previous failed/unknown attempt or silently select a new disposition.
        if (attempts.containsKey(binding) || attempts.size >= 128) return@synchronized null
        prepare().also { attempts[binding] = it; it.start() }
    }
}

internal fun distributedPhysicalId(originalCommitment: String, actor: DomainId, journal: DomainId,
    domain: String, role: String): DomainId {
    val input = listOf("ukkera.distributed-physical-native-id/v1", originalCommitment, actor.value, journal.value, domain, role)
        .joinToString("\n").encodeToByteArray(throwOnInvalidSequence = true)
    val digest = try { sha256(input).value } finally { input.fill(0) }
    return DomainId("${digest.take(8)}-${digest.substring(8, 12)}-${digest.substring(12, 16)}-${digest.substring(16, 20)}-${digest.substring(20, 32)}")
}

/** Genuine Master-role catalogue custody; no remote Client-role FD is created or substituted. */
public class DistributedPhysicalMasterNativeAcquisition internal constructor(
    private val original: AndroidOriginalLocalClient,
    private val registeredOwner: LocalPhysicalJournalExecutionOwner,
    public val binding: LocalPhysicalCatalogueBinding,
    private val storageProfile: LocalPhysicalSpoolStorageProfileV1,
    private val enroll: Boolean,
    private val namespace: OriginalPhysicalNativeNamespace,
    openCatalogue: () -> LocalPhysicalCatalogueOpen,
) {
    private val nativeRoot = SupervisorJob()
    private val nativeScope = CoroutineScope(nativeRoot + Dispatchers.IO)
    private var catalogue: LocalPhysicalAllocationCatalogue? = null
    private val journals = mutableListOf<LocalPhysicalJournal>()
    private var unknownOpen = false
    private var owner: DistributedPhysicalMasterJournalOwner? = null
    private val closeGate = Any()
    private var closing: CompletableDeferred<LocalPhysicalSpoolClose>? = null
    private val work = nativeScope.async(start = CoroutineStart.LAZY) {
        try {
            check(original.physicalEndpoint(registeredOwner) != null && (!enroll || original.permitsFirstEnrollment()))
            namespace.prepare()
            unknownOpen = true
            val opened = openCatalogue()
            if (opened !is LocalPhysicalCatalogueOpen.Opened) {
                if (opened === LocalPhysicalCatalogueOpen.Busy) unknownOpen = false
                return@async null
            }
            catalogue = opened.catalogue
            unknownOpen = false
            val actual = opened.catalogue
            check(actual.binding == binding && actual.storageProfile == storageProfile)
            val ids = actual.retainedAllocationSelectors() ?: error("physical.remote.master-census")
            check(ids.size <= LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES * LocalPhysicalJournalFormatV1.MAX_CATALOGUE_GENERATIONS &&
                ids.distinct().size == ids.size)
            val authority = AuthorityIdentity(original.material.coordinates.assignmentId, binding.deviceId,
                original.receipt.core.authorityEpoch)
            for (id in ids) {
                namespace.requireCurrent()
                val receipt = actual.readExpectedReceipt(id) ?: error("physical.remote.master-receipt")
                val requirement = receipt.binding.requirement
                val domain = localPhysicalDomainOwners(requirement)
                check(receipt.binding.owner == binding && domain.master == binding && domain.authority == authority &&
                    requirement.scope == binding.scope && requirement.masterDeviceId == binding.deviceId &&
                    requirement.clientDeviceId != binding.deviceId && requirement.masterStorageProfile == storageProfile)
                unknownOpen = true
                val retained = actual.openExisting(id, domain.domain)
                if (retained !is LocalPhysicalJournalOpen.Opened) {
                    if (retained !== LocalPhysicalJournalOpen.Held) unknownOpen = false
                    error("physical.remote.master-journal")
                }
                journals.add(retained.journal)
                unknownOpen = false
                val snapshot = (retained.journal.readOriginal() as? LocalPhysicalOriginalRead.Read)?.snapshot
                    ?: error("physical.remote.master-origin")
                check(snapshot.receipt.sourceCommitment == receipt.sourceCommitment &&
                    retained.journal.receipt.sourceCommitment == receipt.sourceCommitment)
                check(retained.journal.close() == LocalPhysicalSpoolClose.CLOSED)
                check(journals.remove(retained.journal))
            }
            namespace.requireCensus(binding, actual.nativeFileCensus())
            if (enroll) check(ids.isEmpty())
            check(original.physicalEndpoint(registeredOwner) != null && (!enroll || original.permitsFirstEnrollment()))
            DistributedPhysicalMasterJournalOwner(registeredOwner, actual, original.material.coordinates.assignmentId,
                namespace, Dispatchers.IO).also { owner = it }
        } catch (_: Exception) { null }
    }
    internal fun start() { work.start() }
    public suspend fun await(): DistributedPhysicalMasterJournalOwner? {
        currentCoroutineContext().ensureActive()
        return work.await().takeIf { original.physicalEndpoint(registeredOwner) != null }
    }
    public suspend fun close(): LocalPhysicalSpoolClose = withContext(NonCancellable) {
        val selected = synchronized(closeGate) {
            closing?.let { return@synchronized it to false }
            CompletableDeferred<LocalPhysicalSpoolClose>().also { closing = it } to true
        }
        if (selected.second) {
            val result = try {
                work.start(); work.join()
                owner?.close() ?: run {
                    var complete = !unknownOpen
                    for (journal in journals) if (journal.close() != LocalPhysicalSpoolClose.CLOSED) complete = false
                    if (complete && catalogue?.close() == LocalPhysicalSpoolClose.HELD) complete = false
                    if (complete && namespace.close() != LocalPhysicalSpoolClose.CLOSED) complete = false
                    if (complete) LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD
                }
            } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
            if (result == LocalPhysicalSpoolClose.CLOSED) {
                nativeRoot.cancel()
                DistributedPhysicalMasterNativeAcquisitions.release(binding, this@DistributedPhysicalMasterNativeAcquisition)
            }
            selected.first.complete(result)
        }
        selected.first.await()
    }
    override fun toString(): String = "DistributedPhysicalMasterNativeAcquisition([REDACTED])"
}

internal object DistributedPhysicalMasterNativeAcquisitions {
    private val held = ConcurrentHashMap<LocalPhysicalCatalogueBinding, DistributedPhysicalMasterNativeAcquisition>()
    private val admission = Any()
    fun release(binding: LocalPhysicalCatalogueBinding, actual: DistributedPhysicalMasterNativeAcquisition) { held.remove(binding, actual) }
    fun retain(binding: LocalPhysicalCatalogueBinding, prepare: () -> DistributedPhysicalMasterNativeAcquisition):
        DistributedPhysicalMasterNativeAcquisition? = synchronized(admission) {
        if (held.containsKey(binding) || held.size >= 128) return@synchronized null
        prepare().also { held[binding] = it; it.start() }
    }
}
