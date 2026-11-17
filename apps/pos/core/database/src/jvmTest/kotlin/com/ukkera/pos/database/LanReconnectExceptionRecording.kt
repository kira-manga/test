package com.ukkera.pos.database

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import jdk.jfr.Recording

/** Test-only observation BEFORE the SDK's generic exception mapping loses its cause.
 * Private recordings are not authority or a new retry/deadline. Export only exception
 * types and bounded static frames; never publish raw recordings or exception messages. */
internal suspend fun <T> recordLanConnectExceptions(root: Path, label: String, action: suspend () -> T): T {
    require(label.matches(Regex("[a-z0-9-]{1,64}\\.jfr")))
    val path = root.resolve(label)
    check(!Files.exists(path, NOFOLLOW_LINKS))
    val recording = Recording().apply {
        maxSize = 16L * 1024 * 1024
        maxAge = Duration.ofMinutes(3)
        enable("jdk.JavaExceptionThrow").withStackTrace()
        start()
    }
    var primary: Throwable? = null
    try { return action() }
    catch (failure: Throwable) { primary = failure; throw failure }
    finally {
        try {
            recording.stop()
            recording.dump(path)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        } catch (diagnosticFailure: Throwable) {
            if (primary == null) throw diagnosticFailure
            primary.addSuppressed(diagnosticFailure)
        } finally { recording.close() }
    }
}
