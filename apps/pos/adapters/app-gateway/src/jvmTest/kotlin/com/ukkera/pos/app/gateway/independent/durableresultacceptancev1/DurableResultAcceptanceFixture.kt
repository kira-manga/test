package com.ukkera.pos.app.gateway.independent.durableresultacceptancev1

import com.ukkera.pos.app.gateway.LocalEndpointWorkflowGateway
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialSource
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.app.gateway.independent.foodfinancialrpcv1.FoodFinancialEncryptedCustody
import com.ukkera.pos.app.gateway.independent.foodfinancialrpcv1.FoodFinancialMasterPort
import com.ukkera.pos.app.gateway.independent.foodfinancialrpcv1.FoodFinancialObservedJournal
import com.ukkera.pos.app.gateway.independent.foodfinancialrpcv1.FrozenFoodFinancialRpc
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterClientResultAcceptance
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LocalMasterRpcWorkflowProfile
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
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
import com.ukkera.pos.types.OperationId
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Controlled accepting endpoint for the frozen DR-A set. The production gateway supplies the
 * acceptance callback; this port models only the official ordering precondition "return from
 * accept before the matching Receipt may be sent". It is not an official Host, transport,
 * Receipt delivery/eviction, or a source of authority.
 */
internal class ControlledAcceptingMasterPort(
    val base: FoodFinancialMasterPort,
    private val events: MutableList<String>,
) : LocalMasterClientResultAcceptance {
    var acceptanceCalls = 0
        private set
    var receiptPermitted = 0
        private set
    var receiptWithheld = 0
        private set
    val offeredOperations = mutableListOf<OperationId>()
    var beforeAcceptance: (suspend () -> Unit)? = null

    override val health: StateFlow<LocalMasterEndpointHealth> get() = base.health

    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = base.signIn(connectionId, login, password, explicitDeviceMove)
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = base.refresh(credentials)
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = base.current(credentials)
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = base.logout(credentials)
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply = base.read(credentials, request)

    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        // Ordinary path: no application acceptance consumer, no Receipt claim in this harness.
        events += "accept.ordinary"
        return base.execute(credentials, request)
    }

    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        events += "accept.ordinary.lookup"
        return base.lookup(credentials, identity)
    }

    override suspend fun executeAccepting(credentials: LocalGatewayCredentials, request: MutationRequest,
        accept: suspend (MutationReply.Recorded) -> Unit): MutationReply {
        val reply = base.execute(credentials, request)
        return offer(reply, accept)
    }

    override suspend fun lookupAccepting(credentials: LocalGatewayCredentials, identity: OperationIdentity,
        accept: suspend (MutationReply.Recorded) -> Unit): LookupReply {
        val reply = base.lookup(credentials, identity)
        if (reply is LookupReply.Found) offer(reply.result, accept)
        return reply
    }

    private suspend fun offer(reply: MutationReply, accept: suspend (MutationReply.Recorded) -> Unit): MutationReply {
        if (reply !is MutationReply.Recorded) {
            // No decoded Recorded reply is offered to a durable consumer.
            return reply
        }
        beforeAcceptance?.invoke()
        acceptanceCalls++
        offeredOperations += reply.operationId
        events += "accept.enter"
        return try {
            accept(reply)
            receiptPermitted++
            events += "receipt.permit"
            reply
        } catch (failure: Throwable) {
            receiptWithheld++
            events += "receipt.withhold"
            throw failure
        }
    }
}

internal class DurableAcceptanceFixture private constructor(private val custody: FoodFinancialEncryptedCustody) {
    val events = mutableListOf<String>()
    val base = FoodFinancialMasterPort(events)
    val accepting = ControlledAcceptingMasterPort(base, events)
    var journal = FoodFinancialObservedJournal(custody.journal, events)
        private set
    var current = true
    private val credentials = LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(FrozenFoodFinancialRpc.loginSession,
            OpaqueCredential(ByteArray(16)))) { current }
    }
    val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL)

    fun gateway(endpoint: LocalMasterClient = accepting): LocalEndpointWorkflowGateway =
        LocalEndpointWorkflowGateway(endpoint, journal, credentials, installed)

    suspend fun reopen() {
        custody.reopen()
        journal = FoodFinancialObservedJournal(custody.journal, events)
        events += "client.reopened"
    }

    suspend fun find(operationId: OperationId): StoredClientIntentV1 =
        assertNotNull(assertIs<com.ukkera.pos.app.gateway.JournalRead.Value<StoredClientIntentV1?>>(
            journal.find(operationId)).value)

    suspend fun close() = custody.close()

    fun assertNoReceipt() {
        assertEquals(0, accepting.receiptPermitted, "A Receipt was permitted without durable acceptance")
        assertFalse(events.contains("receipt.permit"))
    }

    fun assertDurableBeforeReceipt() {
        val durable = events.indexOf("result.durable")
        val permit = events.indexOf("receipt.permit")
        assertTrue(durable >= 0, "The actual encrypted journal never reported DURABLE")
        assertTrue(permit >= 0, "The accepting transport never permitted the matching Receipt")
        assertTrue(durable < permit, "Receipt was permitted before the actual journal write returned DURABLE")
    }

    companion object {
        suspend fun create() = DurableAcceptanceFixture(FoodFinancialEncryptedCustody.create())
    }
}

internal suspend fun <T> withDurableAcceptanceFixture(block: suspend (DurableAcceptanceFixture) -> T): T {
    val fixture = DurableAcceptanceFixture.create()
    return try { block(fixture) } finally { fixture.close() }
}
