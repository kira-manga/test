package com.ukkera.pos.security

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.UUID

/** Stable public volume identities; never derive a UUID from a transient device number. */
public object AndroidNativeStorageVolume {
    public fun uuidForPath(context: Context, path: File): UUID {
        if (Build.VERSION.SDK_INT >= 26) {
            val storage = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
                ?: throw IllegalStateException("Android storage observation unavailable")
            return storage.getUuidForPath(path)
        }
        if (Build.VERSION.SDK_INT < 24) throw UnsupportedOperationException("Android storage requires API 24 or later")

        // API 24/25 does not expose a private adopted volume's UUID. Only prove the public
        // default data volume here. The value is the documented StorageManager.UUID_DEFAULT,
        // preserving the exact identity returned by getUuidForPath on a later supported OS.
        val data = Environment.getDataDirectory().absoluteFile
        val canonicalData = data.canonicalFile
        val canonicalPath = path.canonicalFile
        if (canonicalData != data || !canonicalPath.path.startsWith(data.path + "/")) {
            throw UnsupportedOperationException("Stable private adopted-storage evidence requires Android API 26 or later")
        }
        val root = Os.lstat(data.path)
        if (!OsConstants.S_ISDIR(root.st_mode)) throw IllegalStateException("Android default data volume unavailable")
        var component = data
        for (name in canonicalPath.path.removePrefix(data.path + "/").split('/')) {
            if (name.isEmpty() || name == "." || name == "..") throw IllegalStateException("Android storage ancestry unavailable")
            component = File(component, name)
            val observed = try { Os.lstat(component.path) } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.ENOENT) throw failure
                // Like getUuidForPath, a not-yet-created child inherits the actually observed
                // parent volume. This creates nothing. The concrete namespace owner must
                // separately pin and revalidate the real descendant after authorized creation.
                break
            }
            if (observed.st_dev != root.st_dev ||
                (!OsConstants.S_ISDIR(observed.st_mode) && !OsConstants.S_ISREG(observed.st_mode))) {
                throw UnsupportedOperationException("Stable private storage-volume evidence unavailable on this Android version")
            }
        }
        return LEGACY_DEFAULT_UUID
    }

    private val LEGACY_DEFAULT_UUID: UUID = UUID.fromString("41217664-9172-527a-b3d5-edabb50a7d69")
}
