package com.ukkera.pos.database

import androidx.room3.Transactor
import com.ukkera.pos.security.AuthenticatedPrincipal
import com.ukkera.pos.security.AuthorizationSnapshot
import com.ukkera.pos.security.LocalAccessEligibility
import com.ukkera.pos.security.LocalAuthenticationEntropy
import com.ukkera.pos.security.LocalAuthenticationObservation
import com.ukkera.pos.security.LocalEmployeePrincipal
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.types.DomainId
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ensureActive

/**
 * Trusted, bounded, synchronous observation of the CURRENT service incarnation and monotonic
 * environment. Return null unless their qualification and continuity are available. This is
 * not a platform implementation or evidence of qualification. The issuer context is distinct
 * from the OS boot and must change on reopen/recovery; no context is learned from an issuance.
 */
internal fun interface RoomLocalAuthorizationEnvironment {
    fun observe(): RoomLocalAuthorizationObservation?
}

internal data class RoomLocalAuthorizationObservation(
    val issuerContextId: DomainId,
    val moment: MonotonicMoment,
) {
    fun publicValue(): LocalAuthenticationObservation = LocalAuthenticationObservation(issuerContextId, moment)
}

/**
 * Current protected authorization for an ALREADY trusted local employee principal, not a
 * PrincipalVerifier, token verifier, login, refresh path or accepting platform composition.
 * One source belongs to one store/database and one supervised service lifetime. Its mutable
 * high-water is shared with its ONE physical-instruction registry. Bindings and the existing-
 * access verifier sample in the serialized writer; the owned post-loan publication/adoption/
 * event phases sample the same bounded environment and atom. The trusted composition must give
 * verifier, protected-write hooks and kernel THESE SAME owners. No state rolls back with SQL.
 *
 * Only observations of the trusted CURRENT environment establish/advance the high-water.
 * Null continuity, an actual current boot/context switch or elapsed regression permanently
 * disables this source. Expired/mismatching/malformed stored issuances poison only their view.
 * Arbitrarily recreating this object with an old issuer context is unsupported: this is not
 * disk-rollback resistance, persisted clock proof or service-incarnation provisioning.
 */
internal class RoomCurrentLocalAuthorizationSource(
    private val store: RoomLocalSecurityStore,
    private val environment: RoomLocalAuthorizationEnvironment,
    private val physicalRegistry: PhysicalInstructionPublicationRegistry = PhysicalInstructionPublicationRegistry(environment),
    handoffEntropy: LocalAuthenticationEntropy? = null,
) : TransactionAuthorizationSource {
    init { check(physicalRegistry.ownsEnvironment(environment)) }
    private val handoffCursors = HandoffCursorRoutes(handoffEntropy)

    /** Authentication must serialize its final observation through this exact store owner. */
    internal fun belongsTo(owner: RoomLocalSecurityStore): Boolean = store === owner

    /** Required for the concrete companion; a second source/pool cannot attach accepting hooks. */
    internal fun ownsPhysicalRegistry(registry: PhysicalInstructionPublicationRegistry): Boolean =
        physicalRegistry === registry && store.ownsPhysicalRegistry(registry)

    override suspend fun current(principal: AuthenticatedPrincipal, view: CommandReadView): AuthorizationSnapshot? {
        // An arbitrary public/fake read view is never a capability to inspect protected data.
        val writerView = view as? TransactionView ?: protectedLocalUnavailable()
        return writerView.currentLocalAuthorization(this, principal)
    }

    internal fun bind(
        database: MasterRoomDatabase,
        connection: Transactor,
        principal: LocalEmployeePrincipal,
        coroutineContext: CoroutineContext,
    ): RoomCurrentLocalAuthorizationBinding = RoomCurrentLocalAuthorizationBinding(
        this, principal, store.borrowAuthorizationReader(database, connection), coroutineContext,
    )

    internal fun observeCurrent(coroutineContext: CoroutineContext): RoomLocalAuthorizationObservation =
        physicalRegistry.observeCurrent(coroutineContext)

    internal fun issueHandoffCursor(principal: LocalEmployeePrincipal, observed: RoomLocalAuthorizationObservation,
        accessExpiresAtElapsedMillis: Long, phase: String, after: String?): String =
        handoffCursors.issue(principal, observed, accessExpiresAtElapsedMillis, phase, after)
    internal fun resumeHandoffCursor(principal: LocalEmployeePrincipal, observed: RoomLocalAuthorizationObservation,
        phase: String, handle: String): LocalHandoffCursorPosition? = handoffCursors.resume(principal, observed, phase, handle)
}

/** One concrete binding per actual view; never a reusable callback registry or read authority. */
internal class RoomCurrentLocalAuthorizationBinding internal constructor(
    private val source: RoomCurrentLocalAuthorizationSource,
    private val principal: LocalEmployeePrincipal,
    private val reader: RoomLocalAuthorizationReader,
    private val coroutineContext: CoroutineContext,
) {
    private enum class Phase { CURRENT, PREPARED, FINISHED, CLOSED }
    private enum class Failure { AUTHORIZATION, STORAGE }

    private var phase = Phase.CURRENT
    private var failure: Failure? = null
    private var cancellation: CancellationException? = null
    private var inProgress = false
    private var original: RoomLocalAuthorizationFacts? = null
    private var lastAuthorizationObservation: RoomLocalAuthorizationObservation? = null
    private var prepared: RoomLocalAuthorizationFacts? = null
    private var handoffCursorIssued = false
    private var handoffCursorResumed = false

    internal fun belongsTo(owner: RoomCurrentLocalAuthorizationSource, selected: AuthenticatedPrincipal): Boolean =
        source === owner && principal === selected

    internal suspend fun current(): AuthorizationSnapshot = try {
        begin(Phase.CURRENT)
        val facts = reader.facts(principal) ?: deny()
        ensurePhase(Phase.CURRENT)
        val observed = source.observeCurrent(coroutineContext)
        ensurePhase(Phase.CURRENT)
        val first = original
        if (first != null && !first.sameDependencies(facts)) deny()
        if (!eligible(facts, observed)) deny()
        if (first == null) original = facts
        lastAuthorizationObservation = observed
        AuthorizationSnapshot(principal.actorId, principal.kind, principal.scope, principal.sessionId,
            facts.issuance.generation, principal.deviceId, facts.admission.revision, facts.policy.revision,
            facts.account.enabled, true, facts.admission.admitted, facts.issuance.authenticationPurpose,
            facts.policy.grants)
    } catch (cancelled: CancellationException) {
        if (cancellation == null) cancellation = cancelled
        throw cancelled
    } catch (_: RoomCurrentLocalAuthorizationDenied) {
        deny()
    } catch (_: Exception) {
        unavailable()
    } finally {
        inProgress = false
    }

    /** Same successful current read, not a fresh clock sample or today's policy at restart.
     * The actual owning view checks frame/principal affinity before requesting this data;
     * prepareNormalReturn still rereads the same canonical dependencies before sealing. */
    internal fun captureRuntimeCommandAuthorization(): RuntimeCommandAuthorizationSnapshotV1 {
        ensurePhase(Phase.CURRENT)
        if (inProgress) unavailable()
        return RuntimeCommandAuthorizationSnapshotV1.capture(principal, original ?: unavailable(),
            lastAuthorizationObservation ?: unavailable())
    }

    internal suspend fun handoffRecipients(orderId: DomainId, after: DomainId?, limit: Int): LocalHandoffRecipientPage =
        recipientRead { reader.handoffRecipients(principal, orderId, after, limit) }
    internal suspend fun handoffRecipient(orderId: DomainId, recipientId: DomainId): LocalHandoffRecipient? =
        recipientRead { reader.handoffRecipient(principal, orderId, recipientId) }
    internal suspend fun handoffInboxTarget(): DomainId? = recipientRead { reader.handoffInboxTarget(principal) }
    internal suspend fun issueHandoffCursor(phase: String, after: String?): String = recipientRead {
        if (handoffCursorIssued) unavailable()
        handoffCursorIssued = true // At most one native CSPRNG call in this actual writer loan.
        val facts = original ?: unavailable()
        val observed = source.observeCurrent(coroutineContext)
        if (!eligible(facts, observed)) deny()
        source.issueHandoffCursor(principal, observed, facts.issuance.expiresAtElapsedMillis, phase, after)
    }
    internal suspend fun resumeHandoffCursor(phase: String, handle: String): LocalHandoffCursorPosition? = recipientRead {
        if (handoffCursorResumed) unavailable()
        handoffCursorResumed = true
        val observed = source.observeCurrent(coroutineContext)
        if (!eligible(original ?: unavailable(), observed)) deny()
        source.resumeHandoffCursor(principal, observed, phase, handle)
    }

    private suspend fun <T> recipientRead(read: suspend () -> T): T {
        current() // Current initiating employee, not the prospective recipient, owns this loan.
        return try {
            begin(Phase.CURRENT)
            val result = read()
            ensurePhase(Phase.CURRENT)
            reader.ensureHealthy()
            ensurePhase(Phase.CURRENT)
            if (!eligible(original ?: unavailable(), source.observeCurrent(coroutineContext))) deny()
            result
        } catch (cancelled: CancellationException) {
            if (cancellation == null) cancellation = cancelled
            throw cancelled
        } catch (_: RoomCurrentLocalAuthorizationDenied) { deny() }
        catch (_: Exception) { unavailable() }
        finally { inProgress = false }
    }

    /** All suspendable rereads and key/connection health checks occur before the clock phase. */
    internal suspend fun prepareNormalReturn() {
        try {
            begin(Phase.CURRENT)
            val first = original ?: unavailable()
            val latest = reader.facts(principal) ?: deny()
            ensurePhase(Phase.CURRENT)
            if (!first.sameDependencies(latest)) deny()
            reader.ensureHealthy()
            ensurePhase(Phase.CURRENT)
            prepared = latest
            phase = Phase.PREPARED
        } catch (cancelled: CancellationException) {
            if (cancellation == null) cancellation = cancelled
            throw cancelled
        } catch (_: RoomCurrentLocalAuthorizationDenied) {
            deny()
        } catch (_: Exception) {
            unavailable()
        } finally {
            inProgress = false
        }
    }

    /**
     * Captured AFTER the final protected rereads, BEFORE the last environment callback. Only
     * original scalar identity + genuine access expiry escape, never facts/reader/grants/hash.
     * Actual local issuance bounds access by the original work deadline; do not invent a new
     * lifetime from the short publication lag or copy a refresh DTO as current authorization.
     */
    internal fun physicalAccessWindow(registry: PhysicalInstructionPublicationRegistry,
        origin: PhysicalInteractiveOrigin): PhysicalInteractiveAccessWindow {
        ensurePhase(Phase.PREPARED)
        if (inProgress || !source.ownsPhysicalRegistry(registry) || !origin.matches(principal)) deny()
        val facts = prepared ?: unavailable()
        val issuance = facts.issuance
        return PhysicalInteractiveAccessWindow(PhysicalInteractiveIdentity(principal), issuance.issuerContextId,
            issuance.issuedAt.bootId, issuance.issuedAt.elapsedMillis, issuance.expiresAtElapsedMillis)
    }

    /**
     * Last callback is the synchronous CURRENT environment sample. After it, only captured
     * facts, high-water/state updates and pure cancellation/eligibility comparisons remain.
     * The owning view/kernel may then compare its already captured business deadline only.
     */
    internal fun finishNow(): RoomLocalAuthorizationObservation {
        try {
            begin(Phase.PREPARED)
            val facts = prepared ?: unavailable()
            val observed = source.observeCurrent(coroutineContext)
            ensurePhase(Phase.PREPARED)
            if (!eligible(facts, observed)) deny()
            phase = Phase.FINISHED
            return observed
        } catch (cancelled: CancellationException) {
            if (cancellation == null) cancellation = cancelled
            throw cancelled
        } catch (_: RoomCurrentLocalAuthorizationDenied) {
            deny()
        } catch (_: Exception) {
            unavailable()
        } finally {
            inProgress = false
        }
    }

    /** Nonthrowing invalidation only; never validate from a finally/unwinding path. */
    internal suspend fun close() {
        phase = Phase.CLOSED
        original = null
        lastAuthorizationObservation = null
        prepared = null
        reader.close()
    }

    private fun begin(expected: Phase) {
        ensurePhase(expected)
        if (inProgress) unavailable()
        inProgress = true
    }

    private fun ensurePhase(expected: Phase) {
        coroutineContext.ensureActive()
        throwIfFailed()
        if (phase != expected) unavailable()
    }

    private fun eligible(facts: RoomLocalAuthorizationFacts, observed: RoomLocalAuthorizationObservation): Boolean =
        LocalAccessEligibility.matchesCurrentFacts(principal, facts.issuance, facts.account,
            facts.credentialRevision, facts.credentialPresent, facts.admission,
            observed.issuerContextId, observed.moment)

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
    }
}

/** No private row, provider, SQL or parser details (including causes) escape this boundary. */
internal class RoomCurrentLocalAuthorizationDenied : IllegalStateException("Current local authorization unavailable")

internal fun currentLocalAuthorizationDenied(): Nothing = throw RoomCurrentLocalAuthorizationDenied()
