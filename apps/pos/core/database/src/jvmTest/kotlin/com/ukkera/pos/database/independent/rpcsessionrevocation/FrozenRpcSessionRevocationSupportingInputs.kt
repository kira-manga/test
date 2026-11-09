package com.ukkera.pos.database

import com.ukkera.pos.security.AuthenticationPurpose
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CredentialBinding
import com.ukkera.pos.security.LocalAccountSessionState
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalSessionRecord
import com.ukkera.pos.security.PasswordHashParameters
import com.ukkera.pos.security.PasswordHashRecord
import com.ukkera.pos.security.ProtectedLocalAccount
import com.ukkera.pos.security.ProtectedPepperReference
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.SessionStatus
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.core.PeerFingerprint
import dev.p2pkit.rpc.RpcApplicationSession
import kotlin.time.Duration.Companion.milliseconds

/**
 * Typed, inert encodings of frozen master-rpc-session-revocation v1 fixture relations.
 * These values are not credentials, native admission, authenticated principals or current policy.
 * Account/admission DTOs are passed only to the internal SDK-session selection helper; no storage,
 * hasher, principal factory, protected authorizer or physical-session constructor consumes them.
 */
internal object FrozenRpcSessionRevocationSupportingInputs {
    // F-ORIGINAL organization/company/branch and the independently named other branch.
    val scope = BranchScope(id(1), id(2), id(3))
    val otherBranch = scope.copy(branchId = id(4))
    val actor = id(5)
    val otherActor = id(6)
    val device = id(7)
    val otherDevice = id(8)
    val originalSession = id(9)
    val successorSession = id(10)
    val otherActorSession = id(11)
    val credentialRevision = Revision(0)

    val original = LocalMasterRpcSessionIdentity(scope, actor, device, originalSession,
        SessionGeneration(9), Revision(7), AuthenticationPurpose.INTERACTIVE)
    val successor = original.copy(sessionId = successorSession, generation = SessionGeneration(10))
    val otherActorIdentity = successor.copy(actorId = otherActor, sessionId = otherActorSession)
    val otherDeviceIdentity = otherActorIdentity.copy(deviceId = otherDevice)

    // The shared symbolic fingerprint is encoded canonically; no matching key or handshake exists.
    private val fingerprint = PeerFingerprint("p2f1-" + "a".repeat(52))

    fun applicationSession(identity: LocalMasterRpcSessionIdentity): RpcApplicationSession =
        RpcApplicationSession(fingerprint, identity.actorId.value, identity.sessionId.value, 60_000.milliseconds)

    fun record(identity: LocalMasterRpcSessionIdentity, status: SessionStatus = SessionStatus.ACTIVE): LocalSessionRecord =
        LocalSessionRecord(identity.sessionId, identity.scope, identity.actorId, identity.deviceId,
            identity.generation, status, identity.purpose)

    fun account(sessions: List<LocalSessionRecord>, enabled: Boolean = true): ProtectedLocalAccount {
        val state = LocalAccountSessionState(scope, actor, enabled, Revision(2), SessionGeneration(11), sessions)
        // A syntactic non-null sentinel isolates session-state changes from password replacement.
        // No password is hashed/verified; this record never enters authentication or persistence.
        val credential = PasswordHashRecord(CredentialBinding(scope, actor, credentialRevision),
            PasswordHashParameters(), ProtectedPepperReference(id(12)), ByteArray(16), ByteArray(32))
        return ProtectedLocalAccount(state, credentialRevision, credential)
    }

    fun admission(admitted: Boolean, revision: Revision = original.admissionRevision): LocalDeviceAdmission =
        LocalDeviceAdmission(scope, device, revision, admitted, "NATIVE-P256-RSR-1", null, emptySet())

    private fun id(value: Int): DomainId =
        DomainId("00000000-0000-4000-8000-" + value.toString().padStart(12, '0'))
}
