package com.ukkera.pos.database

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Insert
import androidx.room3.Query

@Entity(tableName = "protected_local_action_approval", primaryKeys = ["scope", "approvalId"],
    foreignKeys = [ForeignKey(entity = BranchRow::class, parentColumns = ["scope"], childColumns = ["scope"])])
internal data class ProtectedLocalActionApprovalRow(
    val scope: String, val approvalId: String,
    val schemaVersion: Int, val formatVersion: Int, val algorithm: String, val revision: Long,
    val keyId: String, val nonce: ByteArray, val ciphertextAndTag: ByteArray,
) { override fun toString(): String = "ProtectedLocalActionApprovalRow([REDACTED])" }

@Dao
internal interface LocalActionApprovalDao {
    @Query("SELECT COALESCE(length(ciphertextAndTag),-1) AS ciphertextByteCount, CASE WHEN " +
        "typeof(scope)='text' AND length(CAST(scope AS BLOB))=110 AND instr(CAST(scope AS BLOB),x'00')=0 " +
        "AND typeof(approvalId)='text' AND length(CAST(approvalId AS BLOB))=36 AND instr(CAST(approvalId AS BLOB),x'00')=0 " +
        "AND typeof(schemaVersion)='integer' AND schemaVersion=1 AND typeof(formatVersion)='integer' AND formatVersion=1 " +
        "AND typeof(algorithm)='text' AND CAST(algorithm AS BLOB)=CAST('AES-256-GCM' AS BLOB) " +
        "AND typeof(revision)='integer' AND revision IN (0,1) " +
        "AND typeof(keyId)='text' AND length(CAST(keyId AS BLOB))=36 AND instr(CAST(keyId AS BLOB),x'00')=0 " +
        "AND typeof(nonce)='blob' AND length(nonce)=12 AND typeof(ciphertextAndTag)='blob' " +
        "THEN 1 ELSE 0 END AS storageValid, CASE WHEN typeof(revision)='integer' AND revision IN (0,1) THEN revision ELSE -1 END AS revision " +
        "FROM protected_local_action_approval WHERE scope=:scope AND approvalId=:id")
    suspend fun metadata(scope: String, id: String): LocalSecurityMetadata?

    @Query("SELECT * FROM protected_local_action_approval WHERE scope=:scope AND approvalId=:id")
    suspend fun approval(scope: String, id: String): ProtectedLocalActionApprovalRow?

    @Insert suspend fun insert(row: ProtectedLocalActionApprovalRow)

    @Query("UPDATE protected_local_action_approval SET schemaVersion=:schemaVersion, formatVersion=:formatVersion, algorithm=:algorithm, " +
        "revision=1, keyId=:keyId, nonce=:nonce, ciphertextAndTag=:ciphertextAndTag " +
        "WHERE scope=:scope AND approvalId=:id AND revision=0")
    suspend fun consume(scope: String, id: String, schemaVersion: Int, formatVersion: Int, algorithm: String,
        keyId: String, nonce: ByteArray, ciphertextAndTag: ByteArray): Int
}
