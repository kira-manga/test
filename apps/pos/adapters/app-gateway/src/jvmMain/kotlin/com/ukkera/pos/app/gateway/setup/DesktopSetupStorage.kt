package com.ukkera.pos.app.gateway.setup

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Desktop private-file primitives for the shared setup records. The root is the app's own
 * OS-private folder (Windows package LocalState, macOS Application Support/<bundle>, 0700).
 * No codec, role or recovery decision lives here; partial/foreign state throws.
 */
internal class DesktopSetupDirectory(parent: Path, name: String, private val maximumBytes: Int, private val maximumFiles: Int) {
    private val posix = parent.fileSystem.supportedFileAttributeViews().contains("posix")
    private val root: Path = parent.resolve(name)
    init {
        require(name.matches(Regex("[a-z0-9.-]{3,96}")) && name != "." && name != "..")
        check(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS))
    }

    fun directory(create: Boolean): Path? {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            if (!create) return null
            try {
                if (posix) Files.createDirectory(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                else Files.createDirectory(root)
            } catch (_: FileAlreadyExistsException) { /* Checked below. */ }
            force(root.parent)
        }
        checkDirectory()
        return root
    }

    fun checkDirectory() {
        check(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) { "Setup storage is not a private directory" }
        if (posix) check(Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS) ==
            PosixFilePermissions.fromString("rwx------")) { "Setup storage permissions differ" }
    }

    fun names(): List<String>? {
        val dir = directory(create = false) ?: return null
        val names = Files.newDirectoryStream(dir).use { stream -> stream.map { it.fileName.toString() }.also { check(it.size <= maximumFiles) } }
        checkDirectory()
        return names
    }

    fun exists(name: String): Boolean = directory(false)?.let { Files.exists(it.resolve(requireName(name)), LinkOption.NOFOLLOW_LINKS) } == true

    fun read(name: String): ByteArray {
        val path = checkNotNull(directory(false)).resolve(requireName(name))
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { "Setup record is not a regular file" }
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            check(size in 1..maximumBytes.toLong()) { "Setup record size differs" }
            val bytes = ByteArray(size.toInt())
            try {
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) check(channel.read(buffer) > 0)
                check(channel.read(ByteBuffer.allocate(1)) == -1)
                return bytes
            } catch (failure: Throwable) { bytes.fill(0); throw failure }
        }
    }

    /** O_EXCL create, force, directory force and exact readback. */
    fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean) {
        require(bytes.size in 1..maximumBytes)
        val dir = checkNotNull(directory(true))
        val path = dir.resolve(requireName(name))
        try {
            FileChannel.open(path, setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS),
                *privateFile()).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
                channel.force(true)
            }
        } catch (existing: FileAlreadyExistsException) {
            if (!allowExactExisting) throw existing
            val current = read(name)
            try { require(current.contentEquals(bytes)) } finally { current.fill(0) }
            return
        }
        force(dir)
        val retained = read(name)
        try { check(retained.contentEquals(bytes)) { "Setup record readback differs" } } finally { retained.fill(0) }
    }

    /** Write `<name>.new`, force, atomic replace, force directory, readback. */
    fun replace(name: String, bytes: ByteArray) {
        require(bytes.size in 1..maximumBytes)
        val dir = checkNotNull(directory(true))
        val temporary = dir.resolve(requireName("$name.new"))
        Files.deleteIfExists(temporary)
        FileChannel.open(temporary, setOf(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS),
            *privateFile()).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
            channel.force(true)
        }
        promote(name)
        val retained = read(name)
        try { check(retained.contentEquals(bytes)) { "Setup record readback differs" } } finally { retained.fill(0) }
    }

    fun promote(name: String) {
        val dir = checkNotNull(directory(false))
        Files.move(dir.resolve(requireName("$name.new")), dir.resolve(requireName(name)),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        force(dir)
    }

    fun delete(name: String) {
        val dir = checkNotNull(directory(false))
        check(Files.deleteIfExists(dir.resolve(requireName(name)))) { "Setup record is absent" }
        force(dir)
    }

    private fun privateFile() = if (posix) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        else emptyArray()

    /** POSIX directory fsync; NTFS metadata is journaled and directories cannot be opened for force. */
    private fun force(directory: Path) {
        if (!posix) return
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun requireName(name: String): String {
        require(name.length in 1..100 && name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '.' } &&
            name != "." && name != "..")
        return name
    }
}

/** Shared setup intent namespace on desktop. */
public class DesktopSetupIntentStorage internal constructor(private val directory: DesktopSetupDirectory) : LocalSetupIntentStorage {
    @Synchronized override fun names(): List<String>? = directory.names()
    @Synchronized override fun read(name: String): ByteArray = directory.read(name)
    @Synchronized override fun writeExclusive(name: String, bytes: ByteArray, allowExactExisting: Boolean): Unit =
        directory.writeExclusive(name, bytes, allowExactExisting)
    @Synchronized override fun verify() { checkNotNull(directory.directory(false)) }
}

/** Launch hint: an interrupted replace is unresolved, never rolled back to an older record. */
public class DesktopMasterLaunchStorage internal constructor(private val directory: DesktopSetupDirectory) : LocalMasterLaunchStorage {
    @Synchronized override fun read(): ByteArray? {
        val names = directory.names() ?: return null
        require(names.all { it == FILE || it == "$FILE.new" })
        if (names.isEmpty()) return null
        check(names == listOf(FILE)) { "Local launch record write remains unresolved" }
        return directory.read(FILE)
    }
    @Synchronized override fun replaceAndForce(bytes: ByteArray) {
        check(!directory.exists("$FILE.new")) { "Local launch record write remains unresolved" }
        directory.replace(FILE, bytes)
    }
    private companion object { const val FILE = "launch" }
}

/** Lifecycle intent record with explicit interrupted-write recovery. */
public class DesktopMasterLifecycleIntentStorage internal constructor(private val directory: DesktopSetupDirectory) :
    LocalMasterLifecycleIntentStorage {
    @Synchronized override fun read(): ByteArray? {
        val names = directory.names() ?: return null
        require(names.all { it == FILE || it == "$FILE.new" })
        return if (FILE in names) directory.read(FILE) else null
    }
    @Synchronized override fun readInterruptedWrite(): ByteArray? =
        if (directory.exists("$FILE.new")) directory.read("$FILE.new") else null
    @Synchronized override fun commitRecovered(bytes: ByteArray) {
        val pending = directory.read("$FILE.new")
        try { require(pending.contentEquals(bytes)) } finally { pending.fill(0) }
        directory.promote(FILE)
        val retained = directory.read(FILE)
        try { check(retained.contentEquals(bytes)) } finally { retained.fill(0) }
    }
    @Synchronized override fun replaceAndForce(bytes: ByteArray) {
        check(!directory.exists("$FILE.new")) { "Lifecycle intent write remains unresolved" }
        directory.replace(FILE, bytes)
    }
    @Synchronized override fun clearAndForce() {
        check(!directory.exists("$FILE.new"))
        directory.delete(FILE)
    }
    private companion object { const val FILE = "intent" }
}

/** Fixed desktop setup namespaces (same names as Android, under the app's private root). */
public class DesktopSetupStorages(parent: Path, suffix: String = "") {
    init { require(suffix.isEmpty() || suffix.matches(Regex("\\.[a-z0-9-]{1,24}"))) }
    public val employee: DesktopSetupIntentStorage = DesktopSetupIntentStorage(DesktopSetupDirectory(parent,
        "ukkera.employee-enrollment-setup-ui.v1$suffix", LocalEmployeeSetupIntents.MAXIMUM_BYTES, LocalEmployeeSetupIntents.MAXIMUM_FILES))
    public val deviceAdmission: DesktopSetupIntentStorage = DesktopSetupIntentStorage(DesktopSetupDirectory(parent,
        "ukkera.original-device-admission-setup-ui.v1$suffix", LocalDeviceAdmissionIntents.MAXIMUM_BYTES, LocalDeviceAdmissionIntents.MAXIMUM_FILES))
    public val freshTarget: DesktopSetupIntentStorage = DesktopSetupIntentStorage(DesktopSetupDirectory(parent,
        "ukkera.fresh-device-pairing-target-setup-ui.v1$suffix", LocalFreshDevicePairingIntents.MAXIMUM_BYTES, LocalFreshDevicePairingIntents.MAXIMUM_FILES))
    public val freshMaster: DesktopSetupIntentStorage = DesktopSetupIntentStorage(DesktopSetupDirectory(parent,
        "ukkera.fresh-device-pairing-master-setup-ui.v1$suffix", LocalFreshDevicePairingIntents.MAXIMUM_BYTES, LocalFreshDevicePairingIntents.MAXIMUM_FILES))
    public val launch: DesktopMasterLaunchStorage = DesktopMasterLaunchStorage(DesktopSetupDirectory(parent,
        "ukkera.local-master-launch.v1$suffix", LocalMasterLaunchRecord.MAXIMUM_BYTES, 2))
    public val lifecycle: DesktopMasterLifecycleIntentStorage = DesktopMasterLifecycleIntentStorage(DesktopSetupDirectory(parent,
        "ukkera.local-master-lifecycle-intent.v1$suffix", LocalMasterLifecycleIntentStore.MAXIMUM_BYTES, 2))
    public val stopBinding: DesktopMasterLifecycleIntentStorage = DesktopMasterLifecycleIntentStorage(DesktopSetupDirectory(parent,
        "ukkera.local-master-stop-binding.v1$suffix", LocalMasterLifecycleIntentStore.MAXIMUM_BYTES, 2))
}

/** JVM monotonic clock (System.nanoTime) and an independent daemon watchdog. */
public class DesktopLocalSetupExecution : LocalSetupExecution {
    override val dispatcher: CoroutineDispatcher = Dispatchers.IO
    private val pid = ProcessHandle.current().pid()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "ukkera-setup-owner-watchdog").apply { isDaemon = true }
    }
    override fun elapsedMillis(): Long { check(ProcessHandle.current().pid() == pid); return System.nanoTime() / 1_000_000 }
    override fun startWatching(tick: () -> Unit) {
        try { watchdog.scheduleWithFixedDelay(tick, 250L, 250L, TimeUnit.MILLISECONDS) }
        catch (failure: Throwable) { watchdog.shutdownNow(); throw failure }
    }
    override fun stopWatching(): Unit = watchdog.shutdown()
}
