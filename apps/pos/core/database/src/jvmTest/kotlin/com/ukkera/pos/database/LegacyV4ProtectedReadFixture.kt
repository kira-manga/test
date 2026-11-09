package com.ukkera.pos.database

import androidx.room3.Transactor
import androidx.room3.useWriterConnection
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.DeviceProtectedLocalStorage
import com.ukkera.pos.security.LocalAccessIssuance
import com.ukkera.pos.security.LocalDeviceAdmission
import com.ukkera.pos.security.LocalOpenResult
import com.ukkera.pos.security.PersistentThrottleState
import com.ukkera.pos.security.ProtectedLocalAccount
import com.ukkera.pos.security.ProtectedLocalBinding
import com.ukkera.pos.security.ProtectedLocalRecordKey
import com.ukkera.pos.security.ProtectedLocalStorageFormat
import com.ukkera.pos.security.ProtectedStorageKeyReference
import com.ukkera.pos.security.SealedLocalRecord
import com.ukkera.pos.security.ThrottleKey
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Test-only protected reader for the separately generated historical v4 schema.
 *
 * Production [RoomLocalSecurityStore] is deliberately bound to the owned
 * [MasterRoomDatabase]. These migration oracles must instead open the actual
 * historical v4 schema, without a schema-7 opener or an invented production
 * 6 -> 7 migration. This adapter reuses the production DAO metadata/envelope
 * queries, production AEAD provider and [ProtectedLocalSecurityCodec] to verify
 * that the exact migrated ciphertext still authenticates and decodes to the same
 * semantic values. It is not a production store, provisions nothing and grants
 * no authority.
 */
internal class LegacyV4ProtectedReadFixture(
    private val database: LegacyV4MasterDatabase,
    private val protection: DeviceProtectedLocalStorage,
    private val keyReference: ProtectedStorageKeyReference,
) {
    suspend fun <T> transaction(block: suspend (LegacyV4ProtectedReadTransaction) -> T): T =
        database.useWriterConnection { connection ->
            requireDurableWriter(connection)
            connection.withTransaction(Transactor.SQLiteTransactionType.IMMEDIATE) {
                block(LegacyV4ProtectedReadTransaction(database, protection, keyReference))
            }
        }
}

internal class LegacyV4ProtectedReadTransaction(
    private val database: LegacyV4MasterDatabase,
    private val protection: DeviceProtectedLocalStorage,
    private val keyReference: ProtectedStorageKeyReference,
) {
    suspend fun readProtectedAccount(scope: BranchScope, actorId: DomainId): ProtectedLocalAccount? = read(
        ProtectedLocalRecordKey.Account(scope, actorId),
        metadata = { database.localSecurity().accountMetadata(scope.storageKey(), actorId.value) },
        envelope = {
            database.localSecurity().account(scope.storageKey(), actorId.value)?.let {
                LegacyV4Envelope(it.scope, it.actorId, null, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
            }
        },
        decode = ProtectedLocalSecurityCodec::decodeAccount,
    )

    suspend fun readPolicy(scope: BranchScope, actorId: DomainId): ActivatedLocalPolicy? = read(
        ProtectedLocalRecordKey.EffectivePolicy(scope, actorId),
        metadata = { database.localSecurity().policyMetadata(scope.storageKey(), actorId.value) },
        envelope = {
            database.localSecurity().policy(scope.storageKey(), actorId.value)?.let {
                LegacyV4Envelope(it.scope, it.actorId, null, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
            }
        },
        decode = LocalEffectivePolicyCodec::decodePolicy,
    )

    suspend fun readAdmission(scope: BranchScope, deviceId: DomainId): LocalDeviceAdmission? = read(
        ProtectedLocalRecordKey.Admission(scope, deviceId),
        metadata = { database.localSecurity().admissionMetadata(scope.storageKey(), deviceId.value) },
        envelope = {
            database.localSecurity().admission(scope.storageKey(), deviceId.value)?.let {
                LegacyV4Envelope(it.scope, it.deviceId, null, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
            }
        },
        decode = ProtectedLocalSecurityCodec::decodeAdmission,
    )

    suspend fun readThrottle(key: ThrottleKey): PersistentThrottleState? {
        val recordKey = ProtectedLocalRecordKey.Throttle(key)
        return read(
            recordKey,
            metadata = {
                database.localSecurity().throttleMetadata(key.scope.storageKey(), key.actorId.value,
                    recordKey.dimensionKey)
            },
            envelope = {
                database.localSecurity().throttle(key.scope.storageKey(), key.actorId.value,
                    recordKey.dimensionKey)?.let {
                    LegacyV4Envelope(it.scope, it.actorId, it.dimensionKey, it.schemaVersion, it.formatVersion,
                        it.algorithm, it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
                }
            },
            decode = ProtectedLocalSecurityCodec::decodeThrottle,
        )
    }

    suspend fun readIssuance(scope: BranchScope, sessionId: DomainId): LocalAccessIssuance? = read(
        ProtectedLocalRecordKey.SessionIssuance(scope, sessionId),
        metadata = { database.localAccessIssuance().metadata(scope.storageKey(), sessionId.value) },
        envelope = {
            database.localAccessIssuance().read(scope.storageKey(), sessionId.value)?.let {
                LegacyV4Envelope(it.scope, it.sessionId, null, it.schemaVersion, it.formatVersion, it.algorithm,
                    it.revision, it.keyId, it.nonce, it.ciphertextAndTag)
            }
        },
        decode = ProtectedLocalSecurityCodec::decodeIssuance,
    )

    private suspend fun <T> read(
        key: ProtectedLocalRecordKey,
        metadata: suspend () -> LocalSecurityMetadata?,
        envelope: suspend () -> LegacyV4Envelope?,
        decode: (ProtectedLocalBinding, ByteArray) -> T,
    ): T? {
        val rowMetadata = metadata() ?: return null
        val maximumCiphertext = key.table.maximumPlaintextBytes.toLong() + ProtectedLocalStorageFormat.TAG_BYTES
        check(rowMetadata.storageValid && rowMetadata.revision >= 0 &&
            rowMetadata.ciphertextByteCount in (ProtectedLocalStorageFormat.TAG_BYTES + 1L)..maximumCiphertext) {
            "Protected local metadata unavailable"
        }
        val expected = ProtectedLocalBinding(key, Revision(rowMetadata.revision))
        val actual = checkNotNull(envelope()) { "Protected local envelope unavailable" }
        val sealed = actual.checked(expected, rowMetadata, keyReference)
        val opened = when (val result = protection.open(keyReference, expected, sealed)) {
            is LocalOpenResult.Opened -> result
            is LocalOpenResult.Unavailable -> error("Protected local record unavailable")
        }
        expected.requirePlaintextSize(opened.size)
        check(opened.size.toLong() + ProtectedLocalStorageFormat.TAG_BYTES == rowMetadata.ciphertextByteCount) {
            "Protected local ciphertext length mismatch"
        }
        val plaintext = opened.copyPlaintext()
        return try {
            decode(expected, plaintext)
        } finally {
            plaintext.fill(0)
        }
    }
}

/** Exact production envelope checks, kept test-only because the production class is private. */
private class LegacyV4Envelope(
    private val scope: String,
    private val recordId: String,
    private val dimensionKey: String?,
    private val schemaVersion: Int,
    private val formatVersion: Int,
    private val algorithm: String,
    private val revision: Long,
    private val keyId: String,
    private val nonce: ByteArray,
    private val ciphertextAndTag: ByteArray,
) {
    fun checked(
        expected: ProtectedLocalBinding,
        metadata: LocalSecurityMetadata,
        permittedKey: ProtectedStorageKeyReference,
    ): SealedLocalRecord {
        check(scope == expected.key.scope.storageKey() && recordId == expected.key.recordId.value)
        check(dimensionKey == (expected.key as? ProtectedLocalRecordKey.Throttle)?.dimensionKey)
        check(schemaVersion == expected.schemaVersion && formatVersion == ProtectedLocalStorageFormat.FORMAT_VERSION)
        check(algorithm == ProtectedLocalStorageFormat.ALGORITHM && revision == expected.revision.value)
        check(keyId == permittedKey.keyId.value)
        check(nonce.size == ProtectedLocalStorageFormat.NONCE_BYTES)
        check(ciphertextAndTag.size.toLong() == metadata.ciphertextByteCount)
        return SealedLocalRecord(expected, permittedKey, nonce, ciphertextAndTag)
    }
}
