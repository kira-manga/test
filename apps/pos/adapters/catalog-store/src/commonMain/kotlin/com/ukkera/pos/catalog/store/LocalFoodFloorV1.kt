package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.text.CharacterCodingException

/** Branch configuration only. These identities do not imply occupancy or bill membership. */
public object LocalFoodFloorProfileV1 {
    public val COMMAND: CommandKey = CommandKey("local.food.floor-configuration", 1)
    public const val FLOOR: String = "local.food.floor"
    public const val TABLE: String = "local.food.table"
    public const val VERSION: String = "local.food.floor-configuration-version"
    public const val MAX_FLOORS: Int = 16
    public const val MAX_TABLES: Int = 100
    public const val MAX_REQUEST_BYTES: Int = 4_096
    public const val MAX_RECORD_BYTES: Int = 61_440
    public const val SAVED: String = "food.floor.saved"
    public val REJECTIONS: Set<String> = setOf("food.floor.stale", "food.floor.capacity",
        "food.floor.identity_conflict", "food.floor.active_tables", "food.table.floor_required", "food.table.floor_changed")
    public fun key(kind: String, id: DomainId): AggregateKey = AggregateKey(kind, id)
    public fun schema(kind: String): String = "$kind/v1"
}

@Serializable
public data class LocalFoodFloorDefinitionV1(
    public val id: DomainId,
    public val names: LocalNamesV1,
    public val layoutOrder: Int,
    public val active: Boolean,
) {
    init { require(layoutOrder in 0..9999); foodFloorNames(names) }
}

@Serializable
public data class LocalFoodTableDefinitionV1(
    public val id: DomainId,
    public val floorId: DomainId,
    public val names: LocalNamesV1,
    public val layoutOrder: Int,
    public val active: Boolean,
) {
    init { require(layoutOrder in 0..9999); foodFloorNames(names) }
}

@Serializable
public sealed interface LocalFoodFloorConfigurationRequestV1 {
    @Serializable @SerialName("FLOOR")
    public data class Floor(public val expectedRevision: Revision?, public val floor: LocalFoodFloorDefinitionV1) :
        LocalFoodFloorConfigurationRequestV1 {
        init { require(expectedRevision == null || expectedRevision.value > 0) }
    }
    @Serializable @SerialName("TABLE")
    public data class Table(public val expectedRevision: Revision?, public val table: LocalFoodTableDefinitionV1) :
        LocalFoodFloorConfigurationRequestV1 {
        init { require(expectedRevision == null || expectedRevision.value > 0) }
    }
}

public object LocalFoodFloorCodecV1 {
    public fun encode(request: LocalFoodFloorConfigurationRequestV1): StoredBytes =
        foodFloorEncode(request, LocalFoodFloorConfigurationRequestV1.serializer(), LocalFoodFloorProfileV1.MAX_REQUEST_BYTES)
    public fun decode(bytes: StoredBytes): LocalFoodFloorConfigurationRequestV1 = foodFloorInput {
        foodFloorDecode(bytes, LocalFoodFloorConfigurationRequestV1.serializer(), LocalFoodFloorProfileV1.MAX_REQUEST_BYTES)
    }
    public fun command(operationId: OperationId, request: LocalFoodFloorConfigurationRequestV1): MutationRequest =
        MutationRequest(operationId, LocalFoodFloorProfileV1.COMMAND, encode(request))
    public fun decodeResult(bytes: StoredBytes): LocalConfigurationResultV1 {
        val result = foodFloorDecode(bytes, LocalConfigurationResultV1.serializer(), 2_048)
        require(if (result.code == LocalFoodFloorProfileV1.SAVED) result.id != null && result.revision?.value?.let { it > 0 } == true
            else result.code in LocalFoodFloorProfileV1.REJECTIONS && result.id == null && result.revision == null)
        return result
    }
}

/** Uses the real borrowed Master transaction and its retained sources; no independent writer. */
public class LocalFoodFloorConfigurationHandler : MasterCommandHandler {
    override val key: CommandKey = LocalFoodFloorProfileV1.COMMAND
    override val writableKinds: Set<String> = setOf(LocalFoodFloorProfileV1.FLOOR, LocalFoodFloorProfileV1.TABLE, LocalFoodFloorProfileV1.VERSION)
    override val claimNamespaces: Set<String> = emptySet()
    override fun normalize(input: StoredBytes): StoredBytes = LocalFoodFloorCodecV1.encode(LocalFoodFloorCodecV1.decode(input))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.key == key && context.scope == view.scope &&
            semanticDigest(view.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalFoodFloorCodecV1.decode(normalized)
        val directory = LocalFoodFloorSourceReader(view).directory()
        val (kind, id) = foodFloorTarget(request)
        val previous = directory.record(kind, id)
        val rejection = foodFloorRejection(request, directory)
        val requirement = foodFloorRequirement(view.scope, request, previous?.revision, context.semanticDigest, context.operationId)
        val versionKey = LocalFoodFloorProfileV1.key(LocalFoodFloorProfileV1.VERSION, DomainId(context.operationId.value))
        check(view.aggregate(versionKey) == null) { "food.floor.version_already_present" }
        val scope = view.scope
        val sources = directory.references()
        return object : PreparedMutation() {
            override val requirement: AuthorizationRequirement = requirement
            override val allowsActionApproval: Boolean = false
            override val affectedResources = foodFloorResources(scope, request, context.originalActorId, context.operationId, sources)
            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                check(authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE &&
                    authorization.isBoundTo(requirement, context.originalActorId))
                if (rejection != null) return ProposedOutcome.Reject(rejection,
                    foodFloorEncode(LocalConfigurationResultV1(rejection, null, null), LocalConfigurationResultV1.serializer(), 2_048))
                val revision = previous?.revision?.next() ?: Revision(1)
                val record = FoodFloorRecordV1(scope, kind, id, revision, context.operationId, context.originalActorId,
                    authorization.policyRevision, recordedAt, request, sources)
                return foodFloorApply(record)
            }
        }
    }
}

internal fun foodFloorTarget(request: LocalFoodFloorConfigurationRequestV1): Pair<String, DomainId> = when (request) {
    is LocalFoodFloorConfigurationRequestV1.Floor -> LocalFoodFloorProfileV1.FLOOR to request.floor.id
    is LocalFoodFloorConfigurationRequestV1.Table -> LocalFoodFloorProfileV1.TABLE to request.table.id
}

internal fun foodFloorExpected(request: LocalFoodFloorConfigurationRequestV1): Revision? = when (request) {
    is LocalFoodFloorConfigurationRequestV1.Floor -> request.expectedRevision
    is LocalFoodFloorConfigurationRequestV1.Table -> request.expectedRevision
}

internal fun foodFloorRequirement(scope: BranchScope, request: LocalFoodFloorConfigurationRequestV1, currentRevision: Revision?,
    digest: PayloadDigest, operationId: OperationId): AuthorizationRequirement = AuthorizationRequirement(
    Permission(SecurityAction.FLOOR_CONFIGURE, PermissionVerb.ADMINISTER), scope,
    ActionBinding(foodFloorTarget(request).second, currentRevision ?: Revision(0), digest, operationId, "food.floor.configure"))

internal fun foodFloorApply(record: FoodFloorRecordV1): ProposedOutcome.Apply {
    val payload = foodFloorEncode(record, FoodFloorRecordV1.serializer(), LocalFoodFloorProfileV1.MAX_RECORD_BYTES)
    return ProposedOutcome.Apply(
        foodFloorEncode(LocalConfigurationResultV1(LocalFoodFloorProfileV1.SAVED, record.id, record.revision), LocalConfigurationResultV1.serializer(), 2_048),
        listOf(AggregateChange(LocalFoodFloorProfileV1.key(record.kind, record.id), foodFloorExpected(record.request),
            LocalFoodFloorProfileV1.schema(record.kind), payload),
            AggregateChange(LocalFoodFloorProfileV1.key(LocalFoodFloorProfileV1.VERSION, DomainId(record.operationId.value)), null,
                LocalFoodFloorProfileV1.schema(LocalFoodFloorProfileV1.VERSION), payload))
            .sortedWith(compareBy({ it.key.kind }, { it.key.id.value })), emptyList())
}

private fun foodFloorNames(names: LocalNamesV1) {
    // Preserve the existing one-nonblank-language naming policy and literal valid Unicode.
    for (value in listOf(names.english, names.arabic)) foodFloorInput {
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        bytes.fill(0)
    }
}

/** Caller-input translation only; stored row/evidence decoding below has no recovery default. */
internal inline fun <T> foodFloorInput(block: () -> T): T = try { block() } catch (_: CharacterCodingException) {
    throw IllegalArgumentException("food.floor.invalid_unicode")
}

internal fun <T> foodFloorEncode(value: T, serializer: KSerializer<T>, maximum: Int): StoredBytes {
    val bytes = localConfigurationJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true)
    try { require(bytes.size <= maximum); return StoredBytes(bytes) } finally { bytes.fill(0) }
}

internal fun <T> foodFloorDecode(bytes: StoredBytes, serializer: KSerializer<T>, maximum: Int): T {
    require(bytes.size <= maximum)
    val raw = bytes.copyBytes()
    val value = try { localConfigurationJson.decodeFromString(serializer, raw.decodeToString(throwOnInvalidSequence = true)) }
        finally { raw.fill(0) }
    require(foodFloorEncode(value, serializer, maximum) == bytes)
    return value
}
