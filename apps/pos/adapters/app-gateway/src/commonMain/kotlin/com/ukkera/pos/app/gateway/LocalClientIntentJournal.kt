package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.LocalAction
import com.ukkera.pos.app.LocalCommandIntent
import com.ukkera.pos.app.LocalPendingPhase
import com.ukkera.pos.app.LocalRecordReference
import com.ukkera.pos.app.LocalRecordValue
import com.ukkera.pos.app.LocalScalarInput
import com.ukkera.pos.app.LocalScalarValue
import com.ukkera.pos.app.LocalText
import com.ukkera.pos.catalog.store.LocalBeginPurchasingDocumentV1
import com.ukkera.pos.catalog.store.LocalCompletePurchasingDocumentV1
import com.ukkera.pos.database.CommandKey
import com.ukkera.pos.database.MutationReply
import com.ukkera.pos.database.MutationRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.database.semanticDigest
import com.ukkera.pos.recovery.OperationIdentity
import com.ukkera.pos.sales.store.LocalFoodStockCodecV2
import com.ukkera.pos.sales.store.LocalFoodStockRequestV2
import com.ukkera.pos.security.BranchScope
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.JournalSequence
import com.ukkera.pos.types.OperationId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex

/** Original local client owner. Session refresh/re-login never changes operation attribution. */
@Serializable
public data class LocalClientIdentity(
    public val scope: BranchScope,
    public val actorId: DomainId,
    public val deviceId: DomainId,
)

/**
 * Real protected durable client storage, not a financial ledger or a master writer lease.
 * Construction/implementations stay in this module. No in-memory success implementation exists.
 * Ordinary intent capacity is NOT protected capacity for recording physical money outcomes.
 */
public abstract class LocalClientIntentJournal internal constructor(public val owner: LocalClientIdentity) {
    /** All interactive adapters sharing this custody serialize read/stage/dispatch/ack handoffs. */
    internal val requests: Mutex = Mutex()
    internal abstract suspend fun find(operationId: OperationId): JournalRead<StoredClientIntentV1?>
    internal abstract suspend fun pending(): JournalRead<List<StoredClientIntentV1>>
    internal abstract suspend fun stage(intent: StoredClientIntentV1): JournalWrite
    internal abstract suspend fun markDispatched(operationId: OperationId): JournalWrite
    internal abstract suspend fun remember(operationId: OperationId, result: StoredKernelResultV1): JournalWrite
    internal abstract suspend fun acknowledge(operationId: OperationId, result: StoredKernelResultV1): JournalWrite
    /** Compare the exact retained original and advance only its nonsecret approval metadata. */
    internal open suspend fun updateActionApproval(previous: StoredClientIntentV1, next: StoredClientIntentV1): JournalWrite =
        JournalWrite.UNAVAILABLE
    /** All source bytes and exact step bodies/IDs are prepared before the first dispatch. */
    internal fun prepareDocumentarySource(beginOperationId: OperationId, begin: LocalBeginPurchasingDocumentV1,
                                         chunkOperationIds: List<OperationId>, completeOperationId: OperationId,
                                         complete: LocalCompletePurchasingDocumentV1,
                                         originalBytes: ByteArray): StoredClientDocumentarySourceV1 =
        StoredClientDocumentarySourceV1.prepare(owner, beginOperationId, begin, chunkOperationIds,
            completeOperationId, complete, originalBytes)
    /** Unsupported native adapters fail closed; absence of custody is never an empty source list. */
    internal open suspend fun saveDocumentarySource(source: StoredClientDocumentarySourceV1): JournalWrite = JournalWrite.UNAVAILABLE
    /** Append-only reviewed child completion retry, gated by the retained previous refusal. */
    internal open suspend fun appendDocumentaryCompletionRetry(source: StoredClientDocumentarySourceV1): JournalWrite = JournalWrite.UNAVAILABLE
    internal open suspend fun documentarySource(documentId: DomainId): JournalRead<StoredClientDocumentarySourceV1?> = JournalRead.Unavailable
    internal open suspend fun documentarySources(): JournalRead<List<StoredClientDocumentarySourceV1>> = JournalRead.Unavailable
    /** Retires client access only. It must not stop the branch master or erase durable evidence. */
    public abstract suspend fun close()
}

internal sealed interface JournalRead<out T> {
    data class Value<T>(val value: T) : JournalRead<T>
    data object Unavailable : JournalRead<Nothing>
}

internal enum class JournalWrite { DURABLE, NO_CAPACITY, IDENTITY_CONFLICT, UNAVAILABLE }

@Serializable
internal data class JournalTextV1(val english: String, val arabic: String) {
    fun ui(): LocalText = LocalText(english, arabic)
    fun validate() { ui() }
}

@Serializable
internal data class JournalValueV1(val label: JournalTextV1, val value: JournalTextV1) {
    fun ui(): LocalRecordValue = LocalRecordValue(label.ui(), value.ui())
    fun validate() { label.validate(); value.validate() }
}

internal fun LocalText.stored(): JournalTextV1 = JournalTextV1(english, arabic)
internal fun LocalRecordValue.stored(): JournalValueV1 = JournalValueV1(label.stored(), value.stored())

/** Exact retained kernel result. Replay flags are observations, not part of original outcome identity. */
@Serializable
internal data class StoredKernelResultV1(
    val operationId: OperationId,
    val originalActorId: DomainId,
    val committed: Boolean,
    val code: String,
    val payloadHex: String,
    val authorityEpoch: Revision,
    val sequence: JournalSequence,
    val recordedAt: UtcInstant,
) {
    fun validate() {
        require(code.length in 1..128 && code.all { it.code in 0x21..0x7e })
        decodeHex(payloadHex, 65_536).fill(0)
    }

    companion object {
        fun from(result: MutationReply.Recorded): StoredKernelResultV1 = StoredKernelResultV1(
            result.operationId, result.originalActorId, result.committed, result.code,
            result.result.copyBytes().useWiped(::encodeHex), result.authorityEpoch, result.sequence, result.recordedAt,
        )
    }
}

@Serializable
@OptIn(ExperimentalSerializationApi::class)
internal data class StoredClientIntentV1(
    val version: Int = 1,
    val owner: LocalClientIdentity,
    val operationId: OperationId,
    val action: String,
    val formVersion: Int,
    val inputDigest: PayloadDigest,
    val commandName: String,
    val commandVersion: Int,
    /** Authority selector metadata, never a fabricated approval proof or business-digest input. */
    val actionApprovalId: DomainId? = null,
    val payloadHex: String,
    val semanticSha256: PayloadDigest,
    val label: JournalTextV1,
    val summary: List<JournalValueV1>,
    val phase: String = LocalPendingPhase.STAGED.name,
    val recorded: StoredKernelResultV1? = null,
    val acknowledged: Boolean = false,
    /** Omitted on old profiles, preserving their exact canonical journal bytes. */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val actionApproval: StoredClientActionApprovalV1? = null,
) {
    val localAction: LocalAction get() = LocalAction.valueOf(action)
    val localPhase: LocalPendingPhase get() = LocalPendingPhase.valueOf(phase)
    fun request(): MutationRequest = MutationRequest(operationId, CommandKey(commandName, commandVersion),
        decodeHex(payloadHex, 65_536).useWiped { StoredBytes(it) }, actionApprovalId)
    fun identity(): OperationIdentity = OperationIdentity(owner.scope.branchId, operationId, owner.actorId, 1, semanticSha256.value)

    fun sameIntent(other: StoredClientIntentV1): Boolean {
        fun StoredClientIntentV1.original(): StoredClientIntentV1 = copy(phase = LocalPendingPhase.STAGED.name,
            recorded = null, acknowledged = false,
            actionApprovalId = if (actionApproval == null) actionApprovalId else null,
            actionApproval = actionApproval?.original())
        return original() == other.original()
    }

    fun validate(expectedOwner: LocalClientIdentity) {
        require(version == 1 && owner == expectedOwner && formVersion in 1..1000)
        localAction; localPhase; label.validate()
        require(summary.size in 1..32)
        summary.forEach { it.validate() }
        require(journalJson.encodeToString(serializer(), this).encodeToByteArray().size <= MAX_CLIENT_ENTRY_BYTES)
        val request = request()
        val normalized = normalizeLocalRequest(request.command, request.payload)
        require(actionMatches(localAction, request))
        if (request.command in unpricedArrivalKeysV2) {
            require(formVersion == 2)
            if (actionApproval == null) require(actionApprovalId == null)
            else actionApproval.validate(this)
        } else require(actionApproval == null)
        if (localAction == LocalAction.FOOD_ASSIGN_TABLE) require(formVersion == 1)
        if (localAction == LocalAction.RETURN_STOCK_POST) require(formVersion == 1)
        if (localAction == LocalAction.SUPPLIER_INVOICE_CAPTURE ||
            localAction == LocalAction.SUPPLIER_INVOICE_MATCH_RECOGNIZE) require(formVersion == 1)
        if (localAction == LocalAction.RETAIL_CUSTOMER_RECEIPT) require(formVersion in 1..2)
        if (localAction == LocalAction.FOOD_ADD) {
            // The semantic digest deliberately excludes UI metadata. Validate the saved
            // Add form/profile pair explicitly so corrupted metadata cannot reconstruct
            // an unsupported form or reinterpret the original guarded/legacy payload.
            val requiredVersion = if (request.command == LocalFoodStockCodecV2.COMMAND)
                when (LocalFoodStockCodecV2.decode(request.payload)) {
                    is LocalFoodStockRequestV2.ActivityProtectedQuotedAdd -> 3
                    is LocalFoodStockRequestV2.ActivityProtectedAdd -> 2
                    else -> 1
                } else 1
            require(formVersion == requiredVersion)
        }
        require(normalized == request.payload && semanticDigest(owner.scope, owner.actorId, request.command, normalized) == semanticSha256)
        if (localAction == LocalAction.RETURN_RIGHTS_ACTIVATE) validateReturnRightsIntent(this)
        require((localPhase == LocalPendingPhase.RECORDED_UNACKNOWLEDGED) == (recorded != null))
        require(!acknowledged || recorded != null)
        recorded?.let {
            it.validate()
            require(it.operationId == operationId && it.originalActorId == owner.actorId)
        }
    }

    companion object {
        fun staged(owner: LocalClientIdentity, intent: LocalCommandIntent, request: MutationRequest,
                   label: LocalText, summary: List<LocalRecordValue>): StoredClientIntentV1 {
            require(request.operationId == intent.operationId)
            val normalized = normalizeLocalRequest(request.command, request.payload)
            return StoredClientIntentV1(owner = owner, operationId = intent.operationId, action = intent.action.name,
                formVersion = intent.formVersion, inputDigest = inputDigest(intent), commandName = request.command.name,
                commandVersion = request.command.version, actionApprovalId = request.actionApprovalId,
                payloadHex = normalized.copyBytes().useWiped(::encodeHex),
                semanticSha256 = semanticDigest(owner.scope, owner.actorId, request.command, normalized),
                label = label.stored(), summary = summary.map { it.stored() },
                actionApproval = StoredClientActionApprovalV1.staged(intent)).also { it.validate(owner) }
        }
    }
}

internal const val MAX_CLIENT_PENDING = 64
internal const val MAX_CLIENT_ENTRY_BYTES = 256 * 1024
internal val journalJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    isLenient = false
    coerceInputValues = false
}

/** Framed UI input identity only; the kernel semantic digest is separately computed publicly. */
internal fun inputDigest(intent: LocalCommandIntent): PayloadDigest {
    fun LocalScalarValue.hasParent(): Boolean = when (this) {
        is LocalScalarValue.Reference -> value.parentId != null
        is LocalScalarValue.References -> values.any { it.parentId != null }
        else -> false
    }
    // Preserve legacy unscoped UI identities; nested coordinates use an explicitly framed v2.
    val scoped = intent.reference?.parentId != null || intent.values.any { it.value.hasParent() } ||
        intent.lines.any { line -> line.values.any { it.value.hasParent() } }
    val text = buildString {
        fun frame(value: String) { append(value.length); append(':'); append(value) }
        fun reference(value: LocalRecordReference?) {
            frame(value?.kind?.name.orEmpty()); frame(value?.id?.value.orEmpty()); frame(value?.revision?.value?.toString().orEmpty())
            if (scoped) frame(value?.parentId?.value.orEmpty())
        }
        fun scalar(input: LocalScalarInput) {
            frame(input.field.name)
            when (val value = input.value) {
                is LocalScalarValue.Text -> { frame("text"); frame(value.value) }
                is LocalScalarValue.Identifier -> { frame("id"); frame(value.value.value) }
                is LocalScalarValue.Integer -> { frame("integer"); frame(value.value.toString()) }
                is LocalScalarValue.Decimal -> { frame("decimal"); frame(value.canonical) }
                is LocalScalarValue.Date -> { frame("date"); frame(value.iso) }
                is LocalScalarValue.Choice -> { frame("choice"); frame(value.code) }
                is LocalScalarValue.Reference -> { frame("reference"); reference(value.value) }
                is LocalScalarValue.Choices -> { frame("choices"); frame(value.codes.size.toString()); value.codes.forEach(::frame) }
                is LocalScalarValue.References -> { frame("references"); frame(value.values.size.toString()); value.values.forEach(::reference) }
                is LocalScalarValue.Flag -> { frame("flag"); frame(value.value.toString()) }
            }
        }
        frame(if (scoped) "ukkera.local-ui-intent/v2" else "ukkera.local-ui-intent/v1"); frame(intent.operationId.value); frame(intent.action.name)
        frame(intent.formVersion.toString()); reference(intent.reference)
        frame(intent.values.size.toString()); intent.values.sortedBy { it.field.ordinal }.forEach(::scalar)
        frame(intent.lineField?.name.orEmpty()); frame(intent.lines.size.toString())
        intent.lines.forEach { line ->
            frame(line.lineId.value); frame(line.values.size.toString()); line.values.sortedBy { it.field.ordinal }.forEach(::scalar)
        }
        // Additive v2 carrier identity. Null preserves every existing v1 input digest;
        // a changed manifest/policy/rule cannot hit the saved-ID replay shortcut.
        intent.arrivalEvidence?.let { frame("ukkera.arrival-selection/v2"); frame(it.equalityDigest.value) }
    }
    return text.encodeToByteArray(throwOnInvalidSequence = true).useWiped { sha256(it) }
}

internal fun encodeHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    val digits = "0123456789abcdef"
    for (byte in bytes) { append(digits[(byte.toInt() and 255) ushr 4]); append(digits[byte.toInt() and 15]) }
}

internal fun decodeHex(value: String, maximumBytes: Int): ByteArray {
    require(value.length <= maximumBytes * 2 && value.length % 2 == 0)
    require(value.all { it in '0'..'9' || it in 'a'..'f' })
    return ByteArray(value.length / 2) { index ->
        ((value[index * 2].digitToInt(16) shl 4) or value[index * 2 + 1].digitToInt(16)).toByte()
    }
}

internal inline fun <T> ByteArray.useWiped(block: (ByteArray) -> T): T = try { block(this) } finally { fill(0) }
