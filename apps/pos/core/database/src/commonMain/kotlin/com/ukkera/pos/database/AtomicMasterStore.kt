package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.recovery.RecoveryAdmissionPolicy
import com.ukkera.pos.recovery.RecoveryAdmissionState
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.database.setup.SavedOriginalSetupV1
import com.ukkera.pos.recovery.ResourceAdmissionDecision
import com.ukkera.pos.security.ActionBinding
import com.ukkera.pos.security.AuthenticatedPrincipal
import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.AuthorizationDecision
import com.ukkera.pos.security.AuthorizationPolicy
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.AuthorizedAction
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CurrentAuthorizationProvider
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.LimitUse
import com.ukkera.pos.security.NativeCompanyBusinessPublicationSnapshot
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.ResultRecoveryAudit
import com.ukkera.pos.security.ResultRecoveryDecision
import com.ukkera.pos.security.ResultRecoveryPolicy
import com.ukkera.pos.security.SelfOwnedNativeHistoryWireV2
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * A bounded Room command kernel. It supplies atomicity, not missing business rules,
 * login/key storage or authority qualification. Handlers and trust adapters are wired
 * only in a trusted process composition root, never selected/instantiated by RPC data.
 * Native source acquisition/recheck/release stays outside Room. Its original-import BODY
 * borrows the concrete history owner's one transaction; that owner, not a handler/provider,
 * alone performs pending native-history preparation and actual outer settlement.
 */
public class AtomicMasterStore internal constructor(
    private val database: MasterRoomDatabase,
    public val scope: BranchScope,
    private val authority: AuthorityIdentity,
    handlers: List<MasterCommandHandler>,
    private val authorizationSource: TransactionAuthorizationSource,
    private val runtimeVerifier: MasterRuntimeVerifier,
    private val clock: MasterClock,
    private val fault: StoreFaultProbe = NoStoreFault,
    readHandlers: List<MasterReadHandler> = emptyList(),
    private val approvalSource: TransactionApprovalSource = UnavailableTransactionApprovalSource,
    private val physicalRecordingGate: PhysicalRecordingExecutionGate = UnavailablePhysicalRecordingExecutionGate,
    private val physicalInstructionRegistry: PhysicalInstructionPublicationRegistry? = null,
    private val runtimeHistoryOwner: RoomRuntimeHistoryOwner? = null,
    nativeOriginalImports: List<NativeOriginalImportInterpreter> = emptyList(),
    private val stockProjection: StockCanonicalProjectionV2? = null,
    historyProducer: RuntimeHistoryBusinessProducer? = null,
) {
    private val handlers: Map<CommandKey, MasterCommandHandler>
    private val readHandlers: Map<ReadKey, MasterReadHandler>
    // The SAME installed history producer used by original startup, never a request label.
    // A missing producer or per-command opt-in cannot create/use a v3 business original.
    private val historyAttributionSupport = historyProducer?.let { producer ->
        producer.commands.toSet().also { require(it.size <= 128) }
            .associateWith(producer::actionApprovalBusinessAttribution)
    }.orEmpty()
    private val dao: MasterDao = database.facts()
    private val physicalService = PhysicalRecordingServiceLatch(physicalInstructionRegistry)
    private val physicalStoreIncarnation = DomainId.random()
    private val physicalServiceIncarnation = DomainId.random()
    private val physicalEndpoint = MutableStateFlow<LocalMasterEndpoint?>(null)
    private val localDisplayDelivery = MutableStateFlow<RoomLocalDisplayDeliveryOwner?>(null)
    private val physicalRegistration = Any()
    private val physicalKinds: Set<String>
    private val physicalClaims: Set<String>
    private val physicalAuthorizers: Map<CommandKey, MasterPhysicalInteractiveAuthorizer>
    private val nativeOriginalImports: Map<NativeOriginalDomain, NativeOriginalImportInterpreter>
    private val nativeOriginalIngress = MutableStateFlow<NativeOriginalIntake?>(null)
    private val nativeOriginalInstalled = MutableStateFlow(false)
    private val nativeOriginalCalls = MutableStateFlow<List<NativeOriginalCall>>(emptyList())
    private class NativeOriginalCall(val invocation: NativeOriginalIntakeInvocation,
        val lease: NativeOriginalRetainedLease)

    init {
        runtimeHistoryOwner?.bindStore(this, historyProducer?.retailCustomerReceiptEventClassifier)
        require(handlers.size <= 256 && handlers.map { it.key }.toSet().size == handlers.size)
        handlers.forEach { handler ->
            require(handler.writableKinds.size in 1..64 && handler.claimNamespaces.size <= 64)
            require((handler is MasterStreamingCommandHandler) == (handler.key in StockStreamingCommandsV2.all))
            if (handler is MasterStreamingCommandHandler) require(stockProjection != null)
            handler.writableKinds.forEach(::requireProtocolName)
            handler.claimNamespaces.forEach(::requireProtocolName)
            require(handler.physicalRecordingKinds.all { it in handler.writableKinds })
            require(handler.physicalRecordingClaimNamespaces.all { it in handler.claimNamespaces })
        }
        this.handlers = handlers.associateBy { it.key }
        physicalAuthorizers = handlers.mapNotNull { handler -> handler.physicalInteractiveAuthorizer?.let { authorizer ->
            check(authorizer.command == handler.key)
            requireProtocolName(authorizer.domainProfile)
            handler.key to authorizer
        } }.toMap()
        if (physicalInstructionRegistry != null) {
            check(authorizationSource is RoomCurrentLocalAuthorizationSource && authorizationSource.ownsPhysicalRegistry(physicalInstructionRegistry))
            check(physicalInstructionRegistry.bindKernel(this, clock))
        }
        // A handler cannot bypass another installed physical profile by claiming to be ordinary.
        // Closed first-release raw facts/fences are ALWAYS protected, including when every
        // handler declaration is accidentally empty. Legacy NEW reservation starts refuse;
        // positive recovery and source-proven historical Record/Apply stay distinct.
        physicalKinds = PhysicalRecordingProtectedEffects.kinds + handlers.flatMap { it.physicalRecordingKinds }
        physicalClaims = PhysicalRecordingProtectedEffects.claims + handlers.flatMap { it.physicalRecordingClaimNamespaces }
        require(readHandlers.size <= 256 && readHandlers.map { it.key }.toSet().size == readHandlers.size)
        this.readHandlers = readHandlers.associateBy { it.key }
        require(nativeOriginalImports.size <= NativeOriginalDomain.entries.size &&
            nativeOriginalImports.map { it.domain }.distinct().size == nativeOriginalImports.size)
        require(nativeOriginalImports.all { it.domain.command in this.handlers })
        this.nativeOriginalImports = nativeOriginalImports.associateBy { it.domain }
    }

    public fun close() {
        denyPhysicalRecording()
        // A synchronous kernel close cannot await physical/native ownership or create
        // another actual Room attempt. The original runtime remains its sole close owner.
        check(runtimeHistoryOwner == null && database.runtimeHistoryOwner == null) {
            "Self-owned Room close belongs to terminal native cleanup"
        }
        database.close()
    }

    /** One grouped endpoint only. A caller cannot supply or replace the registered owner. */
    internal fun allowsOperationalIngress(): Boolean = runtimeHistoryOwner?.operational ?: true

    internal fun registerPhysicalEndpoint(endpoint: LocalMasterEndpoint): Any {
        check(physicalEndpoint.compareAndSet(null, endpoint)) { "Physical ingress already owned" }
        runtimeHistoryOwner?.bindEndpoint(endpoint)
        return physicalRegistration
    }

    /** Actual self-owned local composition only; ordinary builders and installation do not bind. */
    internal fun bindLocalDisplayDelivery(endpoint: LocalMasterEndpoint, peer: LocalAuthenticatedPeer) {
        val reader = readHandlers[MASTER_LOCAL_DISPLAY_READ] as? MasterLocalDisplayReadHandler ?: return
        val history = checkNotNull(runtimeHistoryOwner)
        check(database.runtimeHistoryOwner === history && history.database === database && history.operational &&
            physicalEndpoint.value === endpoint && endpoint.health.value == LocalMasterEndpointHealth.ACTIVE &&
            authorizationSource is RoomCurrentLocalAuthorizationSource && physicalInstructionRegistry != null &&
            peer.scope == scope && peer.deviceId == authority.deviceId && peer.isCurrent())
        val owned = RoomLocalDisplayDeliveryOwner(this, database, history, endpoint, peer, reader)
        check(localDisplayDelivery.compareAndSet(null, owned)) { "Local DISPLAY delivery already owned" }
    }

    /** Includes every existing one-way kernel uncertainty latch, not only explicit close. */
    internal fun localDisplayLifetimeCurrent(owner: RoomLocalDisplayDeliveryOwner): Boolean =
        localDisplayDelivery.value === owner && owner.store === this && owner.database === database &&
            owner.history === runtimeHistoryOwner && database.runtimeHistoryOwner === owner.history &&
            readHandlers[MASTER_LOCAL_DISPLAY_READ] === owner.reader && physicalEndpoint.value === owner.endpoint &&
            owner.endpoint.health.value == LocalMasterEndpointHealth.ACTIVE && owner.history.operational &&
            physicalService.current()

    /** Called at the actual fresh worker before authentication/Room, never by a request/provider. */
    internal fun capturePhysicalWorker(endpoint: LocalMasterEndpoint, registration: Any,
                                       context: CoroutineContext): PhysicalRecordingOwnedIngress {
        check(physicalEndpoint.value === endpoint && registration === physicalRegistration)
        return PhysicalRecordingOwnedIngress(this, endpoint, registration, context)
    }

    internal fun denyPhysicalRecording() {
        physicalService.deny()
        nativeOriginalCalls.value.forEach { it.invocation.deny() }
    }

    internal fun physicalActionsFor(endpoint: LocalMasterEndpoint): LocalMasterPhysicalActions? =
        if (physicalEndpoint.value === endpoint && physicalInstructionRegistry != null && physicalAuthorizers.isNotEmpty() &&
            physicalRecordingGate is PhysicalRecordingInteractiveGate && physicalService.current()) LocalMasterPhysicalActions(endpoint, this)
        else null

    internal fun newPhysicalRequestLifetime(endpoint: LocalMasterEndpoint): PhysicalInteractiveRequestLifetime {
        check(physicalEndpoint.value === endpoint && physicalRecordingGate is PhysicalRecordingInteractiveGate)
        return checkNotNull(physicalInstructionRegistry).newLifetime(this, endpoint)
    }

    private fun physicalWorkerCurrent(ingress: PhysicalRecordingOwnedIngress, context: CoroutineContext): Boolean =
        ingress.storeOwner === this && ingress.endpointOwner === physicalEndpoint.value &&
            ingress.registration === physicalRegistration && ingress.inWorker(context)

    /** No supplied owner/status flag can create this companion. Missing genuine D2-W stays HELD. */
    internal fun nativeOriginalIntakeFor(endpoint: LocalMasterEndpoint): NativeOriginalIntake? {
        if (physicalEndpoint.value !== endpoint || runtimeHistoryOwner == null || physicalInstructionRegistry == null ||
            physicalRecordingGate !is NativeOriginalIntakeSource || physicalRecordingGate !is PhysicalRecordingInteractiveGate ||
            !physicalService.current() || nativeOriginalImports.keys != NativeOriginalDomain.entries.toSet() ||
            NativeOriginalDomain.entries.any { physicalAuthorizers[it.command]?.domainProfile != it.domainProfile }) return null
        val intake = NativeOriginalIntake(endpoint, physicalRecordingGate)
        check(nativeOriginalIngress.compareAndSet(null, intake)) { "Native original ingress already owned" }
        return intake
    }

    /** Exact already-registered association, including the first installation handshake.
     * Matching the association alone never admits enqueue/import: those also require the
     * completed history/source installation below. */
    internal fun ownsNativeOriginalIntake(endpoint: LocalMasterEndpoint, intake: NativeOriginalIntake): Boolean =
        physicalEndpoint.value === endpoint && nativeOriginalIngress.value === intake &&
            intake.registeredSource === physicalRecordingGate && physicalService.current()

    internal fun acceptsNativeOriginal(endpoint: LocalMasterEndpoint, intake: NativeOriginalIntake): Boolean =
        nativeOriginalInstalled.value && ownsNativeOriginalIntake(endpoint, intake)

    /** Runs as bounded Work on the SAME actual clean worker, including installation/restart wake. */
    internal suspend fun enqueueRetainedNativeOriginals(intake: NativeOriginalIntake,
        ingress: PhysicalRecordingOwnedIngress): NativeOriginalWorkerResult {
        if (!physicalWorkerCurrent(ingress, currentCoroutineContext()) || nativeOriginalIngress.value !== intake ||
            intake.registeredSource !== physicalRecordingGate || !physicalService.current()) return NativeOriginalWorkerResult.HELD
        val owner = runtimeHistoryOwner ?: return NativeOriginalWorkerResult.HELD
        if (!nativeOriginalInstalled.value) {
            // Concrete original owner registration only, never a callback/VERIFIED substitute.
            // The separately owned D2-W body must exist before this composition is available.
            owner.bindNativeOriginalIntake(this, database, ingress.endpointOwner, intake)
            check(intake.registeredSource.installedIntake(intake)) { "Native original source registration failed" }
            check(nativeOriginalInstalled.compareAndSet(false, true)) { "Native original source already installed" }
        }
        intake.registeredSource.enqueueRetainedOriginals()
        currentCoroutineContext().ensureActive()
        return if (physicalWorkerCurrent(ingress, currentCoroutineContext()) && physicalService.current())
            NativeOriginalWorkerResult.SETTLED else NativeOriginalWorkerResult.HELD
    }

    /**
     * Locator-only independent service custody. No employee principal, old session or permission
     * snapshot is made here. Native acquisition/recheck/release NEVER borrows a Room context.
     * The SAME concrete original history owner supplies the one outer transaction and its true
     * disposition. A thrown/unknown outer result retains the exact native lease for retirement.
     */
    internal suspend fun importNativeOriginalFromOwnedIngress(intake: NativeOriginalIntake,
        locator: NativeOriginalIntakeLocator, ingress: PhysicalRecordingOwnedIngress): NativeOriginalWorkerResult {
        if (!physicalWorkerCurrent(ingress, currentCoroutineContext()) ||
            !acceptsNativeOriginal(ingress.endpointOwner, intake)) return NativeOriginalWorkerResult.HELD
        val owner = runtimeHistoryOwner ?: return NativeOriginalWorkerResult.HELD
        val source = intake.registeredSource
        val invocation = NativeOriginalIntakeInvocation(this, ingress.endpointOwner, source, locator, scope,
            physicalStoreIncarnation, physicalServiceIncarnation, DomainId.random())
        var call: NativeOriginalCall? = null
        var disposition: NativeOriginalIntakeDisposition? = null
        var result = NativeOriginalWorkerResult.HELD
        var cleanupUncertain = false
        try {
            val acquired = source.acquireOriginal(invocation)
            if (acquired is NativeOriginalAcquire.Retained) {
                // Take custody BEFORE any cancellation point after delivery. A wrong object is
                // uncertainty, not permission to release somebody else's native acquisition.
                check(acquired.lease.invocation === invocation) { "Native original acquisition owner mismatch" }
                val owned = NativeOriginalCall(invocation, acquired.lease)
                while (true) {
                    val current = nativeOriginalCalls.value
                    check(current.size < 128 && current.none { it.invocation === invocation })
                    if (nativeOriginalCalls.compareAndSet(current, current + owned)) break
                }
                call = owned
            }
            currentCoroutineContext().ensureActive()
            when (acquired) {
                NativeOriginalAcquire.Held -> Unit
                NativeOriginalAcquire.StorageUncertain -> result = NativeOriginalWorkerResult.STORAGE_UNCERTAIN
                is NativeOriginalAcquire.Retained -> {
                    if (!physicalWorkerCurrent(ingress, currentCoroutineContext())) {
                        result = NativeOriginalWorkerResult.STORAGE_UNCERTAIN
                    } else when (source.recheckOriginal(acquired.lease)) {
                        PhysicalRecordingNativeCheck.STORAGE_UNCERTAIN -> result = NativeOriginalWorkerResult.STORAGE_UNCERTAIN
                        PhysicalRecordingNativeCheck.HELD -> {
                            // This exact worker has NOT entered any Room/history-owner call.
                            disposition = NativeOriginalIntakeDisposition(invocation, NativeOriginalIntakeDisposition.State.DISPOSED)
                        }
                        PhysicalRecordingNativeCheck.VERIFIED -> {
                            currentCoroutineContext().ensureActive()
                            if (!physicalWorkerCurrent(ingress, currentCoroutineContext())) {
                                result = NativeOriginalWorkerResult.STORAGE_UNCERTAIN
                            } else if (invocation.denied || !physicalService.current()) {
                                // Still a known no-SQL path, not inference from an exception.
                                disposition = NativeOriginalIntakeDisposition(invocation, NativeOriginalIntakeDisposition.State.DISPOSED)
                            } else {
                                // Fixed real-owner entry. It owns one original parent, complete
                                // all-writer capture, finalization, actual outer COMMIT/cleanup and
                                // native settlement. Never replace this with MasterRuntimeVerifier.
                                val outer = owner.importNativeOriginal(this, intake, invocation, acquired.lease)
                                check(outer.invocation === invocation) { "Native original disposition owner mismatch" }
                                disposition = outer
                                when (outer.state) {
                                    NativeOriginalIntakeDisposition.State.UNCERTAIN -> result = NativeOriginalWorkerResult.STORAGE_UNCERTAIN
                                    NativeOriginalIntakeDisposition.State.DISPOSED -> Unit
                                    NativeOriginalIntakeDisposition.State.SETTLED -> {
                                        // Actual native recheck AFTER the real Room/native-history
                                        // owner returned, not an in-transaction provider callback.
                                        disposition = null // reacquire the release candidate only after real native recheck
                                        if (physicalWorkerCurrent(ingress, currentCoroutineContext()) &&
                                            source.recheckOriginal(acquired.lease) == PhysicalRecordingNativeCheck.VERIFIED) {
                                            disposition = outer
                                            result = NativeOriginalWorkerResult.SETTLED
                                        } else {
                                            disposition = null // no unproved release of native custody
                                            result = NativeOriginalWorkerResult.STORAGE_UNCERTAIN
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // Accepted work belongs to this service. Unknown fate is retained, even if native
            // storage or the outer COMMIT completed before the cancelled continuation resumed.
            denyPhysicalRecording()
            throw cancelled
        } catch (_: Exception) {
            result = NativeOriginalWorkerResult.STORAGE_UNCERTAIN
        } finally {
            invocation.deny()
            val owned = call
            if (owned != null) {
                val actual = disposition
                if (actual == null || actual.state == NativeOriginalIntakeDisposition.State.UNCERTAIN ||
                    !ingress.sameWorkerContext(currentCoroutineContext())) {
                    cleanupUncertain = true // retain in BOTH the store and the actual source registry
                } else {
                    val released = try { withContext(NonCancellable) { source.releaseOriginal(owned.lease, actual) } }
                    catch (_: Exception) { PhysicalRecordingNativeRelease.STORAGE_UNCERTAIN }
                    if (released != PhysicalRecordingNativeRelease.RELEASED) cleanupUncertain = true
                    else while (true) {
                        val current = nativeOriginalCalls.value
                        if (nativeOriginalCalls.compareAndSet(current, current.filterNot { it === owned })) break
                    }
                }
            }
            if (cleanupUncertain || result == NativeOriginalWorkerResult.STORAGE_UNCERTAIN) denyPhysicalRecording()
        }
        return if (cleanupUncertain) NativeOriginalWorkerResult.STORAGE_UNCERTAIN else result
    }

    /**
     * BODY of the real owner-created native-original frame, never a second outer transaction.
     * The frame comes only from the SAME D2-W owner and binds this exact TransactionView before
     * its first read. It alone owns complete physical mutation capture/finalization/outer fate.
     * A returned enum is DATA, NOT proof that Room committed or native custody may be released.
     */
    internal suspend fun importNativeOriginalInFrame(frame: RoomRuntimeHistoryFrame,
        invocation: NativeOriginalIntakeInvocation, lease: NativeOriginalRetainedLease): NativeOriginalHistoryBodyResult {
        check(invocation.store === this && invocation.endpoint === physicalEndpoint.value &&
            invocation.source === physicalRecordingGate && lease.invocation === invocation &&
            nativeOriginalCalls.value.singleOrNull { it.invocation === invocation }?.lease === lease)
        val view = TransactionView.forNativeOriginal(scope, dao, frame, this, invocation, lease)
        stockProjection?.let { view.bindNativeStock(this, frame, it) }
        try {
            val result = run originalBody@ {
                if (invocation.denied) return@originalBody NativeOriginalHistoryBodyResult.HELD
                val native = lease.retained
                val domain = NativeOriginalDomain.entries.singleOrNull {
                    it.command == native.command && it.domainProfile == native.domainProfile
                } ?: return@originalBody NativeOriginalHistoryBodyResult.HELD
                val interpreter = nativeOriginalImports[domain] ?: return@originalBody NativeOriginalHistoryBodyResult.HELD
                val projection = interpreter.project(native)
                if (projection.domain != domain || projection.scope != scope ||
                    projection.clientSource.allocationId != invocation.locator.clientAllocationId ||
                    projection.masterSource.installationId != frame.originalInstallationId ||
                    projection.masterSource.deviceId != frame.originalAuthority.deviceId || frame.originalAuthority != authority)
                    return@originalBody NativeOriginalHistoryBodyResult.HELD
                val handler = handlers[domain.command] ?: return@originalBody NativeOriginalHistoryBodyResult.HELD
                if (handler.normalize(native.originalBody) != native.originalBody ||
                    handler.physicalRecordingAction(native.originalBody) != PhysicalRecordingAction.RETAIN_ORIGINAL ||
                    handler.reservedOperationBinding(native.originalBody) != projection.reservedBinding)
                    return@originalBody NativeOriginalHistoryBodyResult.HELD
                val branch = dao.branch(scope.storageKey()) ?: return@originalBody NativeOriginalHistoryBodyResult.HELD
                val parent = frame.originalParent
                if (!branch.writable || branch.assignmentId != authority.assignmentId.value || branch.deviceId != authority.deviceId.value ||
                    branch.epoch != authority.epoch.value || parent.commandEpoch != branch.epoch ||
                    parent.commandSequence != branch.sequence || parent.commandDigest.value != branch.journalHash)
                    return@originalBody NativeOriginalHistoryBodyResult.HELD
                val historicalStart = nativeOriginalStart(view, native, projection)
                    ?: return@originalBody NativeOriginalHistoryBodyResult.HELD
                val digest = semanticDigest(scope, projection.originalActorId, domain.command, native.originalBody)
                val existing = view.retainedCommand(projection.recordingOperationId)
                if (existing != null) {
                    // PRIVATE original recognition only. Never an employee status/result API.
                    // The concrete reader verifies the current raw+advanced application lineage;
                    // matching IDs/digests alone neither authorize nor rematerialize the fact.
                    if (!matchesNativeOriginal(existing, native, projection, historicalStart, digest) ||
                        !interpreter.verifyExisting(view, NativeOriginalImportContext(native, historicalStart, existing.recordedAt), existing))
                        return@originalBody NativeOriginalHistoryBodyResult.HELD
                    return@originalBody NativeOriginalHistoryBodyResult.ALREADY_PRESENT
                }
                val at = clock.now().also(::requireEvidenceV1Time)
                val plan = interpreter.prepare(view, NativeOriginalImportContext(native, historicalStart, at))
                    ?: return@originalBody NativeOriginalHistoryBodyResult.HELD
                check(plan.domain == domain && plan.command == domain.command && plan.operationId == projection.recordingOperationId &&
                    plan.originalActorId == projection.originalActorId && plan.canonicalBody == native.originalBody &&
                    plan.semanticDigest == digest && plan.reservedBinding == projection.reservedBinding &&
                    plan.result.size in 1..NativeOriginalRetainedCodecV1.MAX_RESULT_BYTES)
                val changes = listOf(plan.rawOriginal, plan.initialApplication)
                NativeOriginalRetainedCodecV1.requireChanges(plan.domain, plan.operationId, changes)
                // Partial/advanced data is never overwritten, even if a bad interpreter proposed it.
                if (changes.any { view.aggregate(it.key) != null } || invocation.denied)
                    return@originalBody NativeOriginalHistoryBodyResult.HELD
                val provenance = nativeOriginalProvenance(frame, invocation, native, projection, historicalStart, digest)
                invalidatePhysicalBeforeWrite(plan.operationId, changes.map { it.key }.toSet(), emptySet(), emptySet())
                frame.captureNativeOriginal(plan, projection, provenance)
                persistNativeOriginal(branch, plan, provenance, at)
                fault.at(StoreFaultPoint.BEFORE_COMMIT)
                // HELD MUST be a real rollback in the owner, even after tentative writes. The
                // owner's closed finalizer rechecks the same denial through its own commit cut.
                if (invocation.denied) NativeOriginalHistoryBodyResult.HELD else NativeOriginalHistoryBodyResult.IMPORTED
            }
            view.prepareNormalReturn()
            view.finishNow()
            return if (invocation.denied) NativeOriginalHistoryBodyResult.HELD else result
        } finally { view.close() }
    }

    private suspend fun nativeOriginalStart(view: CommandReadView, native: NativeOriginalRetained,
        projection: NativeOriginalProjection): RetainedCommandEvidence? {
        val certificate = PhysicalCommittedStartProjectionV1.parse(native.certificate)
        val start = view.retainedCommand(projection.intentOperationId) ?: return null
        if (!start.committed || start.nativeOriginalProvenance != null || start.scope != scope ||
            start.operationId != projection.intentOperationId || start.originalActorId != projection.originalActorId ||
            start.command != projection.domain.command || start.semanticDigest != projection.intentSemanticDigest ||
            certificate.scope != scope || certificate.originalActorId != start.originalActorId ||
            certificate.intentOperationId != start.operationId || certificate.command != start.command ||
            certificate.intentSemanticDigest != start.semanticDigest || certificate.assignmentId != start.assignmentId ||
            certificate.epoch != start.authorityEpoch || certificate.sequence != start.sequence || certificate.journalHash != start.journalHash ||
            certificate.resultDigest != start.resultHash || certificate.recordedAt != start.recordedAt ||
            certificate.domainProfile != projection.domain.domainProfile || certificate.startKind != projection.domain.startKind ||
            certificate.masterDeviceId != projection.masterSource.deviceId || certificate.requirementBytes != native.canonicalRequirement) return null
        val sources = listOf(projection.masterSource, projection.clientSource)
        if (native.sourceRows.map { it.key }.toSet() != sources.map {
                AggregateKey(PhysicalRecordingSourceProfileV1.KIND, it.allocationId) }.toSet()) return null
        for (source in sources) {
            val key = AggregateKey(PhysicalRecordingSourceProfileV1.KIND, source.allocationId)
            val held = native.sourceRows.single { it.key == key }
            val row = view.aggregate(key) ?: return null
            val original = start.aggregateWrites.singleOrNull { it.key == key } ?: return null
            val stamp = certificate.sourceStamps.singleOrNull { it.key == key } ?: return null
            if (held.expectedRevision != null || row.revision != Revision(1) || row.schema != PhysicalRecordingSourceProfileV1.SCHEMA ||
                held.schema != row.schema || held.payload != row.payload || source.sourcePayloadDigest != row.digest ||
                sha256(held.payload.bytes()) != row.digest || original.storageGeneration != row.revision ||
                original.schema != row.schema || original.payload != row.payload || original.payloadDigest != row.digest ||
                stamp.schema != row.schema || stamp.generation != row.revision || stamp.payloadDigest != row.digest) return null
        }
        val key = ReservedOperations.key(projection.recordingOperationId)
        val fence = view.aggregate(key) ?: return null
        val reservation = view.reservedOperation(projection.recordingOperationId) ?: return null
        val originalFence = start.aggregateWrites.singleOrNull { it.key == key } ?: return null
        if (reservation.operationId != projection.recordingOperationId || reservation.actorId != projection.originalActorId ||
            reservation.command != projection.domain.command || reservation.targetBinding != projection.reservedBinding ||
            originalFence.storageGeneration != fence.revision || originalFence.schema != fence.schema ||
            originalFence.payload != fence.payload || originalFence.payloadDigest != fence.digest) return null
        return start
    }

    private fun matchesNativeOriginal(existing: RetainedCommandEvidence, native: NativeOriginalRetained,
        projection: NativeOriginalProjection, start: RetainedCommandEvidence, digest: PayloadDigest): Boolean {
        if (!existing.committed || existing.scope != scope || existing.operationId != projection.recordingOperationId ||
            existing.originalActorId != projection.originalActorId || existing.command != projection.domain.command ||
            existing.normalized != native.originalBody || existing.semanticDigest != digest ||
            existing.claimChanges.isNotEmpty() || existing.jobs.isNotEmpty() || existing.aggregateWrites.any { it.storageGeneration != Revision(1) }) return false
        NativeOriginalRetainedCodecV1.requireChanges(projection.domain, existing.operationId,
            existing.aggregateWrites.map { AggregateChange(it.key, null, it.schema, it.payload) })
        val provenance = existing.nativeOriginalProvenance ?: return true // genuine employee v1 original is also exact prior retention
        return provenance.historicalStart == nativeOriginalHistoricalStart(start, projection) &&
            provenance.nativeOriginal == nativeOriginalFacts(native, projection, digest)
    }

    private fun nativeOriginalHistoricalStart(start: RetainedCommandEvidence, projection: NativeOriginalProjection):
        NativeOriginalJournalProvenanceV1.HistoricalStart = NativeOriginalJournalProvenanceV1.HistoricalStart(
        start.originalActorId, start.operationId, start.command, start.semanticDigest, start.authenticationId, start.policyRevision,
        start.assignmentId, projection.masterSource.deviceId, start.authorityEpoch, start.sequence, start.journalHash, start.resultHash, start.recordedAt)

    private fun nativeOriginalFacts(native: NativeOriginalRetained, projection: NativeOriginalProjection, digest: PayloadDigest):
        NativeOriginalJournalProvenanceV1.NativeOriginal = NativeOriginalJournalProvenanceV1.NativeOriginal(
        projection.domain.domainProfile, projection.recordingOperationId, sha256(native.canonicalRequirement.bytes()),
        sha256(native.certificate.bytes()), sha256(native.originalBody.bytes()), digest, projection.masterSource, projection.clientSource)

    private fun nativeOriginalProvenance(frame: RoomRuntimeHistoryFrame, invocation: NativeOriginalIntakeInvocation,
        native: NativeOriginalRetained, projection: NativeOriginalProjection, start: RetainedCommandEvidence,
        digest: PayloadDigest): NativeOriginalJournalProvenanceV1 {
        val binding = frame.originalBinding.copyWireBytes()
        val commitment = try { sha256(binding) } finally { binding.fill(0) }
        return NativeOriginalJournalProvenanceV1(scope, nativeOriginalHistoricalStart(start, projection),
            nativeOriginalFacts(native, projection, digest), NativeOriginalJournalProvenanceV1.CurrentWriter(
                frame.originalInstallationId, frame.originalAuthority.deviceId, physicalStoreIncarnation, physicalServiceIncarnation,
                invocation.invocationId, authority.assignmentId, authority.epoch, SelfOwnedNativeHistoryWireV2.PROFILE, commitment),
            NativeOriginalJournalProvenanceV1.RuntimeParent(JournalSequence(frame.originalParent.historySequence), frame.originalParent.historyDigest))
    }

    /** No fake LocalEmployeePrincipal and no path to money/stock/claims/jobs/source updates. */
    private suspend fun persistNativeOriginal(branch: BranchRow, plan: NativeOriginalImportPlan,
        provenance: NativeOriginalJournalProvenanceV1, at: UtcInstant) {
        val next = JournalSequence(branch.sequence).next()
        val historical = provenance.historicalStart
        val row = OperationRow(scope.storageKey(), plan.operationId.value, plan.originalActorId.value,
            plan.command.name, plan.command.version, plan.semanticDigest.value, "committed", plan.result.copyBytes(),
            sha256(plan.result.bytes()).value, next.value, authority.epoch.value, at.toString(),
            historical.authenticationId.value, historical.policyRevision.value)
        val changes = listOf(plan.rawOriginal, plan.initialApplication)
        val envelope = NativeOriginalRetainedCodecV1.encode(row, authority, PayloadDigest(branch.journalHash),
            plan.canonicalBody, changes, provenance)
        val hash = sha256(envelope)
        dao.insertOperation(row)
        fault.at(StoreFaultPoint.AFTER_RESULT)
        for (change in changes.sortedWith(compareBy({ it.key.kind }, { it.key.id.value }))) {
            dao.insertAggregate(AggregateRow(scope.storageKey(), change.key.kind, change.key.id.value, 1,
                change.schema, change.payload.copyBytes(), sha256(change.payload.bytes()).value))
        }
        fault.at(StoreFaultPoint.AFTER_EFFECTS)
        dao.insertJournal(JournalRow(scope.storageKey(), next.value, plan.operationId.value, authority.assignmentId.value,
            authority.epoch.value, branch.journalHash, hash.value, envelope))
        fault.at(StoreFaultPoint.AFTER_JOURNAL)
        // Distinct historical custody retention event, never a current employee command grant.
        dao.insertAudit(AuditRow(DomainId.random().value, scope.storageKey(), plan.operationId.value, "native-original.retained",
            plan.originalActorId.value, plan.originalActorId.value, historical.policyRevision.value, row.committedAt, plan.semanticDigest.value))
        fault.at(StoreFaultPoint.AFTER_AUDIT)
        val upload = frame(listOf("ukkera.journal-upload/v1", scope.branchId.value, authority.epoch.value.toString(),
            next.value.toString(), hash.value).map(String::encodeToByteArray))
        dao.insertOutbox(listOf(OutboxRow(scope.storageKey(), plan.operationId.value, 0, SYNC_JOB, upload, sha256(upload).value)))
        fault.at(StoreFaultPoint.AFTER_OUTBOX)
        check(dao.advance(scope.storageKey(), branch.revision, Revision(branch.revision).next().value, next.value, hash.value) == 1)
    }

    /** Same registry, authority and actual writer as execution; never a caller-authored requirement. */
    internal fun approvalCommandPreparation(): LocalApprovalCommandPreparation = LocalApprovalCommandPreparation { principal, request, view, additionalResources ->
        if (view !is TransactionView || !view.ownedBy(database, principal)) protectedLocalUnavailable()
        stockProjection?.let { view.bindStock(this, database, it) }
        localDisplayDelivery.value?.let { view.bindLocalDisplayDelivery(this, database, it) }
        if (principal.scope != scope || view.scope != scope) return@LocalApprovalCommandPreparation null
        val handler = handlers[request.command] ?: return@LocalApprovalCommandPreparation null
        val normalized = try { handler.normalize(request.payload) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { return@LocalApprovalCommandPreparation null }
        if (view.originalForKernel(request.operationId) != null) return@LocalApprovalCommandPreparation null
        val digest = semanticDigest(scope, principal.actorId, request.command, normalized)
        val identity = OperationIdentity(scope.branchId, request.operationId, principal.actorId, 1, digest.value)
        val branch = dao.branch(scope.storageKey()) ?: return@LocalApprovalCommandPreparation null
        val recovery = recoveryState(branch)
        val observed = observe(branch, recovery)
        if (!branch.writable || observed.authority != authority ||
            runtimeVerifier.writer(observed) != RuntimeVerification.VERIFIED ||
            runtimeVerifier.absentOperation(observed, identity) != RuntimeVerification.VERIFIED) return@LocalApprovalCommandPreparation null
        val reserved = view.reservedOperation(request.operationId)
        if (reserved != null && !reserved.matchesRecorderRoute(principal.actorId, request.command,
                handler.reservedOperationBinding(normalized), handler.physicalRecordingAction(normalized)))
            return@LocalApprovalCommandPreparation null
        val context = CommandContext(scope, principal.actorId, request.operationId, request.command, digest,
            actionApprovalRequested = true)
        view.bindStockCommand(context)
        val prepared = handler.prepare(normalized, view, context)
        val requirement = prepared.requirement
        val binding = requirement.binding
        check(requirement.scope == scope && binding != null && binding.operationId == request.operationId && binding.payloadDigest == digest)
        if (reserved != null && reserved.actorId != principal.actorId &&
            !reserved.matchesSupervisedResolutionRequirement(requirement)) return@LocalApprovalCommandPreparation null
        if (requirement.permission.verb != PermissionVerb.PERFORM || !prepared.allowsActionApproval)
            return@LocalApprovalCommandPreparation null
        // Only the trusted approval service supplies these derived manager/approval identities.
        // They supplement the actual business footprint in this SAME writer/recovery cut.
        check(additionalResources.size <= 2 && additionalResources.all { it.kind == RecoveryResourceKind.AUTHORIZATION })
        val resources = prepared.affectedResources.toSet() + additionalResources
        if (RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, principal.actorId) !in resources ||
            RecoveryAdmissionPolicy.evaluate(recovery, scope.branchId, resources,
                prepared.recoveryHoldBarrierKinds.toSet()) != ResourceAdmissionDecision.ADMITTED) return@LocalApprovalCommandPreparation null
        if (prepared.admissionRestriction != null) return@LocalApprovalCommandPreparation null
        PreparedLocalApprovalCommand(requirement, resources, actionApprovalBusinessAttribution(request.command, prepared))
    }

    /** Both independent trusted declarations are necessary, neither supplies authority. */
    private fun actionApprovalBusinessAttribution(command: CommandKey,
        prepared: PreparedMutation): ActionApprovalBusinessAttributionSupport =
        if (prepared.actionApprovalBusinessAttribution == ActionApprovalBusinessAttributionSupport.PRESERVE_ORIGINAL_REQUESTER &&
            historyAttributionSupport[command] == ActionApprovalBusinessAttributionSupport.PRESERVE_ORIGINAL_REQUESTER)
            ActionApprovalBusinessAttributionSupport.PRESERVE_ORIGINAL_REQUESTER
        else ActionApprovalBusinessAttributionSupport.UNSUPPORTED

    /**
     * Current authorized domain read, not raw DAO exposure or an outcome lookup. Like execute,
     * this entry must be called from the supervised request ingress outside any inherited Room
     * loan. Revocation/expiry/policy checks and all projection reads share its serialized writer
     * transaction, including the typed finalizer. No data is released from an inner transaction.
     */
    public suspend fun read(principal: AuthenticatedPrincipal?, request: ReadRequest): ReadReply =
        readInternal(principal, request, null)

    internal suspend fun readRpcBusiness(principal: AuthenticatedPrincipal?, request: ReadRequest,
        audience: RoomLocalDisplayRpcAudience): ReadReply {
        val access = audience.access as? LocalMasterRpcBusinessAccess.Read
            ?: return ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
        if (access.profile != LocalMasterRpcWorkflowProfile.KITCHEN || access.request.query != request.query ||
            access.request.payload != request.payload) return ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
        return readInternal(principal, request, audience)
    }

    private suspend fun readInternal(principal: AuthenticatedPrincipal?, request: ReadRequest,
        rpcAudience: RoomLocalDisplayRpcAudience?): ReadReply {
        fun refused(reason: ReadFailure): ReadReply {
            if (reason == ReadFailure.STORAGE_UNCERTAIN) physicalService.deny()
            return ReadReply.Unavailable(reason)
        }
        if (principal !is LocalEmployeePrincipal) return refused(ReadFailure.AUTHORIZATION_DENIED)
        if (principal.scope != scope) return refused(ReadFailure.WRONG_SCOPE)
        val handler = readHandlers[request.query] ?: return refused(ReadFailure.UNKNOWN_QUERY)
        val normalized = try {
            handler.normalize(request.payload)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            return refused(ReadFailure.MALFORMED_REQUEST)
        }
        if (rpcAudience != null && (!LocalMasterKitchenRpcKeysV1.accepts(request.query) ||
            !handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.KITCHEN, normalized)))
            return refused(ReadFailure.AUTHORIZATION_DENIED)
        val digest = queryDigest(scope, principal.actorId, request.query, normalized)
        val displayRead = (handler as? MasterLocalDisplayReadHandler)?.takeIf { it.requiresDelivery(normalized) }
        return try {
            val result = database.runtimeWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    database.runtimeHistoryOwner?.frameFor(connection)?.enterTransaction(currentCoroutineContext())
                    val view = transactionView(connection, principal)
                    try {
                        rpcAudience?.let { view.bindLocalDisplayRpcAudience(it) }
                        val reply = run queryBody@ {
                            val policy = AuthorizationPolicy(CurrentAuthorizationProvider { authorizationSource.current(it, view) })
                            if (policy.currentIdentityDenial(principal) != null) {
                                return@queryBody refused(ReadFailure.AUTHORIZATION_DENIED)
                            }
                            val branch = dao.branch(scope.storageKey())
                                ?: return@queryBody refused(ReadFailure.WRITER_NOT_QUALIFIED)
                            val observed = observe(branch, recoveryState(branch))
                            if (!branch.writable || observed.authority != authority ||
                                runtimeVerifier.writer(observed) != RuntimeVerification.VERIFIED) {
                                return@queryBody refused(ReadFailure.WRITER_NOT_QUALIFIED)
                            }
                            val prepared = handler.prepare(normalized, view,
                                ReadContext(scope, principal.actorId, request.query, digest))
                            val requirements = prepared.requirements.toList()
                            check(requirements.size in 1..256) { "Query must declare bounded authorization requirements" }
                            val grants = requirements.map { requirement ->
                                check(requirement.scope == scope) { "Query returned cross-scope requirement" }
                                requirement.binding?.let {
                                    check(it.operationId == null && it.payloadDigest == digest) {
                                        "Query authorization is not bound to normalized input"
                                    }
                                }
                                when (val authorization = policy.authorize(principal, requirement)) {
                                    is AuthorizationDecision.Allowed -> authorization.authorization
                                    is AuthorizationDecision.Denied -> return@queryBody refused(ReadFailure.AUTHORIZATION_DENIED)
                                }
                            }
                            val now = clock.now()
                            requireEvidenceV1Time(now)
                            view.armLocalDisplaySupport()
                            ReadReply.Data(prepared.project(grants, now), Revision(branch.revision), now)
                        }
                        view.prepareNormalReturn()
                        view.finishNow()
                        reply
                    } catch (_: MasterLocalDisplayDeliveryUnavailable) {
                        rollback(refused(ReadFailure.WRITER_NOT_QUALIFIED))
                    } catch (_: RoomCurrentLocalAuthorizationDenied) {
                        // Denial is data across a successful rollback and outer loan return.
                        // Never infer rollback success from the type of an escaped exception.
                        rollback(refused(ReadFailure.AUTHORIZATION_DENIED))
                    } catch (_: MasterReadAuthorizationDenied) {
                        // A trusted projection may impose a stricter owner restriction.
                        // Successful rollback, not the exception alone, qualifies denial.
                        rollback(refused(ReadFailure.AUTHORIZATION_DENIED))
                    } catch (_: MasterReadInvalidSelection) {
                        rollback(refused(ReadFailure.MALFORMED_REQUEST))
                    } finally { view.close() }
                }
            }
            currentCoroutineContext().ensureActive()
            if (result is ReadReply.Data && displayRead != null &&
                localDisplayDelivery.value?.let { it.reader === displayRead && it.current() } != true)
                refused(ReadFailure.WRITER_NOT_QUALIFIED)
            else if (result is ReadReply.Data && rpcAudience != null && !rpcDisplayCurrent(rpcAudience))
                refused(ReadFailure.AUTHORIZATION_DENIED)
            else result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            refused(ReadFailure.STORAGE_UNCERTAIN)
        }
    }

    /**
     * Direct trusted callers retain ordinary SQL execution and authorized positive recovery.
     * NEW physical work cannot acquire native handles here: this caller may own an outer Room
     * loan even when it is not in a transaction. Use the actual supervised endpoint for that work.
     */
    public suspend fun execute(principal: AuthenticatedPrincipal?, request: MutationRequest): MutationReply =
        executeInternal(principal, request, null)

    internal suspend fun executeFromOwnedIngress(principal: AuthenticatedPrincipal?, request: MutationRequest,
                                                 ingress: PhysicalRecordingOwnedIngress): MutationReply =
        executeInternal(principal, request, ingress)

    /** Only this dedicated actual endpoint path may attach a fresh Start offer to its request. */
    internal suspend fun startPhysicalFromOwnedIngress(principal: AuthenticatedPrincipal?, request: MutationRequest,
        lifetime: PhysicalInteractiveRequestLifetime, ingress: PhysicalRecordingOwnedIngress): PhysicalFreshStartReply {
        var transferred = false
        var start: PhysicalRecordingCommittedStart? = null
        fun held(reason: DeferredReason): PhysicalFreshStartReply =
            PhysicalFreshStartReply(MutationReply.Deferred(request.operationId, reason), null)
        try {
            val registry = physicalInstructionRegistry ?: return held(DeferredReason.PHYSICAL_RECORDING_HELD)
            if (principal !is LocalEmployeePrincipal || principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE ||
                principal.scope != scope) return held(DeferredReason.AUTHORIZATION_DENIED)
            if (lifetime.registry !== registry || lifetime.store !== this || lifetime.endpoint !== ingress.endpointOwner ||
                !physicalWorkerCurrent(ingress, currentCoroutineContext()) || !physicalService.current() ||
                physicalRecordingGate !is PhysicalRecordingInteractiveGate) return held(DeferredReason.PHYSICAL_RECORDING_HELD)
            val handler = handlers[request.command] ?: return held(DeferredReason.UNKNOWN_COMMAND)
            if (physicalAuthorizers[request.command] == null || request.actionApprovalId != null)
                return held(DeferredReason.PHYSICAL_RECORDING_HELD)
            val normalized = try { handler.normalize(request.payload) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { return held(DeferredReason.MALFORMED_REQUEST) }
            if (handler.physicalRecordingAction(normalized)?.isStart() != true || !registry.beginStart(lifetime, principal, request))
                return held(DeferredReason.PHYSICAL_RECORDING_HELD)
            val result = executeInternal(principal, request, ingress, lifetime)
            start = (result as? MutationReply.Recorded)?.physicalStart
            currentCoroutineContext().ensureActive()
            val exactStart = start
            val offer = if (exactStart != null && physicalWorkerCurrent(ingress, currentCoroutineContext()) && exactStart.deliveryCurrent())
                registry.offer(lifetime, exactStart) else null
            transferred = offer != null
            // Replay/deferred/Held may retain genuine historical metadata, never an offer.
            return PhysicalFreshStartReply(result, offer)
        } finally {
            if (!transferred) { lifetime.deny(); start?.denyDelivery() }
        }
    }

    /** Exact installed native call ownership, not a caller lease or a public accepting ticket. */
    private class InteractiveNativeTicket(
        val binding: PhysicalInstructionNativeBinding,
        val lease: PhysicalNativeInstructionLease,
    ) {
        init { check(lease.binding === binding && lease.canonicalRequirement == binding.canonicalRequirement) }
    }

    internal suspend fun publishPhysicalFromOwnedIngress(principal: AuthenticatedPrincipal?, offer: PhysicalInstructionOffer,
        lifetime: PhysicalInteractiveRequestLifetime, ingress: PhysicalRecordingOwnedIngress): PhysicalInstructionReply {
        var transferred = false
        try {
            val registry = physicalInstructionRegistry ?: return PhysicalInstructionReply.Held
            val gate = physicalRecordingGate as? PhysicalRecordingInteractiveGate ?: return PhysicalInstructionReply.Held
            val start = offer.committedStart
            val origin = start.interactiveOrigin
            if (principal !is LocalEmployeePrincipal || !origin.matches(principal) || origin.storeOwner !== this ||
                origin.storeIncarnationId != physicalStoreIncarnation || origin.serviceIncarnationId != physicalServiceIncarnation ||
                start.authority != authority || lifetime.registry !== registry || lifetime.store !== this ||
                lifetime.endpoint !== ingress.endpointOwner || !physicalService.current() || !start.deliveryCurrent())
                return PhysicalInstructionReply.Held
            if (!physicalWorkerCurrent(ingress, currentCoroutineContext())) return PhysicalInstructionReply.StorageUncertain
            val handler = handlers[start.command] ?: return PhysicalInstructionReply.Held
            val authorizer = physicalAuthorizers[start.command] ?: return PhysicalInstructionReply.Held
            if (handler !== origin.handlerOwner || authorizer.domainProfile != start.domainProfile) return PhysicalInstructionReply.Held
            val binding = registry.claimPreparation(offer, lifetime, ingress, gate) ?: return PhysicalInstructionReply.Held
            var nativeLease: PhysicalNativeInstructionLease? = null
            var cleanupUncertain = false
            var authorized = false
            var authorizingResult: InteractiveAuthorization = InteractiveAuthorization.HELD
            try {
                val prepared = gate.prepareInstruction(binding)
                // Immediately take an actually returned SAME-binding lease, before cancellation
                // may throw. A provider retains responsibility for delivery that never resumes.
                if (prepared is PhysicalNativeInstructionPreparation.Prepared) {
                    check(prepared.lease.binding === binding)
                    nativeLease = prepared.lease
                }
                currentCoroutineContext().ensureActive()
                authorizingResult = when (prepared) {
                    PhysicalNativeInstructionPreparation.Held -> InteractiveAuthorization.HELD
                    PhysicalNativeInstructionPreparation.StorageUncertain -> InteractiveAuthorization.STORAGE_UNCERTAIN
                    is PhysicalNativeInstructionPreparation.Prepared -> {
                        if (!physicalWorkerCurrent(ingress, currentCoroutineContext())) InteractiveAuthorization.STORAGE_UNCERTAIN
                        else if (binding.denial.denied || !start.deliveryCurrent()) InteractiveAuthorization.HELD
                        else authorizeRetainedPhysical(principal, handler, authorizer, registry, InteractiveNativeTicket(binding, prepared.lease))
                    }
                }
                authorized = authorizingResult == InteractiveAuthorization.AUTHORIZED &&
                    physicalWorkerCurrent(ingress, currentCoroutineContext()) && !binding.denial.denied
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                authorizingResult = InteractiveAuthorization.STORAGE_UNCERTAIN
            } finally {
                // Distinct instruction/event custody was pre-reserved by prepareInstruction.
                // Normal release frees ONLY temporary leases, not the independent continuation.
                if (!authorized) lifetime.deny()
                nativeLease?.let { lease ->
                    try {
                        if (!ingress.sameWorkerContext(currentCoroutineContext())) cleanupUncertain = true
                        else cleanupUncertain = withContext(NonCancellable) { gate.releaseInstruction(lease) } !=
                            PhysicalRecordingNativeRelease.RELEASED
                    } catch (_: Exception) { cleanupUncertain = true }
                }
                if (cleanupUncertain || authorizingResult == InteractiveAuthorization.STORAGE_UNCERTAIN) {
                    lifetime.deny()
                    physicalService.deny()
                }
            }
            if (cleanupUncertain || authorizingResult == InteractiveAuthorization.STORAGE_UNCERTAIN)
                return PhysicalInstructionReply.StorageUncertain
            currentCoroutineContext().ensureActive()
            if (!authorized || binding.denial.denied || !start.deliveryCurrent()) return PhysicalInstructionReply.Held
            if (!physicalWorkerCurrent(ingress, currentCoroutineContext())) {
                physicalService.deny()
                return PhysicalInstructionReply.StorageUncertain
            }
            // NO native or other open callback after this separate owned post-loan clock phase.
            val publication = registry.publishAfterNormalReturn(binding, currentCoroutineContext()) ?: return PhysicalInstructionReply.Held
            transferred = true
            return PhysicalInstructionReply.Published(publication)
        } finally {
            if (!transferred) { offer.deny(); lifetime.deny() }
        }
    }

    private enum class InteractiveAuthorization { AUTHORIZED, HELD, STORAGE_UNCERTAIN }

    /** Read-only current authorization under the SAME real writer, not Start prepare/evaluate. */
    private suspend fun authorizeRetainedPhysical(principal: LocalEmployeePrincipal, handler: MasterCommandHandler,
        authorizer: MasterPhysicalInteractiveAuthorizer, registry: PhysicalInstructionPublicationRegistry,
        ticket: InteractiveNativeTicket): InteractiveAuthorization = try {
        val binding = ticket.binding
        val start = binding.committedStart
        val origin = binding.origin
        val result = database.runtimeWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    database.runtimeHistoryOwner?.frameFor(connection)?.enterTransaction(currentCoroutineContext())
                val view = transactionView(connection, principal, PhysicalInstructionReadTrace())
                try {
                    val prepared = run authorize@ {
                        if (!origin.matches(principal) || binding.denial.denied) return@authorize false
                        val policy = AuthorizationPolicy(CurrentAuthorizationProvider { authorizationSource.current(it, view) })
                        if (policy.currentIdentityDenial(principal) != null) return@authorize false
                        val branch = dao.branch(scope.storageKey()) ?: return@authorize false
                        val recovery = recoveryState(branch)
                        val observed = observe(branch, recovery)
                        if (!branch.writable || observed.authority != authority || start.authority != authority ||
                            runtimeVerifier.writer(observed) != RuntimeVerification.VERIFIED) return@authorize false
                        val retained = view.retainedCommand(start.operationId) ?: return@authorize false
                        if (!retained.committed || retained.scope != scope || retained.operationId != start.operationId ||
                            retained.originalActorId != start.originalActorId || retained.authenticationId != origin.authenticationId ||
                            retained.command != start.command || retained.normalized != origin.normalized ||
                            retained.semanticDigest != start.semanticDigest || retained.resultHash != start.resultDigest ||
                            retained.recordedAt != start.recordedAt || retained.sequence != start.sequence ||
                            retained.journalHash != start.journalHash || retained.assignmentId != authority.assignmentId ||
                            retained.authorityEpoch != authority.epoch) return@authorize false
                        val action = handler.physicalRecordingAction(origin.normalized)
                        if (handler !== origin.handlerOwner || authorizer !== physicalAuthorizers[start.command] ||
                            authorizer.command != start.command || authorizer.domainProfile != start.domainProfile ||
                            action == null || !action.isStart() || action.startKind() != start.startKind) return@authorize false
                        val now = clock.now()
                        requireEvidenceV1Time(now)
                        val context = CommandContext(scope, start.originalActorId, start.operationId, start.command, start.semanticDigest,
                            physicalParticipants = PhysicalRecordingParticipants(authority, origin.deviceId, start.originalActorId))
                        val requirement = authorizer.prepareRetainedStartAuthorization(view, PhysicalRetainedStartContext(context, start, now))
                            ?: return@authorize false
                        val authorization = requirement.authorization
                        val bound = authorization.binding
                        val originalDomain = NativeOriginalDomain.entries.singleOrNull { domain ->
                            domain.command == start.command && domain.domainProfile == start.domainProfile && domain.startKind == start.startKind
                        } ?: return@authorize false
                        val expectedAction = when (originalDomain) {
                            NativeOriginalDomain.RETAIL_PAYMENT_V3, NativeOriginalDomain.FOOD_PAYMENT_V1 -> SecurityAction.COLLECTION_ATTEST
                            NativeOriginalDomain.RETAIL_REFUND_V3, NativeOriginalDomain.FOOD_REFUND_V1 -> SecurityAction.REFUND_RECORD
                            NativeOriginalDomain.SUPPLIER_CASH_V1 -> SecurityAction.SUPPLIER_CASH_SETTLE
                        }
                        if (requirement.canonicalRequirement != start.requirementBytes || authorization.scope != scope ||
                            bound == null || bound.operationId != start.operationId || bound.payloadDigest != start.semanticDigest ||
                            authorization.permission.verb != PermissionVerb.PERFORM || authorization.permission.action != expectedAction ||
                            (authorization.owner as? OwnerConstraint.OwnerPrivate)?.ownerId != start.originalActorId) return@authorize false
                        when (val decision = policy.authorize(principal, authorization)) {
                            is AuthorizationDecision.Denied -> return@authorize false
                            is AuthorizationDecision.Allowed -> check(decision.authorization.isBoundTo(authorization, principal.actorId))
                        }
                        val resources = requirement.affectedResources
                        if (RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, principal.actorId) !in resources ||
                            RecoveryAdmissionPolicy.evaluate(recovery, scope.branchId, resources, requirement.recoveryHoldBarrierKinds) !=
                            ResourceAdmissionDecision.ADMITTED) return@authorize false
                        view.armLocalDisplaySupport()
                        view.attachPhysicalAuthorization(registry, binding, resources, requirement.businessDeadlineExclusive)
                        true
                    }
                    view.prepareNormalReturn()
                    view.finishNow() // Last callback is the SAME protected environment observation.
                    prepared
                } catch (_: MasterLocalDisplayDeliveryUnavailable) {
                    rollback(false)
                } catch (_: PhysicalRecordingFinalDenied) {
                    rollback(false)
                } catch (_: RoomCurrentLocalAuthorizationDenied) {
                    rollback(false)
                } finally {
                    view.close()
                }
            }
        }
        currentCoroutineContext().ensureActive()
        if (result) InteractiveAuthorization.AUTHORIZED else InteractiveAuthorization.HELD
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { InteractiveAuthorization.STORAGE_UNCERTAIN }

    /**
     * End-only hook for EVERY actual protected business write, before approval consumption or
     * persist. The separately owned native-original importer must call this from its own SAME
     * writer after enforcing its closed effect inventory; this hook grants it no importer rights.
     * Branch authority/recovery/custody replacement must instead retire/deny the whole service.
     */
    internal fun invalidatePhysicalBeforeWrite(operationId: OperationId, aggregates: Set<AggregateKey>,
        claims: Set<ClaimKey>, preparedResources: Set<RecoveryResourceKey>) {
        physicalInstructionRegistry?.denyBusinessWrite(scope, operationId, aggregates, claims, preparedResources)
    }

    internal suspend fun executeRpcBusiness(principal: AuthenticatedPrincipal?, request: MutationRequest,
        audience: RoomLocalDisplayRpcAudience, ingress: PhysicalRecordingOwnedIngress? = null): MutationReply {
        val access = audience.access as? LocalMasterRpcBusinessAccess.Execute
            ?: return MutationReply.Deferred(request.operationId, DeferredReason.AUTHORIZATION_DENIED)
        if (access.profile != LocalMasterRpcWorkflowProfile.KITCHEN || access.request.operationId != request.operationId ||
            access.request.command != request.command || access.request.payload != request.payload ||
            request.actionApprovalId != null) return MutationReply.Deferred(request.operationId, DeferredReason.AUTHORIZATION_DENIED)
        val result = executeInternal(principal, request, ingress, rpcAudience = audience)
        return if (result is MutationReply.Recorded && !rpcDisplayCurrent(audience))
            MutationReply.Deferred(request.operationId, DeferredReason.AUTHORIZATION_DENIED) else result
    }

    private suspend fun executeInternal(principal: AuthenticatedPrincipal?, request: MutationRequest,
                                         ingress: PhysicalRecordingOwnedIngress?,
                                         interactiveLifetime: PhysicalInteractiveRequestLifetime? = null,
                                         rpcAudience: RoomLocalDisplayRpcAudience? = null): MutationReply {
        fun deferred(reason: DeferredReason): MutationReply {
            // Withdraw physical continuations immediately, before cleanup or endpoint delivery.
            // Endpoint supervision separately retires the actual database/service on uncertainty.
            if (reason == DeferredReason.STORAGE_UNCERTAIN) physicalService.deny()
            return MutationReply.Deferred(request.operationId, reason)
        }
        if (principal !is LocalEmployeePrincipal) return deferred(DeferredReason.AUTHORIZATION_DENIED)
        if (principal.scope != scope) return deferred(DeferredReason.WRONG_SCOPE)
        val handler = handlers[request.command] ?: return deferred(DeferredReason.UNKNOWN_COMMAND)
        val normalized = try {
            handler.normalize(request.payload)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            return deferred(DeferredReason.MALFORMED_REQUEST)
        }
        if (rpcAudience != null && (!LocalMasterKitchenRpcKeysV1.accepts(request.command) ||
            !handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.KITCHEN, normalized)))
            return deferred(DeferredReason.AUTHORIZATION_DENIED)
        val digest = semanticDigest(scope, principal.actorId, request.command, normalized)
        val identity = OperationIdentity(scope.branchId, request.operationId, principal.actorId, 1, digest.value)
        val physicalAction = try { handler.physicalRecordingAction(normalized) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalArgumentException) { return deferred(DeferredReason.MALFORMED_REQUEST) }
        catch (_: Exception) { return deferred(DeferredReason.STORAGE_UNCERTAIN) }

        if (physicalAction == null) return publish(executeTransaction(principal, request, handler, normalized,
            digest, identity, null, false, null, rpcAudience), request.operationId)

        if (rpcAudience != null) return deferred(DeferredReason.AUTHORIZATION_DENIED)

        // Native-free, real domain preflight. Positive operation+journal recovery occurs first
        // and remains available even when no endpoint/native owner exists or capacity is full.
        val first = executeTransaction(principal, request, handler, normalized, digest, identity,
            physicalAction, true, null)
        if (first !is KernelReply.PhysicalAdmission) return publish(first, request.operationId)
        if (ingress == null || !physicalWorkerCurrent(ingress, currentCoroutineContext()) || !physicalService.current()) {
            return deferred(DeferredReason.PHYSICAL_RECORDING_HELD)
        }
        val binding = PhysicalRecordingInvocationBinding(this, handler, principal, physicalStoreIncarnation,
            physicalServiceIncarnation, DomainId.random(), authority, request.operationId, request.command,
            digest, normalized, first.admission, PhysicalRecordingNativeLatch(physicalService), interactiveLifetime)
        var issuedStart: PhysicalRecordingCommittedStart? = null
        var replyForTransfer: MutationReply.Recorded? = null
        var startEligibleForTransfer = false
        var startTransferred = false
        try {
            var nativeLease: PhysicalRecordingNativeLease? = null
            var cleanupUncertain = false
            val result = try {
                val acquired = physicalRecordingGate.acquire(binding)
                // Claim an actually returned SAME-binding lease before any cancellation check can
                // throw. A provider owns handles whose delivery never resumes this continuation.
                if (acquired is PhysicalRecordingAcquire.Ready) {
                    check(acquired.lease.binding === binding) { "Physical acquisition owner mismatch" }
                    nativeLease = acquired.lease
                }
                currentCoroutineContext().ensureActive()
                when (acquired) {
                    PhysicalRecordingAcquire.Held -> deferred(DeferredReason.PHYSICAL_RECORDING_HELD)
                    PhysicalRecordingAcquire.Busy -> deferred(DeferredReason.SERVICE_BUSY)
                    PhysicalRecordingAcquire.StorageUncertain -> deferred(DeferredReason.STORAGE_UNCERTAIN)
                    is PhysicalRecordingAcquire.Ready -> {
                        // Context uncertainty is not an ordinary withdrawn capacity. Never enter
                        // another Room/native phase from an unproved inherited connection context.
                        if (!physicalWorkerCurrent(ingress, currentCoroutineContext())) {
                            deferred(DeferredReason.STORAGE_UNCERTAIN)
                        } else {
                            val ticket = PhysicalRecordingTicket(binding, acquired.lease)
                            // The actual writer rechecks positive history BEFORE ticket/latch checks.
                            // A concurrent winner yields nonarming replay even after withdrawal and
                            // keeps surplus allocation; no native/current latch precheck masks it.
                            val stored = executeTransaction(principal, request, handler, normalized, digest, identity,
                                physicalAction, false, ticket)
                            if (stored !is KernelReply.Recorded || stored.replayed ||
                                stored.physicalOutcome !is PhysicalRecordingOutcome.Start) {
                                publish(stored, request.operationId)
                            } else if (!physicalWorkerCurrent(ingress, currentCoroutineContext())) {
                                deferred(DeferredReason.STORAGE_UNCERTAIN)
                            } else if (!binding.latch.current()) {
                                deferred(DeferredReason.PHYSICAL_RECORDING_HELD)
                            } else {
                                // Real native validation occurs AFTER Room has returned its actual
                                // outer COMMIT. SQL committed + no certificate stays original recovery.
                                when (physicalRecordingGate.recheck(acquired.lease)) {
                                    PhysicalRecordingNativeCheck.HELD -> deferred(DeferredReason.PHYSICAL_RECORDING_HELD)
                                    PhysicalRecordingNativeCheck.STORAGE_UNCERTAIN -> deferred(DeferredReason.STORAGE_UNCERTAIN)
                                    PhysicalRecordingNativeCheck.VERIFIED -> {
                                        currentCoroutineContext().ensureActive()
                                        if (!physicalWorkerCurrent(ingress, currentCoroutineContext())) {
                                            deferred(DeferredReason.STORAGE_UNCERTAIN)
                                        } else if (!binding.latch.current()) {
                                            deferred(DeferredReason.PHYSICAL_RECORDING_HELD)
                                        } else {
                                            val start = PhysicalRecordingCommittedStart(binding, stored.physicalOutcome.kind,
                                                JournalSequence(stored.row.sequence), checkNotNull(stored.journalHash),
                                                PayloadDigest(stored.row.resultHash), UtcInstant.parseCanonical(stored.row.committedAt),
                                                acquired.lease.sourceChanges)
                                            // Retain ownership BEFORE passing this object to open/suspending native code.
                                            issuedStart = start
                                            when (physicalRecordingGate.persistStart(acquired.lease, start)) {
                                                PhysicalRecordingNativeCheck.HELD -> deferred(DeferredReason.PHYSICAL_RECORDING_HELD)
                                                PhysicalRecordingNativeCheck.STORAGE_UNCERTAIN -> deferred(DeferredReason.STORAGE_UNCERTAIN)
                                                PhysicalRecordingNativeCheck.VERIFIED -> {
                                                    currentCoroutineContext().ensureActive()
                                                    if (!physicalWorkerCurrent(ingress, currentCoroutineContext()))
                                                        deferred(DeferredReason.STORAGE_UNCERTAIN)
                                                    else if (binding.latch.current())
                                                        recorded(stored.row, false, start).also { replyForTransfer = it }
                                                    else deferred(DeferredReason.PHYSICAL_RECORDING_HELD)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                // Accepted endpoint work is independent of caller cancellation. A service/native
                // cancellation still keeps original evidence and never makes a fresh action permit.
                throw cancelled
            } catch (_: Exception) {
                deferred(DeferredReason.STORAGE_UNCERTAIN)
            } finally {
                // A selected response is only a transfer candidate. Capture actual current
                // worker and invocation eligibility BEFORE normal invocation withdrawal.
                // Cleanup may run in NonCancellable, but it must not keep an already
                // ineligible, untransferred certificate alive across open native release.
                startEligibleForTransfer = issuedStart != null &&
                    replyForTransfer?.physicalStart === issuedStart &&
                    physicalWorkerCurrent(ingress, currentCoroutineContext()) &&
                    binding.latch.current()
                if (!startEligibleForTransfer) issuedStart?.denyDelivery()
                binding.latch.deny()
                nativeLease?.let { lease ->
                    try {
                        // This derives only from the proven clean worker context, AFTER Room fully
                        // returned. The Job may be cancelled; exact context identity must remain.
                        // Otherwise retain native ownership for retirement instead of borrowing a
                        // potentially inherited Room context even for cleanup. Never erase files.
                        if (!ingress.sameWorkerContext(currentCoroutineContext())) {
                            cleanupUncertain = true
                        } else {
                            val released = withContext(NonCancellable) { physicalRecordingGate.release(lease) }
                            cleanupUncertain = released != PhysicalRecordingNativeRelease.RELEASED
                        }
                    } catch (_: Exception) { cleanupUncertain = true }
                }
                if (cleanupUncertain) {
                    issuedStart?.denyDelivery()
                    physicalService.deny()
                }
            }
            if (cleanupUncertain) return deferred(DeferredReason.STORAGE_UNCERTAIN)
            // Cancellation after native persistence/release still belongs to this kernel, not
            // an endpoint response it never received. Successful normal release is not denial.
            currentCoroutineContext().ensureActive()
            // Preserve all original Deferred reasons (especially storage uncertainty).
            // A previously selected but withdrawn carrier cannot be transferred.
            if (issuedStart != null && result === replyForTransfer && !startEligibleForTransfer)
                return deferred(DeferredReason.PHYSICAL_RECORDING_HELD)
            startTransferred = startEligibleForTransfer && result === replyForTransfer &&
                (result as? MutationReply.Recorded)?.physicalStart === issuedStart
            return result
        } finally {
            // Covers every exception/Throwable/cancellation, cleanup failure, and ordinary
            // Deferred/replay path. Only the exact successful response carrier takes custody.
            if (!startTransferred) issuedStart?.denyDelivery()
        }
    }

    private fun publish(reply: KernelReply, operationId: OperationId): MutationReply = when (reply) {
        is KernelReply.Recorded -> recorded(reply.row, reply.replayed)
        is KernelReply.Deferred -> {
            if (reply.reason == DeferredReason.STORAGE_UNCERTAIN) physicalService.deny()
            MutationReply.Deferred(operationId, reply.reason)
        }
        KernelReply.Conflict -> MutationReply.IdentityConflict(operationId)
        is KernelReply.PhysicalAdmission -> error("Physical preflight is not a public result")
    }

    private suspend fun transactionView(connection: Transactor, principal: LocalEmployeePrincipal,
        physicalTrace: PhysicalInstructionReadTrace? = null): TransactionView =
        TransactionView.forWriter(scope, dao, database, connection, currentCoroutineContext(), principal, physicalTrace).also {
            it.bindSerialRegistrationClock(this, database, clock)
            stockProjection?.let { codec -> it.bindStock(this, database, codec) }
            localDisplayDelivery.value?.let { owner -> it.bindLocalDisplayDelivery(this, database, owner) }
        }

    /** Full v1 pair or v2 fragment stream, never an unchecked result-table lookup. */
    private suspend fun retainedOperation(view: TransactionView, operationId: OperationId): OperationRow? =
        when (val original = view.originalForKernel(operationId)) {
            null -> null
            is RetainedOriginalV2.Legacy -> originalOperation(original.evidence)
            is RetainedOriginalV2.Streamed -> with(original.evidence) {
                OperationRow(identity.scope.storageKey(), identity.producer.operationId.value,
                    identity.producer.originalActorId.value, identity.producer.command.name,
                    identity.producer.command.version, identity.producer.semanticDigest.value, "committed",
                    result.copyBytes(), identity.resultHash.value, identity.sequence.value, identity.authorityEpoch.value,
                    identity.recordedAt.toString(), identity.authenticationId.value, identity.policyRevision.value)
            }
        }

    /** No secondary transaction, unbounded business list, partial prefix or physical bypass. */
    private suspend fun executeStreamed(handler: MasterCommandHandler, prepared: PreparedStreamingMutation,
        view: TransactionView, branch: BranchRow, request: MutationRequest, context: CommandContext,
        normalized: StoredBytes, principal: LocalEmployeePrincipal, authorization: AuthorizedAction,
        approval: TransactionApprovalPermit?, recovery: RecoveryAdmissionState, now: UtcInstant): KernelReply {
        val history = runtimeHistoryOwner?.activeFrameForStore(this)
            ?: return KernelReply.Deferred(DeferredReason.WRITER_NOT_QUALIFIED)
        val streamingHandler = handler as? MasterStreamingCommandHandler ?: stockSqlUnavailableV2()
        val guard = StreamedPlanGuard(this, streamingHandler, prepared, context, normalized, authorization, now,
            recovery, physicalKinds, physicalClaims, approval?.additionalResources.orEmpty())
        guard.requireNonphysical()
        reportRuntimeHistoryStage(RuntimeHistoryDiagnosticStage.STREAMED_SPOOL_CREATE_ENTER)
        val spool = TransactionPlanSpool.create(view.stockRelationalAccess(), context)
        reportRuntimeHistoryStage(RuntimeHistoryDiagnosticStage.STREAMED_SPOOL_CREATED)
        val proposed = prepared.propose(authorization, now, spool.sink)
        reportRuntimeHistoryStage(RuntimeHistoryDiagnosticStage.STREAMED_PROPOSAL_RETURNED)
        val decision = if (proposed is StreamedMutationDecision.Apply && proposed.mustCommitBefore?.let {
                now >= it || clock.now() >= it } == true)
            StreamedMutationDecision.Reject("precondition.expired", StoredBytes("precondition.expired".encodeToByteArray()))
        else proposed
        return when (decision) {
            is StreamedMutationDecision.Reject -> {
                // Discard even a partially authored proposal before any terminal recording.
                // In particular it must not consume manager authority.
                spool.discardByOwner()
                nativeStreamingRejectionAdmission(context.key, prepared, decision)?.let {
                    // A discarded proposal must not append a refusal that the genuine native
                    // history cannot qualify on reopen. Its original remains nonrecording.
                    return KernelReply.Deferred(it)
                }
                invalidatePhysicalBeforeWrite(context.operationId, emptySet(), emptySet(), guard.resources)
                history.captureCommand(request, principal, context.semanticDigest, guard.resources, false, prepared.requirement, authorization, null,
                    foodCheckScalarRefusal = request.command == StockStreamingCommandsV2.FOOD)
                val committed = persist(branch, request, normalized, context.semanticDigest, principal,
                    authorization, context, prepared.requirement, ProposedOutcome.Reject(decision.code, decision.result), now)
                fault.at(StoreFaultPoint.BEFORE_COMMIT)
                KernelReply.Recorded(committed.first, false, journalHash = committed.second)
            }
            is StreamedMutationDecision.Apply -> {
                spool.seal(decision, guard)
                reportRuntimeHistoryStage(RuntimeHistoryDiagnosticStage.STREAMED_SEAL_RETURNED)
                // Complete actual footprint, including resources discovered during streaming.
                // The native-format limit is explicit; overflow rolls back, never truncates.
                val resources = linkedSetOf<RecoveryResourceKey>()
                spool.frames(StreamedSectionV2.RESOURCES) {
                    stockSqlRequireV2(resources.size < com.ukkera.pos.security.SelfOwnedNativeHistoryWireV2.MAX_RESOURCES)
                    stockSqlRequireV2(resources.add((it as RetainedCommandFrameV2.Resource).value))
                }
                val sources = spool.summary(StreamedSectionV2.SOURCES)
                val effects = spool.summary(StreamedSectionV2.EFFECTS)
                spool.invalidateBeforeWrite()
                reportRuntimeHistoryStage(RuntimeHistoryDiagnosticStage.STREAMED_INVALIDATION_RETURNED)
                if (approval != null && !approvalSource.consume(approval, view)) currentLocalAuthorizationDenied()
                history.captureCommand(request, principal, context.semanticDigest, resources, true, prepared.requirement, authorization, decision.mustCommitBefore)
                history.recordDependency(listOf("stock.stream/v2", sources.count.toString(), sources.bytes.toString(),
                    sources.commitment.value, effects.count.toString(), effects.bytes.toString(), effects.commitment.value))
                reportRuntimeHistoryStage(RuntimeHistoryDiagnosticStage.STREAMED_HISTORY_CAPTURED)
                val committed = persistStreamed(branch, request, context, principal, authorization, prepared.requirement, decision, spool, now)
                reportRuntimeHistoryStage(RuntimeHistoryDiagnosticStage.STREAMED_PERSIST_RETURNED)
                fault.at(StoreFaultPoint.BEFORE_COMMIT)
                KernelReply.Recorded(committed.first, false, decision.mustCommitBefore, journalHash = committed.second)
            }
        }
    }

    private suspend fun persistStreamed(branch: BranchRow, request: MutationRequest, context: CommandContext,
        principal: LocalEmployeePrincipal, authorization: AuthorizedAction, requirement: AuthorizationRequirement,
        outcome: StreamedMutationDecision.Apply,
        spool: TransactionPlanSpool, at: UtcInstant): Pair<OperationRow, PayloadDigest> {
        val policyRevision = authorization.policyRevision
        val business = context.businessAttribution(authorization, requirement)
        val next = JournalSequence(branch.sequence).next()
        val resultHash = sha256(outcome.result.bytes())
        val row = OperationRow(scope.storageKey(), request.operationId.value, principal.actorId.value,
            request.command.name, request.command.version, context.semanticDigest.value, "committed",
            outcome.result.copyBytes(), resultHash.value, next.value, authority.epoch.value, at.toString(),
            business.authenticationId.value, policyRevision.value)
        dao.insertOperation(row)
        fault.at(StoreFaultPoint.AFTER_RESULT)
        spool.writeBusinessRows()
        fault.at(StoreFaultPoint.AFTER_EFFECTS)
        val identity = RetainedCommandIdentityV2(scope, StreamedProducerIdentityV2(context.operationId, context.key,
            context.originalActorId, context.semanticDigest), authority.assignmentId, authority.epoch, next, at,
            business.authenticationId, policyRevision, resultHash, PayloadDigest(branch.journalHash))
        val root = spool.writeJournalFragments(identity)
        val rootHash = sha256(root.bytes())
        dao.insertJournal(JournalRow(scope.storageKey(), next.value, request.operationId.value, authority.assignmentId.value,
            authority.epoch.value, branch.journalHash, rootHash.value, root.copyBytes()))
        fault.at(StoreFaultPoint.AFTER_JOURNAL)
        dao.insertAudit(AuditRow(DomainId.random().value, scope.storageKey(), request.operationId.value, "command.committed",
            principal.actorId.value, principal.actorId.value, policyRevision.value, row.committedAt, context.semanticDigest.value))
        fault.at(StoreFaultPoint.AFTER_AUDIT)
        val upload = frame(listOf("ukkera.journal-upload/v1", scope.branchId.value, authority.epoch.value.toString(),
            next.value.toString(), rootHash.value).map(String::encodeToByteArray))
        dao.insertOutbox(listOf(OutboxRow(scope.storageKey(), request.operationId.value, 0, SYNC_JOB, upload, sha256(upload).value)))
        spool.frames(StreamedSectionV2.JOBS) { frame ->
            val job = frame as RetainedCommandFrameV2.Job
            check(job.ordinal in 1L..32L)
            dao.insertOutbox(listOf(OutboxRow(scope.storageKey(), request.operationId.value, job.ordinal.toInt(),
                job.value.kind, job.value.body.copyBytes(), sha256(job.value.body.bytes()).value)))
        }
        fault.at(StoreFaultPoint.AFTER_OUTBOX)
        check(dao.advance(scope.storageKey(), branch.revision, Revision(branch.revision).next().value, next.value, rootHash.value) == 1)
        return row to rootHash
    }

    private suspend fun executeTransaction(
        principal: LocalEmployeePrincipal, request: MutationRequest, handler: MasterCommandHandler,
        normalized: StoredBytes, digest: PayloadDigest, identity: OperationIdentity,
        physicalAction: PhysicalRecordingAction?, preflight: Boolean, ticket: PhysicalRecordingTicket?,
        rpcAudience: RoomLocalDisplayRpcAudience? = null,
    ): KernelReply {
        // Captured only inside the transaction; converted to a public result AFTER
        // useWriterConnection returns, including outer COMMIT/invalidation failures.
        return try {
            val stored = database.runtimeApprovedWriterConnection(request.actionApprovalId?.let { request.operationId }) { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    database.runtimeHistoryOwner?.frameFor(connection)?.enterTransaction(currentCoroutineContext())
                    val view = transactionView(connection, principal)
                    try {
                        rpcAudience?.let { view.bindLocalDisplayRpcAudience(it) }
                        val reply = run transactionBody@ {
                            val provider = CurrentAuthorizationProvider { authorizationSource.current(it, view) }
                            val actionPolicy = AuthorizationPolicy(provider)
                            if (actionPolicy.currentIdentityDenial(principal) != null) {
                                return@transactionBody KernelReply.Deferred(DeferredReason.AUTHORIZATION_DENIED)
                            }
                            val branch = dao.branch(scope.storageKey()) ?: return@transactionBody KernelReply.Deferred(DeferredReason.WRITER_NOT_QUALIFIED)
                            // Positive recovery is backed by the bounded original operation /
                            // journal pair, not an unchecked operation row or just resultHash.
                            // An orphan is uncertainty, never both-absent or a rejection.
                            val existing = retainedOperation(view, request.operationId)
                            if (existing != null) {
                                // Result recovery has different rights/purpose rules from a
                                // NEW typed action. Do not use it as generic authentication.
                                if (recoveryPolicy(provider).authorize(principal, scope, DomainId(existing.actorId), request.operationId) !is ResultRecoveryDecision.Allowed) {
                                    return@transactionBody KernelReply.Deferred(DeferredReason.AUTHORIZATION_DENIED)
                                }
                                if (rpcAudience != null) {
                                    val original = view.originalForKernel(request.operationId)
                                        ?: return@transactionBody KernelReply.Deferred(DeferredReason.AUTHORIZATION_DENIED)
                                    val requirements = kitchenRpcRetainedResultRequirements(original, request.operationId,
                                        principal.actorId, digest, normalized)
                                        ?: return@transactionBody KernelReply.Deferred(DeferredReason.AUTHORIZATION_DENIED)
                                    if (recoveryPolicy(provider).authorizeWithCurrentGrant(principal, scope, principal.actorId,
                                            request.operationId) !is ResultRecoveryDecision.Allowed ||
                                        requirements.any { actionPolicy.authorize(principal, it) !is AuthorizationDecision.Allowed })
                                        return@transactionBody KernelReply.Deferred(DeferredReason.AUTHORIZATION_DENIED)
                                }
                                if (!matches(existing, identity)) return@transactionBody KernelReply.Conflict
                                if (handler is MasterRetainedResultGuard &&
                                    !handler.permitsRetainedResult(view, StoredBytes(existing.result)))
                                    return@transactionBody KernelReply.Deferred(DeferredReason.CURRENT_SOURCE_UNAVAILABLE)
                                return@transactionBody KernelReply.Recorded(existing, true)
                            }
                            val recovery = recoveryState(branch)
                            val observed = observe(branch, recovery)
                            if (!branch.writable || observed.authority != authority || runtimeVerifier.writer(observed) != RuntimeVerification.VERIFIED) {
                                return@transactionBody KernelReply.Deferred(DeferredReason.WRITER_NOT_QUALIFIED)
                            }
                            if (runtimeVerifier.absentOperation(observed, identity) != RuntimeVerification.VERIFIED) {
                                return@transactionBody KernelReply.Deferred(DeferredReason.HISTORY_INCOMPLETE)
                            }
                            val reserved = view.reservedOperation(request.operationId)
                            val differentReservationActor = reserved != null && reserved.actorId != principal.actorId
                            if (reserved != null && !reserved.matchesRecorderRoute(principal.actorId, request.command,
                                    handler.reservedOperationBinding(normalized), physicalAction)) {
                                // Do not persist a denial under an ID already promised to a money
                                // observation. In particular, another command cannot burn the slot.
                                return@transactionBody KernelReply.Conflict
                            }
                            val commandContext = CommandContext(scope, principal.actorId, request.operationId, request.command,
                                digest, ticket?.transaction, physicalAction?.let { PhysicalRecordingParticipants(authority, principal.deviceId, principal.actorId) },
                                actionApprovalRequested = request.actionApprovalId != null)
                            view.bindStockCommand(commandContext)
                            val prepared = handler.prepare(normalized, view, commandContext)
                            require(prepared.requirement.scope == scope) { "Handler returned a cross-scope requirement" }
                            val bound = prepared.requirement.binding
                            require(bound != null && bound.operationId == request.operationId && bound.payloadDigest == digest) {
                                "Handler authorization is not bound to normalized operation identity"
                            }
                            if (differentReservationActor &&
                                !checkNotNull(reserved).matchesSupervisedResolutionRequirement(prepared.requirement)) {
                                // This is ONLY the original fixed-ID, journal-first resolution
                                // path. The installed domain handler must have read and verified
                                // its exact immutable original UNKNOWN and custody slot. Its
                                // requirement must retain that original owner, not substitute the
                                // current supervisor or downgrade to a generic cash/read action.
                                // Current scoped RESOLVE/ANY_OWNER/amount authorization still runs
                                // below in BOTH preflight and the actual execution transaction.
                                return@transactionBody KernelReply.Conflict
                            }
                            if (request.actionApprovalId != null && !prepared.allowsActionApproval) {
                                return@transactionBody KernelReply.Deferred(DeferredReason.AUTHORIZATION_DENIED)
                            }
                            val approval = request.actionApprovalId?.let { approvalId ->
                                when (val decision = approvalSource.prepare(principal, approvalId, prepared.requirement, view)) {
                                    is TransactionApprovalDecision.Ready -> decision.permit
                                    TransactionApprovalDecision.Denied -> return@transactionBody KernelReply.Deferred(DeferredReason.AUTHORIZATION_DENIED)
                                }
                            }
                            val authorization = approval?.authorization ?: when (val decision = actionPolicy.authorize(principal, prepared.requirement)) {
                                is AuthorizationDecision.Denied -> return@transactionBody KernelReply.Deferred(DeferredReason.AUTHORIZATION_DENIED)
                                is AuthorizationDecision.Allowed -> decision.authorization
                            }
                            check(authorization.isBoundTo(prepared.requirement, principal.actorId))
                            if (approval != null) {
                                if (!approval.bindBusinessAttribution(commandContext, prepared.requirement,
                                        actionApprovalBusinessAttribution(request.command, prepared)))
                                    return@transactionBody KernelReply.Deferred(DeferredReason.AUTHORIZATION_DENIED)
                            } else commandContext.bindCurrentBusinessAttribution(authorization, prepared.requirement, principal)
                            // A different employee/policy key is not the current actor's
                            // readmission. Adapters must additionally include every actual
                            // approver, policy, source and companion domain dependency.
                            val affectedResources = prepared.affectedResources.toSet() + approval?.additionalResources.orEmpty()
                            val holdBarrierKinds = prepared.recoveryHoldBarrierKinds.toSet()
                            if (RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, principal.actorId) !in affectedResources ||
                                RecoveryAdmissionPolicy.evaluate(recovery, scope.branchId, affectedResources, holdBarrierKinds) != ResourceAdmissionDecision.ADMITTED) {
                                return@transactionBody KernelReply.Deferred(DeferredReason.RESOURCE_RECONCILIATION_REQUIRED)
                            }
                            when (prepared.admissionRestriction) {
                                null -> Unit
                                PreparedMutationAdmissionRestriction.SOURCE_UNAVAILABLE ->
                                    return@transactionBody KernelReply.Deferred(DeferredReason.CURRENT_SOURCE_UNAVAILABLE)
                            }
                            view.armLocalDisplaySupport()
                            val now = clock.now()
                            requireEvidenceV1Time(now)
                            if (physicalAction != null) {
                                val admission = prepared.physicalRecordingAdmission(authorization, now)
                                if (admission == null || admission.action != physicalAction) {
                                    return@transactionBody KernelReply.Deferred(DeferredReason.PHYSICAL_RECORDING_HELD)
                                }
                                if (preflight) return@transactionBody KernelReply.PhysicalAdmission(admission)
                                if (ticket == null || !ticket.current(this@AtomicMasterStore, handler, principal, request, normalized, digest) ||
                                    ticket.binding.admission != admission) {
                                    return@transactionBody KernelReply.Deferred(DeferredReason.PHYSICAL_RECORDING_HELD)
                                }
                            } else check(!preflight && ticket == null)
                            if (prepared is PreparedStreamingMutation) {
                                check(physicalAction == null && reserved == null)
                                return@transactionBody executeStreamed(handler, prepared, view, branch, request,
                                    commandContext, normalized, principal, authorization, approval, recovery, now)
                            }
                            val proposal = prepared.evaluate(authorization, now)
                            val effective = when (proposal) {
                                is ProposedOutcome.Reject -> proposal
                                is ProposedOutcome.Apply -> validateProposal(handler, proposal, now, view) ?: proposal
                            }
                            val physicalOutcome = if (physicalAction != null) {
                                val interpreted = handler.physicalRecordingOutcome(normalized, effective, commandContext)
                                if (ticket == null || !ticket.validate(effective, interpreted, handler, physicalKinds, physicalClaims)) {
                                    return@transactionBody KernelReply.Deferred(DeferredReason.PHYSICAL_RECORDING_HELD)
                                }
                                interpreted
                            } else {
                                if (effective is ProposedOutcome.Apply && (effective.changes.any { it.key.kind in physicalKinds } ||
                                    effective.claims.any { it.key.namespace in physicalClaims })) {
                                    return@transactionBody KernelReply.Deferred(DeferredReason.PHYSICAL_RECORDING_HELD)
                                }
                                null
                            }
                            if (reserved != null && effective is ProposedOutcome.Reject) {
                                // A corrected same-ID observation remains possible. This response
                                // is neither a durable outcome nor permission to repeat collection.
                                return@transactionBody KernelReply.Deferred(DeferredReason.RESERVED_OPERATION_NOT_RECORDED)
                            }
                            // Actual keys (including inserts/absence and retained-operation
                            // presence) and prepared+approval resources, NOT a global write tick.
                            // This precedes even borrowed protected approval consumption.
                            invalidatePhysicalBeforeWrite(request.operationId,
                                (effective as? ProposedOutcome.Apply)?.changes.orEmpty().map { it.key }.toSet(),
                                (effective as? ProposedOutcome.Apply)?.claims.orEmpty().map { it.key }.toSet(), affectedResources)
                            if (effective is ProposedOutcome.Apply) {
                                for (change in effective.changes.filter { it.key.kind == ReservedOperations.KIND }) {
                                    check(change.expectedRevision == null) { "Future operation reservations are immutable" }
                                    val future = decodeReservation(change.key, change.schema, change.payload)
                                    check(future.actorId == principal.actorId && future.operationId != request.operationId &&
                                        future.command in handlers) { "Invalid future operation owner/command" }
                                    if (view.originalForKernel(future.operationId) != null ||
                                        runtimeVerifier.reservableOperation(observed, future.operationId, future.actorId) != RuntimeVerification.VERIFIED) {
                                        return@transactionBody KernelReply.Deferred(DeferredReason.HISTORY_INCOMPLETE)
                                    }
                                }
                                // No replay/reject/CAS/deferred path consumes authority. Consumption,
                                // business effects, result, audit and outbox share this SAME writer.
                                // A failed consumption escapes/rolls back, never leaves a partial use.
                                if (approval != null && !approvalSource.consume(approval, view)) {
                                    currentLocalAuthorizationDenied()
                                }
                            }
                            runtimeHistoryOwner?.frameFor(connection)?.captureCommand(request,principal,digest,affectedResources,
                                effective is ProposedOutcome.Apply,prepared.requirement,authorization,(effective as? ProposedOutcome.Apply)?.mustCommitBefore)
                            val committed = persist(branch, request, normalized, digest, principal, authorization, commandContext,
                                prepared.requirement, effective, now)
                            fault.at(StoreFaultPoint.BEFORE_COMMIT)
                            KernelReply.Recorded(committed.first, false, (effective as? ProposedOutcome.Apply)?.mustCommitBefore,
                                physicalOutcome, committed.second)
                        }
                        // EVERY normal body return, including replay/conflict/denial, goes
                        // through the typed finalizer. Nothing is validated while unwinding.
                        view.prepareNormalReturn()
                        val deadline = (reply as? KernelReply.Recorded)?.mustCommitBefore
                        val finalWall = if (deadline != null) clock.now() else null
                        view.finishNow()
                        // Preserve the original finalizer order. After the last synchronous
                        // monotonic sample, only captured/pure checks and frame data follow.
                        // The wall witness is not fsync/response completion before expiry.
                        if (reply is KernelReply.Recorded && !reply.replayed && reply.physicalOutcome != null) {
                            checkNotNull(ticket).finishNow()
                        }
                        if (deadline != null && (finalWall == null || finalWall >= deadline)) {
                            // Ordinary expiry is a typed rollback, not a fabricated durable
                            // rejection or a poisoned-pool signal. Room must return normally
                            // from rollback AND the outer loan before this Deferred can escape.
                            // An actual rollback/cleanup failure still reaches STORAGE_UNCERTAIN.
                            rollback(KernelReply.Deferred(DeferredReason.PRECONDITION_EXPIRED))
                        }
                        if (reply is KernelReply.Recorded && !reply.replayed && deadline != null) {
                            runtimeHistoryOwner?.let { owner -> checkNotNull(owner.frameFor(connection)).captureFinalDeadlineNow(
                                request.operationId, digest, deadline, checkNotNull(finalWall)) }
                        }
                        reply
                    } catch (_: MasterLocalDisplayDeliveryUnavailable) {
                        // Known source withdrawal rolls back tentative issue/task/outbox and
                        // approval work. A failed rollback/outer return remains uncertainty.
                        rollback(KernelReply.Deferred(DeferredReason.CURRENT_SOURCE_UNAVAILABLE))
                    } catch (_: PhysicalRecordingFinalDenied) {
                        // Typed known rollback only. Actual rollback/loan failure still maps to
                        // STORAGE_UNCERTAIN, and native preparation/evidence is retained outside.
                        rollback(KernelReply.Deferred(DeferredReason.PHYSICAL_RECORDING_HELD))
                    } catch (_: RoomCurrentLocalAuthorizationDenied) {
                        // Includes a final authorization withdrawal after tentative business
                        // writes/approval consumption. Only Room's NORMAL rollback(result)
                        // and outer-loan return may publish this ordinary denial. Any failed
                        // rollback/close/COMMIT remains STORAGE_UNCERTAIN below, regardless of
                        // its primary exception type or suppressed cleanup exceptions.
                        rollback(KernelReply.Deferred(DeferredReason.AUTHORIZATION_DENIED))
                    } finally {
                        view.close()
                    }
                }
            }
            // Typed rollback returns normally from Room. It did not commit a new result,
            // so it must not cross the post-commit fault/crash cut (nor may a replay do so).
            if (!preflight && stored is KernelReply.Recorded && !stored.replayed) {
                fault.at(StoreFaultPoint.AFTER_COMMIT)
            }
            currentCoroutineContext().ensureActive()
            stored
        } catch (cancelled: CancellationException) {
            // Caller must retain the ID: cancellation may race a successful COMMIT.
            throw cancelled
        } catch (_: Exception) {
            KernelReply.Deferred(DeferredReason.STORAGE_UNCERTAIN)
        }
    }

    /**
     * Fresh authenticated application lookup. Never use an RPC retained reply as an
     * employee authorization cache. A supervisor read records an audit in this same
     * transaction and does not change the stored original actor or effect attribution.
     */
    public suspend fun lookup(principal: AuthenticatedPrincipal?, identity: OperationIdentity): LookupReply =
        lookupInternal(principal, identity, null)

    internal suspend fun lookupRpcBusiness(principal: AuthenticatedPrincipal?, identity: OperationIdentity,
        audience: RoomLocalDisplayRpcAudience): LookupReply {
        val access = audience.access as? LocalMasterRpcBusinessAccess.Lookup
            ?: return LookupReply.Deferred(identity.operationId, DeferredReason.AUTHORIZATION_DENIED)
        if (access.profile != LocalMasterRpcWorkflowProfile.KITCHEN || access.identity != identity)
            return LookupReply.Deferred(identity.operationId, DeferredReason.AUTHORIZATION_DENIED)
        val result = lookupInternal(principal, identity, audience)
        return if (result is LookupReply.Found && !rpcDisplayCurrent(audience))
            LookupReply.Deferred(identity.operationId, DeferredReason.AUTHORIZATION_DENIED) else result
    }

    private suspend fun lookupInternal(principal: AuthenticatedPrincipal?, identity: OperationIdentity,
        rpcAudience: RoomLocalDisplayRpcAudience?): LookupReply {
        fun deferred(reason: DeferredReason): LookupReply {
            if (reason == DeferredReason.STORAGE_UNCERTAIN) physicalService.deny()
            return LookupReply.Deferred(identity.operationId, reason)
        }
        if (principal !is LocalEmployeePrincipal) return deferred(DeferredReason.AUTHORIZATION_DENIED)
        if (principal.scope != scope || identity.branchId != scope.branchId) return deferred(DeferredReason.WRONG_SCOPE)
        return try {
            database.runtimeWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    database.runtimeHistoryOwner?.frameFor(connection)?.enterTransaction(currentCoroutineContext())
                    val view = transactionView(connection, principal)
                    try {
                        rpcAudience?.let { view.bindLocalDisplayRpcAudience(it) }
                        val reply = run transactionBody@ {
                            val provider = CurrentAuthorizationProvider { authorizationSource.current(it, view) }
                            if (AuthorizationPolicy(provider).currentIdentityDenial(principal) != null) {
                                return@transactionBody deferred(DeferredReason.AUTHORIZATION_DENIED)
                            }
                            if (rpcAudience != null) {
                                if (identity.originalActorId != principal.actorId || identity.semanticDigestVersion != 1 ||
                                    recoveryPolicy(provider).authorizeWithCurrentGrant(principal, scope, principal.actorId,
                                        identity.operationId) !is ResultRecoveryDecision.Allowed)
                                    return@transactionBody deferred(DeferredReason.AUTHORIZATION_DENIED)
                                val original = view.originalForKernel(identity.operationId)
                                if (original != null) {
                                    val requirements = kitchenRpcRetainedResultRequirements(original, identity.operationId,
                                        identity.originalActorId, PayloadDigest(identity.semanticSha256))
                                        ?: return@transactionBody deferred(DeferredReason.AUTHORIZATION_DENIED)
                                    if (requirements.any { AuthorizationPolicy(provider).authorize(principal, it) !is AuthorizationDecision.Allowed })
                                        return@transactionBody deferred(DeferredReason.AUTHORIZATION_DENIED)
                                }
                            }
                            val row = retainedOperation(view, identity.operationId)
                            val storedActor = row?.let { DomainId(it.actorId) } ?: identity.originalActorId
                            if (recoveryPolicy(provider).authorize(principal, scope, storedActor, identity.operationId) !is ResultRecoveryDecision.Allowed) {
                                return@transactionBody deferred(DeferredReason.AUTHORIZATION_DENIED)
                            }
                            if (row != null) {
                                return@transactionBody if (matches(row, identity)) LookupReply.Found(recorded(row, true))
                                else LookupReply.IdentityConflict(identity.operationId)
                            }
                            val branch = dao.branch(scope.storageKey()) ?: return@transactionBody deferred(DeferredReason.WRITER_NOT_QUALIFIED)
                            val observed = observe(branch, recoveryState(branch))
                            if (!branch.writable || observed.authority != authority || runtimeVerifier.writer(observed) != RuntimeVerification.VERIFIED) {
                                return@transactionBody deferred(DeferredReason.WRITER_NOT_QUALIFIED)
                            }
                            if (identity.semanticDigestVersion != 1 || runtimeVerifier.absentOperation(observed, identity) != RuntimeVerification.VERIFIED) {
                                return@transactionBody deferred(DeferredReason.HISTORY_INCOMPLETE)
                            }
                            // No persisted negative cache and no mutable rights are implied.
                            LookupReply.AbsentAtCurrentWriter(identity.operationId)
                        }
                        view.prepareNormalReturn()
                        view.finishNow()
                        reply
                    } catch (_: MasterLocalDisplayDeliveryUnavailable) {
                        rollback(deferred(DeferredReason.AUTHORIZATION_DENIED))
                    } catch (_: RoomCurrentLocalAuthorizationDenied) {
                        rollback(deferred(DeferredReason.AUTHORIZATION_DENIED))
                    } finally { view.close() }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            deferred(DeferredReason.STORAGE_UNCERTAIN)
        }
    }

    /**
     * Private original-Host check. Reuses installed typed preparation/current policy in the
     * real writer, but never evaluates a proposal, projects private data or executes a command.
     * SDK session revocation separately fences the interval after this loan returns.
     */
    internal suspend fun authorizeRpcBusiness(principal: AuthenticatedPrincipal?,
        access: LocalMasterRpcBusinessAccess, requireRecovery: Boolean,
        rpcAudience: RoomLocalDisplayRpcAudience? = null): LocalMasterRpcAccessResult {
        if (principal !is LocalEmployeePrincipal || principal.scope != scope ||
            principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) return LocalMasterRpcAccessResult.DENIED
        // Decode untrusted request bodies before entering the writer. A malformed typed
        // request is denied; it must not retire unrelated endpoint/native business work.
        val profile = access.profile
        if (profile == LocalMasterRpcWorkflowProfile.KITCHEN &&
            (rpcAudience == null || rpcAudience.access !== access)) return LocalMasterRpcAccessResult.DENIED
        val selectedNormalized = try {
            when (access) {
                is LocalMasterRpcBusinessAccess.Read -> {
                    if (profile == LocalMasterRpcWorkflowProfile.REPORTS &&
                        !LocalMasterReportRpcKeysV1.accepts(access.request.query) ||
                        profile == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS &&
                        !LocalMasterStockTransferRpcKeysV1.accepts(access.request.query) ||
                        profile == LocalMasterRpcWorkflowProfile.KITCHEN &&
                        !LocalMasterKitchenRpcKeysV1.accepts(access.request.query) ||
                        profile == LocalMasterRpcWorkflowProfile.RETAIL_ORDERS &&
                        !LocalMasterRetailOrderRpcKeysV1.accepts(access.request.query) ||
                        profile == LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS &&
                        !LocalMasterPaymentCorrectionRpcKeysV1.accepts(access.request.query) ||
                        profile == LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS &&
                        !LocalMasterCustomerReceiptRpcKeysV1.accepts(access.request.query) ||
                        profile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS &&
                        !LocalMasterFoodCheckRpcKeysV1.accepts(access.request.query) ||
                        profile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL &&
                        !LocalMasterFoodCashFinancialRpcKeysV1.accepts(access.request.query) ||
                        profile == LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS &&
                        !LocalMasterFoodTableAssignmentRpcKeysV1.accepts(access.request.query) ||
                        profile == LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE &&
                        !LocalMasterInvoiceCaptureRpcKeysV1.accepts(access.request.query) ||
                        profile == LocalMasterRpcWorkflowProfile.INVOICE_MATCHING &&
                        !LocalMasterInvoiceMatchingRpcKeysV1.accepts(access.request.query)) return LocalMasterRpcAccessResult.DENIED
                    val handler = readHandlers[access.request.query] ?: return LocalMasterRpcAccessResult.DENIED
                    handler.normalize(access.request.payload).also {
                        if (!handler.supportsRpcWorkflow(profile, it)) return LocalMasterRpcAccessResult.DENIED
                    }
                }
                is LocalMasterRpcBusinessAccess.Execute -> {
                    if (access.request.actionApprovalId != null) return LocalMasterRpcAccessResult.DENIED
                    if (profile == LocalMasterRpcWorkflowProfile.REPORTS &&
                        !LocalMasterReportRpcKeysV1.accepts(access.request.command) ||
                        profile == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS &&
                        !LocalMasterStockTransferRpcKeysV1.accepts(access.request.command) ||
                        profile == LocalMasterRpcWorkflowProfile.KITCHEN &&
                        !LocalMasterKitchenRpcKeysV1.accepts(access.request.command) ||
                        profile == LocalMasterRpcWorkflowProfile.RETAIL_ORDERS &&
                        !LocalMasterRetailOrderRpcKeysV1.accepts(access.request.command) ||
                        profile == LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS &&
                        !LocalMasterPaymentCorrectionRpcKeysV1.accepts(access.request.command) ||
                        profile == LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS &&
                        !LocalMasterCustomerReceiptRpcKeysV1.accepts(access.request.command) ||
                        profile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS &&
                        !LocalMasterFoodCheckRpcKeysV1.accepts(access.request.command) ||
                        profile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL &&
                        !LocalMasterFoodCashFinancialRpcKeysV1.accepts(access.request.command) ||
                        profile == LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS &&
                        !LocalMasterFoodTableAssignmentRpcKeysV1.accepts(access.request.command) ||
                        profile == LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE &&
                        !LocalMasterInvoiceCaptureRpcKeysV1.accepts(access.request.command) ||
                        profile == LocalMasterRpcWorkflowProfile.INVOICE_MATCHING &&
                        !LocalMasterInvoiceMatchingRpcKeysV1.accepts(access.request.command)) return LocalMasterRpcAccessResult.DENIED
                    val handler = handlers[access.request.command] ?: return LocalMasterRpcAccessResult.DENIED
                    handler.normalize(access.request.payload).also {
                        if (!handler.supportsRpcWorkflow(profile, it)) return LocalMasterRpcAccessResult.DENIED
                    }
                }
                is LocalMasterRpcBusinessAccess.Lookup -> null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            return LocalMasterRpcAccessResult.DENIED
        }
        return try {
            val result = database.runtimeWriterConnection { connection ->
                requireDurableWriter(connection)
                connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                    database.runtimeHistoryOwner?.frameFor(connection)?.enterTransaction(currentCoroutineContext())
                    val view = transactionView(connection, principal)
                    try {
                        rpcAudience?.let { view.bindLocalDisplayRpcAudience(it) }
                        val allowed = run checkAccess@ {
                            val provider = CurrentAuthorizationProvider { authorizationSource.current(it, view) }
                            val policy = AuthorizationPolicy(provider)
                            if (policy.currentIdentityDenial(principal) != null) return@checkAccess false
                            val branch = dao.branch(scope.storageKey()) ?: return@checkAccess false
                            val observed = observe(branch, recoveryState(branch))
                            if (!branch.writable || observed.authority != authority ||
                                runtimeVerifier.writer(observed) != RuntimeVerification.VERIFIED) return@checkAccess false
                            val requirements: List<AuthorizationRequirement> = when (access) {
                                is LocalMasterRpcBusinessAccess.Read -> {
                                    val request = access.request
                                    val handler = readHandlers[request.query] ?: return@checkAccess false
                                    val normalized = checkNotNull(selectedNormalized)
                                    val digest = queryDigest(scope, principal.actorId, request.query, normalized)
                                    val prepared = handler.prepare(normalized, view,
                                        ReadContext(scope, principal.actorId, request.query, digest))
                                    prepared.requirements.toList().also { list ->
                                        check(list.size in 1..256)
                                        list.forEach { requirement ->
                                            check(requirement.scope == scope)
                                            requirement.binding?.let { check(it.operationId == null && it.payloadDigest == digest) }
                                        }
                                    }
                                }
                                is LocalMasterRpcBusinessAccess.Execute -> {
                                    val request = access.request
                                    if (request.actionApprovalId != null) return@checkAccess false
                                    val handler = handlers[request.command] ?: return@checkAccess false
                                    val normalized = checkNotNull(selectedNormalized)
                                    val digest = semanticDigest(scope, principal.actorId, request.command, normalized)
                                    if (requireRecovery && recoveryPolicy(provider).authorizeWithCurrentGrant(principal, scope,
                                            principal.actorId, request.operationId) !is ResultRecoveryDecision.Allowed) return@checkAccess false
                                    val retained = if (profile == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS && requireRecovery ||
                                        profile == LocalMasterRpcWorkflowProfile.KITCHEN ||
                                        profile == LocalMasterRpcWorkflowProfile.RETAIL_ORDERS ||
                                        profile == LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS ||
                                        profile == LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS ||
                                        profile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS ||
                                        profile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL ||
                                        profile == LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS ||
                                        profile == LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE ||
                                        profile == LocalMasterRpcWorkflowProfile.INVOICE_MATCHING) view.originalForKernel(request.operationId) else null
                                    if (retained != null) {
                                        // A retained original uses its current typed disclosure rights;
                                        // the original mutation is never prepared again for disclosure.
                                        if (profile == LocalMasterRpcWorkflowProfile.KITCHEN) {
                                            if (recoveryPolicy(provider).authorizeWithCurrentGrant(principal, scope,
                                                principal.actorId, request.operationId) !is ResultRecoveryDecision.Allowed) return@checkAccess false
                                            kitchenRpcRetainedResultRequirements(retained, request.operationId,
                                                principal.actorId, digest, normalized) ?: return@checkAccess false
                                        } else if (profile == LocalMasterRpcWorkflowProfile.RETAIL_ORDERS) {
                                            if (!requireRecovery && recoveryPolicy(provider).authorizeWithCurrentGrant(principal, scope,
                                                principal.actorId, request.operationId) !is ResultRecoveryDecision.Allowed) return@checkAccess false
                                            retailOrderRpcRetainedResultRequirements(retained, request.operationId,
                                                principal.actorId, digest, request.command, normalized) ?: return@checkAccess false
                                        } else if (profile == LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS) {
                                            paymentCorrectionRpcRetainedResultRequirements(retained, request.operationId,
                                                principal.actorId, digest, request.command, normalized) ?: return@checkAccess false
                                        } else if (profile == LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS) {
                                            if (!requireRecovery && recoveryPolicy(provider).authorizeWithCurrentGrant(principal, scope,
                                                principal.actorId, request.operationId) !is ResultRecoveryDecision.Allowed) return@checkAccess false
                                            customerReceiptRpcRetainedResultRequirements(retained, request.operationId,
                                                principal.actorId, digest, view, request.command, normalized) ?: return@checkAccess false
                                        } else if (profile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS) {
                                            if (!requireRecovery && recoveryPolicy(provider).authorizeWithCurrentGrant(principal, scope,
                                                principal.actorId, request.operationId) !is ResultRecoveryDecision.Allowed) return@checkAccess false
                                            foodCheckRpcRetainedResultRequirements(retained, request.operationId,
                                                principal.actorId, digest, view, normalized) ?: return@checkAccess false
                                        } else if (profile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL) {
                                            if (!requireRecovery && recoveryPolicy(provider).authorizeWithCurrentGrant(principal, scope,
                                                principal.actorId, request.operationId) !is ResultRecoveryDecision.Allowed) return@checkAccess false
                                            foodCashFinancialRpcRetainedResultRequirements(retained, request.operationId,
                                                principal.actorId, digest, view, request.command, normalized) ?: return@checkAccess false
                                        } else if (profile == LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS) {
                                            if (!requireRecovery && recoveryPolicy(provider).authorizeWithCurrentGrant(principal, scope,
                                                principal.actorId, request.operationId) !is ResultRecoveryDecision.Allowed) return@checkAccess false
                                            foodTableAssignmentRpcRetainedResultRequirements(retained, request.operationId,
                                                principal.actorId, digest, view, request.command, normalized) ?: return@checkAccess false
                                        } else if (profile == LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE) {
                                            if (!requireRecovery && recoveryPolicy(provider).authorizeWithCurrentGrant(principal, scope,
                                                principal.actorId, request.operationId) !is ResultRecoveryDecision.Allowed) return@checkAccess false
                                            invoiceCaptureRpcRetainedResultRequirements(retained, request.operationId,
                                                principal.actorId, digest, request.command, normalized) ?: return@checkAccess false
                                        } else if (profile == LocalMasterRpcWorkflowProfile.INVOICE_MATCHING) {
                                            if (!requireRecovery && recoveryPolicy(provider).authorizeWithCurrentGrant(principal, scope,
                                                principal.actorId, request.operationId) !is ResultRecoveryDecision.Allowed) return@checkAccess false
                                            invoiceMatchingRpcRetainedResultRequirements(retained, request.operationId,
                                                principal.actorId, digest, view, request.command, normalized) ?: return@checkAccess false
                                        } else stockRpcRetainedResultRequirements(retained, request.operationId,
                                            principal.actorId, digest, normalized) ?: return@checkAccess false
                                    } else {
                                        val prepared = handler.prepare(normalized, view,
                                            CommandContext(scope, principal.actorId, request.operationId, request.command, digest))
                                        listOf(prepared.requirement).also {
                                            check(prepared.requirement.scope == scope)
                                            val bound = checkNotNull(prepared.requirement.binding)
                                            check(bound.operationId == request.operationId && bound.payloadDigest == digest)
                                        }
                                    }
                                }
                                is LocalMasterRpcBusinessAccess.Lookup -> {
                                    val identity = access.identity
                                    if (identity.branchId != scope.branchId || identity.originalActorId != principal.actorId ||
                                        identity.semanticDigestVersion != 1) return@checkAccess false
                                    if (recoveryPolicy(provider).authorizeWithCurrentGrant(principal, scope, identity.originalActorId,
                                            identity.operationId) !is ResultRecoveryDecision.Allowed) return@checkAccess false
                                    if (profile == LocalMasterRpcWorkflowProfile.KITCHEN) {
                                        val original = view.originalForKernel(identity.operationId)
                                            ?: return@checkAccess runtimeVerifier.absentOperation(observed, identity) == RuntimeVerification.VERIFIED
                                        val requirements = kitchenRpcRetainedResultRequirements(original, identity.operationId,
                                            identity.originalActorId, PayloadDigest(identity.semanticSha256)) ?: return@checkAccess false
                                        return@checkAccess requirements.all { policy.authorize(principal, it) is AuthorizationDecision.Allowed }
                                    }
                                    if (profile == LocalMasterRpcWorkflowProfile.RETAIL_ORDERS) {
                                        // Both original storage profiles use the same authentic reader. A
                                        // progressed order/catalog cannot become a fresh edit prerequisite.
                                        val original = view.originalForKernel(identity.operationId)
                                            ?: return@checkAccess runtimeVerifier.absentOperation(observed, identity) == RuntimeVerification.VERIFIED
                                        val requirements = retailOrderRpcRetainedResultRequirements(original, identity.operationId,
                                            identity.originalActorId, PayloadDigest(identity.semanticSha256)) ?: return@checkAccess false
                                        return@checkAccess requirements.all { policy.authorize(principal, it) is AuthorizationDecision.Allowed }
                                    }
                                    if (profile == LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS) {
                                        // Disclosure uses the exact authenticated retained legacy original.
                                        // Later sale/tender/close state cannot reprepare the correction.
                                        val original = view.originalForKernel(identity.operationId)
                                            ?: return@checkAccess runtimeVerifier.absentOperation(observed, identity) == RuntimeVerification.VERIFIED
                                        val requirements = paymentCorrectionRpcRetainedResultRequirements(original,
                                            identity.operationId, identity.originalActorId, PayloadDigest(identity.semanticSha256))
                                            ?: return@checkAccess false
                                        return@checkAccess requirements.all { policy.authorize(principal, it) is AuthorizationDecision.Allowed }
                                    }
                                    if (profile == LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS) {
                                        // Original recovery remains separate from the explicit RETURN_LOOKUP
                                        // read route. Current Record rights rejoin the genuine original collector.
                                        val original = view.originalForKernel(identity.operationId)
                                            ?: return@checkAccess runtimeVerifier.absentOperation(observed, identity) == RuntimeVerification.VERIFIED
                                        val requirements = customerReceiptRpcRetainedResultRequirements(original,
                                            identity.operationId, identity.originalActorId, PayloadDigest(identity.semanticSha256), view)
                                            ?: return@checkAccess false
                                        return@checkAccess requirements.all { policy.authorize(principal, it) is AuthorizationDecision.Allowed }
                                    }
                                    if (profile == LocalMasterRpcWorkflowProfile.FOOD_CHECKS) {
                                        // Both committed streamed and explicitly qualified scalar refusals
                                        // use their exact original source, never a fresh Draft/Add preparation.
                                        val original = view.originalForKernel(identity.operationId)
                                            ?: return@checkAccess runtimeVerifier.absentOperation(observed, identity) == RuntimeVerification.VERIFIED
                                        val requirements = foodCheckRpcRetainedResultRequirements(original,
                                            identity.operationId, identity.originalActorId, PayloadDigest(identity.semanticSha256), view)
                                            ?: return@checkAccess false
                                        return@checkAccess requirements.all { policy.authorize(principal, it) is AuthorizationDecision.Allowed }
                                    }
                                    if (profile == LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL) {
                                        // Financial recovery uses immutable original business/native sources;
                                        // it neither prepares another mutation nor recreates a physical Start.
                                        val original = view.originalForKernel(identity.operationId)
                                            ?: return@checkAccess runtimeVerifier.absentOperation(observed, identity) == RuntimeVerification.VERIFIED
                                        val requirements = foodCashFinancialRpcRetainedResultRequirements(original,
                                            identity.operationId, identity.originalActorId, PayloadDigest(identity.semanticSha256), view)
                                            ?: return@checkAccess false
                                        return@checkAccess requirements.all { policy.authorize(principal, it) is AuthorizationDecision.Allowed }
                                    }
                                    if (profile == LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS) {
                                        // The exact immutable association original supplies disclosure sources;
                                        // later directory/check state cannot reprepare the assignment.
                                        val original = view.originalForKernel(identity.operationId)
                                            ?: return@checkAccess runtimeVerifier.absentOperation(observed, identity) == RuntimeVerification.VERIFIED
                                        val requirements = foodTableAssignmentRpcRetainedResultRequirements(original,
                                            identity.operationId, identity.originalActorId, PayloadDigest(identity.semanticSha256), view)
                                            ?: return@checkAccess false
                                        return@checkAccess requirements.all { policy.authorize(principal, it) is AuthorizationDecision.Allowed }
                                    }
                                    if (profile == LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE) {
                                        // A completed claim retains its exact canonical original. Today's
                                        // PO/catalog state cannot become a new capture prerequisite.
                                        val original = view.originalForKernel(identity.operationId)
                                            ?: return@checkAccess runtimeVerifier.absentOperation(observed, identity) == RuntimeVerification.VERIFIED
                                        val requirements = invoiceCaptureRpcRetainedResultRequirements(original,
                                            identity.operationId, identity.originalActorId, PayloadDigest(identity.semanticSha256))
                                            ?: return@checkAccess false
                                        return@checkAccess requirements.all { policy.authorize(principal, it) is AuthorizationDecision.Allowed }
                                    }
                                    if (profile == LocalMasterRpcWorkflowProfile.INVOICE_MATCHING) {
                                        // Disclosure uses the immutable captured claim for its full monetary
                                        // boundary, never today's selected quantity or a fresh match proposal.
                                        val original = view.originalForKernel(identity.operationId)
                                            ?: return@checkAccess runtimeVerifier.absentOperation(observed, identity) == RuntimeVerification.VERIFIED
                                        val requirements = invoiceMatchingRpcRetainedResultRequirements(original,
                                            identity.operationId, identity.originalActorId, PayloadDigest(identity.semanticSha256), view)
                                            ?: return@checkAccess false
                                        return@checkAccess requirements.all { policy.authorize(principal, it) is AuthorizationDecision.Allowed }
                                    }
                                    if (profile == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS) {
                                        // The stock command has a streamed original. The legacy-only reader
                                        // cannot establish its presence or authoritative absence.
                                        val original = view.originalForKernel(identity.operationId)
                                            ?: return@checkAccess runtimeVerifier.absentOperation(observed, identity) == RuntimeVerification.VERIFIED
                                        val resultRequirements = stockRpcRetainedResultRequirements(original,
                                            identity.operationId, identity.originalActorId, PayloadDigest(identity.semanticSha256))
                                            ?: return@checkAccess false
                                        return@checkAccess resultRequirements.all {
                                            policy.authorize(principal, it) is AuthorizationDecision.Allowed
                                        }
                                    }
                                    val retained = view.retainedCommand(identity.operationId)
                                    if (retained == null) {
                                        // Genuine owned absence carries no body/profile authority. The actual
                                        // execution lane still classifies and authorizes its complete body.
                                        // Report audit absence also needs current EXPORT, bound only to the
                                        // real branch and original operation/digest, not an invented export.
                                        if (profile == LocalMasterRpcWorkflowProfile.REPORTS) {
                                            val requirement = AuthorizationRequirement(
                                                Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.EXPORT), scope,
                                                ActionBinding(scope.branchId, Revision(0), PayloadDigest(identity.semanticSha256),
                                                    identity.operationId, "report.export.audit"))
                                            if (policy.authorize(principal, requirement) !is AuthorizationDecision.Allowed)
                                                return@checkAccess false
                                        }
                                        return@checkAccess runtimeVerifier.absentOperation(observed, identity) == RuntimeVerification.VERIFIED
                                    }
                                    if (retained.nativeOriginalProvenance != null || retained.scope != scope ||
                                        retained.originalActorId != identity.originalActorId ||
                                        retained.semanticDigest.value != identity.semanticSha256) return@checkAccess false
                                    if (profile == LocalMasterRpcWorkflowProfile.REPORTS &&
                                        !LocalMasterReportRpcKeysV1.accepts(retained.command)) return@checkAccess false
                                    val handler = handlers[retained.command] ?: return@checkAccess false
                                    val normalized = handler.normalize(retained.normalized)
                                    if (normalized != retained.normalized || !handler.supportsRpcWorkflow(profile, normalized) ||
                                        semanticDigest(scope, retained.originalActorId, retained.command, normalized) != retained.semanticDigest)
                                        return@checkAccess false
                                    if (profile == LocalMasterRpcWorkflowProfile.REPORTS) {
                                        // Auth-only preparation resolves the actual current export audit
                                        // resource with the original command identity. Never evaluate a
                                        // candidate, reinvoke Begin/Finish or refresh the stored source cut.
                                        val prepared = handler.prepare(normalized, view,
                                            CommandContext(scope, retained.originalActorId, identity.operationId,
                                                retained.command, retained.semanticDigest))
                                        listOf(prepared.requirement).also {
                                            check(prepared.requirement.scope == scope)
                                            val bound = checkNotNull(prepared.requirement.binding)
                                            check(bound.operationId == identity.operationId && bound.payloadDigest == retained.semanticDigest)
                                        }
                                    } else {
                                        // Directory recovery remains a distinct current recovery grant,
                                        // even after configuration administration was removed.
                                        emptyList()
                                    }
                                }
                            }
                            requirements.all { policy.authorize(principal, it) is AuthorizationDecision.Allowed }
                        }
                        view.prepareNormalReturn()
                        view.finishNow()
                        if (allowed) LocalMasterRpcAccessResult.ALLOWED else LocalMasterRpcAccessResult.DENIED
                    } catch (_: MasterLocalDisplayDeliveryUnavailable) {
                        rollback(LocalMasterRpcAccessResult.DENIED)
                    } catch (_: RoomCurrentLocalAuthorizationDenied) {
                        rollback(LocalMasterRpcAccessResult.DENIED)
                    } catch (_: MasterReadAuthorizationDenied) {
                        rollback(LocalMasterRpcAccessResult.DENIED)
                    } catch (_: MasterReadInvalidSelection) {
                        rollback(LocalMasterRpcAccessResult.DENIED)
                    } finally { view.close() }
                }
            }
            currentCoroutineContext().ensureActive()
            if (result == LocalMasterRpcAccessResult.ALLOWED && rpcAudience != null && !rpcDisplayCurrent(rpcAudience))
                LocalMasterRpcAccessResult.DENIED else result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            physicalService.deny()
            LocalMasterRpcAccessResult.UNAVAILABLE
        }
    }

    /** An original matching result needs current financial authority over its whole immutable claim.
     * Reading its already captured source does not prepare/evaluate or repeat the matching action. */
    private suspend fun invoiceMatchingRpcRetainedResultRequirements(original: RetainedOriginalV2, operationId: OperationId,
        originalActorId: DomainId, digest: PayloadDigest, view: CommandReadView, expectedKey: CommandKey? = null,
        expectedNormalized: StoredBytes? = null): List<AuthorizationRequirement>? {
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
        if (retained.nativeOriginalProvenance != null || retained.scope != scope ||
            retained.operationId != operationId || retained.originalActorId != originalActorId ||
            retained.semanticDigest != digest || !LocalMasterInvoiceMatchingRpcKeysV1.accepts(retained.command) ||
            expectedKey != null && retained.command != expectedKey) return null
        val handler = handlers[retained.command] ?: return null
        if (handler is MasterStreamingCommandHandler || retained.command in StockStreamingCommandsV2.all) return null
        val normalized = handler.normalize(retained.normalized)
        if (normalized != retained.normalized || expectedNormalized != null && normalized != expectedNormalized ||
            !handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.INVOICE_MATCHING, normalized) ||
            semanticDigest(scope, originalActorId, retained.command, normalized) != digest) return null
        val provider = handler as? LocalMasterRpcRetainedResultSourceAccessProvider ?: return null
        val requirements = provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.INVOICE_MATCHING, normalized,
            view, CommandContext(scope, originalActorId, operationId, retained.command, digest))?.toList() ?: return null
        check(requirements.size == 1)
        val requirement = requirements.single()
        val money = requirement.limitUse as? LimitUse.Monetary ?: return null
        check(requirement.scope == scope && requirement.owner == OwnerConstraint.Unrestricted &&
            requirement.permission == Permission(SecurityAction.PURCHASING_INVOICE_MATCH_RECOGNIZE, PermissionVerb.PERFORM) &&
            money.amount.currency == com.ukkera.pos.types.Currency.EGP && money.amount.minor >= 0)
        val binding = checkNotNull(requirement.binding)
        check(binding.operationId == operationId && binding.payloadDigest == digest && binding.revision.value >= 1)
        return requirements
    }

    /** Exact capture V2 original only; disclosure never prepares or evaluates the mutation. */
    private fun invoiceCaptureRpcRetainedResultRequirements(original: RetainedOriginalV2, operationId: OperationId,
        originalActorId: DomainId, digest: PayloadDigest, expectedKey: CommandKey? = null,
        expectedNormalized: StoredBytes? = null): List<AuthorizationRequirement>? {
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
        if (retained.nativeOriginalProvenance != null || retained.scope != scope ||
            retained.operationId != operationId || retained.originalActorId != originalActorId ||
            retained.semanticDigest != digest || !LocalMasterInvoiceCaptureRpcKeysV1.accepts(retained.command) ||
            expectedKey != null && retained.command != expectedKey) return null
        val handler = handlers[retained.command] ?: return null
        if (handler is MasterStreamingCommandHandler || retained.command in StockStreamingCommandsV2.all) return null
        val normalized = handler.normalize(retained.normalized)
        if (normalized != retained.normalized || expectedNormalized != null && normalized != expectedNormalized ||
            !handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE, normalized) ||
            semanticDigest(scope, originalActorId, retained.command, normalized) != digest) return null
        val provider = handler as? LocalMasterRpcRetainedResultAccessProvider ?: return null
        return provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE, normalized,
            CommandContext(scope, originalActorId, operationId, retained.command, digest)).toList().also { requirements ->
            check(requirements.size == 1)
            val requirement = requirements.single()
            check(requirement.scope == scope &&
                requirement.permission == Permission(SecurityAction.PURCHASING_INVOICE_CAPTURE, PermissionVerb.PERFORM) &&
                requirement.owner == OwnerConstraint.Unrestricted && requirement.limitUse == LimitUse.NotApplicable)
            val binding = checkNotNull(requirement.binding)
            check(binding.operationId == operationId && binding.payloadDigest == digest && binding.revision == Revision(0))
        }
    }

    /** Exact already-retained streamed identity and typed disclosure only; never a proposal. */
    private fun stockRpcRetainedResultRequirements(
        original: RetainedOriginalV2,
        operationId: OperationId,
        originalActorId: DomainId,
        digest: PayloadDigest,
        expectedNormalized: StoredBytes? = null,
    ): List<AuthorizationRequirement>? {
        val retained = (original as? RetainedOriginalV2.Streamed)?.evidence ?: return null
        val identity = retained.identity
        val producer = identity.producer
        if (identity.scope != scope || producer.operationId != operationId ||
            producer.originalActorId != originalActorId || producer.semanticDigest != digest ||
            !LocalMasterStockTransferRpcKeysV1.accepts(producer.command)) return null
        val handler = handlers[producer.command] ?: return null
        val normalized = handler.normalize(retained.normalized)
        if (normalized != retained.normalized || expectedNormalized != null && normalized != expectedNormalized ||
            !handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS, normalized) ||
            semanticDigest(scope, originalActorId, producer.command, normalized) != digest) return null
        val resultAccess = handler as? LocalMasterRpcRetainedResultAccessProvider ?: return null
        val context = CommandContext(scope, originalActorId, operationId, producer.command, digest)
        return resultAccess.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS,
            normalized, context).toList().also { requirements ->
            check(requirements.size in 1..256)
            requirements.forEach { requirement ->
                check(requirement.scope == scope &&
                    requirement.permission == Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM))
                val bound = checkNotNull(requirement.binding)
                check(bound.operationId == operationId && bound.payloadDigest == digest)
            }
        }
    }

    /** Both unchanged legacy and streamed Station originals, never fresh transition preparation. */
    private fun kitchenRpcRetainedResultRequirements(original: RetainedOriginalV2, operationId: OperationId,
        originalActorId: DomainId, digest: PayloadDigest, expectedNormalized: StoredBytes? = null): List<AuthorizationRequirement>? {
        val key: CommandKey
        val body: StoredBytes
        when (original) {
            is RetainedOriginalV2.Legacy -> with(original.evidence) {
                if (nativeOriginalProvenance != null || scope != this@AtomicMasterStore.scope ||
                    this.operationId != operationId || this.originalActorId != originalActorId || semanticDigest != digest) return null
                key = command; body = normalized
            }
            is RetainedOriginalV2.Streamed -> with(original.evidence) {
                val producer = identity.producer
                if (identity.scope != scope || producer.operationId != operationId ||
                    producer.originalActorId != originalActorId || producer.semanticDigest != digest) return null
                key = producer.command; body = normalized
            }
        }
        if (!LocalMasterKitchenRpcKeysV1.accepts(key)) return null
        val handler = handlers[key] ?: return null
        val normalized = handler.normalize(body)
        if (normalized != body || expectedNormalized != null && normalized != expectedNormalized ||
            !handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.KITCHEN, normalized) ||
            semanticDigest(scope, originalActorId, key, normalized) != digest) return null
        val provider = handler as? LocalMasterRpcRetainedResultAccessProvider ?: return null
        return provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.KITCHEN, normalized,
            CommandContext(scope, originalActorId, operationId, key, digest)).toList().also { requirements ->
            check(requirements.size in 1..256)
            requirements.forEach { requirement ->
                check(requirement.scope == scope &&
                    requirement.permission == Permission(SecurityAction.KITCHEN_TASK, PermissionVerb.PERFORM))
                val binding = checkNotNull(requirement.binding)
                check(binding.operationId == operationId && binding.payloadDigest == digest)
            }
        }
    }

    /** Exact Create/Replace originals only; disclosure never calls preparation or quote sources. */
    private fun retailOrderRpcRetainedResultRequirements(original: RetainedOriginalV2, operationId: OperationId,
        originalActorId: DomainId, digest: PayloadDigest, expectedKey: CommandKey? = null,
        expectedNormalized: StoredBytes? = null): List<AuthorizationRequirement>? {
        val key: CommandKey
        val body: StoredBytes
        val streamed = original is RetainedOriginalV2.Streamed
        when (original) {
            is RetainedOriginalV2.Legacy -> with(original.evidence) {
                if (nativeOriginalProvenance != null || scope != this@AtomicMasterStore.scope ||
                    this.operationId != operationId || this.originalActorId != originalActorId || semanticDigest != digest) return null
                key = command; body = normalized
            }
            is RetainedOriginalV2.Streamed -> with(original.evidence) {
                val producer = identity.producer
                if (identity.scope != scope || producer.operationId != operationId ||
                    producer.originalActorId != originalActorId || producer.semanticDigest != digest) return null
                key = producer.command; body = normalized
            }
        }
        if (!LocalMasterRetailOrderRpcKeysV1.accepts(key) || expectedKey != null && key != expectedKey ||
            streamed != (key in StockStreamingCommandsV2.all)) return null
        val handler = handlers[key] ?: return null
        if ((handler is MasterStreamingCommandHandler) != streamed) return null
        val normalized = handler.normalize(body)
        if (normalized != body || expectedNormalized != null && normalized != expectedNormalized ||
            !handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.RETAIL_ORDERS, normalized) ||
            semanticDigest(scope, originalActorId, key, normalized) != digest) return null
        val provider = handler as? LocalMasterRpcRetainedResultAccessProvider ?: return null
        return provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.RETAIL_ORDERS, normalized,
            CommandContext(scope, originalActorId, operationId, key, digest)).toList().also { requirements ->
            check(requirements.size == 1)
            requirements.forEach { requirement ->
                check(requirement.scope == scope &&
                    requirement.permission == Permission(SecurityAction.ORDER_READ, PermissionVerb.PERFORM) &&
                    requirement.owner == OwnerConstraint.OwnerPrivate(originalActorId))
                val binding = checkNotNull(requirement.binding)
                check(binding.operationId == operationId && binding.payloadDigest == digest)
            }
        }
    }

    /** Canonical authenticated correction originals only; disclosure never prepares the mutation. */
    private fun paymentCorrectionRpcRetainedResultRequirements(original: RetainedOriginalV2, operationId: OperationId,
        originalActorId: DomainId, digest: PayloadDigest, expectedKey: CommandKey? = null,
        expectedNormalized: StoredBytes? = null): List<AuthorizationRequirement>? {
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
        if (retained.nativeOriginalProvenance != null || retained.scope != scope ||
            retained.operationId != operationId || retained.originalActorId != originalActorId ||
            retained.semanticDigest != digest || !LocalMasterPaymentCorrectionRpcKeysV1.accepts(retained.command) ||
            expectedKey != null && retained.command != expectedKey) return null
        val handler = handlers[retained.command] ?: return null
        if (handler is MasterStreamingCommandHandler || retained.command in StockStreamingCommandsV2.all) return null
        val normalized = handler.normalize(retained.normalized)
        if (normalized != retained.normalized || expectedNormalized != null && normalized != expectedNormalized ||
            !handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS, normalized) ||
            semanticDigest(scope, originalActorId, retained.command, normalized) != digest) return null
        val provider = handler as? LocalMasterRpcRetainedResultAccessProvider ?: return null
        // The installed typed provider derives sale, original expected session revision and
        // positive expected amount from these exact canonical original bytes, without sources.
        return provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS,
            normalized, CommandContext(scope, originalActorId, operationId, retained.command, digest)).toList().also { requirements ->
            check(requirements.size == 1)
            val requirement = requirements.single()
            check(requirement.scope == scope &&
                requirement.permission == Permission(SecurityAction.PAYMENT_CLASSIFICATION_CORRECT, PermissionVerb.PERFORM) &&
                requirement.owner == OwnerConstraint.Unrestricted)
            val binding = checkNotNull(requirement.binding)
            check(binding.operationId == operationId && binding.payloadDigest == digest &&
                binding.reasonCode == "payment.classification.correct")
            val amount = checkNotNull(requirement.limitUse as? LimitUse.Monetary).amount
            check(amount.minor > 0)
        }
    }

    /** Authenticated Record original and immutable collector source, never fresh observation preparation. */
    private suspend fun customerReceiptRpcRetainedResultRequirements(original: RetainedOriginalV2, operationId: OperationId,
        originalActorId: DomainId, digest: PayloadDigest, view: CommandReadView, expectedKey: CommandKey? = null,
        expectedNormalized: StoredBytes? = null): List<AuthorizationRequirement>? {
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
        if (retained.nativeOriginalProvenance != null || retained.scope != scope || view.scope != scope ||
            retained.operationId != operationId || retained.originalActorId != originalActorId ||
            retained.semanticDigest != digest || !LocalMasterCustomerReceiptRpcKeysV1.accepts(retained.command) ||
            expectedKey != null && retained.command != expectedKey) return null
        val handler = handlers[retained.command] ?: return null
        if (handler is MasterStreamingCommandHandler || retained.command in StockStreamingCommandsV2.all) return null
        val normalized = handler.normalize(retained.normalized)
        if (normalized != retained.normalized || expectedNormalized != null && normalized != expectedNormalized ||
            !handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS, normalized) ||
            semanticDigest(scope, originalActorId, retained.command, normalized) != digest) return null
        val provider = handler as? LocalMasterRpcRetainedResultSourceAccessProvider ?: return null
        // The canonical body does not contain its collector. Only the installed typed source
        // reader can derive that owner in this same writer/disclosure-fenced transaction.
        val requirements = provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS,
            normalized, view, CommandContext(scope, originalActorId, operationId, retained.command, digest)) ?: return null
        return requirements.toList().also { list ->
            check(list.size == 1)
            val requirement = list.single()
            check(requirement.scope == scope &&
                requirement.permission == Permission(SecurityAction.CUSTOMER_RECEIPT_RECORD, PermissionVerb.PERFORM) &&
                requirement.owner is OwnerConstraint.OwnerPrivate && requirement.limitUse == LimitUse.NotApplicable)
            val binding = checkNotNull(requirement.binding)
            check(binding.operationId == operationId && binding.payloadDigest == digest &&
                binding.reasonCode == "customer.receipt.record")
        }
    }

    /** Original-only association disclosure; no preparation or mutation is performed. */
    private suspend fun foodTableAssignmentRpcRetainedResultRequirements(original: RetainedOriginalV2, operationId: OperationId,
        originalActorId: DomainId, digest: PayloadDigest, view: CommandReadView, expectedKey: CommandKey? = null,
        expectedNormalized: StoredBytes? = null): List<AuthorizationRequirement>? {
        val retained = (original as? RetainedOriginalV2.Legacy)?.evidence ?: return null
        if (retained.nativeOriginalProvenance != null || retained.scope != scope || view.scope != scope ||
            retained.operationId != operationId || retained.originalActorId != originalActorId ||
            retained.semanticDigest != digest || !LocalMasterFoodTableAssignmentRpcKeysV1.accepts(retained.command) ||
            expectedKey != null && retained.command != expectedKey) return null
        val handler = handlers[retained.command] ?: return null
        if (handler is MasterStreamingCommandHandler || retained.command in StockStreamingCommandsV2.all) return null
        val normalized = handler.normalize(retained.normalized)
        if (normalized != retained.normalized || expectedNormalized != null && normalized != expectedNormalized ||
            !handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS, normalized) ||
            semanticDigest(scope, originalActorId, retained.command, normalized) != digest) return null
        val provider = handler as? LocalMasterRpcRetainedResultSourceAccessProvider ?: return null
        // The canonical body does not grant its original owner. Only the installed typed source
        // reader can qualify that owner in this same writer/disclosure-fenced transaction.
        val requirements = provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS,
            normalized, view, CommandContext(scope, originalActorId, operationId, retained.command, digest)) ?: return null
        return requirements.toList().also { list ->
            check(list.size == 1)
            val requirement = list.single()
            check(requirement.scope == scope &&
                requirement.permission == Permission(SecurityAction.ORDER_EDIT, PermissionVerb.PERFORM) &&
                requirement.owner is OwnerConstraint.OwnerPrivate && requirement.limitUse == LimitUse.NotApplicable)
            val binding = checkNotNull(requirement.binding)
            check(binding.operationId == operationId && binding.payloadDigest == digest &&
                binding.reasonCode == "food.table.assignment")
        }
    }

    /** Original-only unpaid Draft/Add disclosure. Full native/typed source proof belongs to
     * the installed handler on this writer loan; Client fields and today's owner never grant it. */
    private suspend fun foodCheckRpcRetainedResultRequirements(original: RetainedOriginalV2, operationId: OperationId,
        originalActorId: DomainId, digest: PayloadDigest, view: CommandReadView,
        expectedNormalized: StoredBytes? = null): List<AuthorizationRequirement>? {
        val normalized: StoredBytes
        when (original) {
            is RetainedOriginalV2.Legacy -> with(original.evidence) {
                if (nativeOriginalProvenance != null || committed || scope != this@AtomicMasterStore.scope ||
                    this.operationId != operationId || this.originalActorId != originalActorId ||
                    semanticDigest != digest || command != StockStreamingCommandsV2.FOOD) return null
                normalized = this.normalized
            }
            is RetainedOriginalV2.Streamed -> with(original.evidence) {
                val producer = identity.producer
                if (identity.scope != scope || producer.operationId != operationId ||
                    producer.originalActorId != originalActorId || producer.semanticDigest != digest ||
                    producer.command != StockStreamingCommandsV2.FOOD) return null
                normalized = this.normalized
            }
        }
        if (view.scope != scope) return null
        val handler = handlers[StockStreamingCommandsV2.FOOD] as? MasterStreamingCommandHandler ?: return null
        if (handler.normalize(normalized) != normalized || expectedNormalized != null && normalized != expectedNormalized ||
            !handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.FOOD_CHECKS, normalized) ||
            semanticDigest(scope, originalActorId, handler.key, normalized) != digest) return null
        val provider = handler as? LocalMasterRpcRetainedResultSourceAccessProvider ?: return null
        val requirements = provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.FOOD_CHECKS,
            normalized, view, CommandContext(scope, originalActorId, operationId, handler.key, digest)) ?: return null
        return requirements.toList().also { list ->
            check(list.size == 1)
            val requirement = list.single()
            check(requirement.scope == scope && requirement.permission.verb == PermissionVerb.PERFORM &&
                requirement.permission.action in setOf(SecurityAction.ORDER_CREATE, SecurityAction.ORDER_EDIT) &&
                requirement.owner is OwnerConstraint.OwnerPrivate && requirement.limitUse == LimitUse.NotApplicable)
            val binding = checkNotNull(requirement.binding)
            check(binding.operationId == operationId && binding.payloadDigest == digest && binding.reasonCode == "food.operation")
            if (requirement.permission.action == SecurityAction.ORDER_CREATE) {
                check(binding.revision == Revision(0) &&
                    requirement.owner == OwnerConstraint.OwnerPrivate(originalActorId))
            }
        }
    }

    /** Closed nonphysical FOOD continuation. Only the installed source reader can bind
     * original ownership and monetary rights; today's collection is not that source. */
    private suspend fun foodCashFinancialRpcRetainedResultRequirements(original: RetainedOriginalV2, operationId: OperationId,
        originalActorId: DomainId, digest: PayloadDigest, view: CommandReadView,
        expectedKey: CommandKey? = null, expectedNormalized: StoredBytes? = null): List<AuthorizationRequirement>? {
        val key: CommandKey
        val normalized: StoredBytes
        when (original) {
            is RetainedOriginalV2.Legacy -> with(original.evidence) {
                if (nativeOriginalProvenance != null || scope != this@AtomicMasterStore.scope ||
                    this.operationId != operationId || this.originalActorId != originalActorId ||
                    semanticDigest != digest || !LocalMasterFoodCashFinancialRpcKeysV1.accepts(command) ||
                    command == StockStreamingCommandsV2.FOOD && committed) return null
                key = command
                normalized = this.normalized
            }
            is RetainedOriginalV2.Streamed -> with(original.evidence) {
                val producer = identity.producer
                if (identity.scope != scope || producer.operationId != operationId ||
                    producer.originalActorId != originalActorId || producer.semanticDigest != digest ||
                    producer.command != StockStreamingCommandsV2.FOOD) return null
                key = producer.command
                normalized = this.normalized
            }
        }
        if (view.scope != scope || expectedKey != null && key != expectedKey) return null
        val handler = handlers[key] ?: return null
        if ((handler is MasterStreamingCommandHandler) != (key == StockStreamingCommandsV2.FOOD) ||
            handler.normalize(normalized) != normalized || expectedNormalized != null && normalized != expectedNormalized ||
            !handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL, normalized) ||
            handler.physicalRecordingAction(normalized) != null ||
            semanticDigest(scope, originalActorId, key, normalized) != digest) return null
        val provider = handler as? LocalMasterRpcRetainedResultSourceAccessProvider ?: return null
        val requirements = provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL,
            normalized, view, CommandContext(scope, originalActorId, operationId, key, digest))?.toList() ?: return null
        check(requirements.size == 1)
        val requirement = requirements.single()
        check(requirement.scope == scope && requirement.permission.verb == PermissionVerb.PERFORM &&
            requirement.owner == OwnerConstraint.OwnerPrivate(originalActorId))
        val binding = checkNotNull(requirement.binding)
        check(binding.operationId == operationId && binding.payloadDigest == digest)
        val action = requirement.permission.action
        if (key == StockStreamingCommandsV2.FOOD) {
            val amount = checkNotNull(requirement.limitUse as? LimitUse.Monetary).amount
            check(amount.currency == com.ukkera.pos.types.Currency.EGP && amount.minor >= 0)
            when (action) {
                SecurityAction.COLLECTION_BEGIN -> check(binding.reasonCode == "food.collection.begin" && binding.revision.value >= 1)
                SecurityAction.COLLECTION_FINALIZE -> check(binding.reasonCode == "food.collection.finalize" &&
                    amount.minor > 0 && binding.revision.value >= 1)
                // A zero-payable collection can finalize at its authentic initial domain
                // revision. The enclosing storage generation is not that revision.
                SecurityAction.COLLECTION_FINALIZE_ZERO -> check(binding.reasonCode == "food.collection.finalize" &&
                    amount.minor == 0L && binding.revision.value >= 0)
                else -> return null
            }
        } else when (action) {
            SecurityAction.COLLECTION_ATTEST -> {
                val amount = checkNotNull(requirement.limitUse as? LimitUse.Monetary).amount
                check(amount.currency == com.ukkera.pos.types.Currency.EGP && amount.minor > 0 &&
                    binding.revision.value >= 1 && binding.reasonCode == "food.payment.apply")
            }
            SecurityAction.CASH_READ -> check(requirement.limitUse == LimitUse.NotApplicable &&
                binding.reasonCode in setOf("food.payment.pending", "food.payment.already_applied"))
            else -> return null
        }
        return requirements
    }

    private fun rpcDisplayCurrent(audience: RoomLocalDisplayRpcAudience): Boolean =
        audience.current() && localDisplayDelivery.value?.current() == true

    private fun recoveryPolicy(provider: CurrentAuthorizationProvider): ResultRecoveryPolicy = ResultRecoveryPolicy(
        provider,
        ResultRecoveryAudit { event ->
            val recordedAt = clock.now()
            requireEvidenceV1Time(recordedAt)
            dao.insertAudit(AuditRow(
                DomainId.random().value, scope.storageKey(), event.operationId.value, "result.supervisor-read",
                event.readingActorId.value, event.originalActorId.value, event.policyRevision.value,
                recordedAt.toString(), sha256(frame(listOf(event.operationId.value.encodeToByteArray(), event.originalActorId.value.encodeToByteArray()))).value,
            ))
            true
        },
    )

    private suspend fun validateProposal(handler: MasterCommandHandler, plan: ProposedOutcome.Apply, now: UtcInstant,
        view: CommandReadView): ProposedOutcome.Reject? {
        require(plan.changes.all { it.key.kind in handler.writableKinds })
        require(plan.claims.all { it.key.namespace in handler.claimNamespaces })
        (view as? TransactionView)?.validateStockReadEvidence(plan)
            ?: check(plan.changes.none { it.key.kind == StockReadEvidenceV1.KIND })
        // Inventory lease/lot expiry is a clock-dependent precondition, not a revision.
        if (plan.mustCommitBefore != null && (now >= plan.mustCommitBefore || clock.now() >= plan.mustCommitBefore)) return rejection("precondition.expired")
        for (change in plan.changes) {
            val current = view.aggregate(change.key)
            if (current?.revision != change.expectedRevision) return rejection("revision.conflict")
        }
        for (claim in plan.claims) {
            val current = view.claim(claim.key)
            when (claim) {
                is ClaimChange.Acquire -> if (current != null) return rejection("unique.conflict")
                is ClaimChange.Release -> if (current != claim.ownerId) return rejection("custody.conflict")
            }
        }
        return null
    }

    private suspend fun persist(
        branch: BranchRow,
        request: MutationRequest,
        normalized: StoredBytes,
        digest: PayloadDigest,
        principal: LocalEmployeePrincipal,
        authorization: AuthorizedAction,
        context: CommandContext,
        requirement: AuthorizationRequirement,
        outcome: ProposedOutcome,
        at: UtcInstant,
    ): Pair<OperationRow, PayloadDigest> {
        val policyRevision = authorization.policyRevision
        val business = context.businessAttribution(authorization, requirement)
        val next = JournalSequence(branch.sequence).next()
        val result = when (outcome) { is ProposedOutcome.Apply -> outcome.result; is ProposedOutcome.Reject -> outcome.result }
        val code = when (outcome) { is ProposedOutcome.Apply -> "committed"; is ProposedOutcome.Reject -> outcome.code }
        require(code != "committed" || outcome is ProposedOutcome.Apply)
        val row = OperationRow(scope.storageKey(), request.operationId.value, principal.actorId.value,
            request.command.name, request.command.version, digest.value, code, result.copyBytes(), sha256(result.bytes()).value,
            next.value, authority.epoch.value, at.toString(), business.authenticationId.value, policyRevision.value)
        dao.insertOperation(row)
        fault.at(StoreFaultPoint.AFTER_RESULT)
        val effectFrames = mutableListOf<ByteArray>()
        if (outcome is ProposedOutcome.Apply) {
            for (change in outcome.changes.sortedWith(compareBy({ it.key.kind }, { it.key.id.value }))) {
                val newRevision = (change.expectedRevision ?: Revision(0)).next()
                val hash = sha256(change.payload.bytes())
                if (change.expectedRevision == null) {
                    dao.insertAggregate(AggregateRow(scope.storageKey(), change.key.kind, change.key.id.value, newRevision.value, change.schema, change.payload.copyBytes(), hash.value))
                } else {
                    check(dao.replaceAggregate(scope.storageKey(), change.key.kind, change.key.id.value, change.expectedRevision.value, newRevision.value,
                        change.schema, change.payload.copyBytes(), hash.value) == 1)
                }
                effectFrames += frame(listOf(change.key.kind, change.key.id.value, newRevision.value.toString(), change.schema).map(String::encodeToByteArray) + change.payload.copyBytes())
            }
            for (claim in outcome.claims.sortedWith(compareBy({ it.key.namespace }, { it.key.key }))) {
                when (claim) {
                    is ClaimChange.Acquire -> dao.insertClaim(ClaimRow(scope.storageKey(), claim.key.namespace, claim.key.key, claim.ownerId.value))
                    is ClaimChange.Release -> check(dao.releaseClaim(scope.storageKey(), claim.key.namespace, claim.key.key, claim.ownerId.value) == 1)
                }
                effectFrames += frame(listOf(if (claim is ClaimChange.Acquire) "claim.acquire" else "claim.release", claim.key.namespace, claim.key.key, claim.ownerId.value).map(String::encodeToByteArray))
            }
        }
        fault.at(StoreFaultPoint.AFTER_EFFECTS)
        val jobs = (outcome as? ProposedOutcome.Apply)?.jobs.orEmpty()
        val envelope = frame(listOf("ukkera.journal/v1", scope.storageKey(), authority.assignmentId.value, authority.epoch.value.toString(), next.value.toString(),
            request.operationId.value, principal.actorId.value, request.command.name, request.command.version.toString(),
            business.authenticationId.value, policyRevision.value.toString(), digest.value, row.outcome, row.committedAt, row.resultHash, branch.journalHash).map(String::encodeToByteArray) +
            listOf(normalized.copyBytes(), result.copyBytes(), frame(effectFrames), frame(jobs.map { frame(listOf(it.kind.encodeToByteArray(), it.body.copyBytes())) })))
        val envelopeHash = sha256(envelope)
        dao.insertJournal(JournalRow(scope.storageKey(), next.value, request.operationId.value, authority.assignmentId.value, authority.epoch.value,
            branch.journalHash, envelopeHash.value, envelope))
        fault.at(StoreFaultPoint.AFTER_JOURNAL)
        dao.insertAudit(AuditRow(DomainId.random().value, scope.storageKey(), request.operationId.value, "command.$code", principal.actorId.value,
            principal.actorId.value, policyRevision.value, row.committedAt, digest.value))
        fault.at(StoreFaultPoint.AFTER_AUDIT)
        val upload = frame(listOf("ukkera.journal-upload/v1", scope.branchId.value,
            authority.epoch.value.toString(), next.value.toString(), envelopeHash.value).map(String::encodeToByteArray))
        dao.insertOutbox(listOf(OutboxRow(scope.storageKey(), request.operationId.value, 0, SYNC_JOB, upload, sha256(upload).value)) + jobs.mapIndexed { index, job ->
            OutboxRow(scope.storageKey(), request.operationId.value, index + 1, job.kind, job.body.copyBytes(), sha256(job.body.bytes()).value)
        })
        fault.at(StoreFaultPoint.AFTER_OUTBOX)
        check(dao.advance(scope.storageKey(), branch.revision, Revision(branch.revision).next().value, next.value, envelopeHash.value) == 1)
        return row to envelopeHash
    }

    private fun recoveryState(branch: BranchRow): RecoveryAdmissionState = Json.decodeFromString<RecoveryAdmissionState>(branch.recoveryJson).also {
        check(it.branchId == scope.branchId)
    }

    private fun observe(row: BranchRow, recovery: RecoveryAdmissionState): BranchObservation = BranchObservation(scope,
        AuthorityIdentity(DomainId(row.assignmentId), DomainId(row.deviceId), Revision(row.epoch)), Revision(row.revision), JournalSequence(row.sequence),
        PayloadDigest(row.journalHash), recovery.revision, runtimeHistoryOwner?.activeFrameForStore(this))

    private fun matches(row: OperationRow, identity: OperationIdentity): Boolean = identity.semanticDigestVersion == 1 &&
        row.actorId == identity.originalActorId.value && row.digest == identity.semanticSha256

    /** All fields originate in the SAME validated retained pair, never today's projection. */
    private fun originalOperation(evidence: RetainedCommandEvidence): OperationRow = OperationRow(
        evidence.scope.storageKey(), evidence.operationId.value, evidence.originalActorId.value,
        evidence.command.name, evidence.command.version, evidence.semanticDigest.value, evidence.code,
        evidence.result.copyBytes(), evidence.resultHash.value, evidence.sequence.value,
        evidence.authorityEpoch.value, evidence.recordedAt.toString(), evidence.storedAuthenticationId.value,
        evidence.storedPolicyRevision.value,
    )

    private fun recorded(row: OperationRow, replayed: Boolean,
                         physicalStart: PhysicalRecordingCommittedStart? = null): MutationReply.Recorded {
        check(sha256(row.result).value == row.resultHash) { "Stored result integrity failed" }
        check(physicalStart == null || !replayed && row.outcome == "committed")
        return MutationReply.Recorded(OperationId(row.operationId), DomainId(row.actorId), row.outcome == "committed", row.outcome,
            StoredBytes(row.result), Revision(row.epoch), JournalSequence(row.sequence), UtcInstant.parseCanonical(row.committedAt), replayed,
            physicalStart)
    }

    private sealed interface KernelReply {
        data class Recorded(val row: OperationRow, val replayed: Boolean, val mustCommitBefore: UtcInstant? = null,
                            val physicalOutcome: PhysicalRecordingOutcome? = null, val journalHash: PayloadDigest? = null) : KernelReply
        class PhysicalAdmission(val admission: PhysicalRecordingAdmission) : KernelReply
        data class Deferred(val reason: DeferredReason) : KernelReply
        data object Conflict : KernelReply
    }
}

/**
 * Ordinary handlers retain only CommandReadView. Protected current authorization can bind only
 * the exact owner-bearing view created inside the kernel's actual writer. The legacy two-arg
 * constructor supplies no protected authority and remains useful for ordinary typed read tests.
 */
internal class TransactionView private constructor(
    override val scope: BranchScope,
    private val dao: MasterDao,
    private val writer: WriterOwner?,
    private val physicalTrace: PhysicalInstructionReadTrace? = null,
    private val nativeOriginalFrame: RoomRuntimeHistoryFrame? = null,
) : StockRelationalReadView {
    internal constructor(scope: BranchScope, dao: MasterDao) : this(scope, dao, null)

    private class WriterOwner(
        val database: MasterRoomDatabase,
        val connection: Transactor,
        val coroutineContext: CoroutineContext,
        val principal: LocalEmployeePrincipal,
        val historyFrame: RoomRuntimeHistoryFrame?,
    )

    private enum class Phase { BODY, PREPARING, PREPARED, FINISHED, CLOSED }
    private enum class Failure { AUTHORIZATION, STORAGE }

    private var stockLoan: StockSqlLoan? = null
    private var stockCodec: StockCanonicalProjectionV2? = null
    private var stockAccess: StockRelationalAccess? = null
    private var phase = Phase.BODY
    private var failure: Failure? = null
    private var cancellation: CancellationException? = null
    private class SerialRegistrationClockOwner(val store: AtomicMasterStore, val clock: MasterClock)
    private var serialRegistrationClock: SerialRegistrationClockOwner? = null
    private var localAuthorization: RoomCurrentLocalAuthorizationBinding? = null
    private var actionApproval: RoomActionApprovalBinding? = null
    private var actionApprovalId: DomainId? = null
    private var actionApprovalRequirement: AuthorizationRequirement? = null
    private var physicalAuthorization: PhysicalInstructionAuthorizationFrame? = null
    private var localDisplayRpcAudience: RoomLocalDisplayRpcAudience? = null
    private var localDisplayOwner: RoomLocalDisplayDeliveryOwner? = null
    private var localDisplayAccess: MasterLocalDisplayDeliveryAccess? = null
    private var localDisplayReadOwner: RoomLocalDisplayDeliveryOwner? = null
    private var localDisplayPrepared = false
    private var localDisplayUsed = false
    private var localDisplayDenied = false
    private var localDisplayBusy = false
    private var localDisplayIntentReads = 0

    internal companion object {
        internal fun forWriter(
            scope: BranchScope,
            dao: MasterDao,
            database: MasterRoomDatabase,
            connection: Transactor,
            coroutineContext: CoroutineContext,
            principal: LocalEmployeePrincipal,
            physicalTrace: PhysicalInstructionReadTrace? = null,
        ): TransactionView {
            val history = database.runtimeHistoryOwner?.frameFor(connection)
            return TransactionView(scope, dao, WriterOwner(database,connection,coroutineContext,principal,history),physicalTrace).also {
                history?.bindView(it,coroutineContext,principal)
            }
        }

        /** No employee authority: exact original-history frame, same real connection and loan. */
        internal fun forNativeOriginal(scope: BranchScope, dao: MasterDao, frame: RoomRuntimeHistoryFrame,
            store: AtomicMasterStore, invocation: NativeOriginalIntakeInvocation,
            lease: NativeOriginalRetainedLease): TransactionView {
            val view = TransactionView(scope, dao, null, nativeOriginalFrame = frame)
            frame.bindNativeOriginalView(store, invocation, lease, view)
            return view
        }
    }

    internal fun bindStock(store: AtomicMasterStore, database: MasterRoomDatabase, codec: StockCanonicalProjectionV2) {
        ensureBody()
        val actual = writer ?: unavailable()
        check(actual.database === database && actual.principal.scope == store.scope && scope == store.scope)
        val existing = stockLoan
        if (existing != null) { check(existing.owner === store && stockCodec === codec); return }
        val loan = StockSqlLoan(store, scope, DomainId.random(), actual.connection, actual.coroutineContext, physicalTrace)
        stockLoan = loan; stockCodec = codec; stockAccess = StockRelationalAccess(loan, codec)
    }

    /** Called by the actual kernel with its existing clock, never a handler-supplied source. */
    internal fun bindSerialRegistrationClock(store: AtomicMasterStore, database: MasterRoomDatabase,
        clock: MasterClock) {
        ensureBody()
        val actual = writer ?: unavailable()
        check(actual.database === database && actual.principal.scope == store.scope && scope == store.scope)
        val existing = serialRegistrationClock
        if (existing != null) {
            check(existing.store === store && existing.clock === clock)
            return
        }
        serialRegistrationClock = SerialRegistrationClockOwner(store, clock)
    }

    internal fun bindLocalDisplayDelivery(store: AtomicMasterStore, database: MasterRoomDatabase,
        owner: RoomLocalDisplayDeliveryOwner) {
        ensureBody()
        val actual = writer ?: unavailable()
        check(actual.database === database && owner.store === store && owner.database === database &&
            store.scope == scope && actual.principal.scope == scope)
        val existing = localDisplayOwner
        check(existing == null || existing === owner)
        localDisplayOwner = owner
    }

    internal fun bindLocalDisplayRpcAudience(audience: RoomLocalDisplayRpcAudience) {
        ensureBody()
        val actual = writer ?: denyLocalDisplay()
        val owner = localDisplayOwner ?: denyLocalDisplay()
        if (localDisplayRpcAudience != null || !audience.belongsTo(actual.database, actual.principal) ||
            owner.database !== actual.database || owner.history.originalScope != actual.principal.scope)
            denyLocalDisplay()
        localDisplayRpcAudience = audience
        localDisplayPrepared = true
        localDisplayUsed = true
        if (!owner.current() || !audience.current()) denyLocalDisplay()
    }

    private fun displayBelongsTo(owner: RoomLocalDisplayDeliveryOwner, actual: WriterOwner): Boolean =
        owner.belongsTo(actual.database, actual.principal) || localDisplayRpcAudience?.let {
            owner.database === actual.database && it.belongsTo(actual.database, actual.principal)
        } == true

    private fun displayCurrent(owner: RoomLocalDisplayDeliveryOwner): Boolean =
        owner.current() && (localDisplayRpcAudience?.current() ?: true)

    internal fun localDisplayDeliveryAccess(): MasterLocalDisplayDeliveryAccess? {
        ensureBody()
        val actual = writer ?: return null
        val owner = localDisplayOwner ?: return null
        if (localAuthorization == null || !displayBelongsTo(owner, actual)) return null
        if (!displayCurrent(owner)) {
            if (localDisplayReadOwner != null) denyLocalDisplay()
            return null
        }
        localDisplayPrepared = true
        return localDisplayAccess ?: MasterLocalDisplayDeliveryAccess(this, owner).also { localDisplayAccess = it }
    }

    /** The concrete registered reader owns this entire projection; absence cannot fall back. */
    internal fun enterLocalDisplayRead(reader: MasterLocalDisplayReadHandler) {
        val access = localDisplayDeliveryAccess() ?: denyLocalDisplay()
        check(access.owner.reader === reader && localDisplayReadOwner == null)
        localDisplayReadOwner = access.owner
    }

    /** Preparation samples support, but only actual current admission relies on it. Earlier
     * identity/conflict/permission/recovery refusals must not become source refusals at return. */
    internal fun armLocalDisplaySupport() {
        ensureBody()
        if (!localDisplayPrepared) return
        localDisplayUsed = true
        if (localDisplayOwner?.let(::displayCurrent) != true) denyLocalDisplay()
    }

    internal suspend fun consumeLocalDisplayIntent(owner: RoomLocalDisplayDeliveryOwner,
        operationId: OperationId, ordinal: Int, expectedBody: StoredBytes) {
        try {
            requireLocalDisplayBody(owner)
            if (localDisplayBusy || localDisplayIntentReads >= MASTER_LOCAL_DISPLAY_MAX_INTENTS) unavailable()
            localDisplayIntentReads++
            localDisplayBusy = true
            requireMasterLocalDisplayIntent(dao, scope, operationId, ordinal, expectedBody) {
                requireLocalDisplayBody(owner)
            }
            requireLocalDisplayBody(owner)
        } catch (cancelled: CancellationException) {
            if (cancellation == null) cancellation = cancelled
            throw cancelled
        } catch (_: MasterLocalDisplayDeliveryUnavailable) {
            denyLocalDisplay()
        } catch (_: RoomCurrentLocalAuthorizationDenied) {
            deny()
        } catch (_: Exception) {
            unavailable()
        } finally { localDisplayBusy = false }
    }

    private suspend fun requireLocalDisplayBody(owner: RoomLocalDisplayDeliveryOwner) {
        ensureBody()
        val actual = writer ?: unavailable()
        if (localDisplayOwner !== owner || localDisplayReadOwner !== owner || localAuthorization == null ||
            !displayBelongsTo(owner, actual) || currentCoroutineContext() != actual.coroutineContext ||
            !actual.connection.inTransaction()) unavailable()
        if (!displayCurrent(owner)) denyLocalDisplay()
    }

    private fun requireLocalDisplayBeforeReturn() {
        if (localDisplayUsed && localDisplayOwner?.let(::displayCurrent) != true) denyLocalDisplay()
    }

    private fun requireLocalDisplayFinalState() {
        if (localDisplayUsed && (localDisplayOwner?.stateCurrent() != true ||
            localDisplayRpcAudience?.stateCurrent() == false)) denyLocalDisplay()
    }

    private fun denyLocalDisplay(): Nothing {
        localDisplayDenied = true
        throwIfFailed()
        masterLocalDisplayUnavailable()
    }

    internal fun bindNativeStock(store: AtomicMasterStore, frame: RoomRuntimeHistoryFrame, codec: StockCanonicalProjectionV2) {
        ensureBody()
        check(writer == null && nativeOriginalFrame === frame && stockLoan == null && store.scope == scope)
        val loan = StockSqlLoan(store, scope, DomainId.random(), frame.connection, frame.coroutineContext)
        stockLoan = loan; stockCodec = codec; stockAccess = StockRelationalAccess(loan, codec)
    }

    override suspend fun stockRelationalAccess(): StockRelationalAccess {
        ensureBody()
        // Only the kernel-installed loan is admitted. Its actual rows/claims/originals and
        // fixed ranges are traced, and incomplete page traversal forbids trace finalization.
        if (physicalAuthorization != null) { physicalTrace?.fail(); unavailable() }
        val access = stockAccess ?: unavailable()
        access.loan.requireBody()
        return access
    }

    internal fun bindStockCommand(context: CommandContext) {
        ensureBody()
        stockLoan?.let { loan ->
            check(context.scope == scope && loan.commandContext == null && loan.readCapture == null)
            loan.commandContext = context
        }
    }
    internal suspend fun validateStockReadEvidence(plan: ProposedOutcome.Apply) {
        ensureBody()
        val access = stockAccess
        if (access == null) check(plan.changes.none { it.key.kind == StockReadEvidenceV1.KIND })
        else access.validateReadEvidence(plan)
    }

    internal suspend fun originalForKernel(operationId: OperationId): RetainedOriginalV2? =
        tracedRead(PhysicalReadDependency.Retained(operationId)) {
            val access = stockAccess
            if (access == null) retainedCommand(operationId)?.let { RetainedOriginalV2.Legacy(it) }
            else access.originals.read(operationId).also { ensureBody() }
        }

    internal fun ownedBy(database: MasterRoomDatabase, principal: LocalEmployeePrincipal): Boolean {
        ensureBody()
        val owner = writer ?: return false
        return owner.database === database && owner.principal === principal
    }

    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = tracedRead(PhysicalReadDependency.Aggregate(key)) {
        readCurrentAggregate(dao, scope, key, ::ensureBody).also {
            (writer?.historyFrame ?: nativeOriginalFrame)?.recordAggregate(key,it)
            stockLoan?.readCapture?.aggregate(key, it)
        }
    }

    override suspend fun retailCustomerReceiptEventMembership(saleId: DomainId): List<RuntimeRetailCustomerReceiptEventLocator>? =
        tracedRead(PhysicalReadDependency.Range(RuntimeRetailCustomerReceiptMembershipProfile.EVENT_KIND)) {
            // Preserve the physical range dependency. Only selected points become ordinary
            // row observations; historical receipt producers still verify the complete BEFORE
            // membership independently, including all old retained census dependencies.
            val owner = writer ?: return@tracedRead null
            val frame = owner.historyFrame ?: return@tracedRead null
            val context = currentCoroutineContext()
            context.ensureActive()
            check(context == owner.coroutineContext && owner.principal.scope == scope && owner.connection.inTransaction())
            frame.retailCustomerReceiptEventMembership(this, saleId).also { ensureBody() }
        }

    override suspend fun originalNativeBusinessPublications(): NativeCompanyBusinessPublicationSnapshot? {
        ensureBody()
        // Detached DAO readers and native-original mirrors cannot create this employee
        // command observation. Only the actual borrowed writer has a registered frame.
        val owner = writer ?: return null
        val frame = owner.historyFrame ?: return null
        val actual = currentCoroutineContext()
        actual.ensureActive()
        check(owner.principal.scope == scope && actual == owner.coroutineContext)
        return frame.originalBusinessPublications(this, actual)
    }

    override suspend fun qualifiedSerialRegistrationTime(): QualifiedSerialRegistrationTime? {
        ensureBody()
        val actual = writer ?: return null
        val bound = serialRegistrationClock ?: return null
        // A detached view, native-original mirror or unbound authorization source cannot
        // obtain even the serial-specific observation. Action authorization remains separate.
        if (localAuthorization == null) return null
        val context = currentCoroutineContext()
        context.ensureActive()
        check(context == actual.coroutineContext && actual.principal.scope == scope &&
            bound.store.scope == scope && actual.connection.inTransaction())
        val qualified = (bound.clock as? SerialRegistrationMasterClock)?.qualifiedSerialRegistrationTime()
        ensureBody()
        return qualified
    }

    override suspend fun claim(key: ClaimKey): DomainId? = tracedRead(PhysicalReadDependency.Claim(key)) {
        readCurrentClaim(dao, scope, key, ::ensureBody).also {
            (writer?.historyFrame ?: nativeOriginalFrame)?.recordClaim(key,it)
            stockLoan?.readCapture?.append(StreamedSourceObservationV2.Claim(key, it))
        }
    }

    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage = tracedRead(PhysicalReadDependency.Range(kind)) {
        requireProtocolName(kind)
        require(limit in 1..100) { "Aggregate key page exceeds supported bounds" }
        ensureBody()
        if (kind in StockRelationalKindsV2.all) physicalTrace?.stockKeysStart(kind, after)
        val rows = dao.aggregateKeys(scope.storageKey(), kind, after?.value, limit + 1)
        ensureBody()
        check(rows.size <= limit + 1)
        val ids = rows.map { DomainId(checkNotNull(it.boundedId) { "Current stored key unavailable" }) }
        check(ids.zipWithNext().all { (a, b) -> a.value < b.value })
        check(after == null || ids.all { it.value > after.value })
        val selected = ids.take(limit)
        val next = if (ids.size > limit) selected.last() else null
        if (kind in StockRelationalKindsV2.all) physicalTrace?.stockKeysEnd(kind, next)
        AggregateKeyPage(selected.map { AggregateKey(kind, it) }, next)
    }

    override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage = nonPhysicalOwnedRead {
        readCurrentClaimKeys(dao, scope, namespace, prefix, after, limit, ::ensureBody)
    }

    internal suspend fun handoffRecipients(orderId: DomainId, after: DomainId?, limit: Int): LocalHandoffRecipientPage = nonPhysicalOwnedRead {
        checkNotNull(localAuthorization).handoffRecipients(orderId, after, limit)
    }
    internal suspend fun handoffRecipient(orderId: DomainId, recipientId: DomainId): LocalHandoffRecipient? = nonPhysicalOwnedRead {
        checkNotNull(localAuthorization).handoffRecipient(orderId, recipientId)
    }
    internal suspend fun handoffInboxTarget(): DomainId? = nonPhysicalOwnedRead {
        checkNotNull(localAuthorization).handoffInboxTarget()
    }
    internal suspend fun issueHandoffCursor(phase: String, after: String?): String = nonPhysicalOwnedRead {
        checkNotNull(localAuthorization).issueHandoffCursor(phase, after)
    }
    internal suspend fun resumeHandoffCursor(phase: String, handle: String): LocalHandoffCursorPosition? = nonPhysicalOwnedRead {
        checkNotNull(localAuthorization).resumeHandoffCursor(phase, handle)
    }
    /** R2 is nonphysical. Never silently add an untracked range/protected dependency to B4. */
    private suspend fun <T> nonPhysicalOwnedRead(read: suspend () -> T): T = try {
        ensureBody()
        if (writer == null || localAuthorization == null || physicalTrace != null || physicalAuthorization != null) {
            physicalTrace?.fail(); unavailable()
        }
        read().also { ensureBody() }
    } catch (cancelled: CancellationException) {
        if (cancellation == null) cancellation = cancelled
        throw cancelled
    } catch (_: RoomCurrentLocalAuthorizationDenied) { deny() }
    catch (_: Exception) { unavailable() }

    override suspend fun foodCheckRejectedOriginalSources(operationId: OperationId): RuntimeHistoricalFoodCheckRejectedSources? =
        tracedRead(PhysicalReadDependency.Retained(operationId)) {
            // Only this admitted native writer can originate the immutable BEFORE projection.
            // A non-native/legacy read has no source proof, not a successful empty cut.
            writer?.historyFrame?.foodCheckRejectedOriginalSources(this, operationId)
        }

    override suspend fun foodTableAssignmentRejectedOriginalSources(operationId: OperationId): RuntimeHistoricalFoodTableAssignmentRejectedSources? =
        tracedRead(PhysicalReadDependency.Retained(operationId)) {
            // Only the exact native frame can supply the original BEFORE literals;
            // absent native custody is unavailable, not a substitute current-order read.
            writer?.historyFrame?.foodTableAssignmentRejectedOriginalSources(this, operationId)
        }

    override suspend fun foodPaymentAlreadyAppliedOriginalSources(operationId: OperationId): RuntimeHistoricalFoodPaymentAlreadyAppliedSources? =
        tracedRead(PhysicalReadDependency.Retained(operationId)) {
            // Only the actual unmutated native frame can select the refused operation's
            // original observations; retained ancestry or current rows cannot replace them.
            writer?.historyFrame?.foodPaymentAlreadyAppliedOriginalSources(this, operationId)
        }

    override suspend fun savedOriginalSetup(): SavedOriginalSetupV1? = nonPhysicalOwnedRead {
        val owner = writer?.database?.runtimeHistoryOwner ?: return@nonPhysicalOwnedRead null
        if (owner.originalScope != scope || owner.compositionProfile.mode !=
            com.ukkera.pos.security.LocalSetupCompositionMode.LIVE) return@nonPhysicalOwnedRead null
        val record = owner.originalRecord.record
        val receipt = record.receipt ?: return@nonPhysicalOwnedRead null
        val definition = record.definition ?: return@nonPhysicalOwnedRead null
        val coordinates = receipt.core.allocation.coordinates
        check(coordinates.scope == scope)
        check(definition.definitionDigest == receipt.core.definitionDigest) {
            "Saved original definition/receipt identity diverged"
        }
        SavedOriginalSetupV1(
            scope = scope,
            installationId = coordinates.installationId,
            assignmentId = coordinates.assignmentId,
            deviceId = coordinates.deviceId,
            firstActorId = coordinates.firstActorId,
            originalRequestId = receipt.core.originalRequestId,
            issuedAttemptId = receipt.core.issuedAttemptId,
            attemptSequence = receipt.core.attemptSequence,
            definitionDigest = receipt.core.definitionDigest,
            genesisDigest = receipt.genesis,
            receiptDigest = receipt.digest,
            installationAuthorityEpoch = receipt.core.authorityEpoch,
            currentAuthorityEpoch = owner.originalAuthority.epoch,
            installedAt = UtcInstant(kotlin.time.Instant.fromEpochMilliseconds(
                receipt.core.installedAtOsUtcEpochMillis)),
            nativeSettlement = record.pending == null && record.issuerAcknowledgement != null &&
                record.parent != null && record.firstChild != null,
        )
    }

    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = tracedRead(PhysicalReadDependency.Retained(operationId)) {
        val operationMetadata = retainedRead { dao.retainedOperationMetadata(scope.storageKey(), operationId.value) }
        val journalMetadata = retainedRead { dao.retainedJournalMetadata(scope.storageKey(), operationId.value) }
        if (operationMetadata == null && journalMetadata == null) return@tracedRead null
        if (operationMetadata == null || journalMetadata == null) {
            retainedCommandUnavailable(RetainedCommandIssue.INCOMPLETE_PAIR)
        }
        if (!operationMetadata.storageValid || operationMetadata.byteCount !in 0L..MAX_STORED_BYTES.toLong() ||
            !journalMetadata.storageValid || journalMetadata.byteCount !in 0L..MAX_RETAINED_ENVELOPE_BYTES.toLong()) {
            retainedCommandUnavailable(RetainedCommandIssue.INVALID_STORAGE)
        }
        // Both members were checked before either BLOB is hydrated. All queries
        // borrow the caller's same IMMEDIATE transaction; no new read transaction.
        val operation = retainedRead { dao.operation(scope.storageKey(), operationId.value) }
        val journal = retainedRead { dao.retainedJournal(scope.storageKey(), operationId.value) }
        // A disappearing pair is not the original both-absent observation.
        if (operation == null || journal == null) retainedCommandUnavailable(RetainedCommandIssue.INCOMPLETE_PAIR)
        decodeRetainedCommand(scope, operationId, operation, journal)
    }

    private suspend fun <T> tracedRead(dependency: PhysicalReadDependency, read: suspend () -> T): T {
        return try {
            ensureBody()
            nativeOriginalFrame?.requireNativeOriginalBody(this, currentCoroutineContext())
            physicalTrace?.add(dependency) // Before even an absent read; never truncate a footprint.
            read().also { nativeOriginalFrame?.requireNativeOriginalBody(this, currentCoroutineContext()) }
        } catch (failure: Throwable) { physicalTrace?.fail(); throw failure }
    }

    internal fun attachPhysicalAuthorization(registry: PhysicalInstructionPublicationRegistry,
        binding: PhysicalInstructionNativeBinding, resources: Set<RecoveryResourceKey>, businessDeadlineExclusive: UtcInstant?) {
        ensureBody()
        val owner = writer ?: unavailable()
        if (physicalAuthorization != null || actionApproval != null || localAuthorization == null ||
            !binding.origin.matches(owner.principal) || binding.scope != scope || binding.denial.denied) throw PhysicalRecordingFinalDenied()
        val dependencies = (physicalTrace ?: throw PhysicalRecordingFinalDenied()).freeze(resources)
        physicalAuthorization = PhysicalInstructionAuthorizationFrame(registry, binding, dependencies, businessDeadlineExclusive)
    }

    internal suspend fun currentLocalAuthorization(
        source: RoomCurrentLocalAuthorizationSource,
        principal: AuthenticatedPrincipal,
    ): AuthorizationSnapshot = try {
        ensureBody()
        val owner = writer ?: unavailable()
        // Bind the selected immutable principal from the kernel, not the first caller's choice.
        if (principal !== owner.principal) unavailable()
        val selected = owner.principal
        if (selected.scope != scope || selected.authenticationPurpose != AuthenticationPurpose.INTERACTIVE) deny()
        val existing = localAuthorization
        if (existing != null && !existing.belongsTo(source, principal)) unavailable()
        val binding = existing ?: source.bind(owner.database, owner.connection, selected, owner.coroutineContext).also {
            // Attach before the first suspension so even a caught/reentrant read retains poison.
            localAuthorization = it
        }
        binding.current()
    } catch (cancelled: CancellationException) {
        if (cancellation == null) cancellation = cancelled
        throw cancelled
    } catch (_: RoomCurrentLocalAuthorizationDenied) {
        deny()
    } catch (_: Exception) {
        unavailable()
    }

    /** A capture is available only on this actual original writer/principal and its already
     * successful protected authorization binding. Native-original import has no such binding. */
    internal fun captureRuntimeCommandAuthorization(frame: RoomRuntimeHistoryFrame,
        principal: LocalEmployeePrincipal): RuntimeCommandAuthorizationSnapshotV1 = try {
        ensureBody()
        val owner = writer ?: unavailable()
        if (owner.historyFrame !== frame || owner.principal !== principal) unavailable()
        (localAuthorization ?: unavailable()).captureRuntimeCommandAuthorization()
    } catch (cancelled: CancellationException) {
        if (cancellation == null) cancellation = cancelled
        throw cancelled
    } catch (_: RoomCurrentLocalAuthorizationDenied) {
        deny()
    } catch (_: Exception) {
        unavailable()
    }

    internal fun captureRuntimeApprovalUse(frame: RoomRuntimeHistoryFrame,
        principal: LocalEmployeePrincipal, applied: Boolean): RuntimeActionApprovalUseCapture {
        ensureBody()
        val owner = writer ?: unavailable()
        if (owner.historyFrame !== frame || owner.principal !== principal) unavailable()
        return (actionApproval ?: unavailable()).captureRuntimeApprovalUse(applied)
    }

    /** Only this actual writer/principal may bind the exact protected approval source. */
    internal suspend fun prepareActionApproval(
        source: RoomTransactionApprovalSource,
        principal: LocalEmployeePrincipal,
        approvalId: DomainId,
        requirement: AuthorizationRequirement,
    ): TransactionApprovalDecision = try {
        ensureBody()
        val owner = writer ?: unavailable()
        if (principal !== owner.principal || principal.scope != scope || requirement.scope != scope ||
            localAuthorization == null) unavailable()
        val previous = actionApproval
        if (previous != null && (!previous.belongsTo(source, principal) || actionApprovalId != approvalId ||
                actionApprovalRequirement != requirement)) unavailable()
        val binding = previous ?: source.bind(owner.database, owner.connection, principal,
            owner.coroutineContext, this, approvalId, requirement).also {
                // Attach before suspension; caught cancellation/storage failures cannot detach it.
                actionApproval = it
                actionApprovalId = approvalId
                actionApprovalRequirement = requirement
            }
        binding.prepare()
    } catch (cancelled: CancellationException) {
        if (cancellation == null) cancellation = cancelled
        throw cancelled
    } catch (_: RoomCurrentLocalAuthorizationDenied) {
        deny()
    } catch (_: Exception) {
        unavailable()
    }

    internal suspend fun consumeActionApproval(
        source: RoomTransactionApprovalSource,
        permit: TransactionApprovalPermit,
    ): Boolean = try {
        ensureBody()
        val owner = writer ?: unavailable()
        val binding = actionApproval ?: unavailable()
        if (permit.owner !== binding || permit.approvalId != actionApprovalId ||
            !binding.belongsTo(source, owner.principal)) unavailable()
        binding.consume(permit)
    } catch (cancelled: CancellationException) {
        if (cancellation == null) cancellation = cancelled
        throw cancelled
    } catch (_: RoomCurrentLocalAuthorizationDenied) {
        deny()
    } catch (_: Exception) {
        unavailable()
    }

    /** Called on every NORMAL body return only. No arbitrary finalizer callback is accepted. */
    internal suspend fun prepareNormalReturn() {
        try {
            ensureBody()
            phase = Phase.PREPARING
            stockLoan?.prepareNormalReturn()
            actionApproval?.prepareNormalReturn()
            val binding = localAuthorization
            if (binding != null) {
                binding.prepareNormalReturn()
                physicalAuthorization?.prepare(binding, writer?.coroutineContext ?: unavailable())
            } else if (writer != null) {
                // Generic test/trust adapters gain no protected authority from this path.
                check(writer.connection.inTransaction())
                writer.coroutineContext.ensureActive()
            } else if (nativeOriginalFrame != null) {
                nativeOriginalFrame.requireNativeOriginalBody(this, currentCoroutineContext())
                check(nativeOriginalFrame.connection.inTransaction())
                nativeOriginalFrame.coroutineContext.ensureActive()
            }
            requireLocalDisplayBeforeReturn()
            throwIfFailed()
            check(phase == Phase.PREPARING) { "Transaction view has expired" }
            (writer?.historyFrame ?: nativeOriginalFrame)?.prepareFromView(this)
            phase = Phase.PREPARED
        } catch (denied: PhysicalRecordingFinalDenied) {
            physicalTrace?.fail()
            throw denied
        } catch (cancelled: CancellationException) {
            if (cancellation == null) cancellation = cancelled
            throw cancelled
        } catch (_: MasterLocalDisplayDeliveryUnavailable) {
            denyLocalDisplay()
        } catch (_: RoomCurrentLocalAuthorizationDenied) {
            deny()
        } catch (_: Exception) {
            unavailable()
        }
    }

    /** No SQL, provider-health query or suspension after the binding's final clock sample. */
    internal fun finishNow() {
        try {
            writer?.coroutineContext?.ensureActive()
            nativeOriginalFrame?.let { it.requireNativeOriginalBody(this, it.coroutineContext) }
            throwIfFailed()
            check(phase == Phase.PREPARED) { "Transaction view has expired" }
            if (physicalAuthorization != null && physicalTrace?.readyForFinalization() != true) throw PhysicalRecordingFinalDenied()
            // SDK activity includes its own age/clock callback. Sample it before the last
            // authoritative clock; afterwards check only captured one-way retirement state.
            // POS access/work expiry uses that final native witness. SDK expiry is also
            // independently enforced at result release; its clock is not correlated here.
            requireLocalDisplayBeforeReturn()
            stockLoan?.finishNow()
            val finalObservation = localAuthorization?.finishNow()
            actionApproval?.let { it.finishAt(finalObservation ?: unavailable()) }
            if (physicalAuthorization != null && physicalTrace?.readyForFinalization() != true) throw PhysicalRecordingFinalDenied()
            physicalAuthorization?.let { it.finishAt(finalObservation ?: unavailable()) }
            requireLocalDisplayFinalState()
            throwIfFailed()
            check(phase == Phase.PREPARED) { "Transaction view has expired" }
            (writer?.historyFrame ?: nativeOriginalFrame)?.finishFromView(this)
            phase = Phase.FINISHED
        } catch (denied: PhysicalRecordingFinalDenied) {
            physicalTrace?.fail()
            throw denied
        } catch (cancelled: CancellationException) {
            if (cancellation == null) cancellation = cancelled
            throw cancelled
        } catch (_: MasterLocalDisplayDeliveryUnavailable) {
            denyLocalDisplay()
        } catch (_: RoomCurrentLocalAuthorizationDenied) {
            deny()
        } catch (_: Exception) {
            unavailable()
        }
    }

    private suspend fun <T> retainedRead(read: suspend () -> T): T {
        ensureBody()
        val value = try {
            read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            retainedCommandUnavailable(RetainedCommandIssue.INVALID_STORAGE)
        }
        ensureBody()
        return value
    }

    private fun ensureBody() {
        writer?.coroutineContext?.ensureActive()
        nativeOriginalFrame?.coroutineContext?.ensureActive()
        throwIfFailed()
        check(phase == Phase.BODY) { "Transaction view has expired" }
    }

    private fun deny(): Nothing {
        if (failure == null) failure = Failure.AUTHORIZATION
        throwIfFailed()
        currentLocalAuthorizationDenied()
    }

    private fun unavailable(): Nothing {
        if (failure == null) failure = Failure.STORAGE
        throwIfFailed()
        protectedLocalUnavailable()
    }

    private fun throwIfFailed() {
        cancellation?.let { throw it }
        when (failure) {
            Failure.AUTHORIZATION -> currentLocalAuthorizationDenied()
            Failure.STORAGE -> protectedLocalUnavailable()
            null -> Unit
        }
        if (localDisplayDenied) masterLocalDisplayUnavailable()
    }

    /** Cleanup still owns the exact borrowed connection. A failed scratch DROP/close
     * escapes before the outer return, forbidding publication or connection reuse. */
    suspend fun close() {
        phase = Phase.CLOSED
        physicalTrace?.fail()
        physicalAuthorization = null
        localDisplayRpcAudience = null
        localDisplayAccess = null
        localDisplayReadOwner = null
        try { stockLoan?.abortBeforeReturn() }
        finally {
            try { actionApproval?.close() }
            finally { localAuthorization?.close() }
        }
    }
}

/** Public only to trusted typed handlers; canonical input comes from their own decoder. */
public fun semanticDigest(scope: BranchScope, originalActorId: DomainId, key: CommandKey, normalized: StoredBytes): PayloadDigest =
    sha256(frame(listOf("ukkera.command/v1", scope.storageKey(), originalActorId.value, key.name, key.version.toString()).map(String::encodeToByteArray) + normalized.copyBytes()))

/** Separate from command digests: queries cannot be confused with mutation/recovery identity. */
public fun queryDigest(scope: BranchScope, actorId: DomainId, key: ReadKey, normalized: StoredBytes): PayloadDigest =
    sha256(frame(listOf("ukkera.query/v1", scope.storageKey(), actorId.value, key.name, key.version.toString()).map(String::encodeToByteArray) + normalized.copyBytes()))

private fun rejection(code: String): ProposedOutcome.Reject = ProposedOutcome.Reject(code, StoredBytes(code.encodeToByteArray()))

/** Evidence-v1 adapter bound; the general UtcInstant scalar deliberately has a wider range. */
internal fun requireEvidenceV1Time(time: UtcInstant) {
    val text = time.canonicalString()
    require(text.length >= 20 && text[4] == '-' && text.take(4).all { it in '0'..'9' }) {
        "Evidence-v1 supports UTC years 0000..9999 only"
    }
}

/** Length framing is unambiguous for arbitrary UTF-8/binary contents, including NUL/newlines. */
internal fun frame(fields: List<ByteArray>): ByteArray {
    require(fields.size <= 1024)
    val size = fields.fold(0L) { total, field -> total + 4 + field.size }
    require(size <= 4_194_304)
    val output = ByteArray(size.toInt())
    var offset = 0
    for (field in fields) {
        for (shift in 24 downTo 0 step 8) output[offset++] = (field.size ushr shift).toByte()
        field.copyInto(output, offset)
        offset += field.size
    }
    return output
}

internal enum class StoreFaultPoint { AFTER_RESULT, AFTER_EFFECTS, AFTER_JOURNAL, AFTER_AUDIT, AFTER_OUTBOX, BEFORE_COMMIT, AFTER_COMMIT }
internal fun interface StoreFaultProbe { suspend fun at(point: StoreFaultPoint) }
internal object NoStoreFault : StoreFaultProbe { override suspend fun at(point: StoreFaultPoint) {} }
