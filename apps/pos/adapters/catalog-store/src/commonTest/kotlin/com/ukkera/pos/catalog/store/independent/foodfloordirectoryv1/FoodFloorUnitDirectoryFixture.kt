@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.catalog.store.independent.foodfloordirectoryv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/**
 * Unit fixture for actual handler preparation/evaluation/projection. Infrastructure identity,
 * current grants, contexts and retained bookkeeping are synthetic test seams. They are neither
 * a real Master transaction nor authenticated native history, Room durability or admitted RPC.
 *
 * No version-shaped directory is injected: each seed passes through the actual configuration
 * handler. Retained payloads/results come from its Apply output; writes use the store's canonical
 * (kind, ID) ordering. The unit journal hashes below are placeholders, never provenance proof.
 * No runtime/history verifier, native business join, audit or store-owned outbox is supplied.
 */
internal class FoodFloorUnitDirectoryFixture : CommandReadView {
    override val scope = FfdCatalogInputs.scope
    private val actor = FfdCatalogInputs.configurator
    private val device = DomainId.random()
    private val session = DomainId.random()
    private val assignment = DomainId.random()
    private val at = UtcInstant.parseCanonical("2026-10-31T02:00:00Z")
    private val generation = SessionGeneration(1)
    private val rows = linkedMapOf<AggregateKey, StoredAggregate>()
    private val originals = linkedMapOf<OperationId, RetainedCommandEvidence>()
    private val handler = LocalFoodFloorConfigurationHandler()
    private val placeholderHash = sha256("unit bookkeeping only".encodeToByteArray())

    data class Snapshot(val rows: List<Triple<AggregateKey, Revision, StoredBytes>>, val operations: List<OperationId>)
    fun snapshot() = Snapshot(rows.values.sortedWith(compareBy({ it.key.kind }, { it.key.id.value }))
        .map { Triple(it.key, it.revision, it.payload) }, originals.keys.toList())

    override suspend fun aggregate(key: AggregateKey) = rows[key]
    override suspend fun claim(key: ClaimKey): DomainId? = error("No claim source is qualified by this unit fixture")
    override suspend fun retainedCommand(operationId: OperationId) = originals[operationId]
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
        val remaining = rows.keys.filter { it.kind == kind && (after == null || it.id.value > after.value) }
            .sortedBy { it.id.value }
        val selected = remaining.take(limit)
        return AggregateKeyPage(selected, if (remaining.size > limit) selected.last().id else null)
    }

    suspend fun propose(request: LocalFoodFloorConfigurationRequestV1, operation: OperationId): ProposedOutcome {
        val normalized = handler.normalize(LocalFoodFloorCodecV1.encode(request))
        val context = CommandContext(scope, actor, operation, handler.key, semanticDigest(scope, actor, handler.key, normalized))
        val prepared = handler.prepare(normalized, this, context)
        return prepared.evaluate(authorize(prepared.requirement), at)
    }

    suspend fun commit(request: LocalFoodFloorConfigurationRequestV1, operation: OperationId): LocalConfigurationResultV1 {
        check(operation !in originals)
        val normalized = handler.normalize(LocalFoodFloorCodecV1.encode(request))
        val outcome = propose(request, operation) as? ProposedOutcome.Apply ?: error("Unit seed/configuration refused")
        val writes = outcome.changes.sortedWith(compareBy({ it.key.kind }, { it.key.id.value })).map { change ->
            check(rows[change.key]?.revision == change.expectedRevision)
            val revision = (change.expectedRevision ?: Revision(0)).next()
            val digest = sha256(change.payload.copyBytes())
            rows[change.key] = StoredAggregate(change.key, revision, change.schema, change.payload, digest)
            RetainedAggregateWrite(change.key, revision, change.schema, change.payload, digest)
        }
        check(outcome.claims.isEmpty() && outcome.jobs.isEmpty())
        originals[operation] = RetainedCommandEvidence(scope, operation, actor, handler.key, assignment,
            Revision(1), JournalSequence(originals.size.toLong() + 1), at, session, Revision(1),
            semanticDigest(scope, actor, handler.key, normalized), "committed", normalized, outcome.result,
            sha256(outcome.result.copyBytes()), placeholderHash, placeholderHash, writes, emptyList(), emptyList())
        return LocalFoodFloorCodecV1.decodeResult(outcome.result)
    }

    suspend fun read(query: LocalFoodFloorQueryV1): LocalFoodFloorPageV1 {
        val reader = LocalFoodFloorReadHandler()
        val normalized = reader.normalize(LocalFoodFloorReadCodecV1.encode(query))
        val prepared = reader.prepare(normalized, this,
            ReadContext(scope, actor, reader.key, queryDigest(scope, actor, reader.key, normalized)))
        val authorizations = prepared.requirements.map { authorize(it) }
        return LocalFoodFloorReadCodecV1.decodeResult(prepared.project(authorizations, at))
    }

    private suspend fun authorize(requirement: AuthorizationRequirement): AuthorizedAction {
        val configured = Permission(SecurityAction.FLOOR_CONFIGURE, PermissionVerb.ADMINISTER)
        val read = Permission(SecurityAction.FLOOR_READ, PermissionVerb.PERFORM)
        check(requirement.scope == scope && requirement.permission in setOf(configured, read))
        val verifier = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials) =
                VerifiedLocalIdentity(actor, scope, device, session, generation, Revision(1), AuthenticationPurpose.INTERACTIVE)
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val credentials = LocalGatewayCredentials(device, OpaqueCredential(ByteArray(16)))
        val principal = try { checkNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(credentials)) }
            finally { credentials.bearer.destroy() }
        val grants = listOf(PermissionGrant(read, scope, GrantEffect.ALLOW),
            PermissionGrant(configured, scope, GrantEffect.ALLOW,
                targetIds = requirement.binding?.documentId?.let { setOf(it) }))
        val snapshot = AuthorizationSnapshot(actor, PrincipalKind.LOCAL_EMPLOYEE, scope, session, generation,
            device, Revision(1), Revision(1), accountEnabled = true, sessionActive = true, deviceAdmitted = true,
            authenticationPurpose = AuthenticationPurpose.INTERACTIVE, grants = grants)
        return (AuthorizationEvaluator.evaluate(principal, snapshot, requirement) as? AuthorizationDecision.Allowed)
            ?.authorization ?: error("Explicit unit grant did not authorize handler requirement")
    }

    companion object {
        suspend fun base(): FoodFloorUnitDirectoryFixture = FoodFloorUnitDirectoryFixture().apply {
            for ((definition, revision, start) in listOf(
                Triple(FfdCatalogInputs.floorA, 7, 1), Triple(FfdCatalogInputs.floorB, 3, 101))) {
                for (version in 1..revision) commit(LocalFoodFloorConfigurationRequestV1.Floor(
                    if (version == 1) null else Revision(version.toLong() - 1), definition.copy(names =
                        if (version == revision) definition.names else LocalNamesV1("Seed ${if (start == 1) "F-A" else "F-B"} v$version", ""))),
                    FfdCatalogInputs.seedOperation(start + version - 1))
            }
            for ((definition, revision, start) in listOf(
                Triple(FfdCatalogInputs.table010, 11, 201), Triple(FfdCatalogInputs.table020, 2, 301),
                Triple(FfdCatalogInputs.table030, 5, 401))) {
                val alias = when (start) { 201 -> "T-010"; 301 -> "T-020"; else -> "T-030" }
                for (version in 1..revision) commit(LocalFoodFloorConfigurationRequestV1.Table(
                    if (version == 1) null else Revision(version.toLong() - 1), definition.copy(names =
                        if (version == revision) definition.names else LocalNamesV1("Seed $alias v$version", ""))),
                    FfdCatalogInputs.seedOperation(start + version - 1))
            }
        }

        suspend fun capacity(): FoodFloorUnitDirectoryFixture = FoodFloorUnitDirectoryFixture().apply {
            // CAPACITY specifies real revision-one creation and exact definition IDs, but no
            // specific seed operation serials. These IDs label this unit producer sequence only.
            for (serial in 1..16) commit(LocalFoodFloorConfigurationRequestV1.Floor(null,
                FfdCatalogInputs.capacityFloor(serial)), FfdCatalogInputs.seedOperation(10_000 + serial))
            for (serial in 1..100) commit(LocalFoodFloorConfigurationRequestV1.Table(null,
                FfdCatalogInputs.capacityTable(serial)), FfdCatalogInputs.seedOperation(11_000 + serial))
        }
    }
}
