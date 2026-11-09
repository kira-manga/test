package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAuthenticatedPeer
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val MASTER_LOCAL_DISPLAY_READ: ReadKey = ReadKey("sales.food.read", 1)
internal const val MASTER_LOCAL_DISPLAY_JOB: String = "local.kitchen.deliver-v1"
internal const val MASTER_LOCAL_DISPLAY_MAX_INTENT_BYTES: Int = 4_096
internal const val MASTER_LOCAL_DISPLAY_MAX_INTENTS: Int = 32

/**
 * Concrete local DISPLAY delivery ingress for the existing FOOD read profile. The original
 * typed adapter still owns the narrow fields and current station permission. STATION/TASK
 * must use the actual consumer on this kernel's self-owned Room lifetime; ordinary selectors
 * retain their existing behavior. Construction alone grants no execution support.
 */
public class MasterLocalDisplayReadHandler(private val delegate: MasterReadHandler) : MasterReadHandler {
    init { require(delegate.key == MASTER_LOCAL_DISPLAY_READ && delegate !is MasterLocalDisplayReadHandler) }
    override public val key: ReadKey get() = delegate.key
    override public fun normalize(input: StoredBytes): StoredBytes = delegate.normalize(input)
    override public fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        delegate.supportsRpcWorkflow(profile, normalized)

    override public suspend fun prepare(normalized: StoredBytes, view: CommandReadView,
        context: ReadContext): PreparedRead {
        if (requiresDelivery(normalized)) {
            val actual = view as? TransactionView ?: masterLocalDisplayUnavailable()
            actual.enterLocalDisplayRead(this)
        }
        return delegate.prepare(normalized, view, context)
    }

    /** The delegate has already normalized the fixed legacy profile; no caller capability. */
    internal fun requiresDelivery(normalized: StoredBytes): Boolean {
        val value = Json.parseToJsonElement(normalized.copyBytes().decodeToString(throwOnInvalidSequence = true))
            as? JsonObject ?: error("Local DISPLAY read selector unavailable")
        val type = value["type"] as? JsonPrimitive ?: error("Local DISPLAY read selector unavailable")
        check(type.isString)
        return when (type.content) {
            "STATION", "TASK" -> true
            "OWN", "ORDER", "HANDOFF" -> false
            else -> error("Local DISPLAY read selector unavailable")
        }
    }
}

/**
 * Actual same-loan consumer access, created only by the registered self-owned Master. It is
 * neither a caller availability flag nor a delivery/physical ACK. The typed adapter must join
 * a complete original producer and pass its exact requested-job ordinal and bytes. Repeated
 * DISPLAY reads preserve the append-only intent and do not start or repeat preparation.
 */
public class MasterLocalDisplayDeliveryAccess internal constructor(
    private val view: TransactionView,
    internal val owner: RoomLocalDisplayDeliveryOwner,
) {
    public suspend fun requireIntent(operationId: OperationId, ordinal: Int, expectedBody: StoredBytes) {
        view.consumeLocalDisplayIntent(owner, operationId, ordinal, expectedBody)
    }
}

/** Unowned, copied and historical views never acquire this actual execution source. */
public fun CommandReadView.masterLocalDisplayDelivery(): MasterLocalDisplayDeliveryAccess? =
    (this as? TransactionView)?.localDisplayDeliveryAccess()

/** One immutable kernel/Room/endpoint/peer association; no accepting public factory. */
internal class RoomLocalDisplayDeliveryOwner(
    internal val store: AtomicMasterStore,
    internal val database: MasterRoomDatabase,
    internal val history: RoomRuntimeHistoryOwner,
    internal val endpoint: LocalMasterEndpoint,
    internal val peer: LocalAuthenticatedPeer,
    internal val reader: MasterLocalDisplayReadHandler,
) {
    private val withdrawn = MutableStateFlow(false)

    internal fun belongsTo(actual: MasterRoomDatabase, principal: LocalEmployeePrincipal): Boolean =
        actual === database && principal.scope == history.originalScope && principal.deviceId == peer.deviceId &&
            peer.scope == history.originalScope && peer.deviceId == history.originalAuthority.deviceId

    /** Sample the actual captured local peer before the protected final no-IO phase. */
    internal fun current(): Boolean {
        if (!stateCurrent()) return false
        val current = try { peer.isCurrent() }
        catch (failure: Throwable) { withdrawn.value = true; throw failure }
        if (!current) withdrawn.value = true
        return current && stateCurrent()
    }

    /** Pure one-way state only; never add a callback after the protected final clock. */
    internal fun stateCurrent(): Boolean {
        if (withdrawn.value) return false
        if (store.localDisplayLifetimeCurrent(this)) return !withdrawn.value
        withdrawn.value = true
        return false
    }
}

/**
 * Exact original Host audience, produced only from an existing admitted registry entry and
 * retained resource-session census. It carries no caller currentness callback or grant.
 * The native DISPLAY consumer remains the original Master owner, not this remote audience.
 */
internal class RoomLocalDisplayRpcAudience(
    private val registry: LocalMasterRpcPeerRegistry,
    private val history: RoomRuntimeHistoryOwner,
    private val peer: LocalMasterRpcRegisteredPeer,
    private val resource: LocalMasterRpcResourceSession,
    internal val access: LocalMasterRpcBusinessAccess,
) {
    private val withdrawn = MutableStateFlow(false)

    internal fun belongsTo(database: MasterRoomDatabase, principal: LocalEmployeePrincipal): Boolean {
        val identity = resource.identity
        return history.database === database && history.originalScope == principal.scope &&
            peer.registry === registry && identity.scope == principal.scope &&
            identity.actorId == principal.actorId && identity.deviceId == principal.deviceId &&
            identity.sessionId == principal.sessionId && identity.generation == principal.sessionGeneration &&
            identity.admissionRevision == principal.admissionRevision &&
            identity.purpose == principal.authenticationPurpose
    }

    /** Native peer checks occur before the final protected no-IO phase. */
    internal fun current(): Boolean {
        if (!stateCurrent()) return false
        if (!resource.current() || !peer.isCurrent()) withdrawn.value = true
        return stateCurrent()
    }

    /** State observations only; no native admission/provider callback after final clock. */
    internal fun stateCurrent(): Boolean = !withdrawn.value && resource.stateCurrent() &&
        registry.audienceStateCurrent(peer)
}

/** Expected source withdrawal; only a normal rollback/outer return may disclose refusal. */
internal class MasterLocalDisplayDeliveryUnavailable : RuntimeException("Local DISPLAY execution unavailable")
internal fun masterLocalDisplayUnavailable(): Nothing = throw MasterLocalDisplayDeliveryUnavailable()

/** Exact point reads; invalid/missing retained intent is corruption, not ordinary absence. */
internal suspend fun requireMasterLocalDisplayIntent(dao: MasterDao, scope: BranchScope,
    operationId: OperationId, ordinal: Int, expectedBody: StoredBytes, checkActive: suspend () -> Unit) {
    check(ordinal in 1..MASTER_LOCAL_DISPLAY_MAX_INTENTS &&
        expectedBody.size in 1..MASTER_LOCAL_DISPLAY_MAX_INTENT_BYTES)
    checkActive()
    val key = scope.storageKey()
    val metadata = dao.localDisplayIntentMetadata(key, operationId.value, ordinal)
        ?: error("Retained DISPLAY intent unavailable")
    checkActive()
    check(metadata.storageValid && metadata.byteCount in 1L..MASTER_LOCAL_DISPLAY_MAX_INTENT_BYTES.toLong())
    val row = dao.outboxIntent(key, operationId.value, ordinal) ?: error("Retained DISPLAY intent unavailable")
    checkActive()
    check(row.scope == key && row.operationId == operationId.value && row.ordinal == ordinal &&
        row.kind == MASTER_LOCAL_DISPLAY_JOB && row.body.size.toLong() == metadata.byteCount)
    val body = StoredBytes(row.body)
    check(sha256(body.bytes()) == PayloadDigest(row.bodyHash) && body == expectedBody)
}
