@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.app.gateway.independent.returnfinancereadintegrity

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Frozen semantic money/facts are read unchanged. Adapter-only coordinates do not
 * represent the fixture's two original portions, original native history or authority. */
@OptIn(ExperimentalSerializationApi::class)
internal object FrozenReturnFinanceReadInputs {
    val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
    private val file = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("qa/pos/designs/return-finance-read-integrity/fixtures.json") }
        .firstOrNull { Files.isRegularFile(it) } ?: error("Frozen return-finance-read-integrity-v1 fixtures required")
    val frozen: JsonObject = json.parseToJsonElement(Files.readString(file)).jsonObject.also {
        check(it.string("specification") == "return-finance-read-integrity-v1" && it.string("status") == "FROZEN")
    }
    fun id(alias: String) = DomainId(frozen.obj("ids").string(alias))
    fun operation(alias: String) = OperationId(id(alias).value)
    fun wiringId(number: Int) = DomainId("72000000-0000-4000-8000-${number.toString().padStart(12, '0')}")
    val caseId = wiringId(1)
    val portionId = wiringId(2)
    val unitId = wiringId(3)
    val cashPaymentId = wiringId(4)
    val externalPaymentId = wiringId(5)
    val externalTenderId = wiringId(6)
    val quantity = Quantity(unitId, frozen.obj("common_input").obj("accepted_header").string("accepted_quantity").toLong(), 0)
    val scope = BranchScope(id("organization"), id("company"), id("origin_branch"))
    val owner = LocalClientIdentity(scope, id("return_employee"), id("return_device"))
    val observedAt = instant("2026-11-03T10:00:01Z")

    fun materialize(snapshot: String, malformed: String? = null): JsonObject {
        var root = buildJsonObject {
            put("common_input", frozen.getValue("common_input"))
            put("facts", frozen.getValue("facts"))
            put("snapshot", frozen.obj("snapshots").getValue(snapshot))
        }
        if (malformed != null) {
            val variant = frozen.getValue("malformed_variants").jsonArray.map { it.jsonObject }
                .single { it.string("id") == malformed }
            check(variant.string("input") == snapshot)
            for (entry in variant.getValue("patch").jsonArray) {
                val operation = entry.jsonObject
                root = patch(root, operation.string("path").removePrefix("/").split('/'),
                    operation["value"], operation.string("op") == "remove").jsonObject
            }
        }
        return root
    }

    private fun patch(node: JsonElement, path: List<String>, value: JsonElement?, remove: Boolean): JsonElement {
        val fields = node.jsonObject.toMutableMap()
        val key = path.first()
        check(key in fields)
        if (path.size == 1) {
            if (remove) fields.remove(key) else fields[key] = checkNotNull(value)
        } else fields[key] = patch(fields.getValue(key), path.drop(1), value, remove)
        return JsonObject(fields)
    }

    fun expected(snapshot: String) = frozen.obj("expected_outputs")
        .obj(frozen.obj("snapshots").obj(snapshot).string("expected_output"))

    /** These compatibility vocabulary mappings do not add observation/application proof. */
    fun attemptStatus(fact: JsonObject): String = when (fact.string("original_outcome")) {
        "SUCCESS" -> "ATTESTED_PAID"
        "NOT_PERFORMED" -> "NOT_PAID"
        "UNKNOWN" -> "OUTCOME_UNKNOWN"
        else -> error("Unbound frozen attempt outcome")
    }

    fun status(snapshot: String): String = when (snapshot) {
        "UNPAID", "UNPAID_LOW_CASH" -> "DUE"
        "PARTIAL", "FAILED" -> "PART_REFUNDED"
        "UNKNOWN" -> "OUTCOME_UNKNOWN"
        "SETTLED" -> "SETTLED"
        else -> error("This snapshot requires unavailable public observation/application facts")
    }

    fun financeBody(root: JsonObject, snapshotName: String): StoredBytes {
        val common = root.obj("common_input")
        val evidence = common.obj("finance_evidence")
        val snapshot = root.obj("snapshot")
        val facts = root.obj("facts")
        val tenders = evidence.obj("original_tenders")
        fun money(currency: JsonElement, minor: JsonElement) = buildJsonObject {
            put("currency", currency); put("minor", minor)
        }
        fun tender(cash: Boolean) = buildJsonObject {
            put("kind", if (cash) "CASH" else "OTHER")
            put("tenderId", if (cash) JsonNull else JsonPrimitive(externalTenderId.value))
            put("policyRevision", if (cash) JsonNull else JsonPrimitive("8"))
            put("requiresTerminal", false); put("requiresReference", false)
        }
        val row = buildJsonObject {
            put("returnId", id("current_return").value); put("saleId", id("sale").value)
            put("claimId", caseId.value); put("portionId", portionId.value)
            put("quantity", json.encodeToJsonElement(Quantity.serializer(), quantity))
            put("revision", snapshot.getValue("obligation_revision"))
            put("requestedDisposition", "QUARANTINE")
            put("originalPayable", money(evidence.getValue("currency"), evidence.getValue("accepted_obligation_minor")))
            put("refunded", money(evidence.getValue("currency"), snapshot.getValue("recorded_actual_paid_minor")))
            put("due", money(evidence.getValue("currency"), snapshot.getValue("liability_minor")))
            put("status", status(snapshotName))
            put("obligations", buildJsonArray {
                for ((name, payment) in listOf("cash" to cashPaymentId, "external" to externalPaymentId)) add(buildJsonObject {
                    put("originalPaymentId", payment.value); put("tender", tender(name == "cash"))
                    put("amount", money(tenders.obj(name).getValue("currency"), tenders.obj(name).getValue("minor")))
                })
            })
            put("attempts", buildJsonArray {
                for ((index, name) in snapshot.getValue("included_facts").jsonArray.withIndex()) {
                    val fact = facts.obj(name.jsonPrimitive.content)
                    val cash = fact.string("tender") == "CASH"
                    val outcome = attemptStatus(fact)
                    add(buildJsonObject {
                        put("attemptId", id(fact.string("attempt_id")).value)
                        put("originalPaymentId", (if (cash) cashPaymentId else externalPaymentId).value)
                        put("status", outcome); put("tender", tender(cash))
                        put("amount", money(fact.getValue("currency"), fact.getValue("amount_minor")))
                        put("ownAttempt", true)
                        put("intentOperationId", operation(fact.string("original_operation_id")).value)
                        put("activeRecordingOperationId", JsonNull)
                        // The DTO requires a marker/session for own paid attempts. These are
                        // inert carrier coordinates, not frozen observation/application IDs.
                        put("completedRecordingOperationId", if (outcome == "ATTESTED_PAID")
                            JsonPrimitive(wiringId(100 + index).value) else JsonNull)
                        put("cashSessionId", id("current_cash_session").value)
                        put("cashSessionRevision", "1")
                    })
                }
            })
        }
        return bytes(buildJsonObject { put("type", "RETURNS"); put("returns", JsonArray(listOf(row))); put("nextAfter", JsonNull) })
    }

    /** Accepted/ordinary eligibility is a synthetic routing precondition only. */
    fun caseBody(): StoredBytes {
        val received = instant("2026-10-31T09:00:00Z")
        val recorded = instant("2026-11-01T09:00:00Z")
        val original = OperationId(wiringId(10).value)
        val decisionOperation = OperationId(wiringId(11).value)
        val evidence = LocalCashEvidenceV1(wiringId(12), recorded)
        val timing = LocalReturnOrdinaryTimingV1("gateway_carrier", 14, 14, id("receipt"), received,
            "Africa/Cairo", original, recorded, instant("2026-11-14T09:00:00Z"), LocalReturnCommercialWindowStatusV1.WITHIN)
        val decision = LocalReturnCaseDecisionViewV1(decisionOperation, LocalReturnOrdinaryAssessmentV1.ELIGIBLE,
            "ORDINARY_REVIEW", "CONTINUING_RIGHTS_REVIEW", wiringId(7), Revision(1), wiringId(8), listOf(wiringId(9)),
            listOf(id("receipt")), received, "Africa/Cairo", null, ordinaryTiming = timing,
            ordinaryInspection = LocalReturnOrdinaryInspectionV1(LocalReturnInspectionConditionV1.UNCHANGED,
                evidence, "2026-11-01T09:00:00Z"))
        val view = LocalReturnCaseViewV1(LocalReturnCaseSummaryV1(caseId, Revision(2),
            LocalReturnCaseStageV1.ACCEPTED_DISPOSITION_PENDING, recorded, id("sale"), portionId,
            decisionOperation, decision.outcome, decision.route, null, id("current_return")), original,
            LocalReturnCaseFactsV1(LocalReturnClaimFactsV1(id("sale"), portionId, quantity, null, recorded,
                "Synthetic gateway routing precondition", evidence), "Synthetic unchanged carrier", LocalReturnRequestedRemedyV1.REFUND,
                "Africa/Cairo", LocalReturnClaimReasonV1.CHANGE_OF_MIND),
            LocalReturnCaseReceiptBindingV1(id("sale"), portionId, quantity, evidence), Revision(1), decision, 3)
        return bytes(json.encodeToJsonElement(LocalReturnCaseReadResultV1.serializer(), LocalReturnCaseReadResultV1.Case(view)))
    }

    fun view(readAllowed: Boolean = true): LocalSessionView {
        val boot = wiringId(14)
        return LocalSessionView(owner.actorId, scope, owner.deviceId, id("original_employee_session"), SessionGeneration(1),
            LocalLoginIdentifier("finance-read-carrier"), ActivatedLocalPolicy(scope, owner.actorId, Revision(8),
                if (readAllowed) listOf(PermissionGrant(Permission(SecurityAction.RETURN_RECEIPT_LOOKUP, PermissionVerb.PERFORM), scope, GrantEffect.ALLOW))
                    else emptyList(),
                LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(boot, 1_000), MonotonicMoment(boot, 2_000))
    }

    private fun bytes(value: JsonElement) = StoredBytes(value.toString().encodeToByteArray())
    private fun instant(value: String) = UtcInstant(Instant.parse(value))
}

internal fun JsonObject.obj(name: String): JsonObject = getValue(name).jsonObject
internal fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

/** Actual gateway read/error path; DATA and session replies are controlled, with
 * no native/source/persistence/physical services and no accepting journal. */
internal class ReturnFinanceReadGatewayFixture(val snapshot: String, malformed: String? = null) {
    val input = FrozenReturnFinanceReadInputs
    val root = input.materialize(snapshot, malformed)
    val journal = RefusingReturnFinanceReadJournal(input.owner)
    var lifetimeCurrent = true
    val endpoint = ControlledReturnFinanceReadEndpoint(input.financeBody(root, snapshot), root.obj("snapshot").string("finance_cut"))
    val gateway = LocalEndpointWorkflowGateway(endpoint, journal, LocalWorkflowCredentialSource {
        LocalWorkflowCredentialLease(LocalGatewayCredentials(input.owner.deviceId, OpaqueCredential(ByteArray(16)))) { lifetimeCurrent }
    }, LocalWorkflowInstallation(emptySet(), setOf(LocalReturnCaseReadCodecV1.QUERY, LocalReturnReadCodec.QUERY)))
    val query = LocalWorkflowQuery(LocalWorkflow.RETURNS,
        reference = LocalRecordReference(LocalRecordKind.RETURN_FINANCE, input.id("current_return"), parentId = input.caseId))
}

internal class ControlledReturnFinanceReadEndpoint(private val finance: StoredBytes, cut: String) : LocalMasterClient {
    private val input = FrozenReturnFinanceReadInputs
    private val revision = Revision(cut.toLong())
    val reads = mutableListOf<ReadRequest>()
    var executeCalls = 0
    var lookupCalls = 0
    var denyFinance = false
    var beforeFinanceReply: suspend () -> Unit = {}
    override val health: StateFlow<LocalMasterEndpointHealth> get() = error("Native health is not bound")
    override suspend fun current(credentials: LocalGatewayCredentials) = LocalSessionViewResult.Current(input.view(!denyFinance))
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        val payload = when (request.query) {
            LocalReturnCaseReadCodecV1.QUERY -> {
                check(input.json.decodeFromString(LocalReturnCaseQueryV1.serializer(), request.payload.copyBytes().decodeToString()) ==
                    LocalReturnCaseQueryV1.Case(input.caseId))
                input.caseBody()
            }
            LocalReturnReadCodec.QUERY -> {
                check(input.json.decodeFromString(LocalReturnQueryV1.serializer(), request.payload.copyBytes().decodeToString()) ==
                    LocalReturnQueryV1.Accepted(input.id("sale"), input.caseId, input.id("current_return")))
                beforeFinanceReply()
                if (denyFinance) return ReadReply.Unavailable(ReadFailure.AUTHORIZATION_DENIED)
                finance
            }
            else -> error("Unbound read surface")
        }
        return ReadReply.Data(payload, revision, input.observedAt)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        executeCalls++; return MutationReply.Deferred(request.operationId, DeferredReason.UNKNOWN_COMMAND)
    }
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookupCalls++; return LookupReply.Deferred(identity.operationId, DeferredReason.STORAGE_UNCERTAIN)
    }
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = LocalAuthenticationResult.Unavailable
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = LocalSignOutResult.Unavailable
}

internal class RefusingReturnFinanceReadJournal(owner: LocalClientIdentity) : LocalClientIntentJournal(owner) {
    var calls = 0
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> { calls++; return JournalRead.Unavailable }
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> { calls++; return JournalRead.Unavailable }
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite { calls++; return JournalWrite.UNAVAILABLE }
    override suspend fun markDispatched(operationId: OperationId): JournalWrite { calls++; return JournalWrite.UNAVAILABLE }
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite { calls++; return JournalWrite.UNAVAILABLE }
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite { calls++; return JournalWrite.UNAVAILABLE }
    override suspend fun close() { calls++ }
}
