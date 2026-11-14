package com.ukkera.pos.security

/**
 * Windows/macOS OS-private directory owned by the current principal and held under an exclusive
 * process lock for its whole lifetime. Replacement is write-temporary, flush, atomic rename and
 * directory flush. [namespace] reports the ACTUAL OS identity (owner SID/uid, volume, directory and
 * lock file IDs/inodes). [assertContinuity] refuses when the directory or lock identity changed.
 */
internal interface DesktopOwnedDirectory : AutoCloseable {
    val createdThisLifetime: Boolean
    fun read(name: String, maximum: Int): ByteArray?
    fun replace(name: String, bytes: ByteArray)
    fun names(): Set<String>
    fun namespace(): SelfOwnedRegistryNamespaceV2
    fun assertContinuity()
}

/** Opens (or, only with [create], creates) the exact OS-private directory for one profile role. */
internal fun interface DesktopOwnedDirectoryFactory {
    fun open(profile: SelfOwnedNativeProfileV1, role: String, create: Boolean): DesktopOwnedDirectory
}

/**
 * V2 provisioner registry byte custody: bootstrap, company bootstrap (written together with its
 * embedded original in ONE atomic record), registry envelope and the four company ledgers. All
 * cryptography and canonical checks stay in the shared core; this store keeps exact bytes only.
 */
internal class DesktopProvisionerRegistryFilesV2(
    private val profile: SelfOwnedNativeProfileV1,
    private val directories: DesktopOwnedDirectoryFactory,
) : SelfOwnedProvisionerRegistryStoreV2 {
    private var directory: DesktopOwnedDirectory? = null
    private var expected: SelfOwnedRegistryNamespaceV2? = null

    override fun openNative() = open(create = true)
    override fun openExistingNative() = open(create = false)

    private fun open(create: Boolean) {
        check(directory == null)
        val opened = directories.open(profile, ROLE, create)
        directory = opened
        try {
            known(opened.names().all { it in NAMES })
            expected = opened.namespace()
            // A created namespace is empty; an existing one never lacks its bootstrap unless empty.
            if (opened.createdThisLifetime) known(opened.names().isEmpty())
            val record = readRecord()
            if (record != null) known(record.second.namespace == expected)
        } catch (failure: Throwable) {
            directory = null; expected = null
            runCatching { opened.close() }
            throw failure
        }
    }

    override fun assertContinuity() {
        val actual = requireOpen()
        actual.assertContinuity()
        known(actual.namespace() == expected)
    }

    override fun closeNative() {
        val actual = directory ?: return
        directory = null
        actual.close()
    }

    override fun bootstrapRecordV2(): SelfOwnedBootstrapV2? = readRecord()?.second

    override fun companyBootstrapRecordV2(): LocalCompanyAuthorityBootstrapV2? = readRecord()?.first

    override fun namespaceIdentityV2(): SelfOwnedRegistryNamespaceV2 { assertContinuity(); return requireNotNull(expected) }

    override fun retainBootstrapV2(next: SelfOwnedBootstrapV2) {
        assertContinuity()
        known(next.namespace == expected && requireOpen().read(COMPANY, MAX_COMPANY) == null)
        replaceVerified(BOOTSTRAP, SelfOwnedNativeProvisionerWireV2.encodeBootstrap(next), MAX_BOOTSTRAP)
    }

    override fun retainCompanyBootstrapV2(next: LocalCompanyAuthorityBootstrapV2) {
        assertContinuity()
        known(next.original.namespace == expected)
        // The company record embeds its original; the separate bootstrap file is retired once a
        // company record exists, so the two can never disagree after a torn write.
        replaceVerified(COMPANY, SelfOwnedNativeProvisionerWireV2.encodeCompanyBootstrap(next), MAX_COMPANY)
    }

    override fun registryEnvelopeBytes(): ByteArray? { assertContinuity(); return requireOpen().read(ENVELOPE, MAX_ENVELOPE) }

    override fun replaceRegistryEnvelope(bytes: ByteArray) {
        assertContinuity()
        replaceVerified(ENVELOPE, bytes, MAX_ENVELOPE)
    }

    override fun companyLedgerEnvelopeBytes(kind: SelfOwnedCompanyLedgerKindV2): ByteArray? {
        assertContinuity()
        return requireOpen().read(ledgerName(kind), SelfOwnedNativeCompanyLedgerProfileV2.MAXIMUM_LEDGER_ENVELOPE_BYTES)
    }

    override fun replaceCompanyLedgerEnvelope(kind: SelfOwnedCompanyLedgerKindV2, bytes: ByteArray) {
        assertContinuity()
        replaceVerified(ledgerName(kind), bytes, SelfOwnedNativeCompanyLedgerProfileV2.MAXIMUM_LEDGER_ENVELOPE_BYTES)
    }

    override fun toString(): String = "DesktopProvisionerRegistryFilesV2([REDACTED])"

    /** (company, effective original): the company record's embedded original wins when present. */
    private fun readRecord(): Pair<LocalCompanyAuthorityBootstrapV2?, SelfOwnedBootstrapV2>? {
        val actual = requireOpen()
        actual.read(COMPANY, MAX_COMPANY)?.let { bytes ->
            val company = decoded(SelfOwnedNativeProvisionerWireV2.decodeCompanyBootstrap(bytes))
            return company to company.original
        }
        val bytes = actual.read(BOOTSTRAP, MAX_BOOTSTRAP) ?: return null
        return null to decoded(SelfOwnedNativeProvisionerWireV2.decodeBootstrap(bytes))
    }

    private fun replaceVerified(name: String, bytes: ByteArray, maximum: Int) {
        known(bytes.size in 1..maximum)
        val actual = requireOpen()
        actual.replace(name, bytes)
        val read = actual.read(name, maximum) ?: uncertain()
        try { known(read.contentEquals(bytes)) } finally { read.fill(0) }
        assertContinuity()
    }

    private fun requireOpen(): DesktopOwnedDirectory = directory ?: uncertain()
    private fun <T> decoded(result: SelfOwnedInstalledIdentityResultV2<T>): T = when (result) {
        is SelfOwnedInstalledIdentityResultV2.Data -> result.value
        is SelfOwnedInstalledIdentityResultV2.Refused -> uncertain()
    }
    private fun known(value: Boolean) { if (!value) uncertain() }
    private fun uncertain(): Nothing = selfOwnedFail(SelfOwnedInstallationFailureV1.STATE_UNCERTAIN)

    internal companion object {
        const val ROLE: String = "provisioner"
        const val BOOTSTRAP: String = "bootstrap.v2"
        const val COMPANY: String = "company.v2"
        const val ENVELOPE: String = "registry.v2"
        const val MAX_BOOTSTRAP: Int = 64 * 1024
        const val MAX_COMPANY: Int = 128 * 1024
        val MAX_ENVELOPE: Int = SelfOwnedInstallationProfileV1.MAXIMUM_REGISTRY_BYTES + 4_096
        fun ledgerName(kind: SelfOwnedCompanyLedgerKindV2): String = "ledger-${kind.name.lowercase()}.v2"
        val NAMES: Set<String> = setOf(BOOTSTRAP, COMPANY, ENVELOPE) + SelfOwnedCompanyLedgerKindV2.entries.map(::ledgerName)
    }
}
