package com.ukkera.pos.rpc

import com.ukkera.pos.database.LocalMasterRpcBusinessAccess
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import dev.p2pkit.rpc.RpcApplicationSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Immutable operation DATA retained by the exact actual SDK resource session. Pooling
 * avoids allocating one SDK session for every Execute/Lookup. Neither membership nor
 * the resource digest grants access: the Master checks the full request and current
 * protected state at each invocation, execution, recovery and disclosure boundary.
 * Entries are removed only after confirmed revocation of that exact SDK session.
 */
internal class LocalRpcBusinessOperations {
    internal class Operation internal constructor(
        val access: LocalMasterRpcBusinessAccess,
        val procedure: String,
        val requestBinding: PayloadDigest?,
        val resource: String,
        internal val bytes: Long,
    )

    private class Session(val actual: RpcApplicationSession) {
        val operations = LinkedHashMap<String, Operation>()
        var bytes = 0L
    }

    private val gate = Mutex()
    private val sessions = mutableListOf<Session>()
    private var count = 0
    private var bytes = 0L

    suspend fun admit(actual: RpcApplicationSession, access: LocalMasterRpcBusinessAccess,
        procedure: String, requestBinding: PayloadDigest?): Operation? {
        if (access is LocalMasterRpcBusinessAccess.Read || !actual.isActive) return null
        val fields = fields(access, procedure, requestBinding)
        val encoded = fields.joinToString("") { "${it.length}:$it" }.encodeToByteArray()
        val metadataBytes = encoded.size.toLong()
        val digest = try { sha256(encoded) } finally { encoded.fill(0) }
        val resource = "$procedure#${digest.value}"
        if (resource.length > 256) return null
        val retainedBytes = metadataBytes + when (access) {
            is LocalMasterRpcBusinessAccess.Execute -> access.request.payload.size.toLong()
            is LocalMasterRpcBusinessAccess.Lookup -> 0L
            is LocalMasterRpcBusinessAccess.Read -> return null
        }
        return gate.withLock {
            if (!actual.isActive) return@withLock null
            val session = sessions.firstOrNull { it.actual === actual }
            val prior = session?.operations?.get(resource)
            if (prior != null) return@withLock prior.takeIf {
                it.procedure == procedure && it.requestBinding == requestBinding && sameBusinessAccess(it.access, access)
            }
            if ((session?.operations?.size ?: 0) >= MAX_SESSION_OPERATIONS || count >= MAX_GLOBAL_OPERATIONS ||
                (session?.bytes ?: 0L) + retainedBytes > MAX_SESSION_BYTES || bytes + retainedBytes > MAX_GLOBAL_BYTES)
                return@withLock null
            // The access types, requests, IDs and StoredBytes are immutable. Retain their
            // bounded DATA directly; never retain the bearer-bearing decoded RPC request.
            val operation = Operation(access, procedure, requestBinding, resource, retainedBytes)
            val target = session ?: Session(actual).also { sessions.add(it) }
            target.operations[resource] = operation
            target.bytes += retainedBytes; count++; bytes += retainedBytes
            operation
        }
    }

    suspend fun capture(actual: RpcApplicationSession, resource: String): Operation? = gate.withLock {
        sessions.firstOrNull { it.actual === actual }?.operations?.get(resource)
    }

    suspend fun current(actual: RpcApplicationSession, operation: Operation): Boolean = gate.withLock {
        sessions.firstOrNull { it.actual === actual }?.operations?.get(operation.resource) === operation
    }

    /** Called after this exact session's SDK revocation and private binding release settle. */
    suspend fun removeRevoked(actual: RpcApplicationSession) = gate.withLock {
        check(!actual.isActive)
        sessions.firstOrNull { it.actual === actual }?.let {
            sessions.remove(it); count -= it.operations.size; bytes -= it.bytes
        }
        check(count >= 0 && bytes >= 0)
    }

    suspend fun removeAllRevoked() = gate.withLock {
        check(sessions.none { it.actual.isActive })
        sessions.clear(); count = 0; bytes = 0L
    }

    private fun fields(access: LocalMasterRpcBusinessAccess, procedure: String, binding: PayloadDigest?): List<String> {
        val common = listOf("ukkera.pos.business.operation/v1", access.profile.name, procedure, binding?.value.orEmpty())
        return common + when (access) {
            is LocalMasterRpcBusinessAccess.Execute -> access.request.let {
                val payload = it.payload.copyBytes()
                val payloadDigest = try { sha256(payload) } finally { payload.fill(0) }
                listOf("execute", it.operationId.value, it.command.name, it.command.version.toString(),
                    payloadDigest.value, it.actionApprovalId?.value.orEmpty())
            }
            is LocalMasterRpcBusinessAccess.Lookup -> access.identity.let {
                listOf("lookup", it.branchId.value, it.operationId.value, it.originalActorId.value,
                    it.semanticDigestVersion.toString(), it.semanticSha256)
            }
            is LocalMasterRpcBusinessAccess.Read -> error("Read scopes have separate selector custody")
        }
    }

    private companion object {
        const val MAX_SESSION_OPERATIONS = 1_024
        const val MAX_GLOBAL_OPERATIONS = 8_192
        const val MAX_SESSION_BYTES = 1_048_576L
        const val MAX_GLOBAL_BYTES = 8_388_608L
    }
}

internal fun sameBusinessAccess(left: LocalMasterRpcBusinessAccess, right: LocalMasterRpcBusinessAccess): Boolean = when {
    left.profile != right.profile -> false
    left is LocalMasterRpcBusinessAccess.Read && right is LocalMasterRpcBusinessAccess.Read ->
        left.request.query == right.request.query && left.request.payload == right.request.payload
    left is LocalMasterRpcBusinessAccess.Execute && right is LocalMasterRpcBusinessAccess.Execute ->
        left.request.operationId == right.request.operationId && left.request.command == right.request.command &&
            left.request.payload == right.request.payload && left.request.actionApprovalId == right.request.actionApprovalId
    left is LocalMasterRpcBusinessAccess.Lookup && right is LocalMasterRpcBusinessAccess.Lookup -> left.identity == right.identity
    else -> false
}
