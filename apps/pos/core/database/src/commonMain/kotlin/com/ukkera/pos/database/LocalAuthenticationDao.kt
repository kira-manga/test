package com.ukkera.pos.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

private const val AUTH_ENVELOPE_STORAGE_TEST = """
    typeof(scope)='text' AND length(CAST(scope AS BLOB))=110 AND instr(CAST(scope AS BLOB),x'00')=0
    AND typeof(schemaVersion)='integer' AND schemaVersion=1
    AND typeof(formatVersion)='integer' AND formatVersion=1
    AND typeof(algorithm)='text' AND CAST(algorithm AS BLOB)=CAST('AES-256-GCM' AS BLOB)
    AND typeof(revision)='integer' AND revision>=0
    AND typeof(keyId)='text' AND length(CAST(keyId AS BLOB))=36 AND instr(CAST(keyId AS BLOB),x'00')=0
    AND typeof(nonce)='blob' AND length(nonce)=12 AND typeof(ciphertextAndTag)='blob'
"""
private const val AUTH_CHECKED_REVISION = "CASE WHEN typeof(revision)='integer' AND revision>=0 THEN revision ELSE -1 END AS revision"

@Dao
internal interface LocalAuthenticationDao {
    @Query("SELECT COALESCE(length(ciphertextAndTag),-1) AS ciphertextByteCount, CASE WHEN " + AUTH_ENVELOPE_STORAGE_TEST +
        " AND typeof(actorId)='text' AND length(CAST(actorId AS BLOB))=36 AND instr(CAST(actorId AS BLOB),x'00')=0 AND typeof(loginKey)='text' AND length(CAST(loginKey AS BLOB))=64 AND loginKey NOT GLOB '*[^0-9a-f]*'" +
        " THEN 1 ELSE 0 END AS storageValid, " + AUTH_CHECKED_REVISION + " FROM protected_local_auth_configuration WHERE scope=:scope AND actorId=:id")
    suspend fun configurationMetadata(scope: String, id: String): LocalSecurityMetadata?

    @Query("SELECT * FROM protected_local_auth_configuration WHERE scope=:scope AND actorId=:id")
    suspend fun configuration(scope: String, id: String): ProtectedLocalAuthConfigurationRow?

    @Insert suspend fun insertConfiguration(row: ProtectedLocalAuthConfigurationRow)

    @Query("SELECT COALESCE(length(ciphertextAndTag),-1) AS ciphertextByteCount, CASE WHEN " + AUTH_ENVELOPE_STORAGE_TEST +
        " AND typeof(sessionId)='text' AND length(CAST(sessionId AS BLOB))=36 AND instr(CAST(sessionId AS BLOB),x'00')=0 AND revision=0" +
        " THEN 1 ELSE 0 END AS storageValid, " + AUTH_CHECKED_REVISION + " FROM protected_local_refresh_issuance WHERE scope=:scope AND sessionId=:id")
    suspend fun refreshMetadata(scope: String, id: String): LocalSecurityMetadata?

    @Query("SELECT * FROM protected_local_refresh_issuance WHERE scope=:scope AND sessionId=:id")
    suspend fun refresh(scope: String, id: String): ProtectedLocalRefreshIssuanceRow?

    @Insert suspend fun insertRefresh(row: ProtectedLocalRefreshIssuanceRow)

    @Query("SELECT COALESCE(length(ciphertextAndTag),-1) AS ciphertextByteCount, CASE WHEN " + AUTH_ENVELOPE_STORAGE_TEST +
        " AND typeof(eventId)='text' AND length(CAST(eventId AS BLOB))=36 AND instr(CAST(eventId AS BLOB),x'00')=0 AND revision=0" +
        " THEN 1 ELSE 0 END AS storageValid, " + AUTH_CHECKED_REVISION + " FROM protected_local_auth_event WHERE scope=:scope AND eventId=:id")
    suspend fun eventMetadata(scope: String, id: String): LocalSecurityMetadata?

    @Query("SELECT * FROM protected_local_auth_event WHERE scope=:scope AND eventId=:id")
    suspend fun event(scope: String, id: String): ProtectedLocalAuthEventRow?

    @Insert suspend fun insertEvent(row: ProtectedLocalAuthEventRow)

    @Query("SELECT COALESCE(length(ciphertextAndTag),-1) AS ciphertextByteCount, CASE WHEN " + AUTH_ENVELOPE_STORAGE_TEST +
        " AND typeof(sessionId)='text' AND length(CAST(sessionId AS BLOB))=36 AND instr(CAST(sessionId AS BLOB),x'00')=0 AND revision=0" +
        " THEN 1 ELSE 0 END AS storageValid, " + AUTH_CHECKED_REVISION + " FROM protected_local_session_archive WHERE scope=:scope AND sessionId=:id")
    suspend fun archiveMetadata(scope: String, id: String): LocalSecurityMetadata?

    @Query("SELECT * FROM protected_local_session_archive WHERE scope=:scope AND sessionId=:id")
    suspend fun archive(scope: String, id: String): ProtectedLocalSessionArchiveRow?

    @Insert suspend fun insertArchive(row: ProtectedLocalSessionArchiveRow)

    @Query("SELECT CASE WHEN typeof(actorId)='text' AND length(CAST(actorId AS BLOB))=36 THEN actorId ELSE '' END AS actorId, " +
        "CASE WHEN typeof(actorId)='text' AND length(CAST(actorId AS BLOB))=36 AND instr(CAST(actorId AS BLOB),x'00')=0 " +
        "THEN 1 ELSE 0 END AS storageValid FROM protected_local_auth_configuration WHERE scope=:scope AND loginKey=:loginKey")
    suspend fun resolveLogin(scope: String, loginKey: String): LocalLoginLocator?

    // Bounded, metadata-first maintenance/provisioning enumeration. The caller authenticates each row.
    @Query("SELECT CASE WHEN typeof(actorId)='text' AND length(CAST(actorId AS BLOB))=36 THEN actorId ELSE '' END AS actorId, " +
        "CASE WHEN typeof(actorId)='text' AND length(CAST(actorId AS BLOB))=36 AND instr(CAST(actorId AS BLOB),x'00')=0 " +
        "THEN 1 ELSE 0 END AS storageValid FROM protected_local_account WHERE scope=:scope AND (:after IS NULL OR actorId>:after) ORDER BY actorId LIMIT 257")
    suspend fun accountPage(scope: String, after: String?): List<LocalLoginLocator>

    @Query("SELECT CASE WHEN typeof(deviceId)='text' AND length(CAST(deviceId AS BLOB))=36 THEN deviceId ELSE '' END AS actorId, " +
        "CASE WHEN typeof(deviceId)='text' AND length(CAST(deviceId AS BLOB))=36 AND instr(CAST(deviceId AS BLOB),x'00')=0 " +
        "THEN 1 ELSE 0 END AS storageValid FROM protected_device_admission WHERE scope=:scope ORDER BY deviceId LIMIT 257")
    suspend fun deviceIds(scope: String): List<LocalLoginLocator>
}
