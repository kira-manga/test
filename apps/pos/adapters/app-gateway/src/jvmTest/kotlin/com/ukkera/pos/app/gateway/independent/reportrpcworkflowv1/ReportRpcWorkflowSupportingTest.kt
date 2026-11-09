package com.ukkera.pos.app.gateway.independent.reportrpcworkflowv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.app.gateway.independent.warehouserpcworkflowv1.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.Permission
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.UtcInstant
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import dev.p2pkit.rpc.RpcRetrySafety
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Frozen RRP-001/002 supporting bindings, not admitted RPC, native authority or persistence. */
class ReportRpcWorkflowSupportingTest {
    private val wire = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/report-rpc-workflow/fixtures.json") }
        .first { Files.isRegularFile(it) }
    private val document = Json.parseToJsonElement(Files.readString(root)).jsonObject
        .also { assertEquals("REPORT-RPC-WORKFLOW-v1", it.getValue("version").jsonPrimitive.content) }
    private val fixtures = document.getValue("fixtures").jsonObject
    private val api = fixtures.getValue("F-API").jsonObject
    private val routes = fixtures.getValue("F-ROUTES").jsonObject
    private val safe = fixtures.getValue("F-SAFE-7").jsonObject
    private val original = fixtures.getValue("F-ORIGINAL").jsonObject
    private val cut = safe.getValue("portable_cut").jsonObject
    private val reportRevision get() = Revision(text(cut, "branchRevision").toLong())
    private val reportObservedAt get() = UtcInstant.parseCanonical(text(cut, "secondObservedAt"))
    private fun text(value: JsonObject, key: String) = value.getValue(key).jsonPrimitive.content
    private val rows: List<LocalBranchReportRowV1> get() = safe.getValue("source_rows").jsonArray.map {
        wire.decodeFromJsonElement(LocalBranchReportRowV1.serializer(), JsonObject(it.jsonObject - "alias"))
    }
    private fun query(after: DomainId? = null) = LocalBranchReportQueryV1(LocalBranchReportSectionV1.SAFE_TRANSFERS,
        after, delivery = LocalBranchReportDeliveryV1.VIEW)
    private fun bytes(value: JsonElement) = StoredBytes(wire.encodeToString(JsonElement.serializer(), value).encodeToByteArray())
    private fun reply(page: LocalBranchReportResultV1.Page, revision: Revision = reportRevision) = ReadReply.Data(
        bytes(wire.encodeToJsonElement(LocalBranchReportResultV1.serializer(), page)), revision, reportObservedAt)
    private fun uiQuery(after: DomainId? = null, revision: Revision? = null) = LocalWorkflowQuery(
        LocalWorkflow.REPORTS, branchReport = LocalBranchReportQuery(LocalBranchReportSection.SAFE_TRANSFERS, after, revision ?: if (after != null) reportRevision else null))

    @Test fun rrp001FixedAdditiveRoutesReuseLegacyCarriersAndNeverReinvoke() {
        val actual = listOf(LocalMasterReportRpcProtocolV1.READ, LocalMasterReportRpcProtocolV1.EXECUTE,
            LocalMasterReportRpcProtocolV1.LOOKUP)
        assertEquals(routes.getValue("rpc_routes").jsonArray.map { it.jsonPrimitive.content },
            actual.map { "${it.name}/${it.version}" })
        val legacy = listOf(LocalMasterClientRpcProtocolV1.READ, LocalMasterClientRpcProtocolV1.EXECUTE,
            LocalMasterClientRpcProtocolV1.LOOKUP)
        actual.zip(legacy).forEach { (report, original) ->
            assertSame(original.request, report.request)
            assertSame(original.response, report.response)
            assertSame(original.applicationError, report.applicationError)
            assertEquals(original.retrySafety, report.retrySafety)
            assertEquals(original.requestLimitBytes, report.requestLimitBytes)
            assertEquals(original.responseLimitBytes, report.responseLimitBytes)
            assertEquals(original.errorLimitBytes, report.errorLimitBytes)
            assertEquals(RpcRetrySafety.NeverReinvoke, report.retrySafety)
        }
        assertEquals(RpcRetrySafety.NeverReinvoke, LocalMasterReportRpcProtocolV1.EXECUTE.retrySafety)
        assertEquals("pos.business.read", LocalMasterClientRpcProtocolV1.READ.name)
        assertEquals("pos.warehouse.read", LocalMasterWarehouseRpcProtocolV1.READ.name)
    }

    @Test fun rrp001OnlyExactReadAndCommandKeysAreClassified() {
        val reads = routes.getValue("allowed_read_keys").jsonArray.map { it.jsonObject }
        reads.forEach { value ->
            val key = ReadKey(text(value, "name"), value.getValue("version").jsonPrimitive.int)
            assertTrue(LocalMasterReportRpcKeysV1.accepts(key))
            assertFalse(LocalMasterReportRpcKeysV1.accepts(key.copy(version = 2)))
        }
        val command = routes.getValue("allowed_command_key").jsonObject
        val key = CommandKey(text(command, "name"), command.getValue("version").jsonPrimitive.int)
        assertTrue(LocalMasterReportRpcKeysV1.accepts(key))
        assertFalse(LocalMasterReportRpcKeysV1.accepts(key.copy(version = 2)))
        for (name in listOf("catalog.configuration.read", "sales.order.read", "inventory.stock.read", "pos.identity.current"))
            assertFalse(LocalMasterReportRpcKeysV1.accepts(ReadKey(name, 1)))
        for (name in listOf("catalog.configuration", "sales.order", "inventory.opening", "pos.physical.start"))
            assertFalse(LocalMasterReportRpcKeysV1.accepts(CommandKey(name, 1)))
    }

    @Test fun rrp001RestrictedInstallationNeverBecomesDirectoryOrGenericAction() {
        val installed = LocalWorkflowInstallation.directoryOnly(LocalMasterRpcWorkflowProfile.REPORTS)
        assertTrue(installed.isRestricted)
        assertTrue(installed.isReportsOnly)
        assertFalse(installed.isDirectoryOnly)
        assertNull(installed.directoryKind)
        assertTrue(installed.has(LocalBranchReportReadCodec.QUERY))
        assertTrue(installed.has(LocalBranchReportExportCodecV1.QUERY))
        assertTrue(installed.has(LocalBranchReportExportCodecV1.COMMAND))
        assertTrue(LocalAction.entries.none(installed::allows))
        installed.requireWorkflow(uiQuery())
        for (workflow in LocalWorkflow.entries.filter { it != LocalWorkflow.REPORTS })
            assertFailsWith<GatewayFailure> { installed.requireWorkflow(LocalWorkflowQuery(workflow)) }
        assertFailsWith<IllegalArgumentException> { uiQuery().copy(search = "private") }
    }

    @Test fun rrp002ExistingSectionsDeliveriesAndOnlyRefundProjectionRoundTrip() {
        // The frozen API fixture describes the original section prefix. The additive
        // correction section/profile has separate payment-correction report coverage.
        val legacySections = LocalBranchReportSectionV1.entries.takeWhile {
            it != LocalBranchReportSectionV1.PAYMENT_CORRECTIONS
        }
        assertEquals(api.getValue("existing_sections").jsonArray.map { it.jsonPrimitive.content },
            legacySections.map { it.name })
        assertEquals(api.getValue("deliveries").jsonArray.map { it.jsonPrimitive.content },
            LocalBranchReportDeliveryV1.entries.map { it.name })
        for (section in legacySections) {
            for (delivery in LocalBranchReportDeliveryV1.entries) {
                val original = LocalBranchReportQueryV1(section, delivery = delivery)
                val encoded = LocalBranchReportReadCodec.encode(original)
                assertEquals(encoded, LocalBranchReportReadHandler().normalize(encoded))
                assertFalse("profile" in Json.parseToJsonElement(encoded.copyBytes().decodeToString()).jsonObject)
            }
            if (section != LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS)
                assertFailsWith<IllegalArgumentException> { LocalBranchReportQueryV1(section,
                    profile = LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2) }
        }
        val projected = LocalBranchReportQueryV1(LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS,
            profile = LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2)
        assertEquals(LocalBranchReportReadCodec.encode(projected),
            LocalBranchReportReadHandler().normalize(LocalBranchReportReadCodec.encode(projected)))
    }

    @Test fun rrp002LimitAndMalformedInputVectorsRefuseWithoutFallback() {
        for (vector in api.getValue("limit_vectors").jsonArray.map { it.jsonObject }) {
            val limit = vector.getValue("value").jsonPrimitive.int
            if (vector.getValue("accepted").jsonPrimitive.boolean)
                assertEquals(limit, query().copy(limit = limit).limit)
            else assertFailsWith<IllegalArgumentException> { query().copy(limit = limit) }
        }
        val encoded = wire.encodeToJsonElement(LocalBranchReportQueryV1.serializer(), query()).jsonObject
        for ((field, value) in listOf("after" to text(api, "malformed_cursor"),
            "section" to text(api, "invalid_enum"), "profile" to text(api, "invalid_projection"))) {
            assertFails { LocalBranchReportReadHandler().normalize(bytes(JsonObject(encoded + (field to JsonPrimitive(value))))) }
        }
        val bounds = api.getValue("payload_max_bytes").jsonObject
        val begin = wire.decodeFromJsonElement(LocalBranchReportExportRequestV1.serializer(), original.getValue("begin"))
        val metadata = LocalBranchReportExportCodecV1.read(DomainId(text(original, "exportId")))
        val result = bytes(wire.encodeToJsonElement(LocalBranchReportExportResultV1.serializer(), LocalBranchReportExportResultV1.NotFound))
        fun padded(body: StoredBytes, count: Int): StoredBytes {
            val raw = body.copyBytes()
            assertTrue(raw.size < count)
            return StoredBytes(raw + ByteArray(count - raw.size) { 32 })
        }
        // The frozen maxima bound input size; they do not make noncanonical JSON
        // admissible. At B, this valid JSON reaches canonical-format rejection;
        // at B+1, the byte guard refuses it before parsing. Canonical round trips
        // remain separate controls, not claims of admissible exact-limit input.
        fun checkBound(body: StoredBytes, limit: Int, decode: (StoredBytes) -> Any) {
            val atLimit = padded(body, limit)
            val oversized = padded(body, limit + 1)
            assertEquals(limit, atLimit.size)
            assertEquals(limit + 1, oversized.size)
            assertEquals("sales.body.non_canonical",
                assertFailsWith<IllegalArgumentException> { decode(atLimit) }.message)
            assertEquals("sales.body.too_large",
                assertFailsWith<IllegalArgumentException> { decode(oversized) }.message)
        }
        val reportBody = LocalBranchReportReadCodec.encode(query())
        assertEquals(reportBody, LocalBranchReportReadHandler().normalize(reportBody))
        checkBound(reportBody, bounds.getValue("report_query").jsonPrimitive.int,
            LocalBranchReportReadHandler()::normalize)
        val commandBody = LocalBranchReportExportCodecV1.encode(begin)
        assertEquals(begin, LocalBranchReportExportCodecV1.decodeRequest(commandBody))
        checkBound(commandBody, bounds.getValue("export_command").jsonPrimitive.int,
            LocalBranchReportExportCodecV1::decodeRequest)
        assertEquals(metadata.payload, LocalBranchReportExportReadHandler().normalize(metadata.payload))
        checkBound(metadata.payload, bounds.getValue("export_metadata_query").jsonPrimitive.int,
            LocalBranchReportExportReadHandler()::normalize)
        assertEquals(LocalBranchReportExportResultV1.NotFound, LocalBranchReportExportCodecV1.decodeResult(result))
        checkBound(result, bounds.getValue("export_metadata_result").jsonPrimitive.int,
            LocalBranchReportExportCodecV1::decodeResult)
    }

    @Test fun rrp002EmptyProjectionEchoesMetadataWithoutInventingDatesOrRows() {
        // Preserve every original null/refund-profile vector; the appended correction
        // section requires its explicit profile and is covered separately.
        val legacySections = LocalBranchReportSectionV1.entries.takeWhile {
            it != LocalBranchReportSectionV1.PAYMENT_CORRECTIONS
        }
        for (section in legacySections) {
            val profiles = if (section == LocalBranchReportSectionV1.FOOD_REFUND_OBLIGATIONS)
                listOf(null, LocalBranchReportProjectionV1.FOOD_REFUND_PAYOUT_V2) else listOf(null)
            for (delivery in LocalBranchReportDeliveryV1.entries) for (profile in profiles) {
                val empty = LocalBranchReportResultV1.Page(section, delivery, null, emptyList(), null, profile)
                val value = wire.encodeToJsonElement(LocalBranchReportResultV1.serializer(), empty).jsonObject
                assertEquals(empty, LocalBranchReportReadCodec.decode(bytes(value)))
                assertTrue(empty.rows.isEmpty())
                if (profile == null) assertFalse("profile" in value)
            }
        }
        val begin = wire.decodeFromJsonElement(LocalBranchReportExportRequestV1.serializer(), original.getValue("begin"))
        val page = assertIs<LocalBranchReportExportRequestV1.Begin>(begin).copy(coverage = null)
        val value = Json.parseToJsonElement(LocalBranchReportExportCodecV1.encode(page).copyBytes().decodeToString()).jsonObject
        assertFalse("coverage" in value)
        assertEquals(page, LocalBranchReportExportCodecV1.decodeRequest(bytes(value)))
    }

    @Test fun rrp009MetadataRequirementDeclarationNamesExportAndRecoveryWithoutAnOriginal() = runTest {
        val scope = FrozenWarehouseRpcV1.scope
        val view = object : CommandReadView {
            override val scope = FrozenWarehouseRpcV1.scope
            override suspend fun aggregate(key: AggregateKey): StoredAggregate? = null
            override suspend fun claim(key: ClaimKey): DomainId? = null
            override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? = null
        }
        val handler = LocalBranchReportExportReadHandler()
        val request = LocalBranchReportExportCodecV1.read(DomainId(text(original, "exportId")))
        val digest = PayloadDigest("a".repeat(64))
        val prepared = handler.prepare(handler.normalize(request.payload), view,
            ReadContext(scope, FrozenWarehouseRpcV1.actor, request.query, digest))
        assertEquals(listOf(Permission(SecurityAction.BRANCH_REPORT, PermissionVerb.EXPORT),
            Permission(SecurityAction.OPERATION_RESULT_RECOVERY, PermissionVerb.PERFORM)),
            prepared.requirements.map { it.permission })
        assertEquals(OwnerConstraint.OwnerPrivate(FrozenWarehouseRpcV1.actor), prepared.requirements[1].owner)
        prepared.requirements.forEach {
            assertEquals(scope, it.scope)
            assertEquals(digest, assertNotNull(it.binding).payloadDigest)
            assertEquals(scope.branchId, assertNotNull(it.binding).documentId)
            assertNull(assertNotNull(it.binding).operationId)
        }
    }

    @Test fun rrp001And002ControlledReportViewUsesActualGatewayMapperAndExactSelectedPage() = runTest {
        withWarehouseRpcFixture(LocalMasterRpcWorkflowProfile.REPORTS) { h ->
            h.master.view = FrozenWarehouseRpcV1.view(grants = listOf(
                FrozenWarehouseRpcV1.grant(SecurityAction.BRANCH_REPORT, PermissionVerb.PERFORM)))
            val selected = rows.drop(5)
            h.master.readReply = { request ->
                assertEquals(LocalBranchReportReadCodec.QUERY, request.query)
                assertEquals(LocalBranchReportReadCodec.encode(query(rows[4].id)), request.payload)
                reply(LocalBranchReportResultV1.Page(query().section, query().delivery, rows[4].id, selected, null))
            }
            h.withReadAccess { access ->
                val page = assertNotNull(branchReportWorkflow(access, uiQuery(rows[4].id)).branchReport)
                assertEquals(selected.map { it.id }, page.documents.map { it.id })
                assertEquals(listOf(600L, 700L), page.documents.flatMap { it.amounts }.map { it.amount.minor })
                assertTrue(page.documents.all { it.cashSessionBusinessDate == null })
                assertEquals(reportRevision, page.branchRevision)
                assertEquals(reportObservedAt, page.observedAt)
                assertNull(page.nextAfter)
            }
            assertEquals(1, h.master.reads.size)
            assertTrue(h.master.mutations.isEmpty())
        }
    }

    @Test fun rrp001And002ChangedCutAndWrongSectionAreNotMergedOrReturned() = runTest {
        withWarehouseRpcFixture(LocalMasterRpcWorkflowProfile.REPORTS) { h ->
            h.master.view = FrozenWarehouseRpcV1.view(grants = listOf(
                FrozenWarehouseRpcV1.grant(SecurityAction.BRANCH_REPORT, PermissionVerb.PERFORM)))
            h.master.readReply = { reply(LocalBranchReportResultV1.Page(query().section, query().delivery,
                null, rows.take(5), rows[4].id)) }
            h.withReadAccess { access ->
                val failure = assertFailsWith<GatewayFailure> { branchReportWorkflow(access, uiQuery(revision = Revision(5100))) }
                assertEquals(LocalServiceProblem.REPORT_RESTART_REQUIRED, failure.problem)
            }
            h.master.readReply = { reply(LocalBranchReportResultV1.Page(LocalBranchReportSectionV1.PAID_SALES,
                query().delivery, null, emptyList(), null)) }
            h.withReadAccess { access -> assertFailsWith<GatewayFailure> { branchReportWorkflow(access, uiQuery()) } }
            assertTrue(h.master.mutations.isEmpty())
        }
    }
}
