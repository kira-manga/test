package com.ukkera.pos.app.gateway.independent.retailcustomerreceiptbatchv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import kotlin.test.*
import kotlin.time.Instant

/**
 * Logical values come from the unchanged receipt-batch v1 freeze (reviewed v1.1).
 * Supplemental UUIDs bind logical units and controlled port identities only.
 */
internal object ReceiptBatchFrozen {
    private const val FREEZE = "qa/pos/freezes/retail-customer-receipt-batch-editor-v1/fixtures.json"
    val fixtures: JsonObject by lazy {
        val file = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve(FREEZE) }.firstOrNull { Files.isRegularFile(it) }
            ?: error("Missing frozen receipt batch fixtures: " + FREEZE)
        Json.parseToJsonElement(Files.readString(file)).jsonObject
    }
    val wire = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false; coerceInputValues = false }
    val main get() = fixtures.obj("S0")
    val i2 get() = fixtures.obj("I2")
    val expected get() = i2.obj("expected")
    val operation get() = OperationId(i2.text("originalOperationId"))
    val actor get() = DomainId(i2.text("trustedOriginalActorId"))
    val scope = BranchScope(bindingId("company"), bindingId("branch"), bindingId("activity"))
    val device = bindingId("device")

    // Public behavior binding: LOCAL-RETAIL-CUSTOMER-RECEIPT-RESULT-CODES.md.
    const val COMMITTED_CODE = "committed"
    const val REFUSED_CODE = "sales.customer_receipt.refused"

    fun bindingId(name: String) = DomainId(UUID.nameUUIDFromBytes(
        ("receipt-batch-v1:" + name).encodeToByteArray()).toString())
    fun unitId(name: String) = bindingId("base-unit:" + name)
    fun instant(text: String) = UtcInstant(Instant.parse(text))
    fun scaled(value: String, scale: Int): Long = BigDecimal(value).setScale(scale).unscaledValue().longValueExact()
    fun quantity(unit: String, scale: Int, value: String) = Quantity(unitId(unit), scaled(value, scale), scale)

    fun variant(id: String): String {
        require(fixtures.array("caseDefinitions").any { definition ->
            definition.jsonObject.array("variantIds").any { it.jsonPrimitive.content == id }
        }) { "Variant is absent from the frozen specification: " + id }
        return id
    }

    fun mainScenario(name: String = "S0"): ReceiptBatchScenario {
        val source = fixtures.obj(name)
        val original = source.obj("originalEvidence")
        val portions = original.array("portions").map { row ->
            row.jsonObject.let { ReceiptBatchOriginal(DomainId(it.text("portionId")),
                it.text("baseUnit"), it.int("quantityScale"), it.text("soldQuantity")) }
        }
        val head = source.obj("head")
        return ReceiptBatchScenario(DomainId(source.text("saleId")), DomainId(source.text("collectorId")),
            instant(original.text("paidFinalizeAt")), portions, head.text("revision").toLong(), head.int("eventCount"),
            head.obj("cumulativeQuantities"), head["latestOperationId"]?.jsonPrimitive?.content?.let(::OperationId))
    }

    fun directory(name: String): ReceiptBatchScenario {
        val recipe = fixtures.obj("directoryFixtures").obj(name).obj("recipe")
        return ReceiptBatchScenario(DomainId(recipe.text("saleId")), DomainId(recipe.text("collectorId")),
            instant(recipe.text("paidFinalizeAt")), recipe.array("canonicalIds").map {
                ReceiptBatchOriginal(DomainId(it.jsonPrimitive.content), recipe.text("allBaseUnits"),
                    recipe.int("allQuantityScales"), recipe.text("allSoldQuantities"))
            }, recipe.text("observedHeadRevision").toLong(), 0,
            JsonObject(recipe.array("canonicalIds").associate { it.jsonPrimitive.content to JsonPrimitive(recipe.text("allCumulativeQuantities")) }),
            null)
    }

    fun directoryInput(name: String, rows: List<DomainId>): JsonObject {
        val recipe = fixtures.obj("directoryFixtures").obj(name).obj("recipe")
        return buildJsonObject {
            put("formVersion", 2)
            putJsonObject("logicalFields") {
                put("SALE_ID", recipe.text("saleId"))
                put("EXPECTED_CUSTOMER_RECEIPT_REVISION", recipe.text("observedHeadRevision"))
                put("CUSTOMER_RECEIVED_AT", recipe.text("explicitObservationTime"))
                put("CUSTOMER_RECEIPT_TIME_ZONE", recipe.text("explicitObservationTimeZone"))
                putJsonArray("LINES") {
                    rows.forEach { id -> add(buildJsonObject {
                        put("PORTION_ID", id.value); put("UNIT", recipe.text("allBaseUnits"))
                        put("QUANTITY_SCALE", recipe.int("allQuantityScales").toString())
                        put("QUANTITY", recipe.text("directInputQuantityPerRow"))
                    }) }
                }
            }
        }
    }

    fun paging(fullyObserved: Boolean = false): ReceiptBatchScenario {
        val source = fixtures.obj("P33")
        val recipe = source.obj("originalRecipe")
        val portions = source.array("canonicalIds").map {
            val id = it.jsonPrimitive.content
            val overrideFields = recipe.obj("overrides")[id]?.jsonObject
            ReceiptBatchOriginal(DomainId(id), overrideFields?.text("baseUnit") ?: recipe.text("defaultBaseUnit"),
                overrideFields?.int("scale") ?: recipe.int("defaultScale"),
                overrideFields?.text("soldQuantity") ?: recipe.text("defaultSoldQuantity"))
        }
        val full = source.obj("fullyObservedPageVariant")
        val cumulative = recipe.obj("cumulativeOverrides").toMutableMap()
        if (fullyObserved) cumulative.putAll(full.obj("cumulativeOverrides"))
        return ReceiptBatchScenario(DomainId(source.text("saleId")), DomainId(source.text("collectorId")),
            instant(source.text("paidFinalizeAt")), portions,
            (if (fullyObserved) full else source).text("headRevision").toLong(),
            (if (fullyObserved) full else source).int("eventCount"),
            JsonObject(cumulative), OperationId(if (fullyObserved) full.obj("appendEvent").text("operationId")
                else source.obj("priorSingleEvent").text("operationId")))
    }

    fun eventCap(name: String): ReceiptBatchScenario {
        val source = fixtures.obj("eventCapacityFixtures").obj(name)
        val id = source.text("originalPortionId")
        return ReceiptBatchScenario(DomainId(source.text("saleId")), DomainId(source.text("collectorId")),
            instant(source.text("paidFinalizeAt")),
            listOf(ReceiptBatchOriginal(DomainId(id), source.text("baseUnit"), source.int("scale"), source.text("soldQuantity"))),
            source.text("headRevision").toLong(), source.int("eventCount"),
            buildJsonObject { put(id, source.text("cumulativeQuantity")) },
            OperationId(source.obj("authorizedInspection").text("latestEventOperationId")))
    }

    fun postCommitHead(scenario: ReceiptBatchScenario): LocalRetailCustomerReceiptReadResultV1 {
        val post = expected.obj("postCommit")
        return scenario.copy(revision = post.text("headRevision").toLong(), eventCount = post.int("eventCount"),
            cumulative = post.obj("cumulativeQuantities"), latest = operation).head()
    }

    fun mutate(input: JsonObject, mutation: JsonObject): JsonObject {
        val path = mutation.text("path").removePrefix("logicalFields.")
        val root = input.obj("logicalFields").toMutableMap()
        if (path.startsWith("LINES[0].")) {
            val rows = root.getValue("LINES").jsonArray.toMutableList()
            val line = rows[0].jsonObject.toMutableMap()
            replace(line, path.removePrefix("LINES[0]."), mutation)
            rows[0] = JsonObject(line)
            root["LINES"] = JsonArray(rows)
        } else replace(root, path, mutation)
        return JsonObject(input.toMutableMap().also { it["logicalFields"] = JsonObject(root) })
    }

    private fun replace(values: MutableMap<String, JsonElement>, key: String, mutation: JsonObject) {
        if (mutation.text("operation") == "REMOVE") values.remove(key)
        else values[key] = mutation.getValue("value")
    }

    /**
     * Malformed lexical values use the public Text scalar where their typed
     * constructor cannot represent them. That proves wrong-kind gateway refusal;
     * lexical constructor refusals are asserted and reported separately.
     * Unrecognized logical field names are deliberately not remapped.
     */
    fun intent(input: JsonObject = i2.obj("input"), reference: LocalRecordReference? = mainScenario().batchReference()): LocalCommandIntent {
        val fields = input.obj("logicalFields")
        val linesValue = fields["LINES"]
        val root = fields.filterKeys { it != "LINES" }.map { (name, value) ->
            scalar(LocalField.valueOf(name), value.jsonPrimitive.content)
        }.toMutableList()
        val rows = (linesValue as? JsonArray)?.mapIndexed { index, item ->
            val row = item.jsonObject
            val supplied = row["PORTION_ID"]?.jsonPrimitive?.content
            val rowId = supplied?.let { runCatching { DomainId(it) }.getOrNull() }
                ?: DomainId(i2.obj("input").obj("logicalFields").array("LINES")[index].jsonObject.text("PORTION_ID"))
            LocalInputLine(rowId, row.map { (name, value) -> scalar(LocalField.valueOf(name), value.jsonPrimitive.content) })
        } ?: emptyList()
        if (linesValue != null && linesValue !is JsonArray) root += LocalScalarInput(LocalField.LINES, LocalScalarValue.Text(linesValue.toString()))
        return LocalCommandIntent(operation, LocalAction.RETAIL_CUSTOMER_RECEIPT, input.int("formVersion"), reference,
            root, if (linesValue is JsonArray) LocalField.LINES else null, rows)
    }

    private fun scalar(field: LocalField, text: String): LocalScalarInput {
        val value: LocalScalarValue = when (field) {
            LocalField.SALE_ID, LocalField.PORTION_ID -> runCatching { LocalScalarValue.Identifier(DomainId(text)) }
                .getOrElse { LocalScalarValue.Text(text) }
            LocalField.UNIT -> if (text.isEmpty()) LocalScalarValue.Text(text) else LocalScalarValue.Identifier(unitId(text))
            LocalField.EXPECTED_CUSTOMER_RECEIPT_REVISION, LocalField.QUANTITY_SCALE ->
                text.toLongOrNull()?.let(LocalScalarValue::Integer) ?: LocalScalarValue.Text(text)
            LocalField.QUANTITY -> runCatching { LocalScalarValue.Decimal(text) }.getOrElse { LocalScalarValue.Text(text) }
            LocalField.CUSTOMER_RECEIPT_TIME_ZONE -> runCatching { LocalScalarValue.Choice(text) }.getOrElse { LocalScalarValue.Text(text) }
            else -> LocalScalarValue.Text(text)
        }
        return LocalScalarInput(field, value)
    }

    fun singleInput(): JsonObject {
        val single = fixtures.obj("S1").obj("logicalSinglePortion")
        return buildJsonObject {
            put("formVersion", fixtures.obj("S1").int("applicationFormVersion"))
            putJsonObject("logicalFields") {
                put("SALE_ID", single.text("saleId")); put("PORTION_ID", single.text("portionId"))
                put("UNIT", single.text("baseUnit")); put("QUANTITY_SCALE", single.int("quantityScale").toString())
                put("QUANTITY", single.text("quantity")); put("EXPECTED_CUSTOMER_RECEIPT_REVISION", single.text("expectedHeadRevision"))
                put("CUSTOMER_RECEIVED_AT", single.text("receivedAt")); put("CUSTOMER_RECEIPT_TIME_ZONE", single.text("receiptTimeZoneId"))
            }
        }
    }

    fun resultPayload(): StoredBytes {
        val result = expected.obj("committedResult")
        return StoredBytes(wire.encodeToString(LocalRetailCustomerReceiptResultV1.serializer(),
            LocalRetailCustomerReceiptResultV1.Applied(DomainId(result.text("saleId")), OperationId(result.text("operationId")),
                Revision(result.text("headRevision").toLong()), instant(result.text("receivedAt")),
                result.text("receiptTimeZoneId"), instant(result.text("recordedAt")))).encodeToByteArray())
    }

    fun reply(payload: StoredBytes = resultPayload(), committed: Boolean = true, code: String = COMMITTED_CODE,
        operationId: OperationId = operation, actorId: DomainId = actor, replayed: Boolean = false) =
        MutationReply.Recorded(operationId, actorId, committed, code, payload, Revision(1), JournalSequence(1),
            instant(expected.obj("committedResult").text("recordedAt")), replayed)

    fun event(scenario: ReceiptBatchScenario, id: OperationId): LocalRetailCustomerReceiptEventViewV1? {
        val current = expected.obj("protectedEvent")
        val prior = main.array("priorEvents").map { it.jsonObject }.firstOrNull { it.text("operationId") == id.value }
        val cap = fixtures.obj("eventCapacityFixtures").values.map { it.jsonObject }
            .firstOrNull { it.text("saleId") == scenario.sale.value && it.obj("eventRecipe").array("operationIds").any { op -> op.jsonPrimitive.content == id.value } }
        val quantities: List<LocalRetailCustomerReceiptPortionV1>
        val received: String
        val zone: String
        val recorded: String
        val revision: Long
        when {
            scenario.sale.value == current.text("saleId") && id == operation -> {
                quantities = current.obj("exactPortionQuantities").map { (portion, value) ->
                    value.jsonObject.let { LocalRetailCustomerReceiptPortionV1(DomainId(portion), quantity(it.text("baseUnit"), it.int("scale"), it.text("value"))) }
                }
                received = current.text("receivedAt"); zone = current.text("receiptTimeZoneId")
                recorded = current.text("recordedAt"); revision = expected.obj("committedResult").text("headRevision").toLong()
            }
            prior != null && scenario.sale == mainScenario().sale -> {
                quantities = prior.obj("quantities").map { (portion, value) ->
                    scenario.portions.single { it.id.value == portion }.typed(value.jsonPrimitive.content)
                }
                received = prior.text("receivedAt"); zone = prior.text("receiptTimeZoneId")
                recorded = prior.text("recordedAt"); revision = main.array("priorEvents").indexOf(prior) + 1L
            }
            cap != null -> {
                val recipe = cap.obj("eventRecipe")
                quantities = listOf(scenario.portions.single().typed(recipe.text("quantityPerEvent")))
                received = recipe.text("receivedAt"); zone = recipe.text("receiptTimeZoneId")
                recorded = expected.obj("committedResult").text("recordedAt")
                revision = recipe.array("operationIds").indexOf(JsonPrimitive(id.value)) + 1L
            }
            else -> return null
        }
        return LocalRetailCustomerReceiptEventViewV1(scenario.sale, id, Revision(revision),
            LocalRetailCustomerReceiptObservationV1.CUSTOMER_RECEIVED_AT_COUNTER, quantities, instant(received), zone, instant(recorded),
            scenario.collector, scenario.paidAt, scenario.collector, OperationId(bindingId("source-finalize").value), scenario.collector,
            sha256("controlled original source".encodeToByteArray()), sha256(id.value.encodeToByteArray()))
    }
}

internal fun JsonObject.obj(key: String) = getValue(key).jsonObject
internal fun JsonObject.array(key: String) = getValue(key).jsonArray
internal fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
internal fun JsonObject.int(key: String) = text(key).toInt()

internal data class ReceiptBatchOriginal(val id: DomainId, val unit: String, val scale: Int, val sold: String) {
    fun typed(value: String = sold) = LocalRetailCustomerReceiptPortionV1(id, ReceiptBatchFrozen.quantity(unit, scale, value))
}

internal data class ReceiptBatchScenario(val sale: DomainId, val collector: DomainId, val paidAt: UtcInstant,
    val portions: List<ReceiptBatchOriginal>, val revision: Long, val eventCount: Int,
    val cumulative: JsonObject, val latest: OperationId?) {
    fun original() = LocalRetailCustomerReceiptOriginalV1(sale, collector, paidAt, portions.map { it.typed() })
    fun head(): LocalRetailCustomerReceiptReadResultV1 = if (eventCount == 0) LocalRetailCustomerReceiptReadResultV1.NotRecorded(original())
        else LocalRetailCustomerReceiptReadResultV1.Head(LocalRetailCustomerReceiptHeadViewV1(original(), Revision(revision), eventCount,
            portions.mapNotNull { row -> cumulative[row.id.value]?.jsonPrimitive?.content?.takeIf { BigDecimal(it).signum() > 0 }?.let(row::typed) },
            requireNotNull(latest)))
    fun headReference() = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT, sale)
    fun batchReference(first: DomainId = portions.first().id) = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_BATCH, first, Revision(revision), sale)
    fun singleReference(id: DomainId = portions.first().id) = LocalRecordReference(LocalRecordKind.CUSTOMER_RECEIPT_PORTION, id, Revision(revision), sale)
}

/** Real shared gateway, controlled endpoint and model journal; no native or Master qualification. */
internal class ReceiptBatchGateway(val scenario: ReceiptBatchScenario = ReceiptBatchFrozen.mainScenario(),
    commandInstalled: Boolean = true, record: Boolean = true, lookup: Boolean = true,
    val journal: ReceiptBatchModelJournal = ReceiptBatchModelJournal(LocalClientIdentity(ReceiptBatchFrozen.scope, scenario.collector, ReceiptBatchFrozen.device))) {
    val endpoint = ReceiptBatchEndpoint(scenario, record, lookup)
    var currentLifetime = true
    private val source = LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(ReceiptBatchFrozen.bindingId("session"), OpaqueCredential(ByteArray(16) { 9 }))) { currentLifetime }
    }
    private val installed = LocalWorkflowInstallation(if (commandInstalled) setOf(LocalRetailCustomerReceiptCodec.COMMAND) else emptySet(),
        setOf(LocalRetailCustomerReceiptReadCodec.QUERY))
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, source, installed)
    fun reopenedGateway(journal: ReceiptBatchModelJournal = this.journal) = LocalEndpointWorkflowGateway(endpoint, journal, source, installed)
}

internal class ReceiptBatchModelJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    val entries = linkedMapOf<OperationId, StoredClientIntentV1>()
    var stageCalls = 0
    override suspend fun find(operationId: OperationId) = JournalRead.Value(entries[operationId])
    override suspend fun pending() = JournalRead.Value(entries.values.filter { !it.acknowledged })
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite {
        stageCalls++
        intent.validate(owner)
        val previous = entries[intent.operationId]
        if (previous != null && !previous.sameIntent(intent)) return JournalWrite.IDENTITY_CONFLICT
        entries[intent.operationId] = intent
        return JournalWrite.DURABLE
    }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite {
        val old = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        entries[operationId] = old.copy(phase = LocalPendingPhase.DISPATCH_UNRESOLVED.name)
        return JournalWrite.DURABLE
    }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        val old = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (old.recorded != null && old.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        entries[operationId] = old.copy(phase = LocalPendingPhase.RECORDED_UNACKNOWLEDGED.name, recorded = result)
        return JournalWrite.DURABLE
    }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite {
        val old = entries[operationId] ?: return JournalWrite.UNAVAILABLE
        if (old.recorded != result) return JournalWrite.IDENTITY_CONFLICT
        entries[operationId] = old.copy(acknowledged = true)
        return JournalWrite.DURABLE
    }
    fun modelRoundTrip(): ReceiptBatchModelJournal = ReceiptBatchModelJournal(owner).also { restored ->
        entries.forEach { (id, entry) ->
            val decoded = journalJson.decodeFromString(StoredClientIntentV1.serializer(),
                journalJson.encodeToString(StoredClientIntentV1.serializer(), entry))
            decoded.validate(owner)
            restored.entries[id] = decoded
        }
    }
    override suspend fun close() = Unit
}

internal class ReceiptBatchEndpoint(private val scenario: ReceiptBatchScenario, record: Boolean, lookup: Boolean) : LocalMasterClient {
    override val health = MutableStateFlow(LocalMasterEndpointHealth.ACTIVE)
    var actor = scenario.collector
    var currentDenied = false
    var head: LocalRetailCustomerReceiptReadResultV1 = scenario.head()
    var rawHead: StoredBytes? = null
    var readFailure: ReadFailure? = null
    var denyRecordReads = false
    var lookupFailure: DeferredReason? = null
    var found: MutationReply.Recorded? = null
    var executeReply: MutationReply = MutationReply.Deferred(ReceiptBatchFrozen.operation, DeferredReason.WRITER_NOT_QUALIFIED)
    var executeFailure: Throwable? = null
    var grants = buildList {
        if (record) add(PermissionGrant(Permission(SecurityAction.CUSTOMER_RECEIPT_RECORD, PermissionVerb.PERFORM),
            ReceiptBatchFrozen.scope, GrantEffect.ALLOW, ownerAccess = OwnerAccess.OWN_ONLY))
        if (lookup) add(PermissionGrant(Permission(SecurityAction.RETURN_RECEIPT_LOOKUP, PermissionVerb.PERFORM),
            ReceiptBatchFrozen.scope, GrantEffect.ALLOW))
    }
    val queries = mutableListOf<LocalRetailCustomerReceiptQueryV1>()
    val executions = mutableListOf<MutationRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult {
        if (currentDenied) return LocalSessionViewResult.Denied
        return LocalSessionViewResult.Current(LocalSessionView(actor, ReceiptBatchFrozen.scope, ReceiptBatchFrozen.device,
            ReceiptBatchFrozen.bindingId("connection"), SessionGeneration(1), LocalLoginIdentifier("synthetic-batch-observer"),
            ActivatedLocalPolicy(ReceiptBatchFrozen.scope, actor, Revision(1), grants, LocalSessionPolicy(), ThrottlePolicy()),
            MonotonicMoment(ReceiptBatchFrozen.bindingId("clock"), 10_000), MonotonicMoment(ReceiptBatchFrozen.bindingId("clock"), 20_000)))
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        if (request.query != LocalRetailCustomerReceiptReadCodec.QUERY) return ReadReply.Unavailable(ReadFailure.UNKNOWN_QUERY)
        val query = ReceiptBatchFrozen.wire.decodeFromString(LocalRetailCustomerReceiptQueryV1.serializer(), request.payload.copyBytes().decodeToString())
        queries += query
        if (denyRecordReads && when (query) {
            is LocalRetailCustomerReceiptQueryV1.Head -> query.access == LocalRetailCustomerReceiptReadAccessV1.RECORD
            is LocalRetailCustomerReceiptQueryV1.Event -> query.access == LocalRetailCustomerReceiptReadAccessV1.RECORD
        }) return ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
        readFailure?.let { return ReadReply.Unavailable(it) }
        val value = when (query) {
            is LocalRetailCustomerReceiptQueryV1.Head -> if (query.saleId == scenario.sale) head else LocalRetailCustomerReceiptReadResultV1.NotAvailable
            is LocalRetailCustomerReceiptQueryV1.Event -> if (query.saleId != scenario.sale) LocalRetailCustomerReceiptReadResultV1.NotAvailable
                else ReceiptBatchFrozen.event(scenario, query.operationId)?.let(LocalRetailCustomerReceiptReadResultV1::Event)
                    ?: LocalRetailCustomerReceiptReadResultV1.EventNotRecorded(query.saleId, query.operationId)
        }
        val payload = if (query is LocalRetailCustomerReceiptQueryV1.Head) rawHead else null
        return ReadReply.Data(payload ?: StoredBytes(ReceiptBatchFrozen.wire.encodeToString(
            LocalRetailCustomerReceiptReadResultV1.serializer(), value).encodeToByteArray()), Revision(1),
            ReceiptBatchFrozen.instant(ReceiptBatchFrozen.expected.obj("committedResult").text("recordedAt")))
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executions += request
        executeFailure?.let { throw it }
        return executeReply
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        lookupFailure?.let { return LookupReply.Deferred(identity.operationId, it) }
        return found?.let(LookupReply::Found) ?: LookupReply.AbsentAtCurrentWriter(identity.operationId)
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = LocalSignOutResult.Unavailable
}

internal suspend fun ReceiptBatchGateway.page(reference: LocalRecordReference = scenario.headReference(),
    workflow: LocalWorkflow = LocalWorkflow.ORDERS, cursor: String? = null): LocalWorkflowPage =
    assertIs<LocalReadResult.Available>(gateway.read(LocalWorkflowQuery(workflow, reference = reference, cursor = cursor))).page

internal fun ReceiptBatchGateway.assertNoStage(id: String, result: LocalStageResult) {
    assertIs<LocalStageResult.NotStaged>(result, id)
    assertEquals(0, journal.stageCalls, id)
    assertTrue(journal.entries.isEmpty(), id)
    assertTrue(endpoint.executions.isEmpty(), id)
}

internal fun assertExactQuantity(id: String, actual: Quantity, expected: JsonObject) {
    assertEquals(ReceiptBatchFrozen.unitId(expected.text("baseUnit")), actual.unitId, id)
    assertEquals(expected.int("scale"), actual.scale, id)
    assertEquals(ReceiptBatchFrozen.scaled(expected.text("value"), expected.int("scale")), actual.scaled, id)
}

internal fun assertFrozenRequest(id: String, entry: StoredClientIntentV1, expected: JsonObject = ReceiptBatchFrozen.expected.obj("originalRequest")) {
    val mutation = entry.request()
    assertEquals(ReceiptBatchFrozen.operation, mutation.operationId, id)
    assertEquals(ReceiptBatchFrozen.actor, entry.owner.actorId, id)
    assertEquals(expected.text("command"), mutation.command.name, id)
    assertEquals(expected.int("commandVersion"), mutation.command.version, id)
    val body = LocalRetailCustomerReceiptCodec.decode(mutation.payload)
    assertEquals(expected.text("saleId"), body.saleId.value, id)
    assertEquals(expected.text("expectedHeadRevision").toLong(), body.expectedHeadRevision.value, id)
    assertEquals(expected.text("observation"), body.observation.name, id)
    assertEquals(ReceiptBatchFrozen.instant(expected.text("receivedAt")), body.receivedAt, id)
    assertEquals(expected.text("receiptTimeZoneId"), body.receiptTimeZoneId, id)
    assertEquals(expected.array("canonicalPortionIds").map { it.jsonPrimitive.content }, body.portions.map { it.portionId.value }, id)
    body.portions.forEach { row -> assertExactQuantity(id, row.quantity, expected.obj("exactPortionQuantities").obj(row.portionId.value)) }
}

/** Accept layout changes while requiring every identity/quantity exactly once in each language. */
internal fun assertCompleteSummary(id: String, summary: List<LocalRecordValue>, expectedRows: List<ReceiptBatchOriginal>, quantities: Map<DomainId, String>) {
    val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    // The freeze requires exact decimal scale and unit identity, not a separator glyph.
    val quantityAndUnit = Regex("(-?[0-9]+(?:\\.[0-9]+)?)[ \\t]+(?:·[ \\t]+)?(" + uuid.pattern + ")")
    for (arabic in listOf(false, true)) {
        val rows = mutableListOf<Pair<String, String>>()
        summary.forEach { item ->
            val label = if (arabic) item.label.arabic else item.label.english
            val value = if (arabic) item.value.arabic else item.value.english
            if (uuid.matches(label)) rows += label to value
            else value.lineSequence().forEach { line ->
                val coordinate = line.substringBefore(": ")
                if (uuid.matches(coordinate) && line.contains(": ")) rows += coordinate to line.substringAfter(": ")
            }
        }
        assertEquals(expectedRows.map { it.id.value }, rows.map { it.first }, id)
        expectedRows.zip(rows).forEach { (expected, actual) ->
            val presented = assertNotNull(quantityAndUnit.matchEntire(actual.second), id + ": malformed summary quantity/unit")
            val decimal = BigDecimal(quantities.getValue(expected.id)).setScale(expected.scale).toPlainString()
            assertEquals(decimal, presented.groupValues[1], id)
            assertEquals(expected.scale, BigDecimal(presented.groupValues[1]).scale(), id)
            assertEquals(ReceiptBatchFrozen.unitId(expected.unit).value, presented.groupValues[2], id)
        }
    }
}
