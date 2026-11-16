package com.ukkera.pos.security

import java.nio.file.Path
import java.security.MessageDigest

/**
 * Master full-state backup custody of the Windows/macOS Master SERVICE. Like
 * [DesktopMasterClientJournalCustody], everything comes from the SAME [DesktopOriginalOs] the
 * Master service observes: an owner-only `ukkera.desktop-master-backups.v1` directory under its
 * fixed application data root and one non-exportable AES-256-GCM recovery key in its OS custody
 * (CNG/DPAPI service account or data-protection Keychain). Key bytes never leave the custody.
 *
 * The key is created once, only when absent; an existing key is never replaced. Archives sealed
 * by a lost key fail authentication. Accepts no caller path, key, provider or clock.
 */
public class DesktopMasterBackupCustody private constructor(
    private val os: DesktopOriginalOs,
    private val alias: String,
    /** Owner-only directory holding the encrypted archives and chain heads. */
    public val archiveDirectory: Path,
    /** Owner-only directory for consistent capture copies (removed after each backup). */
    public val captureDirectory: Path,
    /** Owner-only fresh/quarantined restore target, separate from the live Master database. */
    public val restoreDirectory: Path,
    private val target: SelfOwnedNativeInstalledTargetBindingV1,
    private val principal: SelfOwnedProcessPrincipal,
) {
    /** Stable non-secret label: platform security class + SHA-256 of the key-check value. */
    public val keyId: String get() {
        requireCurrent()
        val check = os.custody.symmetricCheck(alias) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        try { return "desktop-" + os.keySecurity.name.lowercase() + ":" + hex(MessageDigest.getInstance("SHA-256").digest(check)).take(32) }
        finally { check.fill(0) }
    }

    /** Re-observes the installed service identity and process principal; any change fails closed. */
    public fun requireCurrent() {
        desktopKnown(os.observeInstalledTarget().matches(target) && os.principal() == principal)
    }

    /** Sealed layout: ivLength(1) | iv | ciphertext+tag (same framing as the JCA custody). */
    public fun seal(aad: ByteArray, plaintext: ByteArray): ByteArray {
        requireCurrent()
        val (nonce, ciphertext) = os.custody.sealAes(alias, aad, plaintext)
        desktopKnown(nonce.size in 12..16)
        return byteArrayOf(nonce.size.toByte()) + nonce + ciphertext
    }

    public fun open(aad: ByteArray, sealed: ByteArray): ByteArray {
        requireCurrent()
        desktopKnown(sealed.size > 1 + 12 + 16)
        val size = sealed[0].toInt()
        desktopKnown(size in 12..16 && sealed.size > 1 + size + 16)
        return os.custody.openAes(alias, aad, sealed.copyOfRange(1, 1 + size), sealed.copyOfRange(1 + size, sealed.size))
    }

    override fun toString(): String = "DesktopMasterBackupCustody([REDACTED])"

    public companion object {
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** Live composition of the running Windows/macOS Master service. Unsupported hosts refuse. */
        public fun open(service: SelfOwnedInstalledIdentityTargetV2): DesktopMasterBackupCustody =
            of(DesktopNativeOriginalOs.current(service), SelfOwnedNativeProfileV1.LIVE)

        /** Training slot composition: separate directory and key. */
        public fun openTraining(service: SelfOwnedInstalledIdentityTargetV2, slot: LocalTrainingCompositionSlot):
            DesktopMasterBackupCustody = of(DesktopNativeOriginalOs.current(service), SelfOwnedNativeProfileV1.training(slot))

        internal fun of(os: DesktopOriginalOs, profile: SelfOwnedNativeProfileV1): DesktopMasterBackupCustody {
            val suffix = profile.slot?.let { "." + it.name.lowercase().replace('_', '-') } ?: ""
            val target = os.observeInstalledTarget()
            val principal = os.principal()
            val files = os.files
            fun ownedDirectory(parent: String, name: String): String {
                val path = files.child(parent, name)
                if (files.observe(path) == null) files.createDirectory(path)
                val observed = files.observe(path) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
                desktopKnown(observed.kind == DesktopEntryKind.DIRECTORY && observed.owner == files.ownerName)
                return path
            }
            val root = ownedDirectory(files.baseDirectory, "ukkera.desktop-master-backups$suffix.v1")
            val archives = ownedDirectory(root, "archives")
            val capture = ownedDirectory(root, "capture")
            val restore = files.child(root, "restore") // Created only by an approved restore stage.
            val alias = "master-backup$suffix.v1"
            if (os.custody.symmetricCheck(alias)?.also { it.fill(0) } == null) os.custody.createAes(alias)
            desktopKnown(os.custody.symmetricCheck(alias)?.also { it.fill(0) } != null)
            return DesktopMasterBackupCustody(os, alias, Path.of(archives), Path.of(capture), Path.of(restore), target, principal)
        }
    }
}
