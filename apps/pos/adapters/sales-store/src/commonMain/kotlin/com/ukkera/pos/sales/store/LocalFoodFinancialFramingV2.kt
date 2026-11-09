package com.ukkera.pos.sales.store

import com.ukkera.pos.database.*
import com.ukkera.pos.pricing.StoredPricingSnapshotCodec
import com.ukkera.pos.sales.FoodFinancialFramesV2
import com.ukkera.pos.sales.FoodPriceBatch
import com.ukkera.pos.security.*
import com.ukkera.pos.types.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/** Raw leaf bytes are packed, not escaped into another JSON string or one BEFORE row each. */
@Serializable internal data class FoodFinancialLeafV2(val offset: Int, val byteCount: Int, val digest: PayloadDigest)
@Serializable internal data class FoodFinancialChunkV2(val id: DomainId, val byteCount: Int, val digest: PayloadDigest)
@Serializable internal data class FoodFinancialManifestV2(val version: Int, val byteCount: Int,
    val leaves: List<FoodFinancialLeafV2>, val chunks: List<FoodFinancialChunkV2>,
    val inputSet: FoodFinancialLeafV2, val cash: FoodFinancialLeafV2?)
@Serializable internal data class FoodFinancialRootV2(val profile: String, val scope: BranchScope, val kind: String,
    val id: DomainId, val revision: Revision, val operationId: OperationId, val body: FoodFinancialLeafV2)
@Serializable private data class FoodFinancialStockFrameV2(val scope: BranchScope, val domainRevision: Revision,
    val snapshot: FoodFinancialLeafV2, val producerCommand: String, val producerVersion: Int,
    val producerActorId: DomainId, val producerSemanticDigest: PayloadDigest)
@Serializable private data class FoodFinancialInputFrameV2(val kind: String, val id: DomainId, val revision: Revision,
    val schema: String, val digest: PayloadDigest, val operationId: OperationId, val payload: FoodFinancialLeafV2,
    val stock: FoodFinancialStockFrameV2?, val financialBody: FoodFinancialLeafV2?)
@Serializable private data class FoodFinancialInputSetV2(val profile: String, val inputs: List<FoodFinancialInputFrameV2>)

internal fun foodFinancialSchemaV2(kind: String): String = "$kind/v2"
// SEND_RESERVED: collection/order/owner +32 portions +32 batches +32 reservations
// +24 already-issued sources +32 existing station indexes =155 literal input slots.
// This does not admit155 physical rows or multiply any byte/effect budget.
internal const val FOOD_FINANCIAL_MAX_INPUTS_V2: Int = 155
private const val FOOD_FRAME_PROFILE_V2 = "GENUINE_LOCAL_FOOD_FINANCIAL_FRAME_V2"
private const val FOOD_FRAME_BYTES_V2 = 65_536
private const val FOOD_BUNDLE_BYTES_V2 = 1_048_576
private const val FOOD_FRAME_LEAVES_V2 = 384 // Source rows, stock literals, normalized bases/bodies, cash and input set.
private fun foodFinancialChunkIdV2(operation: OperationId, index: Int): DomainId =
    foodId("financial-frame-v2", "${operation.value}/$index")

/** One original operation owns one finite, canonical, deduplicated literal bundle. */
internal class FoodFinancialBundleWriterV2(private val operationId: OperationId,
    private val allowStockInputs: Boolean = false) {
    private val bytes = mutableListOf<ByteArray>()
    private val leaves = mutableListOf<FoodFinancialLeafV2>()
    private var size = 0
    private var sealed = false

    private fun leaf(value: ByteArray): FoodFinancialLeafV2 {
        check(!sealed)
        foodCapacity(value.isNotEmpty() && value.size <= FOOD_BUNDLE_BYTES_V2)
        val digest = sha256(value)
        val existing = leaves.indexOfFirst { it.digest == digest }
        if (existing >= 0) {
            check(bytes[existing].contentEquals(value)) { "food.frame.digest_collision" }
            return leaves[existing]
        }
        foodCapacity(leaves.size < FOOD_FRAME_LEAVES_V2 && value.size <= FOOD_BUNDLE_BYTES_V2 - size)
        return FoodFinancialLeafV2(size, value.size, digest).also {
            leaves += it; bytes += value.copyOf(); size += value.size
        }
    }

    fun document(value: FoodFinancialEnvelopeV1): StoredBytes {
        check(value.framed && value.operationId == operationId)
        foodFinancialBodySourcesV2(value.body).forEach { leaf(it.encodeToByteArray(throwOnInvalidSequence = true)) }
        val body = leaf(foodFrameEncodeV2(value.body, FoodFinancialBodyV1.serializer()))
        return localSalesEncode(FoodFinancialRootV2(FOOD_FRAME_PROFILE_V2, value.scope, value.kind,
            value.id, value.revision, value.operationId, body), FoodFinancialRootV2.serializer(), FOOD_FRAME_BYTES_V2)
    }

    fun finish(inputs: List<FoodFinancialInputV1>, cash: FoodFinancialCashInputV1?): Pair<FoodFinancialManifestV2, List<AggregateChange>> {
        check(!sealed)
        foodCapacity(inputs.size <= FOOD_FINANCIAL_MAX_INPUTS_V2)
        val recorded = inputs.map { value ->
            val stock = value.stock?.let { literal -> FoodFinancialStockFrameV2(literal.scope, literal.domainRevision,
                leaf(literal.snapshot.encodeToByteArray(throwOnInvalidSequence = true)), literal.producerCommand,
                literal.producerVersion, literal.producerActorId, literal.producerSemanticDigest) }
            val body = value.financial?.takeIf { it.framed }?.let { source ->
                check(value.schema == foodFinancialSchemaV2(value.kind) || allowStockInputs && value.schema == foodStockSchema(value.kind))
                foodFinancialBodySourcesV2(source.body).forEach { leaf(it.encodeToByteArray(throwOnInvalidSequence = true)) }
                val witness = leaf(foodFrameEncodeV2(source.body, FoodFinancialBodyV1.serializer()))
                if (value.schema == foodFinancialSchemaV2(value.kind)) {
                    val original = foodFinancialRootV2(value.bytes())
                    check(original.body.byteCount == witness.byteCount && original.body.digest == witness.digest)
                } else requireFoodStockFinancialLiteral(value.bytes(), source)
                witness
            }
            FoodFinancialInputFrameV2(value.kind, value.id, value.revision, value.schema, value.digest, value.operationId,
                leaf(value.bytes().copyBytes()), stock, body)
        }
        val inputSet = leaf(foodFrameEncodeV2(FoodFinancialInputSetV2(FOOD_FRAME_PROFILE_V2, recorded), FoodFinancialInputSetV2.serializer()))
        val cashLeaf = cash?.let { leaf(foodFrameEncodeV2(it, FoodFinancialCashInputV1.serializer())) }
        sealed = true
        val packed = ByteArray(size)
        bytes.forEachIndexed { index, value -> value.copyInto(packed, leaves[index].offset) }
        val descriptors = mutableListOf<FoodFinancialChunkV2>()
        val changes = mutableListOf<AggregateChange>()
        var offset = 0
        while (offset < size) {
            val chunk = packed.copyOfRange(offset, minOf(size, offset + FOOD_FRAME_BYTES_V2))
            val id = foodFinancialChunkIdV2(operationId, descriptors.size)
            descriptors += FoodFinancialChunkV2(id, chunk.size, sha256(chunk))
            changes += AggregateChange(AggregateKey(LocalFoodFinancialProfileV1.FRAME, id), null,
                foodFinancialSchemaV2(LocalFoodFinancialProfileV1.FRAME), StoredBytes(chunk))
            offset += chunk.size
        }
        return FoodFinancialManifestV2(2, size, leaves.toList(), descriptors, inputSet, cashLeaf) to changes
    }
}

/** All references terminate in literal bytes of THIS producer, never recursive history pointers. */
internal class FoodFinancialBundleReaderV2(private val evidence: RetainedCommandEvidence,
    private val manifest: FoodFinancialManifestV2, private val allowStockInputs: Boolean = false) {
    private val used = mutableSetOf<FoodFinancialLeafV2>()
    private val packed: ByteArray
    private var inputCache: List<FoodFinancialInputV1>? = null
    private var inputFrames: List<FoodFinancialInputFrameV2>? = null
    private val documents = mutableMapOf<AggregateKey, List<FoodFinancialLeafV2>>()
    private val beforeSources = mutableMapOf<AggregateKey, List<FoodFinancialLeafV2>>()
    val chunkKeys: Set<AggregateKey>
        get() = manifest.chunks.map { AggregateKey(LocalFoodFinancialProfileV1.FRAME, it.id) }.toSet()

    init {
        check(manifest.version == 2 && manifest.byteCount in 1..FOOD_BUNDLE_BYTES_V2 &&
            manifest.leaves.size in 1..FOOD_FRAME_LEAVES_V2 && manifest.chunks.size ==
            (manifest.byteCount + FOOD_FRAME_BYTES_V2 - 1) / FOOD_FRAME_BYTES_V2)
        var offset = 0
        for (leaf in manifest.leaves) {
            check(leaf.offset == offset && leaf.byteCount in 1..manifest.byteCount - offset)
            offset += leaf.byteCount
        }
        check(offset == manifest.byteCount && manifest.leaves.map { it.digest }.distinct().size == manifest.leaves.size)
        packed = ByteArray(manifest.byteCount)
        offset = 0
        for ((index, descriptor) in manifest.chunks.withIndex()) {
            val expectedSize = minOf(FOOD_FRAME_BYTES_V2, manifest.byteCount - offset)
            check(descriptor.id == foodFinancialChunkIdV2(evidence.operationId, index) && descriptor.byteCount == expectedSize)
            val row = evidence.aggregateWrites.single { it.key == AggregateKey(LocalFoodFinancialProfileV1.FRAME, descriptor.id) }
            check(row.storageGeneration == Revision(1) && row.schema == foodFinancialSchemaV2(LocalFoodFinancialProfileV1.FRAME) &&
                row.payload.size == expectedSize && row.payloadDigest == descriptor.digest && sha256(row.payload.copyBytes()) == descriptor.digest)
            row.payload.copyBytes().copyInto(packed, offset)
            offset += expectedSize
        }
        check(evidence.aggregateWrites.filter { it.key.kind == LocalFoodFinancialProfileV1.FRAME }.map { it.key }.toSet() == chunkKeys)
        // Validate every declared leaf, including any later rejected as unused. No overlap/gap,
        // alternative chunking, missing tail or successful prefix is a valid bundle.
        for (leaf in manifest.leaves) check(sha256(packed.copyOfRange(leaf.offset, leaf.offset + leaf.byteCount)) == leaf.digest)
    }

    private fun bytes(leaf: FoodFinancialLeafV2): ByteArray {
        check(leaf in manifest.leaves)
        used += leaf
        return packed.copyOfRange(leaf.offset, leaf.offset + leaf.byteCount)
    }
    fun cash(): FoodFinancialCashInputV1? = manifest.cash?.let { foodFrameDecodeV2(bytes(it), FoodFinancialCashInputV1.serializer()) }

    fun inputs(): List<FoodFinancialInputV1> {
        inputCache?.let { return it }
        val set = foodFrameDecodeV2(bytes(manifest.inputSet), FoodFinancialInputSetV2.serializer())
        check(set.profile == FOOD_FRAME_PROFILE_V2 && set.inputs.size <= FOOD_FINANCIAL_MAX_INPUTS_V2 &&
            set.inputs.map { it.kind to it.id }.distinct().size == set.inputs.size)
        inputFrames = set.inputs
        val raw = set.inputs.map { frame ->
            val payload = bytes(frame.payload)
            check(payload.size <= FOOD_FRAME_BYTES_V2 && frame.revision.value >= 1 && frame.operationId != evidence.operationId &&
                sha256(payload) == frame.digest)
            val stock = frame.stock?.let { value -> FoodFinancialStockLiteralV1(value.scope, value.domainRevision,
                bytes(value.snapshot).decodeToString(throwOnInvalidSequence = true), value.producerCommand,
                value.producerVersion, value.producerActorId, value.producerSemanticDigest) }
            FoodFinancialInputV1(frame.kind, frame.id, frame.revision, frame.schema, frame.digest, frame.operationId,
                payload.decodeToString(throwOnInvalidSequence = true), stock)
        }
        val prices = foodFinancialPriceBasesV2(raw, evidence.scope, allowStockInputs)
        val result = raw.mapIndexed { index, input ->
            val body = set.inputs[index].financialBody
            if (input.kind !in foodFinancialDocumentKinds) {
                check(body == null); input
            } else if (input.schema == foodFinancialSchemaV2(input.kind)) {
                val root = foodFinancialRootV2(input.bytes())
                val witness = checkNotNull(body)
                check(root.scope == evidence.scope && root.kind == input.kind && root.id == input.id &&
                    root.revision == input.revision && root.operationId == input.operationId &&
                    root.body.byteCount == witness.byteCount && root.body.digest == witness.digest)
                val (restored, sources) = readBody(witness)
                beforeSources[input.key()] = sources
                input.copy(financial = restore(root, restored, prices))
            } else if (allowStockInputs && input.schema == foodStockSchema(input.kind) && body != null) {
                val (restored, sources) = readBody(body)
                beforeSources[input.key()] = sources
                val source = FoodFinancialEnvelopeV1(evidence.scope, input.kind, input.id, input.revision,
                    input.operationId, restored, framed = true, originalBatches = prices).also(::requireFoodFinancialEnvelope)
                requireFoodStockFinancialLiteral(input.bytes(), source)
                input.copy(financial = source)
            } else {
                check(body == null && (input.schema == LocalFoodFinancialProfileV1.schema(input.kind) ||
                    allowStockInputs && input.schema == foodStockSchema(input.kind)))
                input.copy(financial = foodFinancialEnvelope(input.bytes()))
            }
        }
        inputCache = result
        return result
    }

    fun document(payload: StoredBytes): FoodFinancialEnvelopeV1 {
        val root = foodFinancialRootV2(payload)
        check(root.scope == evidence.scope && root.operationId == evidence.operationId)
        val key = AggregateKey(root.kind, root.id)
        val (restored, sources) = readBody(root.body)
        val layout = sources + root.body
        documents[key]?.let { check(it == layout) }
        documents[key] = layout
        return restore(root, restored, foodFinancialPriceBasesV2(inputs(), evidence.scope, allowStockInputs))
    }

    private fun readBody(leaf: FoodFinancialLeafV2): Pair<FoodFinancialBodyV1, List<FoodFinancialLeafV2>> {
        val value = foodFrameDecodeV2(bytes(leaf), FoodFinancialBodyV1.serializer())
        val snapshot = when (value) {
            is FoodFinancialBodyV1.Collection -> value.snapshot
            is FoodFinancialBodyV1.Sale -> value.snapshot
            is FoodFinancialBodyV1.Entitlement -> value.snapshot
            is FoodFinancialBodyV1.Refund -> value.snapshot
            else -> return value to emptyList()
        }
        val leaves = FoodFinancialFramesV2.references(snapshot).map { reference -> manifest.leaves.single {
            it.byteCount == reference.byteCount && it.digest == reference.digest
        } }
        val sources = leaves.map { bytes(it).decodeToString(throwOnInvalidSequence = true) }
        val restored = when (value) {
            is FoodFinancialBodyV1.Collection -> value.copy(sources = sources)
            is FoodFinancialBodyV1.Sale -> value.copy(sources = sources)
            is FoodFinancialBodyV1.Entitlement -> value.copy(sources = sources)
            is FoodFinancialBodyV1.Refund -> value.copy(sources = sources)
            else -> error("food.frame.financial_body_kind")
        }
        return restored to leaves
    }

    private fun restore(root: FoodFinancialRootV2, body: FoodFinancialBodyV1, prices: List<FoodPriceBatch>): FoodFinancialEnvelopeV1 =
        FoodFinancialEnvelopeV1(root.scope, root.kind, root.id, root.revision, root.operationId, body,
            framed = true, originalBatches = prices).also(::requireFoodFinancialEnvelope)

    fun requireCompleteLeaves(after: List<FoodAfterRefV1>) {
        check(used == manifest.leaves.toSet()) { "food.frame.unused_leaf" }
        // Reconstruct the writer's first-use layout from the event's ordered AFTERs and
        // ordered inputs, not from hash-map or aggregate iteration order. Equal literal
        // bytes intentionally share a leaf; a reordered or alternate packed layout is not
        // another encoding of the profile, even if its individual digests are consistent.
        val expected = linkedSetOf<FoodFinancialLeafV2>()
        val afterKeys = after.filter { it.kind in foodFinancialDocumentKinds }.map { AggregateKey(it.kind, it.id) }
        check(documents.keys == afterKeys.toSet())
        afterKeys.forEach { expected.addAll(documents.getValue(it)) }
        for (input in checkNotNull(inputFrames)) {
            input.stock?.let { expected += it.snapshot }
            input.financialBody?.let {
                expected.addAll(beforeSources.getValue(AggregateKey(input.kind, input.id)))
                expected += it
            }
            expected += input.payload
        }
        expected += manifest.inputSet
        manifest.cash?.let { expected += it }
        check(expected.toList() == manifest.leaves) { "food.frame.noncanonical_layout" }
    }
}

/** Transient hydration is never serialized into another escaped financial ancestry string. */
private fun foodFinancialBodySourcesV2(value: FoodFinancialBodyV1): List<String> = when (value) {
    is FoodFinancialBodyV1.Collection -> value.sources
    is FoodFinancialBodyV1.Sale -> value.sources
    is FoodFinancialBodyV1.Entitlement -> value.sources
    is FoodFinancialBodyV1.Refund -> value.sources
    else -> emptyList()
}

internal fun foodFinancialRootV2(bytes: StoredBytes): FoodFinancialRootV2 =
    localSalesDecode(bytes, FoodFinancialRootV2.serializer(), FOOD_FRAME_BYTES_V2).also {
        check(it.profile == FOOD_FRAME_PROFILE_V2 && it.kind in foodFinancialDocumentKinds && it.revision.value >= 1)
    }

internal fun foodFinancialPriceBasesV2(inputs: List<FoodFinancialInputV1>, scope: BranchScope,
    allowStockInputs: Boolean = false): List<FoodPriceBatch> =
    inputs.filter { it.kind == LocalFoodProfileV1.PRICE_BATCH }.map { input ->
        check(input.stock == null && (input.schema == LocalFoodProfileV1.schema(input.kind) ||
            allowStockInputs && input.schema == foodStockSchema(input.kind)) && input.revision == Revision(1) &&
            input.digest == sha256(input.bytes().copyBytes()))
        val value = foodEnvelope(input.bytes())
        check(value.scope == scope && value.id == input.id && value.kind == input.kind && value.revision == input.revision && value.operationId == input.operationId)
        FoodPriceBatch(input.id, input.digest, StoredPricingSnapshotCodec.decode((value.body as FoodBodyV1.PriceBatch).quote))
    }.also { check(it.size <= 32 && it.map { value -> value.id }.distinct().size == it.size) }

/** Canonical logical data remains bounded before decoding; never allocate oversized StoredBytes. */
private fun <T> foodFrameEncodeV2(value: T, serializer: KSerializer<T>): ByteArray =
    localSalesJson.encodeToString(serializer, value).encodeToByteArray(throwOnInvalidSequence = true).also {
        foodCapacity(it.size in 1..FOOD_BUNDLE_BYTES_V2)
    }

private fun <T> foodFrameDecodeV2(bytes: ByteArray, serializer: KSerializer<T>): T {
    check(bytes.size in 1..FOOD_BUNDLE_BYTES_V2)
    val text = bytes.decodeToString(throwOnInvalidSequence = true)
    val stack = mutableListOf<Pair<Char, Int>>()
    var quoted = false; var escaped = false
    for (c in text) {
        if (quoted) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
        } else when (c) {
            '"' -> quoted = true
            '{', '[' -> { check(stack.size < 32); stack += c to 0 }
            '}', ']' -> { check(stack.isNotEmpty() && stack.last().first == if (c == ']') '[' else '{'); stack.removeAt(stack.lastIndex) }
            ',' -> if (stack.lastOrNull()?.first == '[') {
                val count = stack.last().second + 1
                check(count < 512); stack[stack.lastIndex] = '[' to count
            }
        }
    }
    check(stack.isEmpty() && !quoted)
    val value = localSalesJson.decodeFromString(serializer, text)
    check(localSalesJson.encodeToString(serializer, value) == text)
    return value
}
