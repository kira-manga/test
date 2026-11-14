package com.ukkera.pos.security

import dev.p2pkit.core.security.JvmSecureIdentityStore
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Same-device original (cohost) client journal custody of the desktop Master SERVICE. Everything
 * comes from the SAME [DesktopOriginalOs] the Master service observes: an owner-only directory
 * under its fixed application data root, its non-exportable symmetric key custody for the journal
 * data keys and its OS-protected secret store (DPAPI for the service account / data-protection
 * Keychain) for the journal key index and anti-rollback head markers. It is never the UI-process
 * DPAPI/login-Keychain journal store and accepts no caller path, key, provider or clock.
 *
 * The client journal engine addresses keys by name and must enumerate a journal's namespace. The
 * shared desktop custody cannot enumerate, so each journal prefix keeps fixed index slots in the
 * secret store: one data-key slot (written BEFORE the key is created, never replaced) and two
 * head slots by sequence parity (heads are consecutive, so they never collide). An index entry
 * without its key, or any unexpected entry, fails closed; nothing is repaired or recreated.
 */
public class DesktopMasterClientJournalCustody private constructor(
    private val os: DesktopOriginalOs,
    private val registry: JvmSecureIdentityStore,
    private val suffix: String,
    /** Owner-only parent directory for the client journal roots. */
    public val parentDirectory: Path,
    /** Canonical volume UUID holding [parentDirectory]. */
    public val volumeId: String,
    /** SHA-256 of the canonical installed service target, bound into every journal AEAD. */
    public val targetDigest: String,
    private val target: SelfOwnedNativeInstalledTargetBindingV1,
    private val principal: SelfOwnedProcessPrincipal,
) {
    /** Re-observes the installed service identity and process principal; any change fails closed. */
    public fun requireCurrent() {
        val observed = os.observeInstalledTarget()
        desktopKnown(observed.matches(target) && os.principal() == principal)
    }

    public fun keyNames(prefix: String): List<String> {
        val journal = journalPrefix(prefix)
        desktopKnown(journal == prefix)
        val names = SLOTS.mapNotNull { slot -> readSlot(prefix, slot) }
        for (name in names) desktopKnown(name.startsWith(prefix) && classify(name).first == prefix)
        return names.sorted()
    }

    public fun keyExists(name: String): Boolean {
        val (prefix, slot) = classify(name)
        val indexed = readSlot(prefix, slot) ?: return false
        desktopKnown(indexed == name)
        if (slot == DATA_SLOT) desktopKnown(os.custody.symmetricCheck(aesAlias(name))?.also { it.fill(0) } != null)
        return true
    }

    /** Index intent first (never replaced), then the non-exportable data key; heads are index-only. */
    public fun createKey(name: String) {
        val (prefix, slot) = classify(name)
        desktopKnown(readSlot(prefix, slot) == null)
        val bytes = name.encodeToByteArray(throwOnInvalidSequence = true)
        try {
            val kept = registry.putIfAbsent(namespace(prefix, slot), bytes)
            try { desktopKnown(MessageDigest.isEqual(kept, bytes)) } finally { kept.fill(0) }
        } finally { bytes.fill(0) }
        if (slot == DATA_SLOT) {
            desktopKnown(os.custody.symmetricCheck(aesAlias(name))?.also { it.fill(0) } == null)
            os.custody.createAes(aesAlias(name))
        }
        desktopKnown(keyExists(name))
    }

    /** Only superseded anti-rollback heads are ever removed; a data key is never deleted. */
    public fun deleteKey(name: String) {
        val (prefix, slot) = classify(name)
        desktopKnown(slot != DATA_SLOT && readSlot(prefix, slot) == name)
        desktopKnown(registry.delete(namespace(prefix, slot)))
        desktopKnown(readSlot(prefix, slot) == null)
    }

    /** Opaque, stable policy observation compared by equality; reveals no key material. */
    public fun inspectKey(name: String): String {
        desktopKnown(keyExists(name))
        val (_, slot) = classify(name)
        if (slot != DATA_SLOT) return "head:" + os.keySecurity.name
        val check = os.custody.symmetricCheck(aesAlias(name)) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.KEY_STATE_UNCERTAIN)
        try { return "aes256-gcm:" + os.keySecurity.name + ":" + hex(MessageDigest.getInstance("SHA-256").digest(check)) }
        finally { check.fill(0) }
    }

    public fun encrypt(name: String, aad: ByteArray, plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        desktopKnown(classify(name).second == DATA_SLOT && keyExists(name))
        return os.custody.sealAes(aesAlias(name), aad, plaintext)
    }

    public fun decrypt(name: String, nonce: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        desktopKnown(classify(name).second == DATA_SLOT && keyExists(name))
        return os.custody.openAes(aesAlias(name), aad, nonce, ciphertext)
    }

    override fun toString(): String = "DesktopMasterClientJournalCustody([REDACTED])"

    private fun readSlot(prefix: String, slot: String): String? {
        val bytes = registry.read(namespace(prefix, slot)) ?: return null
        try {
            desktopKnown(bytes.size in 1..MAX_NAME)
            return bytes.decodeToString(throwOnInvalidSequence = true)
        } finally { bytes.fill(0) }
    }

    private fun namespace(prefix: String, slot: String): String =
        "ukkera.desktop-master.client-journal-index$suffix.v2/" + hex(sha256(prefix)) + "/" + slot

    private fun aesAlias(name: String): String = "client-journal$suffix.v2/" + hex(sha256(name))

    /** Journal prefix (through the first UUID segment) and slot of a data key or head name. */
    private fun classify(name: String): Pair<String, String> {
        desktopKnown(name.length in 1..MAX_NAME && name.all { it.code in 0x21..0x7e })
        val segments = name.split('/')
        val index = segments.indexOfFirst { UUID.matches(it) }
        desktopKnown(index > 0)
        val prefix = segments.take(index + 1).joinToString("/") + "/"
        val rest = segments.drop(index + 1)
        return when {
            rest.size == 1 && UUID.matches(rest[0]) -> prefix to DATA_SLOT
            rest.size == 3 && UUID.matches(rest[0]) && rest[1].length == 16 && rest[1].all(::lowerHex) &&
                rest[2].length == 64 && rest[2].all(::lowerHex) -> prefix to "head-" + (rest[1].toLong(16) and 1L)
            else -> selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
        }
    }

    private fun journalPrefix(prefix: String): String {
        desktopKnown(prefix.endsWith("/") && prefix.length in 2..MAX_NAME)
        val segments = prefix.removeSuffix("/").split('/')
        desktopKnown(segments.size >= 2 && UUID.matches(segments.last()) && segments.dropLast(1).none { UUID.matches(it) })
        return prefix
    }

    public companion object {
        private const val DATA_SLOT = "data"
        private const val MAX_NAME = 512
        private val SLOTS = listOf(DATA_SLOT, "head-0", "head-1")
        private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private fun lowerHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'
        private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** Live composition of the running Windows/macOS Master service. Unsupported hosts refuse. */
        public fun open(service: SelfOwnedInstalledIdentityTargetV2): DesktopMasterClientJournalCustody =
            of(DesktopNativeOriginalOs.current(service), SelfOwnedNativeProfileV1.LIVE)

        /** Training slot composition of the running Windows/macOS Master service. */
        public fun openTraining(service: SelfOwnedInstalledIdentityTargetV2, slot: LocalTrainingCompositionSlot):
            DesktopMasterClientJournalCustody = of(DesktopNativeOriginalOs.current(service), SelfOwnedNativeProfileV1.training(slot))

        internal fun of(os: DesktopOriginalOs, profile: SelfOwnedNativeProfileV1): DesktopMasterClientJournalCustody {
            val suffix = profile.slot?.let { "." + it.name.lowercase().replace('_', '-') } ?: ""
            val target = os.observeInstalledTarget()
            val principal = os.principal()
            val files = os.files
            val parent = files.child(files.baseDirectory, "ukkera.desktop-master-client-journals$suffix.v2")
            val existing = files.observe(parent)
            if (existing == null) files.createDirectory(parent)
            val observed = files.observe(parent) ?: selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)
            desktopKnown(observed.kind == DesktopEntryKind.DIRECTORY && observed.owner == files.ownerName)
            val volume = files.openDirectory(parent).let { handle -> try { files.volumeUuid(handle) } finally { handle.close() } }
            val encoded = SelfOwnedInstalledIdentityWireV2.encodeTarget(target.target)
            val digest = try { hex(MessageDigest.getInstance("SHA-256").digest(encoded)) } finally { encoded.fill(0) }
            return DesktopMasterClientJournalCustody(os, os.openSdkIdentityStore(profile), suffix, Path.of(parent), volume,
                digest, target, principal)
        }
    }
}
