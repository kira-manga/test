package com.ukkera.pos.security

import com.ukkera.pos.android.files.AndroidNativeFiles
import java.io.File
import java.io.FileDescriptor

/** An unreturned descriptor's uncertain cleanup still fences the complete native owner. */
internal fun androidOwnedOpenDescriptor(
    path: File,
    flags: Int,
    mode: Int,
    work: AndroidOwnedInstallationWork,
): FileDescriptor = try { AndroidNativeFiles.open(path.path, flags, mode) }
catch (failure: AndroidNativeFiles.ClosureUncertainException) { work.nativeClosureUnknown(); throw failure }

/** Bounded native enumeration under the actual owner's close/watchdog obligation. */
internal fun androidOwnedDirectoryNames(
    path: File,
    maximumNames: Int,
    work: AndroidOwnedInstallationWork,
): List<String> {
    require(maximumNames >= 0)
    work.requireActive()
    val cursor = try { AndroidNativeFiles.openDirectory(path.path) }
    catch (failure: AndroidNativeFiles.ClosureUncertainException) { work.nativeClosureUnknown(); throw failure }
    return try {
        val names = mutableListOf<String>()
        while (true) {
            work.requireActive()
            val name = cursor.nextName() ?: break
            if (names.size >= maximumNames) selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            names.add(name)
        }
        names
    } finally {
        try { cursor.close() }
        catch (failure: Throwable) { work.nativeClosureUnknown(); throw failure }
    }
}
