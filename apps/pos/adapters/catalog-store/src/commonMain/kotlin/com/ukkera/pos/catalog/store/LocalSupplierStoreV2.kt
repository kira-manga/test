package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.recovery.RecoveryResourceKey
import com.ukkera.pos.recovery.RecoveryResourceKind
import com.ukkera.pos.security.*
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

/** Exact supplier-only V2 writer; current authority and durable effects remain kernel-owned. */
public class LocalSupplierHandlerV2 : MasterCommandHandler {
    override val key: CommandKey = LocalSupplierProfileV2.COMMAND
    override val writableKinds: Set<String> = setOf(LocalConfigurationProfile.SUPPLIER)
    override val claimNamespaces: Set<String> = emptySet()

    override fun normalize(input: StoredBytes): StoredBytes = LocalSupplierCodecV2.encode(LocalSupplierCodecV2.decode(input))

    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.SUPPLIERS && normalize(normalized) == normalized

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: CommandContext): PreparedMutation {
        check(context.scope == view.scope && context.key == key &&
            semanticDigest(context.scope, context.originalActorId, key, normalized) == context.semanticDigest)
        val request = LocalSupplierCodecV2.decode(normalized)
        val supplier = request.supplier
        val aggregateKey = LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, supplier.id)
        val before = view.aggregate(aggregateKey)
        val previous = before?.let { decodeRecord(view.scope, it) }
        check(previous == null || previous.body is ConfigurationBody.Supplier)
        val required = AuthorizationRequirement(Permission(SecurityAction.SUPPLIER_ADMINISTRATION, PermissionVerb.ADMINISTER),
            context.scope, ActionBinding(supplier.id, previous?.revision ?: Revision(0), context.semanticDigest,
                context.operationId, "local.configuration"), LimitUse.NotApplicable)
        val resources = buildSet {
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, context.originalActorId))
            add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, supplier.id))
            previous?.let { add(RecoveryResourceKey(RecoveryResourceKind.AUTHORIZATION, it.authorizedBy)) }
        }
        return object : PreparedMutation() {
            override val requirement: AuthorizationRequirement = required
            override val affectedResources: Set<RecoveryResourceKey> = resources

            override fun evaluate(authorization: AuthorizedAction, recordedAt: UtcInstant): ProposedOutcome {
                check(authorization.isBoundTo(required, context.originalActorId))
                if (before?.revision != request.expectedStorageRevision) return supplierRejectionV2("configuration.stale_revision")
                if (configurationStorageCapacityRequired(before, null)) return supplierRejectionV2("configuration.capacity_required")
                val next = previous?.revision?.next() ?: Revision(0)
                val record = ConfigurationRecord(context.scope, LocalConfigurationProfile.SUPPLIER, supplier.id, next,
                    supplier.configurationBody(), authorization.actorId, authorization.policyRevision, recordedAt)
                val encoded = boundedConfigurationRecord(record) ?: return supplierRejectionV2("configuration.capacity_required")
                return ProposedOutcome.Apply(encodeValue(LocalSupplierResultV2("configuration.applied", supplier.id,
                    next, supplier.reference), LocalSupplierResultV2.serializer()),
                    listOf(AggregateChange(aggregateKey, before?.revision, LocalSupplierProfileV2.SCHEMA, encoded)))
            }
        }
    }
}

/** Bounded current supplier directory only, with the same protected purchasing-read permission. */
public class LocalSupplierReadHandlerV2 : MasterReadHandler {
    override val key: ReadKey = LocalSupplierProfileV2.QUERY

    override fun normalize(input: StoredBytes): StoredBytes = LocalSupplierCodecV2.query(LocalSupplierCodecV2.decodeQuery(input))

    override fun supportsRpcWorkflow(profile: LocalMasterRpcWorkflowProfile, normalized: StoredBytes): Boolean =
        profile == LocalMasterRpcWorkflowProfile.SUPPLIERS && normalize(normalized) == normalized

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        check(context.scope == view.scope && context.key == key)
        val query = LocalSupplierCodecV2.decodeQuery(normalized)
        val page = if (query.id == null)
            view.aggregateKeys(LocalConfigurationProfile.SUPPLIER, query.after, query.limit) else null
        val keys = page?.keys ?: query.id?.let {
            listOf(LocalConfigurationProfile.key(LocalConfigurationProfile.SUPPLIER, it))
        }.orEmpty()
        val records = keys.mapNotNull { selected ->
            val row = view.aggregate(selected)
            // A same-transaction directory key cannot disappear. Direct-ID absence is genuine.
            check(row != null || page == null) { "Supplier page lost its source" }
            row?.let { it to decodeRecord(view.scope, it) }
        }
        val items = records.map { (row, record) ->
            val supplier = record.body as? ConfigurationBody.Supplier ?: error("Invalid supplier storage")
            LocalSupplierListItemV2(record.id, record.revision, row.revision, supplier.value.names,
                supplier.value.active, supplier.reference)
        }
        val detail = if (page == null) records.singleOrNull()?.second?.let {
            (it.body as? ConfigurationBody.Supplier ?: error("Invalid supplier storage")).supplierV2()
        } else null
        val required = AuthorizationRequirement(Permission(SecurityAction.PURCHASING_READ, PermissionVerb.PERFORM), context.scope)
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = listOf(required)

            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == 1 && authorizations.single().isBoundTo(required, context.actorId))
                return supplierPageBytesV2(items, page?.nextAfter, detail)
            }
        }
    }
}

/** Maximal fitting complete prefix. An oversized detail/individual row cannot become empty success. */
private fun supplierPageBytesV2(items: List<LocalSupplierListItemV2>, nextAfter: DomainId?,
    detail: LocalSupplierV2?): StoredBytes {
    boundedSupplierPageV2(LocalSupplierPageV2(items, nextAfter, detail))?.let { return it }
    check(detail == null && items.size > 1) { "Complete supplier page value exceeds capacity" }
    for (count in items.lastIndex downTo 1) {
        // Omitted rows are genuinely present in the selected native page. Resume after
        // the last emitted ID even when the native row-limit frontier was otherwise null.
        boundedSupplierPageV2(LocalSupplierPageV2(items.take(count), items[count - 1].id))?.let { return it }
    }
    error("Complete supplier page value exceeds capacity")
}

private fun boundedSupplierPageV2(value: LocalSupplierPageV2): StoredBytes? {
    val bytes = localConfigurationJson.encodeToString(LocalSupplierPageV2.serializer(), value).encodeToByteArray()
    return try {
        if (bytes.size <= LocalSupplierProfileV2.MAXIMUM_PAGE_BYTES) StoredBytes(bytes) else null
    } finally { bytes.fill(0) }
}

internal fun LocalSupplierV2.configurationBody(): ConfigurationBody.Supplier =
    ConfigurationBody.Supplier(LocalSupplierV1(id, names, active), reference)

internal fun ConfigurationBody.Supplier.supplierV2(): LocalSupplierV2 =
    LocalSupplierV2(value.id, value.names, value.active, reference)

internal fun supplierRejectionV2(code: String): ProposedOutcome.Reject = ProposedOutcome.Reject(code,
    encodeValue(LocalSupplierResultV2(code, null, null, null), LocalSupplierResultV2.serializer()))
