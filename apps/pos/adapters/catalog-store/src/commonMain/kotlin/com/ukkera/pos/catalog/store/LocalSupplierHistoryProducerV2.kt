package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision

/** Exact supplier V2 originals, using native BEFORE rows and their already joined writers. */
internal class LocalSupplierHistoryProducerV2 : RuntimeHistoryBusinessProducer {
    override val commands: Set<CommandKey> = setOf(LocalSupplierProfileV2.COMMAND)

    override suspend fun join(command: RuntimeHistoricalCommand): Boolean {
        val original = (command.original as? RetainedOriginalV2.Legacy)?.evidence ?: return false
        if (command.command !in commands || original.nativeOriginalProvenance != null) return false
        val request = LocalSupplierCodecV2.decode(command.normalized)
        check(LocalSupplierCodecV2.encode(request) == command.normalized &&
            semanticDigest(command.scope, command.actorId, command.command, command.normalized) == command.semanticDigest)
        val supplier = request.supplier
        val key = LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, supplier.id)
        val before = command.aggregateSource(key)?.row
        val previous = before?.let { decodeRecord(command.scope, it) }
        check(previous == null || previous.body is ConfigurationBody.Supplier)
        command.requireRequirement(AuthorizationRequirement(Permission(SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER),
            command.scope, ActionBinding(supplier.id, previous?.revision ?: Revision(0), command.semanticDigest,
                command.operationId, "local.configuration"), LimitUse.NotApplicable))
        val resources = buildSet {
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, command.actorId))
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, supplier.id))
            previous?.let { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.authorizedBy)) }
        }
        // Reproduce the original writer's precedence from this native cut, not its retained code.
        var refusal = when {
            before?.revision != request.expectedStorageRevision -> "configuration.stale_revision"
            configurationStorageCapacityRequired(before, null) -> "configuration.capacity_required"
            else -> null
        }
        var next: Revision? = null
        var encoded: StoredBytes? = null
        if (refusal == null) {
            next = previous?.revision?.next() ?: Revision(0)
            encoded = boundedConfigurationRecord(ConfigurationRecord(command.scope, LocalConfigurationProfile.SUPPLIER,
                supplier.id, next, supplier.configurationBody(), command.actorId, command.policyRevision, command.recordedAt))
            if (encoded == null) refusal = "configuration.capacity_required"
        }
        if (!original.committed) {
            if (original.code !in setOf("configuration.stale_revision", "configuration.capacity_required")) return false
            val code = refusal ?: return false
            check(original.code == code)
            val expected = supplierRejectionV2(code)
            check(original.result == expected.result && original.aggregateWrites.isEmpty() &&
                original.claimChanges.isEmpty() && original.jobs.isEmpty())
            val bytes = expected.result.copyBytes()
            try { check(sha256(bytes) == original.resultHash) } finally { bytes.fill(0) }
            command.requireConfigurationRejection(expected, resources)
            return true
        }
        // The kernel carrier is "committed"; the typed supplier result has its own code.
        check(refusal == null && original.code == "committed")
        command.requireLegacyEffects(ProposedOutcome.Apply(encodeValue(LocalSupplierResultV2("configuration.applied",
            supplier.id, checkNotNull(next), supplier.reference), LocalSupplierResultV2.serializer()),
            listOf(AggregateChange(key, before?.revision, LocalSupplierProfileV2.SCHEMA, checkNotNull(encoded)))), resources)
        return true
    }
}
