package com.ukkera.pos.app.gateway

import com.ukkera.pos.app.*
import com.ukkera.pos.pricing.PriceComponentKind
import com.ukkera.pos.pricing.TaxInclusion
import com.ukkera.pos.sales.store.*
import com.ukkera.pos.security.sha256
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Revision

/** Only the installed Master's current quote supplies prices/postings; nothing is staged. */
internal suspend fun previewLocalFoodSelection(access: GatewayAccess, input: LocalFoodPreviewInput): LocalFoodPricePreview {
    if (input.action !in setOf(LocalAction.FOOD_CREATE, LocalAction.FOOD_ADD) ||
        (input.formVersion != 1 && !(input.action == LocalAction.FOOD_ADD && input.formVersion in 2..3))) invalid()
    val command = if (input.action == LocalAction.FOOD_ADD && input.formVersion in 2..3)
        LocalFoodStockCodecV2.COMMAND else LocalFoodProfileV1.COMMAND
    if (!access.installed.has(command) || !access.installed.has(LocalFoodSelectionPreviewCodecV1.QUERY))
        throw GatewayFailure(LocalServiceProblem.NOT_IMPLEMENTED)
    val request = try {
        val values = InputValues(input.values)
        val orderId = values.id(LocalField.ORDER_ID)
        val selected = parseFoodSelection(access, input.action, orderId, values, LocalField.LINES, input.lines,
            preserveInputOrder = input.formVersion == 3)
        values.finish()
        LocalFoodSelectionPreviewCodecV1.read(selected.request)
    } catch (_: IllegalArgumentException) { invalid() }
    val selected = LocalFoodCodecV1.decode(request.payload)
    val orderId = when (selected) {
        is LocalFoodRequestV1.Create -> selected.orderId
        is LocalFoodRequestV1.Add -> selected.orderId
        else -> unavailable()
    }
    val portions = when (selected) {
        is LocalFoodRequestV1.Create -> selected.portions
        is LocalFoodRequestV1.Add -> selected.portions
        else -> unavailable()
    }
    val reply = access.read(request)
    val quoted = when (val result = LocalFoodSelectionPreviewCodecV1.result(reply.payload)) {
        is LocalFoodSelectionPreviewResultV1.Available -> result
        is LocalFoodSelectionPreviewResultV1.Unavailable -> throw GatewayFailure(when (result.reason) {
            LocalFoodSelectionPreviewUnavailableV1.TARGET_UNAVAILABLE -> LocalServiceProblem.FOOD_TARGET_UNAVAILABLE
            LocalFoodSelectionPreviewUnavailableV1.SOURCE_UNAVAILABLE -> LocalServiceProblem.UNAVAILABLE
            LocalFoodSelectionPreviewUnavailableV1.CAPACITY_REQUIRED -> LocalServiceProblem.FOOD_CAPACITY_REQUIRED
        })
    }
    val quote = quoted.quote
    if (quoted.requestDigest != sha256(request.payload.copyBytes()) || quote.snapshotId != orderId ||
        quote.revision != Revision(0) || quote.currency != Currency.EGP) unavailable()
    val byPortion = quote.lines.associateBy { it.portionId }
    if (quote.lines.size != portions.size || byPortion.size != portions.size ||
        byPortion.keys != portions.map { it.portionId }.toSet() || portions.any { portion ->
            byPortion.getValue(portion.portionId).let { it.lineId != portion.commercialLineId || it.quantity != portion.quantity }
        }) unavailable()
    val groups = LocalFoodSelectionGroups.group(portions).associateBy { it.lineId }
    if (groups.keys != input.lines.map { it.lineId }.toSet()) unavailable()
    // Fixed components without a portion cannot be silently assigned to a commercial line.
    // The actual current FOOD source supplies no charges/adjustments; genuinely allocated
    // components still retain the engine's exact posted cents if that source evolves.
    if (quote.components.any { it.portionId == null || it.portionId !in byPortion }) unavailable()
    fun posted(kinds: Set<PriceComponentKind>): Money = quote.components.filter { it.kind in kinds }
        .fold(Money(quote.currency, 0)) { sum, component -> sum + component.posted }
    val netKinds = setOf(PriceComponentKind.ITEM_NET, PriceComponentKind.CHARGE_NET)
    val taxKinds = setOf(PriceComponentKind.ITEM_TAX, PriceComponentKind.CHARGE_TAX)
    val chargeKinds = setOf(PriceComponentKind.CHARGE_NET, PriceComponentKind.CHARGE_TAX)
    val lines = input.lines.map { inputLine ->
        val group = groups.getValue(inputLine.lineId)
        val members = group.portions.map { byPortion.getValue(it.portionId) }
        val first = members.first()
        if (members.any { it.unitPrice != first.unitPrice || it.inclusion != first.inclusion }) unavailable()
        val ids = group.portions.map { it.portionId }.toSet()
        val components = quote.components.filter { it.portionId in ids }
        fun linePosted(kinds: Set<PriceComponentKind>): Money = components.filter { it.kind in kinds }
            .fold(Money(quote.currency, 0)) { sum, component -> sum + component.posted }
        val payable = members.fold(Money(quote.currency, 0)) { sum, member -> sum + member.payable }
        val net = linePosted(netKinds)
        val tax = linePosted(taxKinds)
        if (net + tax != payable) unavailable()
        LocalFoodPricePreviewLine(group.lineId, group.totalQuantity, first.unitPrice, payable,
            first.inclusion == TaxInclusion.INCLUSIVE, net, tax)
    }
    val net = posted(netKinds)
    val tax = posted(taxKinds)
    if (net + tax != quote.payable || lines.fold(Money(quote.currency, 0)) { sum, line -> sum + line.payable } != quote.payable)
        unavailable()
    access.checkCurrent()
    return LocalFoodPricePreview(input, lines, quote.payable, quote.beforeDiscountPayable, quote.advertisedDiscount,
        tax, posted(chargeKinds), net)
}
