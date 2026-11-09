@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.inventory.store.independent.stocktransferrpcworkflowv2

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.StockCustody
import com.ukkera.pos.inventory.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * Frozen stock-transfer-rpc-workflow-v2 SUPPORT ONLY handler/declaration slices.
 * These tests call the installed handler's canonical normalization, fixed-profile
 * classification and retained source-SKU requirement declaration. Contexts contain
 * unbound coordinates only; no grants, native originals, views, proposals, results,
 * commits or authenticated custody are supplied. Actual current authorization,
 * disclosure, no-effects vectors and whole frozen cases/groups remain unqualified.
 */
class StockTransferRpcV2HandlerSupportingTest {
    private val input get() = StockTransferRpcV2HandlerInputs

    private fun handler(): MasterCommandHandler = LocalStockRelationalServices(
        LocalStockRepository(), UnavailableLocalStockAuthority,
    ).commands.single { it.key == LocalStockRelationalProfileV2.COMMAND }

    /** S01-FIXED/S01-PROFILE: canonical positive body and trusted selection only. */
    @Test
    fun s01FrozenReleasedCompanyOwnedTransferSelectsOnlyTheFixedProfile() {
        val handler = handler()
        val request = input.transfer()
        val supplied = request.payload.copyBytes()
        val normalized = handler.normalize(request.payload)
        val decoded = assertIs<LocalStockRelationalCommand.Transfer>(LocalStockRelationalCommandsV2.decode(normalized))

        assertEquals(input.root.getValue("constants").jsonObject.text("execute_command_key"),
            "${request.command.name}/${request.command.version}")
        assertEquals(handler.key, request.command)
        assertEquals(request.payload, normalized)
        assertContentEquals(supplied, request.payload.copyBytes())
        assertEquals(input.operation, decoded.operationId)
        assertEquals(input.source, decoded.request.source)
        assertNull(decoded.request.source.supplierId)
        assertEquals(StockCustody.RELEASED, decoded.request.source.custody)
        assertEquals(input.id("W2"), decoded.request.destinationWarehouseId)
        assertEquals(Revision(7), decoded.request.expectedItemRevision)
        assertEquals(input.unit, decoded.request.quantity.unitId)
        assertEquals(2_500L, decoded.request.quantity.scaled)
        assertEquals(3, decoded.request.quantity.scale)
        assertEquals("kg", input.original.text("unit"))
        assertEquals("rebalance-display-stock", decoded.request.reasonCode)
        assertEquals(input.original.text("reason"), decoded.request.reasonCode)
        assertTrue(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS, normalized))
        for (other in LocalMasterRpcWorkflowProfile.entries.filterNot { it == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS }) {
            assertFalse(handler.supportsRpcWorkflow(other, normalized), other.name)
        }
    }

    /** S01-NONTRANSFER: every currently defined non-Transfer body's normalization/classification. */
    @Test
    fun s01ActualWellFormedNonTransferBodiesCannotSelectTheAttachment() {
        val handler = handler()
        for ((name, request) in input.nonTransfers()) {
            assertEquals(handler.key, request.command, name)
            val normalized = handler.normalize(request.payload)
            assertEquals(request.payload, normalized, name)
            assertFalse(LocalStockRelationalCommandsV2.decode(normalized) is LocalStockRelationalCommand.Transfer, name)
            assertFalse(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS, normalized), name)
        }
    }

    /** S01-FIXED: ownership/custody body exclusions, not authoritative stock safety. */
    @Test
    fun s01SupplierOwnedAndNonReleasedTransferBodiesAreExcluded() {
        val handler = handler()
        for ((name, source) in input.excludedSources()) {
            val request = input.transfer(source)
            val normalized = handler.normalize(request.payload)
            assertEquals(request.payload, normalized, name)
            assertIs<LocalStockRelationalCommand.Transfer>(LocalStockRelationalCommandsV2.decode(normalized))
            assertFalse(handler.supportsRpcWorkflow(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS, normalized), name)
        }
    }

    /** S01-LEGACY/S01-CARRIER: legacy key and legacy Transfer-body exclusion only. */
    @Test
    fun s01LegacyTransferKeysAndCarrierDoNotBecomeTheNormalizedAttachment() {
        val handler = handler()
        assertTrue(LocalMasterStockTransferRpcKeysV1.accepts(handler.key))
        for (legacy in listOf(LocalStockStoreProfile.TRANSFER, LocalStockStoreProfile.TRANSFER_SERIAL,
            LocalStockStoreProfile.TRANSFER_OWNED)) {
            assertFalse(LocalMasterStockTransferRpcKeysV1.accepts(legacy))
        }
        val legacy = LocalStockCommands.transfer(input.operation, input.transferRequest())
        assertEquals(legacy.payload, LocalStockCommands.normalize(legacy.command, legacy.payload))
        assertFalse(LocalMasterStockTransferRpcKeysV1.accepts(legacy.command))
        assertFailsWith<LocalStockRejected> { handler.normalize(legacy.payload) }
    }

    /** S06-QUANTITY/S06-NOMUTATION: declaration only; no actual grant-removal or result release. */
    @Test
    fun s06RetainedDeclarationBindsOriginalSourceQuantityWithoutFreshAdjustment() {
        val handler = handler()
        val provider = assertIs<LocalMasterRpcRetainedResultAccessProvider>(handler)
        val normalized = handler.normalize(input.transfer().payload)
        val digest = semanticDigest(input.scope, input.actor, handler.key, normalized)
        val requirements = provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS,
            normalized, input.context(digest))
        val quantity = requirements.filter {
            it.permission == Permission(SecurityAction.INVENTORY_QUANTITY_READ, PermissionVerb.PERFORM)
        }
        assertTrue(quantity.isNotEmpty())
        for (requirement in quantity) {
            assertEquals(input.scope, requirement.scope)
            val binding = assertNotNull(requirement.binding)
            assertEquals(input.id("SKU1"), binding.documentId)
            assertEquals(Revision(7), binding.revision)
            assertEquals(digest, binding.payloadDigest)
            assertEquals(input.operation, binding.operationId)
        }
        assertTrue(requirements.none { it.permission.action == SecurityAction.INVENTORY_ADJUST })
        // The separate kernel recovery/custody/current-access gates are not exercised here.
    }

    /** S03-MISMATCH/S06-OTHERACTOR: original-digest coordinate mismatch refusal, not custody proof. */
    @Test
    fun s03s06ChangedBodyOrForeignOriginalContextHasNoDisclosureDeclaration() {
        val handler = handler()
        val provider = assertIs<LocalMasterRpcRetainedResultAccessProvider>(handler)
        val normalized = handler.normalize(input.transfer().payload)
        val digest = semanticDigest(input.scope, input.actor, handler.key, normalized)
        val contexts = listOf(
            "INTENT-OPERATION" to input.context(digest, operation = OperationId(input.id("OP_OTHER").value)),
            "INTENT-KEY" to input.context(digest, key = LocalStockStoreProfile.TRANSFER_OWNED),
            "AUTH-OTHERACTOR" to input.context(digest, actor = input.id("E2")),
            "AUTH-BRANCH" to input.context(digest, scope = BranchScope(input.id("O1"), input.id("C1"), input.id("B2"))),
            "AUTH-COMPANY" to input.context(digest, scope = BranchScope(input.id("O1"), input.id("C2"), input.id("B3"))),
        )
        for ((name, context) in contexts) {
            assertFailsWith<LocalStockRejected>(name) {
                provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS, normalized, context)
            }
        }
        for ((name, changed) in listOf(
            "INTENT-BODY" to input.transferRequest().copy(quantity = input.quantity("1.000")),
            "INTENT-REVISION" to input.transferRequest().copy(expectedItemRevision = Revision(8)),
        )) {
            val changedBody = handler.normalize(LocalStockRelationalCommandsV2.transfer(input.operation, changed).payload)
            assertFailsWith<LocalStockRejected>(name) {
                provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS,
                    changedBody, input.context(digest))
            }
        }
        for (other in LocalMasterRpcWorkflowProfile.entries.filterNot { it == LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS }) {
            assertFailsWith<LocalStockRejected>("INTENT-PROFILE:${other.name}") {
                provider.rpcRetainedResultRequirements(other, normalized, input.context(digest))
            }
        }
        // Foreign coordinates retain the original digest; rehashed coordinates are not authenticated originals.
    }

    /** S01-CARRIER/S01-NONTRANSFER: malformed or excluded bodies produce no declaration. */
    @Test
    fun s01MalformedAndExcludedBodiesCannotDeclareRetainedTransferAccess() {
        val handler = handler()
        val provider = assertIs<LocalMasterRpcRetainedResultAccessProvider>(handler)
        val normalized = handler.normalize(input.transfer().payload)
        val digest = semanticDigest(input.scope, input.actor, handler.key, normalized)
        val malformed = listOf(
            "WIRE-TRUNCATED" to StoredBytes(normalized.copyBytes().let { it.copyOf(it.size - 1) }),
            "WIRE-IDENTIFIER" to StoredBytes(normalized.copyBytes().decodeToString()
                .replace(input.id("SKU1").value, "not-a-domain-id").encodeToByteArray()),
        )
        for ((name, body) in malformed) {
            assertFailsWith<LocalStockRejected>(name) { handler.normalize(body) }
            assertFailsWith<LocalStockRejected>(name) {
                provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS,
                    body, input.context(digest))
            }
        }
        val excluded = input.nonTransfers() + input.excludedSources().map { (name, source) -> name to input.transfer(source) }
        for ((name, request) in excluded) {
            val body = handler.normalize(request.payload)
            val bodyDigest = semanticDigest(input.scope, input.actor, handler.key, body)
            assertFailsWith<LocalStockRejected>(name) {
                provider.rpcRetainedResultRequirements(LocalMasterRpcWorkflowProfile.STOCK_TRANSFERS,
                    body, input.context(bodyDigest))
            }
        }
    }
}

private object StockTransferRpcV2HandlerInputs {
    private val directory = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/stock-transfer-rpc-workflow-v2") }
        .firstOrNull { Files.isRegularFile(it.resolve("fixtures.json")) }
        ?: error("Frozen stock-transfer workflow v2 inputs are unavailable")
    val root = Json.parseToJsonElement(Files.readString(directory.resolve("fixtures.json"))).jsonObject.also {
        assertEquals("stock-transfer-rpc-workflow-v2", it.text("design_version"))
        assertEquals("FROZEN", it.text("freeze_state"))
    }
    private val base = root.getValue("fixtures").jsonObject.getValue("F-ST-RPC-BASE").jsonObject
    private val aliases = base.getValue("aliases").jsonObject
    val original = base.getValue("original_operation").jsonObject
    fun id(alias: String) = DomainId(aliases.text(alias))
    val scope = BranchScope(id("O1"), id("C1"), id(original.text("branch")))
    val actor = id(original.text("actor"))
    val operation = OperationId(id(original.text("operation")).value)
    private val reason = original.text("reason")
    private val revision = Revision(original.text("source_revision").toLong())
    // Omitted carrier identifiers bind syntax only, never catalog authority or original history.
    private fun carrierId(label: String) = DomainId(UUID.nameUUIDFromBytes(
        "stock-transfer-rpc-support-v2:$label".encodeToByteArray()).toString())
    val unit = carrierId("unit-kg")
    private val scale = base.getValue("sku").jsonObject.getValue("allowed_fraction_digits").jsonPrimitive.int
    fun quantity(text: String) = Quantity.parse(unit, text, scale)
    val source = LocalStockPositionRef(id(original.text("source_sku")), id(original.text("source_warehouse")),
        id(original.text("source_lot")), StockCustody.RELEASED)
    fun transferRequest(source: LocalStockPositionRef = this.source) = LocalWarehouseTransferRequest(
        carrierId("transfer"), carrierId("source-event"), carrierId("destination-event"), source,
        id(original.text("destination")), revision, quantity(original.text("quantity")), reason)
    fun transfer(source: LocalStockPositionRef = this.source) = LocalStockRelationalCommandsV2.transfer(operation, transferRequest(source))
    fun excludedSources() = listOf(
        "INPUT-SUPPLIERCUSTODY" to source.copy(supplierId = carrierId("supplier")),
        "QUARANTINE" to source.copy(custody = StockCustody.QUARANTINE),
        "REJECTED_HELD" to source.copy(custody = StockCustody.REJECTED_HELD),
    )

    // Internal context construction is an unbound data seam, not server attribution or authorization.
    fun context(digest: PayloadDigest, scope: BranchScope = this.scope, actor: DomainId = this.actor,
        operation: OperationId = this.operation, key: CommandKey = LocalStockRelationalProfileV2.COMMAND) =
        CommandContext(scope, actor, operation, key, digest)

    fun nonTransfers(): List<Pair<String, MutationRequest>> {
        val quantity = quantity(original.text("quantity"))
        val sourceInput = base.getValue("source_position").jsonObject
        val expiry = UtcInstant.parseCanonical(sourceInput.text("expiry_utc"))
        val receipt = LocalGoodsReceiptRequest(carrierId("receipt"), carrierId("basis"), Revision(1),
            listOf(LocalStockItemVersion(id("SKU1"), revision)), listOf(LocalGoodsReceiptLine(
                carrierId("line"), carrierId("allocation"), carrierId("receipt-source"), id("SKU1"), id("W1"),
                quantity, null, id("LOT1"), expiry, LocalReceiptDisposition.RELEASED,
                Money(Currency.EGP, base.getValue("expected_transfer").jsonObject.getValue("moved").jsonObject
                    .text("carrying_value_minor").toLong()))), reason)
        val po = LocalPoReceiptRequestV1(carrierId("receipt"), carrierId("order"), Revision(1), Revision(1),
            listOf(LocalStockItemVersion(id("SKU1"), revision)), listOf(LocalPoAllowanceVersionV1(carrierId("allocation"), Revision(1))),
            listOf(LocalPoReceiptLineV1(carrierId("line"), carrierId("allocation"), carrierId("receipt-source"),
                id("SKU1"), id("W1"), quantity, id("LOT1"), expiry, LocalReceiptDisposition.RELEASED)), reason)
        fun request(body: LocalStockRelationalCommand) = MutationRequest(operation,
            LocalStockRelationalProfileV2.COMMAND, LocalStockRelationalCommandsV2.encode(body))
        return listOf(
            "InitializeFresh" to LocalStockRelationalCommandsV2.initializeFresh(operation, carrierId("initialization"), reason),
            "Receive-opening" to LocalStockRelationalCommandsV2.opening(operation, receipt),
            "Receive" to LocalStockRelationalCommandsV2.receive(operation, receipt),
            "ReceivePo" to LocalStockRelationalCommandsV2.receivePo(operation, po),
            "Inspect" to LocalStockRelationalCommandsV2.inspect(operation, LocalInspectionReleaseRequest(
                carrierId("inspection"), carrierId("receipt"), carrierId("line"), Revision(1), revision,
                quantity, carrierId("evidence"), reason)),
            "CaptureCount" to LocalStockRelationalCommandsV2.captureCount(operation,
                LocalCountCaptureRequest(carrierId("count"), source, revision, reason)),
            "PostCount" to LocalStockRelationalCommandsV2.postCount(operation,
                LocalCountPostRequest(carrierId("count"), Revision(1), quantity, reason)),
            "Recall" to LocalStockRelationalCommandsV2.recall(operation,
                LocalLotRecallRequest(id("SKU1"), id("LOT1"), revision, reason)),
            "Expire" to request(LocalStockRelationalCommand.Expire(carrierId("sweep"), reason)),
        )
    }
}

private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
