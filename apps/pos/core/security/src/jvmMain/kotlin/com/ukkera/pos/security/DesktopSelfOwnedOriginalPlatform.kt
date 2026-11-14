package com.ukkera.pos.security

import com.ukkera.pos.types.DomainId
import dev.p2pkit.core.AppId
import dev.p2pkit.core.dsl.jvmSecureIdentityStore
import dev.p2pkit.transport.lan.lan

/**
 * The OS facts and primitives one Windows/macOS Master installation supplies to the shared desktop
 * platform. Each observation is of the running process and its fixed installation; nothing here
 * is caller-selected. Windows: CNG/DPAPI custody, NTFS file IDs, the service SID. macOS: Keychain
 * custody, APFS inodes, the console user's uid.
 */
internal interface DesktopOriginalOs {
    val platform: SelfOwnedNativePlatformV2
    val files: DesktopFiles
    val custody: DesktopKeyCustody
    /** Actual protection class of keys this custody creates. */
    val keySecurity: SelfOwnedNativeSecurityV1
    val supportedProtections: Set<SelfOwnedKeyProtectionV1>
    fun observeInstalledTarget(): SelfOwnedNativeInstalledTargetBindingV1
    fun observeBootSession(): SelfOwnedNativeBootSessionV2
    fun principal(): SelfOwnedProcessPrincipal
    fun elapsedMillis(): Long
    fun elapsedRealtimeNanos(): Long
    fun openSdkIdentityStore(profile: SelfOwnedNativeProfileV1): dev.p2pkit.core.security.JvmSecureIdentityStore
    fun info(message: String) {}
    fun warn(message: String) {}
}

/**
 * Retained boot-ordinal ledger chain, from genesis through the current boot session, in its own
 * owner-only locked directory. Exactly one advance step is appended per new OS boot session; the
 * chain is never truncated or rewritten, and any unverifiable retained chain refuses to open.
 */
internal object DesktopBootLedgerStore {
    private const val DIRECTORY = "boot-ledger.v2"
    private const val CHAIN = "chain.v2"
    private const val MAXIMUM = 4 * 1024 * 1024

    fun observe(files: DesktopFiles, session: SelfOwnedNativeBootSessionV2, allowGenesis: Boolean):
        Pair<SelfOwnedBootOrdinalLedgerV2, List<SelfOwnedBootOrdinalLedgerV2>> {
        DesktopLockedDirectory.open(files, DIRECTORY, create = allowGenesis, allowed = setOf(CHAIN)).use { directory ->
            val retained = directory.read(CHAIN, MAXIMUM)?.second?.let { bytes -> try { decode(bytes) } finally { bytes.fill(0) } }
            val chain: List<SelfOwnedBootOrdinalLedgerV2> = when {
                retained == null -> {
                    desktopKnown(allowGenesis && directory.createdThisLifetime)
                    listOf(SelfOwnedBootOrdinalLedgerV2(0, session, null))
                }
                else -> {
                    desktopKnown(retained.isNotEmpty())
                    val last = retained.last()
                    desktopKnown(SelfOwnedBootChainV2.verify(retained, last) != null)
                    when (val step = last.advance(session)) {
                        is SelfOwnedBootOrdinalStepV2.Same -> retained
                        is SelfOwnedBootOrdinalStepV2.Advanced -> retained + step.ledger
                        is SelfOwnedBootOrdinalStepV2.Refused -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                    }
                }
            }
            val current = chain.last()
            desktopKnown(SelfOwnedBootChainV2.verify(chain, current) != null)
            if (retained == null || chain.size != retained.size) {
                val encoded = encode(chain)
                try { directory.replace(CHAIN, encoded, MAXIMUM) } finally { encoded.fill(0) }
            }
            return current to chain
        }
    }

    private fun encode(chain: List<SelfOwnedBootOrdinalLedgerV2>): ByteArray {
        val parts = chain.map(SelfOwnedNativeProvisionerWireV2::encodeBootOrdinalLedger)
        try {
            val buffer = java.nio.ByteBuffer.allocate(4 + parts.sumOf { 4 + it.size })
            buffer.putInt(parts.size)
            for (part in parts) { buffer.putInt(part.size); buffer.put(part) }
            return buffer.array()
        } finally { parts.forEach { it.fill(0) } }
    }

    private fun decode(bytes: ByteArray): List<SelfOwnedBootOrdinalLedgerV2> {
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        desktopKnown(bytes.size >= 4)
        val count = buffer.int
        desktopKnown(count in 1..100_000)
        val result = ArrayList<SelfOwnedBootOrdinalLedgerV2>(count)
        repeat(count) {
            desktopKnown(buffer.remaining() >= 4)
            val size = buffer.int
            desktopKnown(size in 1..buffer.remaining())
            val part = ByteArray(size).also(buffer::get)
            try {
                result += when (val decoded = SelfOwnedNativeProvisionerWireV2.decodeBootOrdinalLedger(part)) {
                    is SelfOwnedInstalledIdentityResultV2.Data -> decoded.value
                    is SelfOwnedInstalledIdentityResultV2.Refused -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                }
            } finally { part.fill(0) }
        }
        desktopKnown(!buffer.hasRemaining())
        return result
    }
}

/** One native lifetime's OS observation for the shared owner (format V2). */
internal class DesktopOriginalEnvironment private constructor(
    override val profile: SelfOwnedNativeProfileV1,
    private val os: DesktopOriginalOs,
    private val preparation: Boolean,
    override val installedTarget: SelfOwnedNativeInstalledTargetBindingV1,
    override val bootMark: SelfOwnedBootMark.Session,
    private val chain: List<SelfOwnedBootOrdinalLedgerV2>,
    override val principal: SelfOwnedProcessPrincipal,
) : SelfOwnedOriginalEnvironment {
    init { selfOwnedOriginalFormat(installedTarget, bootMark, principal).let { desktopKnown(it == SelfOwnedOriginalFormat.V2) } }

    override fun requireExistingOriginalObservation() {
        if (preparation) selfOwnedFail(SelfOwnedInstallationFailureV1.ORIGINAL_ACTION_REQUIRED)
        requireCurrent()
    }

    override fun currentProvisionerVolumeUuid(): String {
        requireCurrent()
        val handle = os.files.openDirectory(os.files.baseDirectory)
        return try { os.files.volumeUuid(handle) } finally { handle.close() }
    }

    override fun requireCurrent() {
        if (os.observeBootSession() != bootMark.session || os.principal() != principal ||
            !SelfOwnedInstalledIdentityV2.matches(os.observeInstalledTarget().target, installedTarget.target)) {
            selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    override fun requireSupportedProtection(protection: SelfOwnedKeyProtectionV1) {
        if (protection !in os.supportedProtections) selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
    }

    override fun bootLedgerChain(): List<SelfOwnedBootOrdinalLedgerV2> = chain

    override fun toString(): String = "DesktopOriginalEnvironment([REDACTED])"

    companion object {
        fun capture(os: DesktopOriginalOs, profile: SelfOwnedNativeProfileV1, preparation: Boolean): DesktopOriginalEnvironment {
            val target = os.observeInstalledTarget()
            val session = os.observeBootSession()
            val principal = os.principal()
            val (current, chain) = DesktopBootLedgerStore.observe(os.files, session, allowGenesis = preparation)
            val environment = DesktopOriginalEnvironment(profile, os, preparation, target, SelfOwnedBootMark.Session(current),
                chain, principal)
            environment.requireCurrent()
            return environment
        }
    }
}

internal class DesktopSelfOwnedProcess(private val os: DesktopOriginalOs) : SelfOwnedProcessPlatform {
    override fun pid(): Int = ProcessHandle.current().pid().toInt()
    override fun principal(): SelfOwnedProcessPrincipal = os.principal()
    override fun elapsedMillis(): Long = os.elapsedMillis()
    override fun elapsedRealtimeNanos(): Long = os.elapsedRealtimeNanos()
    override fun passwordKeyFactory(): javax.crypto.SecretKeyFactory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
    override fun terminate(pid: Int) {
        // Only the exact current process; the native lease stays held until the OS reaps it.
        if (pid == pid()) Runtime.getRuntime().halt(70)
    }
    override fun info(message: String) = os.info(message)
    override fun warn(message: String) = os.warn(message)
}

/** Shared [DevicePairingKitOwner] over the desktop LAN Kit and OS identity store (V2 Master DATA). */
internal class DesktopMasterSdkOwnerV2(
    private val os: DesktopOriginalOs,
    private val profile: SelfOwnedNativeProfileV1,
    private val work: AndroidOwnedInstallationWork,
) : SelfOwnedMasterSdkOwner {
    private val shared = DevicePairingKitOwner(
        requireActive = { work.requireActive() },
        closureUnknown = { work.nativeClosureUnknown() },
        beforeCreate = {},
        configure = {
            jvmSecureIdentityStore(os.openSdkIdentityStore(profile))
            transports { lan() }
        },
    )
    override fun createNative(exactAppId: AppId, exactDeviceName: String) = shared.create(exactAppId, exactDeviceName)
    override fun captureMasterNative(originalAcquisitionId: DomainId, allocation: OriginalAllocationCommitmentV2,
        environment: SelfOwnedOriginalEnvironment): LocalMasterSdkOriginal = shared.captureMasterV2(originalAcquisitionId,
        allocation, environment.installedTarget as? SelfOwnedNativeInstalledTargetBindingV1
            ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM))
    override fun requireMatchesNative(original: LocalMasterSdkOriginal) =
        shared.requireMatchesMaster(original as? LocalMasterSdkOriginalV2 ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN))
    override fun closeNative() = shared.close()
    override fun toString(): String = "DesktopMasterSdkOwnerV2([REDACTED])"
}

/** Windows/macOS custody for the shared original owner (format V2 only). */
internal class DesktopSelfOwnedOriginalPlatform(private val os: DesktopOriginalOs) : SelfOwnedOriginalPlatform {
    override val process: SelfOwnedProcessPlatform = DesktopSelfOwnedProcess(os)

    override fun captureEnvironment(work: AndroidOwnedInstallationWork, profile: SelfOwnedNativeProfileV1,
        preparation: Boolean): SelfOwnedOriginalEnvironment = DesktopOriginalEnvironment.capture(os, profile, preparation)

    override fun databaseNamespace(environment: SelfOwnedOriginalEnvironment, work: AndroidOwnedInstallationWork,
        ownerToken: Any): SelfOwnedOriginalDatabaseNamespace = DesktopOriginalNamespaceV2(os.files, environment.desktop(), work, ownerToken)

    override fun signing(environment: SelfOwnedOriginalEnvironment, journal: AndroidSelfOwnedInstallationJournal,
        work: AndroidOwnedInstallationWork): SelfOwnedOriginalSigning =
        DesktopOriginalSigningV2(environment.desktop(), journal, work, os.custody, os.keySecurity)

    override fun dataKeys(environment: SelfOwnedOriginalEnvironment, journal: AndroidSelfOwnedInstallationJournal,
        work: AndroidOwnedInstallationWork): SelfOwnedOriginalDataKeys =
        DesktopOriginalDataKeysV2(environment.desktop(), journal, work, os.custody, os.keySecurity)

    override fun masterSdkOwner(work: AndroidOwnedInstallationWork): SelfOwnedMasterSdkOwner =
        DesktopMasterSdkOwnerV2(os, currentProfile ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN), work)

    override fun provisionerRegistry(environment: SelfOwnedOriginalEnvironment, work: AndroidOwnedInstallationWork):
        SelfOwnedOriginalRegistryStore =
        DesktopProvisionerRegistryFilesV2(environment.desktop().profile, DesktopFilesOwnedDirectories(os.files))

    override fun provisionerKeys(environment: SelfOwnedOriginalEnvironment, registry: SelfOwnedOriginalRegistryStore,
        work: AndroidOwnedInstallationWork): SelfOwnedOriginalProvisionerKeys {
        val store = registry as? SelfOwnedProvisionerRegistryStoreV2 ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM)
        return DesktopProvisionerKeysV2(environment.desktop(), store, os.custody, os.keySecurity)
    }

    override fun historyWorkspace(source: AndroidSelfOwnedHistoryReadStorage, work: AndroidOwnedInstallationWork,
        environment: SelfOwnedOriginalEnvironment, original: OriginalNativeHistorySnapshot): SelfOwnedHistoryWorkspaceStore =
        DesktopSelfOwnedHistoryWorkspace(os.files, source, work, environment.desktop(), original)

    override fun receiptMembership(owner: AndroidSelfOwnedInstallationLifetime, work: AndroidOwnedInstallationWork,
        environment: SelfOwnedOriginalEnvironment, readIdentity: Any?): SelfOwnedReceiptMembershipStore =
        DesktopSelfOwnedReceiptMembershipData(os.files, owner, work, environment.desktop(), readIdentity)

    @Volatile private var currentProfile: SelfOwnedNativeProfileV1? = null

    private fun SelfOwnedOriginalEnvironment.desktop(): DesktopOriginalEnvironment =
        (this as? DesktopOriginalEnvironment ?: selfOwnedFail(SelfOwnedInstallationFailureV1.UNSUPPORTED_PLATFORM))
            .also { currentProfile = it.profile }

    override fun toString(): String = "DesktopSelfOwnedOriginalPlatform([REDACTED])"
}
