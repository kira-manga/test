package com.ukkera.pos.app

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Revision

/** Recorded display values only; a component key is not a selectable business target. */
data class LocalManagerQuoteComponent(val key: String, val values: List<LocalRecordValue>) {
    init {
        require(key.isNotBlank() && key.length <= 2048)
        require(values.size in 1..18)
    }
}

/** One current read's component roster, never a new quote, transfer or monetary authority. */
data class LocalManagerQuoteDetails(
    val orderId: DomainId,
    val revision: Revision,
    val components: List<LocalManagerQuoteComponent>,
    val originalQuote: LocalManagerOriginalQuoteDetails? = null,
) {
    init {
        // The existing sales read decoder supports at most 1,024 array entries.
        // Keep all supplied entries separately from the general page's 100-record bound.
        require(components.size <= 1024 && components.map { it.key }.distinct().size == components.size)
    }
}

/** Exact stored pricing explanations, separate from selectable business records. */
data class LocalManagerQuoteSection(val key: String, val title: LocalText, val values: List<LocalRecordValue>) {
    init {
        require(key.isNotBlank() && key.length <= 2048 && values.size in 1..24)
    }
}

data class LocalManagerOriginalQuoteDetails(
    val values: List<LocalRecordValue>,
    val sections: List<LocalManagerQuoteSection>,
) {
    init {
        require(values.size in 1..24 && sections.size <= 16384)
        require(sections.map { it.key }.distinct().size == sections.size)
    }
}
