package com.ukkera.pos.app.gateway

import com.ukkera.pos.security.LocalDelegatedEmployeeCreationV1
import com.ukkera.pos.security.LocalEmployeeCreationReceiptV1
import kotlinx.serialization.Serializable

/** Journal control DATA. DISPATCHED survives cancellation/restart without storing a password. */
@Serializable
internal enum class StoredEmployeeCreationPhaseV1 { PREPARED, DISPATCHED, APPLIED, IDENTITY_CONFLICT, CANCELLED }

/** The complete original stays immutable. A journal entry is never native or employee authority. */
internal class StoredClientEmployeeCreationV1 private constructor(
    val owner: LocalClientIdentity,
    val original: LocalDelegatedEmployeeCreationV1,
    val phase: StoredEmployeeCreationPhaseV1,
    val stopRequested: Boolean,
) {
    val terminal: Boolean get() = phase == StoredEmployeeCreationPhaseV1.APPLIED ||
        phase == StoredEmployeeCreationPhaseV1.IDENTITY_CONFLICT || phase == StoredEmployeeCreationPhaseV1.CANCELLED

    fun validate(device: LocalClientDeviceIdentity) {
        require(owner.scope == device.scope && owner.deviceId == device.deviceId)
        require(original.scope == owner.scope && original.operatorId == owner.actorId)
        require(!stopRequested || phase != StoredEmployeeCreationPhaseV1.PREPARED)
    }

    fun dispatched(): StoredClientEmployeeCreationV1 {
        check(phase == StoredEmployeeCreationPhaseV1.PREPARED && !stopRequested)
        return StoredClientEmployeeCreationV1(owner, original, StoredEmployeeCreationPhaseV1.DISPATCHED, false)
    }

    fun stopped(): StoredClientEmployeeCreationV1 {
        check(phase == StoredEmployeeCreationPhaseV1.DISPATCHED)
        return StoredClientEmployeeCreationV1(owner, original, phase, true)
    }

    /** Only the currently authorized service consumer may record a protected response.
     * Exact correlation is checked here; these public receipt fields do not prove that response. */
    fun applied(receipt: LocalEmployeeCreationReceiptV1): StoredClientEmployeeCreationV1 {
        check(phase != StoredEmployeeCreationPhaseV1.IDENTITY_CONFLICT && phase != StoredEmployeeCreationPhaseV1.CANCELLED)
        require(receipt == originalReceipt())
        return StoredClientEmployeeCreationV1(owner, original, StoredEmployeeCreationPhaseV1.APPLIED, stopRequested)
    }

    /** Only a currently protected same-original server tombstone can supply this DATA.
     * A local stop request or NotObserved response is never a cancellation receipt. */
    fun cancelled(receipt: LocalEmployeeCreationReceiptV1): StoredClientEmployeeCreationV1 {
        check(phase != StoredEmployeeCreationPhaseV1.APPLIED && phase != StoredEmployeeCreationPhaseV1.IDENTITY_CONFLICT)
        require(receipt == originalReceipt())
        return StoredClientEmployeeCreationV1(owner, original, StoredEmployeeCreationPhaseV1.CANCELLED, stopRequested)
    }

    fun identityConflict(): StoredClientEmployeeCreationV1 {
        check(phase != StoredEmployeeCreationPhaseV1.APPLIED && phase != StoredEmployeeCreationPhaseV1.CANCELLED)
        return StoredClientEmployeeCreationV1(owner, original, StoredEmployeeCreationPhaseV1.IDENTITY_CONFLICT, stopRequested)
    }

    fun appliedReceipt(): LocalEmployeeCreationReceiptV1? =
        if (phase == StoredEmployeeCreationPhaseV1.APPLIED) originalReceipt() else null

    fun cancelledReceipt(): LocalEmployeeCreationReceiptV1? =
        if (phase == StoredEmployeeCreationPhaseV1.CANCELLED) originalReceipt() else null

    private fun originalReceipt(): LocalEmployeeCreationReceiptV1 = LocalEmployeeCreationReceiptV1(
        original.scope, original.requestId, original.operatorId, original.actorId, original.contentDigest)

    /** Shared v1 entry framing; platform adapters only store the resulting bounded bytes.
     * The original is binary, avoiding a JSON integer-array expansion of a large definition. */
    fun copyWireBytes(): ByteArray {
        val metadata = journalJson.encodeToString(Metadata.serializer(),
            Metadata(owner = owner, phase = phase, stopRequested = stopRequested)).encodeToByteArray()
        val canonical = original.copyWireBytes()
        try {
            check(metadata.size in 1..MAX_METADATA_BYTES)
            val bytes = ByteArray(8 + metadata.size + canonical.size)
            putSize(bytes, 0, metadata.size)
            metadata.copyInto(bytes, 4)
            putSize(bytes, 4 + metadata.size, canonical.size)
            canonical.copyInto(bytes, 8 + metadata.size)
            check(bytes.size <= MAX_CLIENT_EMPLOYEE_CREATION_BYTES)
            return bytes
        } finally { metadata.fill(0); canonical.fill(0) }
    }

    override fun toString(): String = "StoredClientEmployeeCreationV1([REDACTED])"

    @Serializable
    private data class Metadata(val version: Int = 1, val owner: LocalClientIdentity,
        val phase: StoredEmployeeCreationPhaseV1, val stopRequested: Boolean)

    companion object {
        private const val MAX_METADATA_BYTES = 2048

        fun capture(owner: LocalClientIdentity, original: LocalDelegatedEmployeeCreationV1): StoredClientEmployeeCreationV1 =
            StoredClientEmployeeCreationV1(owner, original, StoredEmployeeCreationPhaseV1.PREPARED, false)
                .also { it.validate(LocalClientDeviceIdentity(owner.scope, owner.deviceId)) }

        fun decode(bytes: ByteArray, device: LocalClientDeviceIdentity): StoredClientEmployeeCreationV1 {
            require(bytes.size in 10..MAX_CLIENT_EMPLOYEE_CREATION_BYTES)
            val metadataSize = sizeAt(bytes, 0)
            require(metadataSize in 1..MAX_METADATA_BYTES && metadataSize <= bytes.size - 8)
            val metadataBytes = bytes.copyOfRange(4, 4 + metadataSize)
            val metadata = try {
                journalJson.decodeFromString(Metadata.serializer(), metadataBytes.decodeToString(throwOnInvalidSequence = true))
            } finally { metadataBytes.fill(0) }
            require(metadata.version == 1)
            val originalSize = sizeAt(bytes, 4 + metadataSize)
            require(originalSize in 1..LocalDelegatedEmployeeCreationV1.MAXIMUM_BYTES && originalSize == bytes.size - 8 - metadataSize)
            val canonical = bytes.copyOfRange(8 + metadataSize, bytes.size)
            val original = try { LocalDelegatedEmployeeCreationV1.decode(canonical) } finally { canonical.fill(0) }
            return StoredClientEmployeeCreationV1(metadata.owner, original, metadata.phase, metadata.stopRequested).also {
                it.validate(device)
                it.copyWireBytes().useWiped { encoded -> require(encoded.contentEquals(bytes)) }
            }
        }

        private fun putSize(bytes: ByteArray, offset: Int, value: Int) {
            for (index in 0..3) bytes[offset + index] = (value ushr (24 - index * 8)).toByte()
        }
        private fun sizeAt(bytes: ByteArray, offset: Int): Int {
            require(offset >= 0 && offset <= bytes.size - 4)
            var value = 0
            for (index in 0..3) value = (value shl 8) or (bytes[offset + index].toInt() and 255)
            return value
        }
    }
}

internal const val MAX_CLIENT_EMPLOYEE_CREATIONS: Int = 64
internal const val MAX_CLIENT_EMPLOYEE_CREATION_BYTES: Int = LocalDelegatedEmployeeCreationV1.MAXIMUM_BYTES + 2056
