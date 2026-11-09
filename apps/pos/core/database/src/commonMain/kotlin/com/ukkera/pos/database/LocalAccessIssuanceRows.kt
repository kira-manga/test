package com.ukkera.pos.database

import androidx.room3.Entity
import androidx.room3.ForeignKey

/** Inert immutable envelope. A row is neither a bearer verification nor a login result. */
@Entity(
    tableName = "protected_local_access_issuance",
    primaryKeys = ["scope", "sessionId"],
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])],
)
internal data class ProtectedLocalAccessIssuanceRow(
    val scope: String,
    val sessionId: String,
    val schemaVersion: Int,
    val formatVersion: Int,
    val algorithm: String,
    val revision: Long,
    val keyId: String,
    val nonce: ByteArray,
    val ciphertextAndTag: ByteArray,
) {
    override fun toString(): String = "ProtectedLocalAccessIssuanceRow([REDACTED])"
}
