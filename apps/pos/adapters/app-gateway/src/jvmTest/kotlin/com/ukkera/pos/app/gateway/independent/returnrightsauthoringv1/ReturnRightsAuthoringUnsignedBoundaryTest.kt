package com.ukkera.pos.app.gateway.independent.returnrightsauthoringv1

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalField
import com.ukkera.pos.app.LocalScalarInput
import com.ukkera.pos.app.LocalScalarValue
import com.ukkera.pos.app.LocalServiceProblem
import com.ukkera.pos.app.LocalStageResult
import com.ukkera.pos.app.gateway.JournalRead
import com.ukkera.pos.app.gateway.JournalWrite
import com.ukkera.pos.app.gateway.LocalClientIdentity
import com.ukkera.pos.app.gateway.LocalClientIntentJournal
import com.ukkera.pos.app.gateway.LocalEndpointWorkflowGateway
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialLease
import com.ukkera.pos.app.gateway.LocalWorkflowCredentialSource
import com.ukkera.pos.app.gateway.LocalWorkflowInstallation
import com.ukkera.pos.app.gateway.StoredClientIntentV1
import com.ukkera.pos.app.gateway.StoredKernelResultV1
import com.ukkera.pos.catalog.store.LocalReturnRightsActivationProfileV1
import com.ukkera.pos.catalog.store.LocalReturnRightsPublicationCodec
import com.ukkera.pos.catalog.store.toNativeCompanyReturnRightsProposal
import com.ukkera.pos.database.LocalMasterClient
import com.ukkera.pos.database.LocalMasterEndpointHealth
import com.ukkera.pos.database.LookupReply
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.ReadReply
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.ActivatedLocalPolicy
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.CompanyBusinessPublicationKind
import com.ukkera.pos.security.GrantEffect
import com.ukkera.pos.security.LocalAuthenticationResult
import com.ukkera.pos.security.LocalGatewayCredentials
import com.ukkera.pos.security.LocalLoginIdentifier
import com.ukkera.pos.security.LocalSessionPolicy
import com.ukkera.pos.security.LocalSessionView
import com.ukkera.pos.security.LocalSessionViewResult
import com.ukkera.pos.security.LocalSignOutResult
import com.ukkera.pos.security.MonotonicMoment
import com.ukkera.pos.security.OpaqueCredential
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PermissionGrant
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.SessionGeneration
import com.ukkera.pos.security.ThrottlePolicy
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Public unsigned mapping and actual generic refusal only. Native preparation, trust, signing and durable staging are unbound. */
class ReturnRightsAuthoringUnsignedBoundaryTest {
    private val inputs = FrozenAuthoringUnsignedInput

    @Test
    fun raa01UnsignedD0NativeProposalKeepsExactPurposeScopeContentDigestAndDetachedBytes() {
        val expected = inputs.canonicalBytes()
        val caller = expected.copyOf()
        try {
            val definition = LocalReturnRightsPublicationCodec.decodeCanonicalPublication(caller)
            val proposal = definition.toNativeCompanyReturnRightsProposal()
            assertEquals(inputs.scope.company(), proposal.company)
            assertEquals(DomainId(inputs.d0Value.authoringString("publicationId")), proposal.publicationId)
            assertEquals(DomainId(inputs.d0Value.authoringString("policyId")), proposal.entityId)
            assertEquals(CompanyBusinessPublicationKind.RETURN_RIGHTS_V1, proposal.kind)
            assertEquals(inputs.canonicalRules.authoringString("domain"), proposal.kind.domain)
            assertEquals(inputs.d0Metadata.authoringString("domainPrefixedUtf8Sha256"), proposal.contentDigest.value)
            assertEquals(inputs.d0Metadata.getValue("domainPrefixedUtf8Bytes").jsonPrimitive.int, expected.size)
            caller.fill(0)
            val exported = proposal.copyCanonicalContent()
            try {
                assertContentEquals(expected, exported)
                exported.fill(0)
                val fresh = proposal.copyCanonicalContent()
                try { assertContentEquals(expected, fresh) } finally { fresh.fill(0) }
            } finally { exported.fill(0) }
            // This public request supplies neither an owner-bound review nor a signing authority.
        } finally { caller.fill(0); expected.fill(0) }
    }

    @Test
    fun raa17GenericRightsPreparationRefusesEmptyOrUnsignedD0InputWithoutJournalWriteOrMasterDispatch() = runTest {
        val actor = inputs.id("employeeE1")
        val device = inputs.id("deviceD1")
        val scope = inputs.scope
        val boot = DomainId.random()
        // Constructor-only session/clock coordinates and an advisory B1 grant are not real admission or permission evidence.
        val view = LocalSessionView(actor, scope, device, DomainId.random(), SessionGeneration(1),
            LocalLoginIdentifier("synthetic-authoring-boundary"),
            ActivatedLocalPolicy(scope, actor, Revision(inputs.identities.authoringString("permissionPolicyRevision").toLong()),
                listOf(PermissionGrant(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), scope,
                    GrantEffect.ALLOW, targetIds = setOf(scope.branchId))), LocalSessionPolicy(), ThrottlePolicy()),
            MonotonicMoment(boot, 1), MonotonicMoment(boot, 2))
        val master = UnsignedAuthoringRefusalMaster(view)
        val journal = UnsignedAuthoringRefusalJournal(LocalClientIdentity(scope, actor, device))
        var live = true
        val ownedCredentials = mutableListOf<LocalGatewayCredentials>()
        val gateway = LocalEndpointWorkflowGateway(master, journal, LocalWorkflowCredentialSource {
            // Disposable placeholder bearer bytes are never inspected or treated as authenticated credentials.
            val owned = LocalGatewayCredentials(DomainId.random(), OpaqueCredential(ByteArray(16)))
            ownedCredentials += owned
            LocalWorkflowCredentialLease(owned) { live }
        }, LocalWorkflowInstallation(setOf(LocalReturnRightsActivationProfileV1.ACTIVATE), emptySet()))
        try {
            val variants = listOf(emptyList(), listOf(LocalScalarInput(LocalField.NOTES, LocalScalarValue.Text(inputs.d0Json))))
            for (values in variants) {
                val intent = LocalCommandIntent(inputs.operationId, LocalAction.RETURN_RIGHTS_ACTIVATE, 1, null, values)
                assertEquals(LocalServiceProblem.NOT_IMPLEMENTED, assertIs<LocalStageResult.NotStaged>(gateway.stage(intent)).problem)
                assertEquals(0, journal.writes)
                assertEquals(0, master.reads)
                assertEquals(0, master.mutations)
                assertEquals(0, master.lookups)
            }
            // Original/pending journal reads are permitted; no durable journal or business-store outcome is asserted.
        } finally { live = false; ownedCredentials.forEach { it.bearer.destroy() } }
    }
}

private object FrozenAuthoringUnsignedInput {
    private val relative = Paths.get("qa/pos/designs/return-rights-authoring-v1/fixtures.json")
    private val file = generateSequence(Paths.get("").toAbsolutePath()) { it.parent }
        .map { it.resolve(relative) }.firstOrNull { Files.isRegularFile(it) }
        ?: error("The frozen return-rights authoring fixtures are required")
    private val root = Json.parseToJsonElement(Files.readString(file)).jsonObject
    val identities = root.getValue("identities").jsonObject
    val canonicalRules = root.getValue("canonicalRules").jsonObject
    val d0Metadata = root.getValue("definitions").jsonObject.getValue("D0").jsonObject
    val d0Value = d0Metadata.getValue("value").jsonObject
    val d0Json = d0Metadata.authoringString("canonicalJson")
    val scope = identities.getValue("branchB1").jsonObject.let {
        BranchScope(DomainId(it.authoringString("organizationId")), DomainId(it.authoringString("companyId")),
            DomainId(it.authoringString("branchId")))
    }
    val operationId = OperationId(identities.authoringString("activationOperationO0"))
    fun id(name: String) = DomainId(identities.authoringString(name))
    fun canonicalBytes() = (canonicalRules.authoringString("domain") + d0Json).encodeToByteArray()
}

/** Controlled Current response only; all real authentication, protected reads, mutations and receipt lookups refuse. */
private class UnsignedAuthoringRefusalMaster(private val view: LocalSessionView) : LocalMasterClient {
    var reads = 0
        private set
    var mutations = 0
        private set
    var lookups = 0
        private set
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = LocalSessionViewResult.Current(view)
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads++; throw AssertionError("Generic rights refusal must not read Master content")
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        mutations++; throw AssertionError("Generic rights refusal must not dispatch a mutation")
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups++; throw AssertionError("Generic rights refusal must not recover a Master receipt")
    }
    override val health: StateFlow<LocalMasterEndpointHealth> get() = forbidden()
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = forbidden()
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = forbidden()
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = forbidden()
    private fun forbidden(): Nothing = throw AssertionError("Real authentication and transport are unbound")
}

/** Empty original/pending observations only, with every write failing loudly; not an in-memory durable journal. */
private class UnsignedAuthoringRefusalJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    var writes = 0
        private set
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> = JournalRead.Value(null)
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> = JournalRead.Value(emptyList())
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite = forbiddenWrite()
    override suspend fun markDispatched(operationId: OperationId): JournalWrite = forbiddenWrite()
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = forbiddenWrite()
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = forbiddenWrite()
    override suspend fun close() = Unit
    private fun forbiddenWrite(): Nothing { writes++; throw AssertionError("Generic rights refusal must not write the journal") }
}

private fun JsonObject.authoringString(name: String) = getValue(name).jsonPrimitive.content
