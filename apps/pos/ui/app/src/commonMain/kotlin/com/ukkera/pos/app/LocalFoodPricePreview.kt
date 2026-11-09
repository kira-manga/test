package com.ukkera.pos.app

import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.UnitPrice

/** Exact editable selection only. Preview neither allocates an operation ID nor stages work. */
data class LocalFoodPreviewInput(
    val action: LocalAction,
    val formVersion: Int,
    val values: List<LocalScalarInput>,
    val lines: List<LocalInputLine>,
) {
    init {
        require(action in setOf(LocalAction.FOOD_CREATE, LocalAction.FOOD_ADD) &&
            (formVersion == 1 || action == LocalAction.FOOD_ADD && formVersion in 2..3))
        require(values.size <= 32 && values.map { it.field }.distinct().size == values.size)
        require(lines.size in 1..8 && lines.map { it.lineId }.distinct().size == lines.size)
    }

    fun sameSelection(other: LocalFoodPreviewInput): Boolean = action == other.action &&
        formVersion == other.formVersion && values == other.values && lines.size == other.lines.size &&
        lines.zip(other.lines).all { (a, b) -> a.lineId == b.lineId && a.values == b.values }

    fun snapshot(): LocalFoodPreviewInput = copy(values = values.toList(),
        lines = lines.map { LocalInputLine(it.lineId, it.values) })
}

/** Unit price is the Master's selected exact price; payable is its posted whole-line amount. */
data class LocalFoodPricePreviewLine(val lineId: DomainId, val quantity: Quantity,
    val unitPrice: UnitPrice, val payable: Money, val taxIncluded: Boolean, val net: Money, val tax: Money)

/** New selection batch only, not an existing check's payable or a protected saved quotation. */
data class LocalFoodPricePreview(val input: LocalFoodPreviewInput,
    val lines: List<LocalFoodPricePreviewLine>, val payable: Money,
    val beforeDiscount: Money, val discount: Money, val tax: Money, val charges: Money, val net: Money) {
    init {
        require(lines.size == input.lines.size && lines.map { it.lineId }.toSet() == input.lines.map { it.lineId }.toSet())
        require(listOf(payable, beforeDiscount, discount, tax, charges, net).all { it.currency == payable.currency && it.minor >= 0 })
        require(beforeDiscount - discount == payable && tax.minor <= payable.minor && charges.minor <= payable.minor)
        require(net + tax == payable)
        require(lines.all { it.payable.currency == payable.currency && it.unitPrice.currency == payable.currency &&
            it.payable.minor >= 0 && it.quantity.scaled > 0 && it.net.currency == payable.currency &&
            it.tax.currency == payable.currency && it.net.minor >= 0 && it.tax.minor >= 0 && it.net + it.tax == it.payable })
        require(lines.fold(Money(payable.currency, 0)) { sum, line -> sum + line.payable } == payable)
    }
}

sealed interface LocalFoodPricePreviewResult {
    data class Available(val preview: LocalFoodPricePreview) : LocalFoodPricePreviewResult
    data class Unavailable(val problem: LocalServiceProblem, val field: LocalField? = null) : LocalFoodPricePreviewResult
}
