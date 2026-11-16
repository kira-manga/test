package com.ukkera.pos.database

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.DomainId

/**
 * Optional facet of the SAME installed physical gate. A separately supplied coordinator or
 * wire provider cannot register it. Public lease/result objects are data; the concrete native
 * owner must retain exact object affinity before any transition, as for the existing B1/B4 SPI.
 */
public interface PhysicalRecordingRemoteGate : PhysicalRecordingExecutionGate {
    public suspend fun retainRemoteInvocation(invocation: LocalMasterPhysicalRemoteInvocation): PhysicalRemoteInvocationLease?
    public suspend fun remoteStage(lease: PhysicalRemoteInvocationLease): PhysicalRemoteNativeStage?
    public suspend fun acceptRemoteStage(lease: PhysicalRemoteInvocationLease,
        result: LocalMasterPhysicalRpcBodyV1.NativeResult): Boolean
    public suspend fun adoptRemotePublication(lease: PhysicalRemoteInvocationLease,
        publication: PhysicalPublishedInstruction, original: PhysicalInteractiveRequestLifetime): Boolean
    /** Actual jobs/FDs/leases and immutable native original must be settled; no TTL eviction. */
    public suspend fun retireRemoteInvocation(lease: PhysicalRemoteInvocationLease): PhysicalRemoteRetirement
    /** Enrolled-device route only; no employee result or new instruction can be returned. */
    public suspend fun retainRemoteOriginal(device: LocalMasterPhysicalOriginalDevice,
        original: LocalMasterPhysicalOriginalRequestV1): LocalMasterPhysicalOriginalRetentionV1
}

public class PhysicalRemoteInvocationLease(public val invocation: LocalMasterPhysicalRemoteInvocation,
    public val leaseId: DomainId) {
    override fun toString(): String = "PhysicalRemoteInvocationLease([REDACTED])"
}

/** Private native broker stage projected as data by the retaining core registry. */
public class PhysicalRemoteNativeStage(public val ordinal: Long, public val stage: LocalMasterPhysicalNativeStageV1,
    public val payload: StoredBytes, public val originalValidityMillis: Long? = null) {
    init { require(ordinal in 0..32 && payload.size <= LocalMasterPhysicalRpcProtocolV1.MAXIMUM_STAGE_BYTES) }
    override fun toString(): String = "PhysicalRemoteNativeStage([REDACTED])"
}

public enum class PhysicalRemoteRetirement { RETIRED, HELD }

/**
 * Opaque original Host-origin invocation. Only PeerRegistry/retained core admission can mint it
 * from an actual resource session and freshly checked matching principal. No decoder, caller
 * callback, native metadata DTO or operation ID constructs this object.
 */
public class LocalMasterPhysicalRemoteInvocation internal constructor(
    public val id: DomainId,
    private val origin: LocalMasterRpcPhysicalOrigin,
    public val request: MutationRequest,
    public val clientNative: LocalMasterPhysicalClientNativeV1,
    public val requestDigest: PayloadDigest,
    public val nativeDigest: PayloadDigest,
) {
    public val scope: com.ukkera.pos.security.BranchScope get() = origin.identity.scope
    public val originalActorId: DomainId get() = origin.identity.actorId
    public val clientDeviceId: DomainId get() = origin.identity.deviceId
    public val authenticationId: DomainId get() = origin.identity.sessionId
    public val sessionGeneration: com.ukkera.pos.security.SessionGeneration get() = origin.identity.generation
    public val admissionRevision: com.ukkera.pos.types.Revision get() = origin.identity.admissionRevision

    /** Actual SDK/native trust observation; outside a database loan only. */
    private val denied = kotlinx.coroutines.flow.MutableStateFlow(false)
    public fun current(): Boolean = !denied.value && origin.current()
    internal fun deny() { denied.value = true }
    private val durableTerminal = kotlinx.coroutines.flow.MutableStateFlow(false)
    public val hasDurableTerminalResult: Boolean get() = durableTerminal.value
    internal fun observeDurableTerminal(recorded: MutationReply.Recorded) {
        check(recorded.operationId == request.operationId && recorded.originalActorId == originalActorId)
        durableTerminal.value = true
    }
    /** End-only captured memory state; safe for final transaction participant selection. */
    public fun stateCurrent(): Boolean = !denied.value && origin.stateCurrent()

    public fun owns(gate: PhysicalRecordingExecutionGate, binding: PhysicalRecordingInvocationBinding): Boolean {
        val owns = (origin.registration.client as? LocalMasterEndpoint)?.ownsPhysicalInvocation(gate, binding) == true
        if (!owns) {
            if (request.operationId == binding.operationId)
                reportLocalMasterRpcReadFailure(LocalMasterRpcReadStage.PHYSICAL_CLIENT_NATIVE_ACQUIRE, IllegalStateException())
            return false
        }
        return matches(binding.scope, binding.originalActorId, binding.clientDeviceId, binding.authenticationId,
            binding.sessionGeneration, binding.admissionRevision, binding.operationId, binding.command)
    }

    public fun owns(gate: PhysicalRecordingExecutionGate, binding: PhysicalInstructionNativeBinding): Boolean =
        (origin.registration.client as? LocalMasterEndpoint)?.ownsPhysicalInstruction(gate, binding) == true &&
            matches(binding.scope, binding.originalActorId, binding.clientDeviceId, binding.authenticationId,
                binding.sessionGeneration, binding.admissionRevision, binding.operationId, binding.committedStart.command)

    private fun matches(scope: com.ukkera.pos.security.BranchScope, actor: DomainId, device: DomainId,
        authentication: DomainId, generation: com.ukkera.pos.security.SessionGeneration,
        admission: com.ukkera.pos.types.Revision, operation: com.ukkera.pos.types.OperationId, command: CommandKey): Boolean {
        fun refused(): Boolean {
            if (request.operationId == operation)
                reportLocalMasterRpcReadFailure(LocalMasterRpcReadStage.PHYSICAL_CLIENT_NATIVE_ACQUIRE, IllegalStateException())
            return false
        }
        if (!stateCurrent()) return refused()
        if (this.scope != scope) return refused()
        if (originalActorId != actor) return refused()
        if (clientDeviceId != device) return refused()
        if (authenticationId != authentication) return refused()
        if (sessionGeneration != generation) return refused()
        if (admissionRevision != admission) return refused()
        return request.operationId == operation && request.command == command
    }

    override fun toString(): String = "LocalMasterPhysicalRemoteInvocation([REDACTED])"
}

/** Actual original enrolled physical peer; independent of an employee login after logout. */
public class LocalMasterPhysicalOriginalDevice internal constructor(
    private val registration: LocalMasterRpcPeerRegistration,
    private val peer: LocalMasterRpcRegisteredPeer,
) {
    public val scope: com.ukkera.pos.security.BranchScope get() = peer.nativePeer.scope
    public val deviceId: DomainId get() = peer.nativePeer.deviceId
    public fun current(): Boolean = registration.lifetime.isCurrent() && peer.isCurrent() &&
        peer.registry.audienceStateCurrent(peer)
    public fun owns(gate: PhysicalRecordingExecutionGate): Boolean =
        (registration.client as? LocalMasterEndpoint)?.physicalRemoteGate === gate && current()
    override fun toString(): String = "LocalMasterPhysicalOriginalDevice([REDACTED])"
}
