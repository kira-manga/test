package com.ukkera.pos.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query

// CAST(text AS BLOB) includes embedded NUL tails. Never move these tests into WHERE: an
// existing but invalid row is unavailable, not an absent/unprovisioned identity or zero bucket.
private const val LOCAL_ENVELOPE_STORAGE_TEST = """
    typeof(scope)='text' AND length(CAST(scope AS BLOB))=110
    AND instr(CAST(scope AS BLOB), x'00')=0
    AND typeof(schemaVersion)='integer' AND schemaVersion=1
    AND typeof(formatVersion)='integer' AND formatVersion=1
    AND typeof(algorithm)='text' AND length(CAST(algorithm AS BLOB))=11 AND algorithm='AES-256-GCM'
    AND instr(CAST(algorithm AS BLOB), x'00')=0
    AND typeof(revision)='integer' AND revision>=0
    AND typeof(keyId)='text' AND length(CAST(keyId AS BLOB))=36
    AND instr(CAST(keyId AS BLOB), x'00')=0
    AND typeof(nonce)='blob' AND length(nonce)=12
    AND typeof(ciphertextAndTag)='blob'
"""

private const val LOCAL_ACTOR_STORAGE_TEST = """
    AND typeof(actorId)='text' AND length(CAST(actorId AS BLOB))=36
    AND instr(CAST(actorId AS BLOB), x'00')=0
"""

private const val LOCAL_CHECKED_REVISION =
    "CASE WHEN typeof(revision)='integer' AND revision>=0 THEN revision ELSE -1 END AS revision"

@Dao
internal interface LocalSecurityDao {
    @Query("SELECT COALESCE(length(ciphertextAndTag), -1) AS ciphertextByteCount, CASE WHEN " +
        LOCAL_ENVELOPE_STORAGE_TEST + LOCAL_ACTOR_STORAGE_TEST +
        " THEN 1 ELSE 0 END AS storageValid, " + LOCAL_CHECKED_REVISION +
        " FROM protected_local_account WHERE scope=:scope AND actorId=:actorId")
    suspend fun accountMetadata(scope: String, actorId: String): LocalSecurityMetadata?

    @Query("SELECT * FROM protected_local_account WHERE scope=:scope AND actorId=:actorId")
    suspend fun account(scope: String, actorId: String): ProtectedLocalAccountRow?

    @Query("SELECT COALESCE(length(ciphertextAndTag), -1) AS ciphertextByteCount, CASE WHEN " +
        LOCAL_ENVELOPE_STORAGE_TEST + LOCAL_ACTOR_STORAGE_TEST +
        " THEN 1 ELSE 0 END AS storageValid, " + LOCAL_CHECKED_REVISION +
        " FROM protected_effective_policy WHERE scope=:scope AND actorId=:actorId")
    suspend fun policyMetadata(scope: String, actorId: String): LocalSecurityMetadata?

    @Query("SELECT * FROM protected_effective_policy WHERE scope=:scope AND actorId=:actorId")
    suspend fun policy(scope: String, actorId: String): ProtectedEffectivePolicyRow?

    @Query("SELECT COALESCE(length(ciphertextAndTag), -1) AS ciphertextByteCount, CASE WHEN " +
        LOCAL_ENVELOPE_STORAGE_TEST +
        " AND typeof(deviceId)='text' AND length(CAST(deviceId AS BLOB))=36" +
        " AND instr(CAST(deviceId AS BLOB), x'00')=0" +
        " THEN 1 ELSE 0 END AS storageValid, " + LOCAL_CHECKED_REVISION +
        " FROM protected_device_admission WHERE scope=:scope AND deviceId=:deviceId")
    suspend fun admissionMetadata(scope: String, deviceId: String): LocalSecurityMetadata?

    @Query("SELECT * FROM protected_device_admission WHERE scope=:scope AND deviceId=:deviceId")
    suspend fun admission(scope: String, deviceId: String): ProtectedDeviceAdmissionRow?

    @Query("SELECT COALESCE(length(ciphertextAndTag), -1) AS ciphertextByteCount, CASE WHEN " +
        LOCAL_ENVELOPE_STORAGE_TEST + LOCAL_ACTOR_STORAGE_TEST +
        " AND typeof(dimensionKey)='text' AND length(CAST(dimensionKey AS BLOB)) IN (7, 43)" +
        " AND instr(CAST(dimensionKey AS BLOB), x'00')=0" +
        " THEN 1 ELSE 0 END AS storageValid, " + LOCAL_CHECKED_REVISION + " FROM protected_login_throttle" +
        " WHERE scope=:scope AND actorId=:actorId AND dimensionKey=:dimensionKey")
    suspend fun throttleMetadata(scope: String, actorId: String, dimensionKey: String): LocalSecurityMetadata?

    @Query("SELECT * FROM protected_login_throttle WHERE scope=:scope AND actorId=:actorId AND dimensionKey=:dimensionKey")
    suspend fun throttle(scope: String, actorId: String, dimensionKey: String): ProtectedLoginThrottleRow?

    // Explicit inserts are internal storage primitives for separately authorized provisioning
    // and synthetic fixtures only. There is no public/default initializer, UPSERT or REPLACE.
    @Insert suspend fun insertAccount(row: ProtectedLocalAccountRow)
    @Insert suspend fun insertPolicy(row: ProtectedEffectivePolicyRow)
    @Insert suspend fun insertAdmission(row: ProtectedDeviceAdmissionRow)
    @Insert suspend fun insertThrottle(row: ProtectedLoginThrottleRow)

    @Query("""
        UPDATE protected_local_account
        SET schemaVersion=:schemaVersion, formatVersion=:formatVersion, algorithm=:algorithm,
            revision=:nextRevision, keyId=:keyId, nonce=:nonce, ciphertextAndTag=:ciphertextAndTag
        WHERE scope=:scope AND actorId=:actorId AND revision=:expectedRevision
    """)
    suspend fun replaceAccount(
        scope: String, actorId: String, expectedRevision: Long, nextRevision: Long,
        schemaVersion: Int, formatVersion: Int, algorithm: String, keyId: String,
        nonce: ByteArray, ciphertextAndTag: ByteArray,
    ): Int

    @Query("""
        UPDATE protected_effective_policy
        SET schemaVersion=:schemaVersion, formatVersion=:formatVersion, algorithm=:algorithm,
            revision=:nextRevision, keyId=:keyId, nonce=:nonce, ciphertextAndTag=:ciphertextAndTag
        WHERE scope=:scope AND actorId=:actorId AND revision=:expectedRevision
    """)
    suspend fun replacePolicy(
        scope: String, actorId: String, expectedRevision: Long, nextRevision: Long,
        schemaVersion: Int, formatVersion: Int, algorithm: String, keyId: String,
        nonce: ByteArray, ciphertextAndTag: ByteArray,
    ): Int

    @Query("""
        UPDATE protected_device_admission
        SET schemaVersion=:schemaVersion, formatVersion=:formatVersion, algorithm=:algorithm,
            revision=:nextRevision, keyId=:keyId, nonce=:nonce, ciphertextAndTag=:ciphertextAndTag
        WHERE scope=:scope AND deviceId=:deviceId AND revision=:expectedRevision
    """)
    suspend fun replaceAdmission(
        scope: String, deviceId: String, expectedRevision: Long, nextRevision: Long,
        schemaVersion: Int, formatVersion: Int, algorithm: String, keyId: String,
        nonce: ByteArray, ciphertextAndTag: ByteArray,
    ): Int

    @Query("""
        UPDATE protected_login_throttle
        SET schemaVersion=:schemaVersion, formatVersion=:formatVersion, algorithm=:algorithm,
            revision=:nextRevision, keyId=:keyId, nonce=:nonce, ciphertextAndTag=:ciphertextAndTag
        WHERE scope=:scope AND actorId=:actorId AND dimensionKey=:dimensionKey AND revision=:expectedRevision
    """)
    suspend fun replaceThrottle(
        scope: String, actorId: String, dimensionKey: String, expectedRevision: Long, nextRevision: Long,
        schemaVersion: Int, formatVersion: Int, algorithm: String, keyId: String,
        nonce: ByteArray, ciphertextAndTag: ByteArray,
    ): Int
}
