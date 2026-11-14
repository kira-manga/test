package com.ukkera.pos.rpc

import com.ukkera.pos.database.LocalMasterEmployeeCreationClient
import com.ukkera.pos.database.LocalMasterEmployeeCreationRpcProtocolV1
import com.ukkera.pos.database.LocalMasterRpcEmployeeCreationRequestV1
import com.ukkera.pos.security.LocalDelegatedEmployeeCreationV1
import com.ukkera.pos.security.LocalEmployeeCreationCancellationV1
import com.ukkera.pos.security.LocalEmployeeCreationContextResultV1
import com.ukkera.pos.security.LocalEmployeeCreationDefinitionResultV1
import com.ukkera.pos.security.LocalEmployeeCreationDraftV1
import com.ukkera.pos.security.LocalEmployeeCreationExecutionV1
import com.ukkera.pos.security.LocalEmployeeCreationReviewV1
import com.ukkera.pos.security.LocalEmployeeCreationStatusV1
import com.ukkera.pos.security.LocalEmployeeCreationTargetV1
import com.ukkera.pos.security.LocalGatewayCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Remote employee creation on the SAME retained selected-original Client and its exact
 * authentication connection. The actor comes from a fresh actual Current on that connection;
 * the Master independently reauthorizes the creator, target and original on every call.
 * A failed Execute or Cancel delivery is storage-uncertain, never a safe absence: the caller
 * keeps the original and resolves it through Lookup. No call is retried here.
 */
public class LocalRpcRemoteEmployeeCreationClient internal constructor(
    private val owner: LocalRpcOriginalClientOwner,
    private val directory: LocalRpcRemoteDirectoryClient,
) : LocalMasterEmployeeCreationClient {
    public fun isCurrent(): Boolean = directory.isCurrent()

    private suspend fun <T : Any> exchange(credentials: LocalGatewayCredentials, failed: () -> T, busy: () -> T,
        build: suspend (LocalGatewayCredentials) -> LocalMasterRpcEmployeeCreationRequestV1,
        decode: (com.ukkera.pos.types.DomainId, com.ukkera.pos.database.LocalMasterRpcEmployeeCreationResponseV1) -> T): T {
        currentCoroutineContext().ensureActive()
        return try {
            owner.directoryExchange(directory, credentials, null, failed, busy) { bridge, actual, actor ->
                val response = bridge.employeeCreation(actual, build(actual), actor)
                decode(actor, response)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { currentCoroutineContext().ensureActive(); failed() }
    }

    override suspend fun employeeCreationContext(operator: LocalGatewayCredentials,
        target: LocalEmployeeCreationTargetV1): LocalEmployeeCreationContextResultV1 =
        exchange(operator, { LocalEmployeeCreationContextResultV1.Unavailable }, { LocalEmployeeCreationContextResultV1.Busy },
            { LocalMasterRpcEmployeeCreationRequestV1.context(it, target) }) { actor, response ->
            LocalMasterEmployeeCreationRpcProtocolV1.decodeContext(target, actor, response)
        }

    override suspend fun captureEmployeeCreationDefinition(operator: LocalGatewayCredentials,
        draft: LocalEmployeeCreationDraftV1): LocalEmployeeCreationDefinitionResultV1 =
        exchange(operator, { LocalEmployeeCreationDefinitionResultV1.Unavailable }, { LocalEmployeeCreationDefinitionResultV1.Busy },
            { LocalMasterRpcEmployeeCreationRequestV1.capture(it, draft) }) { actor, response ->
            LocalMasterEmployeeCreationRpcProtocolV1.decodeCapture(draft, actor, response)
        }

    override suspend fun reviewEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationReviewV1 =
        exchange(operator, { LocalEmployeeCreationReviewV1.Unavailable }, { LocalEmployeeCreationReviewV1.Unavailable },
            { LocalMasterRpcEmployeeCreationRequestV1.review(it, original) }) { actor, response ->
            LocalMasterEmployeeCreationRpcProtocolV1.decodeReview(original, actor, response)
        }

    override suspend fun lookupEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationStatusV1 =
        exchange(operator, { LocalEmployeeCreationStatusV1.Unavailable }, { LocalEmployeeCreationStatusV1.Unavailable },
            { LocalMasterRpcEmployeeCreationRequestV1.lookup(it, original) }) { actor, response ->
            LocalMasterEmployeeCreationRpcProtocolV1.decodeLookup(original, actor, response)
        }

    override suspend fun executeEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1, newPassword: CharArray?): LocalEmployeeCreationExecutionV1 {
        // The caller retains its array. One owned copy moves into the request exactly once; an
        // abandoned caller destroys it first, so a late worker refuses instead of sending it.
        val password = OwnedPassword(newPassword?.copyOf())
        try {
            return exchange(operator, { LocalEmployeeCreationExecutionV1.StorageUncertain },
                { LocalEmployeeCreationExecutionV1.Busy },
                { actual -> password.take { LocalMasterRpcEmployeeCreationRequestV1.execute(actual, original, it) } }) { actor, response ->
                LocalMasterEmployeeCreationRpcProtocolV1.decodeExecution(original, actor, response)
            }
        } finally { withContext(NonCancellable) { password.destroy() } }
    }

    private class OwnedPassword(private var value: CharArray?) {
        private val gate = Mutex()
        private var destroyed = false
        suspend fun <T> take(use: (CharArray?) -> T): T = gate.withLock {
            check(!destroyed) { "Employee-creation password was abandoned" }
            try { use(value) } finally { value?.fill('\u0000'); value = null; destroyed = true }
        }
        suspend fun destroy() = gate.withLock { value?.fill('\u0000'); value = null; destroyed = true }
    }

    override suspend fun cancelEmployeeCreation(operator: LocalGatewayCredentials,
        original: LocalDelegatedEmployeeCreationV1): LocalEmployeeCreationCancellationV1 =
        exchange(operator, { LocalEmployeeCreationCancellationV1.StorageUncertain }, { LocalEmployeeCreationCancellationV1.Busy },
            { LocalMasterRpcEmployeeCreationRequestV1.cancel(it, original) }) { actor, response ->
            LocalMasterEmployeeCreationRpcProtocolV1.decodeCancellation(original, actor, response)
        }

    override fun toString(): String = "LocalRpcRemoteEmployeeCreationClient([REDACTED])"
}
