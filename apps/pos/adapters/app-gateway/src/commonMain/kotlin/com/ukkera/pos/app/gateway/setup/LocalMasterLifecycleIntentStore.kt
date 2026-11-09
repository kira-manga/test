package com.ukkera.pos.app.gateway.setup

import com.ukkera.pos.security.LocalMasterLifecycleIntent

/**
 * Private forced storage adapter. The common store owns the only canonical encoding and
 * interpretation; the platform adapter only reads/replaces/clears exact bytes.
 */
public interface LocalMasterLifecycleIntentStorage {
    /** Null means verified absence. A partial/foreign/locked namespace must throw. */
    public fun read(): ByteArray?
    /** Returns a complete-looking interrupted `.new` payload without promoting it. */
    public fun readInterruptedWrite(): ByteArray?
    /** Promote the exact verified interrupted bytes to the canonical file, forced/read back. */
    public fun commitRecovered(bytes: ByteArray)
    /** Force the complete record and containing directory before returning. */
    public fun replaceAndForce(bytes: ByteArray)
    /** Remove only a known complete record and force the containing directory. */
    public fun clearAndForce()
}

public sealed interface LocalMasterLifecycleIntentRead {
    public data object Absent : LocalMasterLifecycleIntentRead
    public class Retained(public val intent: LocalMasterLifecycleIntent) : LocalMasterLifecycleIntentRead
    /** A malformed, foreign, partial or inaccessible record. Never treated as Absent. */
    public data object Unresolved : LocalMasterLifecycleIntentRead
}

/**
 * Durable pre-dispatch lifecycle request identity. It is separate from the launch hint and
 * never rewrites old launch bytes. A retained record is reused byte-for-byte after process
 * loss, so unknown auth/dispatch outcomes cannot silently mint a new request ID.
 */
public class LocalMasterLifecycleIntentStore(private val storage: LocalMasterLifecycleIntentStorage) {
    private val monitor = LocalSetupMonitor()

    public fun read(): LocalMasterLifecycleIntentRead = withSetupLock(monitor) {
        val bytes = try { storage.read() } catch (_: Exception) {
            return@withSetupLock LocalMasterLifecycleIntentRead.Unresolved
        } ?: return@withSetupLock LocalMasterLifecycleIntentRead.Absent
        try {
            LocalMasterLifecycleIntentRead.Retained(LocalMasterLifecycleIntent.decode(bytes))
        } catch (_: Exception) {
            LocalMasterLifecycleIntentRead.Unresolved
        } finally { bytes.fill(0) }
    }

    /**
     * Evidence-preserving recovery for a crash during the initial atomic write. Only a complete
     * canonical `.new` record is promoted. Partial/foreign/unreadable bytes are left in place
     * and reported Unresolved; they are never treated as Absent or replaced by a new ID.
     */
    public fun settleInterruptedWrite(): LocalMasterLifecycleIntentRead = withSetupLock(monitor) {
        val interrupted = try { storage.readInterruptedWrite() } catch (_: Exception) {
            return@withSetupLock LocalMasterLifecycleIntentRead.Unresolved
        } ?: return@withSetupLock read()
        try {
            val intent = LocalMasterLifecycleIntent.decode(interrupted)
            storage.commitRecovered(interrupted)
            LocalMasterLifecycleIntentRead.Retained(intent)
        } catch (_: Exception) {
            LocalMasterLifecycleIntentRead.Unresolved
        } finally { interrupted.fill(0) }
    }

    /** Exact write/readback before the caller may authenticate or dispatch a lifecycle action. */
    public fun retain(intent: LocalMasterLifecycleIntent): LocalMasterLifecycleIntent = withSetupLock(monitor) {        val expected = intent.encode()
        try {
            storage.replaceAndForce(expected)
            val actual = checkNotNull(storage.read()) { "Lifecycle intent write is unresolved" }
            try {
                check(actual.contentEquals(expected)) { "Lifecycle intent readback differs" }
                val decoded = LocalMasterLifecycleIntent.decode(actual)
                check(decoded.request.requestId == intent.request.requestId &&
                    decoded.request.action == intent.request.action &&
                    decoded.installationId == intent.installationId &&
                    decoded.coordinatesDigest == intent.coordinatesDigest &&
                    decoded.requestDigest == intent.requestDigest) { "Lifecycle intent readback differs" }
            } finally { actual.fill(0) }
            intent
        } finally { expected.fill(0) }
    }

    /** Only after the caller has durably observed the terminal launch projection. */
    public fun clear() { withSetupLock(monitor) {
        storage.clearAndForce()
        check(storage.read() == null) { "Lifecycle intent retirement is unresolved" }
    } }

    public companion object {
        public const val MAXIMUM_BYTES: Int = LocalMasterLifecycleIntent.MAXIMUM_BYTES
    }
}
