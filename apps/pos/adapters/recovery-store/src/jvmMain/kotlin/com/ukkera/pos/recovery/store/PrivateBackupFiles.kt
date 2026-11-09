package com.ukkera.pos.recovery.store

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal const val TRANSFER_BYTES = 256 * 1024
internal const val MAX_DIRECTORY_ENTRIES = 4096
internal val PRIVATE_DIRECTORY = PosixFilePermissions.fromString("rwx------")
internal val PRIVATE_FILE = PosixFilePermissions.fromString("rw-------")


/** Only POSIX owner-private, no-symlink paths are supported by this host candidate. */
internal fun privateDirectory(path: Path, create: Boolean = false): Path {
    val normalized = path.toAbsolutePath().normalize()
    if (path != normalized || normalized.parent == null) fail(LocalBackupFailure.PRIVATE_FILESYSTEM_REQUIRED)
    var part = normalized.root
    for (segment in normalized) {
        part = part.resolve(segment)
        if (Files.isSymbolicLink(part)) fail(LocalBackupFailure.PRIVATE_FILESYSTEM_REQUIRED)
    }
    if (!Files.exists(normalized, NOFOLLOW_LINKS) && create) {
        if (!Files.isDirectory(normalized.parent, NOFOLLOW_LINKS)) fail(LocalBackupFailure.PRIVATE_FILESYSTEM_REQUIRED)
        Files.createDirectory(normalized, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY))
        forceDirectory(normalized.parent)
    }
    val basic = Files.readAttributes(normalized, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (!basic.isDirectory) fail(LocalBackupFailure.PRIVATE_FILESYSTEM_REQUIRED)
    val view = Files.getFileAttributeView(normalized, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        ?: fail(LocalBackupFailure.PRIVATE_FILESYSTEM_REQUIRED)
    if (view.readAttributes().permissions().any { it !in PRIVATE_DIRECTORY }) fail(LocalBackupFailure.PRIVATE_FILESYSTEM_REQUIRED)
    return normalized
}

internal fun requireRegular(path: Path): BasicFileAttributes {
    val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    if (!attributes.isRegularFile || attributes.isSymbolicLink) fail(LocalBackupFailure.PRIVATE_FILESYSTEM_REQUIRED)
    return attributes
}

/** Unknown metadata is never permission to skip retained evidence or SQLite sidecars. */
internal fun evidencePathExists(path: Path): Boolean = try {
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    true
} catch (_: NoSuchFileException) { false }

internal fun privateNewFile(path: Path): FileChannel = FileChannel.open(
    path, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PRIVATE_FILE),
)

internal fun inputFile(path: Path): InputStream {
    requireRegular(path)
    return Channels.newInputStream(FileChannel.open(path, setOf(READ, NOFOLLOW_LINKS)))
}

internal fun forceDirectory(path: Path) {
    FileChannel.open(path, READ).use { it.force(true) }
}

internal fun forceFile(path: Path) {
    requireRegular(path)
    FileChannel.open(path, setOf(WRITE, NOFOLLOW_LINKS)).use { it.force(true) }
}

internal fun writeFully(channel: FileChannel, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size) {
    val buffer = ByteBuffer.wrap(bytes, offset, length)
    while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
}

internal fun writePrivateDurable(path: Path, bytes: ByteArray) {
    privateNewFile(path).use { writeFully(it, bytes); it.force(true) }
    forceDirectory(path.parent)
}

internal fun atomicPublish(source: Path, target: Path, replace: Boolean = false) {
    requireRegular(source)
    if (Files.isSymbolicLink(target)) fail(LocalBackupFailure.PRIVATE_FILESYSTEM_REQUIRED)
    if (!replace && evidencePathExists(target)) fail(LocalBackupFailure.STORAGE_UNAVAILABLE)
    // A successful absence check does not make ATOMIC_MOVE an atomic no-replace operation.
    // Qualified exclusive directory/path custody is still required across this publication.
    if (replace) Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    else Files.move(source, target, ATOMIC_MOVE)
    forceDirectory(target.parent)
}

internal suspend fun fileSha256(path: Path, maximum: Long): String {
    val digest = MessageDigest.getInstance("SHA-256")
    var size = 0L
    val buffer = ByteArray(TRANSFER_BYTES)
    try {
        inputFile(path).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                size += count
                if (size > maximum) fail(LocalBackupFailure.CAPACITY_EXCEEDED)
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    } finally { buffer.fill(0) }
}

internal suspend fun copyPrivateFile(source: Path, target: Path, maximum: Long): Pair<Long, String> {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(TRANSFER_BYTES)
    var size = 0L
    try {
        inputFile(source).use { input ->
            privateNewFile(target).use { output ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    size += count
                    if (size > maximum) fail(LocalBackupFailure.CAPACITY_EXCEEDED)
                    digest.update(buffer, 0, count)
                    writeFully(output, buffer, 0, count)
                }
                output.force(true)
            }
        }
        return size to digest.digest().hex()
    } finally { buffer.fill(0) }
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun readBounded(path: Path, maximum: Int): ByteArray {
    val size = requireRegular(path).size()
    if (size < 0 || size > maximum) fail(LocalBackupFailure.CAPACITY_EXCEEDED)
    return inputFile(path).use { input ->
        val result = input.readNBytes(maximum + 1)
        if (result.size != size.toInt() || result.size > maximum) fail(LocalBackupFailure.STORAGE_UNAVAILABLE)
        result
    }
}

internal fun listBounded(directory: Path): List<Path> = Files.newDirectoryStream(directory).use { entries ->
    buildList {
        for (entry in entries) {
            if (size >= MAX_DIRECTORY_ENTRIES) fail(LocalBackupFailure.CAPACITY_EXCEEDED)
            add(entry)
        }
    }
}

/** Only an invocation-owned flat staging directory is removed; never follow links or recurse. */
internal fun removeStaging(directory: Path) {
    if (!Files.exists(directory, NOFOLLOW_LINKS)) return
    privateDirectory(directory)
    for (file in listBounded(directory)) {
        if (Files.isDirectory(file, NOFOLLOW_LINKS)) fail(LocalBackupFailure.STORAGE_UNAVAILABLE)
        Files.delete(file)
    }
    Files.delete(directory)
    forceDirectory(directory.parent)
}

internal fun assertNoSqliteSidecars(database: Path) {
    for (suffix in listOf("-wal", "-shm", "-journal")) {
        val sidecar = database.resolveSibling(database.fileName.toString() + suffix)
        if (evidencePathExists(sidecar)) {
            val attributes = requireRegular(sidecar)
            if (suffix != "-journal" || attributes.size() != 0L) fail(LocalBackupFailure.UNSUPPORTED_DATABASE)
        }
    }
}
