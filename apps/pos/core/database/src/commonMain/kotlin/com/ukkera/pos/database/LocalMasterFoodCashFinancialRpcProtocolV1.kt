package com.ukkera.pos.database

import dev.p2pkit.rpc.RpcProcedure

/** Closed FOOD financial continuation using the existing exact carriers and call lifetime.
 * These names grant no authority and supply no native physical capability. */
public object LocalMasterFoodCashFinancialRpcProtocolV1 {
    public val READ: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        RpcProcedure(
            name = "pos.food-cash-financial.read", version = 1,
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
            name = "pos.food-cash-financial.execute", version = 1,
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
            name = "pos.food-cash-financial.lookup", version = 1,
            request = LocalMasterClientRpcProtocolV1.LOOKUP.request,
            response = LocalMasterClientRpcProtocolV1.LOOKUP.response,
            applicationError = LocalMasterClientRpcProtocolV1.LOOKUP.applicationError,
            retrySafety = LocalMasterClientRpcProtocolV1.LOOKUP.retrySafety,
            requestLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.requestLimitBytes,
            responseLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.responseLimitBytes,
            errorLimitBytes = LocalMasterClientRpcProtocolV1.LOOKUP.errorLimitBytes,
        )
}
