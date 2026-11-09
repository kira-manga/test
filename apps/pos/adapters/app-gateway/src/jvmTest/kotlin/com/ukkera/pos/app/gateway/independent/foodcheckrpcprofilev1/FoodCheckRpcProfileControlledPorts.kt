package com.ukkera.pos.app.gateway.independent.foodcheckrpcprofilev1

import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

/** Observable controlled-port wait, not an actual native flush, commit or SDK callback. */
internal class FoodCheckSupportGate {
    val entered = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    suspend fun waitHere() { entered.complete(Unit); released.await() }
    fun release() { released.complete(Unit) }
}

internal enum class FoodCheckSupportWrite(val retain: Boolean, val answer: JournalWrite) {
    COMPLETED(true, JournalWrite.DURABLE), FAILED(false, JournalWrite.UNAVAILABLE),
    RETAINED_ANSWER_UNKNOWN(true, JournalWrite.UNAVAILABLE),
}

/**
 * An in-memory input/remembrance order seam only. DURABLE is a scripted answer,
 * not native custody; no protected client storage or restart is installed.
 */
internal class FoodCheckSupportJournal(private val events: MutableList<String>) :
    LocalClientIntentJournal(FrozenFoodCheckRpcProfileInputs.owner) {
    val entries = linkedMapOf<OperationId, StoredClientIntentV1>()
    var readable = true
    var stageWrite = FoodCheckSupportWrite.COMPLETED
    var dispatchWrite = FoodCheckSupportWrite.COMPLETED
    var rememberWrite = FoodCheckSupportWrite.COMPLETED
    var ackWrite = FoodCheckSupportWrite.COMPLETED
    var stageGate: FoodCheckSupportGate? = null
    var dispatchGate: FoodCheckSupportGate? = null
    var rememberGate: FoodCheckSupportGate? = null
    var ackGate: FoodCheckSupportGate? = null
    var stageCalls = 0
    var dispatchCalls = 0
    var rememberCalls = 0
    var ackCalls = 0
    val retired = mutableListOf<OperationId>()

    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> =
        if (readable) JournalRead.Value(entries[operationId]) else JournalRead.Unavailable
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> =
        if (readable) JournalRead.Value(entries.values.filterNot { it.acknowledged }) else JournalRead.Unavailable

    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCalls++
        intent.validate(owner)
        events += "intent.before-retention"
        stageGate?.waitHere()
        val prior = entries[intent.operationId]
        if (prior != null && !prior.sameIntent(intent)) return JournalWrite.IDENTITY_CONFLICT
        if (stageWrite.retain) { entries[intent.operationId] = prior ?: intent; events += "intent.retained" }
        return stageWrite.answer
    }

    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        dispatchCalls++
        val original = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        events += "dispatch.before-retention"
        dispatchGate?.waitHere()
        if (dispatchWrite.retain) {
            entries[operationId] = original.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name)
            events += "dispatch.retained"
        }
        return dispatchWrite.answer
    }

    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        rememberCalls++
        val original = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        events += "result.before-retention"
        rememberGate?.waitHere()
        if (original.recorded != null && original.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        if (rememberWrite.retain) {
            entries[operationId] = original.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = result)
            events += "result.retained"
        }
        return rememberWrite.answer
    }

    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        ackCalls++
        val original = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (original.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        events += "ack.before-retention"
        ackGate?.waitHere()
        if (ackWrite.retain) {
            entries[operationId] = original.copy(acknowledged = true)
            if (!original.acknowledged) retired += operationId
            events += "ack.retained"
        }
        return ackWrite.answer
    }

    override suspend fun close() = Unit
}

/**
 * Current views, read DATA, Lookup statuses and negative terminal DTOs are
 * supplied port inputs only. No authenticator, actual writer, source loan,
 * resource/disclosure fence, transport Receipt or Master result ACK is supplied.
 */
internal class FoodCheckSupportMaster(private val events: MutableList<String>) : LocalMasterClient {
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    var view = FrozenFoodCheckRpcProfileInputs.view()
    var currentReply: () -> LocalSessionViewResult = { LocalSessionViewResult.Current(view) }
    val reads = mutableListOf<ReadRequest>()
    val mutations = mutableListOf<MutationRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    var readReply: (ReadRequest) -> ReadReply = { request -> when (request.query) {
        LocalFoodQuotationProfileReadCodecV1.QUERY -> data(FrozenFoodCheckRpcProfileInputs.bytes("{\"installed\":false}"))
        LocalFoodProfileV1.QUERY -> {
            check(LocalFoodReadCodecV1.decode(request.payload) is LocalFoodQueryV1.Own)
            data(FrozenFoodCheckRpcProfileInputs.bytes(FrozenFoodCheckRpcProfileInputs.wire.encodeToString(
                LocalFoodReadResultV1.serializer(), LocalFoodReadResultV1())))
        }
        else -> error("No current native order, menu, recipe or preview cut is bound")
    } }
    var lookupReply: (OperationIdentity) -> LookupReply = { LookupReply.Deferred(it.operationId, DeferredReason.WRITER_NOT_QUALIFIED) }
    var executeReply: (MutationRequest) -> MutationReply = { MutationReply.Deferred(it.operationId, DeferredReason.WRITER_NOT_QUALIFIED) }

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = LocalSignOutResult.Unavailable
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = currentReply()
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        return readReply(request)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        return lookupReply(identity)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        events += "execute.enter"
        mutations += request
        return executeReply(request)
    }

    private fun data(payload: StoredBytes): ReadReply.Data = ReadReply.Data(payload, Revision(1),
        UtcInstant.parseCanonical("1970-01-01T00:00:00Z"))
}

internal class FoodCheckSupportFixture {
    val events = mutableListOf<String>()
    val master = FoodCheckSupportMaster(events)
    val journal = FoodCheckSupportJournal(events)
    val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.FOOD_CHECKS)
    var current = true
    var connection = FrozenFoodCheckRpcProfileInputs.id("sessionA")
    private val leases = mutableListOf<LocalWorkflowCredentialLease>()
    private val source = LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(FrozenFoodCheckRpcProfileInputs.credentials(connection)) { current }.also(leases::add)
    }
    val gateway = reconstructGateway()

    fun reconstructGateway(): LocalEndpointWorkflowGateway = LocalEndpointWorkflowGateway(master, journal, source, installed)
    suspend fun <T> withReadAccess(body: suspend (GatewayAccess) -> T): T {
        val lease = LocalWorkflowCredentialLease(FrozenFoodCheckRpcProfileInputs.credentials(connection)) { current }
        return try { body(GatewayAccess(master, lease, master.view, installed)) } finally { lease.destroy() }
    }
    suspend fun close() { leases.forEach { it.destroy() }; journal.close() }
}

internal suspend fun <T> withFoodCheckSupportFixture(body: suspend (FoodCheckSupportFixture) -> T): T {
    val fixture = FoodCheckSupportFixture()
    return try { body(fixture) } finally { fixture.close() }
}
