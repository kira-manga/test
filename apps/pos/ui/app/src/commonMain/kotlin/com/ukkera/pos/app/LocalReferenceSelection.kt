package com.ukkera.pos.app

import com.ukkera.pos.types.DomainId

/** A closed, read-only directory selector. It is neither a grant nor a saved command field. */
data class LocalReferenceSource(val kind: LocalRecordKind, val excludedId: DomainId? = null) {
    init { require(kind in setOf(LocalRecordKind.SKU, LocalRecordKind.WAREHOUSE, LocalRecordKind.SUPPLIER)) }

    fun accepts(reference: LocalRecordReference): Boolean = reference.kind == kind &&
        reference.id != excludedId && reference.revision != null && reference.parentId == null
}

/** Each page is a fresh authorized read, not a cross-page snapshot or an existence certificate. */
data class LocalReferenceQuery(
    val source: LocalReferenceSource,
    val after: DomainId? = null,
    val exactId: DomainId? = null,
) { init { require(after == null || exactId == null) } }

data class LocalReferencePage(
    val query: LocalReferenceQuery,
    val choices: List<LocalReferenceChoice>,
    /** Advances past ALL inspected rows, including inactive/excluded rows not offered as choices. */
    val nextAfter: DomainId? = null,
    val notice: LocalText? = null,
) {
    init {
        require(choices.size <= 25 && choices.map { it.reference.id }.distinct().size == choices.size)
        require(choices.all { query.source.accepts(it.reference) })
        require(choices.zipWithNext().all { (a, b) -> a.reference.id.value < b.reference.id.value })
        require(query.after == null || choices.all { it.reference.id.value > query.after.value })
        require(nextAfter == null || query.after == null || nextAfter.value > query.after.value)
        require(nextAfter == null || choices.all { it.reference.id.value <= nextAfter.value })
        require(query.exactId == null || nextAfter == null && choices.all { it.reference.id == query.exactId })
    }
}

sealed interface LocalReferenceResult {
    data class Available(val page: LocalReferencePage) : LocalReferenceResult
    data class Unavailable(val problem: LocalServiceProblem) : LocalReferenceResult
}

/** Only one bounded picker/page is retained per editor. Closing/locking discards this material. */
internal data class LocalReferencePickerState(
    val field: LocalField,
    val lineId: DomainId?,
    val label: LocalText,
    val source: LocalReferenceSource,
    val exactIdText: String = "",
    val requested: LocalReferenceQuery? = null,
    val page: LocalReferencePage? = null,
    val problem: LocalServiceProblem? = null,
    val invalidId: Boolean = false,
)
