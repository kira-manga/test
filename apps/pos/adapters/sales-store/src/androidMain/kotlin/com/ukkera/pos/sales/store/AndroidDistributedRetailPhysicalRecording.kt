package com.ukkera.pos.sales.store

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Process
import android.os.UserManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.ukkera.pos.android.files.AndroidNativeFiles
import com.ukkera.pos.database.AndroidOriginalLocalClient
import com.ukkera.pos.database.LocalMasterPhysicalRpcClientV1
import com.ukkera.pos.database.belongsTo
import com.ukkera.pos.security.AndroidNativeKeyInfo
import com.ukkera.pos.security.AndroidNativeKeyPolicy
import com.ukkera.pos.security.AndroidNativeKeySecurity
import com.ukkera.pos.security.PairedDeviceCredentialJournalCustody
import com.ukkera.pos.security.SelfOwnedKeyProtectionV1
import com.ukkera.pos.types.DomainId
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/** Actual app UID/private files/AndroidKeyStore; one genuine native role per acquisition. */
public object AndroidDistributedRetailPhysicalRecording {
    public fun acquireClient(application: Application, original: PairedDeviceCredentialJournalCustody,
        rpc: LocalMasterPhysicalRpcClientV1, actorId: DomainId, actorIntentJournalId: DomainId,
        actorCustody: LocalPhysicalExternalCustody,
        action: DistributedPhysicalNativeAction): DistributedPhysicalClientNativeAcquisition? =
        acquireClientOwned(application, original, rpc, actorId, actorIntentJournalId, actorCustody,
            action, DistributedPhysicalClientPurpose.INTERACTIVE)

    /** Actual native existing-only recovery. Scalar selectors cannot create files/keys, arm
     * an action, supply native proof or expose a mutable employee/session continuation. */
    public fun acquireRawOriginalRecovery(application: Application, original: PairedDeviceCredentialJournalCustody,
        rpc: LocalMasterPhysicalRpcClientV1, actorId: DomainId, actorIntentJournalId: DomainId,
        allocationCustody: LocalPhysicalExternalCustody): DistributedPhysicalRawRecoveryAcquisition? =
        acquireClientOwned(application, original, rpc, actorId, actorIntentJournalId, allocationCustody,
            DistributedPhysicalNativeAction.REOPEN_EXISTING, DistributedPhysicalClientPurpose.RAW_ORIGINAL_RECOVERY)
            ?.let { DistributedPhysicalRawRecoveryAcquisition(it, rpc) }

    /** Existing-only original recording catalogue; cannot enroll or begin a fresh Start. */
    public fun acquireExistingResolution(application: Application, original: PairedDeviceCredentialJournalCustody,
        rpc: LocalMasterPhysicalRpcClientV1, recordedActorId: DomainId, actorIntentJournalId: DomainId,
        allocationCustody: LocalPhysicalExternalCustody): DistributedPhysicalClientNativeAcquisition? =
        acquireClientOwned(application, original, rpc, recordedActorId, actorIntentJournalId, allocationCustody,
            DistributedPhysicalNativeAction.REOPEN_EXISTING, DistributedPhysicalClientPurpose.EXISTING_RESOLUTION)

    private fun acquireClientOwned(application: Application, original: PairedDeviceCredentialJournalCustody,
        rpc: LocalMasterPhysicalRpcClientV1, actorId: DomainId, actorIntentJournalId: DomainId,
        actorCustody: LocalPhysicalExternalCustody, action: DistributedPhysicalNativeAction,
        purpose: DistributedPhysicalClientPurpose): DistributedPhysicalClientNativeAcquisition? {
        val qualification = PhysicalJournalExternalCustodyOwnership(actorCustody)
        var transferred = false
        try {
            if (!original.belongsTo(application) || !rpc.hasOriginal(original.sdkOriginal, original.approvedOriginal) ||
                !rpc.isCurrent() || qualification.denied) return null
            val composition = AndroidPhysicalCompositionNamespaceV1.of(original.approvedOriginal.masterSdkOriginal.compositionProfile)
            val domain = "ukkera.android.distributed-physical-client/" + composition.toString()
            fun id(role: String) = distributedPhysicalId(original.bindingCommitment, actorId, actorIntentJournalId, domain, role)
            val binding = LocalPhysicalCatalogueBinding(original.scope, id("installation"), original.deviceId,
                id("catalogue"), LocalPhysicalSpoolRole.CLIENT, id("key"))
            val enroll = action == DistributedPhysicalNativeAction.ENROLL_ORIGINAL
            val namespace = DistributedAndroidPhysicalNamespace(application, binding, composition, original.protection, enroll)
            val retained = DistributedPhysicalClientNativeAcquisitions.retain(binding) {
                DistributedPhysicalClientNativeAcquisition(original, rpc, actorId, actorIntentJournalId, binding,
                    action, purpose, LocalPhysicalSpoolStorageProfileV1.ANDROID_API30_APP_PRIVATE_FALLOCATE_V1,
                    qualification, namespace) {
                    if (enroll) AndroidPreallocatedPhysicalJournal.createCatalogue(application, binding, namespace.keySecurity, composition)
                    else AndroidPreallocatedPhysicalJournal.openCatalogue(application, binding, namespace.keySecurity, composition)
                }
            } ?: return null
            transferred = true
            return retained
        } finally { if (!transferred) check(qualification.releaseAfterNativeCleanup()) }
    }

    public fun acquireMaster(application: Application, original: AndroidOriginalLocalClient,
        registeredOwner: LocalPhysicalJournalExecutionOwner,
        action: DistributedPhysicalNativeAction,
        composition: AndroidPhysicalCompositionNamespaceV1 = AndroidPhysicalCompositionNamespaceV1.LIVE): DistributedPhysicalMasterNativeAcquisition? {
        if (!original.belongsTo(application) || original.physicalEndpoint(registeredOwner) == null ||
            composition.mode != original.compositionMode ||
            (action == DistributedPhysicalNativeAction.ENROLL_ORIGINAL && !original.permitsFirstEnrollment())) return null
        val domain = "ukkera.android.distributed-physical-master/" + composition.toString()
        fun id(role: String) = LocalOriginalRetailPhysicalAcquisition.originalPhysicalCatalogueId(original, role, domain)
        val binding = LocalPhysicalCatalogueBinding(original.peer.scope, id("installation"), original.peer.deviceId,
            id("catalogue"), LocalPhysicalSpoolRole.MASTER, id("key"))
        val enroll = action == DistributedPhysicalNativeAction.ENROLL_ORIGINAL
        val namespace = DistributedAndroidPhysicalNamespace(application, binding, composition,
            original.material.protection, enroll)
        return DistributedPhysicalMasterNativeAcquisitions.retain(binding) {
            DistributedPhysicalMasterNativeAcquisition(original, registeredOwner, binding,
                LocalPhysicalSpoolStorageProfileV1.ANDROID_API30_APP_PRIVATE_FALLOCATE_V1, enroll, namespace) {
                if (enroll) AndroidPreallocatedPhysicalJournal.createCatalogue(application, binding, namespace.keySecurity, composition)
                else AndroidPreallocatedPhysicalJournal.openCatalogue(application, binding, namespace.keySecurity, composition)
            }
        }
    }
}

/**
 * Fixed installation leaf below the existing framework no_backup parent. Other genuine
 * installations may share that parent; the selected installation has a complete closed census.
 * Namespace/key enrollment is exclusive and never inferred from a failed existing-only open.
 */
private class DistributedAndroidPhysicalNamespace(
    private val application: Application,
    private val binding: LocalPhysicalCatalogueBinding,
    private val composition: AndroidPhysicalCompositionNamespaceV1,
    private val protection: SelfOwnedKeyProtectionV1,
    private val enroll: Boolean,
) : OriginalPhysicalNativeNamespace {
    private val uid = Process.myUid()
    private val pid = Process.myPid()
    private val directories = linkedMapOf<File, OriginalPhysicalDirectory>()
    private var data: File? = null
    private var prepared = false
    private var closing = false
    private var result: LocalPhysicalSpoolClose? = null
    private var store: KeyStore? = null
    private var observedSecurity: AndroidPhysicalSpoolKeySecurity? = null
    val keySecurity: AndroidPhysicalSpoolKeySecurity get() = checkNotNull(observedSecurity)
    private val prefix: String get() = composition.keyAliasPrefix + "/" + binding.installationId.value + "/storage/"
    private val alias: String get() = prefix + binding.keyId.value

    override fun prepare() {
        check(!prepared && !closing)
        requireCurrent()
        val actualData = application.dataDir.canonicalFile
        data = actualData
        hold(actualData, OriginalPhysicalDirectoryRole.APP_DATA)
        val parent = File(actualData, "no_backup") // Never call the creating framework getter.
        hold(parent, OriginalPhysicalDirectoryRole.FRAMEWORK_NO_BACKUP)
        val root = File(parent, composition.journalDirectoryName)
        if (enroll && absent(root)) create(root, parent) else hold(root, OriginalPhysicalDirectoryRole.PRIVATE)
        val installation = File(root, binding.installationId.value)
        if (enroll) create(installation, root) else hold(installation, OriginalPhysicalDirectoryRole.PRIVATE)
        val catalogue = File(installation, binding.catalogueId.value)
        val role = File(catalogue, binding.role.name.lowercase())
        if (enroll) { create(catalogue, installation); create(role, catalogue) }
        else { hold(catalogue, OriginalPhysicalDirectoryRole.PRIVATE); hold(role, OriginalPhysicalDirectoryRole.PRIVATE) }
        val leaves = listOf("anchor", "catalogue") + (0 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES).map(::slot)
        for (name in leaves) {
            val leaf = File(role, name)
            if (enroll) create(leaf, role) else hold(leaf, OriginalPhysicalDirectoryRole.PRIVATE)
        }
        requireEntries(installation, setOf(binding.catalogueId.value))
        requireEntries(catalogue, setOf(binding.role.name.lowercase()))
        requireEntries(role, leaves.toSet())
        if (enroll) leaves.forEach { requireEntries(File(role, it), emptySet()) }
        val actualStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store = actualStore
        val aliases = actualStore.aliases().toList().filter { it.startsWith(prefix) }.sorted()
        if (enroll) {
            check(aliases.isEmpty())
            val spec = AndroidNativeKeyInfo.configureService(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true), protection).build()
            // No fallback, delete or second generation after a throwing/unknown native call.
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(spec); generateKey() }
        } else check(aliases == listOf(alias))
        check(actualStore.aliases().toList().filter { it.startsWith(prefix) } == listOf(alias))
        observedSecurity = inspect(actualStore)
        prepared = true
        requireCurrent()
    }

    override fun requireCurrent() {
        check(!closing && !Thread.currentThread().isInterrupted && Process.myUid() == uid && Process.myPid() == pid &&
            Build.VERSION.SDK_INT >= AndroidNativeKeyPolicy.MINIMUM_ANDROID_API &&
            AndroidNativeKeyPolicy.supports(Build.VERSION.SDK_INT, protection) &&
            application.applicationContext === application && !application.isDeviceProtectedStorage &&
            application.applicationInfo.uid == uid && application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 &&
            application.getSystemService(UserManager::class.java)?.isUserUnlocked == true)
        data?.let { check(application.dataDir.canonicalFile == it) }
        directories.values.forEach { it.requireCurrent(uid) }
        if (prepared) check(inspect(checkNotNull(store)) == keySecurity)
    }

    private fun inspect(actual: KeyStore): AndroidPhysicalSpoolKeySecurity {
        check(actual.containsAlias(alias))
        val key = actual.getKey(alias, null) as? SecretKey ?: error("physical.remote.android-key")
        key.encoded?.let { it.fill(0); error("physical.remote.exportable-key") }
        check(key.format == null && key.algorithm == KeyProperties.KEY_ALGORITHM_AES)
        val info = SecretKeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        check(info.keystoreAlias == alias && info.keySize == 256 && info.origin == KeyProperties.ORIGIN_GENERATED &&
            info.purposes == (KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT) &&
            info.blockModes.toSet() == setOf(KeyProperties.BLOCK_MODE_GCM) &&
            info.encryptionPaddings.toSet() == setOf(KeyProperties.ENCRYPTION_PADDING_NONE) &&
            info.signaturePaddings.isEmpty() && info.digests.isEmpty() && !AndroidNativeKeyInfo.hasServiceRestrictions(info))
        val security = AndroidNativeKeyInfo.observe(info)
        check(AndroidNativeKeyPolicy.permits(protection, security))
        return when (security) {
            AndroidNativeKeySecurity.SOFTWARE -> AndroidPhysicalSpoolKeySecurity.SOFTWARE
            AndroidNativeKeySecurity.TRUSTED_ENVIRONMENT -> AndroidPhysicalSpoolKeySecurity.TRUSTED_ENVIRONMENT
            AndroidNativeKeySecurity.STRONGBOX -> AndroidPhysicalSpoolKeySecurity.STRONGBOX
            AndroidNativeKeySecurity.HARDWARE_KIND_UNSPECIFIED -> AndroidPhysicalSpoolKeySecurity.HARDWARE_KIND_UNSPECIFIED
        }
    }

    override fun requireCensus(owner: LocalPhysicalCatalogueBinding, census: LocalPhysicalCatalogueNativeCensus) {
        check(owner == binding)
        val role = File(checkNotNull(data), "no_backup/${composition.journalDirectoryName}/${binding.installationId.value}/" +
            "${binding.catalogueId.value}/${binding.role.name.lowercase()}")
        requireEntries(File(role, "anchor"), census.anchorFilenames)
        requireEntries(File(role, "catalogue"), census.catalogueFilenames)
        for (index in 0 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES)
            requireEntries(File(role, slot(index)), census.journalFilenames[index] ?: emptySet())
    }
    private fun hold(path: File, role: OriginalPhysicalDirectoryRole) {
        requireCurrent()
        check(path !in directories && path.isAbsolute && path.absoluteFile == path.canonicalFile)
        val actual = OriginalPhysicalDirectory(path, role)
        directories[path] = actual
        actual.open(uid)
        requireCurrent()
    }
    private fun create(path: File, parent: File) {
        requireCurrent()
        check(enroll && path.parentFile == parent && absent(path))
        checkNotNull(directories[parent]).requireCurrent(uid)
        Os.mkdir(path.path, 0x1c0)
        hold(path, OriginalPhysicalDirectoryRole.PRIVATE)
        directories.getValue(path).force(uid)
        directories.getValue(parent).force(uid)
    }
    private fun requireEntries(path: File, expected: Set<String>) {
        requireCurrent()
        val directory = checkNotNull(directories[path])
        val names = mutableSetOf<String>()
        try {
            AndroidNativeFiles.openDirectory(path.path).use { cursor ->
                while (true) {
                    val name = cursor.nextName() ?: break
                    check(names.size < expected.size && name in expected && names.add(name))
                }
            }
        } catch (failure: Throwable) {
            if (failure is AndroidNativeFiles.ClosureUncertainException ||
                failure.suppressed.any { it is AndroidNativeFiles.ClosureUncertainException }) directory.markClosureUncertain()
            throw failure
        }
        check(names == expected)
        requireCurrent()
    }
    private fun absent(path: File): Boolean = try { Os.lstat(path.path); false }
        catch (missing: ErrnoException) { if (missing.errno == OsConstants.ENOENT) true else throw missing }
    override fun close(): LocalPhysicalSpoolClose {
        result?.let { return it }
        closing = true
        var complete = true
        for (actual in directories.values.toList().asReversed()) if (actual.close() != LocalPhysicalSpoolClose.CLOSED) complete = false
        return (if (complete) LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD).also { result = it }
    }
    private fun slot(index: Int): String = "slot-${index.toString().padStart(3, '0')}"
}
