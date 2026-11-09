package com.ukkera.pos.android.independent.androidapi24v1

import android.os.Build
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ukkera.pos.android.files.AndroidNativeFiles
import java.io.File
import java.io.FileDescriptor
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * AC24-N18/N19/N20 ordinary native adapter slices only. Actual access, identities,
 * same-UID final symlink refusal, close and empty/three-name streaming are bound.
 * Atomic-creation/exec inheritance, cross-process lock, wrong UID, close faults,
 * budget-3 production consumer/grammar, fourth/large/invalid-name cases are NOT RUN
 * (B12-B14). A test-local accumulator is not a production enumeration-budget port.
 */
@RunWith(AndroidJUnit4::class)
class AndroidApi24V1NativeIoSlicesTest {
    @Test
    fun n18_existingReadWriteAndReadOnlyNativeDescriptorsRetainActualIdentityAndContents() = fixture { io ->
        val file = io.create("synthetic-io-01", AndroidApi24V1NativeInputs.originalContent)
        val original = Os.lstat(file.path)
        io.descriptor(file, OsConstants.O_RDWR) { fd ->
            assertEquals(OsConstants.O_RDWR, AndroidNativeFiles.statusFlags(fd) and OsConstants.O_ACCMODE)
            assertTrue("AC24-N18 observed close-on-exec; atomic creation remains separately unbound", AndroidNativeFiles.closeOnExec(fd))
            io.independentFlagsWhenAvailable(fd, OsConstants.O_RDWR)
            val actual = Os.fstat(fd)
            assertEquals(original.st_dev, actual.st_dev)
            assertEquals(original.st_ino, actual.st_ino)
            assertEquals(Process.myUid(), actual.st_uid)
            assertTrue(OsConstants.S_ISREG(actual.st_mode))
            assertEquals(AndroidApi24V1NativeInputs.originalContent, io.read(fd))
        }
        io.descriptor(file, OsConstants.O_RDONLY) { fd ->
            assertEquals(OsConstants.O_RDONLY, AndroidNativeFiles.statusFlags(fd) and OsConstants.O_ACCMODE)
            assertTrue(AndroidNativeFiles.closeOnExec(fd))
            io.independentFlagsWhenAvailable(fd, OsConstants.O_RDONLY)
            val attempted = byteArrayOf(1)
            try { refused { Os.write(fd, attempted, 0, attempted.size) } }
            finally { attempted.fill(0) }
            assertEquals(AndroidApi24V1NativeInputs.originalContent, io.read(fd))
            assertEquals(original.st_dev, Os.fstat(fd).st_dev)
            assertEquals(original.st_ino, Os.fstat(fd).st_ino)
        }
        assertEquals(original.st_dev, Os.lstat(file.path).st_dev)
        assertEquals(original.st_ino, Os.lstat(file.path).st_ino)
    }

    @Test
    fun n19_sameUidFinalSymlinkOpenAndCreateRefuseWithoutChangingTarget() = fixture { io ->
        val target = io.create("synthetic-io-01", AndroidApi24V1NativeInputs.symlinkTargetContent)
        val original = Os.lstat(target.path)
        val link = File(io.root, "synthetic-final-symlink")
        io.symlink(target, link)
        for (flags in listOf(OsConstants.O_RDONLY, OsConstants.O_RDWR or OsConstants.O_TRUNC,
            OsConstants.O_WRONLY or OsConstants.O_CREAT)) {
            refused { io.descriptor(link, flags) { } }
        }
        val directoryLink = File(io.root, "synthetic-directory-symlink")
        io.symlink(io.root, directoryLink)
        refused { io.directory(directoryLink) { it.nextName() } }
        io.descriptor(target, OsConstants.O_RDONLY) { fd ->
            assertEquals(AndroidApi24V1NativeInputs.symlinkTargetContent, io.read(fd))
            assertEquals(original.st_dev, Os.fstat(fd).st_dev)
            assertEquals(original.st_ino, Os.fstat(fd).st_ino)
        }
        assertTrue(OsConstants.S_ISLNK(Os.lstat(link.path).st_mode))
    }

    @Test
    fun n20_nativeStreamCompletesEmptyAndThreeFrozenNamesWithoutOrderAssumption() = fixture { io ->
        io.directory(io.root) { assertEquals(null, it.nextName()) }
        AndroidApi24V1NativeInputs.names.forEach { io.create(it, AndroidApi24V1NativeInputs.originalContent) }
        io.directory(io.root) { cursor ->
            val observed = mutableSetOf<String>()
            while (true) {
                val name = cursor.nextName() ?: break
                assertTrue("AC24-N20 exact finite fixture names, not a production budget claim", name in AndroidApi24V1NativeInputs.names)
                assertTrue(observed.add(name))
            }
            assertEquals(AndroidApi24V1NativeInputs.names, observed)
        }
        Log.i("AndroidApi24V1", "N20_ADAPTER_STREAM_ONLY 0/3 complete; B13/B14 production budget/grammar/fault variants NOT_RUN")
    }

    private fun refused(body: () -> Unit) {
        val failure = try { body(); null } catch (failure: Exception) { failure }
        assertTrue("AC24 native refusal must be explicit, not a successful mutation", failure is ErrnoException || failure is java.io.IOException)
    }

    private fun fixture(body: (SyntheticIo) -> Unit) {
        val app = AndroidApi24V1NativeInputs.application()
        val io = SyntheticIo(File(app.noBackupFilesDir, "synthetic-android-api24-io-${UUID.randomUUID()}"))
        Os.mkdir(io.root.path, 448)
        val stat = Os.lstat(io.root.path)
        assertEquals(Process.myUid(), stat.st_uid)
        assertEquals(448, stat.st_mode and 511)
        // Preserve any failed or closure-uncertain disposable fixture for inspection.
        body(io)
        check(!io.closureUncertain)
        io.cleanupKnownEntries()
    }

    private class SyntheticIo(val root: File) {
        private val entries = mutableListOf<File>()
        var closureUncertain = false
            private set
        fun create(name: String, content: String): File {
            val file = File(root, name)
            val bytes = content.encodeToByteArray()
            try { descriptor(file, OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL) { fd ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = Os.write(fd, bytes, offset, bytes.size - offset)
                    check(count > 0); offset += count
                }
                Os.fsync(fd)
            } } finally { bytes.fill(0) }
            entries.add(file)
            return file
        }

        fun symlink(target: File, link: File) {
            Os.symlink(target.path, link.path)
            entries.add(link)
        }

        fun cleanupKnownEntries() {
            check(!closureUncertain)
            // Public API21 Os.remove calls native remove(3): a final symlink is
            // removed itself, not traversed. Remove only registered entries; the
            // final empty-directory removal refuses unexpected retained entries.
            entries.asReversed().forEach { Os.remove(it.path) }
            Os.remove(root.path)
        }

        fun read(fd: FileDescriptor): String {
            val bytes = ByteArray(256)
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val count = Os.read(fd, bytes, offset, bytes.size - offset)
                    if (count == 0) {
                        val result = bytes.copyOfRange(0, offset)
                        try { return result.decodeToString() } finally { result.fill(0) }
                    }
                    check(count > 0); offset += count
                }
                error("Synthetic marker read did not complete within the harness safety bound")
            } finally { bytes.fill(0) }
        }

        fun <T> descriptor(file: File, flags: Int, body: (FileDescriptor) -> T): T {
            val fd = try { AndroidNativeFiles.open(file.path, flags, 384) }
                catch (failure: AndroidNativeFiles.ClosureUncertainException) { closureUncertain = true; throw failure }
            var primary: Throwable? = null
            try { return body(fd) }
            catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                try { AndroidNativeFiles.close(fd) }
                catch (failure: Throwable) {
                    closureUncertain = true
                    if (primary == null) throw failure else primary.addSuppressed(failure)
                }
            }
        }

        fun <T> directory(file: File, body: (AndroidNativeFiles.DirectoryCursor) -> T): T {
            val cursor = try { AndroidNativeFiles.openDirectory(file.path) }
                catch (failure: AndroidNativeFiles.ClosureUncertainException) { closureUncertain = true; throw failure }
            var primary: Throwable? = null
            try { return body(cursor) }
            catch (failure: Throwable) { primary = failure; throw failure }
            finally {
                try { cursor.close() }
                catch (failure: Throwable) {
                    closureUncertain = true
                    if (primary == null) throw failure else primary.addSuppressed(failure)
                }
            }
        }

        fun independentFlagsWhenAvailable(fd: FileDescriptor, access: Int) {
            // Public Os.fcntlInt is API30, not API21. Never reflect or invent flags on API24.
            if (Build.VERSION.SDK_INT >= 30) {
                assertEquals(access, Os.fcntlInt(fd, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE)
                assertTrue(Os.fcntlInt(fd, OsConstants.F_GETFD, 0) and OsConstants.FD_CLOEXEC != 0)
            } else Log.i("AndroidApi24V1", "N18_API24 actual read/write + adapter flags; B12 independent fcntl/atomic-exec witness NOT_RUN")
        }
    }
}
