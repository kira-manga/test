package com.ukkera.pos.sales.store

import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.PhysicalRecordingSourceProfileV1
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId

/** Journal retention bounds, NOT reserved SQLite growth or a hardware zero-loss promise. */
public object LocalPhysicalJournalFormatV1 {
    public const val PROFILE: String = "physical.journal-first/v1"
    public const val SOURCE_KIND: String = PhysicalRecordingSourceProfileV1.KIND
    public const val SOURCE_SCHEMA: String = PhysicalRecordingSourceProfileV1.SCHEMA
    public const val CELL_BYTES: Int = 32_768
    public const val JOURNAL_BYTES: Int = 262_144
    public const val CATALOGUE_ENTRIES: Int = 128
    public const val CATALOGUE_BYTES: Int = 4_227_072
    public const val ANCHOR_BYTES: Int = 32_768
    public const val MAX_REQUIREMENT_BYTES: Int = 8_192
    public const val MAX_HEADER_BYTES: Int = 16_384
    public const val MAX_START_BYTES: Int = 16_384
    public const val MAX_CONSUMED_BYTES: Int = 4_096
    public const val MAX_ORIGINAL_BYTES: Int = 8_192
    public const val MAX_RESOLUTION_BYTES: Int = 16_384
    public const val MAX_ACK_BYTES: Int = 4_096
    public const val MAX_APPLICATION_ACK_BYTES: Int = 16_384
    public const val MAX_CATALOGUE_ENTRY_BYTES: Int = 24_576
}

internal enum class PhysicalJournalCellV1(val index: Int, val maximumBody: Int) {
    HEADER(0, LocalPhysicalJournalFormatV1.MAX_HEADER_BYTES),
    TRUE_START_CERT(1, LocalPhysicalJournalFormatV1.MAX_START_BYTES),
    ACTION_MAY_HAVE_STARTED(2, LocalPhysicalJournalFormatV1.MAX_CONSUMED_BYTES),
    ORIGINAL(3, LocalPhysicalJournalFormatV1.MAX_ORIGINAL_BYTES),
    LINKED_RESOLUTION(4, LocalPhysicalJournalFormatV1.MAX_RESOLUTION_BYTES),
    MASTER_RAW_ACK(5, LocalPhysicalJournalFormatV1.MAX_ACK_BYTES),
    SQL_IMPORT_ACK(6, LocalPhysicalJournalFormatV1.MAX_ACK_BYTES),
    SQL_APPLICATION_ACK(7, LocalPhysicalJournalFormatV1.MAX_APPLICATION_ACK_BYTES);

    val offset: Int get() = index * LocalPhysicalJournalFormatV1.CELL_BYTES
}

/** Closed native byte geometries. A caller cannot supply an arbitrary writable range. */
internal enum class PhysicalNativeLayoutV1(val bytes: Int, val filename: String) {
    LEGACY(LocalPhysicalSpoolFormatV1.FILE_BYTES, "allocation.native-v1.enc"),
    JOURNAL(LocalPhysicalJournalFormatV1.JOURNAL_BYTES, "physical.journal-first-v1.enc"),
    CATALOGUE(LocalPhysicalJournalFormatV1.CATALOGUE_BYTES, "physical.catalogue-v1.enc"),
    ANCHOR(LocalPhysicalJournalFormatV1.ANCHOR_BYTES, "physical.catalogue-anchor-v1.enc");

    fun range(offset: Int, size: Int, writable: Boolean = false) {
        if (this == LEGACY) return physicalSpoolRange(offset, size, writable)
        check(size == LocalPhysicalJournalFormatV1.CELL_BYTES && offset >= 0 &&
            offset % LocalPhysicalJournalFormatV1.CELL_BYTES == 0 && offset.toLong() + size <= bytes.toLong())
        if (writable) check(this != ANCHOR && offset != 0)
    }
}

internal class PhysicalNativeFileSpecV1(
    val installationId: DomainId,
    val keyId: DomainId,
    val segments: List<String>,
    val layout: PhysicalNativeLayoutV1,
) {
    init {
        check(segments.size in 1..12 && segments.all { it.matches(Regex("[a-zA-Z0-9_-]{1,64}")) })
    }
}

internal fun physicalJournalNativeSpec(owner: LocalPhysicalCatalogueBinding,
    layout: PhysicalNativeLayoutV1, slot: Int? = null): PhysicalNativeFileSpecV1 {
    check(layout != PhysicalNativeLayoutV1.LEGACY)
    check((layout == PhysicalNativeLayoutV1.JOURNAL) == (slot != null))
    if (slot != null) check(slot in 0 until LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES)
    val leaf = when (layout) {
        PhysicalNativeLayoutV1.ANCHOR -> "anchor"
        PhysicalNativeLayoutV1.CATALOGUE -> "catalogue"
        PhysicalNativeLayoutV1.JOURNAL -> "slot-${slot.toString().padStart(3, '0')}"
        PhysicalNativeLayoutV1.LEGACY -> error("physical.journal.layout")
    }
    return PhysicalNativeFileSpecV1(owner.installationId, owner.keyId,
        listOf("physical-journal-v1", owner.installationId.value, owner.catalogueId.value, owner.role.name.lowercase(), leaf), layout)
}

internal class PhysicalNativeOwnedFileV1(
    val native: LocalPhysicalSpoolNativeIdentity,
    val file: PhysicalSpoolFile,
    val cipher: PhysicalSpoolCipher,
)

internal sealed interface PhysicalNativeOpenV1 {
    class Opened(val owned: PhysicalNativeOwnedFileV1) : PhysicalNativeOpenV1
    data object Busy : PhysicalNativeOpenV1
    data object Held : PhysicalNativeOpenV1
}

/** Implemented only by the actual Android/JVM native factories in this module. */
internal fun interface PhysicalJournalNativeFilesV1 {
    fun open(spec: PhysicalNativeFileSpecV1, expected: LocalPhysicalSpoolNativeIdentity?, create: Boolean): PhysicalNativeOpenV1
}

internal class PhysicalJournalPlanV1(val binding: LocalPhysicalJournalBinding,
    val native: LocalPhysicalSpoolNativeIdentity) {
    val bindingBytes: ByteArray = journalBindingBytes(binding)
    val bindingDigest: PayloadDigest = sha256(bindingBytes)
    val bytes: ByteArray = journalFrame(listOf(journalText("physical.journal-first/header-v1"), bindingBytes,
        journalNativeBytes(native), journalGeometryBytes()), LocalPhysicalJournalFormatV1.MAX_HEADER_BYTES)
    val digest: PayloadDigest = sha256(bytes)
}

internal fun journalGeometryBytes(): ByteArray = journalFrame(listOf(journalText(LocalPhysicalJournalFormatV1.PROFILE),
    journalText(LocalPhysicalJournalFormatV1.JOURNAL_BYTES.toString()),
    journalFrame(PhysicalJournalCellV1.entries.map { cell -> journalFrame(listOf(journalText(cell.name),
        journalText(cell.index.toString()), journalText(cell.offset.toString()),
        journalText(LocalPhysicalJournalFormatV1.CELL_BYTES.toString()), journalText(cell.maximumBody.toString()))) })))

internal fun journalOwnerBytes(owner: LocalPhysicalCatalogueBinding): ByteArray = journalFrame(listOf(
    "physical.journal-first/catalogue-owner-v1", owner.scope.organizationId.value, owner.scope.companyId.value,
    owner.scope.branchId.value, owner.installationId.value, owner.deviceId.value, owner.catalogueId.value,
    owner.role.name, owner.keyId.value, LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES.toString(),
    LocalPhysicalJournalFormatV1.CATALOGUE_BYTES.toString(), LocalPhysicalJournalFormatV1.ANCHOR_BYTES.toString(),
).map(::journalText))

internal fun journalReadOwner(bytes: ByteArray): LocalPhysicalCatalogueBinding {
    val fields = journalFields(bytes, 12).map(::journalString)
    check(fields[0] == "physical.journal-first/catalogue-owner-v1" &&
        fields[9] == LocalPhysicalJournalFormatV1.CATALOGUE_ENTRIES.toString() &&
        fields[10] == LocalPhysicalJournalFormatV1.CATALOGUE_BYTES.toString() &&
        fields[11] == LocalPhysicalJournalFormatV1.ANCHOR_BYTES.toString())
    return LocalPhysicalCatalogueBinding(BranchScope(DomainId(fields[1]), DomainId(fields[2]), DomainId(fields[3])),
        DomainId(fields[4]), DomainId(fields[5]), DomainId(fields[6]), LocalPhysicalSpoolRole.valueOf(fields[7]),
        DomainId(fields[8])).also { check(journalOwnerBytes(it).contentEquals(bytes)) }
}

internal fun journalNativeBytes(native: LocalPhysicalSpoolNativeIdentity): ByteArray = journalFrame(listOf(
    native.profile.name, native.directoryIdentity, native.fileIdentity, native.ownerIdentity, native.cryptographyIdentity,
).map(::journalText))

internal fun journalReadNative(bytes: ByteArray): LocalPhysicalSpoolNativeIdentity {
    val fields = journalFields(bytes, 5).map(::journalString)
    return LocalPhysicalSpoolNativeIdentity(LocalPhysicalSpoolStorageProfileV1.valueOf(fields[0]), fields[1], fields[2], fields[3], fields[4])
}

internal fun journalRequirementBytes(value: LocalPhysicalJournalRequirementV1): ByteArray = journalFrame(listOf(
    journalText("physical.journal-first/requirement-v1"),
    journalFrame(listOf(value.scope.organizationId.value, value.scope.companyId.value, value.scope.branchId.value,
        value.originalActorId.value, value.intentOperationId.value, value.intentSemanticDigest.value,
        value.recordingOperationId.value, value.resolutionOperationId.value, value.attemptId.value,
        value.domainProfile, value.command.name, value.command.version.toString(), value.targetBinding.value,
        value.masterAllocationId.value, value.clientAllocationId.value, value.masterDeviceId.value, value.clientDeviceId.value,
        value.masterStorageProfile.name, value.clientStorageProfile.name).map(::journalText)),
    value.canonicalDomainRequirement.copyBytes(),
), LocalPhysicalJournalFormatV1.MAX_REQUIREMENT_BYTES)

internal fun journalReadRequirement(bytes: ByteArray): LocalPhysicalJournalRequirementV1 {
    val outer = journalFields(bytes, 3)
    check(journalString(outer[0]) == "physical.journal-first/requirement-v1")
    val f = journalFields(outer[1], 19).map(::journalString)
    return LocalPhysicalJournalRequirementV1(BranchScope(DomainId(f[0]), DomainId(f[1]), DomainId(f[2])),
        DomainId(f[3]), OperationId(f[4]), PayloadDigest(f[5]), OperationId(f[6]), OperationId(f[7]), DomainId(f[8]), f[9],
        CommandKey(f[10], journalPositiveInt(f[11])), PayloadDigest(f[12]), DomainId(f[13]), DomainId(f[14]),
        DomainId(f[15]), DomainId(f[16]), LocalPhysicalSpoolStorageProfileV1.valueOf(f[17]),
        LocalPhysicalSpoolStorageProfileV1.valueOf(f[18]), StoredBytes(outer[2]))
        .also { check(journalRequirementBytes(it).contentEquals(bytes)) }
}

internal fun journalBindingBytes(value: LocalPhysicalJournalBinding): ByteArray = journalFrame(listOf(
    journalText("physical.journal-first/allocation-v1"), journalRequirementBytes(value.requirement),
    journalOwnerBytes(value.owner), journalText(value.slotIndex.toString()), journalText(value.allocationId.value),
), LocalPhysicalJournalFormatV1.MAX_HEADER_BYTES)

internal fun journalReadBinding(bytes: ByteArray, owner: LocalPhysicalCatalogueBinding): LocalPhysicalJournalBinding {
    val fields = journalFields(bytes, 5)
    check(journalString(fields[0]) == "physical.journal-first/allocation-v1" && fields[2].contentEquals(journalOwnerBytes(owner)))
    return LocalPhysicalJournalBinding(journalReadRequirement(fields[1]), owner,
        journalNonnegativeInt(journalString(fields[3])), DomainId(journalString(fields[4])))
        .also { check(journalBindingBytes(it).contentEquals(bytes)) }
}

internal fun journalReceiptBytes(receipt: LocalPhysicalJournalReceipt): ByteArray = journalFrame(listOf(
    journalText("physical.journal-first/receipt-v1"), journalBindingBytes(receipt.binding),
    journalNativeBytes(receipt.nativeIdentity), journalText(receipt.headerDigest.value),
), LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES)

internal fun journalReadReceipt(bytes: ByteArray, owner: LocalPhysicalCatalogueBinding): LocalPhysicalJournalReceipt {
    val fields = journalFields(bytes, 4)
    check(journalString(fields[0]) == "physical.journal-first/receipt-v1")
    return LocalPhysicalJournalReceipt(journalReadBinding(fields[1], owner), journalReadNative(fields[2]),
        PayloadDigest(journalString(fields[3]))).also { check(journalReceiptBytes(it).contentEquals(bytes)) }
}

/** The AEAD envelope is the existing native primitive; purpose/AAD/frame versions are distinct. */
internal fun journalSeal(cipher: PhysicalSpoolCipher, purpose: String, binding: PayloadDigest,
    index: Int, maximumBody: Int, header: PayloadDigest?, body: ByteArray?): ByteArray {
    check(body == null || body.size in 1..maximumBody)
    val value = body ?: ByteArray(0)
    val plain = ByteArray(LocalPhysicalJournalFormatV1.CELL_BYTES - PHYSICAL_SPOOL_ENVELOPE_BYTES)
    journalPutInt(plain, 0, 1)
    journalPutInt(plain, 4, index)
    journalPutInt(plain, 8, if (body == null) 0 else 1)
    journalPutInt(plain, 12, value.size)
    journalDigestBytes(binding).copyInto(plain, 16)
    journalDigestBytes(sha256(value)).copyInto(plain, 48)
    value.copyInto(plain, 80)
    return plain.withSpoolBytes { p -> journalAad(purpose, binding, index, maximumBody, header).withSpoolBytes { aad ->
        cipher.seal(p, aad).also { check(it.size == LocalPhysicalJournalFormatV1.CELL_BYTES) }
    } }
}

internal fun journalOpen(cipher: PhysicalSpoolCipher, purpose: String, binding: PayloadDigest,
    index: Int, maximumBody: Int, header: PayloadDigest?, encrypted: ByteArray): ByteArray? {
    check(encrypted.size == LocalPhysicalJournalFormatV1.CELL_BYTES)
    return journalAad(purpose, binding, index, maximumBody, header).withSpoolBytes { aad ->
        cipher.open(encrypted, aad).withSpoolBytes { p ->
            check(p.size == LocalPhysicalJournalFormatV1.CELL_BYTES - PHYSICAL_SPOOL_ENVELOPE_BYTES &&
                journalInt(p, 0) == 1 && journalInt(p, 4) == index)
            val state = journalInt(p, 8)
            val size = journalInt(p, 12)
            check(state in 0..1 && size in 0..maximumBody && 80L + size <= p.size.toLong())
            check(if (state == 0) size == 0 else size > 0)
            check(p.copyOfRange(16, 48).contentEquals(journalDigestBytes(binding)))
            check((80 + size until p.size).all { p[it] == 0.toByte() })
            val body = p.copyOfRange(80, 80 + size)
            var transferred = false
            try {
                check(p.copyOfRange(48, 80).contentEquals(journalDigestBytes(sha256(body))))
                if (state == 0) null else body.also { transferred = true }
            } finally { if (!transferred) body.fill(0) }
        }
    }
}

private fun journalAad(purpose: String, binding: PayloadDigest, index: Int,
    maximumBody: Int, header: PayloadDigest?): ByteArray = journalFrame(listOf(
    "physical.journal-first/aes-gcm-v1", purpose, binding.value, index.toString(),
    LocalPhysicalJournalFormatV1.CELL_BYTES.toString(), maximumBody.toString(), header?.value ?: "immutable-root",
).map(::journalText))

internal fun journalFrame(fields: List<ByteArray>, maximum: Int = LocalPhysicalJournalFormatV1.MAX_HEADER_BYTES): ByteArray {
    check(fields.size <= 64)
    val size = 4L + fields.sumOf { 4L + it.size }
    require(size <= maximum.toLong()) { "physical.journal.frame_bound" }
    val output = ByteArray(size.toInt())
    journalPutInt(output, 0, fields.size)
    var offset = 4
    for (field in fields) {
        journalPutInt(output, offset, field.size)
        field.copyInto(output, offset + 4)
        offset += 4 + field.size
    }
    return output
}

internal fun journalFields(bytes: ByteArray, count: Int): List<ByteArray> {
    require(bytes.size <= LocalPhysicalJournalFormatV1.MAX_CATALOGUE_ENTRY_BYTES)
    check(count in 0..64 && journalInt(bytes, 0) == count)
    var offset = 4
    val fields = List(count) {
        val length = journalInt(bytes, offset)
        check(length >= 0 && offset.toLong() + 4L + length <= bytes.size.toLong())
        bytes.copyOfRange(offset + 4, offset + 4 + length).also { offset += 4 + length }
    }
    check(offset == bytes.size)
    return fields
}

internal fun journalText(value: String): ByteArray = value.encodeToByteArray(throwOnInvalidSequence = true)
internal fun journalString(value: ByteArray): String = value.decodeToString(throwOnInvalidSequence = true)
internal fun journalNonnegativeInt(value: String): Int = value.toInt().also { check(it >= 0 && it.toString() == value) }
internal fun journalPositiveInt(value: String): Int = journalNonnegativeInt(value).also { check(it > 0) }
internal fun journalBodyDigest(body: StoredBytes): PayloadDigest = body.copyBytes().withSpoolBytes(::sha256)
internal fun journalDigestBytes(digest: PayloadDigest): ByteArray = ByteArray(32) { i ->
    ((digest.value[2 * i].digitToInt(16) shl 4) or digest.value[2 * i + 1].digitToInt(16)).toByte()
}
internal fun journalPutInt(bytes: ByteArray, offset: Int, value: Int) {
    for (i in 0..3) bytes[offset + i] = (value ushr (24 - i * 8)).toByte()
}
internal fun journalInt(bytes: ByteArray, offset: Int): Int {
    check(offset >= 0 && offset.toLong() + 4 <= bytes.size.toLong())
    var result = 0
    for (i in 0..3) result = (result shl 8) or (bytes[offset + i].toInt() and 255)
    return result
}
