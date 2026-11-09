package com.ukkera.pos.app.gateway.independent.localpricingoriginalv1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.catalog.store.*
import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.pricing.TaxSnapshot
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Unchanged frozen facts; UUIDs and storage CAS are unit-only public-contract bindings. */
internal object LocalPricingOriginalFrozenBindings {
    private val root = javaClass.getResourceAsStream("/independent/localpricingoriginalv1/fixtures.json")
        ?.bufferedReader()?.use { Json.parseToJsonElement(it.readText()).jsonObject }
        ?: error("The unchanged local-pricing-original-workflow-v1 fixture resource is required")
    private val fixtures = root.getValue("fixtures").jsonObject
    private val ids = mutableMapOf<String, DomainId>()
    private val base = fixture("F-BASE")
    private val standard = base.getValue("standard").jsonObject
    private val revision = fixture("F-REVISION")
    private val contexts = fixture("F-CONTEXTS")
    private val dateRule = contexts.getValue("rules").jsonArray.map { it.jsonObject }
        .single { it.string("id") == "C-DATE-2" }
    private val dateQuery = contexts.getValue("context_queries").jsonArray.map { it.jsonObject }
        .single { it.string("id") == "DATE-INSIDE" }

    val scope = BranchScope(id(base.string("organization")), id(base.string("company")), id(base.string("branch")))
    val owner = LocalClientIdentity(scope, id("E-A1"), id("unit-only-client-device"))
    val skuId = id("${standard.string("product")}/${standard.string("variant")}")
    val ruleId = id(revision.string("stable_rule_identity"))
    val contextId = id("${base.string("branch")}/${standard.string("context")}/${skuId.value}")
    val publicationId = id(revision.string("received_release"))
    val policyId = id("unit-only-policy-entry")
    val unitId = id("unit-only-base-unit")
    val originalCompanyRevision = Revision(standard.string("company_revision").toLong())
    val laterCompanyRevision = Revision(revision.string("new_company_revision").toLong())
    val originalCompanyPrice = UnitPrice.parse(Currency.EGP, standard.string("company_unit"))
    val laterCompanyPrice = UnitPrice.parse(Currency.EGP, revision.string("new_unit"))
    val originalBranchPrice = UnitPrice.parse(Currency.EGP, standard.string("branch_unit"))
    val observedAt = UtcInstant.parseCanonical(dateQuery.string("instant_utc"))
    val effectiveFrom = UtcInstant.parseCanonical(dateRule.string("period_start_utc"))
    // Storage coordinates are deliberately separate from the frozen company/policy r5/r6.
    val capturedStorageCas = Revision(21)
    val laterStorageCas = Revision(22)
    private val quantity = Quantity.parse(unitId, dateQuery.string("quantity"), 0)

    fun id(label: String): DomainId = ids.getOrPut(label) { DomainId.random() }
    fun operation(label: String): OperationId = OperationId(id(label).value)
    fun fixture(name: String): JsonObject = fixtures.getValue(name).jsonObject

    fun unsignedPolicy(): LocalPricePolicyEntryV1 = LocalPricePolicyEntryV1(scope.company(), policyId,
        publicationId, laterCompanyRevision, skuId, ruleId,
        fixture("F-AUTH").getValue("local_editor").jsonObject.getValue("company_branch_edit_allowed").jsonPrimitive.boolean,
        quantity, effectiveFrom, null)

    fun activationIntent(cas: Revision?, publication: DomainId = publicationId): LocalCommandIntent =
        LocalCommandIntent(operation("OP-ACTIVATE-R6"), LocalAction.PRICE_POLICY_ACTIVATE, 1,
            LocalRecordReference(LocalRecordKind.PRICE_POLICY_PUBLICATION, publication), listOf(
                scalar(LocalField.SOURCE_ID, LocalScalarValue.Identifier(publication)),
                scalar(LocalField.POLICY_ID, LocalScalarValue.Identifier(policyId)),
                scalar(LocalField.SKU_ID, LocalScalarValue.Identifier(skuId)),
                scalar(LocalField.PRICE_RULE_ID, LocalScalarValue.Identifier(ruleId))) +
                listOfNotNull(cas?.let { scalar(LocalField.EXPECTED_PRICE_POLICY_STORAGE_REVISION, LocalScalarValue.Integer(it.value)) }) +
                scalar(LocalField.CONFIRMED, LocalScalarValue.Flag(true)))

    fun indexIntent(cas: Revision?): LocalCommandIntent = LocalCommandIntent(operation("LP-16-bounded-index-step"),
        LocalAction.PRICE_HISTORY_INDEX, 1, LocalRecordReference(LocalRecordKind.PRICE_HISTORY_INDEX, scope.branchId),
        listOf(scalar(LocalField.SOURCE_ID, LocalScalarValue.Identifier(scope.branchId))) +
            listOfNotNull(cas?.let { scalar(LocalField.EXPECTED_PRICE_HISTORY_INDEX_STORAGE_REVISION, LocalScalarValue.Integer(it.value)) }) +
            scalar(LocalField.CONFIRMED, LocalScalarValue.Flag(true)))

    fun branchRequest(reset: Boolean): LocalPricingRequestV1.ChangeBranchPrice = LocalPricingRequestV1.ChangeBranchPrice(
        skuId, ruleId, originalCompanyRevision, Revision(base.string("policy_revision").toLong()), capturedStorageCas,
        if (reset) null else originalBranchPrice, null, "frozen_original")

    fun entry(intent: LocalCommandIntent, request: MutationRequest): StoredClientIntentV1 = StoredClientIntentV1.staged(
        owner, intent, request, LocalText("Frozen original pricing request", "طلب تسعير أصلي محفوظ"), listOf(
            LocalRecordValue(LocalText("Original operation", "العملية الأصلية"), LocalText(intent.operationId.value, intent.operationId.value))))

    fun roundTrip(entry: StoredClientIntentV1): StoredClientIntentV1 = journalJson.decodeFromString(
        StoredClientIntentV1.serializer(), journalJson.encodeToString(StoredClientIntentV1.serializer(), entry))
        .also { it.validate(owner) }

    fun policy(cas: Revision?, entryRevision: Revision = laterCompanyRevision,
        status: String = "RECEIVED"): LocalPricePolicyViewV1 = LocalPricePolicyViewV1(
        policyId, entryRevision, publicationId, skuId, ruleId, true, quantity, effectiveFrom, null, status, cas)

    fun context(cas: Revision?, companyRevision: Revision): LocalPriceContextViewV1 = LocalPriceContextViewV1(
        skuId, ruleId, contextId, LocalPriceV1(ruleId, companyRevision, null, 0, quantity,
            effectiveFrom = effectiveFrom,
            unitPrice = if (companyRevision == originalCompanyRevision) originalCompanyPrice else laterCompanyPrice,
            inclusion = TaxInclusion.EXCLUSIVE,
            tax = TaxSnapshot(id("unit-only-tax-policy"), originalCompanyRevision, "standard", PercentageRate.parse(standard.string("tax_rate")))),
        companyRevision, policy(cas, entryRevision = companyRevision, status = "ACTIVE"), null)

    fun pageBytes(page: LocalPricingPageV1): StoredBytes = StoredBytes(Json.encodeToString(LocalPricingPageV1.serializer(), page).encodeToByteArray())
    fun resultBytes(result: LocalPricingResultV1): StoredBytes = StoredBytes(Json.encodeToString(LocalPricingResultV1.serializer(), result).encodeToByteArray())
    private fun scalar(field: LocalField, value: LocalScalarValue) = LocalScalarInput(field, value)
}

/** Shared production adapter with scripted reads/absence only; no genuine admission, persistence or native custody. */
internal class LocalPricingOriginalUnitPorts(initial: StoredClientIntentV1? = null,
    initialCas: Revision? = LocalPricingOriginalFrozenBindings.capturedStorageCas) {
    private val inputs = LocalPricingOriginalFrozenBindings
    private var live = true
    private val owned = mutableListOf<LocalGatewayCredentials>()
    private val boot = inputs.id("unit-only-boot")
    val journal = LocalPricingReadOnlyOriginalJournal(initial)
    val master = LocalPricingReadOnlyMaster(initialCas)
    val installed = LocalWorkflowInstallation(setOf(LocalPricingProfile.COMMAND), setOf(LocalPricingProfile.QUERY))
    var view = view(inputs.owner.actorId)
        private set
    val gateway = LocalEndpointWorkflowGateway(master, journal, LocalWorkflowCredentialSource { lease() }, installed)
    init { master.currentView = { view } }

    fun access(): GatewayAccess = GatewayAccess(master, lease(), view, installed)
    fun replaceActor(actor: DomainId) { view = view(actor) }
    fun close() { live = false; owned.forEach { it.bearer.destroy() } }

    private fun view(actor: DomainId): LocalSessionView = LocalSessionView(actor, inputs.scope, inputs.owner.deviceId,
        inputs.id("unit-only-session"), SessionGeneration(1), LocalLoginIdentifier("synthetic-pricing-original"),
        ActivatedLocalPolicy(inputs.scope, actor, inputs.originalCompanyRevision, listOf(
            PermissionGrant(Permission(SecurityAction.CATALOG_ACTIVATE, PermissionVerb.ADMINISTER), inputs.scope, GrantEffect.ALLOW),
            PermissionGrant(Permission(SecurityAction.BRANCH_PRICE_EDIT, PermissionVerb.PERFORM), inputs.scope, GrantEffect.ALLOW)),
            LocalSessionPolicy(), ThrottlePolicy()), MonotonicMoment(boot, 1), MonotonicMoment(boot, 2))

    private fun lease(): LocalWorkflowCredentialLease = LocalGatewayCredentials(inputs.id("unit-only-connection"),
        OpaqueCredential(ByteArray(16))).also { owned += it }.let { LocalWorkflowCredentialLease(it) { live } }
}

/** Returns an already captured original representation; every attempted write fails loudly. */
internal class LocalPricingReadOnlyOriginalJournal(initial: StoredClientIntentV1?) : LocalClientIntentJournal(LocalPricingOriginalFrozenBindings.owner) {
    val entry = initial?.let(LocalPricingOriginalFrozenBindings::roundTrip)
    var writes = 0
        private set
    override suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?> =
        JournalRead.Value(entry?.takeIf { it.operationId == operationId })
    override suspend fun pending(): JournalRead<List<StoredClientIntentV1>> = JournalRead.Value(listOfNotNull(entry))
    override suspend fun stage(intent: StoredClientIntentV1): JournalWrite = forbiddenWrite()
    override suspend fun markDispatched(operationId: OperationId): JournalWrite = forbiddenWrite()
    override suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = forbiddenWrite()
    override suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite = forbiddenWrite()
    override suspend fun close() = Unit
    private fun forbiddenWrite(): Nothing { writes++; throw AssertionError("This unit slice cannot certify a custody write") }
}

/** Scripted Current, typed read and absent lookup responses; execution and real authentication are unbound. */
internal class LocalPricingReadOnlyMaster(var storageCas: Revision?) : LocalMasterClient {
    private val inputs = LocalPricingOriginalFrozenBindings
    lateinit var currentView: () -> LocalSessionView
    var companyRevision = inputs.originalCompanyRevision
    val reads = mutableListOf<ReadRequest>()
    val lookups = mutableListOf<OperationIdentity>()
    val mutations = mutableListOf<MutationRequest>()
    override suspend fun current(credentials: LocalGatewayCredentials): LocalSessionViewResult = LocalSessionViewResult.Current(currentView())
    override suspend fun lookup(credentials: LocalGatewayCredentials, identity: OperationIdentity): LookupReply {
        lookups += identity
        return LookupReply.AbsentAtCurrentWriter(identity.operationId)
    }
    override suspend fun read(credentials: LocalGatewayCredentials, request: ReadRequest): ReadReply {
        reads += request
        check(request.query == LocalPricingProfile.QUERY)
        val bytes = request.payload.copyBytes()
        val query = try { Json.decodeFromString(LocalPricingQueryV1.serializer(), bytes.decodeToString()) } finally { bytes.fill(0) }
        val page = when (query) {
            is LocalPricingQueryV1.PolicyPublication -> { check(query.publicationId == inputs.publicationId); LocalPricingPageV1(policy = inputs.policy(storageCas,
                status = if (companyRevision == inputs.originalCompanyRevision) "RECEIVED" else "ACTIVE")) }
            is LocalPricingQueryV1.Context -> { check(query.skuId == inputs.skuId && query.ruleId == inputs.ruleId); LocalPricingPageV1(context = inputs.context(storageCas, companyRevision)) }
            LocalPricingQueryV1.IndexStatus -> LocalPricingPageV1(indexStatus = if (storageCas == null) "UNINDEXED" else "INDEXING", indexStorageRevision = storageCas)
            else -> throw AssertionError("No broader pricing read is bound by this unit fixture")
        }
        return ReadReply.Data(inputs.pageBytes(page), companyRevision, inputs.observedAt)
    }
    override suspend fun execute(credentials: LocalGatewayCredentials, request: MutationRequest): MutationReply {
        mutations += request
        throw AssertionError("Actual protected pricing execution is unbound")
    }
    override val health: StateFlow<LocalMasterEndpointHealth> get() = forbidden()
    override suspend fun signIn(connectionId: DomainId, login: LocalLoginIdentifier, password: CharArray,
        explicitDeviceMove: Boolean): LocalAuthenticationResult = forbidden()
    override suspend fun refresh(credentials: LocalGatewayCredentials): LocalAuthenticationResult = forbidden()
    override suspend fun logout(credentials: LocalGatewayCredentials): LocalSignOutResult = forbidden()
    private fun forbidden(): Nothing = throw AssertionError("Actual admission/authenticated transport is unbound")
}

private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
