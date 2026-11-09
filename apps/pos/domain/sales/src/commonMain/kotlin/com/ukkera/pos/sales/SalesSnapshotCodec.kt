package com.ukkera.pos.sales

import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.text.CharacterCodingException

public enum class SalesSnapshotKind {
    CASH_SESSION, SAFE_TRANSFER, ORDER, COLLECTION, RECEIPT_ENTITLEMENT, RETURN_CLAIM, ACCEPTED_RETURN,
    PAID_SALE,
}

/** Independent protected-row metadata, supplied by the local authoritative store, NOT decoded from the body. */
public data class SalesSnapshotBinding(
    public val kind: SalesSnapshotKind,
    public val scope: BranchScope,
    public val id: DomainId,
    public val revision: Revision,
)

public enum class SalesSnapshotFailure {
    MALFORMED, TOO_LARGE, NON_CANONICAL, UNSUPPORTED_VERSION, BINDING_MISMATCH, INCONSISTENT_STATE,
}

/** Deliberately contains no protected payload, financial evidence, credentials or parser transcript. */
public class SalesSnapshotRejectedException(public val failure: SalesSnapshotFailure) : IllegalArgumentException(failure.name)

/**
 * Bounded, versioned codecs for protected local STORED snapshots, never request DTOs.
 * Decoding is not authentication, recovery readmission, a committed transition, or
 * proof that related rows/unique indexes exist. A trusted store must validate the
 * expected row binding and cross-aggregate links in its authoritative transaction.
 * No authorization capability, command, reusable inventory proof or plan is stored.
 */
public object SalesSnapshotCodec {
    public const val SCHEMA_VERSION: Int = 1
    /** Serial-bearing retail or price-source-bearing order/collection/receipt envelopes. */
    public const val SERIAL_SCHEMA_VERSION: Int = 2
    /** Explicit linked supervisor resolution; v1/v2 absent-link snapshots keep their exact bytes. */
    public const val SUPERVISED_SCHEMA_VERSION: Int = 3
    /** Explicit normalized logical-demand order/collection; no v1 allocation-list expansion. */
    public const val NORMALIZED_STOCK_SCHEMA_VERSION: Int = 4
    /** NEW original signed-SKU promise capture; absent companions retain exact v1–v4 bytes. */
    public const val ORIGINAL_PROMISE_SCHEMA_VERSION: Int = 5
    /** A created immutable fact, never the mutable collection or return-entitlement revision. */
    public val PAID_SALE_REVISION: Revision = Revision(0)
    /** A storage bound, NOT permission to use an 8 MiB RPC body. */
    public const val MAX_ENCODED_BYTES: Int = 8 * 1024 * 1024

    public fun encodeCashSession(session: CashSession): String = checkedSnapshot {
        validateStoredCash(session)
        encodeSnapshot(SalesSnapshotBinding(SalesSnapshotKind.CASH_SESSION, session.scope, session.id, session.revision),
            StoredCashSession.from(session), StoredCashSession.serializer())
    }

    public fun decodeCashSession(encoded: String, expected: SalesSnapshotBinding): CashSession = checkedSnapshot {
        decodeSnapshot(encoded, expected, SalesSnapshotKind.CASH_SESSION, StoredCashSession.serializer()).restore(expected)
            .also(::validateStoredCash)
    }

    public fun encodeSafeTransfer(transfer: SafeTransfer): String = checkedSnapshot {
        validateStoredTransfer(transfer)
        encodeSnapshot(SalesSnapshotBinding(SalesSnapshotKind.SAFE_TRANSFER, transfer.scope, transfer.id, transfer.revision),
            StoredSafeTransfer.from(transfer), StoredSafeTransfer.serializer())
    }

    public fun decodeSafeTransfer(encoded: String, expected: SalesSnapshotBinding): SafeTransfer = checkedSnapshot {
        decodeSnapshot(encoded, expected, SalesSnapshotKind.SAFE_TRANSFER, StoredSafeTransfer.serializer()).restore(expected)
            .also(::validateStoredTransfer)
    }

    public fun encodeOrder(order: OwnedOrder): String = checkedSnapshot {
        validateStoredOrder(order)
        encodeSnapshot(SalesSnapshotBinding(SalesSnapshotKind.ORDER, order.scope, order.id, order.revision),
            StoredOrder.from(order), StoredOrder.serializer(), retailSnapshotVersion(
                !order.stockRequirements.normalized && order.stockRequirements.allocations.any { it.position.serialId != null }, order.priceSourceReference != null,
                normalized = order.stockRequirements.normalized, promise = order.returnPolicySourceReference != null))
    }

    public fun decodeOrder(encoded: String, expected: SalesSnapshotBinding): OwnedOrder = checkedSnapshot {
        decodeSnapshot(encoded, expected, SalesSnapshotKind.ORDER, StoredOrder.serializer()) {
            retailSnapshotVersion(it.stockRequirements?.any { allocation -> allocation.serialId != null } == true, it.priceSourceReference != null, normalized = it.normalizedStock != null, promise = it.returnPolicySourceReference != null)
        }.restore(expected)
    }

    public fun encodeCollection(collection: CollectionLock): String = checkedSnapshot {
        validateStoredCollection(collection)
        encodeSnapshot(SalesSnapshotBinding(SalesSnapshotKind.COLLECTION, collection.scope, collection.id, collection.revision),
            StoredCollection.from(collection), StoredCollection.serializer(),
            retailSnapshotVersion(!collection.stockRequirements.normalized && collection.stockRequirements.allocations.any { it.position.serialId != null }, collection.priceSourceReference != null,
                collection.attempts.any { it.resolutionObservation != null }, collection.stockRequirements.normalized, collection.returnPolicySourceReference != null))
    }

    /**
     * Intrinsic HISTORICAL ONLY validation against an independent historical row binding.
     * This does not validate current order/session/custody links, establish provenance or
     * authorization, or prove a commit. Current authoritative cross-links remain the caller's duty.
     */
    public fun decodeHistoricalCollection(
        encoded: String,
        expected: SalesSnapshotBinding,
    ): CollectionLock = checkedSnapshot {
        decodeSnapshot(encoded, expected, SalesSnapshotKind.COLLECTION, StoredCollection.serializer()) {
            retailSnapshotVersion(it.stockRequirements?.any { allocation -> allocation.serialId != null } == true, it.priceSourceReference != null, it.attempts.any { attempt -> attempt.resolutionObservation != null }, it.normalizedStock != null, it.returnPolicySourceReference != null)
        }
            .restore(expected)
    }

    /** Related snapshots must come from the same authoritative read cut, not a client cache. */
    public fun decodeCollection(
        encoded: String,
        expected: SalesSnapshotBinding,
        order: OwnedOrder,
        session: CashSession,
    ): CollectionLock = checkedSnapshot {
        decodeSnapshot(encoded, expected, SalesSnapshotKind.COLLECTION, StoredCollection.serializer()) {
            retailSnapshotVersion(it.stockRequirements?.any { allocation -> allocation.serialId != null } == true, it.priceSourceReference != null, it.attempts.any { attempt -> attempt.resolutionObservation != null }, it.normalizedStock != null, it.returnPolicySourceReference != null)
        }.restore(expected)
            .also { SalesSnapshotLinks.requireCollection(it, order, session) }
    }

    /** Standalone immutable financial document; not its mutable return entitlement or a commit receipt. */
    public fun encodePaidSale(sale: PaidSale): String = checkedSnapshot {
        validateStoredSale(sale)
        encodeSnapshot(SalesSnapshotBinding(SalesSnapshotKind.PAID_SALE, sale.scope, sale.id, PAID_SALE_REVISION),
            StoredPaidSale.from(sale), StoredPaidSale.serializer(), retailSnapshotVersion(false, sale.priceSourceReference != null, sale.payments.any { it.resolutionObservation != null }, promise = sale.returnPolicySourceReference != null))
    }

    /**
     * Only logical revision zero is valid, even if body and expected metadata agree on a later revision.
     * An insert-only store using domain+1 must independently require its row generation to be one.
     * This codec neither supplies that store mapping nor proves related rows or source provenance.
     */
    public fun decodePaidSale(encoded: String, expected: SalesSnapshotBinding): PaidSale = checkedSnapshot {
        snapshotCheck(expected.revision == PAID_SALE_REVISION, SalesSnapshotFailure.BINDING_MISMATCH)
        decodeSnapshot(encoded, expected, SalesSnapshotKind.PAID_SALE, StoredPaidSale.serializer()) {
            retailSnapshotVersion(false, it.priceSourceReference != null, it.payments.any { payment -> payment.resolutionObservation != null }, promise = it.returnPolicySourceReference != null)
        }
            .restore(expected.id, expected.scope)
    }

    public fun encodeReceiptEntitlement(entitlement: ReceiptEntitlement): String = checkedSnapshot {
        validateStoredEntitlement(entitlement)
        encodeSnapshot(SalesSnapshotBinding(SalesSnapshotKind.RECEIPT_ENTITLEMENT, entitlement.scope, entitlement.saleId, entitlement.revision),
            StoredReceiptEntitlement.from(entitlement), StoredReceiptEntitlement.serializer(),
            retailSnapshotVersion(false, entitlement.sale.priceSourceReference != null,
                entitlement.sale.payments.any { it.resolutionObservation != null }, promise = entitlement.sale.returnPolicySourceReference != null))
    }

    public fun decodeReceiptEntitlement(encoded: String, expected: SalesSnapshotBinding): ReceiptEntitlement = checkedSnapshot {
        decodeSnapshot(encoded, expected, SalesSnapshotKind.RECEIPT_ENTITLEMENT, StoredReceiptEntitlement.serializer()) {
            retailSnapshotVersion(false, it.sale.priceSourceReference != null, it.sale.payments.any { payment -> payment.resolutionObservation != null }, promise = it.sale.returnPolicySourceReference != null)
        }.restore(expected)
    }

    public fun encodeReturnClaim(claim: ReturnClaim): String = checkedSnapshot {
        validateStoredClaim(claim)
        encodeSnapshot(SalesSnapshotBinding(SalesSnapshotKind.RETURN_CLAIM, claim.scope, claim.id, claim.revision),
            StoredReturnClaim.from(claim), StoredReturnClaim.serializer())
    }

    public fun decodeReturnClaim(
        encoded: String,
        expected: SalesSnapshotBinding,
        entitlement: ReceiptEntitlement,
    ): ReturnClaim = checkedSnapshot {
        decodeSnapshot(encoded, expected, SalesSnapshotKind.RETURN_CLAIM, StoredReturnClaim.serializer()).restore(expected)
            .also { SalesSnapshotLinks.requireClaim(it, entitlement) }
    }

    public fun encodeAcceptedReturn(accepted: AcceptedReturn, entitlement: ReceiptEntitlement, claim: ReturnClaim): String = checkedSnapshot {
        SalesSnapshotLinks.requireAcceptedReturn(accepted, entitlement, claim)
        encodeSnapshot(SalesSnapshotBinding(SalesSnapshotKind.ACCEPTED_RETURN, accepted.scope, accepted.id, accepted.revision),
            StoredAcceptedReturn.from(accepted), StoredAcceptedReturn.serializer(),
            retailSnapshotVersion(false, supervised = accepted.attempts.any { it.resolutionObservation != null }))
    }

    public fun decodeAcceptedReturn(
        encoded: String,
        expected: SalesSnapshotBinding,
        entitlement: ReceiptEntitlement,
        claim: ReturnClaim,
    ): AcceptedReturn = checkedSnapshot {
        decodeSnapshot(encoded, expected, SalesSnapshotKind.ACCEPTED_RETURN, StoredAcceptedReturn.serializer()) {
            retailSnapshotVersion(false, supervised = it.attempts.any { attempt -> attempt.resolutionObservation != null })
        }.restore(expected, entitlement)
            .also { SalesSnapshotLinks.requireAcceptedReturn(it, entitlement, claim) }
    }
}

internal val salesSnapshotJson: Json = Json {
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
    encodeDefaults = true
    explicitNulls = true
    allowSpecialFloatingPointValues = false
}

@Serializable
internal data class StoredSalesScope(val organizationId: DomainId, val companyId: DomainId, val branchId: DomainId) {
    fun restore(): BranchScope = BranchScope(organizationId, companyId, branchId)

    companion object {
        fun from(scope: BranchScope): StoredSalesScope = StoredSalesScope(scope.organizationId, scope.companyId, scope.branchId)
    }
}

@Serializable
private data class StoredSnapshotEnvelope<T>(
    val schemaVersion: Int,
    val kind: String,
    val scope: StoredSalesScope,
    val id: DomainId,
    val revision: Revision,
    val state: T,
)

private fun retailSnapshotVersion(serial: Boolean, priceSources: Boolean = false, supervised: Boolean = false, normalized: Boolean = false, promise: Boolean = false): Int =
    if (promise) SalesSnapshotCodec.ORIGINAL_PROMISE_SCHEMA_VERSION
    else if (normalized) SalesSnapshotCodec.NORMALIZED_STOCK_SCHEMA_VERSION
    else if (supervised) SalesSnapshotCodec.SUPERVISED_SCHEMA_VERSION
    else if (serial || priceSources) SalesSnapshotCodec.SERIAL_SCHEMA_VERSION else SalesSnapshotCodec.SCHEMA_VERSION

private fun extendedSnapshotKind(kind: SalesSnapshotKind): Boolean = kind in setOf(
    SalesSnapshotKind.ORDER, SalesSnapshotKind.COLLECTION, SalesSnapshotKind.PAID_SALE, SalesSnapshotKind.RECEIPT_ENTITLEMENT)

private fun supervisedSnapshotKind(kind: SalesSnapshotKind): Boolean = kind in setOf(
    SalesSnapshotKind.COLLECTION, SalesSnapshotKind.PAID_SALE, SalesSnapshotKind.RECEIPT_ENTITLEMENT, SalesSnapshotKind.ACCEPTED_RETURN)

internal fun <T> encodeSnapshot(binding: SalesSnapshotBinding, value: T, serializer: KSerializer<T>,
    schemaVersion: Int = SalesSnapshotCodec.SCHEMA_VERSION): String {
    snapshotCheck(schemaVersion == SalesSnapshotCodec.SCHEMA_VERSION ||
        schemaVersion == SalesSnapshotCodec.SERIAL_SCHEMA_VERSION && extendedSnapshotKind(binding.kind) ||
        schemaVersion == SalesSnapshotCodec.SUPERVISED_SCHEMA_VERSION && supervisedSnapshotKind(binding.kind) ||
        schemaVersion == SalesSnapshotCodec.NORMALIZED_STOCK_SCHEMA_VERSION && binding.kind in setOf(SalesSnapshotKind.ORDER, SalesSnapshotKind.COLLECTION) ||
        schemaVersion == SalesSnapshotCodec.ORIGINAL_PROMISE_SCHEMA_VERSION && extendedSnapshotKind(binding.kind),
        SalesSnapshotFailure.UNSUPPORTED_VERSION)
    val envelope = StoredSnapshotEnvelope(schemaVersion, binding.kind.name,
        StoredSalesScope.from(binding.scope), binding.id, binding.revision, value)
    return salesSnapshotJson.encodeToString(StoredSnapshotEnvelope.serializer(serializer), envelope).also(::boundSnapshot)
}

internal fun <T> decodeSnapshot(
    encoded: String,
    expected: SalesSnapshotBinding,
    kind: SalesSnapshotKind,
    serializer: KSerializer<T>,
    requiredVersion: (T) -> Int = { SalesSnapshotCodec.SCHEMA_VERSION },
): T {
    boundSnapshot(encoded)
    snapshotCheck(expected.kind == kind, SalesSnapshotFailure.BINDING_MISMATCH)
    val envelopeSerializer = StoredSnapshotEnvelope.serializer(serializer)
    val envelope = salesSnapshotJson.decodeFromString(envelopeSerializer, encoded)
    snapshotCheck(envelope.schemaVersion == requiredVersion(envelope.state) &&
        (envelope.schemaVersion == SalesSnapshotCodec.SCHEMA_VERSION ||
            envelope.schemaVersion == SalesSnapshotCodec.SERIAL_SCHEMA_VERSION && extendedSnapshotKind(kind) ||
            envelope.schemaVersion == SalesSnapshotCodec.SUPERVISED_SCHEMA_VERSION && supervisedSnapshotKind(kind) ||
            envelope.schemaVersion == SalesSnapshotCodec.NORMALIZED_STOCK_SCHEMA_VERSION && kind in setOf(SalesSnapshotKind.ORDER, SalesSnapshotKind.COLLECTION) ||
            envelope.schemaVersion == SalesSnapshotCodec.ORIGINAL_PROMISE_SCHEMA_VERSION && extendedSnapshotKind(kind)),
        SalesSnapshotFailure.UNSUPPORTED_VERSION)
    snapshotCheck(envelope.kind == kind.name && envelope.scope.restore() == expected.scope &&
        envelope.id == expected.id && envelope.revision == expected.revision, SalesSnapshotFailure.BINDING_MISMATCH)
    // Reject duplicate keys, omitted fields, alternate scalar/object encodings and
    // whitespace as well as unknown fields. This format is emitted only by this codec.
    snapshotCheck(salesSnapshotJson.encodeToString(envelopeSerializer, envelope) == encoded, SalesSnapshotFailure.NON_CANONICAL)
    return envelope.state
}

private fun boundSnapshot(encoded: String) {
    snapshotCheck(encoded.length <= SalesSnapshotCodec.MAX_ENCODED_BYTES, SalesSnapshotFailure.TOO_LARGE)
    snapshotCheck(encoded.encodeToByteArray(throwOnInvalidSequence = true).size <= SalesSnapshotCodec.MAX_ENCODED_BYTES,
        SalesSnapshotFailure.TOO_LARGE)
    // Check before recursive parsing; quoted embedded pricing has its own owned bounds.
    val frames = mutableListOf<Pair<Char, Int>>()
    var quoted = false
    var escaped = false
    for (character in encoded) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{', '[' -> {
                snapshotCheck(frames.size < 48, SalesSnapshotFailure.TOO_LARGE)
                frames += character to 0
            }
            '}', ']' -> {
                snapshotCheck(frames.isNotEmpty() && frames.last().first == if (character == ']') '[' else '{',
                    SalesSnapshotFailure.MALFORMED)
                frames.removeAt(frames.lastIndex)
            }
            ',' -> if (frames.isNotEmpty() && frames.last().first == '[') {
                val next = frames.last().second + 1
                // All OUTER sales arrays have domain bounds <=1,024. Check before
                // constructing DTO/object graphs; quoted pricing is checked by its owner.
                snapshotCheck(next < 1_024, SalesSnapshotFailure.TOO_LARGE)
                frames[frames.lastIndex] = '[' to next
            }
        }
    }
    snapshotCheck(frames.isEmpty() && !quoted, SalesSnapshotFailure.MALFORMED)
}

internal fun snapshotCheck(condition: Boolean, failure: SalesSnapshotFailure = SalesSnapshotFailure.INCONSISTENT_STATE) {
    if (!condition) throw SalesSnapshotRejectedException(failure)
}

internal inline fun <T> checkedSnapshot(block: () -> T): T = try {
    block()
} catch (failure: SalesSnapshotRejectedException) {
    throw failure
} catch (_: CharacterCodingException) {
    throw SalesSnapshotRejectedException(SalesSnapshotFailure.MALFORMED)
} catch (_: SerializationException) {
    throw SalesSnapshotRejectedException(SalesSnapshotFailure.MALFORMED)
} catch (_: IllegalArgumentException) {
    throw SalesSnapshotRejectedException(SalesSnapshotFailure.INCONSISTENT_STATE)
} catch (_: ArithmeticException) {
    throw SalesSnapshotRejectedException(SalesSnapshotFailure.INCONSISTENT_STATE)
} catch (_: IllegalStateException) {
    throw SalesSnapshotRejectedException(SalesSnapshotFailure.INCONSISTENT_STATE)
}
