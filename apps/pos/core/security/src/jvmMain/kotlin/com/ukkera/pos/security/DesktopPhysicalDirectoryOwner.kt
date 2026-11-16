package com.ukkera.pos.security

import java.nio.file.Path
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Exact native directory owner for distributed physical namespaces of an actual Master service
 * or signed macOS Client. Constructed without IO and retained by its namespace before open.
 * Currentness is actual installed-process/OS file custody, independent of employee logout.
 */
public class DesktopPhysicalDirectoryOwner private constructor(
    private val source: Source,
    public val path: Path,
) {
    private sealed class Source {
        abstract val base: Path
        abstract fun files(): DesktopFiles
        abstract fun requireCurrent()
        class Master(val custody: DesktopMasterClientJournalCustody) : Source() {
            override val base: Path get() = custody.parentDirectory
            override fun files(): DesktopFiles = custody.physicalDirectoryFiles()
            override fun requireCurrent() = custody.requireCurrent()
        }
        class MacClient(val expected: SelfOwnedMacUiIdentityV2) : Source() {
            override val base: Path = Paths.get(checkNotNull(System.getProperty("user.home")),
                "Library", "Application Support", MacDesktopFiles.BUNDLE_ID).toAbsolutePath().normalize()
            override fun files(): DesktopFiles = MacMasterFiles(base.toString())
            override fun requireCurrent() {
                check(MacInstalledApplicationIdentity.captureUi(expected) is SelfOwnedInstalledIdentityResultV2.Data)
            }
        }
        class PairedClient(val custody: DesktopPairedDevicePhysicalJournalCustody) : Source() {
            override val base: Path get() = custody.parentDirectory
            override fun files(): DesktopFiles = custody.physicalDirectoryFiles()
            override fun requireCurrent() = custody.requireCurrent()
        }
    }

    private var native: DesktopHandle? = null
    private var pathname: DesktopHandle? = null
    private var unknownOpen = false
    private var cursor: DirectoryStream<Path>? = null
    private var unknownCursor = false
    private var cursorCloseUncertain = false
    private var unknownCreate = false
    private var started = false
    private var closing = false
    private var stamp: DesktopStamp? = null
    private var result: Boolean? = null

    @Synchronized public fun open(enroll: Boolean) {
        check(!started && !closing)
        started = true
        val files = currentFiles(requireExisting = !enroll)
        if (enroll) {
            // Some native implementations create and close a temporary directory handle.
            // A thrown/unknown create must remain in this retained owner, never be retried.
            unknownCreate = true
            files.createDirectory(path.toString())
            unknownCreate = false
        }
        unknownOpen = true
        val opened = files.openDirectory(path.toString())
        native = opened
        unknownOpen = false
        val observed = opened.stamp()
        check(observed.kind == DesktopEntryKind.DIRECTORY && observed.owner == files.ownerName && observed.ownerOnly)
        stamp = observed
        requireCurrent()
    }

    @Synchronized public fun requireCurrent() {
        check(started && !closing && !unknownOpen && pathname == null)
        val files = currentFiles()
        val expected = checkNotNull(stamp)
        val observed = checkNotNull(native).stamp()
        check(expected.sameDirectory(observed) && observed.ownerOnly && observed.owner == files.ownerName)
        unknownOpen = true
        val named = files.openDirectory(path.toString())
        pathname = named
        unknownOpen = false
        val namedStamp = named.stamp()
        check(namedStamp.sameDirectory(observed) && namedStamp.ownerOnly && namedStamp.owner == files.ownerName)
        named.close()
        pathname = null
        source.requireCurrent()
    }

    @Synchronized public fun force() {
        requireCurrent()
        checkNotNull(native).sync()
        requireCurrent()
    }

    /** Own the actual enumeration cursor before iterating or closing it. No hidden use/close. */
    @Synchronized public fun names(): Set<String> {
        requireCurrent()
        check(cursor == null && !unknownCursor && !cursorCloseUncertain)
        unknownCursor = true
        val opened = Files.newDirectoryStream(path)
        cursor = opened
        unknownCursor = false
        try {
            val names = mutableSetOf<String>()
            for (entry in opened) check(names.size < 512 && names.add(entry.fileName.toString()))
            closeCursor()
            requireCurrent()
            return names
        } catch (failure: Throwable) {
            if (!cursorCloseUncertain) try { closeCursor() } catch (_: Throwable) { /* Retained unknown. */ }
            throw failure
        }
    }

    private fun closeCursor() {
        val selected = cursor ?: return
        check(!cursorCloseUncertain)
        // Mark before the single close; a throwing close is never retried.
        cursorCloseUncertain = true
        selected.close()
        cursor = null
        cursorCloseUncertain = false
    }

    @Synchronized public fun closeConfirmed(): Boolean {
        result?.let { return it }
        closing = true
        var complete = !unknownOpen && !unknownCursor && !unknownCreate && !cursorCloseUncertain
        if (!cursorCloseUncertain) try { closeCursor() } catch (_: Throwable) { complete = false }
        for (held in listOfNotNull(pathname, native)) try { held.close() } catch (_: Throwable) { complete = false }
        return complete.also { result = it }
    }

    private fun currentFiles(requireExisting: Boolean = true): DesktopFiles {
        check(!Thread.currentThread().isInterrupted)
        source.requireCurrent()
        val base = source.base
        check(path.isAbsolute && path.normalize() == path && path.startsWith(base) && path != base &&
            (if (requireExisting) path.toRealPath() == path else path.parent.toRealPath() == path.parent))
        return source.files()
    }

    override fun toString(): String = "DesktopPhysicalDirectoryOwner([REDACTED])"

    public companion object {
        public fun reserveMaster(custody: DesktopMasterClientJournalCustody,
            path: Path): DesktopPhysicalDirectoryOwner = DesktopPhysicalDirectoryOwner(Source.Master(custody), path)
        public fun reserveMacClient(expected: SelfOwnedMacUiIdentityV2,
            path: Path): DesktopPhysicalDirectoryOwner = DesktopPhysicalDirectoryOwner(Source.MacClient(expected), path)
        public fun reservePairedClient(custody: DesktopPairedDevicePhysicalJournalCustody,
            path: Path): DesktopPhysicalDirectoryOwner = DesktopPhysicalDirectoryOwner(Source.PairedClient(custody), path)
    }
}
