package com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1

import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.sales.store.LocalSalesActivity
import com.ukkera.pos.sales.store.LocalSalesChannel
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * WH-RPC-002/004/005/007/015/019 supporting typed-handler portions. The production
 * normalizers, profile classifiers, preparation, authorization evaluator and projections run
 * against controlled source rows and explicit synthetic current-state snapshots. A proposal
 * is not a committed warehouse, and a projected row is not native persistence or RPC authority.
 * No Master transaction, audit/outbox, guard ledger, restart or whole case is qualified here.
 */
class WarehouseRpcV1ConfigurationBoundarySupportingTest {
    private val frozen get() = FrozenWarehouseRpcV1

    @Test
    fun wh001wh007wh019ActualSharedHandlersClassifyBodiesNotTheirSharedConfigurationKey() {
        val handler = LocalConfigurationHandler(UnavailableCompanyPublicationSignatureVerifier)
        val warehouses = listOf(frozen.body(frozen.create()), frozen.body(frozen.rename()),
            frozen.body(frozen.retire(existing = true)), frozen.body(frozen.retire(existing = false)))
        for (body in warehouses) {
            val original = LocalConfigurationCodec.encode(body)
            val normalized = handler.normalize(original)
            assertEquals(original, normalized)
            assertTrue(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.WAREHOUSES, normalized))
            assertFalse(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.SUPPLIERS, normalized))
            assertEquals(body, LocalConfigurationCodec.decode(normalized))
        }
        val supplier = LocalConfigurationRequestV1.ConfigureSupplier(null,
            LocalSupplierV1(frozen.created.id, frozen.created.names, true))
        val supplierBytes = handler.normalize(LocalConfigurationCodec.encode(supplier))
        assertTrue(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.SUPPLIERS, supplierBytes))
        assertFalse(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.WAREHOUSES, supplierBytes))
        // The frozen wrong-profile variants require otherwise representable canonical bodies.
        for (body in unrelatedBodies()) {
            val normalized = handler.normalize(LocalConfigurationCodec.encode(body))
            assertFalse(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.WAREHOUSES, normalized))
            assertFalse(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.SUPPLIERS, normalized))
        }
        val read = LocalConfigurationRepository(UnavailableCompanyPublicationSignatureVerifier).readHandler()
        for (kind in listOf(LocalConfigurationProfile.WAREHOUSE, LocalConfigurationProfile.SUPPLIER,
            LocalConfigurationProfile.SKU, LocalConfigurationProfile.TENDER, LocalConfigurationProfile.SALES_ROUTE,
            LocalConfigurationProfile.BASIS)) {
            val original = LocalConfigurationCodec.query(LocalConfigurationQueryV1(kind))
            val normalized = read.normalize(original)
            assertEquals(original, normalized)
            assertEquals(kind == LocalConfigurationProfile.WAREHOUSE,
                read.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.WAREHOUSES, normalized))
            assertEquals(kind == LocalConfigurationProfile.SUPPLIER,
                read.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.SUPPLIERS, normalized))
        }
        assertFailsWith<IllegalArgumentException> {
            LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE, barcode = "synthetic-barcode")
        }
    }

    @Test
    fun wh002ActualReadHandlerProjectsFrozenFiftyPlusThreeAndSingletonPagesWithoutCatalogCursor() = runTest(timeout = 5.seconds) {
        for (limit in listOf(50, 1)) {
            val view = WarehouseRpcHandlerView.seeded()
            val handler = LocalConfigurationRepository(UnavailableCompanyPublicationSignatureVerifier).readHandler()
            val pages = mutableListOf<LocalConfigurationPageV1>()
            var after: DomainId? = null
            do {
                val query = LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE, after = after, limit = limit)
                val normalized = handler.normalize(LocalConfigurationCodec.query(query))
                val prepared = handler.prepare(normalized, view, ReadContext(frozen.scope, frozen.actor,
                    handler.key, queryDigest(frozen.scope, frozen.actor, handler.key, normalized)))
                assertEquals(listOf(AuthorizationRequirement(Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM), frozen.scope)),
                    prepared.requirements)
                val page = LocalConfigurationCodec.decodePage(prepared.project(prepared.requirements.map { warehouseAuthorization(it) }, frozen.at))
                pages += page
                after = page.nextAfter
                assertTrue(pages.size <= frozen.warehouses.size)
            } while (after != null)
            if (limit == 50) assertEquals(listOf(50, 3), pages.map { it.items.size })
            else { assertEquals(53, pages.size); assertTrue(pages.all { it.items.size == 1 }) }
            val rows = pages.flatMap { it.items }
            assertEquals(frozen.warehouses.map { it.id }, rows.map { it.id })
            assertEquals(frozen.warehouses.map { it.names }, rows.map { it.names })
            assertTrue(rows.all { it.active == true && it.storageRevision == frozen.storageRevision })
            assertTrue(pages.all { it.catalogRevision == null && it.detail == null })
            assertNull(pages.last().nextAfter)
            assertTrue(view.reads.all { it.kind == LocalConfigurationProfile.WAREHOUSE })
            assertEquals(53, view.rows.size)
        }
    }

    @Test
    fun wh002wh003ActualDirectDetailAndAuthorizedEmptyDirectoryAreDistinctFromFailure() = runTest(timeout = 5.seconds) {
        val handler = LocalConfigurationRepository(UnavailableCompanyPublicationSignatureVerifier).readHandler()
        for (empty in listOf(false, true)) {
            val view = if (empty) WarehouseRpcHandlerView() else WarehouseRpcHandlerView.seeded()
            val query = LocalConfigurationQueryV1(LocalConfigurationProfile.WAREHOUSE, id = frozen.first.id)
            val normalized = handler.normalize(LocalConfigurationCodec.query(query))
            val prepared = handler.prepare(normalized, view, ReadContext(frozen.scope, frozen.actor, handler.key,
                queryDigest(frozen.scope, frozen.actor, handler.key, normalized)))
            assertFailsWith<IllegalStateException> { prepared.project(emptyList(), frozen.at) }
            val page = LocalConfigurationCodec.decodePage(prepared.project(prepared.requirements.map { warehouseAuthorization(it) }, frozen.at))
            assertNull(page.nextAfter)
            assertNull(page.catalogRevision)
            if (empty) { assertTrue(page.items.isEmpty()); assertNull(page.detail) }
            else {
                assertEquals(listOf(frozen.row(frozen.first)), page.items)
                assertEquals(frozen.first, assertIs<LocalConfigurationDetailV1.Warehouse>(page.detail).value)
            }
            assertTrue(view.reads.all { it.kind == LocalConfigurationProfile.WAREHOUSE })
        }
    }

    @Test
    fun wh004wh005ActualCreateAndRenameProposeOnlyTheirExactWarehouseAndStorageCas() = runTest(timeout = 5.seconds) {
        for (intent in listOf(frozen.create(), frozen.rename())) {
            val view = WarehouseRpcHandlerView.seeded()
            val before = view.rows.toMap()
            val request = frozen.request(intent)
            val body = frozen.body(intent)
            val prepared = warehousePrepare(request, view)
            assertEquals(Permission(SecurityAction.WAREHOUSE_CONFIGURE, PermissionVerb.ADMINISTER), prepared.requirement.permission)
            val binding = assertNotNull(prepared.requirement.binding)
            assertEquals(body.warehouse.id, binding.documentId)
            assertEquals(intent.operationId, binding.operationId)
            assertEquals(semanticDigest(frozen.scope, frozen.actor, request.command, request.payload), binding.payloadDigest)
            assertTrue(RecoveryResourceKey(RecoveryResourceKind.STOCK, body.warehouse.id) in prepared.affectedResources)
            assertNull(prepared.admissionRestriction)
            val proposal = assertIs<ProposedOutcome.Apply>(prepared.evaluate(warehouseAuthorization(prepared.requirement), frozen.at))
            assertEquals(1, proposal.changes.size)
            val change = proposal.changes.single()
            assertEquals(LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, body.warehouse.id), change.key)
            assertEquals(body.expectedStorageRevision, change.expectedRevision)
            assertTrue(proposal.claims.isEmpty())
            assertTrue(proposal.jobs.isEmpty())
            val record = frozen.wire.parseToJsonElement(change.payload.copyBytes().decodeToString()).jsonObject
            assertEquals(body.warehouse, frozen.wire.decodeFromJsonElement(LocalWarehouseV1.serializer(),
                record.getValue("body").jsonObject.getValue("value")))
            val result = LocalConfigurationCodec.decodeResult(proposal.result)
            assertEquals("configuration.applied", result.code)
            assertEquals(body.warehouse.id, result.id)
            assertEquals(assertNotNull(result.revision).value, record.getValue("revision").jsonPrimitive.long)
            assertEquals(before, view.rows)
            assertTrue(view.reads.all { it.kind == LocalConfigurationProfile.WAREHOUSE })
        }
        // Proposals are deliberately not applied: addition/count-54, rename/count-53 and all
        // financial/stock/evidence invariants require actual Master transactions and guard history.
    }

    @Test
    fun wh007ActualInactiveExistingAndNewRequestsAreAdmittedForExactNoncommittedRefusal() = runTest(timeout = 5.seconds) {
        for (existing in listOf(true, false)) {
            val view = WarehouseRpcHandlerView.seeded()
            val before = view.rows.toMap()
            val intent = frozen.retire(existing)
            val request = frozen.request(intent)
            val handler = LocalConfigurationHandler(UnavailableCompanyPublicationSignatureVerifier)
            val normalized = handler.normalize(request.payload)
            assertTrue(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.WAREHOUSES, normalized))
            assertFalse(assertIs<LocalConfigurationRequestV1.ConfigureWarehouse>(LocalConfigurationCodec.decode(normalized)).warehouse.active)
            val prepared = warehousePrepare(request, view)
            val refusal = assertIs<ProposedOutcome.Reject>(prepared.evaluate(warehouseAuthorization(prepared.requirement), frozen.at))
            assertEquals(frozen.retirementCode, refusal.code)
            assertEquals(LocalConfigurationResultV1(frozen.retirementCode, null, null), LocalConfigurationCodec.decodeResult(refusal.result))
            assertEquals(before, view.rows)
            assertEquals(frozen.retireInputs.getValue("expected_total").jsonPrimitive.int, view.rows.size)
            val first = view.rows.getValue(LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, frozen.first.id))
            assertEquals(frozen.storageRevision, first.revision)
            assertEquals(frozen.first, frozen.wire.decodeFromJsonElement(LocalWarehouseV1.serializer(),
                frozen.wire.parseToJsonElement(first.payload.copyBytes().decodeToString()).jsonObject.getValue("body").jsonObject.getValue("value")))
            assertFalse(view.rows.containsKey(LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, frozen.created.id)))
            assertTrue(view.reads.all { it.kind == LocalConfigurationProfile.WAREHOUSE })
        }
    }

    @Test
    fun wh015ActualPermissionEvaluatorKeepsReadConfigureResourceAndCurrentStateSeparate() = runTest(timeout = 5.seconds) {
        val view = WarehouseRpcHandlerView.seeded()
        val prepared = warehousePrepare(frozen.request(frozen.rename()), view)
        val requirement = prepared.requirement
        val readRequirement = AuthorizationRequirement(Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM), frozen.scope)
        assertIs<AuthorizationDecision.Denied>(warehouseDecision(requirement, frozen.grants(configure = false)))
        assertIs<AuthorizationDecision.Denied>(warehouseDecision(readRequirement, frozen.grants(read = false)))
        assertIs<AuthorizationDecision.Denied>(warehouseDecision(requirement,
            listOf(frozen.grant(SecurityAction.WAREHOUSE_CONFIGURE, PermissionVerb.ADMINISTER, setOf(frozen.second.id)))))
        assertIs<AuthorizationDecision.Denied>(warehouseDecision(requirement, frozen.grants(configure = false, read = false)))
        for (state in listOf("disabled", "revoked", "unadmitted"))
            assertIs<AuthorizationDecision.Denied>(warehouseDecision(requirement, frozen.grants(), state))
        assertIs<AuthorizationDecision.Allowed>(warehouseDecision(requirement, frozen.grants()))
        assertIs<AuthorizationDecision.Allowed>(warehouseDecision(readRequirement, frozen.grants()))
        assertTrue(view.rows.size == 53)
        // This evaluator has explicit controlled snapshots, not a durable policy activation,
        // protected revocation/commit schedule, identity verifier or original recovery decision.
    }

    private fun unrelatedBodies(): List<LocalConfigurationRequestV1> {
        val id = frozen.created.id
        val names = frozen.created.names
        val sku = LocalPublishedSkuV1(frozen.scope.company(), frozen.slot("wrong-profile-publication"),
            frozen.slot("wrong-profile-sku"), frozen.slot("wrong-profile-product"), Revision(1), Revision(1),
            names, "synthetic-wrong-profile", emptyList(), frozen.slot("wrong-profile-unit"), 0,
            LocalTrackingV1.QUANTITY, LocalFulfillmentV1.RETAIL, false, true, false, true, emptyList(), emptyList())
        return listOf(LocalConfigurationRequestV1.ConfigureTender(null,
                LocalTenderV1(id, names, LocalExternalTenderKindV1.CARD, false, false, true)),
            LocalConfigurationRequestV1.ConfigureSalesRoute(null,
                LocalSalesRouteV1(id, LocalSalesActivity.SUPERMARKET, LocalSalesChannel.COUNTER, frozen.first.id, true)),
            LocalConfigurationRequestV1.ApproveReceiving(LocalReceivingApprovalV1(id, LocalBasisKindV1.OPENING,
                null, frozen.at, null, listOf(LocalApprovedStockLineV1(frozen.slot("wrong-profile-allocation"),
                    sku.skuId, frozen.first.id, Quantity(sku.baseUnitId, 1, 0), Money(Currency.EGP, 0), false)))),
            LocalConfigurationRequestV1.InstallCompanySku(LocalSignedSkuV1(sku, frozen.slot("untrusted-signing-key"), "00".repeat(64))))
    }
}

private suspend fun warehousePrepare(request: MutationRequest, view: CommandReadView): PreparedMutation {
    val handler = LocalConfigurationHandler(UnavailableCompanyPublicationSignatureVerifier)
    val normalized = handler.normalize(request.payload)
    val frozen = FrozenWarehouseRpcV1
    return handler.prepare(normalized, view, CommandContext(frozen.scope, frozen.actor, request.operationId,
        handler.key, semanticDigest(frozen.scope, frozen.actor, handler.key, normalized)))
}

/** Pure-policy inputs. They must never be represented as an authenticated native principal. */
private suspend fun warehouseDecision(requirement: AuthorizationRequirement, grants: List<PermissionGrant>, state: String = "current"): AuthorizationDecision {
    val frozen = FrozenWarehouseRpcV1
    val principal = LocalEmployeePrincipal.verified(VerifiedLocalIdentity(frozen.actor, frozen.scope, frozen.device,
        frozen.session, SessionGeneration(1), Revision(1), AuthenticationPurpose.INTERACTIVE))
    val snapshot = AuthorizationSnapshot(frozen.actor, PrincipalKind.LOCAL_EMPLOYEE, frozen.scope, frozen.session,
        SessionGeneration(1), frozen.device, Revision(1), frozen.policyRevision, state != "disabled", state != "revoked",
        state != "unadmitted", AuthenticationPurpose.INTERACTIVE, grants)
    return AuthorizationPolicy(CurrentAuthorizationProvider { snapshot }).authorize(principal, requirement)
}

private suspend fun warehouseAuthorization(requirement: AuthorizationRequirement): AuthorizedAction =
    assertIs<AuthorizationDecision.Allowed>(warehouseDecision(requirement, FrozenWarehouseRpcV1.grants())).authorization

/** Synthetic row/keyset port only. Its in-memory census is not a genuine SQLite transaction. */
private class WarehouseRpcHandlerView : CommandReadView {
    override val scope = FrozenWarehouseRpcV1.scope
    val rows = linkedMapOf<AggregateKey, StoredAggregate>()
    val reads = mutableListOf<AggregateKey>()
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? { reads += key; return rows[key] }
    override suspend fun claim(key: ClaimKey): DomainId? = error("Warehouse directory must not read unrelated claims")
    override suspend fun retainedCommand(operationId: com.ukkera.pos.types.OperationId): RetainedCommandEvidence? = null
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
        check(kind == LocalConfigurationProfile.WAREHOUSE && limit in 1..50)
        val keys = rows.keys.filter { it.kind == kind && (after == null || it.id.value > after.value) }.sortedBy { it.id.value }
        val page = keys.take(limit)
        return AggregateKeyPage(page, if (keys.size > page.size) page.last().id else null)
    }
    companion object {
        suspend fun seeded(): WarehouseRpcHandlerView {
            val frozen = FrozenWarehouseRpcV1
            val view = WarehouseRpcHandlerView()
            // Generate the carrier shape using the real typed producer, then represent the
            // frozen initial storage-12/domain-11 rows. No proposal is committed to a store.
            val seedRequest = MutationRequest(frozen.createOperation, LocalConfigurationProfile.COMMAND,
                LocalConfigurationCodec.encode(LocalConfigurationRequestV1.ConfigureWarehouse(null, frozen.first)))
            val prepared = warehousePrepare(seedRequest, view)
            val proposal = assertIs<ProposedOutcome.Apply>(prepared.evaluate(warehouseAuthorization(prepared.requirement), frozen.at))
            val template = frozen.wire.parseToJsonElement(proposal.changes.single().payload.copyBytes().decodeToString()).jsonObject
            for (warehouse in frozen.warehouses) {
                val oldBody = template.getValue("body").jsonObject
                val record = JsonObject(template + mapOf("id" to JsonPrimitive(warehouse.id.value),
                    "revision" to frozen.wire.encodeToJsonElement(Revision.serializer(), frozen.seedDomainRevision),
                    "body" to JsonObject(oldBody + ("value" to frozen.wire.encodeToJsonElement(LocalWarehouseV1.serializer(), warehouse)))))
                val payload = frozen.bytes(record)
                val key = LocalConfigurationProfile.key(LocalConfigurationProfile.WAREHOUSE, warehouse.id)
                val copied = payload.copyBytes()
                val digest = try { sha256(copied) } finally { copied.fill(0) }
                view.rows[key] = StoredAggregate(key, frozen.storageRevision, LocalConfigurationProfile.schema(key.kind), payload, digest)
            }
            view.reads.clear()
            return view
        }
    }
}
