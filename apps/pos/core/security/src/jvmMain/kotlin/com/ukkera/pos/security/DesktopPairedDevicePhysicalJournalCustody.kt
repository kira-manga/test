package com.ukkera.pos.security

import java.nio.file.Path

/** Internal OS adapter of the same protected desktop pairing target. Production variants
 * observe the signed app/package and retain its real Keychain/CNG and private directory.
 * External application callers cannot install a key or currentness provider here. */
internal interface DesktopPairedDevicePhysicalJournalNative {
    val installedApplication: SelfOwnedInstalledIdentityTargetV2
    val parentDirectory: Path
    val kind: DesktopPairedDevicePhysicalStorageKind
    fun requireCurrent()
    fun directoryFiles(): DesktopFiles?
    fun createKey(name: String)
    fun keyNames(prefix: String): List<String>
    fun inspectKey(name: String): String
    fun encrypt(name: String, aad: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray>
    fun decrypt(name: String, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray
}

/** Selects the existing physical storage profiles, never a new schema or grant. */
public enum class DesktopPairedDevicePhysicalStorageKind { POSIX_FULL_WRITE_FORCE_V1, WINDOWS_NTFS_FULL_WRITE_FLUSH_V1 }

/** Opaque storage ownership issued before the exact approved native target is handed to
 * RPC. It is independent of credential-acquisition currentness after handoff. Actual
 * employee journal loans and the original SDK/RPC owner remain separate mandatory checks. */
public class DesktopPairedDevicePhysicalJournalCustody internal constructor(
    private val original: PairedDeviceCredentialJournalCustody,
    private val native: DesktopPairedDevicePhysicalJournalNative,
    public val parentDirectory: Path,
) : PairedDevicePhysicalJournalSource() {
    private val installed = native.installedApplication
    public val kind: DesktopPairedDevicePhysicalStorageKind = native.kind
    public val windowsIdentity: SelfOwnedWindowsPackagedAppIdentityV2? = installed as? SelfOwnedWindowsPackagedAppIdentityV2

    init {
        check(original.protection == SelfOwnedKeyProtectionV1.OS_ISOLATED && installed == original.sdkOriginal.identity.installedApplication &&
            ((installed is SelfOwnedMacUiIdentityV2 && kind == DesktopPairedDevicePhysicalStorageKind.POSIX_FULL_WRITE_FORCE_V1) ||
                (installed is SelfOwnedWindowsPackagedAppIdentityV2 && kind == DesktopPairedDevicePhysicalStorageKind.WINDOWS_NTFS_FULL_WRITE_FLUSH_V1)))
    }
    internal override fun matches(original: PairedDeviceCredentialJournalCustody): Boolean = this.original === original
    public fun belongsTo(owner: Any, original: PairedDeviceCredentialJournalCustody): Boolean =
        matches(original) && original.belongsTo(owner)

    public fun requireCurrent() {
        native.requireCurrent()
        check(native.installedApplication == installed && native.kind == kind &&
            native.parentDirectory.toAbsolutePath().normalize() == parentDirectory && parentDirectory.toRealPath() == parentDirectory)
    }
    internal fun physicalDirectoryFiles(): DesktopFiles {
        requireCurrent()
        check(kind == DesktopPairedDevicePhysicalStorageKind.POSIX_FULL_WRITE_FORCE_V1)
        return checkNotNull(native.directoryFiles())
    }
    public fun createKey(name: String) { requireCurrent(); native.createKey(name); requireCurrent() }
    public fun keyNames(prefix: String): List<String> { requireCurrent(); return native.keyNames(prefix).also { requireCurrent() } }
    public fun inspectKey(name: String): String { requireCurrent(); return native.inspectKey(name).also { requireCurrent() } }
    public fun encrypt(name: String, aad: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
        requireCurrent()
        val result = native.encrypt(name, aad, plain)
        try { requireCurrent(); return result }
        catch (failure: Throwable) { result.first.fill(0); result.second.fill(0); throw failure }
    }
    public fun decrypt(name: String, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray {
        requireCurrent()
        val result = native.decrypt(name, nonce, sealed, aad)
        try { requireCurrent(); return result }
        catch (failure: Throwable) { result.fill(0); throw failure }
    }
    override fun toString(): String = "DesktopPairedDevicePhysicalJournalCustody([REDACTED])"
}

internal class MacPairedDevicePhysicalJournalNative(private val installed: SelfOwnedMacUiIdentityV2,
    private val parent: Path, private val boot: MacOriginalBootSession) : DesktopPairedDevicePhysicalJournalNative {
    override val installedApplication: SelfOwnedInstalledIdentityTargetV2 get() = installed
    override val parentDirectory: Path get() = parent
    override val kind = DesktopPairedDevicePhysicalStorageKind.POSIX_FULL_WRITE_FORCE_V1
    private var held: MacClientJournalKeyStore? = null
    private fun store(): MacClientJournalKeyStore = held ?: MacClientJournalKeyStore.open().also { held = it }
    override fun requireCurrent() {
        boot.elapsedMillis()
        check(MacInstalledApplicationIdentity.captureUi(installed) is SelfOwnedInstalledIdentityResultV2.Data)
    }
    override fun directoryFiles(): DesktopFiles { requireCurrent(); return MacMasterFiles(parent.toString()) }
    override fun createKey(name: String) = store().createKey(name)
    override fun keyNames(prefix: String): List<String> = store().keyNames(prefix)
    override fun inspectKey(name: String): String = store().inspectKey(name)
    override fun encrypt(name: String, aad: ByteArray, plain: ByteArray) = store().encrypt(name, aad, plain)
    override fun decrypt(name: String, nonce: ByteArray, sealed: ByteArray, aad: ByteArray) = store().decrypt(name, nonce, sealed, aad)
}

internal class WindowsPairedDevicePhysicalJournalNative(private val installed: SelfOwnedWindowsPackagedAppIdentityV2,
    private val parent: Path, private val boot: WindowsOriginalBootSession) : DesktopPairedDevicePhysicalJournalNative {
    override val installedApplication: SelfOwnedInstalledIdentityTargetV2 get() = installed
    override val parentDirectory: Path get() = parent
    override val kind = DesktopPairedDevicePhysicalStorageKind.WINDOWS_NTFS_FULL_WRITE_FLUSH_V1
    private var held: WindowsClientJournalKeyStore? = null
    private fun store(): WindowsClientJournalKeyStore = held ?: WindowsClientJournalKeyStore.open(installed).also { held = it }
    override fun requireCurrent() { boot.elapsedMillis(); WindowsDesktopFiles.currentUserSid(installed) }
    // Windows keeps the existing real NTFS directory owner and metadata-flush profile.
    override fun directoryFiles(): DesktopFiles? = null
    override fun createKey(name: String) = store().createKey(name)
    override fun keyNames(prefix: String): List<String> = store().keyNames(prefix)
    override fun inspectKey(name: String): String = store().inspectKey(name)
    override fun encrypt(name: String, aad: ByteArray, plain: ByteArray) = store().encrypt(name, aad, plain)
    override fun decrypt(name: String, nonce: ByteArray, sealed: ByteArray, aad: ByteArray) = store().decrypt(name, nonce, sealed, aad)
}
