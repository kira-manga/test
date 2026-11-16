package com.ukkera.pos.database

import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import dev.p2pkit.rpc.RpcProcedure
import dev.p2pkit.rpc.RpcRetrySafety
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bounded typed-view synchronization. The retained route fixes the workflow profile; a
 * request contains only a selector and inert progress, never an actor, grant or audience.
 */
public object LocalMasterChangesRpcProtocolV1 {
    public class Procedures internal constructor(prefix: String) {
        public val SNAPSHOT: RpcProcedure<LocalMasterRpcChangesRequestV1, LocalMasterRpcChangesResponseV1, LocalMasterChangesFailureV1> =
            procedure("$prefix.snapshot")
        public val PULL: RpcProcedure<LocalMasterRpcChangesRequestV1, LocalMasterRpcChangesResponseV1, LocalMasterChangesFailureV1> =
            procedure("$prefix.pull")
    }

    private val routes = LocalMasterRpcWorkflowProfile.entries.associateWith { profile ->
        Procedures(when (profile) {
            LocalMasterRpcWorkflowProfile.SUPPLIERS -> "pos.supplier.changes"
            LocalMasterRpcWorkflowProfile.WAREHOUSES -> "pos.warehouse.changes"
            LocalMasterRpcWorkflowProfile.REPORTS -> "pos.report.changes"
            LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS -> "pos.stock-transfer.changes"
            LocalMasterRpcWorkflowProfile.KITCHEN -> "pos.kitchen.changes"
            LocalMasterRpcWorkflowProfile.RETAIL_ORDERS -> "pos.retail-order.changes"
            LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS -> "pos.payment-correction.changes"
            LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS -> "pos.customer-receipt.changes"
            LocalMasterRpcWorkflowProfile.FOOD_CHECKS -> "pos.food-check.changes"
            LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL -> "pos.food-cash-financial.changes"
            LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS -> "pos.food-table-assignment.changes"
            LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE -> "pos.invoice-capture.changes"
            LocalMasterRpcWorkflowProfile.INVOICE_MATCHING -> "pos.invoice-matching.changes"
            LocalMasterRpcWorkflowProfile.LAN_BUSINESS -> "pos.changes"
        })
    }

    public fun procedures(profile: LocalMasterRpcWorkflowProfile): Procedures = routes.getValue(profile)

    /** Exact canonical selector DATA binding; it supplies no grant or normalized query identity. */
    public fun selectorBinding(selection: ReadRequest): PayloadDigest = changesRequestDigest(selection, null)

    private fun procedure(name: String):
        RpcProcedure<LocalMasterRpcChangesRequestV1, LocalMasterRpcChangesResponseV1, LocalMasterChangesFailureV1> =
        RpcProcedure(
            name = name,
            version = 1,
            request = LocalMasterRpcChangesRequestV1.serializer(),
            response = LocalMasterRpcChangesResponseV1.serializer(),
            applicationError = LocalMasterRpcChangesFailureSerializerV1,
            retrySafety = RpcRetrySafety.NeverReinvoke,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.MAXIMUM_WIRE_BYTES,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.MAXIMUM_WIRE_BYTES,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.ERROR_LIMIT_BYTES,
        )

    public fun capture(requestingActorId: DomainId, reply: LocalMasterChangesReplyV1): LocalMasterRpcChangesResponseV1 =
        LocalMasterRpcChangesResponseV1(requestingActorId, when (reply) {
            is LocalMasterChangesReplyV1.Snapshot -> LocalMasterRpcChangesValueV1.Snapshot(
                copyChangesBytes(reply.data.payload), reply.data.branchRevision, reply.data.observedAt, reply.cursor.copy())
            is LocalMasterChangesReplyV1.Unchanged -> LocalMasterRpcChangesValueV1.Unchanged(reply.cursor.copy(), reply.observedAt)
            is LocalMasterChangesReplyV1.Unavailable -> LocalMasterRpcChangesValueV1.Unavailable(reply.reason)
        })
}

/** The same carrier has expected-cut semantics for snapshot and required-cursor semantics for pull. */
@Serializable(with = LocalMasterRpcChangesRequestSerializerV1::class)
public class LocalMasterRpcChangesRequestV1 private constructor(
    access: ByteArray,
    public val selection: ReadRequest,
    public val cursor: LocalMasterChangeCursorV1?,
    /** Binds every nonsecret argument across separate SDK authorization/handler decoding. */
    public val bindingDigest: PayloadDigest,
) : LocalMasterRpcOwnedRequestV1(access) {
    public companion object {
        public fun capture(credentials: LocalGatewayCredentials, selection: ReadRequest,
            cursor: LocalMasterChangeCursorV1?): LocalMasterRpcChangesRequestV1 {
            val captured = ReadRequest(selection.query.copy(), copyChangesBytes(selection.payload))
            val progress = cursor?.copy()
            val digest = changesRequestDigest(captured, progress)
            return credentials.bearer.useBytes { access -> LocalMasterRpcChangesRequestV1(access, captured, progress, digest) }
        }

        internal fun decoded(access: ByteArray, body: LocalMasterRpcChangesBodyV1): LocalMasterRpcChangesRequestV1 {
            val selection = body.selection()
            val digest = changesRequestDigest(selection, body.cursor)
            return LocalMasterRpcChangesRequestV1(access, selection, body.cursor, digest)
        }
    }
}

@Serializable
internal data class LocalMasterRpcChangesBodyV1(
    val queryName: String,
    val queryVersion: Int,
    @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) val payload: StoredBytes,
    val cursor: LocalMasterChangeCursorV1?,
) {
    fun selection(): ReadRequest = ReadRequest(ReadKey(queryName, queryVersion), payload)
}

public object LocalMasterRpcChangesRequestSerializerV1 : KSerializer<LocalMasterRpcChangesRequestV1> by ownedRpcRequestSerializer(
    "com.ukkera.pos.database.LocalMasterRpcChangesRequestV1", "request", LocalMasterRpcChangesBodyV1.serializer(),
    body = { value: LocalMasterRpcChangesRequestV1 ->
        LocalMasterRpcChangesBodyV1(value.selection.query.name, value.selection.query.version, value.selection.payload, value.cursor)
    },
    construct = { access, body -> LocalMasterRpcChangesRequestV1.decoded(access, body) },
)

@Serializable
public data class LocalMasterRpcChangesResponseV1(
    public val requestingActorId: DomainId,
    public val value: LocalMasterRpcChangesValueV1,
) {
    override fun toString(): String = "LocalMasterRpcChangesResponseV1([REDACTED])"
}

@Serializable
public sealed interface LocalMasterRpcChangesValueV1 {
    @Serializable @SerialName("SNAPSHOT")
    public data class Snapshot(
        @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) public val payload: StoredBytes,
        public val branchRevision: Revision,
        public val observedAt: UtcInstant,
        public val cursor: LocalMasterChangeCursorV1,
    ) : LocalMasterRpcChangesValueV1 {
        override fun toString(): String = "LocalMasterRpcChangesValueV1.Snapshot([REDACTED])"
    }

    @Serializable @SerialName("UNCHANGED")
    public data class Unchanged(public val cursor: LocalMasterChangeCursorV1, public val observedAt: UtcInstant) :
        LocalMasterRpcChangesValueV1 {
        override fun toString(): String = "LocalMasterRpcChangesValueV1.Unchanged([REDACTED])"
    }

    @Serializable @SerialName("UNAVAILABLE")
    public data class Unavailable(
        @Serializable(with = LocalMasterRpcChangesFailureSerializerV1::class) public val reason: LocalMasterChangesFailureV1,
    ) : LocalMasterRpcChangesValueV1
}

internal object LocalMasterRpcChangesFailureSerializerV1 : KSerializer<LocalMasterChangesFailureV1> by rpcEnumSerializer(
    "com.ukkera.pos.database.LocalMasterChangesFailureV1", LocalMasterChangesFailureV1.entries.toTypedArray())

private val changesBindingJson = Json { encodeDefaults = true }

private fun changesRequestDigest(selection: ReadRequest, cursor: LocalMasterChangeCursorV1?): PayloadDigest {
    val body = LocalMasterRpcChangesBodyV1(selection.query.name, selection.query.version, selection.payload, cursor)
    val bytes = changesBindingJson.encodeToString(LocalMasterRpcChangesBodyV1.serializer(), body).encodeToByteArray()
    return try { sha256(bytes) } finally { bytes.fill(0) }
}

private fun copyChangesBytes(value: StoredBytes): StoredBytes {
    require(value.size <= LocalMasterClientRpcProtocolV1.MAXIMUM_PAYLOAD_BYTES) { "Changes payload exceeds profile" }
    val bytes = value.copyBytes()
    return try { StoredBytes(bytes) } finally { bytes.fill(0) }
}
