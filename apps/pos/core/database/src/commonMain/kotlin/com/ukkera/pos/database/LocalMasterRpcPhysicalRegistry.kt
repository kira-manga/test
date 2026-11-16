package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import dev.p2pkit.rpc.RpcApplicationSession
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Captured only by the original Host's peer registry from its actual retained resource session.
 * Scalar identity is immutable correlation, never authorization. The endpoint independently
 * authenticates the employee and authorizes every Start/publication under current Master state.
 * No wire decoder, supplied currentness predicate or native-force assertion creates this owner.
 */
internal class LocalMasterRpcPhysicalOrigin(
    internal val registration: LocalMasterRpcPeerRegistration,
    internal val peer: LocalMasterRpcRegisteredPeer,
    internal val resource: LocalMasterRpcResourceSession,
) {
    internal val identity: LocalMasterRpcSessionIdentity get() = resource.identity

    internal fun current(): Boolean {
        fun refused(): Boolean {
            try { reportLocalMasterRpcReadFailure(LocalMasterRpcReadStage.AUTHORIZATION_SCOPE, IllegalStateException()) }
            catch (_: Throwable) { }
            return false
        }
        if (!stateCurrent()) return refused()
        if (!registration.lifetime.isCurrent()) return refused()
        if (registration.lifetime.cleanupScope.coroutineContext[Job]?.isActive != true) return refused()
        if (!peer.isCurrent()) return refused()
        if (!resource.current()) return refused()
        if (!stateCurrent()) return refused()
        return true
    }

    /** Pure retained denial state; no trust callback, SDK clock or native operation. */
    internal fun stateCurrent(): Boolean = identity.purpose == AuthenticationPurpose.INTERACTIVE &&
        resource.stateCurrent() && peer.registry.audienceStateCurrent(peer)

    internal fun sameOriginal(other: LocalMasterRpcPhysicalOrigin): Boolean =
        registration === other.registration && peer === other.peer && identity == other.identity

    internal fun owns(session: RpcApplicationSession): Boolean = resource.session === session

    override fun toString(): String = "LocalMasterRpcPhysicalOrigin([REDACTED])"
}

/** Status is deliberately separate from instruction data and financial application. */
internal enum class LocalMasterRpcPhysicalPhase {
    STARTING, START_RETAINED, START_HELD, START_UNCERTAIN,
    PUBLISHING, PUBLICATION_RETAINED, PUBLICATION_TAKEN, DENIED,
}

/** Inert bounded status. It contains no offer, publication, native lease or bearer. */
internal class LocalMasterRpcPhysicalStatus(
    val invocationId: DomainId,
    val operationId: OperationId,
    val phase: LocalMasterRpcPhysicalPhase,
) {
    override fun toString(): String = "LocalMasterRpcPhysicalStatus([REDACTED])"
}

internal sealed interface LocalMasterRpcPhysicalAdmission {
    class Retained(val status: LocalMasterRpcPhysicalStatus) : LocalMasterRpcPhysicalAdmission
    data object Held : LocalMasterRpcPhysicalAdmission
    data object Busy : LocalMasterRpcPhysicalAdmission
    data object IdentityConflict : LocalMasterRpcPhysicalAdmission
}

/**
 * A one-use handoff inside the Master process, not an RPC value. A concrete distributed native
 * owner must adopt the SAME publication against its own SAME preparation before encoding data
 * for its original Client. Abandoning this handoff only denies; it never restores an offer.
 */
internal class LocalMasterRpcPhysicalPublication internal constructor(
    val instruction: PhysicalPublishedInstruction,
    val original: PhysicalInteractiveRequestLifetime,
) {
    fun deny() { original.deny(); instruction.deny() }
    override fun toString(): String = "LocalMasterRpcPhysicalPublication([REDACTED])"
}

/**
 * Master-owned bounded retention, not a physical native implementation or a remotely injectable
 * service. It is intentionally not installed as a production RPC route by this file. Installation
 * additionally requires the concrete distributed gate, original Client-native reservation owner
 * and exact Host ingress audience capture. Missing native work cannot be replaced by this cache.
 *
 * Repeated Begin selects retained status, never a replacement lifetime. Retirement requires
 * completed endpoint work and the SAME native gate confirming known terminal custody; unknown
 * work is never age-evicted. The durable SQL/native original prevents a fresh action on replay.
 * At most 128 entries and two supervised jobs per entry are retained. The original Host cleanup
 * scope owns each job; abandoning an RPC waiter cannot cancel accepted endpoint/native work.
 * Closing denies capabilities first and waits for actual endpoint work without retrying it.
 */
internal class LocalMasterRpcPhysicalRegistry(
    private val registration: LocalMasterRpcPeerRegistration,
) {
    private class Entry(
        val id: DomainId,
        val origin: LocalMasterRpcPhysicalOrigin,
        val request: MutationRequest,
        val requestDigest: PayloadDigest,
        val lifetime: PhysicalInteractiveRequestLifetime,
        val remote: LocalMasterPhysicalRemoteInvocation,
    ) {
        var phase = LocalMasterRpcPhysicalPhase.STARTING
        var native: PhysicalRemoteInvocationLease? = null
        var start: PhysicalFreshStartReply? = null
        var publication: PhysicalPublishedInstruction? = null
        val jobs = mutableListOf<Job>()
        var denied = false

        fun status(): LocalMasterRpcPhysicalStatus = LocalMasterRpcPhysicalStatus(
            id, request.operationId, phase)

        fun sameRequest(origin: LocalMasterRpcPhysicalOrigin, request: MutationRequest,
            digest: PayloadDigest, native: LocalMasterPhysicalClientNativeV1): Boolean = this.origin.identity.scope == origin.identity.scope &&
            this.origin.identity.actorId == origin.identity.actorId && this.request.command == request.command &&
            requestDigest == digest && this.request.payload == request.payload &&
            this.request.actionApprovalId == request.actionApprovalId && remote.clientNative == native

        fun deny() {
            denied = true
            lifetime.deny()
            remote.deny()
            publication?.deny()
            start?.recordedOrDeferred.let { (it as? MutationReply.Recorded)?.physicalStart?.denyDelivery() }
            phase = LocalMasterRpcPhysicalPhase.DENIED
        }
    }

    private val gate = Mutex()
    private val endpoint = registration.client as? LocalMasterEndpoint
    private val actions = endpoint?.physicalActions
    private val nativeGate = endpoint?.physicalRemoteGate
    private val entries = mutableListOf<Entry>()
    private var denied = false
    private var closing: CompletableDeferred<Unit>? = null
    private class PublicationAdmission(val status: LocalMasterRpcPhysicalStatus?, val work: Job? = null)

    internal suspend fun begin(origin: LocalMasterRpcPhysicalOrigin,
        credentials: LocalGatewayCredentials, request: MutationRequest,
        clientNative: LocalMasterPhysicalClientNativeV1): LocalMasterRpcPhysicalAdmission {
        currentCoroutineContext().ensureActive()
        val physical = actions ?: return LocalMasterRpcPhysicalAdmission.Held
        if (nativeGate == null || physical.remoteAction(request) == null) return LocalMasterRpcPhysicalAdmission.Held
        if (!valid(origin, credentials)) return LocalMasterRpcPhysicalAdmission.Held
        retireSettled()
        // This is exact original request correlation, not a replacement for the domain's
        // normalized semantic digest or the kernel's genuine original operation identity.
        val digest = semanticDigest(origin.identity.scope, origin.identity.actorId, request.command, request.payload)
        val before = gate.withLock {
            if (denied || !origin.stateCurrent()) return@withLock LocalMasterRpcPhysicalAdmission.Held
            entries.firstOrNull { it.request.operationId == request.operationId }?.let { existing ->
                return@withLock if (!existing.sameRequest(origin, request, digest, clientNative))
                    LocalMasterRpcPhysicalAdmission.IdentityConflict
                else if (!existing.origin.sameOriginal(origin)) LocalMasterRpcPhysicalAdmission.Held
                else LocalMasterRpcPhysicalAdmission.Retained(existing.status())
            }
            if (entries.size >= MAX_ENTRIES || entries.any { it.origin.sameOriginal(origin) &&
                    it.phase in FRESH_PHASES }) LocalMasterRpcPhysicalAdmission.Busy else null
        }
        if (before != null) return before

        // Kernel environment/clock observation and secret copying occur outside the memory lock.
        val lifetime = physical.newRequestLifetime()
        val ownedCredentials = copyCredentials(credentials) ?: run {
            lifetime.deny(); return LocalMasterRpcPhysicalAdmission.Held
        }
        val id = DomainId.random()
        val remote = LocalMasterPhysicalRemoteInvocation(id, origin, request, clientNative, digest, physicalNativeDigest(clientNative))
        val entry = Entry(id, origin, request, digest, lifetime, remote)
        val work = registration.lifetime.cleanupScope.launch(start = CoroutineStart.LAZY) {
            runStart(entry, ownedCredentials)
        }
        // A parent can cancel a LAZY job before its body/finally ever executes.
        work.invokeOnCompletion { ownedCredentials.bearer.destroy() }
        entry.jobs.add(work)
        var retained = false
        try {
            val result = gate.withLock {
                if (denied || !origin.stateCurrent()) return@withLock LocalMasterRpcPhysicalAdmission.Held
                entries.firstOrNull { it.request.operationId == request.operationId }?.let { existing ->
                    return@withLock if (!existing.sameRequest(origin, request, digest, clientNative))
                        LocalMasterRpcPhysicalAdmission.IdentityConflict
                    else if (!existing.origin.sameOriginal(origin)) LocalMasterRpcPhysicalAdmission.Held
                    else LocalMasterRpcPhysicalAdmission.Retained(existing.status())
                }
                if (entries.size >= MAX_ENTRIES || entries.any { it.origin.sameOriginal(origin) &&
                        it.phase in FRESH_PHASES }) return@withLock LocalMasterRpcPhysicalAdmission.Busy
                entries.add(entry)
                retained = true
                LocalMasterRpcPhysicalAdmission.Retained(entry.status())
            }
            if (retained) work.start()
            return result
        } finally {
            if (!retained) {
                lifetime.deny()
                work.cancel()
                ownedCredentials.bearer.destroy()
            }
        }
    }

    /** One actual SDK request. Poll/result DATA cannot manufacture a Start or native owner. */
    internal suspend fun exchange(origin: LocalMasterRpcPhysicalOrigin, credentials: LocalGatewayCredentials,
        body: LocalMasterPhysicalRpcBodyV1): LocalMasterPhysicalRpcDelivery? {
        val selected = when (body) {
            is LocalMasterPhysicalRpcBodyV1.Begin -> when (val admission = begin(origin, credentials,
                    body.original.request(), body.native)) {
                is LocalMasterRpcPhysicalAdmission.Retained -> admission.status.invocationId
                else -> return null
            }
            is LocalMasterPhysicalRpcBodyV1.Next -> body.invocationId
            is LocalMasterPhysicalRpcBodyV1.NativeResult -> body.invocationId
            is LocalMasterPhysicalRpcBodyV1.Publish -> body.invocationId
            is LocalMasterPhysicalRpcBodyV1.Deny -> body.invocationId
        }
        if (!valid(origin, credentials)) return null
        val entry = gate.withLock { entries.singleOrNull { it.id == selected && it.origin.sameOriginal(origin) } }
            ?: return null
        if (body is LocalMasterPhysicalRpcBodyV1.Deny) {
            deny(origin, selected)
        } else if (body is LocalMasterPhysicalRpcBodyV1.NativeResult) {
            val native = gate.withLock { entry.native } ?: return response(origin, credentials, entry)
            if (checkNotNull(nativeGate).acceptRemoteStage(native, body) != true) {
                deny(origin, selected); return null
            }
        } else if (body is LocalMasterPhysicalRpcBodyV1.Publish) {
            publish(origin, credentials, selected)
        }
        // The owned publication job normally adopts immediately. This exact same
        // one-use path also settles a racing foreground call; it cannot adopt twice.
        adoptPublication(origin, credentials, entry)
        return response(origin, credentials, entry)
    }

    private suspend fun response(origin: LocalMasterRpcPhysicalOrigin, credentials: LocalGatewayCredentials,
        entry: Entry): LocalMasterPhysicalRpcDelivery? {
        if (!valid(origin, credentials)) return null
        val native = gate.withLock { entry.native }
        val stage = native?.let { checkNotNull(nativeGate).remoteStage(it) }
        if (!valid(origin, credentials)) return null
        val delivery = gate.withLock {
            if (!entries.any { it === entry } || denied || entry.origin !== origin && !entry.origin.sameOriginal(origin) ||
                !origin.stateCurrent()) return@withLock null
            val retained = (entry.start?.recordedOrDeferred as? MutationReply.Recorded)?.let { result ->
                // Restricted DATA projection of this actual retained kernel result. The private
                // physicalStart/offer/lifetime is deliberately never serialized/reconstructed.
                LocalMasterRpcRecordedV1(result.operationId, result.originalActorId, result.committed, result.code,
                    StoredBytes(result.result.copyBytes()), result.authorityEpoch, result.sequence, result.recordedAt, result.replayed)
            }
            val classification = when {
                entry.denied -> LocalMasterPhysicalNativeStageV1.RECOVERY_ONLY
                stage != null -> stage.stage
                entry.phase == LocalMasterRpcPhysicalPhase.START_UNCERTAIN -> LocalMasterPhysicalNativeStageV1.UNCERTAIN
                entry.phase == LocalMasterRpcPhysicalPhase.START_HELD -> LocalMasterPhysicalNativeStageV1.HELD
                else -> LocalMasterPhysicalNativeStageV1.PENDING
            }
            val availableStage = stage.takeIf { !entry.denied }
            val publication = if (classification == LocalMasterPhysicalNativeStageV1.PUBLICATION)
                entry.publication ?: return@withLock null else null
            val response = LocalMasterPhysicalRpcResponseV1(origin.identity.actorId, entry.request.operationId, entry.id,
                availableStage?.ordinal ?: 0, classification, availableStage?.payload ?: StoredBytes(byteArrayOf()),
                availableStage?.originalValidityMillis, retained,
                offerReady = entry.phase == LocalMasterRpcPhysicalPhase.START_RETAINED && entry.start?.offer != null)
            LocalMasterPhysicalRpcDelivery(response, this, origin, entry, publication)
        } ?: return null
        return delivery.takeIf { it.current() }
    }

    /** Necessary-only currentness for this exact actual response. SDK/current employee
     * authorization is separate. No callback, parsed certificate or scalar identity can
     * install an entry or publication here. A cached PUBLICATION follows the original
     * native lifetime, business invalidations and expiry, including at SDK replay. */
    internal suspend fun deliveryCurrent(delivery: LocalMasterPhysicalRpcDelivery): Boolean {
        if (delivery.registry !== this || !delivery.origin.current()) return false
        val entry = delivery.retainedEntry as? Entry ?: return false
        if (!entry.origin.current()) return false
        fun retained(): Boolean = !denied && !entry.denied && entries.any { it === entry } &&
            entry.origin.sameOriginal(delivery.origin) && entry.origin.stateCurrent() && delivery.origin.stateCurrent() &&
            entry.id == delivery.response.invocationId && entry.request.operationId == delivery.response.operationId &&
            entry.origin.identity.actorId == delivery.response.originalActorId
        val publication = gate.withLock {
            if (!retained()) return false
            if (delivery.response.stage == LocalMasterPhysicalNativeStageV1.PUBLICATION) {
                if (entry.phase != LocalMasterRpcPhysicalPhase.PUBLICATION_TAKEN || delivery.publication == null ||
                    entry.publication !== delivery.publication) return false
                delivery.publication
            } else {
                if (delivery.publication != null) return false
                null
            }
        }
        if (publication != null && !publication.deliverableTo(entry.lifetime)) return false
        return gate.withLock { retained() && (publication == null || entry.publication === publication) }
    }

    private suspend fun adoptPublication(origin: LocalMasterRpcPhysicalOrigin,
        credentials: LocalGatewayCredentials, entry: Entry) {
        val publication = takePublication(origin, credentials, entry.id) ?: return
        adoptNativePublication(entry, publication)
    }

    /** The exact successful protected writer already produced this capability. Adopt it
     * in that SAME owner before another network round trip or fresh authentication query.
     * Its own atomic native/business/current-policy checks and original 1000ms cut remain
     * mandatory; no DTO, supplied Boolean or cached grant can enter this private handoff. */
    private suspend fun adoptOwnedPublication(entry: Entry, actual: PhysicalPublishedInstruction) {
        val publication = gate.withLock {
            if (denied || entry.denied || !entries.any { it === entry } || !entry.origin.stateCurrent() ||
                entry.phase != LocalMasterRpcPhysicalPhase.PUBLICATION_RETAINED || entry.publication !== actual)
                return@withLock null
            entry.phase = LocalMasterRpcPhysicalPhase.PUBLICATION_TAKEN
            LocalMasterRpcPhysicalPublication(actual, entry.lifetime)
        } ?: return
        adoptNativePublication(entry, publication)
    }

    private suspend fun adoptNativePublication(entry: Entry, publication: LocalMasterRpcPhysicalPublication) {
        val native = gate.withLock { entry.native }
        val adopted = try { native != null && checkNotNull(nativeGate).adoptRemotePublication(
            native, publication.instruction, publication.original) }
        catch (cancelled: CancellationException) { publication.deny(); throw cancelled }
        catch (_: Exception) { false }
        if (!adopted) { publication.deny(); deny(entry.origin, entry.id) }
    }

    /** No age eviction. Ask the SAME native owner only after every accepted endpoint job ends. */
    private suspend fun retireSettled() {
        val nativeOwner = nativeGate ?: return
        val candidates = gate.withLock { entries.filter { it.jobs.all(Job::isCompleted) && it.native != null } }
        for (entry in candidates) {
            val native = gate.withLock { entry.native } ?: continue
            if (nativeOwner.retireRemoteInvocation(native) != PhysicalRemoteRetirement.RETIRED) continue
            gate.withLock {
                if (entry.jobs.all(Job::isCompleted)) {
                    entry.deny()
                    entries.removeAll { it === entry }
                }
            }
        }
    }

    internal suspend fun status(origin: LocalMasterRpcPhysicalOrigin,
        credentials: LocalGatewayCredentials, invocationId: DomainId): LocalMasterRpcPhysicalStatus? {
        if (!valid(origin, credentials)) return null
        return gate.withLock {
            entries.firstOrNull { it.id == invocationId && it.origin.sameOriginal(origin) }
                ?.takeIf { !denied && origin.stateCurrent() }?.status()
        }
    }

    /** A duplicate only observes the retained transition; it never reruns publication. */
    internal suspend fun publish(origin: LocalMasterRpcPhysicalOrigin,
        credentials: LocalGatewayCredentials, invocationId: DomainId): LocalMasterRpcPhysicalStatus? {
        if (!valid(origin, credentials)) return null
        val ownedCredentials = copyCredentials(credentials) ?: return null
        var handedOff = false
        try {
            val result: PublicationAdmission = gate.withLock {
                val entry = entries.firstOrNull { it.id == invocationId && it.origin.sameOriginal(origin) }
                    ?: return@withLock PublicationAdmission(null)
                if (denied || entry.denied || !origin.stateCurrent()) return@withLock PublicationAdmission(null)
                if (entry.phase != LocalMasterRpcPhysicalPhase.START_RETAINED)
                    return@withLock PublicationAdmission(entry.status())
                val offer = entry.start?.offer ?: return@withLock PublicationAdmission(entry.status())
                entry.phase = LocalMasterRpcPhysicalPhase.PUBLISHING
                // The job is retained before it starts; no caller or waiter owns cancellation.
                val work = try {
                    registration.lifetime.cleanupScope.launch(start = CoroutineStart.LAZY) {
                        runPublication(entry, offer, ownedCredentials)
                    }
                } catch (failure: Throwable) {
                    entry.deny()
                    throw failure
                }
                work.invokeOnCompletion { ownedCredentials.bearer.destroy() }
                entry.jobs.add(work)
                handedOff = true
                PublicationAdmission(entry.status(), work)
            }
            result.work?.start()
            return result.status
        } finally { if (!handedOff) ownedCredentials.bearer.destroy() }
    }

    /** Actual Master-only native handoff, never a serializable RPC reply or a reusable getter. */
    internal suspend fun takePublication(origin: LocalMasterRpcPhysicalOrigin,
        credentials: LocalGatewayCredentials, invocationId: DomainId): LocalMasterRpcPhysicalPublication? {
        if (!valid(origin, credentials)) return null
        return gate.withLock {
            val entry = entries.firstOrNull { it.id == invocationId && it.origin.sameOriginal(origin) }
                ?: return@withLock null
            if (denied || entry.denied || !origin.stateCurrent() ||
                entry.phase != LocalMasterRpcPhysicalPhase.PUBLICATION_RETAINED) return@withLock null
            val actual = entry.publication ?: return@withLock null
            entry.phase = LocalMasterRpcPhysicalPhase.PUBLICATION_TAKEN
            LocalMasterRpcPhysicalPublication(actual, entry.lifetime)
        }
    }

    internal suspend fun deny(origin: LocalMasterRpcPhysicalOrigin, invocationId: DomainId) {
        gate.withLock {
            entries.firstOrNull { it.id == invocationId && it.origin.sameOriginal(origin) }?.deny()
        }
    }

    internal suspend fun close(): Unit = withContext(NonCancellable) {
        val prepared = gate.withLock {
            closing?.let { return@withLock Pair(it, null) }
            denied = true
            entries.forEach(Entry::deny)
            val done = CompletableDeferred<Unit>()
            closing = done
            Pair(done, entries.flatMap { it.jobs })
        }
        val jobs = prepared.second
        if (jobs != null) {
            try {
                // Do not cancel accepted endpoint/native work or treat a reply timeout as close.
                jobs.forEach { it.start(); it.join() }
                prepared.first.complete(Unit)
            } catch (failure: Throwable) { prepared.first.completeExceptionally(failure) }
        }
        prepared.first.await()
    }

    private suspend fun runStart(entry: Entry, credentials: LocalGatewayCredentials) {
        try {
            if (!valid(entry.origin, credentials)) { denyEntry(entry); return }
            // Retain the actual Host origin plus complete immutable native DATA on the SAME
            // installed gate before either domain prepare can derive its participant sources.
            val retained = checkNotNull(nativeGate).retainRemoteInvocation(entry.remote)
            if (retained == null) {
                reportLocalMasterRpcReadFailure(LocalMasterRpcReadStage.PHYSICAL_CLIENT_NATIVE_ACQUIRE, IllegalStateException())
                denyEntry(entry); return
            }
            gate.withLock { entry.native = retained }
            if (!valid(entry.origin, credentials)) { denyEntry(entry); return }
            val physical = checkNotNull(actions)
            val reply = if (physical.remoteAction(entry.request) == PhysicalRecordingAction.RETAIN_RESOLUTION)
                PhysicalFreshStartReply(physical.retainLinkedResolution(credentials, entry.request), null)
            else physical.start(credentials, entry.request, entry.lifetime)
            (reply.recordedOrDeferred as? MutationReply.Recorded)?.let(entry.remote::observeDurableTerminal)
            when (val decision = reply.recordedOrDeferred) {
                is MutationReply.Deferred -> when (decision.reason) {
                    DeferredReason.AUTHORIZATION_DENIED -> reportLocalMasterRpcReadFailure(
                        LocalMasterRpcReadStage.AUTHORIZATION_CURRENT, IllegalStateException())
                    DeferredReason.CURRENT_SOURCE_UNAVAILABLE -> reportLocalMasterRpcReadFailure(
                        LocalMasterRpcReadStage.PHYSICAL_ORIGINAL_SOURCE_UNAVAILABLE, IllegalStateException())
                    DeferredReason.PHYSICAL_RECORDING_HELD -> reportLocalMasterRpcReadFailure(
                        LocalMasterRpcReadStage.PHYSICAL_CLIENT_NATIVE_ACQUIRE, IllegalStateException())
                    else -> reportLocalMasterRpcReadFailure(LocalMasterRpcReadStage.QUERY_EXECUTION, IllegalStateException())
                }
                is MutationReply.IdentityConflict -> reportLocalMasterRpcReadFailure(
                    LocalMasterRpcReadStage.ORIGINAL_BINDING, IllegalStateException())
                is MutationReply.Recorded -> if (!decision.committed) reportLocalMasterRpcReadFailure(
                    LocalMasterRpcReadStage.QUERY_EXECUTION, IllegalStateException())
            }
            val current = entry.origin.current()
            gate.withLock {
                entry.start = reply // Preserve a committed or uncertain original even after denial.
                if (denied || entry.denied || !current || !entry.origin.stateCurrent()) entry.deny()
                else entry.phase = when {
                    reply.offer != null -> LocalMasterRpcPhysicalPhase.START_RETAINED
                    (reply.recordedOrDeferred as? MutationReply.Deferred)?.reason == DeferredReason.STORAGE_UNCERTAIN ->
                        LocalMasterRpcPhysicalPhase.START_UNCERTAIN
                    else -> LocalMasterRpcPhysicalPhase.START_HELD
                }
            }
        } catch (cancelled: CancellationException) {
            denyEntry(entry)
            throw cancelled
        } catch (_: Exception) {
            gate.withLock {
                entry.lifetime.deny()
                if (denied || entry.denied) entry.deny()
                else entry.phase = LocalMasterRpcPhysicalPhase.START_UNCERTAIN
            }
        } finally { credentials.bearer.destroy() }
    }

    private suspend fun runPublication(entry: Entry, offer: PhysicalInstructionOffer,
        credentials: LocalGatewayCredentials) {
        try {
            if (!valid(entry.origin, credentials)) { denyEntry(entry); return }
            val reply = checkNotNull(actions).publishInstruction(credentials, offer, entry.lifetime)
            val current = entry.origin.current()
            gate.withLock {
                if (reply is PhysicalInstructionReply.Published) entry.publication = reply.instruction
                if (denied || entry.denied || !current || !entry.origin.stateCurrent()) entry.deny()
                else entry.phase = when (reply) {
                    is PhysicalInstructionReply.Published -> LocalMasterRpcPhysicalPhase.PUBLICATION_RETAINED
                    PhysicalInstructionReply.Held -> LocalMasterRpcPhysicalPhase.START_HELD
                    PhysicalInstructionReply.StorageUncertain -> LocalMasterRpcPhysicalPhase.START_UNCERTAIN
                }
            }
            // Adopt the SAME actual one-use publication in its owning worker, within
            // the unchanged Master window; a later network poll only carries DATA.
            if (reply is PhysicalInstructionReply.Published) adoptOwnedPublication(entry, reply.instruction)
        } catch (cancelled: CancellationException) {
            denyEntry(entry)
            throw cancelled
        } catch (_: Exception) {
            gate.withLock {
                entry.lifetime.deny()
                if (denied || entry.denied) entry.deny()
                else entry.phase = LocalMasterRpcPhysicalPhase.START_UNCERTAIN
            }
        } finally { credentials.bearer.destroy() }
    }

    private suspend fun denyEntry(entry: Entry) = withContext(NonCancellable) {
        gate.withLock { entry.deny() }
    }

    private suspend fun valid(origin: LocalMasterRpcPhysicalOrigin, credentials: LocalGatewayCredentials): Boolean {
        currentCoroutineContext().ensureActive()
        if (origin.registration !== registration || registration.client !== endpoint ||
            credentials.connectionId != origin.peer.nativePeer.connectionId ||
            origin.identity.scope != origin.peer.nativePeer.scope ||
            origin.identity.deviceId != origin.peer.nativePeer.deviceId || !origin.current()) return false
        // An old retained audience is not a fresh bearer check. All entry/status/publication
        // transitions require the actual principal source again, not merely a matching locator.
        val principal = registration.principals.authenticateLocal(credentials) ?: return false
        val identity = origin.identity
        currentCoroutineContext().ensureActive()
        return principal.scope == identity.scope && principal.actorId == identity.actorId &&
            principal.deviceId == identity.deviceId && principal.authenticationId == identity.sessionId &&
            principal.sessionGeneration == identity.generation && principal.admissionRevision == identity.admissionRevision &&
            principal.authenticationPurpose == AuthenticationPurpose.INTERACTIVE && origin.current()
    }

    private fun copyCredentials(credentials: LocalGatewayCredentials): LocalGatewayCredentials? = try {
        LocalGatewayCredentials(credentials.connectionId, credentials.bearer.useBytes(::OpaqueCredential))
    } catch (_: Exception) { null }

    override fun toString(): String = "LocalMasterRpcPhysicalRegistry([REDACTED])"

    private companion object {
        const val MAX_ENTRIES = 128
        val FRESH_PHASES = setOf(LocalMasterRpcPhysicalPhase.STARTING,
            LocalMasterRpcPhysicalPhase.START_RETAINED, LocalMasterRpcPhysicalPhase.PUBLISHING,
            LocalMasterRpcPhysicalPhase.PUBLICATION_RETAINED, LocalMasterRpcPhysicalPhase.PUBLICATION_TAKEN)
    }
}
