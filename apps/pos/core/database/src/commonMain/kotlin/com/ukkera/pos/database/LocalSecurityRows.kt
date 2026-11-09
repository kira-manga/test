package com.ukkera.pos.database

import androidx.room3.Entity
import androidx.room3.ForeignKey

// These are inert authenticated envelopes, not principals, tokens or public provisioning APIs.
// Security-relevant payload fields (including enabled/generation/credentialRevision) are sealed.
// Only the scope/key coordinates and the authenticated envelope header are stored in clear.
@Entity(
    tableName = "protected_local_account",
    primaryKeys = ["scope", "actorId"],
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])],
)
internal data class ProtectedLocalAccountRow(
    val scope: String,
    val actorId: String,
    val schemaVersion: Int,
    val formatVersion: Int,
    val algorithm: String,
    val revision: Long,
    val keyId: String,
    val nonce: ByteArray,
    val ciphertextAndTag: ByteArray,
) {
    override fun toString(): String = "ProtectedLocalAccountRow([REDACTED])"
}

@Entity(
    tableName = "protected_effective_policy",
    primaryKeys = ["scope", "actorId"],
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])],
)
internal data class ProtectedEffectivePolicyRow(
    val scope: String,
    val actorId: String,
    val schemaVersion: Int,
    val formatVersion: Int,
    val algorithm: String,
    val revision: Long,
    val keyId: String,
    val nonce: ByteArray,
    val ciphertextAndTag: ByteArray,
) {
    override fun toString(): String = "ProtectedEffectivePolicyRow([REDACTED])"
}

@Entity(
    tableName = "protected_device_admission",
    primaryKeys = ["scope", "deviceId"],
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])],
)
internal data class ProtectedDeviceAdmissionRow(
    val scope: String,
    val deviceId: String,
    val schemaVersion: Int,
    val formatVersion: Int,
    val algorithm: String,
    val revision: Long,
    val keyId: String,
    val nonce: ByteArray,
    val ciphertextAndTag: ByteArray,
) {
    override fun toString(): String = "ProtectedDeviceAdmissionRow([REDACTED])"
}

@Entity(
    tableName = "protected_login_throttle",
    primaryKeys = ["scope", "actorId", "dimensionKey"],
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])],
)
internal data class ProtectedLoginThrottleRow(
    val scope: String,
    val actorId: String,
    // Non-null canonical dimension prevents NULL uniqueness from admitting duplicate buckets.
    val dimensionKey: String,
    val schemaVersion: Int,
    val formatVersion: Int,
    val algorithm: String,
    val revision: Long,
    val keyId: String,
    val nonce: ByteArray,
    val ciphertextAndTag: ByteArray,
) {
    override fun toString(): String = "ProtectedLoginThrottleRow([REDACTED])"
}

/** No unbounded text/blob is hydrated by the metadata-only query. */
internal data class LocalSecurityMetadata(
    val ciphertextByteCount: Long,
    val storageValid: Boolean,
    val revision: Long,
)
