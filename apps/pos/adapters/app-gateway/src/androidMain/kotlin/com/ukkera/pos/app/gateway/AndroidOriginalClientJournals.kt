package com.ukkera.pos.app.gateway

import android.app.Application
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.types.DomainId
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext



public sealed interface AndroidOriginalClientJournalsResult {
    public class Opened internal constructor(public val journals: AndroidOriginalClientJournals) : AndroidOriginalClientJournalsResult
    public data object Unavailable : AndroidOriginalClientJournalsResult
}

/**
 * First cohost only: one device credential journal and that installation's first employee
 * intent journal. Both must be admitted before any gateway is published. Other employees and
 * additional devices need their own explicit enrollment; a lookup NEVER creates an empty one.
 * The root owns both native leases independently of UI sessions and the master Room lifetime.
 */
public class AndroidOriginalClientJournals private constructor(
    private val original: AndroidOriginalLocalClient,
    private val credentialsOwner: AndroidLocalClientJournalOwner,
    private val intentsOwner: AndroidLocalClientJournalOwner,
) : LocalClientIntentJournalSource {
    public val credentials: LocalClientCredentialJournal = checkNotNull(credentialsOwner.credentialJournal)
    private val intents = checkNotNull(intentsOwner.intentJournal)
    private val closure = AtomicReference<CompletableDeferred<Unit>?>(null)
    /** Exact acquired service object, not matching scalar scope/device fields. */
    internal fun belongsTo(original: AndroidOriginalLocalClient): Boolean = this.original === original && closure.get() == null

    override public suspend fun current(owner: LocalClientIdentity): LocalClientIntentJournal? =
        intents.takeIf { closure.get() == null && it.owner == owner }

    public suspend fun close(): Unit = withContext(NonCancellable) {
        val result = CompletableDeferred<Unit>()
        if (!closure.compareAndSet(null, result)) { checkNotNull(closure.get()).await(); return@withContext }
        var failure: Throwable? = null
        try { intentsOwner.close() } catch (caught: Throwable) { failure = caught }
        try { credentialsOwner.close() } catch (caught: Throwable) {
            if (failure == null) failure = caught else failure.addSuppressed(caught)
        }
        val problem = failure
        if (problem == null) result.complete(Unit) else result.completeExceptionally(problem)
        result.await()
    }

    override fun toString(): String = "AndroidOriginalClientJournals([REDACTED])"

    public companion object {
        /**
         * No automatic create-on-open. ENROLL is allowed only by the actual original service's
         * fully admitted C1 and refuses every existing/uncertain key or filesystem intention.
         * REOPEN verifies actual nonexportable native heads and complete durable contents.
         * CONTINUE requires positive completed/unused first-journal evidence, then starts only
         * the untouched second journal. It cannot repair a partial key/file/head or erase work.
         * RECOVER completes existing native-fenced ciphertext, never enrollment, keys or login.
         */
        public suspend fun acquire(application: Application, original: AndroidOriginalLocalClient,
            action: LocalOriginalClientJournalAction): AndroidOriginalClientJournalsResult {
            currentCoroutineContext().ensureActive()
            val firstEnrollment = action == LocalOriginalClientJournalAction.ENROLL_FIRST_CLIENT
            val continuation = action == LocalOriginalClientJournalAction.CONTINUE_FIRST_CLIENT
            val recoverWrites = action == LocalOriginalClientJournalAction.RECOVER_PENDING_WRITES
            val requiresPristineMaster = firstEnrollment || continuation
            if (!original.belongsTo(application) || (requiresPristineMaster && !original.permitsFirstEnrollment()))
                return AndroidOriginalClientJournalsResult.Unavailable
            var credentials: AndroidLocalClientJournalOwner? = null
            var intents: AndroidLocalClientJournalOwner? = null
            var delivered = false
            try {
                val first = AndroidLocalClientJournalOwner.acquireOriginal(application, original,
                    AndroidClientJournalKind.CREDENTIALS, firstEnrollment, recoverWrites)
                if (first !is AndroidClientJournalOutcome.Value) return AndroidOriginalClientJournalsResult.Unavailable
                credentials = first.value
                // This exact owner remains private/locked until both journals are published.
                // No failed read or missing first journal can authorize creation of the second.
                if (continuation && !first.value.verifyUnusedOriginalCredentials())
                    return AndroidOriginalClientJournalsResult.Unavailable
                val second = AndroidLocalClientJournalOwner.acquireOriginal(application, original,
                    AndroidClientJournalKind.INTENTS, requiresPristineMaster, recoverWrites)
                if (second !is AndroidClientJournalOutcome.Value) return AndroidOriginalClientJournalsResult.Unavailable
                intents = second.value
                currentCoroutineContext().ensureActive()
                if (!original.isCurrent() || (requiresPristineMaster && !original.permitsFirstEnrollment()) ||
                    (continuation && !first.value.verifyUnusedOriginalCredentials()))
                    return AndroidOriginalClientJournalsResult.Unavailable
                return AndroidOriginalClientJournalsResult.Opened(AndroidOriginalClientJournals(original, first.value, second.value))
                    .also { delivered = true }
            } finally {
                if (!delivered) withContext(NonCancellable) {
                    // Close BOTH actual owners; an uncertain close retains its process lease.
                    try { intents?.close() } finally { credentials?.close() }
                }
            }
        }
    }
}

/**
 * Format-bound IDs for this exact, already durable signed installation, not random identities
 * chosen after missing files. No scalar reopening API exports these as custody. SHA-256's
 * first 128 bits are encoded as a UUID-shaped DomainId; these IDs are not credentials.
 */
internal fun originalClientJournalId(original: AndroidOriginalLocalClient, kind: AndroidClientJournalKind,
    purpose: String): DomainId {
    val c = original.material.coordinates
    val body = listOf("ukkera.android.original-client-enrollment/v1", c.scope.organizationId.value,
        c.scope.companyId.value, c.scope.branchId.value, c.deviceId.value, c.installationId.value,
        c.firstActorId.value, original.receipt.core.originalRequestId.value, original.receipt.digest.value,
        if (kind == AndroidClientJournalKind.CREDENTIALS) "credentials" else "intents", purpose).joinToString("\n")
    return body.encodeToByteArray().useWiped { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).useWiped { hash ->
            val hex = encodeHex(hash).take(32)
            DomainId("${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}")
        }
    }
}
