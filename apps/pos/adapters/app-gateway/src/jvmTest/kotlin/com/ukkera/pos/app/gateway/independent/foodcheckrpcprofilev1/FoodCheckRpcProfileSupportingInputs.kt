package com.ukkera.pos.app.gateway.independent.foodcheckrpcprofilev1

import com.ukkera.pos.app.*
import com.ukkera.pos.app.gateway.*
import com.ukkera.pos.database.*
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import java.io.File
import kotlinx.serialization.json.*
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exact executable scalar/representation inputs from food-check-rpc-profile/v1.
 * The Add template remains unchanged and NON_EXECUTABLE: no order/catalog/recipe
 * revision, current generation, native original or positive terminal is invented.
 * The separately frozen finite Draft-refusal code is used only as a negative
 * business SHAPE. Its supplied carrier cannot establish its B14 native producer.
 */
internal object FrozenFoodCheckRpcProfileInputs {
    val wire: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        coerceInputValues = false
    }

    private val main = frozenFile("fixtures.json")
    private val catalog = main.getValue("fixture_catalog").jsonObject
    val profile: JsonObject = catalog.getValue("F_PROFILE").jsonObject
    private val ids = catalog.getValue("F_IDS").jsonObject
    private val draftFixture = catalog.getValue("F_DRAFT").jsonObject
    val canonicalDraft: String = draftFixture.string("canonical_support_body")
    private val draftRoot = wire.parseToJsonElement(canonicalDraft).jsonObject
    private val draftBody = draftRoot.getValue("request").jsonObject
    private val service = draftBody.getValue("service").jsonObject
    val delivery: JsonObject = catalog.getValue("F_DELIVERY").jsonObject.getValue("typed_details").jsonObject
    val addTemplate: JsonObject = catalog.getValue("F_ADD_TWO").jsonObject.getValue("body_template").jsonObject
    val templateStatus: String = catalog.getValue("F_ADD_TWO").jsonObject.string("template_status")
    val v1OrderSelector: String = catalog.getValue("F_KITCHEN").jsonObject.getValue("v1_order_selector").toString()

    fun id(key: String): DomainId = DomainId(ids.string(key))
    val scope: BranchScope = BranchScope(id("organization"), id("company"), id("branch"))
    val actor: DomainId = id("actorA")
    val device: DomainId = id("deviceA")
    val order: DomainId = id("order")
    val owner: LocalClientIdentity = LocalClientIdentity(scope, actor, device)
    val draftOperation: OperationId = OperationId(ids.string("draftOperation"))
    val addOperation: OperationId = OperationId(ids.string("addOperation"))
    val laterOperation: OperationId = OperationId(ids.string("laterAddOperation"))
    val portions: List<DomainId> = ids.getValue("portions").jsonArray.map { DomainId(it.jsonPrimitive.content) }

    // No producer, BEFORE source, actual recorded time or order revision is supplied here.
    val draftRefusalCode: String = frozenFile("native-refusal-profile-fixtures-v1.json")
        .getValue("fixture_catalog").jsonObject.getValue("R_DRAFT_DUPLICATE").jsonObject
        .string("expected_business_refusal_code")

    val draft: LocalFoodDraftCreateV1 = LocalFoodDraftCreateV1(order,
        LocalSalesActivity.valueOf(draftBody.string("activity")),
        LocalFoodServiceV1(LocalSalesChannel.valueOf(service.string("channel")),
            service.string("reference"), service.string("seatNote")))

    fun draftIntent(version: Int = 2, channel: String = service.string("channel"),
        includeDelivery: Boolean = false, operation: OperationId = draftOperation): LocalCommandIntent =
        LocalCommandIntent(operation, LocalAction.FOOD_CREATE_DRAFT, version, null, buildList {
            add(LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(order)))
            add(LocalScalarInput(LocalField.ACTIVITY, LocalScalarValue.Choice(draftBody.string("activity"))))
            add(LocalScalarInput(LocalField.CHANNEL, LocalScalarValue.Choice(channel)))
            add(LocalScalarInput(LocalField.SERVICE_REFERENCE, LocalScalarValue.Text(service.string("reference"))))
            add(LocalScalarInput(LocalField.SEAT_NOTE, LocalScalarValue.Text(service.string("seatNote"))))
            if (includeDelivery) {
                add(LocalScalarInput(LocalField.FOOD_DELIVERY_RECIPIENT, LocalScalarValue.Text(delivery.string("recipient"))))
                add(LocalScalarInput(LocalField.FOOD_DELIVERY_CONTACT, LocalScalarValue.Text(delivery.string("contact"))))
                add(LocalScalarInput(LocalField.FOOD_DELIVERY_ADDRESS, LocalScalarValue.Text(delivery.string("address"))))
            }
        })

    /** Only the public gate shape; required revision/selection bindings are deliberately absent. */
    fun unboundAddIntent(version: Int = 3, lines: Int = 1): LocalCommandIntent = LocalCommandIntent(
        addOperation, LocalAction.FOOD_ADD, version, null,
        listOf(LocalScalarInput(LocalField.ORDER_ID, LocalScalarValue.Identifier(order))), LocalField.LINES,
        (portions + id("otherOrder")).take(lines).map { LocalInputLine(it, emptyList()) })

    fun preview(action: LocalAction = LocalAction.FOOD_ADD, version: Int = 3): LocalFoodPreviewInput =
        LocalFoodPreviewInput(action, version, emptyList(), listOf(LocalInputLine(portions.first(), emptyList())))

    fun draftRequest(legacy: Boolean = false): MutationRequest = LocalFoodStockCodecV2.command(draftOperation,
        if (legacy) LocalFoodStockRequestV2.CreateDraft(draft) else LocalFoodStockRequestV2.ActivityProtected(draft))

    fun stagedDraft(legacy: Boolean = false): StoredClientIntentV1 = StoredClientIntentV1.staged(owner,
        draftIntent(if (legacy) 1 else 2), draftRequest(legacy), LocalText("Saved check", "Saved check"),
        listOf(LocalRecordValue(LocalText("Order", "Order"), LocalText(order.value, order.value))))

    /** Retained invalid input DATA, not a staged/native Add original or a bound revision template. */
    fun unresolvedAddTemplateEntry(): StoredClientIntentV1 {
        val bytes = addTemplate.toString().encodeToByteArray()
        return try {
            stagedDraft().copy(operationId = addOperation, action = LocalAction.FOOD_ADD.name, formVersion = 3,
                inputDigest = inputDigest(unboundAddIntent()), payloadHex = encodeHex(bytes),
                semanticSha256 = semanticDigest(scope, actor, LocalFoodStockCodecV2.COMMAND, StoredBytes(bytes)))
        } finally { bytes.fill(0) }
    }

    fun assertDraftOriginal(entry: StoredClientIntentV1, operation: OperationId = draftOperation) {
        assertEquals(owner, entry.owner)
        assertEquals(operation, entry.operationId)
        assertEquals(LocalAction.FOOD_CREATE_DRAFT, entry.localAction)
        assertEquals(2, entry.formVersion)
        assertEquals("sales.food.stock", entry.commandName)
        assertEquals(2, entry.commandVersion)
        assertEquals(canonicalDraft, entry.request().payload.copyBytes().decodeToString())
        assertNull(entry.actionApprovalId)
        assertNull(entry.actionApproval)
        entry.validate(owner)
    }

    fun view(actorId: DomainId = actor, branchScope: BranchScope = scope, allowDraft: Boolean = true): LocalSessionView =
        LocalSessionView(actorId, branchScope, device, id("sessionA"), SessionGeneration(1),
            LocalLoginIdentifier("synthetic-check"), ActivatedLocalPolicy(branchScope, actorId, Revision(1),
                buildList {
                    if (allowDraft) add(PermissionGrant(Permission(SecurityAction.ORDER_CREATE, PermissionVerb.PERFORM),
                        branchScope, GrantEffect.ALLOW))
                    add(PermissionGrant(Permission(SecurityAction.ORDER_EDIT, PermissionVerb.PERFORM), branchScope, GrantEffect.ALLOW))
                    add(PermissionGrant(Permission(SecurityAction.CATALOG_READ, PermissionVerb.PERFORM), branchScope, GrantEffect.ALLOW))
                }, LocalSessionPolicy(), ThrottlePolicy()),
            MonotonicMoment(id("sessionA"), 1_000), MonotonicMoment(id("sessionA"), 2_000))

    fun credentials(connection: DomainId = id("sessionA")): LocalGatewayCredentials =
        LocalGatewayCredentials(connection, OpaqueCredential(ByteArray(16)))

    /** Supplied negative DTO fields only. Sequence/epoch/time are inert port-constructor DATA. */
    fun refusalCarrierData(entry: StoredClientIntentV1 = stagedDraft()): MutationReply.Recorded =
        carrierData(entry, false, draftRefusalCode, LocalFoodResultV1(draftRefusalCode, null, null))

    fun carrierData(entry: StoredClientIntentV1, committed: Boolean, code: String,
        result: LocalFoodResultV1): MutationReply.Recorded = MutationReply.Recorded(entry.operationId, entry.owner.actorId,
        committed, code, resultBytes(result), Revision(1), JournalSequence(1),
        UtcInstant.parseCanonical("1970-01-01T00:00:00Z"), replayed = false)

    fun resultBytes(result: LocalFoodResultV1): StoredBytes =
        StoredBytes(wire.encodeToString(LocalFoodResultV1.serializer(), result).encodeToByteArray())

    fun bytes(text: String): StoredBytes = StoredBytes(text.encodeToByteArray())

    private fun frozenFile(name: String): JsonObject {
        val relative = "qa/pos/designs/food-check-rpc-profile/$name"
        val file = generateSequence(File(".").canonicalFile) { it.parentFile }
            .map { File(it, relative) }.firstOrNull { it.isFile } ?: error("Frozen FOOD_CHECKS input not found: $relative")
        return wire.parseToJsonElement(file.readText()).jsonObject
    }

}

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

internal fun LocalCommandIntent.withValues(values: List<LocalScalarInput>): LocalCommandIntent =
    LocalCommandIntent(operationId, action, formVersion, reference, values, lineField, lines, arrivalEvidence)
