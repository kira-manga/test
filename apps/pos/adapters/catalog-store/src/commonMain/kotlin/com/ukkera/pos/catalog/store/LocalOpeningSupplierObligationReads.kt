package com.ukkera.pos.catalog.store

import com.ukkera.pos.database.*
import com.ukkera.pos.security.*
import com.ukkera.pos.types.Revision
import com.ukkera.pos.types.UtcInstant

internal class OpeningSupplierObligationReadHandler(private val configuration: LocalConfigurationRepository) : MasterReadHandler {
    override val key: ReadKey = LocalOpeningSupplierObligationProfile.READ
    override fun normalize(input: StoredBytes): StoredBytes =
        LocalOpeningSupplierObligationCodec.query(LocalOpeningSupplierObligationCodec.decodeQuery(input))

    override suspend fun prepare(normalized: StoredBytes, view: CommandReadView, context: ReadContext): PreparedRead {
        requirePurchasingReadContext(view, context, key, normalized)
        check(normalize(normalized) == normalized)
        val query = LocalOpeningSupplierObligationCodec.decodeQuery(normalized)
        val bindings = linkedSetOf((query.id ?: query.supplierId ?: context.scope.branchId) to Revision(0))
        val projection = try {
            val reader = OpeningSupplierObligationReader(configuration, view)
            val page = if (query.id == null) view.aggregateKeys(LocalOpeningSupplierObligationProfile.OBLIGATION, query.after, query.limit) else null
            page?.let {
                check(it.keys.size <= query.limit && it.keys.all { key -> key.kind == LocalOpeningSupplierObligationProfile.OBLIGATION &&
                    (query.after == null || key.id.value > query.after.value) } &&
                    it.keys.zipWithNext().all { (left, right) -> left.id.value < right.id.value } &&
                    (it.nextAfter == null || it.keys.lastOrNull()?.id == it.nextAfter))
            }
            val rows = if (query.id != null) listOfNotNull(reader.row(query.id))
                else checkNotNull(page).keys.map { reader.row(it.id) ?: error("Opening supplier page lost its row") }
            val selected = rows.filter { query.supplierId == null || it.record.request.supplierId == query.supplierId }
            selected.forEach { bindings += it.row.key.id to it.row.revision }
            openingSupplierObligationPageBytes(LocalOpeningSupplierObligationPageV1(query, selected.map { it.record.view() }, page?.nextAfter))
        } catch (_: OpeningSupplierObligationHydrationCapacityUnavailable) {
            LocalOpeningSupplierObligationCodec.page(LocalOpeningSupplierObligationPageV1(query, emptyList(), null,
                LocalPurchasingReadAvailabilityV1.HYDRATION_CAPACITY_UNAVAILABLE))
        }
        val requiredAccess = bindings.flatMap { (id, revision) ->
            listOf(SecurityAction.PURCHASING_READ, SecurityAction.INVENTORY_COST_READ).map { action ->
                AuthorizationRequirement(Permission(action, PermissionVerb.PERFORM), context.scope,
                    ActionBinding(id, revision, context.semanticDigest, reasonCode = "purchasing.opening_obligations_read"))
            }
        }
        return object : PreparedRead() {
            override val requirements: List<AuthorizationRequirement> = requiredAccess
            override fun project(authorizations: List<AuthorizedAction>, observedAt: UtcInstant): StoredBytes {
                check(authorizations.size == requirements.size && authorizations.zip(requirements).all { (authorization, requirement) ->
                    authorization.principalKind == PrincipalKind.LOCAL_EMPLOYEE && authorization.approvalId == null &&
                        authorization.isBoundTo(requirement, context.actorId) })
                return projection
            }
        }
    }
}

/** Only oversized complete projections use the page capacity state; no partial list escapes. */
private fun openingSupplierObligationPageBytes(page: LocalOpeningSupplierObligationPageV1): StoredBytes {
    val bytes = purchasingJson.encodeToString(LocalOpeningSupplierObligationPageV1.serializer(), page)
        .encodeToByteArray(throwOnInvalidSequence = true)
    if (bytes.size > LocalOpeningSupplierObligationProfile.MAX_PAGE_BYTES) throw OpeningSupplierObligationHydrationCapacityUnavailable()
    return StoredBytes(bytes)
}
