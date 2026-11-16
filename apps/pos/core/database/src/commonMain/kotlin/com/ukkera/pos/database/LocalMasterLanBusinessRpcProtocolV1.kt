package com.ukkera.pos.database

import dev.p2pkit.rpc.RpcProcedure

/**
 * Additive, closed LAN business routes. Reusing the existing carriers preserves their
 * credential/result custody and byte limits; it does not admit a command or grant authority.
 * Each installed typed handler must separately opt in to the LAN_BUSINESS profile and the
 * actual Master checks current access at invocation, commit, recovery and disclosure.
 */
public object LocalMasterLanBusinessRpcProtocolV1 {
    public val READ: RpcProcedure<LocalMasterRpcReadRequestV1, LocalMasterRpcReadResponseV1, LocalMasterClientRpcErrorV1> =
        route("pos.lan-business.read", LocalMasterClientRpcProtocolV1.READ)
    public val EXECUTE: RpcProcedure<LocalMasterRpcExecuteRequestV1, LocalMasterRpcExecuteResponseV1, LocalMasterClientRpcErrorV1> =
        route("pos.lan-business.execute", LocalMasterClientRpcProtocolV1.EXECUTE)
    public val LOOKUP: RpcProcedure<LocalMasterRpcLookupRequestV1, LocalMasterRpcLookupResponseV1, LocalMasterClientRpcErrorV1> =
        route("pos.lan-business.lookup", LocalMasterClientRpcProtocolV1.LOOKUP)

    private fun <Q, R> route(name: String, source: RpcProcedure<Q, R, LocalMasterClientRpcErrorV1>):
        RpcProcedure<Q, R, LocalMasterClientRpcErrorV1> = RpcProcedure(
        name = name,
        version = 1,
        request = source.request,
        response = source.response,
        applicationError = source.applicationError,
        retrySafety = source.retrySafety,
        requestLimitBytes = source.requestLimitBytes,
        responseLimitBytes = source.responseLimitBytes,
        errorLimitBytes = source.errorLimitBytes,
    )
}
