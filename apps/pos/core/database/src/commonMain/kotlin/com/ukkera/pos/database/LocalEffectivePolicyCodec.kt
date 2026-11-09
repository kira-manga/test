package com.ukkera.pos.database

import com.ukkera.pos.security.*
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.MinorUnitsSerializer
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Versioned extension router. Original v1 bytes/meaning stay exact. The v2 envelope contains a
 * canonical v1 BASE plus explicit, index-bound extensions; a missing ceiling never becomes
 * unlimited. The base is deliberately stripped of extensions only inside this full v2 encoding.
 */
internal object LocalEffectivePolicyCodec {
    private const val V2 = "ukkera.activated-local-policy/v2"
    private val prefix = ("{\"schema\":\"$V2\",").encodeToByteArray()
    private val json = Json {
        encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false
        isLenient = false; coerceInputValues = false; allowSpecialFloatingPointValues = false
        allowStructuredMapKeys = false; prettyPrint = false
    }

    fun requiresV2(value: ActivatedLocalPolicy): Boolean = value.approvalPolicy != null || value.grants.any {
        it.limits.noMonetaryCeiling != null || it.delegationCeiling?.noMonetaryCeiling != null
    }

    fun encodePolicy(value: ActivatedLocalPolicy): ByteArray = guarded {
        if (!requiresV2(value)) return@guarded ProtectedLocalSecurityCodec.encodePolicy(value)
        val grants = value.grants
        val baseGrants = grants.map { grant -> PermissionGrant(grant.permission, grant.scope, grant.effect,
            grant.limits.copy(noMonetaryCeiling = null), grant.ownerAccess, grant.namedSubjectId, grant.targetIds,
            grant.delegationCeiling?.copy(noMonetaryCeiling = null)) }
        val base = ActivatedLocalPolicy(value.scope, value.actorId, value.revision, baseGrants,
            value.sessionPolicy, value.throttlePolicy)
        val baseBytes = ProtectedLocalSecurityCodec.encodePolicy(base)
        try {
            val extensions = grants.mapIndexedNotNull { index, grant ->
                val limit = grant.limits.noMonetaryCeiling?.currency
                val delegation = grant.delegationCeiling?.noMonetaryCeiling?.currency
                if (limit == null && delegation == null) null else MonetaryExtension(index, limit, delegation)
            }
            val approval = value.approvalPolicy?.let { ApprovalPolicyWire(it.validForMillis,
                it.namedSelfApprovalActions.sortedBy(SecurityAction::name)) }
            val bytes = json.encodeToString(PolicyV2.serializer(), PolicyV2(V2,
                baseBytes.decodeToString(throwOnInvalidSequence = true), extensions, approval))
                .encodeToByteArray(throwOnInvalidSequence = true)
            if (bytes.size !in 1..ProtectedLocalTable.EFFECTIVE_POLICY.maximumPlaintextBytes) {
                bytes.fill(0); protectedLocalUnavailable()
            }
            bytes
        } finally { baseBytes.fill(0) }
    }

    fun decodePolicy(binding: ProtectedLocalBinding, plaintext: ByteArray): ActivatedLocalPolicy = guarded {
        require(binding.table == ProtectedLocalTable.EFFECTIVE_POLICY && binding.schemaVersion == 1)
        binding.requirePlaintextSize(plaintext.size)
        if (plaintext.size < prefix.size || prefix.indices.any { plaintext[it] != prefix[it] }) {
            return@guarded ProtectedLocalSecurityCodec.decodePolicy(binding, plaintext)
        }
        val snapshot = plaintext.copyOf()
        try {
            val wire = json.decodeFromString(PolicyV2.serializer(), snapshot.decodeToString(throwOnInvalidSequence = true))
            require(wire.schema == V2 && wire.monetaryExtensions.size <= 1024)
            val baseBytes = wire.baseV1.encodeToByteArray(throwOnInvalidSequence = true)
            val base = try { ProtectedLocalSecurityCodec.decodePolicy(binding, baseBytes) } finally { baseBytes.fill(0) }
            val grants = base.grants.toMutableList()
            var previous = -1
            for (extension in wire.monetaryExtensions) {
                require(extension.grantIndex in grants.indices && extension.grantIndex > previous &&
                    (extension.limitCurrency != null || extension.delegationCurrency != null))
                val grant = grants[extension.grantIndex]
                val limits = if (extension.limitCurrency == null) grant.limits else {
                    require(grant.limits.maximumAmount == null)
                    grant.limits.copy(noMonetaryCeiling = NoMonetaryCeiling(extension.limitCurrency))
                }
                val delegation = if (extension.delegationCurrency == null) grant.delegationCeiling else {
                    val original = checkNotNull(grant.delegationCeiling)
                    require(original.maximumAmount == null)
                    original.copy(noMonetaryCeiling = NoMonetaryCeiling(extension.delegationCurrency))
                }
                grants[extension.grantIndex] = PermissionGrant(grant.permission, grant.scope, grant.effect, limits,
                    grant.ownerAccess, grant.namedSubjectId, grant.targetIds, delegation)
                previous = extension.grantIndex
            }
            val approval = wire.approvalPolicy?.let {
                require(it.namedSelfApprovalActions.size <= 256 && it.namedSelfApprovalActions.toSet().size == it.namedSelfApprovalActions.size)
                LocalApprovalPolicy(it.validForMillis, it.namedSelfApprovalActions.toSet())
            }
            val value = ActivatedLocalPolicy(base.scope, base.actorId, base.revision, grants,
                base.sessionPolicy, base.throttlePolicy, approval)
            val canonical = encodePolicy(value)
            try { require(canonical.contentEquals(snapshot)) } finally { canonical.fill(0) }
            value
        } finally { snapshot.fill(0) }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { protectedLocalUnavailable() }
}

@Serializable
private class PolicyV2(val schema: String, val baseV1: String, val monetaryExtensions: List<MonetaryExtension>,
    val approvalPolicy: ApprovalPolicyWire?)
@Serializable
private class MonetaryExtension(val grantIndex: Int, val limitCurrency: Currency?, val delegationCurrency: Currency?)
@Serializable
private class ApprovalPolicyWire(@Serializable(with = MinorUnitsSerializer::class) val validForMillis: Long,
    val namedSelfApprovalActions: List<SecurityAction>)
