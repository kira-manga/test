package com.ukkera.pos.database.independent.arrivalstockitemabsence

import com.ukkera.pos.database.AggregateKey
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.StockIndexIdentityV2
import com.ukkera.pos.database.StockIndexRecordV2
import com.ukkera.pos.database.StockReadEvidenceV1
import com.ukkera.pos.database.StockReadObservations
import com.ukkera.pos.database.StockRelationalKindsV2
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.StreamedProducerIdentityV2
import com.ukkera.pos.database.StreamedSourceObservationV2
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlin.test.assertNotNull

/** Literal codec scaffolding only; no installed activation, native original or authority. */
internal val ASIA_SCOPE = BranchScope(
    DomainId("00000000-0000-4000-8000-000000000001"),
    DomainId("8891f6af-3003-4a10-a40c-56830e000324"),
    DomainId("17256bce-b955-4c06-898f-b740d9c1f3de"),
)
internal val ASIA_SKU_A = DomainId("ee3fad73-3a68-4225-a5ec-701b656b3935")
internal val ASIA_SKU_B = DomainId("62260de0-2d68-4b23-ae54-06023d543c9b")
internal val ASIA_ITEM = AggregateKey(StockRelationalKindsV2.ITEM, ASIA_SKU_A)
internal val ASIA_NON_STOCK = AggregateKey("qualification.non-stock-service", ASIA_SKU_A)
internal val ASIA_LOT = DomainId("103af92c-c72c-4f2e-a80d-ca10ce2d9bed")
internal val ASIA_WAREHOUSE = DomainId("104f435c-fda2-4184-949f-3f661fe73dd3")
internal val ASIA_PRODUCER = StreamedProducerIdentityV2(
    OperationId("6d78d48c-e301-40fe-9282-0526c8f8c8a8"),
    CommandKey("qualification.arrival-stock-item-absence", 1),
    DomainId("bd706b87-eaf0-45a9-8212-0f40f038eafe"),
    sha256("synthetic literal stock-read vector".encodeToByteArray()),
)

internal fun asiaCurrent(
    key: AggregateKey = ASIA_ITEM,
    generation: Long = 7,
    text: String = "synthetic literal row; not a decoded stock business record",
): StreamedSourceObservationV2.Current {
    val payload = StoredBytes(text.encodeToByteArray())
    return StreamedSourceObservationV2.Current(
        key, Revision(generation), StockRelationalKindsV2.schema(key.kind), payload,
        sha256(payload.copyBytes()),
    )
}

internal fun asiaObservations(sources: List<StreamedSourceObservationV2>): StockReadObservations =
    assertNotNull(StockReadEvidenceV1.decodeChanges(
        ASIA_SCOPE, ASIA_PRODUCER,
        StockReadEvidenceV1.proposedChanges(ASIA_SCOPE, ASIA_PRODUCER, sources),
    ))

internal fun asiaPosition(number: Int = 20): StockIndexRecordV2.Position {
    val key = AggregateKey(StockRelationalKindsV2.POSITION,
        DomainId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"))
    val source = asiaCurrent(key)
    return StockIndexRecordV2.Position(
        StockIndexIdentityV2(key, source.generation, ASIA_PRODUCER.operationId, source.payloadHash),
        ASIA_SKU_B, ASIA_WAREHOUSE, 1, null, 1, ASIA_LOT, null, null,
    )
}

internal fun asiaValue(): StockIndexRecordV2.Value {
    val key = AggregateKey(StockRelationalKindsV2.VALUE_EVENT,
        DomainId("00000000-0000-4000-8000-000000000030"))
    val source = asiaCurrent(key)
    return StockIndexRecordV2.Value(
        StockIndexIdentityV2(key, source.generation, ASIA_PRODUCER.operationId, source.payloadHash),
        ASIA_WAREHOUSE, ASIA_SKU_B, null, null, null, null, 0,
    )
}

/** Input corruption offsets in the existing literal wire; never an alternative decoder. */
internal fun asiaInt(bytes: ByteArray, offset: Int): Int =
    (0 until 4).fold(0) { value, at -> (value shl 8) or (bytes[offset + at].toInt() and 255) }

internal fun asiaSourceTagOffset(bytes: ByteArray): Int = 4 + asiaInt(bytes, 0) + 4

internal fun asiaPutInt(bytes: ByteArray, offset: Int, value: Int) {
    repeat(4) { at -> bytes[offset + at] = (value ushr (24 - at * 8)).toByte() }
}

internal fun asiaPutLong(bytes: ByteArray, offset: Int, value: Long) {
    repeat(8) { at -> bytes[offset + at] = (value ushr (56 - at * 8)).toByte() }
}
