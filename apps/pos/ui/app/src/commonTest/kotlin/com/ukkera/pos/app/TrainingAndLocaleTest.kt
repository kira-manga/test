package com.ukkera.pos.app

import androidx.compose.ui.unit.LayoutDirection
import com.ukkera.pos.app.training.TrainingWorkspace
import com.ukkera.pos.app.ui.AppLanguage
import com.ukkera.pos.app.ui.ArabicCardinal
import com.ukkera.pos.app.ui.arabicCardinal
import com.ukkera.pos.app.ui.parseTrainingCash
import com.ukkera.pos.types.Currency
import com.ukkera.pos.types.Money
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingAndLocaleTest {
    @Test fun sampleBasketTotalsAreExactBoundedAndDoNotChangeSampleStock() {
        val training = TrainingWorkspace()
        val milk = TrainingWorkspace.products[0]
        val rice = TrainingWorkspace.products[1]
        assertTrue(training.add(milk.id))
        assertTrue(training.add(milk.id))
        assertTrue(training.add(rice.id))
        assertEquals(16300L, training.basket.value.total.minor)
        assertEquals(3, training.basket.value.count)
        repeat(100) { training.add(milk.id) }
        assertEquals(milk.available, training.basket.value.lines.first().quantity)
        assertFalse(training.add(milk.id))
        assertEquals(24, milk.available)
        training.removeOne(rice.id)
        assertEquals(1, training.basket.value.lines.size)
        training.close()
        assertEquals(0, training.basket.value.count)
        assertFalse(training.add(milk.id))
    }

    @Test fun arabicEnglishSearchPreservesLeadingZeroBarcodes() {
        val training = TrainingWorkspace()
        assertEquals(listOf(TrainingWorkspace.products.first()), training.search("حليب", null))
        assertEquals(listOf(TrainingWorkspace.products.first()), training.search("MILK", null))
        assertEquals("000000000001", TrainingWorkspace.normalizeSearch("٠٠٠٠٠٠٠٠٠٠٠١"))
        assertEquals(listOf(TrainingWorkspace.products.first()), training.search("٠٠٠٠٠٠٠٠٠٠٠١", null))
        assertEquals("001", TrainingWorkspace.normalizeSearch("۰۰۱"))
        assertTrue(training.search("no-such-item", null).isEmpty())
    }

    @Test fun isolatedTrainingInstancesDoNotShareBaskets() {
        val a = TrainingWorkspace()
        val b = TrainingWorkspace()
        a.add(TrainingWorkspace.products.first().id)
        assertEquals(0, b.basket.value.count)
        a.close()
        assertFalse(b.closed)
    }

    @Test fun exactDisplayAndDirectionDoNotUseFloatingPointOrSwapAmounts() {
        assertEquals(LayoutDirection.Rtl, AppLanguage.ARABIC.direction)
        assertEquals(LayoutDirection.Ltr, AppLanguage.ENGLISH.direction)
        assertEquals("\u2066120.50\u2069 EGP", AppLanguage.ENGLISH.money(Money(Currency.EGP, 12050)))
        assertEquals("\u2066١٢٠٫٥٠\u2069 ج.م", AppLanguage.ARABIC.money(Money(Currency.EGP, 12050)))
        assertEquals("\u2066-0.01\u2069 EGP", AppLanguage.ENGLISH.money(Money(Currency.EGP, -1)))
        assertEquals("\u2066-92233720368547758.08\u2069 EGP", AppLanguage.ENGLISH.money(Money(Currency.EGP, Long.MIN_VALUE)))
    }

    @Test fun decimalInputAcceptsArabicNumeralsAndRejectsAmbiguityOverflowAndNegatives() {
        assertEquals(123450L, parseTrainingCash("١٢٣٤٫٥"))
        assertEquals(50L, parseTrainingCash("0.50"))
        assertEquals(0L, parseTrainingCash("٠"))
        for (value in listOf("-1", "1e3", "1,234", "01", "10.009", ".50", "99999999999999999", "NaN", "")) assertNull(parseTrainingCash(value), value)
    }

    @Test fun largerTextUsesCompactLayoutRatherThanSqueezingTheSidebarAndCart() {
        assertTrue(usesSidebar(1280f, 1f))
        assertFalse(usesSidebar(1280f, 2f))
        assertFalse(usesSidebar(430f, 1f))
        assertFalse(usesSidebar(800f, 1f))
    }

    @Test fun arabicCardinalsFollowTheSelectedAppLanguageNotTheOsLocale() {
        assertEquals(ArabicCardinal.ZERO, arabicCardinal(0))
        assertEquals(ArabicCardinal.ONE, arabicCardinal(1))
        assertEquals(ArabicCardinal.TWO, arabicCardinal(2))
        assertEquals(ArabicCardinal.FEW, arabicCardinal(3))
        assertEquals(ArabicCardinal.FEW, arabicCardinal(110))
        assertEquals(ArabicCardinal.MANY, arabicCardinal(11))
        assertEquals(ArabicCardinal.MANY, arabicCardinal(199))
        assertEquals(ArabicCardinal.OTHER, arabicCardinal(100))
        assertEquals(ArabicCardinal.OTHER, arabicCardinal(102))
    }
}
