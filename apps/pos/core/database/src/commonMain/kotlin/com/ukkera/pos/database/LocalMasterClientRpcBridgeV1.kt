@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.ukkera.pos.database

import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.LocalDevicePairingApprovedMaster
import com.ukkera.pos.security.LocalDevicePairingProfileV2
import com.ukkera.pos.security.LocalDevicePairingSdkOriginal
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import dev.p2pkit.rpc.RpcCallObservation
import dev.p2pkit.rpc.RpcClient
import dev.p2pkit.rpc.RpcConnectionState
import dev.p2pkit.rpc.RpcExecutionEvidence
import dev.p2pkit.rpc.RpcProcedure
import dev.p2pkit.rpc.RpcReply
import dev.p2pkit.rpc.RpcRetry
import dev.p2pkit.rpc.RpcRetrySafety
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ByteArraySerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure

/** Fixed business schemas. Their DTOs are inert data, never database publication capabilities. */
public object LocalMasterClientRpcProtocolV1 {
    public const val MAXIMUM_WIRE_BYTES: Int = 524_288
    public const val MAXIMUM_PAYLOAD_BYTES: Int = 65_536
    public const val ACCESS_BEARER_BYTES: Int = 52
    public const val ERROR_LIMIT_BYTES: Int = 64

    public val READ: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.business.read", version = 1,
            request = LocalMasterRpcReadRequestV1.serializer(),
            response = LocalMasterRpcReadResponseV1.serializer(),
            applicationError = LocalMasterClientRpcErrorV1.serializer(),
            retrySafety = RpcRetrySafety.NeverReinvoke,
            requestLimitBytes = MAXIMUM_WIRE_BYTES,
            responseLimitBytes = MAXIMUM_WIRE_BYTES,
            errorLimitBytes = ERROR_LIMIT_BYTES,
        )

    public val EXECUTE: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.business.execute", version = 1,
            request = LocalMasterRpcExecuteRequestV1.serializer(),
            response = LocalMasterRpcExecuteResponseV1.serializer(),
            applicationError = LocalMasterClientRpcErrorV1.serializer(),
            retrySafety = RpcRetrySafety.NeverReinvoke,
            requestLimitBytes = MAXIMUM_WIRE_BYTES,
            responseLimitBytes = MAXIMUM_WIRE_BYTES,
            errorLimitBytes = ERROR_LIMIT_BYTES,
        )

    public val LOOKUP: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.business.lookup", version = 1,
            request = LocalMasterRpcLookupRequestV1.serializer(),
            response = LocalMasterRpcLookupResponseV1.serializer(),
            applicationError = LocalMasterClientRpcErrorV1.serializer(),
            retrySafety = RpcRetrySafety.NeverReinvoke,
            requestLimitBytes = MAXIMUM_WIRE_BYTES,
            responseLimitBytes = MAXIMUM_WIRE_BYTES,
            errorLimitBytes = ERROR_LIMIT_BYTES,
        )

    /** The Host supplies its actual verified original requester, not a request-body claim. */
    public fun captureRead(requestingActorId: DomainId, reply: ReadReply): LocalMasterRpcReadResponseV1 =
        LocalMasterRpcReadResponseV1(requestingActorId, when (reply) {
            is ReadReply.Data -> LocalMasterRpcReadValueV1.Data(copyRpcStoredBytes(reply.payload), reply.branchRevision, reply.observedAt)
            is ReadReply.Unavailable -> LocalMasterRpcReadValueV1.Unavailable(reply.reason)
        })

    /** Copies presentation data only. Physical start certificates have no remote wire substitute. */
    public fun captureExecute(requestingActorId: DomainId, reply: MutationReply): LocalMasterRpcExecuteResponseV1 =
        LocalMasterRpcExecuteResponseV1(requestingActorId, when (reply) {
            is MutationReply.Recorded -> LocalMasterRpcExecuteValueV1.Recorded(captureRpcRecorded(reply))
            is MutationReply.Deferred -> LocalMasterRpcExecuteValueV1.Deferred(reply.operationId, reply.reason)
            is MutationReply.IdentityConflict -> LocalMasterRpcExecuteValueV1.IdentityConflict(reply.operationId)
        })

    /** Genuine writer-qualified absence remains distinct from every refusal or transport failure. */
    public fun captureLookup(requestingActorId: DomainId, reply: LookupReply): LocalMasterRpcLookupResponseV1 =
        LocalMasterRpcLookupResponseV1(requestingActorId, when (reply) {
            is LookupReply.Found -> {
                require(reply.result.replayed) { "Lookup result is not a retained replay" }
                LocalMasterRpcLookupValueV1.Found(captureRpcRecorded(reply.result))
            }
            is LookupReply.AbsentAtCurrentWriter -> LocalMasterRpcLookupValueV1.AbsentAtCurrentWriter(reply.operationId)
            is LookupReply.Deferred -> LocalMasterRpcLookupValueV1.Deferred(reply.operationId, reply.reason)
            is LookupReply.IdentityConflict -> LocalMasterRpcLookupValueV1.IdentityConflict(reply.operationId)
        })
}

/** Fixed invoice-capture routes reuse the exact version-1 carriers and original call lifetime. */
public object LocalMasterInvoiceCaptureRpcProtocolV1 {
    public val READ: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.invoice-capture.read", version = 1,
            request = LocalMasterClientRpcProtocolV1.READ.request,
            response = LocalMasterClientRpcProtocolV1.READ.response,
            applicationError = LocalMasterClientRpcProtocolV1.READ.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.READ.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.READ.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.READ.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.READ.errorLimitBytes,
        )

    public val EXECUTE: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.invoice-capture.execute", version = 1,
            request = LocalMasterClientRpcProtocolV1.EXECUTE.request,
            response = LocalMasterClientRpcProtocolV1.EXECUTE.response,
            applicationError = LocalMasterClientRpcProtocolV1.EXECUTE.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.EXECUTE.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.errorLimitBytes,
        )

    public val LOOKUP: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.invoice-capture.lookup", version = 1,
            request = LocalMasterClientRpcProtocolV1.LOOKUP.request,
            response = LocalMasterClientRpcProtocolV1.LOOKUP.response,
            applicationError = LocalMasterClientRpcProtocolV1.LOOKUP.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.LOOKUP.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.errorLimitBytes,
        )
}

/** Fixed warehouse routes reuse the existing version-1 carriers; selection grants no authority. */
public object LocalMasterWarehouseRpcProtocolV1 {
    public val READ: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.warehouse.read", version = 1,
            request = LocalMasterClientRpcProtocolV1.READ.request,
            response = LocalMasterClientRpcProtocolV1.READ.response,
            applicationError = LocalMasterClientRpcProtocolV1.READ.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.READ.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.READ.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.READ.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.READ.errorLimitBytes,
        )

    public val EXECUTE: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.warehouse.execute", version = 1,
            request = LocalMasterClientRpcProtocolV1.EXECUTE.request,
            response = LocalMasterClientRpcProtocolV1.EXECUTE.response,
            applicationError = LocalMasterClientRpcProtocolV1.EXECUTE.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.EXECUTE.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.errorLimitBytes,
        )

    public val LOOKUP: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.warehouse.lookup", version = 1,
            request = LocalMasterClientRpcProtocolV1.LOOKUP.request,
            response = LocalMasterClientRpcProtocolV1.LOOKUP.response,
            applicationError = LocalMasterClientRpcProtocolV1.LOOKUP.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.LOOKUP.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.errorLimitBytes,
        )
}

/** Fixed report routes reuse the exact version-1 carriers; selection grants no authority. */
public object LocalMasterReportRpcProtocolV1 {
    public val READ: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.report.read", version = 1,
            request = LocalMasterClientRpcProtocolV1.READ.request,
            response = LocalMasterClientRpcProtocolV1.READ.response,
            applicationError = LocalMasterClientRpcProtocolV1.READ.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.READ.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.READ.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.READ.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.READ.errorLimitBytes,
        )

    public val EXECUTE: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.report.execute", version = 1,
            request = LocalMasterClientRpcProtocolV1.EXECUTE.request,
            response = LocalMasterClientRpcProtocolV1.EXECUTE.response,
            applicationError = LocalMasterClientRpcProtocolV1.EXECUTE.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.EXECUTE.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.errorLimitBytes,
        )

    public val LOOKUP: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.report.lookup", version = 1,
            request = LocalMasterClientRpcProtocolV1.LOOKUP.request,
            response = LocalMasterClientRpcProtocolV1.LOOKUP.response,
            applicationError = LocalMasterClientRpcProtocolV1.LOOKUP.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.LOOKUP.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.errorLimitBytes,
        )
}

/** Fixed stock-transfer routes reuse the exact version-1 carriers and original call lifetime. */
public object LocalMasterStockTransferRpcProtocolV1 {
    public val READ: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.stock-transfer.read", version = 1,
            request = LocalMasterClientRpcProtocolV1.READ.request,
            response = LocalMasterClientRpcProtocolV1.READ.response,
            applicationError = LocalMasterClientRpcProtocolV1.READ.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.READ.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.READ.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.READ.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.READ.errorLimitBytes,
        )

    public val EXECUTE: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.stock-transfer.execute", version = 1,
            request = LocalMasterClientRpcProtocolV1.EXECUTE.request,
            response = LocalMasterClientRpcProtocolV1.EXECUTE.response,
            applicationError = LocalMasterClientRpcProtocolV1.EXECUTE.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.EXECUTE.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.errorLimitBytes,
        )

    public val LOOKUP: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.stock-transfer.lookup", version = 1,
            request = LocalMasterClientRpcProtocolV1.LOOKUP.request,
            response = LocalMasterClientRpcProtocolV1.LOOKUP.response,
            applicationError = LocalMasterClientRpcProtocolV1.LOOKUP.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.LOOKUP.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.errorLimitBytes,
        )
}

/** Fixed kitchen station routes reuse the exact version-1 carriers and original call lifetime. */
public object LocalMasterKitchenRpcProtocolV1 {
    public val READ: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.kitchen.read", version = 1,
            request = LocalMasterClientRpcProtocolV1.READ.request,
            response = LocalMasterClientRpcProtocolV1.READ.response,
            applicationError = LocalMasterClientRpcProtocolV1.READ.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.READ.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.READ.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.READ.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.READ.errorLimitBytes,
        )

    public val EXECUTE: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.kitchen.execute", version = 1,
            request = LocalMasterClientRpcProtocolV1.EXECUTE.request,
            response = LocalMasterClientRpcProtocolV1.EXECUTE.response,
            applicationError = LocalMasterClientRpcProtocolV1.EXECUTE.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.EXECUTE.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.errorLimitBytes,
        )

    public val LOOKUP: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.kitchen.lookup", version = 1,
            request = LocalMasterClientRpcProtocolV1.LOOKUP.request,
            response = LocalMasterClientRpcProtocolV1.LOOKUP.response,
            applicationError = LocalMasterClientRpcProtocolV1.LOOKUP.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.LOOKUP.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.errorLimitBytes,
        )
}

/** Fixed retail order routes reuse the exact version-1 carriers and original call lifetime. */
public object LocalMasterRetailOrderRpcProtocolV1 {
    public val READ: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.retail-order.read", version = 1,
            request = LocalMasterClientRpcProtocolV1.READ.request,
            response = LocalMasterClientRpcProtocolV1.READ.response,
            applicationError = LocalMasterClientRpcProtocolV1.READ.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.READ.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.READ.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.READ.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.READ.errorLimitBytes,
        )

    public val EXECUTE: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.retail-order.execute", version = 1,
            request = LocalMasterClientRpcProtocolV1.EXECUTE.request,
            response = LocalMasterClientRpcProtocolV1.EXECUTE.response,
            applicationError = LocalMasterClientRpcProtocolV1.EXECUTE.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.EXECUTE.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.errorLimitBytes,
        )

    public val LOOKUP: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.retail-order.lookup", version = 1,
            request = LocalMasterClientRpcProtocolV1.LOOKUP.request,
            response = LocalMasterClientRpcProtocolV1.LOOKUP.response,
            applicationError = LocalMasterClientRpcProtocolV1.LOOKUP.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.LOOKUP.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.errorLimitBytes,
        )
}

/** Fixed payment-correction routes reuse the exact version-1 carriers and original call lifetime. */
public object LocalMasterPaymentCorrectionRpcProtocolV1 {
    public val READ: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.payment-correction.read", version = 1,
            request = LocalMasterClientRpcProtocolV1.READ.request,
            response = LocalMasterClientRpcProtocolV1.READ.response,
            applicationError = LocalMasterClientRpcProtocolV1.READ.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.READ.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.READ.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.READ.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.READ.errorLimitBytes,
        )

    public val EXECUTE: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.payment-correction.execute", version = 1,
            request = LocalMasterClientRpcProtocolV1.EXECUTE.request,
            response = LocalMasterClientRpcProtocolV1.EXECUTE.response,
            applicationError = LocalMasterClientRpcProtocolV1.EXECUTE.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.EXECUTE.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.errorLimitBytes,
        )

    public val LOOKUP: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.payment-correction.lookup", version = 1,
            request = LocalMasterClientRpcProtocolV1.LOOKUP.request,
            response = LocalMasterClientRpcProtocolV1.LOOKUP.response,
            applicationError = LocalMasterClientRpcProtocolV1.LOOKUP.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.LOOKUP.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.errorLimitBytes,
        )
}

/** Fixed counter-observation routes reuse the exact version-1 carriers and original call lifetime. */
public object LocalMasterCustomerReceiptRpcProtocolV1 {
    public val READ: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.customer-receipt.read", version = 1,
            request = LocalMasterClientRpcProtocolV1.READ.request,
            response = LocalMasterClientRpcProtocolV1.READ.response,
            applicationError = LocalMasterClientRpcProtocolV1.READ.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.READ.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.READ.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.READ.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.READ.errorLimitBytes,
        )

    public val EXECUTE: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.customer-receipt.execute", version = 1,
            request = LocalMasterClientRpcProtocolV1.EXECUTE.request,
            response = LocalMasterClientRpcProtocolV1.EXECUTE.response,
            applicationError = LocalMasterClientRpcProtocolV1.EXECUTE.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.EXECUTE.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.errorLimitBytes,
        )

    public val LOOKUP: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.customer-receipt.lookup", version = 1,
            request = LocalMasterClientRpcProtocolV1.LOOKUP.request,
            response = LocalMasterClientRpcProtocolV1.LOOKUP.response,
            applicationError = LocalMasterClientRpcProtocolV1.LOOKUP.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.LOOKUP.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.errorLimitBytes,
        )
}

/** Fixed unpaid owner-check routes reuse the exact version-1 carriers and original call lifetime. */
public object LocalMasterFoodCheckRpcProtocolV1 {
    public val READ: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.food-check.read", version = 1,
            request = LocalMasterClientRpcProtocolV1.READ.request,
            response = LocalMasterClientRpcProtocolV1.READ.response,
            applicationError = LocalMasterClientRpcProtocolV1.READ.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.READ.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.READ.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.READ.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.READ.errorLimitBytes,
        )

    public val EXECUTE: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.food-check.execute", version = 1,
            request = LocalMasterClientRpcProtocolV1.EXECUTE.request,
            response = LocalMasterClientRpcProtocolV1.EXECUTE.response,
            applicationError = LocalMasterClientRpcProtocolV1.EXECUTE.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.EXECUTE.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.errorLimitBytes,
        )

    public val LOOKUP: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.food-check.lookup", version = 1,
            request = LocalMasterClientRpcProtocolV1.LOOKUP.request,
            response = LocalMasterClientRpcProtocolV1.LOOKUP.response,
            applicationError = LocalMasterClientRpcProtocolV1.LOOKUP.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.LOOKUP.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.errorLimitBytes,
        )
}

/** Fixed table-assignment routes preserve the existing version-1 carriers and original lifetime. */
public object LocalMasterFoodTableAssignmentRpcProtocolV1 {
    public val READ: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.food-table-assignment.read", version = 1,
            request = LocalMasterClientRpcProtocolV1.READ.request,
            response = LocalMasterClientRpcProtocolV1.READ.response,
            applicationError = LocalMasterClientRpcProtocolV1.READ.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.READ.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.READ.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.READ.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.READ.errorLimitBytes,
        )

    public val EXECUTE: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.food-table-assignment.execute", version = 1,
            request = LocalMasterClientRpcProtocolV1.EXECUTE.request,
            response = LocalMasterClientRpcProtocolV1.EXECUTE.response,
            applicationError = LocalMasterClientRpcProtocolV1.EXECUTE.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.EXECUTE.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.EXECUTE.errorLimitBytes,
        )

    public val LOOKUP: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.food-table-assignment.lookup", version = 1,
            request = LocalMasterClientRpcProtocolV1.LOOKUP.request,
            response = LocalMasterClientRpcProtocolV1.LOOKUP.response,
            applicationError = LocalMasterClientRpcProtocolV1.LOOKUP.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.LOOKUP.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.errorLimitBytes,
        )
}

@Serializable
public enum class LocalMasterClientRpcErrorV1 { UNAVAILABLE }

/**
 * Owns an independently copied bearer. The Host claims each genuinely decoded request at
 * callback entry, before suspension, and retains/reaps unclaimed requests in its decoder owner.
 * Terminal cleanup never races an active synchronous bearer loan into partly cleared bytes.
 * This does not promise erasure of immutable codec intermediates or SDK-owned packet buffers.
 */
public sealed class LocalMasterRpcOwnedRequestV1 protected constructor(access: ByteArray) {
    private enum class Lifetime { UNCLAIMED, CLAIMED, DESTROYING, DESTROYED }
    private class State(val lifetime: Lifetime, val loans: Int)
    private val state = MutableStateFlow(State(Lifetime.UNCLAIMED, 0))
    private val cleanupCompleted = CompletableDeferred<Unit>()
    private val bearer: ByteArray

    init {
        require(access.size == LocalMasterClientRpcProtocolV1.ACCESS_BEARER_BYTES) { "Invalid business access bearer" }
        bearer = access.copyOf()
    }

    /** Version-1 terminal/no-new-use indication; an accepted synchronous loan may still exist. */
    public val isDestroyed: Boolean get() = when (state.value.lifetime) {
        Lifetime.DESTROYING, Lifetime.DESTROYED -> true
        else -> false
    }

    /** Completed buffer wiping, not merely retirement. Decoder retention uses this indication. */
    public val isCleanupComplete: Boolean get() = state.value.lifetime == Lifetime.DESTROYED

    /** Ordinary expiry may only retire a request that no actual callback has entered. */
    public fun claimForCallback(): Boolean {
        while (true) {
            val before = state.value
            if (before.lifetime != Lifetime.UNCLAIMED) return false
            if (state.compareAndSet(before, State(Lifetime.CLAIMED, before.loans))) return true
        }
    }

    public fun destroyIfUnclaimed(): Unit = retire(onlyUnclaimed = true)

    /** Terminal decoder/callback/drained-owner cleanup; this request can never be reclaimed. */
    public fun destroy(): Unit = retire(onlyUnclaimed = false)

    /** Wait without taking a claimed input from its owner or cancelling the owner's cleanup. */
    public suspend fun awaitCleanup(): Unit = cleanupCompleted.await()

    /** A disposable synchronous copy. Retaining the supplied array outside this block is unsupported. */
    public fun <T> useBearer(block: (ByteArray) -> T): T {
        acquireLoan()
        var copied: ByteArray? = null
        return try {
            val bytes = bearer.copyOf().also { copied = it }
            block(bytes)
        } finally {
            try { copied?.fill(0) }
            catch (failure: Throwable) {
                // A failed loan wipe cannot be advertised as a completed request release.
                retire(onlyUnclaimed = false)
                cleanupCompleted.completeExceptionally(failure)
                throw failure
            }
            releaseLoan()
        }
    }

    private fun acquireLoan() {
        while (true) {
            val before = state.value
            check((before.lifetime == Lifetime.UNCLAIMED || before.lifetime == Lifetime.CLAIMED) &&
                before.loans < Int.MAX_VALUE) {
                "Business access bearer is unavailable"
            }
            if (state.compareAndSet(before, State(before.lifetime, before.loans + 1))) return
        }
    }

    private fun releaseLoan() {
        while (true) {
            val before = state.value
            check(before.loans > 0)
            if (state.compareAndSet(before, State(before.lifetime, before.loans - 1))) {
                if (before.lifetime == Lifetime.DESTROYING && before.loans == 1) finishDestruction()
                return
            }
        }
    }

    private fun retire(onlyUnclaimed: Boolean) {
        while (true) {
            val before = state.value
            if (before.lifetime == Lifetime.DESTROYING || before.lifetime == Lifetime.DESTROYED ||
                (onlyUnclaimed && before.lifetime != Lifetime.UNCLAIMED)) return
            if (state.compareAndSet(before, State(Lifetime.DESTROYING, before.loans))) {
                if (before.loans == 0) finishDestruction()
                return
            }
        }
    }

    /** Only the zero-loan retire owner or final loan-release owner may publish completion. */
    private fun finishDestruction() {
        try { destroyOwnedSecrets(); bearer.fill(0) }
        catch (failure: Throwable) {
            cleanupCompleted.completeExceptionally(failure)
            throw failure
        }
        state.value = State(Lifetime.DESTROYED, 0)
        cleanupCompleted.complete(Unit)
    }

    /** Additional request-owned secrets, wiped once by the terminal destruction owner. */
    protected open fun destroyOwnedSecrets() {}

    final override fun toString(): String = "LocalMasterRpcOwnedRequestV1([REDACTED])"
}

@Serializable(with = LocalMasterRpcReadRequestSerializerV1::class)
public class LocalMasterRpcReadRequestV1 private constructor(
    access: ByteArray,
    public val request: ReadRequest,
) : LocalMasterRpcOwnedRequestV1(access) {
    public companion object {
        /** Copies caller-owned input; connection and actor are deliberately not wire fields. */
        public fun capture(credentials: LocalGatewayCredentials, request: ReadRequest): LocalMasterRpcReadRequestV1 =
            credentials.bearer.useBytes { LocalMasterRpcReadRequestV1(it, copyRpcReadRequest(request)) }

        internal fun decoded(access: ByteArray, request: ReadRequest): LocalMasterRpcReadRequestV1 =
            LocalMasterRpcReadRequestV1(access, request)
    }
}

@Serializable(with = LocalMasterRpcExecuteRequestSerializerV1::class)
public class LocalMasterRpcExecuteRequestV1 private constructor(
    access: ByteArray,
    public val request: MutationRequest,
) : LocalMasterRpcOwnedRequestV1(access) {
    public companion object {
        public fun capture(credentials: LocalGatewayCredentials, request: MutationRequest): LocalMasterRpcExecuteRequestV1 =
            credentials.bearer.useBytes { LocalMasterRpcExecuteRequestV1(it, copyRpcMutationRequest(request)) }

        internal fun decoded(access: ByteArray, request: MutationRequest): LocalMasterRpcExecuteRequestV1 =
            LocalMasterRpcExecuteRequestV1(access, request)
    }
}

@Serializable(with = LocalMasterRpcLookupRequestSerializerV1::class)
public class LocalMasterRpcLookupRequestV1 private constructor(
    access: ByteArray,
    public val identity: OperationIdentity,
) : LocalMasterRpcOwnedRequestV1(access) {
    public companion object {
        /** Original branch/actor/digest fields select history; none grants access to it. */
        public fun capture(credentials: LocalGatewayCredentials, identity: OperationIdentity): LocalMasterRpcLookupRequestV1 =
            credentials.bearer.useBytes { LocalMasterRpcLookupRequestV1(it, identity.copy()) }

        internal fun decoded(access: ByteArray, identity: OperationIdentity): LocalMasterRpcLookupRequestV1 =
            LocalMasterRpcLookupRequestV1(access, identity)
    }
}

@Serializable
private data class LocalMasterRpcReadBodyV1(
    val queryName: String,
    val queryVersion: Int,
    @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) val payload: StoredBytes,
) {
    fun domain(): ReadRequest = ReadRequest(ReadKey(queryName, queryVersion), payload)
}

@Serializable
private data class LocalMasterRpcExecuteBodyV1(
    val operationId: OperationId,
    val commandName: String,
    val commandVersion: Int,
    @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) val payload: StoredBytes,
    val actionApprovalId: DomainId? = null,
) {
    fun domain(): MutationRequest = MutationRequest(operationId, CommandKey(commandName, commandVersion), payload, actionApprovalId)
}

/** Host owners may wrap this typed serializer to retain/reap genuinely decoded request arrays. */
public object LocalMasterRpcReadRequestSerializerV1 : KSerializer<LocalMasterRpcReadRequestV1> by ownedRpcRequestSerializer(
    "com.ukkera.pos.database.LocalMasterRpcReadRequestV1", "request", LocalMasterRpcReadBodyV1.serializer(),
    body = { value: LocalMasterRpcReadRequestV1 ->
        LocalMasterRpcReadBodyV1(value.request.query.name, value.request.query.version, value.request.payload)
    },
    construct = { access, body -> LocalMasterRpcReadRequestV1.decoded(access, body.domain()) },
)

public object LocalMasterRpcExecuteRequestSerializerV1 : KSerializer<LocalMasterRpcExecuteRequestV1> by ownedRpcRequestSerializer(
    "com.ukkera.pos.database.LocalMasterRpcExecuteRequestV1", "request", LocalMasterRpcExecuteBodyV1.serializer(),
    body = { value: LocalMasterRpcExecuteRequestV1 ->
        LocalMasterRpcExecuteBodyV1(value.request.operationId, value.request.command.name,
            value.request.command.version, value.request.payload, value.request.actionApprovalId)
    },
    construct = { access, body -> LocalMasterRpcExecuteRequestV1.decoded(access, body.domain()) },
)

public object LocalMasterRpcLookupRequestSerializerV1 : KSerializer<LocalMasterRpcLookupRequestV1> by ownedRpcRequestSerializer(
    "com.ukkera.pos.database.LocalMasterRpcLookupRequestV1", "identity", OperationIdentity.serializer(),
    body = { value: LocalMasterRpcLookupRequestV1 -> value.identity },
    construct = { access, body -> LocalMasterRpcLookupRequestV1.decoded(access, body) },
)

private fun <T : LocalMasterRpcOwnedRequestV1, B : Any> ownedRpcRequestSerializer(
    name: String,
    bodyName: String,
    bodySerializer: KSerializer<B>,
    body: (T) -> B,
    construct: (ByteArray, B) -> T,
): KSerializer<T> = object : KSerializer<T> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(name) {
        element("access", LocalMasterRpcBearerSerializerV1.descriptor)
        element(bodyName, bodySerializer.descriptor)
    }

    override fun serialize(encoder: Encoder, value: T) {
        try {
            value.useBearer { access ->
                encoder.encodeStructure(descriptor) {
                    encodeSerializableElement(descriptor, 0, LocalMasterRpcBearerSerializerV1, access)
                    encodeSerializableElement(descriptor, 1, bodySerializer, body(value))
                }
            }
        } finally { value.destroy() }
    }

    override fun deserialize(decoder: Decoder): T {
        var access: ByteArray? = null
        var decodedBody: B? = null
        var seen = 0
        try {
            decoder.decodeStructure(descriptor) {
                while (true) {
                    val index = decodeElementIndex(descriptor)
                    if (index == CompositeDecoder.DECODE_DONE) break
                    if (index !in 0..1 || seen and (1 shl index) != 0)
                        throw SerializationException("Unexpected business request field")
                    seen = seen or (1 shl index)
                    when (index) {
                        0 -> access = decodeSerializableElement(descriptor, index, LocalMasterRpcBearerSerializerV1)
                        1 -> decodedBody = decodeSerializableElement(descriptor, index, bodySerializer)
                    }
                }
            }
            if (seen != 3) throw SerializationException("Missing business request field")
            return construct(checkNotNull(access), checkNotNull(decodedBody))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Decoder exceptions can include input text; never attach their cause or payload.
            throw SerializationException("Malformed business request")
        } finally { access?.fill(0) }
    }
}

/** Partially decoded credentials are owned from the first byte, including decoder failures. */
internal object LocalMasterRpcBearerSerializerV1 : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = ByteArraySerializer().descriptor
    override fun serialize(encoder: Encoder, value: ByteArray) {
        if (value.size != LocalMasterClientRpcProtocolV1.ACCESS_BEARER_BYTES)
            throw SerializationException("Invalid business access bearer")
        ByteArraySerializer().serialize(encoder, value)
    }
    override fun deserialize(decoder: Decoder): ByteArray = decodeBoundedRpcBytes(
        decoder, descriptor, LocalMasterClientRpcProtocolV1.ACCESS_BEARER_BYTES,
        LocalMasterClientRpcProtocolV1.ACCESS_BEARER_BYTES,
    )
}

private object LocalMasterRpcStoredBytesSerializerV1 : KSerializer<StoredBytes> {
    override val descriptor: SerialDescriptor = ByteArraySerializer().descriptor
    override fun serialize(encoder: Encoder, value: StoredBytes) {
        val bytes = value.copyBytes()
        try { ByteArraySerializer().serialize(encoder, bytes) }
        finally { bytes.fill(0) }
    }
    override fun deserialize(decoder: Decoder): StoredBytes {
        val bytes = decodeBoundedRpcBytes(decoder, descriptor, 0, LocalMasterClientRpcProtocolV1.MAXIMUM_PAYLOAD_BYTES)
        return try { StoredBytes(bytes) } finally { bytes.fill(0) }
    }
}

internal fun decodeBoundedRpcBytes(decoder: Decoder, descriptor: SerialDescriptor, minimum: Int, maximum: Int): ByteArray {
    val staged = ByteArray(maximum)
    try {
        var count = 0
        decoder.decodeStructure(descriptor) {
            if (decodeSequentially()) {
                val size = decodeCollectionSize(descriptor)
                if (size !in minimum..maximum) throw SerializationException("Business byte field exceeds profile")
                for (index in 0 until size) staged[index] = decodeByteElement(descriptor, index)
                count = size
            } else {
                while (true) {
                    val index = decodeElementIndex(descriptor)
                    if (index == CompositeDecoder.DECODE_DONE) break
                    if (index != count || count >= maximum)
                        throw SerializationException("Business byte field exceeds profile")
                    staged[count++] = decodeByteElement(descriptor, index)
                }
            }
        }
        if (count !in minimum..maximum) throw SerializationException("Invalid business byte field")
        return staged.copyOf(count)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw SerializationException("Malformed business byte field")
    } finally { staged.fill(0) }
}

@Serializable
public data class LocalMasterRpcReadResponseV1(
    public val requestingActorId: DomainId,
    public val value: LocalMasterRpcReadValueV1,
) {
    override fun toString(): String = "LocalMasterRpcReadResponseV1(data omitted)"
}

@Serializable
public sealed interface LocalMasterRpcReadValueV1 {
    @Serializable @SerialName("DATA")
    public data class Data(
        @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) public val payload: StoredBytes,
        public val branchRevision: Revision,
        public val observedAt: UtcInstant,
    ) : LocalMasterRpcReadValueV1

    @Serializable @SerialName("UNAVAILABLE")
    public data class Unavailable(
        @Serializable(with = LocalMasterRpcReadFailureSerializerV1::class) public val reason: ReadFailure,
    ) : LocalMasterRpcReadValueV1
}

@Serializable
public data class LocalMasterRpcExecuteResponseV1(
    public val requestingActorId: DomainId,
    public val value: LocalMasterRpcExecuteValueV1,
) {
    override fun toString(): String = "LocalMasterRpcExecuteResponseV1(data omitted)"
}

@Serializable
public sealed interface LocalMasterRpcExecuteValueV1 {
    @Serializable @SerialName("RECORDED")
    public data class Recorded(public val recorded: LocalMasterRpcRecordedV1) : LocalMasterRpcExecuteValueV1

    @Serializable @SerialName("DEFERRED")
    public data class Deferred(
        public val operationId: OperationId,
        @Serializable(with = LocalMasterRpcDeferredReasonSerializerV1::class) public val reason: DeferredReason,
    ) : LocalMasterRpcExecuteValueV1

    @Serializable @SerialName("IDENTITY_CONFLICT")
    public data class IdentityConflict(public val operationId: OperationId) : LocalMasterRpcExecuteValueV1
}

@Serializable
public data class LocalMasterRpcLookupResponseV1(
    public val requestingActorId: DomainId,
    public val value: LocalMasterRpcLookupValueV1,
) {
    override fun toString(): String = "LocalMasterRpcLookupResponseV1(data omitted)"
}

@Serializable
public sealed interface LocalMasterRpcLookupValueV1 {
    @Serializable @SerialName("FOUND")
    public data class Found(public val recorded: LocalMasterRpcRecordedV1) : LocalMasterRpcLookupValueV1

    @Serializable @SerialName("ABSENT_AT_CURRENT_WRITER")
    public data class AbsentAtCurrentWriter(public val operationId: OperationId) : LocalMasterRpcLookupValueV1

    @Serializable @SerialName("DEFERRED")
    public data class Deferred(
        public val operationId: OperationId,
        @Serializable(with = LocalMasterRpcDeferredReasonSerializerV1::class) public val reason: DeferredReason,
    ) : LocalMasterRpcLookupValueV1

    @Serializable @SerialName("IDENTITY_CONFLICT")
    public data class IdentityConflict(public val operationId: OperationId) : LocalMasterRpcLookupValueV1
}

/** Inert retained result fields; constructing or decoding this class publishes no trusted result. */
@Serializable
public data class LocalMasterRpcRecordedV1(
    public val operationId: OperationId,
    public val originalActorId: DomainId,
    public val committed: Boolean,
    public val code: String,
    @Serializable(with = LocalMasterRpcStoredBytesSerializerV1::class) public val result: StoredBytes,
    public val authorityEpoch: Revision,
    public val sequence: JournalSequence,
    public val recordedAt: UtcInstant,
    public val replayed: Boolean,
) {
    init {
        requireProtocolName(code)
        require(committed == (code == "committed")) { "Recorded outcome code differs" }
    }
    override fun toString(): String = "LocalMasterRpcRecordedV1(data omitted)"
}

private object LocalMasterRpcReadFailureSerializerV1 : KSerializer<ReadFailure> by rpcEnumSerializer(
    "com.ukkera.pos.database.ReadFailure", ReadFailure.entries.toTypedArray(),
)

private object LocalMasterRpcDeferredReasonSerializerV1 : KSerializer<DeferredReason> by rpcEnumSerializer(
    "com.ukkera.pos.database.DeferredReason", DeferredReason.entries.toTypedArray(),
)

private fun <T : Enum<T>> rpcEnumSerializer(name: String, values: Array<T>): KSerializer<T> = object : KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: T): Unit = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): T {
        val decoded = decoder.decodeString()
        return values.firstOrNull { it.name == decoded } ?: throw SerializationException("Unknown business refusal")
    }
}

private fun captureRpcRecorded(reply: MutationReply.Recorded): LocalMasterRpcRecordedV1 {
    require(reply.physicalStart == null) { "Physical start is not a remote business result" }
    return LocalMasterRpcRecordedV1(reply.operationId, reply.originalActorId, reply.committed, reply.code,
        copyRpcStoredBytes(reply.result), reply.authorityEpoch, reply.sequence, reply.recordedAt, reply.replayed)
}

private fun copyRpcStoredBytes(value: StoredBytes): StoredBytes {
    val bytes = value.copyBytes()
    return try { StoredBytes(bytes) } finally { bytes.fill(0) }
}

private fun copyRpcReadRequest(value: ReadRequest): ReadRequest =
    ReadRequest(value.query.copy(), copyRpcStoredBytes(value.payload))

private fun copyRpcMutationRequest(value: MutationRequest): MutationRequest =
    MutationRequest(value.operationId, value.command.copy(), copyRpcStoredBytes(value.payload), value.actionApprovalId)

/**
 * Fixed invocation bridge on one actual official Client loan and its native-qualified selected
 * Master. Bind only inside the retained original owner's confirmed native-close/factory gate,
 * after exact factory identity comparison and the one selected connection becoming READY.
 * Native custody and exclusive handoff are not proved by public identity DATA or this bridge.
 * The owner retains this instance once for that exact role, denies it before close/retirement,
 * and guards final publication with its exact original/service/factory references.
 *
 * Only this owning module reconstructs restricted database replies, after its own stored Client
 * delivers a correlated fixed call. There is no accepting raw-byte, reply or details decoder.
 * This class neither grants employee access nor supplies a client journal or writer incarnation.
 */
public class LocalMasterClientRpcBridgeV1 private constructor(
    private val client: RpcClient,
    private val original: LocalDevicePairingSdkOriginal,
    private val approvedMaster: LocalDevicePairingApprovedMaster,
    private val lifetime: Job,
    public val connectionId: DomainId,
    public val profile: LocalMasterRpcWorkflowProfile = LocalMasterRpcWorkflowProfile.SUPPLIERS,
) {
    private val denied = MutableStateFlow(false)
    private val readProcedure = when (profile) {
        LocalMasterRpcWorkflowProfile.SUPPLIERS -> LocalMasterClientRpcProtocolV1.READ
        LocalMasterRpcWorkflowProfile.WAREHOUSES -> LocalMasterWarehouseRpcProtocolV1.READ
        LocalMasterRpcWorkflowProfile.REPORTS -> LocalMasterReportRpcProtocolV1.READ
        LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS -> LocalMasterStockTransferRpcProtocolV1.READ
        LocalMasterRpcWorkflowProfile.KITCHEN -> LocalMasterKitchenRpcProtocolV1.READ
        LocalMasterRpcWorkflowProfile.RETAIL_ORDERS -> LocalMasterRetailOrderRpcProtocolV1.READ
        LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS -> LocalMasterPaymentCorrectionRpcProtocolV1.READ
        LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS -> LocalMasterCustomerReceiptRpcProtocolV1.READ
        LocalMasterRpcWorkflowProfile.FOOD_CHECKS -> LocalMasterFoodCheckRpcProtocolV1.READ
        LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL -> LocalMasterFoodCashFinancialRpcProtocolV1.READ
        LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS -> LocalMasterFoodTableAssignmentRpcProtocolV1.READ
        LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE -> LocalMasterInvoiceCaptureRpcProtocolV1.READ
        LocalMasterRpcWorkflowProfile.INVOICE_MATCHING -> LocalMasterInvoiceMatchingRpcProtocolV1.READ
    }
    private val executeProcedure = when (profile) {
        LocalMasterRpcWorkflowProfile.SUPPLIERS -> LocalMasterClientRpcProtocolV1.EXECUTE
        LocalMasterRpcWorkflowProfile.WAREHOUSES -> LocalMasterWarehouseRpcProtocolV1.EXECUTE
        LocalMasterRpcWorkflowProfile.REPORTS -> LocalMasterReportRpcProtocolV1.EXECUTE
        LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS -> LocalMasterStockTransferRpcProtocolV1.EXECUTE
        LocalMasterRpcWorkflowProfile.KITCHEN -> LocalMasterKitchenRpcProtocolV1.EXECUTE
        LocalMasterRpcWorkflowProfile.RETAIL_ORDERS -> LocalMasterRetailOrderRpcProtocolV1.EXECUTE
        LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS -> LocalMasterPaymentCorrectionRpcProtocolV1.EXECUTE
        LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS -> LocalMasterCustomerReceiptRpcProtocolV1.EXECUTE
        LocalMasterRpcWorkflowProfile.FOOD_CHECKS -> LocalMasterFoodCheckRpcProtocolV1.EXECUTE
        LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL -> LocalMasterFoodCashFinancialRpcProtocolV1.EXECUTE
        LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS -> LocalMasterFoodTableAssignmentRpcProtocolV1.EXECUTE
        LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE -> LocalMasterInvoiceCaptureRpcProtocolV1.EXECUTE
        LocalMasterRpcWorkflowProfile.INVOICE_MATCHING -> LocalMasterInvoiceMatchingRpcProtocolV1.EXECUTE
    }
    private val lookupProcedure = when (profile) {
        LocalMasterRpcWorkflowProfile.SUPPLIERS -> LocalMasterClientRpcProtocolV1.LOOKUP
        LocalMasterRpcWorkflowProfile.WAREHOUSES -> LocalMasterWarehouseRpcProtocolV1.LOOKUP
        LocalMasterRpcWorkflowProfile.REPORTS -> LocalMasterReportRpcProtocolV1.LOOKUP
        LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS -> LocalMasterStockTransferRpcProtocolV1.LOOKUP
        LocalMasterRpcWorkflowProfile.KITCHEN -> LocalMasterKitchenRpcProtocolV1.LOOKUP
        LocalMasterRpcWorkflowProfile.RETAIL_ORDERS -> LocalMasterRetailOrderRpcProtocolV1.LOOKUP
        LocalMasterRpcWorkflowProfile.PAYMENT_CORRECTIONS -> LocalMasterPaymentCorrectionRpcProtocolV1.LOOKUP
        LocalMasterRpcWorkflowProfile.CUSTOMER_RECEIPTS -> LocalMasterCustomerReceiptRpcProtocolV1.LOOKUP
        LocalMasterRpcWorkflowProfile.FOOD_CHECKS -> LocalMasterFoodCheckRpcProtocolV1.LOOKUP
        LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL -> LocalMasterFoodCashFinancialRpcProtocolV1.LOOKUP
        LocalMasterRpcWorkflowProfile.FOOD_TABLE_ASSIGNMENTS -> LocalMasterFoodTableAssignmentRpcProtocolV1.LOOKUP
        LocalMasterRpcWorkflowProfile.INVOICE_CAPTURE -> LocalMasterInvoiceCaptureRpcProtocolV1.LOOKUP
        LocalMasterRpcWorkflowProfile.INVOICE_MATCHING -> LocalMasterInvoiceMatchingRpcProtocolV1.LOOKUP
    }

    /** Native original identification only, not current authorization. */
    public val scope: BranchScope get() = approvedMaster.scope

    /** Losing an observed actual lifetime/identity/READY/singleton-pin gate irreversibly denies this loan. */
    public fun isCurrent(): Boolean {
        if (denied.value) return false
        val expected = approvedMaster.masterSdkOriginal.fingerprint
        val actual = client.localIdentity
        val current = lifetime.isActive && client.state.value == RpcConnectionState.Ready &&
            original.identity.matchesSdkTransportIdentity(actual.appId, actual.peerId, actual.fingerprint, actual.pairingQr) &&
            actual.deviceName == original.exactDeviceName && client.trust.isTrusted(expected) &&
            client.trust.fingerprints().toSet() == setOf(expected)
        if (!current) deny()
        return current && !denied.value
    }

    public fun deny(): Unit { denied.value = true }

    public suspend fun read(
        credentials: LocalGatewayCredentials,
        request: ReadRequest,
        requestingActorId: DomainId,
    ): ReadReply {
        requireConnection(credentials)
        val capturedActor = requestingActorId
        val owned = LocalMasterRpcReadRequestV1.capture(credentials, request)
        try {
            val result = invoke(readProcedure, owned, accept = { true }) { delivered ->
                when (delivered) {
                    is RpcReply.Success -> {
                        requireResponseBinding(delivered.value.requestingActorId == capturedActor)
                        when (val value = delivered.value.value) {
                            is LocalMasterRpcReadValueV1.Data -> ReadReply.Data(copyRpcStoredBytes(value.payload), value.branchRevision, value.observedAt)
                            is LocalMasterRpcReadValueV1.Unavailable -> ReadReply.Unavailable(value.reason)
                        }
                    }
                    is RpcReply.ApplicationError -> ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN)
                }
            }
            requireCurrentCaller()
            return result
        } finally { owned.destroy() }
    }

    public suspend fun execute(
        credentials: LocalGatewayCredentials,
        request: MutationRequest,
        requestingActorId: DomainId,
        acceptRecorded: (suspend (MutationReply.Recorded) -> Unit)? = null,
    ): MutationReply {
        requireConnection(credentials)
        val capturedActor = requestingActorId
        val owned = LocalMasterRpcExecuteRequestV1.capture(credentials, request)
        val operationId = owned.request.operationId
        try {
            val result = invoke(executeProcedure, owned, accept = { result ->
                if (result is MutationReply.Recorded && acceptRecorded != null) {
                    acceptRecorded(result); true
                } else false
            }) { delivered ->
                when (delivered) {
                    is RpcReply.Success -> {
                        requireResponseBinding(delivered.value.requestingActorId == capturedActor)
                        when (val value = delivered.value.value) {
                            is LocalMasterRpcExecuteValueV1.Recorded -> reconstructRecorded(value.recorded, operationId, capturedActor)
                            is LocalMasterRpcExecuteValueV1.Deferred -> {
                                requireResponseBinding(value.operationId == operationId)
                                MutationReply.Deferred(operationId, value.reason)
                            }
                            is LocalMasterRpcExecuteValueV1.IdentityConflict -> {
                                requireResponseBinding(value.operationId == operationId)
                                MutationReply.IdentityConflict(operationId)
                            }
                        }
                    }
                    is RpcReply.ApplicationError -> MutationReply.Deferred(operationId, DeferredReason.STORAGE_UNCERTAIN)
                }
            }
            requireCurrentCaller()
            return result
        } finally { owned.destroy() }
    }

    public suspend fun lookup(
        credentials: LocalGatewayCredentials,
        identity: OperationIdentity,
        requestingActorId: DomainId,
        acceptRecorded: (suspend (MutationReply.Recorded) -> Unit)? = null,
    ): LookupReply {
        requireConnection(credentials)
        val capturedActor = requestingActorId
        val owned = LocalMasterRpcLookupRequestV1.capture(credentials, identity)
        val capturedIdentity = owned.identity
        val operationId = capturedIdentity.operationId
        try {
            val result = invoke(lookupProcedure, owned, accept = { result ->
                if (result is LookupReply.Found && acceptRecorded != null) {
                    acceptRecorded(result.result); true
                } else false
            }) { delivered ->
                when (delivered) {
                    is RpcReply.Success -> {
                        requireResponseBinding(delivered.value.requestingActorId == capturedActor)
                        when (val value = delivered.value.value) {
                            is LocalMasterRpcLookupValueV1.Found -> {
                                requireResponseBinding(value.recorded.replayed)
                                LookupReply.Found(reconstructRecorded(value.recorded, operationId, capturedIdentity.originalActorId))
                            }
                            is LocalMasterRpcLookupValueV1.AbsentAtCurrentWriter -> {
                                requireResponseBinding(value.operationId == operationId)
                                LookupReply.AbsentAtCurrentWriter(operationId)
                            }
                            is LocalMasterRpcLookupValueV1.Deferred -> {
                                requireResponseBinding(value.operationId == operationId)
                                LookupReply.Deferred(operationId, value.reason)
                            }
                            is LocalMasterRpcLookupValueV1.IdentityConflict -> {
                                requireResponseBinding(value.operationId == operationId)
                                LookupReply.IdentityConflict(operationId)
                            }
                        }
                    }
                    is RpcReply.ApplicationError -> LookupReply.Deferred(operationId, DeferredReason.STORAGE_UNCERTAIN)
                }
            }
            requireCurrentCaller()
            return result
        } finally { owned.destroy() }
    }

    /**
     * Fixed employee-creation route on this same selected-original loan. The request must
     * already own a copy of [credentials]' bearer; this call destroys it. The delivered reply
     * is actor-bound DATA only; the caller still checks its target/original binding.
     * A Host application error is undisclosed outcome, never proof of non-execution.
     */
    public suspend fun employeeCreation(
        credentials: LocalGatewayCredentials,
        request: LocalMasterRpcEmployeeCreationRequestV1,
        requestingActorId: DomainId,
    ): LocalMasterRpcEmployeeCreationResponseV1 {
        try {
            requireConnection(credentials)
            val result = invoke(LocalMasterEmployeeCreationRpcProtocolV1.PROCEDURE, request, accept = { false }) { delivered ->
                when (delivered) {
                    is RpcReply.Success -> {
                        requireResponseBinding(delivered.value.requestingActorId == requestingActorId)
                        delivered.value
                    }
                    is RpcReply.ApplicationError -> LocalMasterRpcEmployeeCreationResponseV1(requestingActorId,
                        LocalMasterRpcEmployeeCreationResultV1.Outcome(LocalMasterRpcEmployeeCreationResultV1.Code.UNAVAILABLE))
                }
            }
            requireCurrentCaller()
            return result
        } finally { request.destroy() }
    }

    private fun requireConnection(credentials: LocalGatewayCredentials) {
        require(credentials.connectionId == connectionId) { "Business connection differs" }
    }

    private suspend fun requireCurrentCaller() {
        currentCoroutineContext().ensureActive()
        check(isCurrent()) { "Original Master Client loan is unavailable" }
    }

    private suspend fun <Q : LocalMasterRpcOwnedRequestV1, R, T : Any> invoke(
        procedure: RpcProcedure<Q, R, LocalMasterClientRpcErrorV1>,
        request: Q,
        accept: suspend (T) -> Boolean,
        decode: (RpcReply<R, LocalMasterClientRpcErrorV1>) -> T,
    ): T {
        requireCurrentCaller()
        val observation = RpcCallObservation()
        // The official callback has no deferred public ACK token. A call without a real
        // durable consumer deliberately rejects Receipt, then returns only its validated DATA.
        // This exact per-call sentinel cannot swallow another call's acceptance failure.
        val withheld = ReceiptWithheld()
        var selected: T? = null
        try {
            client.callWithAcceptance(procedure, request, observation = observation,
                retry = RpcRetry.RecoverOnly(maxAttempts = 1)) { offered ->
                requireCurrentCaller()
                val observed = observation.progress.value
                requireResponseBinding(observed.requestId == offered.details.requestId.value &&
                    observed.peerFingerprint == approvedMaster.masterSdkOriginal.fingerprint.value &&
                    offered.hostFingerprint == approvedMaster.masterSdkOriginal.fingerprint &&
                    offered.procedureName == procedure.name && offered.procedureVersion == procedure.version &&
                    offered.hostIncarnation.isNotBlank() &&
                    observed.executionEvidence == RpcExecutionEvidence.HandlerFinished)
                val result = decode(offered.details.reply)
                selected = result
                if (!accept(result)) throw withheld
                requireCurrentCaller()
            }
        } catch (failure: ReceiptWithheld) {
            if (failure !== withheld) throw failure
        }
        requireCurrentCaller()
        return checkNotNull(selected) { "Master business reply unavailable" }
    }

    private class ReceiptWithheld : Exception()

    private fun reconstructRecorded(value: LocalMasterRpcRecordedV1, operationId: OperationId, originalActorId: DomainId): MutationReply.Recorded {
        requireResponseBinding(value.operationId == operationId && value.originalActorId == originalActorId &&
            value.committed == (value.code == "committed"))
        requireProtocolName(value.code)
        return MutationReply.Recorded(value.operationId, value.originalActorId, value.committed, value.code,
            copyRpcStoredBytes(value.result), value.authorityEpoch, value.sequence, value.recordedAt, value.replayed)
    }

    private fun requireResponseBinding(matches: Boolean) {
        if (!matches) throw SerializationException("Master business response binding differs")
    }

    override fun toString(): String = "LocalMasterClientRpcBridgeV1([REDACTED])"

    public companion object {
        /** Requires the actual retained native-selected original/factory owner gate, not caller DATA. */
        public fun bind(
            client: RpcClient,
            original: LocalDevicePairingSdkOriginal,
            approvedMaster: LocalDevicePairingApprovedMaster,
            lifetime: Job,
            connectionId: DomainId,
        ): LocalMasterClientRpcBridgeV1 = bind(client, original, approvedMaster, lifetime, connectionId,
            LocalMasterRpcWorkflowProfile.SUPPLIERS)

        /** The retained original owner fixes the workflow profile before any request is captured. */
        public fun bind(
            client: RpcClient,
            original: LocalDevicePairingSdkOriginal,
            approvedMaster: LocalDevicePairingApprovedMaster,
            lifetime: Job,
            connectionId: DomainId,
            profile: LocalMasterRpcWorkflowProfile,
        ): LocalMasterClientRpcBridgeV1 {
            val sdk = original.identity
            val originalBytes = sdk.copyWireBytes()
            val selectedBytes = approvedMaster.targetSdkIdentity.copyWireBytes()
            try { require(originalBytes.contentEquals(selectedBytes)) { "Approved Master target original differs" } }
            finally { originalBytes.fill(0); selectedBytes.fill(0) }
            val target = sdk.target
            val scope = target.admission.scope
            val master = approvedMaster.masterSdkOriginal
            require(approvedMaster.scope == scope && approvedMaster.targetDeviceId == target.admission.deviceId &&
                approvedMaster.targetKeyId == target.keyId && master.allocation.coordinates.scope == scope) {
                "Approved Master target scope differs"
            }
            val appId = master.compositionProfile.appId(scope)
            require(sdk.appId == appId && master.appId == appId) { "Approved Master AppId differs" }
            require(master.originalAcquisitionId != sdk.originalAcquisitionId &&
                master.originalAcquisitionId != sdk.originalPreparationId && master.peerId != sdk.peerId &&
                master.fingerprint != sdk.fingerprint && master.pairingQr != sdk.pairingQr) {
                "Approved Master is not distinct from its target"
            }
            LocalDevicePairingProfileV2.validateAcquisitionText(original.exactDeviceName)
            return LocalMasterClientRpcBridgeV1(client, original, approvedMaster, lifetime, connectionId, profile).also {
                check(it.isCurrent()) { "Original Master Client loan is unavailable" }
            }
        }
    }
}
