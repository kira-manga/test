package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalAuthenticationEntropy
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Current receiving eligibility only, never an authenticated principal or transferable grant. */
public class LocalHandoffRecipient internal constructor(public val actorId: DomainId, public val label: String,
    public val accountRevision: Revision, public val policyRevision: Revision)
public class LocalHandoffRecipientPage internal constructor(recipients: List<LocalHandoffRecipient>, public val nextAfter: DomainId?) {
    public val recipients: List<LocalHandoffRecipient> = recipients.toList()
}

/** Server-side routing position only. Never serialize this lower bound into a public result. */
public class LocalHandoffCursorPosition internal constructor(public val phase: String, public val after: String?) {
    override fun toString(): String = "LocalHandoffCursorPositionV1([REDACTED])"
}

/**
 * Narrow protected borrower for the installed food adapter. The adapter must prove the current
 * order/owner and declare exact ORDER_HANDOFF authorization before publishing these fields.
 * Account/configuration/policy bytes never escape. No public callback, fresh transaction, fake
 * principal, cached sign-in grants or directory capability on an arbitrary view is accepted.
 */
public object TransactionLocalHandoffRecipients {
    public suspend fun page(view: CommandReadView, orderId: DomainId, after: DomainId? = null,
        limit: Int = 20): LocalHandoffRecipientPage = writer(view).handoffRecipients(orderId, after, limit)
    public suspend fun current(view: CommandReadView, orderId: DomainId,
        recipientId: DomainId): LocalHandoffRecipient? = writer(view).handoffRecipient(orderId, recipientId)
    /** Merely selects an existing exact permission target for an empty private inbox response. */
    public suspend fun ownInboxAuthorizationTarget(view: CommandReadView): DomainId? = writer(view).handoffInboxTarget()
    /** Random issued handle, not a grant, row-existence assertion or durable snapshot. */
    public suspend fun issueInboxCursor(view: CommandReadView, phase: String, after: String?): String =
        writer(view).issueHandoffCursor(phase, after)
    /** Unknown/expired/wrong-owner handles all return null without any claim/source lookup. */
    public suspend fun resumeInboxCursor(view: CommandReadView, phase: String, handle: String): LocalHandoffCursorPosition? =
        writer(view).resumeHandoffCursor(phase, handle)
    private fun writer(view: CommandReadView): TransactionView = view as? TransactionView ?: protectedLocalUnavailable()
}

/**
 * One registry per actual authorization-source lifetime; only its serialized writer bindings
 * call it. At most512 service routes and16 per scope/actor, each with a five-minute monotonic
 * ceiling AND its genuine access expiry. Restart, eviction or expiry requires fresh pagination,
 * never deletion/repair of durable offers. The only entropy is the existing supervised CSPRNG
 * port; absence, failure or a live collision refuses, with no predictable fallback/retry.
 */
internal class HandoffCursorRoutes(private val entropy: LocalAuthenticationEntropy?) {
    private data class Owner(val scope: BranchScope, val actorId: DomainId, val sessionId: DomainId,
        val generation: Long, val deviceId: DomainId, val admissionRevision: Revision) {
        override fun toString(): String = "HandoffCursorOwnerV1([REDACTED])"
    }
    private class Route(val owner: Owner, val phase: String, val after: String?,
        val issued: RoomLocalAuthorizationObservation, val expiresAtElapsedMillis: Long) {
        fun alive(now: RoomLocalAuthorizationObservation): Boolean = now.issuerContextId == issued.issuerContextId &&
            now.moment.bootId == issued.moment.bootId && now.moment.elapsedMillis >= issued.moment.elapsedMillis &&
            now.moment.elapsedMillis < expiresAtElapsedMillis
        override fun toString(): String = "HandoffCursorRouteV1([REDACTED])"
    }
    private val routes = linkedMapOf<String, Route>()

    fun issue(principal: LocalEmployeePrincipal, observed: RoomLocalAuthorizationObservation,
        accessExpiresAtElapsedMillis: Long, phase: String, after: String?): String {
        check(phase == "INDEXED" || phase == "LEGACY")
        val prefix = if (phase == "INDEXED") "${principal.actorId.value}/" else "handoff/"
        if (after != null) {
            check(after.startsWith(prefix))
            val id = DomainId(after.removePrefix(prefix))
            check(after == prefix + id.value)
        }
        check(accessExpiresAtElapsedMillis > observed.moment.elapsedMillis)
        val lifetime = minOf(300_000L, accessExpiresAtElapsedMillis - observed.moment.elapsedMillis)
        val bytes = ByteArray(32)
        val handle = try {
            checkNotNull(entropy) { "Handoff cursor entropy unavailable" }.fill(bytes)
            buildString(64) { for (byte in bytes) {
                val value = byte.toInt() and 0xff
                append("0123456789abcdef"[value ushr 4]); append("0123456789abcdef"[value and 15])
            } }
        } finally { bytes.fill(0) }
        check(handle !in routes) { "Handoff cursor entropy unavailable" }
        prune(observed)
        val owner = owner(principal)
        while (routes.values.count { it.owner.scope == owner.scope && it.owner.actorId == owner.actorId } >= 16) {
            routes.remove(routes.entries.first { it.value.owner.scope == owner.scope && it.value.owner.actorId == owner.actorId }.key)
        }
        while (routes.size >= 512) routes.remove(routes.keys.first())
        routes[handle] = Route(owner, phase, after, observed, observed.moment.elapsedMillis + lifetime)
        return handle
    }

    fun resume(principal: LocalEmployeePrincipal, observed: RoomLocalAuthorizationObservation,
        phase: String, handle: String): LocalHandoffCursorPosition? {
        if (phase != "INDEXED" && phase != "LEGACY" || handle.length != 64 ||
            handle.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        prune(observed)
        val route = routes[handle] ?: return null
        if (route.owner != owner(principal) || route.phase != phase) return null
        return LocalHandoffCursorPosition(route.phase, route.after)
    }

    private fun owner(principal: LocalEmployeePrincipal): Owner = Owner(principal.scope, principal.actorId,
        principal.sessionId, principal.sessionGeneration.value, principal.deviceId, principal.admissionRevision)

    private fun prune(observed: RoomLocalAuthorizationObservation) {
        val iterator = routes.entries.iterator()
        while (iterator.hasNext()) if (!iterator.next().value.alive(observed)) iterator.remove()
    }
}
