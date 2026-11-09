package com.ukkera.pos.database

import com.ukkera.pos.security.*
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ensureActive

/** Closed authentication finalizers. They reread the actual post-write snapshot BEFORE history
 * sealing; afterwards only the exact peer and suspend-inclusive elapsed clock are sampled.
 * No supplied onCommit predicate can substitute for one of these cases. */
internal class RoomLocalAuthenticationFrame private constructor(
    private val source: RoomCurrentLocalAuthorizationSource,
    private val peer: RoomLocalAccessPeerBinding,
    private val context: CoroutineContext,
    private val issued: IssuedAuthentication?,
    private val loggedOut: LocalAccessIssuance?,
) {
    private var prepared=false
    private var finished=false
    private var closed=false
    suspend fun prepareNormalReturn(transaction: RoomLocalSecurityTransaction) {
        context.ensureActive(); check(!prepared && !closed && !finished)
        check(peer.isCurrent())
        val value=issued
        if (value!=null) {
            val current=transaction.readLocalAccessFacts(value.access.scope,value.access.sessionId) ?: transaction.refuse()
            equal(ProtectedLocalSecurityCodec.encodeIssuance(current.issuance),ProtectedLocalSecurityCodec.encodeIssuance(value.access))
            check(current.account.scope==value.account.scope && current.account.actorId==value.account.actorId &&
                current.account.revision==value.account.revision && current.account.generation==value.account.generation &&
                current.account.enabled==value.account.enabled && current.account.sessions==value.account.sessions &&
                current.credentialRevision==value.credentialRevision && current.credentialPresent && peer.matchesAdmission(current.admission))
            equal(ProtectedLocalSecurityCodec.encodeAdmission(current.admission),ProtectedLocalSecurityCodec.encodeAdmission(value.admission))
            val refresh=transaction.readRefresh(value.access.scope,value.access.sessionId) ?: transaction.refuse()
            equal(LocalAuthenticationCodec.encodeRefresh(refresh),LocalAuthenticationCodec.encodeRefresh(value.refresh))
            val configuration=transaction.readAuthConfiguration(value.access.scope,value.access.actorId) ?: transaction.refuse()
            val policy=transaction.readPolicy(value.access.scope,value.access.actorId) ?: transaction.refuse()
            equal(LocalAuthenticationCodec.encodeConfiguration(configuration),LocalAuthenticationCodec.encodeConfiguration(value.configuration))
            val actual=LocalEffectivePolicyCodec.encodePolicy(policy)
            val expected=LocalEffectivePolicyCodec.encodePolicy(value.policy)
            try { check(actual.contentEquals(expected)) } finally { actual.fill(0); expected.fill(0) }
            check(value.eligible(source.observeCurrent(context)))
        } else {
            val access=checkNotNull(loggedOut)
            val current=transaction.readIssuance(access.scope,access.sessionId) ?: transaction.refuse()
            equal(ProtectedLocalSecurityCodec.encodeIssuance(current),ProtectedLocalSecurityCodec.encodeIssuance(access))
            val admission=transaction.readAdmission(access.scope,access.deviceId) ?: transaction.refuse()
            check(admission.admitted && peer.matchesAdmission(admission))
            val account=transaction.readAccount(access.scope,access.actorId) ?: transaction.refuse()
            val session=account.sessions.singleOrNull { it.sessionId==access.sessionId }
                ?: transaction.readSessionArchive(access.scope,access.sessionId)?.session ?: transaction.refuse()
            check(session.actorId==access.actorId && session.deviceId==access.deviceId && session.generation==access.generation &&
                session.status!=SessionStatus.ACTIVE)
        }
        transaction.ensureAuthorizationHealthy(); prepared=true
    }
    fun finishNow() {
        context.ensureActive(); check(prepared && !finished && !closed && peer.isCurrent())
        val observation=source.observeCurrent(context)
        issued?.let { check(it.eligible(observation)) }
        // A matched original logout is allowed after token expiry, never a new identity grant.
        finished=true
    }
    private fun equal(left: ByteArray,right: ByteArray) {
        try { check(left.contentEquals(right)) } finally { left.fill(0); right.fill(0) }
    }
    fun close() { closed=true }
    companion object {
        fun session(source: RoomCurrentLocalAuthorizationSource,peer: RoomLocalAccessPeerBinding,context: CoroutineContext,
            issued: IssuedAuthentication): RoomLocalAuthenticationFrame = RoomLocalAuthenticationFrame(source,peer,context,issued,null)
        fun logout(source: RoomCurrentLocalAuthorizationSource,peer: RoomLocalAccessPeerBinding,context: CoroutineContext,
            original: LocalAccessIssuance): RoomLocalAuthenticationFrame = RoomLocalAuthenticationFrame(source,peer,context,null,original)
    }
}
