package com.ukkera.pos.app.gateway.independent.documentaryclientv2

import com.ukkera.pos.app.gateway.LocalEndpointWorkflowGateway
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialSource
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.app.gateway.StoredClientDocumentarySourceV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentChunkQueryV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentChunkReadResultV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentIntakeViewV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentProfileV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentQueryV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentReadResultV1
import com.ukkera.pos.catalog.store.LocalPurchasingDocumentResultV1
import com.ukkera.pos.database.DeferredReason
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadFailure
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.jsonObject

/**
 * Controlled protocol input/output for bounded U receiver/continuation assertions.
 * This is NOT a Master/history owner, current-grant authority, storage, network or RPC
 * fixture. Its result census is deliberately not called committed native history.
 */
internal class ControlledDocumentaryClientPort(val source: StoredClientDocumentarySourceV1) : LocalMasterClient {
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    var view = FrozenDocumentaryClientInputsV2.view()
    var leaseCurrent = true
    var recoveryAllowed = true
    var freshWorkAllowed = true
    var connectionId = DomainId.random()
    var loseReplyAt: OperationId? = null
    var executionDeferred: DeferredReason? = null
    var beforeExecute: suspend (MutationRequest) -> Unit = {}
    var afterExecute: suspend (MutationRequest) -> Unit = {}
    var resultBody: (MutationRequest) -> LocalPurchasingDocumentResultV1 = { FrozenDocumentaryClientInputsV2.acceptedBody(source, it) }
    var envelope: (MutationReply.Recorded) -> MutationReply.Recorded = { it }
    var readReply: suspend (ReadRequest) -> ReadReply = { ReadReply.Unavailable(ReadFailure.UNKNOWN_QUERY) }
    val executions = mutableListOf<MutationRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    val reads = mutableListOf<ReadRequest>()
    val connectionObservations = mutableListOf<DomainId>()
    val controlledResults = mutableMapOf<OperationId, MutationReply.Recorded>()

    fun gateway(host: SyntheticDocumentaryEncryptedJournal): LocalEndpointWorkflowGateway = LocalEndpointWorkflowGateway(
        this, host.journal, LocalWorkflowCredentialSource {
            if (!leaseCurrent) null else LocalWorkflowCredentialLease(
                LocalGatewayCredentials(connectionId, OpaqueCredential(ByteArray(32) { 0x31 }))) { leaseCurrent }
        }, LocalWorkflowInstallation(FrozenDocumentaryClientInputsV2.commands,
            setOf(LocalPurchasingDocumentProfileV1.READ, LocalPurchasingDocumentProfileV1.BYTES)))

    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        connectionObservations += credentials.connectionId
        executions += request
        beforeExecute(request)
        executionDeferred?.let { return MutationReply.Deferred(request.operationId, it) }
        if (!freshWorkAllowed) return MutationReply.Deferred(request.operationId, DeferredReason.AUTHORIZATION_DENIED)
        val reply = controlledResults.getOrPut(request.operationId) {
            val body = resultBody(request)
            controlledRecorded(source, request, body, body.code in acceptedCodes, executions.size.toLong())
        }
        afterExecute(request)
        if (loseReplyAt == request.operationId) {
            loseReplyAt = null
            return MutationReply.Deferred(request.operationId, DeferredReason.STORAGE_UNCERTAIN)
        }
        return envelope(reply)
    }

    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        connectionObservations += credentials.connectionId
        lookups += identity
        if (!recoveryAllowed) return LookupReply.Deferred(identity.operationId, DeferredReason.AUTHORIZATION_DENIED)
        return controlledResults[identity.operationId]?.let { LookupReply.Found(envelope(replayed(it))) }
            ?: LookupReply.AbsentAtCurrentWriter(identity.operationId)
    }

    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        connectionObservations += credentials.connectionId
        reads += request
        return readReply(request)
    }

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = LocalSessionViewResult.Current(view)
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = LocalSignOutResult.Denied

    companion object {
        private val acceptedCodes = setOf("purchasing.document.begun", "purchasing.document.chunk_stored",
            "purchasing.document.captured", "purchasing.document.attachment_captured")
    }
}

/** Controlled envelopes only; construction here is never native result publication. */
internal fun controlledRecorded(source: StoredClientDocumentarySourceV1, request: MutationRequest,
    body: LocalPurchasingDocumentResultV1 = FrozenDocumentaryClientInputsV2.acceptedBody(source, request),
    committed: Boolean = true, sequence: Long = 1): MutationReply.Recorded = MutationReply.Recorded(
    request.operationId, source.owner.actorId, committed, if (committed) "committed" else body.code,
    FrozenDocumentaryClientInputsV2.encode(body, LocalPurchasingDocumentResultV1.serializer()), Revision(1),
    JournalSequence(sequence), FrozenDocumentaryClientInputsV2.controlTime, replayed = false)

internal fun replayed(result: MutationReply.Recorded): MutationReply.Recorded = MutationReply.Recorded(result.operationId,
    result.originalActorId, result.committed, result.code, result.result, result.authorityEpoch, result.sequence,
    result.recordedAt, replayed = true, physicalStart = result.physicalStart)

/** Exact frozen bytes as controlled protected-read replies, without native history qualification. */
internal class ControlledDocumentaryReadReplies(private val document: FrozenDocument) {
    var projection = FrozenDocumentaryClientInputsV2.fileProjection(document)
    var beforeRead: suspend (ReadRequest) -> Unit = {}
    var slot: (Int) -> LocalPurchasingDocumentChunkReadResultV1? = { index -> originalSlot(index) }
    var denied = false
    val queries = mutableListOf<LocalPurchasingDocumentChunkQueryV1>()

    suspend fun reply(request: ReadRequest): ReadReply {
        beforeRead(request)
        if (denied) return ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
        val data = FrozenDocumentaryClientInputsV2.json.parseToJsonElement(request.payload.copyBytes().decodeToString()).jsonObject
        return when (request.query) {
            LocalPurchasingDocumentProfileV1.READ -> {
                val query = FrozenDocumentaryClientInputsV2.json.decodeFromJsonElement(LocalPurchasingDocumentQueryV1.serializer(), data)
                check(query.documentId == document.id && query.after == null && query.pendingDocumentId == null)
                ReadReply.Data(FrozenDocumentaryClientInputsV2.encode(LocalPurchasingDocumentReadResultV1(
                    detail = LocalPurchasingDocumentIntakeViewV1(projection)), LocalPurchasingDocumentReadResultV1.serializer()),
                    Revision(1), FrozenDocumentaryClientInputsV2.controlTime)
            }
            LocalPurchasingDocumentProfileV1.BYTES -> {
                val query = FrozenDocumentaryClientInputsV2.json.decodeFromJsonElement(LocalPurchasingDocumentChunkQueryV1.serializer(), data)
                queries += query
                check(query.documentId == document.id)
                val value = slot(query.chunkIndex) ?: return ReadReply.Unavailable(ReadFailure.STORAGE_UNCERTAIN)
                ReadReply.Data(FrozenDocumentaryClientInputsV2.encode(value, LocalPurchasingDocumentChunkReadResultV1.serializer()),
                    Revision(1), FrozenDocumentaryClientInputsV2.controlTime)
            }
            else -> ReadReply.Unavailable(ReadFailure.UNKNOWN_QUERY)
        }
    }

    fun originalSlot(index: Int): LocalPurchasingDocumentChunkReadResultV1 = LocalPurchasingDocumentChunkReadResultV1(
        document.id, projection.byteLength, projection.exactBytesDigest, projection.media,
        "purchasing-document-exact-bytes/v1", index, document.file.slots[index].byteLength,
        document.file.slots[index].digest, FrozenDocumentaryClientInputsV2.hex(document.file.slot(index)))
}
