package com.ukkera.pos.database.independent.configurationstaleoriginalcleanv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.RevisionSerializer
import com.ukkera.pos.types.UtcInstant
import com.ukkera.pos.types.UtcInstantSerializer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Bounded proposal assertions for 55 unchanged literal rows in CFG-001–CFG-017
 * and CFR-001–CFR-008.
 * The actual codec/handler prepare and evaluate against a controlled BEFORE row
 * and synthetic authorizer. No Master, native history, current persisted grants,
 * committed storage, recovery, RPC or process restart is bound by this test.
 * A successful proposal is inspected directly; it is never applied by a copied
 * commit model. Whole groups, including durable no-effects, remain NOT_RUN.
 */
@RunWith(Parameterized::class)
class ConfigurationStaleOriginalCleanHandlerSlicesTest(private val fixtureId: String) {
    @Test
    fun publicHandlerProposalMatchesFrozenLiteralRow() = runTest {
        val row = FrozenConfigurationStaleInputs.row(fixtureId)
        val command = row.request()
        val handler = LocalConfigurationHandler(UnavailableCompanyPublicationSignatureVerifier)
        val normalized = handler.normalize(LocalConfigurationCodec.encode(command))
        assertEquals(command, LocalConfigurationCodec.decode(normalized), fixtureId)
        assertEquals(CommandKey(row.command.text("name"), row.command["version"]!!.jsonPrimitive.int),
            handler.key, fixtureId)

        val context = CommandContext(row.scope, row.actorId, row.operationId, handler.key,
            semanticDigest(row.scope, row.actorId, handler.key, normalized))
        val before = row.beforeAggregate()
        val view = ControlledBeforeView(row.scope, row.key, before)
        val beforePayload = before?.payload
        val expectedCas = row.value.objectAt("expectedCAS")
        assertEquals(expectedCas.revisionOrNull("requestedStorageRevision"),
            row.command.revisionOrNull("expectedStorageRevision"), fixtureId)
        assertEquals(expectedCas.revisionOrNull("actualStorageRevision"), before?.revision, fixtureId)
        assertEquals(expectedCas["matches"]!!.jsonPrimitive.boolean,
            row.command.revisionOrNull("expectedStorageRevision") == before?.revision, fixtureId)

        val prepared = handler.prepare(normalized, view, context)
        val required = row.expectedRequirement(context.semanticDigest)
        assertEquals(required, prepared.requirement, fixtureId)
        assertEquals(row.expectedResources(), prepared.affectedResources, fixtureId)

        // The frozen baseline supplies valid interactive authority semantically.
        // This synthetic control binds that baseline to evaluate(), not to a Master.
        val authorization = row.syntheticAuthorization(required)
        assertEquals(row.authorization.text("actorId"), authorization.actorId.value, fixtureId)
        assertEquals(required, authorization.requirement, fixtureId)
        val outcome = prepared.evaluate(authorization, FrozenConfigurationStaleInputs.at)
        val result = when (outcome) {
            is ProposedOutcome.Apply -> outcome.result
            is ProposedOutcome.Reject -> outcome.result
        }
        assertEquals(row.expectedResult(), LocalConfigurationCodec.decodeResult(result), fixtureId)

        if (row.expected.text("targetChange") == "NONE") {
            val refusal = assertIs<ProposedOutcome.Reject>(outcome, fixtureId)
            assertEquals(row.expectedResult().code, refusal.code, fixtureId)
            // Reject has no business changes/claims/jobs/deadline proposal. Native
            // mutation custody, approval absence and durable effects remain unbound.
            assertEquals(beforePayload, view.aggregate(row.key)?.payload, fixtureId)
            row.assertTarget(row.expected.objectAt("target"), view.aggregate(row.key), fixtureId)
        } else {
            val applied = assertIs<ProposedOutcome.Apply>(outcome, fixtureId)
            assertEquals(1, applied.changes.size, fixtureId)
            val change = applied.changes.single()
            assertEquals(row.key, change.key, fixtureId)
            assertEquals(expectedCas.revisionOrNull("actualStorageRevision"),
                change.expectedRevision, fixtureId)
            assertEquals(LocalConfigurationProfile.schema(row.key.kind), change.schema, fixtureId)
            row.assertProposedTarget(row.expected.objectAt("target"), change.payload, fixtureId)
            // Resulting storage revision is a kernel/storage obligation. Do not
            // manufacture it by applying the proposal to an in-memory commit model.
            assertEquals(beforePayload, view.aggregate(row.key)?.payload, fixtureId)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun frozenRows(): List<Array<Any>> = FrozenConfigurationStaleInputs.rows.map {
            arrayOf<Any>(it.text("id"))
        }
    }
}

private object FrozenConfigurationStaleInputs {
    val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    // Constructor-only synthetic envelope metadata, not frozen business inputs
    // or an authentic installation/policy/history. The literal branch is retained.
    val organizationId = DomainId("00000000-0000-4000-8000-000000000001")
    val companyId = DomainId("00000000-0000-4000-8000-000000000002")
    val at = UtcInstant.parseCanonical("1970-01-01T00:00:00Z")
    val policyRevision = Revision(1)
    private val documents = listOf(
        "configuration-stale-original-clean-v1",
        "configuration-refusal-complete-v1",
    ).map { version ->
        ConfigurationStaleOriginalCleanHandlerSlicesTest::class.java
            .getResourceAsStream("/$version.json")!!.use {
                json.parseToJsonElement(it.readBytes().decodeToString()).jsonObject
            }.also { check(it.text("designVersion") == version) }
    }
    val rows = documents.flatMap { document ->
        document["publicHandlerFixtures"]!!.jsonArray.map { it.jsonObject }.also {
            check(it.size == document["publicHandlerFixtureCount"]!!.jsonPrimitive.int)
            check(it.map { row -> row.text("group") }.toSet().size ==
                document["publicHandlerGroupCount"]!!.jsonPrimitive.int)
        }
    }.also {
        check(it.map { row -> row.text("id") }.toSet().size == it.size)
    }

    fun row(id: String): FrozenConfigurationStaleRow =
        FrozenConfigurationStaleRow(rows.single { it.text("id") == id })
}

private class FrozenConfigurationStaleRow(val value: JsonObject) {
    val command = value.objectAt("command")
    val authorization = value.objectAt("originalAuthorizationExpectation")
    val expected = value.objectAt("expected")
    private val originalContext = value.objectAt("originalContext")
    private val before = value.objectAt("before")
    private val json get() = FrozenConfigurationStaleInputs.json
    val scope = BranchScope(FrozenConfigurationStaleInputs.organizationId,
        FrozenConfigurationStaleInputs.companyId, DomainId(originalContext.text("branchId")))
    val actorId = DomainId(originalContext.text("actorId"))
    val operationId = OperationId(originalContext.text("operationId"))
    private val targetId = DomainId(command.objectAt("target").text("id"))
    private val bodyTag = when (value.text("type")) {
        "warehouse" -> "WAREHOUSE"
        "tender" -> "TENDER"
        "supplier" -> "SUPPLIER"
        else -> error("Unsupported frozen public target type")
    }
    val key = LocalConfigurationProfile.key(when (value.text("type")) {
        "warehouse" -> LocalConfigurationProfile.WAREHOUSE
        "tender" -> LocalConfigurationProfile.TENDER
        "supplier" -> LocalConfigurationProfile.SUPPLIER
        else -> error("Unsupported frozen public target type")
    }, targetId)

    fun request(): LocalConfigurationRequestV1 {
        val definition = publicDefinition(command.objectAt("target"))
        val cas = command.revisionOrNull("expectedStorageRevision")
        return when (command.text("subtype")) {
            "ConfigureWarehouse" -> LocalConfigurationRequestV1.ConfigureWarehouse(cas,
                json.decodeFromJsonElement(LocalWarehouseV1.serializer(), definition))
            "ConfigureTender" -> LocalConfigurationRequestV1.ConfigureTender(cas,
                json.decodeFromJsonElement(LocalTenderV1.serializer(), definition))
            "ConfigureSupplier" -> LocalConfigurationRequestV1.ConfigureSupplier(cas,
                json.decodeFromJsonElement(LocalSupplierV1.serializer(), definition))
            else -> error("Unsupported frozen command subtype")
        }
    }

    fun beforeAggregate(): StoredAggregate? {
        if (!before["exists"]!!.jsonPrimitive.boolean) return null
        val payload = StoredBytes(buildJsonObject {
            put("scope", json.encodeToJsonElement(BranchScope.serializer(), scope))
            put("kind", key.kind)
            put("id", targetId.value)
            put("revision", before["domainRevision"]!!)
            put("body", buildJsonObject {
                put("type", bodyTag)
                put("value", publicDefinition(before.objectAt("definition")))
            })
            put("authorizedBy", before["authorizedBy"]!!)
            put("policyRevision", json.encodeToJsonElement(RevisionSerializer,
                FrozenConfigurationStaleInputs.policyRevision))
            put("recordedAt", json.encodeToJsonElement(UtcInstantSerializer,
                FrozenConfigurationStaleInputs.at))
        }.toString().encodeToByteArray())
        return StoredAggregate(key, before.revisionOrNull("storageRevision")!!,
            LocalConfigurationProfile.schema(key.kind), payload, sha256(payload.copyBytes()))
    }

    fun expectedRequirement(digest: PayloadDigest): AuthorizationRequirement {
        val permissions = authorization["requiredPermissions"]!!.jsonArray
        check(authorization["monetaryLimit"] == JsonNull &&
            authorization["baseLimit"] == JsonNull && authorization["ownerLimit"] == JsonNull)
        check(authorization.text("exactSemanticPayloadSource") == "command")
        return AuthorizationRequirement(Permission(SecurityAction.valueOf(permissions[1].jsonPrimitive.content),
            PermissionVerb.valueOf(permissions[0].jsonPrimitive.content)),
            BranchScope(scope.organizationId, scope.companyId, DomainId(authorization.text("branchId"))),
            ActionBinding(DomainId(authorization.text("targetId")),
                authorization.revisionOrNull("previousDomainRevision")!!, digest,
                OperationId(authorization.text("operationId")), authorization.text("reason")),
            LimitUse.NotApplicable, OwnerConstraint.Unrestricted)
    }

    fun expectedResources(): Set<RecoveryResourceKey> = expected["resultResources"]!!.jsonArray.map {
        val parts = it.jsonPrimitive.content.split(':', limit = 2)
        RecoveryResourceKey(RecoveryResourceKind.valueOf(parts[0]), DomainId(parts[1]))
    }.toSet()

    fun expectedResult(): LocalConfigurationResultV1 {
        val result = expected.objectAt("result")
        return LocalConfigurationResultV1(result.text("code"), result.idOrNull("id"),
            result.revisionOrNull("revision"))
    }

    suspend fun syntheticAuthorization(required: AuthorizationRequirement): AuthorizedAction {
        val sessionId = DomainId(originalContext.text("sessionId"))
        val deviceId = DomainId(originalContext.text("deviceId"))
        val generation = SessionGeneration(1)
        val admission = Revision(1)
        val principal = TrustedPrincipalFactory(object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity =
                VerifiedLocalIdentity(actorId, scope, deviceId, sessionId, generation, admission,
                    AuthenticationPurpose.INTERACTIVE)
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }).authenticateLocal(LocalGatewayCredentials(sessionId, OpaqueCredential(ByteArray(32))))!!
        val snapshot = AuthorizationSnapshot(actorId, PrincipalKind.LOCAL_EMPLOYEE, scope, sessionId,
            generation, deviceId, admission, FrozenConfigurationStaleInputs.policyRevision,
            true, true, true, AuthenticationPurpose.INTERACTIVE,
            listOf(PermissionGrant(required.permission, scope, GrantEffect.ALLOW)))
        return assertIs<AuthorizationDecision.Allowed>(
            AuthorizationEvaluator.evaluate(principal, snapshot, required)).authorization
    }

    fun assertTarget(expectedTarget: JsonObject, actual: StoredAggregate?, label: String) {
        assertEquals(expectedTarget["exists"]!!.jsonPrimitive.boolean, actual != null, label)
        assertEquals(expectedTarget.revisionOrNull("storageRevision"), actual?.revision, label)
        if (actual == null) {
            assertNull(expectedTarget.revisionOrNull("domainRevision"), label)
            assertEquals(JsonNull, expectedTarget["definition"], label)
        } else {
            assertProposedTarget(expectedTarget, actual.payload, label)
        }
    }

    fun assertProposedTarget(expectedTarget: JsonObject, payload: StoredBytes, label: String) {
        val record = json.parseToJsonElement(payload.copyBytes().decodeToString()).jsonObject
        assertEquals(scope, json.decodeFromJsonElement(BranchScope.serializer(), record["scope"]!!), label)
        assertEquals(key.kind, record.text("kind"), label)
        assertEquals(targetId.value, record.text("id"), label)
        assertEquals(expectedTarget.revisionOrNull("domainRevision"), record.revisionOrNull("revision"), label)
        assertEquals(bodyTag, record.objectAt("body").text("type"), label)
        assertEquals(publicDefinition(expectedTarget.objectAt("definition")),
            record.objectAt("body").objectAt("value"), label)
    }
}

private class ControlledBeforeView(
    override val scope: BranchScope,
    private val target: AggregateKey,
    private val before: StoredAggregate?,
) : CommandReadView {
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? {
        check(key == target) { "Only the assigned controlled BEFORE target is bound" }
        return before
    }
    override suspend fun claim(key: ClaimKey): DomainId? =
        throw UnsupportedOperationException("No claim source is bound")
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
        throw UnsupportedOperationException("No authentic original is bound")
}

private fun JsonObject.objectAt(key: String): JsonObject = getValue(key).jsonObject
private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
private fun JsonObject.revisionOrNull(key: String): Revision? =
    getValue(key).takeUnless { it == JsonNull }?.jsonPrimitive?.content?.toLong()?.let(::Revision)
private fun JsonObject.idOrNull(key: String): DomainId? =
    getValue(key).takeUnless { it == JsonNull }?.jsonPrimitive?.content?.let(::DomainId)

/** Exact semantic names-key binding; every other literal target field is retained. */
private fun publicDefinition(value: JsonObject): JsonObject = JsonObject(value.mapValues { (key, element) ->
    if (key != "names") element else buildJsonObject {
        put("english", element.jsonObject.getValue("en"))
        put("arabic", element.jsonObject.getValue("ar"))
    }
})
