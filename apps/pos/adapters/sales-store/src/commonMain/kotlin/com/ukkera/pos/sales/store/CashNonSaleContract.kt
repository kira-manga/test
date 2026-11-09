package com.ukkera.pos.sales.store

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.sales.CashMovementKind
import com.ukkera.pos.sales.SalesRule
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json

/** No generic customer receipt/refund, safe transfer or balance override is admitted. */
@Serializable
public enum class NonSaleCashKind {
    NON_SALE_PAY_IN, OPERATING_EXPENSE,
    /** Recognized for compatibility; new writes need a separate source-bound settlement adapter. */
    INVENTORY_SETTLEMENT;

    internal val domain: CashMovementKind get() = when (this) {
        NON_SALE_PAY_IN -> CashMovementKind.NON_SALE_PAY_IN
        OPERATING_EXPENSE -> CashMovementKind.OPERATING_EXPENSE
        INVENTORY_SETTLEMENT -> CashMovementKind.INVENTORY_SETTLEMENT
    }

    internal val reasonCode: String get() = when (this) {
        NON_SALE_PAY_IN -> "cash.non_sale.pay_in"
        OPERATING_EXPENSE -> "cash.operating_expense"
        INVENTORY_SETTLEMENT -> "cash.inventory_settlement"
    }
}

/** Version comes from CommandKey. Actor/scope/digest/authorization are deliberately absent. */
@Serializable
public data class CashNonSaleRequestV1(
    public val sessionId: DomainId,
    public val expectedDomainRevision: Revision,
    public val sourceId: DomainId,
    public val kind: NonSaleCashKind,
    public val amount: Money,
    public val evidenceId: DomainId,
    /** Human attestation time, not master ordering, authority or a commit timestamp. */
    public val occurredAt: UtcInstant,
    /** Protected free-form evidence; the authorization reason code is derived from kind. */
    public val reason: String,
)

public object CashNonSaleStoreProfile {
    public val COMMAND: CommandKey = CommandKey("sales.cash.move", 1)
    public const val SESSION_KIND: String = "sales.cash.session"
    public const val SESSION_SCHEMA: String = "sales.cash.session/v1"
    public const val MOVEMENT_KIND: String = "sales.cash.movement"
    public const val MOVEMENT_SCHEMA: String = "sales.cash.movement/v1"
    public const val CASH_SOURCE_NAMESPACE: String = "sales.cash.source"
    public const val MAX_REQUEST_BYTES: Int = 4_096
    public const val MAX_RESULT_BYTES: Int = 2_048
    /** Matches the current kernel stop-line; not the sales codec's 8 MiB ceiling. */
    public const val MAX_ROW_BYTES: Int = 65_536

    public fun sessionKey(sessionId: DomainId): AggregateKey = AggregateKey(SESSION_KIND, sessionId)
    public fun movementKey(sourceId: DomainId): AggregateKey = AggregateKey(MOVEMENT_KIND, sourceId)
    public fun cashSourceClaim(sourceId: DomainId): ClaimKey = ClaimKey(CASH_SOURCE_NAMESPACE, sourceId.value)
}

/** Kernel rows start at one; domain snapshots start at zero. Overflow/zero is never wrapped. */
public object SalesStorageRevision {
    public fun toStorage(domain: Revision): Revision = domain.next()
    public fun toDomain(storage: Revision): Revision {
        require(storage.value > 0L) { "Sales storage revision must be positive" }
        return Revision(storage.value - 1L)
    }
}

public object CashNonSaleCommandCodec {
    public fun encode(request: CashNonSaleRequestV1): StoredBytes {
        validateRequest(request)
        return encodeBounded(request, CashNonSaleRequestV1.serializer(), CashNonSaleStoreProfile.MAX_REQUEST_BYTES)
    }

    /** Canonical-only versioned DTO: reject duplicate/unknown fields rather than guessing intent. */
    public fun normalize(input: StoredBytes): StoredBytes = encode(decodeRequest(input))

    internal fun decodeRequest(input: StoredBytes): CashNonSaleRequestV1 =
        decodeBounded(input, CashNonSaleRequestV1.serializer(), CashNonSaleStoreProfile.MAX_REQUEST_BYTES).also(::validateRequest)
}

@Serializable
public enum class CashNonSaleUnsupported {
    /** A CASH_MOVE grant/source ID does not establish invoice/payable settlement authority. */
    INVENTORY_SETTLEMENT_SOURCE_REQUIRED,
    /** A complete transactional source-index adapter is not supplied by this profile. */
    PENDING_FINANCIAL_OR_CUSTODY_WORK,
    STORAGE_PROFILE_LIMIT,
    REVISION_EXHAUSTED,
    UNEXPECTED_DOMAIN_TRANSITION,
}

/** Recorded rejection is not money success; Deferred/timeout/cancellation is not this DTO. */
@Serializable
public sealed interface CashNonSaleResultV1 {
    @Serializable @SerialName("APPLIED")
    public data class Applied(
        public val sessionId: DomainId,
        public val domainRevision: Revision,
        public val sourceId: DomainId,
        public val cashLedgerRevision: Revision,
        public val expectedCash: Money,
    ) : CashNonSaleResultV1

    @Serializable @SerialName("DOMAIN_REJECTED")
    public data class DomainRejected(
        @Serializable(with = StoredSalesRuleSerializer::class) public val rule: SalesRule,
    ) : CashNonSaleResultV1

    @Serializable @SerialName("SESSION_MISSING")
    public data object SessionMissing : CashNonSaleResultV1

    @Serializable @SerialName("UNSUPPORTED_PROFILE")
    public data class Unsupported(public val reason: CashNonSaleUnsupported) : CashNonSaleResultV1
}

public object CashNonSaleResultCodec {
    public fun decode(result: StoredBytes): CashNonSaleResultV1 =
        decodeBounded(result, CashNonSaleResultV1.serializer(), CashNonSaleStoreProfile.MAX_RESULT_BYTES).also(::validateResult)

    internal fun encode(result: CashNonSaleResultV1): StoredBytes {
        validateResult(result)
        return encodeBounded(result, CashNonSaleResultV1.serializer(), CashNonSaleStoreProfile.MAX_RESULT_BYTES)
    }
}

/** Adapter-owned enum serializer; domain command/security classes are not wire-enabled. */
public object StoredSalesRuleSerializer : KSerializer<SalesRule> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("sales.cash.rule/v1", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: SalesRule): Unit = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): SalesRule {
        val name = decoder.decodeString()
        return SalesRule.entries.singleOrNull { it.name == name } ?: throw SerializationException("Unknown sales rule")
    }
}

internal val cashStoreJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = true
    encodeDefaults = true
    isLenient = false
    coerceInputValues = false
}

internal fun <T> encodeBounded(value: T, serializer: KSerializer<T>, maximum: Int): StoredBytes {
    val encoded = try {
        cashStoreJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
    } catch (_: Exception) {
        // The kernel's malformed-request boundary expects IllegalArgumentException.
        // UTF-8 errors and parser diagnostics must not escape with protected evidence.
        throw IllegalArgumentException("Cash adapter body cannot be encoded")
    }
    require(encoded.size <= maximum) { "Cash adapter profile bound exceeded" }
    return StoredBytes(encoded)
}

internal fun <T> decodeBounded(input: StoredBytes, serializer: KSerializer<T>, maximum: Int): T {
    require(input.size <= maximum) { "Cash adapter profile bound exceeded" }
    val text = try {
        input.copyBytes().decodeToString(throwOnInvalidSequence = true)
    } catch (_: Exception) {
        throw IllegalArgumentException("Cash adapter body is not UTF-8")
    }
    var depth = 0
    var quoted = false
    var escaped = false
    for (character in text) {
        if (quoted) {
            if (escaped) escaped = false
            else if (character == '\\') escaped = true
            else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{' -> { depth++; require(depth <= 8) { "Cash adapter nesting bound exceeded" } }
            '}' -> { depth--; require(depth >= 0) { "Malformed cash adapter body" } }
            '[', ']' -> throw IllegalArgumentException("Cash adapter profile does not accept arrays")
        }
    }
    require(!quoted && depth == 0) { "Malformed cash adapter body" }
    val value = try {
        cashStoreJson.decodeFromString(serializer, text)
    } catch (_: SerializationException) {
        throw IllegalArgumentException("Malformed cash adapter body")
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("Invalid cash adapter value")
    }
    require(cashStoreJson.encodeToString(serializer, value) == text) { "Cash adapter body must be canonical" }
    return value
}

private fun validateRequest(request: CashNonSaleRequestV1) {
    require(request.amount.minor > 0L) { "Cash movement must be positive" }
    require(request.reason.isNotBlank() && request.reason.length <= 256) { "Bounded actual reason required" }
}

private fun validateResult(result: CashNonSaleResultV1) {
    if (result is CashNonSaleResultV1.Applied) {
        require(result.domainRevision.value > 0L && result.domainRevision.value < Long.MAX_VALUE && result.cashLedgerRevision.value > 0L &&
            result.cashLedgerRevision <= result.domainRevision && result.expectedCash.minor >= 0L)
    }
}
