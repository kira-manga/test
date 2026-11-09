@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package com.ukkera.pos.sales.store.independent.foodtableassignment

import com.ukkera.pos.database.*
import com.ukkera.pos.inventory.store.LocalStockRepository
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*

/** Exact representable FTA-v1 request/legacy-label coordinates; no native originals. */
internal object FtaSalesInputs {
    private fun id(suffix: String) = DomainId("019ad62c-a100-7000-8000-${suffix.padStart(12, '0')}")
    val scope = BranchScope(id("1"), id("65"), id("c9"))
    val creator = id("191")
    val actor = id("192")
    val otherActor = id("193")
    val device = id("1f6")
    val session = id("1ff")
    private val otherSession = id("200")
    val order = id("259")
    val missingOrder = id("25c")
    val floorMain = id("2bd")
    val floorUpper = id("2be")
    val floorRetired = id("2bf")
    val tableA = id("321")
    val tableB = id("322")
    val tableC = id("323")
    val tableDisabled = id("324")
    val tableRetired = id("325")
    val at = UtcInstant.parseCanonical("2026-11-03T00:00:00Z")
    const val legacyReference = "Table 07"
    const val legacySeatNote = "  مقعد ٢ / Seat 2  "
    val first = LocalFoodTableAssignmentRequestV1(order, Revision(12), floorMain, Revision(3), tableA, Revision(7), null)
    val move = LocalFoodTableAssignmentRequestV1(order, Revision(12), floorUpper, Revision(5), tableB, Revision(11), Revision(1))
    fun operation(case: Int) = OperationId(id((10_000 + case).toString(16)).value)

    // These envelope/source coordinates are auxiliary inert DATA, not a claimed retained
    // FOOD/configuration producer. No runtime/historical capability is manufactured.
    private val auxiliarySourceOperation = OperationId(creator.value)
    val legacyEnvelope = FoodEnvelopeV1(scope, LocalFoodProfileV1.ORDER, order, Revision(12), auxiliarySourceOperation,
        FoodBodyV1.Order(creator, actor, LocalSalesActivity.RESTAURANT,
            LocalFoodServiceV1(LocalSalesChannel.DINE_IN, legacyReference, legacySeatNote),
            listOf(id("3e9"), id("3ea"), id("3eb")), listOf(id("38e")), Money(Currency.EGP, 37_620)))
    private val ownerEnvelope = FoodEnvelopeV1(scope, LocalFoodProfileV1.OWNER_INDEX, actor, Revision(1), auxiliarySourceOperation,
        FoodBodyV1.OwnerIndex(listOf(order)))

    private fun source(envelope: FoodEnvelopeV1): FoodTableAssignmentFoodSourceV1 {
        val schema = LocalFoodProfileV1.schema(envelope.kind)
        return FoodTableAssignmentFoodSourceV1(envelope.kind, envelope.id, envelope.revision, schema,
            foodTableAssignmentDigest(foodStoredEnvelopeBytes(envelope, schema)), envelope.operationId, creator)
    }
    fun legacyCut() = FoodTableAssignmentOrderCutV1(legacyEnvelope, ownerEnvelope, source(legacyEnvelope), source(ownerEnvelope))
    fun legacyHeader(): StoredAggregate {
        val schema = LocalFoodProfileV1.schema(LocalFoodProfileV1.ORDER)
        val payload = foodStoredEnvelopeBytes(legacyEnvelope, schema)
        return StoredAggregate(LocalFoodProfileV1.key(LocalFoodProfileV1.ORDER, order), Revision(12), schema, payload,
            foodTableAssignmentDigest(payload))
    }
    val directory = LocalFoodTableAssignmentDirectoryV1(listOf(
        definition(LocalFoodTableDefinitionKindV1.FLOOR, floorMain, 3, null),
        definition(LocalFoodTableDefinitionKindV1.FLOOR, floorUpper, 5, null),
        definition(LocalFoodTableDefinitionKindV1.FLOOR, floorRetired, 2, null, active = false),
        definition(LocalFoodTableDefinitionKindV1.TABLE, tableA, 7, floorMain),
        definition(LocalFoodTableDefinitionKindV1.TABLE, tableB, 11, floorUpper),
        definition(LocalFoodTableDefinitionKindV1.TABLE, tableC, 13, floorMain),
        definition(LocalFoodTableDefinitionKindV1.TABLE, tableDisabled, 2, floorMain, active = false),
        definition(LocalFoodTableDefinitionKindV1.TABLE, tableRetired, 4, floorRetired, active = false),
    ))
    private fun definition(kind: LocalFoodTableDefinitionKindV1, id: DomainId, revision: Long, floor: DomainId?, active: Boolean = true) =
        LocalFoodTableDefinitionSourceV1(kind, id, Revision(revision), auxiliarySourceOperation, creator,
            PayloadDigest("0".repeat(64)), active, floor)

    fun context(request: LocalFoodTableAssignmentRequestV1, case: Int) = CommandContext(scope, actor, operation(case),
        LocalFoodTableAssignmentProfileV1.COMMAND, semanticDigest(scope, actor, LocalFoodTableAssignmentProfileV1.COMMAND,
            LocalFoodTableAssignmentCodecV1.encode(request)))
    fun readContext(orderId: DomainId, actorId: DomainId = actor) = ReadContext(scope, actorId,
        LocalFoodTableAssignmentReadCodecV1.QUERY, queryDigest(scope, actorId, LocalFoodTableAssignmentReadCodecV1.QUERY,
            LocalFoodTableAssignmentReadCodecV1.encode(LocalFoodTableAssignmentQueryV1(orderId))))

    /** Actual authorization evaluator with scripted principal/snapshot input, for refusing
     * handler paths only. This is not production employee/session/device verification. */
    suspend fun authorization(requirement: AuthorizationRequirement, actorId: DomainId = actor,
        ownerAccess: OwnerAccess = OwnerAccess.OWN_ONLY): AuthorizedAction {
        val authentication = if (actorId == otherActor) otherSession else session
        val identity = VerifiedLocalIdentity(
            actorId = actorId,
            scope = scope,
            deviceId = device,
            sessionId = authentication,
            sessionGeneration = SessionGeneration(1),
            admissionRevision = Revision(1),
            purpose = AuthenticationPurpose.INTERACTIVE,
        )
        val verifier = object : PrincipalVerifier {
            override suspend fun verifyLocal(credentials: LocalGatewayCredentials) = identity
            override suspend fun verifyCentral(credentials: CentralGatewayCredentials): VerifiedCentralIdentity? = null
            override suspend fun verifyDevice(credentials: DeviceGatewayCredentials): VerifiedDeviceIdentity? = null
            override suspend fun verifyService(credentials: ServiceGatewayCredentials): VerifiedServiceIdentity? = null
        }
        val credentials = LocalGatewayCredentials(authentication, OpaqueCredential(ByteArray(16)))
        try {
            val principal = checkNotNull(TrustedPrincipalFactory(verifier).authenticateLocal(credentials))
            val snapshot = AuthorizationSnapshot(actorId, principal.kind, scope, authentication, SessionGeneration(1), device,
                Revision(1), Revision(1), true, true, true, AuthenticationPurpose.INTERACTIVE,
                listOf(PermissionGrant(requirement.permission, scope, GrantEffect.ALLOW, ownerAccess = ownerAccess)))
            val decision = AuthorizationEvaluator.evaluate(principal, snapshot, requirement)
            check(decision is AuthorizationDecision.Allowed) {
                "Scripted refusing-path authorization was denied: $decision"
            }
            return decision.authorization
        } finally { credentials.bearer.destroy() }
    }
}

/** Controlled current observations for lawful refusal. Empty census is only test DATA. */
internal class FtaSalesView(private val header: StoredAggregate? = null) : CommandReadView {
    override val scope = FtaSalesInputs.scope
    var retainedReads = 0
        private set
    var censusReads = 0
        private set
    override suspend fun aggregate(key: AggregateKey): StoredAggregate? = header?.takeIf { it.key == key }
    override suspend fun claim(key: ClaimKey): DomainId? = header?.takeIf {
        key == LocalFoodProfileV1.claim(LocalFoodProfileV1.ORDER, it.key.id)
    }?.key?.id
    override suspend fun aggregateKeys(kind: String, after: DomainId?, limit: Int): AggregateKeyPage {
        check(kind == LocalFoodTableAssignmentProfileV1.VERSION)
        censusReads++
        return AggregateKeyPage(emptyList(), null)
    }
    override suspend fun retainedCommand(operationId: OperationId): RetainedCommandEvidence? {
        retainedReads++
        error("No retained original is supplied by these supporting refusal checks")
    }
}

/** Definition DATA only; retained history qualification deliberately has no accepting seam. */
internal class FtaSalesDirectory : TransactionFoodTableAssignmentDirectorySourceV1 {
    var currentReads = 0
        private set
    override suspend fun current(view: CommandReadView): LocalFoodTableAssignmentDirectoryV1 {
        currentReads++
        check(view.scope == FtaSalesInputs.scope)
        return FtaSalesInputs.directory
    }
    override suspend fun requireRecorded(view: CommandReadView, directory: LocalFoodTableAssignmentDirectoryV1,
        consumer: RetainedCommandEvidence) = error("Actual retained directory originals are not bound")
}

internal fun ftaAssignmentHandler(directory: FtaSalesDirectory = FtaSalesDirectory()) =
    LocalFoodTableAssignmentHandler(LocalStockRepository(), directory)
internal fun ftaAssignmentReadHandler(directory: FtaSalesDirectory = FtaSalesDirectory()) =
    LocalFoodTableAssignmentReadHandler(LocalStockRepository(), directory)
