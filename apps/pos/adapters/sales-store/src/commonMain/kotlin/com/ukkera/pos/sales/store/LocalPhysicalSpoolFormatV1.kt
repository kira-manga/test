package com.ukkera.pos.sales.store

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256

/** Fixed native prerequisite bounds. These are NOT a Room/WAL or financial-capacity profile. */
public object LocalPhysicalSpoolFormatV1 {
    public const val PROFILE: String = "ukkera.physical-spool/native-v1"
    public const val FILE_BYTES: Int = SalesPhysicalRecordingBudgetV2.MAX_CLIENT_JOURNAL_BYTES
    public const val MAX_BINDING_BYTES: Int = 4_096
    public const val MAX_OBSERVATION_BYTES: Int = SalesPhysicalRecordingBudgetV2.MAX_REQUEST_BYTES
    public const val MAX_PENDING_REPLY_BYTES: Int = SalesPhysicalRecordingBudgetV2.MAX_STATUS_BYTES
    public const val MAX_APPLICATION_REPLY_BYTES: Int = SalesPhysicalRecordingBudgetV2.MAX_RESULT_BYTES
}

internal enum class PhysicalSpoolCellV1(val index: Int, val offset: Int, val length: Int, val maximumBody: Int) {
    HEADER(0, 0, 8_192, LocalPhysicalSpoolFormatV1.MAX_BINDING_BYTES),
    OBSERVATION(1, 8_192, 12_288, LocalPhysicalSpoolFormatV1.MAX_OBSERVATION_BYTES),
    PENDING(2, 20_480, 8_192, LocalPhysicalSpoolFormatV1.MAX_PENDING_REPLY_BYTES),
    APPLICATION(3, 28_672, 20_480, LocalPhysicalSpoolFormatV1.MAX_APPLICATION_REPLY_BYTES),
    PADDING(4, 49_152, 16_384, 0),
    ;

    val writable: Boolean get() = this == OBSERVATION || this == PENDING || this == APPLICATION
}

internal const val PHYSICAL_SPOOL_ENVELOPE_BYTES: Int = 32 // magic 4 + IV 12 + GCM tag 16
internal const val PHYSICAL_SPOOL_NONCE_BYTES: Int = 12
internal const val PHYSICAL_SPOOL_TAG_BYTES: Int = 16
private const val PHYSICAL_SPOOL_MAGIC: Int = 0x55505331
private const val PHYSICAL_SPOOL_FRAME_BYTES: Int = 80 // four int32 + two binary SHA-256 values

internal class PhysicalSpoolPlanV1(
    val binding: LocalPhysicalSpoolBinding,
    val native: LocalPhysicalSpoolNativeIdentity,
) {
    val requirementDigest: PayloadDigest
    val bytes: ByteArray
    val digest: PayloadDigest

    init {
        val requirement = physicalSpoolRequirementBytes(binding.requirement)
        requirementDigest = sha256(requirement)
        bytes = physicalSpoolFrame(listOf(
            spoolText(LocalPhysicalSpoolFormatV1.PROFILE), requirement,
            physicalSpoolFrame(listOf(binding.role.name, binding.installationId.value, binding.deviceId.value,
                binding.spoolId.value, binding.allocationId.value, binding.keyId.value).map(::spoolText)),
            physicalSpoolFrame(listOf(native.profile.name, native.directoryIdentity, native.fileIdentity,
                native.ownerIdentity, native.cryptographyIdentity).map(::spoolText)),
            physicalSpoolFrame(PhysicalSpoolCellV1.entries.flatMap { cell -> listOf(
                cell.index.toString(), cell.name, cell.offset.toString(), cell.length.toString(), cell.maximumBody.toString(),
            ) }.map(::spoolText)),
        ))
        digest = sha256(bytes)
        check(PhysicalSpoolCellV1.PADDING.offset + PhysicalSpoolCellV1.PADDING.length == LocalPhysicalSpoolFormatV1.FILE_BYTES)
        check(LocalPhysicalSpoolFormatV1.MAX_BINDING_BYTES + LocalPhysicalSpoolFormatV1.MAX_OBSERVATION_BYTES +
            LocalPhysicalSpoolFormatV1.MAX_PENDING_REPLY_BYTES + LocalPhysicalSpoolFormatV1.MAX_APPLICATION_REPLY_BYTES ==
            SalesPhysicalRecordingBudgetV2.MAX_CLIENT_RECORD_BYTES)
    }
}

/** Full requirement AND numeric budget binding; the smaller v2 target digest is not a substitute. */
private fun physicalSpoolRequirementBytes(value: SalesPhysicalRecordingRequirementV2): ByteArray = physicalSpoolFrame(
    listOf(
        "ukkera.physical-spool/requirement-v1",
        value.scope.organizationId.value, value.scope.companyId.value, value.scope.branchId.value,
        value.actorId.value, value.reservationOperationId.value, value.intentOperationId.value,
        value.recordingOperationId.value, value.attemptId.value, value.documentId.value, value.cashSessionId.value,
        value.kind.name, value.amount.currency.name, value.amount.minor.toString(),
        value.tender.kind, value.tender.tenderId?.value ?: "", value.tender.policyRevision?.value?.toString() ?: "",
        value.tender.requiresTerminal.toString(), value.tender.requiresReference.toString(), value.resolving.toString(),
        value.clientAllocationId.value, value.originalSaleId?.value ?: "", value.originalClaimId?.value ?: "",
        value.originalPaymentId?.value ?: "", value.command.name, value.command.version.toString(),
        value.targetBinding.value, value.budgetProfile,
        SalesPhysicalRecordingBudgetV2.MAX_REQUEST_BYTES.toString(),
        SalesPhysicalRecordingBudgetV2.MAX_FACT_BYTES.toString(),
        SalesPhysicalRecordingBudgetV2.MAX_STATUS_BYTES.toString(),
        SalesPhysicalRecordingBudgetV2.MAX_RESULT_BYTES.toString(),
        SalesPhysicalRecordingBudgetV2.MAX_INGRESS_WRITES.toString(),
        SalesPhysicalRecordingBudgetV2.MAX_INGRESS_APPLY_BYTES.toString(),
        SalesPhysicalRecordingBudgetV2.MAX_FINANCIAL_WRITES.toString(),
        SalesPhysicalRecordingBudgetV2.MAX_FINANCIAL_CLAIMS.toString(),
        SalesPhysicalRecordingBudgetV2.MAX_FINANCIAL_ROW_BYTES.toString(),
        SalesPhysicalRecordingBudgetV2.MAX_FINANCIAL_APPLY_BYTES.toString(),
        SalesPhysicalRecordingBudgetV2.MAX_MASTER_JOURNAL_BYTES.toString(),
        SalesPhysicalRecordingBudgetV2.MAX_CLIENT_RECORD_BYTES.toString(),
        SalesPhysicalRecordingBudgetV2.MAX_CLIENT_JOURNAL_BYTES.toString(),
    ).map(::spoolText),
)

internal fun physicalSpoolAad(plan: PhysicalSpoolPlanV1, cell: PhysicalSpoolCellV1, header: PayloadDigest?): ByteArray {
    check((cell == PhysicalSpoolCellV1.HEADER) == (header == null))
    return physicalSpoolFrame(listOf("ukkera.physical-spool/aes-gcm-v1", plan.digest.value,
        cell.index.toString(), cell.name, cell.offset.toString(), cell.length.toString(), cell.maximumBody.toString(),
        header?.value ?: "immutable-header").map(::spoolText))
}

internal fun physicalSpoolPlaintext(cell: PhysicalSpoolCellV1, requirement: PayloadDigest, body: ByteArray?): ByteArray {
    check(body != null || cell.writable)
    check(body == null || body.size <= cell.maximumBody)
    check(body == null || cell == PhysicalSpoolCellV1.PADDING || body.isNotEmpty())
    val value = body ?: ByteArray(0)
    val output = ByteArray(cell.length - PHYSICAL_SPOOL_ENVELOPE_BYTES)
    physicalSpoolPutInt(output, 0, 1)
    physicalSpoolPutInt(output, 4, cell.index)
    physicalSpoolPutInt(output, 8, if (body == null) 0 else 1)
    physicalSpoolPutInt(output, 12, value.size)
    physicalSpoolDigestBytes(requirement).copyInto(output, 16)
    physicalSpoolDigestBytes(sha256(value)).copyInto(output, 48)
    value.copyInto(output, PHYSICAL_SPOOL_FRAME_BYTES)
    return output
}

/** Null is an AUTHENTICATED vacant marker, not authoritative absence of a physical event. */
internal fun physicalSpoolDecodePlaintext(cell: PhysicalSpoolCellV1, requirement: PayloadDigest, bytes: ByteArray): ByteArray? {
    check(bytes.size == cell.length - PHYSICAL_SPOOL_ENVELOPE_BYTES)
    check(physicalSpoolInt(bytes, 0) == 1 && physicalSpoolInt(bytes, 4) == cell.index)
    val state = physicalSpoolInt(bytes, 8)
    val size = physicalSpoolInt(bytes, 12)
    check(state == 0 || state == 1)
    check(size in 0..cell.maximumBody && PHYSICAL_SPOOL_FRAME_BYTES + size <= bytes.size)
    check(if (state == 0) cell.writable && size == 0 else cell == PhysicalSpoolCellV1.PADDING || size > 0)
    check(bytes.copyOfRange(16, 48).contentEquals(physicalSpoolDigestBytes(requirement)))
    check((PHYSICAL_SPOOL_FRAME_BYTES + size until bytes.size).all { bytes[it] == 0.toByte() })
    val body = bytes.copyOfRange(PHYSICAL_SPOOL_FRAME_BYTES, PHYSICAL_SPOOL_FRAME_BYTES + size)
    var transferred = false
    try {
        check(bytes.copyOfRange(48, 80).contentEquals(physicalSpoolDigestBytes(sha256(body))))
        if (state == 0) return null
        transferred = true
        return body
    } finally {
        if (!transferred) body.fill(0)
    }
}

internal fun physicalSpoolEnvelope(nonce: ByteArray, ciphertextAndTag: ByteArray): ByteArray {
    check(nonce.size == PHYSICAL_SPOOL_NONCE_BYTES && ciphertextAndTag.size >= PHYSICAL_SPOOL_TAG_BYTES)
    return ByteArray(4 + nonce.size + ciphertextAndTag.size).also {
        physicalSpoolPutInt(it, 0, PHYSICAL_SPOOL_MAGIC)
        nonce.copyInto(it, 4)
        ciphertextAndTag.copyInto(it, 16)
    }
}

internal fun physicalSpoolNonce(envelope: ByteArray): ByteArray {
    check(envelope.size >= PHYSICAL_SPOOL_ENVELOPE_BYTES && physicalSpoolInt(envelope, 0) == PHYSICAL_SPOOL_MAGIC)
    return envelope.copyOfRange(4, 16)
}

internal fun physicalSpoolNonceIdentity(envelope: ByteArray): String = physicalSpoolNonce(envelope).withSpoolBytes {
    it.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
}

internal fun physicalSpoolRange(offset: Int, size: Int, writable: Boolean = false) {
    check(PhysicalSpoolCellV1.entries.any { it.offset == offset && it.length == size && (!writable || it.writable) })
    check(offset >= 0 && size > 0 && offset.toLong() + size <= LocalPhysicalSpoolFormatV1.FILE_BYTES.toLong())
}

internal fun physicalSpoolTextBound(value: String, maximum: Int) {
    require(value.isNotBlank() && spoolText(value).size <= maximum) { "physical.spool.binding" }
}

private fun spoolText(value: String): ByteArray = value.encodeToByteArray(throwOnInvalidSequence = true)

/** Fixed order plus int32 lengths is unambiguous for arbitrary UTF-8 (including embedded NUL). */
private fun physicalSpoolFrame(fields: List<ByteArray>): ByteArray {
    require(fields.size <= 128)
    val size = fields.sumOf { 4L + it.size }
    require(size <= LocalPhysicalSpoolFormatV1.MAX_BINDING_BYTES)
    val result = ByteArray(size.toInt())
    var offset = 0
    for (field in fields) {
        physicalSpoolPutInt(result, offset, field.size)
        field.copyInto(result, offset + 4)
        offset += 4 + field.size
    }
    return result
}

private fun physicalSpoolDigestBytes(digest: PayloadDigest): ByteArray = ByteArray(32) { index ->
    ((digest.value[index * 2].digitToInt(16) shl 4) or digest.value[index * 2 + 1].digitToInt(16)).toByte()
}

private fun physicalSpoolPutInt(bytes: ByteArray, offset: Int, value: Int) {
    for (index in 0..3) bytes[offset + index] = (value ushr (24 - index * 8)).toByte()
}

private fun physicalSpoolInt(bytes: ByteArray, offset: Int): Int {
    check(offset >= 0 && offset + 4 <= bytes.size)
    var value = 0
    for (index in 0..3) value = (value shl 8) or (bytes[offset + index].toInt() and 0xff)
    return value
}

internal inline fun <T> ByteArray.withSpoolBytes(block: (ByteArray) -> T): T = try { block(this) } finally { fill(0) }
