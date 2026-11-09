package com.ukkera.pos.recovery.store

import com.ukkera.pos.recovery.AuthorityIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import kotlinx.serialization.Serializable

/**
 * One-shot LOCAL helper protocol. No key material, password, bearer or provider exception crosses
 * this boundary. Stdout is exclusively the framed receipt, not logging. A child must write it only
 * after the real exact-current quarantine and all normal SQLite closes returned successfully, and
 * then exit. The parent separately waits for actual process exit/containment reap before success.
 * These codecs neither authorize the request nor provide a child runtime/protected-key provider.
 */
public object StagedQuarantineProcessProtocol {
    public const val VERSION: Int = 1
    public const val MAXIMUM_PAYLOAD_BYTES: Int = 64 * 1024
    private val requestMagic = "UKKERA-QUARANTINE-REQUEST\u0000".toByteArray(Charsets.US_ASCII)
    private val receiptMagic = "UKKERA-QUARANTINE-RECEIPT\u0000".toByteArray(Charsets.US_ASCII)
    internal val maximumReceiptStreamBytes: Int = receiptMagic.size + 4 + MAXIMUM_PAYLOAD_BYTES

    /** Child entrypoint: reads one complete bounded request; the parent closes/redirects stdin. */
    public fun readRequest(input: InputStream): StagedSecurityQuarantineRequest = protocolOperation {
        val payload = readFrame(input, requestMagic)
        try {
            val text = payload.decodeToString(throwOnInvalidSequence = true)
            requireShallowJson(text)
            val wire = backupJson.decodeFromString<QuarantineRequestWire>(text)
            check(wire.version == VERSION && backupJson.encodeToString(wire) == text)
            check(wire.databasePath.length in 1..4096)
            val path = Path.of(wire.databasePath)
            check(path.isAbsolute && path == path.normalize() && path.parent != null)
            StagedSecurityQuarantineRequest(path, wire.scope, wire.backupId, wire.incidentId,
                wire.sourceAuthority, wire.targetDeviceId, wire.approvalId)
        } finally { payload.fill(0) }
    }

    /** Child entrypoint: no status flag or arbitrary exception text can masquerade as a receipt. */
    public fun writeReceipt(output: OutputStream, receipt: StagedSecurityQuarantineReceipt): Unit = protocolOperation {
        val counts = receipt.authenticatedRowCounts.toSortedMap()
        check(counts.size in 1..128 && counts.keys.all(::validSqlName) && counts.values.all { it >= 0 })
        val wire = QuarantineReceiptWire(VERSION, receipt.scope, receipt.backupId, receipt.incidentId,
            receipt.targetDeviceId, counts, receipt.revokedSessionCount)
        val bytes = backupJson.encodeToString(wire).encodeToByteArray()
        try { writeFrame(output, receiptMagic, bytes) } finally { bytes.fill(0) }
    }

    internal fun writeRequest(output: OutputStream, request: StagedSecurityQuarantineRequest): Unit = protocolOperation {
        val path = request.databasePath
        check(path.isAbsolute && path == path.normalize() && path.parent != null && path.toString().length in 1..4096)
        val wire = QuarantineRequestWire(VERSION, path.toString(), request.scope, request.backupId,
            request.incidentId, request.sourceAuthority, request.targetDeviceId, request.approvalId)
        val bytes = backupJson.encodeToString(wire).encodeToByteArray()
        try { writeFrame(output, requestMagic, bytes) } finally { bytes.fill(0) }
    }

    internal fun readReceipt(bytes: ByteArray): StagedSecurityQuarantineReceipt = protocolOperation {
        val payload = readFrame(ByteArrayInputStream(bytes), receiptMagic)
        try {
            val text = payload.decodeToString(throwOnInvalidSequence = true)
            requireShallowJson(text)
            val wire = backupJson.decodeFromString<QuarantineReceiptWire>(text)
            check(wire.version == VERSION && wire.authenticatedRowCounts.size in 1..128 &&
                wire.authenticatedRowCounts.keys.all(::validSqlName))
            val canonical = wire.copy(authenticatedRowCounts = wire.authenticatedRowCounts.toSortedMap())
            check(backupJson.encodeToString(canonical) == text)
            StagedSecurityQuarantineReceipt(wire.scope, wire.backupId, wire.incidentId,
                wire.targetDeviceId, canonical.authenticatedRowCounts, wire.revokedSessionCount)
        } finally { payload.fill(0) }
    }

    private fun writeFrame(output: OutputStream, magic: ByteArray, payload: ByteArray) {
        check(payload.size in 1..MAXIMUM_PAYLOAD_BYTES)
        DataOutputStream(output).apply {
            write(magic)
            writeInt(payload.size)
            write(payload)
            flush()
        }
    }

    private fun readFrame(input: InputStream, magic: ByteArray): ByteArray {
        val data = DataInputStream(input)
        check(ByteArray(magic.size).also(data::readFully).contentEquals(magic))
        val size = data.readInt()
        check(size in 1..MAXIMUM_PAYLOAD_BYTES)
        val payload = ByteArray(size)
        try {
            data.readFully(payload)
            check(data.read() == -1)
            return payload
        } catch (failure: Throwable) { payload.fill(0); throw failure }
    }

    private inline fun <T> protocolOperation(block: () -> T): T = try { block() }
    catch (_: Exception) { fail(LocalBackupFailure.SECURITY_QUARANTINE_UNAVAILABLE) }
}

@Serializable
private data class QuarantineRequestWire(
    val version: Int,
    val databasePath: String,
    val scope: BranchScope,
    val backupId: DomainId,
    val incidentId: DomainId,
    val sourceAuthority: AuthorityIdentity,
    val targetDeviceId: DomainId,
    val approvalId: DomainId,
)

@Serializable
private data class QuarantineReceiptWire(
    val version: Int,
    val scope: BranchScope,
    val backupId: DomainId,
    val incidentId: DomainId,
    val targetDeviceId: DomainId,
    val authenticatedRowCounts: Map<String, Long>,
    val revokedSessionCount: Long,
)
