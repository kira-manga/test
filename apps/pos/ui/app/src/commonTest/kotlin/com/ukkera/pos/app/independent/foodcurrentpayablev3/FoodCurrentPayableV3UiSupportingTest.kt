package com.ukkera.pos.app.independent.foodcurrentpayablev3

import com.ukkera.pos.app.LocalFoodFulfillment
import com.ukkera.pos.app.LocalFoodPreparation
import com.ukkera.pos.app.LocalFoodUiBody
import com.ukkera.pos.app.LocalFoodUiCheck
import com.ukkera.pos.app.LocalFoodUiCurrentUnpaidPayableProfile
import com.ukkera.pos.app.LocalFoodUiFinancial
import com.ukkera.pos.app.LocalFoodUiKitchenObservation
import com.ukkera.pos.app.LocalFoodUiKitchenStatus
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frozen FOOD-CURRENT-V3-CASES-1, primarily FCP3-010 with FCP3-001/003/005/008.
 * Controlled shared-model/copy/formatter bindings only: supplied MIX values do not
 * prove a pricing source, protected read, version selection, screen render or native
 * custody. Required Master/RPC/lifecycle and installed presentation remain separate.
 */
class FoodCurrentPayableV3UiSupportingTest {
    @Test
    fun fcp3010_postingResolutionKeepsExactCurrentAndHistoricalAmountsAcrossPagesAndLanguages() {
        // FCP3-POSTING-1.0.0 supplies these revised values; the original controlled
        // fixture assertions below remain intact. Neither variant proves a native read.
        val members = mixedPortions()
        for (page in listOf(members.take(2), members.subList(2, 5), members.takeLast(1))) {
            val body = mixPage(page, current = 19_402).copy(originalQuotedTotal = money(30_802))
            assertEquals(money(19_402), body.currentUnpaidPayable)
            assertEquals(money(30_802), body.originalQuotedTotal)
            assertNotEquals(money(15_966), body.currentUnpaidPayable)
            assertEquals(page.map { it.portionId }, body.portions.map { it.portionId })
            for (language in AppLanguage.entries) {
                assertEquals("194.02", decimalDigits(language.foodMoney(assertNotNull(body.currentUnpaidPayable))))
                assertEquals("308.02", decimalDigits(language.foodMoney(body.originalQuotedTotal)))
            }
        }
    }

    @Test
    fun fcp3010_mixedPagesPreserveTheSuppliedWholeAmountAndHistoricalQuote() {
        val members = mixedPortions()
        val pages = listOf(members.take(2), members.subList(2, 5), members.takeLast(1))
        for (page in pages) {
            val body = mixPage(page)
            assertEquals(money(19_403), body.currentUnpaidPayable, "FCP3-001/010: whole amount, not A/B subtotal")
            assertEquals(money(30_803), body.originalQuotedTotal)
            assertNotEquals(money(15_966), body.currentUnpaidPayable)
            assertEquals(orderId, body.check.orderId)
            assertEquals(Revision(41), body.check.orderStorageRevision)
            assertEquals(page.map { it.portionId }, body.portions.map { it.portionId })
        }
    }

    @Test
    fun fcp3010_bothLanguagesKeepExactCurrentHistoricalAndZeroMeanings() {
        val body = mixPage(mixedPortions())
        val empty = emptyCheck()
        for (language in AppLanguage.entries) {
            val current = language.foodMoney(assertNotNull(body.currentUnpaidPayable))
            val original = language.foodMoney(body.originalQuotedTotal)
            val zero = language.foodMoney(assertNotNull(empty.currentUnpaidPayable))
            assertEquals("194.03", decimalDigits(current))
            assertEquals("308.03", decimalDigits(original))
            assertEquals("0.00", decimalDigits(zero))
            assertTrue(current.contains("EGP") || current.contains("ج.م"))
            assertNotEquals(current, original)
            assertNotEquals(language.text(foodCopyText(FoodCopy.CURRENT_UNPAID)),
                language.text(foodCopyText(FoodCopy.QUOTE)))
            if (language == AppLanguage.ARABIC) assertTrue(current.any { it in '٠'..'٩' })
        }
        val currentCopy = foodCopyText(FoodCopy.CURRENT_UNPAID)
        val historyCopy = foodCopyText(FoodCopy.QUOTE_WARNING)
        assertTrue(currentCopy.english.contains("current", true) && currentCopy.english.contains("unpaid", true))
        assertTrue(currentCopy.arabic.contains("الحالي") && currentCopy.arabic.contains("غير المدفوع"))
        assertTrue(historyCopy.english.contains("histor", true) && historyCopy.arabic.contains("تاريخي"))
    }

    @Test
    fun fcp3010_nullRemainsUnknownWithoutReplacingTheHistoricalQuote() {
        val unknown = mixPage(mixedPortions(), current = null)
        assertNull(unknown.currentUnpaidPayable)
        assertEquals(money(30_803), unknown.originalQuotedTotal)
        assertEquals(money(0), emptyCheck().currentUnpaidPayable)
        val unavailable = foodCopyText(FoodCopy.CURRENT_UNPAID_UNKNOWN)
        assertTrue(unavailable.english.contains("unavailable", true) || unavailable.english.contains("unknown", true))
        assertTrue(unavailable.arabic.contains("غير متاح") || unavailable.arabic.contains("غير معروف"))
        for (language in AppLanguage.entries) {
            val text = language.text(unavailable)
            assertFalse(text.any { it in '0'..'9' || it in '٠'..'٩' })
            assertNotEquals(language.foodMoney(money(0)), text)
            assertNotEquals(language.foodMoney(unknown.originalQuotedTotal), text)
        }
        // This is the production model and localized copy, not an executed screen branch.
    }

    @Test
    fun fcp3008_legacyDefaultRejectsPreparedKnownMoneyButKeepsPreSendAndNull() {
        val mixed = mixedPortions()
        val preSend = mixed.map { portion ->
            if (portion.cancellationRequested) portion else portion.copy(
                preparation = LocalFoodPreparation.NOT_SENT,
                fulfillment = LocalFoodFulfillment.NOT_HANDED_OVER, started = false)
        }
        val legacy = LocalFoodUiBody.Check(summary(), preSend, money(30_803), false,
            currentUnpaidPayable = money(19_403))
        assertEquals(LocalFoodUiCurrentUnpaidPayableProfile.NEVER_SENT_ONLY, legacy.currentUnpaidPayableProfile)
        assertEquals(money(19_403), legacy.currentUnpaidPayable)
        for (prepared in mixed.subList(1, 5)) {
            assertFailsWith<IllegalArgumentException>("FCP3-008/010: prepared work cannot widen legacy validation") {
                legacy.copy(portions = listOf(prepared))
            }
            assertEquals(money(19_403), mixPage(listOf(prepared)).currentUnpaidPayable)
        }
        val unknownLegacy = LocalFoodUiBody.Check(summary(), mixed, money(30_803), false,
            currentUnpaidPayable = null)
        assertNull(unknownLegacy.currentUnpaidPayable)
        // Actual decoder-selected profile and no fallback are gateway obligations.
    }

    @Test
    fun fcp3005_unavailableFinancialAndRemedyStatesCannotDisplayKnownUnpaidMoney() {
        val body = mixPage(mixedPortions())
        val collectionId = id("60000000", 1)
        assertFailsWith<IllegalArgumentException> { body.copy(check = body.check.copy(collectionId = collectionId)) }
        assertFailsWith<IllegalArgumentException> { body.copy(check = body.check.copy(workClosed = true)) }
        assertFailsWith<IllegalArgumentException> { body.copy(chargeResolutionRequired = true) }
        val financial = LocalFoodUiFinancial(collectionId, Revision(1), "PART_PAID", money(10_000), money(20_000),
            null, 0, false)
        assertFailsWith<IllegalArgumentException> { body.copy(financial = financial) }
        val ready = mixedPortions()[3]
        val unavailable = listOf(
            ready.copy(preparation = LocalFoodPreparation.CANCEL_PENDING, cancellationRequested = true),
            ready.copy(preparation = LocalFoodPreparation.WASTE_AFTER_START, cancellationRequested = true),
            ready.copy(fulfillment = LocalFoodFulfillment.PICKED_UP),
        )
        for (portion in unavailable) {
            assertFailsWith<IllegalArgumentException> { body.copy(portions = listOf(portion)) }
            assertNull(body.copy(portions = listOf(portion), currentUnpaidPayable = null).currentUnpaidPayable)
        }
        val part300 = body.copy(check = body.check.copy(orderId = id("20000000", 6), portionCount = 1, collectionId = collectionId),
            portions = listOf(ready.copy(fulfillment = LocalFoodFulfillment.SERVED)),
            originalQuotedTotal = money(30_000), currentUnpaidPayable = null, financial = financial)
        assertNull(part300.currentUnpaidPayable, "FCP3-005 U02: remaining collection money is not this projection")
        assertEquals(money(20_000), assertNotNull(part300.financial).remaining)
    }

    @Test
    fun fcp3010_servedKnownMoneyNeedsOrdinaryReadyDineInPresentation() {
        val served = mixedPortions()[4]
        assertEquals(money(19_403), mixPage(listOf(served)).currentUnpaidPayable)
        for (channel in listOf("COUNTER", "TAKEAWAY")) {
            assertFailsWith<IllegalArgumentException> {
                mixPage(listOf(served)).copy(check = summary().copy(service = summary().service.copy(channel = channel)))
            }
        }
        for (preparation in listOf(LocalFoodPreparation.QUEUED, LocalFoodPreparation.PREPARING)) {
            assertFailsWith<IllegalArgumentException> { mixPage(listOf(served.copy(preparation = preparation))) }
        }
    }

    @Test
    fun fcp3010_wrongOrderOrRevisionObservationCannotAccompanyKnownMoney() {
        val body = mixPage(mixedPortions().take(2))
        val matching = LocalFoodUiKitchenObservation(orderId, Revision(41), 1, LocalFoodUiKitchenStatus.UNKNOWN)
        assertEquals(money(19_403), body.copy(kitchen = matching).currentUnpaidPayable)
        assertFailsWith<IllegalArgumentException> { body.copy(kitchen = matching.copy(orderId = id("20000000", 2))) }
        assertFailsWith<IllegalArgumentException> { body.copy(kitchen = matching.copy(orderStorageRevision = Revision(42))) }
        // A matching scalar does not establish a genuine current Master transaction.
    }

    private val orderId: DomainId = id("20000000", 1)
    private fun id(prefix: String, number: Int): DomainId =
        DomainId("$prefix-0000-4000-8000-${number.toString().padStart(12, '0')}")
    private fun money(minor: Long): Money = Money(Currency.EGP, minor)
    private fun summary(): LocalFoodUiCheck = LocalFoodUiCheck(orderId, id("10000000", 4), Revision(41), "RESTAURANT",
        LocalFoodUiService("DINE_IN", "Synthetic FCP3 MIX", ""), 6, null, null)
    private fun mixPage(portions: List<LocalFoodUiPortion>, current: Long? = 19_403): LocalFoodUiBody.Check =
        LocalFoodUiBody.Check(summary(), portions, money(30_803), false,
            currentUnpaidPayable = current?.let(::money),
            currentUnpaidPayableProfile = LocalFoodUiCurrentUnpaidPayableProfile.ORDINARY_UNPAID_V3)

    private fun emptyCheck(): LocalFoodUiBody.Check = LocalFoodUiBody.Check(
        summary().copy(orderId = id("20000000", 3), portionCount = 0), emptyList(), money(0), false,
        currentUnpaidPayable = money(0), currentUnpaidPayableProfile = LocalFoodUiCurrentUnpaidPayableProfile.ORDINARY_UNPAID_V3)

    private fun mixedPortions(): List<LocalFoodUiPortion> {
        val states = listOf(LocalFoodPreparation.NOT_SENT, LocalFoodPreparation.QUEUED,
            LocalFoodPreparation.PREPARING, LocalFoodPreparation.READY, LocalFoodPreparation.READY,
            LocalFoodPreparation.CANCELLED_BEFORE_START)
        return states.mapIndexed { index, state ->
            LocalFoodUiPortion(id("40000000", index + 1), "Synthetic FCP3 ${'A' + index}",
                Quantity(id("70000000", 1), 1, 0), "", Revision(index.toLong()), state,
                if (index == 4) LocalFoodFulfillment.SERVED else LocalFoodFulfillment.NOT_HANDED_OVER,
                index in 2..4, index == 5)
        }
    }

    private fun decimalDigits(formatted: String): String = buildString {
        for (character in formatted) when (character) {
            in '0'..'9', '.' -> append(character)
            in '٠'..'٩' -> append('0' + (character - '٠'))
            '٫' -> append('.')
        }
    }
}
