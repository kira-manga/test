package com.ukkera.pos.security

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Actual same-app local provisioner acquisition. Opens no branch database, generates no keys
 * on open, and accepts no supplied root, signer, clock or custody verifier. API24/nondebuggable
 * supports genuine public native evidence; an exact StrongBox request additionally needs API31.
 */
public object AndroidSelfOwnedEnrollmentSource {
    /** Explicit preparation acquisition; may create the original empty P1 namespace. */
    public suspend fun open(application: Application): LocalProvisionerOpenResult =
        openNativeOwner(application, existingOriginal = false, profile = SelfOwnedNativeProfileV1.LIVE)

    /**
     * Existing original P1 only. Missing ancestry, directory, lock or bootstrap is unresolved,
     * never authorization to create a replacement root. Every later environment observation
     * remains in the noncreating P3 original profile. Successful open creates no keys/IDs.
     * Resume/issuance still needs its own explicit operation on the returned original owner.
     */
    public suspend fun openOriginal(application: Application): LocalProvisionerOpenResult =
        openNativeOwner(application, existingOriginal = true, profile = SelfOwnedNativeProfileV1.LIVE)

    /** Explicit training-slot preparation; separate root and key namespace from live. */
    public suspend fun openTraining(application: Application,
        slot: LocalTrainingCompositionSlot): LocalProvisionerOpenResult =
        openNativeOwner(application, existingOriginal = false, profile = SelfOwnedNativeProfileV1.training(slot))

    /** Existing-only training-slot original; never repairs or creates a missing training root. */
    public suspend fun openTrainingOriginal(application: Application,
        slot: LocalTrainingCompositionSlot): LocalProvisionerOpenResult =
        openNativeOwner(application, existingOriginal = true, profile = SelfOwnedNativeProfileV1.training(slot))

    private suspend fun openNativeOwner(application: Application, existingOriginal: Boolean,
        profile: SelfOwnedNativeProfileV1): LocalProvisionerOpenResult {
        val lease = AndroidProvisionerProcessLease.acquire(profile)
            ?: return LocalProvisionerOpenResult.Unavailable(SelfOwnedInstallationFailureV1.BUSY)
        val work = try { AndroidOwnedInstallationWork(lease) } catch (_: Throwable) {
            // No native namespace was entered and no registry descriptor could have been opened.
            lease.releaseUnusedBeforeNativeOpen()
            return LocalProvisionerOpenResult.Unavailable(SelfOwnedInstallationFailureV1.UNAVAILABLE)
        }
        val retainedRegistry = AtomicReference<AndroidLocalProvisionerRegistry?>(null)
        var delivered = false
        try {
            return when (val result = work.run {
                object : AndroidOwnedTask<AndroidLocalProvisioner> {
                    override fun execute(): AndroidLocalProvisioner {
                        val environment = if (existingOriginal)
                            AndroidSelfOwnedEnvironment.captureExistingOriginal(application, work, profile)
                        else AndroidSelfOwnedEnvironment.capture(application, work, profile)
                        // Publish ownership BEFORE any open can partially acquire descriptors.
                        val registry = AndroidLocalProvisionerRegistry(environment, work)
                        retainedRegistry.set(registry)
                        if (existingOriginal) registry.openExistingNative() else registry.openNative()
                        val keys = AndroidLocalProvisionerKeys(environment, registry, work)
                        return AndroidLocalProvisioner(environment, registry, keys, work).also { it.openExisting() }
                    }
                    override fun discard(value: AndroidLocalProvisioner) { value.closeNative() }
                    override fun clearInputs() = Unit
                }
            }) {
                is AndroidOwnedResult.Value -> {
                    val opened = LocalProvisionerOpenResult.Opened(result.value)
                    delivered = true
                    opened
                }
                is AndroidOwnedResult.Unavailable -> LocalProvisionerOpenResult.Unavailable(result.reason)
            }
        } finally {
            if (!delivered) {
                // The worker waits for actual execute/discard and WATCHES actual close itself.
                // This does not wait on/cancel with the departed caller and does not free the lease.
                work.requestClose { retainedRegistry.get()?.closeNative() }
            }
        }
    }
}

/**
 * Process exclusion is additional to the retained cross-process kernel registry lock. One
 * lease exists per exact native root pair, so an independent LIVE/TRAINING/other-slot root is
 * not blocked merely because another composition is open in this process. The same original's
 * root pair still admits at most one owner, and release remains tied to its actual close.
 */
internal class AndroidProvisionerProcessLease private constructor(
    private val root: RootKey,
) {
    private var released = false

    fun releaseAfterActualClose() { release() }
    fun releaseUnusedBeforeNativeOpen() { release() }

    private fun release() = synchronized(gate) {
        check(!released && current[root] === this)
        released = true
        current.remove(root)
    }

    private data class RootKey(
        val targetDirectoryName: String,
        val provisionerDirectoryName: String,
    )

    companion object {
        private val gate = Any()
        private val current = mutableMapOf<RootKey, AndroidProvisionerProcessLease>()

        /** The lease follows the exact fixed native roots, never a caller-selected path. */
        fun acquire(profile: SelfOwnedNativeProfileV1): AndroidProvisionerProcessLease? =
            acquire(RootKey(profile.targetDirectoryName, profile.provisionerDirectoryName))

        /** Existing LIVE target callers cannot select a training root through this entry. */
        fun acquire(): AndroidProvisionerProcessLease? = acquire(SelfOwnedNativeProfileV1.LIVE)

        private fun acquire(root: RootKey): AndroidProvisionerProcessLease? = synchronized(gate) {
            if (current.containsKey(root)) null else AndroidProvisionerProcessLease(root).also {
                current[root] = it
            }
        }
    }
}

/** Concrete OS observations, never a caller-provided capability or hardware attestation. */
internal class AndroidSelfOwnedEnvironment private constructor(
    private val application: Application,
    private val captured: Observation,
    private val work: AndroidOwnedInstallationWork,
    private val observationKind: DirectoryObservation,
    val profile: SelfOwnedNativeProfileV1,
) {
    private enum class DirectoryObservation { PREPARATION, EXISTING_ORIGINAL }

    val target: SelfOwnedTargetDescriptorV1 get() = captured.target
    val bootCount: Int get() = captured.boot
    val uid: Int get() = captured.target.applicationUid
    val noBackupDirectory: File get() = File(captured.noBackupPath)
    val dataDirectory: File get() = File(captured.dataPath)

    /** Reject a creating observation profile BEFORE any re-observation for an original reader. */
    fun requireExistingOriginalObservation() {
        work.requireActive()
        if (observationKind != DirectoryObservation.EXISTING_ORIGINAL) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
        requireCurrent()
    }

    /** Actual fixed provisioner volume; no caller-selected path or volume identity. */
    fun currentProvisionerVolumeUuid(): String {
        requireCurrent()
        val volume = AndroidNativeStorageVolume.uuidForPath(application,
            File(noBackupDirectory, profile.provisionerDirectoryName)).toString()
        requireCurrent()
        return volume
    }

    fun requireCurrent() {
        work.requireActive()
        val actual = observe(application, observationKind)
        if (!actual.target.same(captured.target) || actual.boot != captured.boot ||
            actual.dataPath != captured.dataPath || actual.noBackupPath != captured.noBackupPath ||
            actual.sourcePath != captured.sourcePath || actual.version != captured.version ||
            actual.updatedAt != captured.updatedAt || actual.existingDirectories != captured.existingDirectories) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
        }
        work.requireActive()
    }

    private data class ExistingDirectories(
        val dataDevice: Long, val dataInode: Long, val noBackupDevice: Long,
        val noBackupInode: Long, val ownerUid: Int,
    )

    private class Observation(
        val target: SelfOwnedTargetDescriptorV1, val boot: Int, val dataPath: String,
        val noBackupPath: String, val sourcePath: String, val version: Long, val updatedAt: Long,
        val existingDirectories: ExistingDirectories?,
    )

    companion object {
        fun capture(application: Application, work: AndroidOwnedInstallationWork,
            profile: SelfOwnedNativeProfileV1): AndroidSelfOwnedEnvironment {
            work.requireActive()
            return AndroidSelfOwnedEnvironment(application, observe(application, DirectoryObservation.PREPARATION),
                work, DirectoryObservation.PREPARATION, profile).also { it.requireCurrent() }
        }

        /**
         * Existing original only. This mode is retained for EVERY later observation, including
         * volume/key/namespace checks. Missing ancestry is unresolved; never call a creating
         * framework directory getter, search for a replacement root, or repair the directory.
         */
        fun captureExistingOriginal(application: Application,
            work: AndroidOwnedInstallationWork,
            profile: SelfOwnedNativeProfileV1): AndroidSelfOwnedEnvironment {
            work.requireActive()
            return AndroidSelfOwnedEnvironment(application,
                observe(application, DirectoryObservation.EXISTING_ORIGINAL), work,
                DirectoryObservation.EXISTING_ORIGINAL, profile).also { it.requireCurrent() }
        }

        private fun existingDirectories(data: File, noBackup: File, uid: Int): ExistingDirectories {
            val parent = try { Os.lstat(data.path) } catch (_: ErrnoException) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            val child = try { Os.lstat(noBackup.path) } catch (failure: ErrnoException) {
                // Report only a fixed native stage and errno, never paths or exception text.
                // Diagnostics cannot authorize repair or change the original refusal.
                try {
                    Log.w("UkkeraEnrollment", "Existing ancestry unavailable: stage=NO_BACKUP_LSTAT errno=${failure.errno}")
                } catch (_: Throwable) {
                    // A logging failure must not replace the fail-closed outcome.
                }
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            if (!OsConstants.S_ISDIR(parent.st_mode) || !OsConstants.S_ISDIR(child.st_mode) ||
                parent.st_uid != uid || child.st_uid != uid || parent.st_ino <= 0 || child.st_ino <= 0 ||
                parent.st_mode and 2 != 0 || child.st_mode and 2 != 0) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            }
            // The concrete namespace owner still performs its no-follow descriptor/chain
            // checks. These lstat observations are not a loan, custody proof or generic opener.
            return ExistingDirectories(parent.st_dev, parent.st_ino, child.st_dev, child.st_ino, uid)
        }

        @Suppress("DEPRECATION")
        private fun observe(application: Application, kind: DirectoryObservation): Observation {
            if (Build.VERSION.SDK_INT < SelfOwnedInstallationProfileV1.MINIMUM_ANDROID_API) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
            }
            if (application.applicationContext !== application || application.isDeviceProtectedStorage) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
            }
            val user = application.getSystemService(Context.USER_SERVICE) as? UserManager
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            if (!user.isUserUnlocked) selfOwnedFail(SelfOwnedInstallationFailureV1.USER_LOCKED)
            val uid = Process.myUid()
            val name = application.packageName
            val pm = application.packageManager
            val installed = AndroidNativeApplicationMetadata.installedPackageInfo(pm, name)
            val app = installed.applicationInfo ?: selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
            if (uid < 0 || app.uid != uid || application.applicationInfo.uid != uid || installed.packageName != name ||
                app.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 ||
                application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 ||
                pm.getPackagesForUid(uid)?.toSet() != setOf(name)) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
            }
            if (Build.VERSION.SDK_INT >= 28 && installed.signingInfo?.hasMultipleSigners() == true) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
            }
            val signer = AndroidNativeApplicationMetadata.singleInstalledSignerSha256(installed)
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
            val androidId = Settings.Secure.getString(application.contentResolver, Settings.Secure.ANDROID_ID)
                ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            if (androidId.length !in 1..16 || androidId.any { it !in '0'..'9' && it !in 'a'..'f' } ||
                androidId.all { it == '0' }) selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
            val targetInput = selfOwnedEncode("actual-android-target/v1") {
                string(name); int(uid); string(signer); string(androidId)
            }
            val targetDigest = try { AndroidP256Primitives.hashHex(targetInput) } finally { targetInput.fill(0) }
            val boot = Settings.Global.getInt(application.contentResolver, Settings.Global.BOOT_COUNT)
            if (boot < 0) selfOwnedFail(SelfOwnedInstallationFailureV1.UNAVAILABLE)
            val data = application.dataDir.canonicalFile
            val installedData = File(app.dataDir).canonicalFile
            val noBackup = when (kind) {
                DirectoryObservation.PREPARATION -> application.noBackupFilesDir
                DirectoryObservation.EXISTING_ORIGINAL -> File(data, "no_backup")
            }
            val existing = if (kind == DirectoryObservation.EXISTING_ORIGINAL)
                existingDirectories(data, noBackup, uid) else null
            // Resolve only OS-declared framework ancestry (e.g. /data/user/0). The registry
            // separately lstat/fstat-checks the canonical chain and every owned descendant.
            if (data != installedData || !data.isAbsolute || noBackup.name != "no_backup" ||
                noBackup.parentFile?.canonicalFile != data || noBackup.canonicalFile != File(data, "no_backup")) {
                selfOwnedFail(SelfOwnedInstallationFailureV1.INVALID_APPLICATION)
            }
            return Observation(SelfOwnedTargetDescriptorV1(name, uid, signer, targetDigest), boot,
                data.path, noBackup.canonicalPath, app.sourceDir,
                AndroidNativeApplicationMetadata.versionCode(installed), installed.lastUpdateTime, existing)
        }
    }
}
