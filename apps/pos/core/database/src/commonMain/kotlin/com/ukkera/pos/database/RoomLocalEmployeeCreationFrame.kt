package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Closed, all-INSERT writer component for one delegated creation. The actual service
 * coordinator must additionally confirm exact native settlement and post-return currentness.
 * The frame owns the exact live transaction, current creator and complete image census through
 * native preparation. It retains commitments, never the new password/verifier or a seed account. */
internal class RoomLocalEmployeeCreationFrame private constructor(
    private val principal: LocalEmployeePrincipal,
    private val original: LocalDelegatedEmployeeCreationV1,
    private val facts: RoomLocalAuthorizationFacts,
    private val source: RoomCurrentLocalAuthorizationSource,
    private val peer: RoomLocalAccessPeerBinding,
    private val context: CoroutineContext,
    private val capture: RuntimeDelegatedEmployeeCreationCapture,
) {
    private enum class Phase { NEW, INITIALIZING, BODY, PREPARING, PREPARED, FINISHED, CLOSED }
    private var phase = Phase.NEW
    private var failure: Exception? = null
    private var owner: RoomLocalSecurityTransaction? = null
    private var finished = false
    private var images: List<RuntimeEmployeeCreationImageV1> = emptyList()

    internal suspend fun initialize(transaction: RoomLocalSecurityTransaction,
        inputs: RoomLocalEmployeeCreationInputs): Unit = guarded {
        requirePhase(Phase.NEW)
        phase = Phase.INITIALIZING
        owner = transaction
        transaction.requireAdministrationWriteCount(0)
        val actual = transaction.readAuthorizationFacts(principal) ?: transaction.refuse()
        if (!facts.sameDependencies(actual) || !peer.isCurrent() || !peer.matchesAdmission(actual.admission))
            currentLocalAuthorizationDenied()
        val observed = source.observeCurrent(context)
        if (!eligible(observed)) currentLocalAuthorizationDenied()
        capture.bindPreflight(transaction, inputs, observed)
        transaction.historyPurpose(RuntimeWritePurposeV2.ACCOUNT_ADMINISTRATION,
            principal.actorId, original.requestId, original.contentDigest)
        transaction.beginHistoryEmployeeCreation(this, capture)
        phase = Phase.BODY
    }

    private suspend fun insert(transaction: RoomLocalSecurityTransaction,
        seed: RuntimeEmployeeCreationSeed): Unit = guarded {
        requirePhase(Phase.BODY)
        if (owner !== transaction) transaction.refuse()
        transaction.requireEmployeeCreationWriteCount(this, 0)
        // Keep the canonical image order. Each actual AEAD seal is checked by the history
        // capture before its INSERT; no caller-provided digest can substitute for that seal.
        transaction.insertAccount(seed.account)
        transaction.insertPolicy(seed.policy)
        transaction.insertAuthConfiguration(seed.configuration)
        for (throttle in seed.throttles) transaction.insertThrottle(throttle)
        transaction.insertAuthEvent(seed.event)
        transaction.requireEmployeeCreationWriteCount(this, capture.imageCount)
        images = capture.sealedImages()
    }

    internal suspend fun prepareNormalReturn(transaction: RoomLocalSecurityTransaction): Unit = guarded {
        requirePhase(Phase.BODY)
        if (owner !== transaction || images.size != capture.imageCount) transaction.refuse()
        phase = Phase.PREPARING
        transaction.requireEmployeeCreationWriteCount(this, images.size)
        val actual = transaction.readAuthorizationFacts(principal) ?: transaction.refuse()
        if (!facts.sameDependencies(actual) || !peer.isCurrent() || !peer.matchesAdmission(actual.admission))
            currentLocalAuthorizationDenied()
        val requested = original.copyRequestedDefinition()
        val admissions = transaction.readAdmissions(original.scope).sortedBy { it.deviceId.value }
        if (admissions.size != requested.admissions.size) transaction.refuse()
        for ((current, retained) in admissions.zip(requested.admissions.sortedBy { it.deviceId.value })) {
            if (digest(ProtectedLocalSecurityCodec.encodeAdmission(current)) !=
                digest(ProtectedLocalSecurityCodec.encodeAdmission(retained))) transaction.refuse()
        }
        val accounts = mutableListOf<DomainId>()
        transaction.forEachAccount(original.scope) { accounts += it.state.actorId }
        capture.requireCreatedAccountCensus(accounts)
        if (transaction.resolveLogin(original.scope, requested.definition.login)?.actorId != original.actorId) transaction.refuse()
        for (image in images) {
            val bytes = when (image.table) {
                RuntimeHistoryTableV2.ACCOUNT -> ProtectedLocalSecurityCodec.encodeAccount(
                    transaction.readProtectedAccount(original.scope, image.recordId) ?: transaction.refuse())
                RuntimeHistoryTableV2.POLICY -> LocalEffectivePolicyCodec.encodePolicy(
                    transaction.readPolicy(original.scope, image.recordId) ?: transaction.refuse())
                RuntimeHistoryTableV2.CONFIGURATION -> LocalAuthenticationCodec.encodeConfiguration(
                    transaction.readAuthConfiguration(original.scope, image.recordId) ?: transaction.refuse())
                RuntimeHistoryTableV2.THROTTLE -> {
                    val dimension = if (image.dimension == "account") ThrottleDimension.Account
                        else ThrottleDimension.Device(DomainId(checkNotNull(image.dimension).removePrefix("device:")))
                    ProtectedLocalSecurityCodec.encodeThrottle(transaction.readThrottle(
                        ThrottleKey(original.scope, image.recordId, dimension)) ?: transaction.refuse())
                }
                RuntimeHistoryTableV2.AUTH_EVENT -> LocalAuthenticationCodec.encodeEvent(
                    transaction.readAuthEvent(original.scope, image.recordId) ?: transaction.refuse())
                else -> transaction.refuse()
            }
            if (digest(bytes) != image.plaintextDigest) transaction.refuse()
        }
        transaction.ensureAuthorizationHealthy()
        transaction.requireEmployeeCreationWriteCount(this, images.size)
        transaction.completeHistoryEmployeeCreation(this, capture)
        phase = Phase.PREPARED
    }

    /** All protected reads and native preparation precede this last currentness observation. */
    internal fun finishNow(): Unit = guarded {
        requirePhase(Phase.PREPARED)
        if (!peer.isCurrent() || !eligible(source.observeCurrent(context))) currentLocalAuthorizationDenied()
        phase = Phase.FINISHED
        finished = true
    }

    /** Provisional staging is never a receipt. The eventual service must also have normal
     * outer COMMIT/native settlement/confirmed close before invoking this post-return check. */
    internal fun finishAfterCommit(caller: CoroutineContext): Boolean {
        caller.ensureActive()
        failure?.let { throw it }
        if (!finished || phase != Phase.CLOSED || !peer.isCurrent()) return false
        return try { eligible(source.observeCurrent(caller)) }
        catch (_: RoomCurrentLocalAuthorizationDenied) { false }
    }

    internal fun close() {
        phase = Phase.CLOSED
        owner = null
        images = emptyList()
        capture.clear()
    }

    private fun eligible(observed: RoomLocalAuthorizationObservation): Boolean = LocalAccessEligibility.matchesCurrentFacts(
        principal, facts.issuance, facts.account, facts.credentialRevision, facts.credentialPresent,
        facts.admission, observed.issuerContextId, observed.moment)

    private fun requirePhase(expected: Phase) {
        context.ensureActive()
        failure?.let { throw it }
        check(phase == expected)
    }
    private inline fun <T> guarded(block: () -> T): T = try {
        failure?.let { throw it }; block()
    } catch (caught: Exception) { if (failure == null) failure = caught; throw caught }

    internal sealed interface Staging {
        /** Only after the caller's normal outer transaction/native loan has returned. */
        fun finishAfterCommit(caller: CoroutineContext): Boolean
        class Staged(private val frame: RoomLocalEmployeeCreationFrame): Staging {
            override fun finishAfterCommit(caller: CoroutineContext): Boolean = frame.finishAfterCommit(caller)
        }
        class Refused(val review: LocalEmployeeCreationReviewV1.Refused,
            private val frame: RoomLocalAdministrationObservationFrame): Staging {
            override fun finishAfterCommit(caller: CoroutineContext): Boolean = frame.finishAfterCommit(caller)
        }
    }

    companion object {
        /** The future original-owning service supplies an authenticated principal and actual
         * native source/peer. This helper acquires no writer, hashes no password and grants
         * no original-start permission; its result exists only inside the caller's writer. */
        suspend fun stage(store: RoomLocalSecurityStore, transaction: RoomLocalSecurityTransaction, principal: LocalEmployeePrincipal,
            original: LocalDelegatedEmployeeCreationV1, source: RoomCurrentLocalAuthorizationSource,
            peer: RoomLocalAccessPeerBinding, credential: PasswordHashRecord?): Staging {
            val context = currentCoroutineContext()
            transaction.requireAdministrationWriteCount(0)
            check(source.belongsTo(store) && transaction.belongsTo(store.database))
            if (original.scope != principal.scope || original.operatorId != principal.actorId ||
                principal.authenticationPurpose != AuthenticationPurpose.INTERACTIVE ||
                peer.scope != principal.scope || peer.deviceId != principal.deviceId || !peer.isCurrent())
                currentLocalAuthorizationDenied()
            require(credential == null || credential.binding == CredentialBinding(original.scope, original.actorId, Revision(0)))
            val facts = transaction.readAuthorizationFacts(principal) ?: currentLocalAuthorizationDenied()
            val observed = source.observeCurrent(context)
            if (!peer.matchesAdmission(facts.admission) || !LocalAccessEligibility.matchesCurrentFacts(principal,
                    facts.issuance, facts.account, facts.credentialRevision, facts.credentialPresent,
                    facts.admission, observed.issuerContextId, observed.moment)) currentLocalAuthorizationDenied()
            val permitted = (AuthorizationEvaluator.evaluate(principal, facts.snapshot(principal),
                original.authorizationRequirement()) as? AuthorizationDecision.Allowed)?.authorization
                ?: currentLocalAuthorizationDenied()
            var refusal: LocalEmployeeCreationReviewV1.Refused? = null
            val staged = RoomLocalEmployeeCreationInputs.inspect<RoomLocalEmployeeCreationFrame?>(
                transaction, principal, original, onRefused = { refusal = it; null }, onChecked = { inputs ->
                    val at = source.observeCurrent(context)
                    val authorization = RuntimeCommandAuthorizationSnapshotV1.capture(principal, facts, observed)
                    val seed = RuntimeEmployeeCreationSeed.delegated(original, authorization, credential, at.moment)
                    val capture = RuntimeDelegatedEmployeeCreationCapture.capture(principal, facts, permitted,
                        original, observed, at.moment, credential)
                    val frame = RoomLocalEmployeeCreationFrame(principal, original, facts, source, peer, context, capture)
                    try {
                        transaction.attachEmployeeCreationFrame(frame, inputs)
                        frame.insert(transaction, seed)
                        frame
                    } catch (failure: Throwable) {
                        // The transaction may already own the frame; its close is idempotent.
                        frame.close()
                        throw failure
                    }
                })
            if (staged != null) return Staging.Staged(staged)
            // Input refusal can reveal protected catalogue/census facts. It is a real
            // zero-write read, with the same final currentness/close checks as the public
            // protected review; a valid initial session alone cannot publish its outcome.
            val read = RoomLocalAdministrationObservationFrame(principal, facts, permitted, source, peer, context)
            transaction.attachAdministrationObservationFrame(read)
            return Staging.Refused(checkNotNull(refusal), read)
        }

        private fun digest(bytes: ByteArray): PayloadDigest = try { sha256(bytes) } finally { bytes.fill(0) }
    }
}
