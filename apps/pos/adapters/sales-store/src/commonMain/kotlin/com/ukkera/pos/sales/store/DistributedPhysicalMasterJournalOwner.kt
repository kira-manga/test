package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.LocalMasterPhysicalNativeStageV1
import com.ukkera.pos.database.LocalMasterPhysicalOriginalDevice
import com.ukkera.pos.database.LocalMasterPhysicalOriginalRequestV1
import com.ukkera.pos.database.LocalMasterPhysicalOriginalRetentionV1
import com.ukkera.pos.database.LocalMasterPhysicalRemoteInvocation
import com.ukkera.pos.database.LocalMasterPhysicalRpcBodyV1
import com.ukkera.pos.database.NativeOriginalAcquire
import com.ukkera.pos.database.NativeOriginalIntake
import com.ukkera.pos.database.NativeOriginalIntakeDisposition
import com.ukkera.pos.database.NativeOriginalIntakeInvocation
import com.ukkera.pos.database.NativeOriginalIntakeLocator
import com.ukkera.pos.database.NativeOriginalIntakeSubmission
import com.ukkera.pos.database.PhysicalCommittedStartProjectionV1
import com.ukkera.pos.database.PhysicalRecordingServiceLifetime
import com.ukkera.pos.database.PhysicalRecordingSourceProfileV1
import com.ukkera.pos.security.sha256
import kotlin.reflect.KProperty
import com.ukkera.pos.database.NativeOriginalRetained
import com.ukkera.pos.database.NativeOriginalRetainedLease
import com.ukkera.pos.database.PhysicalInstructionNativeBinding
import com.ukkera.pos.database.PhysicalInteractiveRequestLifetime
import com.ukkera.pos.database.PhysicalNativeInstructionLease
import com.ukkera.pos.database.PhysicalNativeInstructionPreparation
import com.ukkera.pos.database.PhysicalPublishedInstruction
import com.ukkera.pos.database.PhysicalRecordingAction
import com.ukkera.pos.database.PhysicalRecordingAcquire
import com.ukkera.pos.database.PhysicalRecordingCommittedStart
import com.ukkera.pos.database.PhysicalRecordingInvocationBinding
import com.ukkera.pos.database.PhysicalRecordingNativeCheck
import com.ukkera.pos.database.PhysicalRecordingNativeLease
import com.ukkera.pos.database.PhysicalRecordingNativeRelease
import com.ukkera.pos.database.PhysicalRecordingParticipants
import com.ukkera.pos.database.PhysicalRemoteInvocationLease
import com.ukkera.pos.database.PhysicalRemoteNativeStage
import com.ukkera.pos.database.PhysicalRemoteRetirement
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.types.DomainId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Native backend of the SAME registered upper gate. It owns genuine Master-role native files
 * only. Remote stage results are authenticated exchange DATA emitted by the concrete Client
 * owner, never local custody of the remote FD or an independently verifiable OS-force proof.
 * No endpoint, Start, instruction or employee permission is created by this backend.
 */
public class DistributedPhysicalMasterJournalOwner internal constructor(
    private val registeredOwner: LocalPhysicalJournalExecutionOwner,
    private val catalogue: LocalPhysicalAllocationCatalogue,
    private val originalAssignmentId: DomainId,
    private val nativeCustody: PhysicalJournalNativePreparation,
    nativeDispatcher: CoroutineDispatcher,
) {
    private class Stage(val value: PhysicalRemoteNativeStage) {
        val answer = CompletableDeferred<StoredBytes>()
        var received: StoredBytes? = null
    }
    private class Entry(val remote: PhysicalRemoteInvocationLease,
        val client: LocalPhysicalCatalogueBinding, val clientProfile: LocalPhysicalSpoolStorageProfileV1) {
        val denied = PhysicalJournalDenial()
        val stages = MutableStateFlow<Stage?>(null)
        val stageGate = Mutex()
        val stageHistory = mutableListOf<Stage>() // Bounded exact ACK replay, no repeated native work.
        var ordinal = 0L
        var serviceLifetime: PhysicalRecordingServiceLifetime? by PhysicalRemoteCell(null)
        var requestBinding: PhysicalRecordingInvocationBinding? by PhysicalRemoteCell(null)
        var lease: PhysicalRecordingNativeLease? by PhysicalRemoteCell(null)
        var journal: LocalPhysicalJournal? by PhysicalRemoteCell(null)
        var clientSource: AggregateChange? by PhysicalRemoteCell(null)
        // A's actual Master journal and B's own retention requirement stay distinct.
        var foreignRequirement: LocalPhysicalJournalRequirementV1? by PhysicalRemoteCell(null)
        var temporary: PhysicalJournalLeaseOwnership? by PhysicalRemoteCell(null)
        var first: LocalPhysicalStartWrite.Fresh? by PhysicalRemoteCell(null)
        var start: PhysicalRecordingCommittedStart? by PhysicalRemoteCell(null)
        var nativePublication: PhysicalNativeInstructionLease? by PhysicalRemoteCell(null)
        var publicationTemporary: PhysicalJournalLeaseOwnership? by PhysicalRemoteCell(null)
        var evidence: PhysicalJournalLeaseOwnership? by PhysicalRemoteCell(null)
        var publication: PhysicalPublishedInstruction? by PhysicalRemoteCell(null)
        var raw: StoredBytes? by PhysicalRemoteCell(null)
        var rawCandidate: StoredBytes? by PhysicalRemoteCell(null)
        var unreturnedNativeOwner = false
        var uncertain = false
        val jobs = mutableListOf<Deferred<*>>()
        fun deny() {
            val first = !denied.denied
            denied.deny(); requestBinding?.latch?.deny(); nativePublication?.binding?.denial?.deny(); publication?.deny()
            if (first) try { com.ukkera.pos.database.reportLocalMasterRpcReadFailure(
                com.ukkera.pos.database.LocalMasterRpcReadStage.PHYSICAL_CLIENT_NATIVE_ACQUIRE, IllegalStateException()) }
            catch (_: Throwable) { }
        }
    }
    private class Intake(val lease: NativeOriginalRetainedLease, val journal: LocalPhysicalJournal,
        val ownership: PhysicalJournalLeaseOwnership) {
        var uncertain = false
    }
    private class IntakeAttempt(val invocation: NativeOriginalIntakeInvocation) {
        val delivery = MutableStateFlow(0) // WAITING / DELIVERED / ABANDONED
        var retained: Intake? by PhysicalRemoteCell(null)
    }
    private val nativeRoot = SupervisorJob()
    private val nativeScope = CoroutineScope(nativeRoot + nativeDispatcher)
    private val gate = Mutex()
    private val stageAdmission = Mutex()
    private val entries = MutableStateFlow<List<Entry>>(emptyList())
    private val intakes = MutableStateFlow<List<Intake>>(emptyList())
    private val journals = mutableMapOf<DomainId, LocalPhysicalJournal>()
    private val serviceJobs = mutableListOf<Deferred<*>>()
    private var intakeAfter: DomainId? = null
    private val unknownOpens = mutableSetOf<DomainId>()
    private val pendingOriginals = mutableMapOf<DomainId, StoredBytes>()
    private val provisionalIntakeCustody = mutableListOf<PhysicalJournalLeaseOwnership>()
    private val denial = PhysicalJournalDenial()
    private val intake = MutableStateFlow<NativeOriginalIntake?>(null)
    private val closing = MutableStateFlow<CompletableDeferred<LocalPhysicalSpoolClose>?>(null)

    public val binding: LocalPhysicalCatalogueBinding get() = catalogue.binding
    public val storageProfile: LocalPhysicalSpoolStorageProfileV1 get() = catalogue.storageProfile

    init { require(binding.role == LocalPhysicalSpoolRole.MASTER) }

    /** Exact retained object affinity; cleanup selection deliberately survives denial. */
    public fun owns(binding: PhysicalRecordingInvocationBinding): Boolean = entries.value.any {
        it.remote.invocation.owns(registeredOwner, binding) }
    public fun owns(lease: PhysicalRecordingNativeLease): Boolean = entries.value.any { it.lease === lease }
    public fun owns(binding: PhysicalInstructionNativeBinding): Boolean = entries.value.any {
        it.start === binding.committedStart && it.remote.invocation.owns(registeredOwner, binding) }
    public fun owns(lease: PhysicalNativeInstructionLease): Boolean = entries.value.any { it.nativePublication === lease }
    public fun owns(lease: NativeOriginalRetainedLease): Boolean = intakes.value.any { it.lease === lease }

    /** Existing authenticated selector only. Null is uncertainty, never permission to fall back. */
    public suspend fun selectsOriginal(invocation: NativeOriginalIntakeInvocation): Boolean? {
        if (!invocation.belongsTo(registeredOwner) || denial.denied) return null
        val work = serviceOwned {
            gate.withLock {
                val ids = catalogue.retainedAllocationSelectors() ?: return@withLock null
                var selected = false
                for (id in ids) {
                    val receipt = catalogue.readExpectedReceipt(id) ?: return@withLock null
                    if (!validMasterReceipt(receipt)) return@withLock null
                    if (receipt.binding.requirement.clientAllocationId == invocation.locator.clientAllocationId) {
                        if (selected) return@withLock null
                        selected = true
                    }
                }
                selected
            }
        } ?: return null
        return withTimeoutOrNull(750) { work.await() }
    }

    /** Exact opaque original is retained before its metadata can participate in preflight. */
    public suspend fun retainRemoteInvocation(invocation: LocalMasterPhysicalRemoteInvocation): PhysicalRemoteInvocationLease? {
        currentCoroutineContext().ensureActive()
        if (denial.denied || !invocation.current() || invocation.scope != binding.scope ||
            invocation.clientDeviceId == binding.deviceId) return null
        val data = invocation.clientNative
        val profile = setOf(LocalPhysicalSpoolStorageProfileV1.ANDROID_API30_APP_PRIVATE_FALLOCATE_V1,
            LocalPhysicalSpoolStorageProfileV1.JVM_POSIX_FULL_WRITE_FORCE_V1,
            LocalPhysicalSpoolStorageProfileV1.JVM_WINDOWS_NTFS_FULL_WRITE_FLUSH_V1).singleOrNull {
                it.name == data.storageProfile } ?: return null
        val client = LocalPhysicalCatalogueBinding(invocation.scope, data.installationId, invocation.clientDeviceId,
            data.catalogueId, LocalPhysicalSpoolRole.CLIENT, data.keyId)
        return stageAdmission.withLock {
            if (denial.denied || !invocation.stateCurrent() || entries.value.size >= 128) return@withLock null
            entries.value.firstOrNull { it.remote.invocation === invocation }?.let { return@withLock it.remote }
            if (entries.value.any { it.remote.invocation.request.operationId == invocation.request.operationId ||
                    (!it.denied.denied && it.remote.invocation.originalActorId == invocation.originalActorId &&
                        it.remote.invocation.clientDeviceId == invocation.clientDeviceId) }) return@withLock null
            val remote = PhysicalRemoteInvocationLease(invocation, DomainId.random())
            entries.value = entries.value + Entry(remote, client, profile)
            remote
        }
    }

    /** Pure captured memory only: this callback can run inside the authoritative preflight loan. */
    public fun current(view: CommandReadView, participants: PhysicalRecordingParticipants): LocalRetailJournalParticipants? {
        if (denial.denied || view.scope != binding.scope || participants.authority.assignmentId != originalAssignmentId ||
            participants.authority.deviceId != binding.deviceId) return null
        val entry = entries.value.singleOrNull { selected -> selected.remote.invocation.let {
            !selected.denied.denied && it.stateCurrent() && it.scope == view.scope &&
                it.originalActorId == participants.originalActorId && it.clientDeviceId == participants.clientDeviceId
        } } ?: return null
        return LocalRetailJournalParticipants(binding, entry.client, storageProfile, entry.clientProfile)
    }

    public suspend fun remoteStage(lease: PhysicalRemoteInvocationLease): PhysicalRemoteNativeStage? {
        val entry = exact(lease) ?: return null
        if (denial.denied || !lease.invocation.current()) return null
        return entry.stages.value?.value ?: withTimeoutOrNull(100) {
            entry.stages.first { it != null }?.value
        }
    }

    public suspend fun acceptRemoteStage(lease: PhysicalRemoteInvocationLease,
        result: LocalMasterPhysicalRpcBodyV1.NativeResult): Boolean {
        val entry = exact(lease) ?: return false
        if (denial.denied || !lease.invocation.current()) return false
        return entry.stageGate.withLock {
            val stage = entry.stageHistory.singleOrNull { it.value.ordinal == result.ordinal }
                ?: return@withLock false
            if (result.invocationId != lease.invocation.id || result.ordinal != stage.value.ordinal ||
                result.stage != stage.value.stage) return@withLock false
            stage.received?.let { if (it != result.result) entry.deny(); return@withLock it == result.result }
            stage.received = StoredBytes(result.result.copyBytes())
            stage.answer.complete(checkNotNull(stage.received))
            true
        }
    }

    private suspend fun ask(entry: Entry, stage: LocalMasterPhysicalNativeStageV1,
        payload: StoredBytes): StoredBytes? {
        var observedDenial = false
        fun observedStop(): Boolean {
            if (!observedDenial) {
                observedDenial = true
                try { com.ukkera.pos.database.reportLocalMasterRpcReadFailure(
                    com.ukkera.pos.database.LocalMasterRpcReadStage.PHYSICAL_CLIENT_NATIVE_ACQUIRE, IllegalStateException()) }
                catch (_: Throwable) { }
            }
            return true
        }
        fun stopped(): Boolean {
            if (denial.denied) return observedStop()
            if (entry.denied.denied) return observedStop()
            if (!entry.remote.invocation.current()) return observedStop()
            return false
        }
        if (stopped()) return null
        val pending = entry.stageGate.withLock {
            if (entry.stages.value?.answer?.isCompleted == false || entry.ordinal >= 32) return@withLock null
            Stage(PhysicalRemoteNativeStage(++entry.ordinal, stage, payload)).also { entry.stageHistory.add(it); entry.stages.value = it }
        } ?: run { observedStop(); return null }
        // Preparation precedes the protected publication window. Wait only for this
        // exact owned stage or actual denial; no polling/reply interval proves a force
        // failure. Native IO and the protected endpoint keep their own original bounds.
        val answer = awaitPhysicalRemoteStageSettlement(pending.answer) {
            stopped()
        }
        if (answer == null) entry.deny()
        return answer
    }

    public suspend fun acquire(request: PhysicalRecordingInvocationBinding): PhysicalRecordingAcquire {
        val entry = entries.value.singleOrNull { it.remote.invocation.owns(registeredOwner, request) }
            ?: run { request.latch.deny(); return PhysicalRecordingAcquire.Held }
        val work = owned(entry) {
            fun held(): PhysicalRecordingAcquire {
                com.ukkera.pos.database.reportLocalMasterRpcReadFailure(
                    com.ukkera.pos.database.LocalMasterRpcReadStage.PHYSICAL_CLIENT_NATIVE_ACQUIRE, IllegalStateException())
                return PhysicalRecordingAcquire.Held
            }
            val requirement = LocalPhysicalJournalRequirementV1.decode(request.admission.requirementBytes)
            val domain = localPhysicalDomainOwners(requirement)
            val resolution = request.admission.action == PhysicalRecordingAction.RETAIN_RESOLUTION
            val foreign = resolution && domain.client != entry.client
            check(domain.master == binding && domain.authority == request.authority &&
                (if (foreign) requirement.clientDeviceId != entry.remote.invocation.clientDeviceId &&
                    LocalPhysicalForeignResolutionDomain.fromRequirement(requirement) == null
                else domain.client == entry.client) &&
                (if (resolution) distributedResolutionActor(entry.remote.invocation.request) == request.originalActorId
                    else requirement.originalActorId == entry.remote.invocation.originalActorId) &&
                (if (resolution) requirement.resolutionOperationId else requirement.intentOperationId) == request.operationId && (foreign || requirement.clientStorageProfile == entry.clientProfile) &&
                requirement.masterStorageProfile == storageProfile)
            val master = gate.withLock {
                check(entry.requestBinding == null && !entry.unreturnedNativeOwner)
                entry.requestBinding = request
                entry.unreturnedNativeOwner = true
                val opened = if (resolution) {
                    val existing = originalJournal(requirement.clientAllocationId)
                    if (existing == null || existing.receipt.binding.requirement.canonicalBytes != requirement.canonicalBytes ||
                        entries.value.any { it !== entry && it.journal === existing }) {
                        com.ukkera.pos.database.reportLocalMasterRpcReadFailure(
                            com.ukkera.pos.database.LocalMasterRpcReadStage.PHYSICAL_CLIENT_NATIVE_ACQUIRE, IllegalStateException())
                        return@withLock null
                    }
                    LocalPhysicalJournalOpen.Opened(existing)
                } else catalogue.allocate(requirement, domain.domain)
                if (opened !is LocalPhysicalJournalOpen.Opened) {
                    com.ukkera.pos.database.reportLocalMasterRpcReadFailure(
                        com.ukkera.pos.database.LocalMasterRpcReadStage.PHYSICAL_CLIENT_NATIVE_ACQUIRE, IllegalStateException())
                    if (opened !== LocalPhysicalJournalOpen.Held) entry.unreturnedNativeOwner = false
                    return@withLock null
                }
                entry.journal = opened.journal
                journals[requirement.masterAllocationId] = opened.journal
                entry.unreturnedNativeOwner = false
                val captured = (if (resolution) captureResolutionJournal(opened.journal) { entry.deny() }
                    else captureSingleJournal(opened.journal, true) { entry.deny() }) ?: return@withLock null
                entry.temporary = PhysicalJournalLeaseOwnership(captured)
                opened.journal
            } ?: return@owned held()
            val clientRequirement = if (foreign) {
                // Only the actual existing Master journal can supply this reference.
                // No decoded Client catalogue/source assertion qualifies A's UNKNOWN.
                val original = gate.withLock { snapshot(master) } ?: return@owned held()
                val body = original.original ?: return@owned held()
                check(original.actionMayHaveStarted && original.certificate != null &&
                    domain.domain.classifyOriginal(requirement, body) == LocalPhysicalOriginalKind.UNKNOWN)
                localPhysicalResolutionDescriptor(domain.domain, requirement, body, request.originalActorId, request.normalized)
                foreignResolutionRequirement(requirement, body, domain, entry.client,
                    entry.remote.invocation.clientNative.actorIntentJournalId, request.originalActorId,
                    entry.clientProfile, entry.remote.invocation.request).also { entry.foreignRequirement = it }
            } else requirement
            val clientBytes = ask(entry, LocalMasterPhysicalNativeStageV1.RESERVE_CLIENT, clientRequirement.canonicalBytes)
                ?: return@owned held()
            val clientSource = AggregateChange(AggregateKey(LocalPhysicalJournalFormatV1.SOURCE_KIND,
                clientRequirement.clientAllocationId), null, LocalPhysicalJournalFormatV1.SOURCE_SCHEMA, clientBytes)
            val projected = LocalPhysicalJournalSourceProjection.decode(clientSource)
            check(projected.owner == entry.client && projected.requirement.canonicalBytes == clientRequirement.canonicalBytes &&
                projected.nativeIdentity.profile == entry.clientProfile)
            if (resolution) {
                // Background immutable-original delivery/intake owns this same native
                // file. Serialize the snapshot like every other Master journal read;
                // transient file Busy must not be caused by our own concurrent owner IO.
                val snapshot = gate.withLock { snapshot(master) } ?: return@owned held()
                val original = snapshot.original ?: return@owned held()
                check(snapshot.actionMayHaveStarted && snapshot.certificate != null &&
                    domain.domain.classifyOriginal(requirement, original) == LocalPhysicalOriginalKind.UNKNOWN)
                val descriptor = localPhysicalResolutionDescriptor(domain.domain, requirement, original, request.originalActorId, request.normalized)
                // This original was already durably consumed/retained before resolution.
                // Preserve its exact custody even if the following Client ACK is lost.
                gate.withLock { entry.raw = original }
                val retained = ask(entry, LocalMasterPhysicalNativeStageV1.RETAIN_RESOLUTION, request.normalized)
                    ?: return@owned held()
                val expected = if (foreign) foreignResolutionAcknowledgment(clientRequirement, request.normalized)
                    else distributedResolutionAcknowledgment(requirement.clientAllocationId, requirement.canonicalBytes, original, request.normalized)
                check(retained == expected)
                gate.withLock {
                    if (entry.denied.denied || request.latch.denied || !master.retainResolution(requirement.resolutionOperationId,
                            request.originalActorId, descriptor, request.normalized).let {
                                it == LocalPhysicalJournalWrite.RETAINED || it == LocalPhysicalJournalWrite.ALREADY_RETAINED }) return@withLock false
                    val linked = (master.readResolution() as? LocalPhysicalResolutionRead.Read)?.resolution ?: return@withLock false
                    check(linked.body == request.normalized && linked.resolverActorId == request.originalActorId &&
                        linked.originalDigest == journalBodyDigest(original) && linked.resolverAuthorization == descriptor)
                    true
                }.also { if (!it) return@owned held() }
            }
            gate.withLock {
                entry.clientSource = clientSource
                if (entry.denied.denied || request.latch.denied) return@withLock PhysicalRecordingAcquire.Held
                val lease = PhysicalRecordingNativeLease(request, DomainId.random(), if (resolution) emptyList() else listOf(master.receipt.sourceChange, clientSource))
                entry.lease = lease
                PhysicalRecordingAcquire.Ready(lease)
            }
        }
        return waitOwned(entry, work)
    }

    public suspend fun recheck(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeCheck {
        val entry = entries.value.singleOrNull { it.lease === lease && it.requestBinding === lease.binding }
            ?: return PhysicalRecordingNativeCheck.HELD
        val work = owned(entry) {
            if (entry.denied.denied || !entry.remote.invocation.owns(registeredOwner, lease.binding)) return@owned PhysicalRecordingNativeCheck.HELD
            val master = entry.journal ?: return@owned PhysicalRecordingNativeCheck.HELD
            val resolution = lease.binding.admission.action == PhysicalRecordingAction.RETAIN_RESOLUTION
            val checked = gate.withLock {
                if (resolution && (master.readResolution() as? LocalPhysicalResolutionRead.Read)?.resolution?.body != lease.binding.normalized)
                    return@withLock false
                val captured = (if (resolution) captureResolutionJournal(master) { entry.deny() }
                    else captureSingleJournal(master, true) { entry.deny() }) ?: return@withLock false
                val owned = PhysicalJournalLeaseOwnership(captured)
                owned.release(); owned.released
            }
            if (!checked) return@owned PhysicalRecordingNativeCheck.HELD
            val client = ask(entry, LocalMasterPhysicalNativeStageV1.RECHECK_CLIENT,
                (entry.foreignRequirement ?: master.receipt.binding.requirement).canonicalBytes) ?: return@owned PhysicalRecordingNativeCheck.HELD
            if (client != entry.clientSource?.payload) { entry.deny(); return@owned PhysicalRecordingNativeCheck.HELD }
            PhysicalRecordingNativeCheck.VERIFIED
        }
        return waitOwned(entry, work)
    }

    public suspend fun persistStart(lease: PhysicalRecordingNativeLease,
        start: PhysicalRecordingCommittedStart): PhysicalRecordingNativeCheck {
        val entry = entries.value.singleOrNull { it.lease === lease && it.requestBinding === lease.binding }
            ?: return PhysicalRecordingNativeCheck.HELD
        val work = owned(entry) {
            val master = entry.journal ?: return@owned PhysicalRecordingNativeCheck.HELD
            val first = gate.withLock {
                // Genuine committed bytes are preserved even if the employee/network withdrew.
                entry.start = start
                val result = master.persistTrueStart(start)
                (result as? LocalPhysicalStartWrite.Fresh)?.also { entry.first = it }
            } ?: return@owned PhysicalRecordingNativeCheck.HELD
            val retained = ask(entry, LocalMasterPhysicalNativeStageV1.RETAIN_START, start.projection)
                ?: return@owned PhysicalRecordingNativeCheck.HELD
            val client = entry.clientSource ?: return@owned PhysicalRecordingNativeCheck.HELD
            if (retained != distributedCertificateAcknowledgment(client.key.id, client.payload, first.certificate)) {
                entry.deny(); return@owned PhysicalRecordingNativeCheck.HELD
            }
            entry.serviceLifetime = start.captureServiceLifetime()
            if (entry.denied.denied || entry.serviceLifetime?.denied != false) PhysicalRecordingNativeCheck.HELD
            else PhysicalRecordingNativeCheck.VERIFIED
        }
        return waitOwned(entry, work)
    }

    public suspend fun release(lease: PhysicalRecordingNativeLease): PhysicalRecordingNativeRelease = withContext(NonCancellable) {
        val entry = entries.value.singleOrNull { it.lease === lease } ?: return@withContext PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
        joinAccepted(entry)
        gate.withLock {
            entry.temporary?.release()
            if (entry.temporary?.released == true) PhysicalRecordingNativeRelease.RELEASED
            else { entry.uncertain = true; PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN }
        }
    }

    public suspend fun prepareInstruction(request: PhysicalInstructionNativeBinding): PhysicalNativeInstructionPreparation {
        val entry = entries.value.singleOrNull { it.start === request.committedStart && it.remote.invocation.owns(registeredOwner, request) }
            ?: return PhysicalNativeInstructionPreparation.Held
        val work = owned(entry) {
            val master = entry.journal ?: return@owned PhysicalNativeInstructionPreparation.Held
            val first = entry.first ?: return@owned PhysicalNativeInstructionPreparation.Held
            val prepared = gate.withLock {
                if (entry.nativePublication != null || entry.temporary?.released != true || entry.denied.denied ||
                    entry.serviceLifetime?.denied != false ||
                    master.consumeFresh(first) != LocalPhysicalJournalWrite.RETAINED) return@withLock null
                val temporary = captureSingleJournal(master, false) { entry.deny(); request.denial.deny() } ?: return@withLock null
                entry.publicationTemporary = PhysicalJournalLeaseOwnership(temporary)
                val evidence = captureSingleJournal(master, false) { entry.deny(); request.denial.deny() } ?: return@withLock null
                entry.evidence = PhysicalJournalLeaseOwnership(evidence)
                PhysicalNativeInstructionLease(request, DomainId.random(), master.receipt.binding.requirement.canonicalBytes)
                    .also { entry.nativePublication = it }
            } ?: return@owned PhysicalNativeInstructionPreparation.Held
            val consumed = ask(entry, LocalMasterPhysicalNativeStageV1.CONSUME_START, first.certificate)
                ?: return@owned PhysicalNativeInstructionPreparation.Held
            val client = entry.clientSource ?: return@owned PhysicalNativeInstructionPreparation.Held
            if (consumed != distributedCertificateAcknowledgment(client.key.id, client.payload, first.certificate) ||
                entry.denied.denied || request.denial.denied || !master.takeInstruction(first)) {
                entry.deny(); return@owned PhysicalNativeInstructionPreparation.Held
            }
            PhysicalNativeInstructionPreparation.Prepared(prepared)
        }
        return waitOwned(entry, work)
    }

    public suspend fun releaseInstruction(prepared: PhysicalNativeInstructionLease): PhysicalRecordingNativeRelease = withContext(NonCancellable) {
        val entry = entries.value.singleOrNull { it.nativePublication === prepared }
            ?: return@withContext PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
        joinAccepted(entry)
        gate.withLock {
            entry.publicationTemporary?.release()
            if (entry.publicationTemporary?.released == true) PhysicalRecordingNativeRelease.RELEASED
            else { entry.uncertain = true; entry.deny(); PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN }
        }
    }

    public suspend fun adoptRemotePublication(lease: PhysicalRemoteInvocationLease,
        publication: PhysicalPublishedInstruction, original: PhysicalInteractiveRequestLifetime): Boolean {
        val entry = exact(lease) ?: return false
        return entry.stageGate.withLock {
            val prepared = entry.nativePublication ?: return@withLock false
            if (entry.denied.denied || entry.publication != null || entry.publicationTemporary?.released != true ||
                entry.evidence?.released != false || entry.ordinal >= 32 || !publication.matchesNativePreparation(prepared.binding)) return@withLock false
            // This is the original Master SAME-object adoption, never a decoded offer.
            val projection = publication.adoptTo(original) ?: return@withLock false
            if (projection.canonicalRequirement != prepared.canonicalRequirement) { publication.deny(); return@withLock false }
            val validity = publication.remoteOriginalValidityMillis() ?: run { publication.deny(); return@withLock false }
            entry.publication = publication
            entry.stages.value = Stage(PhysicalRemoteNativeStage(++entry.ordinal,
                LocalMasterPhysicalNativeStageV1.PUBLICATION, projection.canonicalRequirement, validity)).also(entry.stageHistory::add)
            true
        }
    }

    public suspend fun retireRemoteInvocation(lease: PhysicalRemoteInvocationLease): PhysicalRemoteRetirement {
        val entry = exact(lease) ?: return PhysicalRemoteRetirement.HELD
        fun held(): PhysicalRemoteRetirement {
            com.ukkera.pos.database.reportLocalMasterRpcReadFailure(
                com.ukkera.pos.database.LocalMasterRpcReadStage.PHYSICAL_CLIENT_NATIVE_ACQUIRE, IllegalStateException())
            return PhysicalRemoteRetirement.HELD
        }
        return gate.withLock {
            if (entry.uncertain) return@withLock held()
            if (entry.unreturnedNativeOwner) return@withLock held()
            if (entry.jobs.any { !it.isCompleted }) return@withLock held()
            if (intakes.value.any { it.journal === entry.journal }) return@withLock held()
            if (entry.rawCandidate != null && entry.rawCandidate != entry.raw) return@withLock held()
            val master = entry.journal
            // A genuine core-retained rejected/terminal result is durable even when no native
            // acquisition was needed. Deferred/unknown/no-result work is never retired here.
            if (master == null) {
                if (!lease.invocation.hasDurableTerminalResult) return@withLock held()
            } else {
                val snapshot = snapshot(master) ?: return@withLock held()
                if ((snapshot.actionMayHaveStarted && snapshot.original == null) || snapshot.original != entry.raw ||
                    (entry.start != null && snapshot.certificate != entry.start?.projection))
                    return@withLock held()
                if (entry.requestBinding?.admission?.action == PhysicalRecordingAction.RETAIN_RESOLUTION) {
                    val read = master.readResolution() as? LocalPhysicalResolutionRead.Read
                        ?: return@withLock held()
                    read.resolution?.let { linked ->
                        if (linked.body != entry.remote.invocation.request.payload ||
                            linked.resolverActorId != entry.remote.invocation.originalActorId ||
                            linked.originalDigest != journalBodyDigest(checkNotNull(snapshot.original)))
                            return@withLock held()
                    }
                }
                if (!entry.denied.denied && entry.raw == null) return@withLock held()
                if (!releaseEntryNative(entry) || master.close() != LocalPhysicalSpoolClose.CLOSED)
                    return@withLock held()
                journals.remove(master.receipt.binding.allocationId)
            }
            entry.deny()
            entries.value = entries.value.filterNot { it === entry }
            PhysicalRemoteRetirement.RETIRED
        }
    }

    /**
     * Device-only original custody route. The caller must be the actual enrolled SDK physical
     * peer. Source/certificate/body are untrusted DATA joined to the genuine already-consumed
     * Master journal and its complete original certificate, never a reconstructed Start/lease.
     */
    public suspend fun retainRemoteOriginal(device: LocalMasterPhysicalOriginalDevice,
        original: LocalMasterPhysicalOriginalRequestV1): LocalMasterPhysicalOriginalRetentionV1 {
        if (!device.owns(registeredOwner) || device.scope != binding.scope || denial.denied)
            return LocalMasterPhysicalOriginalRetentionV1.HELD
        val work = serviceOwned {
            gate.withLock {
                val master = originalJournal(original.clientAllocationId) ?: return@withLock LocalMasterPhysicalOriginalRetentionV1.HELD
                val requirement = master.receipt.binding.requirement
                if (!device.owns(registeredOwner) || requirement.clientDeviceId != device.deviceId ||
                    requirement.canonicalBytes != original.canonicalRequirement ||
                    !sourceMatches(master, original.clientSource, original.certificate))
                    return@withLock LocalMasterPhysicalOriginalRetentionV1.HELD
                val before = snapshot(master) ?: return@withLock LocalMasterPhysicalOriginalRetentionV1.HELD
                if (before.certificate != original.certificate || !before.actionMayHaveStarted)
                    return@withLock LocalMasterPhysicalOriginalRetentionV1.HELD
                val pendingId = master.receipt.binding.allocationId
                val pending = pendingOriginals[pendingId]
                if ((pending != null && pending != original.originalBody) ||
                    (pending == null && pendingOriginals.size >= 128)) return@withLock LocalMasterPhysicalOriginalRetentionV1.HELD
                pendingOriginals[pendingId] = original.originalBody
                val entry = entries.value.singleOrNull { it.journal === master }
                entry?.let {
                    if (it.requestBinding?.admission?.action == PhysicalRecordingAction.RETAIN_RESOLUTION) {
                        // A background repeat of the already forced UNKNOWN is not a new
                        // human action and must not withdraw its admitted linked resolution.
                        // This is exact native evidence from the same device/requirement/
                        // certificate above; conflicting or absent originals never qualify.
                        if (before.original != original.originalBody ||
                            before.originalKind != LocalPhysicalOriginalKind.UNKNOWN) {
                            it.deny()
                            return@withLock LocalMasterPhysicalOriginalRetentionV1.HELD
                        }
                        it.raw = original.originalBody // Known durable origin, not a permission.
                        return@let
                    }
                    val prior = it.rawCandidate
                    if (prior != null && prior != original.originalBody) {
                        it.deny(); return@withLock LocalMasterPhysicalOriginalRetentionV1.HELD
                    }
                    // Retain immutable accepted bytes before native IO or end-only core capture.
                    it.rawCandidate = original.originalBody
                    val prepared = it.nativePublication
                    if (prepared != null) it.publication?.captureOriginalEvent(prepared.binding, sha256(original.originalBody.copyBytes()))
                    it.deny()
                }
                val written = master.retainOriginal(requirement.recordingOperationId, original.originalBody)
                if (written !in RETAINED_WRITES || snapshot(master)?.let {
                        it.certificate == original.certificate && it.original == original.originalBody
                    } != true) {
                    entry?.uncertain = true
                    return@withLock LocalMasterPhysicalOriginalRetentionV1.UNCERTAIN
                }
                pendingOriginals.remove(pendingId)
                entry?.let { it.raw = original.originalBody; it.evidence?.release(); if (it.evidence?.released == false) it.uncertain = true }
                // Queueing reports no business outcome and creates no employee authority.
                intake.value?.enqueue(NativeOriginalIntakeLocator(requirement.clientAllocationId))
                LocalMasterPhysicalOriginalRetentionV1.MASTER_ORIGINAL_RETAINED
            }
        } ?: return LocalMasterPhysicalOriginalRetentionV1.HELD
        val retained = withTimeoutOrNull(750) { work.await() } ?: return LocalMasterPhysicalOriginalRetentionV1.PENDING
        return retained.takeIf { device.owns(registeredOwner) } ?: LocalMasterPhysicalOriginalRetentionV1.HELD
    }

    public fun installedIntake(selected: NativeOriginalIntake): Boolean = selected.owns(registeredOwner) &&
        (intake.compareAndSet(null, selected) || intake.value === selected)

    /** Only authentic immutable catalogue selectors are scanned; never filenames or arbitrary IDs. */
    public suspend fun enqueueRetainedOriginals() {
        val selected = intake.value ?: return
        val work = serviceOwned {
            gate.withLock {
                if (denial.denied) return@withLock
                val ids = catalogue.retainedAllocationSelectors() ?: return@withLock
                if (ids.isEmpty()) return@withLock
                val start = ((intakeAfter?.let(ids::indexOf) ?: -1) + 1) % ids.size
                for (offset in ids.indices) {
                    val id = ids[(start + offset) % ids.size]
                    intakeAfter = id
                    val receipt = catalogue.readExpectedReceipt(id) ?: return@withLock
                    if (!validMasterReceipt(receipt)) return@withLock
                    if (selected.enqueue(NativeOriginalIntakeLocator(receipt.binding.requirement.clientAllocationId)) !=
                        NativeOriginalIntakeSubmission.QUEUED) break
                }
            }
        } ?: return
        withTimeoutOrNull(750) { work.await() }
    }

    public suspend fun acquireOriginal(invocation: NativeOriginalIntakeInvocation): NativeOriginalAcquire {
        if (!invocation.belongsTo(registeredOwner) || invocation.scope != binding.scope || invocation.denied || denial.denied)
            return NativeOriginalAcquire.Held
        val attempt = IntakeAttempt(invocation)
        val work = serviceOwned {
            var held: PhysicalJournalLeaseOwnership? = null
            var transferred = false
            try {
                val selected = gate.withLock {
                    if (intakes.value.size >= 128 || intakes.value.any {
                            it.lease.invocation.locator.clientAllocationId == invocation.locator.clientAllocationId }) return@withLock null
                    val master = originalJournal(invocation.locator.clientAllocationId) ?: return@withLock null
                    val original = snapshot(master) ?: return@withLock null
                    if (original.original == null || original.certificate == null || !original.actionMayHaveStarted) return@withLock null
                    held = PhysicalJournalLeaseOwnership(captureRetainedJournal(master) { invocation.deny() } ?: return@withLock null)
                    provisionalIntakeCustody.add(checkNotNull(held))
                    master to original
                } ?: return@serviceOwned NativeOriginalAcquire.Held
                val master = selected.first
                val observed = selected.second
                val requirement = master.receipt.binding.requirement
                val certificate = checkNotNull(observed.certificate)
                // No native IO or native mutex under Room. The kernel-created invocation owns
                // this once-only SQL DATA window and closes it on return/abandonment.
                val restored = invocation.historicalSources(registeredOwner, requirement.canonicalBytes, certificate)
                    ?: return@serviceOwned NativeOriginalAcquire.Held
                if (!restored.belongsTo(invocation, requirement.canonicalBytes, certificate)) return@serviceOwned NativeOriginalAcquire.Held
                gate.withLock {
                    val sources = restored.sourceRows
                    val client = sources.singleOrNull { it.key.id == requirement.clientAllocationId } ?: return@withLock NativeOriginalAcquire.Held
                    val masterSource = sources.singleOrNull { it.key.id == requirement.masterAllocationId } ?: return@withLock NativeOriginalAcquire.Held
                    if (attempt.delivery.value == 2 || invocation.denied || denial.denied ||
                        snapshot(master)?.let { it.certificate == certificate && it.original == observed.original } != true ||
                        masterSource.payload != master.receipt.sourceCommitment || !sourceMatches(master, client.payload, certificate))
                        return@withLock NativeOriginalAcquire.Held
                    val retained = NativeOriginalRetained(requirement.command, requirement.domainProfile,
                        requirement.canonicalBytes, certificate, checkNotNull(observed.original), sources)
                    val lease = NativeOriginalRetainedLease(invocation, DomainId.random(), retained)
                    val entry = Intake(lease, master, checkNotNull(held))
                    intakes.value = intakes.value + entry
                    provisionalIntakeCustody.remove(checkNotNull(held))
                    attempt.retained = entry
                    transferred = true
                    NativeOriginalAcquire.Retained(lease)
                }
            } finally {
                gate.withLock {
                    if (!transferred) {
                        held?.release()
                        if (held?.released == false) invocation.deny() else held?.let(provisionalIntakeCustody::remove)
                    }
                    if (attempt.delivery.value == 2) abandonIntake(attempt)
                }
            }
        } ?: return NativeOriginalAcquire.Held
        var delivered = false
        try {
            // This actual acquisition owns the invocation's one-use SQL DATA window.
            // A local polling interval must not return HELD and seal that window while
            // the native job still owns a journal/read. Await its real result; caller
            // cancellation still irrevocably denies delivery and owned cleanup below.
            val result = work.await()
            currentCoroutineContext().ensureActive()
            if (invocation.denied || !attempt.delivery.compareAndSet(0, 1)) return NativeOriginalAcquire.Held
            delivered = result is NativeOriginalAcquire.Retained
            return result
        } catch (cancelled: kotlinx.coroutines.CancellationException) { invocation.deny(); throw cancelled }
          catch (_: Exception) { invocation.deny(); return NativeOriginalAcquire.StorageUncertain }
        finally {
            if (!delivered) withContext(NonCancellable) {
                invocation.deny(); attempt.delivery.value = 2
                gate.withLock { abandonIntake(attempt) }
            }
        }
    }

    public suspend fun recheckOriginal(lease: NativeOriginalRetainedLease): PhysicalRecordingNativeCheck {
        val work = serviceOwned {
            gate.withLock {
                val selected = intakes.value.singleOrNull { it.lease === lease && it.lease.invocation === lease.invocation }
                    ?: return@withLock PhysicalRecordingNativeCheck.HELD
                val actual = snapshot(selected.journal)
                if (selected.uncertain || lease.invocation.denied || denial.denied ||
                    !lease.invocation.belongsTo(registeredOwner) || actual == null || actual.certificate != lease.retained.certificate ||
                    actual.original != lease.retained.originalBody || !actual.actionMayHaveStarted ||
                    !sourceMatches(selected.journal, lease.retained.sourceRows.single {
                        it.key.id == selected.journal.receipt.binding.requirement.clientAllocationId }.payload, lease.retained.certificate))
                    PhysicalRecordingNativeCheck.HELD else PhysicalRecordingNativeCheck.VERIFIED
            }
        } ?: return PhysicalRecordingNativeCheck.HELD
        return withTimeoutOrNull(750) { work.await() } ?: run {
            com.ukkera.pos.database.reportLocalMasterRpcReadFailure(
                com.ukkera.pos.database.LocalMasterRpcReadStage.PHYSICAL_ORIGINAL_RECHECK_PENDING, null)
            PhysicalRecordingNativeCheck.HELD
        }
    }

    public suspend fun releaseOriginal(lease: NativeOriginalRetainedLease,
        disposition: NativeOriginalIntakeDisposition): PhysicalRecordingNativeRelease = withContext(NonCancellable) {
        if (disposition.invocation !== lease.invocation || disposition.state == NativeOriginalIntakeDisposition.State.UNCERTAIN)
            return@withContext PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
        // Every accepted service job settles before a native lease used by it can be released.
        val jobs = gate.withLock { serviceJobs.toList() }
        jobs.forEach { it.start(); it.join() }
        gate.withLock {
            val selected = intakes.value.singleOrNull { it.lease === lease } ?: return@withLock PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
            if (selected.uncertain) return@withLock PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN
            selected.ownership.release()
            if (!selected.ownership.released) { selected.uncertain = true; return@withLock PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN }
            intakes.value = intakes.value.filterNot { it === selected }
            PhysicalRecordingNativeRelease.RELEASED
        }
    }

    private fun abandonIntake(attempt: IntakeAttempt) {
        val selected = attempt.retained ?: return
        selected.ownership.release()
        if (selected.ownership.released) intakes.value = intakes.value.filterNot { it === selected }
        else selected.uncertain = true
    }

    private fun validMasterReceipt(receipt: LocalPhysicalJournalReceipt): Boolean = try {
        val requirement = receipt.binding.requirement
        val owners = localPhysicalDomainOwners(requirement)
        receipt.binding.owner == binding && owners.master == binding && owners.authority.assignmentId == originalAssignmentId &&
            owners.authority.deviceId == binding.deviceId && requirement.scope == binding.scope &&
            requirement.masterDeviceId == binding.deviceId && requirement.clientDeviceId != binding.deviceId &&
            requirement.masterStorageProfile == storageProfile
    } catch (_: Exception) { false }

    /** Under gate, existing-only native owner retention before read/close, including unknown opens. */
    private fun originalJournal(clientAllocationId: DomainId): LocalPhysicalJournal? {
        if (denial.denied) return null
        val ids = catalogue.retainedAllocationSelectors() ?: return null
        val expected = ids.mapNotNull(catalogue::readExpectedReceipt).singleOrNull {
            it.binding.requirement.clientAllocationId == clientAllocationId } ?: return null
        if (!validMasterReceipt(expected)) return null
        val id = expected.binding.allocationId
        journals[id]?.let { return it.takeIf { journal -> journal.receipt.sourceCommitment == expected.sourceCommitment } }
        if (!unknownOpens.add(id)) return null
        val opened = catalogue.openExisting(id, localPhysicalDomainOwners(expected.binding.requirement).domain)
        if (opened !is LocalPhysicalJournalOpen.Opened) {
            if (opened !== LocalPhysicalJournalOpen.Held) unknownOpens.remove(id)
            return null
        }
        journals[id] = opened.journal
        unknownOpens.remove(id)
        return opened.journal.takeIf { it.receipt.sourceCommitment == expected.sourceCommitment }
    }

    private fun sourceMatches(master: LocalPhysicalJournal, clientData: StoredBytes, encoded: StoredBytes): Boolean = try {
        val r = master.receipt.binding.requirement
        val certificate = PhysicalCommittedStartProjectionV1.parse(encoded)
        val client = LocalPhysicalJournalSourceProjection.decode(AggregateChange(AggregateKey(
            PhysicalRecordingSourceProfileV1.KIND, r.clientAllocationId), null, PhysicalRecordingSourceProfileV1.SCHEMA, clientData))
        val owners = localPhysicalDomainOwners(r)
        certificate.scope == r.scope && certificate.originalActorId == r.originalActorId &&
            certificate.intentOperationId == r.intentOperationId && certificate.command == r.command &&
            certificate.intentSemanticDigest == r.intentSemanticDigest && certificate.requirementBytes == r.canonicalBytes &&
            certificate.domainProfile == r.domainProfile && certificate.startKind.name == master.domain.startKind.name &&
            certificate.assignmentId == owners.authority.assignmentId && certificate.masterDeviceId == binding.deviceId &&
            certificate.epoch == owners.authority.epoch && certificate.sourceStamps.size == 2 &&
            certificate.sourceStamps.map { it.key.id }.toSet() == setOf(r.masterAllocationId, r.clientAllocationId) &&
            client.owner == owners.client && client.requirement.canonicalBytes == r.canonicalBytes &&
            certificate.sourceStamps.all { stamp ->
                val expected = when (stamp.key.id) { r.masterAllocationId -> master.receipt.sourceCommitment
                    r.clientAllocationId -> clientData; else -> null }
                expected != null && stamp.key.kind == PhysicalRecordingSourceProfileV1.KIND &&
                    stamp.schema == PhysicalRecordingSourceProfileV1.SCHEMA && stamp.generation.value == 1L &&
                    stamp.payloadDigest == sha256(expected.copyBytes())
            }
    } catch (_: Exception) { false }

    private fun snapshot(master: LocalPhysicalJournal): LocalPhysicalOriginalSnapshot? =
        when (val read = master.readOriginal()) {
            is LocalPhysicalOriginalRead.Read -> read.snapshot
            LocalPhysicalOriginalRead.Busy -> {
                com.ukkera.pos.database.reportLocalMasterRpcReadFailure(
                    com.ukkera.pos.database.LocalMasterRpcReadStage.PHYSICAL_CLIENT_NATIVE_ACQUIRE, IllegalStateException())
                null
            }
            LocalPhysicalOriginalRead.Held -> {
                com.ukkera.pos.database.reportLocalMasterRpcReadFailure(
                    com.ukkera.pos.database.LocalMasterRpcReadStage.PHYSICAL_CLIENT_NATIVE_ACQUIRE, IllegalStateException())
                null
            }
        }

    private fun releaseEntryNative(entry: Entry): Boolean {
        entry.temporary?.release(); entry.publicationTemporary?.release(); entry.evidence?.release()
        return entry.temporary?.released != false && entry.publicationTemporary?.released != false && entry.evidence?.released != false
    }

    public fun denyNewWork() { denial.deny(); entries.value.forEach { it.deny() } }

    public suspend fun close(): LocalPhysicalSpoolClose = withContext(NonCancellable) {
        denyNewWork()
        val selected = gate.withLock {
            closing.value?.let { return@withLock it to false }
            CompletableDeferred<LocalPhysicalSpoolClose>().also { closing.value = it } to true
        }
        if (selected.second) {
            val result = try {
                val jobs = gate.withLock { entries.value.flatMap { it.jobs } + serviceJobs }
                jobs.forEach { it.start(); it.join() }
                gate.withLock {
                    var complete = unknownOpens.isEmpty() && intakes.value.isEmpty() && pendingOriginals.isEmpty() &&
                        provisionalIntakeCustody.all { it.released }
                    for (entry in entries.value) {
                        if (entry.uncertain || entry.unreturnedNativeOwner ||
                            (entry.rawCandidate != null && entry.rawCandidate != entry.raw)) complete = false
                        if (!releaseEntryNative(entry)) complete = false
                    }
                    // An unresolved accepted/unknown force keeps its actual native owners alive.
                    if (complete) for (journal in journals.values) if (journal.close() != LocalPhysicalSpoolClose.CLOSED) complete = false
                    if (complete && catalogue.close() != LocalPhysicalSpoolClose.CLOSED) complete = false
                    if (complete && nativeCustody.close() != LocalPhysicalSpoolClose.CLOSED) complete = false
                    if (complete) LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD
                }
            } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
            if (result == LocalPhysicalSpoolClose.CLOSED) nativeRoot.cancel()
            selected.first.complete(result)
        }
        selected.first.await()
    }

    private suspend fun joinAccepted(entry: Entry) {
        val jobs = gate.withLock { entry.jobs.toList() }
        jobs.forEach { it.start(); it.join() }
    }

    private fun exact(lease: PhysicalRemoteInvocationLease): Entry? =
        entries.value.singleOrNull { it.remote === lease && it.remote.invocation === lease.invocation }

    private suspend fun <T> owned(entry: Entry, block: suspend () -> T): Deferred<T> {
        val held = gate.withLock {
            check(entry.jobs.size < 16 && !denial.denied)
            nativeScope.async(start = CoroutineStart.LAZY) {
                try { block() }
                catch (failure: Throwable) { entry.uncertain = true; entry.deny(); throw failure }
            }.also { entry.jobs.add(it) }
        }
        held.start() // Actual retained owner first, native scheduling second.
        return held
    }

    private suspend fun <T> serviceOwned(block: suspend () -> T): Deferred<T>? {
        val held = gate.withLock {
            if (denial.denied) return@withLock null
            serviceJobs.removeAll { it.isCompleted }
            if (serviceJobs.size >= 128) return@withLock null
            nativeScope.async(start = CoroutineStart.LAZY) { block() }.also { serviceJobs.add(it) }
        } ?: return null
        held.start()
        return held
    }

    private suspend fun <T> waitOwned(entry: Entry, work: Deferred<T>): T {
        // This is the actual accepted native job, which may await a correlated remote
        // preparation stage. The unchanged endpoint watchdog can abandon this waiter,
        // never the separately owned job or its native custody. Its real outcome, rather
        // than a shorter reply interval, determines the native result.
        return try {
            work.await()
        } catch (failure: Throwable) { entry.deny(); throw failure }
    }

    override fun toString(): String = "DistributedPhysicalMasterJournalOwner([REDACTED])"
    private companion object {
        val RETAINED_WRITES = setOf(LocalPhysicalJournalWrite.RETAINED, LocalPhysicalJournalWrite.ALREADY_RETAINED)
    }
}

/** Volatile immutable observations only; never a caller-provided authority/currentness check. */
private class PhysicalRemoteCell<T>(initial: T) {
    private val state = MutableStateFlow(initial)
    operator fun getValue(owner: Any?, property: KProperty<*>): T = state.value
    operator fun setValue(owner: Any?, property: KProperty<*>, value: T) { state.value = value }
}

private fun captureRetainedJournal(journal: LocalPhysicalJournal, onDenial: () -> Unit): List<PhysicalSpoolLease>? {
    var catalogue: List<PhysicalSpoolLease> = emptyList()
    var actual: PhysicalSpoolLease? = null
    var transferred = false
    try {
        catalogue = journal.catalogue.capture(onDenial) ?: return null
        actual = journal.capture(onDenial) ?: return null
        val result = catalogue + actual
        transferred = true
        return result
    } finally { if (!transferred && !releasePhysicalSpoolLeases(catalogue + listOfNotNull(actual))) onDenial() }
}

internal fun distributedCertificateAcknowledgment(allocationId: DomainId, source: StoredBytes,
    certificate: StoredBytes): StoredBytes = StoredBytes(journalFrame(listOf(
        journalText("ukkera.distributed-physical-native-stage/v1"), journalText(allocationId.value),
        journalText(journalBodyDigest(certificate).value), journalText(journalBodyDigest(source).value)), 4_096))
