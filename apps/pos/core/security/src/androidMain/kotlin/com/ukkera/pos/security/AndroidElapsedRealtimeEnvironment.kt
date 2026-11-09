package com.ukkera.pos.security

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import com.ukkera.pos.types.DomainId
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal enum class AndroidElapsedRealtimeFailure {
    UNSUPPORTED_API,
    INVALID_APPLICATION_CONTEXT,
    DEVICE_NOT_UNLOCKED,
    BOOT_EVIDENCE_BUSY,
    BOOT_EVIDENCE_TIMEOUT,
    BOOT_EVIDENCE_UNAVAILABLE,
    NATIVE_CLOCK_UNAVAILABLE,
    PROCESS_CONTEXT_CHANGED,
}

internal sealed interface AndroidElapsedRealtimeAcquisition {
    class Acquired(val environment: AndroidElapsedRealtimeEnvironment) : AndroidElapsedRealtimeAcquisition {
        override fun toString(): String = "AndroidElapsedRealtimeAcquisition.Acquired([REDACTED])"
    }

    data class Unavailable(val reason: AndroidElapsedRealtimeFailure) : AndroidElapsedRealtimeAcquisition
}

/**
 * One INTERNAL, fresh authentication environment per service activation. The profile uses actual
 * public Android boot-count evidence acquired once AFTER first unlock, plus the suspend-inclusive
 * SystemClock.elapsedRealtimeNanos clock. This is not master appointment/storage custody and it
 * is not a claim that the selected OEM/device lifecycle has been physically qualified.
 *
 * issuerContextId is generated fresh here and is distinct from bootId. The future mobile owner
 * must use this issuer for the whole activation and call retire() on custody/lifecycle loss;
 * it must never reconstruct this source under an old issuer. No caller-authored liveness callback
 * or accepting Boolean is used. Retirement can only deny and is permanent.
 *
 * Same-activation callers in the current database architecture serialize their observations in
 * the actual writer. The bounded CAS loop also tolerates sampling contention without inventing
 * a clock value; exhausting it conservatively retires this source rather than waiting/spinning.
 */
internal class AndroidElapsedRealtimeEnvironment private constructor(
    val installationId: DomainId,
    val issuerContextId: DomainId,
    private val bootId: DomainId,
    private val processId: Int,
    private val processUid: Int,
    initialElapsedNanos: Long,
) : LocalAuthenticationEnvironment {
    val profile: String get() = "ukkera.android/boot-count-elapsed-realtime-nanos/v1"
    private val retired: AtomicBoolean = AtomicBoolean(false)
    private val lastElapsedNanos: AtomicLong = AtomicLong(initialElapsedNanos)

    override fun observe(): LocalAuthenticationObservation? {
        if (retired.get()) return null
        return try {
            if (Process.myPid() != processId || Process.myUid() != processUid) return unavailable()
            repeat(MAX_SAMPLE_ATTEMPTS) {
                if (retired.get()) return null
                val previous = lastElapsedNanos.get()
                // No settings/Binder/key/connection/file/wall-clock lookup belongs in observe().
                val sampled = SystemClock.elapsedRealtimeNanos()
                if (sampled < 0L || sampled < previous) return unavailable()
                if (lastElapsedNanos.compareAndSet(previous, sampled)) {
                    val observation = LocalAuthenticationObservation(issuerContextId,
                        MonotonicMoment(bootId, sampled / NANOS_PER_MILLISECOND))
                    return if (retired.get()) null else observation
                }
                // A racing newer sample is not itself regression. Resample against its current
                // high-water on the next bounded attempt; never clamp/fabricate elapsed time.
            }
            unavailable()
        } catch (cancelled: CancellationException) {
            // No returned clock fact: preserve cancellation rather than inventing an observation.
            throw cancelled
        } catch (_: Exception) {
            unavailable()
        }
    }

    /** Nonblocking denial only. This neither closes native work nor proves a process was reaped. */
    fun retire(): Unit {
        retired.set(true)
    }

    private fun unavailable(): LocalAuthenticationObservation? {
        retire()
        return null
    }

    override fun toString(): String = "AndroidElapsedRealtimeEnvironment([REDACTED])"

    companion object {
        /**
         * Only actual Application context is retained during acquisition, never an Activity.
         * One bounded outstanding native boot-evidence reader exists per process. Timeout or
         * cancellation may leave that read running; its slot stays occupied until it REALLY exits.
         * It owns no database/key handles and cannot publish a late environment after cancellation.
         * There is no retry or unknown-boot fallback. A later supervised activation may try again.
         */
        suspend fun acquireForNewServiceActivation(application: Application, installationId: DomainId,
            maximumBootReadMillis: Long): AndroidElapsedRealtimeAcquisition {
            require(maximumBootReadMillis in 1..30_000) { "Invalid boot-evidence acquisition budget" }
            currentCoroutineContext().ensureActive()
            if (Build.VERSION.SDK_INT < 24) return acquisitionUnavailable(AndroidElapsedRealtimeFailure.UNSUPPORTED_API)
            return try {
                val context = application.applicationContext
                    ?: return acquisitionUnavailable(AndroidElapsedRealtimeFailure.INVALID_APPLICATION_CONTEXT)
                val processId = Process.myPid()
                val processUid = Process.myUid()
                if (processId <= 0 || processUid < 0 || context.applicationInfo.uid != processUid) {
                    return acquisitionUnavailable(AndroidElapsedRealtimeFailure.INVALID_APPLICATION_CONTEXT)
                }
                val startedAt = SystemClock.elapsedRealtimeNanos()
                if (startedAt < 0L) return acquisitionUnavailable(AndroidElapsedRealtimeFailure.NATIVE_CLOCK_UNAVAILABLE)
                val evidence = withTimeoutOrNull(maximumBootReadMillis) {
                    AndroidBootCountReader.read(context)
                } ?: return acquisitionUnavailable(AndroidElapsedRealtimeFailure.BOOT_EVIDENCE_TIMEOUT)
                currentCoroutineContext().ensureActive()
                if (evidence is AndroidBootCountRead.Unavailable) return acquisitionUnavailable(evidence.reason)
                evidence as AndroidBootCountRead.Observed
                val now = SystemClock.elapsedRealtimeNanos()
                if (now < startedAt || now < evidence.elapsedNanos || evidence.elapsedNanos < startedAt) {
                    return acquisitionUnavailable(AndroidElapsedRealtimeFailure.NATIVE_CLOCK_UNAVAILABLE)
                }
                // Coroutine delay scheduling alone need not count deep sleep. The real elapsed
                // clock also enforces this acquisition budget after the native read returns.
                if (now - startedAt > maximumBootReadMillis * NANOS_PER_MILLISECOND) {
                    return acquisitionUnavailable(AndroidElapsedRealtimeFailure.BOOT_EVIDENCE_TIMEOUT)
                }
                if (Process.myPid() != processId || Process.myUid() != processUid) {
                    return acquisitionUnavailable(AndroidElapsedRealtimeFailure.PROCESS_CONTEXT_CHANGED)
                }
                val bootId = androidBootDomainId(installationId, evidence.bootCount)
                val issuer = DomainId.random()
                if (issuer == bootId) return acquisitionUnavailable(AndroidElapsedRealtimeFailure.NATIVE_CLOCK_UNAVAILABLE)
                currentCoroutineContext().ensureActive()
                AndroidElapsedRealtimeAcquisition.Acquired(AndroidElapsedRealtimeEnvironment(
                    installationId, issuer, bootId, processId, processUid, now))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                acquisitionUnavailable(AndroidElapsedRealtimeFailure.BOOT_EVIDENCE_UNAVAILABLE)
            }
        }

        private const val MAX_SAMPLE_ATTEMPTS: Int = 4
    }
}

private const val NANOS_PER_MILLISECOND: Long = 1_000_000L

private fun acquisitionUnavailable(reason: AndroidElapsedRealtimeFailure): AndroidElapsedRealtimeAcquisition =
    AndroidElapsedRealtimeAcquisition.Unavailable(reason)

private sealed interface AndroidBootCountRead {
    data class Observed(val bootCount: Int, val elapsedNanos: Long) : AndroidBootCountRead
    data class Unavailable(val reason: AndroidElapsedRealtimeFailure) : AndroidBootCountRead
}

/** One process-owned daemon reader, zero queued work, and no worker/thread per timeout retry. */
private object AndroidBootCountReader {
    private val reading: AtomicBoolean = AtomicBoolean(false)
    private val executor: ThreadPoolExecutor = ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(), ThreadFactory { work ->
            Thread(work, "ukkera-android-boot-evidence").apply { isDaemon = true }
        }, ThreadPoolExecutor.AbortPolicy()).apply { allowCoreThreadTimeOut(true) }

    suspend fun read(context: Context): AndroidBootCountRead = suspendCancellableCoroutine { continuation ->
        if (!reading.compareAndSet(false, true)) {
            continuation.resume(AndroidBootCountRead.Unavailable(AndroidElapsedRealtimeFailure.BOOT_EVIDENCE_BUSY))
            return@suspendCancellableCoroutine
        }
        try {
            executor.execute {
                try {
                    if (!continuation.isActive) return@execute
                    val result = try {
                        val users = context.getSystemService(UserManager::class.java)
                        if (users == null || !users.isUserUnlocked) {
                            AndroidBootCountRead.Unavailable(AndroidElapsedRealtimeFailure.DEVICE_NOT_UNLOCKED)
                        } else {
                            // No default value: unavailable evidence is NEVER boot zero.
                            val bootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
                            val elapsed = SystemClock.elapsedRealtimeNanos()
                            when {
                                bootCount < 0 -> AndroidBootCountRead.Unavailable(AndroidElapsedRealtimeFailure.BOOT_EVIDENCE_UNAVAILABLE)
                                elapsed < 0L -> AndroidBootCountRead.Unavailable(AndroidElapsedRealtimeFailure.NATIVE_CLOCK_UNAVAILABLE)
                                else -> AndroidBootCountRead.Observed(bootCount, elapsed)
                            }
                        }
                    } catch (_: Exception) {
                        AndroidBootCountRead.Unavailable(AndroidElapsedRealtimeFailure.BOOT_EVIDENCE_UNAVAILABLE)
                    }
                    // One completion only. Cancellable continuation discards a late plain metadata
                    // result; environment/issuer construction happens only in the live caller.
                    continuation.resume(result)
                } finally {
                    reading.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            reading.set(false)
            continuation.resume(AndroidBootCountRead.Unavailable(AndroidElapsedRealtimeFailure.BOOT_EVIDENCE_BUSY))
        } catch (_: Exception) {
            reading.set(false)
            continuation.resume(AndroidBootCountRead.Unavailable(AndroidElapsedRealtimeFailure.BOOT_EVIDENCE_UNAVAILABLE))
        }
    }
}

/**
 * Public boot-count evidence is scoped to the protected installation. This deterministic UUIDv8
 * encoding is NOT a service incarnation, wall-clock estimate, secret or rollback-resistant counter.
 * A real reboot ends this process; new activation obtains the current boot count again. Restored
 * whole-system snapshots/privileged settings tampering are outside this clock's evidence contract.
 */
private fun androidBootDomainId(installationId: DomainId, bootCount: Int): DomainId {
    require(bootCount >= 0)
    val framed = "ukkera.android-boot-count/v1\u0000${installationId.value}\u0000$bootCount".encodeToByteArray()
    val digest = MessageDigest.getInstance("SHA-256").digest(framed)
    return try {
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x80).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        val bytes = ByteBuffer.wrap(digest)
        DomainId(UUID(bytes.long, bytes.long).toString())
    } finally {
        framed.fill(0)
        digest.fill(0)
    }
}
