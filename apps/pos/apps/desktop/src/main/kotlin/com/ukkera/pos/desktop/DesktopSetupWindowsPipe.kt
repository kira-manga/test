package com.ukkera.pos.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import com.ukkera.pos.app.gateway.setup.ipc.DesktopSetupIpcConnection
import com.ukkera.pos.app.gateway.setup.ipc.DesktopSetupIpcEndpoint
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Windows setup-console transport between the packaged UI and the packaged Master service.
 * The service owns the pipe name from its first instance (FILE_FLAG_FIRST_PIPE_INSTANCE) and keeps
 * a next instance pending so the name is never released; remote clients are rejected; the protected
 * DACL admits only SYSTEM, Administrators and the owner (the service). Both ends verify that the
 * peer process carries this package's family name, so neither a squatting server nor a foreign
 * client is ever spoken to. The client requests SECURITY_IDENTIFICATION only (no impersonation).
 */
internal object DesktopSetupWindowsPipe {
    const val NAME: String = "\\\\.\\pipe\\UkkeraPOSTestMaster.setup"
    private const val SDDL = "D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;OW)"

    fun listen(expectedClientFamily: String): DesktopSetupIpcEndpoint = Endpoint(expectedClientFamily)

    fun connect(expectedServerFamily: String): DesktopSetupIpcConnection {
        val raw = Win.kernel.CreateFileW(WString(NAME), GENERIC_READ or GENERIC_WRITE, 0, null, OPEN_EXISTING,
            FILE_FLAG_OVERLAPPED or SECURITY_SQOS_PRESENT or SECURITY_IDENTIFICATION, null)
        if (invalid(raw)) throw IOException("The Master setup console is unavailable (${Native.getLastError()})")
        val handle = PipeHandle(raw!!)
        try {
            val pid = IntByReference()
            if (Win.kernel.GetNamedPipeServerProcessId(raw, pid) == 0 || packageFamily(pid.value) != expectedServerFamily) {
                throw IOException("The setup endpoint is not this package's Master service")
            }
        } catch (failure: Throwable) { handle.close(); throw failure }
        return PipeConnection(handle)
    }

    private class Endpoint(private val expectedClient: String) : DesktopSetupIpcEndpoint {
        private val descriptor: Pointer
        private val attributes = Memory(24)
        private var closed = false
        private var pending: PipeHandle?

        init {
            val sd = PointerByReference()
            check(Win.advapi.ConvertStringSecurityDescriptorToSecurityDescriptorW(WString(SDDL), 1, sd, null) != 0) {
                "Cannot build the setup console security descriptor"
            }
            descriptor = sd.value
            attributes.clear(); attributes.setInt(0, 24); attributes.setPointer(8, descriptor); attributes.setInt(16, 0)
            pending = try { create(first = true) } catch (failure: Throwable) { Win.kernel.LocalFree(descriptor); throw failure }
        }

        private fun create(first: Boolean): PipeHandle {
            val mode = PIPE_ACCESS_DUPLEX or FILE_FLAG_OVERLAPPED or (if (first) FILE_FLAG_FIRST_PIPE_INSTANCE else 0)
            val raw = Win.kernel.CreateNamedPipeW(WString(NAME), mode, PIPE_REJECT_REMOTE_CLIENTS, 2, 65536, 65536, 0, attributes)
            if (invalid(raw)) throw IOException("Cannot own the setup console pipe (${Native.getLastError()})")
            return PipeHandle(raw!!)
        }

        override fun accept(): DesktopSetupIpcConnection {
            while (true) {
                val current = synchronized(this) {
                    if (closed) throw IOException("closed")
                    pending ?: create(first = false).also { pending = it }
                }
                current.connectServer()
                synchronized(this) {
                    pending = null
                    if (closed) { current.close(); throw IOException("closed") }
                    // Keep the name owned while this peer is served.
                    pending = runCatching { create(first = false) }.getOrNull()
                }
                val pid = IntByReference()
                val trusted = runCatching {
                    current.use { Win.kernel.GetNamedPipeClientProcessId(it, pid) } != 0 && packageFamily(pid.value) == expectedClient
                }.getOrDefault(false)
                if (trusted) return PipeConnection(current)
                current.close()
            }
        }

        override fun close() {
            val waiting = synchronized(this) {
                if (closed) return
                closed = true
                pending.also { pending = null }
            }
            waiting?.close()
            Win.kernel.LocalFree(descriptor)
        }
    }

    /**
     * One overlapped handle. Operations run under a shared lock; [close] cancels outstanding I/O and
     * waits for those operations to leave before closing, so a handle value is never reused under them.
     */
    private class PipeHandle(private val raw: Pointer) : AutoCloseable {
        private val lock = ReentrantReadWriteLock()
        @Volatile private var closed = false

        fun <T> use(block: (Pointer) -> T): T {
            lock.readLock().lock()
            try {
                if (closed) throw IOException("closed")
                return block(raw)
            } finally { lock.readLock().unlock() }
        }

        /** Runs one overlapped operation to completion; returns the transferred count or -1 at end of pipe. */
        fun overlapped(start: (Pointer, Pointer) -> Int, acceptedError: Int = 0): Int = use { handle ->
            val event = Win.kernel.CreateEventW(null, 1, 0, null)
            if (invalid(event)) throw IOException("Cannot create an I/O event")
            val ov = Memory(32)
            try {
                ov.clear(); ov.setPointer(24, event)
                if (start(handle, ov) == 0) {
                    when (val error = Native.getLastError()) {
                        ERROR_IO_PENDING -> Unit
                        acceptedError -> return@use 0
                        ERROR_BROKEN_PIPE, ERROR_NO_DATA -> return@use -1
                        else -> throw IOException("Setup console I/O failed ($error)")
                    }
                }
                val done = IntByReference()
                if (Win.kernel.GetOverlappedResult(handle, ov, done, 1) == 0) {
                    when (val error = Native.getLastError()) {
                        ERROR_BROKEN_PIPE, ERROR_NO_DATA -> return@use -1
                        else -> throw IOException("Setup console I/O failed ($error)")
                    }
                }
                done.value
            } finally { Win.kernel.CloseHandle(event) }
        }

        fun connectServer() {
            overlapped({ handle, ov -> Win.kernel.ConnectNamedPipe(handle, ov) }, acceptedError = ERROR_PIPE_CONNECTED)
        }

        override fun close() {
            if (closed) return
            closed = true
            while (!lock.writeLock().tryLock(50, TimeUnit.MILLISECONDS)) Win.kernel.CancelIoEx(raw, null)
            try { Win.kernel.CloseHandle(raw) } finally { lock.writeLock().unlock() }
        }
    }

    private class PipeConnection(private val handle: PipeHandle) : DesktopSetupIpcConnection {
        private val readBuffer = Memory(65536)
        private val writeBuffer = Memory(65536)

        override val input: InputStream = object : InputStream() {
            override fun read(): Int { val one = ByteArray(1); return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                synchronized(readBuffer) {
                    val count = minOf(len, readBuffer.size().toInt())
                    val read = handle.overlapped({ h, ov -> Win.kernel.ReadFile(h, readBuffer, count, null, ov) })
                    if (read <= 0) { readBuffer.clear(); return -1 }
                    readBuffer.read(0, b, off, read)
                    readBuffer.clear(read.toLong())
                    return read
                }
            }
            override fun close() = this@PipeConnection.close()
        }

        override val output: OutputStream = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) = synchronized(writeBuffer) {
                var offset = off
                var remaining = len
                try {
                    while (remaining > 0) {
                        val count = minOf(remaining, writeBuffer.size().toInt())
                        writeBuffer.write(0, b, offset, count)
                        val written = handle.overlapped({ h, ov -> Win.kernel.WriteFile(h, writeBuffer, count, null, ov) })
                        if (written <= 0) throw IOException("closed")
                        offset += written; remaining -= written
                    }
                } finally { writeBuffer.clear() }
            }
            override fun close() = this@PipeConnection.close()
        }

        override fun close() = handle.close()
    }

    private fun packageFamily(pid: Int): String? {
        val process = Win.kernel.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid)
        if (invalid(process)) return null
        try {
            val length = IntByReference(256)
            val buffer = CharArray(256)
            if (Win.kernel.GetPackageFamilyName(process, length, buffer) != 0) return null
            return String(buffer, 0, (length.value - 1).coerceIn(0, 255))
        } finally { Win.kernel.CloseHandle(process) }
    }

    private fun invalid(handle: Pointer?): Boolean = handle == null || Pointer.nativeValue(handle) == -1L

    private const val GENERIC_READ = -0x80000000
    private const val GENERIC_WRITE = 0x40000000
    private const val OPEN_EXISTING = 3
    private const val FILE_FLAG_OVERLAPPED = 0x40000000
    private const val SECURITY_SQOS_PRESENT = 0x00100000
    private const val SECURITY_IDENTIFICATION = 0x00010000
    private const val PIPE_ACCESS_DUPLEX = 3
    private const val FILE_FLAG_FIRST_PIPE_INSTANCE = 0x00080000
    private const val PIPE_REJECT_REMOTE_CLIENTS = 8
    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
    private const val ERROR_IO_PENDING = 997
    private const val ERROR_PIPE_CONNECTED = 535
    private const val ERROR_BROKEN_PIPE = 109
    private const val ERROR_NO_DATA = 232

    @Suppress("FunctionName")
    private interface Kernel : StdCallLibrary {
        fun CreateNamedPipeW(name: WString, openMode: Int, pipeMode: Int, instances: Int, outSize: Int, inSize: Int,
            timeout: Int, attributes: Pointer?): Pointer?
        fun ConnectNamedPipe(handle: Pointer, overlapped: Pointer): Int
        fun CreateFileW(name: WString, access: Int, share: Int, attributes: Pointer?, disposition: Int, flags: Int, template: Pointer?): Pointer?
        fun ReadFile(handle: Pointer, buffer: Pointer, length: Int, read: Pointer?, overlapped: Pointer): Int
        fun WriteFile(handle: Pointer, buffer: Pointer, length: Int, written: Pointer?, overlapped: Pointer): Int
        fun GetOverlappedResult(handle: Pointer, overlapped: Pointer, transferred: IntByReference, wait: Int): Int
        fun CreateEventW(attributes: Pointer?, manual: Int, initial: Int, name: WString?): Pointer?
        fun CancelIoEx(handle: Pointer, overlapped: Pointer?): Int
        fun CloseHandle(handle: Pointer?): Int
        fun GetNamedPipeServerProcessId(handle: Pointer, pid: IntByReference): Int
        fun GetNamedPipeClientProcessId(handle: Pointer, pid: IntByReference): Int
        fun OpenProcess(access: Int, inherit: Int, pid: Int): Pointer?
        fun GetPackageFamilyName(process: Pointer?, length: IntByReference, name: CharArray): Int
        fun LocalFree(memory: Pointer?): Pointer?
    }

    @Suppress("FunctionName")
    private interface Advapi : StdCallLibrary {
        fun ConvertStringSecurityDescriptorToSecurityDescriptorW(text: WString, revision: Int, descriptor: PointerByReference,
            size: Pointer?): Int
    }

    private object Win {
        val kernel: Kernel by lazy { Native.load("kernel32", Kernel::class.java) }
        val advapi: Advapi by lazy { Native.load("advapi32", Advapi::class.java) }
    }
}
