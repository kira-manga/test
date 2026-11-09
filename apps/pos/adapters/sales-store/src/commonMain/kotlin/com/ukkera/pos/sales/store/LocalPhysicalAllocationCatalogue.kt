package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateChange
import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId

/** Installed owner selection, not a wire assertion, qualification flag, or capacity receipt. */
public data class LocalPhysicalCatalogueBinding(
    public val scope: BranchScope,
    public val installationId: DomainId,
    public val deviceId: DomainId,
    public val catalogueId: DomainId,
    public val role: LocalPhysicalSpoolRole,
    public val keyId: DomainId,
) {
    override fun toString(): String = "LocalPhysicalCatalogueBindingV1([REDACTED])"
}

public class LocalPhysicalJournalBinding internal constructor(
    public val requirement: LocalPhysicalJournalRequirementV1,
    public val owner: LocalPhysicalCatalogueBinding,
    public val slotIndex: Int,
    public val allocationId: DomainId,
) {
    init {
        require(slotIndex in 0 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES && owner.scope == requirement.scope)
        require(if (owner.role == LocalPhysicalSpoolRole.MASTER)
            allocationId == requirement.masterAllocationId && owner.deviceId == requirement.masterDeviceId
        else allocationId == requirement.clientAllocationId && owner.deviceId == requirement.clientDeviceId)
    }
    public val requirementBytes: StoredBytes get() = StoredBytes(journalRequirementBytes(requirement))
    override fun toString(): String = "LocalPhysicalJournalBindingV1([REDACTED])"
}

/** Decoded only from the protected catalogue or a freshly forced native allocation. Not a permit. */
public class LocalPhysicalJournalReceipt internal constructor(
    public val binding: LocalPhysicalJournalBinding,
    public val nativeIdentity: LocalPhysicalSpoolNativeIdentity,
    public val headerDigest: PayloadDigest,
) {
    internal val plan: PhysicalJournalPlanV1 = PhysicalJournalPlanV1(binding, nativeIdentity)
    public val allocationDigest: PayloadDigest get() = plan.digest
    public val fileBytes: Int get() = LocalPhysicalJournalFormatV1.JOURNAL_BYTES
    /** Exact immutable contribution body, not proof of a preexisting SQL source row. */
    public val sourceCommitment: StoredBytes get() = StoredBytes(journalReceiptBytes(this)).also {
        check(it.size <= LocalPhysicalJournalFormatV1.MAX_HEADER_BYTES)
    }
    /** Prospective revision-one row. Only the kernel-owned real intent transaction may commit it. */
    public val sourceChange: AggregateChange get() = AggregateChange(
        AggregateKey(LocalPhysicalJournalFormatV1.SOURCE_KIND, binding.allocationId), null,
        LocalPhysicalJournalFormatV1.SOURCE_SCHEMA, sourceCommitment)
    override fun toString(): String = "LocalPhysicalJournalReceiptV1([REDACTED])"
}

/**
 * PURE decoded proposal data, NOT an authentic native receipt, lease, certificate or capacity.
 * The typed domain checks these exact bytes ONLY from the kernel-owned transaction's two
 * sourceChanges and rederives the whole requirement. Decoding arbitrary submitted/stored bytes
 * cannot open files, mint a native receipt, prove current custody or arm a physical action.
 */
public class LocalPhysicalJournalSourceProjection private constructor(receipt: LocalPhysicalJournalReceipt) {
    public val requirement: LocalPhysicalJournalRequirementV1 = receipt.binding.requirement
    public val owner: LocalPhysicalCatalogueBinding = receipt.binding.owner
    public val slotIndex: Int = receipt.binding.slotIndex
    public val allocationId: DomainId = receipt.binding.allocationId
    public val nativeIdentity: LocalPhysicalSpoolNativeIdentity = receipt.nativeIdentity
    public val headerDigest: PayloadDigest = receipt.headerDigest
    public val allocationDigest: PayloadDigest = receipt.allocationDigest
    public val sourceCommitment: StoredBytes = receipt.sourceCommitment

    public companion object {
        /** Bounded strict canonical decoding only; malformed/extra/old-source shapes fail. */
        public fun decode(change: AggregateChange): LocalPhysicalJournalSourceProjection {
            require(change.key.kind == LocalPhysicalJournalFormatV1.SOURCE_KIND &&
                change.schema == LocalPhysicalJournalFormatV1.SOURCE_SCHEMA && change.expectedRevision == null &&
                change.payload.size in 1..LocalPhysicalJournalFormatV1.MAX_HEADER_BYTES)
            val receipt = change.payload.copyBytes().withSpoolBytes { bytes ->
                val outer = journalFields(bytes, 4)
                val allocation = journalFields(outer[1], 5)
                journalReadReceipt(bytes, journalReadOwner(allocation[2]))
            }
            require(receipt.binding.allocationId == change.key.id)
            val expectedProfile = if (receipt.binding.owner.role == LocalPhysicalSpoolRole.MASTER)
                receipt.binding.requirement.masterStorageProfile else receipt.binding.requirement.clientStorageProfile
            require(receipt.nativeIdentity.profile == expectedProfile)
            return LocalPhysicalJournalSourceProjection(receipt)
        }
    }

    override fun toString(): String = "LocalPhysicalJournalSourceProjectionV1([REDACTED])"
}

public sealed interface LocalPhysicalCatalogueOpen {
    public class Opened internal constructor(public val catalogue: LocalPhysicalAllocationCatalogue) : LocalPhysicalCatalogueOpen
    public data object Busy : LocalPhysicalCatalogueOpen
    public data object Held : LocalPhysicalCatalogueOpen
}

public sealed interface LocalPhysicalJournalOpen {
    public class Opened internal constructor(public val journal: LocalPhysicalJournal) : LocalPhysicalJournalOpen
    public data object Busy : LocalPhysicalJournalOpen
    public data object Held : LocalPhysicalJournalOpen
    public data object Exhausted : LocalPhysicalJournalOpen
    public data object IdentityConflict : LocalPhysicalJournalOpen
}

internal class PhysicalCatalogueRoot(val owner: LocalPhysicalCatalogueBinding,
    val anchor: LocalPhysicalSpoolNativeIdentity, val catalogue: LocalPhysicalSpoolNativeIdentity,
    val headerDigest: PayloadDigest) {
    val header: ByteArray = catalogueHeader(owner, anchor, catalogue)
    val planDigest: PayloadDigest = sha256(header)
    val anchorBinding: PayloadDigest = catalogueAnchorBinding(owner, anchor)
    val bytes: ByteArray = journalFrame(listOf(journalText("physical.journal-first/catalogue-root-v1"),
        journalOwnerBytes(owner), journalNativeBytes(anchor), journalNativeBytes(catalogue),
        journalText(headerDigest.value), header), LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES)
}

/**
 * Actual fixed native catalogue + separately authenticated expected-receipt anchor. No SQL writes,
 * scans/adoption of orphan journals, key generation, reset or reclaim. Blocking worker-only API.
 *
 * All 128 catalogue entries and the anchor are physically initialized before publication. Each
 * journal's one fixed file is allocated before its immutable catalogue entry is published. A cut
 * between file initialization and entry publication leaves an unavailable orphan, not vacancy.
 * The separately installed private directories/key and native supervisor remain real obligations.
 * Valid joint rollback is NOT detected by AEAD; authorized/uncertain restore must quarantine.
 */
public class LocalPhysicalAllocationCatalogue internal constructor(
    public val binding: LocalPhysicalCatalogueBinding,
    private val root: PhysicalCatalogueRoot,
    private val anchor: PhysicalNativeOwnedFileV1,
    private val catalogue: PhysicalNativeOwnedFileV1,
    private val nativeFiles: PhysicalJournalNativeFilesV1,
) {
    /** Cached actual native observation, safe to read without IO; not current-custody evidence. */
    public val storageProfile: LocalPhysicalSpoolStorageProfileV1 = root.catalogue.profile
    private var expectedFrames: List<PayloadDigest>? = null
    private val usedNonces: MutableSet<String> = mutableSetOf()

    /** Fresh allocation only; a repeated ID must use explicit protected-catalogue recovery. */
    public fun allocate(requirement: LocalPhysicalJournalRequirementV1,
        domain: LocalPhysicalJournalDomain): LocalPhysicalJournalOpen {
        if (!validJournalInput {
            require(domain.domainProfile == requirement.domainProfile && domain.command == requirement.command &&
                requirement.scope == binding.scope && binding.deviceId ==
                (if (binding.role == LocalPhysicalSpoolRole.MASTER) requirement.masterDeviceId else requirement.clientDeviceId))
            domain.validateRequirement(requirement)
        })
            return LocalPhysicalJournalOpen.IdentityConflict
        val allocation = if (binding.role == LocalPhysicalSpoolRole.MASTER) requirement.masterAllocationId else requirement.clientAllocationId
        var owned: PhysicalNativeOwnedFileV1? = null
        var transferred = false
        var answer: LocalPhysicalJournalOpen = LocalPhysicalJournalOpen.Held
        try {
            answer = access {
                val entries = readEntries()
                if (entries.any { it?.binding?.allocationId == allocation }) return@access LocalPhysicalJournalOpen.IdentityConflict
                val index = entries.indexOfFirst { it == null }
                if (index < 0) return@access LocalPhysicalJournalOpen.Exhausted
                val journalBinding = LocalPhysicalJournalBinding(requirement, binding, index, allocation)
                when (val opened = nativeFiles.open(physicalJournalNativeSpec(binding, PhysicalNativeLayoutV1.JOURNAL, index), null, true)) {
                    PhysicalNativeOpenV1.Busy -> return@access LocalPhysicalJournalOpen.Busy
                    PhysicalNativeOpenV1.Held -> return@access LocalPhysicalJournalOpen.Held
                    is PhysicalNativeOpenV1.Opened -> owned = opened.owned
                }
                val native = owned ?: error("physical.catalogue.native")
                val journal = initializePhysicalJournal(this, journalBinding, native, domain)
                val cellIndex = index + 1
                val bytes = journalReceiptBytes(journal.receipt)
                try {
                    journalSeal(catalogue.cipher, "catalogue", root.planDigest, cellIndex,
                        LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES, root.headerDigest, bytes).withSpoolBytes { encrypted ->
                        check(usedNonces.add(physicalSpoolNonceIdentity(encrypted)))
                        val digest = sha256(encrypted)
                        catalogue.file.overwrite(cellIndex * LocalPhysicalJournalFormatV1.CELL_BYTES, encrypted)
                        catalogue.file.force()
                        val previous = expectedFrames ?: error("physical.catalogue.unopened")
                        expectedFrames = previous.mapIndexed { i, old -> if (i == cellIndex) digest else old }
                    }
                    val retained = readEntries()[index] ?: error("physical.catalogue.publication")
                    check(journalReceiptBytes(retained).contentEquals(bytes))
                    journal.markPublished()
                    LocalPhysicalJournalOpen.Opened(journal)
                } finally { bytes.fill(0) }
            }
            transferred = answer is LocalPhysicalJournalOpen.Opened
        } catch (_: Exception) {
            answer = LocalPhysicalJournalOpen.Held
        } finally {
            if (!transferred && closeCatalogueNativeFile(owned?.file) == LocalPhysicalSpoolClose.HELD) answer = LocalPhysicalJournalOpen.Held
        }
        return answer
    }

    /** Exact original receipt comes from the AEAD catalogue; never from the candidate journal. */
    public fun openExisting(allocationId: DomainId, domain: LocalPhysicalJournalDomain): LocalPhysicalJournalOpen {
        var owned: PhysicalNativeOwnedFileV1? = null
        var transferred = false
        var answer: LocalPhysicalJournalOpen = LocalPhysicalJournalOpen.Held
        try {
            answer = access {
                val receipt = readEntries().singleOrNull { it?.binding?.allocationId == allocationId }
                    ?: return@access LocalPhysicalJournalOpen.Held
                domain.validateRequirement(receipt.binding.requirement)
                when (val opened = nativeFiles.open(physicalJournalNativeSpec(binding, PhysicalNativeLayoutV1.JOURNAL,
                    receipt.binding.slotIndex), receipt.nativeIdentity, false)) {
                    PhysicalNativeOpenV1.Busy -> return@access LocalPhysicalJournalOpen.Busy
                    PhysicalNativeOpenV1.Held -> return@access LocalPhysicalJournalOpen.Held
                    is PhysicalNativeOpenV1.Opened -> owned = opened.owned
                }
                val journal = reopenPhysicalJournal(this, receipt, owned ?: error("physical.catalogue.native"), domain)
                journal.markPublished()
                LocalPhysicalJournalOpen.Opened(journal)
            }
            transferred = answer is LocalPhysicalJournalOpen.Opened
        } catch (_: Exception) {
            answer = LocalPhysicalJournalOpen.Held
        } finally {
            if (!transferred && closeCatalogueNativeFile(owned?.file) == LocalPhysicalSpoolClose.HELD) answer = LocalPhysicalJournalOpen.Held
        }
        return answer
    }

    /** No eviction: native close retains descriptors/process claims while invocation leases exist. */
    public fun close(): LocalPhysicalSpoolClose {
        val first = closeCatalogueNativeFile(catalogue.file)
        val second = closeCatalogueNativeFile(anchor.file)
        return if (first == LocalPhysicalSpoolClose.CLOSED && second == LocalPhysicalSpoolClose.CLOSED)
            LocalPhysicalSpoolClose.CLOSED else LocalPhysicalSpoolClose.HELD
    }

    internal fun capture(onRetirement: () -> Unit): List<PhysicalSpoolLease>? {
        // These two file.capture calls return single leases. Allocate both reference slots
        // before either capture; no collection flattening or list growth can lose a lease.
        val leases = ArrayList<PhysicalSpoolLease>(2)
        var transferred = false
        try {
            val result = anchor.file.access {
                verifyAnchor()
                when (val inner = catalogue.file.access {
                    readEntries()
                    catalogue.file.force()
                    verifyCatalogueHeader()
                    leases += catalogue.file.capture(onRetirement) ?: error("physical.catalogue.lease")
                    leases += anchor.file.capture(onRetirement) ?: error("physical.catalogue.lease")
                }) {
                    is PhysicalSpoolAccess.Value -> Unit
                    else -> error("physical.catalogue.lease")
                }
                verifyAnchor()
            }
            if (result !is PhysicalSpoolAccess.Value) return null
            val captured = leases.toList()
            transferred = true
            return captured
        } finally {
            if (!transferred) {
                try { onRetirement() } finally {
                    check(releasePhysicalSpoolLeases(leases)) { "physical.catalogue.lease_cleanup" }
                }
            }
        }
    }

    /** Authenticated bounded existing selectors for the installed restart pump; never a UI read. */
    internal fun retainedAllocationSelectors(): List<DomainId>? {
        val outer = anchor.file.access {
            verifyAnchor()
            val inner = catalogue.file.access {
                val ids = readEntries().filterNotNull().map { it.binding.allocationId }
                catalogue.file.force()
                verifyCatalogueHeader()
                ids
            }
            verifyAnchor()
            inner
        }
        if (outer !is PhysicalSpoolAccess.Value) return null
        val inner = outer.value
        return if (inner is PhysicalSpoolAccess.Value) inner.value.toList() else null
    }

    /** Service-owned exact lookup only. No journal scan/adoption, allocation, or learned receipt. */
    internal fun readExpectedReceipt(allocationId: DomainId): LocalPhysicalJournalReceipt? {
        val outer = anchor.file.access {
            verifyAnchor()
            val inner = catalogue.file.access {
                val receipt = readEntries().filterNotNull().singleOrNull { it.binding.allocationId == allocationId }
                catalogue.file.force()
                verifyCatalogueHeader()
                receipt
            }
            verifyAnchor()
            inner
        }
        if (outer !is PhysicalSpoolAccess.Value) return null
        return when (val inner = outer.value) {
            is PhysicalSpoolAccess.Value -> inner.value
            else -> null
        }
    }

    internal fun establish() {
        val result = anchor.file.access {
            verifyAnchor()
            when (val inner = catalogue.file.access {
                readEntries()
                catalogue.file.force()
                readEntries()
                verifyCatalogueHeader()
            }) {
                is PhysicalSpoolAccess.Value -> Unit
                else -> error("physical.catalogue.open")
            }
            anchor.file.force()
            verifyAnchor()
        }
        check(result is PhysicalSpoolAccess.Value)
    }

    private fun access(block: () -> LocalPhysicalJournalOpen): LocalPhysicalJournalOpen = when (val outer = anchor.file.access {
        verifyAnchor()
        val result = catalogue.file.access { val value = block(); verifyCatalogueHeader(); value }
        verifyAnchor()
        result
    }) {
        is PhysicalSpoolAccess.Value -> when (val inner = outer.value) {
            is PhysicalSpoolAccess.Value -> inner.value
            PhysicalSpoolAccess.Busy -> LocalPhysicalJournalOpen.Busy
            PhysicalSpoolAccess.Held -> LocalPhysicalJournalOpen.Held
        }
        PhysicalSpoolAccess.Busy -> LocalPhysicalJournalOpen.Busy
        PhysicalSpoolAccess.Held -> LocalPhysicalJournalOpen.Held
    }

    private fun verifyAnchor() {
        anchor.file.read(0, LocalPhysicalJournalFormatV1.CELL_BYTES).withSpoolBytes { encrypted ->
            val body = journalOpen(anchor.cipher, "catalogue-anchor", root.anchorBinding, 0,
                LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES, null, encrypted) ?: error("physical.catalogue.anchor")
            body.withSpoolBytes { check(it.contentEquals(root.bytes)) }
        }
    }

    private fun verifyCatalogueHeader() {
        catalogue.file.read(0, LocalPhysicalJournalFormatV1.CELL_BYTES).withSpoolBytes { encrypted ->
            check(sha256(encrypted) == root.headerDigest)
            val body = journalOpen(catalogue.cipher, "catalogue", root.planDigest, 0,
                LocalPhysicalJournalFormatV1.MAX_HEADER_BYTES, null, encrypted) ?: error("physical.catalogue.header")
            body.withSpoolBytes { check(it.contentEquals(root.header)) }
        }
    }

    private fun readEntries(): List<LocalPhysicalJournalReceipt?> {
        verifyCatalogueHeader()
        val digests = mutableListOf(root.headerDigest)
        val nonces = mutableSetOf<String>()
        catalogue.file.read(0, LocalPhysicalJournalFormatV1.CELL_BYTES).withSpoolBytes { nonces.add(physicalSpoolNonceIdentity(it)) }
        val entries = List(LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES) { slot ->
            val index = slot + 1
            catalogue.file.read(index * LocalPhysicalJournalFormatV1.CELL_BYTES, LocalPhysicalJournalFormatV1.CELL_BYTES).withSpoolBytes { encrypted ->
                val digest = sha256(encrypted)
                expectedFrames?.let { check(it[index] == digest) }
                digests += digest
                check(nonces.add(physicalSpoolNonceIdentity(encrypted)))
                val bytes = journalOpen(catalogue.cipher, "catalogue", root.planDigest, index,
                    LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES, root.headerDigest, encrypted)
                bytes?.withSpoolBytes { journalReadReceipt(it, binding).also { receipt -> check(receipt.binding.slotIndex == slot) } }
            }
        }
        check(entries.filterNotNull().map { it.binding.allocationId }.distinct().size == entries.count { it != null })
        if (expectedFrames == null) expectedFrames = digests.toList()
        usedNonces.addAll(nonces)
        return entries
    }

    override fun toString(): String = "LocalPhysicalAllocationCatalogueV1([REDACTED])"
}

internal fun createPhysicalCatalogue(owner: LocalPhysicalCatalogueBinding,
    files: PhysicalJournalNativeFilesV1): LocalPhysicalCatalogueOpen = acquirePhysicalCatalogue(owner, files, true)

internal fun openPhysicalCatalogue(owner: LocalPhysicalCatalogueBinding,
    files: PhysicalJournalNativeFilesV1): LocalPhysicalCatalogueOpen = acquirePhysicalCatalogue(owner, files, false)

private fun acquirePhysicalCatalogue(owner: LocalPhysicalCatalogueBinding, files: PhysicalJournalNativeFilesV1,
    create: Boolean): LocalPhysicalCatalogueOpen {
    var anchor: PhysicalNativeOwnedFileV1? = null
    var catalogue: PhysicalNativeOwnedFileV1? = null
    var transferred = false
    var result: LocalPhysicalCatalogueOpen = LocalPhysicalCatalogueOpen.Held
    try {
        // Do not return from this function before cleanup has had the final word. A busy
        // second open followed by an uncertain first-file close must be HELD, not BUSY.
        result = run acquire@ {
            when (val opened = files.open(physicalJournalNativeSpec(owner, PhysicalNativeLayoutV1.ANCHOR), null, create)) {
                PhysicalNativeOpenV1.Busy -> return@acquire LocalPhysicalCatalogueOpen.Busy
                PhysicalNativeOpenV1.Held -> return@acquire LocalPhysicalCatalogueOpen.Held
                is PhysicalNativeOpenV1.Opened -> anchor = opened.owned
            }
            val a = anchor ?: error("physical.catalogue.anchor")
            var root: PhysicalCatalogueRoot? = null
            if (!create) {
                val decoded = a.file.access {
                    a.file.read(0, LocalPhysicalJournalFormatV1.CELL_BYTES).withSpoolBytes { encrypted ->
                        val bytes = journalOpen(a.cipher, "catalogue-anchor", catalogueAnchorBinding(owner, a.native), 0,
                            LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES, null, encrypted) ?: error("physical.catalogue.anchor")
                        bytes.withSpoolBytes { readCatalogueRoot(owner, a.native, it) }
                    }
                }
                root = (decoded as? PhysicalSpoolAccess.Value)?.value ?: error("physical.catalogue.anchor")
            }
            when (val opened = files.open(physicalJournalNativeSpec(owner, PhysicalNativeLayoutV1.CATALOGUE), root?.catalogue, create)) {
                PhysicalNativeOpenV1.Busy -> return@acquire LocalPhysicalCatalogueOpen.Busy
                PhysicalNativeOpenV1.Held -> return@acquire LocalPhysicalCatalogueOpen.Held
                is PhysicalNativeOpenV1.Opened -> catalogue = opened.owned
            }
            val c = catalogue ?: error("physical.catalogue.file")
            check(c.native.cryptographyIdentity == a.native.cryptographyIdentity && c.native.profile == a.native.profile)
            if (create) root = initializeCatalogue(owner, a, c)
            val value = LocalPhysicalAllocationCatalogue(owner, root ?: error("physical.catalogue.root"), a, c, files)
            value.establish()
            LocalPhysicalCatalogueOpen.Opened(value)
        }
        transferred = result is LocalPhysicalCatalogueOpen.Opened
    } catch (_: Exception) {
        result = LocalPhysicalCatalogueOpen.Held
    } finally {
        if (!transferred) {
            val c = closeCatalogueNativeFile(catalogue?.file)
            val a = closeCatalogueNativeFile(anchor?.file)
            if (c == LocalPhysicalSpoolClose.HELD || a == LocalPhysicalSpoolClose.HELD) result = LocalPhysicalCatalogueOpen.Held
        }
    }
    return result
}

private fun initializeCatalogue(owner: LocalPhysicalCatalogueBinding, anchor: PhysicalNativeOwnedFileV1,
    catalogue: PhysicalNativeOwnedFileV1): PhysicalCatalogueRoot {
    val header = catalogueHeader(owner, anchor.native, catalogue.native)
    val digest = sha256(header)
    val first = journalSeal(catalogue.cipher, "catalogue", digest, 0, LocalPhysicalJournalFormatV1.MAX_HEADER_BYTES, null, header)
    val root = PhysicalCatalogueRoot(owner, anchor.native, catalogue.native, sha256(first))
    val all = ByteArray(LocalPhysicalJournalFormatV1.CATALOGUE_BYTES)
    try {
        first.copyInto(all)
        val nonces = mutableSetOf(physicalSpoolNonceIdentity(first))
        for (index in 1..LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES) {
            journalSeal(catalogue.cipher, "catalogue", digest, index, LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES,
                root.headerDigest, null).withSpoolBytes { encrypted ->
                check(nonces.add(physicalSpoolNonceIdentity(encrypted)))
                encrypted.copyInto(all, index * LocalPhysicalJournalFormatV1.CELL_BYTES)
            }
        }
        check(catalogue.file.access { catalogue.file.initialize(all) } is PhysicalSpoolAccess.Value)
        journalSeal(anchor.cipher, "catalogue-anchor", root.anchorBinding, 0,
            LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES, null, root.bytes).withSpoolBytes { bytes ->
            check(nonces.add(physicalSpoolNonceIdentity(bytes)))
            check(anchor.file.access { anchor.file.initialize(bytes) } is PhysicalSpoolAccess.Value)
        }
        return root
    } finally { header.fill(0); first.fill(0); all.fill(0) }
}

private fun catalogueHeader(owner: LocalPhysicalCatalogueBinding, anchor: LocalPhysicalSpoolNativeIdentity,
    catalogue: LocalPhysicalSpoolNativeIdentity): ByteArray = journalFrame(listOf(
    journalText("physical.journal-first/catalogue-header-v1"), journalOwnerBytes(owner),
    journalNativeBytes(anchor), journalNativeBytes(catalogue), journalGeometryBytes(),
))

internal fun catalogueAnchorBinding(owner: LocalPhysicalCatalogueBinding,
    native: LocalPhysicalSpoolNativeIdentity): PayloadDigest = journalFrame(listOf(
    journalText("physical.journal-first/installed-anchor-v1"), journalOwnerBytes(owner), journalNativeBytes(native),
)).withSpoolBytes(::sha256)

internal fun readCatalogueRoot(owner: LocalPhysicalCatalogueBinding, nativeAnchor: LocalPhysicalSpoolNativeIdentity,
    bytes: ByteArray): PhysicalCatalogueRoot {
    val fields = journalFields(bytes, 6)
    var root: PhysicalCatalogueRoot? = null
    var transferred = false
    try {
        check(journalString(fields[0]) == "physical.journal-first/catalogue-root-v1" &&
            journalOwnerBytes(owner).withSpoolBytes { fields[1].contentEquals(it) })
        check(journalReadNative(fields[2]) == nativeAnchor)
        val value = PhysicalCatalogueRoot(owner, nativeAnchor, journalReadNative(fields[3]), PayloadDigest(journalString(fields[4])))
        root = value
        check(fields[5].contentEquals(value.header) && bytes.contentEquals(value.bytes))
        transferred = true
        return value
    } finally {
        fields.forEach { it.fill(0) }
        if (!transferred) {
            root?.header?.fill(0)
            root?.bytes?.fill(0)
        }
    }
}

internal inline fun validJournalInput(block: () -> Unit): Boolean = try { block(); true }
    catch (_: IllegalArgumentException) { false }
    catch (_: IllegalStateException) { false }

private fun closeCatalogueNativeFile(file: PhysicalSpoolFile?): LocalPhysicalSpoolClose =
    try { file?.close() ?: LocalPhysicalSpoolClose.CLOSED } catch (_: Exception) { LocalPhysicalSpoolClose.HELD }
