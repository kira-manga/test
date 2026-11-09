package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.DenyAllPrincipalVerifier
import com.ukkera.pos.security.LocalAccessEligibility
import com.ukkera.pos.security.LocalAccessTokenHash
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.PrincipalVerifier
import com.ukkera.pos.security.ProtectedLocalStorageFormat
import com.ukkera.pos.security.VerifiedLocalIdentity
import com.ukkera.pos.types.DomainId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore

/**
 * Trusted transport/OS-local adapter, NOT a request DTO or device-ID lookup. Capture evidence
 * of the actual authenticated physical lifetime; return null when unavailable/untrusted.
 * No production adapter is installed by this slice. The source must not re-enter Room loans.
 */
internal fun interface RoomLocalAccessPeerSource {
    suspend fun capture(connectionId: DomainId): RoomLocalAccessPeerBinding?
}

/**
 * Immutable evidence from ONE authenticated physical key/lifetime. Descriptors alone do not
 * authenticate a peer. The callback must synchronously test this EXACT captured lifetime,
 * including retirement/rebinding, not whichever peer currently reuses a logical connection ID.
 * A positive observation does not fence subsequent invalidation or network disclosure.
 * This callback and its provenance remain trusted composition/qualification responsibilities.
 */
internal class RoomLocalAccessPeerBinding(
    val connectionId: DomainId,
    val scope: BranchScope,
    val deviceId: DomainId,
    val publicKeyIdentifier: String?,
    val publicFingerprint: String?,
    private val currentLifetime: () -> Boolean,
) {
    init {
        require(publicKeyIdentifier != null || publicFingerprint != null) { "Peer evidence unavailable" }
        for (descriptor in listOf(publicKeyIdentifier, publicFingerprint)) {
            require(descriptor == null ||
                (descriptor.length in 1..ProtectedLocalStorageFormat.MAX_ADMISSION_DESCRIPTOR_BYTES &&
                    descriptor.all { it.code in 0x21..0x7e })) { "Peer evidence unavailable" }
        }
    }

    fun isCurrent(): Boolean = currentLifetime()

    /** BOTH descriptors must agree when both are required; no OR/fallback or capability grant. */
    fun matchesAdmission(admission: LocalDeviceAdmission): Boolean =
        admission.scope == scope && admission.deviceId == deviceId &&
            (admission.publicKeyIdentifier != null || admission.publicFingerprint != null) &&
            (admission.publicKeyIdentifier == null || admission.publicKeyIdentifier == publicKeyIdentifier) &&
            (admission.publicFingerprint == null || admission.publicFingerprint == publicFingerprint)

    override fun toString(): String = "RoomLocalAccessPeerBinding([REDACTED])"
}

/**
 * Existing INTERACTIVE access authentication only. No token/session issuance, password login,
 * refresh, provisioning, central/device/service fallback or accepting application binding.
 *
 * SUPPORTED ENTRY PRECONDITION (not mechanically established here): call from an ordinary
 * structured request coroutine OUTSIDE every Room loan/transaction, without an inherited,
 * shared, retained or re-entered borrowed connection context. Merely changing a dispatcher or
 * Job does not establish this. Room3 can reuse one bare loan in two children and create nested
 * SAVEPOINTs; requireDurableWriter rejects SQL transactions, NOT every inherited bare loan.
 * Call factory authentication first, then the command kernel separately, never inside a
 * caller-held loan. Accepting composition must qualify context lineage and non-re-entry.
 *
 * Independent supported callers serialize through one live store/database writer. The source
 * must be the SAME source instance used by D053 kernel authorization; constructor validation
 * checks exact STORE identity but cannot establish how a separate kernel is wired. Another Room instance/process
 * is not fenced. The sole outer loan spans inner COMMIT and final peer/time observations;
 * identity escapes only after the entire outer call/cleanup returns normally. Observations
 * are not simultaneous freshness/currentness at delivery or reusable disclosure authority.
 * Commands still reauthorize current grants/session state through D053.
 *
 * One explicitly bounded service-lifetime limiter admits before copying/provider/Room work.
 * Saturation is unavailable, not wrong credentials. Null is a generic authentication refusal,
 * including D053 continuity unavailability; it must not be recorded as a password mismatch.
 * Storage/hash-provider/ordinary peer errors produce sanitized unavailable instead.
 * It bounds work/waiters, not duration,
 * fairness, hung providers, financial capacity or platform key/time/transport qualification.
 */
internal class RoomLocalAccessPrincipalVerifier(
    private val scope: BranchScope,
    private val store: RoomLocalSecurityStore,
    private val authorization: RoomCurrentLocalAuthorizationSource,
    private val peers: RoomLocalAccessPeerSource,
    maximumConcurrentVerifications: Int,
) : PrincipalVerifier by DenyAllPrincipalVerifier {
    init {
        require(maximumConcurrentVerifications > 0) { "Local access capacity must be positive" }
        if (!authorization.belongsTo(store)) localAccessVerificationUnavailable()
    }

    private val admission = Semaphore(maximumConcurrentVerifications)

    override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity? {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        if (!admission.tryAcquire()) throw RoomLocalAccessVerificationBusy()
        var parsed: ParsedLocalAccessBearer? = null
        return try {
            val bearer = credentials.bearer.useBytes { ParsedLocalAccessBearer.parse(it) }
            parsed = bearer
            caller.ensureActive()
            if (bearer == null) return null
            val peer = peers.capture(credentials.connectionId)
            caller.ensureActive()
            if (peer == null || peer.connectionId != credentials.connectionId || peer.scope != scope) return null

            val result = localSecurityLane(store.database) {
                val facts = store.transaction { transaction ->
                    val current = transaction.readLocalAccessFacts(scope, bearer.sessionId) ?: return@transaction null
                    if (current.issuance.deviceId != peer.deviceId || !peer.matchesAdmission(current.admission) ||
                        !bearer.matches(current.issuance.tokenHash)) return@transaction null
                    transaction.ensureAuthorizationHealthy()
                    current // Never return a provisional identity from the inner transaction.
                } ?: return@localSecurityLane null

                caller.ensureActive()
                val peerCurrent = peer.isCurrent()
                caller.ensureActive()
                if (!peerCurrent) return@localSecurityLane null
                // Last security observation, not a promise that Room has no later cleanup.
                val observation = authorization.observeCurrent(caller)
                val issuance = facts.issuance
                val candidate = VerifiedLocalIdentity(issuance.actorId, issuance.scope, issuance.deviceId,
                    issuance.sessionId, issuance.generation, issuance.admissionRevisionAtIssue,
                    issuance.authenticationPurpose)
                if (LocalAccessEligibility.matchesCurrentFacts(candidate, issuance, facts.account,
                        facts.credentialRevision, facts.credentialPresent, facts.admission,
                        observation.issuerContextId, observation.moment)) candidate else null
            }
            caller.ensureActive()
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: RoomCurrentLocalAuthorizationDenied) {
            caller.ensureActive()
            null
        } catch (_: RoomLocalStorageUncertain) {
            caller.ensureActive()
            throw RoomLocalAccessVerificationStorageUncertain()
        } catch (_: Exception) {
            caller.ensureActive()
            localAccessVerificationUnavailable()
        } finally {
            parsed?.close()
            admission.release()
        }
    }
}

/** Exact ULA1 binary syntax only. No request-supplied actor, branch, grants, JSON or text fallback. */
internal class ParsedLocalAccessBearer private constructor(val sessionId: DomainId, private val secret: ByteArray) {
    private var closed = false

    fun matches(hash: LocalAccessTokenHash): Boolean {
        check(!closed) { "Local access bearer unavailable" }
        return hash.matchesSecret(secret)
    }

    fun close() {
        secret.fill(0)
        closed = true
    }

    override fun toString(): String = "ParsedLocalAccessBearer([REDACTED])"

    companion object {
        const val SIZE: Int = 52
        fun parse(bytes: ByteArray): ParsedLocalAccessBearer? {
            if (bytes.size != SIZE || bytes[0] != 0x55.toByte() || bytes[1] != 0x4c.toByte() ||
                bytes[2] != 0x41.toByte() || bytes[3] != 0x31.toByte()) return null
            // Only the public UUID locator becomes text, in canonical/network byte order.
            val locator = buildString(36) {
                for (index in 0 until 16) {
                    if (index == 4 || index == 6 || index == 8 || index == 10) append('-')
                    val value = bytes[4 + index].toInt() and 0xff
                    append("0123456789abcdef"[value ushr 4])
                    append("0123456789abcdef"[value and 15])
                }
            }
            return ParsedLocalAccessBearer(DomainId(locator), bytes.copyOfRange(20, SIZE))
        }
    }
}

/** Never leak provider, SQL, parser, stored-row or token details, including through a cause. */
internal open class RoomLocalAccessVerificationUnavailable : IllegalStateException("Local access verification unavailable")

/** Same sanitized boundary, but trusted runtime can avoid treating bounded saturation as poison. */
internal class RoomLocalAccessVerificationBusy : RoomLocalAccessVerificationUnavailable()

/** True writer/COMMIT/rollback/outer-cleanup uncertainty, unlike an ordinary provider refusal. */
internal class RoomLocalAccessVerificationStorageUncertain : RoomLocalAccessVerificationUnavailable()

internal fun localAccessVerificationUnavailable(): Nothing = throw RoomLocalAccessVerificationUnavailable()
