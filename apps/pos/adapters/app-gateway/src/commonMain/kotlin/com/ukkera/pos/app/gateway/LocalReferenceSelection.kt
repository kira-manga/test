package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.catalog.store.LocalConfigurationListItemV1
import com.ukkera.pos.catalog.store.LocalConfigurationQueryV1
import com.ukkera.pos.types.DomainId

internal suspend fun readLocalReferences(access: GatewayAccess, query: LocalReferenceQuery): LocalReferencePage {
    val kind = configurationKind(query.source.kind) ?: invalid()
    val cut = configurationRead(access, LocalConfigurationQueryV1(kind, id = query.exactId, after = query.after, limit = 25))
    val page = cut.page
    val after = query.after
    // Validate the complete page BEFORE filtering. An inactive page can still have a successor;
    // never use only displayed rows as its cursor or silently accept a repeated/backward range.
    if (page.items.zipWithNext().any { (a, b) -> a.id.value >= b.id.value } ||
        after != null && page.items.any { it.id.value <= after.value } ||
        page.nextAfter != null && (query.exactId != null || page.nextAfter != page.items.lastOrNull()?.id) ||
        query.exactId != null && (page.items.size > 1 || page.items.any { it.id != query.exactId })) unavailable()
    return LocalReferencePage(query, page.items.filter { it.active == true && it.id != query.source.excludedId }.map {
        LocalReferenceChoice(LocalRecordReference(query.source.kind, it.id, it.revision), names(it.names, it.id))
    }, page.nextAfter, readCut(cut.reply))
}

internal fun pagedReferenceField(field: LocalField, kind: LocalRecordKind, required: Boolean = true,
    initial: LocalReferenceChoice? = null, excludedId: DomainId? = null): LocalFormField.Scalar =
    scalar(field, LocalInputKind.REFERENCE, initial?.let { LocalScalarValue.Reference(it.reference) }, required = required)
        .copy(references = listOfNotNull(initial), referenceSource = LocalReferenceSource(kind, excludedId))

/** Re-read a selected identity before freezing NEW intent bytes. Never used for a saved retry. */
internal suspend fun currentConfigurationReference(access: GatewayAccess, reference: LocalRecordReference,
    kind: LocalRecordKind, field: LocalField): LocalConfigurationListItemV1 {
    if (!LocalReferenceSource(kind).accepts(reference)) invalid(field)
    val row = configurationRead(access, LocalConfigurationQueryV1(configurationKind(kind) ?: invalid(field),
        id = reference.id)).page.items.singleOrNull() ?: invalid(field)
    if (row.id != reference.id) unavailable()
    if (row.active != true || row.revision != reference.revision) invalid(field)
    return row
}
