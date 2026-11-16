package com.ukkera.pos.security

import java.nio.file.Path
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Genuine packaged-user directory custody for distributed physical journals. The namespace
 * retains this object BEFORE open/create. Every path is below the running package's actual
 * LocalState; it is never a Master service SID or an inherited-DACL assertion. A failed close
 * remains terminal and no handle is retried, replaced, or declared certainly absent.
 */
public class WindowsPrivateClientPhysicalDirectory private constructor(
    private val expected: SelfOwnedWindowsPackagedAppIdentityV2,
    public val path: Path,
) {
    private var native: WindowsOriginalHandle? = null
    private var pathname: WindowsOriginalHandle? = null
    private var unknownOpen = false
    private var cursor: DirectoryStream<Path>? = null
    private var unknownCursor = false
    private var cursorCloseUncertain = false
    private var unknownCreate = false
    private var started = false
    private var closing = false
    private var stamp: DesktopStamp? = null
    private var result: Boolean? = null

    /** One explicit attempt. Caller retained this descriptor slot before invoking it. */
    @Synchronized public fun open(enroll: Boolean) {
        check(!started && !closing)
        started = true
        val sid = currentSid(requireExisting = !enroll)
        unknownOpen = true
        val held = if (enroll) WindowsOriginalNative.createPackagedUserDirectory(path.toString(), sid)
            else WindowsOriginalNative.desktopOpen(path.toString(), write = false, directory = true)
                ?: error("physical.windows.directory-unavailable")
        native = held // Own actual return before any metadata/DACL/pathname operation.
        unknownOpen = false
        val observed = WindowsOriginalNative.desktopStamp(held.pointer, sid, sid)
        check(observed.kind == DesktopEntryKind.DIRECTORY && observed.owner == sid && observed.ownerOnly)
        stamp = observed
        requireCurrent()
    }

    /** Native handle + pathname identity + exact one-ACE protected owner-only DACL, every cut. */
    @Synchronized public fun requireCurrent() {
        check(started && !closing && !unknownOpen && pathname == null)
        val sid = currentSid()
        val expectedStamp = checkNotNull(stamp)
        val held = checkNotNull(native)
        val current = WindowsOriginalNative.desktopStamp(held.pointer, sid, sid)
        check(expectedStamp.sameDirectory(current) && current.ownerOnly && current.owner == sid)
        unknownOpen = true
        val named = WindowsOriginalNative.desktopOpen(path.toString(), write = false, directory = true)
            ?: error("physical.windows.directory-unavailable")
        pathname = named // Retain transient native owner before DACL/identity checks/close.
        unknownOpen = false
        val pathStamp = WindowsOriginalNative.desktopStamp(named.pointer, sid, sid)
        check(pathStamp.sameDirectory(current) && pathStamp.ownerOnly && pathStamp.owner == sid)
        named.closeConfirmed()
        pathname = null // Only after actual known close.
        check(currentSid() == sid)
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
        for (handle in listOfNotNull(pathname, native)) {
            try { handle.closeConfirmed() } catch (_: Throwable) { complete = false }
        }
        return complete.also { result = it }
    }

    private fun currentSid(requireExisting: Boolean = true): String {
        check(!Thread.currentThread().isInterrupted)
        val local = System.getenv("LOCALAPPDATA") ?: error("physical.windows.local-state")
        val parent = Paths.get(local, "Packages", expected.packageFamilyName, "LocalState").toAbsolutePath().normalize()
        check(path.isAbsolute && path.normalize() == path && path != parent && path.startsWith(parent) &&
            (if (requireExisting) path.toRealPath() == path else path.parent.toRealPath() == path.parent))
        return WindowsDesktopFiles.currentUserSid(expected)
    }

    override fun toString(): String = "WindowsPrivateClientPhysicalDirectory([REDACTED])"

    public companion object {
        /** Reservation only; no mkdir/open/key operation occurs before the caller retains it. */
        public fun reserve(expected: SelfOwnedWindowsPackagedAppIdentityV2,
            path: Path): WindowsPrivateClientPhysicalDirectory = WindowsPrivateClientPhysicalDirectory(expected, path)
    }
}
