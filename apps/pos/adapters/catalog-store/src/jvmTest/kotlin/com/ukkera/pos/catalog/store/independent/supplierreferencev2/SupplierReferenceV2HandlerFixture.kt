package com.ukkera.pos.catalog.store.independent.supplierreferencev2

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlin.test.assertIs

/**
 * Actual handler prepare/evaluate/project with controlled JVM transaction DATA and pure
 * authorization snapshots. Positive rows come only from handler Apply proposals. The
 * bookkeeping below is not retained/native history, a Master writer, Room, audit/outbox,
 * current durable authority, or admitted RPC. It intentionally creates no history proof.
 */
internal class SupplierReferenceV2HandlerFixture(
    override val scope: BranchScope = SupplierReferenceV2Inputs.scope,
    val actor: DomainId = SupplierReferenceV2Inputs.actor,
    val device: DomainId = SupplierReferenceV2Inputs.device,
) : CommandReadView {
    val at = UtcInstant.parseCanonical("2026-11-06T01:00:00Z")
    private val rows = linkedMapOf<AggregateKey, StoredAggregate>()
    private val session = DomainId.random()
    private val generation = SessionGeneration(1)
    val originals = linkedMapOf<OperationId, UnitOriginal>()
    val v2 = LocalSupplierHandlerV2()
    val v1 = LocalConfigurationHandler(UnavailableCompanyPublicationSignatureVerifier)
    private val readV2 = LocalSupplierReadHandlerV2()
    private val readV1 = LocalConfigurationRepository(UnavailableCompanyPublicationSignatureVerifier).readHandler()
    val readPermission = Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM)
    val editPermission = Permission(SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER)
    var permissions = setOf(readPermission, editPermission)
    var accountEnabled = true
    var sessionActive = true
    var deviceAdmitted = true
    var missingDirectoryRow: AggregateKey? = null

    data class UnitOriginal(val command: CommandKey, val normalized: StoredBytes, val result: StoredBytes,
        val before: List<StoredAggregate?>, val after: List<StoredAggregate>)
    data class RowState(val key: AggregateKey, val revision: Revision, val schema: String, val payload: StoredBytes)
    fun snapshot(): List<RowState> = rows.values.sortedBy { it.key.id.value }.map { RowState(it.key, it.revision, it.schema, it.payload) }
    fun row(id: DomainId): StoredAggregate = checkNotNull(rows[LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, id)])
    fun storageRevision(id: DomainId): Revision = row(id).revision
    fun current(id: DomainId): LocalSupplierV2 =
        (decodeRecord(scope, row(id)).body as ConfigurationBody.Supplier).supplierV2()

    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = if (key == missingDirectoryRow) null else rows[key]
    override suspend fun claim(key: ClaimKey): DomainId? = error("No claim source is provided by this supplier unit fixture")
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? =
        error("Unit originals are not native retained-command evidence")
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
        val remaining = rows.keys.filter { it.kind == kind && (after == null || it.id.value > after.value) }.sortedBy { it.id.value }
        val emitted = remaining.take(limit)
        return AggregateKeyPage(emitted, if (remaining.size > emitted.size) emitted.last().id else null)
    }

    suspend fun prepared(request: LocalSupplierRequestV2, operation: OperationId): PreparedMutation =
        prepare(v2, LocalSupplierCodecV2.encode(request), operation)

    suspend fun propose(request: LocalSupplierRequestV2, operation: OperationId): ProposedOutcome {
        val prepared = prepared(request, operation)
        return prepared.evaluate(authorized(prepared.requirement), at)
    }

    suspend fun commit(request: LocalSupplierRequestV2, operation: OperationId): LocalSupplierResultV2 {
        val normalized = v2.normalize(LocalSupplierCodecV2.encode(request))
        val prepared = prepare(v2, normalized, operation)
        val apply = assertIs<ProposedOutcome.Apply>(prepared.evaluate(authorized(prepared.requirement), at))
        retain(v2.key, normalized, operation, apply)
        return LocalSupplierCodecV2.decodeResult(apply.result)
    }

    suspend fun commitV1(request: LocalConfigurationRequestV1.ConfigureSupplier, operation: OperationId): LocalConfigurationResultV1 {
        val normalized = v1.normalize(LocalConfigurationCodec.encode(request))
        val prepared = prepare(v1, normalized, operation)
        val apply = assertIs<ProposedOutcome.Apply>(prepared.evaluate(authorized(prepared.requirement), at))
        retain(v1.key, normalized, operation, apply)
        return LocalConfigurationCodec.decodeResult(apply.result)
    }

    private suspend fun prepare(handler: MasterCommandHandler, payload: StoredBytes, operation: OperationId): PreparedMutation {
        val normalized = handler.normalize(payload)
        return handler.prepare(normalized, this, CommandContext(scope, actor, operation, handler.key,
            semanticDigest(scope, actor, handler.key, normalized)))
    }

    private fun retain(command: CommandKey, normalized: StoredBytes, operation: OperationId, apply: ProposedOutcome.Apply) {
        check(operation !in originals)
        check(apply.changes.size == 1 && apply.changes.single().key.kind == LocalConfigurationProfile.SUPPLIER)
        check(apply.claims.isEmpty() && apply.jobs.isEmpty())
        val before = apply.changes.map { rows[it.key] }
        val after = apply.changes.map { change ->
            check(rows[change.key]?.revision == change.expectedRevision)
            val storageRevision = (change.expectedRevision ?: Revision(0)).next()
            StoredAggregate(change.key, storageRevision, change.schema, change.payload,
                sha256(change.payload.copyBytes())).also { rows[change.key] = it }
        }
        originals[operation] = UnitOriginal(command, normalized, apply.result, before, after)
    }

    suspend fun read(query: LocalSupplierQueryV2): LocalSupplierPageV2 = LocalSupplierCodecV2.decodePage(readBytes(query))
    suspend fun readBytes(query: LocalSupplierQueryV2): StoredBytes = project(readV2, LocalSupplierCodecV2.query(query))
    suspend fun readV1(query: LocalConfigurationQueryV1): LocalConfigurationPageV1 =
        LocalConfigurationCodec.decodePage(project(readV1, LocalConfigurationCodec.query(query)))

    private suspend fun project(handler: MasterReadHandler, payload: StoredBytes): StoredBytes {
        val normalized = handler.normalize(payload)
        val prepared = handler.prepare(normalized, this, ReadContext(scope, actor, handler.key,
            queryDigest(scope, actor, handler.key, normalized)))
        return prepared.project(prepared.requirements.map { authorized(it) }, at)
    }

    suspend fun decision(requirement: AuthorizationRequirement, requiredScope: BranchScope = scope): AuthorizationDecision {
        val verifier = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials): VerifiedLocalIdentity =
                VerifiedLocalIdentity(actor, scope, device, session, generation, Revision(1), AuthenticationPurpose.INTERACTIVE)
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val credentials = LocalGatewayCredentials(device, OpaqueCredential(ByteArray(16)))
        val principal = try { checkNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(credentials)) }
            finally { credentials.bearer.destroy() }
        val grants = permissions.map { PermissionGrant(it, scope, GrantEffect.ALLOW) }
        val snapshot = AuthorizationSnapshot(actor, PrincipalKind.LOCAL_EMPLOYEE, scope, session, generation,
            device, Revision(1), Revision(1), accountEnabled, sessionActive, deviceAdmitted,
            AuthenticationPurpose.INTERACTIVE, grants)
        return AuthorizationEvaluator.evaluate(principal, snapshot, requirement.copy(scope = requiredScope))
    }

    private suspend fun authorized(requirement: AuthorizationRequirement): AuthorizedAction =
        assertIs<AuthorizationDecision.Allowed>(decision(requirement)).authorization
}
