package com.ukkera.pos.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

// The original four-table DAO stays usable by a genuinely generated v2 database. Invalid
// storage is reported as such, never filtered away into an apparently missing issuance.
private const val ISSUANCE_STORAGE_TEST = """
    typeof(scope)='text' AND length(CAST(scope AS BLOB))=110
    AND instr(CAST(scope AS BLOB), x'00')=0
    AND typeof(sessionId)='text' AND length(CAST(sessionId AS BLOB))=36
    AND instr(CAST(sessionId AS BLOB), x'00')=0
    AND typeof(schemaVersion)='integer' AND schemaVersion=1
    AND typeof(formatVersion)='integer' AND formatVersion=1
    AND typeof(algorithm)='text' AND length(CAST(algorithm AS BLOB))=11 AND algorithm='AES-256-GCM'
    AND instr(CAST(algorithm AS BLOB), x'00')=0
    AND typeof(revision)='integer' AND revision=0
    AND typeof(keyId)='text' AND length(CAST(keyId AS BLOB))=36
    AND instr(CAST(keyId AS BLOB), x'00')=0
    AND typeof(nonce)='blob' AND length(nonce)=12
    AND typeof(ciphertextAndTag)='blob'
"""

@Dao
internal interface LocalAccessIssuanceDao {
    @Query("SELECT COALESCE(length(ciphertextAndTag), -1) AS ciphertextByteCount, CASE WHEN " +
        ISSUANCE_STORAGE_TEST + " THEN 1 ELSE 0 END AS storageValid, " +
        "CASE WHEN typeof(revision)='integer' AND revision=0 THEN revision ELSE -1 END AS revision " +
        "FROM protected_local_access_issuance WHERE scope=:scope AND sessionId=:sessionId")
    suspend fun metadata(scope: String, sessionId: String): LocalSecurityMetadata?

    @Query("SELECT * FROM protected_local_access_issuance WHERE scope=:scope AND sessionId=:sessionId")
    suspend fun read(scope: String, sessionId: String): ProtectedLocalAccessIssuanceRow?

    /** Internal inert primitive only; no replacement, rotation, provisioning or login endpoint. */
    @Insert suspend fun insert(row: ProtectedLocalAccessIssuanceRow)
}
