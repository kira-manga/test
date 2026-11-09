package com.ukkera.pos.app.gateway.independent.foodfinancialrpcv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRelationalQueriesV2
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** Explicit schedule boundary before the real journal write, never a native/SDK barrier. */
internal class FoodFinancialWriteBarrier {
    val entered = CompletableDeferred<Unit>()
    private val released = CompletableDeferred<Unit>()
    suspend fun awaitRelease() { entered.complete(Unit); released.await() }
    fun release() { released.complete(Unit) }
}

/**
 * Real production JVM AES-GCM, private file locking, atomic replace and fsync.
 * The JCA key and current-custody callback are synthetic controls. They do not
 * establish hardware keys, native cash capacity, rollback fencing or RPC admission.
 */
internal class FoodFinancialEncryptedCustody private constructor(private val directory: Path,
    private val key: SecretKey) {
    private val journalId = DomainId.random()
    private val keyId = DomainId.random()
    private var lifetime = AtomicBoolean(true)
    private var view = newView()
    private val custody = JvmClientJournalCustody { view.takeIf { it.isCurrent() } }
    private var opened: JvmEncryptedClientIntentJournal? = null
    val journal get() = checkNotNull(opened)
    private fun newView() = JvmClientJournalCustodyView(directory, FrozenFoodFinancialRpc.owner,
        journalId, keyId, DomainId.random(), key, lifetime::get)

    suspend fun reopen() {
        retire()
        lifetime = AtomicBoolean(true)
        view = newView()
        opened = assertIs<JvmClientJournalOpenResult.Opened>(JvmEncryptedClientIntentJournal.openExisting(custody),
            "Reopen must load the original encrypted manifest; an empty substitute is not permitted").journal
    }

    suspend fun retire() {
        try { opened?.close() } finally { opened = null; lifetime.set(false) }
    }

    suspend fun close() = withContext(NonCancellable) {
        try { retire() } finally {
            withContext(Dispatchers.IO) {
                // Only this fixture's newly allocated private directory is removed.
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    companion object {
        suspend fun create(): FoodFinancialEncryptedCustody {
            val directory = Files.createTempDirectory("food-financial-client-synthetic-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            val fixture = FoodFinancialEncryptedCustody(directory,
                KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
            try {
                fixture.opened = assertIs<JvmClientJournalOpenResult.Opened>(
                    JvmEncryptedClientIntentJournal.createNew(fixture.custody),
                    "Supported private POSIX storage and AES-GCM are prerequisites").journal
                return fixture
            } catch (failure: Throwable) { fixture.close(); throw failure }
        }
    }
}

/** Controlled barriers/failures surround production writes; DURABLE is returned only by the real delegate. */
internal class FoodFinancialObservedJournal(private val delegate: LocalClientIntentJournal,
    private val events: MutableList<String>) : LocalClientIntentJournal(delegate.owner) {
    var stageBarrier: FoodFinancialWriteBarrier? = null
    var rememberBarrier: FoodFinancialWriteBarrier? = null
    var failStage = false
    var failRemember = false
    var stages = 0
    var dispatches = 0
    var remembrances = 0
    var acknowledgements = 0
    override suspend fun find(operationId: OperationId) = delegate.find(operationId)
    override suspend fun pending() = delegate.pending()

    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stages++
        events += "stage.enter"
        stageBarrier?.awaitRelease()
        if (failStage) return JournalWrite.UNAVAILABLE
        val result = delegate.stage(intent)
        if (result == JournalWrite.DURABLE) events += "stage.durable"
        return result
    }

    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        dispatches++
        val result = delegate.markDispatched(operationId)
        if (result == JournalWrite.DURABLE) events += "dispatch.durable"
        return result
    }

    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        remembrances++
        events += "result.enter"
        rememberBarrier?.awaitRelease()
        if (failRemember) return JournalWrite.UNAVAILABLE
        val saved = delegate.remember(operationId, result)
        if (saved == JournalWrite.DURABLE) events += "result.durable"
        return saved
    }

    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        acknowledgements++
        events += "ack.enter"
        val saved = delegate.acknowledge(operationId, result)
        if (saved == JournalWrite.DURABLE) events += "ack.durable"
        return saved
    }

    override suspend fun close() = delegate.close()
}

/**
 * Current view, original DATA and terminal/status replies are controlled inputs.
 * No accepting authenticator, native source provider, writer ledger, transport
 * receipt or Master application ACK is installed by this port.
 */
internal class FoodFinancialMasterPort(private val events: MutableList<String>) : LocalMasterClient {
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    var view = FrozenFoodFinancialRpc.view()
    var currentReply: () -> LocalSessionViewResult = { LocalSessionViewResult.Current(view) }
    val reads = mutableListOf<ReadRequest>()
    val mutations = mutableListOf<MutationRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    var normalizedStock = true
    var originalData: LocalFoodPaymentReadResult = FrozenFoodFinancialRpc.original
    var readReply: suspend (ReadRequest) -> ReadReply = { request ->
        val input = FrozenFoodFinancialRpc
        when (request.query) {
            LocalFoodCheckKitchenCodecV1.QUERY, LocalFoodCheckKitchenCodecV2.QUERY,
            LocalFoodCheckKitchenCodecV3.QUERY -> input.data(input.checkPayload(request.query))
            LocalStockRelationalQueriesV2.PROFILE -> input.data(input.bytes("{\"version\":2,\"normalized\":$normalizedStock}"))
            LocalFoodFinancialProfileV1.QUERY -> input.data(input.bytes(input.json.encodeToString(
                LocalFoodFinancialReadResultV1.serializer(), LocalFoodFinancialReadResultV1.Collection(input.coveredCollection()))))
            LocalFoodPaymentReadCodec.QUERY -> input.data(input.bytes(input.json.encodeToString(
                LocalFoodPaymentReadResult.serializer(), originalData)))
            LocalFoodProfileV1.QUERY -> input.data(input.bytes(input.json.encodeToString(
                LocalFoodReadResultV1.serializer(), LocalFoodReadResultV1())))
            else -> error("Unexpected read outside the frozen financial binding")
        }
    }
    var lookupReply: suspend (OperationIdentity) -> LookupReply = { LookupReply.AbsentAtCurrentWriter(it.operationId) }
    var executeReply: suspend (MutationRequest) -> MutationReply = {
        MutationReply.Deferred(it.operationId, DeferredReason.WRITER_NOT_QUALIFIED)
    }

    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = currentReply()
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        return readReply(request)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        events += "lookup.enter"
        lookups += identity
        return lookupReply(identity)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        events += "execute.enter"
        mutations += request
        return executeReply(request)
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean) = LocalAuthenticationResult.Unavailable
    override suspend fun refresh(credentials: LocalGatewayCredentials) = LocalAuthenticationResult.Unavailable
    override suspend fun logout(credentials: LocalGatewayCredentials) = LocalSignOutResult.Unavailable
}

internal class FoodFinancialGatewayFixture private constructor(private val custody: FoodFinancialEncryptedCustody) {
    val events = mutableListOf<String>()
    val master = FoodFinancialMasterPort(events)
    var journal = FoodFinancialObservedJournal(custody.journal, events)
        private set
    var current = true
    private val credentials = LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(FrozenFoodFinancialRpc.loginSession,
            OpaqueCredential(ByteArray(16)))) { current }
    }
    val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.FOOD_CASH_FINANCIAL)
    fun gateway(profile: LocalWorkflowInstallation = installed) =
        LocalEndpointWorkflowGateway(master, journal, credentials, profile)

    suspend fun reopen() {
        custody.reopen()
        journal = FoodFinancialObservedJournal(custody.journal, events)
        events += "client.reopened"
    }
    suspend fun find(operationId: OperationId): StoredClientIntentV1 =
        assertNotNull(assertIs<JournalRead.Value<StoredClientIntentV1?>>(journal.find(operationId)).value)
    suspend fun close() = custody.close()

    suspend fun <T> readAccess(installed: LocalWorkflowInstallation, block: suspend (GatewayAccess) -> T): T {
        val lease = assertNotNull(credentials.acquire())
        return try { block(GatewayAccess(master, lease, master.view, installed)) } finally { lease.destroy() }
    }

    companion object {
        suspend fun create() = FoodFinancialGatewayFixture(FoodFinancialEncryptedCustody.create())
    }
}

internal suspend fun <T> withFoodFinancialGateway(block: suspend (FoodFinancialGatewayFixture) -> T): T {
    val fixture = FoodFinancialGatewayFixture.create()
    return try { block(fixture) } finally { fixture.close() }
}
