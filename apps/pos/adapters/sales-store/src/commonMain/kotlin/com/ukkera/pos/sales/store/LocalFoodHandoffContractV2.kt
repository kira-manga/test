package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.types.*
import com.ukkera.pos.sales.FoodCollectionStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Explicit effect/read protocol; historical command requests and durable results remain v1. */
public object LocalFoodHandoffProfileV2 {
    public val QUERY: ReadKey = ReadKey("sales.food.handoff-directory", 2)
    public const val OFFER: String = "sales.food.handoff-offer"
    public const val BEFORE: String = "sales.food.handoff-before"
    public const val EVENT: String = "sales.food.handoff-event"
    public const val INCOMING: String = "sales.food.handoff-incoming"
    public val kinds: Set<String> = setOf(OFFER, BEFORE, EVENT)
    public fun incoming(recipientId: DomainId, handoffId: DomainId): ClaimKey = ClaimKey(INCOMING, "${recipientId.value}/${handoffId.value}")
    internal fun schema(kind: String): String = "$kind/v2"
}

@Serializable public sealed interface LocalFoodHandoffQueryV2 {
    @Serializable @SerialName("RECIPIENTS")
    public data class Recipients(public val orderId: DomainId, public val expectedOrderRevision: Revision,
        public val after: DomainId? = null) : LocalFoodHandoffQueryV2
    @Serializable @SerialName("INCOMING")
    public data class Incoming(public val cursor: LocalFoodHandoffCursorV2? = null) : LocalFoodHandoffQueryV2
}
/** Opaque server-issued actor/session routing handle. Expiry needs refresh, never offer repair. */
@Serializable public data class LocalFoodHandoffCursorV2(public val phase: String, public val after: String) {
    init { require(phase in setOf("INDEXED", "LEGACY") && after.length == 64 && after.all { it in '0'..'9' || it in 'a'..'f' }) }
}
@Serializable public data class LocalFoodRecipientV2(public val actorId: DomainId, public val label: String,
    public val accountRevision: Revision, public val policyRevision: Revision)
/** No drawer/session or historical recovery IDs are exposed to a recipient. */
@Serializable public data class LocalFoodHandoffFinancialV1(public val expectation: LocalFoodHandoffFinancialExpectationV1,
    public val status: FoodCollectionStatus, public val collected: Money, public val remaining: Money,
    public val refundDue: Money? = null, public val pendingClaimCount: Int = 0, public val paymentUnresolved: Boolean = false) {
    init { require(collected.minor >= 0 && remaining.minor >= 0 && (refundDue == null || refundDue.minor >= 0) && pendingClaimCount in 0..64) }
}
@Serializable public data class LocalFoodIncomingOfferV2(public val orderId: DomainId, public val handoffId: DomainId,
    public val fromActorId: DomainId, public val recipientId: DomainId, public val orderStorageRevision: Revision,
    public val service: LocalFoodServiceV1, public val activity: LocalSalesActivity,
    public val portionCount: Int, public val offeredAt: UtcInstant, public val financial: LocalFoodHandoffFinancialV1? = null)
@Serializable public enum class LocalFoodHandoffDirectoryUnavailableV2 { TARGET_UNAVAILABLE, NOT_PRECOLLECTION, PAYMENT_UNRESOLVED, CAPACITY_REQUIRED, CURSOR_EXPIRED }
@Serializable public data class LocalFoodHandoffDirectoryResultV2(public val recipients: List<LocalFoodRecipientV2> = emptyList(),
    public val incoming: List<LocalFoodIncomingOfferV2> = emptyList(), public val nextRecipient: DomainId? = null,
    public val nextIncoming: LocalFoodHandoffCursorV2? = null, public val financial: LocalFoodHandoffFinancialV1? = null, public val unavailable: LocalFoodHandoffDirectoryUnavailableV2? = null) {
    init { require(recipients.size <= 20 && incoming.size <= 8 && (recipients.isEmpty() || incoming.isEmpty()) &&
        recipients.map { it.actorId }.distinct().size == recipients.size && incoming.map { it.handoffId }.distinct().size == incoming.size)
        require(unavailable == null || recipients.isEmpty() && incoming.isEmpty() && nextRecipient == null && nextIncoming == null && financial == null) }
}
public object LocalFoodHandoffCodecV2 {
    public fun read(query: LocalFoodHandoffQueryV2): ReadRequest = ReadRequest(LocalFoodHandoffProfileV2.QUERY,
        localSalesEncode(query, LocalFoodHandoffQueryV2.serializer(), 2_048))
    public fun result(bytes: StoredBytes): LocalFoodHandoffDirectoryResultV2 =
        localSalesDecode(bytes, LocalFoodHandoffDirectoryResultV2.serializer(), 65_536)
}
