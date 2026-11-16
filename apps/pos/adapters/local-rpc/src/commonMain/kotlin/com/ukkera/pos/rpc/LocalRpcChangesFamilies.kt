package com.ukkera.pos.rpc

import com.ukkera.pos.database.LocalMasterChangesRpcProtocolV1
import com.ukkera.pos.database.LocalMasterChangesFailureV1
import com.ukkera.pos.database.ReadRequest
import com.ukkera.pos.database.StoredBytes
import com.ukkera.pos.security.PayloadDigest
import com.ukkera.pos.security.sha256
import dev.p2pkit.rpc.RpcApplicationSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Bounded retained selector DATA for actual read-only SDK sessions. Membership never grants
 * access: the current typed queries are authorized again at every invocation and disclosure.
 * Session removal follows successful exact SDK revocation; Receipt observations do not evict.
 */
internal class LocalRpcChangesFamilies {
    internal class Family internal constructor(val digest: PayloadDigest,
        internal val selectors: List<Selector>) {
        val selections: List<ReadRequest> = selectors.map { it.selection }
    }

    internal class Selector internal constructor(val digest: PayloadDigest, val selection: ReadRequest)
    private class Session {
        val selectors = LinkedHashMap<PayloadDigest, Selector>()
        val families = LinkedHashMap<PayloadDigest, Family>()
        var bytes: Long = 0
    }

    internal sealed interface Admission {
        class Retained(val family: Family) : Admission
        data object Capacity : Admission
        data object Conflict : Admission
    }

    private val gate = Mutex()
    private val sessions = LinkedHashMap<RpcApplicationSession, Session>()
    private var selectorCount = 0
    private var familyCount = 0
    private var payloadBytes = 0L

    /** Preflight counts/bytes and exact digest equality before making any retained copy. */
    suspend fun admit(session: RpcApplicationSession, selections: List<ReadRequest>): Admission {
        require(selections.size in 1..32 && selections.sumOf { it.payload.size.toLong() } <= 65_536)
        val digests = selections.map(LocalMasterChangesRpcProtocolV1::selectorBinding)
        if (digests.toSet().size != digests.size) return Admission.Conflict
        // Retain only the grouping actually requested. A later member pull creates its
        // singleton grouping lazily and references the same retained selector payload.
        val families = mapOf(familyDigest(digests) to digests)
        return gate.withLock {
            val retained = sessions[session]
            for ((index, digest) in digests.withIndex()) {
                val previous = retained?.selectors?.get(digest) ?: continue
                if (!sameSelection(previous.selection, selections[index])) return@withLock Admission.Conflict
            }
            for ((digest, members) in families) {
                val previous = retained?.families?.get(digest) ?: continue
                if (previous.selectors.map { it.digest } != members) return@withLock Admission.Conflict
            }
            val newSelectors = digests.indices.filter { retained?.selectors?.containsKey(digests[it]) != true }
            val newFamilies = families.keys.count { retained?.families?.containsKey(it) != true }
            val bytes = newSelectors.sumOf { selections[it].payload.size.toLong() }
            if ((retained?.selectors?.size ?: 0) + newSelectors.size > MAX_SESSION_SELECTORS ||
                (retained?.families?.size ?: 0) + newFamilies > MAX_SESSION_FAMILIES ||
                (retained?.bytes ?: 0) + bytes > MAX_SESSION_BYTES ||
                selectorCount + newSelectors.size > MAX_GLOBAL_SELECTORS ||
                familyCount + newFamilies > MAX_GLOBAL_FAMILIES || payloadBytes + bytes > MAX_GLOBAL_BYTES)
                return@withLock Admission.Capacity
            // No state mutation precedes all successful defensive copies. Batch and later
            // member single-query families reference the same one retained payload per selector.
            val copies = newSelectors.associate { index ->
                val selection = selections[index]
                val temporary = selection.payload.copyBytes()
                val copy = try { ReadRequest(selection.query.copy(), StoredBytes(temporary)) }
                    finally { temporary.fill(0) }
                digests[index] to Selector(digests[index], copy)
            }
            val target = retained ?: Session()
            val createdFamilies = families.filterKeys { !target.families.containsKey(it) }.mapValues { (digest, members) ->
                Family(digest, members.map { checkNotNull(target.selectors[it] ?: copies[it]) })
            }
            target.selectors.putAll(copies)
            target.families.putAll(createdFamilies)
            target.bytes += bytes
            sessions[session] = target
            selectorCount += copies.size; familyCount += createdFamilies.size; payloadBytes += bytes
            Admission.Retained(checkNotNull(target.families[familyDigest(digests)]))
        }
    }

    suspend fun capture(session: RpcApplicationSession, digest: PayloadDigest): Family? =
        gate.withLock { sessions[session]?.families?.get(digest) }

    suspend fun current(session: RpcApplicationSession, family: Family): Boolean =
        gate.withLock { sessions[session]?.families?.get(family.digest) === family }

    /** Caller has awaited revocation and private binding release of this exact SDK session. */
    suspend fun removeRevoked(session: RpcApplicationSession) = gate.withLock {
        check(!session.isActive)
        sessions.remove(session)?.let {
            selectorCount -= it.selectors.size; familyCount -= it.families.size; payloadBytes -= it.bytes
        }
        check(selectorCount >= 0 && familyCount >= 0 && payloadBytes >= 0)
    }

    suspend fun removeAllRevoked() = gate.withLock {
        check(sessions.keys.none { it.isActive })
        sessions.clear(); selectorCount = 0; familyCount = 0; payloadBytes = 0
    }

    private companion object {
        const val MAX_SESSION_SELECTORS = 128
        const val MAX_SESSION_FAMILIES = 128
        const val MAX_SESSION_BYTES = 262_144L
        const val MAX_GLOBAL_SELECTORS = 4_096
        const val MAX_GLOBAL_FAMILIES = 4_096
        const val MAX_GLOBAL_BYTES = 8_388_608L

        fun familyDigest(digests: List<PayloadDigest>): PayloadDigest {
            val bytes = ("ukkera.pos.changes.family/v1\n" + digests.joinToString("\n") { it.value }).encodeToByteArray()
            return try { sha256(bytes) } finally { bytes.fill(0) }
        }
    }
}

internal fun sameSelection(left: ReadRequest, right: ReadRequest): Boolean =
    left.query == right.query && left.payload == right.payload

/** Immutable per-call correlation DATA captured by the actual SDK, never a caller grant. */
internal class LocalRpcChangesScope private constructor(
    val procedure: String,
    val family: PayloadDigest?,
    val arguments: PayloadDigest,
    val projection: PayloadDigest?,
    val refusal: LocalMasterChangesFailureV1?,
) {
    val noDataOnly: Boolean get() = refusal != null
    val resource: String = when (refusal) {
        LocalMasterChangesFailureV1.CAPACITY_EXCEEDED -> "$procedure#capacity#${arguments.value}"
        LocalMasterChangesFailureV1.MALFORMED_REQUEST -> "$procedure#malformed#${arguments.value}"
        null -> "$procedure#${family!!.value}#${arguments.value}#${projection!!.value}"
        else -> error("Unsupported changes refusal scope")
    }

    init {
        require((family == null) == noDataOnly && (projection == null) == noDataOnly)
        require(resource.length <= 256) { "Changes scope exceeds SDK resource bound" }
    }

    companion object {
        fun data(procedure: String, family: PayloadDigest, arguments: PayloadDigest, projection: PayloadDigest): LocalRpcChangesScope =
            LocalRpcChangesScope(procedure, family, arguments, projection, null)
        fun capacity(procedure: String, arguments: PayloadDigest): LocalRpcChangesScope =
            LocalRpcChangesScope(procedure, null, arguments, null, LocalMasterChangesFailureV1.CAPACITY_EXCEEDED)
        fun malformed(procedure: String, arguments: PayloadDigest): LocalRpcChangesScope =
            LocalRpcChangesScope(procedure, null, arguments, null, LocalMasterChangesFailureV1.MALFORMED_REQUEST)
        fun parse(resource: String, procedure: String): LocalRpcChangesScope? = try {
            if (resource.length > 256) null else {
                val parts = resource.split('#')
                if (parts.firstOrNull() != procedure) null
                else if (parts.size == 3 && parts[1] == "capacity") capacity(procedure, PayloadDigest(parts[2]))
                else if (parts.size == 3 && parts[1] == "malformed") malformed(procedure, PayloadDigest(parts[2]))
                else if (parts.size == 4) data(procedure, PayloadDigest(parts[1]), PayloadDigest(parts[2]), PayloadDigest(parts[3]))
                else null
            }
        } catch (_: IllegalArgumentException) { null }
    }
}
