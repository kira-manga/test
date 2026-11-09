package com.ukkera.pos.app.independent.foodcurrentpayablev2

import com.ukkera.pos.app.LocalFoodFulfillment
import com.ukkera.pos.app.LocalFoodPreparation
import com.ukkera.pos.app.LocalFoodUiBody
import com.ukkera.pos.app.LocalFoodUiCheck
import com.ukkera.pos.app.LocalFoodUiPortion
import com.ukkera.pos.app.LocalFoodUiService
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.FoodCopy
import com.ukkera.pos.app.ui.foodCopyText
import com.ukkera.pos.app.ui.foodMoney
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.DomainId
import com.ukkera.pos.types.Money
import com.ukkera.pos.types.Quantity
import com.ukkera.pos.types.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Controlled shared-model/copy/formatter subassertions of FCPV2-007/009. These do not render
 * an owner screen or establish protected reads, platform coverage or authority for an action.
 */
class FoodCurrentPayableFrozenUiSlicesTest {
    @Test
    fun fcpv2007_knownCurrentAmountsRemainSeparateFromHistoricalOriginalMoney() {
        val states = listOf(
            Triple(22_000L, 22_000L, "220.00"),
            Triple(11_000L, 22_000L, "110.00"),
            Triple(39_000L, 50_000L, "390.00"),
            Triple(0L, 22_000L, "0.00"),
        )
        for ((current, original, expectedDecimal) in states) {
            val body = check(current, original, cancelled = current == 0L)
            assertEquals(Money(Currency.EGP, current), body.currentUnpaidPayable)
            assertEquals(Money(Currency.EGP, original), body.originalQuotedTotal)
            for (language in AppLanguage.entries) {
                val formatted = language.foodMoney(assertNotNull(body.currentUnpaidPayable))
                assertEquals(expectedDecimal, decimalDigits(formatted), "FCPV2-007: exact known current money")
                assertTrue(formatted.contains("EGP") || formatted.contains("ج.م"), "FCPV2-007: EGP denomination")
                if (language == AppLanguage.ARABIC) assertTrue(formatted.any { it in '٠'..'٩' })
            }
        }
    }

    @Test
    fun fcpv2007_nullIsUnavailableAndKnownZeroStaysKnown() {
        val zero = check(0, 22_000, cancelled = true)
        val unknown = check(null, 50_000)
        assertNotNull(zero.currentUnpaidPayable, "FCPV2-007: known zero is an amount")
        assertEquals(Money(Currency.EGP, 0), zero.currentUnpaidPayable)
        assertNull(unknown.currentUnpaidPayable, "FCPV2-007: unknown is not a numeric estimate")
        assertEquals(Money(Currency.EGP, 50_000), unknown.originalQuotedTotal)

        val unavailable = foodCopyText(FoodCopy.CURRENT_UNPAID_UNKNOWN)
        assertTrue(unavailable.english.contains("unavailable", ignoreCase = true) ||
            unavailable.english.contains("unknown", ignoreCase = true), "FCPV2-007: English unknown meaning")
        assertTrue(unavailable.arabic.contains("غير متاح") || unavailable.arabic.contains("غير معروف"),
            "FCPV2-007: Arabic unknown meaning")
        for (language in AppLanguage.entries) {
            val copy = language.text(unavailable)
            assertFalse(copy.any { it in '0'..'9' || it in '٠'..'٩' }, "FCPV2-007: unavailable copy has no numeric total")
            assertNotEquals(language.foodMoney(Money(Currency.EGP, 0)), copy)
        }
        // Screen selection of the unknown copy, failed-read cleanup and actual rendering are
        // separate obligations; this check binds the model and production copy only.
    }

    @Test
    fun fcpv2007_bothLanguagesIdentifyCurrentAndHistoricalMeanings() {
        val current = foodCopyText(FoodCopy.CURRENT_UNPAID)
        val original = foodCopyText(FoodCopy.QUOTE)
        val historical = foodCopyText(FoodCopy.QUOTE_WARNING)
        assertTrue(current.english.contains("current", ignoreCase = true) &&
            current.english.contains("unpaid", ignoreCase = true))
        assertTrue(current.arabic.contains("الحالي") && current.arabic.contains("غير المدفوع"))
        assertTrue(original.english.contains("original", ignoreCase = true))
        assertTrue(original.arabic.contains("الأصلي"))
        assertTrue(historical.english.contains("histor", ignoreCase = true))
        assertTrue(historical.arabic.contains("تاريخي"))
        for (language in AppLanguage.entries) assertNotEquals(language.text(current), language.text(original))
    }

    @Test
    fun fcpv2009_changingTheVisiblePortionDoesNotReplaceTheSuppliedCompleteAmount() {
        val body = check(39_000, 50_000)
        for (portionId in listOf(id(11), id(13), id(14))) {
            val page = body.copy(portions = listOf(portion(portionId)))
            assertEquals(1, page.portions.size)
            assertEquals(Money(Currency.EGP, 39_000), page.currentUnpaidPayable)
            assertEquals(Money(Currency.EGP, 50_000), page.originalQuotedTotal)
            assertEquals(body.check.orderId, page.check.orderId)
            assertEquals(body.check.orderStorageRevision, page.check.orderStorageRevision)
        }
        // A model copy cannot prove that the Master read the complete order or a coherent cut.
    }

    private fun id(number: Int): DomainId = DomainId("c2830000-0000-4000-8000-${number.toString().padStart(12, '0')}")

    private fun check(current: Long?, original: Long, cancelled: Boolean = false): LocalFoodUiBody.Check {
        val summary = LocalFoodUiCheck(id(1), id(2), Revision(3), "RESTAURANT",
            LocalFoodUiService("DINE_IN", "Synthetic current-payable check", ""),
            if (original == 50_000L) 4 else 2, null, null)
        return LocalFoodUiBody.Check(summary, listOf(portion(id(11), cancelled)), Money(Currency.EGP, original), false,
            currentUnpaidPayable = current?.let { Money(Currency.EGP, it) })
    }

    private fun portion(portionId: DomainId, cancelled: Boolean = false): LocalFoodUiPortion =
        LocalFoodUiPortion(portionId, "Synthetic frozen serving", Quantity(id(20), 1, 0), "", Revision(0),
            if (cancelled) LocalFoodPreparation.CANCELLED_BEFORE_START else LocalFoodPreparation.NOT_SENT,
            LocalFoodFulfillment.NOT_HANDED_OVER, false, cancelled)

    private fun decimalDigits(formatted: String): String = buildString {
        for (character in formatted) when (character) {
            in '0'..'9', '.' -> append(character)
            in '٠'..'٩' -> append('0' + (character - '٠'))
            '٫' -> append('.')
        }
    }
}
