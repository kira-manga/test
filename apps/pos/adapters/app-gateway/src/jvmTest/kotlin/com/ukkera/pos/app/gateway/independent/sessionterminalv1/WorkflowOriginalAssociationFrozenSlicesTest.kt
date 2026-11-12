package com.ukkera.pos.app.gateway.independent.sessionterminalv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.ControlledSessionClient
import com.ukkera.pos.app.gateway.independent.sessionpresentationv1.SessionGatewayFixture
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/** Implementer wiring of the frozen MRSR-01 unchanged/changed identity observables only.
 * Actual branch/workflow adapters and encrypted JVM journals; controlled Master replies.
 * No native admission, genuine employee issuance, SDK lifetime or business-success claim. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkflowOriginalAssociationFrozenSlicesTest {
    @Test fun mrsr01_changedSessionNeverSubstitutesASuccessor() = mismatch("session")
    @Test fun mrsr01_changedGenerationNeverSubstitutesASuccessor() = mismatch("generation")
    @Test fun mrsr01_changedEmployeeNeverSubstitutesASuccessor() = mismatch("actor")
    @Test fun mrsr01_changedDeviceNeverSubstitutesASuccessor() = mismatch("device")
    @Test fun mrsr01_changedBranchNeverSubstitutesASuccessor() = mismatch("branch")

    @Test fun mrsr01_repeatedExactIdentityIsNotAResourceGrant() = runTest {
        val frozen = AssociationFrozenInput.load()
        val sample = AssociationReplySample(frozen)
        val fixture = SessionGatewayFixture.create(StandardTestDispatcher(testScheduler),
            identitySample = frozen.identity(), initialSessionSample = frozen.originalSession(),
            administrationSample = { AssociationReplyClient(it, sample) })
        try {
            val session = fixture.signIn()
            val retained = fixture.observeJournals()
            repeat(2) {
                sample.arm("unchanged")
                fixture.client.refuseNextRead(ReadFailure.AUTHORIZATION_DENIED)
                assertEquals(LocalServiceProblem.DENIED,
                    assertIs<LocalReadResult.Unavailable>(session.workflows.read(query(fixture))).problem, frozen.message)
                assertTrue(session.isCurrent(), frozen.message)
            }
            assertEquals(2, fixture.client.protectedCalls.size, frozen.message)
            assertEquals(retained, fixture.observeJournals(), frozen.message)
        } finally { fixture.close() }
    }

    private fun mismatch(variant: String) = runTest {
        val frozen = AssociationFrozenInput.load()
        val sample = AssociationReplySample(frozen)
        val fixture = SessionGatewayFixture.create(StandardTestDispatcher(testScheduler),
            identitySample = frozen.identity(), initialSessionSample = frozen.originalSession(),
            administrationSample = { AssociationReplyClient(it, sample) })
        try {
            val session = fixture.signIn()
            val adapter = session.workflows
            val retained = fixture.observeJournals()
            sample.arm(variant)
            assertEquals(LocalServiceProblem.SESSION_ENDED,
                assertIs<LocalReadResult.Unavailable>(adapter.read(query(fixture))).problem, frozen.message)
            assertFalse(session.isCurrent(), frozen.message)
            assertEquals(LocalSessionLifecycle.REAUTHENTICATION_REQUIRED, session.lifecycle?.value, frozen.message)
            repeat(2) { adapter.read(query(fixture)) }
            assertTrue(fixture.client.protectedCalls.isEmpty(), frozen.message)
            assertEquals(retained, fixture.observeJournals(), frozen.message)
            fixture.retireAndReopenJournals()
            assertEquals(retained, fixture.observeJournals(), frozen.message)
        } finally { fixture.close() }
    }

    private fun query(fixture: SessionGatewayFixture) = LocalWorkflowQuery(LocalWorkflow.INVENTORY,
        reference = LocalRecordReference(LocalRecordKind.SKU, fixture.skuSample))
}

private class AssociationFrozenInput(private val original: JsonObject, private val successor: JsonObject) {
    val message = "MRSR-01 steps 1/2 and changed-original subset of step 4; client-lease supporting slice"
    private fun id(symbol: String) = DomainId(UUID.nameUUIDFromBytes(symbol.toByteArray(Charsets.UTF_8)).toString())
    private fun symbol(name: String) = original.getValue(name).jsonPrimitive.content
    fun identity() = LocalClientIdentity(BranchScope(id(symbol("organization")), id(symbol("company")),
        id(symbol("branch"))), id(symbol("actor")), id(symbol("device")))
    fun originalSession() = id(symbol("session"))
    fun changed(view: LocalSessionView, variant: String): LocalSessionView = LocalSessionView(
        if (variant == "actor") id(symbol("other_actor")) else view.actorId,
        if (variant == "branch") view.scope.copy(branchId = id(symbol("other_branch"))) else view.scope,
        if (variant == "device") id(symbol("other_device")) else view.deviceId,
        if (variant == "session") id(successor.getValue("fresh_same_actor_session").jsonPrimitive.content) else view.sessionId,
        if (variant == "generation") view.generation.next() else view.generation,
        view.login, view.policy, view.accessExpiresAt, view.workExpiresAt)

    companion object {
        fun load(): AssociationFrozenInput {
            val relative = "qa/pos/designs/master-rpc-session-revocation"
            val directory = generateSequence(Paths.get("").toAbsolutePath().normalize()) { it.parent }
                .map { it.resolve(relative) }.first { Files.isDirectory(it) }
            val freeze = Json.parseToJsonElement(Files.readString(directory.resolve("freeze-v1.json"))).jsonObject
            check(freeze.getValue("state").jsonPrimitive.content == "FROZEN")
            freeze.getValue("files").jsonArray.forEach { entry ->
                val file = entry.jsonObject
                val bytes = Files.readAllBytes(directory.resolve(file.getValue("path").jsonPrimitive.content))
                val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                check(hash == file.getValue("sha256").jsonPrimitive.content) { "Frozen MRSR packet changed" }
            }
            val cases = Files.readString(directory.resolve("cases-v1.md"))
            check(cases.contains("## MRSR-01 —"))
            val fixtures = Json.parseToJsonElement(Files.readString(directory.resolve("fixtures-v1.json")))
                .jsonObject.getValue("fixtures").jsonObject
            return AssociationFrozenInput(fixtures.getValue("F-ORIGINAL").jsonObject,
                fixtures.getValue("F-SUCCESSOR").jsonObject)
        }
    }
}

private class AssociationReplySample(private val frozen: AssociationFrozenInput) {
    private var passes: Int? = null
    private var variant: String? = null
    fun arm(value: String) { passes = 1; variant = value }
    fun transform(reply: LocalSessionViewResult): LocalSessionViewResult {
        val remaining = passes ?: return reply
        if (remaining > 0) { passes = remaining - 1; return reply }
        passes = null
        val current = checkNotNull(reply as? LocalSessionViewResult.Current)
        return LocalSessionViewResult.Current(frozen.changed(current.view, checkNotNull(variant)))
    }
}

private class AssociationReplyClient(private val delegate: ControlledSessionClient,
    private val sample: AssociationReplySample) : LocalMasterClient by delegate, LocalMasterAdministrationClient {
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult =
        sample.transform(delegate.current(credentials))
    override suspend fun readAdministration(operator: LocalGatewayCredentials, query: LocalAdministrationQuery): LocalAdministrationReadResult =
        error("No administrative read in this supporting slice")
    override suspend fun administer(operator: LocalGatewayCredentials, command: LocalAdministrationCommand,
        newPassword: CharArray?): LocalCredentialAdministrationResult = error("No administrative mutation in this supporting slice")
    override suspend fun lookupAdministration(operator: LocalGatewayCredentials, command: LocalAdministrationCommand): LocalAdministrationStatus =
        error("No administrative lookup in this supporting slice")
    override suspend fun cancelAdministration(operator: LocalGatewayCredentials, command: LocalAdministrationCommand): LocalAdministrationStatus =
        error("No administrative cancellation in this supporting slice")
}
