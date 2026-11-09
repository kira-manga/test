package com.ukkera.pos.database

import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.AuthorizationRequirement
import com.ukkera.pos.security.OwnerConstraint
import com.ukkera.pos.security.PermissionVerb
import com.ukkera.pos.security.SecurityAction
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Immutable future operation ownership. This is NOT a money outcome or physical capacity proof.
 * The target binding is derived from a closed observation subtype and immutable target fields;
 * it intentionally excludes the not-yet-observed physical outcome. Branch is the enclosing DB
 * key and original-custody actor/command are separately fixed, never supplied by an observation's
 * authority claims. Only the kernel's explicit RETAIN_RESOLUTION path can use the fixed linked
 * resolution ID under a different currently authorized recorder. It does not rewrite this row,
 * relabel the original employee's custody or exempt current owner/amount authorization.
 */
public data class FutureOperationReservation(
    public val operationId: OperationId,
    public val actorId: DomainId,
    public val command: CommandKey,
    public val targetBinding: PayloadDigest,
)

/**
 * Shared by actual execution and approval preparation so one path cannot silently regain the
 * obsolete original-actor-only rule. This only permits the installed typed handler to prepare;
 * a different recorder must additionally pass matchesSupervisedResolutionRequirement and all
 * ordinary protected authorization/resource/native guards. No caller-supplied authority here.
 */
internal fun FutureOperationReservation.matchesRecorderRoute(
    recorderActorId: DomainId,
    currentCommand: CommandKey,
    currentTargetBinding: PayloadDigest?,
    action: PhysicalRecordingAction?,
): Boolean = command == currentCommand && targetBinding == currentTargetBinding &&
    (actorId == recorderActorId || action == PhysicalRecordingAction.RETAIN_RESOLUTION)

/**
 * A narrow identity/requirement shape check, NOT permission or original-fact proof. The kernel
 * calls this only after matching the existing command and immutable target binding and after
 * the installed typed handler has prepared a RETAIN_RESOLUTION. It still evaluates current
 * authorization (including the original owner's ANY_OWNER restriction), resource admission,
 * the real native gate and the exact effective fact proposal in their normal transactions.
 * The trusted domain reader must prove the original UNKNOWN and fixed linked resolution ID.
 */
internal fun FutureOperationReservation.matchesSupervisedResolutionRequirement(
    requirement: AuthorizationRequirement,
): Boolean = requirement.permission.verb == PermissionVerb.PERFORM &&
    requirement.permission.action in setOf(SecurityAction.COLLECTION_RESOLVE, SecurityAction.REFUND_RESOLVE) &&
    requirement.owner == OwnerConstraint.OwnerPrivate(actorId)

public object OperationReservationBinding {
    /** Domain-separated, bounded canonical frame; neither concatenation nor locale formatting. */
    public fun frame(domain: String, fields: List<String>): PayloadDigest {
        requireProtocolName(domain)
        require(fields.size in 1..32)
        val parts = listOf("ukkera.operation-reservation-target/v1", domain) + fields
        val encoded = parts.map { it.encodeToByteArray(throwOnInvalidSequence = true) }
        require(encoded.all { it.size in 1..512 })
        return sha256(frame(encoded))
    }
}

/**
 * A trusted handler may include this insertion atomically with its pre-money intent. The kernel
 * checks complete-history absence at the current writer before allowing insertion. Other
 * handlers cannot consume the ID first, and a wrong observation does not burn its record slot.
 * Reservation rows are never released, overwritten or inferred from a client-supplied flag.
 */
public object ReservedOperations {
    public const val KIND: String = "kernel.operation-reservation"
    public const val SCHEMA: String = "kernel.operation-reservation/v1"

    public fun key(operationId: OperationId): AggregateKey = AggregateKey(KIND, DomainId(operationId.value))

    public fun create(reservation: FutureOperationReservation): AggregateChange = AggregateChange(
        key(reservation.operationId), null, SCHEMA, encodeReservation(reservation),
    )
}

@Serializable
private data class ReservationWire(
    val version: Int, val operationId: OperationId, val actorId: DomainId,
    val command: String, val commandVersion: Int, val targetBinding: String,
)

private val reservationJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }

private fun encodeReservation(value: FutureOperationReservation): StoredBytes = StoredBytes(
    reservationJson.encodeToString(ReservationWire.serializer(), ReservationWire(1, value.operationId, value.actorId,
        value.command.name, value.command.version, value.targetBinding.value)).encodeToByteArray(),
)

internal fun decodeReservation(key: AggregateKey, schema: String, payload: StoredBytes): FutureOperationReservation {
    check(key.kind == ReservedOperations.KIND && schema == ReservedOperations.SCHEMA && payload.size <= 2048)
    val wire = reservationJson.decodeFromString(ReservationWire.serializer(), payload.copyBytes().decodeToString(throwOnInvalidSequence = true))
    check(wire.version == 1 && key.id.value == wire.operationId.value)
    val reservation = FutureOperationReservation(wire.operationId, wire.actorId,
        CommandKey(wire.command, wire.commandVersion), PayloadDigest(wire.targetBinding))
    check(encodeReservation(reservation) == payload)
    return reservation
}

internal suspend fun CommandReadView.reservedOperation(operationId: OperationId): FutureOperationReservation? {
    val row = aggregate(ReservedOperations.key(operationId)) ?: return null
    check(row.revision == Revision(1)) { "Future operation reservation was replaced" }
    return decodeReservation(row.key, row.schema, row.payload)
}
