@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.ukkera.pos.app.gateway.independent.localserialregistration

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.StateFlow

/**
 * Unchanged local-serial-registration-v1 F-LSR-SCOPE/IDENTITY/SKU/SUCCESSOR/
 * TIME/PENDING request coordinates. Allocation, authority, digest, signing key,
 * device and session coordinates are inert schema carriers. The zero signature
 * authenticates nothing. No native original, qualified time, active SKU, Master
 * result or physically held stock is supplied by this fixture.
 */
internal object LsrGatewayInputs {
    val scope = BranchScope(id("11000000-0000-4000-8000-000000000001"),
        id("31000000-0000-4000-8000-000000000001"), id("41000000-0000-4000-8000-000000000001"))
    val actor = id("51000000-0000-4000-8000-000000000001")
    val otherActor = id("51000000-0000-4000-8000-000000000002")
    val operation = OperationId("f1000000-0000-4000-8000-000000000001")
    val otherOperation = OperationId("f1000000-0000-4000-8000-000000000002")
    val registration = id("a1000000-0000-4000-8000-000000000001")
    val publication = id("b1000000-0000-4000-8000-000000000004")
    val serial = id("c1000000-0000-4000-8000-000000000001")
    val sku = id("d1000000-0000-4000-8000-000000000001")
    val skuPublication = id("e1000000-0000-4000-8000-000000000003")
    val from = UtcInstant.parseCanonical("2031-04-05T12:00:00Z")
    val until = UtcInstant.parseCanonical("2031-04-05T13:00:00Z")
    const val code = "000AbC-07"
    val inertDigest = PayloadDigest("01".repeat(32))
    val device = carrier(5)
    val session = carrier(6)
    val owner = LocalClientIdentity(scope, actor, device)

    fun signedShape(code: String = LsrGatewayInputs.code): LocalSignedSerialRegistrationV1 =
        LocalSignedSerialRegistrationV1(LocalPublishedSerialRegistrationV1(scope, carrier(1), inertDigest,
            LocalSerialAuthorityReferenceV1(carrier(2), carrier(3), inertDigest, inertDigest),
            publication, registration, Revision(4), LocalSerialRegistrationReferenceV1(registration,
                id("b1000000-0000-4000-8000-000000000003"), Revision(3), inertDigest),
            serial, code, sku, skuPublication, inertDigest, from, until), carrier(4), "0".repeat(128))

    fun request(code: String = LsrGatewayInputs.code): MutationRequest = LocalSerialRegistrationCodecV1.activate(
        operation, LocalActivateSerialRegistrationV1(signedShape(code), null))

    fun intent(operationId: OperationId = operation): LocalCommandIntent = LocalCommandIntent(operationId,
        LocalAction.SERIAL_REGISTRATION_ACTIVATE, 1,
        LocalRecordReference(LocalRecordKind.SERIAL_REGISTRATION_PUBLICATION, publication), listOf(
            LocalScalarInput(LocalField.DOCUMENT_ID, LocalScalarValue.Identifier(registration)),
            LocalScalarInput(LocalField.SOURCE_ID, LocalScalarValue.Identifier(publication)),
            LocalScalarInput(LocalField.CONFIRMED, LocalScalarValue.Flag(true))))

    fun entry(code: String = LsrGatewayInputs.code): StoredClientIntentV1 = StoredClientIntentV1.staged(
        owner, intent(), request(code), text("Exact serial activation"), listOf(
            LocalRecordValue(text("Exact serial / code / SKU"), text("${serial.value} / $code / ${sku.value}")),
            LocalRecordValue(text("Registration / publication / revision"), text("${registration.value} / ${publication.value} / 4")),
            LocalRecordValue(text("Interval input; qualified time unavailable"), text("${from.value.toEpochMilliseconds()} / ${until.value.toEpochMilliseconds()}"))))

    fun currentView(granted: Boolean = true, actorId: DomainId = actor, branchScope: BranchScope = scope,
        deviceId: DomainId = device): LocalSessionView = LocalSessionView(actorId, branchScope, deviceId,
        session, SessionGeneration(1), LocalLoginIdentifier("synthetic-serial-requester"),
        ActivatedLocalPolicy(branchScope, actorId, Revision(1),
            if (granted) listOf(PermissionGrant(Permission(SecurityAction.CATALOG_ACTIVATE,
                PermissionVerb.ADMINISTER), branchScope, GrantEffect.ALLOW)) else emptyList(),
            LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(session, 1_000), MonotonicMoment(session, 2_000))

    fun text(value: String) = LocalText(value, value)
    fun id(value: String) = DomainId(value)
    private fun carrier(index: Int) = id("73000000-0000-4000-8000-${index.toString().padStart(12, '0')}")
}

/** Controlled client DATA only. DURABLE here is a scripted response, not native persistence. */
internal class LsrControlledJournal(original: StoredClientIntentV1? = LsrGatewayInputs.entry()) :
    LocalClientIntentJournal(LsrGatewayInputs.owner) {
    var entry = original
    var dispatchWrite = JournalWrite.DURABLE
    var stageCalls = 0
    var dispatchCalls = 0
    var resultWrites = 0
    var acknowledgements = 0
    override suspend fun find(operationId: OperationId) = JournalRead.Value(entry?.takeIf { it.operationId == operationId })
    override suspend fun pending() = JournalRead.Value(listOfNotNull(entry))
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCalls++
        error("No new native-completed publication is supplied")
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        dispatchCalls++
        if (dispatchWrite == JournalWrite.DURABLE) {
            val previous = requireNotNull(entry)
            require(previous.operationId == operationId)
            entry = previous.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name)
        }
        return dispatchWrite
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        resultWrites++
        error("No genuine Master result is supplied")
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgements++
        error("No genuine Master result is supplied")
    }
    override suspend fun close() = Unit
}

/** No native authentication/writer. Absence and grants are controlled adapter inputs only. */
internal class LsrControlledEndpoint : LocalMasterClient {
    var viewResult: LocalSessionViewResult = LocalSessionViewResult.Current(LsrGatewayInputs.currentView())
    var lookupReply: (OperationIdentity) -> LookupReply = {
        LookupReply.Deferred(it.operationId, DeferredReason.HISTORY_INCOMPLETE)
    }
    val lookups = mutableListOf<OperationIdentity>()
    val executions = mutableListOf<MutationRequest>()
    var readCalls = 0
    var heldLookup: CompletableDeferred<Unit>? = null
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("No native runtime supplied")
    override suspend fun current(credentials: LocalGatewayCredentials) = viewResult
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        heldLookup?.await()
        return lookupReply(identity)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executions += request
        return MutationReply.Deferred(request.operationId, DeferredReason.HISTORY_INCOMPLETE)
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        readCalls++
        error("No native SKU/time/activation source supplied")
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = error("No native authentication supplied")
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = error("No native refresh supplied")
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = error("No native logout supplied")
}

internal class LsrGatewayFixture(original: StoredClientIntentV1? = LsrGatewayInputs.entry(), installed: Boolean = true) {
    val journal = LsrControlledJournal(original)
    val endpoint = LsrControlledEndpoint()
    var lifetimeCurrent = true
    var credentialAvailable = true
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource {
        if (!credentialAvailable) null else LocalWorkflowCredentialLease(
            LocalGatewayCredentials(LsrGatewayInputs.device, OpaqueCredential(ByteArray(16)))) { lifetimeCurrent }
    }, LocalWorkflowInstallation(if (installed) setOf(LocalSerialRegistrationProfileV1.ACTIVATE) else emptySet(), emptySet()))
}
