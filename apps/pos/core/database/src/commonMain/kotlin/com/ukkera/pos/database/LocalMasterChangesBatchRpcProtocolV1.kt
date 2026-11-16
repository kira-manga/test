package com.ukkera.pos.database

import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import dev.p2pkit.rpc.RpcProcedure
import dev.p2pkit.rpc.RpcRetrySafety
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.json.Json

/** Additive coherent batch route, installed only for the closed LAN_BUSINESS profile. */
public object LocalMasterChangesBatchRpcProtocolV1 {
    public val SNAPSHOT: RpcProcedure<LocalMasterRpcChangesBatchRequestV1, LocalMasterRpcChangesBatchResponseV1, LocalMasterChangesFailureV1> =
        RpcProcedure(
            name = "pos.changes.snapshot-batch", version = 1,
            request = LocalMasterRpcChangesBatchRequestV1.serializer(),
            response = LocalMasterRpcChangesBatchResponseV1.serializer(),
            applicationError = LocalMasterRpcChangesFailureSerializerV1,
            retrySafety = RpcRetrySafety.NeverReinvoke,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.MAXIMUM_WIRE_BYTES,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.MAXIMUM_WIRE_BYTES,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.ERROR_LIMIT_BYTES,
        )

    public fun capture(actorId: DomainId, reply: LocalMasterChangesBatchReplyV1): LocalMasterRpcChangesBatchResponseV1 =
        LocalMasterRpcChangesBatchResponseV1(actorId, when (reply) {
            is LocalMasterChangesBatchReplyV1.Snapshot -> LocalMasterRpcChangesBatchValueV1.Snapshot(reply.snapshots.map {
                LocalMasterChangesRpcProtocolV1.capture(actorId, it).value as LocalMasterRpcChangesValueV1.Snapshot
            })
            is LocalMasterChangesBatchReplyV1.Unavailable -> LocalMasterRpcChangesBatchValueV1.Unavailable(reply.reason)
        })
}

@Serializable(with = LocalMasterRpcChangesBatchRequestSerializerV1::class)
public class LocalMasterRpcChangesBatchRequestV1 private constructor(
    access: ByteArray,
    public val selections: List<ReadRequest>,
    public val bindingDigest: PayloadDigest,
) : LocalMasterRpcOwnedRequestV1(access) {
    public companion object {
        public fun capture(credentials: LocalGatewayCredentials, selections: List<ReadRequest>): LocalMasterRpcChangesBatchRequestV1 {
            requireChangesBatchSelections(selections)
            val captured = selections.map { selection ->
                val bytes = selection.payload.copyBytes()
                try { ReadRequest(selection.query.copy(), StoredBytes(bytes)) } finally { bytes.fill(0) }
            }
            val digest = batchRequestDigest(captured)
            return credentials.bearer.useBytes { LocalMasterRpcChangesBatchRequestV1(it, captured, digest) }
        }

        internal fun decoded(access: ByteArray, body: LocalMasterRpcChangesBatchBodyV1): LocalMasterRpcChangesBatchRequestV1 {
            val selections = body.selections.map { it.selection() }
            requireChangesBatchSelections(selections)
            val digest = batchRequestDigest(selections)
            return LocalMasterRpcChangesBatchRequestV1(access, selections, digest)
        }
    }
}

@Serializable
internal data class LocalMasterRpcChangesSelectionV1(
    val queryName: String,
    val queryVersion: Int,
    @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) val payload: StoredBytes,
) {
    fun selection(): ReadRequest = ReadRequest(ReadKey(queryName, queryVersion), payload)
}

@Serializable
internal data class LocalMasterRpcChangesBatchBodyV1(
    @Serializable(with = LocalMasterRpcChangesSelectionListSerializerV1::class)
    val selections: List<LocalMasterRpcChangesSelectionV1>,
)

public object LocalMasterRpcChangesBatchRequestSerializerV1 : KSerializer<LocalMasterRpcChangesBatchRequestV1> by ownedRpcRequestSerializer(
    "com.ukkera.pos.database.LocalMasterRpcChangesBatchRequestV1", "request", LocalMasterRpcChangesBatchBodyV1.serializer(),
    body = { request: LocalMasterRpcChangesBatchRequestV1 -> LocalMasterRpcChangesBatchBodyV1(request.selections.map {
        LocalMasterRpcChangesSelectionV1(it.query.name, it.query.version, it.payload)
    }) },
    construct = { access, body -> LocalMasterRpcChangesBatchRequestV1.decoded(access, body) },
)

@Serializable
public data class LocalMasterRpcChangesBatchResponseV1(
    public val requestingActorId: DomainId,
    public val value: LocalMasterRpcChangesBatchValueV1,
) {
    override fun toString(): String = "LocalMasterRpcChangesBatchResponseV1([REDACTED])"
}

@Serializable
public sealed interface LocalMasterRpcChangesBatchValueV1 {
    @Serializable @SerialName("SNAPSHOT")
    public data class Snapshot(
        @Serializable(with = LocalMasterRpcChangesSnapshotListSerializerV1::class)
        public val snapshots: List<LocalMasterRpcChangesValueV1.Snapshot>,
    ) : LocalMasterRpcChangesBatchValueV1 {
        override fun toString(): String = "LocalMasterRpcChangesBatchValueV1.Snapshot([REDACTED])"
    }

    @Serializable @SerialName("UNAVAILABLE")
    public data class Unavailable(
        @Serializable(with = LocalMasterRpcChangesFailureSerializerV1::class)
        public val reason: LocalMasterChangesFailureV1,
    ) : LocalMasterRpcChangesBatchValueV1
}

private object LocalMasterRpcChangesSelectionListSerializerV1 : KSerializer<List<LocalMasterRpcChangesSelectionV1>> by
    boundedChangesBatchList(LocalMasterRpcChangesSelectionV1.serializer(), { it.payload.size }, LOCAL_MASTER_CHANGES_BATCH_MAX_SELECTION_BYTES_V1)

private object LocalMasterRpcChangesSnapshotListSerializerV1 : KSerializer<List<LocalMasterRpcChangesValueV1.Snapshot>> by
    boundedChangesBatchList(LocalMasterRpcChangesValueV1.Snapshot.serializer(), { it.payload.size }, LOCAL_MASTER_CHANGES_BATCH_MAX_PROJECTION_BYTES_V1)

/** Rejects count and aggregate bounds during decoding, before accepting another payload. */
@OptIn(ExperimentalSerializationApi::class)
private fun <T> boundedChangesBatchList(element: KSerializer<T>, payloadSize: (T) -> Int, maximumBytes: Int): KSerializer<List<T>> =
    object : KSerializer<List<T>> {
        private val delegate = ListSerializer(element)
        override val descriptor = delegate.descriptor
        override fun serialize(encoder: Encoder, value: List<T>) {
            require(value.size in 1..LOCAL_MASTER_CHANGES_BATCH_MAX_SELECTORS_V1)
            require(value.sumOf { payloadSize(it).toLong() } <= maximumBytes)
            delegate.serialize(encoder, value)
        }

        override fun deserialize(decoder: Decoder): List<T> {
            val result = ArrayList<T>()
            var bytes = 0L
            decoder.decodeStructure(descriptor) {
                fun append(index: Int) {
                    if (index != result.size || index >= LOCAL_MASTER_CHANGES_BATCH_MAX_SELECTORS_V1)
                        throw SerializationException("Changes batch count exceeds bounds")
                    val value = decodeSerializableElement(descriptor, index, element)
                    bytes += payloadSize(value)
                    if (bytes > maximumBytes) throw SerializationException("Changes batch payload exceeds bounds")
                    result.add(value)
                }
                if (decodeSequentially()) {
                    val count = decodeCollectionSize(descriptor)
                    if (count !in 1..LOCAL_MASTER_CHANGES_BATCH_MAX_SELECTORS_V1)
                        throw SerializationException("Changes batch count exceeds bounds")
                    repeat(count, ::append)
                } else while (true) {
                    val index = decodeElementIndex(descriptor)
                    if (index == CompositeDecoder.DECODE_DONE) break
                    append(index)
                }
            }
            if (result.isEmpty()) throw SerializationException("Changes batch is empty")
            return result
        }
    }

private val changesBatchBindingJson = Json { encodeDefaults = true }

private fun batchRequestDigest(selections: List<ReadRequest>): PayloadDigest {
    val body = LocalMasterRpcChangesBatchBodyV1(selections.map { LocalMasterRpcChangesSelectionV1(it.query.name, it.query.version, it.payload) })
    val bytes = changesBatchBindingJson.encodeToString(LocalMasterRpcChangesBatchBodyV1.serializer(), body).encodeToByteArray()
    return try { sha256(bytes) } finally { bytes.fill(0) }
}

private fun requireChangesBatchSelections(selections: List<ReadRequest>) {
    require(selections.size in 1..LOCAL_MASTER_CHANGES_BATCH_MAX_SELECTORS_V1) { "Changes batch count exceeds bounds" }
    require(selections.sumOf { it.payload.size.toLong() } <= LOCAL_MASTER_CHANGES_BATCH_MAX_SELECTION_BYTES_V1) {
        "Changes batch payload exceeds bounds"
    }
}
