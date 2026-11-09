package com.ukkera.pos.app.training

import com.ukkera.pos.app.SessionDisplayContext
import com.ukkera.pos.app.navigation.RouteRegistry
import com.ukkera.pos.app.navigation.SecurityRoot
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BilingualText(val en: String, val ar: String)
enum class TrainingCategory { GROCERY, BAKERY, CAFE, KITCHEN, CLOTHING }

data class TrainingProduct(
    val id: DomainId,
    val barcode: String,
    val name: BilingualText,
    val detail: BilingualText,
    val category: TrainingCategory,
    val unitPrice: Money,
    val available: Int,
) {
    init {
        require(barcode.length in 1..48 && barcode.all { it in '0'..'9' })
        require(unitPrice.currency == Currency.EGP && unitPrice.minor in 0..100_000_000)
        require(available in 0..9999)
        require(name.en.length in 1..120 && name.ar.length in 1..120)
    }
}

data class TrainingLine(val product: TrainingProduct, val quantity: Int) {
    init { require(quantity in 1..99 && quantity <= product.available) }
    val total: Money get() = Money(Currency.EGP, product.unitPrice.minor * quantity)
}

data class TrainingBasket(val lines: List<TrainingLine> = emptyList()) {
    val count: Int get() = lines.sumOf { it.quantity }
    val total: Money get() = lines.fold(Money(Currency.EGP, 0)) { sum, line -> sum + line.total }
}

/**
 * Entirely synthetic, bounded, in-memory rehearsal. No auth, storage, network, printing, payment or
 * outbox dependency exists here. Totals are a zero-tax illustration, NOT an authoritative price
 * snapshot, reservation, receipt, collection or durable operation result.
 */
class TrainingWorkspace(private val onDraftChanged: (Boolean) -> Unit = {}) {
    private val mutableBasket = MutableStateFlow(TrainingBasket())
    val basket: StateFlow<TrainingBasket> = mutableBasket.asStateFlow()
    var closed: Boolean = false
        private set

    fun search(query: String, category: TrainingCategory?): List<TrainingProduct> {
        if (closed) return emptyList()
        val bounded = normalizeSearch(query.take(80))
        return products.filter { product ->
            (category == null || product.category == category) &&
                (bounded.isBlank() || normalizeSearch(product.name.en + " " + product.name.ar + " " + product.barcode).contains(bounded))
        }
    }

    fun add(productId: DomainId): Boolean {
        if (closed) return false
        val product = products.firstOrNull { it.id == productId } ?: return false
        val current = mutableBasket.value
        val line = current.lines.firstOrNull { it.product.id == productId }
        val quantity = (line?.quantity ?: 0) + 1
        if (quantity > minOf(99, product.available)) return false
        mutableBasket.value = TrainingBasket(if (line == null) current.lines + TrainingLine(product, 1)
            else current.lines.map { if (it.product.id == productId) it.copy(quantity = quantity) else it })
        onDraftChanged(true)
        return true
    }

    fun removeOne(productId: DomainId) {
        if (closed) return
        mutableBasket.value = TrainingBasket(mutableBasket.value.lines.mapNotNull {
            when { it.product.id != productId -> it; it.quantity == 1 -> null; else -> it.copy(quantity = it.quantity - 1) }
        })
        onDraftChanged(mutableBasket.value.lines.isNotEmpty())
    }

    fun clear() {
        if (closed) return
        mutableBasket.value = TrainingBasket()
        onDraftChanged(false)
    }

    fun close() {
        if (closed) return
        closed = true
        mutableBasket.value = TrainingBasket()
        onDraftChanged(false)
    }

    companion object {
        private fun id(number: Int) = DomainId("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}")

        fun display(company: Boolean): SessionDisplayContext = SessionDisplayContext(
            actorId = id(900), companyId = id(901), branchId = if (company) null else id(902),
            actorName = "Training", companyName = "Ukkera training", branchName = if (company) null else "Garden branch · sample",
            allowedAreas = RouteRegistry.areas(if (company) SecurityRoot.COMPANY_WORKSPACE else SecurityRoot.BRANCH_WORKSPACE).toSet(),
        )

        val products = listOf(
            TrainingProduct(id(1), "000000000001", BilingualText("Fresh milk", "حليب طازج"), BilingualText("1 litre · chilled", "١ لتر · مبرد"), TrainingCategory.GROCERY, Money(Currency.EGP, 4250), 24),
            TrainingProduct(id(2), "000000000002", BilingualText("Basmati rice", "أرز بسمتي"), BilingualText("1 kg · sealed pack", "١ كجم · عبوة مغلقة"), TrainingCategory.GROCERY, Money(Currency.EGP, 7800), 18),
            TrainingProduct(id(3), "000000000003", BilingualText("Olive oil", "زيت زيتون"), BilingualText("250 ml · extra virgin", "٢٥٠ مل · بكر ممتاز"), TrainingCategory.GROCERY, Money(Currency.EGP, 14500), 8),
            TrainingProduct(id(4), "000000000004", BilingualText("Still water", "مياه معدنية"), BilingualText("600 ml · bottle", "٦٠٠ مل · زجاجة"), TrainingCategory.GROCERY, Money(Currency.EGP, 1000), 48),
            TrainingProduct(id(5), "000000000005", BilingualText("Butter croissant", "كرواسون بالزبدة"), BilingualText("1 piece · bakery", "قطعة واحدة · مخبوزات"), TrainingCategory.BAKERY, Money(Currency.EGP, 3500), 12),
            TrainingProduct(id(6), "000000000006", BilingualText("Sourdough loaf", "خبز بالعجين المخمر"), BilingualText("1 loaf · bakery", "رغيف واحد · مخبوزات"), TrainingCategory.BAKERY, Money(Currency.EGP, 6500), 6),
            TrainingProduct(id(7), "000000000007", BilingualText("Cappuccino", "كابتشينو"), BilingualText("Regular · whole milk", "عادي · حليب كامل الدسم"), TrainingCategory.CAFE, Money(Currency.EGP, 5500), 30),
            TrainingProduct(id(8), "000000000008", BilingualText("Mint tea", "شاي بالنعناع"), BilingualText("Regular · no sugar", "عادي · بدون سكر"), TrainingCategory.CAFE, Money(Currency.EGP, 3000), 30),
            TrainingProduct(id(9), "000000000009", BilingualText("Grilled chicken wrap", "راب دجاج مشوي"), BilingualText("Regular · no additions", "عادي · بدون إضافات"), TrainingCategory.KITCHEN, Money(Currency.EGP, 9500), 15),
            TrainingProduct(id(10), "000000000010", BilingualText("Garden salad", "سلطة خضراء"), BilingualText("Regular · dressing aside", "عادي · الصلصة جانبًا"), TrainingCategory.KITCHEN, Money(Currency.EGP, 6000), 10),
            TrainingProduct(id(11), "000000000011", BilingualText("Cotton T-shirt", "تيشيرت قطني"), BilingualText("Navy · medium variant", "كحلي · مقاس متوسط"), TrainingCategory.CLOTHING, Money(Currency.EGP, 24900), 4),
            TrainingProduct(id(12), "000000000012", BilingualText("Linen shirt", "قميص كتان"), BilingualText("Sand · large variant", "رملي · مقاس كبير"), TrainingCategory.CLOTHING, Money(Currency.EGP, 49900), 3),
        )

        /** Normalizes Arabic/Persian digits without dropping barcode leading zeros. */
        fun normalizeSearch(value: String): String = value.trim().lowercase().map { character ->
            when (character) {
                in '٠'..'٩' -> '0' + (character - '٠')
                in '۰'..'۹' -> '0' + (character - '۰')
                else -> character
            }
        }.joinToString("")
    }
}
