package com.ukkera.pos.app.gateway.independent.sessionterminalv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.ControlledPolicyInput
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.ControlledSessionClient
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.RetainedSessionObservation
import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockStoreProfile
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.test.*

/** Wires only the unchanged STC-S18 exact-retention and STC-S10/MRSR-01 refusal observables.
 * Prepared Begin/Start rows are inert DATA in the real ordinary encrypted client journal.
 * No physical observation, protected financial capacity, committed payment or recovery is supplied.
 * This is supporting storage evidence, not complete qualification of any assigned frozen case. */
@OptIn(ExperimentalCoroutinesApi::class)
class RetainedPreparedOriginalFrozenSlicesTest {
    @Test fun stcS10S18_terminalDenialPreservesPreparedOriginals() = observe("denied", true)
    @Test fun mrsr01StcS18_changedSessionPreservesPreparedOriginals() = observe("session", true)
    @Test fun mrsr01StcS18_changedGenerationPreservesPreparedOriginals() = observe("generation", true)
    @Test fun mrsr01StcS18_changedEmployeePreservesPreparedOriginals() = observe("actor", true)
    @Test fun mrsr01StcS18_changedDevicePreservesPreparedOriginals() = observe("device", true)
    @Test fun mrsr01StcS18_changedBranchPreservesPreparedOriginals() = observe("branch", true)
    @Test fun stcS10S18_busyObservationPreservesPreparedOriginals() = observe("busy", false)
    @Test fun stcS10S18_unavailableObservationPreservesPreparedOriginals() = observe("unavailable", false)

    private fun observe(variant: String, terminal: Boolean) = runTest {
        val input = RetainedOriginalInputs.load()
        val sample = RetainedOriginalReply(input)
        val fixture = RetainedOriginalJournalFixture.create(input, sample, StandardTestDispatcher(testScheduler))
        try {
            val signedIn = fixture.gateway.signIn("ACTOR-A", charArrayOf('s', 'a', 'm', 'p', 'l', 'e'))
            val session = assertIs<SignInResult.Authenticated<BranchUiSession>>(signedIn).session
            val before = fixture.snapshot()
            assertEquals(input.entries, before.pending, input.message)
            assertTrue(before.pending.all { it.recorded == null && !it.acknowledged &&
                it.localPhase == LocalPendingPhase.STAGED }, input.message)
            val adapter = session.workflows
            sample.arm(variant)
            val expectedProblem = when (variant) {
                "busy" -> LocalServiceProblem.RETRY_LATER
                "unavailable" -> LocalServiceProblem.UNAVAILABLE
                else -> LocalServiceProblem.SESSION_ENDED
            }
            val query = LocalWorkflowQuery(LocalWorkflow.INVENTORY,
                reference = LocalRecordReference(LocalRecordKind.SKU, input.product))
            assertEquals(expectedProblem, assertIs<LocalReadResult.Unavailable>(adapter.read(query)).problem, input.message)
            assertEquals(!terminal, session.isCurrent(), input.message)
            assertEquals(if (terminal) LocalSessionLifecycle.REAUTHENTICATION_REQUIRED else LocalSessionLifecycle.ACTIVE,
                session.lifecycle?.value, input.message)
            if (terminal) repeat(2) {
                assertEquals(LocalServiceProblem.SESSION_ENDED,
                    assertIs<LocalReadResult.Unavailable>(adapter.read(query)).problem, input.message)
            }
            assertTrue(fixture.client.protectedCalls.isEmpty(), input.message)
            assertEquals(before, fixture.snapshot(), input.message)
            fixture.closeAndReopen()
            assertEquals(before, fixture.snapshot(), input.message)
            assertTrue(fixture.client.protectedCalls.isEmpty(), input.message)
        } finally { fixture.close() }
    }
}

private data class PreparedOriginalSnapshot(val credentialRevision: Long,
    val sessions: List<RetainedSessionObservation>, val pending: List<StoredClientIntentV1>)

/** Separate fixture: earlier frozen harnesses and their empty-journal observations stay intact. */
private class RetainedOriginalJournalFixture private constructor(
    val client: ControlledSessionClient,
    val gateway: LocalEndpointBranchGateway,
    private val authCustody: JvmClientCredentialCustody,
    private val intentCustody: JvmClientJournalCustody,
    private var auth: JvmEncryptedClientCredentialJournal,
    private var intents: JvmEncryptedClientIntentJournal,
    private val live: AtomicBoolean,
) {
    suspend fun snapshot(): PreparedOriginalSnapshot {
        val state = when (val read = auth.read()) {
            is JournalRead.Value -> read.value
            JournalRead.Unavailable -> error("Encrypted credential read is unavailable")
        }
        val credentials = try {
            state.validate(auth.owner)
            state.revision to state.sessions.map {
                RetainedSessionObservation(it.localId, it.identity, it.sessionId, it.phase, it.originalRevoked)
            }
        } finally { state.destroy() }
        val pending = when (val read = intents.pending()) {
            is JournalRead.Value -> read.value.sortedBy { it.operationId.value }
            JournalRead.Unavailable -> error("Encrypted prepared-original read is unavailable")
        }
        pending.forEach { it.validate(intents.owner) }
        return PreparedOriginalSnapshot(credentials.first, credentials.second, pending)
    }

    suspend fun closeAndReopen() {
        gateway.closeAndAwait()
        auth.close()
        intents.close()
        auth = (JvmEncryptedClientCredentialJournal.openExisting(authCustody)
            as? JvmClientCredentialJournalOpenResult.Opened)?.journal ?: error("Credential reopen failed")
        intents = (JvmEncryptedClientIntentJournal.openExisting(intentCustody)
            as? JvmClientJournalOpenResult.Opened)?.journal ?: error("Prepared-original reopen failed")
    }

    suspend fun close() = withContext(NonCancellable) {
        try { gateway.closeAndAwait() }
        finally {
            try { auth.close() }
            finally { try { intents.close() } finally { live.set(false); client.close() } }
        }
    }

    companion object {
        suspend fun create(input: RetainedOriginalInputs, sample: RetainedOriginalReply,
            dispatcher: CoroutineDispatcher): RetainedOriginalJournalFixture {
            val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
            val root = Files.createTempDirectory("ukkera-retained-original-", permissions).toRealPath()
            check(Files.getFileStore(root).supportsFileAttributeView("posix"))
            val authDirectory = Files.createDirectory(root.resolve("credentials"), permissions)
            val intentDirectory = Files.createDirectory(root.resolve("intents"), permissions)
            val live = AtomicBoolean(true)
            val keys = KeyGenerator.getInstance("AES").apply { init(256) }
            val authView = JvmClientCredentialCustodyView(authDirectory,
                LocalClientDeviceIdentity(input.owner.scope, input.owner.deviceId), DomainId.random(),
                DomainId.random(), DomainId.random(), keys.generateKey(), live::get)
            val intentView = JvmClientJournalCustodyView(intentDirectory, input.owner, DomainId.random(),
                DomainId.random(), DomainId.random(), keys.generateKey(), live::get)
            val authCustody = JvmClientCredentialCustody { authView.takeIf { live.get() } }
            val intentCustody = JvmClientJournalCustody { intentView.takeIf { live.get() } }
            val auth = (JvmEncryptedClientCredentialJournal.createNew(authCustody)
                as? JvmClientCredentialJournalOpenResult.Opened)?.journal ?: error("Credential creation failed")
            var intents: JvmEncryptedClientIntentJournal? = null
            try {
                val journal = (JvmEncryptedClientIntentJournal.createNew(intentCustody)
                    as? JvmClientJournalOpenResult.Opened)?.journal ?: error("Prepared-original creation failed")
                intents = journal
                input.entries.forEach { check(journal.stage(it) == JournalWrite.DURABLE) }
                val client = ControlledSessionClient(input.owner, ControlledPolicyInput.quantityOnly(Revision(0)), input.originalSession)
                val endpoint = object : LocalMasterClient by client {
                    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
                        sample.transform(client.current(credentials))
                }
                val peer = LocalAuthenticatedPeer(client.connectionSample, input.owner.scope, input.owner.deviceId,
                    "controlled-retained-original-sample", null, live::get)
                val connection = LocalMasterClientConnection(endpoint, peer, DomainId.random(),
                    LocalWorkflowInstallation(emptySet(), setOf(LocalStockStoreProfile.QUANTITIES)),
                    LocalBranchDisplayLabels(input.owner.scope, "COMPANY-1 sample", "BRANCH-A sample"), null, null, live::get)
                val gateway = LocalEndpointBranchGateway(MutableStateFlow(connection), auth,
                    LocalClientIntentJournalSource { requested -> journal.takeIf { it.owner == requested } }, dispatcher)
                return RetainedOriginalJournalFixture(client, gateway, authCustody, intentCustody, auth, journal, live)
            } catch (failure: Throwable) {
                withContext(NonCancellable) { try { auth.close() } finally { intents?.close(); live.set(false) } }
                throw failure
            }
        }
    }
}

private class RetainedOriginalReply(private val input: RetainedOriginalInputs) {
    private var passes: Int? = null
    private var variant: String? = null
    fun arm(value: String) { check(passes == null); passes = 1; variant = value }
    fun transform(reply: LocalSessionViewResult): LocalSessionViewResult {
        val remaining = passes ?: return reply
        if (remaining > 0) { passes = remaining - 1; return reply }
        passes = null
        return when (val selected = checkNotNull(variant)) {
            "denied" -> LocalSessionViewResult.Denied
            "busy" -> LocalSessionViewResult.Busy
            "unavailable" -> LocalSessionViewResult.Unavailable
            else -> LocalSessionViewResult.Current(input.changed(assertIs<LocalSessionViewResult.Current>(reply).view, selected))
        }
    }
}

private class RetainedOriginalInputs(private val fixtures: JsonObject) {
    val message = "STC-S18 prepared-request exact-retention subset; STC-S10/MRSR-01 client refusal subset"
    private fun id(name: String) = DomainId(fixtures.getValue("identities").jsonObject.getValue(name).jsonPrimitive.content)
    private val retail = fixtures.getValue("F-STC-RETAIL").jsonObject
    private fun retailId(name: String) = DomainId(retail.getValue(name).jsonPrimitive.content)
    // Format-required selectors/revisions are inert harness scaffolding, not frozen custody facts.
    private fun scaffolding(name: String) = DomainId(UUID.nameUUIDFromBytes(
        ("STC-S18-prepared-journal-only:" + name).toByteArray(Charsets.UTF_8)).toString())
    val owner = LocalClientIdentity(BranchScope(id("organization_1"), id("retail_company"), id("retail_branch")),
        id("account_a"), id("shared_client_device"))
    val originalSession = id("session_a_original")
    val product = retailId("product")
    val entries: List<StoredClientIntentV1> by lazy {
        val items = listOf(LocalSalesStockRevisionV1(product, Revision(1)))
        val collection = scaffolding("collection")
        val begin = MutationRequest(OperationId(scaffolding("begin-operation").value),
            LocalSalesStoreProfile.COLLECTION_COMMAND, LocalCollectionCommandCodec.encode(LocalCollectionRequestV1.Begin(
                retailId("order"), Revision(0), id("cash_session_a"), Revision(0), collection,
                scaffolding("reservation"), items)))
        val original = MutationRequest(OperationId(retailId("operation").value), LocalFinancialJournalProfileV3.COLLECTION_COMMAND,
            LocalCollectionCommandCodecV3.encode(LocalCollectionRequestV3.Start(collection, Revision(0),
                id("cash_session_a"), Revision(0), Revision(0), items, retailId("collection_instruction"),
                LocalTenderSelectionV1.External(scaffolding("external-card-tender"), Revision(0)),
                Money(Currency.EGP, retail.getValue("input").jsonObject.getValue("payable_minor").jsonPrimitive.long),
                recordingOperationId = OperationId(scaffolding("recording-operation").value),
                resolutionOperationId = OperationId(scaffolding("resolution-operation").value),
                masterAllocationId = scaffolding("master-allocation"), clientAllocationId = scaffolding("client-allocation"))))
        listOf(begin, original).map { physicalIntent(owner, it,
            LocalText("Synthetic prepared original", "طلب أصلي اصطناعي محضر"), listOf(LocalRecordValue(
                LocalText("Frozen order", "طلب الاختبار المجمد"), LocalText(retailId("order").value, retailId("order").value)))) }.sortedBy { it.operationId.value }
    }

    fun changed(view: LocalSessionView, variant: String) = LocalSessionView(
        if (variant == "actor") id("account_b") else view.actorId,
        if (variant == "branch") view.scope.copy(branchId = id("other_retail_branch")) else view.scope,
        if (variant == "device") id("second_client_device") else view.deviceId,
        if (variant == "session") id("session_b") else view.sessionId,
        if (variant == "generation") view.generation.next() else view.generation,
        view.login, view.policy, view.accessExpiresAt, view.workExpiresAt)

    companion object {
        fun load(): RetainedOriginalInputs {
            val root = generateSequence(Paths.get("").toAbsolutePath().normalize()) { it.parent }
                .first { Files.isDirectory(it.resolve("qa/pos/freezes/session-terminal-cleanup-v1")) }
            val directory = root.resolve("qa/pos/freezes/session-terminal-cleanup-v1")
            val frozen = FrozenTerminalCleanupV1.load()
            frozen.case("STC-S10"); frozen.case("STC-S18")
            val mr = root.resolve("qa/pos/designs/master-rpc-session-revocation")
            val manifest = Json.parseToJsonElement(Files.readString(mr.resolve("freeze-v1.json"))).jsonObject
            check(manifest.getValue("state").jsonPrimitive.content == "FROZEN")
            manifest.getValue("files").jsonArray.forEach { item ->
                val entry = item.jsonObject
                val actual = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(
                    mr.resolve(entry.getValue("path").jsonPrimitive.content))).joinToString("") { "%02x".format(it) }
                check(actual == entry.getValue("sha256").jsonPrimitive.content)
            }
            check(Files.readString(mr.resolve("cases-v1.md")).contains("## MRSR-01 —"))
            return RetainedOriginalInputs(Json.parseToJsonElement(Files.readString(directory.resolve("fixtures.json"))).jsonObject)
        }
    }
}
