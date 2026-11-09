package com.ukkera.pos.database

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Bounded point reads on the caller's existing transaction. Metadata is selected by key only:
 * malformed storage must not look absent, and SQLite coercions must not manufacture valid rows.
 * These checks provide neither authorization nor origin, current-frontier or absence authority.
 */
internal suspend fun readCurrentAggregate(
    dao: MasterDao,
    scope: BranchScope,
    key: AggregateKey,
    checkActive: () -> Unit,
): StoredAggregate? {
    val scopeKey = scope.storageKey()
    val metadata = currentPointQuery(checkActive) {
        dao.currentAggregateMetadata(scopeKey, key.kind, key.id.value)
    } ?: return null
    if (!metadata.storageValid || metadata.byteCount !in 0L..MAX_STORED_BYTES.toLong()) {
        currentPointUnavailable()
    }
    val row = currentPointQuery(checkActive) {
        dao.aggregate(scopeKey, key.kind, key.id.value)
    } ?: currentPointUnavailable()
    return validateCurrentPoint {
        check(row.scope == scopeKey && row.kind == key.kind && row.id == key.id.value)
        check(row.revision >= 1)
        requireProtocolName(row.schema)
        check(row.payload.size.toLong() == metadata.byteCount)
        // Hash and return the same bounded private copy, not a subsequently mutable DAO array.
        val payload = StoredBytes(row.payload)
        val digest = PayloadDigest(row.payloadHash)
        check(sha256(payload.bytes()) == digest)
        StoredAggregate(key, Revision(row.revision), row.schema, payload, digest)
    }
}

internal suspend fun readCurrentClaim(
    dao: MasterDao,
    scope: BranchScope,
    key: ClaimKey,
    checkActive: () -> Unit,
): DomainId? {
    val scopeKey = scope.storageKey()
    val valid = currentPointQuery(checkActive) {
        dao.currentClaimStorageValid(scopeKey, key.namespace, key.key)
    } ?: return null
    if (!valid) currentPointUnavailable()
    val row = currentPointQuery(checkActive) {
        dao.claim(scopeKey, key.namespace, key.key)
    } ?: currentPointUnavailable()
    return validateCurrentPoint {
        check(row.scope == scopeKey && row.namespace == key.namespace && row.key == key.key)
        DomainId(row.ownerId)
    }
}

private suspend fun <T> currentPointQuery(checkActive: () -> Unit, read: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    checkActive()
    val value = try {
        read()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentPointUnavailable()
    }
    currentCoroutineContext().ensureActive()
    checkActive()
    return value
}

private inline fun <T> validateCurrentPoint(validate: () -> T): T = try {
    validate()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    currentPointUnavailable()
}

/** Never attach an SQL/parser exception, its message, or a private row as the cause. */
private fun currentPointUnavailable(): Nothing = throw IllegalStateException("Current stored state unavailable")
