package com.ukkera.pos.catalog

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/**
 * Configurable engineering admission limits, NOT company monetary/quantity policy. A trusted
 * importer selects these before constructing definitions; never accept client-supplied limits
 * as a way to enlarge a server's resource budget. Collections are bounded before defensive copy.
 */
public data class CatalogBounds(
    public val maxEntities: Int = 10_000,
    public val maxPriceRules: Int = 4_096,
    public val maxChildren: Int = 256,
    public val maxPolicyDeclarations: Int = 256,
    public val maxChannels: Int = 32,
    public val maxIssues: Int = 256,
    public val maxValidationWork: Int = 2_000_000,
) {
    init {
        require(maxEntities > 0 && maxPriceRules > 0 && maxChildren > 0)
        require(maxPolicyDeclarations > 0 && maxChannels > 0 && maxIssues > 0 && maxValidationWork > 0)
    }
}

public data class CatalogVersionRef(public val id: DomainId, public val revision: Revision)

/** ACTIVE is definition availability, not proof of an authorized durable activation. */
public enum class CatalogStatus { ACTIVE, ARCHIVED }

internal fun <T> frozen(values: Collection<T>, maximum: Int, label: String): List<T> {
    require(values.size <= maximum) { "$label exceeds engineering capacity $maximum" }
    return FrozenList(values.toList())
}

/** No mutable backing/iterator is exposed, including through a JVM mutable-list cast. */
private class FrozenList<T>(private val values: List<T>) : AbstractList<T>() {
    override val size: Int get() = values.size
    override fun get(index: Int): T = values[index]
}
