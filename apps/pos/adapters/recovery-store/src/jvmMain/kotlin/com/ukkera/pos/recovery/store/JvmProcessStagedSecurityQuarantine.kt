package com.ukkera.pos.recovery.store

import com.ukkera.pos.types.DomainId
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Configuration belongs only to the trusted service composition, never a restore request. */
public class JvmQuarantineProcessConfiguration(
    command: List<String>,
    public val workingDirectory: Path,
    environment: Map<String, String>,
    public val maximumRunMillis: Long = 120_000,
    public val gracefulStopMillis: Long = 2_000,
    public val forcedStopMillis: Long = 10_000,
    public val maximumDrainMillis: Long = 3_000,
) {
    private val commandValues: List<String> = command.toList()
    private val environmentValues: Map<String, String> = environment.toMap()
    public val command: List<String> get() = commandValues.toList()
    public val environment: Map<String, String> get() = environmentValues.toMap()

    init {
        require(commandValues.size in 1..128 && commandValues.all { it.length in 1..8192 && '\u0000' !in it })
        val executable = Path.of(commandValues.first())
        require(executable.isAbsolute && executable == executable.normalize())
        require(workingDirectory.isAbsolute && workingDirectory == workingDirectory.normalize())
        require(environmentValues.size <= 128 && environmentValues.all { (key, value) ->
            key.length in 1..128 && key.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' } &&
                value.length <= 8192 && '\u0000' !in value
        })
        require(maximumRunMillis in 1_000..300_000 && gracefulStopMillis in 1..30_000 &&
            forcedStopMillis in 1_000..30_000 && maximumDrainMillis in 100..30_000)
    }

    override fun toString(): String = "JvmQuarantineProcessConfiguration([REDACTED])"
}

public enum class QuarantineProcessUncertainty {
    START_UNCERTAIN,
    EXIT_UNCONFIRMED,
    CONTAINMENT_UNCONFIRMED,
    PIPE_CLEANUP_UNCONFIRMED,
    CUSTODY_RELEASE_UNCONFIRMED,
}

/**
 * Mandatory bridge to the SAME qualified stopped-maintenance supervisor. Before returning a
 * lease, authorize the exact stage/request and durably account for a possibly starting child.
 * Arrange an independent OS deadline/containment that also covers blocked ProcessBuilder.start
 * and a parent crash before childStarted. No callback/status alone proves that containment.
 * The launch image/argv/environment must be fixed, integrity-qualified and unable to escape its
 * containment or hand staged-file handles to another process. Java waitFor proves only direct
 * child exit. Cancellation before lease delivery must not orphan the supervisor's ownership.
 */
public fun interface QuarantineProcessCustody {
    public suspend fun acquire(
        request: StagedSecurityQuarantineRequest,
        maximumRunMillis: Long,
    ): QuarantineProcessCustodyLease
}

public interface QuarantineProcessCustodyLease {
    public suspend fun ensureStopped()

    /** Register the actual child handle, not a caller PID. This is not containment qualification. */
    public fun childStarted(child: ProcessHandle)

    /**
     * Called only after this parent actually observed direct-child waitFor completion. Independently
     * confirm the entire supervised containment is reaped/no escaped descendants hold this stage.
     * Throw on uncertainty; do not turn an expired join into this acknowledgement. Must be idempotent.
     */
    public suspend fun confirmReaped(child: ProcessHandle)

    /** Nonblocking: retain durable stop/custody and arrange actual OS retirement, not Room.close. */
    public fun retirementRequired(reason: QuarantineProcessUncertainty, child: ProcessHandle?)

    /**
     * Release only this helper loan after confirmed reap (or an unattempted launch). Never resume
     * the master here; its enclosing maintenance lease still owns it. Uncertainty retains custody.
     */
    public suspend fun close()
}

/**
 * Real parent-side ProcessBuilder adapter, with no shell interpolation or inherited environment.
 * It does not supply the child entrypoint, key provider, recovery authorization, master stop or OS
 * containment. All are required production integration, not synthetic accepting defaults.
 *
 * Stdout/stderr drain independently with fixed byte bounds. A private bounded request file is
 * redirected as stdin so a child not reading input cannot strand the supervisor in a pipe write.
 * Cancellation/deadline causes graceful then forcible termination and actual waitFor before a
 * successful custody release. An unconfirmed start/reap/pipe cleanup permanently retires this
 * wrapper. No stage file is removed here; the enclosing restore removes invocation files only
 * after a successful receipt, and otherwise retains the stage for supervised cleanup.
 */
public class JvmProcessStagedSecurityQuarantine(
    private val configuration: JvmQuarantineProcessConfiguration,
    private val custody: QuarantineProcessCustody,
) : StagedSecurityQuarantine {
    private val mutex = Mutex()
    private var retired: Boolean = false

    override suspend fun quarantine(request: StagedSecurityQuarantineRequest): StagedSecurityQuarantineReceipt {
        mutex.lock()
        try {
            if (retired) fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
            val lease = try { custody.acquire(request, configuration.maximumRunMillis) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE) }
            val invocation = Invocation()
            var result: StagedSecurityQuarantineReceipt? = null
            var failure: Throwable? = null
            var cancellation: CancellationException? = null
            try {
                result = withContext(Dispatchers.IO) { runChild(request, lease, invocation) }
            } catch (caught: Throwable) {
                failure = caught
                if (caught is CancellationException) cancellation = caught
            }
            finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (settleChild(lease, invocation)) {
                        try { lease.close() }
                        catch (_: Throwable) {
                            uncertain(lease, invocation, QuarantineProcessUncertainty.CUSTODY_RELEASE_UNCONFIRMED)
                            failure = LocalBackupException(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
                        }
                    } else {
                        // Do NOT call close/release even if the retirement callback failed. The
                        // acquired durable helper loan must remain outstanding until supervision
                        // establishes actual containment reap, never merely garbage collection.
                        failure = LocalBackupException(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
                    }
                    invocation.output?.fill(0)
                }
            }
            // Keep caller cancellation even if cleanup also discovered uncertainty. The wrapper
            // and mandatory supervisor already retain the stop; cancellation proves no outcome.
            currentCoroutineContext().ensureActive()
            cancellation?.let { throw it }
            if (failure != null) fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
            return result ?: fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
        } finally { mutex.unlock() }
    }

    private suspend fun runChild(
        request: StagedSecurityQuarantineRequest,
        lease: QuarantineProcessCustodyLease,
        invocation: Invocation,
    ): StagedSecurityQuarantineReceipt {
        currentCoroutineContext().ensureActive()
        lease.ensureStopped()
        val path = request.databasePath
        if (!path.isAbsolute || path != path.normalize() || path.parent == null || Files.isSymbolicLink(path)) {
            fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
        }
        requireRegular(path)
        privateDirectory(path.parent)
        privateDirectory(configuration.workingDirectory)
        val executable = Path.of(configuration.command.first())
        requireRegular(executable)
        if (!Files.isExecutable(executable)) fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
        val input = path.parent.resolve(".quarantine-request-${DomainId.random().value}")
        privateNewFile(input).use { channel ->
            StagedQuarantineProcessProtocol.writeRequest(Channels.newOutputStream(channel), request)
            channel.force(true)
        }
        forceDirectory(path.parent)
        invocation.io = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "ukkera-quarantine-pipe").apply { isDaemon = true }
        }
        val builder = ProcessBuilder(configuration.command).directory(configuration.workingDirectory.toFile())
            .redirectInput(input.toFile()).redirectErrorStream(false)
        builder.environment().clear()
        builder.environment().putAll(configuration.environment)
        val startedAt = System.nanoTime()
        invocation.launchAttempted = true
        val child = builder.start().also { invocation.child = it }
        lease.childStarted(child.toHandle())
        val io = checkNotNull(invocation.io)
        invocation.stdout = io.submit(Callable { boundedOutput(child.inputStream, StagedQuarantineProcessProtocol.maximumReceiptStreamBytes) })
        invocation.stderr = io.submit(Callable { discardBounded(child.errorStream, StagedQuarantineProcessProtocol.MAXIMUM_PAYLOAD_BYTES) })
        while (true) {
            currentCoroutineContext().ensureActive()
            check(elapsedMillis(startedAt) < configuration.maximumRunMillis)
            // Completed exceptional readers are inspected promptly; neither an output flood nor
            // malformed child output gets to hold the process until the full deadline by design.
            invocation.stdout?.takeIf { it.isDone }?.get()
            invocation.stderr?.takeIf { it.isDone }?.get()
            if (child.waitFor(100, TimeUnit.MILLISECONDS)) break
        }
        check(elapsedMillis(startedAt) < configuration.maximumRunMillis && child.exitValue() == 0)
        val drainStart = System.nanoTime()
        while (invocation.stdout?.isDone != true || invocation.stderr?.isDone != true) {
            currentCoroutineContext().ensureActive()
            check(elapsedMillis(drainStart) < configuration.maximumDrainMillis)
            delay(10)
        }
        invocation.stderr?.get()
        val bytes = checkNotNull(invocation.stdout).get().also { invocation.output = it }
        val receipt = StagedQuarantineProcessProtocol.readReceipt(bytes)
        check(receipt.scope == request.scope && receipt.backupId == request.backupId &&
            receipt.incidentId == request.incidentId && receipt.targetDeviceId == request.targetDeviceId)
        lease.ensureStopped()
        return receipt // The finally path still has to confirm containment reap and join pipe tasks.
    }

    private suspend fun settleChild(lease: QuarantineProcessCustodyLease, invocation: Invocation): Boolean {
        var confirmed = true
        val child = invocation.child
        if (child == null && invocation.launchAttempted) {
            uncertain(lease, invocation, QuarantineProcessUncertainty.START_UNCERTAIN)
            confirmed = false
        } else if (child != null) {
            val exited = try {
                if (!child.waitFor(0, TimeUnit.MILLISECONDS)) {
                    try { child.destroy() } catch (_: Throwable) { /* Still require actual wait/force. */ }
                    if (!child.waitFor(configuration.gracefulStopMillis, TimeUnit.MILLISECONDS)) {
                        try { child.destroyForcibly() } catch (_: Throwable) { /* No inferred success. */ }
                    }
                }
                child.waitFor(configuration.forcedStopMillis, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) { false }
            if (!exited) {
                uncertain(lease, invocation, QuarantineProcessUncertainty.EXIT_UNCONFIRMED)
                confirmed = false
            } else {
                try { lease.confirmReaped(child.toHandle()) }
                catch (_: Throwable) {
                    uncertain(lease, invocation, QuarantineProcessUncertainty.CONTAINMENT_UNCONFIRMED)
                    confirmed = false
                }
            }
        }
        val io = invocation.io
        if (io != null) {
            val drained = try {
                io.shutdown()
                io.awaitTermination(configuration.maximumDrainMillis, TimeUnit.MILLISECONDS)
            } catch (_: Throwable) { false }
            if (!drained) {
                uncertain(lease, invocation, QuarantineProcessUncertainty.PIPE_CLEANUP_UNCONFIRMED)
                confirmed = false
                try { io.shutdownNow() } catch (_: Throwable) { /* Retirement owns remaining threads. */ }
            }
        }
        // A cancelled recipient can leave a completed result in a Future. Recover ownership for
        // best-effort clearing even if normal parsing never obtained it. No memory-erasure claim.
        if (invocation.output == null) {
            try { invocation.stdout?.takeIf { it.isDone && !it.isCancelled }?.get()?.let { invocation.output = it } }
            catch (_: Throwable) { /* No successfully returned buffer. */ }
        }
        return confirmed
    }

    private fun uncertain(
        lease: QuarantineProcessCustodyLease, invocation: Invocation, reason: QuarantineProcessUncertainty,
    ) {
        retired = true
        try { lease.retirementRequired(reason, invocation.child?.toHandle()) }
        catch (_: Throwable) { /* Permanently retired; never convert a hook failure to safe custody. */ }
    }
}

private class Invocation {
    var launchAttempted: Boolean = false
    var child: Process? = null
    var io: ExecutorService? = null
    var stdout: Future<ByteArray>? = null
    var stderr: Future<Unit>? = null
    var output: ByteArray? = null
}

private fun elapsedMillis(start: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

private fun boundedOutput(input: InputStream, maximum: Int): ByteArray {
    val buffer = ByteArray(maximum + 1)
    try {
        input.use {
            var offset = 0
            while (offset < buffer.size) {
                val count = it.read(buffer, offset, buffer.size - offset)
                if (count < 0) return buffer.copyOf(offset)
                if (count > 0) offset += count
            }
            fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
        }
    } finally { buffer.fill(0) }
}

private fun discardBounded(input: InputStream, maximum: Int) {
    val buffer = ByteArray(8192)
    var count = 0L
    try {
        input.use {
            while (true) {
                val read = it.read(buffer)
                if (read < 0) return
                count += read
                if (count > maximum) fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE)
            }
        }
    } finally { buffer.fill(0) }
}
