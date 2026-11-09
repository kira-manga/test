package com.ukkera.pos.database.independent.receiptselectorv2

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.AggregateKeyPage
import com.ukkera.pos.database.ClaimKey
import com.ukkera.pos.database.ClaimKeyEntry
import com.ukkera.pos.database.ClaimKeyPage
import com.ukkera.pos.database.CommandReadView
import com.ukkera.pos.database.RetainedAggregateWrite
import com.ukkera.pos.database.RetainedClaimAction
import com.ukkera.pos.database.RetainedClaimChange
import com.ukkera.pos.database.RetainedCommandEvidence
import com.ukkera.pos.database.RuntimeRetailCustomerReceiptEventLocator
import com.ukkera.pos.database.StockStreamingCommandsV2
import com.ukkera.pos.database.StoredAggregate
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptCodec
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptObservationV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptPortionV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptProfileV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptRequestV1
import com.ukkera.pos.sales.store.LocalRetailCustomerReceiptResultV1
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.seconds

/**
 * Frozen retail-receipt-selector-v2 values. The resource is an unchanged copy of
 * fixtures.json. All rows/retained pairs assembled here are explicitly INERT DATA.
 * They were not committed by a Master, admitted by native history or authorized.
 *
 * EA unit, order, assignment, authentication, policy and journal hash fillers bind
 * unasserted schema fields only. Empty source resources confer no source proof.
 * Unspecified new-write sequence values are zero DATA, not invented writer order.
 * Canonical serializers format these inputs; arithmetic/membership comes only from
 * the frozen observations and expected cuts, never the application proposer.
 */
internal object FrozenReceiptSelectorV2Data {
    private val bridge = ReceiptSelectorProductionDataBridge
    internal val root: JsonObject = checkNotNull(javaClass.getResourceAsStream(
        "/independent/receiptselectorv2/fixtures.json")).use {
        bridge.json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
    }.also {
        check(it.text("schema") == "selected-sale-receipt-design-fixtures-v2")
        check(it.text("status") == "FROZEN_READY_NOT_EXECUTED")
    }
    private val aliases = root.getValue("idAliases").jsonObject
    internal val scope = BranchScope(id("organization"), id("company"), id("branch"))
    internal val actorA = id("A")
    internal val actorB = id("B")
    internal val actorD = id("D")
    internal val selectedSaleId = id("S")
    internal val unitId = DomainId("a9000000-0000-4000-8000-000000000001")
    internal const val ZONE = "Africa/Cairo"
    internal val profiles = listOf(0, 65, 513)
    private val portionSerializer = ListSerializer(LocalRetailCustomerReceiptPortionV1.serializer())
    private val assignmentId = DomainId("a9000000-0000-4000-8000-000000000002")

    internal fun id(alias: String): DomainId = DomainId(aliases.text(alias))
    internal fun operation(alias: String): OperationId = OperationId(id(alias).value)
    internal fun instant(value: String): UtcInstant = UtcInstant.parseCanonical(value)
    internal fun each(portionId: DomainId, amount: Long): LocalRetailCustomerReceiptPortionV1 =
        LocalRetailCustomerReceiptPortionV1(portionId, Quantity(unitId, amount, 0))

    internal fun selected(cut: String = "base"): ReceiptSelectorCutData {
        val fixture = root.getValue("F-SELECT").jsonObject
        val original = fixture.getValue("original").jsonObject
        val sale = saleData(id("S"), actorA, operation("Finalize-S"), original.text("finalizedAt"),
            original.text("authorityEpoch").toLong(), original.text("journalSequence").toLong(),
            original.getValue("sold").jsonObject.entries.map { (alias, amount) -> each(id(alias), amount.jsonPrimitive.content.toLong()) })
        val observations = fixture.getValue("events").jsonArray.map { raw ->
            val event = raw.jsonObject
            ReceiptSelectorObservation(operation(event.text("operation")), id(event.text("actor")),
                LocalRetailCustomerReceiptRequestV1(sale.saleId, Revision(event.getValue("headRevision").jsonPrimitive.int.toLong() - 1),
                    instant(event.text("receivedAt")), event.text("receiptTimeZoneId"), observation(event.text("observation")),
                    event.getValue("portions").jsonObject.entries.map { (alias, amount) -> each(id(alias), amount.jsonPrimitive.content.toLong()) }),
                instant(event.text("recordedAt")), JournalSequence(event.text("journalSequence").toLong()))
        }.toMutableList()
        when (cut) {
            "base" -> Unit
            "W-wins" -> observations += selectedWrite("W")
            "W-ALT-wins" -> observations += selectedWrite("W-ALT")
            "W-then-L" -> { observations += selectedWrite("W"); observations += selectedWrite("L") }
            else -> error("Unknown frozen selected cut")
        }
        return assemble(sale, observations)
    }

    internal fun selectedWrite(alias: String): ReceiptSelectorObservation {
        val write = root.getValue("F-SELECT").jsonObject.getValue("writes").jsonObject.getValue(alias).jsonObject
        val payload = write.getValue("payload").jsonObject
        return ReceiptSelectorObservation(operation(alias), id(write.text("actor")),
            LocalRetailCustomerReceiptRequestV1(id(payload.text("saleId")), Revision(payload.text("expectedHeadRevision").toLong()),
                instant(payload.text("receivedAt")), payload.text("receiptTimeZoneId"), observation(payload.text("observation")),
                payload.getValue("portions").jsonArray.map { raw ->
                    val portion = raw.jsonObject
                    each(id(portion.text("portionId")), portion.text("quantity").toLong())
                }), instant(write.text("fixtureMasterCommitAt")), JournalSequence(0))
    }

    internal fun empty(): ReceiptSelectorCutData {
        val fixture = root.getValue("F-EMPTY").jsonObject
        val sale = saleData(id("N"), actorA, operation("Finalize-N"), fixture.text("finalizedAt"),
            fixture.text("originalAuthorityEpoch").toLong(), fixture.text("originalFinalizeJournalSequence").toLong(),
            listOf(each(id("P-N"), 2)))
        return assemble(sale, emptyList())
    }

    internal fun cap64(): ReceiptSelectorCutData {
        val fixture = root.getValue("F-CAP64").jsonObject
        val sale = saleData(id("S-CAP64"), actorA, operation("Finalize-C64"), fixture.text("originalFinalizedAt"),
            fixture.text("originalAuthorityEpoch").toLong(), fixture.text("originalFinalizeJournalSequence").toLong(),
            listOf(each(id("P-C64-A"), 64), each(id("P-C64-B"), 1)))
        val base = instant("2026-11-02T10:00:00Z")
        return assemble(sale, (1..64).map { ordinal ->
            val operation = OperationId("a2000000-0000-4000-8000-${ordinal.toString(16).padStart(12, '0')}")
            ReceiptSelectorObservation(operation, actorA,
                LocalRetailCustomerReceiptRequestV1(sale.saleId, Revision(ordinal.toLong() - 1),
                    UtcInstant(base.value + (2L * ordinal).seconds), ZONE,
                    LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER,
                    listOf(each(id("P-C64-A"), 1))),
                UtcInstant(base.value + (2L * ordinal + 1).seconds), JournalSequence(2000L + ordinal))
        })
    }

    internal fun cap256(includeSecond: Boolean = false): ReceiptSelectorCutData {
        val fixture = root.getValue("F-CAP256").jsonObject
        val sale = saleData(id("S-CAP256"), actorA, operation("Finalize-C256"), fixture.text("originalFinalizedAt"),
            fixture.text("originalAuthorityEpoch").toLong(), fixture.text("originalFinalizeJournalSequence").toLong(),
            (1..256).map { each(cap256Portion(it), 2) })
        val first = fixture.getValue("firstEvent").jsonObject
        val observations = mutableListOf(ReceiptSelectorObservation(operation("C256-E1"), actorA,
            LocalRetailCustomerReceiptRequestV1(sale.saleId, Revision(0), instant(first.text("receivedAt")), ZONE,
                LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER, listOf(each(cap256Portion(1), 1))),
            instant(first.text("recordedAt")), JournalSequence(first.text("journalSequence").toLong())))
        if (includeSecond) {
            val next = fixture.getValue("permittedNext").jsonObject
            observations += ReceiptSelectorObservation(operation("C256-E2"), actorA,
                LocalRetailCustomerReceiptRequestV1(sale.saleId, Revision(next.text("expectedHeadRevision").toLong()),
                    instant(next.text("receivedAt")), ZONE, LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER,
                    listOf(each(cap256Portion(256), 1))), instant(next.text("fixtureMasterCommitAt")), JournalSequence(0))
        }
        return assemble(sale, observations)
    }

    internal fun cap256Portion(ordinal: Int): DomainId =
        DomainId("a1000000-0000-4000-8000-${ordinal.toString(16).padStart(12, '0')}")

    internal fun growth(profile: Int, schedule: String = "RSEL-01-02-03-06-07"): Sequence<ReceiptSelectorGrowthObservation> {
        require(profile in profiles)
        val phases = root.getValue("F-GROWTH").jsonObject.getValue("growthPhases").jsonObject.getValue(schedule).jsonArray
        return sequence {
            for (saleOrdinal in 1..profile) {
                val phase = phases[if (saleOrdinal <= 65) 0 else 1].jsonObject
                val base = instant(phase.text("phaseBase"))
                val suffix = saleOrdinal.toString(16).padStart(8, '0') + "0000"
                val saleId = DomainId("d0000000-0000-4000-8000-$suffix")
                val portionId = DomainId("c0000000-0000-4000-8000-$suffix")
                val finalize = OperationId("e0000000-0000-4000-8000-$suffix")
                for (eventOrdinal in 1..64) {
                    val operation = OperationId("f0000000-0000-4000-8000-${saleOrdinal.toString(16).padStart(8, '0')}${eventOrdinal.toString(16).padStart(4, '0')}")
                    val request = LocalRetailCustomerReceiptRequestV1(saleId, Revision(eventOrdinal.toLong() - 1),
                        UtcInstant(base.value + (2L * eventOrdinal).seconds), ZONE,
                        LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER, listOf(each(portionId, 1)))
                    yield(ReceiptSelectorGrowthObservation(saleOrdinal, eventOrdinal, actorB, finalize,
                        JournalSequence(saleOrdinal.toLong()), operation, request,
                        UtcInstant(base.value + (2L * eventOrdinal + 1).seconds)))
                }
            }
        }
    }

    internal fun authorization(actor: DomainId): JsonObject = buildJsonObject {
        put("actorId", actor.value)
        put("authenticationId", authentication(actor).value)
        put("generation", "0")
        put("policyRevision", "0")
    }

    internal fun authentication(actor: DomainId): DomainId = DomainId(
        "a9000000-0000-4000-8000-${when (actor) { actorA -> "000000000010"; actorB -> "000000000011"; actorD -> "000000000012"; else -> error("Unknown data actor") }}")

    internal fun headKey(saleId: DomainId): AggregateKey = AggregateKey(LocalRetailCustomerReceiptProfileV1.HEAD, saleId)
    internal fun eventKey(operation: OperationId): AggregateKey = AggregateKey(LocalRetailCustomerReceiptProfileV1.EVENT, DomainId(operation.value))
    internal fun beforeKey(operation: OperationId): AggregateKey = AggregateKey(LocalRetailCustomerReceiptProfileV1.HEAD_BEFORE, DomainId(operation.value))
    internal fun headClaim(saleId: DomainId): ClaimKey = ClaimKey(LocalRetailCustomerReceiptProfileV1.NAMESPACE, "head/${saleId.value}")
    internal fun eventClaim(operation: OperationId): ClaimKey = ClaimKey(LocalRetailCustomerReceiptProfileV1.NAMESPACE, "event/${operation.value}")
    internal fun membershipPrefix(saleId: DomainId): String = "sale/${saleId.value}/event/"
    internal fun membershipClaim(saleId: DomainId, operation: OperationId): ClaimKey =
        ClaimKey(LocalRetailCustomerReceiptProfileV1.NAMESPACE, membershipPrefix(saleId) + operation.value)

    private fun saleData(saleId: DomainId, collector: DomainId, finalize: OperationId, paidAt: String,
        epoch: Long, sequence: Long, portions: List<LocalRetailCustomerReceiptPortionV1>): ReceiptSelectorSaleData {
        val coordinates = buildJsonObject {
            put("schema", LocalRetailCustomerReceiptProfileV1.SOURCE_SCHEMA)
            put("scope", bridge.json.encodeToJsonElement(BranchScope.serializer(), scope))
            put("saleId", saleId.value)
            put("orderId", saleId.value) // Inert unasserted schema carrier, not an order original.
            put("originalCollectorId", collector.value)
            put("saleGeneration", "1")
            put("saleSchema", "sales.paid_sale/v1")
            put("saleDigest", sha256("DATA sale ${saleId.value}".encodeToByteArray()).value)
            put("finalizeOperationId", finalize.value)
            put("finalizeActorId", collector.value)
            put("finalizeCommand", StockStreamingCommandsV2.COLLECTION.name)
            put("finalizeVersion", StockStreamingCommandsV2.COLLECTION.version)
            put("finalizeSemanticDigest", sha256("DATA Finalize ${finalize.value}".encodeToByteArray()).value)
            put("finalizeAssignmentId", assignmentId.value)
            put("finalizeAuthorityEpoch", epoch.toString())
            put("finalizeSequence", sequence.toString())
            put("paidFinalizeAt", paidAt)
        }
        return ReceiptSelectorSaleData(scope, saleId, collector, finalize, instant(paidAt),
            portions.sortedBy { it.portionId.value }, coordinates)
    }

    private fun assemble(sale: ReceiptSelectorSaleData, observations: List<ReceiptSelectorObservation>): ReceiptSelectorCutData {
        val cut = ReceiptSelectorCutData(sale, bridge.original(sale.coordinates, sale.sold), observations)
        val cumulative = mutableMapOf<DomainId, Long>()
        var previousHead: StoredBytes? = null
        var previousEvent: StoredBytes? = null
        for ((index, input) in observations.withIndex()) {
            check(input.request.saleId == sale.saleId && input.request.expectedHeadRevision == Revision(index.toLong()))
            for (portion in input.request.portions) cumulative[portion.portionId] =
                Math.addExact(cumulative[portion.portionId] ?: 0L, portion.quantity.scaled)
            val head = buildJsonObject {
                put("schema", LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA)
                put("source", sale.coordinates)
                put("headRevision", (index + 1).toString())
                put("eventOperationIds", JsonArray(observations.take(index + 1).map { JsonPrimitive(it.operation.value) }))
                put("recordedPortions", bridge.json.encodeToJsonElement(portionSerializer,
                    cumulative.entries.sortedBy { it.key.value }.map { each(it.key, it.value) }))
                put("lastOperationId", input.operation.value)
            }
            val headBytes = bridge.canonical("LocalRetailCustomerReceiptHeadV1", head)
            val result = LocalRetailCustomerReceiptResultV1.Applied(sale.saleId, input.operation, Revision(index.toLong() + 1),
                input.request.receivedAt, input.request.receiptTimeZoneId, input.recordedAt)
            val normalized = LocalRetailCustomerReceiptCodec.encode(input.request)
            val digest = semanticDigest(sale.scope, input.actor, LocalRetailCustomerReceiptCodec.COMMAND, normalized)
            val event = buildJsonObject {
                put("schema", LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA)
                put("operationId", input.operation.value)
                put("actorId", input.actor.value)
                put("semanticDigest", digest.value)
                put("authorization", authorization(input.actor))
                put("observation", input.request.observation.name)
                put("portions", bridge.json.encodeToJsonElement(portionSerializer, input.request.portions))
                put("receivedAt", input.request.receivedAt.canonicalString())
                put("receiptTimeZoneId", input.request.receiptTimeZoneId)
                put("recordedAt", input.recordedAt.canonicalString())
                put("beforeHeadGeneration", if (index == 0) null else index.toString())
                put("beforeHeadDigest", previousHead?.let { sha256(it.copyBytes()).value })
                put("beforeHeadOperationId", observations.getOrNull(index - 1)?.operation?.value)
                put("beforeEventDigest", previousEvent?.let { sha256(it.copyBytes()).value })
                put("sourceRecoveryResources", JsonArray(emptyList()))
                put("after", head)
                put("result", bridge.json.encodeToJsonElement(LocalRetailCustomerReceiptResultV1.Applied.serializer(), result))
            }
            val eventBytes = bridge.canonical("LocalRetailCustomerReceiptEventV1", event)
            val writes = mutableListOf(
                RetainedAggregateWrite(headKey(sale.saleId), Revision(index.toLong() + 1), LocalRetailCustomerReceiptProfileV1.HEAD_SCHEMA,
                    headBytes, sha256(headBytes.copyBytes())),
                RetainedAggregateWrite(eventKey(input.operation), Revision(1), LocalRetailCustomerReceiptProfileV1.EVENT_SCHEMA,
                    eventBytes, sha256(eventBytes.copyBytes())),
            )
            previousHead?.let { before ->
                writes += RetainedAggregateWrite(beforeKey(input.operation), Revision(1), LocalRetailCustomerReceiptProfileV1.HEAD_BEFORE_SCHEMA,
                    before, sha256(before.copyBytes()))
            }
            val claims = mutableListOf(eventClaim(input.operation), membershipClaim(sale.saleId, input.operation))
            if (index == 0) claims += headClaim(sale.saleId)
            val resultBytes = StoredBytes(bridge.json.encodeToString(LocalRetailCustomerReceiptResultV1.serializer(), result).encodeToByteArray())
            cut.originals[input.operation] = RetainedCommandEvidence(sale.scope, input.operation, input.actor,
                LocalRetailCustomerReceiptCodec.COMMAND, assignmentId, Revision(1), input.sequence, input.recordedAt,
                authentication(input.actor), Revision(0), digest, "committed", normalized, resultBytes,
                sha256(resultBytes.copyBytes()), sha256("DATA previous journal".encodeToByteArray()),
                sha256("DATA journal ${input.operation.value}".encodeToByteArray()), writes,
                claims.map { RetainedClaimChange(RetainedClaimAction.ACQUIRE, it, sale.saleId) }, emptyList())
            for (write in writes) cut.rows[write.key] = StoredAggregate(write.key, write.storageGeneration,
                write.schema, write.payload, write.payloadDigest)
            for (claim in claims) cut.claims[claim] = sale.saleId
            previousHead = headBytes
            previousEvent = eventBytes
        }
        cut.members = observations.map { input ->
            val row = checkNotNull(cut.rows[eventKey(input.operation)])
            RuntimeRetailCustomerReceiptEventLocator(row.key, row.revision, row.schema, row.digest)
        }.sortedBy { it.key.id.value }
        return cut
    }

    private fun observation(value: String): LocalRetailCustomerReceiptObservationV1 = LocalRetailCustomerReceiptObservationV1.valueOf(value)
}

internal data class ReceiptSelectorSaleData(val scope: BranchScope, val saleId: DomainId, val collector: DomainId,
    val finalize: OperationId, val paidAt: UtcInstant, val sold: List<LocalRetailCustomerReceiptPortionV1>, val coordinates: JsonObject)

internal data class ReceiptSelectorObservation(val operation: OperationId, val actor: DomainId,
    val request: LocalRetailCustomerReceiptRequestV1, val recordedAt: UtcInstant, val sequence: JournalSequence)

internal data class ReceiptSelectorGrowthObservation(val saleOrdinal: Int, val eventOrdinal: Int, val actor: DomainId,
    val finalize: OperationId, val originalSequence: JournalSequence, val operation: OperationId,
    val request: LocalRetailCustomerReceiptRequestV1, val recordedAt: UtcInstant)

internal class ReceiptSelectorCutData(val sale: ReceiptSelectorSaleData, val original: Any, val observations: List<ReceiptSelectorObservation>) {
    internal val rows = mutableMapOf<AggregateKey, StoredAggregate>()
    internal val claims = mutableMapOf<ClaimKey, DomainId>()
    internal val originals = mutableMapOf<OperationId, RetainedCommandEvidence>()
    internal var members: List<RuntimeRetailCustomerReceiptEventLocator>? = emptyList()

    internal fun snapshot(): List<Any?> = listOf(rows.mapValues { (_, row) ->
        listOf(row.key, row.revision, row.schema, row.digest, row.payload)
    }, claims.toMap(), originals.toMap(), members?.toList())
}

/**
 * Controlled selected membership and literal retained DATA, never native admission.
 * G0/G65/G513 select the full unchanged lazy unrelated fixture family. No unrelated
 * event is silently dropped from that family, but it is not durable growth.
 * A global browse or unrelated point request is a test failure, not a prefix response.
 */
internal class ControlledReceiptSelectorView(internal val cut: ReceiptSelectorCutData, internal val growthProfile: Int) : CommandReadView {
    override val scope: BranchScope = cut.sale.scope
    internal val aggregateReads = mutableListOf<AggregateKey>()
    internal val originalReads = mutableListOf<OperationId>()
    internal var membershipReads = 0
    internal var globalScans = 0
    internal val claimRanges = mutableListOf<Pair<String, Int>>()
    internal val unrelatedData: Sequence<ReceiptSelectorGrowthObservation> get() = FrozenReceiptSelectorV2Data.growth(growthProfile)

    init { require(growthProfile in FrozenReceiptSelectorV2Data.profiles) }

    override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        aggregateReads += key
        check(!key.id.value.startsWith("d0000000-") && !key.id.value.startsWith("f0000000-")) {
            "Selected evidence requested an unrelated DATA point"
        }
        return cut.rows[key]
    }

    override suspend fun claim(key: ClaimKey): DomainId? = cut.claims[key]

    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? {
        originalReads += operationId
        return cut.originals[operationId]
    }

    override suspend fun retailCustomerReceiptEventMembership(saleId: DomainId): List<RuntimeRetailCustomerReceiptEventLocator>? {
        check(saleId == cut.sale.saleId)
        membershipReads++
        return cut.members?.toList()
    }

    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
        globalScans++
        error("Global aggregate census is not a selected DATA read")
    }

    override suspend fun claimKeys(namespace: String, prefix: String, after: String?, limit: Int): ClaimKeyPage {
        check(namespace == LocalRetailCustomerReceiptProfileV1.NAMESPACE &&
            prefix == FrozenReceiptSelectorV2Data.membershipPrefix(cut.sale.saleId))
        claimRanges += prefix to limit
        val entries = cut.claims.entries.filter { it.key.namespace == namespace && it.key.key.startsWith(prefix) &&
            (after == null || it.key.key > after) }.sortedBy { it.key.key }.map { ClaimKeyEntry(it.key, it.value) }
        val selected = entries.take(limit)
        return ClaimKeyPage(selected, if (entries.size > limit) selected.last().key.key else null)
    }
}

internal fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
